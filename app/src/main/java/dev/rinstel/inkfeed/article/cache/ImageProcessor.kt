package dev.rinstel.inkfeed.article.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import dev.rinstel.inkfeed.core.database.InkFeedDatabase
import dev.rinstel.inkfeed.core.model.ImagePolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

class ImageProcessor(
    private val context: Context,
    private val database: InkFeedDatabase,
    private val client: OkHttpClient
) {
    fun process(articleId: Long, html: String, baseUrl: String, policy: ImagePolicy): String {
        val document = Jsoup.parseBodyFragment(html, baseUrl)
        document.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
        val images = document.select("img[src]")
        val selected = when (policy) {
            ImagePolicy.NONE -> emptyList()
            ImagePolicy.FIRST -> images.take(1)
            ImagePolicy.ESSENTIAL -> images.take(3)
            ImagePolicy.ALL -> images.take(10)
        }
        images.filterNot { it in selected }.forEach { it.remove() }
        selected.forEachIndexed { index, element ->
            val originalUrl = element.absUrl("src")
            if (originalUrl.isBlank()) {
                element.remove()
                return@forEachIndexed
            }
            runCatching {
                val bytes = download(originalUrl)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("无法读取图片尺寸")
                val options = BitmapFactory.Options().apply {
                    inSampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight)
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    ?: error("无法解码图片")
                val scaled = scale(source, 1200)
                val grayscale = grayscale(scaled)
                val dir = File(context.filesDir, "article-assets/$articleId").apply { mkdirs() }
                val file = File(dir, "image-${index + 1}.jpg")
                FileOutputStream(file).use {
                    grayscale.compress(Bitmap.CompressFormat.JPEG, 78, it)
                }
                val epubPath = "images/article-$articleId-${index + 1}.jpg"
                database.addAsset(
                    articleId, originalUrl, file.absolutePath, epubPath,
                    grayscale.width, grayscale.height
                )
                element.attr("src", epubPath)
                element.removeAttr("srcset")
                if (source !== scaled) source.recycle()
                if (scaled !== grayscale) scaled.recycle()
                grayscale.recycle()
            }.onFailure {
                element.remove()
            }
        }
        return document.body().html()
    }

    private fun download(url: String): ByteArray {
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", "InkFeed/0.1")
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) error("图片 HTTP ${response.code}")
            val body = response.body ?: error("图片响应为空")
            if (body.contentLength() > MAX_IMAGE_BYTES) error("图片超过大小限制")
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_IMAGE_BYTES) error("图片超过大小限制")
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        }
    }

    private fun scale(source: Bitmap, maxWidth: Int): Bitmap {
        if (source.width <= maxWidth) return source
        val height = (source.height * (maxWidth.toFloat() / source.width)).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, maxWidth, height, true)
    }

    private fun grayscale(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(output).drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 12L * 1024L * 1024L
    }
}

internal fun imageSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (width / sample > 2400 || height / sample > 3200) {
        sample *= 2
    }
    return sample
}

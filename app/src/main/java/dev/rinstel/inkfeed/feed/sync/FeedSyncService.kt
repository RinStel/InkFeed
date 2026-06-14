package dev.rinstel.inkfeed.feed.sync

import android.content.Context
import android.util.Log
import dev.rinstel.inkfeed.article.cache.ImageProcessor
import dev.rinstel.inkfeed.article.extractor.ArticleExtractor
import dev.rinstel.inkfeed.core.database.InkFeedDatabase
import dev.rinstel.inkfeed.core.model.Source
import dev.rinstel.inkfeed.core.util.AppSettings
import dev.rinstel.inkfeed.feed.parser.FeedParser
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

data class SourceSyncResult(
    val added: Int,
    val duplicate: Int,
    val failed: Int
)

data class SyncResult(
    val sourceCount: Int,
    val newArticleCount: Int,
    val duplicateCount: Int,
    val failedArticleCount: Int,
    val errors: List<String>
)

class FeedSyncService(
    context: Context,
    private val database: InkFeedDatabase,
    private val settings: AppSettings,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val imageProcessor = ImageProcessor(context, database, client)

    fun syncAll(): SyncResult {
        val sources = database.sources(enabledOnly = true)
        var added = 0
        var duplicate = 0
        var failed = 0
        val errors = mutableListOf<String>()
        sources.forEach { source ->
            runCatching { sync(source) }
                .onSuccess {
                    added += it.added
                    duplicate += it.duplicate
                    failed += it.failed
                }
                .onFailure { errors += "${source.title}: ${it.message ?: "同步失败"}" }
        }
        return SyncResult(sources.size, added, duplicate, failed, errors)
    }

    fun sync(source: Source): SourceSyncResult {
        return try {
            val feedResponse = execute(source.feedUrl)
            val parsed = feedResponse.byteStream().use(FeedParser::parse)
            database.updateSourceMetadata(source.id, parsed.title, parsed.siteUrl)
            var added = 0
            var duplicate = 0
            var failed = 0
            parsed.items.asSequence()
                .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
                .take(10)
                .forEach { item ->
                    val existingId = database.touchExistingArticle(source.id, item)
                    val articleId = if (existingId != null) {
                        duplicate++
                        if (!database.articleNeedsContent(existingId)) {
                            Log.d("InkFeed", "sync: duplicate complete source=${source.title} title=${item.title}")
                            return@forEach
                        }
                        Log.d("InkFeed", "sync: retrying incomplete articleId=$existingId title=${item.title}")
                        existingId
                    } else {
                        database.insertArticle(
                            source.id,
                            item,
                            item.summary.orEmpty(),
                            ""
                        ).also { insertedId ->
                            if (insertedId != -1L) {
                                Log.d("InkFeed", "sync: inserted source=${source.title} articleId=$insertedId title=${item.title}")
                                added++
                            }
                        }
                    }
                    if (articleId == -1L) {
                        Log.d("InkFeed", "sync: duplicate after insert source=${source.title} title=${item.title}")
                        duplicate++
                        return@forEach
                    }
                    runCatching {
                        val page = execute(item.url).string()
                        val extracted = ArticleExtractor.extract(page, item.url)
                        if (settings.downloadImages) {
                            val processedHtml = imageProcessor.process(
                                articleId, extracted.html, item.url, settings.imagePolicy
                            )
                            database.updateArticleContent(
                                articleId,
                                Jsoup.parse(processedHtml).text(),
                                processedHtml
                            )
                        } else {
                            database.updateArticleContent(
                                articleId,
                                extracted.text,
                                extracted.html
                            )
                        }
                    }.onFailure {
                        Log.d("InkFeed", "sync: content fetch failed articleId=$articleId title=${item.title} error=${it.message}")
                        failed++
                    }
                }
            val result = SourceSyncResult(added, duplicate, failed)
            Log.d("InkFeed", "sync: source=${source.title} added=$added duplicate=$duplicate failed=$failed")
            database.recordSync(
                source.id,
                "新增 ${result.added}，重复 ${result.duplicate}，失败 ${result.failed}"
            )
            result
        } catch (error: Exception) {
            database.recordSync(source.id, "失败：${error.message ?: "未知错误"}")
            throw error
        }
    }

    fun cancel() {
        client.dispatcher.cancelAll()
    }

    private fun execute(url: String) = client.newCall(
        Request.Builder()
            .url(url)
            .header("User-Agent", "InkFeed/0.1 (+Android offline feed reader)")
            .build()
    ).execute().also {
        if (!it.isSuccessful) {
            val code = it.code
            it.close()
            error("HTTP $code")
        }
    }.body ?: error("响应内容为空")
}

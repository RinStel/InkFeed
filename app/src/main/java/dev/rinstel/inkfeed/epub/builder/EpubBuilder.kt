package dev.rinstel.inkfeed.epub.builder

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.rinstel.inkfeed.core.database.InkFeedDatabase
import dev.rinstel.inkfeed.core.model.Article
import dev.rinstel.inkfeed.core.model.ArticleAsset
import dev.rinstel.inkfeed.core.util.AppSettings
import dev.rinstel.inkfeed.core.util.BeijingTime
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class BuildResult(
    val displayPath: String,
    val articleCount: Int,
    val packageCount: Int = 1
)

class EpubBuilder(
    private val context: Context,
    private val settings: AppSettings,
    private val database: InkFeedDatabase
) {
    fun buildDaily(articles: List<Article>): BuildResult {
        val date = BeijingTime.date()
        val main = build("InkFeed $date", "daily", "$date.epub", articles)
        if (!settings.splitEpubBySource) return main
        var packageCount = 1
        articles.groupBy { it.sourceId }.values.forEach { sourceArticles ->
            val source = sourceArticles.first().sourceTitle
            val filename = "$date-${safeFilename(source)}.epub"
            build(
                "InkFeed $source $date",
                "daily/sources",
                filename,
                sourceArticles,
                debugGroup = "daily/$date/${safeFilename(source)}"
            )
            packageCount++
        }
        return main.copy(packageCount = packageCount)
    }

    fun buildStarred(articles: List<Article>): BuildResult =
        build("InkFeed 收藏", "starred", "starred.epub", articles)

    private fun build(
        title: String,
        directory: String,
        filename: String,
        articles: List<Article>,
        debugGroup: String = directory
    ): BuildResult {
        require(articles.isNotEmpty()) { "没有可生成的文章" }
        val assets = database.assets(articles.map(Article::id))
        val treeUri = settings.outputTreeUri
        val path = if (treeUri != null) {
            writeToTree(treeUri, directory, filename) { output ->
                EpubArchive.write(output, title, articles, assets)
            }
            "$directory/$filename"
        } else {
            val dir = File(context.getExternalFilesDir(null), "InkFeed/$directory").apply { mkdirs() }
            val target = File(dir, filename)
            val temporary = File.createTempFile("inkfeed-", ".epub", dir)
            try {
                temporary.outputStream().use { EpubArchive.write(it, title, articles, assets) }
                try {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            } finally {
                temporary.delete()
            }
            target.absolutePath
        }
        if (settings.htmlDebug) writeDebugHtml(debugGroup, articles, assets)
        return BuildResult(path, articles.size)
    }

    private fun writeToTree(
        treeUri: Uri,
        directory: String,
        filename: String,
        writer: (OutputStream) -> Unit
    ) {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val root = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val dir = directory.split('/')
            .filter(String::isNotBlank)
            .fold(root) { parent, segment ->
                findChild(parent, segment)
                    ?: DocumentsContract.createDocument(
                        resolver,
                        parent,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        segment
                    )
                    ?: error("无法创建输出目录：$segment")
            }
        val existing = findChild(dir, filename)
        val token = UUID.randomUUID().toString()
        val temporaryName = ".$filename.$token.tmp"
        val temporary = DocumentsContract.createDocument(
            resolver,
            dir,
            "application/epub+zip",
            temporaryName
        )
            ?: error("无法创建 EPUB")
        try {
            resolver.openOutputStream(temporary, "w")?.use(writer) ?: error("无法写入 EPUB")
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, temporary) }
            throw error
        }

        if (existing == null) {
            val renamed = DocumentsContract.renameDocument(resolver, temporary, filename)
            if (renamed == null) {
                runCatching { DocumentsContract.deleteDocument(resolver, temporary) }
                error("输出目录不支持安全重命名 EPUB")
            }
            return
        }

        val backupName = ".$filename.$token.backup"
        val backup = DocumentsContract.renameDocument(resolver, existing, backupName)
        if (backup == null) {
            runCatching { DocumentsContract.deleteDocument(resolver, temporary) }
            error("输出目录不支持安全替换 EPUB")
        }
        val renamed = DocumentsContract.renameDocument(resolver, temporary, filename)
        if (renamed == null) {
            runCatching { DocumentsContract.renameDocument(resolver, backup, filename) }
            runCatching { DocumentsContract.deleteDocument(resolver, temporary) }
            error("替换 EPUB 失败，已尝试恢复旧文件")
        }
        runCatching { DocumentsContract.deleteDocument(resolver, backup) }
    }

    private fun findChild(parent: Uri, name: String): Uri? {
        val resolver = context.contentResolver
        val parentId = DocumentsContract.getDocumentId(parent)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, parentId)
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(
                        parent, cursor.getString(0)
                    )
                }
            }
        }
        return null
    }

    private fun writeDebugHtml(
        group: String,
        articles: List<Article>,
        assets: List<ArticleAsset>
    ) {
        val dir = File(context.getExternalFilesDir(null), "InkFeed/html-debug/$group").apply { mkdirs() }
        articles.forEachIndexed { index, article ->
            File(dir, "article-${index + 1}.html").writeText(EpubArchive.chapter(article))
        }
        assets.forEach { asset ->
            val source = File(asset.localPath)
            if (source.exists()) {
                val destination = File(dir, asset.epubPath)
                destination.parentFile?.mkdirs()
                source.copyTo(destination, overwrite = true)
            }
        }
    }

    private fun safeFilename(value: String): String =
        value.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(60).ifBlank { "source" }
}

internal object EpubArchive {
    fun create(title: String, articles: List<Article>, assets: List<ArticleAsset>): ByteArray {
        val output = ByteArrayOutputStream()
        write(output, title, articles, assets)
        return output.toByteArray()
    }

    fun write(
        output: OutputStream,
        title: String,
        articles: List<Article>,
        assets: List<ArticleAsset>
    ) {
        ZipOutputStream(output).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            val crc = CRC32().apply { update(mimetype) }
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                compressedSize = mimetype.size.toLong()
                this.crc = crc.value
            })
            zip.write(mimetype)
            zip.closeEntry()
            zip.text("META-INF/container.xml", CONTAINER)
            zip.text("OEBPS/style.css", STYLE)
            zip.text("OEBPS/nav.xhtml", nav(title, articles))
            articles.forEachIndexed { index, article ->
                zip.text("OEBPS/article-${index + 1}.xhtml", chapter(article))
            }
            assets.forEach { asset ->
                val file = File(asset.localPath)
                if (file.exists()) {
                    zip.putNextEntry(ZipEntry("OEBPS/${asset.epubPath}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            zip.text("OEBPS/package.opf", packageFile(title, articles, assets))
        }
    }

    fun chapter(article: Article): String {
        val date = article.publishedAt?.let(BeijingTime::dateTime) ?: "时间未知"
        val body = article.contentHtml?.takeIf { it.isNotBlank() }
            ?: "<p>${xml(article.contentText ?: article.summary.orEmpty())}</p>"
        return """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head><title>${xml(article.title)}</title><link rel="stylesheet" href="style.css"/></head>
            <body>
              <h1>${xml(article.title)}</h1>
              <p class="meta">${xml(article.sourceTitle)} · ${xml(date)}</p>
              <p class="meta"><a href="${xml(article.url)}">原文链接</a></p>
              <div class="content">$body</div>
            </body></html>""".trimIndent()
    }

    private fun nav(title: String, articles: List<Article>) =
        """<?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
        <head><title>${xml(title)}</title><link rel="stylesheet" href="style.css"/></head>
        <body><h1>${xml(title)}</h1><nav epub:type="toc" id="toc"><ol>
        ${articles.mapIndexed { i, a -> "<li><a href=\"article-${i + 1}.xhtml\">${xml(a.title)}</a></li>" }.joinToString("\n")}
        </ol></nav></body></html>""".trimIndent()

    private fun packageFile(
        title: String,
        articles: List<Article>,
        assets: List<ArticleAsset>
    ): String {
        val manifest = buildString {
            append("""<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
            append("""<item id="css" href="style.css" media-type="text/css"/>""")
            articles.indices.forEach {
                append("""<item id="a${it + 1}" href="article-${it + 1}.xhtml" media-type="application/xhtml+xml"/>""")
            }
            assets.filter { File(it.localPath).exists() }.forEach {
                append("""<item id="img${it.id}" href="${xml(it.epubPath)}" media-type="${xml(it.mimeType)}"/>""")
            }
        }
        val spine = articles.indices.joinToString("") { """<itemref idref="a${it + 1}"/>""" }
        return """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="book-id">urn:uuid:${UUID.randomUUID()}</dc:identifier>
                <dc:title>${xml(title)}</dc:title>
                <dc:language>zh-CN</dc:language>
                <dc:creator>InkFeed</dc:creator>
                <meta property="dcterms:modified">${BeijingTime.isoInstant(System.currentTimeMillis())}</meta>
              </metadata>
              <manifest>$manifest</manifest>
              <spine>$spine</spine>
            </package>""".trimIndent()
    }

    private fun ZipOutputStream.text(path: String, value: String) {
        putNextEntry(ZipEntry(path))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private const val CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

    private const val STYLE = """
body { font-family: serif; line-height: 1.6; margin: 1.2em; color: #000; background: #fff; }
h1 { font-size: 1.4em; }
.meta { font-size: 0.85em; color: #555; }
img { max-width: 100%; height: auto; }
pre, code { white-space: pre-wrap; font-family: monospace; }
blockquote { border-left: 0.2em solid #777; margin-left: 0; padding-left: 1em; }
"""
}

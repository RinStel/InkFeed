package dev.rinstel.inkfeed.epub.builder

import dev.rinstel.inkfeed.core.model.Article
import dev.rinstel.inkfeed.core.model.ArticleAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class EpubArchiveTest {
    @Test
    fun createsMinimalValidEpubStructure() {
        val article = Article(
            id = 1L,
            sourceId = 1L,
            sourceTitle = "Source",
            title = "Article Title",
            url = "https://example.com/a",
            guid = "guid-1",
            author = "Author",
            publishedAt = 1_718_325_600_000L,
            summary = "Summary",
            contentText = "Hello world",
            contentHtml = "<p>Hello world</p>",
            readingMinutes = 1,
            isRead = false,
            isStarred = false,
            cachedAt = 1_718_325_600_000L,
            syncedAt = 1_718_325_600_000L,
            addedToDailyPackage = false
        )

        val bytes = EpubArchive.create("InkFeed Test", listOf(article), emptyList<ArticleAsset>())

        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }

        assertEquals("application/epub+zip", entries["mimetype"])
        assertTrue(entries.containsKey("META-INF/container.xml"))
        assertTrue(entries.containsKey("OEBPS/package.opf"))
        assertTrue(entries.containsKey("OEBPS/nav.xhtml"))
        assertTrue(entries.containsKey("OEBPS/article-1.xhtml"))
        assertTrue(entries["OEBPS/package.opf"]!!.contains("InkFeed Test"))
        assertTrue(entries["OEBPS/article-1.xhtml"]!!.contains("Article Title"))
    }
}

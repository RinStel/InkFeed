package dev.rinstel.inkfeed.article.extractor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleExtractorTest {
    @Test
    fun removesNoiseAndKeepsReadableContent() {
        val html = """
            <html><body>
              <nav>Navigation</nav>
              <article>
                <h1>Title</h1>
                <p>First useful paragraph.</p>
                <p>Second useful paragraph with <a href="/more">link</a>.</p>
                <script>alert('bad')</script>
              </article>
              <footer>Footer</footer>
            </body></html>
        """.trimIndent()

        val result = ArticleExtractor.extract(html, "https://example.com/post")

        assertTrue(result.text.contains("First useful paragraph"))
        assertTrue(result.html.contains("https://example.com/more"))
        assertFalse(result.html.contains("Navigation"))
        assertFalse(result.html.contains("<script"))
    }
}

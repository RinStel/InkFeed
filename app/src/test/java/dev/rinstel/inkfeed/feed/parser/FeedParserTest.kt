package dev.rinstel.inkfeed.feed.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FeedParserTest {
    @Test
    fun parsesRssChannelAndItem() {
        val xml = """
            <rss version="2.0">
              <channel>
                <title>Example RSS</title>
                <link>https://example.com/</link>
                <item>
                  <title><![CDATA[Hello <b>world</b>]]></title>
                  <link>https://example.com/posts/1</link>
                  <guid>post-1</guid>
                  <author>Alice</author>
                  <pubDate>Sun, 14 Jun 2026 09:00:00 +0800</pubDate>
                  <description><![CDATA[<p>Summary text</p>]]></description>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val feed = FeedParser.parse(xml.byteInputStream())

        assertEquals("Example RSS", feed.title)
        assertEquals("https://example.com/", feed.siteUrl)
        assertEquals(1, feed.items.size)
        with(feed.items.single()) {
            assertEquals("Hello world", title)
            assertEquals("https://example.com/posts/1", url)
            assertEquals("post-1", guid)
            assertEquals("Alice", author)
            assertEquals("Summary text", summary)
            assertNotNull(publishedAt)
        }
    }

    @Test
    fun parsesAtomAlternateLinksAndNestedAuthor() {
        val xml = """
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Example Atom</title>
              <link rel="self" href="https://example.com/feed.xml"/>
              <link rel="alternate" href="https://example.com/"/>
              <entry>
                <title>Atom article</title>
                <id>tag:example.com,2026:1</id>
                <link rel="alternate" href="https://example.com/article"/>
                <author><name>Bob</name><email>bob@example.com</email></author>
                <updated>2026-06-14T09:00:00+08:00</updated>
                <summary type="html">&lt;p&gt;Atom summary&lt;/p&gt;</summary>
              </entry>
            </feed>
        """.trimIndent()

        val feed = FeedParser.parse(xml.byteInputStream())

        assertEquals("Example Atom", feed.title)
        assertEquals("https://example.com/", feed.siteUrl)
        with(feed.items.single()) {
            assertEquals("Atom article", title)
            assertEquals("https://example.com/article", url)
            assertEquals("Bob", author)
            assertEquals("Atom summary", summary)
            assertNotNull(publishedAt)
        }
    }
}

package dev.rinstel.inkfeed.feed.parser

import dev.rinstel.inkfeed.core.model.FeedItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.jsoup.select.Elements
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ParsedFeed(
    val title: String?,
    val siteUrl: String?,
    val items: List<FeedItem>
)

object FeedParser {
    fun parse(input: InputStream): ParsedFeed {
        val document = Jsoup.parse(input, "UTF-8", "", Parser.xmlParser())

        val rssChannel = document.selectFirst("rss > channel")
        if (rssChannel != null) {
            return parseRssChannel(rssChannel)
        }
        val atomFeed = document.selectFirst("feed")
        if (atomFeed != null) {
            return parseAtomFeed(atomFeed)
        }
        return ParsedFeed(null, null, emptyList())
    }

    private fun parseRssChannel(channel: Element): ParsedFeed {
        val title = channel.selectFirst("channel > title")?.text()?.trim()
            ?: channel.selectFirst("> title")?.text()?.trim()
        val siteUrl = channel.selectFirst("channel > link")?.text()?.trim()
            ?: channel.selectFirst("> link")?.text()?.trim()
        val items = channel.select("item").map { element ->
            parseRssItem(element)
        }
        return ParsedFeed(title, siteUrl, items)
    }

    private fun parseAtomFeed(feed: Element): ParsedFeed {
        val title = feed.selectFirst("feed > title")?.text()?.trim()
            ?: feed.selectFirst("> title")?.text()?.trim()
        val links = feed.select("link")
        val siteUrl = links.firstOrNull { link ->
            val rel = link.attr("rel")
            rel.isBlank() || rel == "alternate"
        }?.attr("href")?.takeIf { it.isNotBlank() }
        val items = feed.select("entry").map { element ->
            parseAtomEntry(element)
        }
        return ParsedFeed(title, siteUrl, items)
    }

    private fun parseRssItem(item: Element): FeedItem {
        val title = clean(item.selectFirst("title")?.text().orEmpty()).ifBlank { "无标题" }
        val url = item.selectFirst("link")?.text()?.trim().orEmpty()
        val guid = item.selectFirst("guid")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val author = item.selectFirst("author")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: item.selectFirst("creator")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val publishedAt = selectFirstText(item, "pubDate")
            ?.let(::parseDate)
        val summary = selectFirstText(item, "description")
            ?: selectFirstText(item, "encoded")
            ?: selectFirstText(item, "content:encoded")
        return FeedItem(title, url.ifBlank { guid.orEmpty() }, guid, author, publishedAt, summary)
    }

    private fun parseAtomEntry(entry: Element): FeedItem {
        val title = clean(entry.selectFirst("title")?.text().orEmpty()).ifBlank { "无标题" }
        val links = entry.select("link")
        val url = links.firstOrNull { link ->
            val rel = link.attr("rel")
            rel.isBlank() || rel == "alternate"
        }?.attr("href")?.takeIf { it.isNotBlank() }.orEmpty()
        val guid = entry.selectFirst("id")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val author = entry.selectFirst("author > name")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
        val publishedAt = selectFirstText(entry, "updated")?.let(::parseDate)
            ?: selectFirstText(entry, "published")?.let(::parseDate)
        val summary = selectFirstText(entry, "summary")
            ?: selectFirstText(entry, "content")
        return FeedItem(title, url.ifBlank { guid.orEmpty() }, guid, author, publishedAt, summary)
    }

    private fun selectFirstText(parent: Element, cssQuery: String): String? {
        val element = parent.selectFirst(cssQuery) ?: return null
        val type = element.attr("type").lowercase()
        val text = element.text().trim()
        return if (text.isBlank()) null else clean(text).take(1000)
    }

    private fun clean(value: String): String =
        Jsoup.parse(value).text().replace(Regex("\\s+"), " ").trim()

    private fun parseDate(value: String): Long? {
        val text = value.trim()
        val patterns = arrayOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm Z",
            "EEE, dd MMM yyyy HH:mm:ss z",
            "EEE, dd MMM yyyy HH:mm z",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm'Z'",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd"
        )
        for (pattern in patterns) {
            val date = parseWithPattern(text, pattern) ?: continue
            return date.time
        }
        return null
    }

    private fun parseWithPattern(text: String, pattern: String): Date? = runCatching {
        SimpleDateFormat(pattern, Locale.US).apply {
            isLenient = true
            if (!pattern.contains("X") && !pattern.contains("Z") && !pattern.contains("z")) {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        }.parse(text)
    }.getOrNull()
}

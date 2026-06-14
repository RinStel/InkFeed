package dev.rinstel.inkfeed.feed.parser

import dev.rinstel.inkfeed.core.model.FeedItem
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ParsedFeed(
    val title: String?,
    val siteUrl: String?,
    val items: List<FeedItem>
)

object FeedParser {
    fun parse(input: InputStream): ParsedFeed {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(input, null)
        }
        var feedTitle: String? = null
        var siteUrl: String? = null
        val items = mutableListOf<FeedItem>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "channel" -> parseRssChannel(parser).also {
                        feedTitle = it.title
                        siteUrl = it.siteUrl
                        items += it.items
                    }
                    "feed" -> parseAtomFeed(parser).also {
                        feedTitle = it.title
                        siteUrl = it.siteUrl
                        items += it.items
                    }
                }
            }
            event = parser.next()
        }
        return ParsedFeed(feedTitle, siteUrl, items)
    }

    private fun parseRssChannel(parser: XmlPullParser): ParsedFeed {
        var title: String? = null
        var siteUrl: String? = null
        val items = mutableListOf<FeedItem>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "title" -> if (title == null) title = parser.nextText().trim()
                    "link" -> if (siteUrl == null) siteUrl = parser.nextText().trim()
                    "item" -> items += parseItem(parser, "item")
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name.equals("channel", true)) {
                break
            }
        }
        return ParsedFeed(title, siteUrl, items)
    }

    private fun parseAtomFeed(parser: XmlPullParser): ParsedFeed {
        var title: String? = null
        var siteUrl: String? = null
        val items = mutableListOf<FeedItem>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "title" -> if (title == null) title = parser.nextText().trim()
                    "link" -> {
                        val href = parser.getAttributeValue(null, "href")
                        val rel = parser.getAttributeValue(null, "rel")
                        if (!href.isNullOrBlank() && (rel == null || rel == "alternate")) {
                            siteUrl = href
                        }
                        skip(parser)
                    }
                    "entry" -> items += parseItem(parser, "entry")
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name.equals("feed", true)) {
                break
            }
        }
        return ParsedFeed(title, siteUrl, items)
    }

    private fun parseItem(parser: XmlPullParser, endTag: String): FeedItem {
        var title = "无标题"
        var url = ""
        var guid: String? = null
        var author: String? = null
        var publishedAt: Long? = null
        var summary: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "title" -> title = clean(parser.nextText())
                    "link" -> {
                        val href = parser.getAttributeValue(null, "href")
                        if (!href.isNullOrBlank()) {
                            val rel = parser.getAttributeValue(null, "rel")
                            if (rel == null || rel == "alternate") url = href
                            skip(parser)
                        } else {
                            url = parser.nextText().trim()
                        }
                    }
                    "guid", "id" -> guid = parser.nextText().trim()
                    "author" -> author = parseAuthor(parser)
                    "creator" -> author = clean(parser.nextText())
                    "pubdate", "published", "updated", "date" ->
                        publishedAt = parseDate(parser.nextText())
                    "description", "summary", "encoded", "content" ->
                        summary = clean(parser.nextText()).take(1000)
                    else -> skip(parser)
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name.equals(endTag, true)) {
                break
            }
        }
        if (url.isBlank()) url = guid.orEmpty()
        return FeedItem(title, url, guid, author, publishedAt, summary)
    }

    private fun clean(value: String): String =
        Jsoup.parse(value).text().replace(Regex("\\s+"), " ").trim()

    private fun parseAuthor(parser: XmlPullParser): String? {
        var name: String? = null
        var directText: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name.equals("name", true)) {
                        name = clean(parser.nextText())
                    } else {
                        skip(parser)
                    }
                }
                XmlPullParser.TEXT -> {
                    parser.text?.trim()?.takeIf { it.isNotBlank() }?.let { directText = it }
                }
                XmlPullParser.END_TAG -> if (parser.name.equals("author", true)) break
            }
        }
        return name ?: directText?.let(::clean)
    }

    private fun parseDate(value: String): Long? {
        val text = value.trim()
        val formatters = listOf(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_ZONED_DATE_TIME,
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        )
        return formatters.firstNotNullOfOrNull { formatter ->
            runCatching { ZonedDateTime.parse(text, formatter).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(text, formatter).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { Instant.from(formatter.parse(text)).toEpochMilli() }.getOrNull()
        }
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
            }
        }
    }
}

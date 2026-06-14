package dev.rinstel.inkfeed.feed.opml

import android.util.Xml
import dev.rinstel.inkfeed.core.database.InkFeedDatabase
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

object OpmlImporter {
    fun import(input: InputStream, database: InkFeedDatabase): Int {
        val parser = Xml.newPullParser().apply { setInput(input, null) }
        var count = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("outline", true)) {
                val url = parser.getAttributeValue(null, "xmlUrl")
                if (!url.isNullOrBlank()) {
                    val title = parser.getAttributeValue(null, "title")
                        ?: parser.getAttributeValue(null, "text")
                        ?: url
                    val site = parser.getAttributeValue(null, "htmlUrl")
                    if (database.addSource(title, url, site) != -1L) count++
                }
            }
            event = parser.next()
        }
        return count
    }
}

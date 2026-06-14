package dev.rinstel.inkfeed.article.extractor

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class ExtractedArticle(val text: String, val html: String)

object ArticleExtractor {
    fun extract(html: String, baseUrl: String): ExtractedArticle {
        val document = Jsoup.parse(html, baseUrl)
        document.select(
            "script,style,noscript,iframe,nav,footer,header,aside,form,button,.advertisement,.ads,.social,.comments"
        ).remove()
        val root = bestContentRoot(document)
        root.select("a[href]").forEach { it.attr("href", it.absUrl("href")) }
        root.select("img[src]").forEach { it.attr("src", it.absUrl("src")) }
        root.select("*").forEach {
            it.removeAttr("style")
            it.removeAttr("class")
            it.removeAttr("id")
            it.removeAttr("onclick")
        }
        document.outputSettings().syntax(Document.OutputSettings.Syntax.xml)
        val text = root.text().replace(Regex("\\s+"), " ").trim()
        return ExtractedArticle(text, root.html())
    }

    private fun bestContentRoot(document: Document): Element {
        document.selectFirst("article")?.let { return it }
        document.selectFirst("main")?.let { return it }
        return document.select("section,div").maxByOrNull { candidate ->
            candidate.select("p").sumOf { it.text().length } -
                candidate.select("a").sumOf { it.text().length }
        } ?: document.body()
    }
}

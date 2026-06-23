package novelsourcery.lib.novelupdatesparsers.parsers

import net.dankito.readability4j.Readability4J
import novelsourcery.lib.novelupdatesparsers.SiteParser
import novelsourcery.lib.novelupdatesparsers.combined
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class GenericFallbackParser : SiteParser {
    private val contentSelectors = listOf(
        ".chapter-content",
        ".entry-content",
        ".post-content",
        ".content",
        "#content",
        ".chapter__content",
        ".text_story",
        "article",
    )
    private val titleSelectors = listOf(".chapter-title", ".entry-title", "h1", "h2", ".title")

    override fun canHandle(doc: Document, url: HttpUrl) = true

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        var content = ""
        for (selector in contentSelectors) {
            content = doc.select(selector).html()
            if (content.isNotEmpty() && content.length > 100) break
        }

        val title = titleSelectors
            .mapNotNull { doc.select(it).first()?.text()?.takeIf { t -> t.isNotEmpty() } }
            .firstOrNull() ?: ""

        if (content.isEmpty()) {
            val article = Readability4J(url.toString(), doc.outerHtml()).parse()
            val r4jContent = article.content
            if (!r4jContent.isNullOrEmpty()) {
                return combined(article.title ?: title, r4jContent)
            }
            doc.select("nav, header, footer, .hidden").remove()
            return doc.select("body").html()
        }

        return if (title.isNotEmpty()) "<h2>$title</h2><hr><br>$content" else content
    }
}

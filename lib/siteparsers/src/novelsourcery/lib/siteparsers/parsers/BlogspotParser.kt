package novelsourcery.lib.siteparsers.parsers

import novelsourcery.lib.siteparsers.SiteParser
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class BlogspotParser : SiteParser {
    private val bloat = listOf(".button-container", ".ChapterNav", ".ch-bottom", ".separator")
    private val titleSelectors = listOf(".entry-title", ".post-title", "head title")
    private val contentSelectors = listOf(".content-post", ".entry-content", ".post-body")

    override fun canHandle(doc: Document, url: HttpUrl): Boolean = matches(doc, "meta[name=\"generator\"]", "content", Regex("blogger")) ||
        matches(doc, "meta[name=\"google-adsense-platform-domain\"]", "content", Regex("blogspot")) ||
        matches(doc, "link[rel=\"alternate\"]", "href", Regex("blogger\\.com/feeds|blogspot\\.com/feeds")) ||
        matches(doc, "link", "href", Regex("www\\.blogger\\.com/static|www\\.blogger\\.com/dyn-css")) ||
        matches(doc, "script", null, Regex("_WidgetManager\\._Init|_WidgetManager\\._RegisterWidget"))

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        bloat.forEach { doc.select(it).remove() }

        val title = titleSelectors
            .mapNotNull { doc.select(it).first()?.text()?.trim()?.takeIf { t -> t.isNotEmpty() } }
            .firstOrNull() ?: ""

        val content = contentSelectors
            .mapNotNull { sel ->
                val el = doc.select(sel).first()
                el?.html()?.takeIf { el.text().trim().length > 50 }
            }
            .firstOrNull() ?: ""

        return if (title.isNotEmpty() && content.isNotEmpty()) {
            "<h2>$title</h2><hr><br>$content"
        } else {
            content
        }
    }

    private fun matches(doc: Document, selector: String, attr: String?, regex: Regex): Boolean = doc.select(selector).any { el: Element ->
        val value = if (attr != null) el.attr(attr) else (el.html().ifEmpty { el.text() })
        regex.containsMatchIn(value.lowercase())
    }
}

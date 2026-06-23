package keiyoushi.lib.novelupdatesparsers.parsers

import keiyoushi.lib.novelupdatesparsers.SiteParser
import keiyoushi.lib.novelupdatesparsers.combined
import keiyoushi.lib.novelupdatesparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class RainOfSnowParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "rainofsnow"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val displayedDiv = doc.select(".bb-item").firstOrNull { el ->
            el.attr("style").contains("display: block") || el.attr("style").contains("display:block")
        }
        val snowDoc = Jsoup.parse(displayedDiv?.html() ?: "")
        listOf(".responsivevoice-button", ".zoomdesc-cont p img", ".zoomdesc-cont p noscript")
            .forEach { snowDoc.select(it).remove() }
        val titleElement = snowDoc.select(".scroller h2").first()
        val title = if (titleElement != null) {
            val t = titleElement.text()
            titleElement.remove()
            t
        } else {
            ""
        }
        val content = snowDoc.select(".zoomdesc-cont").html()
        return combined(title, content)
    }
}

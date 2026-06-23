package keiyoushi.lib.novelupdatesparsers.parsers

import keiyoushi.lib.novelupdatesparsers.SiteParser
import keiyoushi.lib.novelupdatesparsers.combined
import keiyoushi.lib.novelupdatesparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class NovelWorldTranslationsParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "novelworldtranslations"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        doc.select(".separator img").remove()
        doc.select(".entry-content a").filter { el ->
            el.attr("href").contains("https://novelworldtranslations.blogspot.com")
        }.forEach { it.parent()?.remove() }
        val title = doc.select(".entry-title").first()?.text() ?: ""
        val rawContent = doc.select(".entry-content").html()
            .replace("&nbsp;", "").replace("\n", "<br>")
        val contentDoc = Jsoup.parse(rawContent)
        contentDoc.select("span, p, div").forEach { el ->
            if (el.text().trim().isEmpty()) el.remove()
        }
        return combined(title, contentDoc.html())
    }
}

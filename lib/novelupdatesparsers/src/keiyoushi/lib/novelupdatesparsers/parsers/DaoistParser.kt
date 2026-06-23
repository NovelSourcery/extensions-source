package keiyoushi.lib.novelupdatesparsers.parsers

import keiyoushi.lib.novelupdatesparsers.SiteParser
import keiyoushi.lib.novelupdatesparsers.combined
import keiyoushi.lib.novelupdatesparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class DaoistParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "daoist"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val title = doc.select(".chapter__title").first()?.text() ?: ""
        doc.select("span.patreon-lock-icon").remove()
        doc.select("img[data-src]").forEach { el ->
            val dataSrc = el.attr("data-src")
            if (dataSrc.isNotEmpty()) {
                el.attr("src", dataSrc)
                el.removeAttr("data-src")
            }
        }
        val content = doc.select(".chapter__content").html()
        return combined(title, content)
    }
}

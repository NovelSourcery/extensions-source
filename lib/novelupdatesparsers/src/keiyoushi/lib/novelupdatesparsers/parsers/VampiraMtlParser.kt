package keiyoushi.lib.novelupdatesparsers.parsers

import eu.kanade.tachiyomi.network.GET
import keiyoushi.lib.novelupdatesparsers.SiteParser
import keiyoushi.lib.novelupdatesparsers.combined
import keiyoushi.lib.novelupdatesparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class VampiraMtlParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "vampiramtl"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val redirectUrl = doc.select(".entry-content a").first()?.attr("href") ?: ""
        val targetDoc = if (redirectUrl.isNotEmpty()) {
            val targetUrl = url.toString() + redirectUrl
            val resp = client.newCall(GET(targetUrl, headers)).execute()
            Jsoup.parse(resp.body.string(), targetUrl)
        } else {
            doc
        }
        val title = targetDoc.select(".entry-title").first()?.text() ?: ""
        val content = targetDoc.select(".entry-content").html()
        return combined(title, content)
    }
}

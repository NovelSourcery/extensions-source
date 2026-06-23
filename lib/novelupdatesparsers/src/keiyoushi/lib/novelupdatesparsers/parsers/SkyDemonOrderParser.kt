package keiyoushi.lib.novelupdatesparsers.parsers

import keiyoushi.lib.novelupdatesparsers.SiteParser
import keiyoushi.lib.novelupdatesparsers.combined
import keiyoushi.lib.novelupdatesparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class SkyDemonOrderParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "skydemonorder"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val ageCheck = doc.select("main").text().lowercase()
        if (ageCheck.contains("age verification required")) {
            throw Exception("Age verification required, please open in webview.")
        }
        val title = doc.select("header .font-medium.text-sm").first()?.text()?.trim() ?: ""
        val content = doc.select("#chapter-body").html()
        return combined(title, content)
    }
}

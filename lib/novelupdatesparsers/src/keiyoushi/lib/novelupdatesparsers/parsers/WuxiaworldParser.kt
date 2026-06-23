package keiyoushi.lib.novelupdatesparsers.parsers

import keiyoushi.lib.novelupdatesparsers.SiteParser
import keiyoushi.lib.novelupdatesparsers.combined
import keiyoushi.lib.novelupdatesparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class WuxiaworldParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "wuxiaworld"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        doc.select(".MuiLink-root").remove()
        val title = doc.select("h4 span").first()?.text() ?: ""
        val content = doc.select(".chapter-content").html()
        return combined(title, content)
    }
}

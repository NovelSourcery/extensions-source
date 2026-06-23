package keiyoushi.lib.novelupdatesparsers.parsers

import keiyoushi.lib.novelupdatesparsers.SiteParser
import keiyoushi.lib.novelupdatesparsers.combined
import keiyoushi.lib.novelupdatesparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class WebNovelParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "webnovel"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val title = doc.select(".cha-tit .pr .dib").first()?.text() ?: ""
        val content = doc.select(".cha-words").html().ifEmpty { doc.select("._content").html() }
        return combined(title, content)
    }
}

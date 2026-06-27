package novelsourcery.lib.novelupdatesparsers.parsers

import novelsourcery.lib.novelupdatesparsers.SiteParser
import novelsourcery.lib.novelupdatesparsers.combined
import novelsourcery.lib.novelupdatesparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

// mii translates
class IsoTlsParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "isotls"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        listOf("footer", "header", "nav", ".ezoic-ad", ".ezoic-adpicker-ad", ".ezoic-videopicker-video")
            .forEach { doc.select(it).remove() }
        val title = doc.select("head title").first()?.text() ?: ""
        val content = doc.select("main article").html()
        return combined(title, content)
    }
}

package novelsourcery.lib.siteparsers.parsers

import novelsourcery.lib.siteparsers.SiteParser
import novelsourcery.lib.siteparsers.combined
import novelsourcery.lib.siteparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class ScribbleHubParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "scribblehub"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        doc.select(".wi_authornotes").remove()
        val title = doc.select(".chapter-title").first()?.text() ?: ""
        val content = doc.select(".chp_raw").html()
        return combined(title, content)
    }
}

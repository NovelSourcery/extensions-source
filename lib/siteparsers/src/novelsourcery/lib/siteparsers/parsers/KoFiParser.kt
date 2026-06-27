package novelsourcery.lib.siteparsers.parsers

import novelsourcery.lib.siteparsers.SiteParser
import novelsourcery.lib.siteparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class KoFiParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "ko-fi"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val scriptHtml = doc.select("script:containsData(shadowDom.innerHTML)").html()
        val match = Regex("""shadowDom\.innerHTML \+= '(<div.*?)';""").find(scriptHtml)
        return match?.groupValues?.get(1) ?: ""
    }
}

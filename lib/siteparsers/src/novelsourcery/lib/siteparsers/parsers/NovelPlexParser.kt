package novelsourcery.lib.siteparsers.parsers

import novelsourcery.lib.siteparsers.SiteParser
import novelsourcery.lib.siteparsers.combined
import novelsourcery.lib.siteparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class NovelPlexParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "novelplex"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        doc.select(".passingthrough_adreminder").remove()
        val title = doc.select(".halChap--jud").first()?.text() ?: ""
        val content = doc.select(".halChap--kontenInner").html()
        return combined(title, content)
    }
}

package novelsourcery.lib.siteparsers.parsers

import novelsourcery.lib.siteparsers.SiteParser
import novelsourcery.lib.siteparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class NoBadNovelParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "nobadnovel"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val title = doc.select("h1").first()?.html()
        val content = doc.select("h1 + div").first()?.html()

        return "<h2>$title</h2><hr><br>$content"
    }
}

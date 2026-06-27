package novelsourcery.lib.siteparsers.parsers

import novelsourcery.lib.siteparsers.SiteParser
import novelsourcery.lib.siteparsers.combined
import novelsourcery.lib.siteparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class MiriluParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "mirilu"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        doc.select("#jp-post-flair").remove()
        val titleElement = doc.select(".entry-content p strong").first()
        val title = titleElement?.text() ?: ""
        titleElement?.remove()
        val content = doc.select(".entry-content").html()
        return combined(title, content)
    }
}

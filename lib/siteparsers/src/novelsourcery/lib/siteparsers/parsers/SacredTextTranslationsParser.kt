package novelsourcery.lib.siteparsers.parsers

import novelsourcery.lib.siteparsers.SiteParser
import novelsourcery.lib.siteparsers.combined
import novelsourcery.lib.siteparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class SacredTextTranslationsParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "sacredtexttranslations"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        listOf(".entry-content blockquote", ".entry-content div", ".reaction-buttons")
            .forEach { doc.select(it).remove() }
        val title = doc.select(".entry-title").first()?.text() ?: ""
        val content = doc.select(".entry-content").html()
        return combined(title, content)
    }
}

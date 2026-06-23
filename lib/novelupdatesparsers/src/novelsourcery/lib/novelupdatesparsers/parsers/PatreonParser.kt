package novelsourcery.lib.novelupdatesparsers.parsers

import novelsourcery.lib.novelupdatesparsers.SiteParser
import novelsourcery.lib.novelupdatesparsers.combined
import novelsourcery.lib.novelupdatesparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class PatreonParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "patreon"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        doc.select("#track-click, [class*=\"hidden \"]").remove()
        val title = doc.select("h1[data-tag=\"post-title\"]").text()
        val content = doc.select("[data-tag=\"post-card\"] [class*=\"PaddingTop\"]").html()
        return combined(title, content)
    }
}

package novelsourcery.lib.siteparsers.parsers

import eu.kanade.tachiyomi.network.GET
import novelsourcery.lib.siteparsers.SiteParser
import novelsourcery.lib.siteparsers.combined
import novelsourcery.lib.siteparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class StabbingWithASyringeParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "stabbingwithasyringe"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val redirectUrl = doc.select(".entry-content a").first()?.attr("href") ?: ""
        val targetDoc = if (redirectUrl.isNotEmpty()) {
            val resp = client.newCall(GET(redirectUrl, headers)).execute()
            Jsoup.parse(resp.body.string(), redirectUrl)
        } else {
            doc
        }
        listOf(".has-inline-color", ".wp-block-buttons", ".wpcnt", "#jp-post-flair")
            .forEach { targetDoc.select(it).remove() }
        val titleElement = targetDoc.select(".entry-content h3").first()
        val title = if (titleElement != null) {
            val t = titleElement.text()
            titleElement.remove()
            t
        } else {
            ""
        }
        val content = targetDoc.select(".entry-content").html()
        return combined(title, content)
    }
}

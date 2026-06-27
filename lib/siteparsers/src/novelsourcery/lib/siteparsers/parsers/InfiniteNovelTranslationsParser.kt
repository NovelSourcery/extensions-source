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

class InfiniteNovelTranslationsParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "infinitenoveltranslations"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val redirectUrl = doc.select("article > p > a").first()?.attr("href") ?: ""
        val targetDoc = if (redirectUrl.isNotEmpty()) {
            val resp = client.newCall(GET(redirectUrl, headers)).execute()
            Jsoup.parse(resp.body.string(), redirectUrl)
        } else {
            doc
        }
        val title = targetDoc.select(".entry-title").text()
        val content = targetDoc.select(".entry-content").html()
        return combined(title, content)
    }
}

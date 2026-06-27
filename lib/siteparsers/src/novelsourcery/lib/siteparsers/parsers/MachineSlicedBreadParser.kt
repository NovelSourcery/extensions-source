package novelsourcery.lib.siteparsers.parsers

import eu.kanade.tachiyomi.network.GET
import novelsourcery.lib.siteparsers.SiteParser
import novelsourcery.lib.siteparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class MachineSlicedBreadParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "machineslicedbread"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val urlParts = url.toString().split("/").filter { it.isNotEmpty() }
        val pathSegments = urlParts.drop(2)
        val targetDoc = if (pathSegments.size == 1) {
            val redirectPath = doc.select(".entry-content a").first()?.attr("href")
                ?: throw Exception("Chapter path not found.")
            val resp = client.newCall(GET(redirectPath, headers)).execute()
            Jsoup.parse(resp.body.string(), redirectPath)
        } else {
            doc
        }
        return targetDoc.select(".entry-content").html()
    }
}

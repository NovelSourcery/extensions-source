package novelsourcery.lib.novelupdatesparsers.parsers

import eu.kanade.tachiyomi.network.GET
import novelsourcery.lib.novelupdatesparsers.SiteParser
import novelsourcery.lib.novelupdatesparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class YoruParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "yoru"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val chapterId = url.pathSegments.last()
        val apiUrl = "https://pxp-main-531j.onrender.com/api/v1/book_chapters/$chapterId/content"
        val jsonUrl = client.newCall(GET(apiUrl, headers)).execute().body.string().trim().removeSurrounding("\"")
        return client.newCall(GET(jsonUrl, headers)).execute().body.string()
    }
}

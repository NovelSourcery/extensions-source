package novelsourcery.lib.novelupdatesparsers.parsers

import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import novelsourcery.lib.novelupdatesparsers.SiteParser
import novelsourcery.lib.novelupdatesparsers.combined
import novelsourcery.lib.novelupdatesparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class GreenzParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "greenz"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val chapterSlug = url.pathSegments.last()
        val apiUrl = "https://greenz.com/api/chapters/slug/$chapterSlug"

        val response = client.newCall(GET(apiUrl, headers)).execute()
        val data = Json.parseToJsonElement(response.body.string())
            .jsonObject["data"]!!.jsonObject

        val chapterName = data["name"]?.jsonPrimitive?.content ?: ""
        val chapterNumber = data["chapterNumber"]?.jsonPrimitive?.content ?: ""
        val rawContent = data["content"]?.jsonPrimitive?.content ?: ""

        val title = "Chapter $chapterNumber - $chapterName"
        val content = Jsoup.parse(rawContent).html()
        return combined(title, content)
    }
}

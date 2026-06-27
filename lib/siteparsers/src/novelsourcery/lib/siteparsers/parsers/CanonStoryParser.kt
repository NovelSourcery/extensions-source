package novelsourcery.lib.siteparsers.parsers

import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import novelsourcery.lib.siteparsers.SiteParser
import novelsourcery.lib.siteparsers.combined
import novelsourcery.lib.siteparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class CanonStoryParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "canonstory"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val parts = url.toString().split("/")
        if (parts.size < 7) throw Exception("Invalid chapter URL structure")
        val novelSlug = parts[4]
        val chapterSlug = parts[6]
        val apiUrl = "${parts[0]}//${parts[2]}/api/public/chapter-by-slug/$novelSlug/$chapterSlug"

        val response = client.newCall(GET(apiUrl, headers)).execute()
        val data = Json.parseToJsonElement(response.body.string())
            .jsonObject["data"]!!.jsonObject["currentChapter"]!!.jsonObject

        val chapterNumber = data["chapterNumber"]?.jsonPrimitive?.content ?: ""
        val titleText = data["title"]?.jsonPrimitive?.content ?: ""
        val content = data["content"]?.jsonPrimitive?.content ?: ""

        val title = if (titleText.isNotEmpty()) "Chapter $chapterNumber - $titleText" else "Chapter $chapterNumber"
        return combined(title, content.replace("\n", "<br>"))
    }
}

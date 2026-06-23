package novelsourcery.lib.novelupdatesparsers.parsers

import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
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

class KonkonParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "konkon"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val chapterId = url.toString().split("/")[5]
        val apiUrl = "https://api-k.konkon.ink/api/public/chapters/$chapterId"

        val response = client.newCall(
            GET(
                apiUrl,
                headers.newBuilder()
                    .set("Origin", "https://konkon.ink")
                    .set("Referer", "https://konkon.ink/")
                    .set("Accept", "application/json")
                    .build(),
            ),
        ).execute()
        if (!response.isSuccessful) throw Exception("Failed to fetch chapter: ${response.code}")

        val data = Json.parseToJsonElement(response.body.string())
            .jsonObject["data"]!!.jsonObject

        val isLocked = data["user_has_access"]?.jsonPrimitive?.boolean?.not() ?: true
        if (isLocked) throw Exception("Chapter is locked. Please open in webview and log in.")

        val title = data["title"]?.jsonPrimitive?.content ?: ""
        var content = data["content"]?.jsonPrimitive?.content
            ?: throw Exception("Could not extract chapter content.")

        val contentDoc = Jsoup.parse(content)
        contentDoc.select("span").forEach { it.tagName("p") }
        contentDoc.select("br").remove()
        content = contentDoc.html()

        return combined(title, content)
    }
}

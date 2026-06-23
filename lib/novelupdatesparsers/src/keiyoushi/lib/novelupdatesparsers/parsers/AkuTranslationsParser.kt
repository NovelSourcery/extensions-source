package keiyoushi.lib.novelupdatesparsers.parsers

import eu.kanade.tachiyomi.network.GET
import keiyoushi.lib.novelupdatesparsers.SiteParser
import keiyoushi.lib.novelupdatesparsers.domainKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class AkuTranslationsParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "akutranslations"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val apiUrl = url.toString().replace("/novel", "/api/novel")
        val response = client.newCall(GET(apiUrl, headers)).execute()
        val json = Json.parseToJsonElement(response.body.string()).jsonObject

        val rawContent = json["content"]?.jsonPrimitive?.content
            ?: throw Exception("Invalid API response structure.")

        return rawContent
            .trim().split(Regex("\n+"))
            .map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("\n") { "<p>$it</p>" }
    }
}

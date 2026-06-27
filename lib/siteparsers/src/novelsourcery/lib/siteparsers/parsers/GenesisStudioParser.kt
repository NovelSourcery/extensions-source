package novelsourcery.lib.siteparsers.parsers

import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import novelsourcery.lib.siteparsers.SiteParser
import novelsourcery.lib.siteparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class GenesisStudioParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "genesistudio"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val apiUrl = "$url/__data.json?x-sveltekit-invalidated=001"
        val response = client.newCall(GET(apiUrl, headers)).execute()
        val nodes = Json.parseToJsonElement(response.body.string())
            .jsonObject["nodes"]!!.jsonArray

        val data = nodes.first { it.jsonObject["type"]?.jsonPrimitive?.content == "data" }
            .jsonObject["data"]!!.jsonArray

        for (i in data.indices) {
            val entry = data[i].jsonObject
            if ("content" in entry && "notes" in entry && "footnotes" in entry) {
                val content = data[entry["content"]!!.jsonPrimitive.int].jsonPrimitive.content
                val notes = data[entry["notes"]!!.jsonPrimitive.int].jsonPrimitive.contentOrNull ?: ""
                val footnotes = data[entry["footnotes"]!!.jsonPrimitive.int].jsonPrimitive.contentOrNull ?: ""
                return content +
                    (if (notes.isNotEmpty()) "<h2>Notes</h2><br>$notes" else "") +
                    footnotes
            }
        }
        return ""
    }
}

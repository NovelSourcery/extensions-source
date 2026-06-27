package novelsourcery.lib.siteparsers.parsers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import novelsourcery.lib.siteparsers.SiteParser
import novelsourcery.lib.siteparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class BrightNovelsParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "brightnovels"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val dataPage = doc.select("#app").attr("data-page")
        if (dataPage.isNullOrEmpty()) throw Exception("data-page attribute not found on Bright Novels.")

        val pageData = Json.parseToJsonElement(dataPage).jsonObject
        val chapter = pageData["props"]!!.jsonObject["chapter"]!!.jsonObject

        val title = chapter["title"]?.jsonPrimitive?.content ?: ""
        val content = chapter["content"]?.jsonPrimitive?.content ?: ""

        val cleaned = Jsoup.parse(content).also { it.select("script, style").remove() }.html()
        return "<h2>$title</h2><hr><br>$cleaned"
    }
}

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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class NovelsHubParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "novelshub"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val segments = url.pathSegments
        val novelSlug = segments[segments.size - 2]
        val chapterSlug = segments.last()
        val apiUrl = "https://api.novelshub.org/api/chapter?mangaslug=$novelSlug&chapterslug=$chapterSlug"

        val response = client.newCall(GET(apiUrl, headers)).execute()
        val chapter = Json.parseToJsonElement(response.body.string())
            .jsonObject["chapter"]!!.jsonObject

        val chapterNumber = chapter["number"]?.jsonPrimitive?.content ?: ""
        val rawContent = chapter["content"]?.jsonPrimitive?.content ?: ""

        val title = "Chapter $chapterNumber"
        val contentDoc = Jsoup.parse(rawContent)
        contentDoc.select("div").forEach { el ->
            val style = el.attr("style")
            if (style.isEmpty()) return@forEach
            when {
                Regex("border:.*#ff6b00").containsMatchIn(style) ->
                    el.removeAttr("style").addClass("novels-hub_box_orange")
                Regex("color:.*#ff6b00.*text-transform:.*uppercase").containsMatchIn(style) ->
                    el.removeAttr("style").addClass("novels-hub_box-title_orange")
                Regex("color:.*white.*border-top:.*#ff6b00").containsMatchIn(style) ->
                    el.removeAttr("style").addClass("novels-hub_box-text_orange")
                Regex("border:.*#00ff88").containsMatchIn(style) ->
                    el.removeAttr("style").addClass("novels-hub_box_green")
                Regex("color:.*#00ff88.*text-transform:.*uppercase").containsMatchIn(style) ->
                    el.removeAttr("style").addClass("novels-hub_box-title_green")
                Regex("border-left:.*#00ff88").containsMatchIn(style) ->
                    el.removeAttr("style").addClass("novels-hub_comment_green")
                Regex("border:.*#0066ff").containsMatchIn(style) ->
                    el.removeAttr("style").addClass("novels-hub_box_blue")
                Regex("color:.*#0099ff.*text-transform:.*uppercase").containsMatchIn(style) ->
                    el.removeAttr("style").addClass("novels-hub_box-title_blue")
                Regex("color:.*#d0d0d0").containsMatchIn(style) ->
                    el.removeAttr("style").addClass("novels-hub_box-text_blue")
            }
        }
        contentDoc.select("span").forEach { el ->
            val style = el.attr("style")
            if (style.isEmpty()) return@forEach
            when {
                Regex("color:.*#ff6b6b").containsMatchIn(style) ->
                    el.removeAttr("style").addClass("novels-hub_text_red")
                Regex("color:.*#4d9fff").containsMatchIn(style) ->
                    el.removeAttr("style").addClass("novels-hub_text_blue")
                Regex("color:.*#a78bfa").containsMatchIn(style) ->
                    el.removeAttr("style").addClass("novels-hub_text_purple")
            }
        }
        return combined(title, contentDoc.html())
    }
}

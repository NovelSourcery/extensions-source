package novelsourcery.lib.siteparsers.parsers

import eu.kanade.tachiyomi.network.GET
import novelsourcery.lib.siteparsers.SiteParser
import novelsourcery.lib.siteparsers.combined
import novelsourcery.lib.siteparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class RedOxTranslationParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "redoxtranslation"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val chapterId = url.pathSegments.last()
        val title = "Chapter $chapterId"
        val txtUrl = "${url.toString().split("chapter")[0]}txt/$chapterId.txt"
        val text = client.newCall(GET(txtUrl, headers)).execute().body.string()
        val content = text.split("\n").joinToString("<br>") { sentence ->
            when {
                sentence.contains("{break}") -> "<br> <p>****</p>"
                else ->
                    sentence
                        .replace(Regex("""\*\*(.*?)\*\*"""), "<strong>$1</strong>")
                        .replace(Regex("""\+\+(.*?)\+\+"""), "<em>$1</em>")
            }
        }
        return combined(title, content)
    }
}

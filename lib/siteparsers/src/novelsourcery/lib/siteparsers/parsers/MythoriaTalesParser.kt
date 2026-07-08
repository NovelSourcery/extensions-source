package novelsourcery.lib.siteparsers.parsers

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import novelsourcery.lib.siteparsers.SiteParser
import novelsourcery.lib.siteparsers.combined
import novelsourcery.lib.siteparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document

class MythoriaTalesParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "mythoriatales"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val scriptHtml = doc.select("script:containsData(script-2)").joinToString("") { it.html() }
        if (scriptHtml.isEmpty()) throw Exception("Failed to find script-2")

        val matches2 = Regex(""""script-2.*?[^_]+([^"\\]+)""").findAll(scriptHtml).toList()
        val rawScriptPath = matches2.getOrNull(1)?.groupValues?.get(1)
            ?: throw Exception("Failed to extract script-2 URL")

        val scriptPath = rawScriptPath.replace("\\/", "/").trimStart('/')
        val scriptUrl = url.resolve("/$scriptPath")?.toString()
            ?: throw Exception("Failed to build valid script URL")

        val scriptText = client.newCall(GET(scriptUrl, headers)).execute().body.string()
        val actionHash = Regex("[a-f0-9]{42}").find(scriptText)?.value
            ?: throw Exception("Failed to extract ACTION_HASH")

        val urlStr = url.toString()
        val urlParts = urlStr.split("/")
        val slug = urlParts[4]
        val chapterNum = urlParts[6].toIntOrNull() ?: 0

        val rscHeaders = headers.newBuilder()
            .set("Accept", "text/x-component")
            .set("Content-Type", "text/plain;charset=UTF-8")
            .set("next-action", actionHash)
            .set("Origin", "https://${url.host}")
            .set("Referer", urlStr)
            .build()

        val rscBody = """["$slug",$chapterNum]""".toRequestBody("text/plain;charset=UTF-8".toMediaType())

        val rscResponse = client.newCall(POST(urlStr, rscHeaders, rscBody)).execute()
        if (!rscResponse.isSuccessful) throw Exception("Failed to fetch chapter: ${rscResponse.code}")

        val rscText = rscResponse.body.string().replace(Regex("""(\d+:[{TE])"""), "\n$1")
        val segments = rscText.split(Regex("""\n(?=\d+:[{TE])"""))

        val contentSegment = segments
            .filter { Regex("""^\d+:T""").containsMatchIn(it) && !it.startsWith("0:") }
            .joinToString("") { it.replace(Regex("""^\d+:T[0-9a-f]+,"""), "") }

        if (contentSegment.isEmpty()) throw Exception("Could not find chapter content segment in stream.")

        val metaSegment = segments.find { it.startsWith("1:") }
        var chapterTitle = ""
        if (metaSegment != null) {
            runCatching {
                val meta = Json.parseToJsonElement(metaSegment.substring(2))
                    .jsonObject["data"]!!.jsonObject["chapter"]!!.jsonObject
                val title = meta["title"]?.jsonPrimitive?.content
                val num = meta["chapterNumber"]?.jsonPrimitive?.int ?: chapterNum
                if (title != null) chapterTitle = "Chapter $num: $title"
            }
        }
        if (chapterTitle.isEmpty()) chapterTitle = "Chapter $chapterNum"

        val lines = contentSegment
            // Convert markdown images to HTML before paragraph processing
            .replace(Regex("""!\[([^\]]*)\]\(([^)]+)\)"""), """<img src="$2" alt="$1">""")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .fold(mutableListOf<String>()) { acc, line ->
                // If the line starts with a lowercase letter, merge it with the previous item
                if (acc.isNotEmpty() && line[0].isLowerCase() && !acc.last().startsWith("<img")) {
                    acc[acc.lastIndex] = "${acc.last()} $line"
                } else {
                    acc.add(line)
                }
                acc
            }

        val content = lines
            .joinToString("\n") { line ->
                // Don't wrap img tags in p tags
                if (line.startsWith("<img")) line else "<p>$line</p>"
            }
            .replace(
                Regex("""\[dialogue\s+speaker="([^"]*)"\](.*?)\[/dialogue\]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                "$1: $2",
            )
            .replace(Regex("""\[sfx\](.*?)\[/sfx\]""", RegexOption.IGNORE_CASE), "$1")
            .replace(Regex("""\[/?(dialogue|sfx)[^\]]*\]""", RegexOption.IGNORE_CASE), "")

        return combined(chapterTitle, content)
    }
}

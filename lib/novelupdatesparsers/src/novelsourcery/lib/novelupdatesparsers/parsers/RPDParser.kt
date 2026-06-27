package novelsourcery.lib.novelupdatesparsers.parsers

import eu.kanade.tachiyomi.network.GET
import novelsourcery.lib.novelupdatesparsers.SiteParser
import novelsourcery.lib.novelupdatesparsers.domainKey
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class RPDParser : SiteParser {
    override fun canHandle(doc: Document, url: HttpUrl) = url.domainKey() == "r-p-d"

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val urlStr = url.toString()
        val parts = urlStr.split("/")
        val resolveUrl = "${parts[0]}//${parts[2]}/resolve?p=/${parts.drop(3).joinToString("/")}"
        val resolveResponse = client.newCall(GET(resolveUrl, headers)).execute()
        val resolveJson = resolveResponse.body.string()
        val locationMatch = Regex(""""location"\s*:\s*"([^"]+)"""").find(resolveJson)
        val location = locationMatch?.groupValues?.get(1) ?: urlStr
        val parts2 = location.split("/")
        val base = "${parts2[0]}//${parts2[2]}"

        val metaResponse = client.newCall(
            GET("$base/api/chapter-meta?seriesSlug=${parts2[4]}&chapterSlug=${parts2[5]}", headers),
        ).execute()
        val metaJson = metaResponse.body.string()
        val idMatch = Regex(""""id"\s*:\s*(\d+)""").find(metaJson)
        val id = idMatch?.groupValues?.get(1) ?: throw Exception("Failed to get chapter id")

        val tokenResponse = client.newCall(GET("$base/api/chapters/$id/parts-token", headers)).execute()
        val tokenJson = tokenResponse.body.string()
        val tokenMatch = Regex(""""token"\s*:\s*"([^"]+)"""").find(tokenJson)
        val token = tokenMatch?.groupValues?.get(1) ?: throw Exception("Failed to get token")

        var total = 1
        var i = 1
        return buildString {
            while (i <= total) {
                val partResponse = client.newCall(
                    GET("$base/api/chapters/$id/parts?index=$i&token=$token", headers),
                ).execute()
                val partJson = partResponse.body.string()
                val markdownMatch = Regex(""""markdown"\s*:\s*"([\s\S]*?)(?<!\\)"""").find(partJson)
                val totalMatch = Regex(""""total"\s*:\s*(\d+)""").find(partJson)
                val markdown = markdownMatch?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                total = totalMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                append("<p>").append(markdown.replace("\n\n", "</p><p>")).append("</p>")
                i++
            }
        }
    }
}

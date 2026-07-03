package novelsourcery.lib.siteparsers.parsers

import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import novelsourcery.lib.siteparsers.SiteParser
import novelsourcery.lib.siteparsers.combined
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy
import kotlin.getValue

class KonkonParser : SiteParser {
    private val json: Json by injectLazy()
    override fun canHandle(doc: Document, url: HttpUrl) = "konkon" in url.host.split(".")

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        val data = try {
            json.decodeFromString<ApiDto>(doc.body().text()).data
        } catch (_: Exception) {
            val chapterId = url.toString().split("/")[5]
            val apiUrl = "https://api-k.konkon.ink/api/public/chapters/$chapterId"

            val response = client.newCall(GET(apiUrl, headers)).execute()
            if (!response.isSuccessful) throw Exception("Failed to fetch chapter: ${response.code}")

            json.decodeFromString<ApiDto>(response.body.string()).data
        }

        val isLocked = data.locked?.not() ?: true
        if (isLocked) throw Exception("Chapter is locked. Please open in webview and log in.")

        val title = data.title ?: ""
        val content = data.content ?: throw Exception("Could not extract chapter content.")

        val contentDoc = Jsoup.parse(content, url.toString())
        contentDoc.select("br, h3[style], hr").remove()
        contentDoc.select("span").forEach { it.tagName("p") }
        contentDoc.select("p").forEach {
            if (it.text().trim().isBlank()) {
                it.remove()
            }
        }
        return combined(title, contentDoc.body().html())
    }

    @Serializable
    data class ApiDto(
        val data: ChapterDto,
    )

    @Serializable
    data class ChapterDto(
        val id: Int,
        val title: String? = null,
        val slug: String? = null,
        @SerialName("user_has_access") val locked: Boolean?,
        @SerialName("updated_at") val updatedAt: String? = null,
        val content: String? = null,
    )
}

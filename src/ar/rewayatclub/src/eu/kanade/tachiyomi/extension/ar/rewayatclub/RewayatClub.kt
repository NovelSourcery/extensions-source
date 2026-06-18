package eu.kanade.tachiyomi.novelextension.ar.rewayatclub

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class RewayatClub :
    HttpSource(),
    NovelSource {

    override val name = "Rewayat Club"
    override val baseUrl = "https://rewayat.club"
    override val lang = "ar"
    override val supportsLatest = true
    override val isNovelSource = true
    override val client = network.client

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val apiUrl = "https://api.rewayat.club"

    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/api/novels?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val body = json.decodeFromString<NovelsResponse>(response.body.string())
        val novels = body.results.map { it.toSManga() }
        return MangasPage(novels, body.next != null)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/api/novels?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return GET("$apiUrl/api/novels?page=$page&search=$q", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/api/novels${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val item = json.decodeFromString<NovelItem>(response.body.string())
        return SManga.create().apply {
            url = "/${item.slug}"
            title = item.arabic
            thumbnail_url = "$apiUrl${item.poster_url}"
            description = item.about
            genre = item.genre.joinToString { it.arabic }
            status = when (item.get_novel_status) {
                "مكتملة" -> SManga.COMPLETED
                "مستمرة" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET("$apiUrl/api/chapters${manga.url}/?ordering=-number&page=1", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = json.decodeFromString<ChaptersResponse>(response.body.string())
        val chapters = body.results.map { ch ->
            SChapter.create().apply {
                url = "${ch.novel_slug}/${ch.number}"
                name = ch.title
                chapter_number = ch.number.toFloat()
                date_upload = DATE_FORMAT.tryParse(ch.date)
            }
        }
        if (body.next != null) {
            val nextUrl = body.next!!
            val nextResponse = client.newCall(GET(nextUrl, headers)).execute()
            return chapters.plus(chapterListParse(nextResponse))
        }
        return chapters
    }

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.encodedPath
        return listOf(Page(0, url))
    }

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.newCall(GET("$baseUrl${page.url}", headers)).execute().asJsoup()
        val nuxtScript = doc.select("script").firstOrNull { it.html().contains("window.__NUXT__") }
            ?: return ""
        val html = nuxtScript.html()
        val contentMatch = Regex("""e\.content="(.*?)" """).find(html) ?: return ""
        val content = contentMatch.groupValues[1]
            .replace("\\u003C", "<").replace("\\u003E", ">")
            .replace("\\n", "\n").replace("\\/", "/")
        return content
    }

    override fun imageUrlParse(response: Response): String = ""

    private fun NovelItem.toSManga() = SManga.create().apply {
        url = "/$slug"
        title = arabic
        thumbnail_url = "$apiUrl${poster_url}"
        genre = this@toSManga.genre.joinToString { it.arabic }
        status = when (get_novel_status) {
            "مكتملة" -> SManga.COMPLETED
            "مستمرة" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    @Serializable
    data class NovelsResponse(
        val count: Int = 0,
        val next: String? = null,
        val results: List<NovelItem> = emptyList(),
    )

    @Serializable
    data class NovelItem(
        val arabic: String = "",
        val english: String = "",
        val about: String = "",
        val slug: String = "",
        @SerialName("poster_url") val poster_url: String = "",
        val genre: List<GenreItem> = emptyList(),
        @SerialName("get_novel_status") val get_novel_status: String = "",
    )

    @Serializable
    data class GenreItem(val arabic: String = "")

    @Serializable
    data class ChaptersResponse(
        val count: Int = 0,
        val next: String? = null,
        val results: List<ChapterItem> = emptyList(),
    )

    @Serializable
    data class ChapterItem(
        val number: Int = 0,
        val title: String = "",
        val date: String = "",
        @SerialName("novel_slug") val novel_slug: String = "",
    )

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }
}

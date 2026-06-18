package eu.kanade.tachiyomi.novelextension.ar.seanovel

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response

class SeaNovel :
    HttpSource(),
    NovelSource {

    override val name = "SeaNovel"
    override val baseUrl = "https://seanovel.org"
    override val lang = "ar"
    override val supportsLatest = true
    override val isNovelSource = true
    override val client = network.client

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun popularMangaRequest(page: Int): Request {
        val limit = 50
        val offset = (page - 1) * limit
        return GET("$baseUrl/api/novels?sort=views&page=1&limit=$limit&offset=$offset", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body.string()
        val novels = json.decodeFromString<List<NovelDto>>(body)
        return MangasPage(novels.map { it.toSManga() }, novels.size >= 50)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val limit = 50
        val offset = (page - 1) * limit
        return GET("$baseUrl/api/novels?sort=latest&page=1&limit=$limit&offset=$offset", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val limit = 50
        val offset = (page - 1) * limit
        return GET("$baseUrl/api/novels?q=$query&limit=$limit&offset=$offset", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.substringAfterLast("/")
        return GET("$baseUrl/api/novel/$slug", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body.string()
        val novel = json.decodeFromString<NovelDto>(body)
        return novel.toSManga()
    }

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.substringAfterLast("/")
        return GET("$baseUrl/api/novel/$slug/chapters?page=1&limit=10000&sort=asc", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()
        val chapterResponse = json.decodeFromString<ChapterResponse>(body)
        val slug = response.request.url.toString().substringAfter("/novel/").substringBefore("/chapters")
        return chapterResponse.chapters.map { it.toSChapter(slug) }
    }

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.encodedPath
        return listOf(Page(0, url))
    }

    override suspend fun fetchPageText(page: Page): String {
        val url = baseUrl + page.url
        val response = client.newCall(GET(url, headers)).execute()
        val doc = response.asJsoup()
        val html = doc.html()

        val paragraphsMatch = Regex("\"initialParagraphs\":\\[((?:[^\\[\\]]|\\[(?:[^\\[\\]]|\\[[^\\[\\]]*\\])*\\])*)\\]").find(html)
        if (paragraphsMatch != null) {
            val paragraphsStr = paragraphsMatch.groupValues[1]
            val paragraphs = json.decodeFromString<List<String>>("[$paragraphsStr]")
            return paragraphs.joinToString("<br><br>") { "<p>$it</p>" }
        }

        val content = doc.selectFirst(".chapter-content, .content, .entry-content, article") ?: return ""
        content.select("script, style, nav, footer, .ads, .navigation").remove()
        return content.html().trim()
    }

    override fun imageUrlParse(response: Response): String = ""

    private fun NovelDto.toSManga(): SManga = SManga.create().apply {
        url = "/novels/$slug"
        title = titleAr.ifEmpty { titleOriginal }
        author = this@toSManga.author
        description = this@toSManga.description
        thumbnail_url = "$baseUrl/api/novel/$slug/cover?type=original&v=$coverVersion"
        genre = genres.joinToString(", ")
        status = when (this@toSManga.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "dropped" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun ChapterDto.toSChapter(novelSlug: String = ""): SChapter = SChapter.create().apply {
        url = "/novels/$novelSlug/chapters/$id"
        name = title
        date_upload = runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).parse(date)?.time
        }.getOrNull() ?: 0L
    }

    @Serializable
    data class NovelDto(
        val slug: String = "",
        @SerialName("title_ar") val titleAr: String = "",
        @SerialName("title_original") val titleOriginal: String = "",
        val author: String = "",
        val status: String = "",
        val genres: List<String> = emptyList(),
        val description: String = "",
        @SerialName("cover_version") val coverVersion: String = "1",
        @SerialName("chapters_count") val chaptersCount: Int = 0,
    )

    @Serializable
    data class ChapterResponse(
        val chapters: List<ChapterDto> = emptyList(),
        val total: Int = 0,
        @SerialName("hasMore") val hasMore: Boolean = false,
    )

    @Serializable
    data class ChapterDto(
        val id: Double = 0.0,
        val title: String = "",
        val date: String = "",
    )
}

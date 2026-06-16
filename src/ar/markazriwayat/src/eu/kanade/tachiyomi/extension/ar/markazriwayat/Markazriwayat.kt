package eu.kanade.tachiyomi.novelextension.ar.markazriwayat

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

class Markazriwayat :
    HttpSource(),
    NovelSource {

    override val name = "Markazriwayat"
    override val baseUrl = "https://markazriwayat.com"
    override val lang = "ar"
    override val supportsLatest = true
    override val isNovelSource = true
    override val client = network.client

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/wp-json/theam/v1/library?page=$page&per_page=$PER_PAGE&sort=popular", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseLibraryResponse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/wp-json/theam/v1/library?page=$page&per_page=$PER_PAGE&sort=new", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseLibraryResponse(response)

    private fun parseLibraryResponse(response: Response): MangasPage {
        val body = json.decodeFromString<LibraryResponse>(response.body.string())
        val novels = body.items.map { item ->
            SManga.create().apply {
                title = item.title
                url = item.link.toRelative()
                thumbnail_url = item.cover.ifBlank { null }
                genre = (item.genres + item.tags).joinToString { it.name }
                status = when (item.status.key) {
                    "end" -> SManga.COMPLETED
                    "on-going" -> SManga.ONGOING
                    "stop" -> SManga.ON_HIATUS
                    else -> SManga.UNKNOWN
                }
            }
        }
        return MangasPage(novels, hasNextPage = body.page < body.totalPages)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val term = java.net.URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/wp-json/theam/v1/novel-search?term=$term&page=$page&per_page=$PER_PAGE", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val body = json.decodeFromString<SearchResponse>(response.body.string())
        val novels = body.items.mapNotNull { item ->
            val link = item.link.ifBlank { return@mapNotNull null }
            SManga.create().apply {
                title = item.title
                url = link.toRelative()
                thumbnail_url = item.cover.ifBlank { null }
                genre = item.genres.joinToString()
            }
        }
        return MangasPage(novels, hasNextPage = novels.size >= PER_PAGE)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        checkCaptcha(doc)
        return SManga.create().apply {
            title = doc.selectFirst("h1.manga-title")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst(".manga-cover-wrap > img")?.absCover()
            val authors = doc.select(".manga-author")
            if (authors.size >= 2) {
                description = buildString {
                    append("مترجم الرواية : ${authors[0].text().trim()}\n")
                    append("مؤلف الرواية : ${authors[1].text().trim()}")
                }
            } else {
                description = authors.joinToString("\n") { it.text().trim() }
            }
            genre = (doc.select(".pill-list .pill").map { it.text().trim() }).joinToString()
            val statusText = doc.selectFirst(".status-pill")?.text().orEmpty().lowercase()
            status = when {
                "مكتملة" in statusText || "complete" in statusText -> SManga.COMPLETED
                "مستمرة" in statusText || "ongoing" in statusText -> SManga.ONGOING
                "متوقفة" in statusText || "stop" in statusText -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val doc = client.newCall(GET(baseUrl + manga.url, headers)).execute().asJsoup()
        checkCaptcha(doc)
        val mangaId = doc.selectFirst("#manga-chapters-list")?.attr("data-manga-id")?.takeIf { it.isNotBlank() }
        if (mangaId != null) {
            val apiChapters = fetchChaptersViaApi(mangaId)
            if (apiChapters.isNotEmpty()) return apiChapters
        }
        return parseChaptersFromHtml(doc).sortedByDescending { it.chapter_number }
    }

    private fun fetchChaptersViaApi(mangaId: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var page = 1
        while (true) {
            val url = "$baseUrl/wp-json/theam/v1/manga-chapters?manga_id=$mangaId&order=DESC&page=$page&per_page=100"
            val body = try {
                json.decodeFromString<ChaptersResponse>(client.newCall(GET(url, headers)).execute().body.string())
            } catch (_: Exception) {
                break
            }
            body.items.forEach { item ->
                if (item.url.isBlank()) return@forEach
                chapters.add(
                    SChapter.create().apply {
                        name = item.label
                        this.url = item.url.toRelative()
                        chapter_number = item.num.toChapterNumber()
                        date_upload = DATE_FORMAT.tryParse(item.date)
                    },
                )
            }
            if (!body.hasMore || body.items.isEmpty()) break
            page++
        }
        return chapters.sortedByDescending { it.chapter_number }
    }

    private fun parseChaptersFromHtml(doc: org.jsoup.nodes.Document): List<SChapter> = doc.select(".ch-row").mapNotNull { row ->
        val link = row.selectFirst("a")?.attr("href")?.ifBlank { null } ?: return@mapNotNull null
        SChapter.create().apply {
            name = row.selectFirst(".ch-title")?.text()?.trim() ?: link
            url = link.toRelative()
            chapter_number = (row.selectFirst(".ch-num")?.text() ?: name).toChapterNumber()
            date_upload = DATE_FORMAT.tryParse(row.selectFirst(".ch-date")?.text())
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> = parseChaptersFromHtml(response.asJsoup()).sortedByDescending { it.chapter_number }

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.encodedPath))

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.newCall(GET(baseUrl + page.url, headers)).execute().asJsoup()
        checkCaptcha(doc)
        val content = doc.selectFirst(".reading-content .text-right")
            ?: doc.selectFirst(".reading-content")
            ?: return ""
        content.select(
            "script, ins, .adsbygoogle, iframe, noscript, " +
                "span.theam-chobf, " +
                "span[data-theam-chobf], " +
                "[style*=\"display:none\"], " +
                "[style*=\"visibility:hidden\"], " +
                "[aria-hidden=\"true\"]",
        ).remove()
        return content.html().trim()
    }

    override fun imageUrlParse(response: Response): String = ""

    private fun Response.asJsoup() = Jsoup.parse(body.string(), request.url.toString())

    private fun checkCaptcha(doc: org.jsoup.nodes.Document) {
        val title = doc.title().trim()
        if (title == "Bot Verification" || title == "Just a moment...") {
            throw Exception("Captcha detected, please open in WebView")
        }
    }

    private fun String.toRelative(): String = when {
        startsWith(baseUrl) -> removePrefix(baseUrl)
        startsWith("/") -> this
        else -> "/$this"
    }

    private fun org.jsoup.nodes.Element.absCover(): String? {
        val raw = attr("data-src").ifBlank { null }
            ?: attr("data-lazy-src").ifBlank { null }
            ?: attr("src").ifBlank { null }
            ?: return null
        return if (raw.startsWith("http")) raw else "$baseUrl$raw"
    }

    private fun String?.toChapterNumber(): Float {
        if (this.isNullOrBlank()) return -1f
        return CHAPTER_NUM_REGEX.find(this)?.value?.toFloatOrNull() ?: -1f
    }

    @Serializable
    private data class LibraryResponse(
        val page: Int = 0,
        @SerialName("per_page") val perPage: Int = 0,
        val total: Int = 0,
        @SerialName("totalPages") val totalPages: Int = 0,
        val items: List<LibraryItem> = emptyList(),
    )

    @Serializable
    private data class LibraryItem(
        val title: String = "",
        val link: String = "",
        val cover: String = "",
        val status: StatusInfo = StatusInfo(),
        val genres: List<GenreInfo> = emptyList(),
        val tags: List<GenreInfo> = emptyList(),
    )

    @Serializable
    private data class StatusInfo(
        val key: String = "",
        val label: String = "",
    )

    @Serializable
    private data class GenreInfo(
        val name: String = "",
    )

    @Serializable
    private data class SearchResponse(
        val items: List<SearchItem> = emptyList(),
    )

    @Serializable
    private data class SearchItem(
        val title: String = "",
        val link: String = "",
        val cover: String = "",
        val genres: List<String> = emptyList(),
    )

    @Serializable
    private data class ChaptersResponse(
        val items: List<ChapterItem> = emptyList(),
        @SerialName("has_more") val hasMore: Boolean = false,
    )

    @Serializable
    private data class ChapterItem(
        val label: String = "",
        val url: String = "",
        val num: String = "",
        val date: String = "",
    )

    companion object {
        private const val PER_PAGE = 20
        private val CHAPTER_NUM_REGEX = Regex("""\d+(\.\d+)?""")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}

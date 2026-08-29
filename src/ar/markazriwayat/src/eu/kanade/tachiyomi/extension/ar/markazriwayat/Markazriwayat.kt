package eu.kanade.tachiyomi.novelextension.ar.markazriwayat

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class Markazriwayat :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun buildPopularMangaRequest(page: Int): Request = GET("$baseUrl/wp-json/theam/v1/library?page=$page&per_page=$PER_PAGE&sort=popular", headers)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = buildPopularMangaRequest(page)
        return parseLibraryResponse(client.get(request.url, request.headers))
    }

    private fun buildLatestUpdatesRequest(page: Int): Request = GET("$baseUrl/wp-json/theam/v1/library?page=$page&per_page=$PER_PAGE&sort=new", headers)

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = buildLatestUpdatesRequest(page)
        return parseLibraryResponse(client.get(request.url, request.headers))
    }

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

    private fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val term = java.net.URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/wp-json/theam/v1/novel-search?term=$term&page=$page&per_page=$PER_PAGE", headers)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val request = buildSearchMangaRequest(page, query, filters)
        val response = client.get(request.url, request.headers)
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
        return MangasPage(novels, hasNextPage = body.hasMore)
    }

    private fun buildMangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        val response = client.get(url, headers)
        if (!response.isSuccessful) return null
        val doc = response.asJsoup()
        return parseMangaDetails(doc).apply { this.url = path }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val request = buildMangaDetailsRequest(manga)
        val doc = client.get(request.url, request.headers).asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) fetchChapterList(doc) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(doc: Document): SManga = SManga.create().apply {
        title = doc.selectFirst("h1.manga-title")?.text().orEmpty()
        thumbnail_url = doc.selectFirst(".manga-cover-wrap > img")?.absCover()
        val authors = doc.select(".manga-author")
        author = if (authors.size >= 2) {
            authors[1].text()
        } else {
            authors.firstOrNull()?.text() ?: ""
        }
        if (authors.size >= 2) {
            description = buildString {
                append("مترجم الرواية : ${authors[0].text()}\n")
                append("مؤلف الرواية : ${authors[1].text()}")
            }
        } else {
            description = authors.joinToString("\n") { it.text() }
        }
        genre = (doc.select(".pill-list .pill").map { it.text() }).joinToString()
        val statusText = doc.selectFirst(".status-pill")?.text().orEmpty().lowercase()
        status = when {
            "مكتملة" in statusText || "complete" in statusText -> SManga.COMPLETED
            "مستمرة" in statusText || "ongoing" in statusText -> SManga.ONGOING
            "متوقفة" in statusText || "stop" in statusText -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    private suspend fun fetchChapterList(doc: Document): List<SChapter> {
        val mangaId = doc.selectFirst("#manga-chapters-list")?.attr("data-manga-id")?.takeIf { it.isNotBlank() }
        if (mangaId != null) {
            val apiChapters = fetchChaptersViaApi(mangaId)
            if (apiChapters.isNotEmpty()) return apiChapters
        }
        return parseChaptersFromHtml(doc).sortedByDescending { it.chapter_number }
    }

    private suspend fun fetchChaptersViaApi(mangaId: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var page = 1
        while (true) {
            val url = "$baseUrl/wp-json/theam/v1/manga-chapters?manga_id=$mangaId&order=DESC&page=$page&per_page=100"
            val body = try {
                val resp = client.get(url, headers)
                val parsed = json.decodeFromString<ChaptersResponse>(resp.body.string())
                resp.close()
                parsed
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
                        date_upload = DATE_FORMAT.tryParseDate(item.date)
                    },
                )
            }
            if (!body.hasMore || body.items.isEmpty()) break
            page++
        }
        return chapters.sortedByDescending { it.chapter_number }
    }

    private fun parseChaptersFromHtml(doc: Document): List<SChapter> = doc.select(".ch-row").mapNotNull { row ->
        val link = row.selectFirst("a")?.attr("href")?.ifBlank { null } ?: return@mapNotNull null
        SChapter.create().apply {
            name = row.selectFirst(".ch-title")?.text() ?: link
            url = link.toRelative()
            chapter_number = (row.selectFirst(".ch-num")?.text() ?: name).toChapterNumber()
            date_upload = DATE_FORMAT.tryParseDate(row.selectFirst(".ch-date")?.text())
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(baseUrl + chapter.url, headers)
        return listOf(Page(0, response.request.url.encodedPath))
    }

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.get(baseUrl + page.url, headers).asJsoup()
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

    private fun String.toRelative(): String = when {
        startsWith(baseUrl) -> removePrefix(baseUrl)
        startsWith("/") -> this
        else -> "/$this"
    }

    private fun Element.absCover(): String? {
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
    private class LibraryResponse(
        val page: Int = 0,
        @SerialName("per_page") val perPage: Int = 0,
        val total: Int = 0,
        @SerialName("totalPages") val totalPages: Int = 0,
        val items: List<LibraryItem> = emptyList(),
    )

    @Serializable
    private class LibraryItem(
        val id: Int = 0,
        val title: String = "",
        val link: String = "",
        val cover: String = "",
        val status: StatusInfo = StatusInfo(),
        val genres: List<GenreInfo> = emptyList(),
        val tags: List<GenreInfo> = emptyList(),
    )

    @Serializable
    private class StatusInfo(
        val key: String = "",
        val label: String = "",
    )

    @Serializable
    private class GenreInfo(
        val name: String = "",
    )

    @Serializable
    private class SearchResponse(
        val items: List<SearchItem> = emptyList(),
        @SerialName("has_more") val hasMore: Boolean = false,
    )

    @Serializable
    private class SearchItem(
        val title: String = "",
        val link: String = "",
        val cover: String = "",
        val genres: List<String> = emptyList(),
    )

    @Serializable
    private class ChaptersResponse(
        val items: List<ChapterItem> = emptyList(),
        @SerialName("has_more") val hasMore: Boolean = false,
    )

    @Serializable
    private class ChapterItem(
        val label: String = "",
        val url: String = "",
        val num: String = "",
        val date: String = "",
    )

    companion object {
        private const val PER_PAGE = 20
        private val CHAPTER_NUM_REGEX = Regex("""\d+(\.\d+)?""")
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    }
}

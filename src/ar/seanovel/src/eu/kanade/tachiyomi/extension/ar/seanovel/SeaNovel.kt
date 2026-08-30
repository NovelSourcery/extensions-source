package eu.kanade.tachiyomi.novelextension.ar.seanovel

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
import keiyoushi.utils.SlugPath
import keiyoushi.utils.tryParseDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document

@Source
abstract class SeaNovel :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    private val junkPattern = Regex(
        "^الفصل\\s+[\\d٠-٩]+\\s*[:：].+" +
            "|^http" +
            "|.*seanovel\\..*" +
            "|.*بحر الروايات.*" +
            "|اكتشف أفضل الروايات.*" +
            "|أنت تقرأ الفصل.*" +
            "|انتهى الفصل.*" +
            "|تابع القراءة على.*" +
            "|^الفصل\\s+(التالي|السابق).*" +
            "|^تابع\\s+القراءة.*" +
            "|^اشترك\\s+في.*" +
            "|^<\\s*" +
            "|^\\[.*\\]$",
        RegexOption.IGNORE_CASE,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** [SManga.url] stored as a bare slug via [mangaPathTemplate]. */
    private val mangaPathTemplate = SlugPath("/novels/")

    private val dateFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)

    private fun buildPopularMangaRequest(page: Int): Request {
        val limit = 50
        val offset = (page - 1) * limit
        return GET("$baseUrl/api/novels?sort=views&page=1&limit=$limit&offset=$offset", headers)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = buildPopularMangaRequest(page)
        val response = client.get(request.url, request.headers)
        val body = response.body.string()
        val novels = json.decodeFromString<List<NovelDto>>(body)
        return MangasPage(novels.map { it.toSManga() }, novels.size >= 50)
    }

    private fun buildLatestUpdatesRequest(page: Int): Request {
        val limit = 50
        val offset = (page - 1) * limit
        return GET("$baseUrl/api/novels?sort=latest&page=1&limit=$limit&offset=$offset", headers)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = buildLatestUpdatesRequest(page)
        val response = client.get(request.url, request.headers)
        val body = response.body.string()
        val novels = json.decodeFromString<List<NovelDto>>(body)
        return MangasPage(novels.map { it.toSManga() }, novels.size >= 50)
    }

    private fun buildSearchMangaRequest(page: Int, query: String): Request {
        val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/api/search-index?q=$encodedQuery&__page=$page", headers)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val request = buildSearchMangaRequest(page, query)
        val response = client.get(request.url, request.headers)
        val url = response.request.url
        val queryParam = url.queryParameter("q") ?: ""
        val pageParam = url.queryParameter("__page")?.toIntOrNull() ?: 1
        val body = response.body.string()
        val allNovels = json.decodeFromString<List<NovelDto>>(body)
        val filtered = if (queryParam.isNotBlank()) {
            allNovels.filter { novel ->
                novel.titleAr.contains(queryParam, ignoreCase = true) ||
                    novel.titleOriginal.contains(queryParam, ignoreCase = true)
            }
        } else {
            allNovels
        }
        val limit = 50
        val offset = (pageParam - 1) * limit
        val paginated = filtered.drop(offset).take(limit)
        return MangasPage(paginated.map { it.toSManga() }, offset + limit < filtered.size)
    }

    private fun buildMangaDetailsRequest(manga: SManga): Request {
        val slug = mangaPathTemplate.resolve(manga.url).substringAfterLast("/")
        return GET("$baseUrl/api/novel/$slug", headers)
    }

    override fun getMangaUrl(manga: SManga): String = mangaPathTemplate.absolute(baseUrl, manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val manga = SManga.create().apply { this.url = mangaPathTemplate.slug(url.encodedPath) }
        val request = buildMangaDetailsRequest(manga)
        val response = client.get(request.url, request.headers)
        if (!response.isSuccessful) return null
        return json.decodeFromString<NovelDto>(response.body.string()).toSManga()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = mangaPathTemplate.resolve(manga.url).substringAfterLast("/")

        val updatedManga = if (fetchDetails) {
            val request = buildMangaDetailsRequest(manga)
            val response = client.get(request.url, request.headers)
            val body = response.body.string()
            json.decodeFromString<NovelDto>(body).toSManga()
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) fetchChapterList(slug) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private suspend fun fetchChapterList(slug: String): List<SChapter> {
        val allChapters = mutableListOf<SChapter>()
        var offset = 0
        val limit = 100
        var hasMore: Boolean
        do {
            val url = "$baseUrl/api/novel/$slug/chapters?offset=$offset&limit=$limit&sort=asc"
            val response = client.get(url, headers)
            val chapterResponse = json.decodeFromString<ChapterResponse>(response.body.string())
            if (chapterResponse.chapters.isEmpty()) break
            allChapters.addAll(chapterResponse.chapters.map { it.toSChapter(slug) })
            offset += limit
            hasMore = chapterResponse.hasMore
        } while (hasMore)
        // Paginated ascending (oldest-first) to make offset/limit walk the full list correctly -
        // the app expects newest-first, so flip the assembled result.
        return allChapters.reversed()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val url = baseUrl + page.url
        val response = client.get(url, headers)
        val doc = response.asJsoup()
        val html = doc.html()

        val paragraphs = extractParagraphsFromRsc(html)
        if (paragraphs != null && paragraphs.isNotEmpty()) {
            val cleaned = paragraphs.filter { text ->
                text.isNotBlank() && !junkPattern.containsMatchIn(text)
            }
            if (cleaned.isNotEmpty()) {
                return cleaned.joinToString("<br><br>") { "<p>$it</p>" }
            }
        }

        val paragraphsFromHtml = extractParagraphsFromHtml(doc)
        if (paragraphsFromHtml.isNotEmpty()) {
            return paragraphsFromHtml.joinToString("<br><br>") { "<p>$it</p>" }
        }

        val content = doc.selectFirst(".chapter-content, .content, .entry-content, article") ?: return ""
        content.select(
            "script, style, nav, footer, header, .ads, .navigation, .chapter-nav, .prev-next, .share, .comments, .breadcrumb, .novel-info, .sidebar, .footer, .header, [role=navigation], [role=banner], [role=contentinfo]",
        ).remove()
        content.select("a[href*=\"/chapters/\"], a[href*=\"/novels/\"]").remove()
        return content.html().trim()
    }

    private fun extractParagraphsFromRsc(html: String): List<String>? {
        val marker = "\"initialParagraphs\":"
        val markerIdx = html.indexOf(marker)
        if (markerIdx < 0) return null
        var idx = markerIdx + marker.length
        while (idx < html.length && html[idx] == ' ') idx++
        if (idx >= html.length || html[idx] != '[') return null
        val start = idx
        var depth = 0
        var inStr = false
        var i = idx
        while (i < html.length) {
            val c = html[i]
            if (inStr) {
                if (c == '\\' && i + 1 < html.length) {
                    i += 2
                    continue
                }
                if (c == '"') inStr = false
            } else {
                if (c == '"') {
                    inStr = true
                } else if (c == '[') {
                    depth++
                } else if (c == ']') {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        if (depth != 0) return null
        val arrayText = html.substring(start, i + 1)
            .replace("\\\"", "\"")
        return try {
            json.decodeFromString<List<String>>(arrayText)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractParagraphsFromHtml(doc: Document): List<String> = doc.select("p").map { it.text() }.filter { text ->
        text.isNotBlank() && text.length > 3 && !junkPattern.containsMatchIn(text)
    }

    private fun NovelDto.toSManga(): SManga = SManga.create().apply {
        url = mangaPathTemplate.slug("/novels/$slug")
        title = titleAr.ifEmpty { titleOriginal }
        author = this@toSManga.author
        description = this@toSManga.description
        thumbnail_url = "$baseUrl/api/novel/$slug/cover?v=$coverVersion"
        genre = genres.joinToString()
        status = when (this@toSManga.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "dropped" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun ChapterDto.toSChapter(novelSlug: String = ""): SChapter = SChapter.create().apply {
        val chapterId = id.toInt()
        url = "/novels/$novelSlug/chapters/$chapterId"
        name = title
        chapter_number = id.toFloat()
        date_upload = dateFormatter.tryParseDateTime(date)
    }

    @Serializable
    class NovelDto(
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
    class ChapterResponse(
        val chapters: List<ChapterDto> = emptyList(),
        val total: Int = 0,
        @SerialName("hasMore") val hasMore: Boolean = false,
    )

    @Serializable
    class ChapterDto(
        val id: Double = 0.0,
        val title: String = "",
        val date: String = "",
    )
}

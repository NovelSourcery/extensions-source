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
import org.jsoup.nodes.Document

class SeaNovel :
    HttpSource(),
    NovelSource {

    override val name = "SeaNovel"
    override val baseUrl = "https://seanovel.org"
    override val lang = "ar"
    override val supportsLatest = true
    override val isNovelSource = true
    override val client = network.client

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
        val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/api/search-index?q=$encodedQuery&__page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val url = response.request.url
        val query = url.queryParameter("q") ?: ""
        val page = url.queryParameter("__page")?.toIntOrNull() ?: 1
        val body = response.body.string()
        val allNovels = json.decodeFromString<List<NovelDto>>(body)
        val filtered = if (query.isNotBlank()) {
            allNovels.filter { novel ->
                novel.titleAr.contains(query, ignoreCase = true) ||
                    novel.titleOriginal.contains(query, ignoreCase = true)
            }
        } else {
            allNovels
        }
        val limit = 50
        val offset = (page - 1) * limit
        val paginated = filtered.drop(offset).take(limit)
        return MangasPage(paginated.map { it.toSManga() }, offset + limit < filtered.size)
    }

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
        return GET("$baseUrl/api/novel/$slug/chapters?offset=0&limit=100&sort=asc", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()
        val chapterResponse = json.decodeFromString<ChapterResponse>(body)
        val slug = response.request.url.toString().substringAfter("/novel/").substringBefore("/chapters")
        return chapterResponse.chapters.map { it.toSChapter(slug) }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val slug = manga.url.substringAfterLast("/")
        val allChapters = mutableListOf<SChapter>()
        var offset = 0
        val limit = 100
        do {
            val request = GET("$baseUrl/api/novel/$slug/chapters?offset=$offset&limit=$limit&sort=asc", headers)
            val response = client.newCall(request).execute()
            val chapterResponse = json.decodeFromString<ChapterResponse>(response.body.string())
            if (chapterResponse.chapters.isEmpty()) break
            allChapters.addAll(chapterResponse.chapters.map { it.toSChapter(slug) })
            offset += limit
        } while (chapterResponse.hasMore)
        return allChapters
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
        val markerIdx = html.indexOf(marker) ?: return null
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
        val chapterId = id.toInt()
        url = "/novels/$novelSlug/chapters/$chapterId"
        name = title
        chapter_number = id.toFloat()
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

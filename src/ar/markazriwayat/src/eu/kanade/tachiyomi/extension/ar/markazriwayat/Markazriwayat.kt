package eu.kanade.tachiyomi.novelextension.ar.markazriwayat

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class Markazriwayat : HttpSource(), NovelSource {

    override val name = "مركز رواية"
    override val baseUrl = "https://markazriwayat.com"
    override val lang = "ar"
    override val supportsLatest = true

    override val isNovelSource = true

    // ✅ التغيير الأهم: استخدام network.client بدلاً من network.cloudflareClient
    //    لضمان التوافق مع جميع إصدارات Tachiyomi
    override val client = network.client

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ============================== التصفح ==============================

    override fun popularMangaRequest(page: Int): Request = GET(pagedUrl("/library/", page), headers)

    override fun latestUpdatesRequest(page: Int): Request = GET(pagedUrl("/new/", page), headers)

    private fun pagedUrl(path: String, page: Int): String =
        if (page <= 1) "$baseUrl$path" else "$baseUrl$path".trimEnd('/') + "/page/$page/"

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        checkCaptcha(doc)
        val novels = doc.select("a.lib-card").mapNotNull { card ->
            val title = card.selectFirst(".lib-card__title")?.text()?.trim() ?: return@mapNotNull null
            val href = card.attr("href").ifBlank { return@mapNotNull null }
            SManga.create().apply {
                this.title = title
                url = href.toRelative()
                thumbnail_url = card.selectFirst(".lib-card__img img")?.absCover()
            }
        }
        return MangasPage(novels, hasNextPage = novels.isNotEmpty() && hasNextPage(doc))
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    private fun hasNextPage(doc: Document): Boolean =
        doc.selectFirst("a.next, a[rel=next], link[rel=next], .pagination a.next, .nav-links a.next") != null

    // ============================== البحث عبر API ==============================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val term = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/wp-json/theam/v1/novel-search?term=$term&page=$page&per_page=20", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val result = json.decodeFromString<SearchResponse>(response.body.string())
        val novels = result.items.mapNotNull { item ->
            val link = item.link.ifBlank { return@mapNotNull null }
            SManga.create().apply {
                title = item.title
                url = link.toRelative()
                thumbnail_url = item.cover.ifBlank { null }
                genre = item.genres.joinToString()
                description = if (item.chaptersCount > 0) "عدد الفصول: ${item.chaptersCount}" else ""
            }
        }
        return MangasPage(novels, hasNextPage = result.hasMore)
    }

    // ============================== تفاصيل الرواية ==============================

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        checkCaptcha(doc)
        return SManga.create().apply {
            title = doc.selectFirst("h1.manga-title")?.text()?.trim() ?: ""
            thumbnail_url = doc.selectFirst(".manga-cover-wrap > img")?.absCover()
            author = doc.selectFirst(".manga-author")?.text()?.trim()
            description = doc.selectFirst(".manga-summary")?.wholeText()?.trim()
            genre = doc.select(".pill-list .pill").joinToString { it.text().trim() }
            val statusText = doc.selectFirst(".manga-status-pill")?.text()?.lowercase() ?: ""
            status = when {
                statusText.contains("complete") || statusText.contains("مكتملة") -> SManga.COMPLETED
                statusText.contains("ongoing") || statusText.contains("جارية") -> SManga.ONGOING
                else -> SManga.ONGOING
            }
        }
    }

    // ============================== الفصول (API + HTML احتياطي) ==============================

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        // جلب الصفحة أولاً لاستخراج manga-id
        val response = client.newCall(GET(baseUrl + manga.url, headers)).execute()
        val doc = response.asJsoup()
        response.close()
        checkCaptcha(doc)

        val mangaId = doc.selectFirst("#manga-chapters-list")?.attr("data-manga-id")
            ?.takeIf { it.isNotBlank() }

        val apiChapters = if (mangaId != null) fetchChaptersViaApi(mangaId) else emptyList()
        return orderChapters(apiChapters.ifEmpty { parseChaptersFromHtml(doc) })
    }

    private fun fetchChaptersViaApi(mangaId: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var page = 1
        while (true) {
            val url = "$baseUrl/wp-json/theam/v1/manga-chapters" +
                    "?manga_id=$mangaId&order=DESC&page=$page&per_page=30"
            val response = client.newCall(GET(url, headers)).execute()
            val body = response.body.string()
            response.close()

            val parsed = try {
                json.decodeFromString<ChaptersResponse>(body)
            } catch (_: Exception) {
                break
            }
            parsed.items.forEach { item ->
                if (item.url.isBlank()) return@forEach
                chapters.add(
                    SChapter.create().apply {
                        name = item.label
                        url = item.url.toRelative()
                        chapter_number = item.num.toChapterNumber()
                        date_upload = parseDate(item.date)
                    }
                )
            }
            if (!parsed.hasMore || parsed.items.isEmpty()) break
            page++
        }
        return chapters
    }

    private fun parseChaptersFromHtml(doc: Document): List<SChapter> =
        doc.select(".ch-row").mapNotNull { row ->
            val link = row.selectFirst("a")?.attr("href")?.ifBlank { null } ?: return@mapNotNull null
            val title = row.selectFirst(".ch-title")?.text()?.trim() ?: link
            SChapter.create().apply {
                name = title
                url = link.toRelative()
                chapter_number = (row.selectFirst(".ch-num")?.text() ?: title).toChapterNumber()
                date_upload = parseDate(row.selectFirst(".ch-date, .ch-row time")?.text())
            }
        }

    override fun chapterListParse(response: Response): List<SChapter> =
        orderChapters(parseChaptersFromHtml(response.asJsoup()))

    private fun orderChapters(chapters: List<SChapter>): List<SChapter> =
        chapters.sortedByDescending { it.chapter_number }

    // ============================== محتوى الفصل ==============================

    override fun pageListParse(response: Response): List<Page> =
        listOf(Page(0, response.request.url.encodedPath))

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = response.asJsoup()
        response.close()
        checkCaptcha(doc)
        val content = doc.selectFirst(".reading-content .text-right")
            ?: doc.selectFirst(".reading-content")
            ?: return ""
        content.select("script, ins, .adsbygoogle, iframe, noscript").remove()
        return content.html().trim()
    }

    override fun imageUrlParse(response: Response): String = ""

    // ============================== دوال مساعدة ==============================

    private fun Response.asJsoup(): Document = Jsoup.parse(body.string(), request.url.toString())

    private fun checkCaptcha(doc: Document) {
        val title = doc.title().trim()
        if (title == "Bot Verification" || title == "Just a moment...") {
            throw Exception("تم اكتشاف كابتشا، يرجى فتح الرابط في متصفح WebView")
        }
    }

    private fun String.toRelative(): String = when {
        startsWith(baseUrl) -> removePrefix(baseUrl)
        startsWith("http://") || startsWith("https://") -> try {
            URI(this).path
        } catch (_: Exception) {
            this
        }
        startsWith("/") -> this
        else -> "/$this"
    }

    private fun org.jsoup.nodes.Element.absCover(): String? {
        val raw = attr("data-src").ifBlank { null }
            ?: attr("data-lazy-src").ifBlank { null }
            ?: attr("src").ifBlank { null }
            ?: return null
        return if (raw.startsWith("http")) raw else baseUrl + raw.toRelative()
    }

    private fun String?.toChapterNumber(): Float {
        if (this.isNullOrBlank()) return -1f
        return CHAPTER_NUM_REGEX.find(this)?.value?.toFloatOrNull() ?: -1f
    }

    private fun parseDate(dateStr: String?): Long {
        val raw = dateStr?.trim().orEmpty()
        if (raw.isBlank()) return 0L
        DATE_FORMATS.forEach { fmt ->
            try {
                return fmt.parse(raw)?.time ?: return@forEach
            } catch (_: Exception) { }
        }
        return 0L
    }

    // ============================== نماذج API ==============================

    @Serializable
    private data class SearchResponse(
        val items: List<SearchItem> = emptyList(),
        @SerialName("has_more") val hasMore: Boolean = false,
    )

    @Serializable
    private data class SearchItem(
        val title: String = "",
        val link: String = "",
        val cover: String = "",
        val genres: List<String> = emptyList(),
        @SerialName("chapters_count") val chaptersCount: Int = 0,
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
        private val CHAPTER_NUM_REGEX = Regex("""\d+(\.\d+)?""")
        private val DATE_FORMATS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
            "dd/MM/yyyy"
        ).map { SimpleDateFormat(it, Locale.US) }
    }
}

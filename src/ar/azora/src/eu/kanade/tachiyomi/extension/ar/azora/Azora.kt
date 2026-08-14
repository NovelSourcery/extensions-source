package eu.kanade.tachiyomi.novelextension.ar.azora

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
import keiyoushi.source.KeiSource
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class Azora :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    private fun buildPopularMangaRequest(page: Int): Request = GET("$baseUrl/novels?page=$page", headers)

    override suspend fun getPopularManga(page: Int): MangasPage = parseNovelsPage(client.newCall(buildPopularMangaRequest(page)).execute())

    private fun buildLatestUpdatesRequest(page: Int): Request = GET("$baseUrl/novels?page=$page", headers)

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseNovelsPage(client.newCall(buildLatestUpdatesRequest(page)).execute())

    private fun buildSearchMangaRequest(page: Int, query: String): Request {
        val term = java.net.URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/novels?search=$term&page=$page", headers)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = parseNovelsPage(client.newCall(buildSearchMangaRequest(page, query)).execute())

    private fun parseNovelsPage(response: Response): MangasPage {
        val doc = response.asJsoup()
        val items = doc.select("a[href*='/series/']").map { el ->
            SManga.create().apply {
                title = el.selectFirst("h3, .title, h2, .novel-title")?.text().orEmpty()
                url = el.attr("href").toRelative()
                thumbnail_url = el.selectFirst("img")?.absCover()
            }
        }
        val hasNext = doc.select("a[rel=next], .pagination a.next, .next-page").isNotEmpty()
        return MangasPage(items.distinctBy { it.url }.filter { it.url.isNotBlank() }, hasNext)
    }

    private fun buildMangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = client.newCall(buildMangaDetailsRequest(manga)).execute().asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) parseChaptersFromHtml(doc).sortedByDescending { it.chapter_number } else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(doc: Document): SManga = SManga.create().apply {
        title = doc.selectFirst("h1")?.text().orEmpty()
        thumbnail_url = doc.selectFirst(".cover img, .poster img")?.absCover()
        author = doc.selectFirst(".author, .novel-author")?.text().orEmpty()
        description = doc.selectFirst(".description, .synopsis, .novel-description")?.text().orEmpty()
        genre = doc.select(".genre, .tag, .pill").joinToString { it.text() }
        status = when (doc.selectFirst(".status, .novel-status")?.text()?.lowercase()) {
            "completed", "مكتملة" -> SManga.COMPLETED
            "ongoing", "مستمرة" -> SManga.ONGOING
            "hiatus", "متوقفة" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChaptersFromHtml(doc: Document): List<SChapter> {
        val rows = doc.select(".ch-row, .chapter-row, .chapter-list a")
        val chapters = rows.mapNotNull { row ->
            val link = row.selectFirst("a[href]")?.attr("href")?.ifBlank { null } ?: return@mapNotNull null
            val name = row.selectFirst(".ch-title, .chapter-title, .title")?.text() ?: row.text()
            val numText = row.selectFirst(".ch-num, .chapter-num, .num")?.text() ?: name
            val dateText = row.selectFirst(".ch-date, .chapter-date, .date")?.text() ?: ""
            SChapter.create().apply {
                this.name = name
                this.url = link.toRelative()
                chapter_number = numText.toChapterNumber()
                date_upload = runCatching { DATE_FORMAT.parse(dateText)?.time }.getOrNull() ?: 0L
            }
        }
        return chapters.distinctBy { it.url }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val response = client.newCall(GET(url, headers)).execute()
        if (!response.isSuccessful) return null
        return parseMangaDetails(response.asJsoup()).apply { this.url = url.encodedPath }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.newCall(GET(baseUrl + page.url, headers)).execute().asJsoup()
        val content = doc.selectFirst(".novel-reader-content, .reading-content, .chapter-content, article")
            ?: doc.selectFirst("main")
            ?: doc.body()

        content.select(
            "script, style, ins, .adsbygoogle, iframe, noscript, " +
                "span.theam-chobf, span[data-theam-chobf], " +
                "[style*=display:none], [style*=visibility:hidden], [aria-hidden=true], " +
                "nav, .nav, .chapter-nav, .prev-next, .navigation, " +
                ".share, .share-buttons, .social-share, " +
                ".comments, .discussion, .comment-section, #comments, #discussion, " +
                "footer, .footer, .site-footer, " +
                ".reactions, .emoji-reactions, .reaction-buttons, " +
                ".related-posts, .recommended, .suggested, " +
                ".leaderboard, .top-readers, " +
                "header, .header, .site-header, " +
                "aside, .sidebar, " +
                ".purchase, .premium, .unlock, " +
                "form, .login-form, .signup-form",
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

    companion object {
        private val CHAPTER_NUM_REGEX = Regex("""\d+(\.\d+)?""")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}

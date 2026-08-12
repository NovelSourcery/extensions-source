package eu.kanade.tachiyomi.novelextension.ar.azora

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

class Azora :
    HttpSource(),
    NovelSource {

    override val name = "Azora"
    override val baseUrl = "https://azorafly.com"
    override val lang = "ar"
    override val supportsLatest = true
    override val isNovelSource = true
    override val client = network.client

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/novels?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseNovelsPage(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/novels?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseNovelsPage(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val term = java.net.URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/novels?search=$term&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseNovelsPage(response)

    private fun parseNovelsPage(response: Response): MangasPage {
        val doc = response.asJsoup()
        val items = doc.select("a[href*='/series/']").map { el ->
            SManga.create().apply {
                title = el.selectFirst("h3, .title, h2, .novel-title")?.text()?.trim().orEmpty()
                url = el.attr("href").toRelative()
                thumbnail_url = el.selectFirst("img")?.absCover()
            }
        }
        val hasNext = doc.select("a[rel=next], .pagination a.next, .next-page").isNotEmpty()
        return MangasPage(items.distinctBy { it.url }.filter { it.url.isNotBlank() }, hasNext)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst(".cover img, .poster img")?.absCover()
            author = doc.selectFirst(".author, .novel-author")?.text()?.trim().orEmpty()
            description = doc.selectFirst(".description, .synopsis, .novel-description")?.text()?.trim().orEmpty()
            genre = doc.select(".genre, .tag, .pill").joinToString { it.text().trim() }
            status = when (doc.selectFirst(".status, .novel-status")?.text()?.trim()?.lowercase()) {
                "completed", "مكتملة" -> SManga.COMPLETED
                "ongoing", "مستمرة" -> SManga.ONGOING
                "hiatus", "متوقفة" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        return parseChaptersFromHtml(doc).sortedByDescending { it.chapter_number }
    }

    private fun parseChaptersFromHtml(doc: Document): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        val rows = doc.select(".ch-row, .chapter-row, .chapter-list a")
        for (row in rows) {
            val link = row.selectFirst("a[href]")?.attr("href")?.ifBlank { null } ?: continue
            val name = row.selectFirst(".ch-title, .chapter-title, .title")?.text()?.trim() ?: row.text().trim()
            val numText = row.selectFirst(".ch-num, .chapter-num, .num")?.text() ?: name
            val dateText = row.selectFirst(".ch-date, .chapter-date, .date")?.text() ?: ""
            chapters.add(
                SChapter.create().apply {
                    this.name = name
                    this.url = link.toRelative()
                    chapter_number = numText.toChapterNumber()
                    date_upload = runCatching { DATE_FORMAT.parse(dateText)?.time }?.getOrNull() ?: 0L
                },
            )
        }
        return chapters.distinctBy { it.url }
    }

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.encodedPath))

    override suspend fun fetchPageText(page: Page): String {
        val url = baseUrl + page.url
        val response = client.newCall(GET(url, headers)).execute()
        val doc = response.asJsoup()
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

    override fun imageUrlParse(response: Response): String = ""

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

    companion object {
        private val CHAPTER_NUM_REGEX = Regex("""\d+(\.\d+)?""")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}

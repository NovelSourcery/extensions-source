package eu.kanade.tachiyomi.multisrc.lightnovelwp

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.formattedText
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * LightNovelWP multisrc base class.
 * Ported from LNReader TypeScript plugin.
 *
 * Sites using this template:
 * - novelsknight.com
 * - arcanetranslations.com
 * - various other WordPress-based light novel sites
 */
abstract class LightNovelWP(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
) : HttpSource(),
    NovelSource {

    // isNovelSource is provided by NovelSource interface with default value true

    override val supportsLatest = true

    override val client = network.cloudflareClient

    // Configuration options - can be overridden by child classes
    protected open val seriesPath: String = "/series/"
    protected open val reverseChapters: Boolean = false
    protected open val hasLocked: Boolean = false

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl$seriesPath".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("order", "popular")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = mangaListParse(response, popularMangaSelector(), popularMangaNextPageSelector(), ::popularMangaFromElement)

    protected fun mangaListParse(
        response: Response,
        selector: String,
        nextPageSelector: String,
        fromElement: (Element) -> SManga,
    ): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(selector).map { fromElement(it) }
        val hasNextPage = document.selectFirst(nextPageSelector) != null
        return MangasPage(mangas, hasNextPage)
    }

    protected open fun popularMangaSelector() = "article"

    protected open fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("a[title], a[href*='/novel/']")
        if (link != null) {
            title = link.attr("title").ifEmpty { link.text() }.trim()
            val href = link.attr("abs:href")
            if (href.isNotBlank()) {
                setUrlWithoutDomain(href)
            }
        }
        thumbnail_url = element.selectFirst("img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }
    }

    protected open fun popularMangaNextPageSelector() = "a.next.page-numbers, .pagination .next"

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl$seriesPath".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("order", "latest")
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = mangaListParse(response, latestUpdatesSelector(), latestUpdatesNextPageSelector(), ::latestUpdatesFromElement)

    protected open fun latestUpdatesSelector() = popularMangaSelector()

    protected open fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    protected open fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/page/$page/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = mangaListParse(response, searchMangaSelector(), searchMangaNextPageSelector(), ::searchMangaFromElement)

    protected open fun searchMangaSelector() = popularMangaSelector()

    protected open fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    protected open fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // ======================== Details ========================

    override fun mangaDetailsParse(response: Response): SManga = mangaDetailsParse(response.asJsoup())

    protected open fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        // Get cover and title from image
        document.selectFirst("img.ts-post-image, .thumb img")?.let {
            thumbnail_url = it.attr("data-src").ifEmpty { it.attr("src") }
            title = it.attr("title").ifEmpty { title }
        }

        // Get genres
        document.selectFirst(".genxed, .sertogenre")?.let { genreElement ->
            genre = genreElement.select("a").joinToString { it.text().trim() }
        }

        // Get summary
        description = document.selectFirst(".entry-content, div[itemprop=description]")?.formattedText()

        // Parse info section for author/status
        document.select(".spe span, .serl span, .sertostat").forEach { element ->
            val text = element.text().lowercase()
            when {
                text.contains("author") || text.contains("autor") -> {
                    author = element.ownText().substringAfter(":").trim()
                        .ifEmpty { element.selectFirst("a")?.text()?.trim() }
                }

                text.contains("artist") -> {
                    artist = element.ownText().substringAfter(":").trim()
                        .ifEmpty { element.selectFirst("a")?.text()?.trim() }
                }

                text.contains("status") -> {
                    status = parseStatus(element.ownText().substringAfter(":").trim())
                }
            }
        }
    }

    private fun parseStatus(status: String): Int = when {
        status.contains("Ongoing", ignoreCase = true) ||
            status.contains("en cours", ignoreCase = true) ||
            status.contains("em andamento", ignoreCase = true) -> SManga.ONGOING

        status.contains("Completed", ignoreCase = true) ||
            status.contains("complété", ignoreCase = true) ||
            status.contains("completo", ignoreCase = true) -> SManga.COMPLETED

        status.contains("Hiatus", ignoreCase = true) ||
            status.contains("en pause", ignoreCase = true) -> SManga.ON_HIATUS

        status.contains("Dropped", ignoreCase = true) ||
            status.contains("Cancelled", ignoreCase = true) -> SManga.CANCELLED

        else -> SManga.UNKNOWN
    }

    // ======================== Chapters ========================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        val chapters = document.select(".eplister li").mapIndexed { index, element ->
            SChapter.create().apply {
                element.selectFirst("a")?.let {
                    setUrlWithoutDomain(it.attr("abs:href"))
                }

                // LightNovelWP HTML structure:
                // <li data-ID="...">
                //   <a href="...">
                //     <div class="epl-num"><span>Ch. 50</span><span><i class="fas fa-lock"></i></span></div>
                //     <div class="epl-title">Chapter Title</div>
                //     <div class="epl-date">March 16, 2026</div>
                //   </a>
                // </li>

                // Get chapter number from first span inside epl-num
                val eplNumDiv = element.selectFirst(".epl-num")
                val chapterNumText = eplNumDiv?.selectFirst("span")?.text()?.trim()
                    ?: eplNumDiv?.ownText()?.trim()
                val chapterTitle = element.selectFirst(".epl-title")?.text()?.trim()
                val releaseDate = element.selectFirst(".epl-date")?.text()?.trim()
                val price = element.selectFirst(".epl-price")?.text()?.trim()

                // Check if chapter is locked - look for lock icon or emoji
                val isLocked = element.selectFirst(".fa-lock, .fas.fa-lock, i[class*='lock']") != null ||
                    eplNumDiv?.text()?.contains("🔒") == true ||
                    (price != null && price.lowercase() != "free" && price.isNotEmpty())

                // Build chapter name
                name = buildString {
                    if (isLocked) append("🔒 ")
                    if (chapterTitle != null) {
                        append(chapterTitle)
                    } else if (chapterNumText != null) {
                        append("Chapter $chapterNumText")
                    } else {
                        append("Chapter ${index + 1}")
                    }
                }

                // Extract chapter number from text like "Ch. 50" or "50"
                val numMatch = chapterNumText?.let { Regex("""(\d+(?:\.\d+)?)""").find(it)?.groupValues?.get(1) }
                chapter_number = numMatch?.toFloatOrNull() ?: (index + 1).toFloat()

                // Parse date if available
                date_upload = releaseDate?.let { parseDate(it) } ?: 0L
            }
        }

        return if (reverseChapters) chapters.reversed() else chapters
    }

    private fun parseDate(dateStr: String): Long = try {
        DATE_FORMAT.parse(dateStr)?.time ?: 0L
    } catch (e: Exception) {
        0L
    }

    // ======================== Pages ========================

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.encodedPath))

    override fun imageUrlParse(response: Response): String = ""

    // ======================== Novel Content ========================

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(if (page.url.startsWith("http")) page.url else baseUrl + page.url, headers)).execute()
        val document = response.asJsoup()

        // Get content from epcontent div
        val content = document.selectFirst(".epcontent.entry-content, .epcontent")
            ?: return ""

        // Remove unwanted elements
        content.select(".bottomnav, script, ins, .adsbygoogle, .ads").remove()

        // Get paragraph content
        return content.select("p").joinToString("\n\n") { it.html() }
    }

    // ======================== Filters ========================

    override fun getFilterList(): FilterList = FilterList(
        OrderFilter(),
        StatusFilter(),
    )

    protected class OrderFilter :
        Filter.Select<String>(
            "Order by",
            arrayOf("Popular", "Latest", "A-Z", "Rating"),
            0,
        )

    protected class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("All", "Ongoing", "Completed", "Hiatus"),
            0,
        )

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("MMMM dd, yyyy", Locale.US)
    }
}

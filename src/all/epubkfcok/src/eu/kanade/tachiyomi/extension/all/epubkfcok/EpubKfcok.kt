package eu.kanade.tachiyomi.extension.all.epubkfcok

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class EpubKfcok :
    HttpSource(),
    NovelSource {

    override val name = "EPUB KFCok"
    override val baseUrl = "https://epub.kfcok.net"
    override val lang = "all"
    override val supportsLatest = true
    override val isNovelSource = true

    override val client = network.cloudflareClient

    private var cachedTotalPages: Int = 0

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())

        parseTotalPages(document)

        val novels = document.select("div.card").mapNotNull { parseNovelCard(it) }
        val hasNextPage = hasNextPage(document)

        return MangasPage(novels, hasNextPage)
    }

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = StringBuilder("$baseUrl/?page=$page")

        if (query.isNotBlank()) {
            url.append("&q=${java.net.URLEncoder.encode(query, "UTF-8")}")
        }

        filters.forEach { filter ->
            when (filter) {
                is TagFilter -> {
                    val tags = filter.state.split(",")
                        .map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() }
                    tags.forEach { tag ->
                         url.append("&tags[]=$tag")
                    }
                }

                is InversePaginationFilter -> {
                    if (filter.state && cachedTotalPages > 0) {
                        // Calculate inverse page
                        val inversePage = cachedTotalPages - page + 1
                        if (inversePage > 0) {
                            return GET(url.toString().replace("page=$page", "page=$inversePage"), headers)
                        }
                    }
                }

                else -> {}
            }
        }

        return GET(url.toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            // Title - Try multiple selectors
            title = document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst("strong.book-title")?.text()?.trim()
                ?: ""

            // Cover image
            thumbnail_url = document.selectFirst("div.cover")?.let { cover ->
                // Check for img tag first
                cover.selectFirst("img")?.attr("src")?.let { imgSrc ->
                    if (imgSrc.startsWith("http")) imgSrc else "$baseUrl$imgSrc"
                } ?: cover.attr("style")?.let { style ->
                    extractCoverUrl(style)
                }
            }

            // Description
            description = document.select("section.description-box .description, section.box.description-box .description, div.description")
                .firstOrNull()?.text()?.trim() ?: ""

            // Tags/Genres
            genre = document.select("div.tags a, .tags-box a")
                .mapNotNull { it.text()?.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")

            // Author
            author = document.selectFirst("ul.info-list li:contains(Author)")?.let {
                it.text().replace("Author:", "").trim()
            }

            // Status
            val statusText = document.selectFirst("ul.info-list li:contains(Status)")?.text()?.lowercase() ?: ""
            status = when {
                statusText.contains("completed") -> SManga.COMPLETED
                statusText.contains("ongoing") -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string())
        val chapters = mutableListOf<SChapter>()

        // Try multiple chapter list selectors
        document.select("ul.chapter-list li, section.chapters-box ul li, div.chapter-list div.chapter-item").forEach { item ->
            val link = item.selectFirst("a") ?: return@forEach

            // Extract chapter number from various possible attributes/text
            val chapterNum = item.attr("data-ch").toIntOrNull()
                ?: link.attr("data-ch").toIntOrNull()
                ?: link.text().replace(Regex("[^0-9]"), "").toIntOrNull()
                ?: chapters.size + 1

            // Get chapter title/name
            val chapterName = link.text()?.trim() ?: "Chapter $chapterNum"

            // Get chapter URL
            val chapterUrl = link.attr("href").let { href ->
                when {
                    href.startsWith("http") -> href.replace(baseUrl, "")
                    href.startsWith("/") -> href
                    else -> "/$href"
                }
            }

            chapters.add(
                SChapter.create().apply {
                    url = chapterUrl
                    name = chapterName
                    chapter_number = chapterNum.toFloat()

                    // Try to get date if available
                    item.selectFirst("span.date, time")?.text()?.let { dateStr ->
                        date_upload = parseDate(dateStr)
                    }
                },
            )
        }

        return chapters.reversed()
    }

    // ======================== Pages ========================

    override fun pageListRequest(chapter: SChapter): Request {
        val url = if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

    override fun fetchPageList(chapter: SChapter): rx.Observable<List<Page>> = rx.Observable.just(listOf(Page(0, if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url)))

    // ======================== Page Text (Novel) ========================
    override suspend fun fetchPageText(page: Page): String {
        val request = GET(page.url, headers)
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body.string())

        // Try multiple content selectors (adjust based on actual site structure)
        val contentContainer = document.selectFirst(
            "div.reader, div.chapter-content, div.content, div.chapter-body",
        ) ?: return ""

        // Remove unwanted elements that are not part of the novel content
        contentContainer.select(
            "script, style, iframe, .ads, .advertisement, " +
                ".toolbar, .topbar, .reader-controls, .toolbar-actions, " +
                ".chapter-meta, .back-link, .chapter-kicker, .chapter-title, " +
                ".chapter-subtitle, button, form, input, nav, .pagination",
        ).remove()

        // Convert relative image URLs to absolute so they load correctly
        contentContainer.select("img").forEach { img ->
            val src = img.attr("src")
            if (src.isNotBlank() && !src.startsWith("http")) {
                val absoluteSrc = if (src.startsWith("//")) "https:$src" else "$baseUrl$src"
                img.attr("src", absoluteSrc)
            }
        }

        // Return the cleaned inner HTML (preserves all tags, including images)
        return contentContainer.html()
    }
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // ======================== Filters ========================

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Enter tags separated by commas"),
        TagFilter("Tags"),
        Filter.Separator(),
        InversePaginationFilter("Inverse Pagination (newest first)"),
    )

    class TagFilter(name: String) : Filter.Text(name)
    class InversePaginationFilter(name: String) : Filter.CheckBox(name, false)

    // ======================== Helpers ========================

    private fun parseNovelCard(card: Element): SManga? {
        val link = card.selectFirst("a.card-link") ?: return null
        val href = link.attr("href")

        return SManga.create().apply {
            url = when {
                href.startsWith("http") -> href.replace(baseUrl, "")
                href.startsWith("/") -> href
                else -> "/$href"
            }

            // Title from strong.book-title
            title = card.selectFirst("strong.book-title")?.text()?.trim() ?: ""

            // Cover handling
            val coverDiv = card.selectFirst("div.cover")
            thumbnail_url = coverDiv?.let { cover ->
                // Check for img tag first
                cover.selectFirst("img")?.attr("src")?.let { imgSrc ->
                    if (imgSrc.startsWith("http")) imgSrc else "$baseUrl$imgSrc"
                } ?: cover.attr("style")?.let { style ->
                    extractCoverUrl(style)
                }
            }

            // Tags
            genre = card.select("div.tags a")
                .mapNotNull { it.text()?.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")

            // Check if it has image chapters
            val hasImageChapters = card.select("span.book-flag.image-chapters").isNotEmpty()
            if (hasImageChapters) {
                // Add flag to description or status
                description = "Contains image chapters"
            }
        }
    }

    private fun extractCoverUrl(style: String): String? {
        val match = Regex("""url\(['"]?([^)'"]+)['"]?\)""").find(style)
        val path = match?.groupValues?.getOrNull(1) ?: return null

        return when {
            path.startsWith("http") -> path
            path.startsWith("//") -> "https:$path"
            path.startsWith("/") -> "$baseUrl$path"
            else -> "$baseUrl/$path"
        }
    }

    private fun parseTotalPages(document: Document) {
        val paginationLinks = document.select("div.pagination a")
        if (paginationLinks.isNotEmpty()) {
            val maxPage = paginationLinks.mapNotNull { it.text().toIntOrNull() }.maxOrNull() ?: 1
            cachedTotalPages = maxPage
        }
    }

    private fun hasNextPage(document: Document): Boolean {
        val currentPage = document.selectFirst("div.pagination a.active")?.text()?.toIntOrNull() ?: 1
        return currentPage < cachedTotalPages
    }

    private fun parseDate(dateStr: String): Long {
        return try {
            val formats = listOf(
                SimpleDateFormat("yyyy-MM-dd", Locale.US),
                SimpleDateFormat("MM/dd/yyyy", Locale.US),
                SimpleDateFormat("dd MMM yyyy", Locale.US),
            )

            for (format in formats) {
                try {
                    return format.parse(dateStr)?.time ?: 0
                } catch (e: Exception) {
                    // Continue to next format
                }
            }
            0
        } catch (e: Exception) {
            0
        }
    }
}

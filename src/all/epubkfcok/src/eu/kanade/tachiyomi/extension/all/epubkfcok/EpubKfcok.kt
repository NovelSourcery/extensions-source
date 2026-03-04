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
            // Title from h1
            title = document.selectFirst("h1")?.text()?.trim() ?: ""

            // Cover image from .cover div background-image
            thumbnail_url = document.selectFirst("div.cover")?.attr("style")?.let { style ->
                extractCoverUrl(style)
            }

            // Description from .description div
            description = document.select("section.description-box .description, section.box.description-box .description")
                .firstOrNull()?.text()?.trim() ?: ""

            genre = document.select("div.tags a, .tags-box a")
                .mapNotNull { it.text()?.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")

            // Author from info list
            author = document.selectFirst("ul.info-list li:contains(Author) strong")?.nextSibling()?.toString()?.trim()
                ?: document.selectFirst("ul.info-list li:contains(Author)")?.text()?.replace("Author:", "")?.trim()

            // Status
            val statusText = document.selectFirst("ul.info-list li:contains(Status)")?.text()?.lowercase() ?: ""
            status = when {
                statusText.contains("completed") -> SManga.COMPLETED
                statusText.contains("ongoing") -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }

            if (status == SManga.UNKNOWN) {
                val badgeText = document.selectFirst(".status-badge")?.text()?.lowercase() ?: ""
                status = when {
                    badgeText.contains("completed") -> SManga.COMPLETED
                    badgeText.contains("ongoing") -> SManga.ONGOING
                    else -> SManga.UNKNOWN
                }
            }
        }
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string())
        val chapters = mutableListOf<SChapter>()

        document.select("ul.chapter-list li, section.chapters-box ul li").forEach { li ->
            val link = li.selectFirst("a") ?: return@forEach
            val chapterNum = li.attr("data-ch").toIntOrNull()
                ?: link.text().replace(Regex("[^0-9]"), "").toIntOrNull()
                ?: chapters.size + 1

            chapters.add(
                SChapter.create().apply {
                    url = link.attr("href").let { href ->
                        if (href.startsWith("http")) {
                            href.removePrefix(baseUrl)
                        } else if (href.startsWith("/")) {
                            href
                        } else {
                            "/$href"
                        }
                    }
                    name = link.text()?.trim() ?: "Chapter $chapterNum"
                    chapter_number = chapterNum.toFloat()
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

        val reader = document.selectFirst("div.reader") ?: return ""

        val content = StringBuilder()

        reader.selectFirst("h1")?.let { h1 ->
            content.append("<h1>${h1.text()}</h1>\n")
        }

        reader.select("p").forEach { p ->
            val text = p.text()?.trim()
            if (!text.isNullOrEmpty()) {
                content.append("<p>$text</p>\n")
            }
        }

        if (content.isEmpty()) {
            val text = reader.text()?.trim() ?: ""
            return "<p>$text</p>"
        }

        return content.toString()
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
            url = if (href.startsWith("http")) {
                href.removePrefix(baseUrl)
            } else if (href.startsWith("/")) {
                href
            } else {
                "/$href"
            }
            title = card.selectFirst("div.info strong")?.text()?.trim() ?: ""

            val coverDiv = card.selectFirst("div.cover")
            thumbnail_url = coverDiv?.attr("style")?.let { style ->
                extractCoverUrl(style)
            }

            genre = card.select("div.tags a")
                .mapNotNull { it.text()?.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")
        }
    }

    private fun extractCoverUrl(style: String): String? {
        val match = Regex("""url\(([^)]+)\)""").find(style)
        val path = match?.groupValues?.getOrNull(1) ?: return null

        return when {
            path.startsWith("http") -> path
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
}

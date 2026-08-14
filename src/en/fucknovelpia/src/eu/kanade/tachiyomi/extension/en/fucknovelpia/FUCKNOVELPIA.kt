package eu.kanade.tachiyomi.novelextension.en.fucknovelpia

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class FUCKNOVELPIA :
    KeiSource(),
    NovelSource {

    override val supportsLatest = false

    private var cachedTotalPages: Int = 0

    /** [SManga.url] is stored as the bare slug under `/novel/`; a stored value starting with
     * "/" is a pre-existing full-path entry and is resolved unchanged. */
    private val mangaPath = SlugPath("/novel/")

    // ======================== Popular ========================

    private fun buildPopularMangaRequest(page: Int): Request = GET("$baseUrl/?page=$page", headers)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = Jsoup.parse(client.newCall(buildPopularMangaRequest(page)).execute().body.string())

        parseTotalPages(document)

        val novels = document.select("div.card").mapNotNull { parseNovelCard(it) }
        val hasNextPage = hasNextPage(document)

        return MangasPage(novels, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // ======================== Search ========================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        var request: Request? = null

        val url = buildString {
            append("$baseUrl/?page=$page")

            if (query.isNotBlank()) {
                append("&q=${java.net.URLEncoder.encode(query, "UTF-8")}")
            }

            filters.forEach { filter ->
                when (filter) {
                    is TagFilter -> {
                        val tags = filter.state.split(",")
                            .map { it.trim().lowercase() }
                            .filter { it.isNotEmpty() }
                        tags.forEach { tag ->
                            append("&tags[]=$tag")
                        }
                    }

                    is InversePaginationFilter -> {
                        if (filter.state && cachedTotalPages > 0) {
                            // Calculate inverse page
                            val inversePage = cachedTotalPages - page + 1
                            if (inversePage > 0) {
                                request = GET(toString().replace("page=$page", "page=$inversePage"), headers)
                            }
                        }
                    }

                    else -> {}
                }
            }
        }

        val document = Jsoup.parse(client.newCall(request ?: GET(url, headers)).execute().body.string())
        parseTotalPages(document)
        val novels = document.select("div.card").mapNotNull { parseNovelCard(it) }
        return MangasPage(novels, hasNextPage(document))
    }

    // ======================== Details + Chapters ========================

    private fun buildMangaDetailsRequest(manga: SManga): Request = GET(baseUrl + mangaPath.resolve(manga.url), headers)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = Jsoup.parse(client.newCall(buildMangaDetailsRequest(manga)).execute().body.string())

        val updatedManga = if (fetchDetails) parseMangaDetails(document) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(document) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        // Title - Try multiple selectors
        title = document.selectFirst("h1")?.text()
            ?: document.selectFirst("strong.book-title")?.text()
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
            .firstOrNull()?.text() ?: ""

        // Tags/Genres
        genre = document.select("div.tags a, .tags-box a")
            .mapNotNull { it.text() }
            .filter { it.isNotEmpty() }
            .joinToString()

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

    private fun parseChapterList(document: Document): List<SChapter> {
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
            val chapterName = link.text() ?: "Chapter $chapterNum"

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

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPath.resolve(manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        val response = client.newCall(GET(baseUrl + path, headers)).execute()
        if (!response.isSuccessful) return null
        val document = response.asJsoup()
        return parseMangaDetails(document).apply { this.url = mangaPath.slug(path) }
    }

    // ======================== Pages ========================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url
        val response = client.newCall(GET(url, headers)).execute()
        return listOf(Page(0, response.request.url.toString()))
    }

    // ======================== Page Text (Novel) ========================
    override suspend fun fetchPageText(page: Page): String {
        val request = GET(if (page.url.startsWith("http")) page.url else baseUrl + page.url, headers)
        val response = client.newCall(request).execute()
        val document = response.asJsoup()

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

    // ======================== Filters ========================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
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
            val relativeUrl = when {
                href.startsWith("http") -> href.replace(baseUrl, "")
                href.startsWith("/") -> href
                else -> "/$href"
            }
            url = mangaPath.slug(relativeUrl)

            // Title from strong.book-title
            title = card.selectFirst("strong.book-title")?.text() ?: ""

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
                .mapNotNull { it.text() }
                .filter { it.isNotEmpty() }
                .joinToString()

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

package eu.kanade.tachiyomi.extension.en.novelable

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

class Novelable : HttpSource(), NovelSource {

    override val name = "Novelable"
    override val baseUrl = "https://novelable.com"
    override val lang = "en"
    override val supportsLatest = true
    override val isNovelSource = true

    override val client = network.cloudflareClient

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/top/popular?page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())
        val novels = parseSearchResults(document)
        // Check for next page using paginator
        val hasNextPage = document.selectFirst("div.paginator a.link:not(.active)") != null
        return MangasPage(novels.mangas, hasNextPage)
    }

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/latest?page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/search?query=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page", headers)
        }

        // Build URL with filters
        val url = StringBuilder(baseUrl)

        filters.forEach { filter ->
            when (filter) {
                is CategoryFilter -> {
                    if (filter.state > 0) {
                        val category = categoryValues[filter.state]
                        url.clear()
                        url.append("$baseUrl/genre/$category")
                    }
                }
                else -> {}
            }
        }

        if (url.toString() == baseUrl) {
            url.append("/search")
        }

        url.append(if (url.contains("?")) "&page=$page" else "?page=$page")

        return GET(url.toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            url = response.request.url.encodedPath

            // Title from h1 or meta
            title = document.selectFirst("h1.novel-title, .novel-header h1")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: ""
            // remove | Novelable suffix
            title = title.substringBefore(" | Novelable").trim()

            // Cover image
            thumbnail_url = document.selectFirst(".novel-cover img, .cover img")?.absUrl("src")
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")

            // Parse from div.meta.box structure
            val metaBox = document.selectFirst("div.meta.box, div.meta")

            // Author from meta box
            author = metaBox?.selectFirst("p:has(strong:contains(Author)) a span")?.text()
                ?: document.selectFirst(".novel-author a, .author a")?.text()

            // Multiple authors
            val authors = metaBox?.select("p:has(strong:contains(Author)) a span")?.map { it.text() }
            if (!authors.isNullOrEmpty() && authors.size > 1) {
                author = authors.joinToString(", ")
            }

            // Description from summary section
            description = document.selectFirst("div.summary p.content")?.text()
                ?: document.selectFirst(".novel-synopsis, .novel-description, .description")?.text()

            // Genre/Tags from meta box
            genre = metaBox?.select("p:has(strong:contains(Genre)) a span")?.map { it.text() }?.joinToString(", ")
                ?: document.select(".novel-genres a, .genres a, .tags a").map { it.text() }.joinToString(", ")

            // Status from meta box
            val statusText = metaBox?.selectFirst("p:has(strong:contains(Status)) a span")?.text()?.lowercase()
                ?: document.selectFirst(".novel-status, .status")?.text()?.lowercase()
                ?: ""
            status = when {
                statusText.contains("ongoing") -> SManga.ONGOING
                statusText.contains("completed") -> SManga.COMPLETED
                statusText.contains("hiatus") -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string())

        // Try to find book ID (UUID format) from various sources
        var bookId: String? = null

        // 1. From inline script: var bookId = "6b5d37da-798a-4d10-8917-c877529473d5"
        document.select("script:not([src])").forEach { script ->
            val content = script.data()
            val bookIdMatch = Regex("""var\s+bookId\s*=\s*["']([a-f0-9-]{36})["']""").find(content)
            if (bookIdMatch != null) {
                bookId = bookIdMatch.groupValues[1]
                return@forEach
            }
        }

        // 2. From dropdown button id like btn-dropdown-e4e33d0b-758c-440f-a58d-d7d3e0be866e
        if (bookId == null) {
            document.select("[id*=btn-dropdown-]").forEach { element ->
                val id = element.attr("id").substringAfter("btn-dropdown-")
                if (id.matches(Regex("[a-f0-9-]{36}"))) {
                    bookId = id
                    return@forEach
                }
            }
        }

        // 3. From data attributes
        if (bookId == null) {
            bookId = document.selectFirst("[data-book-id]")?.attr("data-book-id")
                ?: document.selectFirst("[data-novel-id]")?.attr("data-novel-id")
        }

        // 4. From other inline scripts
        if (bookId == null) {
            bookId = extractBookIdFromScript(document)
        }

        // If we have a valid UUID book ID, fetch chapters from API
        if (!bookId.isNullOrEmpty() && bookId!!.matches(Regex("[a-f0-9-]{36}"))) {
            try {
                val chaptersRequest = GET("$baseUrl/api/book/$bookId/chapters", headers)
                val chaptersResponse = client.newCall(chaptersRequest).execute()
                if (chaptersResponse.isSuccessful) {
                    val chaptersHtml = chaptersResponse.body.string()
                    val chaptersDoc = Jsoup.parse(chaptersHtml)
                    val chapters = parseChapterList(chaptersDoc)
                    if (chapters.isNotEmpty()) {
                        return chapters
                    }
                }
            } catch (_: Exception) {
                // Fall through to HTML parsing
            }
        }

        // Fallback to parsing from page HTML
        return parseChapterList(document)
    }

    private fun parseChapterList(document: Document): List<SChapter> {
        // Parse chapter list from ul.chapter-list or #chapter-list
        return document.select("ul.chapter-list li, #chapter-list li, .chapter-list li").mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null

            SChapter.create().apply {
                url = link.attr("href").let {
                    if (it.startsWith("http")) java.net.URL(it).path else it
                }
                name = link.selectFirst(".chapter-title, strong")?.text()
                    ?: link.attr("title").takeIf { it.isNotEmpty() }
                    ?: link.text()

                // Parse date from time element
                date_upload = element.selectFirst("time.chapter-update, time")?.text()?.let {
                    parseRelativeDate(it)
                } ?: 0L

                // Try to extract chapter number from li id
                chapter_number = element.attr("id").toFloatOrNull()
                    ?: extractChapterNumber(name)
            }
        }
        // No need to reverse - chapters are already in correct order (1, 2, 3...)
    }

    // ======================== Pages ========================

    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        return listOf(Page(0, response.request.url.toString()))
    }

    // ======================== Page Text (Novel) ========================

    override suspend fun fetchPageText(page: Page): String {
        val request = GET(page.url, headers)
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body.string())

        val content = StringBuilder()

        // Parse chapter title - check specific selectors first
        document.selectFirst("h2.text-center, h3.text-center, .chapter__content h2, h2, .chapter-title")?.let { title ->
            content.append("<h2>${title.text()}</h2>\n")
        }

        // Parse chapter content from div.chapter__content .content-inner (as shown in MD)
        val contentDiv = document.selectFirst(
            ".chapter__content .content-inner, .chapter__content, #chapter__content .content-inner, #chapter__content, .chapter-content, .reading-content",
        )

        contentDiv?.let { div ->
            // First, try to parse all <p> elements directly
            val paragraphs = div.select("p")
            if (paragraphs.isNotEmpty()) {
                paragraphs.forEach { p ->
                    val text = p.text()?.trim()
                    if (!text.isNullOrEmpty()) {
                        content.append("<p>$text</p>\n")
                    }
                }
            } else {
                // Fallback: parse child elements
                div.children().forEach { element ->
                    when (element.tagName()) {
                        "p" -> {
                            val text = element.text()?.trim()
                            if (!text.isNullOrEmpty()) {
                                content.append("<p>$text</p>\n")
                            }
                        }
                        "h1", "h2", "h3", "h4" -> {
                            content.append("<h3>${element.text()}</h3>\n")
                        }
                        "img" -> {
                            val src = element.absUrl("src")
                            if (src.isNotEmpty()) {
                                content.append("<img src=\"$src\">\n")
                            }
                        }
                        "div" -> {
                            // Skip ad divs and script containers
                            if (!element.hasClass("adsbygoogle") && !element.attr("id").contains("ad", ignoreCase = true) &&
                                !element.attr("id").startsWith("pf-")
                            ) {
                                // Recursively get text from paragraphs inside this div
                                element.select("p").forEach { p ->
                                    val text = p.text()?.trim()
                                    if (!text.isNullOrEmpty()) {
                                        content.append("<p>$text</p>\n")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return content.toString()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // ======================== Filters ========================

    override fun getFilterList(): FilterList {
        return FilterList(
            Filter.Header("Category (cannot combine with search)"),
            CategoryFilter("Category", categoryNames),
        )
    }

    class CategoryFilter(name: String, values: Array<String>) : Filter.Select<String>(name, values)

    private val categoryNames = arrayOf(
        "All",
        "Action",
        "Adventure",
        "Comedy",
        "Drama",
        "Eastern",
        "Ecchi",
        "Fantasy",
        "Gender Bender",
        "Harem",
        "Historical",
        "Horror",
        "Josei",
        "Martial Arts",
        "Mature",
        "Mecha",
        "Mystery",
        "Psychological",
        "Romance",
        "School Life",
        "Sci-fi",
        "Seinen",
        "Shoujo",
        "Shoujo Ai",
        "Shounen",
        "Shounen Ai",
        "Slice of Life",
        "Smut",
        "Sports",
        "Supernatural",
        "Tragedy",
        "Wuxia",
        "Xianxia",
        "Xuanhuan",
        "Yaoi",
        "Yuri",
    )

    private val categoryValues = arrayOf(
        "",
        "action",
        "adventure",
        "comedy",
        "drama",
        "eastern",
        "ecchi",
        "fantasy",
        "gender-bender",
        "harem",
        "historical",
        "horror",
        "josei",
        "martial-arts",
        "mature",
        "mecha",
        "mystery",
        "psychological",
        "romance",
        "school-life",
        "sci-fi",
        "seinen",
        "shoujo",
        "shoujo-ai",
        "shounen",
        "shounen-ai",
        "slice-of-life",
        "smut",
        "sports",
        "supernatural",
        "tragedy",
        "wuxia",
        "xianxia",
        "xuanhuan",
        "yaoi",
        "yuri",
    )

    // ======================== Helpers ========================

    private fun parseSearchResults(document: Document): MangasPage {
        val novels = document.select(".novel-item, .book-item, article.novel").map { element ->
            SManga.create().apply {
                val link = element.selectFirst("a[href*='/novel/']")
                    ?: element.selectFirst("a.novel-title, h3 a, h2 a")
                    ?: element.selectFirst("a")

                url = link?.attr("href")?.let {
                    if (it.startsWith("http")) java.net.URL(it).path else it
                } ?: ""

                title = link?.attr("title")?.takeIf { it.isNotEmpty() }
                    ?: link?.text()
                    ?: element.selectFirst("h3, h2, .title")?.text()
                    ?: ""

                thumbnail_url = element.selectFirst("img")?.absUrl("src")
                    ?: element.selectFirst("img")?.absUrl("data-src")

                author = element.selectFirst(".author, .novel-author")?.text()

                genre = element.select(".genre, .tag, .category").map { it.text() }.joinToString(", ")
            }
        }

        val hasNextPage = document.selectFirst(".pagination .next, a[rel=next], .page-next") != null

        return MangasPage(novels, hasNextPage)
    }

    private fun extractBookIdFromScript(document: Document): String? {
        // Try to extract book ID from inline scripts
        val scripts = document.select("script:not([src])")
        for (script in scripts) {
            val content = script.data()
            // Look for patterns like book_id: "xxx" or bookId = "xxx"
            val idPattern = Regex("""(?:book_?id|novel_?id)[\s:=]+["']?([a-f0-9-]+)["']?""", RegexOption.IGNORE_CASE)
            idPattern.find(content)?.groupValues?.getOrNull(1)?.let {
                return it
            }
        }
        return null
    }

    private fun parseRelativeDate(dateStr: String): Long {
        val now = System.currentTimeMillis()
        val lowerDate = dateStr.lowercase()

        return when {
            lowerDate.contains("just now") || lowerDate.contains("second") -> now
            lowerDate.contains("minute") -> {
                val minutes = Regex("(\\d+)").find(lowerDate)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 1
                now - minutes * 60 * 1000
            }
            lowerDate.contains("hour") -> {
                val hours = Regex("(\\d+)").find(lowerDate)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 1
                now - hours * 60 * 60 * 1000
            }
            lowerDate.contains("day") -> {
                val days = Regex("(\\d+)").find(lowerDate)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 1
                now - days * 24 * 60 * 60 * 1000
            }
            lowerDate.contains("week") -> {
                val weeks = Regex("(\\d+)").find(lowerDate)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 1
                now - weeks * 7 * 24 * 60 * 60 * 1000
            }
            lowerDate.contains("month") -> {
                val months = Regex("(\\d+)").find(lowerDate)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 1
                now - months * 30 * 24 * 60 * 60 * 1000
            }
            lowerDate.contains("year") -> {
                val years = Regex("(\\d+)").find(lowerDate)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 1
                now - years * 365 * 24 * 60 * 60 * 1000
            }
            else -> 0L
        }
    }

    private fun extractChapterNumber(name: String): Float {
        // Try to extract chapter number from name
        val patterns = listOf(
            Regex("""chapter\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
            Regex("""ch\.?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
            Regex("""^(\d+(?:\.\d+)?)\s*[-:]"""),
        )

        for (pattern in patterns) {
            pattern.find(name)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.let {
                return it
            }
        }

        return 0f
    }
}

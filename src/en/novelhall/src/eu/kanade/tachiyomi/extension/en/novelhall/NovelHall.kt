package eu.kanade.tachiyomi.extension.en.novelhall

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

class NovelHall : HttpSource(), NovelSource {

    override val name = "NovelHall"
    override val baseUrl = "https://novelhall.com"
    override val lang = "en"
    override val supportsLatest = true
    override val isNovelSource = true

    override val client = network.cloudflareClient

    // Auto-detected genre slug from page 1 pagination links
    // e.g. /genre/action/ shows pagination with /genre/action3/2/ → slug = "action3"
    private var lastGenreSlug: String? = null
    private var lastGenreName: String? = null

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/all2022-$page.html", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())
        return parseNovelList(document)
    }

    private fun parseNovelList(document: org.jsoup.nodes.Document): MangasPage {
        // Try list format first (li.btm)
        var novels = document.select("li.btm").mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href")
            if (href.isBlank()) return@mapNotNull null

            SManga.create().apply {
                url = href
                title = element.text().trim()
                thumbnail_url = null // No cover in list view
            }
        }

        // If no results from list format, try table format
        if (novels.isEmpty()) {
            novels = document.select(".section3 table tr, table.table tbody tr, table tr").mapNotNull { row ->
                val link = row.selectFirst("td.w70 a, td:first-child a, td a[href*='/']") ?: return@mapNotNull null
                val href = link.attr("href")
                if (href.isBlank() || !href.contains("/")) return@mapNotNull null

                SManga.create().apply {
                    url = href
                    title = link.text().trim()
                    thumbnail_url = null
                }
            }.distinctBy { it.url }
        }

        // Keep paging while results are returned
        val hasNextPage = novels.isNotEmpty()

        return MangasPage(novels, hasNextPage)
    }

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/lastupdate.html", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/index.php?s=so&module=book&keyword=${java.net.URLEncoder.encode(query, "UTF-8")}"
            return GET(url, headers)
        }

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    val selectedGenre = filter.getSelectedGenre()
                    if (selectedGenre != null) {
                        return if (page == 1) {
                            GET("$baseUrl/genre/$selectedGenre/", headers)
                        } else {
                            GET("$baseUrl/genre/$selectedGenre/$page/", headers)
                        }
                    }
                }
                is SortFilter -> {
                    val selectedSort = filter.getSelectedSort()
                    if (selectedSort != null) {
                        return GET("$baseUrl/$selectedSort", headers)
                    }
                }
                else -> {}
            }
        }

        return popularMangaRequest(page)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val requestUrl = response.request.url.toString()

        if (requestUrl.contains("/genre/") || requestUrl.contains("/type/") ||
            requestUrl.contains("/lastupdate") || requestUrl.contains("all2022")
        ) {
            return popularMangaParse(response)
        }

        val document = Jsoup.parse(response.body.string())

        val novels = document.select("table tr").mapNotNull { row ->
            val link = row.selectFirst("td:nth-child(2) a") ?: return@mapNotNull null
            val href = link.attr("href")
            if (href.isBlank()) return@mapNotNull null

            val name = row.selectFirst("td:nth-child(2)")?.text()
                ?.replace(Regex("\\t+"), "")
                ?.replace("\n", " ")
                ?.trim() ?: return@mapNotNull null

            SManga.create().apply {
                url = href
                title = name
                thumbnail_url = null
            }
        }

        return MangasPage(novels, false)
    }

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            url = response.request.url.encodedPath

            title = document.selectFirst(".book-info > h1")?.text() ?: "Untitled"

            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")

            description = document.selectFirst(".intro")?.text()?.trim()

            // Parse author - remove "Author：" prefix
            val totalSection = document.selectFirst(".total")
            totalSection?.select("p")?.remove() // Remove p elements that might interfere

            author = totalSection?.select("span")?.find { it.text().contains("Author") }?.text()?.replace("Author：", "")?.trim()

            // Parse status
            val statusText = totalSection?.select("span")?.find { it.text().contains("Status") }?.text()?.replace("Status：", "")?.replace("Active", "Ongoing")?.trim()?.lowercase() ?: ""

            status = when {
                statusText.contains("ongoing") -> SManga.ONGOING
                statusText.contains("completed") -> SManga.COMPLETED
                statusText.contains("hiatus") -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }

            // Parse genres
            genre = totalSection?.select("a")?.map { it.text() }?.joinToString(", ")
        }
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request {
        val url = if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string())

        val chapters = document.select("#morelist ul > li").mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val href = link.attr("href")
            if (href.isBlank()) return@mapNotNull null

            SChapter.create().apply {
                url = href
                name = link.text().trim()
            }
        }

        // Chapters on NovelHall are in ascending order (oldest first)
        // Return in descending order (newest first) for consistency
        return chapters
    }

    // ======================== Pages ========================

    override fun pageListRequest(chapter: SChapter): Request {
        val url = if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url
        return GET(url, headers)
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

        // Parse chapter content from #htmlContent div
        val contentSection = document.selectFirst("#htmlContent")

        contentSection?.let { section ->
            // Process all children
            section.children().forEach { element ->
                when (element.tagName()) {
                    "p" -> {
                        val text = element.text()?.trim()
                        if (!text.isNullOrEmpty()) {
                            content.append("<p>$text</p>\n")
                        }
                    }
                    "br" -> {
                        // Ignore line breaks, they're handled by paragraph structure
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
                }
            }

            // If no structured content, get raw HTML and convert to paragraphs
            if (content.isEmpty()) {
                val html = section.html()
                // Split by <br> tags and wrap each segment in paragraphs
                html.split(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE))
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("<script") }
                    .forEach { segment ->
                        val cleanText = Jsoup.parse(segment).text()
                        if (cleanText.isNotBlank()) {
                            content.append("<p>$cleanText</p>\n")
                        }
                    }
            }
        }

        return content.toString()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // ======================== Filters ========================

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Note: Sort, Genre, and Search are mutually exclusive"),
        Filter.Separator(),
        SortFilter(),
        GenreFilter(),
    )

    class SortFilter : Filter.Select<String>("Sort/List", sortOptions.map { it.first }.toTypedArray()) {
        fun getSelectedSort(): String? {
            return if (state > 0 && state < sortOptions.size) sortOptions[state].second else null
        }
    }

    class GenreFilter : Filter.Select<String>("Genre", genres.map { it.first }.toTypedArray()) {
        fun getSelectedGenre(): String? {
            return if (state > 0 && state < genres.size) genres[state].second else null
        }
    }

    companion object {
        private val sortOptions = listOf(
            Pair("Default (Popular)", ""),
            Pair("Latest Updates", "lastupdate.html"),
        )

        // Genre paths are specific so we use these values
        private val genres = listOf(
            Pair("All", ""),
            Pair("Fantasy", "fantasy20223"),
            Pair("Romance", "romance20223"),
            Pair("Romantic", "romantic3"),
            Pair("Modern Romance", "modern_romance"),
            Pair("CEO", "ceo2022"),
            Pair("Action", "action3"),
            Pair("Urban", "urban"),
            Pair("Billionaire", "billionaire20223"),
            Pair("Modern Life", "modern_life"),
            Pair("Historical Romance", "historical_romance2023"),
            Pair("Adult", "adult"),
            Pair("Game", "game20233"),
            Pair("Xianxia", "xianxia2022"),
            Pair("Sci-fi", "scifi"),
            Pair("Historical", "historical2023"),
            Pair("Drama", "drama20233"),
            Pair("Urban Life", "urban_life"),
            Pair("Harem", "harem20223"),
            Pair("Fantasy Romance", "fantasy_romance"),
            Pair("Comedy", "comedy3"),
            Pair("Adventure", "adventure"),
            Pair("Farming", "farming2023"),
            Pair("Military", "military2023"),
            Pair("Son-In-Law", "soninlaw2022"),
            Pair("Wuxia", "wuxia"),
            Pair("Games", "games3"),
            Pair("Josei", "josei"),
            Pair("Ecchi", "ecchi"),
            Pair("Mystery", "mystery"),
            Pair("School Life", "school_life"),
        )
    }
}

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

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/all2022-$page.html", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())

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

        // Check for next page using multiple selectors
        val requestUrl = response.request.url.toString()
        val hasNextPage = when {
            // Genre pages: check for pagination at bottom
            requestUrl.contains("/genre/") -> {
                // Genre pages use .page-nav with numbered links
                // Example: <div class="page-nav mt20">
                //   <a href="/genre/harem20223/2/" rel="next">next page</a>
                //   <a href="/genre/harem20223/22/" data-ci-pagination-page="22">last page</a>
                // </div>
                val pageNav = document.selectFirst(".page-nav, .pagenav, div.page-nav")
                if (pageNav != null) {
                    // Check if there's a "next" link or "next page" text
                    pageNav.selectFirst("a[rel=next]") != null ||
                        pageNav.selectFirst("a:containsOwn(next)") != null ||
                        pageNav.selectFirst("a:containsOwn(Next)") != null ||
                        pageNav.selectFirst("a:containsOwn(next page)") != null
                } else {
                    // No pagination found, check if we have enough results to assume more pages
                    novels.size >= 15
                }
            }
            // Type/status pages
            requestUrl.contains("/type/") -> {
                document.selectFirst(".page-nav a[rel=next]") != null ||
                    document.selectFirst("a:contains(next)") != null
            }
            // Default pages (all2022-X.html)
            else -> {
                document.selectFirst(".page-nav a[rel=next]") != null ||
                    document.selectFirst("a:contains(next page)") != null ||
                    document.selectFirst("a.page-numbers:not(.current) + a.page-numbers") != null
            }
        }

        return MangasPage(novels, hasNextPage)
    }

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/lastupdate.html", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Note: You can either use sort OR genre OR keyword - they are mutually exclusive
        if (query.isNotBlank()) {
            // Keyword search (no pagination)
            val url = "$baseUrl/index.php?s=so&module=book&keyword=${java.net.URLEncoder.encode(query, "UTF-8")}"
            return GET(url, headers)
        }

        // Check filters - genre and sort are mutually exclusive
        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    val selectedGenre = filter.getSelectedGenre()
                    if (selectedGenre != null) {
                        // Genre browsing: /genre/{slug}/ or /genre/{slug}/{page}/
                        val genreUrl = if (page == 1) {
                            "$baseUrl/genre/$selectedGenre/"
                        } else {
                            "$baseUrl/genre/$selectedGenre/$page/"
                        }
                        return GET(genreUrl, headers)
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

        // Default: popular list
        return popularMangaRequest(page)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val requestUrl = response.request.url.toString()

        // Genre/type pages use same parsing as popular
        if (requestUrl.contains("/genre/") || requestUrl.contains("/type/") ||
            requestUrl.contains("/lastupdate") || requestUrl.contains("all2022")
        ) {
            return popularMangaParse(response)
        }

        // Keyword search results
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

        // Search has no pagination
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
            return if (state > 0) sortOptions[state].second else null
        }
    }

    class GenreFilter : Filter.Select<String>("Genre", genres.map { it.first }.toTypedArray()) {
        fun getSelectedGenre(): String? {
            return if (state > 0) genres[state].second else null
        }
    }

    companion object {
        private val sortOptions = listOf(
            Pair("Default (Popular)", ""),
            Pair("Latest Updates", "lastupdate.html"),
        )

        // Genre paths: /genre/{slug}/ with pagination /genre/{slug}/{page}/
        // Slugs confirmed from site (e.g. drama20233)
        private val genres = listOf(
            Pair("All", ""),
            Pair("Action", "action20231"),
            Pair("Adventure", "adventure20232"),
            Pair("Drama", "drama20233"),
            Pair("Comedy", "comedy20234"),
            Pair("Fantasy", "fantasy20235"),
            Pair("Harem", "harem20236"),
            Pair("Historical", "historical20237"),
            Pair("Horror", "horror20238"),
            Pair("Josei", "josei20239"),
            Pair("Martial Arts", "martial-arts20240"),
            Pair("Mature", "mature20241"),
            Pair("Mecha", "mecha20242"),
            Pair("Mystery", "mystery20243"),
            Pair("Psychological", "psychological20244"),
            Pair("Romance", "romance20245"),
            Pair("School Life", "school-life20246"),
            Pair("Sci-fi", "sci-fi20247"),
            Pair("Seinen", "seinen20248"),
            Pair("Shoujo", "shoujo20249"),
            Pair("Shounen", "shounen20250"),
            Pair("Slice of Life", "slice-of-life20251"),
            Pair("Sports", "sports20252"),
            Pair("Supernatural", "supernatural20253"),
            Pair("Tragedy", "tragedy20254"),
            Pair("Wuxia", "wuxia20255"),
            Pair("Xianxia", "xianxia20256"),
            Pair("Xuanhuan", "xuanhuan20257"),
            Pair("Yaoi", "yaoi20258"),
            Pair("Yuri", "yuri20259"),
        )
    }
}

package eu.kanade.tachiyomi.extension.en.libread

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class LibRead : ReadNovelFull(
    name = "LibRead",
    baseUrl = "https://libread.com",
    lang = "en",
) {
    override val latestPage = "sort/latest-release"

    // LibRead uses /sort/ prefix
    override fun popularMangaRequest(page: Int): Request {
        return okhttp3.Request.Builder()
            .url("$baseUrl/sort/most-popular?page=$page")
            .headers(headers)
            .build()
    }

    override fun popularMangaSelector() = "div.ul-list1 div.li, ul.ul-list2 li"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("h3.tit a, a.tit, a.con")
        if (link != null) {
            title = link.attr("title").ifEmpty { link.text().trim() }
            setUrlWithoutDomain(link.attr("abs:href"))
        }
        thumbnail_url = element.selectFirst("img")?.let { img ->
            val src = img.attr("data-src").ifEmpty { img.attr("src") }
            if (src.startsWith("/")) "$baseUrl$src" else src
        }
    }

    // Override pagination selector to use div.pages ul structure
    override fun popularMangaNextPageSelector() = "div.pages ul li a[rel=next], div.pages ul li.next:not(.disabled) a"

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector()).map { popularMangaFromElement(it) }

        // Check for next page using the pages ul structure
        val hasNextPage = document.selectFirst("div.pages ul li a[rel=next]") != null ||
            document.selectFirst("div.pages ul li.active + li a") != null

        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return okhttp3.Request.Builder()
            .url("$baseUrl/$latestPage?page=$page")
            .headers(headers)
            .build()
    }

    override fun latestUpdatesSelector() = "div.ul-list1 div.li, ul.ul-list2 li"

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // Search/Filter - Use type (sort by novel origin) and genre Picker from libread.json
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            // Text search
            return Request.Builder()
                .url("$baseUrl/search?keyword=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page")
                .headers(headers)
                .build()
        }

        // Check for genre filter first (takes priority over type)
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val selectedGenre = genreFilter?.getSelectedGenre()
        if (selectedGenre != null) {
            return Request.Builder()
                .url("$baseUrl/$selectedGenre?page=$page")
                .headers(headers)
                .build()
        }

        // Check for type/sort filter
        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()
        val selectedType = typeFilter?.getSelectedType()
        if (selectedType != null) {
            return Request.Builder()
                .url("$baseUrl/$selectedType?page=$page")
                .headers(headers)
                .build()
        }

        // Default: popular
        return popularMangaRequest(page)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // Filters from libread.json
    override fun getFilterList() = FilterList(
        Filter.Header("Note: Genre/Type filter only works with empty search"),
        TypeFilter(),
        GenreFilter(),
    )

    private class TypeFilter : Filter.Select<String>(
        "Novel Type",
        arrayOf("Most Popular", "Latest Release", "Chinese Novel", "Korean Novel", "Japanese Novel", "English Novel"),
    ) {
        fun getSelectedType(): String? = when (state) {
            0 -> null // default, use popular request
            1 -> "sort/latest-release"
            2 -> "sort/latest-release/chinese-novel"
            3 -> "sort/latest-release/korean-novel"
            4 -> "sort/latest-release/japanese-novel"
            5 -> "sort/latest-release/english-novel"
            else -> null
        }
    }

    private class GenreFilter : Filter.Select<String>(
        "Genre",
        arrayOf(
            "All", "Action", "Adult", "Adventure", "Comedy", "Drama", "Eastern",
            "Ecchi", "Fantasy", "Game", "Gender Bender", "Harem", "Historical",
            "Horror", "Josei", "Martial Arts", "Mature", "Mecha", "Mystery",
            "Psychological", "Reincarnation", "Romance", "School Life", "Sci-fi",
            "Seinen", "Shoujo", "Shounen Ai", "Shounen", "Slice of Life", "Smut",
            "Sports", "Supernatural", "Tragedy", "Wuxia", "Xianxia", "Xuanhuan", "Yaoi",
        ),
    ) {
        fun getSelectedGenre(): String? {
            if (state == 0) return null
            val values = arrayOf(
                "", "genre/Action", "genre/Adult", "genre/Adventure", "genre/Comedy",
                "genre/Drama", "genre/Eastern", "genre/Ecchi", "genre/Fantasy",
                "genre/Game", "genre/Gender+Bender", "genre/Harem", "genre/Historical",
                "genre/Horror", "genre/Josei", "genre/Martial+Arts", "genre/Mature",
                "genre/Mecha", "genre/Mystery", "genre/Psychological", "genre/Reincarnation",
                "genre/Romance", "genre/School+Life", "genre/Sci-fi", "genre/Seinen",
                "genre/Shoujo", "genre/Shounen+Ai", "genre/Shounen", "genre/Slice+of+Life",
                "genre/Smut", "genre/Sports", "genre/Supernatural", "genre/Tragedy",
                "genre/Wuxia", "genre/Xianxia", "genre/Xuanhuan", "genre/Yaoi",
            )
            return values.getOrNull(state)
        }
    }

    // Novel detail page parsing - parse from div.txt structure
    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        document.selectFirst("div.m-imgtxt, div.m-book1")?.let { info ->
            thumbnail_url = info.selectFirst("img")?.let { img ->
                val src = img.attr("data-src").ifEmpty { img.attr("src") }
                if (src.startsWith("/")) "$baseUrl$src" else src
            }
            title = info.selectFirst("h1.tit")?.text()?.trim() ?: ""
        }

        // Parse info from div.txt div.item structure
        document.select("div.txt div.item, div.m-imgtxt div.item").forEach { element ->
            val label = element.selectFirst("span.s1")?.text()?.trim()?.removeSuffix(":")?.trim() ?: ""
            val value = element.selectFirst("span.s2, span.s3")

            when (label.lowercase()) {
                "author", "authors" -> {
                    author = value?.text()?.trim() ?: element.select("a").joinToString(", ") { it.text().trim() }
                }
                "genre", "genres" -> {
                    genre = element.select("a").joinToString(", ") { it.text().trim() }
                        .ifEmpty { value?.text()?.trim() }
                }
                "status" -> {
                    val statusText = value?.text()?.trim() ?: ""
                    status = when {
                        statusText.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                        statusText.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                }
            }
        }

        description = document.selectFirst("div.m-desc div.txt div.inner, div.desc-text")?.text()?.trim()
    }

    // Chapter list parsing - LibRead uses select with options
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        // Try to get chapters from select options
        val chapters = document.select("select#idData option, ul#idData li a").mapIndexedNotNull { index, element ->
            val chapterUrl = if (element.tagName() == "option") {
                val value = element.attr("value")
                if (value.isNotBlank() && value != "0") {
                    if (value.startsWith("/")) value else "/$value"
                } else {
                    null
                }
            } else {
                element.attr("href")
            }

            if (chapterUrl.isNullOrBlank()) return@mapIndexedNotNull null

            SChapter.create().apply {
                setUrlWithoutDomain(chapterUrl)
                name = element.text().trim().ifEmpty { "Chapter ${index + 1}" }
                chapter_number = (index + 1).toFloat()
            }
        }

        return chapters
    }

    // Content parsing
    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(okhttp3.Request.Builder().url(page.url).headers(headers).build()).execute()
        val document = response.asJsoup()

        val content = document.selectFirst("div.txt div#article, div#chapter-content, div.chapter-content, div#chr-content")
        if (content != null) {
            // Remove ads and unwanted elements
            content.select("div.ads, script, ins, .adsbygoogle, .chapter-ad").remove()
            return content.html()
        }

        return ""
    }
}

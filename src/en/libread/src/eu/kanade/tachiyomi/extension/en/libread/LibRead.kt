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

class LibRead :
    ReadNovelFull(
        name = "LibRead",
        baseUrl = "https://libread.com",
        lang = "en",
    ) {
    override val latestPage = "sort/latest-release"
    override val popularPage = "sort/most-popular"
    override val pageAsPath = true

    // LibRead uses /sort/ prefix; pagination handled by base class when pageAsPath=true

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

    override fun popularMangaNextPageSelector() = "li.next:not(.disabled), ul.pagination li.active + li a, div.pages a[href], div.pages ul li a[href]"

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(popularMangaSelector()).map { popularMangaFromElement(it) }

        // Try common next-page indicators first
        var hasNextPage = document.selectFirst("li.next:not(.disabled), ul.pagination li.active + li a") != null

        if (!hasNextPage) {
            // Fallback: inspect div.pages anchors — look for numeric links greater than current page
            val path = response.request.url.encodedPath.trimEnd('/')
            val currentPage = path.substringAfterLast('/').toIntOrNull() ?: 1

            val pageAnchors = document.select("div.pages a[href]").filter { a ->
                val href = a.attr("href")
                href.isNotBlank() && !href.startsWith("javascript", true)
            }

            hasNextPage = pageAnchors.any { a ->
                val text = a.text().trim()
                val num = text.toIntOrNull()
                if (num != null) {
                    num > currentPage
                } else {
                    // treat arrows (>, >>) or next labels as next page
                    text.contains(">") || a.attr("rel") == "next"
                }
            }
        }

        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = okhttp3.Request.Builder()
        .url("$baseUrl/$latestPage?page=$page")
        .headers(headers)
        .build()

    override fun latestUpdatesSelector() = "div.ul-list1 div.li, ul.ul-list2 li"

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            // Text search
            return Request.Builder()
                .url("$baseUrl/search?keyword=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page")
                .headers(headers)
                .build()
        }

        // When no search query, apply filters in priority order: Genre > Type
        val genreFilter = filters.filterIsInstance<GenreFilter>().firstOrNull()
        val selectedGenre = genreFilter?.getSelectedGenre()
        if (selectedGenre != null && selectedGenre.isNotEmpty()) {
            val genrePath = selectedGenre.trim().trimStart('/')
            return if (pageAsPath && page > 1) {
                Request.Builder()
                    .url("$baseUrl/$genrePath/$page")
                    .headers(headers)
                    .build()
            } else {
                Request.Builder()
                    .url("$baseUrl/$genrePath${if (!pageAsPath) "?page=$page" else ""}")
                    .headers(headers)
                    .build()
            }
        }

        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()
        val selectedType = typeFilter?.getSelectedType()
        if (selectedType != null && selectedType.isNotEmpty()) {
            val typePath = selectedType.trim().trimStart('/')
            return if (pageAsPath && page > 1) {
                Request.Builder()
                    .url("$baseUrl/$typePath/$page")
                    .headers(headers)
                    .build()
            } else {
                Request.Builder()
                    .url("$baseUrl/$typePath${if (!pageAsPath) "?page=$page" else ""}")
                    .headers(headers)
                    .build()
            }
        }

        // Default: popular
        return popularMangaRequest(page)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun getFilterList() = FilterList(
        Filter.Header("Note: Genre/Type filter only works with empty search"),
        TypeFilter(),
        GenreFilter(),
    )

    private class TypeFilter :
        Filter.Select<String>(
            "Novel Type",
            arrayOf("All", "Most Popular", "Latest Release", "Chinese Novel", "Korean Novel", "Japanese Novel", "English Novel"),
        ) {
        fun getSelectedType(): String? = when (state) {
            0 -> "sort/latest-release" // All maps to latest-release according to libread.json

            1 -> "sort/most-popular"

            2 -> "sort/latest-release"

            3 -> "sort/latest-release/chinese-novel"

            4 -> "sort/latest-release/korean-novel"

            5 -> "sort/latest-release/japanese-novel"

            6 -> "sort/latest-release/english-novel"

            else -> "sort/latest-release"
        }
    }

    private class GenreFilter :
        Filter.Select<String>(
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
            if (state == 0) return ""
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
            return values.getOrNull(state) ?: ""
        }
    }

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        document.selectFirst("div.m-imgtxt, div.m-book1")?.let { info ->
            thumbnail_url = info.selectFirst("img")?.let { img ->
                val src = img.attr("data-src").ifEmpty { img.attr("src") }
                if (src.startsWith("/")) "$baseUrl$src" else src
            }
            title = info.selectFirst("h1.tit")?.text()?.trim() ?: ""
        }

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

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

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

        return chapters.reversed()
    }

    // Content parsing
    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(okhttp3.Request.Builder().url(page.url).headers(headers).build()).execute()
        val document = response.asJsoup()

        val content = document.selectFirst("div.txt div#article, div#chapter-content, div.chapter-content, div#chr-content")
        if (content != null) {
            content.select("div.ads, script, ins, .adsbygoogle, .chapter-ad").remove()
            return content.html()
        }

        return ""
    }
}

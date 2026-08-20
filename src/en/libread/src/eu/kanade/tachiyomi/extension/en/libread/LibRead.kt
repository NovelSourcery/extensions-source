package eu.kanade.tachiyomi.novelextension.en.libread

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.SlugPath
import kotlinx.serialization.json.JsonElement
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class LibRead : ReadNovelFull() {
    override val latestPage = "sort/latest-release"
    override val popularPage = "sort/most-popular"
    override val pageAsPath = true

    // LibRead uses /sort/ prefix; pagination handled by base class when pageAsPath=true

    // Same engine as FreeWebNovel: ajax chapter endpoint is dead and the chapter list is
    // paginated at /libread/<slug>?page=N (page 1 is the novel page); page count and total
    // come from #indexselect ("C.1 - C.40" ranges).
    override val noAjax = true
    override val chaptersPaginated = true
    override val mangaPathTemplate = SlugPath("/libread/")

    override fun chapterListPageRequest(manga: SManga, page: Int): Request {
        val base = baseUrl + mangaPathTemplate.resolve(manga.url).trimEnd('/')
        val url = if (page <= 1) base else "$base?$pageParam=$page"
        return GET(url, headers)
    }

    override fun chapterPageSelector() = "#idData li a"

    // Chapter urls follow /libread/<slug>/chapter-0<N> (literal leading zero), so the fast list
    // can be synthesized.
    override fun chapterUrlFromNumber(manga: SManga, number: Int): String? {
        val path = mangaPathTemplate.resolve(manga.url).trimEnd('/')
        if (path.isBlank()) return null
        return "$path/chapter-0$number"
    }

    override fun popularMangaSelector() = "div.ul-list1 div.li, ul.ul-list2 li"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        // title/url are lateinit vars; an element whose link selector doesn't match (ad slot,
        // stray li, layout drift - seen on the search/genre listing pages) must still leave both
        // assigned or any later read (e.g. dedup by manga.url) crashes with
        // UninitializedPropertyAccessException.
        title = ""
        url = ""
        val link = element.selectFirst("h3.tit a, a.tit, a.con")
        if (link != null) {
            title = link.attr("title").ifEmpty { link.text() }
            setSlugUrl(link.attr("abs:href"))
        }
        thumbnail_url = element.selectFirst("img")?.let { img ->
            val src = img.attr("data-src").ifEmpty { img.attr("src") }
            if (src.startsWith("/")) "$baseUrl$src" else src
        }
    }

    override fun popularMangaNextPageSelector() = "li.next:not(.disabled), ul.pagination li.active + li a, div.pages a[href], div.pages ul li a[href]"

    override fun buildLatestUpdatesRequest(page: Int): Request = okhttp3.Request.Builder()
        .url("$baseUrl/$latestPage?page=$page")
        .headers(headers)
        .build()

    override fun latestUpdatesSelector() = "div.ul-list1 div.li, ul.ul-list2 li"

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
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
        return buildPopularMangaRequest(page)
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
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

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = super.mangaDetailsParse(document)

        // LibRead specific customization if needed (fallback to base class parsing)
        if (manga.title.isNullOrBlank()) {
            manga.title = document.selectFirst("div.m-imgtxt h1.tit, div.m-book1 h1.tit")?.text() ?: ""
        }
        if (manga.thumbnail_url.isNullOrBlank()) {
            document.selectFirst("div.m-imgtxt img, div.m-book1 img")?.let { img ->
                val src = img.attr("data-src").ifEmpty { img.attr("src") }
                manga.thumbnail_url = if (src.startsWith("/")) "$baseUrl$src" else src
            }
        }
        if (manga.description.isNullOrBlank()) {
            manga.description = document.selectFirst("div.m-desc div.txt div.inner, div.desc-text")?.text()
        }

        return manga
    }

    // Content parsing
    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) page.url else baseUrl + page.url
        val response = client.get(url, headers)
        val document = response.asJsoup()

        val content = document.selectFirst("div.txt div#article, div#chapter-content, div.chapter-content, div#chr-content")
        if (content != null) {
            content.select("div.ads, script, ins, .adsbygoogle, .chapter-ad").remove()
            return content.html()
        }

        return ""
    }
}

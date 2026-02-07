package eu.kanade.tachiyomi.multisrc.readnovelfull

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * ReadNovelFull multisrc base class.
 * Ported from LNReader TypeScript plugin.
 *
 * Sites using this template:
 * - readnovelfull.com
 * - allnovel.org
 * - novelfull.com
 * - boxnovel/novlove.com
 * - libread.com
 * - freewebnovel.com
 * - allnovelfull/novgo.net
 * - novelbin.com
 * - lightnovelplus.com
 */
abstract class ReadNovelFull(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
) : ParsedHttpSource(), NovelSource, ConfigurableSource {

    // isNovelSource is provided by NovelSource interface with default value true

    override val supportsLatest = true

    override val client = network.client

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Configuration options - can be overridden by child classes
    protected open val latestPage: String = "latest-release-novel"
    protected open val searchPage: String = "search"
    protected open val novelListing: String? = null
    protected open val chapterListing: String? = "ajax/chapter-archive"
    protected open val chapterParam: String = "novelId"
    protected open val pageParam: String = "page"
    protected open val typeParam: String = "type"
    protected open val genreParam: String = "category_novel"
    protected open val genreKey: String = "id"
    protected open val langParam: String? = null
    protected open val urlLangCode: String? = null
    protected open val searchKey: String = "keyword"
    protected open val postSearch: Boolean = false
    protected open val noAjax: Boolean = false
    protected open val pageAsPath: Boolean = false
    protected open val noPages: List<String> = emptyList()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        return if (pageAsPath && page > 1) {
            GET("$baseUrl/most-popular/$page", headers)
        } else {
            GET("$baseUrl/most-popular?$pageParam=$page", headers)
        }
    }

    override fun popularMangaSelector() = "div.col-novel-main div.list-novel div.row, div.archive div.row, div.index-intro div.item, div.ul-list1 div.li, div.col-l div.li, div.col-r div.li"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("h3.novel-title a, .novel-title a, a.cover, h3.tit a, .txt h3.tit a, div.txt h3.tit a, div.con a")
        if (link != null) {
            title = link.text().trim().ifEmpty { link.attr("title") }
            setUrlWithoutDomain(link.attr("abs:href"))
        } else {
            // Fallback - try to find any link in the element
            element.selectFirst("a[href]")?.let {
                title = it.attr("title").ifEmpty { it.text().trim() }
                setUrlWithoutDomain(it.attr("abs:href"))
            }
        }
        // Try multiple image selectors for different site structures
        // Use abs:src and abs:data-src to handle relative URLs
        thumbnail_url = element.selectFirst("img")?.let { img ->
            img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
        } ?: element.selectFirst("div.pic img, div.s1 img")?.let { img ->
            img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
        }
    }

    override fun popularMangaNextPageSelector() = "li.next:not(.disabled), ul.pagination li.active + li a"

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        return if (pageAsPath && page > 1) {
            GET("$baseUrl/$latestPage/$page", headers)
        } else {
            GET("$baseUrl/$latestPage?$pageParam=$page", headers)
        }
    }

    override fun latestUpdatesSelector() = popularMangaSelector() + ", ul.ul-list2 li"

    override fun latestUpdatesFromElement(element: Element): SManga {
        // Handle ul-list2 li structure for FreeWebNovel latest updates
        if (element.tagName() == "li" && element.selectFirst("div.s1.con") != null) {
            return SManga.create().apply {
                val link = element.selectFirst("a.tit")
                if (link != null) {
                    title = link.attr("title").ifEmpty { link.text().trim() }
                    setUrlWithoutDomain(link.attr("abs:href"))
                }
                thumbnail_url = element.selectFirst("div.pic img")?.let { img ->
                    img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
                }
            }
        }
        return popularMangaFromElement(element)
    }

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Build URL with filters
        val urlBuilder = "$baseUrl/$searchPage".toHttpUrl().newBuilder()

        if (query.isNotEmpty()) {
            urlBuilder.addQueryParameter(searchKey, query)
        }

        // Apply filters
        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> {
                    val type = filter.toUriPart()
                    if (type != "all") {
                        urlBuilder.addQueryParameter(typeParam, type)
                    }
                }
                is GenreFilter -> {
                    filter.state.filter { it.state }.forEach { genre ->
                        urlBuilder.addQueryParameter(genreParam, genre.id)
                    }
                }
                is StatusFilter -> {
                    val status = filter.toUriPart()
                    if (status != "all") {
                        urlBuilder.addQueryParameter("status", status)
                    }
                }
                else -> {}
            }
        }

        if (!postSearch) {
            urlBuilder.addQueryParameter(pageParam, page.toString())
        }

        val url = urlBuilder.build().toString()

        return if (postSearch && query.isNotEmpty()) {
            val body = FormBody.Builder()
                .add(searchKey, query)
                .build()
            POST(url, headers, body)
        } else {
            GET(url, headers)
        }
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // ======================== Details ========================

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        document.selectFirst("div.books, div.book, div.m-imgtxt, div.m-book1")?.let { info ->
            thumbnail_url = info.selectFirst("img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }
            title = info.selectFirst("h3.title, h1.tit, img")?.let {
                it.text().ifEmpty { it.attr("title") }
            } ?: ""
        }

        // Parse info section
        document.select("div.info div, ul.info-meta li, div.m-imgtxt div.item").forEach { element ->
            val text = element.text()
            when {
                text.contains("Author", ignoreCase = true) -> {
                    author = element.select("a").joinToString { it.text().trim() }
                        .ifEmpty { text.substringAfter(":").trim() }
                }
                text.contains("Genre", ignoreCase = true) -> {
                    genre = element.select("a").joinToString { it.text().trim() }
                        .ifEmpty { text.substringAfter(":").trim() }
                }
                text.contains("Status", ignoreCase = true) -> {
                    status = parseStatus(text.substringAfter(":").trim())
                }
            }
        }

        // Try multiple selectors for description
        description = document.selectFirst(
            "div.desc-text, div.inner, div.desc, div.m-desc div.txt div.inner, " +
                "div.summary div.content, div#editdescription, div.desc-text-full, " +
                "div.novel-detail-body div.summary, div.desc_panel",
        )?.text()?.trim()
    }

    private fun parseStatus(status: String): Int = when {
        status.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
        status.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
        status.contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS
        status.contains("Dropped", ignoreCase = true) -> SManga.CANCELLED
        status.contains("Cancelled", ignoreCase = true) -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // ======================== Chapters ========================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val novelPath = response.request.url.encodedPath

        // Try to get chapters from AJAX endpoint
        if (!noAjax) {
            val novelId = document.selectFirst("div#rating")?.attr("data-novel-id")
                ?: novelPath.replace(Regex("[^0-9]"), "").takeIf { it.isNotEmpty() }

            if (novelId != null && chapterListing != null) {
                try {
                    val ajaxUrl = "$baseUrl/$chapterListing?$chapterParam=$novelId"
                    val ajaxResponse = client.newCall(GET(ajaxUrl, headers)).execute()
                    val ajaxDocument = ajaxResponse.asJsoup()

                    val chapters = ajaxDocument.select("ul.list-chapter li a, select option[value]").mapIndexedNotNull { index, element ->
                        val chapterUrl = if (element.tagName() == "option") {
                            element.attr("value")
                        } else {
                            element.attr("abs:href")
                        }

                        // Skip if URL is empty
                        if (chapterUrl.isBlank()) return@mapIndexedNotNull null

                        SChapter.create().apply {
                            setUrlWithoutDomain(chapterUrl)
                            name = if (element.tagName() == "option") {
                                element.text().trim().ifEmpty { "Chapter ${index + 1}" }
                            } else {
                                element.attr("title").ifEmpty { element.text().trim() }
                            }
                            chapter_number = (index + 1).toFloat()
                        }
                    }

                    if (chapters.isNotEmpty()) {
                        return chapters
                    }
                } catch (e: Exception) {
                    // Fall back to parsing from page
                }
            }
        }

        // Parse chapters directly from page (noAjax mode or fallback)
        return document.select("ul#idData li a, div.chapter-list a, ul.list-chapter li a").mapIndexedNotNull { index, element ->
            val chapterUrl = element.attr("abs:href")
            // Skip if URL is empty
            if (chapterUrl.isBlank()) return@mapIndexedNotNull null

            SChapter.create().apply {
                setUrlWithoutDomain(chapterUrl)
                name = element.attr("title").ifEmpty { element.text().trim() }
                chapter_number = (index + 1).toFloat()
            }
        }
    }

    override fun chapterListSelector() = throw UnsupportedOperationException()

    override fun chapterFromElement(element: Element) = throw UnsupportedOperationException()

    // ======================== Pages ========================

    override fun pageListParse(document: Document): List<Page> {
        // For novel sources, we return a single page that will contain the text
        return listOf(Page(0, document.location()))
    }

    override fun imageUrlParse(document: Document): String = ""

    // ======================== Novel Content ========================

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(page.url, headers)).execute()
        val document = response.asJsoup()

        // Try multiple selectors for chapter content
        val contentSelectors = listOf(
            "div#chr-content",
            "div#chr-content.chr-c",
            "div#chapter-content",
            "div#article",
            "div.txt",
            "div.chapter-content",
            "div.content",
        )

        for (selector in contentSelectors) {
            val content = document.selectFirst(selector)
            if (content != null) {
                if (preferences.getBoolean(PREF_RAW_HTML, false)) {
                    return content.html()
                }

                // Remove ads and unwanted elements
                content.select("div.ads, div.unlock-buttons, sub, script, ins, .adsbygoogle").remove()

                // Get clean HTML content
                return content.html()
            }
        }

        return ""
    }

    // ======================== Filters ========================

    override fun getFilterList() = FilterList(
        Filter.Header("Type filters"),
        TypeFilter(),
        Filter.Header("Genre filters"),
        GenreFilter(getGenreList()),
        Filter.Header("Status filters"),
        StatusFilter(),
    )

    private class TypeFilter : Filter.Select<String>(
        "Type",
        arrayOf("All", "English", "Japanese", "Korean", "Chinese"),
        0,
    ) {
        fun toUriPart() = values[state].lowercase()
    }

    private class GenreFilter(genres: List<Genre>) : Filter.Group<GenreCheckBox>(
        "Genres",
        genres.map { GenreCheckBox(it.name, it.id) },
    )

    private class GenreCheckBox(name: String, val id: String) : Filter.CheckBox(name)

    private class StatusFilter : Filter.Select<String>(
        "Status",
        arrayOf("All", "Ongoing", "Completed"),
        0,
    ) {
        fun toUriPart() = values[state].lowercase()
    }

    protected data class Genre(val name: String, val id: String)

    protected open fun getGenreList() = listOf(
        Genre("Action", "action"),
        Genre("Adult", "adult"),
        Genre("Adventure", "adventure"),
        Genre("Comedy", "comedy"),
        Genre("Drama", "drama"),
        Genre("Eastern", "eastern"),
        Genre("Ecchi", "ecchi"),
        Genre("Fantasy", "fantasy"),
        Genre("Game", "game"),
        Genre("Gender Bender", "gender-bender"),
        Genre("Harem", "harem"),
        Genre("Historical", "historical"),
        Genre("Horror", "horror"),
        Genre("Josei", "josei"),
        Genre("Lolicon", "lolicon"),
        Genre("Martial Arts", "martial-arts"),
        Genre("Mature", "mature"),
        Genre("Mecha", "mecha"),
        Genre("Modern Life", "modern-life"),
        Genre("Mystery", "mystery"),
        Genre("Psychological", "psychological"),
        Genre("Reincarnation", "reincarnation"),
        Genre("Romance", "romance"),
        Genre("School Life", "school-life"),
        Genre("Sci-fi", "sci-fi"),
        Genre("Seinen", "seinen"),
        Genre("Shoujo", "shoujo"),
        Genre("Shounen", "shounen"),
        Genre("Slice of Life", "slice-of-life"),
        Genre("Smut", "smut"),
        Genre("Sports", "sports"),
        Genre("Supernatural", "supernatural"),
        Genre("System", "system"),
        Genre("Thriller", "thriller"),
        Genre("Tragedy", "tragedy"),
        Genre("Transmigration", "transmigration"),
        Genre("Wuxia", "wuxia"),
        Genre("Xianxia", "xianxia"),
        Genre("Xuanhuan", "xuanhuan"),
        Genre("Yaoi", "yaoi"),
        Genre("Yuri", "yuri"),
    )

    // ======================== Settings ========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val rawHtmlPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_RAW_HTML
            title = "Return raw HTML"
            summary = "If enabled, returns the raw HTML of the chapter content instead of parsed text. Useful for custom parsers."
            setDefaultValue(false)
        }
        screen.addPreference(rawHtmlPref)
    }

    companion object {
        private const val PREF_RAW_HTML = "pref_raw_html"
        private val DATE_FORMAT = SimpleDateFormat("MMM dd, yyyy", Locale.US)
    }
}

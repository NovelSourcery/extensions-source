package eu.kanade.tachiyomi.novelextension.en.lightnovelworld

import android.app.Application
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.security.MessageDigest
import java.util.Calendar

class LightNovelWorld :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "Light Novel World"
    override val baseUrl = "https://lightnovelworld.org"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient
    private val json: Json by injectLazy()

    override val isNovelSource = true

    override fun imageUrlParse(response: Response): String = ""

    // ======================== Pagination Caching ========================

    @Serializable
    data class PaginationState(
        val lastChapterTotal: Int = 0,
        val lastUpdated: Long = 0L,
        val cachedChapters: List<CachedChapter> = emptyList(),
    )

    @Serializable
    data class CachedChapter(
        val name: String,
        val url: String,
        val dateUpload: Long = 0L,
        val chapterNumber: Float = -1f,
    )

    private val cacheDir: File by lazy {
        val dir = File(Injekt.get<Application>().cacheDir, "lightnovelworld_chapters")
        dir.mkdirs()
        dir
    }

    private val cacheLock = Any()

    private fun getCacheFile(novelPath: String): File {
        val md5 = MessageDigest.getInstance("MD5")
            .digest(novelPath.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$md5.json")
    }

    private fun loadPaginationState(novelPath: String): PaginationState? = synchronized(cacheLock) {
        val file = getCacheFile(novelPath)
        if (!file.exists()) return@synchronized null
        try {
            json.decodeFromString<PaginationState>(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun savePaginationState(novelPath: String, state: PaginationState) = synchronized(cacheLock) {
        try {
            getCacheFile(novelPath).writeText(json.encodeToString(state))
        } catch (_: Exception) {}
    }

    private fun clearAllChapterCache() {
        synchronized(cacheLock) {
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    private fun String?.toAbsoluteUrl(): String? = when {
        this.isNullOrEmpty() -> null
        startsWith("http") -> this
        startsWith("/") -> baseUrl + this
        else -> this
    }

    // ======================== Popular/Latest ========================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/advanced-search/?sort=rank&order=asc&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseAdvancedSearch(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/advanced-search/?sort=updates&order=desc&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseAdvancedSearch(response)

    // ======================== Search ========================

    @Serializable
    data class SearchResponse(val novels: List<SearchNovel> = emptyList())

    @Serializable
    data class SearchNovel(
        val title: String = "",
        val slug: String = "",
        @SerialName("cover_path") val coverPath: String? = null,
        val author: String? = null,
    )

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Text query uses the JSON API (max 50 results, no pagination, filters ignored)
        if (query.isNotEmpty()) {
            val url = "$baseUrl/api/search/".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("search_type", "title")
                .build()
            return GET(url, headers)
        }

        val url = "$baseUrl/advanced-search/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    filter.state.filter { it.isIncluded() }.forEach {
                        url.addQueryParameter("genres_include", it.name)
                    }
                    filter.state.filter { it.isExcluded() }.forEach {
                        url.addQueryParameter("genres_exclude", it.name)
                    }
                }
                is GenreLogicFilter -> url.addQueryParameter("genre_logic", filter.toUriPart())
                is TagsIncludeFilter -> {
                    if (filter.state.isNotBlank()) {
                        url.addQueryParameter("tags_include", filter.state.trim())
                    }
                }
                is TagsExcludeFilter -> {
                    if (filter.state.isNotBlank()) {
                        url.addQueryParameter("tags_exclude", filter.state.trim())
                    }
                }
                is ChapterRangeFilter -> {
                    val value = filter.toUriPart()
                    if (value != "all") {
                        url.addQueryParameter("chapter_range", value)
                    }
                }
                is StatusFilter -> {
                    filter.state.filter { it.state }.forEach {
                        url.addQueryParameter("status", it.value)
                    }
                }
                is SortFilter -> url.addQueryParameter("sort", filter.toUriPart())
                is OrderFilter -> url.addQueryParameter("order", filter.toUriPart())
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        // JSON API response for text queries
        if (response.request.url.encodedPath.startsWith("/api/search")) {
            val result = json.decodeFromString<SearchResponse>(response.body.string())
            val novels = result.novels.map { novel ->
                SManga.create().apply {
                    title = novel.title
                    url = "/novel/${novel.slug}/"
                    thumbnail_url = novel.coverPath.toAbsoluteUrl()
                    author = novel.author
                }
            }
            return MangasPage(novels, hasNextPage = false)
        }

        return parseAdvancedSearch(response)
    }

    private fun parseAdvancedSearch(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())

        val novels = doc.select(".recommendation-card").mapNotNull { card ->
            val link = card.selectFirst("a.card-cover-link")
                ?: card.selectFirst(".card-footer a[href]")
                ?: return@mapNotNull null
            val title = card.selectFirst(".card-title")?.text()?.trim()
                ?: card.selectFirst(".card-cover img")?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            SManga.create().apply {
                this.title = title
                url = link.attr("href").removePrefix(baseUrl)
                thumbnail_url = card.selectFirst(".card-cover img")?.attr("src").toAbsoluteUrl()
            }
        }

        // 24 results per page; total count shown as "N novels found"
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val totalCount = doc.selectFirst(".results-count")?.text()
            ?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0
        val hasNextPage = currentPage * 24 < totalCount

        return MangasPage(novels, hasNextPage)
    }

    // ======================== Novel Details ========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            title = doc.selectFirst(".novel-title")?.text()?.trim()
                ?: doc.selectFirst("img.novel-cover")?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: "No Title Found"

            thumbnail_url = doc.selectFirst("img.novel-cover")?.attr("src").toAbsoluteUrl()

            author = doc.selectFirst(".novel-author")?.text()
                ?.removePrefix("Author:")?.trim()

            // Genres + tags combined
            val genres = doc.select(".novel-genres .genre-tag").map { it.text().trim() }
            val tags = doc.select(".tags-container .tag-item").map { it.text().trim() }
            genre = (genres + tags).distinctBy { it.lowercase() }.joinToString(", ")

            val statusText = doc.selectFirst(".status-badge")?.text()?.lowercase()
            status = when {
                statusText?.contains("ongoing") == true -> SManga.ONGOING
                statusText?.contains("completed") == true -> SManga.COMPLETED
                statusText?.contains("hiatus") == true -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }

            description = buildString {
                doc.selectFirst(".summary-content")?.let {
                    append(it.formattedText())
                }

                // Append stats info
                val stats = mutableListOf<String>()
                doc.selectFirst(".rank-badge")?.text()?.trim()?.let { stats.add(it) }
                doc.select(".novel-stats-grid .stat-box").forEach { box ->
                    val value = box.selectFirst(".stat-value")?.text()?.trim()
                    val label = box.selectFirst(".stat-label")?.text()?.trim()
                    if (value != null && label != null) {
                        stats.add("$label: $value")
                    }
                }
                if (stats.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(stats.joinToString(" • "))
                }
            }
        }
    }

    /**
     * Extract text preserving <p> and <br> as newlines.
     */
    private fun Element.formattedText(): String {
        val node = clone()
        node.select("script, style").remove()
        node.select("br").forEach { it.after("\\n") }
        node.select("p").filter { it !== node }.forEach { it.after("\\n\\n") }
        return node.text()
            .replace("\\n", "\n")
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request {
        val path = manga.url.trimEnd('/')
        return GET("$baseUrl$path/chapters/", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())
        val basePath = response.request.url.encodedPath

        // Total pages from the page selector ("Page X of N")
        val totalPages = doc.select("#pageSelect option").size.coerceAtLeast(1)

        // Current chapter total from "A total of N chapters" text
        val currentTotal = Regex("""A total of (\d+) chapters""")
            .find(doc.selectFirst(".chapters-description")?.text().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val cached = loadPaginationState(basePath)

        // Cache hit: total unchanged, return cached chapters without refetching pages
        if (cached != null && currentTotal > 0 &&
            cached.lastChapterTotal == currentTotal &&
            cached.cachedChapters.isNotEmpty()
        ) {
            return cached.cachedChapters.map { it.toSChapter() }.reversed()
        }

        val chapters = mutableListOf<SChapter>()
        var startPage = 1

        // Incremental fetch: total grew → keep fully-cached pages (fixed 50/page),
        // refetch only the last partial page and anything after it
        if (cached != null && currentTotal > cached.lastChapterTotal && cached.cachedChapters.isNotEmpty()) {
            val fullPages = cached.cachedChapters.size / CHAPTERS_PER_PAGE
            if (fullPages > 0) {
                chapters += cached.cachedChapters.take(fullPages * CHAPTERS_PER_PAGE).map { it.toSChapter() }
                startPage = fullPages + 1
            }
        }

        for (page in startPage..totalPages) {
            val pageDoc = if (page == 1) {
                doc
            } else {
                val pageResponse = client.newCall(GET("$baseUrl$basePath?page=$page", headers)).execute()
                Jsoup.parse(pageResponse.body.string())
            }
            chapters += parseChapterPage(pageDoc)
        }

        savePaginationState(
            basePath,
            PaginationState(
                lastChapterTotal = if (currentTotal > 0) currentTotal else chapters.size,
                lastUpdated = System.currentTimeMillis(),
                cachedChapters = chapters.map {
                    CachedChapter(it.name, it.url, it.date_upload, it.chapter_number)
                },
            ),
        )

        // Site lists chapters ascending; app expects newest first
        return chapters.reversed()
    }

    private fun CachedChapter.toSChapter(): SChapter {
        val cached = this
        return SChapter.create().apply {
            name = cached.name
            url = cached.url
            date_upload = cached.dateUpload
            chapter_number = cached.chapterNumber
        }
    }

    private fun parseChapterPage(doc: Document): List<SChapter> {
        return doc.select(".chapter-card").mapNotNull { card ->
            val onclick = card.attr("onclick")
            val chapterUrl = Regex("""location\.href='([^']+)'""").find(onclick)
                ?.groupValues?.get(1)
                ?: card.selectFirst("a[href]")?.attr("href")
                ?: return@mapNotNull null

            SChapter.create().apply {
                url = chapterUrl.removePrefix(baseUrl)
                name = card.selectFirst(".chapter-title")?.text()?.trim()
                    ?: "Chapter ${card.selectFirst(".chapter-number")?.text()?.trim().orEmpty()}"
                chapter_number = card.selectFirst(".chapter-number")?.text()?.trim()
                    ?.toFloatOrNull() ?: -1f
                date_upload = parseRelativeDate(card.selectFirst(".chapter-time")?.text().orEmpty())
            }
        }
    }

    private fun parseRelativeDate(dateText: String): Long {
        // Normalize NBSP and other whitespace
        val text = dateText.replace('\u00A0', ' ').trim().lowercase()
        val match = Regex("""(\d+)\s*(second|minute|hour|day|week|month|year)""").find(text)
            ?: return if (text.contains("just now")) System.currentTimeMillis() else 0L

        val amount = match.groupValues[1].toIntOrNull() ?: return 0L
        val calendar = Calendar.getInstance()
        when (match.groupValues[2]) {
            "second" -> calendar.add(Calendar.SECOND, -amount)
            "minute" -> calendar.add(Calendar.MINUTE, -amount)
            "hour" -> calendar.add(Calendar.HOUR_OF_DAY, -amount)
            "day" -> calendar.add(Calendar.DAY_OF_MONTH, -amount)
            "week" -> calendar.add(Calendar.WEEK_OF_YEAR, -amount)
            "month" -> calendar.add(Calendar.MONTH, -amount)
            "year" -> calendar.add(Calendar.YEAR, -amount)
        }
        return calendar.timeInMillis
    }

    // ======================== Chapter Content ========================

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.encodedPath))

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = Jsoup.parse(response.body.string())

        val content = doc.selectFirst("#chapterText")
            ?: doc.selectFirst(".chapter-content")
            ?: return ""

        // Remove ads and bloat
        content.select(
            "script, style, ins, iframe, " +
                ".chapter-ad-container, .ad-unit, .chapter-promo, [data-ad-position]",
        ).remove()

        return content.html()
    }

    // ======================== Preferences ========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = CLEAR_CHAPTER_CACHE_KEY
            title = "Clear All Cached Chapter Data"
            summary = "Toggle this to clear cached chapter pagination data for all novels."
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                clearAllChapterCache()
                true
            }
        }.also(screen::addPreference)
    }

    // ======================== Filters ========================

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Filters ignored with text search"),
        SortFilter(),
        OrderFilter(),
        StatusFilter(),
        ChapterRangeFilter(),
        GenreLogicFilter(),
        GenreFilter(),
        Filter.Separator(),
        Filter.Header("Tags (comma-separated, free text)"),
        TagsIncludeFilter(),
        TagsExcludeFilter(),
    )

    private class SortFilter :
        Filter.Select<String>(
            "Sort By",
            arrayOf("Rank", "Rating", "Views", "Bookmarks", "Latest Updates", "Newest Added"),
        ) {
        fun toUriPart(): String = when (state) {
            0 -> "rank"
            1 -> "rating"
            2 -> "views"
            3 -> "bookmarks"
            4 -> "updates"
            5 -> "new"
            else -> "rank"
        }
    }

    private class OrderFilter : Filter.Select<String>("Sort Order", arrayOf("Ascending", "Descending")) {
        fun toUriPart(): String = if (state == 1) "desc" else "asc"
    }

    private class StatusCheckBox(name: String, val value: String) : Filter.CheckBox(name)

    private class StatusFilter :
        Filter.Group<StatusCheckBox>(
            "Status",
            listOf(
                StatusCheckBox("Ongoing", "ongoing"),
                StatusCheckBox("Completed", "completed"),
                StatusCheckBox("Hiatus", "hiatus"),
            ),
        )

    private class ChapterRangeFilter :
        Filter.Select<String>(
            "Chapter Count",
            arrayOf("All", "Less than 50", "50-100", "100-500", "500-1000", "More than 1000"),
        ) {
        fun toUriPart(): String = when (state) {
            1 -> "<50"
            2 -> "50-100"
            3 -> "100-500"
            4 -> "500-1000"
            5 -> ">1000"
            else -> "all"
        }
    }

    private class GenreLogicFilter :
        Filter.Select<String>(
            "Genre Mode (for included genres)",
            arrayOf("AND (match all)", "OR (match any)"),
        ) {
        fun toUriPart(): String = if (state == 1) "OR" else "AND"
    }

    private class GenreTriState(name: String) : Filter.TriState(name)

    private class GenreFilter :
        Filter.Group<GenreTriState>(
            "Genres",
            listOf(
                "Action", "Adult", "Adventure", "Comedy", "Drama", "Eastern", "Ecchi",
                "Fan-Fiction", "Fantasy", "Game", "Gender-Bender", "Harem", "Historical",
                "Horror", "Isekai", "Josei", "LGBT+", "Magic", "Magical-Realism",
                "Martial-Arts", "Mature", "Mecha", "Mystery", "Psychological", "Romance",
                "School-Life", "Sci-Fi", "Seinen", "Shoujo", "Shounen", "Slice-of-Life",
                "Sports", "Supernatural", "Thriller", "Tragedy", "Wuxia", "Xianxia",
                "Xuanhuan", "Yaoi", "Yuri",
            ).map { GenreTriState(it) },
        )

    private class TagsIncludeFilter : Filter.Text("Include Tags")

    private class TagsExcludeFilter : Filter.Text("Exclude Tags")

    companion object {
        private const val CHAPTERS_PER_PAGE = 50
        private const val CLEAR_CHAPTER_CACHE_KEY = "lightnovelworld_clear_chapter_cache"
    }
}

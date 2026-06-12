package eu.kanade.tachiyomi.novelextension.en.lightnovelworld

import android.app.Application
import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.RefreshContext
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.lib.chapterutils.paginatedChapterList
import keiyoushi.lib.chapterutils.shouldReturnExisting
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
import java.util.Calendar

class LightNovelWorld :
    HttpSource(),
    NovelSource {

    override val name = "Light Novel World"
    override val baseUrl = "https://lightnovelworld.org"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient
    private val json: Json by injectLazy()

    override val isNovelSource = true

    init {
        migrateDeleteLegacyChapterCache()
    }

    private fun migrateDeleteLegacyChapterCache() {
        val legacyDir = File(Injekt.get<Application>().cacheDir, "lightnovelworld_chapters")
        if (legacyDir.exists()) {
            val deleted = legacyDir.deleteRecursively()
            Log.d(TAG, "migration: deleted legacy chapter cache dir (success=$deleted)")
        }
    }

    override fun imageUrlParse(response: Response): String = ""

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

    override suspend fun getChapterList(manga: SManga, context: RefreshContext): List<SChapter> {
        val response = client.newCall(chapterListRequest(manga)).execute()
        val page1Doc = Jsoup.parse(response.body.string())
        val basePath = response.request.url.encodedPath

        val totalPages = page1Doc.select("#pageSelect option").size.coerceAtLeast(1)
        val currentTotal = Regex("""A total of (\d+) chapters""")
            .find(page1Doc.selectFirst(".chapters-description")?.text().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0

        Log.d(TAG, "getChapterList: url=$basePath existing=${context.existingChapters.size} siteTotal=$currentTotal totalPages=$totalPages")

        if (shouldReturnExisting(context.existingChapters.size, currentTotal)) {
            Log.d(TAG, "getChapterList: count unchanged — returning existing")
            return context.existingChapters
        }

        return paginatedChapterList(
            context = context,
            siteTotal = currentTotal,
            assumedPageSize = CHAPTERS_PER_PAGE,
            fetchPage = { page ->
                val doc = if (page == 1) {
                    page1Doc
                } else {
                    val pageResponse = client.newCall(GET("$baseUrl$basePath?page=$page", headers)).execute()
                    Jsoup.parse(pageResponse.body.string())
                }
                Pair(parseChapterPage(doc), page < totalPages)
            },
            sortChapters = { it },
        ).reversed()
    }

    // Fallback path for when getChapterList is not used; performs a full fetch with no optimisation.
    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())
        val basePath = response.request.url.encodedPath
        val totalPages = doc.select("#pageSelect option").size.coerceAtLeast(1)
        val chapters = parseChapterPage(doc).toMutableList()
        for (page in 2..totalPages) {
            val pageResponse = client.newCall(GET("$baseUrl$basePath?page=$page", headers)).execute()
            chapters += parseChapterPage(Jsoup.parse(pageResponse.body.string()))
        }
        return chapters.reversed()
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
        val text = dateText.replace(' ', ' ').trim().lowercase()
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
        private const val TAG = "LightNovelWorld"
        private const val CHAPTERS_PER_PAGE = 50
    }
}

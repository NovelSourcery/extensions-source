package eu.kanade.tachiyomi.extension.en.novelfire

import android.app.Application
import android.content.SharedPreferences
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/**
 * NovelFire novel source - ported from LN Reader plugin
 * @see https://github.com/LNReader/lnreader-plugins novelfire.ts
 * Features: Advanced filters, JSON chapter API, rate limiting detection,
 *           tag caching with include/exclude advanced search
 */
class NovelFire : HttpSource(), NovelSource, ConfigurableSource {

    override val name = "NovelFire"
    override val baseUrl = "https://novelfire.net"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient
    private val json: Json by injectLazy()

    override val isNovelSource = true

    // SharedPreferences for tag caching and settings
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // In-memory tag cache (id -> name)
    private var tagCache: List<TagItem> = emptyList()

    // Custom error for rate limiting
    private class NovelFireThrottlingError(message: String = "Novel Fire is rate limiting requests") : Exception(message)

    // Custom error for AJAX not found
    private class NovelFireAjaxNotFound(message: String = "Novel Fire says its Ajax interface is not found") : Exception(message)

    // ======================== Tag Caching ========================

    @Serializable
    data class TagItem(val id: Int, val name: String)

    @Serializable
    data class TagResponse(val status: Int = 0, val data: List<TagItem> = emptyList())

    /**
     * Load cached tags from SharedPreferences into memory.
     */
    private fun loadCachedTags(): List<TagItem> {
        if (tagCache.isNotEmpty()) return tagCache
        val cached = preferences.getString(TAGS_CACHE_KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<TagItem>>(cached).also { tagCache = it }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Fetch all tags from the NovelFire AJAX endpoint and cache them.
     * Called lazily on first search that uses tags.
     */
    private fun fetchAndCacheTags(): List<TagItem> {
        return try {
            val response = client.newCall(GET("$baseUrl/ajax/getTags?term=", headers)).execute()
            val body = response.body.string()
            val tagResponse = json.decodeFromString<TagResponse>(body)
            val tags = tagResponse.data
            if (tags.isNotEmpty()) {
                preferences.edit()
                    .putString(TAGS_CACHE_KEY, json.encodeToString(tags))
                    .putLong(TAGS_CACHE_TIME_KEY, System.currentTimeMillis())
                    .apply()
                tagCache = tags
            }
            tags
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get tags, using cache if available, otherwise fetching from network.
     * Refreshes cache if older than 7 days.
     */
    private fun getTags(): List<TagItem> {
        val cached = loadCachedTags()
        if (cached.isNotEmpty()) {
            val cacheTime = preferences.getLong(TAGS_CACHE_TIME_KEY, 0L)
            val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
            if (System.currentTimeMillis() - cacheTime < sevenDaysMs) {
                return cached
            }
        }
        return fetchAndCacheTags().ifEmpty { cached }
    }

    /**
     * Resolve comma-separated tag names to their numeric IDs.
     * Case-insensitive, partial matching supported.
     */
    private fun resolveTagNames(input: String, tags: List<TagItem>): List<Int> {
        if (input.isBlank()) return emptyList()
        val names = input.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return names.mapNotNull { name ->
            // Try exact match first, then prefix match
            tags.find { it.name.lowercase() == name }?.id
                ?: tags.find { it.name.lowercase().startsWith(name) }?.id
        }
    }

    override fun imageUrlParse(response: Response): String = ""

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        return parseChapterContent(response)
    }

    // ======================== Popular/Browse ========================

    override fun popularMangaRequest(page: Int): Request {
        val filters = getFilterList()
        return searchMangaRequest(page, "", filters)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return searchMangaParse(response)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/search-adv?ctgcon=and&totalchapter=0&ratcon=min&rating=0&status=-1&sort=date&tagcon=and&page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        checkCloudflare(doc)
        return parseNovelList(doc)
    }

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // If there's a search query, use the simple search endpoint
        if (query.isNotEmpty()) {
            return GET("$baseUrl/search?keyword=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page", headers)
        }

        // Use advanced search with filters
        val url = "$baseUrl/search-adv".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        // Collect tag inputs to resolve later
        var tagOperator = "and"

        // Apply filters if provided
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> url.addQueryParameter("sort", filter.toUriPart())
                is StatusFilter -> url.addQueryParameter("status", filter.toUriPart())
                is GenreOperatorFilter -> url.addQueryParameter("ctgcon", filter.toUriPart())
                is GenreFilter -> {
                    val selected = filter.state.filter { it.state }.map { it.value }
                    if (selected.isNotEmpty()) {
                        selected.forEach { genre ->
                            url.addQueryParameter("categories[]", genre)
                        }
                    }
                }
                is LanguageFilter -> {
                    val selected = filter.state.filter { it.state }.map { it.value }
                    if (selected.isNotEmpty()) {
                        selected.forEach { lang ->
                            url.addQueryParameter("country_id[]", lang)
                        }
                    }
                }
                is RatingOperatorFilter -> url.addQueryParameter("ratcon", filter.toUriPart())
                is RatingFilter -> url.addQueryParameter("rating", filter.toUriPart())
                is ChaptersFilter -> url.addQueryParameter("totalchapter", filter.toUriPart())
                is TagOperatorFilter -> tagOperator = filter.toUriPart()
                is TagFilter -> {
                    // Tri-state tags: included = STATE_INCLUDE, excluded = STATE_EXCLUDE
                    val included = filter.state.filter { it.isIncluded() }.map { it.id }
                    val excluded = filter.state.filter { it.isExcluded() }.map { it.id }
                    included.forEach { id ->
                        url.addQueryParameter("tags[]", id.toString())
                    }
                    excluded.forEach { id ->
                        url.addQueryParameter("tags_excluded[]", id.toString())
                    }
                    // If tags cache is empty, fetch tags now for next time
                    if (tagCache.isEmpty()) {
                        fetchAndCacheTags()
                    }
                }
                else -> {}
            }
        }

        // Always add tagcon
        url.addQueryParameter("tagcon", tagOperator)

        // Set defaults if no filters
        if (filters.isEmpty()) {
            url.addQueryParameter("ctgcon", "and")
            url.addQueryParameter("totalchapter", "0")
            url.addQueryParameter("ratcon", "min")
            url.addQueryParameter("rating", "0")
            url.addQueryParameter("status", "-1")
            url.addQueryParameter("sort", "rank-top")
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        checkCloudflare(doc)
        return parseNovelList(doc)
    }

    private fun parseNovelList(doc: Document): MangasPage {
        val novels = doc.select(".novel-item").mapNotNull { element ->
            try {
                // Multiple fallbacks for title extraction
                val title = element.selectFirst("a")?.attr("title")?.takeIf { it.isNotBlank() }
                    ?: element.selectFirst(".novel-title")?.text()?.takeIf { it.isNotBlank() }
                    ?: element.selectFirst("h4")?.text()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val novelUrl = element.selectFirst("a")?.attr("href")?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val coverElement = element.selectFirst(".novel-cover > img")
                val coverUrl = coverElement?.attr("data-src") ?: coverElement?.attr("src")

                SManga.create().apply {
                    this.title = title
                    this.url = novelUrl.removePrefix(baseUrl)
                    // Default cover fallback
                    thumbnail_url = when {
                        coverUrl.isNullOrEmpty() -> "$baseUrl/images/no-cover.jpg"
                        coverUrl.startsWith("http") -> coverUrl
                        coverUrl.startsWith("/") -> baseUrl + coverUrl
                        else -> coverUrl
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        // Check for next page - handles multiple pagination formats
        val hasNextPage = doc.selectFirst(".pagination .page-item:not(.disabled) a[rel=\"next\"]") != null ||
            doc.selectFirst(".pagination li.page-item a.page-link[rel=\"next\"]") != null ||
            doc.selectFirst(".pagination .page-item.active + .page-item:not(.disabled) a") != null ||
            doc.selectFirst("a.page-link[aria-label*=\"Next\"]") != null ||
            doc.selectFirst("nav[aria-label*=\"Pagination\"] a[rel=\"next\"]") != null ||
            doc.selectFirst("nav[role=\"navigation\"] a[rel=\"next\"]") != null

        return MangasPage(novels, hasNextPage)
    }

    // ======================== Novel Details ========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())
        checkCloudflare(doc)

        val manga = SManga.create().apply {
            // Multiple fallbacks for title
            title = doc.selectFirst(".novel-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst(".cover > img")?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: "No Title Found"

            // Get cover image
            val coverElement = doc.selectFirst(".cover > img")
            val coverUrl = coverElement?.attr("src") ?: coverElement?.attr("data-src")
            // Default cover fallback
            thumbnail_url = when {
                coverUrl.isNullOrEmpty() -> "$baseUrl/images/no-cover.jpg"
                coverUrl.startsWith("http") -> coverUrl
                coverUrl.startsWith("/") -> baseUrl + coverUrl
                else -> coverUrl
            }

            // Get genres
            genre = doc.select(".categories .property-item")
                .joinToString(", ") { it.text().trim() }

            // Get summary
            val summary = doc.selectFirst(".summary .content")?.text()?.trim()
            description = summary?.replace("Show More", "") ?: "No Summary Found"

            // Get author
            author = doc.selectFirst(".author .property-item > span")?.text() ?: doc.selectFirst(".author .property-item")?.text() ?: "No Author Found"

            // Get status
            val statusText = doc.selectFirst(".header-stats .ongoing, .header-stats .completed")?.text()?.lowercase()
            status = when {
                statusText?.contains("ongoing") == true -> SManga.ONGOING
                statusText?.contains("completed") == true -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }

        return manga
    }

    // ======================== Chapters ========================

    // JSON response data classes
    @Serializable
    data class ChapterAjaxResponse(
        val data: List<ChapterData> = emptyList(),
        val recordsTotal: Int = 0,
        val recordsFiltered: Int = 0,
    )

    @Serializable
    data class ChapterData(
        @SerialName("n_sort") val nSort: Int = 0,
        val slug: String = "",
        val title: String = "",
        @SerialName("created_at") val createdAt: String = "",
    )

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()
        val doc = Jsoup.parse(body)
        checkCloudflare(doc)

        // Get the novel URL path for building chapter URLs
        val novelPath = response.request.url.encodedPath.trimStart('/')

        // Try to extract post_id from the page
        val postId = extractPostId(doc)

        return if (postId != null) {
            // Use JSON Ajax endpoint (primary method)
            try {
                getAllChaptersFromAjax(novelPath, postId)
            } catch (e: Exception) {
                // Fall back to HTML parsing
                val totalChaptersText = doc.selectFirst(".header-stats .icon-book-open")?.parent()?.text()?.trim() ?: "0"
                val totalChapters = totalChaptersText.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
                val pages = (totalChapters + 99) / 100
                getAllChaptersFromHtml(novelPath, pages)
            }
        } else {
            // Fallback to HTML parsing
            val totalChaptersText = doc.selectFirst(".header-stats .icon-book-open")?.parent()?.text()?.trim() ?: "0"
            val totalChapters = totalChaptersText.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
            val pages = (totalChapters + 99) / 100
            getAllChaptersFromHtml(novelPath, pages)
        }
    }

    private fun extractPostId(doc: Document): String? {
        // Try to extract from report button: report-post_id="2196"
        val reportButton = doc.selectFirst("#novel-report[report-post_id]")
        if (reportButton != null) {
            val postId = reportButton.attr("report-post_id")
            if (postId.isNotEmpty()) return postId
        }

        // Try to extract from script: post_id='2196'
        val scripts = doc.select("script").html()
        val postIdMatch = Regex("""post_id\s*[=:]\s*['"]?(\d+)['"]?""").find(scripts)
        if (postIdMatch != null) {
            return postIdMatch.groupValues[1]
        }

        return null
    }

    private fun getAllChaptersFromAjax(novelPath: String, postId: String): List<SChapter> {
        val timestamp = System.currentTimeMillis()
        val ajaxUrl = "$baseUrl/listChapterDataAjax".toHttpUrl().newBuilder()
            .addQueryParameter("post_id", postId)
            .addQueryParameter("draw", "1")
            .addQueryParameter("columns[0][data]", "title")
            .addQueryParameter("columns[0][name]", "")
            .addQueryParameter("columns[0][searchable]", "true")
            .addQueryParameter("columns[0][orderable]", "false")
            .addQueryParameter("columns[0][search][value]", "")
            .addQueryParameter("columns[0][search][regex]", "false")
            .addQueryParameter("columns[1][data]", "created_at")
            .addQueryParameter("columns[1][name]", "")
            .addQueryParameter("columns[1][searchable]", "true")
            .addQueryParameter("columns[1][orderable]", "true")
            .addQueryParameter("columns[1][search][value]", "")
            .addQueryParameter("columns[1][search][regex]", "false")
            .addQueryParameter("columns[2][data]", "n_sort")
            .addQueryParameter("columns[2][name]", "")
            .addQueryParameter("columns[2][searchable]", "false")
            .addQueryParameter("columns[2][orderable]", "true")
            .addQueryParameter("columns[2][search][value]", "")
            .addQueryParameter("columns[2][search][regex]", "false")
            .addQueryParameter("order[0][column]", "2")
            .addQueryParameter("order[0][dir]", "asc")
            .addQueryParameter("start", "0")
            .addQueryParameter("length", "-1")
            .addQueryParameter("search[value]", "")
            .addQueryParameter("search[regex]", "false")
            .addQueryParameter("_", timestamp.toString())
            .build()

        val response = client.newCall(GET(ajaxUrl.toString(), headers)).execute()
        val responseBody = response.body.string()

        // Check for rate limiting
        if (responseBody.contains("You are being rate limited")) {
            throw NovelFireThrottlingError()
        }

        // Check for Ajax not found
        if (responseBody.contains("Ajax Interface is not found")) {
            throw NovelFireAjaxNotFound()
        }

        val ajaxResponse = json.decodeFromString<ChapterAjaxResponse>(responseBody)

        // Build chapter URLs using the novel path and slug
        // Format: /book/novel-name/chapter-number
        return ajaxResponse.data.mapIndexed { index, chapter ->
            SChapter.create().apply {
                name = chapter.title
                // Use chapter-number format as slug often leads to 404s
                url = "/$novelPath/chapter-${chapter.nSort}"
                date_upload = 0L
                chapter_number = chapter.nSort.toFloat()
            }
        }.sortedBy { it.chapter_number } // Sort chapters by number ascending
    }

    private fun getAllChaptersFromHtml(novelPath: String, pages: Int): List<SChapter> {
        val allChapters = mutableListOf<SChapter>()

        for (page in 1..pages.coerceAtLeast(1)) {
            val pageUrl = "$baseUrl/$novelPath/chapters?page=$page"
            val response = client.newCall(GET(pageUrl, headers)).execute()
            val body = response.body.string()

            // Check for rate limiting
            if (body.contains("You are being rate limited")) {
                throw NovelFireThrottlingError()
            }

            val doc = Jsoup.parse(body)
            checkCloudflare(doc)

            doc.select(".chapter-list li").forEach { element ->
                val linkElement = element.selectFirst("a") ?: return@forEach
                val chapterName = linkElement.attr("title").ifEmpty { linkElement.text() }
                val chapterUrl = linkElement.attr("href")

                if (chapterUrl.isNotEmpty()) {
                    allChapters.add(
                        SChapter.create().apply {
                            name = chapterName
                            url = chapterUrl.removePrefix(baseUrl)
                            date_upload = 0L
                        },
                    )
                }
            }
        }

        // Sort by chapter number numerically (extract number from name)
        return allChapters.sortedWith(
            compareBy { chapter ->
                val match = Regex("""(?:chapter|ch\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(chapter.name)
                match?.groupValues?.get(1)?.toDoubleOrNull() ?: Double.MAX_VALUE
            },
        )
    }

    // ======================== Chapter Content ========================

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.encodedPath
        return listOf(Page(0, url))
    }

    private fun parseChapterContent(response: Response): String {
        val doc = Jsoup.parse(response.body.string())
        checkCloudflare(doc)

        // Remove bloat elements
        doc.select(".box-ads, .box-notification").remove()

        // Remove elements starting with 'nf' (NovelFire specific)
        doc.select("*").forEach { element ->
            val tagName = element.tagName()
            if (tagName.startsWith("nf", ignoreCase = true)) {
                element.remove()
            }
        }

        // Get chapter content
        val content = doc.selectFirst("#content")?.html()

        return content ?: ""
    }

    // ======================== Cloudflare Detection ========================

    private fun checkCloudflare(doc: Document) {
        val title = doc.title()
        if (title.contains("Cloudflare", ignoreCase = true) ||
            doc.selectFirst("title")?.text()?.contains("Cloudflare", ignoreCase = true) == true
        ) {
            throw Exception("Cloudflare challenge detected. Please open in WebView.")
        }
    }

    // ======================== Filters ========================

    override fun getFilterList(): FilterList {
        val cachedTags = loadCachedTags()
        val tagList = cachedTags.sortedBy { it.name.lowercase() }.map { TagTriState(it.name, it.id) }
        val tagNote = if (cachedTags.isEmpty()) {
            "Tags will be fetched on first search with tags"
        } else {
            "${cachedTags.size} tags cached"
        }
        return FilterList(
            SortFilter(),
            StatusFilter(),
            GenreOperatorFilter(),
            GenreFilter(),
            LanguageFilter(),
            RatingOperatorFilter(),
            RatingFilter(),
            ChaptersFilter(),
            Filter.Separator(),
            Filter.Header("Tags ($tagNote)"),
            TagOperatorFilter(),
            if (tagList.isNotEmpty()) TagFilter(tagList) else TagFilter(emptyList()),
        )
    }

    // ======================== Preferences ========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = CLEAR_TAG_CACHE_KEY
            title = "Clear Tag Cache"
            summary = "Toggle this to clear the cached tag list (${loadCachedTags().size} tags). Tags will be re-fetched on next search."
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                preferences.edit()
                    .remove(TAGS_CACHE_KEY)
                    .remove(TAGS_CACHE_TIME_KEY)
                    .apply()
                tagCache = emptyList()
                true
            }
        }.also(screen::addPreference)
    }

    // Filter Classes
    private class SortFilter : Filter.Select<String>(
        "Sort Results By",
        arrayOf(
            "Rank (Top)",
            "Rating Score (Top)",
            "Review Count (Most)",
            "Comment Count (Most)",
            "Bookmark Count (Most)",
            "Today Views (Most)",
            "Monthly Views (Most)",
            "Total Views (Most)",
            "Title (A>Z)",
            "Title (Z>A)",
            "Last Updated (Newest)",
            "Chapter Count (Most)",
        ),
    ) {
        fun toUriPart(): String {
            return when (state) {
                0 -> "rank-top"
                1 -> "rating-score-top"
                2 -> "review"
                3 -> "comment"
                4 -> "bookmark"
                5 -> "today-view"
                6 -> "monthly-view"
                7 -> "total-view"
                8 -> "abc"
                9 -> "cba"
                10 -> "date"
                11 -> "chapter-count-most"
                else -> "rank-top"
            }
        }
    }

    private class StatusFilter : Filter.Select<String>(
        "Translation Status",
        arrayOf("All", "Completed", "Ongoing"),
    ) {
        fun toUriPart(): String {
            return when (state) {
                0 -> "-1"
                1 -> "1"
                2 -> "0"
                else -> "-1"
            }
        }
    }

    private class GenreOperatorFilter : Filter.Select<String>(
        "Genres (And/Or/Exclude)",
        arrayOf("AND", "OR", "EXCLUDE"),
    ) {
        fun toUriPart(): String {
            return when (state) {
                0 -> "and"
                1 -> "or"
                2 -> "exclude"
                else -> "and"
            }
        }
    }

    private class GenreFilter : Filter.Group<GenreCheckBox>(
        "Genres",
        listOf(
            GenreCheckBox("Action", "3"),
            GenreCheckBox("Adult", "28"),
            GenreCheckBox("Adventure", "4"),
            GenreCheckBox("Anime", "46"),
            GenreCheckBox("Arts", "47"),
            GenreCheckBox("Comedy", "5"),
            GenreCheckBox("Drama", "24"),
            GenreCheckBox("Eastern", "44"),
            GenreCheckBox("Ecchi", "26"),
            GenreCheckBox("Fan-fiction", "48"),
            GenreCheckBox("Fantasy", "6"),
            GenreCheckBox("Game", "19"),
            GenreCheckBox("Gender Bender", "25"),
            GenreCheckBox("Harem", "7"),
            GenreCheckBox("Historical", "12"),
            GenreCheckBox("Horror", "37"),
            GenreCheckBox("Isekai", "49"),
            GenreCheckBox("Josei", "2"),
            GenreCheckBox("Lgbt+", "45"),
            GenreCheckBox("Magic", "50"),
            GenreCheckBox("Magical Realism", "51"),
            GenreCheckBox("Manhua", "52"),
            GenreCheckBox("Martial Arts", "15"),
            GenreCheckBox("Mature", "8"),
            GenreCheckBox("Mecha", "34"),
            GenreCheckBox("Military", "53"),
            GenreCheckBox("Modern Life", "54"),
            GenreCheckBox("Movies", "55"),
            GenreCheckBox("Mystery", "16"),
            GenreCheckBox("Other", "64"),
            GenreCheckBox("Psychological", "9"),
            GenreCheckBox("Realistic Fiction", "56"),
            GenreCheckBox("Reincarnation", "43"),
            GenreCheckBox("Romance", "1"),
            GenreCheckBox("School Life", "21"),
            GenreCheckBox("Sci-fi", "20"),
            GenreCheckBox("Seinen", "10"),
            GenreCheckBox("Shoujo", "38"),
            GenreCheckBox("Shoujo Ai", "57"),
            GenreCheckBox("Shounen", "17"),
            GenreCheckBox("Shounen Ai", "39"),
            GenreCheckBox("Slice of Life", "13"),
            GenreCheckBox("Smut", "29"),
            GenreCheckBox("Sports", "42"),
            GenreCheckBox("Supernatural", "18"),
            GenreCheckBox("System", "58"),
            GenreCheckBox("Tragedy", "32"),
            GenreCheckBox("Urban", "63"),
            GenreCheckBox("Urban Life", "59"),
            GenreCheckBox("Video Games", "60"),
            GenreCheckBox("War", "61"),
            GenreCheckBox("Wuxia", "31"),
            GenreCheckBox("Xianxia", "23"),
            GenreCheckBox("Xuanhuan", "22"),
            GenreCheckBox("Yaoi", "14"),
            GenreCheckBox("Yuri", "62"),
        ),
    )
    private class GenreCheckBox(name: String, val value: String) : Filter.CheckBox(name)

    private class LanguageFilter : Filter.Group<LanguageCheckBox>(
        "Language",
        listOf(
            LanguageCheckBox("Chinese", "1"),
            LanguageCheckBox("Korean", "2"),
            LanguageCheckBox("Japanese", "3"),
            LanguageCheckBox("English", "4"),
        ),
    )
    private class LanguageCheckBox(name: String, val value: String) : Filter.CheckBox(name)

    private class RatingOperatorFilter : Filter.Select<String>(
        "Rating (Min/Max)",
        arrayOf("Min", "Max"),
    ) {
        fun toUriPart(): String {
            return when (state) {
                0 -> "min"
                1 -> "max"
                else -> "min"
            }
        }
    }

    private class RatingFilter : Filter.Select<String>(
        "Rating",
        arrayOf("All", "1", "2", "3", "4", "5"),
    ) {
        fun toUriPart(): String {
            return when (state) {
                0 -> "0"
                1 -> "1"
                2 -> "2"
                3 -> "3"
                4 -> "4"
                5 -> "5"
                else -> "0"
            }
        }
    }

    private class ChaptersFilter : Filter.Select<String>(
        "Chapters",
        arrayOf(
            "All",
            "<50",
            "50-100",
            "100-200",
            "200-500",
            "500-1000",
            ">1000",
        ),
    ) {
        fun toUriPart(): String {
            return when (state) {
                0 -> "0"
                1 -> "1,49"
                2 -> "50,100"
                3 -> "100,200"
                4 -> "200,500"
                5 -> "500,1000"
                6 -> "1001,1000000"
                else -> "0"
            }
        }
    }

    // Tag filters for include/exclude advanced search (tri-state: include/exclude/ignore)
    private class TagOperatorFilter : Filter.Select<String>(
        "Tag Mode (for included tags)",
        arrayOf("AND (match all)", "OR (match any)"),
    ) {
        fun toUriPart(): String = if (state == 1) "or" else "and"
    }

    private class TagTriState(name: String, val id: Int) : Filter.TriState(name)

    private class TagFilter(tags: List<TagTriState>) : Filter.Group<TagTriState>("Tags", tags)

    companion object {
        private const val TAGS_CACHE_KEY = "novelfire_tags_cache"
        private const val TAGS_CACHE_TIME_KEY = "novelfire_tags_cache_time"
        private const val CLEAR_TAG_CACHE_KEY = "novelfire_clear_tag_cache"
    }
}

package eu.kanade.tachiyomi.extension.en.ranobes

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.URLEncoder

class Ranobes : HttpSource(), NovelSource, ConfigurableSource {

    private val preferences: SharedPreferences by getPreferencesLazy()

    /**
     * Whether to reverse the chapter list (show oldest first).
     * Default is false (newest first).
     */
    private val reverseChapterList: Boolean
        get() = preferences.getBoolean(PREF_REVERSE_CHAPTERS, false)

    override val name = "Ranobes"
    override val baseUrl = "https://ranobes.net"
    override val lang = "en"
    override val supportsLatest = true
    override val isNovelSource = true

    override val client = network.cloudflareClient

    // Cache dle_hash for search
    private var dleHash: String? = null

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        return if (page == 1) {
            GET("$baseUrl/ranking/", headers)
        } else {
            val formBody = FormBody.Builder()
                .add("cstart", page.toString())
                .add("ajax", "true")
                .build()
            POST("$baseUrl/ranking/", headers, formBody)
        }
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())

        val novels = document.select("article.rank-story").map { article ->
            SManga.create().apply {
                val link = article.selectFirst("h2.title a") ?: return@map null
                url = link.attr("href").removePrefix(baseUrl)
                title = link.text().trim()

                thumbnail_url = article.selectFirst("figure img")?.attr("src")?.let {
                    if (it.startsWith("http")) it else baseUrl + it
                }

                description = article.selectFirst("div.moreless__short")?.text()?.trim()

                genre = article.select("div.rank-story-genre a").joinToString(", ") {
                    it.text().trim()
                }
            }
        }.filterNotNull()

        // Check if there's more pages by looking for pagination
        val hasNextPage = document.select("div.pages a").isNotEmpty()

        return MangasPage(novels, hasNextPage)
    }

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/updates/page/$page/", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())

        val novels = document.select("div.block.story_line.story_line-img").map { block ->
            val link = block.selectFirst("a") ?: return@map null

            SManga.create().apply {
                // URL goes to chapter, need to extract novel URL
                url = link.attr("href").let { href ->
                    // Convert chapter URL to novel URL
                    // e.g., /cultivation-being-immortal-1206585/3089467.html -> /novels/1206585-cultivation-being-immortal.html
                    val match = Regex("""(/[^/]+-(\d+)/\d+\.html)""").find(href)
                    if (match != null) {
                        val novelSlug = href.split("/")[1].substringBeforeLast("-")
                        val novelId = match.groupValues[2]
                        "/novels/$novelId-$novelSlug.html"
                    } else {
                        href
                    }
                }
                title = block.selectFirst("h3.title")?.text()?.trim() ?: ""

                thumbnail_url = block.selectFirst("i.image.cover")?.attr("style")?.let {
                    extractBackgroundUrl(it)
                }
            }
        }.filterNotNull().distinctBy { it.url }

        val hasNextPage = document.selectFirst("div.pages a:contains(${response.request.url.toString().substringAfter("page/").substringBefore("/").toIntOrNull()?.plus(1) ?: 2})") != null

        return MangasPage(novels, hasNextPage)
    }

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // Apply filters
        var genreId: String? = null
        var statusId: String? = null
        var sortBy: String? = null

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> genreId = filter.getSelectedValue()
                is StatusFilter -> statusId = filter.getSelectedValue()
                is SortFilter -> sortBy = filter.getSelectedValue()
                else -> {}
            }
        }

        // Genre filter - uses /tags/genre/ format
        if (!genreId.isNullOrEmpty()) {
            val sortPath = when (sortBy) {
                "views" -> ""
                "rating" -> "rating/"
                "dateDesc" -> "d/"
                "dateAsc" -> "o/"
                else -> ""
            }
            return GET("$baseUrl/tags/genre/$genreId/${if (page > 1) "page/$page/" else ""}", headers)
        }

        // Status filter (Browse page)
        if (!statusId.isNullOrEmpty()) {
            return GET("$baseUrl/novels/$statusId${if (page > 1) "page/$page/" else ""}", headers)
        }

        // Use search if query provided
        if (query.isNotBlank()) {
            val url = if (page == 1) {
                "$baseUrl/search/${URLEncoder.encode(query, "UTF-8")}/"
            } else {
                "$baseUrl/search/${URLEncoder.encode(query, "UTF-8")}/page/$page/"
            }
            return GET(url, headers)
        }

        // Default to popular
        return popularMangaRequest(page)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val requestUrl = response.request.url.toString()

        // Genre browse pages have similar structure to search
        if (requestUrl.contains("/genres/")) {
            return parseGenrePage(response)
        }

        // Status browse pages
        if (requestUrl.contains("/novels/") && !requestUrl.contains("/novels/")) {
            return parseNovelsPage(response)
        }

        val document = Jsoup.parse(response.body.string())

        val novels = document.select("article.block.story.shortstory").map { article ->
            SManga.create().apply {
                val link = article.selectFirst("h2.title a") ?: return@map null
                url = link.attr("href").removePrefix(baseUrl)
                title = link.text().trim()

                thumbnail_url = article.selectFirst("figure.cover")?.attr("style")?.let {
                    extractBackgroundUrl(it)
                }

                description = article.selectFirst("div.cont-in > div")?.text()?.trim()

                genre = article.selectFirst("div.r-rate div.grey")?.text()?.trim()

                // Status from link
                val statusLink = article.selectFirst("a[title*=translated]")?.text()?.lowercase() ?: ""
                status = when {
                    statusLink.contains("completed") -> SManga.COMPLETED
                    statusLink.contains("ongoing") -> SManga.ONGOING
                    else -> SManga.UNKNOWN
                }
            }
        }.filterNotNull()

        val hasNextPage = document.select("div.pages a").any {
            it.text().toIntOrNull()?.let { num ->
                num > (response.request.url.toString().substringAfter("page/").substringBefore("/").toIntOrNull() ?: 1)
            } ?: false
        }

        return MangasPage(novels, hasNextPage)
    }

    private fun parseGenrePage(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())

        val novels = document.select("article.block.story.shortstory, article.rank-story").mapNotNull { article ->
            val link = article.selectFirst("h2.title a") ?: return@mapNotNull null

            SManga.create().apply {
                url = link.attr("href").removePrefix(baseUrl)
                title = link.text().trim()

                thumbnail_url = article.selectFirst("figure img, figure.cover")?.let {
                    it.attr("src").ifEmpty { extractBackgroundUrl(it.attr("style") ?: "") }
                }?.let { if (it.startsWith("http")) it else baseUrl + it }

                description = article.selectFirst("div.moreless__short")?.text()?.trim()

                genre = article.select("div.rank-story-genre a, .genre a").joinToString(", ") {
                    it.text().trim()
                }
            }
        }

        val hasNextPage = document.select("div.pages a").isNotEmpty()
        return MangasPage(novels, hasNextPage)
    }

    private fun parseNovelsPage(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            // Title
            title = document.selectFirst("h1.title")?.ownText()?.trim() ?: ""

            // Cover
            thumbnail_url = document.selectFirst("div.poster img")?.attr("src")?.let {
                if (it.startsWith("http")) it else baseUrl + it
            }

            // Author
            author = document.selectFirst("li:contains(Authors) span.tag_list a")?.text()?.trim()

            // Status
            val statusCoo = document.selectFirst("li:contains(Status in COO) a")?.text()?.lowercase() ?: ""
            val statusTrans = document.selectFirst("li:contains(Translation) a")?.text()?.lowercase() ?: ""
            status = when {
                statusTrans.contains("completed") -> SManga.COMPLETED
                statusCoo.contains("completed") && statusTrans.contains("ongoing") -> SManga.ONGOING
                statusTrans.contains("ongoing") -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }

            // Genres - combine genres, events, and tags
            val genres = mutableListOf<String>()
            document.select("#mc-fs-genre .links a").forEach { genres.add(it.text().trim()) }
            document.select("#mc-fs-keyw .links a").take(10).forEach { genres.add(it.text().trim()) }
            genre = genres.distinct().joinToString(", ")

            // Description
            description = buildString {
                document.selectFirst("div.moreless__full")?.text()?.let {
                    append(it.replace("Collapse", "").trim())
                } ?: document.selectFirst("div.moreless__short")?.text()?.let {
                    append(it.replace("Read more", "").trim())
                }

                // Add alternative titles
                document.selectFirst("h1.title span.subtitle")?.text()?.let {
                    if (it.isNotBlank()) {
                        append("\n\nAlternative: $it")
                    }
                }

                // Add additional info
                document.selectFirst("li:contains(Year) span")?.text()?.let {
                    append("\n\nYear: $it")
                }
                document.selectFirst("li:contains(Language) span a")?.text()?.let {
                    append("\nOriginal Language: $it")
                }
                document.selectFirst("li:contains(In original) a")?.text()?.let {
                    append("\nOriginal Chapters: $it")
                }
                document.selectFirst("li:contains(Translated) span.grey")?.text()?.let {
                    append("\nTranslated: $it")
                }
            }
        }
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request {
        // Extract novel ID from URL like /novels/22198-martial-world-v812312.html
        val novelId = manga.url.substringAfter("/novels/").substringBefore("-")
        return GET("$baseUrl/chapters/$novelId/", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val allChapters = mutableListOf<SChapter>()
        var currentPage = 1
        var document = Jsoup.parse(response.body.string())

        // Get base URL for pagination (without trailing slash issues)
        val baseChapterUrl = response.request.url.toString()
            .substringBefore("/page/")
            .trimEnd('/')

        while (true) {
            val chapters = parseChaptersFromDocument(document)
            allChapters.addAll(chapters)

            // Check for more pages from the JSON data or dropdown
            val jsonData = extractWindowData(document)
            val maxPage = jsonData?.get("pages_count")?.toString()?.toIntOrNull()
                ?: document.select("select option").mapNotNull { it.attr("value").toIntOrNull() }.maxOrNull()
                ?: 1

            if (currentPage >= maxPage) break

            currentPage++
            val nextUrl = "$baseChapterUrl/page/$currentPage/"
            val nextResponse = client.newCall(GET(nextUrl, headers)).execute()
            document = Jsoup.parse(nextResponse.body.string())
        }

        return if (reverseChapterList) allChapters.reversed() else allChapters
    }

    /**
     * Extract window.__DATA__ JSON from the page
     */
    private fun extractWindowData(document: org.jsoup.nodes.Document): Map<String, Any>? {
        val script = document.select("script").find { it.data().contains("window.__DATA__") }
        if (script == null) return null

        val scriptContent = script.data()

        // Try multiple patterns to extract JSON
        val jsonStr = run {
            // Pattern 1: window.__DATA__ = {...};
            val match1 = Regex("""window\.__DATA__\s*=\s*(\{.+\});""", RegexOption.DOT_MATCHES_ALL)
                .find(scriptContent)
            if (match1 != null) return@run match1.groupValues[1]

            // Pattern 2: window.__DATA__ = {...} (no semicolon)
            val match2 = Regex("""window\.__DATA__\s*=\s*(\{.+\})""", RegexOption.DOT_MATCHES_ALL)
                .find(scriptContent)
            if (match2 != null) return@run match2.groupValues[1]

            null
        } ?: return null

        return try {
            org.json.JSONObject(jsonStr).let { json ->
                json.keys().asSequence().associateWith { key -> json.get(key) }
            }
        } catch (e: Exception) {
            android.util.Log.e("Ranobes", "Failed to parse window.__DATA__ JSON", e)
            null
        }
    }

    private fun parseChaptersFromDocument(document: org.jsoup.nodes.Document): List<SChapter> {
        // First try to parse from window.__DATA__ JSON (more reliable)
        val jsonData = extractWindowData(document)
        if (jsonData != null) {
            android.util.Log.d("Ranobes", "Found window.__DATA__ with keys: ${jsonData.keys}")

            val chaptersJson = jsonData["chapters"]
            if (chaptersJson is org.json.JSONArray && chaptersJson.length() > 0) {
                android.util.Log.d("Ranobes", "Found ${chaptersJson.length()} chapters in JSON")
                return (0 until chaptersJson.length()).mapNotNull { i ->
                    try {
                        val chapterObj = chaptersJson.getJSONObject(i)
                        SChapter.create().apply {
                            // Try different key names for URL
                            url = (
                                chapterObj.optString("link", "")
                                    .ifEmpty { chapterObj.optString("url", "") }
                                    .ifEmpty { chapterObj.optString("href", "") }
                                )
                                .removePrefix(baseUrl)

                            if (url.isEmpty()) return@mapNotNull null

                            // Try different key names for title
                            name = chapterObj.optString("title", "")
                                .ifEmpty { chapterObj.optString("name", "") }
                                .ifEmpty { chapterObj.optString("chapter_title", "") }
                                .ifEmpty { "Chapter" }

                            // Parse date
                            val dateStr = chapterObj.optString("date", "")
                                .ifEmpty { chapterObj.optString("created_at", "") }
                                .ifEmpty { chapterObj.optString("published_at", "") }
                            date_upload = if (dateStr.isNotEmpty()) {
                                try {
                                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ENGLISH)
                                        .parse(dateStr)?.time ?: 0L
                                } catch (e: Exception) {
                                    try {
                                        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH)
                                            .parse(dateStr)?.time ?: 0L
                                    } catch (e: Exception) {
                                        0L
                                    }
                                }
                            } else {
                                parseRelativeDate(
                                    chapterObj.optString("showDate", "")
                                        .ifEmpty { chapterObj.optString("date_formatted", "") },
                                )
                            }

                            // Try to extract chapter number
                            val numMatch = Regex("""Chapter\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(name)
                            chapter_number = numMatch?.groupValues?.getOrNull(1)?.toFloatOrNull()
                                ?: chapterObj.optDouble("chapter_number", 0.0).toFloat()
                                    .let { if (it == 0f) chapterObj.optInt("num", 0).toFloat() else it }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Ranobes", "Error parsing chapter at index $i", e)
                        null
                    }
                }
            }
        }

        // Fallback to HTML parsing
        android.util.Log.d("Ranobes", "Falling back to HTML parsing for chapters")
        return document.select("div.cat_block.cat_line a").map { link ->
            SChapter.create().apply {
                url = link.attr("href").removePrefix(baseUrl)
                name = link.selectFirst("h6.title")?.text()?.trim() ?: link.attr("title")

                // Try to extract chapter number
                val numMatch = Regex("""Chapter\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(name)
                chapter_number = numMatch?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f

                // Parse date
                val dateText = link.selectFirst("small span.comment-count")?.text()?.trim() ?: ""
                date_upload = parseRelativeDate(dateText)
            }
        }
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

        // Get chapter title
        val chapterTitle = document.selectFirst("h1.h4.title")?.ownText()?.trim()
        if (!chapterTitle.isNullOrEmpty()) {
            content.append("<h2>$chapterTitle</h2>\n")
        }

        // Get chapter content
        val textDiv = document.selectFirst("div.text#arrticle")
        textDiv?.children()?.forEach { element ->
            when (element.tagName()) {
                "p" -> {
                    val text = element.text()?.trim()
                    if (!text.isNullOrEmpty()) {
                        content.append("<p>$text</p>\n")
                    }
                }
                "br" -> content.append("<br>\n")
                else -> {
                    val text = element.text()?.trim()
                    if (!text.isNullOrEmpty()) {
                        content.append("<p>$text</p>\n")
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
            Filter.Header("Note: Text search overrides filters"),
            Filter.Separator(),
            GenreFilter(),
            StatusFilter(),
            SortFilter(),
        )
    }

    class GenreFilter : Filter.Select<String>("Genre", genres.map { it.first }.toTypedArray()) {
        fun getSelectedValue(): String? = if (state > 0) genres[state].second else null
    }

    class StatusFilter : Filter.Select<String>("Status", statusOptions.map { it.first }.toTypedArray()) {
        fun getSelectedValue(): String? = if (state > 0) statusOptions[state].second else null
    }

    class SortFilter : Filter.Select<String>("Sort By", sortOptions.map { it.first }.toTypedArray()) {
        fun getSelectedValue(): String? = if (state > 0) sortOptions[state].second else "views"
    }

    companion object {
        private val genres = listOf(
            Pair("All", ""),
            Pair("Action", "Action"),
            Pair("Adult", "Adult"),
            Pair("Adventure", "Adventure"),
            Pair("Comedy", "Comedy"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Fantasy", "Fantasy"),
            Pair("Gender Bender", "Gender-Bender"),
            Pair("Harem", "Harem"),
            Pair("Historical", "Historical"),
            Pair("Horror", "Horror"),
            Pair("Josei", "Josei"),
            Pair("Martial Arts", "Martial-Arts"),
            Pair("Mature", "Mature"),
            Pair("Mecha", "Mecha"),
            Pair("Mystery", "Mystery"),
            Pair("Psychological", "Psychological"),
            Pair("Romance", "Romance"),
            Pair("School Life", "School-Life"),
            Pair("Sci-fi", "Sci-fi"),
            Pair("Seinen", "Seinen"),
            Pair("Shoujo", "Shoujo"),
            Pair("Shoujo Ai", "Shoujo-Ai"),
            Pair("Shounen", "Shounen"),
            Pair("Shounen Ai", "Shounen-Ai"),
            Pair("Slice of Life", "Slice-of-Life"),
            Pair("Smut", "Smut"),
            Pair("Sports", "Sports"),
            Pair("Supernatural", "Supernatural"),
            Pair("Tragedy", "Tragedy"),
            Pair("Wuxia", "Wuxia"),
            Pair("Xianxia", "Xianxia"),
            Pair("Xuanhuan", "Xuanhuan"),
            Pair("Yaoi", "Yaoi"),
            Pair("Yuri", "Yuri"),
        )

        private val statusOptions = listOf(
            Pair("All", ""),
            Pair("Ongoing", "ongoing/"),
            Pair("Completed", "completed/"),
        )

        private val sortOptions = listOf(
            Pair("Most Viewed", "views"),
            Pair("Rating", "rating"),
            Pair("Newest", "dateDesc"),
            Pair("Oldest", "dateAsc"),
        )

        private const val PREF_REVERSE_CHAPTERS = "pref_reverse_chapters"
    }

    // ======================== Settings ========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_REVERSE_CHAPTERS
            title = "Reverse Chapter List"
            summary = "Show chapters in oldest-to-newest order instead of newest-to-oldest."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    // ======================== Helpers ========================

    private fun extractBackgroundUrl(style: String): String? {
        val match = Regex("""url\(([^)]+)\)""").find(style)
        val path = match?.groupValues?.getOrNull(1) ?: return null
        return when {
            path.startsWith("http") -> path
            path.startsWith("/") -> baseUrl + path
            else -> "$baseUrl/$path"
        }
    }

    private fun parseRelativeDate(dateString: String): Long {
        val now = System.currentTimeMillis()
        val lower = dateString.lowercase()

        return when {
            lower.contains("minute") -> {
                val minutes = lower.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
                now - minutes * 60 * 1000
            }
            lower.contains("hour") -> {
                val hours = lower.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
                now - hours * 60 * 60 * 1000
            }
            lower.contains("day") -> {
                val days = lower.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
                now - days * 24 * 60 * 60 * 1000
            }
            lower.contains("week") -> {
                val weeks = lower.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
                now - weeks * 7 * 24 * 60 * 60 * 1000
            }
            lower.contains("month") -> {
                val months = lower.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
                now - months * 30L * 24 * 60 * 60 * 1000
            }
            lower.contains("year") -> {
                val years = lower.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 1
                now - years * 365L * 24 * 60 * 60 * 1000
            }
            else -> 0L
        }
    }
}

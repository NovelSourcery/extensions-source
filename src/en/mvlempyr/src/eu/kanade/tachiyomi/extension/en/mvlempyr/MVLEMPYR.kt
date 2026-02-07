package eu.kanade.tachiyomi.extension.en.mvlempyr

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Locale

class MVLEMPYR : HttpSource(), NovelSource, ConfigurableSource {

    override val name = "MVLEMPYR"
    override val baseUrl = "https://www.mvlempyr.io"
    override val lang = "en"
    override val supportsLatest = true

    override val isNovelSource = true

    override val client = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val perPage: String
        get() = preferences.getString(PER_PAGE_PREF, "50") ?: "50"

    private val useLocalLoading: Boolean
        get() = preferences.getBoolean(LOCAL_LOADING_PREF, false)

    // Cache for local loading mode
    @Volatile
    private var cachedNovels: List<CachedNovel>? = null

    private data class CachedNovel(
        val manga: SManga,
        val novelCode: Long?,
        val avgReview: Float?,
        val reviewCount: Int?,
        val chapterCount: Int?,
        val created: Long?,
        val genres: List<String>,
        val tags: List<String>,
    )

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("Referer", chapSite)
        .add("Origin", chapSite)

    private val json: Json = Json { ignoreUnknownKeys = true }
    private val chapSite = "https://chap.heliosarchive.online"
    private val assetsSite = "https://assets.mvlempyr.app/images/600"

    // WordPress API Response structure
    @Serializable
    private data class WpNovel(
        val id: Int = 0,
        val date: String? = null,
        val slug: String = "",
        val title: WpRendered = WpRendered(),
        val content: WpRendered = WpRendered(),
        val excerpt: WpRendered = WpRendered(),
        @SerialName("featured_media") val featuredMedia: Int = 0,
        val genres: List<Int> = emptyList(),
        val tags: List<Long> = emptyList(),
        @SerialName("author-name") val authorName: String? = null,
        val bookid: String? = null,
        @SerialName("novel-code") val novelCode: Long? = null,
    )

    @Serializable
    private data class WpRendered(
        val rendered: String = "",
    )

    @Serializable
    private data class ChapterPost(
        val id: Int = 0,
        val date: String? = null,
        val link: String? = null,
        val title: WpRendered = WpRendered(),
        val acf: ChapterAcf? = null,
    )

    @Serializable
    private data class ChapterAcf(
        @SerialName("ch_name") val chName: String? = null,
        @SerialName("novel_code") val novelCode: kotlinx.serialization.json.JsonElement? = null,
        @SerialName("chapter_number") val chapterNumber: kotlinx.serialization.json.JsonElement? = null,
    )

    override fun popularMangaRequest(page: Int): Request {
        if (useLocalLoading && page == 1) {
            // Load all novels for local filtering mode
            return GET("$chapSite/wp-json/wp/v2/mvl-novels?per_page=10000", headers)
        }
        // API mode - use pagination
        return GET("$chapSite/wp-json/wp/v2/mvl-novels?per_page=$perPage&page=$page&orderby=id&order=desc", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return if (useLocalLoading) {
            parseAndCacheAllNovels(response)
        } else {
            parseNovelsResponse(response)
        }
    }

    override fun latestUpdatesRequest(page: Int): Request {
        if (useLocalLoading && page == 1) {
            return GET("$chapSite/wp-json/wp/v2/mvl-novels?per_page=10000", headers)
        }
        return GET("$chapSite/wp-json/wp/v2/mvl-novels?per_page=$perPage&page=$page&orderby=date&order=desc", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return if (useLocalLoading) {
            parseAndCacheAllNovels(response, sortBy = "created")
        } else {
            parseNovelsResponse(response)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (useLocalLoading && page == 1) {
            // Load all for local filtering
            return GET("$chapSite/wp-json/wp/v2/mvl-novels?per_page=10000", headers)
        }

        val url = "$chapSite/wp-json/wp/v2/mvl-novels".toHttpUrl().newBuilder()
            .addQueryParameter("per_page", perPage)
            .addQueryParameter("page", page.toString())

        if (query.isNotEmpty()) {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            url.addQueryParameter("search", encodedQuery)
        }

        // Apply filters
        // WordPress REST API valid orderby: author, date, id, include, modified, parent, relevance, slug, include_slugs, title
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    when (filter.state) {
                        0 -> {} // None - no orderby parameter
                        1 -> url.addQueryParameter("orderby", "date") // Latest Added
                        2 -> url.addQueryParameter("orderby", "title") // A-Z
                        3 -> url.addQueryParameter("orderby", "modified") // Last Modified
                        4 -> url.addQueryParameter("orderby", "relevance") // Relevance
                    }
                    if (filter.state > 0) url.addQueryParameter("order", "desc")
                }
                is GenreFilter -> {
                    val includedGenres = filter.state.filter { it.isIncluded() }.map { it.id }
                    val excludedGenres = filter.state.filter { it.isExcluded() }.map { it.id }

                    if (includedGenres.isNotEmpty()) {
                        includedGenres.forEach { id ->
                            url.addQueryParameter("genres[]", id.toString())
                        }
                    }
                    if (excludedGenres.isNotEmpty()) {
                        excludedGenres.forEach { id ->
                            url.addQueryParameter("genres_exclude[]", id.toString())
                        }
                    }
                }
                is TagFilter -> {
                    val includedTags = filter.state.filter { it.isIncluded() }.map { it.id }
                    val excludedTags = filter.state.filter { it.isExcluded() }.map { it.id }

                    if (includedTags.isNotEmpty()) {
                        includedTags.forEach { id ->
                            url.addQueryParameter("tags[]", id.toString())
                        }
                    }
                    if (excludedTags.isNotEmpty()) {
                        excludedTags.forEach { id ->
                            url.addQueryParameter("tags_exclude[]", id.toString())
                        }
                    }
                }
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return if (useLocalLoading) {
            parseAndCacheAllNovels(response)
        } else {
            parseNovelsResponse(response)
        }
    }

    private fun parseNovelsResponse(response: Response): MangasPage {
        val responseBody = response.body.string()

        // Check pagination headers
        val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = currentPage < totalPages

        return try {
            // Parse as JSON array manually to handle the WordPress format
            val jsonArray = json.parseToJsonElement(responseBody).jsonArray

            val novels = jsonArray.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    createSMangaFromJson(obj)
                } catch (e: Exception) {
                    null
                }
            }

            MangasPage(novels, hasNextPage)
        } catch (e: Exception) {
            MangasPage(emptyList(), false)
        }
    }

    /**
     * Parse and cache all novels for local loading mode.
     * In this mode, all novels are loaded at once and filtered/sorted in memory.
     */
    private fun parseAndCacheAllNovels(response: Response, sortBy: String = "reviewCount"): MangasPage {
        val responseBody = response.body.string()

        return try {
            val jsonArray = json.parseToJsonElement(responseBody).jsonArray

            val novels = jsonArray.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    val manga = createSMangaFromJson(obj)

                    CachedNovel(
                        manga = manga,
                        novelCode = obj["novel-code"]?.jsonPrimitive?.longOrNull,
                        avgReview = obj["average-review"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull(),
                        reviewCount = obj["total-reviews"]?.jsonPrimitive?.intOrNull,
                        chapterCount = obj["total-chapters"]?.jsonPrimitive?.intOrNull,
                        created = obj["createdOn"]?.jsonPrimitive?.contentOrNull?.let { parseDate(it) },
                        genres = obj["genre"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                        tags = obj["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    )
                } catch (e: Exception) {
                    null
                }
            }

            // Cache for future use
            cachedNovels = novels

            // Sort based on the sort criteria
            val sorted = when (sortBy) {
                "created" -> novels.sortedByDescending { it.created ?: 0L }
                "avgReview" -> novels.sortedByDescending { it.avgReview ?: 0f }
                "chapterCount" -> novels.sortedByDescending { it.chapterCount ?: 0 }
                else -> novels.sortedByDescending { it.reviewCount ?: 0 }
            }

            // Return first page
            val pageSize = perPage.toIntOrNull() ?: 50
            val firstPage = sorted.take(pageSize).map { it.manga }
            val hasNextPage = sorted.size > pageSize

            MangasPage(firstPage, hasNextPage)
        } catch (e: Exception) {
            MangasPage(emptyList(), false)
        }
    }

    private fun createSMangaFromJson(obj: JsonObject): SManga = SManga.create().apply {
        val slug = obj["slug"]?.jsonPrimitive?.content ?: ""

        // Try multiple title fields: "name" (API), "title.rendered" (WP), "title" (fallback)
        val titleRendered = obj["name"]?.jsonPrimitive?.content
            ?: obj["title"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content
            ?: obj["title"]?.jsonPrimitive?.contentOrNull
            ?: "Untitled"
        val contentRendered = obj["content"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content ?: ""
        val excerptRendered = obj["excerpt"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content ?: ""
        val synopsisText = obj["synopsis-text"]?.jsonPrimitive?.content
            ?: obj["synopsis"]?.jsonPrimitive?.contentOrNull
        val bookId = obj["bookid"]?.jsonPrimitive?.content
        val novelCode = obj["novel-code"]?.jsonPrimitive?.longOrNull
        val authorNameValue = obj["author-name"]?.jsonPrimitive?.content

        url = "/novel/$slug"
        title = cleanHtml(titleRendered)
        author = authorNameValue

        // Use novelCode for thumbnail if available, otherwise bookid
        thumbnail_url = if (novelCode != null) {
            "$assetsSite/$novelCode.webp"
        } else if (!bookId.isNullOrBlank()) {
            "$assetsSite/$bookId.webp"
        } else {
            null
        }

        // Use synopsis-text, synopsis, excerpt or content for description
        description = synopsisText?.let { cleanHtml(it) }
            ?: cleanHtml(excerptRendered.ifBlank { contentRendered })
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            title = doc.selectFirst("h1.novel-title")?.text() ?: "Untitled"

            // Parse associated names/alternative titles and include in description
            val associatedNamesText = doc.select("div.additionalinfo.tm10 > div.textwrapper")
                .find { it.selectFirst("span")?.text()?.contains("Associated Names", ignoreCase = true) == true }
                ?.selectFirst("span:last-child, a")?.text()?.trim()

            var desc = doc.selectFirst("div.synopsis.w-richtext")?.text()?.trim() ?: ""
            if (!associatedNamesText.isNullOrBlank()) {
                // Split by common delimiters and clean
                val altTitles = associatedNamesText.split(",", ";", "/", "|")
                    .mapNotNull { it.trim().takeIf { s -> s.isNotBlank() && s != title } }
                    .distinct()
                if (altTitles.isNotEmpty()) {
                    desc = "Alternative Titles: ${altTitles.joinToString(", ")}\n\n$desc"
                }
            }

            description = desc
            author = doc.select("div.additionalinfo.tm10 > div.textwrapper")
                .find { it.selectFirst("span")?.text()?.contains("Author") == true }
                ?.selectFirst("a, span:last-child")?.text() ?: ""
            genre = doc.select(".genre-tags").map { it.text() }.joinToString(", ")
            status = when {
                doc.selectFirst(".novelstatustextlarge")?.text()?.contains("Ongoing", ignoreCase = true) == true -> SManga.ONGOING
                doc.selectFirst(".novelstatustextlarge")?.text()?.contains("Completed", ignoreCase = true) == true -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = doc.selectFirst("img.novel-image")?.attr("src")
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())

        val novelCode = doc.selectFirst("#novel-code")?.text()?.toLongOrNull() ?: return emptyList()
        val convertedId = convertNovelId(BigInteger.valueOf(novelCode))

        val chapters = mutableListOf<SChapter>()
        var page = 1
        var hasMore = true

        while (hasMore) {
            val chapResponse = client.newCall(
                GET("$chapSite/wp-json/wp/v2/posts?tags=$convertedId&per_page=500&page=$page", headers),
            ).execute()

            val chaptersJson = chapResponse.body.string()
            if (chaptersJson.isBlank() || chaptersJson == "[]") {
                hasMore = false
                continue
            }

            val chapData: List<ChapterPost> = json.decodeFromString(chaptersJson)

            if (chapData.isEmpty()) {
                hasMore = false
                continue
            }

            chapData.forEach { chap ->
                val acf = chap.acf ?: return@forEach
                val chapterName = acf.chName ?: "Chapter"
                val chapterNumberStr = acf.chapterNumber?.jsonPrimitive?.contentOrNull
                    ?: acf.chapterNumber?.jsonPrimitive?.intOrNull?.toString()
                    ?: ""
                val novelCodeStr = acf.novelCode?.jsonPrimitive?.content ?: ""

                chapters.add(
                    SChapter.create().apply {
                        url = "/chapter/$novelCodeStr-$chapterNumberStr"
                        name = chapterName
                        date_upload = parseDate(chap.date)
                        chapter_number = chapterNumberStr.toFloatOrNull() ?: 0f
                    },
                )
            }

            val totalPages = chapResponse.headers["X-Wp-Totalpages"]?.toIntOrNull() ?: 1
            hasMore = page < totalPages
            page++
        }

        return chapters.reversed()
    }

    override fun pageListParse(response: Response): List<Page> {
        // The chapter URL format is /chapter/{novelCode}-{chapterNumber}
        val chapterUrl = response.request.url.toString()
        return listOf(Page(0, chapterUrl))
    }

    override suspend fun fetchPageText(page: Page): String {
        // Chapter content is on chap.heliosarchive.online
        val url = if (page.url.startsWith("http")) {
            page.url
        } else {
            // page.url is like /chapter/{novelCode}-{chapterNumber}
            "$chapSite${page.url}"
        }
        val response = client.newCall(GET(url, headers)).execute()
        val doc = Jsoup.parse(response.body.string())
        // Content is in #chapter-content #chapter based on API docs
        return doc.selectFirst("#chapter-content #chapter")?.html()
            ?: doc.selectFirst("#chapter")?.html()
            ?: doc.selectFirst(".ChapterContent")?.html()
            ?: ""
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Filters"),
        SortFilter(),
        Filter.Header("Include/Exclude Genres (Tap to toggle)"),
        GenreFilter(),
        Filter.Header("Include/Exclude Tags (Tap to toggle)"),
        TagFilter(),
    )

    // WordPress REST API valid orderby values: author, date, id, include, modified, parent, relevance, slug, include_slugs, title
    private class SortFilter : Filter.Select<String>(
        "Sort by",
        arrayOf("None", "Latest Added", "A-Z", "Last Modified", "Relevance"),
    )

    private class GenreFilter : Filter.Group<GenreTriState>(
        "Genres",
        listOf(
            GenreTriState("Action", 1), GenreTriState("Adult", 2), GenreTriState("Adventure", 3), GenreTriState("Comedy", 4), GenreTriState("Drama", 5), GenreTriState("Ecchi", 6), GenreTriState("Fan-Fiction", 7), GenreTriState("Fantasy", 8), GenreTriState("Gender Bender", 9), GenreTriState("Harem", 10), GenreTriState("Historical", 11), GenreTriState("Horror", 12),
            GenreTriState("Josei", 13), GenreTriState("Martial Arts", 14), GenreTriState("Mature", 15), GenreTriState("Mecha", 16), GenreTriState("Mystery", 17), GenreTriState("Psychological", 18), GenreTriState("Romance", 19), GenreTriState("School Life", 20), GenreTriState("Sci-fi", 21), GenreTriState("Seinen", 22), GenreTriState("Shoujo", 23), GenreTriState("Shoujo Ai", 24),
            GenreTriState("Shounen", 25), GenreTriState("Shounen Ai", 26), GenreTriState("Slice of Life", 27), GenreTriState("Smut", 28), GenreTriState("Sports", 29), GenreTriState("Supernatural", 30), GenreTriState("Tragedy", 31), GenreTriState("Wuxia", 32), GenreTriState("Xianxia", 33), GenreTriState("Xuanhuan", 34), GenreTriState("Yaoi", 35), GenreTriState("Yuri", 36),
        ),
    )

    private class GenreTriState(name: String, val id: Int) : Filter.TriState(name)

    private class TagFilter : Filter.Group<TagTriState>(
        "Tags",
        listOf(
            TagTriState("Academy", 100), TagTriState("Antihero Protagonist", 101), TagTriState("Beast Companions", 102), TagTriState("Calm Protagonist", 103), TagTriState("Cheats", 104), TagTriState("Clever Protagonist", 105),
            TagTriState("Cold Protagonist", 106), TagTriState("Cultivation", 107), TagTriState("Cunning Protagonist", 108), TagTriState("Dark", 109), TagTriState("Demons", 110), TagTriState("Dragons", 111), TagTriState("Dungeons", 112),
            TagTriState("Fantasy World", 113), TagTriState("Female Protagonist", 114), TagTriState("Game Elements", 115), TagTriState("Gods", 116), TagTriState("Hidden Abilities", 117), TagTriState("Level System", 118),
            TagTriState("Magic", 119), TagTriState("Male Protagonist", 120), TagTriState("Monsters", 121), TagTriState("Nobles", 122), TagTriState("Overpowered Protagonist", 123), TagTriState("Reincarnation", 124),
            TagTriState("Revenge", 125), TagTriState("Royalty", 126), TagTriState("Second Chance", 127), TagTriState("System", 128), TagTriState("Transmigration", 129), TagTriState("Weak to Strong", 130),
        ),
    )

    private class TagTriState(name: String, val id: Int) : Filter.TriState(name)

    private fun convertNovelId(code: BigInteger): BigInteger {
        val t = BigInteger("1999999997")
        var u = BigInteger.ONE
        var c = BigInteger("7").mod(t)
        var d = code

        while (d > BigInteger.ZERO) {
            if (d and BigInteger.ONE == BigInteger.ONE) {
                u = u.multiply(c).mod(t)
            }
            c = c.multiply(c).mod(t)
            d = d.shiftRight(1)
        }

        return u
    }

    private fun parseDate(dateString: String?): Long {
        if (dateString == null) return 0L
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            format.parse(dateString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun parseCreatedDate(dateString: String?): Long {
        if (dateString == null) return 0L
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            format.parse(dateString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun cleanHtml(html: String): String {
        return Jsoup.parse(html).text()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = LOCAL_LOADING_PREF
            title = "Local loading mode"
            summary = "Load all novels at once for faster filtering (uses more memory)"
            setDefaultValue(false)
        }.let { screen.addPreference(it) }

        ListPreference(screen.context).apply {
            key = PER_PAGE_PREF
            title = "Results per page"
            entries = arrayOf("20", "50", "100")
            entryValues = arrayOf("20", "50", "100")
            setDefaultValue("50")
            summary = "%s"
        }.let { screen.addPreference(it) }
    }

    companion object {
        private const val PER_PAGE_PREF = "per_page"
        private const val LOCAL_LOADING_PREF = "local_loading"
    }
}

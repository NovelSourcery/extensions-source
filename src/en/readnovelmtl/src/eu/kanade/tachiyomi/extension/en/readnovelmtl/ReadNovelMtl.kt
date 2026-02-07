package eu.kanade.tachiyomi.extension.en.readnovelmtl

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class ReadNovelMtl : HttpSource(), NovelSource, ConfigurableSource {

    override val name = "ReadNovelMtl"
    override val baseUrl = "https://readnovelmtl.com"
    override val lang = "en"
    override val supportsLatest = true
    override val isNovelSource = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client = network.cloudflareClient

    // Cached categories
    private var categoryCache: List<Pair<String, String>>? = null
    private var categoriesLastFetched: Long = 0
    private val categoryCacheDuration = 24 * 60 * 60 * 1000L // 24 hours

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/ranking/all-time${if (page > 1) "?page=$page" else ""}", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())
        return parseNovelList(document)
    }

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/novel${if (page > 1) "?page=$page" else ""}", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var categorySlug: String? = null
        var rankingType: String? = null
        var categoryType: String? = null

        filters.forEach { filter ->
            when (filter) {
                is CategoryFilter -> {
                    if (filter.state > 0) {
                        categorySlug = getCategoriesList().getOrNull(filter.state)?.second
                    }
                }
                is RankingFilter -> {
                    if (filter.state > 0) {
                        rankingType = rankingOptions[filter.state].second
                    }
                }
                is CategoryTypeFilter -> {
                    if (filter.state > 0) {
                        categoryType = categoryTypeOptions[filter.state].second
                    }
                }
                else -> {}
            }
        }

        // If category is selected, use category URL (no search term allowed)
        if (!categorySlug.isNullOrEmpty()) {
            val url = "$baseUrl/category/$categorySlug${if (page > 1) "?page=$page" else ""}"
            return GET(url, headers)
        }

        // If ranking type is selected
        if (!rankingType.isNullOrEmpty()) {
            var url = "$baseUrl/ranking/$rankingType"
            val params = mutableListOf<String>()
            if (page > 1) params.add("page=$page")
            if (!categoryType.isNullOrEmpty()) params.add("category=$categoryType")
            if (params.isNotEmpty()) {
                url += "?" + params.joinToString("&")
            }
            return GET(url, headers)
        }

        // Otherwise use search by author or just browse latest
        if (query.isNotBlank()) {
            val url = "$baseUrl/novel?author=${java.net.URLEncoder.encode(query, "UTF-8")}${if (page > 1) "&page=$page" else ""}"
            return GET(url, headers)
        }

        // Default to latest
        return latestUpdatesRequest(page)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())

        // Try JSON-LD first
        val jsonLd = document.selectFirst("script[type=application/ld+json]:contains(Book)")?.data()
        if (jsonLd != null) {
            try {
                val schema = json.decodeFromString<SchemaBook>(jsonLd)
                return SManga.create().apply {
                    url = response.request.url.encodedPath
                    title = schema.name
                    thumbnail_url = schema.image?.firstOrNull()
                    author = schema.author
                    description = schema.description
                    genre = schema.genre?.joinToString(", ")
                    status = SManga.UNKNOWN
                }
            } catch (_: Exception) {}
        }

        // Fallback to HTML parsing
        return SManga.create().apply {
            url = response.request.url.encodedPath

            title = document.selectFirst("h1.h3.fw-bold")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: ""

            thumbnail_url = document.selectFirst(".position-relative img")?.absUrl("src")

            author = document.selectFirst("a[href*=author=]")?.text()

            // Get description from specific div
            description = document.selectFirst(".mb-4[style*=font-size]")?.text()

            // Get genres/categories
            genre = document.select(".d-flex.flex-wrap.gap-2 a.badge").map { it.text() }.joinToString(", ")

            // Parse status
            val statusText = document.select(".d-flex.align-items-center.gap-2").find { it.selectFirst("i.fa-info-circle") != null }?.selectFirst("span")?.text()?.lowercase() ?: ""

            status = when {
                statusText.contains("ongoing") -> SManga.ONGOING
                statusText.contains("completed") -> SManga.COMPLETED
                statusText.contains("hiatus") -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string())

        val chapters = mutableListOf<SChapter>()

        // Parse chapters from accordion sections
        document.select("#chapter-chunk .accordion-item").forEach { accordionItem ->
            accordionItem.select("tbody tr").forEach { row ->
                val link = row.selectFirst("a[href*=/chapter/]") ?: return@forEach
                val href = link.attr("href")
                if (href.isBlank()) return@forEach

                chapters.add(
                    SChapter.create().apply {
                        // Handle both absolute and relative URLs safely
                        url = extractUrlPath(href)
                        name = link.text().trim()

                        // Parse date from span
                        val dateText = row.selectFirst("span.text-muted")?.text()?.trim() ?: ""
                        date_upload = parseDateString(dateText)
                    },
                )
            }
        }

        return chapters
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

        // Parse chapter content from #content div
        val contentSection = document.selectFirst("#content")

        contentSection?.let { section ->
            section.children().forEach { element ->
                when (element.tagName()) {
                    "p" -> {
                        val text = element.text()?.trim()
                        if (!text.isNullOrEmpty()) {
                            content.append("<p>$text</p>\n")
                        }
                    }
                    "h1", "h2", "h3" -> {
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

            // If no paragraphs found, try direct text
            if (content.isEmpty()) {
                val directText = section.text()
                if (directText.isNotEmpty()) {
                    directText.split("\n").filter { it.isNotBlank() }.forEach { line ->
                        content.append("<p>${line.trim()}</p>\n")
                    }
                }
            }
        }

        return content.toString()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // ======================== Filters ========================

    override fun getFilterList(): FilterList {
        val categoryList = getCategoriesList()
        return FilterList(
            Filter.Header("Note: Search uses author name"),
            Filter.Separator(),
            RankingFilter("Ranking", rankingOptions.map { it.first }.toTypedArray()),
            CategoryTypeFilter("Ranking Category", categoryTypeOptions.map { it.first }.toTypedArray()),
            Filter.Separator(),
            Filter.Header("Or select a category (ignores search)"),
            CategoryFilter("Category", categoryList.map { it.first }.toTypedArray()),
        )
    }

    class RankingFilter(name: String, values: Array<String>) : Filter.Select<String>(name, values)
    class CategoryTypeFilter(name: String, values: Array<String>) : Filter.Select<String>(name, values)
    class CategoryFilter(name: String, values: Array<String>) : Filter.Select<String>(name, values)

    private val rankingOptions = listOf(
        Pair("None", ""),
        Pair("Daily", "daily"),
        Pair("Weekly", "weekly"),
        Pair("Monthly", "monthly"),
        Pair("All Time", "all-time"),
    )

    private val categoryTypeOptions = listOf(
        Pair("All", ""),
        Pair("For Male", "for-male"),
        Pair("For Female", "for-female"),
        Pair("Yaoi", "yaoi"),
    )

    // ======================== Helpers ========================

    private fun parseNovelList(document: Document): MangasPage {
        val novels = document.select(".novel-list, .py-3.py-md-4").mapNotNull { item ->
            val titleElement = item.selectFirst("h2.h5 a, .h5 a") ?: return@mapNotNull null

            SManga.create().apply {
                // Safely extract URL path - handle both absolute and relative URLs
                val href = titleElement.attr("href")
                url = extractUrlPath(href)
                title = titleElement.text()
                thumbnail_url = item.selectFirst("img[src*=cover]")?.absUrl("src")

                author = item.selectFirst("a[href*=author=]")?.text()

                // Parse status
                val statusText = item.selectFirst(".fa-info-circle")?.parent()?.text()?.lowercase() ?: ""
                status = when {
                    statusText.contains("ongoing") -> SManga.ONGOING
                    statusText.contains("completed") -> SManga.COMPLETED
                    statusText.contains("hiatus") -> SManga.ON_HIATUS
                    else -> SManga.UNKNOWN
                }

                genre = item.select("a.badge").map { it.text() }.joinToString(", ")
            }
        }.distinctBy { it.url }

        val hasNextPage = document.selectFirst(".pagination li:has(a:contains(Next))") != null

        return MangasPage(novels, hasNextPage)
    }

    private fun getCategoriesList(): List<Pair<String, String>> {
        // Return cached categories to avoid blocking main thread
        // Categories will be populated by first actual request
        if (categoryCache != null) {
            return categoryCache!!
        }

        // Return default list - actual categories will be fetched on background thread
        return defaultCategories
    }

    // Call this from a background-safe context (like searchMangaParse)
    private fun fetchCategoriesIfNeeded() {
        val alwaysFetch = preferences.getBoolean(PREF_ALWAYS_FETCH_CATEGORIES, false)
        val now = System.currentTimeMillis()

        // Skip if cache is valid
        if (!alwaysFetch && categoryCache != null && (now - categoriesLastFetched) < categoryCacheDuration) {
            return
        }

        // Try to fetch categories
        try {
            val response = client.newCall(GET("$baseUrl/category", headers)).execute()
            val document = Jsoup.parse(response.body.string())

            val categories = mutableListOf<Pair<String, String>>()
            categories.add(Pair("All Categories", ""))

            document.select(".card-body a[href*=/category/]").forEach { element ->
                val href = element.attr("href")
                val slug = href.substringAfter("/category/")
                val name = element.text().substringBefore("(").trim()
                if (slug.isNotBlank() && name.isNotBlank()) {
                    categories.add(Pair(name, slug))
                }
            }

            categoryCache = categories
            categoriesLastFetched = now
        } catch (_: Exception) {
            // Keep existing cache or use defaults
            if (categoryCache == null) {
                categoryCache = defaultCategories
            }
        }
    }

    private val defaultCategories = listOf(
        Pair("All Categories", ""),
        Pair("Action", "action"),
        Pair("Adventure", "adventure"),
        Pair("Comedy", "comedy"),
        Pair("Drama", "drama"),
        Pair("Fantasy", "fantasy"),
        Pair("Harem", "harem"),
        Pair("Martial Arts", "martial-arts"),
        Pair("Romance", "romance"),
        Pair("Xianxia", "xianxia"),
        Pair("Xuanhuan", "xuanhuan"),
    )

    private fun parseDateString(dateStr: String): Long {
        return try {
            val cleanDate = dateStr.trim().replace(Regex("[\\(\\)]"), "")
            val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            formatter.parse(cleanDate)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Safely extract URL path from href, handling:
     * - Absolute URLs with protocol (http://, https://)
     * - Protocol-relative URLs (//domain.com/path)
     * - Relative URLs (/path or path)
     */
    private fun extractUrlPath(href: String): String {
        return when {
            href.isEmpty() -> ""
            // Absolute URL with protocol
            href.startsWith("http://") || href.startsWith("https://") -> {
                val withoutProtocol = href.substringAfter("://")
                val path = withoutProtocol.substringAfter("/", "")
                if (path.isEmpty()) "/" else "/$path"
            }
            // Protocol-relative URL
            href.startsWith("//") -> {
                val withoutSlashes = href.substring(2)
                val path = withoutSlashes.substringAfter("/", "")
                if (path.isEmpty()) "/" else "/$path"
            }
            // Relative URL starting with /
            href.startsWith("/") -> href
            // Relative URL without leading /
            else -> "/$href"
        }
    }

    // ======================== Preferences ========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ALWAYS_FETCH_CATEGORIES
            title = "Always fetch categories"
            summary = "Fetch category list every time instead of caching"
            setDefaultValue(false)
        }.also { screen.addPreference(it) }
    }

    // ======================== Data Classes ========================

    @Serializable
    data class SchemaBook(
        val name: String,
        val description: String? = null,
        val author: String? = null,
        val image: List<String>? = null,
        val genre: List<String>? = null,
    )

    companion object {
        private const val PREF_ALWAYS_FETCH_CATEGORIES = "always_fetch_categories"
    }
}

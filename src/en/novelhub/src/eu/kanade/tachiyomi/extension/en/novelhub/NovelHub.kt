package eu.kanade.tachiyomi.extension.en.novelhub

import android.app.Application
import android.content.SharedPreferences
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

/**
 * NovelHub.net - Novel reading extension
 * Per instructions.html: Popular from trending section, flip chapter list
 */
class NovelHub :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "NovelHub"
    override val baseUrl = "https://novelhub.net"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient
    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private var genresList: List<Pair<String, String>> = emptyList()
    private var fetchGenresAttempts = 0
    private val scope = CoroutineScope(Dispatchers.IO)
    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        // Per instructions.html: Popular from trending section
        return GET("$baseUrl/trending?page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())

        // Per instructions.html: Parse from trending section
        // Selector: section[aria-labelledby="trending-heading"] or div.flex-shrink-0
        val novels = mutableListOf<SManga>()

        // Primary: From trending section items
        doc.select("section[aria-labelledby=trending-heading] a[href*=/novel/], div.flex-shrink-0 a[href*=/novel/]").forEach { element ->
            try {
                val url = element.attr("href").replace(baseUrl, "")
                if (url.isBlank() || !url.contains("/novel/")) return@forEach

                // Per instructions.html: title in h4 or from img alt
                val title = element.selectFirst("h4")?.text()?.trim()
                    ?: element.selectFirst("img")?.attr("alt")?.trim()
                    ?: return@forEach

                // Per instructions.html: cover from img with data-src or src
                val cover = element.selectFirst("img")?.let { img ->
                    img.attr("data-src").ifEmpty { img.attr("src") }
                } ?: ""

                novels.add(
                    SManga.create().apply {
                        this.title = title
                        this.url = url
                        thumbnail_url = if (cover.startsWith("http")) cover else "$baseUrl/$cover"
                    },
                )
            } catch (e: Exception) {
            }
        }

        if (novels.isEmpty()) {
            doc.select("a[href*=/novel/]").forEach { element ->
                try {
                    val url = element.attr("href").replace(baseUrl, "")
                    val title = element.selectFirst("h4, h3, .title")?.text()?.trim()
                        ?: element.attr("title").ifEmpty { null }
                        ?: element.selectFirst("img")?.attr("alt")
                        ?: return@forEach

                    val cover = element.selectFirst("img")?.let { img ->
                        img.attr("data-src").ifEmpty { img.attr("src") }
                    } ?: ""

                    novels.add(
                        SManga.create().apply {
                            this.title = title
                            this.url = url
                            thumbnail_url = if (cover.startsWith("http")) cover else "$baseUrl/$cover"
                        },
                    )
                } catch (e: Exception) {
                }
            }
        }

        val hasNextPage = doc.selectFirst("a[rel=next], a:contains(Next)") != null
        return MangasPage(novels.distinctBy { it.url }, hasNextPage)
    }
    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Search ========================

    @Serializable
    private data class SearchResponse(
        val results: List<SearchResult> = emptyList(),
    )

    @Serializable
    private data class SearchResult(
        val id: Int = 0,
        val title: String = "",
        val slug: String = "",
        val author: String? = null,
        val cover_image: String? = null,
        val latest_chapter: String? = null,
        val updated_at: String? = null,
        val url: String = "",
    )

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            return GET("$baseUrl/api/search/autocomplete?q=$encodedQuery", headers)
        }

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    val genre = filter.toValue()
                    if (genre != null) {
                        return GET("$baseUrl/genre/$genre?page=$page", headers)
                    }
                }

                else -> {}
            }
        }

        // Default to trending
        return GET("$baseUrl/trending?page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val responseBody = response.body.string()

        return if (response.request.url.toString().contains("/api/")) {
            val searchResponse = json.decodeFromString<SearchResponse>(responseBody)
            val novels = searchResponse.results.map { result ->
                SManga.create().apply {
                    url = "/novel/${result.slug}"
                    title = result.title
                    thumbnail_url = result.cover_image
                    author = result.author
                }
            }
            MangasPage(novels, false)
        } else {
            // HTML response from genre filter - parse like popular
            val doc = Jsoup.parse(responseBody)
            val novels = mutableListOf<SManga>()

            doc.select("a[href*=/novel/]").forEach { element ->
                try {
                    val url = element.attr("href").replace(baseUrl, "")
                    if (url.isBlank() || !url.contains("/novel/")) return@forEach

                    val title = element.selectFirst("h4, h3, .title")?.text()?.trim()
                        ?: element.attr("title").ifEmpty { null }
                        ?: element.selectFirst("img")?.attr("alt")
                        ?: return@forEach

                    val cover = element.selectFirst("img")?.let { img ->
                        img.attr("data-src").ifEmpty { img.attr("src") }
                    } ?: ""

                    novels.add(
                        SManga.create().apply {
                            this.title = title
                            this.url = url
                            thumbnail_url = if (cover.startsWith("http")) cover else "$baseUrl/$cover"
                        },
                    )
                } catch (e: Exception) {
                }
            }

            val hasNextPage = doc.selectFirst("a[rel=next], a:contains(Next)") != null
            MangasPage(novels.distinctBy { it.url }, hasNextPage)
        }
    }
    // ======================== Details ========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim() ?: ""

            // Per user: Cover is in div.flex-shrink-0 img with full URL
            // Example: <img src="https://novelhub.net/storage/novels/covers/my-charity-system-made-me-too-op.webp"
            thumbnail_url = doc.selectFirst("div.flex-shrink-0 img")?.let { img ->
                img.attr("src").ifEmpty { null }
                    ?: img.attr("data-src").ifEmpty { null }
            } ?: doc.selectFirst("img[alt*=Cover], img.object-cover")?.let { img ->
                img.attr("src").ifEmpty { img.attr("data-src") }
            }

            description = doc.selectFirst("div.prose p, div.p-4.max-w-none p")?.text()?.trim()
            author = doc.selectFirst("span.font-medium.text-white")?.text()?.trim()
            genre = doc.select("a[href*=/genre/]").joinToString(", ") { it.text().trim() }
            status = when {
                doc.text().contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                doc.text().contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }
    // ======================== Chapters ========================

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())
        val novelPath = response.request.url.encodedPath

        val chaptersText = doc.selectFirst("div:contains(Chapters)")
            ?.selectFirst("div.font-bold, div.text-lg")?.text()

        val totalChapters = chaptersText?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0

        if (totalChapters == 0) return emptyList()

        // Per instructions.html: flip chapter list (generate 1 to totalChapters, NOT reversed)
        return (1..totalChapters).map { chapterNum ->
            SChapter.create().apply {
                url = "$novelPath/chapter-$chapterNum"
                name = "Chapter $chapterNum"
                chapter_number = chapterNum.toFloat()
            }
        }.reversed()
    }
    // ======================== Pages ========================

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString().removePrefix(baseUrl)
        return listOf(Page(0, chapterUrl))
    }

    override fun imageUrlParse(response: Response): String = ""
    // ======================== Novel Content ========================

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = Jsoup.parse(response.body.string())

        return doc.selectFirst("article#chapter-content")?.html()
            ?: doc.selectFirst("article")?.html()
            ?: ""
    }
    // ======================== Filters ========================

    override fun getFilterList(): FilterList {
        scope.launch { fetchGenres() }

        val filters = mutableListOf<Filter<*>>()

        val genres = getCachedGenres()
        if (genres.isNotEmpty()) {
            filters.add(GenreFilter("Genre", genres))
        } else {
            filters.add(Filter.Header("Press 'Reset' to load genres"))
        }

        return FilterList(filters)
    }

    private fun getCachedGenres(): List<Pair<String, String>> {
        val cached = preferences.getString("genres_cache", null) ?: return emptyList()
        return try {
            json.decodeFromString(cached)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun fetchGenres() {
        if (fetchGenresAttempts >= 3 || genresList.isNotEmpty()) return

        try {
            val response = client.newCall(GET("$baseUrl/genres", headers)).execute()
            val doc = Jsoup.parse(response.body.string())

            //   <h3 class="...">Action</h3>
            val genres = doc.select("a[href*=/genre/]").mapNotNull { element ->
                val href = element.attr("href")
                val slug = href.substringAfterLast("/genre/").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val name = element.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
                Pair(slug, name)
            }.distinctBy { it.first }

            if (genres.isNotEmpty()) {
                genresList = genres
                preferences.edit().putString("genres_cache", json.encodeToString(genres)).apply()
            }
        } catch (e: Exception) {
            // Ignore
        } finally {
            fetchGenresAttempts++
        }
    }

    class GenreFilter(name: String, private val genres: List<Pair<String, String>>) : Filter.Select<String>(name, arrayOf("All") + genres.map { it.second }.toTypedArray()) {
        fun toValue(): String? = if (state == 0) null else genres.getOrNull(state - 1)?.first
    }

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
    }
}

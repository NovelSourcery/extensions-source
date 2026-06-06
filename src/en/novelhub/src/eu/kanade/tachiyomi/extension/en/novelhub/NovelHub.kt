package eu.kanade.tachiyomi.novelextension.en.novelhub

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
 * Popular from /ranking (server-rendered), flip chapter list
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
        // /trending is an empty JS shell; /ranking is server-rendered and paginated
        return GET("$baseUrl/ranking?page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseNovelList(response)

    /**
     * Shared parser for novel listing pages (/ranking, /latest, genre pages).
     * Handles both card layouts:
     * - article cards (latest/genre): lazy img with data-src inside the cover anchor
     * - ranking cards: img with relative src outside the anchor
     */
    private fun parseNovelList(response: Response): MangasPage {
        // Base URI needed so absUrl() resolves relative covers like "storage/novels/covers/x.webp"
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())
        return parseNovelListFromDoc(doc)
    }

    private fun parseNovelListFromDoc(doc: org.jsoup.nodes.Document): MangasPage {
        val novels = mutableListOf<SManga>()
        val seen = mutableSetOf<String>()

        doc.select("a[href*=/novel/]").forEach { anchor ->
            val slug = anchor.absUrl("href").substringAfter("/novel/", "")
                .substringBefore("?").substringBefore("#").trim('/')
            if (slug.isBlank() || slug.contains("/")) return@forEach // skip chapter links
            if (!seen.add(slug)) return@forEach

            // Nearest card container: article, or the closest div that holds the cover img
            val card = anchor.parents().firstOrNull {
                it.tagName() == "article" || (it.tagName() == "div" && it.selectFirst("img") != null)
            } ?: anchor

            val title = card.selectFirst("h3, h4")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: card.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
                ?: anchor.text().trim().takeIf { it.isNotEmpty() }
                ?: return@forEach

            val img = anchor.selectFirst("img") ?: card.selectFirst("img")
            val cover = img?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
                ?.takeIf { it.isNotEmpty() }
                ?: "$baseUrl/storage/novels/covers/$slug.webp"

            novels.add(
                SManga.create().apply {
                    this.title = title
                    url = "/novel/$slug"
                    thumbnail_url = cover
                },
            )
        }

        val hasNextPage = doc.selectFirst("a[rel=next], a:contains(Next)") != null
        return MangasPage(novels, hasNextPage)
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

        // Default to ranking (server-rendered; /trending is an empty JS shell)
        return GET("$baseUrl/ranking?page=$page", headers)
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
            // HTML response from genre filter - same card layout as listings
            val doc = Jsoup.parse(responseBody, response.request.url.toString())
            parseNovelListFromDoc(doc)
        }
    }
    // ======================== Details ========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string(), response.request.url.toString())

        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim() ?: ""

            // og:image always carries the cover; DOM fallback scoped to cover path
            // (header logo also sits in a div.flex-shrink-0, so don't select by that class)
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotEmpty() }
                ?: doc.selectFirst("img[src*=/covers/], img[data-src*=/covers/]")?.let { img ->
                    img.absUrl("src").ifEmpty { img.absUrl("data-src") }
                }

            // Synopsis holds multiple <p> tags - join all of them, preserving <br> breaks
            description = doc.selectFirst("div.prose, div.p-4.max-w-none")?.let { el ->
                el.select("br").prepend("\\n")
                el.select("p")
                    .map { it.text().replace("\\n", "\n").trim() }
                    .filter { it.isNotEmpty() }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("\n\n")
                    ?: el.text().replace("\\n", "\n").trim()
            }

            author = doc.selectFirst("span.font-medium.text-white")?.text()?.trim()
            genre = doc.select("a[href*=/genre/]")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")

            // Status sits in a stats block: value div (font-bold) + "Status" label div.
            // Don't scan doc.text() - the footer always contains a "Completed" nav link.
            val statusText = doc.selectFirst("div:matchesOwn(^\\s*Status\\s*$)")
                ?.parent()?.selectFirst("div.font-bold")?.text() ?: ""
            status = when {
                statusText.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                statusText.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                statusText.contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS
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

        var totalChapters = chaptersText?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0

        if (totalChapters == 0) {
            val latestChapter = doc.select("a.group h3")
                .firstOrNull()?.text()
                ?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

            if (latestChapter != null && latestChapter > 0) {
                totalChapters = latestChapter
            } else {
                try {
                    val chaptersResponse = client.newCall(GET("$baseUrl$novelPath/chapters?sort=desc", headers)).execute()
                    val chaptersDoc = Jsoup.parse(chaptersResponse.body.string())
                    val firstChapter = chaptersDoc.select("a.group h3")
                        .firstOrNull()?.text()
                        ?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                    if (firstChapter != null && firstChapter > 0) {
                        totalChapters = firstChapter
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        if (totalChapters == 0) return emptyList()

        // Per instructions.html: flip chapter list (generate 1 to totalChapters, NOT reversed)
        return (1..totalChapters).map { chapterNum ->
            SChapter.create().apply {
                url = "$novelPath/chapter-$chapterNum"
                name = "Chapter $chapterNum"
                chapter_number = chapterNum.toFloat()
                date_upload = 0L
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

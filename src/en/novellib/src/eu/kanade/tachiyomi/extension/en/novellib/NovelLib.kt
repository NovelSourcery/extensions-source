package eu.kanade.tachiyomi.extension.en.novellib

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
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class NovelLib :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "NovelLib"

    override val baseUrl = "https://www.novellib.online"

    override val lang = "en"

    override val supportsLatest = true

    override val isNovelSource = true

    override val client = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by getPreferencesLazy()

    // ======================== Popular / Latest ========================

    override fun popularMangaRequest(page: Int): Request = browseRequest(page, "", "popularity", "Descending", "", "")

    override fun popularMangaParse(response: Response): MangasPage = browseParse(response)

    override fun latestUpdatesRequest(page: Int): Request = browseRequest(page, "", "newest", "Descending", "", "")

    override fun latestUpdatesParse(response: Response): MangasPage = browseParse(response)

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var sortBy = "popularity"
        var direction = "Descending"
        var status = ""
        var genre = ""

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sortBy = filter.toUriPart()
                is DirectionFilter -> direction = filter.toUriPart()
                is StatusFilter -> status = filter.toUriPart()
                is GenreFilter -> genre = filter.toUriPart()
                else -> {}
            }
        }

        return browseRequest(page, query, sortBy, direction, status, genre)
    }

    override fun searchMangaParse(response: Response): MangasPage = browseParse(response)

    private fun browseRequest(page: Int, query: String, sortBy: String, direction: String, status: String, genre: String): Request {
        val url = "$baseUrl/novel/browse".toHttpUrl().newBuilder()
            .addQueryParameter("sortDirection", direction)
            .addQueryParameter("status", status)
            .addQueryParameter("sortBy", sortBy)

        if (query.isNotBlank()) url.addQueryParameter("search", query)
        if (genre.isNotBlank()) url.addQueryParameter("genre", genre)
        if (page > 1) url.addQueryParameter("page", page.toString())

        return GET(url.build(), headers)
    }

    private fun browseParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())

        // Browse pages carry the filter form — refresh the genre cache for free
        cacheGenresFrom(doc)

        val entries = doc.select("div.manga-item").mapNotNull { element ->
            val link = element.selectFirst("a[href^=/novel/]") ?: return@mapNotNull null
            SManga.create().apply {
                url = link.attr("href")
                title = element.selectFirst("a[title]")?.attr("title")?.takeIf { it.isNotBlank() }
                    ?: element.selectFirst("img[alt]")?.attr("alt")?.takeIf { it.isNotBlank() }
                    ?: link.text()
                thumbnail_url = element.selectFirst("img")?.attr("abs:src")
            }
        }

        // The next button still renders on the last page but self-links,
        // so compare its page param against the current one
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val nextPage = doc.selectFirst("nav a:has(span:containsOwn(chevron_right))")
            ?.attr("href")?.substringAfter("page=", "")?.substringBefore("&")?.toIntOrNull()
        val hasNextPage = nextPage != null && nextPage > currentPage

        return MangasPage(entries, hasNextPage)
    }

    // ======================== Details ========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("img[alt$=Cover]")?.attr("abs:src")
            author = doc.selectFirst("a[href^=/author/]")?.text()?.trim()
            genre = doc.select("div.flex-1 a[href^=/genre/]").joinToString(", ") { it.text().trim() }

            val statusText = doc.selectFirst("h1")?.previousElementSibling()?.text()
                ?: doc.selectFirst("span:matchesOwn(^(?i)(Ongoing|Completed)\$)")?.text()
                ?: ""
            status = when {
                statusText.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
                statusText.contains("completed", ignoreCase = true) -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            val synopsis = doc.selectFirst("p[class*=line-clamp]")
                ?.let { formatDescription(it) }
                .orEmpty()

            // Stats with no SManga field (rating, words, views, votes) go into the description
            val extras = buildList {
                doc.selectFirst("div.text-amber-500 > span.font-bold")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
                    ?.let { add("Rating: $it") }

                doc.select("div.grid-cols-3 > div").forEach { stat ->
                    val cells = stat.select("p")
                    val value = cells.getOrNull(0)?.text()?.trim()
                    val label = cells.getOrNull(1)?.text()?.trim()
                    if (!value.isNullOrBlank() && !label.isNullOrBlank() && !value.equals("N/A", ignoreCase = true)) {
                        add("$label: $value")
                    }
                }
            }

            description = buildString {
                append(synopsis)
                if (extras.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(extras.joinToString(" • "))
                }
            }.trim()
        }
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.substringAfter("/novel/").substringBefore("/").substringBefore("?")
        return GET("$baseUrl/novel/details-content/$slug", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())
        val slug = response.request.url.pathSegments.last()

        val chapterNumberRegex = Regex("""Ch\.?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

        return doc.select("a[href^=/novel/$slug/]").mapNotNull { element ->
            val spans = element.select("span")
            val chapterName = spans.getOrNull(0)?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val lockLabel = spans.getOrNull(1)?.text()?.trim().orEmpty()
            val locked = lockLabel.isNotBlank() && !lockLabel.equals("free", ignoreCase = true)

            SChapter.create().apply {
                url = element.attr("href")
                name = if (locked) "🔒 $chapterName" else chapterName
                chapter_number = chapterNumberRegex.find(chapterName)
                    ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            }
        }.reversed()
    }

    // ======================== Chapter Content ========================

    override fun fetchPageList(chapter: SChapter): rx.Observable<List<Page>> = rx.Observable.just(listOf(Page(0, chapter.url)))

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = Jsoup.parse(response.body.string())

        val content = doc.selectFirst("article.reading-container div.content")
            ?: doc.selectFirst("div.content")
            ?: throw Exception("Chapter content not found")

        content.select("hr").remove()

        return content.html()
    }

    // ======================== Filters ========================

    override fun getFilterList(): FilterList {
        val genres = getCachedGenres()

        if (genres.isEmpty()) {
            // Browse page carries the genre list — fetch and cache it in the background
            Thread {
                try {
                    val response = client.newCall(browseRequest(1, "", "popularity", "Descending", "", "")).execute()
                    cacheGenresFrom(Jsoup.parse(response.body.string()))
                } catch (_: Exception) {}
            }.start()
        }

        val filters = mutableListOf<Filter<*>>(
            SortFilter(),
            DirectionFilter(),
            StatusFilter(),
        )

        if (genres.isNotEmpty()) {
            filters += GenreFilter(genres)
        } else {
            filters += Filter.Header("Genres are downloading, reopen filters shortly")
        }

        return FilterList(filters)
    }

    private fun cacheGenresFrom(doc: Document) {
        val genres = doc.select("input[name=genre]")
            .map { it.attr("value").trim() }
            .filter { it.isNotEmpty() }
        if (genres.isNotEmpty()) {
            preferences.edit().putString(GENRES_CACHE_KEY, json.encodeToString(genres)).apply()
        }
    }

    private fun getCachedGenres(): List<String> {
        val cached = preferences.getString(GENRES_CACHE_KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString(cached)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ======================== Preferences ========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = RESET_FILTERS_KEY
            title = "Reset filter cache"
            summary = "Toggle to clear the cached genre list (${getCachedGenres().size} genres). It re-downloads the next time filters open."
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                preferences.edit().remove(GENRES_CACHE_KEY).apply()
                false
            }
        }.also(screen::addPreference)
    }

    // ======================== Description Formatting ========================

    /**
     * Converts an HTML description element to plain text while preserving
     * paragraph (<p>) and line (<br>) breaks as newlines.
     */
    private fun formatDescription(element: Element): String {
        val el = element.clone()
        el.select("br").forEach { it.after(BR_TOKEN) }
        el.select("p, div, li").forEach { it.after(P_TOKEN) }
        return el.text()
            .replace(Regex("\\s*$P_TOKEN\\s*"), "\n\n")
            .replace(Regex("\\s*$BR_TOKEN\\s*"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    // Filter classes
    private class SortFilter :
        Filter.Select<String>(
            "Sort By",
            arrayOf("Popularity", "Top Rated", "Newest", "Most Chapters"),
        ) {
        fun toUriPart(): String = when (state) {
            1 -> "toprated"
            2 -> "newest"
            3 -> "mostchapters"
            else -> "popularity"
        }
    }

    private class DirectionFilter :
        Filter.Select<String>(
            "Sort Direction",
            arrayOf("Descending", "Ascending"),
        ) {
        fun toUriPart(): String = if (state == 1) "Ascending" else "Descending"
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("All", "Ongoing", "Completed"),
        ) {
        fun toUriPart(): String = when (state) {
            1 -> "Ongoing"
            2 -> "Completed"
            else -> ""
        }
    }

    private class GenreFilter(private val genres: List<String>) :
        Filter.Select<String>(
            "Genre",
            arrayOf("All") + genres,
        ) {
        fun toUriPart(): String = if (state == 0) "" else genres[state - 1]
    }

    companion object {
        private const val GENRES_CACHE_KEY = "genres_cache"
        private const val RESET_FILTERS_KEY = "reset_filters_cache"
        private const val BR_TOKEN = "__NL_BR__"
        private const val P_TOKEN = "__NL_P__"
    }
}

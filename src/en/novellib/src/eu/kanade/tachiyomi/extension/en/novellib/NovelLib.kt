package eu.kanade.tachiyomi.novelextension.en.novellib

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
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

@Source
abstract class NovelLib :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by getPreferencesLazy()

    /**
     * The site's novel detail URL shape, as `/novel/<slug>`. [SManga.url] is stored as the bare
     * slug (see [SlugPath]); a stored value starting with "/" is a pre-existing full-path entry
     * from before this source adopted slug storage, and is resolved unchanged regardless of
     * this template.
     */
    protected open val mangaPathTemplate: SlugPath = SlugPath("/novel/")

    // ======================== Popular / Latest ========================

    protected open fun buildPopularMangaRequest(page: Int): Request = browseRequest(page, "", "popularity", "Descending", "", "")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val popularRequest = buildPopularMangaRequest(page)
        return browseParse(client.get(popularRequest.url, popularRequest.headers))
    }

    protected open fun buildLatestUpdatesRequest(page: Int): Request = browseRequest(page, "", "newest", "Descending", "", "")

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val latestRequest = buildLatestUpdatesRequest(page)
        return browseParse(client.get(latestRequest.url, latestRequest.headers))
    }

    // ======================== Search ========================

    protected open fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
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

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val searchRequest = buildSearchMangaRequest(page, query, filters)
        return browseParse(client.get(searchRequest.url, searchRequest.headers))
    }

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
        val doc = response.asJsoup()

        // Browse pages carry the filter form — refresh the genre cache for free
        cacheGenresFrom(doc)

        val entries = doc.select("div.manga-item").mapNotNull { element ->
            val link = element.selectFirst("a[href^=/novel/]") ?: return@mapNotNull null
            SManga.create().apply {
                url = mangaPathTemplate.slug(link.attr("href"))
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

    // ======================== Details + Chapters ========================

    protected open fun buildMangaDetailsRequest(manga: SManga): Request = GET(mangaPathTemplate.absolute(baseUrl, manga.url), headers)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) {
            val mangaDetailsRequest = buildMangaDetailsRequest(manga)
            val doc = client.get(mangaDetailsRequest.url, mangaDetailsRequest.headers).asJsoup()
            parseMangaDetails(doc)
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) parseChaptersForManga(manga) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(doc: Document): SManga = SManga.create().apply {
        title = doc.selectFirst("h1")?.text().orEmpty()
        thumbnail_url = doc.selectFirst("img[alt$=Cover]")?.attr("abs:src")
        author = doc.selectFirst("a[href^=/author/]")?.text()
        genre = doc.select("div.flex-1 a[href^=/genre/]").joinToString { it.text() }

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
            doc.selectFirst("div.text-amber-500 > span.font-bold")?.text()
                ?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
                ?.let { add("Rating: $it") }

            doc.select("div.grid-cols-3 > div").forEach { stat ->
                val cells = stat.select("p")
                val value = cells.getOrNull(0)?.text()
                val label = cells.getOrNull(1)?.text()
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

    private suspend fun parseChaptersForManga(manga: SManga): List<SChapter> {
        val slug = mangaPathTemplate.resolve(manga.url).substringAfter("/novel/").substringBefore("/").substringBefore("?")
        val response = client.get("$baseUrl/novel/details-content/$slug", headers)
        val doc = response.asJsoup()

        val chapterNumberRegex = Regex("""Ch\.?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

        return doc.select("a[href^=/novel/$slug/]").mapNotNull { element ->
            val spans = element.select("span")
            val chapterName = spans.getOrNull(0)?.text()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val lockLabel = spans.getOrNull(1)?.text().orEmpty()
            val locked = lockLabel.isNotBlank() && !lockLabel.equals("free", ignoreCase = true)

            SChapter.create().apply {
                url = element.attr("href")
                name = if (locked) "🔒 $chapterName" else chapterName
                chapter_number = chapterNumberRegex.find(chapterName)
                    ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            }
        }.reversed()
    }

    override fun getMangaUrl(manga: SManga): String = mangaPathTemplate.absolute(baseUrl, manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val tempManga = SManga.create().apply { this.url = mangaPathTemplate.slug(url.encodedPath) }
        val mangaDetailsRequest = buildMangaDetailsRequest(tempManga)
        val response = client.get(mangaDetailsRequest.url, mangaDetailsRequest.headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        val doc = response.asJsoup()
        return parseMangaDetails(doc).apply { this.url = tempManga.url }
    }

    // ======================== Chapter Content ========================

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val response = client.get(baseUrl + page.url, headers)
        val doc = response.asJsoup()

        val content = doc.selectFirst("article.reading-container div.content")
            ?: doc.selectFirst("div.content")
            ?: throw Exception("Chapter content not found")

        content.select("hr").remove()

        return content.html()
    }

    // ======================== Filters ========================

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = getCachedGenres()

        if (genres.isEmpty()) {
            // Browse page carries the genre list — fetch and cache it in the background
            Thread {
                try {
                    val response = client.newCall(browseRequest(1, "", "popularity", "Descending", "", "")).execute()
                    cacheGenresFrom(response.asJsoup())
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

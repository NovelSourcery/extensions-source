package eu.kanade.tachiyomi.novelextension.en.dragonholic

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
import keiyoushi.lib.chapterutils.shouldReturnExisting
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.formattedText
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.jsonInstance
import keiyoushi.utils.parseAs
import keiyoushi.utils.setAltTitles
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Dragonholic (dragonholictranslations.com). A heavily customized WordPress site - not a Madara
 * "wp-manga" theme despite the old base class - Tailwind/shadcn markup, Alpine.js filter widgets
 * on `/browse/`, and a JSON `/api/chapters` endpoint for the chapter list.
 */
@Source
abstract class Dragonholic :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val supportsLatest = true
    override val supportsFilterFetching = true

    private val preferences by getPreferencesLazy()

    /** [SManga.url] is stored as the bare slug under "/series/"; a stored value starting with
     * "/" is a pre-existing full-path entry and is resolved unchanged. */
    private val mangaPath = SlugPath("/series/")

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // ======================== Popular / Latest ========================

    override suspend fun getPopularManga(page: Int): MangasPage = parseBrowseResponse(client.get(buildBrowseUrl(page, sort = "trending", order = "desc"), headers))

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseBrowseResponse(client.get(buildBrowseUrl(page, sort = "updated", order = "desc"), headers))

    private fun buildBrowseUrl(page: Int, sort: String, order: String): HttpUrl {
        // WP-style path pagination ("/browse/page/N/"); page 1 has no "/page/1/" segment.
        val path = if (page > 1) "/browse/page/$page/" else "/browse/"
        return (baseUrl + path).toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("order", order)
            .build()
    }

    private fun parseBrowseResponse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = doc.select("#series-list-container a[href*=/series/]").mapNotNull { a ->
            val title = a.selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            SManga.create().apply {
                setSlugUrl(mangaPath, a.attr("abs:href"))
                this.title = title
                thumbnail_url = a.selectFirst("img")?.attr("abs:src")
            }
        }
        val hasNextPage = doc.selectFirst("a.next.page-numbers") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ======================== Search + Filters ========================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        var sort = "trending"
        var order = "desc"
        var status = ""
        var translator = ""
        var tag = ""
        val genres = mutableListOf<String>()

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sort = filter.toUriPart()
                is OrderFilter -> order = filter.toUriPart()
                is StatusFilter -> status = filter.toUriPart()
                is TranslatorFilter -> translator = filter.selected()
                is TagFilter -> tag = filter.state.trim()
                is GenreFilter -> filter.state.forEach { if (it.state) genres.add(it.id) }
                else -> {}
            }
        }

        val url = buildBrowseUrl(page, sort, order).newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("search", query)
            if (status.isNotEmpty()) addQueryParameter("status", status)
            if (translator.isNotEmpty()) addQueryParameter("nauthor", translator)
            if (tag.isNotEmpty()) addQueryParameter("tags[]", tag)
            genres.forEach { addQueryParameter("genres[]", it) }
        }.build()

        return parseBrowseResponse(client.get(url, headers))
    }

    // Genre/translator option lists are dynamic (33 genres, 200+ translators) and only available
    // by scraping the browse page's Alpine.js filter widgets - fetched in the background and
    // cached via KeiSource's remote-filter-fetching support. Status/sort/order are small fixed
    // enums, so those stay as plain hardcoded Filter.Select options. Tags are NOT fetched this
    // way (see FilterData) - the tag cloud has 12,000+ entries.
    override suspend fun fetchFilterData(): JsonElement {
        val html = client.get("$baseUrl/browse/", headers).body.string()
        return FilterData(
            genres = extractAlpineOptions(html, "genres"),
            translators = extractTranslatorOptions(html),
        ).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.let { runCatching { it.parseAs<FilterData>() }.getOrNull() } ?: FilterData()
        return FilterList(
            SortFilter(),
            OrderFilter(),
            StatusFilter(),
            TranslatorFilter(filterData.translators),
            TagFilter(),
            Filter.Separator(),
            Filter.Header("Genres"),
            GenreFilter(filterData.genres.map { GenreOption(it.label, it.id) }),
        )
    }

    /** Extracts a `nameKey: "<key>"` Alpine widget's `options: [...]` array (genres/tags). */
    private fun extractAlpineOptions(html: String, nameKey: String): List<FilterOption> {
        val markerIdx = html.indexOf("nameKey: &quot;$nameKey&quot;")
        if (markerIdx == -1) return emptyList()
        val optionsIdx = html.indexOf("options:", markerIdx)
        val arrayStart = if (optionsIdx == -1) -1 else html.indexOf('[', optionsIdx)
        if (arrayStart == -1) return emptyList()
        val arrayJson = extractBalanced(html, arrayStart, '[', ']')?.replace("&quot;", "\"") ?: return emptyList()
        return runCatching {
            jsonInstance.parseToJsonElement(arrayJson).jsonArray.map { it.parseAs<FilterOption>() }
        }.getOrDefault(emptyList())
    }

    /** Extracts the translator `filterableSelect(JSON.parse('...'))` widget's option list. */
    private fun extractTranslatorOptions(html: String): List<FilterOption> {
        val marker = "filterableSelect(JSON.parse('"
        val startIdx = html.indexOf(marker)
        if (startIdx == -1) return emptyList()
        val jsonStart = startIdx + marker.length
        val endIdx = html.indexOf("'))", jsonStart)
        if (endIdx == -1) return emptyList()
        // The JS string literal escapes its own quotes as " rather than \" - undo that.
        val json = html.substring(jsonStart, endIdx).replace("\\u0022", "\"")
        return runCatching {
            jsonInstance.parseToJsonElement(json).jsonObject["options"]?.jsonArray
                ?.map { it.parseAs<FilterOption>() }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** Returns the substring from [start] (a bracket character) to its matching close bracket. */
    private fun extractBalanced(text: String, start: Int, open: Char, close: Char): String? {
        var depth = 0
        var inString = false
        var escape = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == open -> depth++
                !inString && c == close -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
            i++
        }
        return null
    }

    private class SortFilter : Filter.Select<String>("Sort By", arrayOf("Latest Upload", "Recently Updated", "Title", "Trending"), 3) {
        fun toUriPart() = arrayOf("new", "updated", "title", "trending")[state]
    }

    private class OrderFilter : Filter.Select<String>("Order", arrayOf("Descending", "Ascending")) {
        fun toUriPart() = if (state == 0) "desc" else "asc"
    }

    private class StatusFilter : Filter.Select<String>("Status", arrayOf("All Statuses", "Ongoing", "Completed", "Hiatus", "Cancelled")) {
        fun toUriPart() = arrayOf("", "ongoing", "completed", "hiatus", "cancelled")[state]
    }

    private class TranslatorFilter(private val options: List<FilterOption>) :
        Filter.Select<String>(
            "Translator",
            (if (options.isEmpty()) listOf("All Translators (tap 'Reset' to load)") else options.map { it.label })
                .toTypedArray(),
        ) {
        fun selected(): String = options.getOrNull(state)?.id?.takeIf { it.isNotEmpty() && it != "0" } ?: ""
    }

    private class GenreOption(name: String, val id: String) : Filter.CheckBox(name)
    private class GenreFilter(options: List<GenreOption>) : Filter.Group<GenreOption>("Genres", options)

    // Free-text instead of an enumerated list - the site's tag cloud has 12,000+ entries.
    private class TagFilter : Filter.Text("Tag (exact slug, e.g. \"bait\")")

    // ======================== Details + Chapters ========================

    private fun buildMangaDetailsUrl(manga: SManga): String = baseUrl + mangaPath.resolve(manga.url)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)

        // Details and the chapters API's series_id both come off the same novel page.
        val doc = client.get(buildMangaDetailsUrl(manga), headers).asJsoup()
        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) fetchChapterList(manga, doc, chapters) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(doc: Document): SManga = SManga.create().apply {
        title = doc.selectFirst("h1")?.text()?.trim().orEmpty()

        val altTitle = doc.selectFirst("p.text-muted-foreground.mb-4.text-sm")?.text()?.trim()
        if (!altTitle.isNullOrEmpty()) setAltTitles(listOf(altTitle))

        thumbnail_url = doc.selectFirst("div[x-data=coverModal] img")?.attr("abs:src")

        author = doc.select("div.flex.flex-col")
            .firstOrNull { it.selectFirst("span")?.text()?.trim() == "By" }
            ?.selectFirst("a")?.text()

        genre = doc.select("a[href*=/genre/]").mapNotNull { it.text().trim().ifEmpty { null } }
            .distinct().joinToString()

        val statusText = doc.selectFirst("div.mb-3.flex.flex-wrap")?.text()?.trim().orEmpty()
        status = when {
            statusText.contains("ongoing", true) -> SManga.ONGOING
            statusText.contains("completed", true) -> SManga.COMPLETED
            statusText.contains("hiatus", true) -> SManga.ON_HIATUS
            statusText.contains("cancel", true) -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        val synopsis = doc.selectFirst("div[x-ref=synopsis]")
        synopsis?.select("p")?.firstOrNull { it.text().equals("Synopsis", true) }?.remove()
        val views = doc.select("div.text-muted-foreground.mb-4.inline-flex span").firstOrNull()?.text()?.trim()

        description = buildString {
            if (!views.isNullOrEmpty()) appendLine(views).appendLine()
            append(synopsis?.formattedText().orEmpty())
        }.trim()
    }

    private suspend fun fetchChapterList(manga: SManga, doc: Document, existingChapters: List<SChapter>): List<SChapter> {
        val seriesId = Regex(""""seriesId":(\d+)""").find(doc.outerHtml())?.groupValues?.get(1)
            ?: return existingChapters
        val novelSlug = mangaPath.resolve(manga.url).removePrefix("/series/").trim('/')

        val result = runCatching {
            client.get(
                "$baseUrl/api/chapters?series_id=$seriesId&sort_order=desc&per_page=5000",
                headers,
            ).parseAs<ChaptersResponse>()
        }.getOrNull()
        if (result == null || !result.success) return existingChapters
        if (shouldReturnExisting(existingChapters.size, result.chapters.size)) return existingChapters

        val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)
        // Already newest-first from the API (sort_order=desc).
        return result.chapters.mapNotNull { item ->
            if (item.isPremium && !showLocked) return@mapNotNull null
            SChapter.create().apply {
                name = (if (item.isPremium) "🔒 " else "") + item.name.trim()
                url = "/series/$novelSlug/${item.slug}/"
                chapter_number = item.chapterOrder?.toFloatOrNull() ?: 0f
                date_upload = item.createdAt?.let(::parseDate) ?: 0L
            }
        }
    }

    private fun parseDate(date: String): Long = runCatching {
        LocalDateTime.parse(date, dateFormatter).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPath.resolve(manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = mangaPath.slug(url.encodedPath)
        val manga = SManga.create().apply { this.url = slug }
        val response = client.get(buildMangaDetailsUrl(manga), headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        return parseMangaDetails(response.asJsoup()).apply { this.url = slug }
    }

    // ======================== Pages / Content ========================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.get(baseUrl + page.url, headers).asJsoup()
        return doc.selectFirst(".chapter-content")?.html().orEmpty()
    }

    // ======================== Settings ========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Include premium/locked chapters in the chapter list."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_SHOW_LOCKED = "pref_show_locked_chapters"
    }
}

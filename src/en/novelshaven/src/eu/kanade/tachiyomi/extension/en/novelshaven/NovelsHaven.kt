package eu.kanade.tachiyomi.novelextension.en.novelshaven

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
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities

@Source
abstract class NovelsHaven :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val supportsFilterFetching = true

    private val preferences by getPreferencesLazy()

    override suspend fun getPopularManga(page: Int): MangasPage = getSeriesList(page, "all-time-rank")

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSeriesList(page, "last-updated")

    private suspend fun getSeriesList(page: Int, sort: String): MangasPage {
        val url = "$baseUrl/series".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("order", "descending")
            .addQueryParameter("page", page.toString())
            .build()
        return client.get(url).asJsoup().parseSeriesList()
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/series".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("query", query.trim())
            filters.filterIsInstance<UrlFilter>().forEach { it.addTo(this) }
            addQueryParameter("page", page.toString())
        }.build()
        return client.get(url).asJsoup().parseSeriesList()
    }

    private fun Document.parseSeriesList(): MangasPage {
        val mangas = select("#novels-list li:has(article)").map { item ->
            SManga.create().apply {
                url = item.selectFirst("a[href^=/series/]")!!.attr("href").removePrefix("/series/")
                title = item.selectFirst("a.text-lg[href^=/series/]")!!.text()
                thumbnail_url = item.selectFirst("img")?.absUrl("src")
            }
        }
        val hasNextPage = selectFirst("a[aria-label=\"Go to next page\"][aria-disabled=false]") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != "series") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        val flight = Flight.parse(client.get("$baseUrl/series/$slug").asJsoup())
        return flight.series().toSManga().apply { this.url = slug }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val flight = Flight.parse(client.get(getMangaUrl(manga)).asJsoup())
        val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)
        val chapterList = flight.find { "chapters" in it && "seriesId" in it }
            ?.get("chapters")?.jsonArray.orEmpty()
            .map { flight.resolve(it).parseAs<ChapterDto>() }
            .filter { !it.isPadding && (showLocked || !it.isPremium) }
            .map { it.toSChapter(manga.url) }
            .reversed()
        return SMangaUpdate(flight.series().toSManga(), chapterList)
    }

    private fun Flight.series(): SeriesDto = find { "series_genres" in it }?.parseAs<SeriesDto>()
        ?: throw Exception("Series data not found")

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/series/${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, getChapterUrl(chapter)))

    override suspend fun fetchPageText(page: Page): String {
        val flight = Flight.parse(client.get(page.url).asJsoup())
        val chapter = flight.find { (it["chapter"] as? JsonObject)?.containsKey("content") == true }
            ?.get("chapter")?.jsonObject
            ?: throw Exception("Chapter not found")
        val isPremium = chapter["is_premium"]?.jsonPrimitive?.booleanOrNull == true
        val content = chapter["content"]?.let(flight::resolve)?.jsonPrimitive?.contentOrNull
        if (content.isNullOrBlank()) {
            throw Exception(if (isPremium) "Premium chapter: unlock it on NovelsHaven in WebView" else "Chapter content not found")
        }
        return content.split(PARAGRAPH_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("") { "<p>${Entities.escape(it).replace("\n", "<br>")}</p>" }
    }

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/series").asJsoup()
        val chunks = document.select("script[src*=/_next/static/chunks/]").map { it.absUrl("src") }.distinct()
        for (chunk in chunks) {
            val script = client.get(chunk).use { it.body.string() }
            val lists = FILTER_LIST_REGEX.findAll(script).associate { it.groupValues[1] to it.groupValues[2].parseAs<List<String>>() }
            val genres = lists["GENRES"] ?: continue
            return FilterDataDto(genres, lists["TAGS"].orEmpty()).toJsonElement()
        }
        throw Exception("Filter lists not found")
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filters = mutableListOf<Filter<*>>(
            SortFilter(),
            StatusFilter(),
            LanguageFilter(),
        )
        data?.parseAs<FilterDataDto>()?.let {
            filters += Filter.Separator()
            filters += GenreMatchFilter()
            filters += GenreFilter(it.genres)
            filters += TagMatchFilter()
            filters += TagFilter(it.tags)
        }
        return FilterList(filters)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Include premium chapters in the chapter list (their content can't be read without unlocking them on the site)."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_SHOW_LOCKED = "show_locked_chapters"
        private val PARAGRAPH_REGEX = Regex("""\n\s*\n""")
        private val FILTER_LIST_REGEX = Regex("""\["(GENRES|TAGS)",0,(\[[^\]]*])""")
    }
}

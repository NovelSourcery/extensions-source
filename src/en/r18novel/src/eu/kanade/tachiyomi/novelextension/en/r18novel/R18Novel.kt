package eu.kanade.tachiyomi.novelextension.en.r18novel

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
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.formattedText
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class R18Novel :
    KeiSource(),
    NovelSource {

    override val supportsFilterFetching = true

    // Kept from the old ReadNovelFull-based version so existing library entries ("<id>" or "/webnovel/<id>/") still resolve.
    private val mangaPath = SlugPath("/webnovel/", "/")

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 2, period = 1.seconds)

    override suspend fun getPopularManga(page: Int): MangasPage = parseListing("$baseUrl/allvisit/?page=$page")

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseListing("$baseUrl/dayvisit/?page=$page")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("keyword", query.trim())
                .addQueryParameter("page", page.toString())
                .build()
            return parseListing(url.toString())
        }

        val genre = filters.firstInstanceOrNull<GenreFilter>()?.toValue()
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toValue()
        val list = filters.firstInstanceOrNull<ListFilter>()?.toValue() ?: "allvisit/"
        // Genre pages and the completed list support sorting; the ranking lists don't.
        val path = genre?.let { "genre/$it/" } ?: list
        val url = "$baseUrl/$path".toHttpUrl().newBuilder().apply {
            if (sort != null && (genre != null || list == "full.html")) addQueryParameter("sort", sort)
            addQueryParameter("page", page.toString())
        }.build()
        return parseListing(url.toString())
    }

    private suspend fun parseListing(url: String): MangasPage {
        val document = client.get(url).asJsoup()
        val mangas = document.select("#results-grid a.nl2-book-card, main a.nl2-book-card").distinctBy { it.attr("href") }.map { card ->
            SManga.create().apply {
                setSlugUrl(mangaPath, card.absUrl("href"))
                title = card.selectFirst("h3")?.text() ?: card.attr("title")
                author = card.selectFirst(".folio-spine-author")?.text()
                thumbnail_url = card.selectFirst("img")?.absUrl("src")
            }
        }
        val hasNextPage = document.selectFirst("a[aria-label=Next page]:not(.pointer-events-none)") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun getMangaUrl(manga: SManga): String = mangaPath.absolute(baseUrl, manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != "webnovel") return null
        val id = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        val document = client.get(mangaPath.absolute(baseUrl, id)).asJsoup()
        return parseDetails(document).apply { this.url = id }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val updatedManga = if (fetchDetails) parseDetails(document) else manga
        val updatedChapters = if (fetchChapters) fetchChapterList(document) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.folio-detail-title")!!.text()
        thumbnail_url = document.selectFirst(".folio-detail-cover img")?.absUrl("src")
        author = document.selectFirst(".folio-detail-author")?.text()?.takeIf { it.isNotEmpty() }
        genre = document.select(".folio-taxonomy-links a").eachText().distinct().joinToString().takeIf { it.isNotEmpty() }

        val facts = document.facts()
        status = when (facts["Status"]?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        description = buildString {
            document.selectFirst(".folio-synopsis-body")?.formattedText()?.takeIf { it.isNotEmpty() }?.let { append(it).append("\n\n") }
            facts.filterKeys { it != "Status" }.forEach { (key, value) -> append(key).append(": ").append(value).append('\n') }
        }.trim()
    }

    private fun Document.facts(): Map<String, String> = select("dl.folio-facts > div").associate {
        it.selectFirst("dt")!!.text() to it.selectFirst("dd")!!.text()
    }

    private suspend fun fetchChapterList(detailDocument: Document): List<SChapter> {
        val firstChapter = detailDocument.selectFirst("a.folio-primary-action[href]")?.absUrl("href") ?: return emptyList()
        // The chapter-list endpoint is keyed by a text slug that only the reader page exposes.
        val readerHtml = client.get(firstChapter).use { it.body.string() }
        val slug = TOC_SLUG_REGEX.find(readerHtml)?.groupValues?.get(1) ?: return emptyList()
        val url = "$baseUrl/ajax/chapter-list".toHttpUrl().newBuilder().addQueryParameter("slug", slug).build()
        val chapters = client.get(url).parseAs<ChapterListDto>().chapters
        val updated = DATE_FORMAT.tryParseDate(detailDocument.facts()["Updated"], ZoneOffset.UTC)
        return chapters.mapIndexed { index, chapter ->
            SChapter.create().apply {
                setUrlWithoutDomain(chapter.url)
                name = chapter.title
                chapter_number = chapter.index.toFloat()
                if (index == chapters.lastIndex) date_upload = updated
            }
        }.reversed()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, getChapterUrl(chapter)))

    override suspend fun fetchPageText(page: Page): String {
        val document = client.get(page.url).asJsoup()
        return document.selectFirst("#chr-content")?.html() ?: throw Exception("Chapter content not found")
    }

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/allvisit/").asJsoup()
        return document.select("#filters-sidebar a[href*=/genre/]")
            .map { FilterOptionDto(it.attr("href").substringAfter("/genre/").trimEnd('/'), it.text()) }
            .filter { it.name.isNotEmpty() }
            .distinctBy { it.value }
            .toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<List<FilterOptionDto>>().orEmpty()
        return FilterList(
            Filter.Header("Search text ignores filters"),
            ListFilter(),
            Filter.Header("Genre overrides the list; sort applies to genres and Completed"),
            GenreFilter(genres),
            SortFilter(),
        )
    }

    private open class SelectFilter(name: String, private val options: List<Pair<String, String?>>) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        fun toValue(): String? = options[state].second
    }

    private class ListFilter :
        SelectFilter(
            "List",
            listOf(
                "Ranking (all time)" to "allvisit/",
                "Ranking (monthly)" to "monthvisit/",
                "Latest" to "dayvisit/",
                "Completed" to "full.html",
            ),
        )

    private class GenreFilter(genres: List<FilterOptionDto>) : SelectFilter("Genre", listOf("Any" to null) + genres.map { it.name to it.value })

    private class SortFilter :
        SelectFilter(
            "Sort",
            listOf("Latest update" to null, "Most popular" to "popular", "Most chapters" to "chapters"),
        )

    companion object {
        private val TOC_SLUG_REGEX = Regex("""tocSlug\s*=\s*"([^"]+)"""")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
    }
}

@Serializable
private class FilterOptionDto(val value: String, val name: String)

@Serializable
private class ChapterListDto(val chapters: List<ChapterDto>)

@Serializable
private class ChapterDto(val title: String, val url: String, val index: Int)

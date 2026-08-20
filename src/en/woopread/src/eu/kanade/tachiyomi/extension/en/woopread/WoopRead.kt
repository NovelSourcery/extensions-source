package eu.kanade.tachiyomi.novelextension.en.woopread

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
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import kotlin.time.Instant

@Source
abstract class WoopRead :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    private fun browseUrl(page: Int, sortBy: String, filters: FilterList): HttpUrl {
        val url = "$baseUrl/api/novels".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sortBy", sortBy)
            .addQueryParameter("chapters", "Any")

        val language = filters.filterIsInstance<LanguageFilter>().firstOrNull()?.toUriPart() ?: "Any"
        val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.toUriPart() ?: "Any"
        val genres = filters.filterIsInstance<GenreFilter>().firstOrNull()
            ?.state?.filter { it.state }?.joinToString(",") { it.id } ?: ""

        url.addQueryParameter("language", language)
        url.addQueryParameter("status", status)
        url.addQueryParameter("genres", genres)
        return url.build()
    }

    private fun parseMangaListResponse(response: Response): MangasPage {
        val result = response.parseAs<ListResponse>()
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val mangas = result.novels.map { it.toSManga() }
        return MangasPage(mangas, page * PER_PAGE < result.totalCount)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaListResponse(client.get(browseUrl(page, "Popular", FilterList()), headers))

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaListResponse(client.get(browseUrl(page, "Updated", FilterList()), headers))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val url = "$baseUrl/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()
            return parseMangaListResponse(client.get(url, headers))
        }
        val sortBy = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart() ?: "Popular"
        return parseMangaListResponse(client.get(browseUrl(page, sortBy, filters), headers))
    }

    private val mangaPathTemplate = SlugPath("/series/")

    // manga.url is just the slug; this normalizes both bare-slug (current) and pre-migration
    // full-path stored values back to the bare slug.
    private fun SManga.slug(): String = mangaPathTemplate.slug(mangaPathTemplate.resolve(url))

    private fun NovelDto.toSManga() = SManga.create().apply {
        title = this@toSManga.title
        url = slug
        thumbnail_url = cover
        author = this@toSManga.author
        genre = displayGenres.joinToString()
        status = parseStatus(this@toSManga.status)
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPathTemplate.resolve(manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = mangaPathTemplate.slug(url.encodedPath)
        val response = client.get("$baseUrl/series/$slug", headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        return fetchNovelDetails(SManga.create().apply { this.url = slug })
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = if (fetchDetails) async { fetchNovelDetails(manga) } else null
        val chaptersDeferred = if (fetchChapters) async { fetchNovelChapterList(manga) } else null

        SMangaUpdate(
            manga = detailsDeferred?.await() ?: manga,
            chapters = chaptersDeferred?.await() ?: chapters,
        )
    }

    private suspend fun fetchNovelDetails(manga: SManga): SManga {
        val response = client.get("$baseUrl/series/${manga.slug()}", headers)
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBeforeLast(" - WoopRead")?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            author = doc.selectFirst("span:matchesOwn(^Author$) ~ a, span:contains(Author) + a")?.text()
            genre = doc.select("a[href*=genres=], a[href*=tags=]")
                .map { it.text() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
                .joinToString()
            status = parseStatus(
                doc.selectFirst("span:matchesOwn(^Status$) ~ span, span:contains(Status) + span")?.text(),
            )
            description = buildString {
                val type = doc.selectFirst("span:matchesOwn(^Type$) ~ span, span:contains(Type) + span")?.text()
                if (!type.isNullOrEmpty()) append("Type: $type\n")
                val synopsis = doc.selectFirst("#novel-description-content")?.text()
                if (!synopsis.isNullOrEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append(synopsis)
                }
            }.trim()
        }
    }

    private suspend fun fetchNovelChapterList(manga: SManga): List<SChapter> {
        val seriesSlug = manga.slug()
        val response = client.get("$baseUrl/api/novels/$seriesSlug/chapters", headers)
        val chapters = response.parseAs<List<ChapterDto>>()
        return chapters.sortedByDescending { it.number }.map { dto ->
            SChapter.create().apply {
                name = dto.title
                url = "$seriesSlug/${dto.slug}"
                chapter_number = dto.number.toFloat()
                date_upload = dto.publishDate?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() } ?: 0L
            }
        }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/series/${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get("$baseUrl/series/${chapter.url}", headers)
        return listOf(Page(0, response.request.url.toString()))
    }

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) page.url else "$baseUrl/series/${page.url}"
        val doc = client.get(url, headers).asJsoup()
        val paragraphs = doc.select("[data-paragraph-index]")
            .sortedBy { it.attr("data-paragraph-index").toIntOrNull() ?: 0 }
        if (paragraphs.isNotEmpty()) {
            return paragraphs.joinToString("") { "<p>${it.html()}</p>" }
        }
        return doc.selectFirst("[id^=chapter-] .space-y-4, [id^=chapter-]")?.html().orEmpty()
    }

    private fun parseStatus(status: String?) = when (status?.lowercase()?.trim()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "dropped" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("Filters are ignored during text search"),
        SortFilter(),
        StatusFilter(),
        LanguageFilter(),
        GenreFilter(),
    )

    private class SortFilter :
        Filter.Select<String>(
            "Sort by",
            arrayOf("Popular", "New", "Rating", "Chapters", "Updated"),
        ) {
        fun toUriPart() = arrayOf("Popular", "New", "Rating", "Chapters", "Updated")[state]
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("Any", "Ongoing", "Completed", "Hiatus", "Dropped"),
        ) {
        fun toUriPart() = arrayOf("Any", "Ongoing", "Completed", "Hiatus", "Dropped")[state]
    }

    private class LanguageFilter :
        Filter.Select<String>(
            "Language",
            arrayOf("Any", "Korean", "Chinese", "Japanese", "English"),
        ) {
        fun toUriPart() = arrayOf("Any", "Korean", "Chinese", "Japanese", "English")[state]
    }

    private class Genre(name: String, val id: String) : Filter.CheckBox(name)
    private class GenreFilter : Filter.Group<Genre>("Genres", GENRES.map { Genre(it.first, it.second) })

    companion object {
        private const val PER_PAGE = 20
        private val GENRES = listOf(
            "Action" to "action", "Adult" to "adult", "Adventure" to "adventure", "Comedy" to "comedy",
            "Drama" to "drama", "Ecchi" to "ecchi", "Fantasy" to "fantasy", "Gender Bender" to "gender-bender",
            "Harem" to "harem", "Historical" to "historical", "Horror" to "horror", "Josei" to "josei",
            "Martial Arts" to "martial-arts", "Mature" to "mature", "Mecha" to "mecha", "Mystery" to "mystery",
            "Psychological" to "psychological", "Romance" to "romance", "School Life" to "school-life",
            "Sci-fi" to "sci-fi", "Seinen" to "seinen", "Shoujo" to "shoujo", "Shounen" to "shounen",
            "Slice of Life" to "slice-of-life", "Sports" to "sports", "Supernatural" to "supernatural",
            "Tragedy" to "tragedy",
        )
    }
}

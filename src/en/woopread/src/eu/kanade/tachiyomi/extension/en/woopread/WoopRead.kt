package eu.kanade.tachiyomi.novelextension.en.woopread

import eu.kanade.tachiyomi.network.GET
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
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class WoopRead :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun browseRequest(page: Int, sortBy: String, filters: FilterList): Request {
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
        return GET(url.build(), headers)
    }

    private fun parseMangaListResponse(response: Response): MangasPage {
        val result = response.parseAs<ListResponse>()
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val mangas = result.novels.map { it.toSManga() }
        return MangasPage(mangas, page * PER_PAGE < result.totalCount)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaListResponse(client.newCall(browseRequest(page, "Popular", FilterList())).execute())

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaListResponse(client.newCall(browseRequest(page, "Updated", FilterList())).execute())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val url = "$baseUrl/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()
            return parseMangaListResponse(client.newCall(GET(url, headers)).execute())
        }
        val sortBy = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart() ?: "Popular"
        return parseMangaListResponse(client.newCall(browseRequest(page, sortBy, filters)).execute())
    }

    // manga.url is just the slug; strip any wrapping path/id so old stored urls still resolve.
    private fun extractSlug(url: String): String = url.trim('/').substringAfterLast("/")

    private fun NovelDto.toSManga() = SManga.create().apply {
        title = this@toSManga.title
        url = slug
        thumbnail_url = cover
        author = this@toSManga.author
        genre = displayGenres.joinToString()
        status = parseStatus(this@toSManga.status)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${extractSlug(manga.url)}"

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
        val response = client.newCall(GET("$baseUrl/series/${extractSlug(manga.url)}", headers)).execute()
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBeforeLast(" - WoopRead")?.trim().orEmpty()
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            author = doc.selectFirst("span:matchesOwn(^Author$) ~ a, span:contains(Author) + a")?.text()
            genre = doc.select("a[href*=genres=], a[href*=tags=]")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
                .joinToString()
            status = parseStatus(
                doc.selectFirst("span:matchesOwn(^Status$) ~ span, span:contains(Status) + span")?.text(),
            )
            description = buildString {
                val type = doc.selectFirst("span:matchesOwn(^Type$) ~ span, span:contains(Type) + span")?.text()?.trim()
                if (!type.isNullOrEmpty()) append("Type: $type\n")
                val synopsis = doc.selectFirst("#novel-description-content")?.text()?.trim()
                if (!synopsis.isNullOrEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append(synopsis)
                }
            }.trim()
        }
    }

    private suspend fun fetchNovelChapterList(manga: SManga): List<SChapter> {
        val seriesSlug = extractSlug(manga.url)
        val response = client.newCall(GET("$baseUrl/api/novels/$seriesSlug/chapters", headers)).execute()
        val chapters = response.parseAs<List<ChapterDto>>()
        return chapters.sortedByDescending { it.number }.map { dto ->
            SChapter.create().apply {
                name = dto.title
                url = "$seriesSlug/${dto.slug}"
                chapter_number = dto.number.toFloat()
                date_upload = dto.publishDate?.let { runCatching { dateFormat.parse(it)?.time }.getOrNull() } ?: 0L
            }
        }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/series/${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.newCall(GET("$baseUrl/series/${chapter.url}", headers)).execute()
        return listOf(Page(0, response.request.url.toString()))
    }

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) page.url else "$baseUrl/series/${page.url}"
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
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

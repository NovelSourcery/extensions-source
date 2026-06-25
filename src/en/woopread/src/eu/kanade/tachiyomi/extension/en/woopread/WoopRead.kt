package eu.kanade.tachiyomi.novelextension.en.woopread

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class WoopRead :
    HttpSource(),
    NovelSource {

    override val name = "WoopRead"
    override val baseUrl = "https://woopread.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.client

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

    override fun popularMangaRequest(page: Int): Request = browseRequest(page, "Popular", FilterList())
    override fun latestUpdatesRequest(page: Int): Request = browseRequest(page, "Updated", FilterList())

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            val url = "$baseUrl/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()
            return GET(url, headers)
        }
        val sortBy = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart() ?: "Popular"
        return browseRequest(page, sortBy, filters)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<ListResponse>()
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val mangas = result.novels.map { it.toSManga() }
        return MangasPage(mangas, page * PER_PAGE < result.totalCount)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    private fun NovelDto.toSManga() = SManga.create().apply {
        title = this@toSManga.title
        url = "$id/$slug"
        thumbnail_url = cover
        author = this@toSManga.author
        genre = displayGenres.joinToString()
        status = parseStatus(this@toSManga.status)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url.substringAfter("/")}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/series/${manga.url.substringAfter("/")}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
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

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val id = manga.url.substringBefore("/")
        val seriesSlug = manga.url.substringAfter("/")
        val response = client.newCall(GET("$baseUrl/api/novels/$id/chapters", headers)).execute()
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

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/series/${chapter.url}"

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/series/${chapter.url}", headers)

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

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

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun parseStatus(status: String?) = when (status?.lowercase()?.trim()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "dropped" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    override fun getFilterList() = FilterList(
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

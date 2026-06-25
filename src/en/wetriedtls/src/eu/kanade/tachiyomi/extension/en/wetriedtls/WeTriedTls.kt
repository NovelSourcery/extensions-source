package eu.kanade.tachiyomi.novelextension.en.wetriedtls

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.setAltTitles
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class WeTriedTls :
    HttpSource(),
    NovelSource {

    override val name = "WeTried Translations"
    override val baseUrl = "https://wetriedtls.com"
    private val apiUrl = "https://api.wetriedtls.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.client

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun queryUrl(page: Int, query: String, orderBy: String, status: String) = "$apiUrl/query".toHttpUrl().newBuilder()
        .addQueryParameter("page", page.toString())
        .addQueryParameter("perPage", "20")
        .addQueryParameter("series_type", "Novel")
        .addQueryParameter("query_string", query)
        .addQueryParameter("orderBy", orderBy)
        .addQueryParameter("adult", "true")
        .addQueryParameter("status", status)
        .addQueryParameter("tags_ids", "[]")
        .build()

    override fun popularMangaRequest(page: Int): Request = GET(queryUrl(page, "", "total_views", "All"), headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<QueryResponse>()
        val entries = result.data.map { it.toSManga() }
        return MangasPage(entries, result.meta.nextPageUrl != null)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(queryUrl(page, "", "latest", "All"), headers)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.toUriPart() ?: "All"
        val orderBy = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart() ?: "created_at"
        return GET(queryUrl(page, query, orderBy, status), headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    private fun SeriesDto.toSManga() = SManga.create().apply {
        title = this@toSManga.title
        url = "$id/$seriesSlug"
        thumbnail_url = thumbnail
        status = parseStatus(this@toSManga.status)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url.substringAfter("/")}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/series/${manga.url.substringAfter("/")}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val dto = response.parseAs<SeriesDto>()
        return SManga.create().apply {
            title = dto.title
            url = "${dto.id}/${dto.seriesSlug}"
            thumbnail_url = dto.thumbnail
            author = dto.author
            status = parseStatus(dto.status)
            genre = dto.tags.joinToString { it.name }

            val altNames = dto.alternativeNames
                ?.split(",", ";")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && it != dto.title }
                .orEmpty()
            if (altNames.isNotEmpty()) setAltTitles(altNames)

            description = buildString {
                dto.rating?.let { append("Rating: $it\n") }
                dto.studio?.takeIf { it.isNotBlank() }?.let { append("Group: $it\n") }
                dto.releaseYear?.takeIf { it.isNotBlank() }?.let { append("Year: $it\n") }
                val desc = dto.description?.let { Jsoup.parseBodyFragment(it).text() }
                if (!desc.isNullOrBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(desc)
                }
            }.trim()
        }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val seriesId = manga.url.substringBefore("/")
        val seriesSlug = manga.url.substringAfter("/")
        val chapters = mutableListOf<SChapter>()
        var page = 1
        while (true) {
            val response = client.newCall(
                GET("$apiUrl/chapters/$seriesId?page=$page&perPage=100", headers),
            ).execute()
            val result = response.parseAs<ChaptersResponse>()
            result.data.forEach { dto ->
                chapters.add(
                    SChapter.create().apply {
                        name = dto.chapterTitle?.takeIf { it.isNotBlank() } ?: dto.chapterName
                        url = "$seriesSlug/${dto.chapterSlug}"
                        chapter_number = dto.index.toFloatOrNull() ?: -1f
                        date_upload = dto.createdAt?.let { dateFormat.parse(it)?.time } ?: 0L
                    },
                )
            }
            if (result.meta.nextPageUrl == null) break
            page++
        }
        return chapters.sortedByDescending { it.chapter_number }
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/series/${chapter.url}"

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

    override suspend fun fetchPageText(page: Page): String {
        val (seriesSlug, chapterSlug) = page.url.removePrefix(baseUrl).trim('/').split("/").let {
            it[it.size - 2] to it[it.size - 1]
        }
        val response = client.newCall(GET("$apiUrl/chapter/$seriesSlug/$chapterSlug", headers)).execute()
        return response.parseAs<ChapterContentResponse>().chapter.chapterContent.orEmpty()
    }

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/series/${chapter.url}", headers)

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun parseStatus(status: String?) = when (status?.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "dropped", "cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    override fun getFilterList() = FilterList(
        SortFilter(),
        StatusFilter(),
    )

    private class SortFilter :
        Filter.Select<String>(
            "Sort by",
            arrayOf("Latest", "Popular", "Rating", "New"),
        ) {
        fun toUriPart() = arrayOf("latest", "total_views", "rating", "created_at")[state]
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("All", "Ongoing", "Completed", "Hiatus", "Dropped"),
        ) {
        fun toUriPart() = arrayOf("All", "Ongoing", "Completed", "Hiatus", "Dropped")[state]
    }
}

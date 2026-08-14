package eu.kanade.tachiyomi.novelextension.en.wetriedtls

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.setAltTitles
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Source
abstract class WeTriedTls :
    KeiSource(),
    NovelSource {

    private val apiUrl = "https://api.wetriedtls.com"
    override val supportsLatest = true

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
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

    private fun parseMangaListResponse(response: Response): MangasPage {
        val result = response.parseAs<QueryResponse>()
        val entries = result.data.map { it.toSManga() }
        return MangasPage(entries, result.meta.nextPageUrl != null)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaListResponse(client.newCall(GET(queryUrl(page, "", "total_views", "All"), headers)).execute())

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaListResponse(client.newCall(GET(queryUrl(page, "", "latest", "All"), headers)).execute())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.toUriPart() ?: "All"
        val orderBy = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart() ?: "created_at"
        return parseMangaListResponse(client.newCall(GET(queryUrl(page, query, orderBy, status), headers)).execute())
    }

    // manga.url is just the slug; strip any wrapping path/id so old stored urls still resolve.
    private fun extractSlug(url: String): String = url.trim('/').substringAfterLast("/")

    private fun SeriesDto.toSManga() = SManga.create().apply {
        title = this@toSManga.title
        url = seriesSlug
        thumbnail_url = thumbnail
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
        val response = client.newCall(GET("$apiUrl/series/${extractSlug(manga.url)}", headers)).execute()
        val dto = response.parseAs<SeriesDto>()
        return SManga.create().apply {
            title = dto.title
            url = dto.seriesSlug
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

    private suspend fun fetchNovelChapterList(manga: SManga): List<SChapter> {
        val seriesSlug = extractSlug(manga.url)
        val chapters = mutableListOf<SChapter>()
        var page = 1
        while (true) {
            val response = client.newCall(
                GET("$apiUrl/chapters/$seriesSlug?page=$page&perPage=100", headers),
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

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/series/${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.newCall(GET("$baseUrl/series/${chapter.url}", headers)).execute()
        return listOf(Page(0, response.request.url.toString()))
    }

    override suspend fun fetchPageText(page: Page): String {
        val (seriesSlug, chapterSlug) = page.url.removePrefix(baseUrl).trim('/').split("/").let {
            it[it.size - 2] to it[it.size - 1]
        }
        val response = client.newCall(GET("$apiUrl/chapter/$seriesSlug/$chapterSlug", headers)).execute()
        return response.parseAs<ChapterContentResponse>().chapter.chapterContent.orEmpty()
    }

    private fun parseStatus(status: String?) = when (status?.lowercase()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "dropped", "cancelled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
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

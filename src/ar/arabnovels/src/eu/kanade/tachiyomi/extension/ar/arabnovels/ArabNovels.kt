package eu.kanade.tachiyomi.extension.ar.arabnovels

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
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.extractNextJs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import java.net.URLDecoder
import kotlin.time.Instant

@Source
abstract class ArabNovels :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    /** Novels live at "/novels/<slug>/", chapters at "/read/<slug>/<number>". */
    private val mangaPath = SlugPath("/novels/")

    // The site requires an Arabic Accept-Language header, otherwise responses may be blocked.
    override fun Headers.Builder.configureHeaders(): Headers.Builder = this.set("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")

    override suspend fun getPopularManga(page: Int): MangasPage = parseListResponse(client.get(browseUrl(page, "popular"), Fetch.DOCUMENT))

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseListResponse(client.get(browseUrl(page, "updated"), Fetch.DOCUMENT))

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (query.isNotBlank()) {
            val url = "$baseUrl/novels".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
                .build()
            return parseListResponse(client.get(url, Fetch.DOCUMENT))
        }
        val sortBy = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart() ?: "updated"
        return parseListResponse(client.get(browseUrl(page, sortBy), Fetch.DOCUMENT))
    }

    override fun getMangaUrl(manga: SManga): String = mangaPath.absolute(baseUrl, manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = mangaPath.slug(url.encodedPath)
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
        val response = client.get("$baseUrl/novels/${manga.url}", Fetch.DOCUMENT)
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
            thumbnail_url =
                doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty()
            author = doc.selectFirst("a[href^='/u/']")?.text()?.trim().orEmpty()
            description =
                doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim().orEmpty()
            status = parseStatus(
                doc.selectFirst("span:matchesOwn(مستمرة|مكتملة)")?.text()?.trim(),
            )
            url = manga.url
        }
    }

    private suspend fun fetchNovelChapterList(manga: SManga): List<SChapter> {
        val doc = client.get("$baseUrl/novels/${manga.url}", Fetch.DOCUMENT).asJsoup()
        val dto = doc.extractNextJs<ChapterListDto> { it is JsonObject && "chapters" in it }
            ?: throw Exception("Could not find chapter list in page data")
        return dto.chapters.map { chapter ->
            SChapter.create().apply {
                name = chapter.title
                url = "${manga.url}/${chapter.number}"
                chapter_number = chapter.number.toFloat()
                date_upload = chapter.publishedAt.let { Instant.parseOrNull(it) }?.toEpochMilliseconds() ?: 0L
            }
        }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/read/${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, "$baseUrl/read/${chapter.url}"))

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.get(page.url, Fetch.DOCUMENT).asJsoup()
        return doc.selectFirst("#chapter-body")?.html().orEmpty()
    }

    private suspend fun parseListResponse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = doc.select("article.group.relative").mapNotNull { card ->
            val link = card.selectFirst("a[href^='/novels/']") ?: return@mapNotNull null
            val slug = link.attr("href").removePrefix("/novels/").trimEnd('/')
            if (slug.isBlank()) return@mapNotNull null
            val img = card.selectFirst("img[src*='/media/covers/']")?.attr("src") ?: ""
            val cover = decodeCover(img)
            val cardTitle = card.selectFirst("h3")?.text()?.trim().orEmpty()
            if (cardTitle.isBlank()) return@mapNotNull null
            val cardAuthor = card.select("p.line-clamp-1.text-xs.text-muted-foreground")
                .first()?.text()?.trim().orEmpty()
            val cardStatus = parseStatus(
                card.selectFirst("span:matchesOwn(مستمرة|مكتملة)")?.text()?.trim(),
            )
            SManga.create().apply {
                this.url = slug
                this.title = cardTitle
                this.thumbnail_url = cover
                this.author = cardAuthor
                this.status = cardStatus
            }
        }
        return MangasPage(mangas, mangas.size >= PER_PAGE)
    }

    private fun browseUrl(page: Int, sortBy: String): HttpUrl = "$baseUrl/novels".toHttpUrl().newBuilder()
        .addQueryParameter("sort", sortBy)
        .addQueryParameter("page", page.toString())
        .build()

    private fun decodeCover(src: String): String {
        val encoded = src.substringAfter("url=").substringBefore("&")
        val decoded = URLDecoder.decode(encoded, "UTF-8")
        return if (decoded.startsWith("http")) decoded else "$baseUrl$decoded"
    }

    private fun parseStatus(status: String?) = when (status?.lowercase()?.trim()) {
        "مستمرة" -> SManga.ONGOING
        "مكتملة" -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("Filters are ignored during text search"),
        SortFilter(),
        StatusFilter(),
    )

    private class SortFilter :
        Filter.Select<String>(
            "Sort by",
            arrayOf("Modern", "Popular"),
        ) {
        fun toUriPart() = arrayOf("updated", "popular")[state]
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("Any", "Ongoing", "Completed"),
        ) {
        fun toUriPart() = arrayOf(null, null, "COMPLETED")[state]
    }

    @Serializable
    class ChapterListDto(
        @SerialName("chapters") val chapters: List<ChapterDto>,
    )

    @Serializable
    class ChapterDto(
        @SerialName("id") val id: String,
        @SerialName("number") val number: Int,
        @SerialName("title") val title: String,
        @SerialName("access") val access: String,
        @SerialName("publishedAt") val publishedAt: String,
        @SerialName("wordCount") val wordCount: Int,
        @SerialName("viewCount") val viewCount: Int,
    )

    companion object {
        private const val PER_PAGE = 20
    }
}

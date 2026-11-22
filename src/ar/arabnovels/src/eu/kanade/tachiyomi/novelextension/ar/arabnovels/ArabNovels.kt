package eu.kanade.tachiyomi.novelextension.ar.arabnovels

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
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
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

    override suspend fun getPopularManga(page: Int): MangasPage = parseListResponse(client.get(browseUrl(page, "popular", null), Fetch.DOCUMENT))

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseListResponse(client.get(browseUrl(page, "updated", null), Fetch.DOCUMENT))

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
        val sortBy = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: "updated"
        val status = filters.firstInstanceOrNull<StatusFilter>()?.toUriPart()
        return parseListResponse(client.get(browseUrl(page, sortBy, status), Fetch.DOCUMENT))
    }

    override fun getMangaUrl(manga: SManga): String = mangaPath.absolute(baseUrl, manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val path = url.encodedPath
        if (!path.startsWith("/novels/")) return null
        val slug = path.removePrefix("/novels/").trimEnd('/')
        if (slug.isBlank()) return null
        return fetchNovelDetails(slug)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Details and chapters come from the same novel page - fetch once.
        val doc = client.get("$baseUrl/novels/${manga.url}", Fetch.DOCUMENT).asJsoup()
        val updatedManga = if (fetchDetails) parseNovelDetails(doc, manga.url) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(doc, manga.url) else chapters
        return SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
    }

    private suspend fun fetchNovelDetails(slug: String): SManga {
        val doc = client.get("$baseUrl/novels/$slug", Fetch.DOCUMENT).asJsoup()
        return parseNovelDetails(doc, slug)
    }

    private fun parseNovelDetails(doc: Document, slug: String): SManga {
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        if (title.isBlank()) throw Exception("Missing title for $slug")
        return SManga.create().apply {
            this.url = slug
            this.title = title
            thumbnail_url =
                doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            author =
                doc.selectFirst("a[href^='/u/']")?.text().orEmpty()
            description =
                doc.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
            status = parseStatus(
                doc.selectFirst("span:matchesOwn(مستمرة|مكتملة)")?.text(),
            )
        }
    }

    private fun parseChapterList(doc: Document, slug: String): List<SChapter> {
        val dto = doc.extractNextJs<ChapterListDto> { it is JsonObject && "chapters" in it }
            ?: throw Exception("Could not find chapter list in page data")
        return dto.chapters.map { chapter ->
            SChapter.create().apply {
                name = chapter.title
                // The site may use fractional chapter numbers (e.g. 1420.5 for side chapters),
                // so keep the raw literal for the URL and the numeric value for sorting.
                url = "$slug/${chapter.number.content}"
                chapter_number = chapter.number.doubleOrNull?.toFloat() ?: 0f
                date_upload = chapter.publishedAt.let { Instant.parseOrNull(it) }?.toEpochMilliseconds() ?: 0L
            }
        }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/read/${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, "$baseUrl/read/${chapter.url}"))

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.get(page.url, Fetch.DOCUMENT).asJsoup()
        val body = doc.selectFirst("#chapter-body") ?: return ""
        sanitize(body)
        return body.html()
    }

    /** Removes executable elements and event-handler attributes from the chapter body. */
    private fun sanitize(element: Element) {
        element.select("script, style, iframe, ins, noscript, [onclick], [onerror], [onload]").remove()
        element.select(".ads, .adsbygoogle, [class*='ad']").remove()
    }

    private suspend fun parseListResponse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = doc.select("article.group.relative").mapNotNull { card ->
            val link = card.selectFirst("a[href^='/novels/']") ?: return@mapNotNull null
            val slug = link.attr("href").removePrefix("/novels/").trimEnd('/')
            if (slug.isBlank()) return@mapNotNull null
            // The cover src is a Next.js image URL with the real path percent-encoded in the
            // "url" query parameter, e.g. /_next/image?url=%2Fmedia%2Fcovers%2F<uuid>.webp&w=3840&q=75
            val cover = decodeCover(card.selectFirst("img")?.attr("src").orEmpty())
            val cardTitle = card.selectFirst("h3")?.text().orEmpty()
            if (cardTitle.isBlank()) return@mapNotNull null
            val cardAuthor = card.select("p.line-clamp-1.text-xs.text-muted-foreground")
                .first()?.text().orEmpty()
            val cardStatus = parseStatus(
                card.selectFirst("span:matchesOwn(مستمرة|مكتملة)")?.text(),
            )
            SManga.create().apply {
                this.url = slug
                this.title = cardTitle
                this.thumbnail_url = cover
                this.author = cardAuthor
                this.status = cardStatus
            }
        }
        return MangasPage(mangas, mangas.size >= 20)
    }

    private fun browseUrl(page: Int, sortBy: String, status: String?): HttpUrl = "$baseUrl/novels".toHttpUrl().newBuilder()
        .addQueryParameter("sort", sortBy)
        .addQueryParameter("page", page.toString())
        .let { if (status != null) it.addQueryParameter("status", status) else it }
        .build()

    private fun decodeCover(src: String): String {
        if (src.isBlank()) return ""
        val encoded = src.substringAfter("url=").substringBefore("&")
        val decoded = URLDecoder.decode(encoded, "UTF-8")
        val url = if (decoded.startsWith("http")) decoded else "$baseUrl$decoded"
        // Only allow the source site or its media host.
        val host = url.toHttpUrl().host
        if (host == "arabnovels.com" || host.endsWith(".arabnovels.com")) return url
        return ""
    }

    private fun parseStatus(status: String?) = when (status?.lowercase()) {
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
        val chapters: List<ChapterDto>,
    )

    @Serializable
    class ChapterDto(
        val number: JsonPrimitive,
        val title: String,
        val publishedAt: String,
    )

    companion object {
        private const val PER_PAGE = 20
    }
}

package eu.kanade.tachiyomi.novelextension.en.leafstudio

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * LeafStudio (leafstudio.site). Plain WordPress-theme HTML, no API. As of 2026-08 the site has
 * moved to a paid "emerald" unlock model - most/all chapters are `.premium_chap` (login-gated),
 * only `.free_chap` ones (if any remain) return real prose; premium chapters are still listed
 * (prefixed) so the list isn't empty, but fetching one just returns the site's own login prompt.
 */
@Source
abstract class LeafStudio :
    KeiSource(),
    NovelSource {

    override val supportsLatest = false

    /** [SManga.url] is stored as the bare slug (site has no fixed path segment before it); a
     * stored value starting with "/" that resolves to more than one segment is a pre-existing
     * full-path entry and is resolved unchanged. */
    private val mangaPath = SlugPath("/")

    /** Stores [SManga.url] as a bare slug via [mangaPath], reusing [setUrlWithoutDomain]'s
     * domain-stripping for a raw absolute href. */
    private fun SManga.setSlugUrl(href: String) {
        setUrlWithoutDomain(href)
        url = mangaPath.slug(url)
    }

    // ======================== Browse / Search ========================

    private fun buildPopularMangaRequest(page: Int): Request {
        val path = if (page > 1) "/novels/page/$page" else "/novels"
        return GET("$baseUrl$path", headers)
    }

    private fun buildLatestUpdatesRequest(page: Int): Request = buildPopularMangaRequest(page)

    private fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val path = if (page > 1) "/novels/page/$page" else "/novels"
        val url = "$baseUrl$path".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("type", "")
            .addQueryParameter("language", "")
            .addQueryParameter("status", "")
            .addQueryParameter("sort", "")
            .build()
        return GET(url, headers)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = parseNovelList(client.newCall(buildPopularMangaRequest(page)).execute().asJsoup())

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseNovelList(client.newCall(buildLatestUpdatesRequest(page)).execute().asJsoup())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = parseNovelList(client.newCall(buildSearchMangaRequest(page, query, filters)).execute().asJsoup())

    private fun parseNovelList(document: Document): MangasPage {
        val mangas = document.select("a.novel-item").map { element ->
            SManga.create().apply {
                setSlugUrl(element.attr("abs:href"))
                title = element.selectFirst("p.novel-item-title")?.text().orEmpty()
                thumbnail_url = element.selectFirst("img.novel-item-Cover")?.attr("abs:src")
            }
        }
        // The site has a single small catalog; /novels/page/N repeats page 1 (no real pagination).
        return MangasPage(mangas, false)
    }

    // ======================== Details + Chapters ========================

    private fun buildMangaDetailsRequest(manga: SManga): Request = GET(baseUrl + mangaPath.resolve(manga.url), headers)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Details and the chapter list both live on the same novel page - fetch it once.
        val doc = client.newCall(buildMangaDetailsRequest(manga)).execute().asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(doc) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.title")?.text().orEmpty()
        thumbnail_url = document.selectFirst("img#novel_cover")?.attr("abs:src")
        description = document.select("div.desc_div > p").joinToString("\n\n") { it.text() }.ifBlank { null }
        genre = document.select("div#tags_div > a.novel_genre").joinToString { it.text() }.ifBlank { null }
        status = when (document.selectFirst("a#novel_status")?.text()) {
            "Active" -> SManga.ONGOING
            "Completed" -> SManga.COMPLETED
            "Hiatus" -> SManga.ON_HIATUS
            "Dropped", "Cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    // Both free and premium(locked) chapters are listed - locked ones are prefixed so the reader
    // can see they exist, even though fetching one currently just returns a login prompt.
    private fun parseChapterList(document: Document): List<SChapter> = document.select("a.chap").mapNotNull { element ->
        val href = element.attr("abs:href")
        if (href.isBlank()) return@mapNotNull null
        val rawName = element.selectFirst("p")?.text().orEmpty()
        val locked = element.hasClass("premium_chap")
        SChapter.create().apply {
            setUrlWithoutDomain(href)
            name = if (locked) "🔒 $rawName" else rawName
            chapter_number = Regex("""Chapter\s+(\d+(?:\.\d+)?)""").find(rawName)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
        }
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPath.resolve(manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val manga = SManga.create().apply { this.url = mangaPath.slug(url.encodedPath) }
        val response = client.newCall(buildMangaDetailsRequest(manga)).execute()
        if (!response.isSuccessful) return null
        return parseMangaDetails(response.asJsoup()).apply { this.url = manga.url }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    // ======================== Content ========================
    // Single text page fetched once in fetchPageText, matching the ReadNovelFull multisrc idiom.

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.newCall(GET(baseUrl + chapter.url, headers)).execute()
        return listOf(Page(0, response.request.url.encodedPath))
    }

    override suspend fun fetchPageText(page: Page): String {
        val pageUrl = if (page.url.startsWith("http")) page.url else baseUrl + page.url
        val response = client.newCall(GET(pageUrl, headers)).execute()
        val document = response.asJsoup()
        return document.select("article > p.chapter_content").joinToString("") { paragraphToHtml(it) }
    }

    private fun paragraphToHtml(element: Element): String {
        val html = element.html().trim()
        return if (html.isBlank()) "" else "<p>$html</p>"
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()
}

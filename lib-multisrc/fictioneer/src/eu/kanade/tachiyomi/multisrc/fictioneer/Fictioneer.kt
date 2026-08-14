package eu.kanade.tachiyomi.multisrc.fictioneer

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response

/**
 * Base class for sites using the Fictioneer WordPress plugin.
 */
abstract class Fictioneer :
    KeiSource(),
    NovelSource {

    override val supportsLatest = false

    /** The browse page path (e.g. "browse", "stories", "novels", "collection/novels"). */
    protected open val browsePage: String = "stories"

    /**
     * The site's novel detail URL shape, as `/story/<slug>`. [SManga.url] is stored as the bare
     * slug (see [SlugPath]); a stored value starting with "/" is a pre-existing full-path entry
     * from before this source adopted slug storage, and is resolved unchanged regardless of
     * this template.
     */
    protected open val mangaPathTemplate: SlugPath = SlugPath("/story/")

    /** Stores [SManga.url] as a bare slug via [mangaPathTemplate]. */
    protected fun SManga.setSlugUrl(href: String) = setSlugUrl(mangaPathTemplate, href)

    // -- Browse --

    protected open fun buildPopularMangaRequest(page: Int): Request {
        val pagePath = if (page == 1) "" else "page/$page/"
        return GET("$baseUrl/$browsePage/$pagePath", headers)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = parsePopularMangaResponse(client.newCall(buildPopularMangaRequest(page)).execute())

    protected open fun parsePopularMangaResponse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val novels = doc.select("#featured-list > li > div > div, #list-of-stories > li > div > div").mapNotNull { element ->
            val titleEl = element.selectFirst("h3 > a") ?: return@mapNotNull null
            val url = titleEl.attr("href")
            SManga.create().apply {
                title = titleEl.text().trim()
                setSlugUrl(url.trimEnd('/'))
                thumbnail_url = element.selectFirst("a.cell-img:has(img)")?.attr("href")
            }
        }
        val hasNext = doc.selectFirst("a.next.page-numbers") != null
        return MangasPage(novels, hasNext)
    }

    protected open fun buildLatestUpdatesRequest(page: Int): Request = buildPopularMangaRequest(page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = parsePopularMangaResponse(client.newCall(buildLatestUpdatesRequest(page)).execute())

    // -- Search --

    protected open fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val pagePath = if (page == 1) "" else "page/$page/"
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/$pagePath?s=$encodedQuery&post_type=fcn_story", headers)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val response = client.newCall(buildSearchMangaRequest(page, query, filters)).execute()
        val doc = response.asJsoup()
        val novels = doc.select("#search-result-list > li > div > div").mapNotNull { element ->
            val titleEl = element.selectFirst("h3 > a") ?: return@mapNotNull null
            val url = titleEl.attr("href")
            SManga.create().apply {
                title = titleEl.text().trim()
                setSlugUrl(url.trimEnd('/'))
                thumbnail_url = element.selectFirst("a.cell-img:has(img)")?.attr("href")
            }
        }
        return MangasPage(novels, false)
    }

    // -- Details + Chapters --

    protected open fun buildMangaDetailsRequest(manga: SManga): Request = GET(baseUrl + mangaPathTemplate.resolve(manga.url), headers)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Details and the chapter list both live on the same story page - fetch it once.
        val doc = client.newCall(buildMangaDetailsRequest(manga)).execute().asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(doc) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    protected open fun parseMangaDetails(doc: org.jsoup.nodes.Document): SManga = SManga.create().apply {
        title = doc.selectFirst("h1.story__identity-title")?.text()?.trim() ?: "Untitled"
        author = doc.selectFirst("div.story__identity-meta")?.text()
            ?.split("|")?.firstOrNull()
            ?.replace("Author:", "")?.replace("by ", "")?.trim()
        thumbnail_url = doc.selectFirst("figure.story__thumbnail > a")?.attr("href")
        genre = doc.select("div.tag-group > a, section.tag-group > a")
            .joinToString { it.text().trim() }
        description = doc.selectFirst("section.story__summary")?.text()?.trim()
        status = when (doc.selectFirst("span.story__status")?.text()?.trim()?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "cancelled" -> SManga.CANCELLED
            "hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    protected open fun parseChapterList(doc: org.jsoup.nodes.Document): List<SChapter> = doc.select("li.chapter-group__list-item._publish")
        .filter { el ->
            !el.className().contains("_password") &&
                el.selectFirst("i")?.className()?.contains("fa-lock") != true
        }
        .mapNotNull { element ->
            val linkEl = element.selectFirst("a") ?: return@mapNotNull null
            val url = linkEl.attr("href")
            SChapter.create().apply {
                this.url = url.replace(baseUrl, "").trimEnd('/')
                name = linkEl.text().trim()
            }
        }.reversed()

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPathTemplate.resolve(manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga {
        val manga = SManga.create().apply { this.url = mangaPathTemplate.slug(url.encodedPath) }
        val doc = client.newCall(buildMangaDetailsRequest(manga)).execute().asJsoup()
        return parseMangaDetails(doc).apply { this.url = manga.url }
    }

    // -- Pages --

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.newCall(GET(baseUrl + chapter.url, headers)).execute()
        return listOf(Page(0, response.request.url.encodedPath))
    }

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = response.asJsoup()
        return doc.selectFirst("section#chapter-content > div")?.html() ?: ""
    }
}

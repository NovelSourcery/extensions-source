package eu.kanade.tachiyomi.multisrc.fictioneer

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response

/**
 * Base class for sites using the Fictioneer WordPress plugin.
 */
open class Fictioneer(
    override val name: String,
    override val baseUrl: String,
    override val lang: String = "en",
) : HttpSource(),
    NovelSource {

    override val isNovelSource = true

    override val supportsLatest = false
    override val client = network.cloudflareClient

    /** The browse page path (e.g. "browse", "stories", "novels", "collection/novels"). */
    protected open val browsePage: String = "stories"

    // -- Browse --

    override fun popularMangaRequest(page: Int): Request {
        val pagePath = if (page == 1) "" else "page/$page/"
        return GET("$baseUrl/$browsePage/$pagePath", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val novels = doc.select("#featured-list > li > div > div, #list-of-stories > li > div > div").mapNotNull { element ->
            val titleEl = element.selectFirst("h3 > a") ?: return@mapNotNull null
            val url = titleEl.attr("href")
            SManga.create().apply {
                title = titleEl.text().trim()
                setUrlWithoutDomain(url.trimEnd('/'))
                thumbnail_url = element.selectFirst("a.cell-img:has(img)")?.attr("href")
            }
        }
        val hasNext = doc.selectFirst(".page-numbers .next") != null
        return MangasPage(novels, hasNext)
    }

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // -- Search --

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val pagePath = if (page == 1) "" else "page/$page/"
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/$pagePath?s=$encodedQuery&post_type=fcn_story", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val novels = doc.select("#search-result-list > li > div > div").mapNotNull { element ->
            val titleEl = element.selectFirst("h3 > a") ?: return@mapNotNull null
            val url = titleEl.attr("href")
            SManga.create().apply {
                title = titleEl.text().trim()
                setUrlWithoutDomain(url.trimEnd('/'))
                thumbnail_url = element.selectFirst("a.cell-img:has(img)")?.attr("href")
            }
        }
        return MangasPage(novels, false)
    }

    // -- Details --

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
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
    }

    // -- Chapters --

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        return doc.select("li.chapter-group__list-item._publish")
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
            }
    }

    // -- Pages --

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.encodedPath
        return listOf(Page(0, url))
    }

    override fun imageUrlParse(response: Response): String = ""

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = response.asJsoup()
        return doc.selectFirst("section#chapter-content > div")?.html() ?: ""
    }
}

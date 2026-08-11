package eu.kanade.tachiyomi.novelextension.en.leafstudio

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * LeafStudio (leafstudio.site). Plain WordPress-theme HTML, no API. As of 2026-08 the site has
 * moved to a paid "emerald" unlock model - most/all chapters are `.premium_chap` (login-gated),
 * only `.free_chap` ones (if any remain) return real prose; premium chapters are still listed
 * (prefixed) so the list isn't empty, but fetching one just returns the site's own login prompt.
 */
class LeafStudio :
    HttpSource(),
    NovelSource {

    override val name = "LeafStudio"
    override val baseUrl = "https://leafstudio.site"
    override val lang = "en"
    override val supportsLatest = false
    override val isNovelSource = true

    // ======================== Browse / Search ========================

    override fun popularMangaRequest(page: Int): Request {
        val path = if (page > 1) "/novels/page/$page" else "/novels"
        return GET("$baseUrl$path", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
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

    override fun popularMangaParse(response: Response): MangasPage = parseNovelList(response.asJsoup())

    override fun latestUpdatesParse(response: Response): MangasPage = parseNovelList(response.asJsoup())

    override fun searchMangaParse(response: Response): MangasPage = parseNovelList(response.asJsoup())

    private fun parseNovelList(document: Document): MangasPage {
        val mangas = document.select("a.novel-item").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.selectFirst("p.novel-item-title")?.text()?.trim().orEmpty()
                thumbnail_url = element.selectFirst("img.novel-item-Cover")?.attr("abs:src")
            }
        }
        // The site has a single small catalog; /novels/page/N repeats page 1 (no real pagination).
        return MangasPage(mangas, false)
    }

    // ======================== Details ========================

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1.title")?.text()?.trim().orEmpty()
            thumbnail_url = document.selectFirst("img#novel_cover")?.attr("abs:src")
            description = document.select("div.desc_div > p").joinToString("\n\n") { it.text() }.ifBlank { null }
            genre = document.select("div#tags_div > a.novel_genre").joinToString(", ") { it.text().trim() }.ifBlank { null }
            status = when (document.selectFirst("a#novel_status")?.text()?.trim()) {
                "Active" -> SManga.ONGOING
                "Completed" -> SManga.COMPLETED
                "Hiatus" -> SManga.ON_HIATUS
                "Dropped", "Cancelled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ======================== Chapters ========================
    // Both free and premium(locked) chapters are listed - locked ones are prefixed so the reader
    // can see they exist, even though fetching one currently just returns a login prompt.

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("a.chap").mapNotNull { element ->
            val href = element.attr("abs:href")
            if (href.isBlank()) return@mapNotNull null
            val rawName = element.selectFirst("p")?.text()?.trim().orEmpty()
            val locked = element.hasClass("premium_chap")
            SChapter.create().apply {
                setUrlWithoutDomain(href)
                name = if (locked) "🔒 $rawName" else rawName
                chapter_number = Regex("""Chapter\s+(\d+(?:\.\d+)?)""").find(rawName)
                    ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            }
        }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    // ======================== Content ========================
    // Single text page fetched once in fetchPageText, matching the ReadNovelFull multisrc idiom.

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.encodedPath))

    override fun imageUrlParse(response: Response): String = ""

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

    override fun getFilterList(): FilterList = FilterList()
}

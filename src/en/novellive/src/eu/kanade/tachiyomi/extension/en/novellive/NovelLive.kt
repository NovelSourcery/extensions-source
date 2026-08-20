package eu.kanade.tachiyomi.novelextension.en.novellive

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.lib.chapterutils.paginatedChapterList
import keiyoushi.network.get
import keiyoushi.utils.SlugPath
import keiyoushi.utils.formattedText
import keiyoushi.utils.stripChapterNumberPrefix
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * novellive.app — FreeWebNovel/ReadNovelFull-style engine (lightnovelpub.me is a page-1-only
 * mirror that redirects deeper pages here). Browse cards are `div.li-row`; chapter list is
 * paginated at /book/<slug>/<page> (page count from #indexselect). Always fetches every index
 * page for real chapter titles instead of synthesizing urls from the latest chapter number.
 */
@Source
abstract class NovelLive : ReadNovelFull() {

    override val mangaPathTemplate = SlugPath("/book/")

    override fun buildPopularMangaRequest(page: Int): Request = GET("$baseUrl/list/most-popular-novels/$page", headers)

    override fun buildLatestUpdatesRequest(page: Int): Request = GET("$baseUrl/list/latest-novels/$page", headers)

    override fun popularMangaSelector() = "div.ul-list1 div.li-row, div.li-row"

    // Base picks the cover anchor first (matches a.cover/a[title] earlier in DOM) -> empty title.
    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst(".txt h3.tit > a, h3.tit > a") ?: return@apply
        setSlugUrl(link.attr("abs:href"))
        title = link.attr("title").ifBlank { link.text() }
        thumbnail_url = element.selectFirst(".pic img")?.let {
            it.attr("abs:data-src").ifEmpty { it.attr("abs:src") }.ifEmpty { it.attr("src") }
        }
    }

    override fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder().addQueryParameter("keyword", query).build()
        return GET(url, headers)
    }

    override fun getFilterList(data: JsonElement?) = FilterList()

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst(".m-desc h1.tit, h1.tit")?.text()
            ?: document.selectFirst("meta[property=og:novel:novel_name]")?.attr("content").orEmpty()
        thumbnail_url = document.selectFirst(".m-imgtxt .pic img, .pic img")?.let {
            it.attr("abs:data-src").ifEmpty { it.attr("abs:src") }.ifEmpty { it.attr("src") }
        }
        author = document.select(".m-imgtxt a[href*=/author/]").joinToString { it.text() }
            .ifBlank { document.selectFirst("meta[property=og:novel:author]")?.attr("content")?.trim() }
        genre = document.select(".m-imgtxt a[href*=/genres/]").joinToString { it.text() }
            .ifBlank {
                document.selectFirst("meta[property=og:novel:genre]")?.attr("content")
                    ?.split(",")?.joinToString { g -> g.trim().lowercase().replaceFirstChar(Char::uppercase) }
                    .orEmpty()
            }
        status = when (
            document.selectFirst(".m-imgtxt .item:has(.glyphicon-time) .s1, meta[property=og:novel:status]")
                ?.let { it.text().ifBlank { it.attr("content") } }?.trim()?.lowercase()
        ) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        description = document.selectFirst(".m-desc .txt .inner, .m-desc .inner")?.let { el ->
            el.select("script, style").remove()
            el.formattedText()
        }?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) mangaDetailsParse(client.get(baseUrl + mangaPathTemplate.resolve(manga.url), headers).asJsoup()) else manga
        val updatedChapters = if (fetchChapters) fetchNovelLiveChapterList(manga, chapters) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private suspend fun fetchNovelLiveChapterList(manga: SManga, existingChapters: List<SChapter>): List<SChapter> {
        val novelPath = mangaPathTemplate.resolve(manga.url).trimEnd('/')
        val detailDoc = client.get(baseUrl + novelPath, headers).asJsoup()
        val options = detailDoc.select("#indexselect option")
        val totalPages = options.size.coerceAtLeast(1)

        // Latest chapter number: prefer the last #indexselect option's upper bound
        // (e.g. "C.2321 - C.2334" -> 2334), else the newest entry in div.m-newest1.
        val latestNum = options.lastOrNull()?.text()
            ?.let { Regex("""(\d+)\D*$""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: detailDoc.selectFirst("div.m-newest1 ul.ul-list5 a[href*=/chapter-]")
                ?.attr("href")?.let { Regex("""chapter-(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: 0

        // Pages are oldest-first (#indexselect C.1-C.40, C.41-C.80, ...). Keep fetch order, then
        // number by position (href chapter numbers are unreliable) and present newest-first.
        val ascending = paginatedChapterList(
            existingChapters = existingChapters,
            siteTotal = latestNum,
            assumedPageSize = 40,
            sortChapters = { it },
            fetchPage = { page ->
                val doc = if (page == 1) {
                    detailDoc
                } else {
                    client.get("$baseUrl$novelPath/$page", headers).asJsoup()
                }
                val chapters = doc.select("ul.ul-list5 li a").mapNotNull { a ->
                    val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
                    SChapter.create().apply {
                        setUrlWithoutDomain(href)
                        name = a.attr("title").ifBlank { a.text() }.stripChapterNumberPrefix()
                    }
                }
                Pair(chapters, page < totalPages)
            },
        )
        ascending.forEachIndexed { i, ch -> ch.chapter_number = (i + 1).toFloat() }
        return ascending.reversed()
    }
}

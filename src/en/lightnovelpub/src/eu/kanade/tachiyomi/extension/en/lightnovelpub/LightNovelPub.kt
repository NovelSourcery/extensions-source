package eu.kanade.tachiyomi.novelextension.en.lightnovelpub

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.RefreshContext
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.lib.chapterutils.paginatedChapterList
import keiyoushi.utils.formattedText
import keiyoushi.utils.stripChapterNumberPrefix
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * lightnovelpub.me — FreeWebNovel/ReadNovelFull-style engine. Browse cards are `div.li-row`
 * (verified SSR for both desktop and mobile UA); full chapter list is paginated at
 * /book/<slug>/<page> (page count from #indexselect), content in div.txt.
 */
class LightNovelPub :
    HttpSource(),
    NovelSource {

    override val name = "Light Novel Pub"
    override val baseUrl = "https://lightnovelpub.me"
    override val lang = "en"
    override val supportsLatest = true
    override val isNovelSource = true
    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    private fun Response.asJsoup(): Document = Jsoup.parse(body.string(), request.url.toString())


    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/list/most-popular-novels/$page", headers)
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/list/latest-novels/$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseList(response)
    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    private fun parseList(response: Response): MangasPage {
        val doc = response.asJsoup()
        val novels = doc.select("div.ul-list1 div.li-row, div.li-row").mapNotNull { el ->
            val link = el.selectFirst(".txt h3.tit > a, h3.tit > a") ?: return@mapNotNull null
            SManga.create().apply {
                setUrlWithoutDomain(link.attr("abs:href"))
                title = link.attr("title").ifBlank { link.text().trim() }
                thumbnail_url = el.selectFirst(".pic img")?.let {
                    it.attr("abs:data-src").ifEmpty { it.attr("abs:src") }.ifEmpty { it.attr("src") }
                }
            }
        }.filter { it.url.isNotBlank() }
        val hasNext = doc.selectFirst("ul.pagination li.next:not(.disabled), ul.pagination li.active + li a") != null
        return MangasPage(novels, hasNext)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder().addQueryParameter("keyword", query).build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)


    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst(".m-desc h1.tit, h1.tit")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:novel:novel_name]")?.attr("content").orEmpty()
            thumbnail_url = doc.selectFirst(".m-imgtxt .pic img, .pic img")?.let {
                it.attr("abs:data-src").ifEmpty { it.attr("abs:src") }.ifEmpty { it.attr("src") }
            }
            author = doc.select(".m-imgtxt a[href*=/author/]").joinToString { it.text().trim() }
                .ifBlank { doc.selectFirst("meta[property=og:novel:author]")?.attr("content")?.trim() }
            genre = doc.select(".m-imgtxt a[href*=/genres/]").joinToString { it.text().trim() }
                .ifBlank {
                    doc.selectFirst("meta[property=og:novel:genre]")?.attr("content")
                        ?.split(",")?.joinToString(", ") { g -> g.trim().lowercase().replaceFirstChar(Char::uppercase) }
                        .orEmpty()
                }
            status = when (
                doc.selectFirst(".m-imgtxt .item:has(.glyphicon-time) .s1, meta[property=og:novel:status]")
                    ?.let { it.text().ifBlank { it.attr("content") } }?.trim()?.lowercase()
            ) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            description = doc.selectFirst(".m-desc .txt .inner, .m-desc .inner")?.let { el ->
                el.select("script, style").remove()
                el.formattedText()
            }?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()
        }
    }


    override suspend fun getChapterList(manga: SManga, context: RefreshContext): List<SChapter> {
        val novelPath = manga.url.trimEnd('/')
        val detailDoc = client.newCall(GET(baseUrl + novelPath, headers)).execute().asJsoup()
        val options = detailDoc.select("#indexselect option")
        val totalPages = options.size.coerceAtLeast(1)
        val siteTotal = options.lastOrNull()?.text()
            ?.let { Regex("""(\d+)\D*$""").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0

        // Pages are oldest-first (#indexselect C.1-C.40, C.41-C.80, ...). Keep fetch order, then
        // number by position (href chapter numbers are unreliable) and present newest-first.
        val ascending = paginatedChapterList(
            context = context,
            siteTotal = siteTotal,
            assumedPageSize = 40,
            sortChapters = { it },
            fetchPage = { page ->
                val doc = if (page == 1) {
                    detailDoc
                } else {
                    client.newCall(GET("$baseUrl$novelPath/$page", headers)).execute().asJsoup()
                }
                val chapters = doc.select("ul.ul-list5 li a").mapNotNull { a ->
                    val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
                    SChapter.create().apply {
                        setUrlWithoutDomain(href)
                        name = a.attr("title").ifBlank { a.text().trim() }.stripChapterNumberPrefix()
                    }
                }
                Pair(chapters, page < totalPages)
            },
        )
        ascending.forEachIndexed { i, ch -> ch.chapter_number = (i + 1).toFloat() }
        return ascending.reversed()
    }

    override fun chapterListParse(response: Response): List<SChapter> = response.asJsoup().select("ul.ul-list5 li a").map { a ->
        SChapter.create().apply {
            setUrlWithoutDomain(a.attr("abs:href"))
            name = a.attr("title").ifBlank { a.text().trim() }.stripChapterNumberPrefix()
        }
    }.reversed()


    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.encodedPath))

    override fun imageUrlParse(response: Response): String = ""

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) page.url else baseUrl + page.url
        val doc = Jsoup.parse(client.newCall(GET(url, headers)).execute().body.string(), url)
        val content = doc.selectFirst("div#chr-content, div.chapter-content, div#content, div.txt, div#article")
            ?: return ""
        content.select("script, ins, .ads, .adsbygoogle, div.unlock-buttons, div[id^=pf-]").remove()
        return content.html()
    }
}

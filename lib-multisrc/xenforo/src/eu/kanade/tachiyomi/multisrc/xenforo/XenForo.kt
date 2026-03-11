package eu.kanade.tachiyomi.multisrc.xenforo

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

abstract class XenForo(
    override val name: String,
    override val baseUrl: String,
    override val lang: String = "en",
) : HttpSource(),
    NovelSource {

    override val supportsLatest = false

    protected open val reverseChapters: Boolean = false

    override val client = network.cloudflareClient

    /**
     * List of forums to browse. Each entry is a pair of (title, forumId).
     */
    abstract val forums: List<Pair<String, Int>>

    /**
     * Regex pattern for thread URLs to blacklist from listings.
     */
    open val novelUrlBlacklist: Regex? = null

    open val orderByModes: List<Pair<String, String>> = listOf(
        "Relevance" to "relevance",
        "Date" to "date",
        "Most recent" to "last_update",
        "Most replies" to "replies",
        "Words" to "word_count",
    )

    private var searchId: String = "1"
    private var totalSearchPages: Int = 1

    // region Popular (browse forum listings)

    override fun popularMangaRequest(page: Int): Request {
        val forum = forums.first()
        return GET("$baseUrl/forums/.${forum.second}/page-$page/", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = parseForumListing(doc)
        val pageCountElem = doc.selectFirst(".pageNav-main .pageNav-page:last-of-type a")
        val pageCount = pageCountElem?.text()?.toIntOrNull() ?: 1
        val currentPage = response.request.url.toString().let { url ->
            Regex("page-(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        }
        return MangasPage(mangas, currentPage < pageCount)
    }

    private fun parseForumListing(doc: Document): List<SManga> {
        return doc.select(".js-threadList .structItem--thread").mapNotNull { el ->
            val linkEl = el.selectFirst(".structItem-title a") ?: return@mapNotNull null
            val href = linkEl.attr("href")
            val threadUrl = handleNovelUrl(href)

            novelUrlBlacklist?.let { if (it.matches(threadUrl)) return@mapNotNull null }

            SManga.create().apply {
                title = extractTitle(el.selectFirst(".structItem-title")!!)
                url = "/threads/$threadUrl"
                thumbnail_url = el.selectFirst(".structItem-cell--icon img")?.let { extractImage(it) } ?: ""
                author = el.selectFirst("a.username")?.text()
            }
        }
    }

    // endregion

    // region Latest (not supported, returns empty)

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // endregion

    // region Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (page == 1) {
            searchId = "1"
        }
        if (page > totalSearchPages) {
            return GET("$baseUrl/search/1/?q=empty&page=9999", headers)
        }

        var forumId = forums.first().second
        var order = orderByModes.first().second

        filters.forEach { filter ->
            when (filter) {
                is CategoryFilter -> {
                    if (filter.state > 0) {
                        forumId = forums[filter.state].second
                    }
                }
                is OrderByFilter -> {
                    if (filter.state > 0) {
                        order = orderByModes[filter.state].second
                    }
                }
                else -> {}
            }
        }

        val url = "$baseUrl/search/$searchId/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("q", query)
            .addQueryParameter("t", "post")
            .addQueryParameter("c[child_nodes]", "1")
            .addQueryParameter("c[nodes][0]", forumId.toString())
            .addQueryParameter("c[threadmark_categories][0]", "1")
            .addQueryParameter("c[threadmark_only]", "1")
            .addQueryParameter("c[title_only]", "1")
            .addQueryParameter("o", order)
            .addQueryParameter("g", "1")
            .build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()

        // Extract search ID from the canonical URL
        doc.selectFirst("meta[property=og:url]")?.attr("content")?.let { ogUrl ->
            Regex("search/(\\d+)/").find(ogUrl)?.groupValues?.get(1)?.let { searchId = it }
        }

        val pageNavElem = doc.selectFirst(".pageNav-main .pageNav-page:last-of-type a")
        totalSearchPages = pageNavElem?.text()?.toIntOrNull() ?: 1

        val mangas = doc.select(".block-body .contentRow").map { el ->
            val linkEl = el.selectFirst(".contentRow-title a")!!
            SManga.create().apply {
                title = extractTitle(linkEl)
                url = "/threads/${handleNovelUrl(linkEl.attr("href"))}"
                thumbnail_url = el.selectFirst(".contentRow-figure img")?.let { extractImage(it) } ?: ""
                author = el.selectFirst("a.username")?.text()
            }
        }

        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return MangasPage(mangas, currentPage < totalSearchPages)
    }

    // endregion

    // region Manga details

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}/threadmarks?per_page=200", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()

        val statusText = doc.select(".threadmarkListingHeader-stats dl.pairs")
            .firstOrNull { el ->
                val dt = el.selectFirst("dt")?.text() ?: ""
                dt == "Status" || dt == "Index progress"
            }
            ?.selectFirst("dd")?.text()

        val status = when (statusText) {
            "Ongoing", "Incomplete" -> SManga.ONGOING
            "Completed" -> SManga.COMPLETED
            "Hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        val usernameElements = doc.select(".username")
        val imgEl = doc.selectFirst(".threadmarkListingHeader-icon img")
            ?: if (usernameElements.isNotEmpty()) {
                val userId = usernameElements.first()!!.attr("data-user-id")
                try {
                    val tooltipDoc = client.newCall(
                        GET("$baseUrl/members/.$userId?tooltip=true", headers),
                    ).execute().asJsoup()
                    tooltipDoc.selectFirst(".memberTooltip-avatar img")
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

        val titleEl = doc.selectFirst(".p-title-value")
        val title = if (titleEl != null) {
            extractTitle(titleEl)
        } else {
            doc.head()?.selectFirst("meta[property=og:title]")?.attr("content") ?: ""
        }

        val descEl = doc.selectFirst(".threadmarkListingHeader-extraInfo .bbWrapper")
        val description = descEl?.text()
            ?: doc.head()?.selectFirst("meta[name=description]")?.attr("content")
            ?: ""

        val tags = doc.select(".threadmarkListingHeader-tags a").map { it.text() }

        return SManga.create().apply {
            this.title = title
            this.thumbnail_url = imgEl?.let { extractImage(it) } ?: ""
            this.description = description
            this.author = usernameElements.joinToString { it.text() }
            this.genre = tags.joinToString()
            this.status = status
        }
    }

    // endregion

    // region Chapters

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl${manga.url}/threadmarks?per_page=200", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()

        // Determine total number of pages
        val threadmarkCountEl = doc.select(".threadmarkListingHeader-stats dl.pairs")
            .firstOrNull { it.selectFirst("dt")?.text() == "Threadmarks" }
        val totalCount = threadmarkCountEl?.selectFirst("dd")?.text()
            ?.replace(",", "")?.toIntOrNull() ?: 0
        val totalPages = if (totalCount > 0) ((totalCount - 1) / 200) + 1 else 1

        val chapters = mutableListOf<SChapter>()
        chapters.addAll(parseChapters(doc, 1))

        // Fetch remaining pages
        val baseThreadUrl = response.request.url.toString().substringBefore("/threadmarks")
        for (page in 2..totalPages) {
            val pageDoc = client.newCall(
                GET("$baseThreadUrl/threadmarks?per_page=200&page=$page", headers),
            ).execute().asJsoup()
            chapters.addAll(parseChapters(pageDoc, page))
        }

        return if (reverseChapters) chapters.reversed() else chapters
    }

    private fun parseChapters(doc: Document, page: Int): List<SChapter> {
        var index = 0
        return doc.select(".structItemContainer .structItem").mapNotNull { el ->
            val linkEl = el.selectFirst(".structItem-title a") ?: return@mapNotNull null
            val timeEl = el.selectFirst("time.structItem-latestDate")
            index++
            SChapter.create().apply {
                name = linkEl.text()
                url = shrinkChapterUrl(linkEl.attr("href"))
                chapter_number = ((page - 1) * 200 + index).toFloat()
                date_upload = timeEl?.let { parseDate(it) } ?: 0L
            }
        }
    }

    private fun parseDate(timeEl: Element): Long = try {
        timeEl.attr("data-time")?.toLongOrNull()?.let { it * 1000 }
            ?: timeEl.attr("title")?.let {
                SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH).parse(it)?.time
            }
            ?: 0L
    } catch (_: Exception) {
        0L
    }

    // endregion

    // region Pages (novel content)

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.toString()
        return listOf(Page(0, url))
    }

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.newCall(GET(page.url, headers)).execute().asJsoup()
        val postId = page.url.substringAfterLast("#")
        val post = doc.selectFirst("#js-$postId") ?: doc.selectFirst(".message-body")
        val message = post?.selectFirst(".bbWrapper") ?: return ""

        // Clean up
        message.select(".bbCodeBlock-expandLink, .bbCodeBlock-shrinkLink").remove()
        message.select(".bbCodeSpoiler button").remove()
        fixImages(message)

        return message.html()
    }

    // endregion

    // region Helpers

    private fun fixImages(element: Element) {
        element.select(".bbCodeBlock-content img.lazyload:not(noscript *)").forEach { img ->
            val siblings = img.nextElementSiblings()
            if (siblings.isNotEmpty()) {
                val sibling = siblings.first()!!
                sibling.selectFirst("img")?.attr("src")?.let { img.attr("src", it) }
                sibling.remove()
            }
        }
        element.select("img").forEach { img ->
            val src = img.attr("src")
            if (src.startsWith("/")) {
                img.attr("src", baseUrl + src)
            }
        }
    }

    private fun extractImage(imgEl: Element): String {
        val src = imgEl.attr("src")
        if (src.isBlank()) return ""
        return src.replace(Regex("^/"), "$baseUrl/").replace(Regex("\\?[0-9]*$"), "")
    }

    private fun extractTitle(element: Element): String {
        element.select(".unreadLink, .labelLink, .label, .label-append").remove()
        return element.text().trim()
    }

    private fun handleNovelUrl(url: String): String = url.replace(Regex("\\?.*$"), "")
        .replace(Regex("/$"), "")
        .replace(Regex("/threadmarks$"), "")
        .replace(Regex("/unread$"), "")
        .removePrefix("threads/")
        .removePrefix("/threads/")

    private fun shrinkChapterUrl(url: String): String {
        // Keep only post reference (e.g. "posts/123456" or "post-123456")
        return url.removePrefix(baseUrl)
    }

    private fun Response.asJsoup(): Document = Jsoup.parse(body.string(), request.url.toString())

    // endregion

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // region Filters

    override fun getFilterList(): FilterList = FilterList(
        CategoryFilter(forums.map { it.first }),
        OrderByFilter(orderByModes.map { it.first }),
    )

    class CategoryFilter(categories: List<String>) :
        Filter.Select<String>(
            "Category",
            categories.toTypedArray(),
        )

    class OrderByFilter(orders: List<String>) :
        Filter.Select<String>(
            "Order by",
            orders.toTypedArray(),
        )

    // endregion
}

package eu.kanade.tachiyomi.novelextension.en.freewebnovel

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.utils.SlugPath
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * FreeWebNovel - ReadNovelFull-based novel site
 * Uses the ReadNovelFull multisrc template which handles all the parsing logic.
 */
@Source
abstract class FreeWebNovel : ReadNovelFull() {
    override val popularPage = "sort/most-popular"

    // freewebnovel uses "latest-release" for the latest-release listing
    override val latestPage = "sort/latest-release"
    override val noAjax = true
    override val pageAsPath = true

    // freewebnovel paginates the chapter list at /novel/<slug>?page=N (page 1 is the novel page);
    // page count and total come from #indexselect (options are "C.1 - C.40" ranges).
    override val chaptersPaginated = true
    override val mangaPathTemplate = SlugPath("/novel/")

    override fun chapterListPageRequest(manga: SManga, page: Int): Request {
        val base = mangaPathTemplate.absolute(baseUrl, manga.url).trimEnd('/')
        val url = if (page <= 1) base else "$base?$pageParam=$page"
        return GET(url, headers)
    }

    // Real list is #idData; m-newest1/2 are the "latest chapters" widgets and must not be mixed in.
    override fun chapterPageSelector() = "#idData li a"

    // Chapter urls follow /novel/<slug>/chapter-N, so the fast list can be synthesized.
    override fun chapterUrlFromNumber(manga: SManga, number: Int): String? {
        val path = mangaPathTemplate.resolve(manga.url).trimEnd('/')
        if (path.isBlank()) return null
        return "$path/chapter-$number"
    }

    // Keyword search ignores every other parameter, and /search-adv ignores the keyword
    override fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("keyword", query.trim())
        } else {
            "$baseUrl/search-adv".toHttpUrl().newBuilder().apply {
                addQueryParameter("apply", "1")
                filters.filterIsInstance<UrlFilter>().forEach { it.addToUrl(this) }
            }
        }
        // Page 1 must not carry the param: the site 301s it away
        if (page > 1) url.addQueryParameter(pageParam, page.toString())
        return GET(url.build(), headers)
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("Filters are ignored when searching by text"),
        SortFilter(),
        LanguageFilter(),
        GenresFilter(),
        GenreMatchFilter(),
        NovelStatusFilter(),
        ContentRatingFilter(),
        LastUpdatedFilter(),
        ChaptersFilter(),
        RatingFilter(),
    )

    // The current page is a <strong>; on the last page the link after it is a javascript: no-op
    override fun popularMangaNextPageSelector() = "div.pages .jump-pc strong + a[href^=/]"

    override fun popularMangaFromElement(element: Element): SManga = listElementToSManga(element)

    override fun latestUpdatesFromElement(element: Element): SManga = listElementToSManga(element)

    override fun searchMangaFromElement(element: Element): SManga = listElementToSManga(element)

    private fun listElementToSManga(element: Element): SManga {
        val manga = SManga.create()
        // title is a lateinit var; if both title selectors below miss, it must still be assigned
        // or any later read of it crashes.
        manga.title = ""

        // Title and url
        val titleEl = element.selectFirst("h3.tit > a") ?: element.selectFirst(".txt h3.tit a")
        if (titleEl != null) {
            val href = titleEl.attr("abs:href").ifEmpty { titleEl.attr("href") }
            if (href.isNotBlank()) {
                manga.setSlugUrl(href)
            } else {
                // Fallback: try to find href in parent or sibling elements
                element.selectFirst("a[href]")?.let {
                    manga.setSlugUrl(it.attr("abs:href").ifEmpty { it.attr("href") })
                }
            }
            val rawTitle = titleEl.attr("title").ifBlank { titleEl.text() }
            manga.title = rawTitle
                .substringBefore(" - Free Web Novel")
                .substringBefore(" - FreeWebNovel")
                .trim()
        } else {
            // Fallback: find first link with title
            element.selectFirst("a[href]")?.let { link ->
                manga.setSlugUrl(link.attr("abs:href").ifEmpty { link.attr("href") })
                manga.title = link.attr("title").ifEmpty { link.text() }
                    .substringBefore(" - Free Web Novel")
                    .substringBefore(" - FreeWebNovel")
                    .trim()
            }
        }

        // Thumbnail
        val img = element.selectFirst(".pic img") ?: element.selectFirst("img")
        img?.let {
            val src = it.attr("abs:data-src").ifEmpty { it.attr("abs:src") }.ifEmpty { it.attr("src") }
            if (src.isNotBlank()) manga.thumbnail_url = src
        }

        return manga
    }

    // The server can ship #article's paragraphs shuffled, with their real position in
    // data-reader-order-index (or a CSS `order` fallback); reader-content-order.js re-sorts them in
    // the browser. Mirror that: sort the indexed nodes back into the slots they occupy.
    override suspend fun fetchPageText(page: Page): String {
        val html = super.fetchPageText(page)
        if (!html.contains("data-reader-order") && !html.contains("order:")) return html
        val article = Jsoup.parseBodyFragment(html).body()
        article.children().filter { it.hasAttr("data-reader-order-anchor") }.forEach { it.remove() }
        val slots = article.children().mapNotNull { el -> readerOrderIndex(el)?.let { it to el } }
        val sorted = slots.sortedBy { it.first }.map { it.second }
        val markers = slots.map { (_, el) -> Comment("").also { el.before(it) } }
        slots.forEach { it.second.remove() }
        markers.zip(sorted).forEach { (marker, el) ->
            el.removeAttr("data-reader-order-index").removeAttr("style")
            marker.replaceWith(el)
        }
        return article.html()
    }

    private fun readerOrderIndex(el: Element): Int? = el.attr("data-reader-order-index").toIntOrNull()
        ?: CSS_ORDER.findAll(el.attr("style")).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = super.mangaDetailsParse(document)

        // Normalize title: remove site suffixes if present
        if (!manga.title.isNullOrBlank()) {
            manga.title = manga.title.substringBefore(" - Free Web Novel").substringBefore(" - FreeWebNovel").trim()
        }

        // Fallback status detection specific to FreeWebNovel
        if (manga.status == SManga.UNKNOWN) {
            val statusText = listOf(
                document.selectFirst(".status")?.text(),
                document.selectFirst("p:contains(Status)")?.text(),
                document.selectFirst("meta[property=\"og:novel:status\"]")?.attr("content"),
            ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: ""

            if (statusText.isNotBlank()) {
                manga.status = when {
                    statusText.contains("ongoing", ignoreCase = true) -> SManga.ONGOING
                    statusText.contains("completed", ignoreCase = true) -> SManga.COMPLETED
                    statusText.contains("hiatus", ignoreCase = true) -> SManga.ON_HIATUS
                    statusText.contains("dropped", ignoreCase = true) || statusText.contains("cancel", ignoreCase = true) -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
            }
        }

        return manga
    }

    companion object {
        private val CSS_ORDER = Regex("""(?:^|;)\s*order\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
    }
}

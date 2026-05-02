package eu.kanade.tachiyomi.extension.en.freewebnovel

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * FreeWebNovel - ReadNovelFull-based novel site
 * Uses the ReadNovelFull multisrc template which handles all the parsing logic.
 */
class FreeWebNovel :
    ReadNovelFull(
        name = "FreeWebNovel",
        baseUrl = "https://freewebnovel.com",
        lang = "en",
    ) {
    override val popularPage = "sort/most-popular"

    // freewebnovel uses "latest-release" for the latest-release listing
    override val latestPage = "sort/latest-release"
    override val searchPage = "search"
    override val searchKey = "searchkey"
    override val postSearch = true
    override val noAjax = true
    override val pageAsPath = true
    override val noPages = listOf("sort/most-popular")

    override fun getTypeOptions() = listOf(
        "Most Popular" to "sort/most-popular",
        "Latest Novels" to "sort/latest-novels",
        "Chinese Novel" to "sort/latest-novels/chinese-novel",
        "Korean Novel" to "sort/latest-novels/korean-novel",
        "Japanese Novel" to "sort/latest-novels/japanese-novel",
        "English Novel" to "sort/latest-novels/english-novel",
    )

    override fun popularMangaNextPageSelector() = "li.next:not(.disabled), ul.pagination li.active + li a, div.pages ul li a"

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun popularMangaFromElement(element: org.jsoup.nodes.Element): SManga = listElementToSManga(element)

    override fun latestUpdatesFromElement(element: Element): SManga = listElementToSManga(element)

    override fun searchMangaFromElement(element: Element): SManga = listElementToSManga(element)

    private fun listElementToSManga(element: Element): SManga {
        val manga = SManga.create()

        // Title and url
        val titleEl = element.selectFirst("h3.tit > a") ?: element.selectFirst(".txt h3.tit a")
        if (titleEl != null) {
            val href = titleEl.attr("abs:href").ifEmpty { titleEl.attr("href") }
            if (href.isNotBlank()) manga.setUrlWithoutDomain(href)
            val rawTitle = titleEl.attr("title").ifBlank { titleEl.text() }
            manga.title = rawTitle
                .substringBefore(" - Free Web Novel")
                .substringBefore(" - FreeWebNovel")
                .trim()
        }

        // Thumbnail
        val img = element.selectFirst(".pic img") ?: element.selectFirst("img")
        img?.let {
            val src = it.attr("abs:data-src").ifEmpty { it.attr("abs:src") }.ifEmpty { it.attr("src") }
            if (src.isNotBlank()) manga.thumbnail_url = src
        }

        return manga
    }

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
}

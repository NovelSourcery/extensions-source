package eu.kanade.tachiyomi.novelextension.ar.hizomanga

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class HizoManga : MadaraNovel() {
    // hizomanga.net blocks the Madara chapter endpoints at the edge (both
    // POST /content/<id>/ajax/chapters/ and the legacy
    // admin-ajax.php?action=manga_get_chapters answer 403/400), but the novel page itself
    // returns 200 and already renders the complete chapter list into
    // `.listing-chapters_wrap .wp-manga-chapter` — verified on a 239-chapter series, where
    // the `<li>` elements are contiguous 1..239 in the initial HTML with no pagination.
    // So the list is read straight off the novel page instead of the ajax endpoint.
    override val useNewChapterEndpointDefault = false

    /** "Chapter 12", "Ch. 12 - Title", "Episode 3: Title" — stripped from the displayed name. */
    private val chapterPrefixRegex =
        Regex("""^(?:chapter|chap|ch|episode|ep)\.?\s*(\d+(?:\.\d+)?)\s*[-–—:.]?\s*""", RegexOption.IGNORE_CASE)

    /** "12 - Title" — a bare number only counts when an explicit separator follows. */
    private val chapterSeparatorRegex = Regex("""^(\d+(?:\.\d+)?)\s*[-–—:]\s*""")

    override fun buildPopularMangaRequest(page: Int): Request = GET("$baseUrl/page/$page/?per_page=100&s=&post_type=wp-manga", headers)

    override fun buildLatestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/?per_page=100&s=&post_type=wp-manga&m_orderby=latest", headers)

    override fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/page/$page/?per_page=100&s=${query.replace(" ", "+")}&post_type=wp-manga", headers)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = buildPopularMangaRequest(page)
        val doc = client.get(request.url, request.headers).asJsoup()
        val mangas = parseNovels(doc)
        val hasNextPage = doc.selectFirst(".pagination a:contains(next)") != null ||
            doc.selectFirst("a.next.page-numbers") != null ||
            doc.selectFirst(".nav-previous a") != null ||
            doc.selectFirst(".wp-pagenavi a.nextpostslink") != null ||
            doc.selectFirst(".page-item.next:not(.disabled) a") != null ||
            doc.selectFirst(".navigation-ajax .load-ajax") != null
        return MangasPage(mangas, hasNextPage)
    }

    /**
     * Reads the chapter list from the novel page's own HTML.
     *
     * Madara normally asks the ajax endpoint for chapters, but every chapter endpoint on this
     * host is rejected at the CDN (403 for `ajax/chapters/`, 400 for `manga_get_chapters`), so
     * the shared implementation would return nothing. The novel page itself always carries the
     * full list, so parse it from there and keep the shared one only as a fallback.
     */
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchChapters) {
            return SMangaUpdate(
                if (fetchDetails) {
                    val doc = client.newCall(buildMangaDetailsRequest(manga)).execute().asJsoup()
                    mangaDetailsFromPage(doc, manga)
                } else {
                    manga
                },
                chapters,
            )
        }

        val doc = client.newCall(buildChapterListRequest(manga)).execute().asJsoup()
        val updatedManga = if (fetchDetails) mangaDetailsFromPage(doc, manga) else manga
        val parsed = chaptersFromPage(doc)
        return SMangaUpdate(
            updatedManga,
            if (reverseChapterList) parsed else parsed.reversed(),
        )
    }

    /** Mirrors the theme's detail parsing, which is private and so not callable from here. */
    private fun mangaDetailsFromPage(doc: Document, manga: SManga): SManga {
        val details = SManga.create().apply {
            title = doc.selectFirst(".post-title h1, #manga-title h1")?.text() ?: manga.title
            thumbnail_url = doc.selectFirst(".summary_image img")?.let { img ->
                img.attr("data-lazy-src").ifEmpty { null }
                    ?: img.attr("data-src").ifEmpty { null }
                    ?: img.attr("src").ifEmpty { null }
            } ?: manga.thumbnail_url
            author = doc.selectFirst(".manga-authors")?.text() ?: manga.author
            description = doc.selectFirst("div.summary__content")?.text()
                ?: doc.selectFirst("#tab-manga-about")?.text()
                ?: doc.selectFirst(".manga-excerpt")?.text()
                ?: manga.description
            genre = manga.genre
            status = if (
                doc.select(".post-content_item, .post-content")
                    .find { it.selectFirst("h5")?.text() == "Status" }
                    ?.selectFirst(".summary-content")?.text()
                    ?.contains("Ongoing", ignoreCase = true) == true
            ) {
                SManga.ONGOING
            } else {
                SManga.COMPLETED
            }
        }
        return details.apply { url = manga.url }
    }

    /** Parses `.wp-manga-chapter` entries exactly as the theme does, but from page HTML. */
    private fun chaptersFromPage(doc: Document): List<SChapter> {
        val total = doc.select(".wp-manga-chapter").size
        val result = mutableListOf<SChapter>()

        doc.select(".wp-manga-chapter").forEachIndexed { index, element ->
            try {
                val anchor = element.selectFirst("a") ?: return@forEachIndexed
                val rawName = anchor.text()
                if (rawName.isBlank()) return@forEachIndexed

                val chapterUrl = anchor.attr("href")
                if (chapterUrl.isBlank() || chapterUrl == "#") return@forEachIndexed

                val number = chapterPrefixRegex.find(rawName)
                    ?: chapterSeparatorRegex.find(rawName)
                val parsedNumber = number?.groupValues?.get(1)?.toFloatOrNull()
                val name = number?.let { rawName.removeRange(it.range).trim() }?.ifEmpty { null } ?: rawName

                result.add(
                    SChapter.create().apply {
                        url = chapterUrl.removePrefix(baseUrl)
                        this.name = if (element.className().contains("premium-block")) "🔒 $name" else name
                        date_upload = parseDate(element.selectFirst(".chapter-release-date")?.text().orEmpty())
                        chapter_number = parsedNumber ?: (total - index).toFloat()
                    },
                )
            } catch (_: Exception) {
                // Skip malformed entries rather than failing the whole list.
            }
        }
        return result
    }

    override suspend fun fetchPageText(page: Page): String {
        // page.url is already an absolute URL (see MadaraNovel.getPageList) - baseUrl must not be
        // prepended again here, or the request resolves against a bogus "site+https" host.
        val response = client.get(page.url, headers)
        val doc = response.asJsoup()

        checkCaptcha(doc, page.url)

        doc.select(
            "div.ads, div.unlock-buttons, sub, script, ins, .adsbygoogle, .code-block, noscript, " +
                "div[id*=google], div[id*=bidgear], div[class*=bidgear], div[class*=google-tag], " +
                "iframe, .foxaholic-google-tag-manager-body, .foxaholic-bidgear-before-content-1x1, " +
                ".foxaholic-bidgear-banner-before-content, div[id^=bg-ssp], " +
                ".adx-zone, .adx-head, [id*='-ad-'], [class*='-ad-'], .ad-container, " +
                ".comments, .comment-list, #comments, .comment-respond, " +
                ".social-sharing, .share-buttons, .post-share, " +
                ".wpdiscuz, .discussion, .comment-form",
        ).remove()

        val candidates = listOf(
            doc.selectFirst(".text-left"),
            doc.selectFirst(".text-right"),
            doc.selectFirst(".reading-content .text-left"),
            doc.selectFirst(".reading-content .text-right"),
            doc.selectFirst(".entry-content"),
            doc.selectFirst(".c-blog-post > div > div:nth-child(2)"),
            doc.selectFirst(".reading-content"),
            doc.selectFirst(".chapter-content"),
        ).filterNotNull()

        var contentElement: Element? = null
        var maxParagraphText = -1
        for (element in candidates) {
            val paragraphTextLength = element.select("p").sumOf { it.text().length }
            if (paragraphTextLength > maxParagraphText) {
                maxParagraphText = paragraphTextLength
                contentElement = element
            }
        }

        var content = contentElement?.html() ?: ""

        content = content.replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
        content = content.replace(Regex("""\(adsbygoogle[^)]*\)[^;]*;?"""), "")
        content = content.replace(Regex("""<div[^>]*>\s*</div>"""), "")

        content = removeTrailingNonChapterContent(content)

        return content.trim()
    }

    private fun removeTrailingNonChapterContent(html: String): String {
        val markers = listOf(
            "*******",
            "المترجمة",
            "سبحان الله",
            "تطور سلاح",
            "الفصل يضحك",
        )
        var cutoff = html.length
        for (marker in markers) {
            val idx = html.lastIndexOf(marker)
            if (idx != -1 && idx < cutoff) {
                cutoff = idx
            }
        }
        return if (cutoff < html.length) {
            html.substring(0, cutoff).trim()
        } else {
            html
        }
    }
}

package eu.kanade.tachiyomi.novelextension.ar.hizomanga

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import keiyoushi.annotation.Source
import keiyoushi.network.get
import okhttp3.Request
import org.jsoup.nodes.Element

@Source
abstract class HizoManga : MadaraNovel() {
    override val useNewChapterEndpointDefault = true

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

package eu.kanade.tachiyomi.novelextension.ar.riwyat

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.get
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

@Source
abstract class Riwyat : MadaraNovel() {
    override val useNewChapterEndpointDefault = true

    /**
     * cenele.com sits behind Cloudflare with two custom WAF rules that both answer 403 on chapter
     * reads:
     *
     * 1. Any request whose User-Agent is not a desktop browser string is blocked outright
     *    (`okhttp/4.12.0` and `Dart/3.10` both 403, any real Chrome UA 200).
     * 2. A *mobile* Chrome User-Agent combined with an exact self-referer (`Referer: https://cenele.com/`)
     *    is blocked. A desktop User-Agent with the same referer passes.
     *
     * `KeiSource` adds `Referer: $baseUrl/` to every request by default, so sending a mobile UA
     * trips both rules at once and every chapter fetch fails. Sending a desktop UA *and* dropping
     * the referer avoids both.
     */
    private val desktopUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    private val desktopClientHintsInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            // KeiSource's default self-referer is what rule 2 matches on.
            .removeHeader("Referer")
            .removeHeader("Sec-Fetch-Dest")
            .removeHeader("Sec-Fetch-Mode")
            .removeHeader("Sec-Fetch-Site")
            .removeHeader("Sec-Fetch-User")
            .header("User-Agent", desktopUserAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            .header("sec-ch-ua", "\"Chromium\";v=\"140\", \"Not=A?Brand\";v=\"24\", \"Google Chrome\";v=\"140\"")
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")
            .header("Upgrade-Insecure-Requests", "1")
            .build()
        chain.proceed(request)
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(desktopClientHintsInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = removeAll("Referer")
        .set("User-Agent", desktopUserAgent)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .set("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
        .set("sec-ch-ua", "\"Chromium\";v=\"140\", \"Not=A?Brand\";v=\"24\", \"Google Chrome\";v=\"140\"")
        .set("sec-ch-ua-mobile", "?0")
        .set("sec-ch-ua-platform", "\"Windows\"")
        .set("Upgrade-Insecure-Requests", "1")

    /**
     * `headersBuilder()` always sets `Referer: $baseUrl/` before [configureHeaders] runs, so these
     * headers have to be rebuilt here to be sure the self-referer Cloudflare rejects is gone.
     */
    private fun browserHeaders(): Headers = headersBuilder().build()

    override fun buildPopularMangaRequest(page: Int): Request = GET("$baseUrl/page/$page/?s=&post_type=wp-manga", browserHeaders())

    override fun buildLatestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/?s=&post_type=wp-manga&m_orderby=latest", browserHeaders())

    override fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request = super.buildSearchMangaRequest(page, query, filters).newBuilder()
        .headers(browserHeaders())
        .build()

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = buildPopularMangaRequest(page)
        return parseCeneleNovels(client.get(request.url, request.headers).asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = buildLatestUpdatesRequest(page)
        return parseCeneleNovels(client.get(request.url, request.headers).asJsoup())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val request = buildSearchMangaRequest(page, query, filters)
        return parseCeneleNovels(client.get(request.url, request.headers).asJsoup())
    }

    override fun buildChapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, browserHeaders())

    override fun buildMangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, browserHeaders())

    private fun parseCeneleNovels(doc: Document): MangasPage {
        val novels = doc.select(
            ".c-tabs-item__content, .page-item-detail, .c-blog-listing .row",
        ).mapNotNull { el ->
            try {
                val titleEl = el.selectFirst(".post-title h3 a, .post-title h5 a, .post-title a")
                    ?: return@mapNotNull null
                val title = titleEl.text()
                val url = titleEl.attr("href")
                if (title.isEmpty() || url.isEmpty()) return@mapNotNull null
                val thumbnail = el.selectFirst("img")?.let { img ->
                    img.attr("data-lazy-src").ifEmpty { null }
                        ?: img.attr("data-src").ifEmpty { null }
                        ?: img.attr("src").ifEmpty { null }
                }
                SManga.create().apply {
                    this.url = url.removePrefix(baseUrl)
                    this.title = title
                    thumbnail_url = thumbnail
                }
            } catch (_: Exception) {
                null
            }
        }

        val hasNextPage = doc.selectFirst(
            ".wp-pagenavi .nextpostslink, a.next.page-numbers, .nav-previous a, .page-item.next:not(.disabled) a",
        ) != null
        return MangasPage(novels, hasNextPage)
    }

    override fun extractChapterCount(doc: Document): Int {
        val arabicChapterLabels = listOf("الفصول", "عدد الفصول", "الفصل", "chapters", "chapter")
        val labeled = doc.select(".post-content_item")
            .find { item ->
                val h5 = item.selectFirst("h5")?.text()?.lowercase() ?: return@find false
                arabicChapterLabels.any { h5.contains(it, ignoreCase = true) }
            }
            ?.selectFirst(".summary-content")
            ?.text()?.toIntOrNull()
        if (labeled != null && labeled > 0) return labeled

        return doc.select(".post-content_item .summary-content")
            .mapNotNull { it.text().toIntOrNull() }
            .firstOrNull { it > 0 } ?: 0
    }

    override suspend fun fetchPageText(page: Page): String {
        val pageUrl = if (page.url.startsWith("http")) page.url else baseUrl + page.url
        // Use browser headers (no Referer) so Cloudflare WAF accepts chapter fetches.
        val response = client.get(pageUrl, browserHeaders())
        val doc = response.asJsoup()

        val contentElement = doc.selectFirst(".reading-content.current .text-left")
            ?: doc.selectFirst(".reading-content .text-left")
            ?: doc.selectFirst(".reading-content.current")
            ?: doc.selectFirst(".reading-content")
            ?: doc.selectFirst(".text-left")
            ?: doc.selectFirst(".entry-content")
            ?: return ""

        contentElement.select("script, style, noscript, iframe").remove()

        contentElement.select(
            "[style*=display: none], [style*=display:none], " +
                "[style*=visibility: hidden], [style*=visibility:hidden], " +
                "[style*=height: 0], [style*=height:0], " +
                "[style*=font-size: 0], [style*=font-size:0], " +
                "[style*=z-index: -1], [style*=z-index:-1], " +
                "[style*=opacity: 0], [style*=opacity:0]",
        ).remove()

        contentElement.select(
            "nav, .chapter-nav, .prev-next, .navigation, .breadcrumb, " +
                ".wp-manga-chapter-nav, .reading-nav, .nav-links, " +
                ".nav-previous, .nav-next, .adjacent-post, " +
                "button, .btn, .button, input[type=button], input[type=submit], " +
                "form, .wp-block-button, .wp-block-buttons, " +
                ".adsbygoogle, .code-block, .ad-container, " +
                "[id*=google], [id*=bidgear], [class*=bidgear], " +
                ".adx-zone, .adx-head, [class*=google-tag], " +
                ".wp-manga-comment, .comments-area, .comment-list, " +
                ".share-buttons, .social-share, .related-posts, " +
                ".post-meta, .entry-meta, .post-title, .chapter-title, " +
                // Site-specific chrome: chapter meta header, app promos and the reading gate.
                ".nhv-reading-chapter-head, .nhv-reading-meta-strip, " +
                ".nhv-reading-meta-card, .nhv-reading-progress-track, " +
                ".nhv-reader-promo, .nhv-ts-gate, .nhv-ts-form, " +
                ".nhv-embed, .nhv-ads, .c-tabs-item__content",
        ).remove()

        contentElement.select("[class]").forEach { el ->
            val cls = el.className()
            if (cls.matches(Regex("^[a-z0-9]{10,}$"))) {
                el.remove()
            }
        }

        contentElement.select("[id]").forEach { el ->
            val id = el.id()
            if (id.matches(Regex("^[a-z0-9]{10,}$"))) {
                el.remove()
            }
        }

        contentElement.select("div").forEach { div ->
            val text = div.text()
            val hasSpam = (
                text.contains("نص تمويهي", ignoreCase = true) ||
                    text.contains("فضاء الروايات", ignoreCase = true)
                ) &&
                text.length < 300
            val hasNav = (
                (
                    text.contains("تحميل PDF", ignoreCase = true) ||
                        text.contains("PDF متوفر", ignoreCase = true) ||
                        text.contains("اشترك في عضوية", ignoreCase = true) ||
                        text.contains("بدون إعلانات", ignoreCase = true)
                    ) &&
                    text.length < 200
                )
            if (hasSpam || hasNav) {
                div.remove()
            }
        }

        contentElement.select("p:empty, div:empty, span:empty").remove()

        val html = contentElement.html().trim()
        return html
    }
}

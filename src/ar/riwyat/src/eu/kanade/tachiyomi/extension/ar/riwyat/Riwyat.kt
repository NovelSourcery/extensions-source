package eu.kanade.tachiyomi.novelextension.ar.riwyat

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

@Source
abstract class Riwyat : MadaraNovel() {
    override val useNewChapterEndpointDefault = true

    private val mobileUserAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"

    private val mobileHeadersInterceptor = Interceptor { chain ->
        val request = chain.request().newBuilder()
            .header("User-Agent", mobileUserAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-User", "?1")
            .header("Upgrade-Insecure-Requests", "1")
            .build()
        chain.proceed(request)
    }

    // Applies to every request made through [client] (popular/latest/search listings, manga
    // details, chapter list and chapter text alike), so it replaces the old per-endpoint
    // 403/503 + "Just a moment..."/"Attention Required!" checks that used to live in the
    // now-absorbed popularMangaParse/latestUpdatesParse/searchMangaParse/mangaDetailsParse
    // overrides.
    private val cloudflareInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val message = "Cloudflare Turnstile — افتح الصفحة في WebView لتجاوز التحدي."
        if (response.code == 403 || response.code == 503) {
            response.close()
            throw Exception(message)
        }
        val title = try {
            Jsoup.parse(response.peekBody(8192L).string()).title().trim()
        } catch (e: Exception) {
            ""
        }
        if (title == "Just a moment..." || title == "Attention Required!") {
            response.close()
            throw Exception(message)
        }
        response
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor(mobileHeadersInterceptor)
        .addInterceptor(cloudflareInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun mobileHeaders(): Headers = headers.newBuilder()
        .set("User-Agent", mobileUserAgent)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("Accept-Language", "ar,en;q=0.9")
        .build()

    override fun buildPopularMangaRequest(page: Int): Request = GET("$baseUrl/page/$page/?s=&post_type=wp-manga", mobileHeaders())

    override fun buildLatestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/?s=&post_type=wp-manga&m_orderby=latest", mobileHeaders())

    override fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request = super.buildSearchMangaRequest(page, query, filters).newBuilder()
        .headers(mobileHeaders())
        .build()

    override suspend fun getPopularManga(page: Int): MangasPage = parseCeneleNovels(client.newCall(buildPopularMangaRequest(page)).execute().asJsoup())

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseCeneleNovels(client.newCall(buildLatestUpdatesRequest(page)).execute().asJsoup())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = parseCeneleNovels(client.newCall(buildSearchMangaRequest(page, query, filters)).execute().asJsoup())

    override fun buildChapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, mobileHeaders())

    override fun buildMangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, mobileHeaders())

    private fun parseCeneleNovels(doc: Document): MangasPage {
        val novels = doc.select(
            ".c-tabs-item__content, .page-item-detail, .c-blog-listing .row",
        ).mapNotNull { el ->
            try {
                val titleEl = el.selectFirst(".post-title h3 a, .post-title h5 a, .post-title a")
                    ?: return@mapNotNull null
                val title = titleEl.text().trim()
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
                val h5 = item.selectFirst("h5")?.text()?.trim()?.lowercase() ?: return@find false
                arabicChapterLabels.any { h5.contains(it, ignoreCase = true) }
            }
            ?.selectFirst(".summary-content")
            ?.text()?.trim()?.toIntOrNull()
        if (labeled != null && labeled > 0) return labeled

        return doc.select(".post-content_item .summary-content")
            .mapNotNull { it.text().trim().toIntOrNull() }
            .firstOrNull { it > 0 } ?: 0
    }

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
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
                ".post-meta, .entry-meta, .post-title, .chapter-title",
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
            val text = div.text().trim()
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

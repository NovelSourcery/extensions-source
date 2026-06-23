package eu.kanade.tachiyomi.novelextension.ar.riwyat

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import eu.kanade.tachiyomi.multisrc.madaranovel.formattedDescription
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

class Riwyat :
    MadaraNovel(
        baseUrl = "https://cenele.com",
        name = "Riwyat",
        lang = "ar",
    ) {
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

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor(mobileHeadersInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun mobileHeaders(): Headers = headers.newBuilder()
        .set("User-Agent", mobileUserAgent)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("Accept-Language", "ar,en;q=0.9")
        .build()

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/page/$page/?s=&post_type=wp-manga", mobileHeaders())

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/?s=&post_type=wp-manga&m_orderby=latest", mobileHeaders())

    override fun popularMangaParse(response: Response): MangasPage {
        if (response.code == 403 || response.code == 503) {
            throw Exception("Cloudflare Turnstile — افتح المصدر في WebView لتجاوز التحدي.")
        }
        val doc = response.asJsoup()
        if (doc.title().trim() == "Just a moment...") {
            throw Exception("Cloudflare Turnstile — افتح المصدر في WebView لتجاوز التحدي.")
        }
        return parseCeneleNovels(doc)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        if (response.code == 403 || response.code == 503) {
            throw Exception("Cloudflare Turnstile — افتح المصدر في WebView لتجاوز التحدي.")
        }
        val doc = response.asJsoup()
        if (doc.title().trim() == "Just a moment...") {
            throw Exception("Cloudflare Turnstile — افتح المصدر في WebView لتجاوز التحدي.")
        }
        return parseCeneleNovels(doc)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        if (response.code == 403 || response.code == 503) {
            throw Exception("Cloudflare Turnstile — افتح المصدر في WebView لتجاوز التحدي.")
        }
        val doc = response.asJsoup()
        if (doc.title().trim() == "Just a moment...") {
            throw Exception("Cloudflare Turnstile — افتح المصدر في WebView لتجاوز التحدي.")
        }
        return parseCeneleNovels(doc)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, mobileHeaders())

    override fun mangaDetailsParse(response: Response): SManga {
        if (response.code == 403 || response.code == 503) {
            throw Exception("Cloudflare Turnstile — افتح صفحة الرواية في WebView لتجاوز التحدي.")
        }
        val doc = response.asJsoup()
        val title = doc.title().trim()
        if (title == "Just a moment..." || title == "Attention Required!") {
            throw Exception("Cloudflare Turnstile — افتح صفحة الرواية في WebView لتجاوز التحدي.")
        }

        doc.select(".manga-title-badges, #manga-title span").remove()
        extractPostId(doc)?.let { cachePostId(response.request.url.encodedPath, it) }

        return SManga.create().apply {
            this.title = doc.selectFirst(".post-title h1, #manga-title h1")?.text()?.trim() ?: ""
            val summaryImage = doc.selectFirst(".summary_image img")
            thumbnail_url = if (summaryImage != null) {
                summaryImage.attr("data-lazy-src").ifEmpty { null }
                    ?: summaryImage.attr("data-src").ifEmpty { null }
                    ?: summaryImage.attr("src").ifEmpty { null }
            } else {
                null
            }
            description = doc.selectFirst("div.summary__content")?.formattedDescription()
                ?: doc.selectFirst("#tab-manga-about")?.formattedDescription()
                ?: doc.selectFirst(".manga-excerpt")?.formattedDescription()
                ?: ""
            author = doc.selectFirst(".manga-authors")?.text()?.trim()
                ?: doc.select(".post-content_item, .post-content")
                    .find { it.selectFirst("h5")?.text() == "Author" }
                    ?.selectFirst(".summary-content")?.text()?.trim()
                ?: ""
            genre = doc.select(".post-content_item, .post-content")
                .filter { element ->
                    val h5Text = element.selectFirst("h5")?.text()?.trim()?.lowercase() ?: ""
                    h5Text.contains("genre") ||
                        h5Text.contains("tag") ||
                        h5Text.contains("género") ||
                        h5Text.contains("التصنيفات")
                }
                .mapNotNull { it.selectFirst(".summary-content")?.select("a") }
                .flatten()
                .map { it.text().trim() }
                .joinToString(", ")
            status = if (doc.select(".post-content_item, .post-content")
                    .find { it.selectFirst("h5")?.text() == "Status" }
                    ?.selectFirst(".summary-content")?.text()?.contains("Ongoing", ignoreCase = true) == true
            ) {
                SManga.ONGOING
            } else {
                SManga.COMPLETED
            }
        }
    }

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

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()

        if (response.code == 403 || response.code == 503) {
            response.close()
            throw Exception("Cloudflare Turnstile — افتح الفصل في WebView لتجاوز التحدي.")
        }

        val doc = response.asJsoup()

        val title = doc.title().trim()
        if (title == "Just a moment..." || title == "Attention Required!") {
            throw Exception("Cloudflare Turnstile — افتح الفصل في WebView لتجاوز التحدي.")
        }

        doc.select(
            "span[style*=opacity:\\ 0][style*=position:\\ fixed], " +
                "span[style*=opacity:0][style*=position:fixed]",
        ).remove()

        doc.select(
            "[style*=display:\\ none], [style*=display:none], " +
                "[style*=visibility:\\ hidden], [style*=visibility:hidden], " +
                "[style*=height:\\ 0], [style*=height:0], " +
                "[style*=font-size:\\ 0], [style*=font-size:0], " +
                "[style*=z-index:\\ -1], [style*=z-index:-1]",
        ).remove()

        doc.select(
            ".ghost, .hidden, .invisible, .sr-only, .offscreen, .d-none, .cloned, .propagated",
        ).remove()

        doc.select(
            "div.ads, div.unlock-buttons, sub, script, ins, .adsbygoogle, " +
                ".code-block, noscript, iframe, [id*='-ad-'], [class*='-ad-'], .ad-container",
        ).remove()

        doc.select(
            "nav, .chapter-nav, .prev-next, .navigation, .breadcrumb, " +
                ".wp-manga-chapter-nav, .reading-nav, .nav-links, " +
                ".nav-previous, .nav-next, .adjacent-post, " +
                "button, .btn, .button, input[type=button], input[type=submit], " +
                "form, .wp-block-button, .wp-block-buttons",
        ).remove()

        doc.select(
            "[class*=chapter-nav], [class*=prev-next], [class*=pagination], " +
                "[class*=report], [class*=modal], [class*=dialog], [class*=popup], " +
                "[class*=share], [class*=social], [class*=breadcrumb], " +
                "[class*=comment], [class*=footer], [class*=header-widget]",
        ).remove()

        val spamKeywords = listOf(
            "نادي الروايات", "تابعونا", "تابعونا على", "قناتنا على",
            "discord", "تيليجرام", "tg.me", "t.me", "شروحات", "أنشر روايتك",
            "نص تمويهي", "فضاء الروايات", "تطبيق سارق", "cenele.com",
        )

        val navigationTexts = listOf(
            "السابق", "التالي", "صفحة الرواية", "تحميل PDF", "PDF متوفر",
            "إبلاغ", "Report a problem", "Send", "Close",
            "اشترك في عضوية VIP", "بدون إعلانات", "نهاية الفصل",
            "الفصل التالي", "الفصل السابق", "التنقل بين الفصول",
            "Share", "مشاركة", "Print", "طباعة",
        )

        doc.select("p, span, div, a, li, h1, h2, h3, h4, h5, h6").forEach { el ->
            val text = el.text().trim()
            if (text.isEmpty()) return@forEach
            val isSpam = spamKeywords.any { text.contains(it, ignoreCase = true) }
            val isNavigation = navigationTexts.any { text.contains(it, ignoreCase = true) }
            if ((isSpam || isNavigation) && text.length < 300) {
                el.remove()
            } else if (el.tagName() == "a" && text.length < 100) {
                el.remove()
            }
        }

        doc.select("p:empty, div:empty, span:empty").remove()

        doc.select("p, span, div").forEach { el ->
            val text = el.text()
            if (text.length in 1..100) {
                val arabicCount = text.count { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\uFB50'..'\uFDFF' || it in '\uFE70'..'\uFEFF' }
                if (arabicCount.toDouble() / text.length < 0.3) {
                    el.remove()
                }
            }
        }

        val contentElement = listOf(
            doc.selectFirst(".text-left"),
            doc.selectFirst(".text-right"),
            doc.selectFirst(".reading-content .text-left"),
            doc.selectFirst(".reading-content .text-right"),
            doc.selectFirst(".entry-content"),
            doc.selectFirst(".reading-content"),
        ).filterNotNull().maxByOrNull { element ->
            element.select("p").sumOf { it.text().length }
        }

        val html = contentElement?.html()?.trim() ?: ""
        return stripNavigationLines(html)
    }

    private fun stripNavigationLines(html: String): String {
        val navPatterns = listOf(
            "الفصل السابق", "الفصل التالي", "صفحة الرواية", "تحميل PDF",
            "PDF متوفر", "نهاية الفصل", "تنقل بين الفصول", "التالي", "السابق",
            "اشترك في عضوية", "بدون إعلانات", "Rating", "Report", "إبلاغ",
            "نص تمويهي", "فضاء الروايات", "تطبيق سارق", "cenele.com",
        )
        val pipeNavRegex = Regex("""الفصل\s+السابق\s*\|.*الفصل\s+التالي|الفصل\s+التالي\s*\|.*الفصل\s+السابق""")
        val ratingRegex = Regex("""[*★☆rating]{0,5}\s*[+]\s*\d+\s*[-]\s*\d+""")
        val downloadPdfRegex = Regex("""تحميل\s+PDF\s+متوفر\s+الآن""")

        return html.split("\n").filter { line ->
            val text = line.trim().replace(Regex("""<[^>]*>"""), "").trim()
            if (text.isEmpty()) return@filter false
            if (navPatterns.any { text.contains(it) }) return@filter false
            if (pipeNavRegex.containsMatchIn(text)) return@filter false
            if (ratingRegex.containsMatchIn(text)) return@filter false
            if (downloadPdfRegex.containsMatchIn(text)) return@filter false
            true
        }.joinToString("\n").trim()
    }
}

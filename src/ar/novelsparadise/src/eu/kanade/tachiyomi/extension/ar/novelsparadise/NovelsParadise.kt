package eu.kanade.tachiyomi.novelextension.ar.novelsparadise

import android.util.Log
import android.webkit.CookieManager
import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.utils.WebViewSession
import keiyoushi.utils.WebViewTimeoutException
import keiyoushi.utils.applicationContext
import keiyoushi.utils.formattedText
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebViewBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * NovelsParadise (novelsparadise.site).
 *
 * The site is a custom PHP app sitting behind a Cloudflare managed challenge on every path, with a
 * markup shape that differs from the LightNovelWP sites [LightNovelWPNovel] was written for, so the
 * list/detail/chapter parsers are overridden here:
 *
 * - Browsing lives at `/np-light/series-list/`; a novel's detail page is `/np-light/series/<slug>/`.
 * - List cards are `article.series-card` > `a.series-card-main` > `img[alt]` (there is no `a[title]`,
 *   which is what the base parser looks for).
 */
@Source
abstract class NovelsParadise : LightNovelWPNovel() {
    override val reverseChapters = true

    /** Novel detail pages live at `/np-light/series/<slug>`; the slug is stored bare. */
    override val seriesPath: String = "np-light/series"

    /** The browsing/search list page is a different path than the novel detail pages. */
    private val listPath = "np-light/series-list"

    private val webViewSession = WebViewSession()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder {
        addInterceptor(CloudflareChallengeInterceptor(webViewSession, baseUrl))
        return this
    }

    /**
     * The detail page renders cover + chapter list via client-side JS after initial load, so a plain
     * HTTP fetch misses both. Load the URL in a WebView (shared cookie jar → cf_clearance already
     * solved applies), wait for the rendered DOM, and return it for the standard parsers.
     */
    private fun fetchRenderedDom(url: String): Document? {
        return try {
            var html: String? = null
            runWebViewBlocking<Unit>(call = client.newCall(GET(url, headers)), session = webViewSession, timeout = 45.seconds) {
                useOkHttpNetwork = true
                var done = false
                var grabCount = 0

                // Debug: log every AJAX/XHR the detail page fires so we can see where real
                // chapters load from (they are NOT in the initial HTML).
                interceptRequest { req ->
                    val u = req.url.toString()
                    if (u.contains("chapter", true) || u.contains("ajax", true) || u.contains("api/", true) || u.contains("novel", true) || u.contains("content", true)) {
                        Log.i(TAG, "wv-req: ${req.method} $u")
                    }
                    null
                }

                fun grab() {
                    if (done) return
                    grabCount++
                    evaluateJs(
                        """
                        JSON.stringify({
                            title: document.title,
                            chapterLinks: document.querySelectorAll('a[href*="/chapter-"]').length,
                            chapterContainer: (document.querySelector('.chapter-list, .eplister, .listing-chapters_wrap, #chapterlist, .chapterlist')) ? 'yes' : 'none',
                            totalLinks: document.querySelectorAll('a[href]').length,
                            outerLen: document.documentElement.outerHTML.length
                        })
                    """,
                    ) { json ->
                        if (done) return@evaluateJs
                        // evaluateJs returns a JSON-encoded string; unwrap it if needed.
                        val unwrapped = when {
                            json.isNullOrBlank() || json.isEmpty() || json == "null" -> return@evaluateJs
                            json.startsWith("\"") -> runCatching { json.parseAs<String>() }.getOrNull() ?: return@evaluateJs
                            else -> json
                        }
                        val obj = runCatching { unwrapped.parseAs<kotlinx.serialization.json.JsonObject>() }.getOrNull()
                        if (obj == null) {
                            Log.i(TAG, "wv-grab #$grabCount: parse-failed final=$unwrapped")
                            return@evaluateJs
                        }
                        val chapterCount = obj["chapterLinks"]?.jsonPrimitive?.intOrNull ?: 0
                        val container = obj["chapterContainer"]?.jsonPrimitive?.content ?: "none"
                        Log.i(TAG, "wv-grab #$grabCount: chapterLinks=$chapterCount container=$container")
                        // Wait until a real chapter list appears (≥3 anchors = not just the next/prev nav links)
                        // OR we've polled long enough to give up.
                        if (chapterCount >= 3 || grabCount >= 15) {
                            done = true
                            evaluateJs("document.documentElement.outerHTML") { hJson ->
                                val h = runCatching { hJson.parseAs<String>() }.getOrNull()
                                html = h
                                if (h != null) {
                                    // Dump the full rendered DOM to a file we can inspect later, AND
                                    // log its chapter-list structure directly to logcat.
                                    runCatching {
                                        java.io.File(java.io.File(applicationContext.filesDir, "npdump"), "detail.html")
                                            .apply { parentFile?.mkdirs() }
                                            .writeText(h)
                                        Log.i(TAG, "saved DOM to files/npdump/detail.html (${h.length} chars)")
                                    }
                                    runCatching { logDomStructure(h) }
                                }
                                resolve(Unit)
                            }
                        }
                    }
                }

                onPageFinished { grab() }
                poll(interval = 2.seconds) { grab() }
                loadUrl(url)
            }
            html?.let { Jsoup.parse(it) }
        } catch (e: Exception) {
            Log.w(TAG, "WebView render of $url failed: ${e.message}")
            null
        }
    }

    // ---- list pages -------------------------------------------------------

    override fun buildPopularMangaRequest(page: Int): Request = GET("$baseUrl/$listPath/?page=$page", headers)

    override fun buildLatestUpdatesRequest(page: Int): Request = GET("$baseUrl/np-light/?page=$page", headers)

    override fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // The site's real search endpoint is /np-light/search/?q=<query>, not the series-list page.
        if (query.isNotBlank()) {
            val q = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            return GET("$baseUrl/np-light/search/?q=$q", headers)
        }
        return GET("$baseUrl/$listPath/?page=$page", headers)
    }

    override fun parseMangaListResponse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())

        // The site uses two card families: the series-list page emits `article.series-card`, while the
        // homepage (latest tab) uses `article.web-poster-card`/`web-compact-card`. Match any.
        val cardSel = "article.series-card, article.web-poster-card, article.web-compact-card"
        val firstCard = doc.selectFirst(cardSel)
        if (firstCard != null) {
            // Debug: capture one card's structure to confirm title/cover selectors.
            val img = firstCard.selectFirst("img")
            Log.i(
                TAG,
                "list: sample card = ${firstCard.tagName()}.${firstCard.className().take(30)} " +
                    "h=${firstCard.selectFirst("h2,h3,.web-poster-title,.web-compact-title")?.text()?.take(30)} " +
                    "imgAlt=${img?.attr("alt")?.take(30)} imgSrc=${img?.attr("src")?.take(50)}",
            )
        }

        val mangas = doc.select(cardSel).mapNotNull { card ->
            val link = card.selectFirst("a.series-card-main[href], a[href*=/series/], a.web-poster-main[href], a.web-compact-title[href]")
                ?: return@mapNotNull null
            val href = link.attr("href")
            if (href.isBlank()) return@mapNotNull null

            val title = card.selectFirst("h2, h3, .series-card-title, .web-poster-title, .web-compact-title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: link.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val cover = card.selectFirst("img")?.let { img ->
                listOf("data-src", "data-lazy-src", "data-lazy", "src").firstNotNullOfOrNull { attr ->
                    img.attr(attr).takeIf { it.isNotBlank() }
                }
            }.orEmpty()

            SManga.create().apply {
                this.title = title
                url = mangaPathTemplate.slug(href)
                thumbnail_url = cover.toAbsolute()
            }
        }.distinctBy { it.url }

        // If the homepage (latest tab) uses a different card container, log the actual structure
        // so we can adapt instead of returning an empty list.
        if (mangas.isEmpty() || doc.select(cardSel).isEmpty()) {
            val cardish = doc.select("div[class*='card'], article, a[href*=/series/]").take(12)
                .map { "${it.tagName()}.${it.className().take(40)}" }
            val seriesCardCount = doc.select("article.series-card").size
            val allCardCount = doc.select(cardSel).size
            Log.i(TAG, "list: no cards found ($seriesCardCount/$allCardCount); candidates=$cardish")
        }

        // Pagination: the site renders numbered links like /np-light/series-list/?page=2.
        // The base class only looks for `.pagination .next` which this theme doesn't emit, so the
        // app used to think page 1 was the end and stop at 20 novels. Treat any "page=" link whose
        // number is not the current page as proof there's a next page.
        val currentPage = Regex("""[?&]page=(\d+)""").find(doc.location().ifEmpty { "" })
            ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNextPage = Regex("""[?&]page=(\d+)""").findAll(doc.html())
            .map { it.groupValues[1].toIntOrNull() }
            .filterNotNull()
            .any { it > currentPage }

        Log.i(TAG, "list parsed=${mangas.size} of ${doc.select("article.series-card").size} cards hasNext=$hasNextPage")
        val paginationLinks = doc.select("a[href*='page='], .pagination a, .page-numbers, a.next, a[rel=next]")
            .map { "page=${it.attr("href").substringAfter("page=").take(8)}" }
            .take(8)
        Log.i(TAG, "list pagination links: $paginationLinks")
        return MangasPage(mangas, hasNextPage)
    }

    // ---- detail page ------------------------------------------------------

    override fun parseMangaDetails(doc: Document): SManga = SManga.create().apply {
        title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")?.trim()
            ?: "Unknown Title"

        thumbnail_url = doc.selectFirst(
            "meta[property=og:image], meta[name=twitter:image]," +
                " img.series-cover, .series-detail img, .thumb img, img.ts-post-image, img.cover, img[onerror]",
        )?.let { el ->
            when (el.tagName()) {
                "meta" -> el.attr("content")
                else -> listOf("data-src", "data-lazy-src", "data-lazy", "data-img", "src").firstNotNullOfOrNull { attr ->
                    el.attr(attr).takeIf { it.isNotBlank() }
                }
            }
        }?.toAbsolute().orEmpty()

        // The description may live in any of several containers depending on theme version; the
        // initial HTML occasionally inlines a short teaser too.  Prefer the *longest* dedicated
        // description element (formattedText keeps <br>/<p> breaks), then fall back to the
        // entry-content block or the og:description meta tag.
        val descCandidates = listOf(
            ".seriestudec",
            ".seriestucontent .seriestudec",
            ".series-description",
            "[itemprop=description]",
            ".infox .description", ".infox .desc",
            ".meta-post-description",
            ".summary",
            ".novel-info .description",
            ".story-description", ".novel-description", ".sinopsis",
        )
        // Debug: log every candidate's length so we can see which actually holds the full synopsis.
        descCandidates.forEach { sel ->
            val el = doc.selectFirst(sel)
            if (el != null) Log.i(TAG, "dom-desc[$sel] len=${el.formattedText().length} text=\"${el.formattedText().take(60)}\"")
        }
        description = descCandidates.mapNotNull { doc.selectFirst(it) }
            .maxByOrNull { it.formattedText().length }
            ?.formattedText()?.sanitizeDescription()?.takeIf { it.length >= 5 }
            ?: doc.selectFirst(".entry-content, .entry-summary")?.formattedText()
                ?.sanitizeDescription()?.takeIf { it.length >= 5 }
            ?: doc.selectFirst("meta[property=og:description], meta[name=description]")
                ?.attr("content")?.trim()
            ?: ""

        author = listOf(
            "a[href*=author]",
            ".spe span:contains(Author), .serl:contains(Author)",
            ".series-author a, .series-author",
            "[itemprop=author]",
        ).firstNotNullOfOrNull { sel ->
            doc.selectFirst(sel)?.text()?.trim()?.takeIf { it.length >= 2 }
        }?.let { el ->
            // Some themes put "Author: Name" inside the same element
            el.substringAfter("Author", "").replace(":", "").trim().takeIf { it.isNotBlank() } ?: el
        } ?: ""

        genre = listOf(
            ".genxed a, .sertogenre a",
            "a[href*=genre]",
            ".series-genres a, .tags a, .tagcloud a",
        ).flatMap { sel -> doc.select(sel).map { it.text().trim() } }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(", ")

        status = when {
            doc.select(".sertostat, .spe, .serl, .series-status").text()
                .contains("Completed", ignoreCase = true) -> SManga.COMPLETED
            doc.select(".sertostat, .spe, .serl, .series-status").text()
                .contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }

        Log.i(TAG, "detail: title=$title desc=${description?.take(80)} cover=$thumbnail_url status=$status")
    }

    // ---- chapters ---------------------------------------------------------

    override fun parseChapterList(doc: Document): List<SChapter> {
        // The rendered chapter rows are `a.chapter-row` inside `section.chapter-list` (the DOM log
        // confirmed: a.chapter-row > p.chapter-list-scroll > section.chapter-list). Restricting to
        // this container immediately excludes the site header, breadcrumbs, tag links, search icons,
        // and any "related novels" sidebar, so we don't even need a slug comparison — the section
        // only ever lists THIS novel's chapters.
        val slugs = doc.select("section.chapter-list a.chapter-row, .chapter-list a.chapter-row")
        Log.i(TAG, "parseChapterList: chapter-row anchors=${slugs.size}")

        val chapters = slugs.mapNotNull { link ->
            val href = link.attr("href").trim()
            if (href.isBlank() || "/chapter" !in href) return@mapNotNull null
            // Rendered chapter rows append the upload time at the end, in two shapes:
            //   "... +2026-09-15 00:06:13"   (with '+')
            //   "... الفصل 430: ...2026-04-03 19:07:41"   (no '+', date glued on)
            // Strip it from the name and put it in the date field instead.
            val rawName = link.text().trim().ifBlank { return@mapNotNull null }
            val name = stripTrailingDate(rawName)
            SChapter.create().apply {
                // Store a site-relative path starting with "/" (same convention as the base parser).
                url = href.toAbsolute().removePrefix(baseUrl).let { if (it.startsWith("/")) it else "/$it" }
                this.name = name
                date_upload = parseChapterDate(link)
            }
        }.distinctBy { it.url }.toList()

        Log.i(TAG, "chapters parsed=${chapters.size}")
        return if (reverseChapters) chapters.reversed() else chapters
    }

    /** Removes junk appended after a description (share buttons, "continue reading") and normalizes whitespace. */
    private fun String.sanitizeDescription(): String = replace(Regex("""\s{3,}"""), "\n\n")
        .trim()

    /** Removes a trailing upload time like "+2026-09-15 00:06:13" or "2026-04-03 19:07:41" (with or without "+"). */
    private fun stripTrailingDate(name: String): String {
        // The '|' alternatives must be tried in order; the 'without +' form is anchored to the end
        // of the line so it doesn't strip "Chapter 5: The End".
        val m = TRAILING_DATE.find(name) ?: return name.trim()
        return name.removeRange(m.range).trim()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val detailUrl = mangaPathTemplate.absolute(baseUrl, manga.url)
        val rendered = fetchRenderedDom(detailUrl)

        val updatedManga = if (fetchDetails && rendered != null) {
            parseMangaDetails(rendered)
        } else if (fetchDetails) {
            client.newCall(GET(detailUrl, headers)).execute().use { resp ->
                parseMangaDetails(Jsoup.parse(resp.body.string()))
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            if (rendered != null) parseChapterList(rendered) else emptyList()
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseChapterDate(link: Element): Long {
        // The upload time is appended to the chapter link's own text ("… +2026-09-15 00:06:13"),
        // so read it from there first, then fall back to the enclosing row.
        val text = link.text().ifBlank { link.closest("li, .chapter-item, .chapter-row")?.text().orEmpty() }
        val dateMatch = Regex("""(\d{4}-\d{2}-\d{2})(?:\s+(\d{2}:\d{2}:\d{2}))?""").find(text) ?: return 0L
        val date = dateMatch.groupValues[1]
        val time = dateMatch.groupValues[2]
        val pattern = if (time.isNotBlank()) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd"
        val value = if (time.isNotBlank()) "$date $time" else date
        return runCatching {
            java.text.SimpleDateFormat(pattern, java.util.Locale.US).parse(value)?.time ?: 0L
        }.getOrDefault(0L)
    }

    // ---- content ----------------------------------------------------------

    override suspend fun fetchPageText(page: eu.kanade.tachiyomi.source.model.Page): String {
        // Chapter content itself is server-rendered and ships in the initial HTML, so try the fast
        // OKHttp path first (the interceptor already carries the solved cf_clearance cookie). Only
        // if that lands on a Cloudflare challenge do we fall back to rendering via WebView — that
        // path is slow (waits for full render), so it must be the exception, not the default.
        var html = client.newCall(GET(baseUrl + page.url, headers)).execute().body.string()
        if (isChallenge(html)) {
            Log.w(TAG, "chapter GET hit a challenge, rendering via WebView")
            html = fetchRenderedDom(baseUrl + page.url)?.outerHtml() ?: html
        }
        val doc = Jsoup.parse(html)
        doc.select("script, style, ins, iframe, .ads, .adsbygoogle, .advertisement, .ad-unit, nav, footer, header").remove()

        // Strip ad-placeholder elements the site injects mid-content ("Advertise here" etc.).
        doc.select("div[class*='ad'], div[id*='ad'], p[class*='ad']").forEach { el ->
            val txt = el.text().trim()
            if (txt.length <= 40 && AD_TEXT_MARKERS.any { it in txt.lowercase() }) el.remove()
        }

        val candidates = doc.select(".chapter-content, .epcontent, .reading-content, #chapter-content, .entry-content")
        val best = candidates.toList()
            .maxByOrNull { el -> el.select("p").sumOf { it.text().length } }

        Log.i(TAG, "content: candidates=${candidates.size} bestLen=${best?.text()?.length ?: 0}")
        return best?.html() ?: doc.selectFirst("article")?.html() ?: html
    }

    /**
     * TEMP DEBUG: inspect the rendered DOM's chapter-list structure so we can find the container
     * the site actually uses for the current novel's chapters (the JS-loaded ones) vs. the
     * "recommended/related novels" sidebar which is what our current selectors wrongly match.
     */
    private fun logDomStructure(h: String) {
        val doc = Jsoup.parse(h)
        val slug = doc.selectFirst("link[rel=canonical]")?.attr("href")?.substringAfterLast("/")?.trim()
        val h1 = doc.selectFirst("h1")?.text()?.take(40)
        Log.i(TAG, "dom: h1=$h1 canonicalSlug=$slug")

        // candidate chapter-containers present in DOM
        val containers = listOf(
            ".chapter-list", ".eplister", ".listing-chapters_wrap", "#chapterlist", ".chapterlist",
            ".chnav", ".chapter-wrapper", ".allc", ".chapter_list", ".chapters", ".c-list",
            ".list-chapters", ".chp-lst", ".chapter-item", "ul.chapters", ".l-chapters",
        ).mapNotNull { sel ->
            val el = doc.selectFirst(sel)
            if (el != null) {
                "${el.tagName()}.${el.className().take(60)} children=${el.children().size}"
            } else {
                null
            }
        }
        Log.i(TAG, "dom: chapter containers found: $containers")

        // The REAL chapter anchors are a[href*=/chapter-]; log their ancestor chain so we can find
        // the container the CLI-selected novel's chapters actually live in.
        val chapterAnchors = doc.select("a[href*=/chapter-]").take(10)
        Log.i(TAG, "dom: a[href*=/chapter-] count=${doc.select("a[href*=/chapter-]").size}")
        chapterAnchors.forEach { a ->
            val chain = buildList {
                var el: Element? = a
                repeat(6) {
                    el ?: return@repeat
                    add("${el.tagName()}.${el.className().take(40)}")
                    el = el.parent()
                }
            }
            Log.i(TAG, "dom-a: href=${a.attr("href").take(70)} txt=\"${a.text().trim().take(50)}\" chain=${chain.joinToString(" > ")}")
        }
    }

    private fun String.toAbsolute(): String = when {
        isBlank() -> this
        startsWith("http://") || startsWith("https://") -> this
        // Protocol-relative (//cdn.example/...) — adopt the site's scheme.
        startsWith("//") -> "https:$this"
        startsWith("/") -> baseUrl + this
        else -> "$baseUrl/$this"
    }

    private fun isChallenge(body: String): Boolean {
        // Real pages are large (100 KB+) and only reference "challenge-platform" in <script> tags;
        // only small pages (< 10 KB) with a challenge <title> are actual CF challenge stubs.
        if (body.length > 10_000) return false
        val titleStart = body.indexOf("<title>", 0, ignoreCase = true)
        if (titleStart < 0) return false
        val titleEnd = body.indexOf("</title>", titleStart, ignoreCase = true)
        if (titleEnd < 0) return false
        val title = body.substring(titleStart + 7, titleEnd)
        return title.contains("Just a moment", ignoreCase = true) ||
            title.contains("Attention Required", ignoreCase = true) ||
            title.contains("Security Check", ignoreCase = true)
    }

    private companion object {
        const val TAG = "NovelsParadiseCF"

        /**
         * Trailing upload time on each rendered chapter row, with or without a leading "+":
         * "... +2026-09-15 00:06:13" or "... الفصل 430: نعمة آلهة القمر2026-04-03 19:07:41".
         */
        val TRAILING_DATE = Regex("""\s*\+?\s*\d{4}-\d{2}-\d{2}(?:\s+\d{2}:\d{2}:\d{2})?\s*$""")

        /** Placeholder texts the site leaves where an ad would load. */
        val AD_TEXT_MARKERS = listOf("advertise here", "advertisement", "ad space", "ad start", "ad end")
    }
}

/**
 * Detects Cloudflare managed challenge pages ("Just a moment…") served by
 * novelsparadise.site and auto-solves them via WebView, then retries the request
 * with the `cf_clearance` cookie harvested from the solved WebView session.
 *
 * OkHttp and the WebView have separate cookie stores, so the challenge cookie solved inside the
 * WebView must be read back and injected into the OkHttp request for the retry to pass. The cookie
 * is cached per host so subsequent calls go straight through; if a cached cookie is ever rejected we
 * re-solve once and retry, then fail with a clear message.
 */
private class CloudflareChallengeInterceptor(
    private val session: WebViewSession,
    private val baseUrl: String,
) : Interceptor {

    private val cookieCache = ConcurrentHashMap<String, String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        // Attach a previously solved cookie so normal traffic skips the challenge.
        // If the process restarted (cookieCache empty) but the user already solved the challenge
        // in WebView, cf_clearance still lives in the shared CookieManager — reuse it directly
        // instead of re-solving, so a manual bypass is respected.
        val cached = cookieCache.getOrPut(host) {
            val shared = runCatching {
                CookieManager.getInstance().getCookie("https://$host/").orEmpty()
            }.getOrDefault("")
            if (shared.isNotBlank() && "cf_clearance" in shared) shared else ""
        }.takeIf { it.isNotBlank() }
        Log.i(TAG, "→ ${request.method} ${request.url} (cachedCookie=${cached != null})")
        var response = chain.proceed(if (cached != null) request.carrying(cached) else request)
        Log.i(TAG, "← ${response.code} ${request.url}")

        if (!isChallengePage(response)) return response
        Log.w(TAG, "challenge detected on ${request.url}, solving via WebView…")
        response.close()

        val cookie = solveChallenge(chain)
        cookieCache[host] = cookie

        response = chain.proceed(request.carrying(cookie))
        Log.i(TAG, "retry → ${response.code} ${request.url}")
        if (isChallengePage(response)) {
            response.close()
            throw Exception(
                "NovelsParadise: the Cloudflare challenge was solved but the request is still " +
                    "blocked. Please open the site in WebView once, then retry.",
            )
        }

        return response
    }

    private fun okhttp3.Request.carrying(cookie: String): okhttp3.Request = newBuilder().header("Cookie", cookie).build()

    private fun isChallengePage(response: Response): Boolean {
        val body = response.peekBody(8192).string()
        return isChallenge(body)
    }

    private fun isChallenge(body: String): Boolean {
        // Real pages are large (100 KB+) and only reference "challenge-platform" in <script> tags;
        // only small pages (< 10 KB) with a challenge <title> are actual CF challenge stubs.
        if (body.length > 10_000) return false
        val titleStart = body.indexOf("<title>", 0, ignoreCase = true)
        if (titleStart < 0) return false
        val titleEnd = body.indexOf("</title>", titleStart, ignoreCase = true)
        if (titleEnd < 0) return false
        val title = body.substring(titleStart + 7, titleEnd)
        return title.contains("Just a moment", ignoreCase = true) ||
            title.contains("Attention Required", ignoreCase = true) ||
            title.contains("Security Check", ignoreCase = true)
    }

    private fun solveChallenge(chain: Interceptor.Chain): String {
        Log.i(TAG, "opening WebView at $baseUrl (useOkHttpNetwork=false)")
        try {
            runWebViewBlocking<Unit>(call = chain.call(), session = session, timeout = 60.seconds) {
                useOkHttpNetwork = false
                var done = false

                fun checkSolved() {
                    if (done) return
                    // The challenge page swaps out once solved; a real document title means the
                    // interstitial is gone. Read it from the live DOM rather than the callback arg
                    // so it also works for the poll fallback below.
                    evaluateJs("document.title") { titleJson ->
                        if (done) return@evaluateJs
                        val title = runCatching { titleJson.parseAs<String>() }.getOrDefault("")
                        if (title.isNotBlank() && !isChallengeTitle(title)) {
                            done = true
                            resolve(Unit)
                        }
                    }
                }

                onPageFinished { checkSolved() }
                // onPageFinished can miss the swap on some WebView builds, so re-check periodically.
                poll(interval = 1.seconds) { checkSolved() }
                loadUrl(baseUrl)
            }
        } catch (e: WebViewTimeoutException) {
            throw Exception(
                "NovelsParadise: could not solve the Cloudflare challenge automatically. " +
                    "Please open the site in WebView once, then retry.",
                e,
            )
        }

        // The WebView used its own network stack, so cf_clearance was stored as an HttpOnly cookie
        // in the shared Android cookie store — read it back for the OkHttp retry.
        val cookie = CookieManager.getInstance().getCookie(baseUrl)
        if (cookie.isNullOrBlank() || "cf_clearance" !in cookie) {
            throw Exception(
                "NovelsParadise: the Cloudflare challenge was solved but no cf_clearance cookie " +
                    "was stored. Please open the site in WebView once, then retry.",
            )
        }

        return cookie
    }

    private fun isChallengeTitle(title: String): Boolean = title.contains("Just a moment", ignoreCase = true) ||
        title.contains("Attention Required", ignoreCase = true) ||
        title.contains("Security Check", ignoreCase = true)

    companion object {
        private const val TAG = "NovelsParadiseCF"
    }
}

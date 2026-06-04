package eu.kanade.tachiyomi.multisrc.madaranovel

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.SourceTracker
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy
import java.util.Calendar

/**
 * Base class for Madara Engine powered novel sites.
 * Handles common parsing and request logic.
 * @see https://github.com/LNReader/lnreader-plugins madara/template.ts
 */
open class MadaraNovel(
    override val baseUrl: String,
    override val name: String,
    override val lang: String = "en",
) : HttpSource(),
    NovelSource,
    ConfigurableSource,
    SourceTracker {

    override val isNovelSource = true

    override val supportsLatest = true
    override val client = network.cloudflareClient

    protected val json: Json by injectLazy()

    private val preferences: SharedPreferences by getPreferencesLazy()

    /**
     * Override this in subclass to set default value.
     * When useNewChapterEndpoint = true: Uses POST to $novelUrl/ajax/chapters/
     * When useNewChapterEndpoint = false: Uses POST to /wp-admin/admin-ajax.php with manga ID
     */
    protected open val useNewChapterEndpointDefault = false

    /**
     * Whether to use the new chapter endpoint (POST to /ajax/chapters/).
     * Can be toggled in extension settings if not overridden.
     */
    protected val useNewChapterEndpoint: Boolean
        get() = preferences.getBoolean(USE_NEW_CHAPTER_ENDPOINT_PREF, useNewChapterEndpointDefault)

    /**
     * Override this in subclass to change the default value for chapter reversal.
     */
    protected open val reverseChapterListDefault: Boolean = false

    /**
     * Whether to reverse the chapter list (show oldest first).
     * Default is false (newest first), override [reverseChapterListDefault] to change.
     */
    protected val reverseChapterList: Boolean
        get() = preferences.getBoolean(PREF_REVERSE_CHAPTERS, reverseChapterListDefault)

    // LN Reader: Captcha title checks
    private val captchaTitles = listOf(
        "Bot Verification",
        "You are being redirected...",
        "Un instant...",
        "Just a moment...",
        "Redirecting...",
    )

    /**
     * LN Reader: Check for captcha/bot verification pages
     * Throws exception to prompt webview open
     */
    protected fun checkCaptcha(doc: Document, url: String) {
        val title = doc.title().trim()
        if (captchaTitles.contains(title)) {
            throw Exception("Captcha detected, please open in WebView")
        }
        // Also check for Cloudflare Turnstile
        if (doc.selectFirst("script[src*='challenges.cloudflare.com/turnstile']") != null) {
            throw Exception("Cloudflare Turnstile detected, please open in WebView")
        }
    }

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/page/$page/?s=&post_type=wp-manga"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = parseNovels(doc)
        // Check multiple pagination selectors for different Madara themes
        val hasNextPage = doc.selectFirst(".pagination a:contains(next)") != null ||
            doc.selectFirst("a.next.page-numbers") != null ||
            doc.selectFirst(".nav-previous a") != null ||
            doc.selectFirst(".wp-pagenavi a.nextpostslink") != null ||
            doc.selectFirst(".page-item.next:not(.disabled) a") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/page/$page/?s=&post_type=wp-manga&m_orderby=latest"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var url = "$baseUrl/page/$page/?s=${query.replace(" ", "+")}&post_type=wp-manga"

        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> {
                    if (filter.state != 0) {
                        url += "&m_orderby=${filter.toUriPart()}"
                    }
                }

                is SortFilter -> {
                    if (filter.state != 0) {
                        url += "&m_orderby=${filter.toUriPart()}"
                    }
                }

                else -> {}
            }
        }

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    protected fun parseNovels(doc: Document): List<SManga> {
        doc.select(".manga-title-badges").remove()

        // Comprehensive selector for various Madara theme layouts:
        // - .page-item-detail: Standard novel list item
        // - .c-tabs-item__content: Tab content items
        // - .item-thumb.c-image-hover: Thumbnail items
        // - .tab-thumb.c-image-hover: Tab thumbnail items
        // - div.col-4, div.col-md-2, div.col-12.col-md-4: Grid layouts (FansTranslations, etc)
        // - div.hover-details: Hover detail items (SonicMTL)
        // - .badge-pos-2: Badge position items (HiraethTranslation)
        return doc.select(
            ".page-item-detail, .c-tabs-item__content, .item-thumb.c-image-hover, " +
                ".tab-thumb.c-image-hover, div.col-4, div.col-md-2, div.col-12.col-md-4, " +
                "div.hover-details, .badge-pos-2 .page-item-detail",
        ).mapNotNull { element ->
            try {
                val title = element.selectFirst(".post-title")?.text()?.trim()
                    ?: element.selectFirst("a")?.attr("title")?.ifEmpty { null }
                    ?: return@mapNotNull null
                val url = element.selectFirst(".post-title a")?.attr("href")
                    ?: element.selectFirst("a")?.attr("href")
                    ?: return@mapNotNull null

                // Ensure URL is relative path (not full URL)
                val relativeUrl = when {
                    url.startsWith(baseUrl) -> url.removePrefix(baseUrl)

                    url.startsWith("http://") || url.startsWith("https://") -> {
                        // Extract path from full URL
                        try {
                            java.net.URI(url).path
                        } catch (e: Exception) {
                            url
                        }
                    }

                    url.startsWith("/") -> url

                    else -> "/$url"
                }

                val image = element.selectFirst("img")
                val cover = image?.attr("data-lazy-src")?.ifEmpty { null }
                    ?: image?.attr("data-src")?.ifEmpty { null }
                    ?: image?.attr("src")?.ifEmpty { null }
                    ?: image?.attr("data-lazy-srcset")?.split(" ")?.firstOrNull()
                    ?: image?.attr("srcset")?.split(" ")?.firstOrNull()
                    ?: ""

                SManga.create().apply {
                    this.title = title
                    this.url = relativeUrl
                    thumbnail_url = cover
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()

        // LN Reader: Check for captcha before parsing
        checkCaptcha(doc, response.request.url.toString())

        doc.select(".manga-title-badges, #manga-title span").remove()

        // Cache the WP post id for tracking (bookmark/history) calls later
        extractPostId(doc)?.let { cachePostId(response.request.url.encodedPath, it) }

        return SManga.create().apply {
            title = doc.selectFirst(".post-title h1, #manga-title h1")?.text()?.trim() ?: ""

            // Get cover from summary image
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
                    // Match various genre/tag label variations (including i18n)
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

    /**
     * Extracts the WordPress post id of a novel from its page.
     * Used both for the old chapter list endpoint and tracking calls.
     */
    protected fun extractPostId(doc: Document): String? = doc.selectFirst(".rating-post-id")?.attr("value")?.ifEmpty { null }
        ?: doc.selectFirst("#manga-chapters-holder")?.attr("data-id")?.ifEmpty { null }
        ?: doc.selectFirst("a[data-post-id]")?.attr("data-post-id")?.ifEmpty { null }
        // Fallback: extract from shortlink (e.g., <link rel="shortlink" href="...?p=91245">)
        ?: doc.selectFirst("link[rel=shortlink]")?.attr("href")
            ?.let { Regex("""[?&]p=(\d+)""").find(it)?.groupValues?.get(1) }

    // "Chapter 12", "Ch. 12.5 - Title", "Episode 3: Title"
    private val chapterPrefixRegex =
        Regex("""^(?:chapter|chap|ch|episode|ep)\.?\s*(\d+(?:\.\d+)?)\s*[-–—:.]?\s*""", RegexOption.IGNORE_CASE)

    // "12 - Title" (bare number requires an explicit separator so titles that
    // merely start with a number aren't eaten)
    private val numberSeparatorRegex = Regex("""^(\d+(?:\.\d+)?)\s*[-–—:]\s*""")

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val mangaUrl = response.request.url.encodedPath

        // Cache the WP post id for tracking (bookmark/history) calls later
        val postId = extractPostId(doc)
        postId?.let { cachePostId(mangaUrl, it) }

        val chapters = mutableListOf<SChapter>()
        var html: String

        if (useNewChapterEndpoint) {
            val emptyBody = FormBody.Builder().build()
            val newHeaders = headersBuilder()
                .set("Referer", response.request.url.toString())
                .build()
            val chapResponse = client.newCall(
                POST("$baseUrl${mangaUrl}ajax/chapters/", newHeaders, emptyBody),
            ).execute()
            html = chapResponse.body.string()
        } else {
            val formBody = FormBody.Builder()
                .add("action", "manga_get_chapters")
                .add("manga", postId ?: "")
                .build()

            val chapResponse = client.newCall(
                POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody),
            ).execute()
            html = chapResponse.body.string()
        }

        if (html != "0") {
            val chapDoc = Jsoup.parse(html)
            val totalChaps = chapDoc.select(".wp-manga-chapter").size

            chapDoc.select(".wp-manga-chapter").forEachIndexed { index, element ->
                try {
                    val rawName = element.selectFirst("a")?.text()?.trim() ?: return@forEachIndexed
                    val isLocked = element.className().contains("premium-block")

                    // The app shows the chapter number separately from the title,
                    // so strip "Chapter 12 - " style prefixes from the displayed name
                    val numberMatch = chapterPrefixRegex.find(rawName)
                        ?: numberSeparatorRegex.find(rawName)
                    val parsedNumber = numberMatch?.groupValues?.get(1)?.toFloatOrNull()
                    var chapterName = numberMatch?.let { rawName.removeRange(it.range).trim() }
                        ?.ifEmpty { null }
                        ?: rawName

                    if (isLocked) {
                        chapterName = "🔒 $chapterName"
                    }

                    val releaseDate = element.selectFirst(".chapter-release-date")?.text()?.trim() ?: ""
                    val chapterUrl = element.selectFirst("a")?.attr("href") ?: return@forEachIndexed

                    if (chapterUrl != "#") {
                        // Ensure URL is relative path
                        val relativeChapterUrl = when {
                            chapterUrl.startsWith(baseUrl) -> chapterUrl.removePrefix(baseUrl)

                            chapterUrl.startsWith("http://") || chapterUrl.startsWith("https://") -> {
                                try {
                                    java.net.URI(chapterUrl).path
                                } catch (e: Exception) {
                                    chapterUrl
                                }
                            }

                            chapterUrl.startsWith("/") -> chapterUrl

                            else -> "/$chapterUrl"
                        }

                        chapters.add(
                            SChapter.create().apply {
                                url = relativeChapterUrl
                                name = chapterName
                                date_upload = parseDate(releaseDate)
                                chapter_number = parsedNumber ?: (totalChaps - index).toFloat()
                            },
                        )
                    }
                } catch (e: Exception) {
                    // Skip problematic chapters
                }
            }
        }

        // Return chapters in requested order
        return if (reverseChapterList) chapters else chapters.reversed()
    }

    /**
     * For novel sources, we return a single Page containing the chapter URL.
     * The actual content is fetched via fetchPageText() which is called for NovelSource.
     */
    override fun pageListParse(response: Response): List<Page> {
        // Return a page with the chapter URL - the content will be fetched via fetchPageText
        val url = response.request.url.encodedPath
        return listOf(Page(0, url))
    }

    override fun imageUrlParse(response: Response): String = ""

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = response.asJsoup()

        // LN Reader: Check for captcha before parsing
        checkCaptcha(doc, baseUrl + page.url)

        // Remove ads and unwanted elements FIRST (comprehensive list from LN Reader)
        doc.select(
            "div.ads, div.unlock-buttons, sub, script, ins, .adsbygoogle, .code-block, noscript, " +
                "div[id*=google], div[id*=bidgear], div[class*=bidgear], div[class*=google-tag], " +
                "iframe, .foxaholic-google-tag-manager-body, .foxaholic-bidgear-before-content-1x1, " +
                ".foxaholic-bidgear-banner-before-content, div[id^=bg-ssp], " +
                ".adx-zone, .adx-head, [id*='-ad-'], [class*='-ad-'], .ad-container",
        ).remove()

        // Try multiple selectors for chapter content
        // Look for the largest content block among candidates
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

        // Select the candidate with the most paragraph tags (actual content)
        var contentElement: org.jsoup.nodes.Element? = null
        var maxParagraphText = -1
        for (element in candidates) {
            val paragraphTextLength = element.select("p").sumOf { it.text().length }
            if (paragraphTextLength > maxParagraphText) {
                maxParagraphText = paragraphTextLength
                contentElement = element
            }
        }

        if (preferences.getBoolean(PREF_RAW_HTML, false)) {
            return contentElement?.html() ?: doc.html()
        }

        // Get the content HTML and clean up any remaining script artifacts
        var content = contentElement?.html() ?: ""

        // Remove any inline scripts that may have been left
        content = content.replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
        // Remove adsbygoogle push calls
        content = content.replace(Regex("""\(adsbygoogle[^)]*\)[^;]*;?"""), "")
        // Remove empty divs
        content = content.replace(Regex("""<div[^>]*>\s*</div>"""), "")

        return content.trim()
    }

    override fun getFilterList(): FilterList = FilterList(
        StatusFilter(),
        SortFilter(),
    )

    protected fun parseDate(dateStr: String): Long {
        return try {
            if (dateStr.isEmpty()) return 0L

            val number = Regex("\\d+").find(dateStr)?.value?.toIntOrNull() ?: return 0L
            val calendar = Calendar.getInstance()

            when {
                dateStr.contains("second", ignoreCase = true) -> calendar.add(Calendar.SECOND, -number)
                dateStr.contains("minute", ignoreCase = true) -> calendar.add(Calendar.MINUTE, -number)
                dateStr.contains("hour", ignoreCase = true) -> calendar.add(Calendar.HOUR_OF_DAY, -number)
                dateStr.contains("day", ignoreCase = true) -> calendar.add(Calendar.DAY_OF_MONTH, -number)
                dateStr.contains("week", ignoreCase = true) -> calendar.add(Calendar.WEEK_OF_YEAR, -number)
                dateStr.contains("month", ignoreCase = true) -> calendar.add(Calendar.MONTH, -number)
                dateStr.contains("year", ignoreCase = true) -> calendar.add(Calendar.YEAR, -number)
            }

            calendar.timeInMillis
        } catch (e: Exception) {
            0L
        }
    }

    protected fun Response.asJsoup(): Document = Jsoup.parse(body.string())

    // ======================== Tracking ========================

    /**
     * Set to false in a subclass to disable chapter read syncing for that source,
     * e.g. if the site removed the wp-manga user history AJAX endpoint.
     */
    protected open val chapterTrackingSupported = true

    /**
     * Set to false in a subclass to disable favorites/bookmark syncing for that source,
     * e.g. if the site removed the wp-manga bookmark AJAX endpoint.
     */
    protected open val favoritesTrackingSupported = true

    private val trackingEnabled: Boolean
        get() = preferences.getBoolean(PREF_ENABLE_TRACKING, false)

    override val supportsChapterTracking: Boolean
        get() = chapterTrackingSupported && trackingEnabled

    override val supportsFavoritesTracking: Boolean
        get() = favoritesTrackingSupported && trackingEnabled

    override suspend fun onChaptersRead(
        manga: SManga,
        changedChapters: List<SChapter>,
        allChapters: List<SChapter>,
        categories: List<String>,
    ) {
        if (!supportsChapterTracking) return
        val target = changedChapters
            .filter { it.chapter_number > 0f }
            .maxByOrNull { it.chapter_number }
            ?: changedChapters.lastOrNull()
            ?: return

        // The site happily overwrites history with an older chapter, so guard locally
        if (preferences.getBoolean(PREF_PROTECT_HIGHEST, true)) {
            val cached = highestTrackedNumber(cacheKey(manga.url))
            if (cached != null && target.chapter_number <= cached) return
        }

        val chapterSlug = target.url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (chapterSlug.isEmpty()) return

        val postId = resolvePostId(manga) ?: return
        // The history endpoint requires a fresh wp-manga nonce. It lives in the
        // `var manga = {...}` JS object on the chapter reading page, so scrape it
        // from there (with the novel page as fallback).
        val nonce = fetchNonce(target.url) ?: fetchNonce(manga.url) ?: return

        val historyBody = FormBody.Builder()
            .add("action", "manga-user-history")
            .add("postID", postId)
            .add("chapterSlug", chapterSlug)
            .add("paged", "1")
            .add("img_id", "")
            .add("nonce", nonce)
            .build()
        var anySuccess = ajaxSucceeded(historyBody)

        if (preferences.getBoolean(PREF_BOOKMARK_CHAPTER, false)) {
            val bookmarkBody = FormBody.Builder()
                .add("action", "wp-manga-user-bookmark")
                .add("postID", postId)
                .add("chapter", chapterSlug)
                .add("page", "1")
                .build()
            anySuccess = ajaxSucceeded(bookmarkBody) || anySuccess
        }

        if (anySuccess) {
            recordHighestTracked(cacheKey(manga.url), target.chapter_number)
        }
    }

    override suspend fun onFavorited(manga: SManga, categories: List<String>) {
        if (!supportsFavoritesTracking) return
        val postId = resolvePostId(manga) ?: return
        val body = FormBody.Builder()
            .add("action", "wp-manga-user-bookmark")
            .add("postID", postId)
            .add("chapter", "")
            .add("page", "1")
            .build()
        ajaxSucceeded(body)
    }

    override suspend fun onUnfavorited(manga: SManga, categories: List<String>) {
        if (!supportsFavoritesTracking) return
        val postId = resolvePostId(manga) ?: return
        val body = FormBody.Builder()
            .add("action", "wp-manga-delete-bookmark")
            .add("postID", postId)
            .add("isMangaSingle", "1")
            .build()
        ajaxSucceeded(body)
    }

    /** Sends a wp-admin/admin-ajax.php POST and reports whether it returned {"success":true}. */
    private fun ajaxSucceeded(body: FormBody): Boolean = try {
        client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body)).execute().use { resp ->
            resp.isSuccessful && resp.body.string().contains("\"success\":true")
        }
    } catch (e: Exception) {
        false
    }

    /** Returns the cached post id, or scrapes the novel page when missing. */
    private fun resolvePostId(manga: SManga): String? {
        cachedPostId(cacheKey(manga.url))?.let { return it }
        return try {
            val url = baseUrl + manga.url
            val response = client.newCall(GET(url, headers)).execute()
            val doc = Jsoup.parse(response.use { it.body.string() }, url)
            extractPostId(doc)?.also { cachePostId(manga.url, it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fetches a page and extracts the AJAX nonce used by manga-user-history.
     * It lives in the `user_history-js-extra` script on chapter reading pages:
     * `var user_history_params = {"ajax_url":"...","postID":"...","nonce":"..."}`.
     * Pages carry several localized script objects with their own nonces
     * (wpMangaLogin, madara, etc.) — only this one is valid for history calls.
     */
    private fun fetchNonce(path: String): String? = try {
        val response = client.newCall(GET(baseUrl + path, headers)).execute()
        val html = response.use { it.body.string() }
        Regex("""var\s+user_history_params\s*=\s*\{[^}]*?"nonce"\s*:\s*"(\w+)"""").find(html)
            ?.groupValues?.get(1)
            // Fallback: the wp-manga `var manga` object's nonce
            ?: Regex("""var\s+manga\s*=\s*\{[^}]*?"nonce"\s*:\s*"(\w+)"""").find(html)
                ?.groupValues?.get(1)
    } catch (e: Exception) {
        null
    }

    private val postIdCache = mutableMapOf<String, String>()

    private fun cacheKey(url: String): String = url.removePrefix(baseUrl).substringBefore('?').trimEnd('/')

    private fun cachedPostId(key: String): String? {
        synchronized(postIdCache) {
            if (postIdCache.isEmpty()) {
                val raw = preferences.getString(PREF_POST_ID_CACHE, "") ?: ""
                raw.split('\n').forEach { line ->
                    val sep = line.indexOf('|')
                    if (sep > 0) postIdCache[line.substring(0, sep)] = line.substring(sep + 1)
                }
            }
            return postIdCache[key]
        }
    }

    protected fun cachePostId(mangaUrl: String, id: String) {
        val key = cacheKey(mangaUrl)
        synchronized(postIdCache) {
            // Hydrate from prefs first so we don't clobber other entries
            if (cachedPostId(key) == id) return
            postIdCache[key] = id
            val serialized = postIdCache.entries.joinToString("\n") { "${it.key}|${it.value}" }
            preferences.edit().putString(PREF_POST_ID_CACHE, serialized).apply()
        }
    }

    private fun highestTrackedNumber(mangaUrl: String): Float? {
        val raw = preferences.getString(PREF_HIGHEST_CACHE, "") ?: ""
        raw.split('\n').forEach { line ->
            val sep = line.indexOf('|')
            if (sep > 0 && line.substring(0, sep) == mangaUrl) {
                return line.substring(sep + 1).toFloatOrNull()
            }
        }
        return null
    }

    private fun recordHighestTracked(mangaUrl: String, value: Float) {
        val raw = preferences.getString(PREF_HIGHEST_CACHE, "") ?: ""
        val rebuilt = StringBuilder()
        var replaced = false
        raw.split('\n').filter { it.isNotEmpty() }.forEach { line ->
            val sep = line.indexOf('|')
            if (sep > 0 && line.substring(0, sep) == mangaUrl) {
                if (!replaced) {
                    rebuilt.append(mangaUrl).append('|').append(value).append('\n')
                    replaced = true
                }
            } else {
                rebuilt.append(line).append('\n')
            }
        }
        if (!replaced) rebuilt.append(mangaUrl).append('|').append(value).append('\n')
        preferences.edit().putString(PREF_HIGHEST_CACHE, rebuilt.toString()).apply()
    }

    // ======================== Settings ========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = USE_NEW_CHAPTER_ENDPOINT_PREF
            title = "Use New Chapter Endpoint"
            summary = "Uses POST to /ajax/chapters/ instead of admin-ajax.php. Try toggling if chapters don't load."
            setDefaultValue(useNewChapterEndpointDefault)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_REVERSE_CHAPTERS
            title = "Reverse Chapter List"
            summary = "Show chapters in oldest-to-newest order instead of newest-to-oldest."
            setDefaultValue(reverseChapterListDefault)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_RAW_HTML
            title = "Return raw HTML"
            summary = "If enabled, returns the raw HTML of the chapter content instead of parsed text. Useful for custom parsers."
            setDefaultValue(false)
        }.also(screen::addPreference)

        if (chapterTrackingSupported || favoritesTrackingSupported) {
            SwitchPreferenceCompat(screen.context).apply {
                key = PREF_ENABLE_TRACKING
                title = "Enable $name tracking"
                summary = "Sync library bookmarks and reading progress to your $name account. Requires being logged in via WebView."
                setDefaultValue(false)
            }.also(screen::addPreference)

            if (chapterTrackingSupported) {
                SwitchPreferenceCompat(screen.context).apply {
                    key = PREF_PROTECT_HIGHEST
                    title = "Don't sync chapters below current"
                    summary = "Skip syncing a chapter lower than the highest already synced for that novel. The site otherwise overwrites your history with the older chapter."
                    setDefaultValue(true)
                }.also(screen::addPreference)

                SwitchPreferenceCompat(screen.context).apply {
                    key = PREF_BOOKMARK_CHAPTER
                    title = "Bookmark chapter on read"
                    summary = "Also bookmark the chapter on the site when marking it read, updating the bookmark's chapter pointer."
                    setDefaultValue(false)
                }.also(screen::addPreference)
            }

            SwitchPreferenceCompat(screen.context).apply {
                key = PREF_RESET_TRACKING_CACHE
                title = "Reset tracking cache"
                summary = "Toggle to clear cached post IDs and highest-synced-chapter records."
                setDefaultValue(false)
                setOnPreferenceChangeListener { _, newValue ->
                    if (newValue as Boolean) {
                        preferences.edit()
                            .remove(PREF_POST_ID_CACHE)
                            .remove(PREF_HIGHEST_CACHE)
                            .apply()
                        synchronized(postIdCache) { postIdCache.clear() }
                        false
                    } else {
                        true
                    }
                }
            }.also(screen::addPreference)
        }
    }

    companion object {
        private const val USE_NEW_CHAPTER_ENDPOINT_PREF = "pref_use_new_chapter_endpoint"
        private const val PREF_REVERSE_CHAPTERS = "pref_reverse_chapters"
        private const val PREF_RAW_HTML = "pref_raw_html"
        private const val PREF_ENABLE_TRACKING = "pref_enable_tracking"
        private const val PREF_PROTECT_HIGHEST = "pref_protect_highest"
        private const val PREF_BOOKMARK_CHAPTER = "pref_bookmark_chapter_on_read"
        private const val PREF_RESET_TRACKING_CACHE = "pref_reset_tracking_cache"
        private const val PREF_POST_ID_CACHE = "pref_post_id_cache"
        private const val PREF_HIGHEST_CACHE = "pref_highest_tracked_cache"
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("All", "Ongoing", "Completed"),
        ) {
        fun toUriPart() = when (state) {
            0 -> ""
            1 -> "latest"
            2 -> "completed"
            else -> ""
        }
    }

    private class SortFilter :
        Filter.Select<String>(
            "Sort",
            arrayOf("Latest", "Trending", "Rating", "Review"),
        ) {
        fun toUriPart() = when (state) {
            0 -> "latest"
            1 -> "trending"
            2 -> "rating"
            3 -> "review"
            else -> "latest"
        }
    }
}

/**
 * Converts an element's HTML into plain text while preserving paragraph
 * (<p>) and line (<br>) breaks as newlines, instead of Jsoup's text()
 * which collapses everything into a single line.
 *
 * Top-level so standalone Madara-like sources that don't extend
 * [MadaraNovel] can reuse it too.
 */
fun Element.formattedDescription(): String {
    val breakToken = "__MADARA_BR__"
    val paragraphToken = "__MADARA_P__"
    val node = clone()
    node.select("script, style").remove()
    node.select("br").forEach { it.after(breakToken) }
    node.select("p, div, h1, h2, h3, h4, h5, h6, li")
        // the root element itself can match (e.g. div.summary__content) but has
        // no parent on the clone, so after() would throw
        .filter { it !== node }
        .forEach { it.after(paragraphToken) }
    return node.text()
        .replace(' ', ' ')
        .replace(Regex("""\s*$paragraphToken\s*"""), "\n\n")
        .replace(Regex("""\s*$breakToken\s*"""), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

package eu.kanade.tachiyomi.multisrc.readnovelfull

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.chapterutils.paginatedChapterList
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.setAltTitles
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * ReadNovelFull multisrc base class.
 * Ported from LNReader TypeScript plugin.
 *
 * Sites using this template:
 * - readnovelfull.com
 * - allnovel.org
 * - novelfull.com
 * - boxnovel/novlove.com
 * - libread.com
 * - freewebnovel.com
 * - allnovelfull/novgo.net
 * - novelbin.com
 * - lightnovelplus.com
 */
abstract class ReadNovelFull :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    // isNovelSource is provided by NovelSource interface with default value true

    /**
     * The site's manga detail URL shape, as `<prefix><slug><suffix>`. [SManga.url] is stored as
     * the bare slug (see [SlugPath]); override when a site doesn't use the common bare-slug
     * ".html" shape. A stored value starting with "/" is a pre-existing full-path entry from
     * before this source adopted slug storage, and is resolved unchanged regardless of this
     * template.
     */
    protected open val mangaPathTemplate: SlugPath = SlugPath("/", ".html")

    /** Stores [SManga.url] as a bare slug via [mangaPathTemplate]. */
    protected fun SManga.setSlugUrl(href: String) = setSlugUrl(mangaPathTemplate, href)

    // Sliding-window rate limit: the first [rateLimitPermits] requests in any [rateLimitPeriodSeconds]
    // window dispatch immediately, the rest are throttled. This lets the common case (one page in fast
    // mode, or a few pages when only a handful of chapters are new) go through with no delay while still
    // pacing the rare full accurate walk (every index page) below Cloudflare's 429 burst threshold.
    protected open val rateLimitPermits: Int = 4
    protected open val rateLimitPeriodSeconds: Long = 2

    // Max retries when the site answers 429; each retry waits the Retry-After interval.
    protected open val maxRetriesOn429: Int = 3

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(::retryOnTooManyRequests)
        .rateLimit(permits = rateLimitPermits, period = rateLimitPeriodSeconds, unit = TimeUnit.SECONDS)

    private fun retryOnTooManyRequests(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        var attempts = 0
        while (response.code == 429 && attempts < maxRetriesOn429) {
            val waitSeconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 60) ?: 5
            response.close()
            try {
                Thread.sleep(waitSeconds * 1000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            response = chain.proceed(request)
            attempts++
        }
        return response
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Configuration options - can be overridden by child classes
    protected open val popularPage: String = "most-popular"
    protected open val latestPage: String = "latest-release-novel"
    protected open val searchPage: String = "search"
    protected open val novelListing: String? = null
    protected open val chapterListing: String? = "ajax/chapter-archive"
    protected open val chapterParam: String = "novelId"
    protected open val pageParam: String = "page"
    protected open val typeParam: String = "type"
    protected open val genreParam: String = "category_novel"
    protected open val genreKey: String = "id"
    protected open val langParam: String? = null
    protected open val urlLangCode: String? = null
    protected open val searchKey: String = "keyword"
    protected open val postSearch: Boolean = false
    protected open val noAjax: Boolean = false
    protected open val pageAsPath: Boolean = false
    protected open val noPages: List<String> = emptyList()

    // Set true on sites whose chapter list is split across multiple pages on the novel page
    // (engine convention: a #indexselect page picker, chapters under div.m-newest2 ul.ul-list5).
    // Enables the existing-chapters-aware paginated path in fetchReadNovelFullChapterList below.
    protected open val chaptersPaginated: Boolean = false
    protected open val chapterListPageSize: Int = 100

    // ======================== Popular ========================

    protected open fun buildPopularMangaRequest(page: Int): Request = if (pageAsPath && page > 1) {
        // If this specific page path is listed in `noPages`, fall back to query parameter pagination
        if (noPages.any { it.trim().trimStart('/') == popularPage.trim().trimStart('/') }) {
            GET("$baseUrl/$popularPage?$pageParam=$page", headers)
        } else {
            GET("$baseUrl/$popularPage/$page", headers)
        }
    } else {
        GET("$baseUrl/$popularPage?$pageParam=$page", headers)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = mangaListParse(client.newCall(buildPopularMangaRequest(page)).execute(), popularMangaSelector(), popularMangaNextPageSelector(), ::popularMangaFromElement)

    protected fun mangaListParse(
        response: Response,
        selector: String,
        nextPageSelector: String,
        fromElement: (Element) -> SManga,
    ): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(selector).map { fromElement(it) }.filter { it.url.isNotBlank() }
        val hasNextPage = document.selectFirst(nextPageSelector) != null
        return MangasPage(mangas, hasNextPage)
    }

    protected open fun popularMangaSelector() = "div.col-novel-main div.list-novel div.row, div.archive div.row, div.index-intro div.item, div.ul-list1 div.li, div.col-l div.li, div.col-r div.li"

    protected open fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = "Unknown Title"
        url = ""

        // Try to find the title link with progressively broader selectors
        // Extended selectors to handle FreeWebNovel and other site variations
        val link = element.selectFirst("h3.novel-title a, .novel-title a, a.cover, h3.tit a, .txt h3.tit a, div.txt h3.tit a, div.con a, a.tit, a[title], a.s2, span.s2 a, .truyen-title a")

        if (link != null) {
            // Prefer title attribute, then text content
            title = link.attr("title").ifEmpty { link.text() }.ifBlank { "Unknown Title" }
            // Set URL regardless of title - even "Unknown Title" entries need URLs to work
            val href = link.attr("abs:href")
            if (href.isNotBlank()) {
                setSlugUrl(href)
            }
        } else {
            // Last resort: look for any link with href in the element
            val anyLink = element.selectFirst("a[href]")
            if (anyLink != null) {
                val linkText = anyLink.attr("title").ifEmpty { anyLink.text() }
                if (linkText.isNotBlank()) {
                    title = linkText
                    setSlugUrl(anyLink.attr("abs:href"))
                } else {
                    // If no text, still set URL with generic title
                    val href = anyLink.attr("abs:href")
                    if (href.isNotBlank()) {
                        setSlugUrl(href)
                    }
                }
            }
        }

        // Try multiple image selectors for different site structures
        // Use abs:src and abs:data-src to handle relative URLs
        thumbnail_url = element.selectFirst("img")?.let { img ->
            img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
        } ?: element.selectFirst("div.pic img, div.s1 img")?.let { img ->
            img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
        }
    }

    protected open fun popularMangaNextPageSelector() = "li.next:not(.disabled), ul.pagination li.active + li a, div.pages a:contains(>>), div.pages a:contains(>), div.pages a[href], div.paging a[href], div.pagination a.next"

    // ======================== Latest ========================

    protected open fun buildLatestUpdatesRequest(page: Int): Request = if (pageAsPath && page > 1) {
        if (noPages.any { it.trim().trimStart('/') == latestPage.trim().trimStart('/') }) {
            GET("$baseUrl/$latestPage?$pageParam=$page", headers)
        } else {
            GET("$baseUrl/$latestPage/$page", headers)
        }
    } else {
        GET("$baseUrl/$latestPage?$pageParam=$page", headers)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = mangaListParse(client.newCall(buildLatestUpdatesRequest(page)).execute(), latestUpdatesSelector(), latestUpdatesNextPageSelector(), ::latestUpdatesFromElement)

    protected open fun latestUpdatesSelector() = popularMangaSelector() + ", ul.ul-list2 li"

    protected open fun latestUpdatesFromElement(element: Element): SManga {
        // Handle ul-list2 li structure for FreeWebNovel latest updates
        if (element.tagName() == "li" && element.selectFirst("div.s1.con") != null) {
            return SManga.create().apply {
                title = "Unknown Title"
                url = ""
                val link = element.selectFirst("a.tit")
                if (link != null) {
                    title = link.attr("title").ifEmpty { link.text() }.ifBlank { "Unknown Title" }
                    setSlugUrl(link.attr("abs:href"))
                }
                thumbnail_url = element.selectFirst("div.pic img")?.let { img ->
                    img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
                }
            }
        }
        return popularMangaFromElement(element)
    }

    protected open fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ======================== Search ========================

    protected open fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var selectedType = "all"
        val selectedGenres = mutableListOf<String>()
        var selectedStatus = "all"

        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> selectedType = filter.toUriPart()
                is GenreFilter -> selectedGenres += filter.state.filter { it.state }.map { it.id }
                is StatusFilter -> selectedStatus = filter.toUriPart()
                else -> {}
            }
        }

        // Match LNReader multisrc behavior for browse filters on path-based sites.
        // When query is empty and the source is path-driven (no novelListing),
        // build URLs like /most-popular?page=1 or /genre/action?page=1.
        if (query.isBlank() && novelListing == null) {
            val genrePath = selectedGenres.firstOrNull()?.let { genre ->
                val normalized = genre.trim().trimStart('/')
                when {
                    normalized.isBlank() -> null
                    normalized.contains('/') -> normalized
                    else -> "genre/$normalized"
                }
            }

            val basePath = when {
                !genrePath.isNullOrBlank() -> genrePath
                selectedType != "all" -> selectedType.trimStart('/').ifBlank { popularPage }
                else -> popularPage
            }

            // Respect page-as-path pagination like the template implementation
            if (pageAsPath && page > 1 && !noPages.any { it.trim().trimStart('/') == basePath.trim().trimStart('/') }) {
                val pathUrl = "$baseUrl/$basePath/$page"
                val builder = pathUrl.toHttpUrl().newBuilder()
                if (selectedStatus != "all") builder.addQueryParameter("status", selectedStatus)
                return GET(builder.build(), headers)
            }

            val routeUrl = "$baseUrl/$basePath".toHttpUrl().newBuilder().apply {
                if (selectedStatus != "all") {
                    addQueryParameter("status", selectedStatus)
                }
                // Only add query page param when using query-style pagination or when page>1
                if (!pageAsPath || page > 1) addQueryParameter(pageParam, page.toString())
            }

            return GET(routeUrl.build(), headers)
        }

        if (selectedType != "all" && selectedType.contains('/')) {
            val selectedTypePath = selectedType.trim().trimStart('/')

            if (pageAsPath && page > 1 && !noPages.any { it.trim().trimStart('/') == selectedTypePath }) {
                val pathUrl = "$baseUrl/$selectedTypePath/$page"
                val builder = pathUrl.toHttpUrl().newBuilder()
                if (query.isNotEmpty()) builder.addQueryParameter(searchKey, query)
                selectedGenres.forEach { builder.addQueryParameter(genreParam, it) }
                if (selectedStatus != "all") builder.addQueryParameter("status", selectedStatus)
                return GET(builder.build(), headers)
            }

            val typePathUrl = "$baseUrl/$selectedTypePath".toHttpUrl().newBuilder()
            if (query.isNotEmpty()) {
                typePathUrl.addQueryParameter(searchKey, query)
            }
            selectedGenres.forEach { typePathUrl.addQueryParameter(genreParam, it) }
            if (selectedStatus != "all") {
                typePathUrl.addQueryParameter("status", selectedStatus)
            }
            if (!pageAsPath || page > 1) typePathUrl.addQueryParameter(pageParam, page.toString())
            return GET(typePathUrl.build(), headers)
        }

        // Build URL with filters
        val urlBuilder = "$baseUrl/$searchPage".toHttpUrl().newBuilder()

        if (query.isNotEmpty()) {
            urlBuilder.addQueryParameter(searchKey, query)
        }

        // Apply filters
        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> {
                    val type = filter.toUriPart()
                    if (type != "all") {
                        urlBuilder.addQueryParameter(typeParam, type)
                    }
                }

                is GenreFilter -> {
                    filter.state.filter { it.state }.forEach { genre ->
                        urlBuilder.addQueryParameter(genreParam, genre.id)
                    }
                }

                is StatusFilter -> {
                    val status = filter.toUriPart()
                    if (status != "all") {
                        urlBuilder.addQueryParameter("status", status)
                    }
                }

                else -> {}
            }
        }

        if (!postSearch) {
            urlBuilder.addQueryParameter(pageParam, page.toString())
        }

        val url = urlBuilder.build().toString()

        return if (postSearch && query.isNotEmpty()) {
            val body = FormBody.Builder()
                .add(searchKey, query)
                .add(pageParam, page.toString()) // Add page to POST body for pagination
                .build()
            POST(url, headers, body)
        } else {
            GET(url, headers)
        }
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = mangaListParse(client.newCall(buildSearchMangaRequest(page, query, filters)).execute(), searchMangaSelector(), searchMangaNextPageSelector(), ::searchMangaFromElement)

    protected open fun searchMangaSelector() = popularMangaSelector()

    protected open fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    protected open fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    // ======================== Details ========================

    protected open fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = ""
        document.selectFirst("div.books, div.book, div.m-imgtxt, div.m-book1")?.let { info ->
            thumbnail_url = info.selectFirst("div.pic img, img")?.let {
                it.attr("abs:data-src").ifEmpty { it.attr("abs:src") }
            }
            title = info.selectFirst("h3.title, h1.tit, img")?.let {
                it.text().ifEmpty { it.attr("title") }
            } ?: ""
        }

        if (title.isBlank()) {
            title = document.selectFirst("meta[property=\"og:title\"]")?.attr("content")
                ?.substringBefore(" - ")
                ?.trim()
                .orEmpty()
                .ifEmpty { document.title().substringBefore(" - ").trim() }
                .ifEmpty { "Unknown Title" }
        }

        // Parse info section
        var rating = ""
        var altNames = listOf<String>()
        val genresList = mutableListOf<String>()
        document.select("div.info div, ul.info-meta li, div.m-imgtxt div.item").forEach { element ->
            val text = element.text()
            when {
                text.contains("Author", ignoreCase = true) -> {
                    author = element.select("a").joinToString { it.text() }
                        .ifEmpty { text.substringAfter(":").trim() }
                }

                text.contains("Genre", ignoreCase = true) || element.select("span.glyphicon-th-list").isNotEmpty() -> {
                    val gs = element.select("a").map { it.text() }.filter { it.isNotBlank() }
                    if (gs.isNotEmpty()) {
                        genresList.addAll(gs)
                    } else {
                        val raw = text.substringAfter(":").trim()
                        if (raw.isNotBlank()) {
                            genresList.addAll(raw.split(",").map { it.trim() }.filter { it.isNotBlank() })
                        }
                    }
                    // Also check for genres in child divs with class 'right' or 's2'/'s3' (LibRead/FreeWebNovel)
                    element.select("div.right a, span.s2 a, span.s3 a").forEach { link ->
                        val linkText = link.text()
                        if (linkText.isNotBlank() && !genresList.contains(linkText)) {
                            genresList.add(linkText)
                        }
                    }
                }

                text.contains("Status", ignoreCase = true) -> {
                    status = parseStatus(text.substringAfter(":").trim())
                }

                text.contains("Alternative names", ignoreCase = true) -> {
                    altNames = text.substringAfter(":").trim()
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                }

                text.contains("Rating", ignoreCase = true) -> {
                    // Extract rating value (e.g., "8.8 / 10 from 10587 ratings")
                    val ratingValue = element.selectFirst("span[itemprop=ratingValue]")
                        ?.text()
                        ?.trim()
                    val reviewCount = element.selectFirst("span[itemprop=reviewCount]")
                        ?.text()
                        ?.trim()
                    if (!ratingValue.isNullOrBlank()) {
                        rating = "Rating: $ratingValue/10"
                        if (!reviewCount.isNullOrBlank()) {
                            rating += " ($reviewCount ratings)"
                        }
                    }
                }
            }
        }

        // Fallback: some sites may expose status in other selectors or meta tags
        if (status == SManga.UNKNOWN) {
            val statusCandidates = listOf(
                document.selectFirst(".status, span.status, li.status, p.status")?.text(),
                document.selectFirst("meta[property=\"og:novel:status\"]")?.attr("content"),
            )
            statusCandidates.firstOrNull { !it.isNullOrBlank() }?.let { status = parseStatus(it.trim()) }
        }

        // Fallback: Extract rating from multiple possible rating sections (AllNovel, LibRead, NovPub, NovelBin, etc.)
        if (rating.isBlank()) {
            // Try microdata aggregateRating first
            var found: String? = document.selectFirst("div.small[itemprop=aggregateRating] span[itemprop=ratingValue], span[itemprop=ratingValue]")
                ?.text()
            var foundCount: String? = document.selectFirst("div.small[itemprop=aggregateRating] span[itemprop=reviewCount], span[itemprop=reviewCount]")
                ?.text()

            // Try common structures: libread/novpub/freewebnovel have p.vote like "4.6 / 5 ( 260 votes )"
            if (found.isNullOrBlank()) {
                val voteText = document.selectFirst("div.score p.vote, div.score .vote, p.vote, div.m-desc .vote, div.score, div.small em, div.small, div.rate-info .small")
                    ?.text()
                    ?.trim()
                if (!voteText.isNullOrBlank()) {
                    // try to parse patterns like "4.6 / 5 ( 260 votes )" or "Rating: X / Y from Z ratings"
                    val regex = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*/\\s*([0-9]+(?:\\.[0-9]+)?)(?:\\s*\\(\\s*([0-9,]+)\\s*(?:votes?|ratings)\\s*\\))?")
                    val m = regex.find(voteText)
                    if (m != null) {
                        found = m.groupValues[1]
                        val scale = m.groupValues[2]
                        val cnt = m.groupValues.getOrNull(3)?.replace(",", "")
                        foundCount = cnt?.takeIf { it.isNotBlank() }
                        // keep original scale for display
                        if (!found.isNullOrBlank() && scale.isNotBlank()) {
                            rating = "Rating: $found/$scale"
                            if (!foundCount.isNullOrBlank()) rating += " ($foundCount votes)"
                        }
                    } else {
                        // fallback: use entire voteText as rating string
                        found = voteText
                    }
                }
            }

            // If we found microdata rating and haven't formatted rating yet, build string
            if (!found.isNullOrBlank() && rating.isBlank()) {
                rating = if (!foundCount.isNullOrBlank()) {
                    "Rating: $found/${if (found.length <= 2) "10" else "10"} ($foundCount ratings)"
                } else {
                    // If found came from span[itemprop=ratingValue], we may not know scale; prefer to show as /10
                    "Rating: $found/10"
                }
            }
        }

        var descCandidate = document.selectFirst("div.tab-content div#tab-description div.desc-text, div#tab-description div.novel-description-block div.desc-text")
            ?.let { element ->
                // Extract all paragraphs or full text, preserving line breaks
                val paragraphs = element.select("p").map { it.text() }.filter { it.isNotBlank() }
                if (paragraphs.isNotEmpty()) {
                    paragraphs.joinToString("\n\n")
                } else {
                    // Preserve br tags as line breaks
                    element.html()
                        .replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n")
                        .let { html -> org.jsoup.Jsoup.parse(html).text() }
                }
            }
            ?.takeIf { it.isNotBlank() }
            .orEmpty()

        if (descCandidate.isBlank()) {
            descCandidate = document.selectFirst("div.col-xs-12.col-sm-8.col-md-8.desc div.desc-text")
                ?.let { element ->
                    val paragraphs = element.select("p").map { it.text() }.filter { it.isNotBlank() }
                    if (paragraphs.isNotEmpty()) {
                        paragraphs.joinToString("\n\n")
                    } else {
                        // Preserve br tags as line breaks
                        element.html()
                            .replace("<br>", "\n").replace("<br/>", "\n").replace("<br />", "\n")
                            .let { html -> org.jsoup.Jsoup.parse(html).text() }
                    }
                }
                ?.takeIf { it.isNotBlank() }
                .orEmpty()
        }

        if (descCandidate.isBlank()) {
            descCandidate = document.selectFirst(
                "div.desc-text#novel-description-content, div#novel-description-content, " +
                    "div.novel-description-block div.desc-text, [itemprop=description], " +
                    "div.inner, div.desc, div.m-desc div.txt div.inner, " +
                    "div.summary div.content, div#editdescription, div.desc-text-full, " +
                    "div.novel-detail-body div.summary, div.desc_panel",
            )?.let { element ->
                val paragraphs = element.select("p").map { it.text() }.filter { it.isNotBlank() }
                if (paragraphs.isNotEmpty()) {
                    paragraphs.joinToString("\n\n")
                } else {
                    element.text()
                }
            }
                ?.takeIf { it.isNotBlank() }
                .orEmpty()
        }

        val normalizedTitle = title.trim().lowercase()
        val normalizedDesc = descCandidate.trim().lowercase()
        val descLooksLikeTitle = when {
            normalizedDesc.isBlank() -> true
            normalizedDesc == normalizedTitle -> true
            normalizedDesc.startsWith(normalizedTitle) && descCandidate.length < 120 -> true
            descCandidate.length < 30 -> true
            else -> false
        }

        if (descLooksLikeTitle) {
            descCandidate = document.selectFirst("meta[property=\"og:description\"], meta[name=\"description\"]")
                ?.attr("content")
                ?.trim()
                .orEmpty()
        }

        description = descCandidate

        // Prepend rating to description (rating should appear at start)
        if (rating.isNotBlank()) {
            description = if (!description.isNullOrBlank()) {
                rating + "\n\n" + description
            } else {
                rating
            }
        }

        // Extract tags from tag container and add to genres list
        val tags = document.select("div.tag-container a, div.tags a, div.novel-tags a, div.tag a")
            .mapNotNull { it.text().takeIf { t -> t.isNotBlank() } }
        if (tags.isNotEmpty()) {
            genresList.addAll(tags)
        }

        // Build final genre string from collected genres and tags, deduplicated
        val finalGenres = genresList.map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString()
        if (finalGenres.isNotBlank()) {
            genre = finalGenres
        }

        // Set alternative titles if they differ from main title
        val filteredAltNames = altNames.filter { it.lowercase() != normalizedTitle }
        if (filteredAltNames.isNotEmpty()) {
            setAltTitles(filteredAltNames)
        }
    }

    private fun parseStatus(status: String): Int = when {
        status.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
        status.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
        status.contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS
        status.contains("Dropped", ignoreCase = true) -> SManga.CANCELLED
        status.contains("Cancelled", ignoreCase = true) -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // ======================== Chapters ========================

    // Number of chapter-list pages, read from the #indexselect page picker.
    protected open fun chapterListPageCount(detailDoc: Document): Int = detailDoc.select("#indexselect option").size.coerceAtLeast(1)

    // Site-reported total chapter count, used by paginatedChapterList to skip work when nothing
    // changed. Derived from the last #indexselect option's upper bound (e.g. "2321-2334" -> 2334).
    // 0 = unknown (no short-circuit).
    protected open fun siteChapterTotal(detailDoc: Document): Int = detailDoc.select("#indexselect option").lastOrNull()?.text()
        ?.let { Regex("""(\d+)\D*$""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        ?: 0

    // Request for page [page] of the chapter list. Page 1 is the novel page itself.
    protected open fun chapterListPageRequest(manga: SManga, page: Int): Request {
        val path = mangaPathTemplate.absolute(baseUrl, manga.url).trimEnd('/')
        val url = if (page <= 1) path else "$path/$page"
        return GET(url, headers)
    }

    protected open fun chapterPageSelector(): String = "#idData li a, div.m-newest2 ul.ul-list5 li a"

    // Parse one paginated chapter-list page in document order (numbering/reversal done by caller).
    protected open fun parseChapterPage(document: Document): List<SChapter> = document.select(chapterPageSelector()).mapNotNull { element ->
        val chapterUrl = element.attr("abs:href")
        if (chapterUrl.isBlank()) return@mapNotNull null
        SChapter.create().apply {
            setUrlWithoutDomain(chapterUrl)
            name = element.attr("title").ifEmpty { element.text() }
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) mangaDetailsParse(client.newCall(GET(mangaPathTemplate.absolute(baseUrl, manga.url), headers)).execute().asJsoup()) else manga
        val updatedChapters = if (fetchChapters) fetchReadNovelFullChapterList(manga, chapters) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private suspend fun fetchReadNovelFullChapterList(manga: SManga, existingChapters: List<SChapter>): List<SChapter> {
        if (!chaptersPaginated) {
            return parseChapterListResponse(client.newCall(GET(mangaPathTemplate.absolute(baseUrl, manga.url), headers)).execute())
        }

        val detailDoc = fetchChapterListPage(manga, 1)
        val pageCount = chapterListPageCount(detailDoc)

        // Fast mode (default): synthesize the list from the latest chapter number and a stable chapter
        // url pattern, skipping the per-page index fetches. Only when the source provides a pattern.
        if (!accurateChapters) {
            synthesizeChapters(manga, siteChapterTotal(detailDoc))?.let { return it }
        }

        val chapters = paginatedChapterList(
            existingChapters = existingChapters,
            siteTotal = siteChapterTotal(detailDoc),
            assumedPageSize = chapterListPageSize,
            sortChapters = { it },
            fetchPage = { page ->
                val doc = if (page == 1) detailDoc else fetchChapterListPage(manga, page)
                Pair(parseChapterPage(doc), page < pageCount)
            },
        )
        // Number each chapter from its own sequence number and present newest-first, independent of
        // the order pages arrive in (which varies by site and across the incremental merge).
        chapters.forEach { it.chapter_number = chapterSequenceNumber(it) }
        return chapters.sortedByDescending { it.chapter_number }
    }

    // Chapter sequence number taken from the trailing number of the chapter url (e.g. /chapter-3753),
    // falling back to the first number in the name. Used to order the accurate list deterministically.
    protected open fun chapterSequenceNumber(chapter: SChapter): Float {
        CHAPTER_URL_NUMBER.find(chapter.url)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it }
        return CHAPTER_NAME_NUMBER.find(chapter.name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
    }

    // Fetch one chapter-list page, failing loudly on a non-success response so a throttled/blocked
    // page is never parsed as an empty list (which would silently drop a block of chapters).
    private fun fetchChapterListPage(manga: SManga, page: Int): Document {
        val response = client.newCall(chapterListPageRequest(manga, page)).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw Exception("Failed to load chapter list page $page (HTTP $code)")
        }
        return response.asJsoup()
    }

    private val accurateChapters get() = preferences.getBoolean(PREF_ACCURATE_CHAPTERS, false)

    // Domain-relative url of chapter [number] when the site has a stable pattern (e.g.
    // /<slug>/chapter-N). Return null (default) to disable fast mode and always page through the list.
    protected open fun chapterUrlFromNumber(manga: SManga, number: Int): String? = null

    private fun synthesizeChapters(manga: SManga, total: Int): List<SChapter>? {
        if (total <= 0 || chapterUrlFromNumber(manga, total) == null) return null
        return (total downTo 1).map { number ->
            SChapter.create().apply {
                url = chapterUrlFromNumber(manga, number) ?: return null
                name = "Chapter $number"
                chapter_number = number.toFloat()
            }
        }
    }

    protected open fun parseChapterListResponse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val novelPath = response.request.url.encodedPath

        // Try to get chapters from AJAX endpoint
        if (!noAjax) {
            val novelId = document.selectFirst("div#rating")?.attr("data-novel-id")
                ?: novelPath.replace(Regex("[^0-9]"), "").takeIf { it.isNotEmpty() }

            if (novelId != null && chapterListing != null) {
                try {
                    val ajaxUrl = "$baseUrl/$chapterListing?$chapterParam=$novelId"
                    val ajaxResponse = client.newCall(GET(ajaxUrl, headers)).execute()
                    val ajaxDocument = ajaxResponse.asJsoup()

                    val chapters = ajaxDocument.select("ul.list-chapter li a, select option[value]").mapIndexedNotNull { index, element ->
                        val chapterUrl = if (element.tagName() == "option") {
                            element.attr("value")
                        } else {
                            element.attr("abs:href")
                        }

                        // Skip if URL is empty
                        if (chapterUrl.isBlank()) return@mapIndexedNotNull null

                        SChapter.create().apply {
                            setUrlWithoutDomain(chapterUrl)
                            name = if (element.tagName() == "option") {
                                element.text().ifEmpty { "Chapter ${index + 1}" }
                            } else {
                                element.attr("title").ifEmpty { element.text() }
                            }
                            chapter_number = (index + 1).toFloat()
                        }
                    }

                    if (chapters.isNotEmpty()) {
                        return chapters.reversed()
                    }
                } catch (e: Exception) {
                    // Fall back to parsing from page
                }
            }
        }

        // Parse chapters directly from page (noAjax mode or fallback)
        return document.select("ul#idData li a, div.chapter-list a, ul.list-chapter li a").mapIndexedNotNull { index, element ->
            val chapterUrl = element.attr("abs:href")
            // Skip if URL is empty
            if (chapterUrl.isBlank()) return@mapIndexedNotNull null

            SChapter.create().apply {
                setUrlWithoutDomain(chapterUrl)
                name = element.attr("title").ifEmpty { element.text() }
                chapter_number = (index + 1).toFloat()
            }
        }.reversed()
    }

    override fun getMangaUrl(manga: SManga): String = mangaPathTemplate.absolute(baseUrl, manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga {
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
        return mangaDetailsParse(doc).apply { this.url = mangaPathTemplate.slug(url.encodedPath) }
    }

    // ======================== Pages ========================

    // Novel: single text page fetched once in fetchPageText. The app's getPageList short-circuit
    // returns the stub without calling this, so it never double-fetches.
    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, baseUrl + chapter.url))

    // ======================== Novel Content ========================

    override suspend fun fetchPageText(page: Page): String {
        val pageUrl = if (page.url.startsWith("http")) page.url else baseUrl + page.url
        val response = client.newCall(GET(pageUrl, headers)).execute()
        val document = response.asJsoup()

        // Try multiple selectors for chapter content
        val contentSelectors = listOf(
            "div#chr-content",
            "div#chr-content.chr-c",
            "div#chapter-content",
            "div#article",
            "div.txt",
            "div.chapter-content",
            "div.content",
        )

        for (selector in contentSelectors) {
            val content = document.selectFirst(selector)
            if (content != null) {
                if (preferences.getBoolean(PREF_RAW_HTML, false)) {
                    var raw = content.html()
                    // Remove obfuscated freewebnovel watermarks (e.g., free𝑤𝑒𝑏novel.com)
                    raw = raw.replace(Regex("free.*?novel\\.com", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                    return raw
                }

                // Remove ads and unwanted elements
                content.select("div.ads, div.unlock-buttons, sub, script, ins, .adsbygoogle").remove()

                // Remove any watermark-like text fragments (best-effort)
                var contentHtml = content.html()
                contentHtml = contentHtml.replace(Regex("free.*?novel\\.com", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")

                // Get clean HTML content
                return contentHtml
            }
        }

        // Fallback for pages where content is embedded under article/main containers.
        val fallback = document.selectFirst("article, main") ?: return ""
        fallback.select("script, style, noscript").remove()
        return fallback.html()
    }

    // ======================== Filters ========================

    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("Type filters"),
        TypeFilter(getTypeOptions()),
        Filter.Header("Genre filters"),
        GenreFilter(getGenreList()),
        Filter.Header("Status filters"),
        StatusFilter(),
    )

    private class TypeFilter(typeOptions: List<Pair<String, String>>) :
        Filter.Select<String>(
            "Type",
            typeOptions.map { it.first }.toTypedArray(),
            0,
        ) {
        private val optionValues = typeOptions.map { it.second }

        fun toUriPart() = optionValues.getOrElse(state) { "all" }
    }

    private class GenreFilter(genres: List<Genre>) :
        Filter.Group<GenreCheckBox>(
            "Genres",
            genres.map { GenreCheckBox(it.name, it.id) },
        )

    private class GenreCheckBox(name: String, val id: String) : Filter.CheckBox(name)

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("All", "Ongoing", "Completed"),
            0,
        ) {
        fun toUriPart() = values[state].lowercase()
    }

    protected data class Genre(val name: String, val id: String)

    protected open fun getTypeOptions() = listOf(
        "All" to "all",
        "English" to "english",
        "Japanese" to "japanese",
        "Korean" to "korean",
        "Chinese" to "chinese",
    )

    protected open fun getGenreOptions(): List<Pair<String, String>> = emptyList()

    protected open fun getGenreList(): List<Genre> {
        val legacyGenreOptions = getGenreOptions()
            .mapNotNull { (name, rawId) ->
                val normalizedId = normalizeLegacyGenreId(rawId)
                if (normalizedId.isBlank()) null else Genre(name, normalizedId)
            }

        if (legacyGenreOptions.isNotEmpty()) {
            return legacyGenreOptions
        }

        return listOf(
            Genre("Action", "action"),
            Genre("Adult", "adult"),
            Genre("Adventure", "adventure"),
            Genre("Comedy", "comedy"),
            Genre("Drama", "drama"),
            Genre("Eastern", "eastern"),
            Genre("Ecchi", "ecchi"),
            Genre("Fantasy", "fantasy"),
            Genre("Game", "game"),
            Genre("Gender Bender", "gender-bender"),
            Genre("Harem", "harem"),
            Genre("Historical", "historical"),
            Genre("Horror", "horror"),
            Genre("Josei", "josei"),
            Genre("Lolicon", "lolicon"),
            Genre("Martial Arts", "martial-arts"),
            Genre("Mature", "mature"),
            Genre("Mecha", "mecha"),
            Genre("Modern Life", "modern-life"),
            Genre("Mystery", "mystery"),
            Genre("Psychological", "psychological"),
            Genre("Reincarnation", "reincarnation"),
            Genre("Romance", "romance"),
            Genre("School Life", "school-life"),
            Genre("Sci-fi", "sci-fi"),
            Genre("Seinen", "seinen"),
            Genre("Shoujo", "shoujo"),
            Genre("Shounen", "shounen"),
            Genre("Slice of Life", "slice-of-life"),
            Genre("Smut", "smut"),
            Genre("Sports", "sports"),
            Genre("Supernatural", "supernatural"),
            Genre("System", "system"),
            Genre("Thriller", "thriller"),
            Genre("Tragedy", "tragedy"),
            Genre("Transmigration", "transmigration"),
            Genre("Wuxia", "wuxia"),
            Genre("Xianxia", "xianxia"),
            Genre("Xuanhuan", "xuanhuan"),
            Genre("Yaoi", "yaoi"),
            Genre("Yuri", "yuri"),
        )
    }

    private fun normalizeLegacyGenreId(rawId: String): String {
        val trimmed = rawId.trim()
        if (trimmed.isEmpty()) return ""

        val suffix = trimmed.substringAfterLast('/').substringAfterLast('=')
        return suffix
            .replace("+", "-")
            .replace("_", "-")
            .lowercase()
    }

    // ======================== Settings ========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val rawHtmlPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_RAW_HTML
            title = "Return raw HTML"
            summary = "If enabled, returns the raw HTML of the chapter content instead of parsed text. Useful for custom parsers."
            setDefaultValue(false)
        }
        screen.addPreference(rawHtmlPref)

        if (chaptersPaginated) {
            screen.addPreference(
                SwitchPreferenceCompat(screen.context).apply {
                    key = PREF_ACCURATE_CHAPTERS
                    title = "Accurate chapter list"
                    summary = "Fetch every index page for real chapter titles. Slower on long novels. " +
                        "When off (default), the list is built quickly from the latest chapter number."
                    setDefaultValue(false)
                },
            )
        }
    }

    companion object {
        private const val PREF_RAW_HTML = "pref_raw_html"
        private const val PREF_ACCURATE_CHAPTERS = "pref_accurate_chapters"
        private val DATE_FORMAT = SimpleDateFormat("MMM dd, yyyy", Locale.US)
        private val CHAPTER_URL_NUMBER = Regex("""(\d+)\D*$""")
        private val CHAPTER_NAME_NUMBER = Regex("""(\d+(?:\.\d+)?)""")
    }
}

package eu.kanade.tachiyomi.novelextension.ar.sunovels

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

@Source
abstract class Sunovels :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    override val supportsLatest = true

    /** [SManga.url] stored as bare slug under "/novel/"; a stored value starting with "/" is a
     * pre-existing full-path entry and is resolved unchanged. */
    private val mangaPath = SlugPath("/novel/")

    override suspend fun getPopularManga(page: Int): MangasPage = parsePopularOrLatestResponse(client.get("$baseUrl/library?page=$page", headers))

    private fun parsePopularOrLatestResponse(response: Response): MangasPage {
        val body = response.body?.string() ?: return MangasPage(emptyList(), false)
        val doc = Jsoup.parse(body, response.request.url.toString())
        val novels = mutableListOf<SManga>()

        // Extract per-novel data from RSC: each list-item has href + src + title together
        val listItemPattern = Regex(
            """"list-item","children".*?"href":"/novel/([^"]+)".*?"src":"/uploads/([^"]+)".*?"children":"([^"]*[؀-ۿ][^"]*)"""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val rscBody = extractRscBody(body)
        listItemPattern.findAll(rscBody).forEach { match ->
            val slug = match.groupValues[1]
            val src = "/uploads/${match.groupValues[2]}"
            val title = match.groupValues[3].trim()
            if (novels.any { it.url == mangaPath.slug("/novel/$slug") }) return@forEach
            if (title.isBlank()) return@forEach
            novels.add(
                SManga.create().apply {
                    url = mangaPath.slug("/novel/$slug")
                    this.title = title
                    thumbnail_url = src
                },
            )
        }

        // Fallback: Parse regular HTML if RSC parsing found nothing
        if (novels.isEmpty()) {
            doc.select("li.list-item").forEach { item ->
                val link = item.selectFirst("a[href*=/novel/]") ?: return@forEach
                val title = item.selectFirst("h4")?.text() ?: return@forEach
                val slug = link.attr("href").removePrefix("/novel/")
                if (novels.any { it.url == mangaPath.slug(link.attr("href")) }) return@forEach
                val realImg = findImageForSlug(body, slug)
                novels.add(
                    SManga.create().apply {
                        url = mangaPath.slug(link.attr("href"))
                        this.title = title
                        thumbnail_url = realImg
                    },
                )
            }
        }

        val hasNextPage = doc.selectFirst("li.next:not(.disabled)") != null
        return MangasPage(novels.distinctBy { it.url }, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = parsePopularOrLatestResponse(client.get("$baseUrl/library?page=$page&sort=latest", headers))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val response = client.get("$baseUrl/search/?title=$q&page=$page", headers)
        val body = response.body?.string() ?: return MangasPage(emptyList(), false)
        val doc = Jsoup.parse(body, response.request.url.toString())
        val novels = mutableListOf<SManga>()

        // Parse from RSC data (search results are in RSC, not regular HTML)
        val rscBody = extractRscBody(body)
        val listItemPattern = Regex(
            """"list-item","children".*?"href":"/novel/([^"]+)".*?"src":"/uploads/([^"]+)".*?"children":"([^"]*[؀-ۿ][^"]*)"""",
            RegexOption.DOT_MATCHES_ALL,
        )
        listItemPattern.findAll(rscBody).forEach { match ->
            val slug = match.groupValues[1]
            val src = "/uploads/${match.groupValues[2]}"
            val title = match.groupValues[3].trim()
            if (novels.any { it.url == mangaPath.slug("/novel/$slug") }) return@forEach
            if (title.isBlank()) return@forEach
            novels.add(
                SManga.create().apply {
                    url = mangaPath.slug("/novel/$slug")
                    this.title = title
                    thumbnail_url = src
                },
            )
        }

        // Fallback: Parse regular HTML
        if (novels.isEmpty()) {
            doc.select("li.list-item").forEach { item ->
                val link = item.selectFirst("a[href*=/novel/]") ?: return@forEach
                val title = item.selectFirst("h4")?.text() ?: return@forEach
                val slug = link.attr("href").removePrefix("/novel/")
                if (novels.any { it.url == mangaPath.slug(link.attr("href")) }) return@forEach
                val realImg = findImageForSlug(body, slug)
                novels.add(
                    SManga.create().apply {
                        url = mangaPath.slug(link.attr("href"))
                        this.title = title
                        thumbnail_url = realImg
                    },
                )
            }
        }

        // Check for next page
        val hasNextPage = Regex(""""page":(\d+)"""").findAll(rscBody).any {
            it.groupValues[1].toIntOrNull()?.let { p -> p > 1 } == true
        } || doc.selectFirst("li.next:not(.disabled)") != null

        return MangasPage(novels, hasNextPage)
    }

    private fun buildMangaDetailsRequest(manga: SManga): Request = GET(mangaPath.absolute(baseUrl, manga.url), headers)
    private fun buildChapterListRequest(manga: SManga): Request = GET(mangaPath.absolute(baseUrl, manga.url) + "?activeTab=chapters", headers)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = if (fetchDetails) {
            async {
                val request = buildMangaDetailsRequest(manga)
                parseMangaDetails(client.get(request.url, request.headers))
            }
        } else {
            null
        }
        val chaptersDeferred = if (fetchChapters) {
            async {
                val request = buildChapterListRequest(manga)
                parseChapterList(client.get(request.url, request.headers))
            }
        } else {
            null
        }

        SMangaUpdate(
            manga = detailsDeferred?.await() ?: manga,
            chapters = chaptersDeferred?.await() ?: chapters,
        )
    }

    private fun parseMangaDetails(response: Response): SManga {
        val body = response.body?.string() ?: return SManga.create()
        val doc = Jsoup.parse(body, response.request.url.toString())
        return SManga.create().apply {
            val novelH1 = doc.selectFirst(".info h1, .novel-header h1, .main-head h1")
            val novelH3 = doc.selectFirst(".info h3, .novel-header h3, .main-head h3")
            title = novelH3?.text()?.ifEmpty { null }
                ?: novelH1?.text()?.ifEmpty { null }
                ?: doc.selectFirst("meta[property=og:title]")
                    ?.attr("content")
                    ?.removePrefix("رواية ")
                    ?.substringBefore(" | شمس الروايات")
                    ?.substringBefore(" | Sunovels")
                    ?.trim()
                ?: doc.title()
                    .removePrefix("رواية ")
                    .substringBefore(" | شمس الروايات")
                    .substringBefore(" | Sunovels")
                    .trim()
            status = when {
                doc.selectFirst(".top.Ongoing, .Ongoing") != null -> SManga.ONGOING
                doc.selectFirst(".top.Completed, .Completed") != null -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            val imgMatch = Regex("\"image\":\"([^\"]*)\"").find(body)
            thumbnail_url = imgMatch?.groupValues?.get(1)?.let {
                if (it.startsWith("/")) "$baseUrl$it" else it
            } ?: doc.selectFirst("figure.cover img, .img-container img")?.attr("src")?.let {
                if (it.startsWith("/")) "$baseUrl$it" else it
            }
            genre = doc.select(".tag, .tags a.tag")
                .mapNotNull { it.text().takeIf { t -> t.isNotEmpty() } }
                .distinct()
                .joinToString()
            description = doc.selectFirst(".description p, .description")?.text()
                ?: doc.selectFirst("meta[property=og:description]")
                    ?.attr("content")?.trim()
                ?: ""
        }
    }

    private fun parseChapterList(response: Response): List<SChapter> {
        val body = response.body?.string() ?: return emptyList()
        val slug = response.request.url.encodedPath.substringAfter("/novel/").substringBefore("?")
        val novelUrl = "${response.request.url.scheme}://${response.request.url.host}/novel/$slug"
        val chapters = mutableListOf<SChapter>()

        // Parse first page chapters (default = page 0 = chapters 1-50)
        parseChaptersFromHtml(body, slug, chapters, novelUrl)

        // Extract total pages
        val totalPages = extractTotalPages(body)
        if (totalPages <= 1) return chapters.sortedByDescending { it.chapter_number }

        // Fetch remaining pages in parallel with retry
        val pagesToFetch = (1 until totalPages).toMutableList()
        val concurrency = 5
        val maxRetries = 2

        for (attempt in 0..maxRetries) {
            if (pagesToFetch.isEmpty()) break
            val failedPages = mutableListOf<Int>()
            for (batch in pagesToFetch.chunked(concurrency)) {
                val futures = batch.map { page ->
                    Thread {
                        try {
                            val pageUrl = "$novelUrl?activeTab=chapters&page=$page"
                            val pageResponse = client.newCall(GET(pageUrl, headers)).execute()
                            val pageBody = pageResponse.body?.string() ?: return@Thread
                            synchronized(chapters) {
                                val before = chapters.size
                                parseChaptersFromHtml(pageBody, slug, chapters, novelUrl)
                                if (chapters.size == before) {
                                    synchronized(failedPages) { failedPages.add(page) }
                                }
                            }
                        } catch (_: Exception) {
                            synchronized(failedPages) { failedPages.add(page) }
                        }
                    }
                }
                futures.forEach { it.start() }
                futures.forEach { it.join() }
            }
            pagesToFetch.clear()
            pagesToFetch.addAll(failedPages)
        }

        return chapters.sortedByDescending { it.chapter_number }
    }

    private fun parseChaptersFromHtml(body: String, slug: String, chapters: MutableList<SChapter>, docUrl: String) {
        val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)

        // Method 1: Plain HTML links
        val doc = Jsoup.parse(body, docUrl)
        doc.select("a[href*=/novel/$slug/]").forEach { link ->
            val href = link.attr("href")
            if (href.isEmpty()) return@forEach
            val chapterNum = Regex("/novel/$slug/(\\d+)").find(href)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: return@forEach
            if (chapters.any { it.chapter_number == chapterNum }) return@forEach
            val locked = link.selectFirst("svg[data-icon=lock]") != null
            if (locked && !showLocked) return@forEach
            val title = link.selectFirst("span, strong")?.text()
                ?: link.text()
            chapters.add(
                SChapter.create().apply {
                    url = "/novel/$slug/${chapterNum.toInt()}"
                    name = (if (locked) "🔒 " else "") + title.ifEmpty { "الفصل ${chapterNum.toInt()}" }
                    chapter_number = chapterNum
                },
            )
        }
        // Method 2: Unescaped RSC data - each chapter block is
        // "href":"/novel/<slug>/<n>","prefetch":...,"title":"<name>" ... a lock-status svg
        // (data-icon "lock" or "lock-open") before the next chapter's href. The title itself
        // isn't a fixed shape - early/untitled chapters use a bare "<n> <word>" placeholder,
        // later ones a real "<word> <n> - <name>" title, so match any string value.
        // Scoped to start at the chapters list itself - the page also has a "continue reading"
        // widget referencing one arbitrary chapter earlier in the RSC body, which would otherwise
        // steal the first real chapter's title/lock-icon match (its own href has no title/icon
        // nearby, so the non-greedy match skips ahead into the real list to find one).
        val rscBody = extractRscBody(body).let { it.substringAfter("chaptersList", it) }
        val chapterBlockPattern = Regex(
            """"href":"/novel/$slug/(\d+)"[^}]*?"title":"([^"]+)".*?"data-icon":"(lock(?:-open)?)"""",
            RegexOption.DOT_MATCHES_ALL,
        )

        for (match in chapterBlockPattern.findAll(rscBody)) {
            val num = match.groupValues[1].toFloatOrNull() ?: continue
            if (chapters.any { it.chapter_number == num }) continue
            val locked = match.groupValues[3] == "lock"
            if (locked && !showLocked) continue
            val title = match.groupValues[2]
            chapters.add(
                SChapter.create().apply {
                    url = "/novel/$slug/${num.toInt()}"
                    name = (if (locked) "🔒 " else "") + title.ifEmpty { "الفصل ${num.toInt()}" }
                    chapter_number = num
                },
            )
        }
    }

    private fun extractTotalPages(body: String): Int {
        // Try RSC data first
        val rscMatch = Regex(""""totalPages\\?":(\d+)""").find(body)
        if (rscMatch != null) return rscMatch.groupValues[1].toIntOrNull() ?: 1

        // Try parsing unescaped RSC
        val rscBody = extractRscBody(body)
        val rscMatch2 = Regex(""""totalPages":(\d+)""").find(rscBody)
        if (rscMatch2 != null) return rscMatch2.groupValues[1].toIntOrNull() ?: 1

        // Fallback: parse pagination from HTML
        val doc = Jsoup.parse(body)
        val pageLinks = doc.select("ul.pagination li a")
        var maxPage = 1
        pageLinks.forEach { link ->
            val num = Regex("Page (\\d+)").find(link.attr("aria-label"))
                ?.groupValues?.get(1)?.toIntOrNull()
            if (num != null && num > maxPage) maxPage = num
        }
        return maxPage
    }

    override fun getMangaUrl(manga: SManga): String = mangaPath.absolute(baseUrl, manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = mangaPath.slug(url.encodedPath)
        val manga = SManga.create().apply { this.url = path }
        val request = buildMangaDetailsRequest(manga)
        val response = client.get(request.url, request.headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        return parseMangaDetails(response).apply { this.url = path }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(baseUrl + chapter.url, headers)
        return listOf(Page(0, response.request.url.encodedPath))
    }

    override suspend fun fetchPageText(page: Page): String {
        val response = client.get("$baseUrl${page.url}", headers)
        val doc = response.asJsoup()
        val content = doc.selectFirst(
            ".chapter-content, .content, .entry-content, .post-content, article, .text",
        ) ?: return ""
        // Remove hidden watermark elements (d-none class contains anti-scraping hashes)
        content.select("p.d-none, .d-none").remove()
        // Remove ads, navigation, and other non-content elements, plus the Play
        // Store/App Store download badges the app embeds inline in the chapter body.
        content.select(
            "script, style, .ads, .navigation, .chapter-nav, " +
                ".social-share, .comments, nav, footer, " +
                "a[href*=play.google.com], a[href*=apps.apple.com]",
        ).remove()
        return content.html().trim()
    }

    /**
     * Extract and concatenate all RSC flight data into a single string for easy searching.
     */
    private fun extractRscBody(html: String): String = buildString {
        val pattern = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""")
        pattern.findAll(html).forEach { match ->
            val raw = match.groupValues[1]
            append(
                raw.replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t"),
            )
            append("\n")
        }
    }

    /**
     * Find the image URL for a given novel slug from the raw HTML body.
     * Searches RSC flight data for a matching src/href pair.
     */
    private fun findImageForSlug(html: String, slug: String): String? {
        val rscBody = extractRscBody(html)
        val idx = rscBody.indexOf("/novel/$slug")
        if (idx < 0) return null
        // Search nearby for the image src
        val searchRange = rscBody.substring(
            maxOf(0, idx - 500),
            minOf(rscBody.length, idx + 500),
        )
        val srcMatch = Regex(""""src":"/uploads/([^"]+)"""").find(searchRange)
        return srcMatch?.groupValues?.get(1)?.let { "/uploads/$it" }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Include premium/locked chapters in the chapter list."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_SHOW_LOCKED = "pref_show_locked_chapters"
    }
}

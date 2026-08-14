package eu.kanade.tachiyomi.novelextension.ar.sunovels

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

@Source
abstract class Sunovels :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    /** [SManga.url] stored as bare slug under "/novel/"; a stored value starting with "/" is a
     * pre-existing full-path entry and is resolved unchanged. */
    private val mangaPath = SlugPath("/novel/")

    override suspend fun getPopularManga(page: Int): MangasPage = parsePopularOrLatestResponse(client.newCall(GET("$baseUrl/library?page=$page", headers)).execute())

    private fun parsePopularOrLatestResponse(response: Response): MangasPage {
        val body = response.body?.string() ?: return MangasPage(emptyList(), false)
        val doc = Jsoup.parse(body)
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
                val title = item.selectFirst("h4")?.text()?.trim() ?: return@forEach
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

    override suspend fun getLatestUpdates(page: Int): MangasPage = parsePopularOrLatestResponse(client.newCall(GET("$baseUrl/library?page=$page&sort=latest", headers)).execute())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val response = client.newCall(GET("$baseUrl/search/?title=$q&page=$page", headers)).execute()
        val body = response.body?.string() ?: return MangasPage(emptyList(), false)
        val doc = Jsoup.parse(body)
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
                val title = item.selectFirst("h4")?.text()?.trim() ?: return@forEach
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

    private fun buildMangaDetailsRequest(manga: SManga): Request = GET(baseUrl + mangaPath.resolve(manga.url), headers)
    private fun buildChapterListRequest(manga: SManga): Request = GET(baseUrl + mangaPath.resolve(manga.url) + "?activeTab=chapters", headers)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = if (fetchDetails) {
            async { parseMangaDetails(client.newCall(buildMangaDetailsRequest(manga)).execute()) }
        } else {
            null
        }
        val chaptersDeferred = if (fetchChapters) {
            async { parseChapterList(client.newCall(buildChapterListRequest(manga)).execute()) }
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
        val doc = Jsoup.parse(body)
        return SManga.create().apply {
            val novelH1 = doc.selectFirst(".info h1, .novel-header h1, .main-head h1")
            val novelH3 = doc.selectFirst(".info h3, .novel-header h3, .main-head h3")
            title = novelH3?.text()?.trim()?.ifEmpty { null }
                ?: novelH1?.text()?.trim()?.ifEmpty { null }
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
                .mapNotNull { it.text().trim().takeIf { t -> t.isNotEmpty() } }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst(".description p, .description")?.text()?.trim()
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
        parseChaptersFromHtml(body, slug, chapters)

        // Extract total pages
        val totalPages = extractTotalPages(body)
        if (totalPages <= 1) return chapters.sortedBy { it.chapter_number }

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
                                parseChaptersFromHtml(pageBody, slug, chapters)
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

        return chapters.sortedBy { it.chapter_number }
    }

    private fun parseChaptersFromHtml(body: String, slug: String, chapters: MutableList<SChapter>) {
        // Method 1: Plain HTML links
        val doc = Jsoup.parse(body)
        doc.select("a[href*=/novel/$slug/]").forEach { link ->
            val href = link.attr("href")
            if (href.isEmpty()) return@forEach
            val chapterNum = Regex("/novel/$slug/(\\d+)").find(href)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: return@forEach
            if (chapters.any { it.chapter_number == chapterNum }) return@forEach
            val title = link.selectFirst("span, strong")?.text()?.trim()
                ?: link.text().trim()
            chapters.add(
                SChapter.create().apply {
                    url = "/novel/$slug/${chapterNum.toInt()}"
                    name = title.ifEmpty { "الفصل ${chapterNum.toInt()}" }
                    chapter_number = chapterNum
                },
            )
        }
        // Method 2: Unescaped RSC data - find href patterns
        val rscBody = extractRscBody(body)
        val hrefPattern = Regex(""""href":"/novel/$slug/(\d+)"""")
        val titlePattern = Regex(""""title":"(\d+ [^"]+)"""")
        val hrefes = hrefPattern.findAll(rscBody).map { it.groupValues[1].toFloatOrNull() }.filterNotNull().toList()
        val titles = titlePattern.findAll(rscBody).map { it.groupValues[1] }.toList()

        for ((i, num) in hrefes.withIndex()) {
            if (chapters.any { it.chapter_number == num }) continue
            val title = titles.getOrElse(i) { "" }
            chapters.add(
                SChapter.create().apply {
                    url = "/novel/$slug/${num.toInt()}"
                    name = title.ifEmpty { "الفصل ${num.toInt()}" }
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

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPath.resolve(manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = mangaPath.slug(url.encodedPath)
        val manga = SManga.create().apply { this.url = path }
        val response = client.newCall(buildMangaDetailsRequest(manga)).execute()
        if (!response.isSuccessful) return null
        return parseMangaDetails(response).apply { this.url = path }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.newCall(GET(baseUrl + chapter.url, headers)).execute()
        return listOf(Page(0, response.request.url.encodedPath))
    }

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET("$baseUrl${page.url}", headers)).execute()
        val doc = Jsoup.parse(response.body.string())
        val content = doc.selectFirst(
            ".chapter-content, .content, .entry-content, .post-content, article, .text",
        ) ?: return ""
        // Remove hidden watermark elements (d-none class contains anti-scraping hashes)
        content.select("p.d-none, .d-none").remove()
        // Remove ads, navigation, and other non-content elements
        content.select(
            "script, style, .ads, .navigation, .chapter-nav, " +
                ".social-share, .comments, nav, footer",
        ).remove()
        return content.html().trim()
    }

    /**
     * Extract and concatenate all RSC flight data into a single string for easy searching.
     */
    private fun extractRscBody(html: String): String {
        val sb = StringBuilder()
        val pattern = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""")
        pattern.findAll(html).forEach { match ->
            val raw = match.groupValues[1]
            sb.append(
                raw.replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t"),
            )
            sb.append("\n")
        }
        return sb.toString()
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
}

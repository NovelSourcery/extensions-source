package eu.kanade.tachiyomi.novelextension.ar.sunovels

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

class Sunovels :
    HttpSource(),
    NovelSource {

    override val name = "Sunovels"
    override val baseUrl = "https://sunovels.com"
    override val lang = "ar"
    override val supportsLatest = true
    override val isNovelSource = true
    override val client = network.client

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/library?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body?.string() ?: return MangasPage(emptyList(), false)
        val doc = Jsoup.parse(body)
        val novels = mutableListOf<SManga>()

        // Extract per-novel data from RSC: each list-item has href + src + title together
        val listItemPattern = Regex(
            """"list-item","children".*?"href":"/novel/([^"]+)".*?"src":"/uploads/([^"]+)".*?"children":"([^"]*[\u0600-\u06FF][^"]*)"""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val rscBody = extractRscBody(body)
        listItemPattern.findAll(rscBody).forEach { match ->
            val slug = match.groupValues[1]
            val src = "/uploads/${match.groupValues[2]}"
            val title = match.groupValues[3].trim()
            if (novels.any { it.url == "/novel/$slug" }) return@forEach
            if (title.isBlank()) return@forEach
            novels.add(
                SManga.create().apply {
                    url = "/novel/$slug"
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
                if (novels.any { it.url == link.attr("href") }) return@forEach
                val slug = link.attr("href").removePrefix("/novel/")
                val realImg = findImageForSlug(body, slug)
                novels.add(
                    SManga.create().apply {
                        url = link.attr("href")
                        this.title = title
                        thumbnail_url = realImg
                    },
                )
            }
        }

        val hasNextPage = doc.selectFirst("li.next:not(.disabled)") != null
        return MangasPage(novels.distinctBy { it.url }, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/library?page=$page&sort=latest", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search/?title=$q&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val body = response.body?.string() ?: return MangasPage(emptyList(), false)
        val doc = Jsoup.parse(body)
        val novels = mutableListOf<SManga>()

        // Parse from RSC data (search results are in RSC, not regular HTML)
        val rscBody = extractRscBody(body)
        val listItemPattern = Regex(
            """"list-item","children".*?"href":"/novel/([^"]+)".*?"src":"/uploads/([^"]+)".*?"children":"([^"]*[\u0600-\u06FF][^"]*)"""",
            RegexOption.DOT_MATCHES_ALL,
        )
        listItemPattern.findAll(rscBody).forEach { match ->
            val slug = match.groupValues[1]
            val src = "/uploads/${match.groupValues[2]}"
            val title = match.groupValues[3].trim()
            if (novels.any { it.url == "/novel/$slug" }) return@forEach
            if (title.isBlank()) return@forEach
            novels.add(
                SManga.create().apply {
                    url = "/novel/$slug"
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
                if (novels.any { it.url == link.attr("href") }) return@forEach
                val slug = link.attr("href").removePrefix("/novel/")
                val realImg = findImageForSlug(body, slug)
                novels.add(
                    SManga.create().apply {
                        url = link.attr("href")
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

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body?.string() ?: return SManga.create()
        val doc = Jsoup.parse(body)
        return SManga.create().apply {
            val novelH1 = doc.selectFirst(".info h1, .novel-header h1, .main-head h1")
            val novelH3 = doc.selectFirst(".info h3, .novel-header h3, .main-head h3")
            title = novelH3?.text()?.trim()?.ifEmpty { null }
                ?: novelH1?.text()?.trim()?.ifEmpty { null }
                ?: doc.selectFirst("meta[property=og:title]")
                    ?.attr("content")
                    ?.removePrefix("\u0631\u0648\u0627\u064a\u0629 ")
                    ?.substringBefore(" | \u0634\u0645\u0633 \u0627\u0644\u0631\u0648\u0627\u064a\u0627\u062a")
                    ?.substringBefore(" | Sunovels")
                    ?.trim()
                ?: doc.title()
                    .removePrefix("\u0631\u0648\u0627\u064a\u0629 ")
                    .substringBefore(" | \u0634\u0645\u0633 \u0627\u0644\u0631\u0648\u0627\u064a\u0627\u062a")
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

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl${manga.url}?activeTab=chapters", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
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
                    name = title.ifEmpty { "\u0627\u0644\u0641\u0635\u0644 ${chapterNum.toInt()}" }
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
                    name = title.ifEmpty { "\u0627\u0644\u0641\u0635\u0644 ${num.toInt()}" }
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

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.encodedPath
        return listOf(Page(0, url))
    }

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.newCall(GET("$baseUrl${page.url}", headers)).execute().asJsoup()
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

    override fun imageUrlParse(response: Response): String = ""

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
     * Extract RSC (React Server Components) flight data chunks from the page body.
     * These are embedded in script tags like: self.__next_f.push([1,"..."])
     */
    private fun extractRscChunks(html: String): List<String> {
        val chunks = mutableListOf<String>()
        val pattern = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""")
        pattern.findAll(html).forEach { match ->
            val raw = match.groupValues[1]
            // Unescape JSON string escapes
            val unescaped = raw
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
            chunks.add(unescaped)
        }
        return chunks
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

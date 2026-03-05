package eu.kanade.tachiyomi.multisrc.lightnovelwpnovel

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.setAltTitles
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Base class for LightNovelWP Engine powered novel sites.
 * Handles common parsing and request logic.
 */
open class LightNovelWPNovel(
    override val baseUrl: String,
    override val name: String,
    override val lang: String = "en",
) : HttpSource(),
    NovelSource {

    override val isNovelSource = true

    override val supportsLatest = true
    override val client = network.cloudflareClient

    protected open val seriesPath = "series"

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/$seriesPath?page=$page"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = parseNovels(doc)
        val hasNextPage = doc.selectFirst(".pagination .next, .pagination a.next") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/$seriesPath?page=$page&order=latest"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var url = "$baseUrl/$seriesPath?page=$page"

        if (query.isNotBlank()) {
            url += "&s=${query.replace(" ", "+")}"
        }

        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> {
                    if (filter.state != 0) {
                        url += "&status=${filter.toUriPart()}"
                    }
                }

                is SortFilter -> {
                    if (filter.state != 0) {
                        url += "&order=${filter.toUriPart()}"
                    }
                }

                else -> {}
            }
        }

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    /**
     * Helper function to extract image URL from element with various attribute fallbacks
     */
    protected fun parseImageUrl(element: org.jsoup.nodes.Element?): String? = element?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
        ?: element?.attr("data-src")?.takeIf { it.isNotBlank() }
        ?: element?.attr("src")?.takeIf { it.isNotBlank() }
        ?: element?.attr("data-lazy-srcset")?.takeIf { it.isNotBlank() }?.split(" ")?.firstOrNull()

    protected fun parseNovels(doc: Document): List<SManga> {
        return doc.select("article").mapNotNull { element ->
            try {
                val titleElement = element.selectFirst("a[title]") ?: return@mapNotNull null
                val title = titleElement.attr("title")
                val url = titleElement.attr("href")
                // Try multiple image selectors - ts-post-image is used by LightNovelWP
                val image = element.selectFirst(".ts-post-image img, .ts-post-image, img.ts-post-image, img")
                val cover = parseImageUrl(image) ?: ""

                SManga.create().apply {
                    this.title = title
                    // Ensure URL is relative path
                    this.url = when {
                        url.startsWith(baseUrl) -> url.removePrefix(baseUrl)

                        url.startsWith("http://") || url.startsWith("https://") -> {
                            try {
                                java.net.URI(url).path
                            } catch (e: Exception) {
                                url
                            }
                        }

                        url.startsWith("/") -> url

                        else -> "/$url"
                    }
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
        return SManga.create().apply {
            // Get cover from detail page
            val thumbImg = doc.selectFirst(".thumb img, .thumbook img, img.ts-post-image")
            thumbnail_url = parseImageUrl(thumbImg)

            // Parse title from multiple sources - always set it since it's lateinit
            title = thumbImg?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst(".entry-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.substringBefore(" - ")?.trim()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("title")?.text()
                    ?.substringBefore(" - ")?.trim()?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst(".post-title h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: "Unknown Title"

            description = doc.selectFirst(".entry-content, [itemprop=description]")?.text()?.trim() ?: ""

            val authorElement = doc.select(".spe span:contains(Author), .serl:contains(Author)").first()
            author = authorElement?.nextElementSibling()?.text()?.trim()
                ?: authorElement?.parent()?.text()?.substringAfter("Author")?.replace(":", "")?.trim()
                ?: ""

            val artistElement = doc.select(".spe span:contains(Artist), .serl:contains(Artist)").first()
            artist = artistElement?.nextElementSibling()?.text()?.trim()
                ?: artistElement?.parent()?.text()?.substringAfter("Artist")?.replace(":", "")?.trim()
                ?: ""

            genre = doc.select(".genxed a, .sertogenre a").joinToString(", ") { it.text() }

            // Parse alternative titles from info section
            val altNameText = doc.select(
                ".spe span:contains(Alternative), .spe span:contains(Alt), .infox .alternative, .seriestualt",
            ).firstOrNull()?.let { el ->
                el.nextElementSibling()?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: el.parent()?.text()
                        ?.substringAfter("Alternative")?.substringAfter("Alt")
                        ?.replace(":", "")?.trim()?.takeIf { it.isNotBlank() }
            }
            if (!altNameText.isNullOrBlank()) {
                val titles = altNameText.split(",", ";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                setAltTitles(titles)
                description = buildString {
                    append(description.orEmpty())
                    if (isNotEmpty()) append("\n\n")
                    append("Alternative Titles: ")
                    append(titles.joinToString(", "))
                }.trim()
            }

            status = when {
                doc.select(".sertostat, .spe, .serl").text().contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                doc.select(".sertostat, .spe, .serl").text().contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                doc.select(".sertostat, .spe, .serl").text().contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val chapters = mutableListOf<SChapter>()

        doc.select(".eplister li").forEach { element ->
            try {
                val linkElement = element.selectFirst("a") ?: return@forEach
                val url = linkElement.attr("href")

                // epl-num structure: <div class="epl-num"><span>Ch. 50</span><span><i class="fas fa-lock"></i></span></div>
                // The chapter number is in the FIRST span, second span may contain lock icon
                val eplNumElement = element.selectFirst(".epl-num")
                val chapterNum = eplNumElement?.selectFirst("span")?.text()?.trim()
                    ?: eplNumElement?.ownText()?.trim()
                    ?: ""
                val chapterTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
                val dateStr = element.selectFirst(".epl-date")?.text()?.trim() ?: ""

                // Check for locked status - look for lock icon or price indicator
                val isLocked = element.selectFirst(".fa-lock, .fas.fa-lock, i[class*='lock']") != null ||
                    element.select(".epl-price").text().let {
                        !it.contains("Free", ignoreCase = true) && it.isNotEmpty()
                    } || chapterNum.contains("🔒")

                var name = if (chapterTitle.isNotEmpty()) chapterTitle else "Chapter $chapterNum"
                if (isLocked) {
                    name = "🔒 $name"
                }

                // Extract chapter number from epl-num text (trailing digits)
                val extractedNumber = chapterNum.replace("\uD83D\uDD12", "").trim()
                    .let { Regex("(\\d+)$").find(it)?.groupValues?.get(1)?.toFloatOrNull() }

                chapters.add(
                    SChapter.create().apply {
                        this.url = url.replace(baseUrl, "")
                        this.name = name
                        date_upload = parseDate(dateStr)
                        if (extractedNumber != null) {
                            chapter_number = extractedNumber
                        }
                    },
                )
            } catch (e: Exception) {
                // Skip problematic chapters
            }
        }

        return chapters
    }

    override fun pageListParse(response: Response): List<Page> {
        // Return a page with the chapter URL for fetchPageText
        val url = response.request.url.encodedPath
        return listOf(Page(0, url))
    }

    override fun imageUrlParse(response: Response): String = ""

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val html = response.body.string()
        val doc = Jsoup.parse(html)

        // Remove unwanted elements first
        doc.select(
            ".unlock-buttons, .ads, script, style, .sharedaddy, .code-block, .su-spoiler-title, " +
                "noscript, ins, .adsbygoogle, iframe, [id*=google], [class*=google]",
        ).remove()

        // LightNovelWP sites may have multiple .epcontent.entry-content divs
        // The actual content is usually the one with the most paragraphs
        val contentCandidates = doc.select(".epcontent.entry-content, .epcontent, .entry-content")

        // Find the content div with the most paragraph text
        val bestContent = contentCandidates
            .toList()
            .filter { it.select("p").isNotEmpty() }
            .maxByOrNull { element -> element.select("p").sumOf { p -> p.text().length } }

        if (bestContent != null && bestContent.text().length > 100) {
            return bestContent.html()
        }

        // Fallback: Try other selectors
        val fallbackSelectors = listOf(
            "#chapter-content",
            ".reading-content",
            ".text-left",
            ".chapter-content",
            "article .entry-content",
        )

        for (selector in fallbackSelectors) {
            val content = doc.selectFirst(selector)
            if (content != null && content.text().length > 100) {
                return content.html()
            }
        }

        // Last resort: LNReader regex approach
        val contentMatch = Regex("""<div[^>]*class="epcontent[^"]*"[^>]*>([^]*?)<div[^>]*class="?bottomnav""")
            .find(html)?.groupValues?.get(1)

        if (contentMatch != null) {
            val paragraphs = Regex("""<p[^>]*>([^]*?)</p>""")
                .findAll(contentMatch)
                .map { "<p>${it.groupValues[1]}</p>" }
                .joinToString("\n")
            if (paragraphs.isNotEmpty()) return paragraphs
        }

        return ""
    }

    override fun getFilterList(): FilterList = FilterList(
        StatusFilter(),
        SortFilter(),
    )

    protected fun parseDate(dateStr: String): Long {
        val date = dateStr.trim()
        if (date.isEmpty()) return 0L

        // Try common date formats used by LightNovelWP sites
        val formats = listOf(
            "MMMM dd, yyyy", // January 5, 2025
            "MMM dd, yyyy", // Jan 5, 2025
            "MMMM d, yyyy", // January 5, 2025 (single digit)
            "MMM d, yyyy", // Jan 5, 2025 (single digit)
            "dd MMMM yyyy", // 05 January 2025
            "dd MMM yyyy", // 05 Jan 2025
            "yyyy-MM-dd", // 2025-01-05
            "dd/MM/yyyy", // 05/01/2025
            "MM/dd/yyyy", // 01/05/2025
        )

        for (format in formats) {
            try {
                return SimpleDateFormat(format, Locale.US).parse(date)?.time ?: continue
            } catch (_: ParseException) {
                // Try next format
            }
        }

        return 0L
    }

    protected fun Response.asJsoup(): Document = Jsoup.parse(body.string())

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("All", "Ongoing", "Completed", "Hiatus"),
        ) {
        fun toUriPart() = when (state) {
            0 -> ""
            1 -> "ongoing"
            2 -> "completed"
            3 -> "hiatus"
            else -> ""
        }
    }

    private class SortFilter :
        Filter.Select<String>(
            "Sort",
            arrayOf("Default", "A-Z", "Z-A", "Latest Update", "Latest Added", "Popular", "Rating"),
        ) {
        fun toUriPart() = when (state) {
            0 -> ""
            1 -> "title"
            2 -> "titlereverse"
            3 -> "update"
            4 -> "latest"
            5 -> "popular"
            6 -> "rating"
            else -> ""
        }
    }
}

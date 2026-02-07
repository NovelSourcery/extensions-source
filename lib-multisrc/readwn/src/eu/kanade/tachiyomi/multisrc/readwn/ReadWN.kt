package eu.kanade.tachiyomi.multisrc.readwn

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * ReadWN multisrc base class.
 * Ported from LNReader TypeScript plugin.
 *
 * Sites using this template:
 * - readwn.com
 * - fansmtl.com
 * - wuxiaspace.com
 */
abstract class ReadWN(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
) : ParsedHttpSource(), NovelSource {

    override val isNovelSource = true

    override val supportsLatest = true

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        // /list/all/all-newstime-{page-1}.html
        val url = "$baseUrl/list/all/all-newstime-${page - 1}.html"
        return GET(url, headers)
    }

    override fun popularMangaSelector() = "li.novel-item"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("a[href]")
        if (link != null) {
            val href = link.attr("abs:href")
            if (href.isNotBlank()) {
                setUrlWithoutDomain(href)
            }
        }
        title = element.selectFirst("h4")?.text()?.trim() ?: ""
        thumbnail_url = element.selectFirst(".novel-cover img")?.let {
            val src = it.attr("data-src").ifEmpty { it.attr("src") }
            if (src.startsWith("/")) "$baseUrl$src" else src
        }
    }

    override fun popularMangaNextPageSelector() = ".pagination a.next"

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/list/all/all-lastdotime-${page - 1}.html"
        return GET(url, headers)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // If there's a search query, use POST search
        if (query.isNotBlank()) {
            val body = FormBody.Builder()
                .add("show", "title")
                .add("tempid", "1")
                .add("tbname", "news")
                .add("keyboard", query)
                .build()

            return POST(
                "$baseUrl/e/search/index.php",
                headers.newBuilder()
                    .add("Content-Type", "application/x-www-form-urlencoded")
                    .add("Referer", "$baseUrl/search.html")
                    .add("Origin", baseUrl)
                    .build(),
                body,
            )
        }

        // Otherwise, use filters with list URL
        // URL format: /list/{genre}/{status}-{sort}-{page}.html
        var genre = "all"
        var status = "all"
        var sort = "newstime"

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    if (filter.state > 0) {
                        genre = genreValues[filter.state].lowercase().replace(" ", "-")
                    }
                }
                is StatusFilter -> {
                    if (filter.state > 0) {
                        status = when (filter.state) {
                            1 -> "ongoing"
                            2 -> "completed"
                            else -> "all"
                        }
                    }
                }
                is SortFilter -> {
                    sort = when (filter.state) {
                        0 -> "lastdotime" // Latest
                        1 -> "newstime" // Popular (new)
                        2 -> "allvisit" // Views
                        else -> "newstime"
                    }
                }
                else -> {}
            }
        }

        val url = "$baseUrl/list/$genre/$status-$sort-${page - 1}.html"
        return GET(url, headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector(): String? = null // Search doesn't have pagination

    // ======================== Details ========================

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.novel-title")?.text()?.trim() ?: ""
        author = document.selectFirst("span[itemprop=author]")?.text()?.trim()
        thumbnail_url = document.selectFirst("figure.cover img")?.let {
            val src = it.attr("data-src").ifEmpty { it.attr("src") }
            if (src.startsWith("/")) "$baseUrl$src" else src
        }
        description = document.selectFirst(".summary")?.text()
            ?.replace("Summary", "")?.trim()
        genre = document.select("div.categories ul li").joinToString { it.text().trim() }

        // Get status from header stats
        document.select("div.header-stats span").forEach { span ->
            if (span.selectFirst("small")?.text() == "Status") {
                status = when (span.selectFirst("strong")?.text()?.trim()?.lowercase()) {
                    "ongoing" -> SManga.ONGOING
                    "completed" -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
            }
        }
    }

    // ======================== Chapters ========================

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val novelPath = response.request.url.encodedPath

        // Get the latest chapter number from header stats
        val latestChapterNo = document.selectFirst(".header-stats span strong")
            ?.text()?.trim()?.toIntOrNull() ?: 0

        val chapters = document.select(".chapter-list li").mapIndexed { index, element ->
            SChapter.create().apply {
                element.selectFirst("a")?.let {
                    setUrlWithoutDomain(it.attr("abs:href"))
                }
                name = element.selectFirst("a .chapter-title")?.text()?.trim() ?: "Chapter ${index + 1}"
                chapter_number = (index + 1).toFloat()

                // Parse release time
                val releaseTime = element.selectFirst("a .chapter-update")?.text()?.trim()
                date_upload = releaseTime?.let { parseRelativeDate(it) } ?: 0L
            }
        }.toMutableList()

        // If there are more chapters than listed, generate them
        if (latestChapterNo > chapters.size && chapters.isNotEmpty()) {
            val lastChapterPath = chapters.lastOrNull()?.url ?: novelPath
            val lastChapterNo = lastChapterPath
                .substringAfterLast("_")
                .substringBefore(".html")
                .toIntOrNull() ?: chapters.size

            for (i in (lastChapterNo + 1)..latestChapterNo) {
                chapters.add(
                    SChapter.create().apply {
                        url = novelPath.replace(".html", "_$i.html")
                        name = "Chapter $i"
                        chapter_number = i.toFloat()
                    },
                )
            }
        }

        return chapters
    }

    private fun parseRelativeDate(dateStr: String): Long {
        if (!dateStr.contains("ago")) {
            return try {
                DATE_FORMAT.parse(dateStr)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        }

        val number = dateStr.substringBefore(" ").toIntOrNull() ?: return 0L
        val calendar = Calendar.getInstance()

        when {
            dateStr.contains("hour") -> calendar.add(Calendar.HOUR_OF_DAY, -number)
            dateStr.contains("day") -> calendar.add(Calendar.DAY_OF_MONTH, -number)
            dateStr.contains("month") -> calendar.add(Calendar.MONTH, -number)
            dateStr.contains("year") -> calendar.add(Calendar.YEAR, -number)
        }

        return calendar.timeInMillis
    }

    override fun chapterListSelector() = throw UnsupportedOperationException()

    override fun chapterFromElement(element: Element) = throw UnsupportedOperationException()

    // ======================== Pages ========================

    override fun pageListParse(document: Document): List<Page> {
        return listOf(Page(0, document.location()))
    }

    override fun imageUrlParse(document: Document): String = ""

    // ======================== Novel Content ========================

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(page.url, headers)).execute()
        val document = response.asJsoup()

        return document.selectFirst(".chapter-content")?.html() ?: ""
    }

    // ======================== Filters ========================

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Note: Filters are ignored when searching by text"),
        Filter.Separator(),
        GenreFilter(),
        StatusFilter(),
        SortFilter(),
    )

    protected open val genreValues = arrayOf(
        "All", "Action", "Adult", "Adventure", "Comedy", "Drama",
        "Ecchi", "Fantasy", "Gender Bender", "Harem", "Historical",
        "Horror", "Josei", "Martial Arts", "Mature", "Mecha",
        "Mystery", "Psychological", "Romance", "School Life",
        "Sci-fi", "Seinen", "Shoujo", "Shounen", "Slice of Life",
        "Smut", "Sports", "Supernatural", "Tragedy", "Wuxia", "Xianxia", "Xuanhuan", "Yaoi",
    )

    protected class GenreFilter(genres: Array<String> = defaultGenres) : Filter.Select<String>("Genre", genres, 0) {
        companion object {
            val defaultGenres = arrayOf(
                "All", "Action", "Adult", "Adventure", "Comedy", "Drama",
                "Ecchi", "Fantasy", "Gender Bender", "Harem", "Historical",
                "Horror", "Josei", "Martial Arts", "Mature", "Mecha",
                "Mystery", "Psychological", "Romance", "School Life",
                "Sci-fi", "Seinen", "Shoujo", "Shounen", "Slice of Life",
                "Smut", "Sports", "Supernatural", "Tragedy", "Wuxia", "Xianxia", "Xuanhuan", "Yaoi",
            )
        }
    }

    protected class StatusFilter : Filter.Select<String>(
        "Status",
        arrayOf("All", "Ongoing", "Completed"),
        0,
    )

    protected class SortFilter : Filter.Select<String>(
        "Sort by",
        arrayOf("Latest", "New", "Views"),
        0,
    )

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("MMM dd, yyyy", Locale.US)
    }
}

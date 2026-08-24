package eu.kanade.tachiyomi.multisrc.lightnovelworld

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

open class LightNovelWorld(
    override val name: String,
    override val baseUrl: String,
    override val lang: String = "en",
) : HttpSource(),
    NovelSource {

    override val isNovelSource = true

    override val supportsLatest = true
    override val client = network.cloudflareClient

    // -- Browse --

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/browse/all/popular/all/$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        doc.select(".novel-item.ads").remove()
        val novels = doc.select(".novel-item").mapNotNull { element ->
            val titleEl = element.selectFirst(".novel-title > a") ?: return@mapNotNull null
            SManga.create().apply {
                title = titleEl.text().trim()
                setUrlWithoutDomain(titleEl.attr("href"))
                thumbnail_url = element.selectFirst("img")?.attr("data-src")
            }
        }
        val hasNext = doc.selectFirst(".PagedList-skipToNext a") != null
        return MangasPage(novels, hasNext)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/browse/all/updated/all/$page", headers)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // -- Search --

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            // Fetch the search page to get the verify token, then POST to /lnsearchlive
            val searchPage = client.newCall(GET("$baseUrl/search", headers)).execute().asJsoup()
            val token = searchPage.selectFirst("#novelSearchForm > input")?.attr("value") ?: ""

            val body = FormBody.Builder()
                .add("inputContent", query)
                .build()

            return POST(
                "$baseUrl/lnsearchlive",
                headers.newBuilder().add("LNRequestVerifyToken", token).build(),
                body,
            )
        }
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selectedValue() ?: "all"
        val order = filters.filterIsInstance<OrderFilter>().firstOrNull()?.selectedValue() ?: "popular"
        val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.selectedValue() ?: "all"
        return GET("$baseUrl/browse/$genre/$order/$status/$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val requestUrl = response.request.url.toString()
        if (requestUrl.contains("lnsearchlive")) {
            val json = JSONObject(response.body.string())
            val html = json.optString("resultview", "")
            val doc = Jsoup.parse(html)

            val novels = doc.select(".novel-item").mapNotNull { element ->
                val titleEl = element.selectFirst("h4.novel-title") ?: return@mapNotNull null
                val url = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                SManga.create().apply {
                    title = titleEl.text().trim()
                    setUrlWithoutDomain(url)
                    thumbnail_url = element.selectFirst("img")?.attr("src")
                }
            }
            return MangasPage(novels, false)
        }
        return popularMangaParse(response)
    }

    // -- Details --

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst("h1.novel-title")?.text()?.trim() ?: "Untitled"
            thumbnail_url = doc.selectFirst("figure.cover > img")?.attr("data-src")
            author = doc.selectFirst(".author > a > span")?.text()
            description = doc.selectFirst(".summary > .content")?.text()?.trim()
            genre = doc.select(".categories ul li").joinToString { it.text().trim() }
            status = when (doc.selectFirst(".header-stats span:last-child strong")?.text()?.trim()?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // -- Chapters (paginated) --

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val totalChapters = doc.selectFirst(".header-stats span:first-child strong")
            ?.text()?.trim()?.toIntOrNull() ?: 0
        val totalPages = (totalChapters + 99) / 100
        val novelUrl = response.request.url.encodedPath

        val chapters = mutableListOf<SChapter>()
        for (page in 1..totalPages) {
            val pageDoc = if (page == 1) {
                // Try first page
                val firstPageUrl = "$baseUrl$novelUrl/chapters/page-1"
                client.newCall(GET(firstPageUrl, headers)).execute().asJsoup()
            } else {
                val pageUrl = "$baseUrl$novelUrl/chapters/page-$page"
                client.newCall(GET(pageUrl, headers)).execute().asJsoup()
            }

            pageDoc.select(".chapter-list li").forEach { element ->
                val linkEl = element.selectFirst("a") ?: return@forEach
                val chapterNo = element.selectFirst(".chapter-no")?.text()?.trim() ?: ""
                val chapterTitle = element.selectFirst(".chapter-title")?.text()?.trim() ?: ""
                val dateStr = element.selectFirst(".chapter-update")?.attr("datetime") ?: ""

                chapters.add(
                    SChapter.create().apply {
                        url = linkEl.attr("href")
                        name = if (chapterTitle.isNotEmpty()) {
                            "Chapter $chapterNo - $chapterTitle"
                        } else {
                            "Chapter $chapterNo"
                        }
                        date_upload = try {
                            DATE_FORMAT.parse(dateStr)?.time ?: 0L
                        } catch (_: Exception) {
                            0L
                        }
                        chapter_number = chapterNo.toFloatOrNull() ?: -1f
                    },
                )
            }
        }

        return chapters
    }

    // -- Pages --

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.encodedPath
        return listOf(Page(0, url))
    }

    override fun imageUrlParse(response: Response): String = ""

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = response.asJsoup()
        return doc.selectFirst("#chapter-container")?.html() ?: ""
    }

    // -- Filters --

    override fun getFilterList() = FilterList(
        OrderFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    private class OrderFilter :
        SelectFilter(
            "Order",
            arrayOf(
                "Popular" to "popular",
                "New" to "new",
                "Updated" to "updated",
            ),
        )

    private class StatusFilter :
        SelectFilter(
            "Status",
            arrayOf(
                "All" to "all",
                "Completed" to "completed",
                "Ongoing" to "ongoing",
            ),
        )

    private class GenreFilter :
        SelectFilter(
            "Genre",
            arrayOf(
                "All" to "all",
                "Action" to "action",
                "Adventure" to "adventure",
                "Comedy" to "comedy",
                "Drama" to "drama",
                "Eastern Fantasy" to "eastern-fantasy",
                "Ecchi" to "ecchi",
                "Fantasy" to "fantasy",
                "Fantasy Romance" to "fantasy-romance",
                "Gender Bender" to "gender-bender",
                "Harem" to "harem",
                "Historical" to "historical",
                "Horror" to "horror",
                "Josei" to "josei",
                "Martial Arts" to "martial-arts",
                "Mature" to "mature",
                "Mecha" to "mecha",
                "Mystery" to "mystery",
                "Psychological" to "psychological",
                "Romance" to "romance",
                "School Life" to "school-life",
                "Sci-fi" to "sci-fi",
                "Seinen" to "seinen",
                "Shoujo" to "shoujo",
                "Shoujo Ai" to "shoujo-ai",
                "Shounen" to "shounen",
                "Shounen Ai" to "shounen-ai",
                "Slice of Life" to "slice-of-life",
                "Smut" to "smut",
                "Sports" to "sports",
                "Supernatural" to "supernatural",
                "Tragedy" to "tragedy",
                "Wuxia" to "wuxia",
                "Xianxia" to "xianxia",
                "Xuanhuan" to "xuanhuan",
                "Yaoi" to "yaoi",
                "Yuri" to "yuri",
            ),
        )

    open class SelectFilter(
        name: String,
        private val options: Array<Pair<String, String>>,
    ) : eu.kanade.tachiyomi.source.model.Filter.Select<String>(
        name,
        options.map { it.first }.toTypedArray(),
    ) {
        fun selectedValue(): String = options[state].second
    }

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }
}

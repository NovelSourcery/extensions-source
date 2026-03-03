package eu.kanade.tachiyomi.extension.zh.alicesw

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * AliceSW (çˆ±ä¸½ä¸ä¹¦å±‹) - Chinese novel source
 * https://www.alicesw.com/
 */
class AliceSW :
    HttpSource(),
    NovelSource {

    override val name = "AliceSW (çˆ±ä¸½ä¸ä¹¦å±‹)"
    override val baseUrl = "https://www.alicesw.com"
    override val lang = "zh"
    override val supportsLatest = true

    override val isNovelSource = true

    override val client = network.cloudflareClient

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/all/order/hits+desc.html?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseNovelListPage(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/all/order/update_time+desc.html?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseNovelListPage(response)

    private fun parseNovelListPage(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())
        val novels = mutableListOf<SManga>()

        // Each row has: [number], [category_link], [novel_title_link], [latest_chapter], [author], [word_count], [date]
        // Select all links to novel pages
        document.select("a[href^=/novel/][href$=.html]").forEach { element ->
            val novelUrl = element.attr("href")
            val title = element.text().trim()

            if (novelUrl.isNotBlank() && title.isNotBlank() && !novelUrl.contains("/book/")) {
                // Avoid duplicates
                if (novels.none { it.url == novelUrl }) {
                    novels.add(
                        SManga.create().apply {
                            url = novelUrl
                            this.title = title
                        },
                    )
                }
            }
        }

        val hasNextPage = document.selectFirst("a:contains(ä¸‹ä¸€é¡µ)") != null

        return MangasPage(novels, hasNextPage)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var searchType = "title"
        filters.forEach { filter ->
            when (filter) {
                is SearchTypeFilter -> searchType = filter.toUriPart()
                else -> {}
            }
        }
        return GET("$baseUrl/search.html?q=$query&f=$searchType&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseNovelListPage(response)

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            // Title - from the main h1 (first h1 on page)
            title = document.selectFirst("h1")?.text()?.trim() ?: ""

            // Author - look for "ä½œè€…ï¼š" pattern and get the link after it
            author = document.selectFirst("a[href*=f=author]")?.text()?.trim()

            // Description - text after "å†…å®¹ç®€ä»‹ï¼š" or the main content block
            val bodyText = document.body().text()
            val descStart = bodyText.indexOf("çˆ±ä¸½ä¸ä¹¦å±‹æ‰€æœ‰å°è¯´ä¸­å‡ºçŽ°çš„äººç‰©å‡ä¸º18å²ä»¥ä¸Š")
            val descEnd = bodyText.indexOf("æ ‡ç­¾ï¼š")
            description = if (descStart > 0 && descEnd > descStart) {
                bodyText.substring(descStart, descEnd)
                    .replace("çˆ±ä¸½ä¸ä¹¦å±‹æ‰€æœ‰å°è¯´ä¸­å‡ºçŽ°çš„äººç‰©å‡ä¸º18å²ä»¥ä¸Šçš„æˆäººï¼Œè‹¥æœ‰ä¸å¦¥ä¹‹å¤„ï¼Œä»…æ˜¯æ–‡å­¦åˆ›ä½œæ•ˆæžœ", "")
                    .trim()
            } else {
                document.select("p, div").map { it.ownText().trim() }
                    .filter { it.length > 50 && !it.contains("çˆ±ä¸½ä¸") && !it.contains("Copyright") }
                    .firstOrNull() ?: ""
            }

            var genreList = document.select("a[href*=f=tag]")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .toMutableList()

            // Category from lists links (like ä¹±ä¼¦, éƒ½å¸‚, etc)
            val category = document.select("a[href*=/lists/]")
                .map { it.text().trim() }
                .filter { it.isNotBlank() && !it.contains("é¦–é¡µ") }
                .distinct()
                .firstOrNull()
            if (!category.isNullOrBlank()) {
                genreList.add(0, category)
            }
            genre = genreList.joinToString(", ")

            // Status - look for "å°è¯´çŠ¶æ€ï¼š" pattern or è¿žè½½/å®Œç»“
            val pageText = document.text()
            status = when {
                pageText.contains("è¿žè½½ä¸­") || pageText.contains("è¿žè½½") -> SManga.ONGOING
                pageText.contains("å®Œç»“") || pageText.contains("å®Œæœ¬") || pageText.contains("å·²å®Œç»“") -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            // Thumbnail - from img with src containing cdn or cover
            thumbnail_url = document.selectFirst("img[src*=cdn], img[src*=cover], img[src*=uploads]")?.attr("src")
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val novelId = manga.url.substringAfter("/novel/").substringBefore(".html")
        return GET("$baseUrl/other/chapters/id/$novelId.html", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string())
        val chapters = mutableListOf<SChapter>()

        val chapterElements = document.select("ul.mulu_list li a").ifEmpty {
            document.select(".mulu_list a").ifEmpty {
                document.select("ul li a[href*=/book/]")
            }
        }

        chapterElements.forEachIndexed { index, element ->
            val chapterUrl = element.attr("href")
            val chapterTitle = element.text().trim()

            if (chapterUrl.isNotBlank() && chapterTitle.isNotBlank()) {
                chapters.add(
                    SChapter.create().apply {
                        url = chapterUrl
                        name = chapterTitle
                        chapter_number = (index + 1).toFloat()
                    },
                )
            }
        }

        return chapters
    }

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.toString()
        return listOf(Page(0, url))
    }

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) page.url else baseUrl + page.url
        val response = client.newCall(GET(url, headers)).execute()
        val document = Jsoup.parse(response.body.string())

        val content = document.selectFirst(".j_readContent")
            ?: document.selectFirst(".read-content")
            ?: document.selectFirst("#content")
            ?: document.selectFirst("article")

        return content?.html() ?: ""
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun getFilterList(): FilterList = FilterList(
        SearchTypeFilter(),
        CategoryFilter(),
        SortFilter(),
    )

    private class SearchTypeFilter :
        Filter.Select<String>(
            "Search Type",
            arrayOf("Title", "Author", "Tag"),
        ) {
        fun toUriPart(): String = when (state) {
            1 -> "author"
            2 -> "tag"
            else -> "title"
        }
    }

    private class CategoryFilter :
        Filter.Select<String>(
            "Category",
            arrayOf(
                "All",
                "å¥³ä¸»å°è¯´",
                "ç”·ä¸»å°è¯´",
                "ä¹¡æ‘å°è¯´",
                "åŒäººå°è¯´",
                "ä¹±ä¼¦",
                "å…¶ä»–",
            ),
        )

    private class SortFilter :
        Filter.Select<String>(
            "Sort By",
            arrayOf("Update Time", "Popularity", "Word Count"),
        )
}

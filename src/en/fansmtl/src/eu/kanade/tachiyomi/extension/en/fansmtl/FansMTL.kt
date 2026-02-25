package eu.kanade.tachiyomi.extension.en.fansmtl

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.util.Calendar

/**
 * FansMTL / FanMTL - ReadWN-based novel site
 * URL pattern: /list/{genre}/all-{sort}-{page-1}.html
 * Search: POST to /e/search/index.php (no pagination)
 */
class FansMTL :
    HttpSource(),
    NovelSource {

    override val name = "Fans MTL"
    override val baseUrl = "https://www.fanmtl.com"
    override val lang = "en"
    override val supportsLatest = true
    override val isNovelSource = true

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/list/all/all-newstime-${page - 1}.html", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        val novels = doc.select("li.novel-item").mapNotNull { element ->
            val link = element.selectFirst("a[href*='/novel/']") ?: element.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.attr("href")
            if (href.isBlank() || !href.contains("/novel/")) return@mapNotNull null

            val title = element.selectFirst("h4.novel-title")?.text()?.trim()
                ?: link.attr("title").ifEmpty { null }
                ?: element.selectFirst("img")?.attr("alt")
                ?: return@mapNotNull null

            val img = element.selectFirst("figure.novel-cover img, .cover-wrap img, img.lazy")
            val imgSrc = img?.let { imgEl ->
                // data-src is the real image, src is often a placeholder
                imgEl.attr("data-src").ifEmpty { imgEl.attr("src") }
            } ?: ""

            SManga.create().apply {
                setUrlWithoutDomain(if (href.startsWith("/")) href else "/$href")
                this.title = title
                thumbnail_url = when {
                    imgSrc.isBlank() || imgSrc.contains("placeholder") -> null
                    imgSrc.startsWith("http") -> imgSrc
                    imgSrc.startsWith("/") -> "$baseUrl$imgSrc"
                    else -> "$baseUrl/$imgSrc"
                }
            }
        }

        // Pagination - FansMTL uses 0-indexed pages in URL
        val hasNext = novels.isNotEmpty() && novels.size >= 18 // FansMTL shows ~18 per page
        return MangasPage(novels, hasNext)
    }
    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/list/all/all-lastdotime-${page - 1}.html", headers)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var selectedGenre = "all"
        var selectedStatus = "all"
        var sortBy = "newstime"

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> if (filter.state != 0) selectedGenre = filter.toUriPart()
                is StatusFilter -> if (filter.state != 0) selectedStatus = filter.toUriPart()
                is SortFilter -> sortBy = filter.toUriPart()
                else -> {}
            }
        }

        return if (query.isNotBlank()) {
            // Text search - POST, no pagination
            val body = FormBody.Builder()
                .add("show", "title")
                .add("tempid", "1")
                .add("tbname", "news")
                .add("keyboard", query)
                .build()
            POST(
                "$baseUrl/e/search/index.php",
                headers.newBuilder()
                    .add("Content-Type", "application/x-www-form-urlencoded")
                    .add("Referer", "$baseUrl/search.html")
                    .add("Origin", baseUrl)
                    .build(),
                body,
            )
        } else {
            // Genre browsing with pagination: /list/{genre}/{status}-{sort}-{page-1}.html
            GET("$baseUrl/list/$selectedGenre/$selectedStatus-$sortBy-${page - 1}.html", headers)
        }
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)
    // ======================== Details ========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())
        return SManga.create().apply {
            title = doc.selectFirst("h1.novel-title")?.text()?.trim() ?: ""
            author = doc.selectFirst("span[itemprop=author]")?.text()?.trim()
            thumbnail_url = doc.selectFirst("figure.cover img")?.let { img ->
                val src = img.attr("data-src").ifEmpty { img.attr("src") }
                if (src.startsWith("/")) "$baseUrl$src" else src
            }
            description = doc.selectFirst(".summary")?.text()?.replace("Summary", "")?.trim()
            genre = doc.select("div.categories ul li").joinToString { it.text().trim() }

            doc.select("div.header-stats span").forEach { span ->
                if (span.selectFirst("small")?.text() == "Status") {
                    status = when (span.selectFirst("strong")?.text()?.trim()?.lowercase()) {
                        "ongoing" -> SManga.ONGOING
                        "completed" -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                }
            }
        }
    }
    override fun fetchChapterList(manga: SManga): rx.Observable<List<SChapter>> {
        return rx.Observable.fromCallable {
            val novelSlug = manga.url
                .substringAfterLast("/novel/")
                .substringBefore(".html")

            val allChapters = mutableListOf<SChapter>()
            val seenUrls = mutableSetOf<String>()
            var page = 0

            // Paginate through AJAX endpoint which returns chapters in ascending order (0-indexed)
            while (true) {
                try {
                    val ajaxUrl = "$baseUrl/e/extend/fy.php?page=$page&wjm=$novelSlug"
                    val ajaxRequest = GET(
                        ajaxUrl,
                        headers.newBuilder()
                            .add("X-Requested-With", "XMLHttpRequest")
                            .build(),
                    )
                    val ajaxResponse = client.newCall(ajaxRequest).execute()
                    val ajaxDoc = Jsoup.parse(ajaxResponse.body.string())

                    val items = ajaxDoc.select("a[href*='.html']")
                    if (items.isEmpty()) break

                    var addedAny = false
                    items.forEach { link ->
                        val href = link.attr("href")
                        if (href.isBlank() || !href.contains(".html")) return@forEach

                        val chapterUrl = if (href.startsWith("http")) href.substringAfter(baseUrl) else href
                        if (!seenUrls.add(chapterUrl)) return@forEach

                        // Extract chapter number from data attribute or URL
                        val orderNo = link.attr("data-orderno").toIntOrNull()
                            ?: link.attr("data-chapterno").toIntOrNull()
                            ?: chapterUrl.substringAfterLast("_").substringBefore(".html").toIntOrNull()

                        val chapterNum = orderNo ?: (allChapters.size + 1)

                        allChapters.add(
                            SChapter.create().apply {
                                setUrlWithoutDomain(chapterUrl)
                                name = link.selectFirst(".chapter-title")?.text()?.trim()
                                    ?: link.text().trim().ifEmpty { "Chapter $chapterNum" }
                                chapter_number = chapterNum.toFloat()
                            },
                        )
                        addedAny = true
                    }

    // ======================== Chapters ========================

                    if (!addedAny) break
                    page++
                } catch (e: Exception) {
                    break
                }
            }

            // If AJAX returned nothing, fall back to detail page chapter list
            if (allChapters.isEmpty()) {
                val novelUrl = "$baseUrl${manga.url}"
                val detailResponse = client.newCall(GET(novelUrl, headers)).execute()
                val doc = Jsoup.parse(detailResponse.body.string())

                doc.select(".chapter-list li").forEachIndexed { index, element ->
                    val link = element.selectFirst("a") ?: return@forEachIndexed
                    val href = link.attr("href")
                    if (href.isBlank()) return@forEachIndexed

                    val chapterNum = index + 1
                    allChapters.add(
                        SChapter.create().apply {
                            setUrlWithoutDomain(if (href.startsWith("http")) href.substringAfter(baseUrl) else href)
                            name = link.selectFirst(".chapter-title")?.text()?.trim() ?: "Chapter $chapterNum"
                            chapter_number = chapterNum.toFloat()
                            val releaseTime = link.selectFirst(".chapter-update")?.text()?.trim()
                            date_upload = releaseTime?.let { parseRelativeDate(it) } ?: 0L
                        },
                    )
                }
            }

            // AJAX returns ascending order; reverse for newest-first
            allChapters.reversed()
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())
        val novelPath = response.request.url.encodedPath

        val latestChapterNo = doc.selectFirst(".header-stats span strong")
            ?.text()?.trim()?.toIntOrNull() ?: 0

        val chapters = doc.select(".chapter-list li").mapIndexed { index, element ->
            SChapter.create().apply {
                element.selectFirst("a")?.let {
                    setUrlWithoutDomain(it.attr("abs:href"))
                }
                name = element.selectFirst("a .chapter-title")?.text()?.trim() ?: "Chapter ${index + 1}"
                chapter_number = (index + 1).toFloat()
                val releaseTime = element.selectFirst("a .chapter-update")?.text()?.trim()
                date_upload = releaseTime?.let { parseRelativeDate(it) } ?: 0L
            }
        }.toMutableList()

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

        return chapters.reversed()
    }

    private fun parseRelativeDate(dateStr: String): Long {
        if (!dateStr.contains("ago")) return 0L
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
    // ======================== Pages ========================

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

    override fun imageUrlParse(response: Response) = ""

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(page.url, headers)).execute()
        val doc = Jsoup.parse(response.body.string())
        return doc.selectFirst(".chapter-content")?.html() ?: ""
    }
    // ======================== Filters ========================

    override fun getFilterList() = FilterList(
        Filter.Header("Text search ignores filters"),
        GenreFilter(),
        StatusFilter(),
        SortFilter(),
    )

    private class GenreFilter :
        Filter.Select<String>(
            "Genre",
            arrayOf(
                "All", "Action", "Adventure", "Anime", "BG", "Billionaire", "BL",
                "Comedy", "Competitive Sports", "Contemporary Romance", "Crossing",
                "Derivative Fanfic", "Detective", "Douluo", "Dragon Ball", "Drama",
                "Eastern Fantasy", "Ecchi", "Elf", "Faloo", "Fan-Fiction", "Fantasy",
                "Fantasy Romance", "Football", "Game", "Gender Bender", "GL", "Harem",
                "Historical", "Historical Romance", "Hogwarts", "Horror", "Hot",
                "Josei", "LGBT", "Lolicon", "Magic", "Magical Realism", "Martial Arts",
                "Marvel", "Mecha", "Military", "Modern Life", "Movies", "Mystery",
                "Naruto", "NBA", "One Piece", "Other", "Pokemon", "Psychological",
                "Realistic Fiction", "Rebirth", "Reincarnation", "Romance",
                "School Life", "Sci-fi", "Science Fiction", "Secret", "Seinen",
                "Shoujo", "Shoujo Ai", "Shounen", "Shounen Ai", "Sign in",
                "Slice of Life", "Smut", "Sports", "Supernatural", "Suspense",
                "System", "Systemflow", "Terror", "Tragedy", "Travel Through Time",
                "Urban Life", "Video Games", "Villain", "War", "Wuxia", "Xianxia",
                "Xuanhuan", "Yaoi", "Yuri",
            ),
            0,
        ) {
        fun toUriPart(): String {
            val values = arrayOf(
                "all", "action", "adventure", "anime", "bg", "billionaire", "bl",
                "comedy", "competitive-sports", "contemporary-romance", "crossing",
                "derivative-fanfic", "detective", "douluo", "dragon-ball", "drama",
                "eastern-fantasy", "ecchi", "elf", "faloo", "fan-fiction", "fantasy",
                "fantasy-romance", "football", "game", "gender-bender", "gl", "harem",
                "historical", "historical-romance", "hogwarts", "horror", "hot",
                "josei", "lgbt", "lolicon", "magic", "magical-realism", "martial-arts",
                "marvel", "mecha", "military", "modern-life", "movies", "mystery",
                "naruto", "nba", "one-piece", "other", "pokemon", "psychological",
                "realistic-fiction", "rebirth", "reincarnation", "romance",
                "school-life", "sci-fi", "science-fiction", "secret", "seinen",
                "shoujo", "shoujo-ai", "shounen", "shounen-ai", "sign-in",
                "slice-of-life", "smut", "sports", "supernatural", "suspense",
                "system", "systemflow", "terror", "tragedy", "travel-through-time",
                "urban-life", "video-games", "villain", "war", "wuxia", "xianxia",
                "xuanhuan", "yaoi", "yuri",
            )
            return values[state]
        }
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("All", "Completed", "Ongoing"),
            0,
        ) {
        fun toUriPart() = when (state) {
            0 -> "all"
            1 -> "Completed"
            2 -> "Ongoing"
            else -> "all"
        }
    }

    private class SortFilter :
        Filter.Select<String>(
            "Sort by",
            arrayOf("New", "Popular", "Latest Update"),
            0,
        ) {
        fun toUriPart() = when (state) {
            0 -> "newstime"
            1 -> "onclick"
            2 -> "lastdotime"
            else -> "newstime"
        }
    }
}

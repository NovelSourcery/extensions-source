package eu.kanade.tachiyomi.extension.en.novelbuddy

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class NovelBuddy :
    HttpSource(),
    NovelSource {

    override val name = "NovelBuddy"
    override val baseUrl = "https://novelbuddy.io"
    override val lang = "en"
    override val supportsLatest = true

    override val isNovelSource = true

    override val client = network.cloudflareClient

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(page.url, headers)).execute()
        val document = response.asJsoup()
        return novelContentParse(document)
    }

    private fun Response.asJsoup(): Document = Jsoup.parse(body.string(), request.url.toString())

    private fun novelContentParse(document: Document): String {
        document.select("#listen-chapter").remove()
        document.select("#google_translate_element").remove()

        val contentElement = document.selectFirst(".chapter__content") ?: return ""
        return contentElement.html()
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/search?sort=views&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = parseNovels(document)
        val hasNextPage = document.selectFirst(".pagination .page-item.active + .page-item:not(.disabled)") != null ||
            (
                document.selectFirst(".paginator") != null &&
                    document.select(".paginator a.btn.link:not(.active)").isNotEmpty()
                )
        return MangasPage(mangas, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/search?sort=updated_at&page=$page", headers)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()

        if (query.isNotBlank()) {
            url.addQueryParameter("q", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> url.addQueryParameter("sort", filter.toUriPart())

                is StatusFilter -> {
                    val status = filter.toUriPart()
                    if (status != "all") {
                        url.addQueryParameter("status", status)
                    }
                }

                is GenreFilter -> {
                    filter.state.filter { it.state }.forEach { genre ->
                        url.addQueryParameter("genre[]", genre.uriPart)
                    }
                }

                else -> {}
            }
        }

        url.addQueryParameter("page", page.toString())

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun getFilterList() = FilterList(
        SortFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    private class SortFilter :
        Filter.Select<String>(
            "Sort by",
            arrayOf("Views", "Updated At", "Created At", "Name", "Rating"),
        ) {
        fun toUriPart() = when (state) {
            0 -> "views"
            1 -> "updated_at"
            2 -> "created_at"
            3 -> "name"
            4 -> "rating"
            else -> "views"
        }
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("All", "Ongoing", "Completed"),
        ) {
        fun toUriPart() = when (state) {
            0 -> "all"
            1 -> "ongoing"
            2 -> "completed"
            else -> "all"
        }
    }

    private class Genre(name: String, val uriPart: String) : Filter.CheckBox(name)

    private class GenreFilter :
        Filter.Group<Genre>(
            "Genres",
            listOf(
                Genre("Action", "action"),
                Genre("Action Adventure", "action-adventure"),
                Genre("Adult", "adult"),
                Genre("Adventure", "adventure"),
                Genre("Chinese", "chinese"),
                Genre("Comedy", "comedy"),
                Genre("Cultivation", "cultivation"),
                Genre("Drama", "drama"),
                Genre("Eastern", "eastern"),
                Genre("Ecchi", "ecchi"),
                Genre("Fan Fiction", "fan-fiction"),
                Genre("Fanfiction", "fanfiction"),
                Genre("Fantasy", "fantasy"),
                Genre("Game", "game"),
                Genre("Gender Bender", "gender-bender"),
                Genre("Harem", "harem"),
                Genre("Historical", "historical"),
                Genre("Horror", "horror"),
                Genre("Isekai", "isekai"),
                Genre("Josei", "josei"),
                Genre("Lolicon", "lolicon"),
                Genre("Magic", "magic"),
                Genre("Martial Arts", "martial-arts"),
                Genre("Mature", "mature"),
                Genre("Mecha", "mecha"),
                Genre("Military", "military"),
                Genre("Modern Life", "modern-life"),
                Genre("Mystery", "mystery"),
                Genre("Psychological", "psychological"),
                Genre("Reincarnation", "reincarnation"),
                Genre("Romance", "romance"),
                Genre("School Life", "school-life"),
                Genre("Sci-fi", "sci-fi"),
                Genre("Seinen", "seinen"),
                Genre("Shoujo", "shoujo"),
                Genre("Shoujo Ai", "shoujo-ai"),
                Genre("Shounen", "shounen"),
                Genre("Shounen Ai", "shounen-ai"),
                Genre("Slice of Life", "slice-of-life"),
                Genre("Smut", "smut"),
                Genre("Sports", "sports"),
                Genre("Supernatural", "supernatural"),
                Genre("System", "system"),
                Genre("Tragedy", "tragedy"),
                Genre("Urban", "urban"),
                Genre("Urban Life", "urban-life"),
                Genre("Wuxia", "wuxia"),
                Genre("Xianxia", "xianxia"),
                Genre("Xuanhuan", "xuanhuan"),
                Genre("Yaoi", "yaoi"),
                Genre("Yuri", "yuri"),
            ),
        )

    private fun parseNovels(document: Document): List<SManga> {
        return document.select(".book-item").mapNotNull { element ->
            val titleElement = element.selectFirst(".title a") ?: return@mapNotNull null
            val novelUrl = titleElement.attr("href")
            if (novelUrl.isNullOrEmpty()) return@mapNotNull null

            SManga.create().apply {
                title = element.selectFirst(".title")?.text() ?: ""
                thumbnail_url = element.selectFirst("img")?.attr("data-src")?.let {
                    if (it.startsWith("//")) "https:$it" else it
                }
                url = normalizeRelativeUrl(novelUrl)
            }
        }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = if (manga.url.startsWith("http")) manga.url else "$baseUrl/${manga.url}"
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val manga = SManga.create()

        manga.title = document.selectFirst(".name h1")?.text()?.trim() ?: "Untitled"
        manga.thumbnail_url = document.selectFirst(".img-cover img")?.attr("data-src")?.let {
            if (it.startsWith("//")) "https:$it" else it
        }
        manga.description = document.selectFirst(".section-body.summary .content")?.text()?.trim()

        document.select(".meta.box p").forEach { element ->
            val detailName = element.selectFirst("strong")?.text() ?: return@forEach
            when (detailName) {
                "Authors :" -> {
                    manga.author = element.select("a span").joinToString(", ") { it.text() }
                }

                "Status :" -> {
                    manga.status = when (element.select("a").text().lowercase()) {
                        "ongoing" -> SManga.ONGOING
                        "completed" -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                }

                "Genres :" -> {
                    manga.genre = element.select("a").joinToString(", ") { it.text().trim() }
                }
            }
        }

        return manga
    }

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        val scriptText = document.select("script").joinToString { it.data() }
        val novelIdMatch = Regex("""bookId\s*=\s*(\d+);""").find(scriptText)
        val novelId = novelIdMatch?.groupValues?.get(1) ?: return emptyList()

        val chapterListUrl = "$baseUrl/api/manga/$novelId/chapters?source=detail"
        val chapterResponse = client.newCall(GET(chapterListUrl, headers)).execute()
        val chapterDocument = Jsoup.parse(chapterResponse.body.string())

        val chapters = mutableListOf<SChapter>()

        chapterDocument.select("li").forEach { element ->
            val chapterName = element.selectFirst(".chapter-title")?.text()?.trim() ?: return@forEach
            val chapterUrl = element.selectFirst("a")?.attr("href")

            if (chapterUrl.isNullOrBlank()) return@forEach

            val chapter = SChapter.create().apply {
                name = chapterName
                url = normalizeRelativeUrl(chapterUrl)

                val releaseDateText = element.selectFirst(".chapter-update")?.text()?.trim()
                date_upload = parseDateOrZero(releaseDateText)
            }

            chapters.add(chapter)
        }

        return chapters.reversed()
    }

    private fun parseDateOrZero(dateStr: String?): Long {
        if (dateStr == null) return 0L
        return try {
            // Match pattern like "Jan 15, 2024"
            val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
            val monthsPattern = months.joinToString("|")
            val regex = Regex("""($monthsPattern)\s+(\d{1,2}),\s+(\d{4})""", RegexOption.IGNORE_CASE)
            val match = regex.find(dateStr) ?: return 0L

            val monthStr = match.groupValues[1]
            val day = match.groupValues[2].toInt()
            val year = match.groupValues[3].toInt()
            val month = months.indexOfFirst { it.equals(monthStr, ignoreCase = true) }

            if (month == -1) return 0L

            val calendar = java.util.Calendar.getInstance()
            calendar.set(year, month, day, 0, 0, 0)
            calendar.set(java.util.Calendar.MILLISECOND, 0)
            calendar.timeInMillis
        } catch (e: Exception) {
            0L
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val url = if (chapter.url.startsWith("http")) chapter.url else "$baseUrl/${chapter.url}"
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString(), null))
    private fun normalizeRelativeUrl(url: String): String = url
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix(baseUrl)
        .removePrefix("//")
        .removePrefix("/")

    override fun imageUrlParse(response: Response) = ""
}

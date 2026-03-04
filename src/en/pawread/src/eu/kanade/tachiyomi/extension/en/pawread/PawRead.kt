package eu.kanade.tachiyomi.extension.en.pawread

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
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

class PawRead :
    HttpSource(),
    NovelSource {

    override val name = "PawRead"
    override val baseUrl = "https://m.pawread.com"
    override val lang = "en"
    override val supportsLatest = true
    override val isNovelSource = true
    override val client = network.cloudflareClient
    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/list/?sort=click&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = parseNovels(doc)
        // Always assume next page exists if we got results
        return MangasPage(mangas, mangas.isNotEmpty())
    }
    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/list/?sort=update&page=$page", headers)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/search/?keywords=$query&page=$page", headers)
        }

        var url = "$baseUrl/list/"

        val filterValues = mutableListOf<String>()
        var sort = "click"
        var order = ""

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    val genre = filter.toUriPart()
                    if (genre.isNotEmpty()) filterValues.add(genre)
                }

                is StatusFilter -> {
                    val status = filter.toUriPart()
                    if (status.isNotEmpty()) filterValues.add(status)
                }

                is LangFilter -> {
                    val lang = filter.toUriPart()
                    if (lang.isNotEmpty()) filterValues.add(lang)
                }

                is SortFilter -> sort = filter.toUriPart()

                is OrderFilter -> order = if (filter.state) "-" else ""

                else -> {}
            }
        }

        if (filterValues.isNotEmpty()) {
            url += filterValues.joinToString("-") + "/"
        }

        url += "${order}$sort/?page=$page"

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)
    // ======================== Parse Novels ========================

    private fun parseNovels(doc: Document): List<SManga> {
        return doc.select(".list-comic a.txtA, .list-comic a.title, .itemBox a.txtA, .itemBox a.title").mapNotNull { element ->
            try {
                val title = element.text().trim()
                if (title.isBlank()) return@mapNotNull null

                val url = element.attr("href")
                val path = url.split("/").filter { it.isNotEmpty() }.take(2).joinToString("/")

                val parent = element.parent() ?: element.parents().firstOrNull()
                val cover = parent?.selectFirst("img")?.attr("src") ?: ""

                SManga.create().apply {
                    this.title = title
                    this.url = "/$path/"
                    thumbnail_url = cover
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = baseUrl + manga.url.let { if (it.endsWith("/")) it else "$it/" }
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()

        return SManga.create().apply {
            // Cover and name from #Cover div
            val coverDiv = doc.selectFirst("#Cover")
            val img = coverDiv?.selectFirst("img")
            thumbnail_url = img?.attr("src")

            // Title: try img title, then h1, then <title> tag minus " - PawRead"
            title = img?.attr("title")?.trim()?.ifBlank { null }
                ?: doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
                ?: doc.selectFirst("title")?.text()
                    ?.replace(Regex("\\s*-\\s*PawRead.*$", RegexOption.IGNORE_CASE), "")
                    ?.trim()
                ?: ""

            val infoItems = doc.select("p.txtItme")
            if (infoItems.size >= 1) {
                status = parseStatus(infoItems[0].text())
            }
            if (infoItems.size >= 2) {
                author = infoItems[1].text().trim()
            }

            // Genres from btn-default links
            genre = doc.select("a.btn-default").joinToString(", ") { it.text().trim() }

            // Summary from #full-des
            description = doc.selectFirst("#full-des")?.text()?.trim()
        }
    }

    private fun parseStatus(text: String): Int = when {
        text.contains("Ongoing", ignoreCase = true) ||
            text.contains("lianzai", ignoreCase = true) -> SManga.ONGOING

        text.contains("Completed", ignoreCase = true) ||
            text.contains("wanjie", ignoreCase = true) -> SManga.COMPLETED

        text.contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS

        else -> SManga.UNKNOWN
    }
    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val novelPath = response.request.url.encodedPath.let {
            if (it.endsWith("/")) it.dropLast(1) else it
        }

        return doc.select("div.item-box").mapNotNull { element ->
            try {
                val chapterId = element.attr("onclick")
                    .let { Regex("\\d+").find(it)?.value } ?: return@mapNotNull null

                val chapterPath = "$novelPath/$chapterId.html"

                val spans = element.select("span")
                val chapterName = spans.firstOrNull()?.text()?.trim() ?: "Chapter $chapterId"

                // Date from second span (format: YYYY.MM.DD)
                val dateStr = spans.getOrNull(1)?.text()?.trim() ?: ""
                val dateUpload = try {
                    if (dateStr.contains(".")) {
                        DATE_FORMAT.parse(dateStr)?.time ?: 0L
                    } else {
                        0L
                    }
                } catch (e: Exception) {
                    0L
                }

                if (dateStr.contains("Advanced", ignoreCase = true)) return@mapNotNull null

                SChapter.create().apply {
                    url = chapterPath
                    name = chapterName
                    date_upload = dateUpload
                }
            } catch (e: Exception) {
                null
            }
        }.reversed()
    }
    // ======================== Pages ========================

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.encodedPath))

    override fun imageUrlParse(response: Response): String = ""
    // ======================== Novel Content ========================

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = response.asJsoup()

        val content = doc.selectFirst("div.main") ?: return ""

        val watermarks = listOf("pawread", "tinyurl", "bit.ly")
        content.select("p").forEach { p ->
            val text = p.text().lowercase()
            if (watermarks.any { text.contains(it) }) {
                p.remove()
            }
        }

        return content.html()
    }
    // ======================== Filters ========================

    override fun getFilterList() = FilterList(
        Filter.Header("Filters are ignored when using text search"),
        GenreFilter(),
        StatusFilter(),
        LangFilter(),
        SortFilter(),
        OrderFilter(),
    )

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("All", "Completed", "Ongoing", "Hiatus"),
        ) {
        fun toUriPart() = when (state) {
            1 -> "wanjie"
            2 -> "lianzai"
            3 -> "hiatus"
            else -> ""
        }
    }

    private class LangFilter :
        Filter.Select<String>(
            "Language",
            arrayOf("All", "Chinese", "Korean", "Japanese"),
        ) {
        fun toUriPart() = when (state) {
            1 -> "chinese"
            2 -> "korean"
            3 -> "japanese"
            else -> ""
        }
    }

    private class SortFilter :
        Filter.Select<String>(
            "Sort By",
            arrayOf("Clicks", "Time Updated", "Time Posted"),
        ) {
        fun toUriPart() = when (state) {
            0 -> "click"
            1 -> "update"
            2 -> "post"
            else -> "click"
        }
    }

    private class OrderFilter : Filter.CheckBox("Ascending Order", false)

    private class GenreFilter :
        Filter.Select<String>(
            "Genre",
            genres.map { it.first }.toTypedArray(),
            0,
        ) {
        fun toUriPart() = genres[state].second
    }

    private fun Response.asJsoup(): Document = Jsoup.parse(body.string())

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy.MM.dd", Locale.US)

        private val genres = listOf(
            Pair("All", ""),
            Pair("Fantasy", "Fantasy"),
            Pair("Action", "Action"),
            Pair("Xuanhuan", "Xuanhuan"),
            Pair("Romance", "Romance"),
            Pair("Comedy", "Comedy"),
            Pair("Mystery", "Mystery"),
            Pair("Mature", "Mature"),
            Pair("Harem", "Harem"),
            Pair("Wuxia", "Wuxia"),
            Pair("Xianxia", "Xianxia"),
            Pair("Tragedy", "Tragedy"),
            Pair("Sci-fi", "Scifi"),
            Pair("Historical", "Historical"),
            Pair("Ecchi", "Ecchi"),
            Pair("Adventure", "Adventure"),
            Pair("Adult", "Adult"),
            Pair("Supernatural", "Supernatural"),
            Pair("Psychological", "Psychological"),
            Pair("Drama", "Drama"),
            Pair("Horror", "Horror"),
            Pair("Josei", "Josei"),
            Pair("Mecha", "Mecha"),
            Pair("Seinen", "Seinen"),
            Pair("Shoujo", "Shoujo"),
            Pair("Shounen", "Shounen"),
            Pair("Smut", "Smut"),
            Pair("Yaoi", "Yaoi"),
            Pair("Yuri", "Yuri"),
            Pair("Martial Arts", "MartialArts"),
            Pair("School Life", "SchoolLife"),
            Pair("Shoujo Ai", "ShoujoAi"),
            Pair("Shounen Ai", "ShounenAi"),
            Pair("Slice of Life", "SliceofLife"),
            Pair("Gender Bender", "GenderBender"),
            Pair("Sports", "Sports"),
            Pair("Urban", "Urban"),
            Pair("Adventurer", "Adventurer"),
        )
    }
}

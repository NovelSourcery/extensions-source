package eu.kanade.tachiyomi.extension.all.vynovel

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
import java.net.URLEncoder

class Vynovel : HttpSource(), NovelSource {

    override val name = "Vynovel"
    override val baseUrl = "https://vynovel.com"
    override val lang = "all"
    override val supportsLatest = true
    override val isNovelSource = true

    override val client = network.cloudflareClient

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/search?sort=viewed&sort_type=desc&page=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())

        val novels = document.select("div.novel-list div.comic-item").map { item ->
            SManga.create().apply {
                val link = item.selectFirst("a") ?: return@map null
                url = link.attr("href").removePrefix(baseUrl)
                title = item.selectFirst("div.comic-title")?.text()?.trim() ?: ""

                // Extract thumbnail from data-background-image or style
                val coverDiv = item.selectFirst("div.comic-image")
                thumbnail_url = coverDiv?.attr("data-background-image")
                    ?: coverDiv?.attr("style")?.let { extractBackgroundUrl(it) }
            }
        }.filterNotNull()

        val hasNextPage = document.selectFirst("ul.pagination li.page-item:not(.disabled) a[rel=next]") != null

        return MangasPage(novels, hasNextPage)
    }

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/search?sort=updated_at&sort_type=desc&page=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = StringBuilder("$baseUrl/search?page=$page")

        // Default values
        var searchPosition = "0"
        var searchInDesc = false
        var authorPosition = "0"
        var author = ""
        var status = "2" // All
        var sort = "viewed"
        var sortType = "desc"
        val includeGenres = mutableListOf<String>()
        val excludeGenres = mutableListOf<String>()

        // Process filters
        filters.forEach { filter ->
            when (filter) {
                is SearchPositionFilter -> searchPosition = filter.pairValues[filter.state].second
                is SearchInDescFilter -> searchInDesc = filter.state
                is AuthorFilter -> author = filter.state
                is AuthorPositionFilter -> authorPosition = filter.pairValues[filter.state].second
                is StatusFilter -> status = filter.pairValues[filter.state].second
                is SortFilter -> sort = filter.pairValues[filter.state].second
                is SortTypeFilter -> sortType = filter.pairValues[filter.state].second
                is GenreFilter -> {
                    filter.state.forEach { genre ->
                        when (genre.state) {
                            Filter.TriState.STATE_INCLUDE -> includeGenres.add(genre.value)
                            Filter.TriState.STATE_EXCLUDE -> excludeGenres.add(genre.value)
                            else -> {}
                        }
                    }
                }
                else -> {}
            }
        }

        // Build URL
        url.append("&search_po=$searchPosition")
        url.append("&q=${URLEncoder.encode(query, "UTF-8")}")
        if (searchInDesc) url.append("&check_search_desc=1")
        url.append("&author_po=$authorPosition")
        url.append("&author=${URLEncoder.encode(author, "UTF-8")}")
        url.append("&completed=$status")
        url.append("&sort=$sort")
        url.append("&sort_type=$sortType")

        // Add genres
        includeGenres.forEach { genre ->
            url.append("&genre[]=${URLEncoder.encode(genre, "UTF-8")}")
        }
        excludeGenres.forEach { genre ->
            url.append("&exclude_genre[]=${URLEncoder.encode(genre, "UTF-8")}")
        }

        return GET(url.toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            // Title
            title = document.selectFirst("h1.title")?.text()?.trim() ?: ""

            // Cover
            thumbnail_url = document.selectFirst("div.img-manga img")?.attr("src")

            // Author
            author = document.select("p:contains(Authors) a").joinToString(", ") { it.text().trim() }.ifEmpty {
                document.selectFirst("p:contains(Authors)")?.text()
                    ?.replace("Authors", "")?.replace(":", "")?.trim()
            }

            // Status
            val statusText = document.selectFirst("p:contains(Status) span")?.text()?.lowercase() ?: ""
            status = when {
                statusText.contains("completed") -> SManga.COMPLETED
                statusText.contains("ongoing") -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }

            // Genres
            genre = document.select("p:contains(Genres) a.badge")
                .mapNotNull { it.text()?.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")

            // Description
            description = document.selectFirst("div.summary p.content")?.text()?.trim()
        }
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string())
        val chapters = mutableListOf<SChapter>()

        document.select("div.list-group a.list-chapter").forEach { link ->
            val chapterText = link.selectFirst("span")?.text()?.trim() ?: return@forEach
            val dateText = link.selectFirst("p")?.text()?.trim() ?: ""

            // Extract chapter number from text like "Chapter 1.1"
            val chapterNum = chapterText.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: chapters.size.toFloat() + 1

            chapters.add(
                SChapter.create().apply {
                    url = link.attr("href").removePrefix(baseUrl)
                    name = chapterText
                    chapter_number = chapterNum
                    date_upload = parseDate(dateText)
                },
            )
        }

        return chapters // API returns ascending (ch1, ch2...), Mihon expects descending (newest first)
    }

    // ======================== Pages ========================

    override fun pageListRequest(chapter: SChapter): Request {
        val url = if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        return listOf(Page(0, response.request.url.toString()))
    }

    // ======================== Page Text (Novel) ========================

    override suspend fun fetchPageText(page: Page): String {
        val request = GET(page.url, headers)
        val response = client.newCall(request).execute()
        val document = Jsoup.parse(response.body.string())

        val content = StringBuilder()

        // Get chapter title
        val chapterTitle = document.selectFirst("div.content h3.text-center")?.text()?.trim()
        if (!chapterTitle.isNullOrEmpty()) {
            content.append("<h2>$chapterTitle</h2>\n")
        }

        // Get content from div.content
        val contentDiv = document.selectFirst("div.content.bg-1")
        contentDiv?.select("p")?.forEach { p ->
            val text = p.text()?.trim()
            if (!text.isNullOrEmpty()) {
                content.append("<p>$text</p>\n")
            }
        }

        return content.toString()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // ======================== Filters ========================

    override fun getFilterList(): FilterList {
        return FilterList(
            Filter.Header("Search Options"),
            SearchPositionFilter("Search Position", searchPositionValues),
            SearchInDescFilter("Search in Description"),
            Filter.Separator(),
            Filter.Header("Author Options"),
            AuthorFilter("Author"),
            AuthorPositionFilter("Author Position", searchPositionValues),
            Filter.Separator(),
            StatusFilter("Status", statusValues),
            SortFilter("Sort By", sortValues),
            SortTypeFilter("Sort Order", sortTypeValues),
            Filter.Separator(),
            Filter.Header("Genres (tap to include, tap again to exclude)"),
            GenreFilter("Genres", getGenreList()),
        )
    }

    class SearchPositionFilter(name: String, internal val pairValues: Array<Pair<String, String>>) :
        Filter.Select<String>(name, pairValues.map { it.first }.toTypedArray())

    class SearchInDescFilter(name: String) : Filter.CheckBox(name, false)
    class AuthorFilter(name: String) : Filter.Text(name)
    class AuthorPositionFilter(name: String, internal val pairValues: Array<Pair<String, String>>) :
        Filter.Select<String>(name, pairValues.map { it.first }.toTypedArray())

    class StatusFilter(name: String, internal val pairValues: Array<Pair<String, String>>) :
        Filter.Select<String>(name, pairValues.map { it.first }.toTypedArray())

    class SortFilter(name: String, internal val pairValues: Array<Pair<String, String>>) :
        Filter.Select<String>(name, pairValues.map { it.first }.toTypedArray())

    class SortTypeFilter(name: String, internal val pairValues: Array<Pair<String, String>>) :
        Filter.Select<String>(name, pairValues.map { it.first }.toTypedArray())

    class Genre(name: String, val value: String) : Filter.TriState(name)
    class GenreFilter(name: String, genres: List<Genre>) : Filter.Group<Genre>(name, genres)

    private val searchPositionValues = arrayOf(
        Pair("Contain", "0"),
        Pair("Begin", "1"),
        Pair("End", "2"),
    )

    private val statusValues = arrayOf(
        Pair("All", "2"),
        Pair("Completed", "1"),
        Pair("Ongoing", "0"),
    )

    private val sortValues = arrayOf(
        Pair("Viewed", "viewed"),
        Pair("Scored", "scored"),
        Pair("Newest", "created_at"),
        Pair("Latest Update", "updated_at"),
    )

    private val sortTypeValues = arrayOf(
        Pair("Descending", "desc"),
        Pair("Ascending", "asc"),
    )

    private fun getGenreList(): List<Genre> = listOf(
        Genre("Action", "Action-1-action"),
        Genre("Adult", "Adult-6-adult"),
        Genre("Adventure", "Adventure-10-adventure"),
        Genre("Billionaire", "Billionaire-62-billionaire"),
        Genre("Chinese", "Chinese-27-chinese"),
        Genre("Comedy", "Comedy-2-comedy"),
        Genre("Contemporary Romance", "Contemporary Romance-65-contemporary_romance"),
        Genre("Drama", "Drama-3-drama"),
        Genre("Ecchi", "Ecchi-38-ecchi"),
        Genre("Erciyuan", "Erciyuan-59-erciyuan"),
        Genre("Faloo", "Faloo-31-faloo"),
        Genre("Fan Fiction", "fan fiction-66-fan_fiction"),
        Genre("Fan-Fiction", "Fan-Fiction-8-fanfiction"),
        Genre("Fanfiction", "Fanfiction-68-fanfiction"),
        Genre("Fantasy", "Fantasy-4-fantasy"),
        Genre("Game", "Game-16-game"),
        Genre("Games", "Games-67-games"),
        Genre("Gender Bender", "Gender Bender-43-gender_bender"),
        Genre("Harem", "Harem-11-harem"),
        Genre("Historical", "Historical-23-historical"),
        Genre("Horror", "Horror-29-horror"),
        Genre("Isekai", "Isekai-48-isekai"),
        Genre("Japanese", "Japanese-42-japanese"),
        Genre("Josei", "Josei-24-josei"),
        Genre("Korean", "Korean-37-korean"),
        Genre("LitRPG", "LitRPG-56-litrpg"),
        Genre("Magic", "Magic-54-magic"),
        Genre("Magical Realism", "Magical Realism-47-magical_realism"),
        Genre("Martial Arts", "Martial Arts-12-martial_arts"),
        Genre("Martialarts", "Martialarts-53-martialarts"),
        Genre("Mature", "Mature-32-mature"),
        Genre("Mecha", "Mecha-44-mecha"),
        Genre("Military", "Military-45-military"),
        Genre("Modern Life", "Modern Life-64-modern_life"),
        Genre("Modern&", "Modern&-57-modern"),
        Genre("ModernRomance", "ModernRomance-50-modernromance"),
        Genre("Mystery", "Mystery-18-mystery"),
        Genre("NA", "NA-61-na"),
        Genre("Psychological", "Psychological-34-psychological"),
        Genre("Romance", "Romance-5-romance"),
        Genre("Romantic", "Romantic-49-romantic"),
        Genre("School Life", "School Life-13-school_life"),
        Genre("Sci-fi", "Sci-fi-9-scifi"),
        Genre("Seinen", "Seinen-33-seinen"),
        Genre("Shoujo", "Shoujo-25-shoujo"),
        Genre("Shoujo Ai", "Shoujo Ai-40-shoujo_ai"),
        Genre("Shounen", "Shounen-22-shounen"),
        Genre("Shounen Ai", "Shounen Ai-14-shounen_ai"),
        Genre("Slice of Life", "Slice of Life-15-slice_of_life"),
        Genre("Smut", "Smut-39-smut"),
        Genre("Son-In-Law", "Son-In-Law-63-soninlaw"),
        Genre("Sports", "Sports-21-sports"),
        Genre("StrongLead", "StrongLead-55-stronglead"),
        Genre("Supernatural", "Supernatural-19-supernatural"),
        Genre("Thriller", "Thriller-30-thriller"),
        Genre("Tragedy", "Tragedy-35-tragedy"),
        Genre("Two-dimensional", "Two-dimensional-58-twodimensional"),
        Genre("Uncategorized", "Uncategorized-69-uncategorized"),
        Genre("Urban", "Urban-52-urban"),
        Genre("Urban Life", "Urban Life-26-urban_life"),
        Genre("Video Games", "Video Games-51-video_games"),
        Genre("Virtual Reality", "Virtual Reality-60-virtual_reality"),
        Genre("VirtualReality", "VirtualReality-46-virtualreality"),
        Genre("Wuxia", "Wuxia-41-wuxia"),
        Genre("Xianxia", "Xianxia-20-xianxia"),
        Genre("Xuanhuan", "Xuanhuan-17-xuanhuan"),
        Genre("Yaoi", "Yaoi-28-yaoi"),
        Genre("Yuri", "Yuri-36-yuri"),
    )

    // ======================== Helpers ========================

    private fun extractBackgroundUrl(style: String): String? {
        val match = Regex("""url\(['"]?([^'")\s]+)['"]?\)""").find(style)
        return match?.groupValues?.getOrNull(1)
    }

    private fun parseDate(dateString: String): Long {
        return try {
            val months = mapOf(
                "Jan" to 0, "Feb" to 1, "Mar" to 2, "Apr" to 3,
                "May" to 4, "Jun" to 5, "Jul" to 6, "Aug" to 7,
                "Sep" to 8, "Oct" to 9, "Nov" to 10, "Dec" to 11,
            )
            val parts = dateString.trim().split(" ")
            if (parts.size >= 3) {
                val month = months[parts[0]] ?: 0
                val day = parts[1].replace(",", "").toIntOrNull() ?: 1
                val year = parts[2].toIntOrNull() ?: 2023
                java.util.Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                }.timeInMillis
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }
}

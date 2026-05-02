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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.URLDecoder

class NovelBuddy :
    HttpSource(),
    NovelSource {

    override val name = "NovelBuddy"
    override val baseUrl = "https://novelbuddy.io"
    private val apiUrl = "https://api.novelbuddy.io"
    override val lang = "en"
    override val supportsLatest = true
    override val isNovelSource = true
    override val client = network.cloudflareClient

    private val json = Json { ignoreUnknownKeys = true }

    private fun buildUrl(pathOrUrl: String): String {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            return pathOrUrl
        }
        return "$baseUrl/${pathOrUrl.trimStart('/')}"
    }

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(buildUrl(page.url), headers)).execute()
        val document = Jsoup.parse(response.body.string())

        val script = document.selectFirst("#__NEXT_DATA__")?.html()
        if (script != null) {
            return parseChapterFromScript(script)
        }

        // Fallback to direct content parsing
        val contentElement = document.selectFirst(".chapter__content") ?: return ""
        contentElement.select("#listen-chapter").remove()
        contentElement.select("#google_translate_element").remove()

        var content = contentElement.html()
        // Remove webnovel watermarks
        content = content.replace(
            Regex("Find authorized novels in Webnovel.*?faster updates, better experience.*?Please click www\\.webnovel\\.com for visiting\\.", RegexOption.DOT_MATCHES_ALL),
            "",
        )
        // Remove obfuscated freewebnovel watermarks
        content = content.replace(Regex("free.*?novel\\.com", RegexOption.IGNORE_CASE), "")

        return content
    }

    private fun parseChapterFromScript(script: String): String {
        return try {
            val data = json.parseToJsonElement(script).jsonObject
            val initialChapter = data["props"]?.jsonObject?.get("pageProps")?.jsonObject?.get("initialChapter")
            var content = initialChapter?.jsonObject?.get("content")?.jsonPrimitive?.content ?: return ""

            // Remove webnovel watermarks
            content = content.replace(
                Regex("Find authorized novels in Webnovel.*?faster updates, better experience.*?Please click www\\.webnovel\\.com for visiting\\.", RegexOption.DOT_MATCHES_ALL),
                "",
            )
            // Remove obfuscated freewebnovel watermarks
            content = content.replace(Regex("free.*?novel\\.com", RegexOption.IGNORE_CASE), "")

            content
        } catch (e: Exception) {
            ""
        }
    }

    // ======================== Popular ========================
    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/titles/search".toHttpUrl().newBuilder()
        url.addQueryParameter("sort", "views")
        url.addQueryParameter("limit", "24")
        url.addQueryParameter("page", page.toString())
        return GET(url.build(), headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseApiResponse(response)

    // ======================== Latest ========================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/titles/search".toHttpUrl().newBuilder()
        url.addQueryParameter("sort", "latest")
        url.addQueryParameter("limit", "24")
        url.addQueryParameter("page", page.toString())
        return GET(url.build(), headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseApiResponse(response)

    // ======================== Search ========================
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiUrl/titles/search".toHttpUrl().newBuilder()

        if (query.isNotBlank()) {
            url.addQueryParameter("q", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is OrderByFilter -> url.addQueryParameter("sort", filter.toUriPart())
                is StatusFilter -> url.addQueryParameter("status", filter.toUriPart())
                is GenreFilter -> {
                    val included = filter.state.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.value }
                    val excluded = filter.state.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.value }
                    if (included.isNotEmpty()) {
                        url.addQueryParameter("genres", included.joinToString(","))
                    }
                    if (excluded.isNotEmpty()) {
                        url.addQueryParameter("exclude", excluded.joinToString(","))
                    }
                }
                is MinChaptersFilter -> {
                    filter.state.toIntOrNull()?.let { if (it > 0) url.addQueryParameter("min_ch", it.toString()) }
                }
                is MaxChaptersFilter -> {
                    filter.state.toIntOrNull()?.let { if (it > 0) url.addQueryParameter("max_ch", it.toString()) }
                }
                is DemoFilter -> {
                    val demos = filter.state.filter { it.state }.map { it.value }
                    if (demos.isNotEmpty()) {
                        url.addQueryParameter("demographic", demos.joinToString(","))
                    }
                }
                else -> {}
            }
        }

        url.addQueryParameter("limit", "24")
        url.addQueryParameter("page", page.toString())
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseApiResponse(response)

    private fun parseApiResponse(response: Response): MangasPage {
        return try {
            val jsonData = json.parseToJsonElement(response.body.string()).jsonObject
            val items = jsonData["data"]?.jsonObject?.get("items")?.jsonArray ?: return MangasPage(emptyList(), false)

            val mangas = items.mapNotNull { item ->
                val obj = item.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val url = obj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val cover = obj["cover"]?.jsonPrimitive?.content

                SManga.create().apply {
                    title = name
                    this.url = URLDecoder.decode(url, "UTF-8").removePrefix("/").removePrefix(baseUrl)
                    thumbnail_url = cover?.let { if (it.startsWith("//")) "https:$it" else it }
                }
            }

            // Check if there are more pages (simplified heuristic: if we got 24 items, assume there's a next page)
            val hasNextPage = items.size >= 24

            MangasPage(mangas, hasNextPage)
        } catch (e: Exception) {
            MangasPage(emptyList(), false)
        }
    }

    override fun getFilterList() = FilterList(
        OrderByFilter(),
        Filter.Separator(),
        StatusFilter(),
        Filter.Separator(),
        GenreFilter(),
        Filter.Separator(),
        MinChaptersFilter(),
        MaxChaptersFilter(),
        Filter.Separator(),
        DemoFilter(),
    )

    // ======================== Filters ========================
    private class OrderByFilter :
        Filter.Select<String>(
            "Order By",
            arrayOf("Views", "Latest", "Popular", "A-Z", "Rating", "Chapters"),
        ) {
        fun toUriPart() = when (state) {
            0 -> "views"
            1 -> "latest"
            2 -> "popular"
            3 -> "alphabetical"
            4 -> "rating"
            5 -> "chapters"
            else -> "views"
        }
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("All", "Ongoing", "Completed", "Hiatus", "Cancelled"),
        ) {
        fun toUriPart() = when (state) {
            0 -> "all"
            1 -> "ongoing"
            2 -> "completed"
            3 -> "hiatus"
            4 -> "cancelled"
            else -> "all"
        }
    }

    private class GenreCheckbox(name: String, val value: String) : Filter.TriState(name)

    private class GenreFilter :
        Filter.Group<GenreCheckbox>(
            "Genres",
            listOf(
                GenreCheckbox("Action", "action"),
                GenreCheckbox("Action Adventure", "action-adventure"),
                GenreCheckbox("Adult", "adult"),
                GenreCheckbox("Adventure", "adventure"),
                GenreCheckbox("Cultivation", "cultivation"),
                GenreCheckbox("Drama", "drama"),
                GenreCheckbox("Eastern", "eastern"),
                GenreCheckbox("Ecchi", "ecchi"),
                GenreCheckbox("Fan-Fiction", "fan-fiction"),
                GenreCheckbox("Fanfiction", "fanfiction"),
                GenreCheckbox("Fantasy", "fantasy"),
                GenreCheckbox("Game", "game"),
                GenreCheckbox("Gender Bender", "gender-bender"),
                GenreCheckbox("Harem", "harem"),
                GenreCheckbox("Historical", "historical"),
                GenreCheckbox("Horror", "horror"),
                GenreCheckbox("Isekai", "isekai"),
                GenreCheckbox("Josei", "josei"),
                GenreCheckbox("Lolicon", "lolicon"),
                GenreCheckbox("Magic", "magic"),
                GenreCheckbox("Martial Arts", "martial-arts"),
                GenreCheckbox("Mature", "mature"),
                GenreCheckbox("Mecha", "mecha"),
                GenreCheckbox("Military", "military"),
                GenreCheckbox("Modern Life", "modern-life"),
                GenreCheckbox("Mystery", "mystery"),
                GenreCheckbox("Psychological", "psychological"),
                GenreCheckbox("Reincarnation", "reincarnation"),
                GenreCheckbox("Romance", "romance"),
                GenreCheckbox("School Life", "school-life"),
                GenreCheckbox("Sci-fi", "sci-fi"),
                GenreCheckbox("Seinen", "seinen"),
                GenreCheckbox("Shoujo", "shoujo"),
                GenreCheckbox("Shoujo Ai", "shoujo-ai"),
                GenreCheckbox("Shounen", "shounen"),
                GenreCheckbox("Shounen Ai", "shounen-ai"),
                GenreCheckbox("Slice of Life", "slice-of-life"),
                GenreCheckbox("Smut", "smut"),
                GenreCheckbox("Sports", "sports"),
                GenreCheckbox("Supernatural", "supernatural"),
                GenreCheckbox("System", "system"),
                GenreCheckbox("Thriller", "thriller"),
                GenreCheckbox("Tragedy", "tragedy"),
                GenreCheckbox("Urban", "urban"),
                GenreCheckbox("Urban Life", "urban-life"),
                GenreCheckbox("Wuxia", "wuxia"),
                GenreCheckbox("Xianxia", "xianxia"),
                GenreCheckbox("Xuanhuan", "xuanhuan"),
                GenreCheckbox("Yaoi", "yaoi"),
                GenreCheckbox("Yuri", "yuri"),
            ),
        )

    private class MinChaptersFilter : Filter.Text("Minimum Chapters", "")
    private class MaxChaptersFilter : Filter.Text("Maximum Chapters", "")

    private class DemoCheckbox(name: String, val value: String) : Filter.CheckBox(name)

    private class DemoFilter :
        Filter.Group<DemoCheckbox>(
            "Demographics",
            listOf(
                DemoCheckbox("Shounen", "shounen"),
                DemoCheckbox("Shoujo", "shoujo"),
                DemoCheckbox("Seinen", "seinen"),
                DemoCheckbox("Josei", "josei"),
            ),
        )

    // ======================== Details ========================
    override fun mangaDetailsRequest(manga: SManga): Request = GET(buildUrl(manga.url), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())

        val script = document.selectFirst("#__NEXT_DATA__")?.html()
        if (script != null) {
            return parseNovelFromScript(script)
        }

        // Fallback to HTML parsing if script not found
        return SManga.create().apply {
            title = document.selectFirst(".name h1")?.text()?.trim() ?: "Untitled"
            thumbnail_url = document.selectFirst(".img-cover img")?.attr("data-src")
            description = document.selectFirst(".section-body.summary .content")?.text()?.trim()
        }
    }

    private fun parseNovelFromScript(script: String): SManga {
        return try {
            val data = json.parseToJsonElement(script).jsonObject
            val initialManga = data["props"]?.jsonObject?.get("pageProps")?.jsonObject?.get("initialManga")?.jsonObject
                ?: return SManga.create().apply { title = "Untitled" }

            SManga.create().apply {
                title = initialManga["name"]?.jsonPrimitive?.content ?: "Untitled"
                thumbnail_url = initialManga["cover"]?.jsonPrimitive?.content
                description = initialManga["summary"]?.jsonPrimitive?.content
                    ?.let { org.jsoup.Jsoup.parse(it).text().trim() }
                    ?.let { org.jsoup.Jsoup.parse(it).text().trim() }

                author = initialManga["authors"]?.jsonArray?.joinToString(", ") {
                    it.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                } ?: ""

                genre = initialManga["genres"]?.jsonArray?.joinToString(", ") {
                    it.jsonObject["name"]?.jsonPrimitive?.content ?: ""
                } ?: ""

                val rawStatus = initialManga["status"]?.jsonPrimitive?.content?.lowercase() ?: ""
                status = when {
                    rawStatus.contains("ongoing") -> SManga.ONGOING
                    rawStatus.contains("completed") -> SManga.COMPLETED
                    rawStatus.contains("hiatus") -> SManga.ON_HIATUS
                    rawStatus.contains("dropped") || rawStatus.contains("cancelled") -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
            }
        } catch (e: Exception) {
            SManga.create().apply { title = "Untitled" }
        }
    }

    // ======================== Chapters ========================
    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string())

        // Extract novel ID from the page script
        val script = document.selectFirst("#__NEXT_DATA__")?.html() ?: return emptyList()
        val novelId = extractNovelId(script)

        if (novelId.isNullOrEmpty()) return emptyList()

        // Call the API endpoint to fetch all chapters - use /titles/{id}/chapters not /manga/{id}/chapters
        val chapterApiUrl = "$apiUrl/titles/$novelId/chapters"
        val apiResponse = client.newCall(GET(chapterApiUrl, headers)).execute()

        return try {
            val apiData = json.parseToJsonElement(apiResponse.body.string()).jsonObject
            val chapters = mutableListOf<SChapter>()

            apiData["data"]?.jsonObject?.get("chapters")?.jsonArray?.forEach { item ->
                val obj = item.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: return@forEach
                val url = obj["url"]?.jsonPrimitive?.content ?: return@forEach

                chapters.add(
                    SChapter.create().apply {
                        this.name = name
                        this.url = URLDecoder.decode(url, "UTF-8").removePrefix("/").removePrefix(baseUrl)
                    },
                )
            }

            // API returns chapters in correct order
            chapters
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractNovelId(script: String): String? = try {
        val data = json.parseToJsonElement(script).jsonObject
        data["props"]?.jsonObject?.get("pageProps")?.jsonObject?.get("initialManga")?.jsonObject
            ?.get("id")?.jsonPrimitive?.content
    } catch (e: Exception) {
        null
    }

    // ======================== Pages ========================
    override fun pageListRequest(chapter: SChapter): Request = GET(buildUrl(chapter.url), headers)

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

    override fun imageUrlParse(response: Response): String = ""
}

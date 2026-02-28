package eu.kanade.tachiyomi.extension.en.mvlempyr

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Locale

class MVLEMPYR :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "MVLEMPYR"
    override val baseUrl = "https://www.mvlempyr.io"
    override val lang = "en"
    override val supportsLatest = true

    override val isNovelSource = true

    override val client = network.cloudflareClient

    private val perPage = 20

    @Volatile
    private var cachedNovels: List<CachedNovel>? = null

    private data class CachedNovel(
        val manga: SManga,
        val name: String,
        val novelCode: Long?,
        val avgReview: Float?,
        val reviewCount: Int?,
        val chapterCount: Int?,
        val created: Long?,
        val genres: List<String>,
        val tags: List<String>,
    )

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
        .add("Referer", chapSite)
        .add("Origin", chapSite)

    private val json: Json = Json { ignoreUnknownKeys = true }
    private val chapSite = "https://chap.heliosarchive.online"
    private val assetsSite = "https://assets.mvlempyr.app/images/600"

    // WordPress API Response structure
    @Serializable
    private data class WpNovel(
        val id: Int = 0,
        val date: String? = null,
        val slug: String = "",
        val title: WpRendered = WpRendered(),
        val content: WpRendered = WpRendered(),
        val excerpt: WpRendered = WpRendered(),
        @SerialName("featured_media") val featuredMedia: Int = 0,
        val genres: List<Int> = emptyList(),
        val tags: List<Long> = emptyList(),
        @SerialName("author-name") val authorName: String? = null,
        val bookid: String? = null,
        @SerialName("novel-code") val novelCode: Long? = null,
    )

    @Serializable
    private data class WpRendered(
        val rendered: String = "",
    )

    @Serializable
    private data class ChapterPost(
        val id: Int = 0,
        val date: String? = null,
        val link: String? = null,
        val title: WpRendered = WpRendered(),
        val acf: ChapterAcf? = null,
    )

    @Serializable
    private data class ChapterAcf(
        @SerialName("ch_name") val chName: String? = null,
        @SerialName("novel_code") val novelCode: kotlinx.serialization.json.JsonElement? = null,
        @SerialName("chapter_number") val chapterNumber: kotlinx.serialization.json.JsonElement? = null,
    )

    override fun popularMangaRequest(page: Int): Request {
        // Always load all novels for local filtering (matching TS approach)
        return GET("$chapSite/wp-json/wp/v2/mvl-novels?per_page=10000", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        ensureCache(response)
        return getFilteredPage(1, "", FilterList())
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$chapSite/wp-json/wp/v2/mvl-novels?per_page=10000", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        ensureCache(response)
        return getFilteredPage(1, "", FilterList(), sortBy = "created")
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$chapSite/wp-json/wp/v2/mvl-novels?per_page=10000", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        ensureCache(response)
        return MangasPage(emptyList(), false)
    }

    override fun fetchPopularManga(page: Int): rx.Observable<MangasPage> = rx.Observable.fromCallable {
        if (cachedNovels == null) {
            val response = client.newCall(popularMangaRequest(1)).execute()
            ensureCache(response)
        }
        getFilteredPage(page, "", FilterList())
    }

    override fun fetchLatestUpdates(page: Int): rx.Observable<MangasPage> = rx.Observable.fromCallable {
        if (cachedNovels == null) {
            val response = client.newCall(latestUpdatesRequest(1)).execute()
            ensureCache(response)
        }
        getFilteredPage(page, "", FilterList(), sortBy = "created")
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): rx.Observable<MangasPage> = rx.Observable.fromCallable {
        if (cachedNovels == null) {
            val response = client.newCall(searchMangaRequest(1, query, filters)).execute()
            ensureCache(response)
        }
        getFilteredPage(page, query, filters)
    }

    private fun ensureCache(response: Response) {
        if (cachedNovels != null) return
        val responseBody = response.body.string()
        try {
            val jsonArray = json.parseToJsonElement(responseBody).jsonArray
            cachedNovels = jsonArray.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    val manga = createSMangaFromJson(obj)
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull
                        ?: obj["title"]?.jsonObject?.get("rendered")?.jsonPrimitive?.contentOrNull
                        ?: ""

                    CachedNovel(
                        manga = manga,
                        name = name,
                        novelCode = obj["novel-code"]?.jsonPrimitive?.longOrNull,
                        avgReview = obj["average-review"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull(),
                        reviewCount = obj["total-reviews"]?.jsonPrimitive?.intOrNull,
                        chapterCount = obj["total-chapters"]?.jsonPrimitive?.intOrNull,
                        created = obj["createdOn"]?.jsonPrimitive?.contentOrNull?.let { parseCreatedDate(it) },
                        genres = obj["genre"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                        tags = obj["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            cachedNovels = emptyList()
        }
    }

    private fun getFilteredPage(page: Int, query: String, filters: FilterList, sortBy: String? = null): MangasPage {
        var novels = cachedNovels ?: return MangasPage(emptyList(), false)

        // Local search by name
        if (query.isNotBlank()) {
            novels = novels.filter { it.name.contains(query, ignoreCase = true) }
        }

        // Local genre filtering (include/exclude)
        var selectedSort = sortBy
        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    val included = filter.state.filter { it.isIncluded() }.map { it.value.lowercase() }
                    val excluded = filter.state.filter { it.isExcluded() }.map { it.value.lowercase() }
                    if (included.isNotEmpty()) {
                        novels = novels.filter { novel -> included.all { genre -> novel.genres.any { it.equals(genre, ignoreCase = true) } } }
                    }
                    if (excluded.isNotEmpty()) {
                        novels = novels.filter { novel -> excluded.none { genre -> novel.genres.any { it.equals(genre, ignoreCase = true) } } }
                    }
                }

                is TagFilter -> {
                    val included = filter.state.filter { it.isIncluded() }.map { it.value.lowercase() }
                    val excluded = filter.state.filter { it.isExcluded() }.map { it.value.lowercase() }
                    if (included.isNotEmpty()) {
                        novels = novels.filter { novel -> included.all { tag -> novel.tags.any { it.equals(tag, ignoreCase = true) } } }
                    }
                    if (excluded.isNotEmpty()) {
                        novels = novels.filter { novel -> excluded.none { tag -> novel.tags.any { it.equals(tag, ignoreCase = true) } } }
                    }
                }

                is SortFilter -> if (selectedSort == null) {
                    selectedSort = when (filter.state) {
                        0 -> "reviewCount"
                        1 -> "created"
                        2 -> "avgReview"
                        3 -> "chapterCount"
                        else -> "reviewCount"
                    }
                }

                else -> {}
            }
        }

        val sorted = when (selectedSort ?: "reviewCount") {
            "created" -> novels.sortedByDescending { it.created ?: 0L }
            "avgReview" -> novels.sortedByDescending { it.avgReview ?: 0f }
            "chapterCount" -> novels.sortedByDescending { it.chapterCount ?: 0 }
            else -> novels.sortedByDescending { it.reviewCount ?: 0 }
        }

        // Paginate
        val startIndex = (page - 1) * perPage
        val endIndex = minOf(startIndex + perPage, sorted.size)
        val pageNovels = if (startIndex < sorted.size) sorted.subList(startIndex, endIndex).map { it.manga } else emptyList()
        val hasNext = endIndex < sorted.size

        return MangasPage(pageNovels, hasNext)
    }

    private fun createSMangaFromJson(obj: JsonObject): SManga = SManga.create().apply {
        val slug = obj["slug"]?.jsonPrimitive?.content ?: ""

        val titleRendered = obj["name"]?.jsonPrimitive?.content
            ?: obj["title"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content
            ?: obj["title"]?.jsonPrimitive?.contentOrNull
            ?: "Untitled"
        val contentRendered = obj["content"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content ?: ""
        val excerptRendered = obj["excerpt"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content ?: ""
        val synopsisText = obj["synopsis-text"]?.jsonPrimitive?.content
            ?: obj["synopsis"]?.jsonPrimitive?.contentOrNull
        val bookId = obj["bookid"]?.jsonPrimitive?.content
        val novelCode = obj["novel-code"]?.jsonPrimitive?.longOrNull
        val authorNameValue = obj["author-name"]?.jsonPrimitive?.content

        url = "/novel/$slug"
        title = cleanHtml(titleRendered)
        author = authorNameValue

        thumbnail_url = if (novelCode != null) {
            "$assetsSite/$novelCode.webp"
        } else if (!bookId.isNullOrBlank()) {
            "$assetsSite/$bookId.webp"
        } else {
            null
        }

        description = synopsisText?.let { cleanHtml(it) }
            ?: cleanHtml(excerptRendered.ifBlank { contentRendered })
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            title = doc.selectFirst("h1.novel-title")?.text() ?: "Untitled"

            val associatedNamesText = doc.select("div.additionalinfo.tm10 > div.textwrapper")
                .find { it.selectFirst("span")?.text()?.contains("Associated Names", ignoreCase = true) == true }
                ?.selectFirst("span:last-child, a")?.text()?.trim()

            var desc = doc.selectFirst("div.synopsis.w-richtext")?.text()?.trim() ?: ""
            if (!associatedNamesText.isNullOrBlank()) {
                // Split by common delimiters and clean
                val altTitles = associatedNamesText.split(",", ";", "/", "|")
                    .mapNotNull { it.trim().takeIf { s -> s.isNotBlank() && s != title } }
                    .distinct()
                if (altTitles.isNotEmpty()) {
                    desc = "Alternative Titles: ${altTitles.joinToString(", ")}\n\n$desc"
                }
            }

            description = desc
            author = doc.select("div.additionalinfo.tm10 > div.textwrapper")
                .find { it.selectFirst("span")?.text()?.contains("Author") == true }
                ?.selectFirst("a, span:last-child")?.text() ?: ""
            genre = doc.select(".genre-tags").map { it.text() }.joinToString(", ")
            status = when {
                doc.selectFirst(".novelstatustextlarge")?.text()?.contains("Ongoing", ignoreCase = true) == true -> SManga.ONGOING
                doc.selectFirst(".novelstatustextlarge")?.text()?.contains("Completed", ignoreCase = true) == true -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = doc.selectFirst("img.novel-image")?.attr("src")
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())

        val novelCode = doc.selectFirst("#novel-code")?.text()?.toLongOrNull() ?: return emptyList()
        val convertedId = convertNovelId(BigInteger.valueOf(novelCode))

        val chapters = mutableListOf<SChapter>()
        var page = 1
        var hasMore = true

        while (hasMore) {
            val chapResponse = client.newCall(
                GET("$chapSite/wp-json/wp/v2/posts?tags=$convertedId&per_page=500&page=$page", headers),
            ).execute()

            val chaptersJson = chapResponse.body.string()
            if (chaptersJson.isBlank() || chaptersJson == "[]") {
                hasMore = false
                continue
            }

            val chapData: List<ChapterPost> = json.decodeFromString(chaptersJson)

            if (chapData.isEmpty()) {
                hasMore = false
                continue
            }

            chapData.forEach { chap ->
                val acf = chap.acf ?: return@forEach
                val chapterName = acf.chName ?: "Chapter"
                val chapterNumberStr = acf.chapterNumber?.jsonPrimitive?.contentOrNull
                    ?: acf.chapterNumber?.jsonPrimitive?.intOrNull?.toString()
                    ?: ""
                val novelCodeStr = acf.novelCode?.jsonPrimitive?.content ?: ""

                chapters.add(
                    SChapter.create().apply {
                        url = "/chapter/$novelCodeStr-$chapterNumberStr"
                        name = chapterName
                        date_upload = parseDate(chap.date)
                        chapter_number = chapterNumberStr.toFloatOrNull() ?: 0f
                    },
                )
            }

            val totalPages = chapResponse.headers["X-Wp-Totalpages"]?.toIntOrNull() ?: 1
            hasMore = page < totalPages
            page++
        }

        return chapters.reversed()
    }

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString()
        return listOf(Page(0, chapterUrl))
    }

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) {
            page.url
        } else {
            "$chapSite${page.url}"
        }
        val response = client.newCall(GET(url, headers)).execute()
        val doc = Jsoup.parse(response.body.string())
        // Content is in #chapter-content #chapter based on API docs
        return doc.selectFirst("#chapter-content #chapter")?.html()
            ?: doc.selectFirst("#chapter")?.html()
            ?: doc.selectFirst(".ChapterContent")?.html()
            ?: ""
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Filters (all local)"),
        SortFilter(),
        Filter.Header("Include/Exclude Genres (Tap to toggle)"),
        GenreFilter(),
        Filter.Header("Include/Exclude Tags (Tap to toggle)"),
        TagFilter(),
    )

    private class SortFilter :
        Filter.Select<String>(
            "Sort by",
            arrayOf("Most Reviewed", "Latest Added", "Best Rated", "Chapter Count"),
        )

    private class GenreFilter :
        Filter.Group<GenreTriState>(
            "Genres",
            listOf(
                GenreTriState("Action"), GenreTriState("Adult"), GenreTriState("Adventure"),
                GenreTriState("Comedy"), GenreTriState("Drama"), GenreTriState("Ecchi"),
                GenreTriState("Fan-Fiction"), GenreTriState("Fantasy"), GenreTriState("Gender Bender"),
                GenreTriState("Harem"), GenreTriState("Historical"), GenreTriState("Horror"),
                GenreTriState("Josei"), GenreTriState("Martial Arts"), GenreTriState("Mature"),
                GenreTriState("Mecha"), GenreTriState("Mystery"), GenreTriState("Psychological"),
                GenreTriState("Romance"), GenreTriState("School Life"), GenreTriState("Sci-fi"),
                GenreTriState("Seinen"), GenreTriState("Shoujo"), GenreTriState("Shoujo Ai"),
                GenreTriState("Shounen"), GenreTriState("Shounen Ai"), GenreTriState("Slice of Life"),
                GenreTriState("Smut"), GenreTriState("Sports"), GenreTriState("Supernatural"),
                GenreTriState("Tragedy"), GenreTriState("Wuxia"), GenreTriState("Xianxia"),
                GenreTriState("Xuanhuan"), GenreTriState("Yaoi"), GenreTriState("Yuri"),
            ),
        )

    private class GenreTriState(val value: String) : Filter.TriState(value)

    private class TagFilter :
        Filter.Group<TagTriState>(
            "Tags",
            listOf(
                TagTriState("Academy"), TagTriState("Antihero Protagonist"),
                TagTriState("Beast Companions"), TagTriState("Calm Protagonist"),
                TagTriState("Cheats"), TagTriState("Clever Protagonist"),
                TagTriState("Cold Protagonist"), TagTriState("Cultivation"),
                TagTriState("Cunning Protagonist"), TagTriState("Dark"),
                TagTriState("Demons"), TagTriState("Dragons"), TagTriState("Dungeons"),
                TagTriState("Fantasy World"), TagTriState("Female Protagonist"),
                TagTriState("Game Elements"), TagTriState("Gods"),
                TagTriState("Hidden Abilities"), TagTriState("Level System"),
                TagTriState("Magic"), TagTriState("Male Protagonist"),
                TagTriState("Monsters"), TagTriState("Nobles"),
                TagTriState("Overpowered Protagonist"), TagTriState("Reincarnation"),
                TagTriState("Revenge"), TagTriState("Royalty"),
                TagTriState("Second Chance"), TagTriState("System"),
                TagTriState("Transmigration"), TagTriState("Weak to Strong"),
            ),
        )

    private class TagTriState(val value: String) : Filter.TriState(value)

    private fun convertNovelId(code: BigInteger): BigInteger {
        val t = BigInteger("1999999997")
        var u = BigInteger.ONE
        var c = BigInteger("7").mod(t)
        var d = code

        while (d > BigInteger.ZERO) {
            if (d and BigInteger.ONE == BigInteger.ONE) {
                u = u.multiply(c).mod(t)
            }
            c = c.multiply(c).mod(t)
            d = d.shiftRight(1)
        }

        return u
    }

    private fun parseDate(dateString: String?): Long {
        if (dateString == null) return 0L
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            format.parse(dateString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun parseCreatedDate(dateString: String?): Long {
        if (dateString == null) return 0L
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            format.parse(dateString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun cleanHtml(html: String): String = Jsoup.parse(html).text()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
    }

    companion object
}

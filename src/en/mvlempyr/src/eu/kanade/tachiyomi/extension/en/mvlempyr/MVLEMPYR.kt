package eu.kanade.tachiyomi.novelextension.en.mvlempyr

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.formattedText
import keiyoushi.utils.setAltTitles
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Response
import org.jsoup.Jsoup
import java.math.BigInteger
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class MVLEMPYR :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val supportsLatest = true

    private val perPage = 20

    /**
     * The site's novel detail URL shape, as `/novel/<slug>`. [SManga.url] is stored as the bare
     * slug (see [SlugPath]); a stored value starting with "/" is a pre-existing full-path entry
     * from before this source adopted slug storage, and is resolved unchanged regardless of
     * this template.
     */
    private val mangaPathTemplate: SlugPath = SlugPath("/novel/")

    // Chapter urls are "/chapter/<novelCode>-<chapterNumber>", independent of the novel's own slug.
    private val chapterPathTemplate: SlugPath = SlugPath("/chapter/")

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

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .add("Referer", chapSite)
        .add("Origin", chapSite)

    private val json: Json = Json { ignoreUnknownKeys = true }
    private val chapSite = "https://chap.heliosarchive.online"
    private val assetsSite = "https://assets.mvlempyr.app/images/600"

    // WordPress API Response structure
    @Serializable
    private class WpNovel(
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
    private class WpRendered(
        val rendered: String = "",
    )

    @Serializable
    private class ChapterPost(
        val id: Int = 0,
        val date: String? = null,
        val link: String? = null,
        val title: WpRendered = WpRendered(),
        val acf: ChapterAcf? = null,
    )

    @Serializable
    private class ChapterAcf(
        @SerialName("ch_name") val chName: String? = null,
        @SerialName("novel_code") val novelCode: JsonElement? = null,
        @SerialName("chapter_number") val chapterNumber: JsonElement? = null,
    )

    private fun buildNovelListUrl(): String = "$chapSite/wp-json/wp/v2/mvl-novels?per_page=10000"

    override suspend fun getPopularManga(page: Int): MangasPage {
        ensureCache(client.get(buildNovelListUrl(), headers))
        return getFilteredPage(page, "", FilterList())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        ensureCache(client.get(buildNovelListUrl(), headers))
        return getFilteredPage(page, "", FilterList(), sortBy = "created")
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        ensureCache(client.get(buildNovelListUrl(), headers))
        return getFilteredPage(page, query, filters)
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

        url = mangaPathTemplate.slug("/novel/$slug")
        title = cleanHtml(titleRendered)
        author = authorNameValue

        thumbnail_url = if (novelCode != null) {
            "$assetsSite/$novelCode.webp"
        } else if (!bookId.isNullOrBlank()) {
            "$assetsSite/$bookId.webp"
        } else {
            null
        }

        description = synopsisText?.let { formatDescription(it) }
            ?: formatDescription(excerptRendered.ifBlank { contentRendered })
    }

    private fun buildMangaDetailsUrl(manga: SManga): String = mangaPathTemplate.absolute(baseUrl, manga.url)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Details and the chapter list both live on the same novel page - fetch it once.
        val doc = client.get(buildMangaDetailsUrl(manga), headers).asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(doc) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(doc: org.jsoup.nodes.Document): SManga = SManga.create().apply {
        title = doc.selectFirst("h1.novel-title")?.text() ?: "Untitled"

        val associatedNamesText = doc.select("div.additionalinfo.tm10 > div.textwrapper")
            .find { it.selectFirst("span")?.text()?.contains("Associated Names", ignoreCase = true) == true }
            ?.selectFirst("span:last-child, a")?.text()

        description = doc.selectFirst("div.synopsis.w-richtext")?.formattedText()?.trim() ?: ""
        if (!associatedNamesText.isNullOrBlank()) {
            val altTitles = associatedNamesText.split(",", ";", "/", "|")
                .mapNotNull { it.trim().takeIf { s -> s.isNotBlank() && s != title } }
                .distinct()
            if (altTitles.isNotEmpty()) {
                setAltTitles(altTitles)
            }
        }
        author = doc.select("div.additionalinfo.tm10 > div.textwrapper")
            .find { it.selectFirst("span")?.text()?.contains("Author") == true }
            ?.selectFirst("a, span:last-child")?.text() ?: ""
        genre = doc.select(".genre-tags").map { it.text() }.joinToString()
        status = when {
            doc.selectFirst(".novelstatustextlarge")?.text()?.contains("Ongoing", ignoreCase = true) == true -> SManga.ONGOING
            doc.selectFirst(".novelstatustextlarge")?.text()?.contains("Completed", ignoreCase = true) == true -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        thumbnail_url = doc.selectFirst("img.novel-image")?.attr("src")
    }

    private suspend fun parseChapterList(doc: org.jsoup.nodes.Document): List<SChapter> {
        val novelCode = doc.selectFirst("#novel-code")?.text()?.toLongOrNull() ?: return emptyList()
        val convertedId = convertNovelId(BigInteger.valueOf(novelCode))

        val chapters = mutableListOf<SChapter>()
        var page = 1
        var hasMore = true

        while (hasMore) {
            val chapResponse = client.get(
                "$chapSite/wp-json/wp/v2/posts?tags=$convertedId&per_page=500&page=$page",
                headers,
            )

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
                        url = "$novelCodeStr-$chapterNumberStr"
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

        return chapters.sortedByDescending { it.chapter_number }
    }

    override fun getMangaUrl(manga: SManga): String = mangaPathTemplate.absolute(baseUrl, manga.url)

    override fun getChapterUrl(chapter: SChapter): String = chapterPathTemplate.absolute(baseUrl, chapter.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = mangaPathTemplate.slug(url.encodedPath)
        val tempManga = SManga.create().apply { this.url = slug }
        val response = client.get(buildMangaDetailsUrl(tempManga), headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        return parseMangaDetails(response.asJsoup()).apply { this.url = slug }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapterPathTemplate.resolve(chapter.url)))

    override suspend fun fetchPageText(page: Page): String {
        // Chapter text is served from the main site (the WP host is Cloudflare-blocked). The text
        // span has a dynamic id, so target it by class.
        val url = if (page.url.startsWith("http")) page.url else "$baseUrl${page.url}"
        val response = client.get(url, headers)
        val doc = response.asJsoup()
        val content = doc.selectFirst("#chapter .ct-span")
            ?: doc.selectFirst("#chapter .oxy-stock-content-styles")
            ?: doc.selectFirst("#chapter")
            ?: return ""
        content.select("script, style, ins, .adsbygoogle, .code-block, .ad").remove()
        return content.html()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
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
        return runCatching {
            LocalDateTime.parse(dateString, DATE_FORMATTER).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    private fun parseCreatedDate(dateString: String?): Long {
        if (dateString == null) return 0L
        return runCatching {
            LocalDateTime.parse(dateString, CREATED_DATE_FORMATTER).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    private fun cleanHtml(html: String): String = Jsoup.parse(html).text()

    private fun formatDescription(html: String): String = Jsoup.parse(html).formattedText()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
    }

    companion object {
        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        private val CREATED_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    }
}

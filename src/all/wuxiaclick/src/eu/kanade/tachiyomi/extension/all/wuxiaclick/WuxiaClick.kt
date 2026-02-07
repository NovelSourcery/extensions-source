package eu.kanade.tachiyomi.extension.all.wuxiaclick

import eu.kanade.tachiyomi.network.GET
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response

class WuxiaClick : HttpSource(), NovelSource {

    override val name = "WuxiaClick"
    override val baseUrl = "https://wuxia.click"
    private val apiUrl = "https://wuxiaworld.eu/api"
    override val lang = "all"
    override val supportsLatest = true
    override val isNovelSource = true

    override val client = network.cloudflareClient

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // Track the Next.js build ID for data fetching
    private var buildId: String? = null

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        val offset = (page - 1) * 12
        return GET("$apiUrl/search/?search=&offset=$offset&limit=12&order=-rating", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val searchResponse = json.decodeFromString<SearchResponse>(response.body.string())

        val novels = searchResponse.results.map { novel ->
            SManga.create().apply {
                url = "/novel/${novel.slug}"
                title = novel.name
                thumbnail_url = novel.image ?: ""
                author = novel.categories?.firstOrNull()?.name
                description = novel.description
                genre = novel.categories?.joinToString(", ") { it.name } ?: ""
                status = when {
                    novel.chapters >= (novel.numOfChaps ?: 0) -> SManga.COMPLETED
                    else -> SManga.ONGOING
                }
            }
        }

        val hasNextPage = searchResponse.next != null
        return MangasPage(novels, hasNextPage)
    }

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        val offset = (page - 1) * 12
        // Using -last_chapter since -updated_at is not a valid choice
        return GET("$apiUrl/search/?search=&offset=$offset&limit=12&order=-last_chapter", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var order = "-weekly_views"
        var category: String? = null
        var tag: String? = null

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> order = filter.pairValues[filter.state].second
                is CategoryFilter -> {
                    if (filter.state > 0) {
                        category = filter.pairValues[filter.state].second
                    }
                }
                is TagFilter -> {
                    if (filter.state.isNotBlank()) {
                        tag = filter.state.trim().lowercase().replace(" ", "-")
                    }
                }
                else -> {}
            }
        }

        // When browsing by category or tag (no text query), use /novels/ endpoint
        // which supports proper category_name/tag_name filtering
        val limit = 24
        val offset = (page - 1) * limit

        return if (query.isBlank() && !category.isNullOrEmpty()) {
            GET("$apiUrl/novels/?category_name=$category&limit=$limit&offset=$offset&order=$order", headers)
        } else if (query.isBlank() && !tag.isNullOrEmpty()) {
            GET("$apiUrl/novels/?tag_name=$tag&limit=$limit&offset=$offset&order=$order", headers)
        } else {
            // Text search uses the /search/ endpoint
            val searchOffset = (page - 1) * 12
            val url = buildString {
                append("$apiUrl/search/?search=${java.net.URLEncoder.encode(query, "UTF-8")}&offset=$searchOffset&limit=12&order=$order")
                if (!category.isNullOrEmpty()) append("&category=$category")
                if (!tag.isNullOrEmpty()) append("&tag=$tag")
            }
            GET(url, headers)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.removePrefix("/novel/")
        return GET("$apiUrl/novels/$slug/", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val novel = json.decodeFromString<NovelDetail>(response.body.string())

        return SManga.create().apply {
            url = "/novel/${novel.slug}"
            title = novel.name
            thumbnail_url = novel.image ?: novel.originalImage
            author = novel.author?.name
            description = buildString {
                append(novel.description)
                val otherNamesList = novel.getOtherNamesList()
                if (otherNamesList.isNotEmpty()) {
                    append("\n\nAlternative Names: ${otherNamesList.joinToString(", ")}")
                }
                novel.rating?.let { rating ->
                    append("\n\nRating: $rating")
                }
                novel.humanViews?.let { views ->
                    append("\nViews: $views")
                }
            }
            genre = buildString {
                novel.categories?.let { cats ->
                    append(cats.joinToString(", ") { it.name })
                }
                novel.tags?.let { tags ->
                    if (tags.isNotEmpty()) {
                        if (isNotEmpty()) append(", ")
                        append(tags.take(10).joinToString(", ") { it.name })
                    }
                }
            }
            status = when (novel.status?.uppercase()) {
                "OG" -> SManga.ONGOING
                "CP" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.removePrefix("/novel/")
        return GET("$apiUrl/chapters/$slug/", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = json.decodeFromString<List<ChapterInfo>>(response.body.string())

        return chapters.map { chapter ->
            SChapter.create().apply {
                url = "/chapter/${chapter.novSlugChapSlug}"
                name = chapter.title
                chapter_number = chapter.index.toFloat()
                date_upload = parseChapterDate(chapter.timeAdded)
            }
        } // API returns ascending (ch1, ch2...), Mihon expects descending (newest first)
    }

    // ======================== Pages ========================

    override fun pageListRequest(chapter: SChapter): Request {
        val slug = chapter.url.removePrefix("/chapter/")
        return GET("$apiUrl/getchapter/$slug/", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        return listOf(Page(0, response.request.url.toString()))
    }

    // ======================== Page Text (Novel) ========================

    override suspend fun fetchPageText(page: Page): String {
        val request = GET(page.url, headers)
        val response = client.newCall(request).execute()
        val chapter = json.decodeFromString<ChapterContent>(response.body.string())

        val content = StringBuilder()

        // Add title
        content.append("<h2>${chapter.title}</h2>\n")

        // Process text content
        val text = chapter.text

        // Split by lines and wrap in paragraphs
        text.split("\n").forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                content.append("<p>$trimmed</p>\n")
            }
        }

        return content.toString()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // ======================== Filters ========================

    override fun getFilterList(): FilterList {
        return FilterList(
            SortFilter("Sort By", sortOptions),
            Filter.Separator(),
            Filter.Header("Filter by Category (select one)"),
            CategoryFilter("Category", categoryOptions),
            Filter.Separator(),
            Filter.Header("Or filter by Tag (enter slug)"),
            TagFilter("Tag (e.g., male-protagonist)"),
        )
    }

    class SortFilter(name: String, internal val pairValues: Array<Pair<String, String>>) :
        Filter.Select<String>(name, pairValues.map { it.first }.toTypedArray())

    class CategoryFilter(name: String, internal val pairValues: Array<Pair<String, String>>) :
        Filter.Select<String>(name, pairValues.map { it.first }.toTypedArray())

    class TagFilter(name: String) : Filter.Text(name)

    private val sortOptions = arrayOf(
        Pair("Weekly Views", "-weekly_views"),
        Pair("Total Views", "-total_views"),
        Pair("Rating", "-rating"),
        Pair("Chapters", "-numOfChaps"),
        Pair("Recently Updated", "-last_chapter"),
        Pair("Newest", "-created_at"),
        Pair("Name A-Z", "name"),
        Pair("Name Z-A", "-name"),
    )

    private val categoryOptions = arrayOf(
        Pair("All Categories", ""),
        Pair("Action", "action"),
        Pair("Adult", "adult"),
        Pair("Adventure", "adventure"),
        Pair("Comedy", "comedy"),
        Pair("Drama", "drama"),
        Pair("Ecchi", "ecchi"),
        Pair("Fantasy", "fantasy"),
        Pair("Gender Bender", "gender-bender"),
        Pair("Harem", "harem"),
        Pair("Historical", "historical"),
        Pair("Horror", "horror"),
        Pair("Josei", "josei"),
        Pair("Martial Arts", "martial-arts"),
        Pair("Mature", "mature"),
        Pair("Mecha", "mecha"),
        Pair("Mystery", "mystery"),
        Pair("Psychological", "psychological"),
        Pair("Romance", "romance"),
        Pair("School Life", "school-life"),
        Pair("Sci-fi", "sci-fi"),
        Pair("Seinen", "seinen"),
        Pair("Shoujo", "shoujo"),
        Pair("Shoujo Ai", "shoujo-ai"),
        Pair("Shounen", "shounen"),
        Pair("Shounen Ai", "shounen-ai"),
        Pair("Slice of Life", "slice-of-life"),
        Pair("Smut", "smut"),
        Pair("Sports", "sports"),
        Pair("Supernatural", "supernatural"),
        Pair("Tragedy", "tragedy"),
        Pair("Wuxia", "wuxia"),
        Pair("Xianxia", "xianxia"),
        Pair("Xuanhuan", "xuanhuan"),
        Pair("Yaoi", "yaoi"),
        Pair("Yuri", "yuri"),
    )

    // ======================== Helpers ========================

    private fun parseChapterDate(dateString: String?): Long {
        if (dateString.isNullOrEmpty()) return 0L
        return try {
            val months = mapOf(
                "January" to 0, "February" to 1, "March" to 2, "April" to 3,
                "May" to 4, "June" to 5, "July" to 6, "August" to 7,
                "September" to 8, "October" to 9, "November" to 10, "December" to 11,
            )
            val parts = dateString.split(" ")
            if (parts.size >= 3) {
                val month = months[parts[0]] ?: 0
                val day = parts[1].toIntOrNull() ?: 1
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

    // ======================== Data Classes ========================

    @Serializable
    data class SearchResponse(
        val count: Int,
        val next: String? = null,
        val previous: String? = null,
        val results: List<NovelSearchResult>,
    )

    @Serializable
    data class NovelSearchResult(
        val name: String,
        val image: String? = null,
        val slug: String,
        val description: String? = null,
        val rating: String? = null,
        val ranking: Int? = null,
        val views: String? = null,
        val chapters: Int = 0,
        val categories: List<Category>? = null,
        val tags: List<Tag>? = null,
        val numOfChaps: Int? = null,
    )

    @Serializable
    data class NovelDetail(
        val slug: String,
        val name: String,
        val description: String? = null,
        val image: String? = null,
        @SerialName("original_image") val originalImage: String? = null,
        val author: Author? = null,
        val categories: List<Category>? = null,
        val tags: List<Tag>? = null,
        val views: String? = null,
        @SerialName("human_views") val humanViews: String? = null,
        val chapters: Int = 0,
        val rating: String? = null,
        val status: String? = null,
        @SerialName("other_names") val otherNames: JsonElement? = null, // Can be array or empty object
        @SerialName("numOfChaps") val numOfChaps: Int? = null,
    ) {
        // Helper to get other names as list regardless of JSON type
        fun getOtherNamesList(): List<String> {
            return try {
                otherNames?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
            } catch (e: Exception) {
                // If it's not an array (e.g., empty object {}), return empty list
                emptyList()
            }
        }
    }

    @Serializable
    data class Author(
        val name: String,
        val slug: String? = null,
    )

    @Serializable
    data class Category(
        val name: String,
        val slug: String,
        val title: String? = null,
    )

    @Serializable
    data class Tag(
        val id: Int? = null,
        val name: String,
        val slug: String,
        val title: String? = null,
    )

    @Serializable
    data class ChapterInfo(
        val id: Int,
        val index: Int,
        val title: String,
        val novSlugChapSlug: String,
        val timeAdded: String? = null,
    )

    @Serializable
    data class ChapterContent(
        val index: Int? = null,
        val title: String,
        val text: String,
    )
}

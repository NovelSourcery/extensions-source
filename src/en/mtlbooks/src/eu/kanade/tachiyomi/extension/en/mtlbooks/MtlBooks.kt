package eu.kanade.tachiyomi.extension.en.mtlbooks

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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class MtlBooks :
    HttpSource(),
    NovelSource {

    override val name = "MtlBooks"
    override val baseUrl = "https://mtlbooks.com"
    private val apiUrl = "https://alpha.mtlbooks.com/api/v1"
    private val imageProxy = "https://wsrv.nl"
    override val lang = "en"
    override val supportsLatest = true
    override val isNovelSource = true

    private val json: Json by injectLazy()

    override val client = network.cloudflareClient
    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiUrl/search/?page=$page&order=popular&sort=DESC&source=all"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val apiResponse = json.decodeFromString<SearchResponse>(response.body.string())

        val novels = apiResponse.result.data.map { novel ->
            SManga.create().apply {
                url = "/novel/${novel.slug}"
                title = novel.name
                thumbnail_url = buildImageUrl(novel.thumbnail)
                author = novel.users?.name
                description = novel.description
                genre = (novel.genres + novel.tags).joinToString(", ")
                status = when (novel.status.lowercase()) {
                    "ongoing" -> SManga.ONGOING
                    "completed" -> SManga.COMPLETED
                    "hiatus" -> SManga.ON_HIATUS
                    else -> SManga.UNKNOWN
                }
            }
        }

        val hasNextPage = novels.size >= 20
        return MangasPage(novels, hasNextPage)
    }
    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/search/?page=$page&order=recent&sort=DESC&source=all"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)
    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val params = mutableListOf<String>()
        params.add("page=$page")

        if (query.isNotBlank()) {
            params.add("q=${java.net.URLEncoder.encode(query, "UTF-8")}")
        }

        var sortOrder = "DESC"
        var orderBy = "recent"

        val includeGenres = mutableListOf<String>()
        val includeTags = mutableListOf<String>()
        val excludeTags = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        var wordCount: String? = null

        filters.forEach { filter ->
            when (filter) {
                is OrderFilter -> orderBy = orderOptions[filter.state].second

                is SortFilter -> sortOrder = sortOptions[filter.state].second

                is WordCountFilter -> {
                    if (filter.state > 0) {
                        wordCount = wordCountOptions[filter.state].second
                    }
                }

                is GenreFilter -> {
                    filter.state.forEachIndexed { index, checkbox ->
                        if (checkbox.state) {
                            includeGenres.add(genreList[index])
                        }
                    }
                }

                is TagIncludeFilter -> {
                    filter.state.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                        includeTags.add(it)
                    }
                }

                is TagExcludeFilter -> {
                    filter.state.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                        excludeTags.add(it)
                    }
                }

                is StatusFilter -> {
                    filter.state.forEachIndexed { index, checkbox ->
                        if (checkbox.state) {
                            statuses.add(statusList[index])
                        }
                    }
                }

                else -> {}
            }
        }

        params.add("order=$orderBy")
        params.add("sort=$sortOrder")
        params.add("source=all")

        if (wordCount != null) {
            params.add("wordcount=$wordCount")
        }

        if (includeGenres.isNotEmpty()) {
            params.add("include_genres=${includeGenres.joinToString(",")}")
        }

        if (includeTags.isNotEmpty()) {
            params.add("include_tags=${includeTags.joinToString(",")}")
        }

        if (excludeTags.isNotEmpty()) {
            params.add("exclude_tags=${excludeTags.joinToString(",")}")
        }

        if (statuses.isNotEmpty()) {
            params.add("status=${statuses.joinToString(",")}")
        }

        val url = "$apiUrl/search/?${params.joinToString("&")}"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)
    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.substringAfter("/novel/")
        return GET("$apiUrl/novels/$slug", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val apiResponse = json.decodeFromString<NovelDetailResponse>(response.body.string())
        val novel = apiResponse.result

        return SManga.create().apply {
            url = "/novel/${novel.slug}"
            title = novel.name
            thumbnail_url = buildImageUrl(novel.thumbnail)
            author = novel.users?.name
            description = novel.description
            // Include both genres and tags
            genre = (novel.genres + novel.tags).joinToString(", ")
            status = when (novel.status.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }
    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.substringAfter("/novel/")
        val body = json.encodeToString(
            ChapterListRequest.serializer(),
            ChapterListRequest(slug, 1, "ASC"),
        ).toRequestBody("application/json".toMediaType())
        return POST("$apiUrl/chapters/list", headers, body)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        val slug = response.request.url.toString().substringAfter("novel_slug=").substringBefore("&")

        val firstPage = json.decodeFromString<ChapterListResponse>(response.body.string())
        val totalPages = (firstPage.result.pagination.total + firstPage.result.pagination.limit - 1) / firstPage.result.pagination.limit
        val novelSlug = firstPage.result.novelSlug

        firstPage.result.chapterLists.forEach { ch ->
            chapters.add(
                SChapter.create().apply {
                    url = "/novel/$novelSlug/chapter/${ch.chapterSlug}"
                    name = ch.chapterTitle
                    chapter_number = ch.chapterNumber.toFloat()
                },
            )
        }

        for (page in 2..totalPages) {
            try {
                val body = json.encodeToString(
                    ChapterListRequest.serializer(),
                    ChapterListRequest(novelSlug, page, "ASC"),
                ).toRequestBody("application/json".toMediaType())

                val pageResponse = client.newCall(POST("$apiUrl/chapters/list", headers, body)).execute()
                val pageData = json.decodeFromString<ChapterListResponse>(pageResponse.body.string())

                pageData.result.chapterLists.forEach { ch ->
                    chapters.add(
                        SChapter.create().apply {
                            url = "/novel/$novelSlug/chapter/${ch.chapterSlug}"
                            name = ch.chapterTitle
                            chapter_number = ch.chapterNumber.toFloat()
                        },
                    )
                }
            } catch (_: Exception) {
                break
            }
        }

        return chapters.reversed()
    }
    // ======================== Pages ========================

    override fun pageListRequest(chapter: SChapter): Request {
        val parts = chapter.url.split("/")
        val novelSlug = parts.getOrNull(2) ?: ""
        val chapterSlug = parts.getOrNull(4) ?: ""

        val body = json.encodeToString(
            ChapterReadRequest.serializer(),
            ChapterReadRequest(novelSlug, chapterSlug),
        ).toRequestBody("application/json".toMediaType())

        return POST("$apiUrl/chapters/read", headers, body)
    }

    override fun pageListParse(response: Response): List<Page> {
        val chapterResponse = json.decodeFromString<ChapterReadResponse>(response.body.string())
        val novelSlug = chapterResponse.result.novelSlug
        val chapterSlug = chapterResponse.result.chapter.chapterSlug

        return listOf(Page(0, "mtlbooks://$novelSlug/$chapterSlug"))
    }
    // ======================== Page Text (Novel) ========================

    override suspend fun fetchPageText(page: Page): String {
        val cleanUrl = page.url.removePrefix("mtlbooks://")
        val parts = cleanUrl.split("/")
        val novelSlug = parts.getOrNull(0) ?: ""
        val chapterSlug = parts.getOrNull(1) ?: ""

        val body = json.encodeToString(
            ChapterReadRequest.serializer(),
            ChapterReadRequest(novelSlug, chapterSlug),
        ).toRequestBody("application/json".toMediaType())

        val response = client.newCall(POST("$apiUrl/chapters/read", headers, body)).execute()
        val responseBody = response.body.string()

        return try {
            val chapterResponse = json.decodeFromString<ChapterReadResponse>(responseBody)
            val rawContent = chapterResponse.result.chapter.content

            if (rawContent.isNullOrBlank()) {
                "<p>No content available for this chapter.</p>"
            } else {
                val content = StringBuilder()
                // Split by newlines and wrap in paragraphs
                rawContent.split("\n").filter { it.isNotBlank() }.forEach { line ->
                    content.append("<p>${line.trim()}</p>\n")
                }
                content.toString().ifEmpty { "<p>No content available.</p>" }
            }
        } catch (e: Exception) {
            "<p>Error loading chapter: ${e.message}</p>"
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")
    // ======================== Filters ========================

    override fun getFilterList(): FilterList = FilterList(
        OrderFilter("Order By", orderOptions.map { it.first }.toTypedArray()),
        SortFilter("Sort", sortOptions.map { it.first }.toTypedArray()),
        WordCountFilter("Word Count", wordCountOptions.map { it.first }.toTypedArray()),
        Filter.Separator(),
        Filter.Header("Genres (select multiple)"),
        GenreFilter("Genres", genreList),
        Filter.Separator(),
        Filter.Header("Status (select multiple)"),
        StatusFilter("Status", statusList),
        Filter.Separator(),
        Filter.Header("Tags (comma-separated)"),
        TagIncludeFilter("Include Tags"),
        TagExcludeFilter("Exclude Tags"),
    )

    class OrderFilter(name: String, values: Array<String>) : Filter.Select<String>(name, values)
    class SortFilter(name: String, values: Array<String>) : Filter.Select<String>(name, values)
    class WordCountFilter(name: String, values: Array<String>) : Filter.Select<String>(name, values)
    class TagIncludeFilter(name: String) : Filter.Text(name)
    class TagExcludeFilter(name: String) : Filter.Text(name)

    class GenreFilter(name: String, genres: List<String>) :
        Filter.Group<Filter.CheckBox>(
            name,
            genres.map { GenreCheckBox(it) },
        )
    class GenreCheckBox(name: String) : Filter.CheckBox(name)

    class StatusFilter(name: String, statuses: List<String>) :
        Filter.Group<Filter.CheckBox>(
            name,
            statuses.map { StatusCheckBox(it) },
        )
    class StatusCheckBox(name: String) : Filter.CheckBox(name)

    private val orderOptions = listOf(
        Pair("Recent", "recent"),
        Pair("Popular", "popular"),
    )

    private val sortOptions = listOf(
        Pair("Descending", "DESC"),
        Pair("Ascending", "ASC"),
    )

    private val wordCountOptions = listOf(
        Pair("Unlimited", ""),
        Pair("< 100k", "0-100k"),
        Pair("100k - 200k", "100k-200k"),
        Pair("200k - 500k", "200k-500k"),
        Pair("500k - 800k", "500k-800k"),
        Pair("800k - 1M", "800k-1M"),
        Pair("> 1M", "1M+"),
    )

    private val genreList = listOf(
        "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Fan-Fiction",
        "Historical", "Josei", "Psychological", "Romance", "School Life",
        "Sci-fi", "Shoujo", "Slice Of Life", "Supernatural", "Urban",
        "Virtual Reality", "Xianxia", "Yaoi", "Adult", "Harem",
        "Fantasy Romance", "Game", "Gender Bender", "Horror", "Magic",
        "Martial Arts", "Marvel", "Mature", "Mecha", "Mystery",
        "Reincarnation", "Seinen", "Shounen", "Smut", "Sports",
        "Tragedy", "Wuxia", "Xuanhuan", "Yuri",
    )

    private val statusList = listOf(
        "Completed",
        "Ongoing",
        "Hiatus",
    )
    // ======================== Helpers ========================

    private fun buildImageUrl(thumbnail: String?): String? {
        if (thumbnail.isNullOrEmpty()) return null
        return "$imageProxy/?url=https://cdn.mtlbooks.com/poster/$thumbnail&w=300&h=400&fit=cover&output=webp&maxage=3M"
    }

    // ======================== Data Classes ========================

    @Serializable
    data class SearchResponse(
        val status: Int,
        val result: SearchResult,
    )

    @Serializable
    data class SearchResult(
        val data: List<NovelItem>,
    )

    @Serializable
    data class NovelItem(
        val name: String,
        val slug: String,
        @SerialName("alt_name") val altName: List<String> = emptyList(),
        val description: String? = null,
        val status: String,
        val thumbnail: String? = null,
        val genres: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val wordcount: Int = 0,
        val chaptercount: Int = 0,
        val users: AuthorInfo? = null,
    )

    @Serializable
    data class AuthorInfo(
        val id: Int? = null,
        val name: String? = null,
    )

    @Serializable
    data class NovelDetailResponse(
        val status: Int,
        val result: NovelDetail,
    )

    @Serializable
    data class NovelDetail(
        val id: Int,
        val name: String,
        val slug: String,
        val description: String? = null,
        val status: String,
        val thumbnail: String? = null,
        val genres: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val wordcount: Int = 0,
        val chaptercount: Int = 0,
        val users: AuthorInfo? = null,
    )

    @Serializable
    data class ChapterListRequest(
        @SerialName("novel_slug") val novelSlug: String,
        val page: Int,
        val order: String,
    )

    @Serializable
    data class ChapterListResponse(
        val status: Int,
        val result: ChapterListResult,
    )

    @Serializable
    data class ChapterListResult(
        @SerialName("novel_slug") val novelSlug: String,
        @SerialName("total_chapters") val totalChapters: Int,
        @SerialName("chapter_lists") val chapterLists: List<ChapterItem>,
        val pagination: Pagination,
    )

    @Serializable
    data class ChapterItem(
        @SerialName("chapter_number") val chapterNumber: Int,
        @SerialName("chapter_title") val chapterTitle: String,
        @SerialName("chapter_slug") val chapterSlug: String,
    )

    @Serializable
    data class Pagination(
        val page: Int,
        val limit: Int,
        val total: Int,
    )

    @Serializable
    data class ChapterReadRequest(
        @SerialName("novel_slug") val novelSlug: String,
        @SerialName("chapter_slug") val chapterSlug: String,
    )

    @Serializable
    data class ChapterReadResponse(
        val status: Int,
        val result: ChapterReadResult,
    )

    @Serializable
    data class ChapterReadResult(
        @SerialName("novel_slug") val novelSlug: String,
        val chapter: ChapterContent,
    )

    @Serializable
    data class ChapterContent(
        @SerialName("chapter_number") val chapterNumber: Int,
        @SerialName("chapter_title") val chapterTitle: String,
        @SerialName("chapter_slug") val chapterSlug: String,
        val content: String? = null,
    )
}

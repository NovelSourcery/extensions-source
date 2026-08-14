package eu.kanade.tachiyomi.novelextension.en.mtlbooks

import android.util.Log
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.lib.chapterutils.incrementalStartPage
import keiyoushi.lib.chapterutils.mergeChapters
import keiyoushi.lib.chapterutils.shouldReturnExisting
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.jsonInstance
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

@Source
abstract class MtlBooks :
    KeiSource(),
    NovelSource {

    private val apiUrl = "https://alpha.mtlbooks.com/api/v1"
    private val imageProxy = "https://wsrv.nl"
    private val mangaPath = SlugPath("/novel/")

    private val json: Json = jsonInstance

    // ======================== Popular ========================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$apiUrl/search/?page=$page&order=popular&sort=DESC&source=all"
        return parseSearchResponse(client.newCall(GET(url, headers)).execute())
    }

    private fun parseSearchResponse(response: Response): MangasPage {
        val apiResponse = json.decodeFromString<SearchResponse>(response.body.string())

        val novels = apiResponse.result.data.map { novel ->
            SManga.create().apply {
                url = novel.slug
                title = novel.name
                thumbnail_url = buildImageUrl(novel.thumbnail)
                author = novel.users?.name
                description = novel.description
                genre = (novel.genres + novel.tags).joinToString()
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

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$apiUrl/search/?page=$page&order=recent&sort=DESC&source=all"
        return parseSearchResponse(client.newCall(GET(url, headers)).execute())
    }
    // ======================== Search ========================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.toString().substringAfter("/novel/", "")
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
            .substringBefore('/')
        if (slug.isBlank()) return null
        return try {
            parseMangaDetails(client.newCall(GET("$apiUrl/novels/$slug", headers)).execute().body.string())
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
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
        return parseSearchResponse(client.newCall(GET(url, headers)).execute())
    }
    // ======================== Details ========================

    private fun buildMangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.substringAfter("/novel/")
        return GET("$apiUrl/novels/$slug", headers)
    }

    // Webview should open the site page, not the JSON API endpoint
    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPath.resolve(manga.url)

    // chapter.url is the site path; strip the "/chapter/" segment of legacy entries
    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url.replace("/chapter/", "/")

    private fun parseMangaDetails(body: String): SManga {
        val apiResponse = json.decodeFromString<NovelDetailResponse>(body)
        val novel = apiResponse.result

        return SManga.create().apply {
            url = novel.slug
            title = novel.name
            thumbnail_url = buildImageUrl(novel.thumbnail)
            author = novel.users?.name
            description = novel.description
            // Include both genres and tags
            genre = (novel.genres + novel.tags).joinToString()
            status = when (novel.status.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }
    // ======================== Chapters ========================

    private fun buildChapterListRequest(slug: String, page: Int = 1): Request {
        val body = json.encodeToString(
            ChapterListRequest.serializer(),
            ChapterListRequest(slug, page, "ASC"),
        ).toRequestBody("application/json".toMediaType())
        return POST("$apiUrl/chapters/list", headers, body)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        // Details and chapters come from different endpoints - fire both concurrently when both
        // flags are set, rather than awaiting them sequentially.
        val detailsDeferred = if (fetchDetails) {
            async { parseMangaDetails(client.newCall(buildMangaDetailsRequest(manga)).execute().body.string()) }
        } else {
            null
        }
        val chaptersDeferred = if (fetchChapters) async { fetchChapterList(manga, chapters) } else null

        SMangaUpdate(
            manga = detailsDeferred?.await() ?: manga,
            chapters = chaptersDeferred?.await() ?: chapters,
        )
    }

    private fun fetchChapterList(manga: SManga, existingChapters: List<SChapter>): List<SChapter> {
        val slug = manga.url.substringAfter("/novel/")

        // Page 1 is always required for the total chapter count. The API sometimes returns a
        // result missing the expected fields instead of an HTTP error - fall back to the cached
        // list rather than crash when we have one.
        val page1Response = client.newCall(buildChapterListRequest(slug)).execute()
        val page1Body = page1Response.body.string()
        val page1 = try {
            json.decodeFromString<ChapterListResponse>(page1Body)
        } catch (e: Exception) {
            if (existingChapters.isNotEmpty()) {
                Log.w(TAG, "fetchChapterList: page 1 decode failed (${page1Response.code}), keeping existing - ${e.message}")
                return existingChapters
            }
            throw Exception("MTLBooks chapter list error (${page1Response.code}): ${page1Body.take(200)}")
        }
        val pagination = page1.result.pagination
        val total = pagination.total
        val limit = pagination.limit
        val totalPages = (total + limit - 1) / limit
        val novelSlug = page1.result.novelSlug

        Log.d(TAG, "fetchChapterList: slug=$slug existing=${existingChapters.size} siteTotal=$total totalPages=$totalPages")

        if (shouldReturnExisting(existingChapters.size, total)) {
            Log.d(TAG, "fetchChapterList: count unchanged — returning existing")
            return existingChapters
        }

        val existingCount = existingChapters.size
        val startPage = if (existingCount > 0) incrementalStartPage(existingCount, limit) else 1
        val keepCount = (startPage - 1) * limit
        Log.d(TAG, "fetchChapterList: startPage=$startPage keepCount=$keepCount")

        val freshChapters = mutableListOf<SChapter>()

        // Reuse already-fetched page 1 data if it falls within the fresh range.
        if (startPage == 1) {
            page1.result.chapterLists.forEach { freshChapters.add(chapterItemToSChapter(novelSlug, it)) }
        }

        for (page in maxOf(startPage, 2)..totalPages) {
            try {
                val pageData = json.decodeFromString<ChapterListResponse>(
                    client.newCall(buildChapterListRequest(novelSlug, page)).execute().body.string(),
                )
                pageData.result.chapterLists.forEach { freshChapters.add(chapterItemToSChapter(novelSlug, it)) }
            } catch (e: Exception) {
                Log.w(TAG, "fetchChapterList: page $page failed — ${e.message}")
                break
            }
        }

        Log.d(TAG, "fetchChapterList: fresh=${freshChapters.size} keep=$keepCount")
        return mergeChapters(existingChapters, freshChapters, keepCount).reversed()
    }

    private fun chapterItemToSChapter(novelSlug: String, ch: ChapterItem): SChapter = SChapter.create().apply {
        url = "/novel/$novelSlug/${ch.chapterSlug}"
        name = ch.chapterTitle
        chapter_number = ch.chapterNumber.toFloat()
    }
    // ======================== Pages ========================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // "/novel/<slug>/<chapterSlug>" (current) or "/novel/<slug>/chapter/<chapterSlug>" (legacy)
        val parts = chapter.url.trim('/').split("/")
        val novelSlug = parts.getOrNull(1) ?: ""
        val chapterSlug = parts.lastOrNull() ?: ""

        val body = json.encodeToString(
            ChapterReadRequest.serializer(),
            ChapterReadRequest(novelSlug, chapterSlug),
        ).toRequestBody("application/json".toMediaType())

        val response = client.newCall(POST("$apiUrl/chapters/read", headers, body)).execute()
        val chapterResponse = json.decodeFromString<ChapterReadResponse>(response.body.string())
        val resolvedNovelSlug = chapterResponse.result.novelSlug
        val resolvedChapterSlug = chapterResponse.result.chapter.chapterSlug

        return listOf(Page(0, "mtlbooks://$resolvedNovelSlug/$resolvedChapterSlug"))
    }
    // ======================== Page Text (Novel) ========================

    override suspend fun fetchPageText(page: Page): String {
        // chapter.url shape: /novel/{novelSlug}/{chapterSlug}, or legacy
        // /novel/{novelSlug}/chapter/{chapterSlug}. Take the last segment for the chapter slug so
        // the legacy "chapter" path piece never gets sent as the slug itself.
        val parts = page.url.trim('/').split("/")
        val novelSlug = parts.getOrNull(1) ?: ""
        val chapterSlug = parts.lastOrNull() ?: ""

        val body = json.encodeToString(
            ChapterReadRequest.serializer(),
            ChapterReadRequest(novelSlug, chapterSlug),
        ).toRequestBody("application/json".toMediaType())

        val response = client.newCall(POST("$apiUrl/chapters/read", headers, body)).execute()
        val responseBody = response.body.string()
        if (!response.isSuccessful) {
            throw Exception("MTLBooks server error (${response.code}): ${responseBody.take(200)}")
        }

        val chapterResponse = json.decodeFromString<ChapterReadResponse>(responseBody)
        val rawContent = chapterResponse.result.chapter.content

        return if (rawContent.isNullOrBlank()) {
            "<p>No content available for this chapter.</p>"
        } else {
            buildString {
                rawContent.split("\n").filter { it.isNotBlank() }.forEach { line ->
                    append("<p>${line.trim()}</p>\n")
                }
            }.ifEmpty { "<p>No content available.</p>" }
        }
    }

    // ======================== Filters ========================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
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
    class SearchResponse(
        val status: Int,
        val result: SearchResult,
    )

    @Serializable
    class SearchResult(
        val data: List<NovelItem>,
    )

    @Serializable
    class NovelItem(
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
    class AuthorInfo(
        val id: Int? = null,
        val name: String? = null,
    )

    @Serializable
    class NovelDetailResponse(
        val status: Int,
        val result: NovelDetail,
    )

    @Serializable
    class NovelDetail(
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
    class ChapterListRequest(
        @SerialName("novel_slug") private val novelSlug: String,
        private val page: Int,
        private val order: String,
    )

    @Serializable
    class ChapterListResponse(
        val status: Int,
        val result: ChapterListResult,
    )

    @Serializable
    class ChapterListResult(
        @SerialName("novel_slug") val novelSlug: String,
        @SerialName("total_chapters") private val totalChapters: Int,
        @SerialName("chapter_lists") val chapterLists: List<ChapterItem>,
        val pagination: Pagination,
    )

    @Serializable
    class ChapterItem(
        @SerialName("chapter_number") val chapterNumber: Int,
        @SerialName("chapter_title") val chapterTitle: String,
        @SerialName("chapter_slug") val chapterSlug: String,
    )

    @Serializable
    class Pagination(
        private val page: Int,
        val limit: Int,
        val total: Int,
    )

    @Serializable
    class ChapterReadRequest(
        @SerialName("novel_slug") private val novelSlug: String,
        @SerialName("chapter_slug") private val chapterSlug: String,
    )

    @Serializable
    class ChapterReadResponse(
        val status: Int,
        val result: ChapterReadResult,
    )

    @Serializable
    class ChapterReadResult(
        @SerialName("novel_slug") val novelSlug: String,
        val chapter: ChapterContent,
    )

    @Serializable
    class ChapterContent(
        @SerialName("chapter_number") private val chapterNumber: Int,
        @SerialName("chapter_title") private val chapterTitle: String,
        @SerialName("chapter_slug") val chapterSlug: String,
        val content: String? = null,
    )

    companion object {
        private const val TAG = "MtlBooks"
    }
}

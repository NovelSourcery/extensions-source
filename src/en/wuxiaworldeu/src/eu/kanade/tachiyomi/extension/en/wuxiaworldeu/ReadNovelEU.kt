package eu.kanade.tachiyomi.novelextension.en.wuxiaworldeu

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.setAltTitles
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Entities
import kotlin.time.Instant

@Source
abstract class ReadNovelEU :
    KeiSource(),
    NovelSource {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun extractNovelSlug(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return when {
            trimmed.contains("/api/novels/") -> trimmed.substringAfter("/api/novels/").substringBefore("/")
            trimmed.contains("/novel/") -> trimmed.substringAfter("/novel/").substringBefore("/")
            else -> trimmed.removePrefix("/").substringAfterLast("/")
        }
    }

    private fun extractChapterSlug(rawUrl: String): String = rawUrl.trim().trimEnd('/')
        .substringAfter("/getchapter/")
        .substringAfter("/chapter/")
        .removePrefix("/")

    private inline fun <reified T> Response.pageProps(): T {
        val data = Jsoup.parse(body.string()).selectFirst("script#__NEXT_DATA__")?.data()
            ?: throw Exception("Missing page data")
        val props = json.parseToJsonElement(data).jsonObject["props"]!!.jsonObject["pageProps"]!!
        return json.decodeFromJsonElement(props)
    }

    // ======================== Browse / Search ========================

    override suspend fun getPopularManga(page: Int): MangasPage = browse(page, "popular")

    override suspend fun getLatestUpdates(page: Int): MangasPage = browse(page, "latest")

    private suspend fun browse(page: Int, orderBy: String, params: Map<String, String> = emptyMap()): MangasPage {
        val url = "$baseUrl/browse".toHttpUrl().newBuilder()
            .addQueryParameter("order_by", orderBy)
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .addQueryParameter("page", page.toString())
            .build()
        val listing = client.get(url, headers, Fetch.DOCUMENT).pageProps<BrowseProps>().listing
        return MangasPage(listing.items.map { it.toSManga() }, listing.page < listing.totalPages)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val url = "$baseUrl/search".toHttpUrl().newBuilder()
                .addPathSegment(query.trim())
                .addQueryParameter("page", page.toString())
                .build()
            val result = client.get(url, headers, Fetch.DOCUMENT).pageProps<SearchProps>().result
            return MangasPage(result.items.map { it.toSManga() }, result.page < result.totalPages)
        }

        var orderBy = "popular"
        val params = mutableMapOf<String, String>()
        filters.forEach { filter ->
            when (filter) {
                is SelectFilter -> {
                    val value = filter.selected
                    if (filter.key == "order_by") {
                        orderBy = value
                    } else if (value.isNotEmpty()) {
                        params[filter.key] = value
                    }
                }
                is TagFilter -> {
                    val tag = filter.state.trim().lowercase().replace(" ", "-")
                    if (tag.isNotEmpty()) params["tag"] = tag
                }
                else -> {}
            }
        }
        return browse(page, orderBy, params)
    }

    private fun NovelItem.toSManga(): SManga = SManga.create().apply {
        url = slug
        title = this@toSManga.title.trim()
        thumbnail_url = coverUrl(coverKey, coverOriginal)
        author = authorName
        status = parseStatus(this@toSManga.status)
    }

    private fun coverUrl(key: String?, original: String?): String? = when {
        !key.isNullOrBlank() -> "$CDN_URL/$key-480.webp"
        original?.startsWith("http") == true -> original
        else -> null
    }

    private fun parseStatus(status: String?): Int = when (status?.uppercase()) {
        "ONGOING" -> SManga.ONGOING
        "COMPLETED" -> SManga.COMPLETED
        "HIATUS" -> SManga.ON_HIATUS
        "DROPPED" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // ======================== Details + Chapters ========================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novel/${extractNovelSlug(manga.url)}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/chapter/${extractChapterSlug(chapter.url)}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = extractNovelSlug(url.encodedPath)
        return try {
            fetchNovel(slug).novel.toDetails()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchNovel(slug: String): NovelProps = client.get("$baseUrl/novel/$slug", headers, Fetch.DOCUMENT).pageProps()

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val props = fetchNovel(extractNovelSlug(manga.url))
        return SMangaUpdate(
            manga = if (fetchDetails) props.novel.toDetails() else manga,
            chapters = if (fetchChapters) fetchChapterList(props) else chapters,
        )
    }

    private fun NovelDetail.toDetails(): SManga = SManga.create().apply {
        url = slug
        title = this@toDetails.title.trim()
        thumbnail_url = coverUrl(coverKey, coverOriginal)
        author = this@toDetails.author?.name
        description = this@toDetails.description?.trim()
        genre = (genres.map { it.name } + tags.map { it.name }).distinct().joinToString()
        status = parseStatus(this@toDetails.status)
        setAltTitles(altTitles.filter { !it.equals(this@toDetails.title, ignoreCase = true) })
    }

    private suspend fun fetchChapterList(props: NovelProps): List<SChapter> {
        val novel = props.novel
        val pages = props.toc.ranges.map { it.page }.distinct().sorted()
        val loaded = mutableMapOf<Int, List<TocItem>>()
        if (props.toc.order == "asc") loaded[props.toc.range.page] = props.toc.items

        pages.filter { it !in loaded }.chunked(TOC_BATCH).forEach { batch ->
            val input = buildJsonObject {
                batch.forEachIndexed { i, page ->
                    putJsonObject(i.toString()) {
                        putJsonObject("json") {
                            put("novelId", novel.id)
                            put("page", page)
                        }
                    }
                }
            }
            val url = "$baseUrl/api/trpc/${List(batch.size) { "novel.toc" }.joinToString(",")}".toHttpUrl().newBuilder()
                .addQueryParameter("batch", "1")
                .addQueryParameter("input", input.toString())
                .build()
            val results = json.decodeFromString<List<TrpcResult<TocPage>>>(client.get(url, headers, Fetch.XHR).body.string())
            results.forEach { loaded[it.result.data.json.page] = it.result.data.json.items }
        }

        return pages.flatMap { loaded[it].orEmpty() }
            .distinctBy { it.number.content }
            .map { item ->
                SChapter.create().apply {
                    url = "${novel.slug}-${item.number.content}"
                    name = item.title?.takeIf { it.isNotBlank() } ?: "Chapter ${item.number.content}"
                    chapter_number = item.number.content.toFloatOrNull() ?: -1f
                    date_upload = Instant.tryParse(item.createdAt)
                }
            }
            .reversed()
    }

    // ======================== Content ========================

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, extractChapterSlug(chapter.url)))

    override suspend fun fetchPageText(page: Page): String {
        val chapter = client.get("$baseUrl/chapter/${extractChapterSlug(page.url)}", headers, Fetch.DOCUMENT)
            .pageProps<ChapterProps>().data.chapter
        return buildString {
            chapter.title?.let { append("<h2>${Entities.escape(it)}</h2>\n") }
            chapter.paragraphs.map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                append("<p>${Entities.escape(it)}</p>\n")
            }
        }
    }

    // ======================== Filters ========================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SelectFilter("Sort By", "order_by", sortOptions),
        SelectFilter("Status", "status", statusOptions),
        SelectFilter("Original Language", "lang", languageOptions),
        SelectFilter("Genre", "genre", genreOptions),
        TagFilter(),
    )

    class SelectFilter(name: String, val key: String, private val options: Array<Pair<String, String>>) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val selected: String get() = options[state].second
    }

    class TagFilter : Filter.Text("Tag slug")

    private val sortOptions = arrayOf(
        Pair("Popular this week", "popular"),
        Pair("Popular this month", "monthly"),
        Pair("Most viewed", "views"),
        Pair("Trending", "trending"),
        Pair("Top rated", "rating"),
        Pair("Latest update", "latest"),
        Pair("Newest", "new"),
        Pair("Most chapters", "chapters"),
        Pair("Name A-Z", "name"),
    )

    private val statusOptions = arrayOf(
        Pair("Any", ""),
        Pair("Ongoing", "ongoing"),
        Pair("Completed", "completed"),
        Pair("Hiatus", "hiatus"),
        Pair("Dropped", "dropped"),
    )

    private val languageOptions = arrayOf(
        Pair("Any", ""),
        Pair("Chinese", "cn"),
        Pair("Korean", "kr"),
        Pair("Japanese", "jp"),
        Pair("English", "en"),
        Pair("Indonesian", "id"),
        Pair("Other", "other"),
    )

    private val genreOptions = arrayOf(
        Pair("All", ""),
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
        Pair("MTL", "mtl"),
    )

    // ======================== Data Classes ========================

    @Serializable
    class BrowseProps(val listing: Listing)

    @Serializable
    class SearchProps(val result: Listing)

    @Serializable
    class Listing(
        val items: List<NovelItem> = emptyList(),
        val page: Int = 1,
        val totalPages: Int = 1,
    )

    @Serializable
    class NovelItem(
        val slug: String,
        val title: String,
        val coverKey: String? = null,
        val coverOriginal: String? = null,
        val status: String? = null,
        val authorName: String? = null,
    )

    @Serializable
    class NovelProps(val novel: NovelDetail, val toc: Toc)

    @Serializable
    class NovelDetail(
        val id: Long,
        val slug: String,
        val title: String,
        val altTitles: List<String> = emptyList(),
        val description: String? = null,
        val status: String? = null,
        val coverKey: String? = null,
        val coverOriginal: String? = null,
        val genres: List<Named> = emptyList(),
        val tags: List<Named> = emptyList(),
        val author: Named? = null,
    )

    @Serializable
    class Named(val name: String)

    @Serializable
    class Toc(
        val items: List<TocItem> = emptyList(),
        val range: TocRange = TocRange(),
        val ranges: List<TocRange> = emptyList(),
        val order: String? = null,
    )

    @Serializable
    class TocRange(val page: Int = 1)

    @Serializable
    class TocItem(
        val number: JsonPrimitive,
        val title: String? = null,
        val createdAt: String? = null,
    )

    @Serializable
    class TocPage(val page: Int, val items: List<TocItem> = emptyList())

    @Serializable
    class TrpcResult<T>(val result: TrpcData<T>)

    @Serializable
    class TrpcData<T>(val data: TrpcJson<T>)

    @Serializable
    class TrpcJson<T>(val json: T)

    @Serializable
    class ChapterProps(val data: ChapterData)

    @Serializable
    class ChapterData(val chapter: ChapterContent)

    @Serializable
    class ChapterContent(
        val title: String? = null,
        val paragraphs: List<String> = emptyList(),
    )
}

private const val CDN_URL = "https://cdn.wuxiaworld.eu"
private const val TOC_BATCH = 10

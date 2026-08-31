package eu.kanade.tachiyomi.novelextension.en.rechapters

import android.util.Base64
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
import keiyoushi.utils.parseAs
import keiyoushi.utils.setAltTitles
import keiyoushi.utils.stripChapterNumberPrefix
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant

@Source
abstract class Rechapters :
    KeiSource(),
    NovelSource {

    override suspend fun getPopularManga(page: Int): MangasPage = search(page, sort = "popularity_score")

    override suspend fun getLatestUpdates(page: Int): MangasPage = search(page, sort = "last_chapter_at_ts")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sortFilter = filters.filterIsInstance<SortFilter>().firstOrNull()
        val sort = if (query.isNotBlank()) "relevance" else sortFilter?.toUriPart() ?: "popularity_score"

        val categoryGroups = filters.filterIsInstance<CategoryGroup>()
        val include = categoryGroups.flatMap { it.state }.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.slug }
        val exclude = categoryGroups.flatMap { it.state }.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.slug }

        val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()
        val wordCountFilter = filters.filterIsInstance<WordCountFilter>().firstOrNull()
        val ratingFilter = filters.filterIsInstance<RatingFilter>().firstOrNull()
        val chapterCountFilter = filters.filterIsInstance<ChapterCountFilter>().firstOrNull()
        val updatedFilter = filters.filterIsInstance<UpdatedFilter>().firstOrNull()

        return search(
            page,
            query,
            sort,
            include,
            exclude,
            statusFilter?.toUriPart(),
            wordCountFilter?.toUriPart(),
            ratingFilter?.toUriPart(),
            chapterCountFilter?.toUriPart(),
            updatedFilter?.toUriPart(),
        )
    }

    private suspend fun search(
        page: Int,
        query: String = "",
        sort: String,
        include: List<String> = emptyList(),
        exclude: List<String> = emptyList(),
        writing: Int? = null,
        wordCount: Pair<Int?, Int?>? = null,
        ratingAverageMin: Int? = null,
        chapterCount: Pair<Int?, Int?>? = null,
        lastChapterAtMin: Long? = null,
    ): MangasPage {
        val urlBuilder = "$baseUrl/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("locale", lang)
            .addQueryParameter("pageSize", PAGE_SIZE.toString())
            .addQueryParameter("sort", sort)

        if (query.isNotBlank()) urlBuilder.addQueryParameter("q", query)
        if (include.isNotEmpty()) urlBuilder.addQueryParameter("categories", include.joinToString(","))
        if (exclude.isNotEmpty()) urlBuilder.addQueryParameter("excludeCategories", exclude.joinToString(","))
        writing?.let { urlBuilder.addQueryParameter("writing", it.toString()) }
        wordCount?.first?.let { urlBuilder.addQueryParameter("wordCountMin", it.toString()) }
        wordCount?.second?.let { urlBuilder.addQueryParameter("wordCountMax", it.toString()) }
        ratingAverageMin?.let { urlBuilder.addQueryParameter("ratingAverageMin", it.toString()) }
        chapterCount?.first?.let { urlBuilder.addQueryParameter("chapterCountMin", it.toString()) }
        chapterCount?.second?.let { urlBuilder.addQueryParameter("chapterCountMax", it.toString()) }
        lastChapterAtMin?.let { urlBuilder.addQueryParameter("lastChapterAtMin", it.toString()) }
        buildCursor(page)?.let { urlBuilder.addQueryParameter("cursor", it) }

        val envelope = client.get(urlBuilder.build(), headers).parseAs<SearchResponseDto>()
        val data = envelope.data ?: return MangasPage(emptyList(), false)
        return MangasPage(data.items.map { it.toSManga() }, data.hasMore)
    }

    private fun buildCursor(page: Int): String? {
        if (page <= 1) return null
        val offset = (page - 1) * PAGE_SIZE
        return Base64.encodeToString("{\"offset\":$offset}".toByteArray(), Base64.NO_WRAP)
    }

    private fun BookDto.toSManga(): SManga = SManga.create().apply {
        url = "/book/$slug-$nanoId"
        title = this@toSManga.title
        author = this@toSManga.author
        description = intro
        thumbnail_url = coverUrl
        if (tags.isNotEmpty()) genre = tags.joinToString()
        status = statusFor(writingStatusCode)

        val altTitlesList = titles.filter { it.isNotBlank() && it != this@toSManga.title }.distinct()
        if (altTitlesList.isNotEmpty()) setAltTitles(altTitlesList)
    }

    private fun statusFor(code: String?): Int = when (code) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        if (segments.getOrNull(0) != "book" || segments.size != 2) return null
        val slug = segments[1]
        val manga = SManga.create().apply {
            this.url = "/book/$slug"
            title = slug
        }
        return loadMangaDetails(manga)
    }

    private suspend fun loadMangaDetails(manga: SManga): SManga {
        val doc = client.get(baseUrl + manga.url, headers).asJsoup()

        return SManga.create().apply {
            url = manga.url
            title = doc.selectFirst("h1")?.text() ?: manga.title
            author = doc.selectFirst("a[href^=\"/search?q=\"]")?.text() ?: manga.author
            thumbnail_url = doc.selectFirst(".book-cover img")?.attr("abs:src")?.takeIf { it.isNotBlank() } ?: manga.thumbnail_url

            val tags = doc.select("a[href^=/tag/]").map { it.attr("aria-label").ifBlank { it.text() } }.filter { it.isNotBlank() }.distinct()
            if (tags.isNotEmpty()) genre = tags.joinToString() else genre = manga.genre

            val metrics = doc.selectFirst("div[aria-label=\"Book metrics\"]")?.text().orEmpty()
            status = when {
                metrics.contains("Completed", true) -> SManga.COMPLETED
                metrics.contains("Ongoing", true) -> SManga.ONGOING
                metrics.contains("Hiatus", true) -> SManga.ON_HIATUS
                else -> manga.status
            }

            val synopsisHeader = doc.select("h2").firstOrNull { it.text().equals("Synopsis", true) }
            val synopsisText = synopsisHeader?.parent()?.select("p")?.joinToString("\n\n") { it.text() }
            description = synopsisText?.takeIf { it.isNotBlank() } ?: manga.description
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = if (fetchDetails) async { loadMangaDetails(manga) } else null
        val chaptersDeferred = if (fetchChapters) async { loadChapterList(manga) } else null

        SMangaUpdate(
            manga = detailsDeferred?.await() ?: manga,
            chapters = chaptersDeferred?.await() ?: chapters,
        )
    }

    private suspend fun loadChapterList(manga: SManga): List<SChapter> {
        val slug = manga.url.removePrefix("/book/").trim('/')
        val nanoId = slug.substringAfterLast('-')

        val items = mutableListOf<ChapterDto>()
        var bucket = 0
        while (bucket < MAX_CHAPTER_BUCKETS) {
            val url = "$baseUrl/api/book/$nanoId/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("bucket", bucket.toString())
                .addQueryParameter("languageCode", lang)
                .addQueryParameter("order", "asc")
                .build()
            val page = client.get(url, headers).parseAs<ChaptersResponseDto>().data?.items.orEmpty()
            if (page.isEmpty()) break
            items += page
            if (page.size < CHAPTER_BUCKET_SIZE) break
            bucket++
        }

        return items.map { chapter ->
            SChapter.create().apply {
                url = "/book/$slug/${chapter.chapterNanoId}"
                name = chapter.title.stripChapterNumberPrefix()
                chapter_number = chapter.orderNum?.toFloatOrNull() ?: -1f
                date_upload = runCatching { Instant.parse(chapter.createdAt).toEpochMilli() }.getOrDefault(0L)
            }
        }.reversed()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.get(baseUrl + page.url, headers).asJsoup()
        val paragraphs = doc.select("p[data-block-index]")
        if (paragraphs.isEmpty()) throw Exception("Could not find chapter content")
        return paragraphs.joinToString("") { "<p>${it.html()}</p>" }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        StatusFilter(),
        WordCountFilter(),
        RatingFilter(),
        ChapterCountFilter(),
        UpdatedFilter(),
        Filter.Separator(),
        CategoryGroup("Genres", GENRES),
        CategoryGroup("Orientation", ORIENTATIONS),
        CategoryGroup("Ratings", RATINGS),
        CategoryGroup("Audience", AUDIENCE),
        CategoryGroup("Setting", SETTINGS),
    )

    class SortFilter :
        Filter.Select<String>(
            "Sort",
            arrayOf("Popular", "Latest update", "Highest rated", "Most rated", "Most words", "Most chapters"),
        ) {
        fun toUriPart(): String = when (state) {
            1 -> "last_chapter_at_ts"
            2 -> "rating_average"
            3 -> "rating_count"
            4 -> "word_count"
            5 -> "chapter_count"
            else -> "popularity_score"
        }
    }

    class StatusFilter : Filter.Select<String>("Status", arrayOf("All", "Ongoing", "Completed")) {
        fun toUriPart(): Int? = when (state) {
            1 -> 1
            2 -> 2
            else -> null
        }
    }

    class WordCountFilter :
        Filter.Select<String>(
            "Words",
            arrayOf("Any", "< 100k", "100k - 500k", "500k - 1M", "1M - 5M", "5M - 10M", "10M+"),
        ) {
        fun toUriPart(): Pair<Int?, Int?> = when (state) {
            1 -> null to 100000
            2 -> 100000 to 500000
            3 -> 500000 to 1000000
            4 -> 1000000 to 5000000
            5 -> 5000000 to 10000000
            6 -> 10000000 to null
            else -> null to null
        }
    }

    class RatingFilter : Filter.Select<String>("Rating", arrayOf("Any", "8.0+", "6.0+", "4.0+")) {
        fun toUriPart(): Int? = when (state) {
            1 -> 8
            2 -> 6
            3 -> 4
            else -> null
        }
    }

    class ChapterCountFilter :
        Filter.Select<String>(
            "Chapters",
            arrayOf("Any", "< 100", "100 - 500", "500 - 1000", "1000 - 3000", "3000+"),
        ) {
        fun toUriPart(): Pair<Int?, Int?> = when (state) {
            1 -> null to 100
            2 -> 100 to 500
            3 -> 500 to 1000
            4 -> 1000 to 3000
            5 -> 3000 to null
            else -> null to null
        }
    }

    class UpdatedFilter : Filter.Select<String>("Updated", arrayOf("Any", "Last 24h", "Last 7 days", "Last 30 days", "Last year")) {
        fun toUriPart(): Long? {
            val now = System.currentTimeMillis() / 1000
            return when (state) {
                1 -> now - 86_400
                2 -> now - 604_800
                3 -> now - 2_592_000
                4 -> now - 31_536_000
                else -> null
            }
        }
    }

    class CategoryCheckBox(val slug: String, name: String) : Filter.TriState(name)
    class CategoryGroup(name: String, categories: List<String>) : Filter.Group<CategoryCheckBox>(name, categories.map { CategoryCheckBox(slugify(it), it) })

    @Serializable
    private class SearchResponseDto(val data: SearchDataDto? = null)

    @Serializable
    private class SearchDataDto(val items: List<BookDto> = emptyList(), val hasMore: Boolean = false)

    @Serializable
    private class BookDto(
        val nanoId: String,
        val slug: String,
        val title: String,
        val author: String? = null,
        val intro: String? = null,
        val coverUrl: String? = null,
        val writingStatusCode: String? = null,
        val tags: List<String> = emptyList(),
        val titles: List<String> = emptyList(),
    )

    @Serializable
    private class ChaptersResponseDto(val data: ChaptersDataDto? = null)

    @Serializable
    private class ChaptersDataDto(val items: List<ChapterDto> = emptyList())

    @Serializable
    private class ChapterDto(
        val chapterNanoId: String,
        val title: String,
        val orderNum: String? = null,
        val createdAt: String? = null,
    )

    companion object {
        private const val PAGE_SIZE = 20
        private const val CHAPTER_BUCKET_SIZE = 100
        private const val MAX_CHAPTER_BUCKETS = 500

        private fun slugify(label: String): String = label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

        private val GENRES = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror",
            "Mystery", "Psychological", "Romance", "Sci-fi", "Thriller", "Tragedy",
        )
        private val ORIENTATIONS = listOf("Yaoi", "Yuri", "Shoujo ai", "Shounen ai", "Lgbt+", "Het")
        private val RATINGS = listOf("Mature", "Adult", "General")
        private val AUDIENCE = listOf("Josei", "Seinen", "Shoujo", "Shounen")
        private val SETTINGS = listOf(
            "Reincarnation", "School life", "Slice of life", "Magic", "Supernatural", "System", "Urban",
            "Historical", "Isekai", "Litrpg", "Wuxia", "Xianxia", "Xuanhuan", "Eastern", "Fan-fiction",
            "Game", "Gender bender", "Harem", "Smut", "Ecchi", "Magical realism", "Martial arts", "Mecha",
            "Military", "Modern life", "Anime & comics", "Realistic", "Sports", "Other",
        )
    }
}

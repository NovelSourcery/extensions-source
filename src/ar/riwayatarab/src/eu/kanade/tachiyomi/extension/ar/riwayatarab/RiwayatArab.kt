package eu.kanade.tachiyomi.novelextension.ar.riwayatarab

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

private const val API_URL = "https://api.riwayatarab.com"

/** The site's own listing page size; more entries just make the JSON response larger. */
private const val PAGE_SIZE = 24

/** The chapters endpoint silently caps `limit` at 100, so longer novels are walked page by page. */
private const val CHAPTERS_PER_PAGE = 100

/** 2558 chapters is the longest novel on the site, i.e. 26 pages; this ceiling is a safety net. */
private const val MAX_CHAPTER_PAGES = 100

/**
 * Chapter pages are independent, so a handful are fetched at a time instead of walking the whole
 * novel one request after another (4,350 chapters = 44 pages = ~20s sequentially).
 */
private const val PARALLEL_CHAPTER_PAGES = 6

private const val SORT_VIEWS = "views"
private const val SORT_UPDATED = "updated_at"
private const val SORT_CREATED = "created_at"
private const val SORT_RATING = "rating"

/**
 * رواياتعرب (riwayatarab.com) — an Arabic web-novel library.
 *
 * The site is a Next.js front end whose entire catalogue is served by the public JSON API at
 * [API_URL]. Everything here talks to that API directly, so no HTML is parsed:
 *
 * - listing: `GET /api/novels?page=…&limit=24&sort=views|updated_at|created_at|rating&search=…&category=…`
 * - details: `GET /api/novels/{slug}`
 * - chapters: `GET /api/novels/{slug}/chapters?page=…&limit=100&order=asc`
 * - chapter text: `GET /api/novels/{slug}/chapter/{chapterSlug}`
 *
 * [SManga.url] holds the API `slug`. Arabic slugs come percent-encoded from the API and stay in
 * that form, so they can be inserted into the API paths without re-encoding.
 */
@Source
abstract class RiwayatArab :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    // The API answers JSON regardless of the browser-ish defaults KeiSource adds.
    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("Accept", "application/json")

    // ==================== Catalogue ====================

    override suspend fun getPopularManga(page: Int): MangasPage = fetchListing(page, sort = SORT_VIEWS)

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchListing(page, sort = SORT_UPDATED)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        var sort = SORT_VIEWS
        var category = ""
        filters.forEach { filter ->
            when (filter) {
                is CategoryFilter -> category = filter.selected()
                is SortFilter -> sort = filter.selected()
                else -> {}
            }
        }
        return fetchListing(page, sort, query, category)
    }

    private suspend fun fetchListing(
        page: Int,
        sort: String,
        query: String = "",
        category: String = "",
    ): MangasPage {
        val url = "$API_URL/api/novels".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("sort", sort)
            .apply {
                if (query.isNotBlank()) addQueryParameter("search", query)
                if (category.isNotBlank()) addQueryParameter("category", category)
            }
            .build()

        val response = client.get(url, headers).parseAs<NovelListResponse>()
        return MangasPage(response.novels.map { it.toSManga() }, response.pagination.hasNext)
    }

    // ==================== Details ====================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novel/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.encodedPath.substringAfter("/novel/", "").trim('/')
        if (slug.isEmpty()) return null
        return fetchNovel(slug)
    }

    private suspend fun fetchNovel(slug: String): SManga? {
        val response = client.get("$API_URL/api/novels/$slug", headers, ensureSuccess = false)
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        return response.parseAs<NovelDto>().toSManga().apply { url = slug }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = if (fetchDetails) async { fetchNovel(manga.url) } else null
        val chaptersDeferred = if (fetchChapters) async { fetchChapterList(manga.url) } else null

        SMangaUpdate(
            manga = detailsDeferred?.await() ?: manga,
            chapters = chaptersDeferred?.await() ?: chapters,
        )
    }

    // ==================== Chapters ====================

    private suspend fun fetchChapterList(slug: String): List<SChapter> = coroutineScope {
        val firstPage = fetchChapterPage(slug, page = 1)
        val totalPages = minOf(firstPage.pagination.totalPages, MAX_CHAPTER_PAGES)

        val pages = mutableListOf(firstPage)
        // Pages are independent, so groups of them are requested concurrently and merged after.
        for (group in (2..totalPages).chunked(PARALLEL_CHAPTER_PAGES)) {
            pages += group.map { page -> async { fetchChapterPage(slug, page) } }.awaitAll()
        }

        pages.asSequence()
            .flatMap { it.chapters.asSequence() }
            // A chapter published mid-walk shifts the pages, which can repeat an entry.
            .distinctBy { it.slug }
            .map { it.toSChapter(slug) }
            .sortedByDescending { it.chapter_number }
            .toList()
    }

    private suspend fun fetchChapterPage(slug: String, page: Int): ChapterListResponse = client.get(
        "$API_URL/api/novels/$slug/chapters?page=$page&limit=$CHAPTERS_PER_PAGE&order=asc",
        headers,
    ).parseAs<ChapterListResponse>()

    // ==================== Reader ====================

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        // [SChapter.url] is stored as /novel/{novelSlug}/chapter/{chapterSlug}.
        val segments = page.url.trim('/').split('/')
        if (segments.size < 4 || segments[0] != "novel" || segments[2] != "chapter") return ""
        val novelSlug = segments[1]
        val chapterSlug = segments.drop(3).joinToString("/")

        val chapter = client.get("$API_URL/api/novels/$novelSlug/chapter/$chapterSlug", headers)
            .parseAs<ChapterResponse>()
            .chapter

        // The API stores the body as plain text with `\r\n` (and long runs of blank lines) rather
        // than markup, so the blank lines are collapsed before joining the paragraphs.
        return chapter.content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
    }

    // ==================== Filters ====================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        CategoryFilter(),
        SortFilter(),
    )

    private class CategoryFilter : Filter.Select<String>("التصنيف", CATEGORY_LABELS) {
        fun selected(): String = CATEGORY_SLUGS[state]
    }

    private class SortFilter :
        Filter.Select<String>(
            "الترتيب",
            arrayOf("الأكثر قراءة", "آخر تحديث", "الأحدث", "الأعلى تقييماً"),
        ) {
        fun selected(): String = when (state) {
            1 -> SORT_UPDATED
            2 -> SORT_CREATED
            3 -> SORT_RATING
            else -> SORT_VIEWS
        }
    }
}

/** The categories exposed by `GET /api/categories`, in the same order as [CATEGORY_SLUGS]. */
private val CATEGORY_LABELS = arrayOf(
    "الكل", "+18", "إثارة", "اعادة ولادة", "اكشن", "التناسخ", "حريم",
    "حيوان مرافق", "خيال", "دراما", "رعب", "رومانسي", "رومانسية", "زراعة",
    "سحر", "سوداوي", "ضعيف الى قوي", "عالم حديث", "عام", "غموض",
    "فنون قتالية", "قوي الى اقوى", "كوميدي", "كوميديا", "لعبة", "مدرسية",
    "مغامرة", "مولفة", "نظام", "نفسي", "نهاية عالم",
)

private val CATEGORY_SLUGS = arrayOf(
    "", "18", "thriller", "اعادة-ولادة", "action", "التناسخ", "harem",
    "حيوان-مرافق", "fantasy", "drama", "رعب", "رومانسي", "romance", "زراعة",
    "سحر", "سوداوي", "ضعيف-الى-قوي", "عالم-حديث", "عام", "غموض",
    "martial-arts", "قوي-الى-اقوى", "كوميدي", "comedy", "لعبة", "مدرسية",
    "مغامرة", "مولفة", "نظام", "نفسي", "نهاية-عالم",
)

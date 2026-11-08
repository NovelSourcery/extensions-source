package eu.kanade.tachiyomi.novelextension.ar.novltime

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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val API_URL = SITE_URL + "/api/v1"

/** The API caps `limit` at 100 for listings. */
private const val PAGE_SIZE = 100

/** 200 is the largest chapter page the API answers before it times out; a few are read per novel. */
private const val CHAPTERS_PER_PAGE = 200

/** The longest novel on the site has ~5,000 chapters, i.e. 25 pages; this is a safety net. */
private const val MAX_CHAPTER_PAGES = 60

/** Each distinct sort/genre/query keeps up to 13 cursors, so a small cap is plenty. */
private const val MAX_CACHED_QUERIES = 32

private const val SORT_POPULAR = "popular"
private const val SORT_LATEST = "latest"

/** Total attempts (first try + retries) for a single GET. */
private const val MAX_ATTEMPTS = 3

/** Base backoff between attempts; grows linearly (400 ms, 800 ms, …). */
private const val RETRY_DELAY_MS = 400L

/**
 * نوفل تايم (novltime.com) — an Arabic web-novel library.
 *
 * The site is a Next.js front end backed by a public JSON API at [API_URL]:
 *
 * - listing: `GET /api/v1/novels?limit=100&sort=popular|latest|trending|rating|views&genre=…&search=…`
 * - details: `GET /api/v1/novels/{slug}`
 * - chapters: `GET /api/v1/novels/{id}/chapters/published?limit=200&cursor=…`
 * - chapter text: the rendered HTML page at `/ar/novel/{slug}/chapter/{chapterSlug}`
 *
 * The endpoints only accept `page=1`: every following page is requested with the signed
 * `nextCursor` of the previous one, so pages are walked from the last cursor seen for a query
 * (see [listCursors]) instead of being requested by number.
 *
 * [SManga.url] holds the novel slug, while [SChapter.url] holds the reader path including the
 * `/ar` locale prefix.
 */
@Source
abstract class NovelTime :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    // The API answers JSON regardless of the browser-ish defaults KeiSource adds.
    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("Accept", "application/json")

    /**
     * The host sits behind a gateway that intermittently answers 504 on otherwise valid requests
     * (big chapter lists especially), and it can also stall a connection. Bounded timeouts keep a
     * stalled attempt inside the host app's 30 s chapter-load budget, and [RetryInterceptor] gives
     * the request another chance before that budget runs out.
     */
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        connectTimeout(10, TimeUnit.SECONDS)
        readTimeout(10, TimeUnit.SECONDS)
        callTimeout(25, TimeUnit.SECONDS)
        addInterceptor(RetryInterceptor())
    }

    private class RetryInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val canRetry = request.method == "GET"
            var attempt = 0

            while (true) {
                val response = try {
                    chain.proceed(request)
                } catch (e: IOException) {
                    if (!canRetry || attempt >= MAX_ATTEMPTS - 1 || chain.call().isCanceled()) throw e
                    attempt++
                    Thread.sleep(RETRY_DELAY_MS * attempt)
                    continue
                }

                val code = response.code
                val transient = code == 408 || code == 429 || code >= 500
                if (!canRetry || !transient || attempt >= MAX_ATTEMPTS - 1) return response

                response.close()
                attempt++
                Thread.sleep(RETRY_DELAY_MS * attempt)
            }
        }
    }

    // The API returns large objects with fields this source does not read, and some of them are
    // null for novels that are still being set up, so both leniencies are needed.
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** `page -> nextCursor` for every listing query seen so far. */
    private val listCursors = ConcurrentHashMap<String, ConcurrentHashMap<Int, String>>()

    // ==================== Catalogue ====================

    override suspend fun getPopularManga(page: Int): MangasPage = fetchListing(page, SORT_POPULAR)

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchListing(page, SORT_LATEST)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        var sort = SORT_POPULAR
        var genre = ""
        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> genre = filter.selected()
                is SortFilter -> sort = filter.selected()
                else -> {}
            }
        }
        return fetchListing(page, sort, query.trim(), genre)
    }

    private suspend fun fetchListing(
        page: Int,
        sort: String,
        query: String = "",
        genre: String = "",
    ): MangasPage {
        // Cursors are signed for one exact query, so they are cached per sort/genre/query triple.
        val cursors = cursorStore("$sort\u0000$genre\u0000$query")

        var cursor = cursors[page]
        if (cursor == null && page > 1) {
            // Walk forward from the closest page already known instead of starting over.
            var walkedPage = (1 until page).lastOrNull { cursors.containsKey(it) } ?: 1
            var walkedCursor = cursors[walkedPage]
            while (walkedPage < page) {
                val step = requestListing(sort, query, genre, walkedCursor)
                val next = step.meta.nextCursor
                if (!step.meta.hasMore || next.isNullOrEmpty()) return MangasPage(emptyList(), false)
                walkedPage++
                cursors[walkedPage] = next
                walkedCursor = next
            }
            cursor = cursors[page]
        }

        val response = requestListing(sort, query, genre, cursor)
        response.meta.nextCursor
            ?.takeIf { it.isNotEmpty() && response.meta.hasMore }
            ?.let { cursors[page + 1] = it }

        return MangasPage(response.data.map { it.toSManga() }, response.meta.hasMore)
    }

    private fun cursorStore(key: String): ConcurrentHashMap<Int, String> {
        if (listCursors.size > MAX_CACHED_QUERIES) listCursors.clear()
        return listCursors.computeIfAbsent(key) { ConcurrentHashMap() }
    }

    private suspend fun requestListing(
        sort: String,
        query: String,
        genre: String,
        cursor: String?,
    ): NovelListResponse {
        val url = "$API_URL/novels".toHttpUrl().newBuilder()
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .apply {
                if (cursor.isNullOrEmpty()) {
                    addQueryParameter("page", "1")
                } else {
                    addQueryParameter("cursor", cursor)
                }
                if (sort.isNotEmpty()) addQueryParameter("sort", sort)
                if (genre.isNotEmpty()) addQueryParameter("genre", genre)
                if (query.isNotEmpty()) addQueryParameter("search", query)
            }
            .build()

        return client.get(url, headers).parseAs<NovelListResponse>(json)
    }

    // ==================== Details ====================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/ar/novel/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val novelIndex = segments.indexOf("novel")
        val slug = segments.getOrNull(novelIndex + 1) ?: return null
        return fetchNovel(slug)
    }

    private suspend fun fetchNovel(slug: String): SManga? {
        val novel = fetchNovelDto(slug) ?: return null
        return novel.toSManga().apply {
            url = slug
            authorOf(slug)?.let { author = it }
        }
    }

    private suspend fun fetchNovelDto(slug: String): NovelDto? {
        val response = client.get("$API_URL/novels/$slug", headers, ensureSuccess = false)
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        return response.parseAs<NovelDetailResponse>(json).data
    }

    /**
     * The API omits the author from single-novel responses, but the novel page renders it in the
     * author card. The card is the last `/ar/u/` link whose text is the display name rather than
     * the `@handle`.
     */
    private suspend fun authorOf(slug: String): String? {
        val response = client.get("$baseUrl/ar/novel/$slug", headers, ensureSuccess = false)
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        return response.use {
            it.asJsoup()
                .select("a[href^=\"/ar/u/\"]")
                .map { link -> link.text().trim() }
                .lastOrNull { name -> name.isNotEmpty() && !name.startsWith("@") }
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val needsNovel = fetchDetails || fetchChapters
        val novelDeferred = if (needsNovel) async { fetchNovelDto(manga.url) } else null
        val authorDeferred = if (fetchDetails) async { authorOf(manga.url) } else null

        val novel = novelDeferred?.await()
        val author = authorDeferred?.await()

        val updatedManga = if (fetchDetails && novel != null) {
            novel.toSManga().apply {
                url = manga.url
                if (!author.isNullOrBlank()) this.author = author
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters && novel != null) {
            fetchChapterList(novel.id, manga.url)
        } else {
            chapters
        }

        SMangaUpdate(manga = updatedManga, chapters = updatedChapters)
    }

    // ==================== Chapters ====================

    private suspend fun fetchChapterList(novelId: String, novelSlug: String): List<SChapter> {
        val chapters = mutableListOf<ChapterDto>()
        var cursor: String? = null
        var pages = 0

        while (pages < MAX_CHAPTER_PAGES) {
            val url = "$API_URL/novels/$novelId/chapters/published".toHttpUrl().newBuilder()
                .addQueryParameter("limit", CHAPTERS_PER_PAGE.toString())
                .apply {
                    if (cursor.isNullOrEmpty()) {
                        addQueryParameter("page", "1")
                    } else {
                        addQueryParameter("cursor", cursor)
                    }
                }
                .build()

            val response = client.get(url, headers).parseAs<ChapterListResponse>(json)
            chapters += response.data
            pages++

            val next = response.meta.nextCursor
            if (!response.meta.hasMore || next.isNullOrEmpty() || next == cursor) break
            cursor = next
        }

        return chapters
            .distinctBy { it.id }
            .map { it.toSChapter(novelSlug) }
            .sortedByDescending { it.chapter_number }
    }

    // ==================== Reader ====================

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) page.url else baseUrl + page.url
        val doc = client.get(url, headers).asJsoup()

        val body = doc.selectFirst("div.chapter-body")
            ?: doc.selectFirst("article")
            ?: return ""

        body.select("script, style, ins, iframe, nav, button, .ads, [data-ad]").remove()
        return body.html()
    }

    // ==================== Filters ====================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        GenreFilter(),
    )

    private class SortFilter :
        Filter.Select<String>(
            "الترتيب",
            arrayOf("الأكثر شهرة", "الأكثر رواجاً", "أحدث الإضافات", "الأعلى تقييماً", "الأكثر قراءة"),
        ) {
        fun selected(): String = when (state) {
            1 -> "trending"
            2 -> SORT_LATEST
            3 -> "rating"
            4 -> "views"
            else -> SORT_POPULAR
        }
    }

    private class GenreFilter :
        Filter.Select<String>(
            "التصنيف",
            arrayOf("الكل") + GENRE_LABELS.values.toTypedArray(),
        ) {
        fun selected(): String = GENRE_LABELS.keys.elementAtOrNull(state - 1).orEmpty()
    }
}

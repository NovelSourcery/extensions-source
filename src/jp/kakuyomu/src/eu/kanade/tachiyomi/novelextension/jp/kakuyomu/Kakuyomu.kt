package eu.kanade.tachiyomi.novelextension.jp.kakuyomu

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
import keiyoushi.utils.WebViewTimeoutException
import keiyoushi.utils.boolean
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.get
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.string
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Kakuyomu is a Next.js (Pages Router) app whose data is an Apollo Client cache serialized into
 * `__NEXT_DATA__` on every server-rendered page - browse/search/details are scraped from that
 * normalized `{__ref: "Type:id"}` graph via [extractApolloState]/[deref] instead of HTML selectors.
 *
 * The episode reading page itself (`/works/{workId}/episodes/{episodeId}/read`) does NOT return
 * this SSR payload for a plain HTTP request - it 404s regardless of cookies/UA, evidently only
 * rendering for a real browser session that fetches the body client-side. [fetchPageText] falls
 * back to [runWebView] for that one request. NOTE: the `.widget-episodeBody` selector below is
 * Kakuyomu's long-standing, publicly documented reader markup, but this could not be verified
 * live against the real page in this environment (only the 404 from a plain request could be
 * confirmed) - verify on-device before relying on it.
 */
@Source
abstract class Kakuyomu :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    // ======================== Popular / Latest / Search ========================

    override suspend fun getPopularManga(page: Int): MangasPage = search(page, "", "WEEKLY_RANKING")

    override suspend fun getLatestUpdates(page: Int): MangasPage = search(page, "", "PUBLISHED_AT")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val order = filters.filterIsInstance<OrderFilter>().firstOrNull()?.toUriPart() ?: "WEEKLY_RANKING"
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.toUriPart().orEmpty()
        return search(page, query, order, genre)
    }

    private suspend fun search(page: Int, query: String, order: String, genre: String = ""): MangasPage {
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("order", order)
            .addQueryParameter("offset", ((page - 1) * PAGE_SIZE).toString())
            .apply { if (genre.isNotEmpty()) addQueryParameter("genres[]", genre) }
            .build()

        val doc = client.get(url, headers).asJsoup()
        val apollo = doc.extractApolloState() ?: return MangasPage(emptyList(), false)

        val rootQuery = apollo["ROOT_QUERY"]?.let { it as? JsonObject } ?: return MangasPage(emptyList(), false)
        val connection = rootQuery.entries.firstOrNull { it.key.startsWith("searchWorks(") }?.value as? JsonObject
            ?: return MangasPage(emptyList(), false)

        val works = (connection["nodes"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { apollo.deref(it)?.parseAs<WorkDto>() }

        val hasNextPage = (connection["pageInfo"] as? JsonObject)?.get("hasNextPage")?.boolean ?: false
        return MangasPage(works.map { it.toSManga(apollo) }, hasNextPage)
    }

    // ======================== Details + Chapters ========================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val workId = manga.url.substringAfterLast("/")
        val doc = client.get("$baseUrl/works/$workId", headers).asJsoup()
        val apollo = doc.extractApolloState() ?: return SMangaUpdate(manga, chapters)
        val work = apollo["Work:$workId"]?.parseAs<WorkDto>()

        val updatedManga = if (fetchDetails && work != null) work.toSManga(apollo) else manga
        val updatedChapters = if (fetchChapters && work != null) parseChapterList(apollo, work, workId) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseChapterList(apollo: JsonObject, work: WorkDto, workId: String): List<SChapter> {
        val episodeRefs = work.tableOfContentsV2
            .mapNotNull { apollo.deref(it)?.parseAs<TocChapterDto>() }
            .flatMap { it.episodeUnions }

        // Number sequentially over resolved episodes only - indexing the pre-filter list would
        // leave a gap (and a wrong fallback title number) at every unresolvable episode ref.
        var number = 0
        return episodeRefs.mapNotNull { episodeRef ->
            val episodeId = (episodeRef as? JsonObject)?.get("__ref")?.string?.substringAfter(":") ?: return@mapNotNull null
            val episode = apollo.deref(episodeRef)?.parseAs<EpisodeDto>() ?: return@mapNotNull null
            number++
            SChapter.create().apply {
                url = "/works/$workId/episodes/$episodeId"
                name = episode.title ?: "Episode $number"
                chapter_number = number.toFloat()
                date_upload = episode.publishedAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() } ?: 0L
            }
        }.reversed()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val workId = url.pathSegments.getOrNull(1) ?: return null
        val response = client.get("$baseUrl/works/$workId", headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        val apollo = response.asJsoup().extractApolloState() ?: return null
        return apollo["Work:$workId"]?.parseAs<WorkDto>()?.toSManga(apollo)
    }

    // ======================== Pages ========================

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val url = baseUrl + page.url + "/read"
        val html = try {
            runWebView<String>(timeout = 30.seconds) {
                var resolved = false
                onPageFinished {
                    poll(1.seconds) {
                        if (resolved) return@poll
                        evaluateJs("document.querySelector('.widget-episodeBody')?.innerHTML || ''") { result ->
                            val content = result.parseAs<String>()
                            if (content.isNotBlank()) {
                                resolved = true
                                resolve(content)
                            }
                        }
                    }
                }
                loadUrl(url)
            }
        } catch (e: WebViewTimeoutException) {
            throw Exception("Kakuyomu: could not load the episode body via WebView. Please open the chapter in WebView once, then retry.", e)
        }

        // Furigana readings (<rt>/<rp>) are redundant with the base kanji text they annotate.
        val body = Jsoup.parseBodyFragment(html)
        body.select("rt, rp").remove()
        return body.html()
    }

    // ======================== Filters ========================

    override fun getFilterList(data: JsonElement?) = FilterList(OrderFilter(), GenreFilter())

    private class OrderFilter : Filter.Select<String>("Sort", arrayOf("Weekly ranking", "Recently published")) {
        fun toUriPart() = if (state == 1) "PUBLISHED_AT" else "WEEKLY_RANKING"
    }

    private class GenreFilter :
        Filter.Select<String>(
            "Genre",
            arrayOf("All", "Action", "Drama", "Fantasy", "Horror", "Love story", "Romance", "Sci-fi"),
        ) {
        fun toUriPart() = when (state) {
            1 -> "ACTION"
            2 -> "DRAMA"
            3 -> "FANTASY"
            4 -> "HORROR"
            5 -> "LOVE_STORY"
            6 -> "ROMANCE"
            7 -> "SF"
            else -> ""
        }
    }

    // ======================== DTOs ========================

    @Serializable
    private class WorkDto(
        val id: String,
        val title: String,
        val author: JsonElement? = null,
        val genre: String? = null,
        val introduction: String? = null,
        val tagLabels: List<String> = emptyList(),
        val serialStatus: String? = null,
        val tableOfContentsV2: List<JsonElement> = emptyList(),
    ) {
        fun toSManga(apollo: JsonObject): SManga = SManga.create().apply {
            url = "/works/$id"
            title = this@WorkDto.title
            thumbnail_url = "https://cdn-static.kakuyomu.jp/works/$id/ogimage.png"
            author = apollo.deref(this@WorkDto.author)?.parseAs<UserAccountDto>()?.activityName
            description = introduction
            genre = (listOfNotNull(genre) + tagLabels).joinToString()
            status = when (serialStatus) {
                "RUNNING" -> SManga.ONGOING
                "COMPLETED" -> SManga.COMPLETED
                "PAUSED" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    @Serializable
    private class UserAccountDto(val activityName: String? = null, val name: String? = null)

    @Serializable
    private class TocChapterDto(val episodeUnions: List<JsonElement> = emptyList())

    @Serializable
    private class EpisodeDto(val title: String? = null, val publishedAt: String? = null)

    companion object {
        private const val PAGE_SIZE = 20
    }
}

/** Extracts the raw `__APOLLO_STATE__` normalized cache from a server-rendered page. */
private fun Document.extractApolloState(): JsonObject? = extractNextJs({ it is JsonObject && "ROOT_QUERY" in it }, JsonObject.serializer())

/** Resolves an Apollo `{"__ref": "Type:id"}` pointer to the entity it points at. */
private fun JsonObject.deref(ref: JsonElement?): JsonElement? = (ref as? JsonObject)?.get("__ref")?.string?.let { this[it] }

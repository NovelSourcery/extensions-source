package eu.kanade.tachiyomi.novelextension.en.flamecomics

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

/**
 * FlameComics (flamecomics.xyz). Next.js site whose `_next/data/{buildId}/...json` routes serve
 * both comics/manhwa and novels; novels are the `series.type in ("Novel", "Web Novel")` subset and
 * live under a separate `novel_id`/`/novel/{id}` namespace with real HTML chapter content (not
 * split page images), so this is a standalone NovelSource rather than sharing the manga path.
 */
class FlameComics :
    HttpSource(),
    NovelSource {

    override val name = "FlameComics"
    override val baseUrl = "https://flamecomics.xyz"
    override val lang = "en"
    override val supportsLatest = true
    override val isNovelSource = true

    private val cdn = "https://cdn.flamecomics.xyz"
    private val json = Json { ignoreUnknownKeys = true }
    private val novelTypes = setOf("Novel", "Web Novel")

    override val client = network.client.newBuilder()
        .addInterceptor(::buildIdOutdatedInterceptor)
        .build()

    @Volatile
    private var cachedBuildId: String? = null
    private val buildIdLock = Any()

    private fun fetchBuildId(): String {
        val html = client.newCall(GET(baseUrl, headers)).execute().use { it.body.string() }
        val nextData = Jsoup.parse(html).selectFirst("#__NEXT_DATA__")?.data()
            ?: throw Exception("Could not find __NEXT_DATA__")
        return json.parseToJsonElement(nextData).jsonObject["buildId"]
            ?.jsonPrimitive?.contentOrNull
            ?: throw Exception("Could not extract buildId")
    }

    private fun getBuildId(): String {
        cachedBuildId?.let { return it }
        synchronized(buildIdLock) {
            cachedBuildId?.let { return it }
            val id = fetchBuildId()
            cachedBuildId = id
            return id
        }
    }

    // Single retry per stale buildId: invalidate only if nobody else already refreshed it, then
    // rebuild the failing request's path segment in place.
    private fun buildIdOutdatedInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.encodedPath.contains("/_next/data/")) return chain.proceed(request)

        val usedBuildId = request.url.pathSegments.getOrNull(2)
        val response = chain.proceed(request)
        if (response.code != 404 || usedBuildId == null) return response

        response.close()
        synchronized(buildIdLock) {
            if (cachedBuildId == usedBuildId) cachedBuildId = null
        }
        val freshId = runCatching { getBuildId() }.getOrNull()
        if (freshId == null || freshId == usedBuildId) {
            return chain.proceed(request)
        }
        val newUrl = request.url.newBuilder().setPathSegment(2, freshId).build()
        return chain.proceed(request.newBuilder().url(newUrl).build())
    }

    private fun dataApiUrl(path: String): String = "$baseUrl/_next/data/${getBuildId()}$path"

    private fun thumbnailUrl(novelId: Int, cover: String, lastEdit: Long? = null): String {
        val url = "$cdn/uploads/images/novels/$novelId/$cover".toHttpUrl().newBuilder()
        if (lastEdit != null) url.addQueryParameter(lastEdit.toString(), null)
        return url.build().toString()
    }

    private fun NovelListItem.toSManga(): SManga {
        val id = novel_id!!
        return SManga.create().apply {
            title = this@toSManga.title
            setUrlWithoutDomain("/novel/$id")
            thumbnail_url = cover?.let { thumbnailUrl(id, it, last_edit) }
        }
    }

    // ======================== Browse / Search ========================
    // The catalogue is small (a few dozen novels) and the API has no server-side novel filter or
    // pagination worth using, so all three listings fetch the same browse.json and filter/sort
    // client-side; search smuggles the query through the URL fragment (never sent to the server)
    // so *Parse can recover it without a second round trip.

    override fun popularMangaRequest(page: Int): Request = GET(dataApiUrl("/browse.json"), headers)

    override fun latestUpdatesRequest(page: Int): Request = GET(dataApiUrl("/browse.json"), headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET(dataApiUrl("/browse.json").toHttpUrl().newBuilder().fragment(query).build(), headers)

    private fun novelsFrom(response: Response): List<NovelListItem> {
        val data = json.decodeFromString<BrowsePageData>(response.body.string())
        return data.pageProps.series.filter { it.type in novelTypes && it.novel_id != null }
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val novels = novelsFrom(response).sortedByDescending { it.likes ?: 0 }
        return MangasPage(novels.map { it.toSManga() }, false)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val novels = novelsFrom(response).sortedByDescending { it.last_edit ?: 0L }
        return MangasPage(novels.map { it.toSManga() }, false)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val query = response.request.url.fragment.orEmpty()
        val novels = novelsFrom(response).filter { it.title.contains(query, ignoreCase = true) }
        return MangasPage(novels.map { it.toSManga() }, false)
    }

    // ======================== Details ========================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl${manga.url}"

    private fun novelIdOf(manga: SManga): String = manga.url.substringAfterLast('/')

    override fun mangaDetailsRequest(manga: SManga): Request = GET(dataApiUrl("/novel/${novelIdOf(manga)}.json"), headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val data = json.decodeFromString<NovelDetailsPageData>(response.body.string()).pageProps.novels
        return SManga.create().apply {
            title = data.title
            thumbnail_url = data.cover?.let { thumbnailUrl(data.novel_id, it, data.last_edit) }

            val synopsis = data.description?.let { Jsoup.parseBodyFragment(it).wholeText() }.orEmpty()
            val altNames = data.altTitles.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
            description = buildString {
                append(synopsis)
                if (altNames.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append("Alternative Names:")
                    altNames.forEach { append("\n- $it") }
                }
            }.takeIf { it.isNotEmpty() }

            genre = data.tags?.joinToString()
            author = data.author?.joinToString()
            artist = data.artist?.joinToString()
            status = when (data.status?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "dropped", "cancelled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = json.decodeFromString<NovelDetailsPageData>(response.body.string()).pageProps
        val novelId = data.novels.novel_id
        return data.chapters.map { ch ->
            SChapter.create().apply {
                url = "/novel/$novelId/${ch.token}"
                chapter_number = ch.chapter.toFloatOrNull() ?: -1f
                date_upload = ch.release_date * 1000
                name = buildString {
                    append("Chapter ${ch.chapter.removeSuffix(".00").removeSuffix(".0")}")
                    if (!ch.title.isNullOrBlank()) append(" - ${ch.title}")
                }
            }
        }.sortedByDescending { it.chapter_number }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    // ======================== Content ========================
    // Single metadata page per chapter; the real fetch happens in fetchPageText.

    override fun pageListRequest(chapter: SChapter): Request = throw UnsupportedOperationException("Not used")
    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException("Not used")
    override fun fetchPageList(chapter: SChapter): rx.Observable<List<Page>> = rx.Observable.just(listOf(Page(0, chapter.url)))

    override fun imageUrlParse(response: Response): String = ""

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(dataApiUrl("${page.url}.json"), headers)).execute()
        val data = json.decodeFromString<ChapterContentPageData>(response.body.string()).pageProps.chapter
        return data.content
    }

    override fun getFilterList(): FilterList = FilterList()
}

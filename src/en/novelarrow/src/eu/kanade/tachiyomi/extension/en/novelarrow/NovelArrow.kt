package eu.kanade.tachiyomi.novelextension.en.novelarrow

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

/**
 * novelarrow.com — the successor to the dead novelbin.com. Built on Next.js, so novel details,
 * the chapter list and chapter content all live in the React Server Component (RSC) flight payload
 * rather than plain HTML. Browse/search fall back to the server-rendered genre listings.
 *
 * Legacy migration: novelbin used novelbin.com/b/<slug> paths. The interceptor rewrites those onto
 * novelarrow (/novel/<slug> for a novel, /chapter/<slug>/<chapter> for a chapter) so existing
 * library entries keep resolving; the chapter list is re-fetched with novelarrow's own urls.
 */
@Source
abstract class NovelArrow :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The site's novel detail URL shape, as `/novel/<slug>`. [SManga.url] is stored as the bare
     * slug (see [SlugPath]); a stored value starting with "/" is a pre-existing full-path entry
     * from before this source adopted slug storage, and is resolved unchanged regardless of
     * this template.
     */
    private val mangaPathTemplate: SlugPath = SlugPath("/novel/")

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor { chain ->
        val request = chain.request()
        var url = request.url
        if (url.host.contains("novelbin.com")) {
            url = url.newBuilder().host("novelarrow.com").build()
        }
        val path = url.encodedPath
        if (path.startsWith("/b/")) {
            val segments = path.removePrefix("/b/").trim('/').split("/").filter { it.isNotEmpty() }
            val newPath = when {
                segments.size <= 1 -> "/novel/${segments.getOrElse(0) { "" }}"
                else -> "/chapter/${segments.joinToString("/")}"
            }
            url = url.newBuilder().encodedPath(newPath).build()
        }
        chain.proceed(request.newBuilder().url(url).build())
    }

    // Next.js only returns the flight payload (details/chapters/content) when this header is set.
    private fun rscHeaders() = headersBuilder().add("RSC", "1").build()

    // Browse

    private fun buildPopularMangaRequest(page: Int): Request = GET("$baseUrl/genre/action?page=$page", headers)

    override suspend fun getPopularManga(page: Int): MangasPage = browseParse(client.newCall(buildPopularMangaRequest(page)).execute())

    private fun buildLatestUpdatesRequest(page: Int): Request = GET("$baseUrl/genre/action?page=$page", headers)

    override suspend fun getLatestUpdates(page: Int): MangasPage = browseParse(client.newCall(buildLatestUpdatesRequest(page)).execute())

    private fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val genre = (filters.firstOrNull { it is GenreFilter } as? GenreFilter)?.selected() ?: "action"
        return GET("$baseUrl/genre/$genre?page=$page", headers)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = browseParse(client.newCall(buildSearchMangaRequest(page, query, filters)).execute())

    private fun browseParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        val mangas = doc.select("a[href^=/novel/]:has(img)")
            .distinctBy { it.attr("href") }
            .mapNotNull { a ->
                val img = a.selectFirst("img") ?: return@mapNotNull null
                SManga.create().apply {
                    setSlugUrl(mangaPathTemplate, a.attr("abs:href"))
                    title = img.attr("alt").removeSuffix(" - Novel cover").trim()
                    thumbnail_url = img.attr("abs:src")
                }
            }
            .filter { it.title.isNotBlank() }
        return MangasPage(mangas, mangas.size >= 20)
    }

    // Details

    private fun buildMangaDetailsRequest(manga: SManga): Request = GET(baseUrl + mangaPathTemplate.resolve(manga.url), rscHeaders())

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) {
            parseMangaDetails(client.newCall(buildMangaDetailsRequest(manga)).execute().body.string())
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters) loadChapterList(manga) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(flight: String): SManga = SManga.create().apply {
        title = STRING.decode(OG_TITLE.firstGroup(flight))
            ?.substringBefore(" Novel | Read Online")?.trim()
            ?: STRING.decode(OG_IMAGE_ALT.firstGroup(flight)).orEmpty()
        thumbnail_url = STRING.decode(COVER.firstGroup(flight))
        author = STRING.decode(AUTHOR.firstGroup(flight))
        // The novel's own genres are the "genres" array whose items carry "label"/"href"
        // (a separate "genres" array holds the site-wide genre nav). The site also has a much
        // finer-grained "Tags" section (article:tag og-meta) that isn't in that genres array
        // at all (e.g. APOCALYPSE, FIREARMS, GORE) - fold those in too.
        val genreLabels = GENRES_ARRAY.findAll(flight)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains("\"label\"") }
            ?.let { body -> GENRE_LABEL.findAll(body).mapNotNull { STRING.decode(it.groupValues[1]) } }
            .orEmpty()
        val tagLabels = ARTICLE_TAG.findAll(flight).mapNotNull { STRING.decode(it.groupValues[1]) }
        genre = (genreLabels + tagLabels)
            .map { label -> label.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) } }
            .distinct()
            .joinToString()
            .ifBlank { null }
        status = when (STRING.decode(STATUS.firstGroup(flight))?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "dropped", "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        description = stringArrayAfter(flight, "synopsisParagraphs")
            .mapNotNull { STRING.decode(it) }
            .joinToString("\n\n")
            .ifBlank { null }
    }

    // A bracket-balanced regex can't bound a "key":[...] array when an element's text itself
    // contains "[" or "]" (e.g. a synopsis mentioning "[Kairas]") - walk it as quoted strings
    // instead, which only needs quote/escape state, not bracket depth.
    private fun stringArrayAfter(flight: String, key: String): List<String> {
        val marker = "\"$key\":["
        var pos = flight.indexOf(marker).takeIf { it != -1 }?.plus(marker.length) ?: return emptyList()
        val values = mutableListOf<String>()
        while (pos < flight.length) {
            while (pos < flight.length && flight[pos].isWhitespace()) pos++
            if (pos >= flight.length || flight[pos] == ']') break
            val match = STRING.matchAt(flight, pos) ?: break
            values.add(match.groupValues[1])
            pos = match.range.last + 1
            while (pos < flight.length && flight[pos].isWhitespace()) pos++
            if (pos < flight.length && flight[pos] == ',') pos++
        }
        return values
    }

    // Chapters

    // The novel page's flight only embeds a slice of chapters; the api-web endpoint returns the
    // full list in one call (limit == total, single page), so use it instead.
    private fun loadChapterList(manga: SManga): List<SChapter> {
        val slug = mangaPathTemplate.resolve(manga.url).substringAfter("/novel/").trim('/').substringBefore('/')
        val response = client.newCall(GET("$baseUrl/api-web/novels/$slug/chapters?sort=asc", headers)).execute()
        val data = json.decodeFromString<ChapterListResponse>(response.body.string())
        // API returns oldest-first; number ascending then present newest-first.
        return data.items.mapIndexed { index, item ->
            SChapter.create().apply {
                url = "/chapter/$slug/${item.chapter_id}"
                name = if (item.premium_content) "🔒 ${item.chapter_name}" else item.chapter_name
                chapter_number = (index + 1).toFloat()
            }
        }.reversed()
    }

    @kotlinx.serialization.Serializable
    private class ChapterListResponse(val items: List<ChapterItem> = emptyList())

    @kotlinx.serialization.Serializable
    private class ChapterItem(
        val chapter_id: String = "",
        val chapter_name: String = "",
        val premium_content: Boolean = false,
    )

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPathTemplate.resolve(manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = mangaPathTemplate.slug(url.encodedPath)
        val tempManga = SManga.create().apply { this.url = slug }
        val response = client.newCall(buildMangaDetailsRequest(tempManga)).execute()
        if (!response.isSuccessful) return null
        return parseMangaDetails(response.body.string()).apply { this.url = slug }
    }

    // Content

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) page.url else baseUrl + page.url
        val flight = client.newCall(GET(url, rscHeaders())).execute().body.string()

        val refId = CONTENT_REF.firstGroup(flight) ?: return ""
        // Flight text chunk: "<id>:T<hexByteLength>,<content>". Cut exactly hexByteLength UTF-8
        // bytes so the trailing flight rows on the same line aren't swept into the content.
        val header = Regex("(?:^|\\n)$refId:T([0-9a-f]+),").find(flight) ?: return ""
        val byteLength = header.groupValues[1].toInt(16)
        val bytes = flight.substring(header.range.last + 1).toByteArray(Charsets.UTF_8)
        return String(bytes.copyOfRange(0, minOf(byteLength, bytes.size)), Charsets.UTF_8).trim()
    }

    // Filters

    override fun getFilterList(data: JsonElement?) = FilterList(GenreFilter())

    private class GenreFilter : Filter.Select<String>("Genre", GENRES.map { it.first }.toTypedArray()) {
        fun selected() = GENRES[state].second
    }

    private fun Regex.firstGroup(input: String): String? = find(input)?.groupValues?.getOrNull(1)

    private object STRING {
        // Matches a JSON string body (without the surrounding quotes), honoring backslash escapes.
        private val QUOTED = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        fun matchAt(input: String, index: Int) = QUOTED.matchAt(input, index)
        fun decode(raw: String?): String? {
            if (raw == null) return null
            return try {
                Json.decodeFromString<String>("\"$raw\"").takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                raw
            }
        }
    }

    companion object {
        private val OG_TITLE = Regex("\"og:title\",\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        private val OG_IMAGE_ALT = Regex("\"og:image:alt\",\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        private val COVER = Regex("\"coverImage\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        private val AUTHOR = Regex("\"author\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        private val STATUS = Regex("\"status\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        private val GENRES_ARRAY = Regex("\"genres\":\\[((?:[^\\[\\]]|\\\\.)*)\\]")
        private val GENRE_LABEL = Regex("\"label\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        private val ARTICLE_TAG = Regex("\"article:tag\",\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        private val CONTENT_REF = Regex("\"chapter_content\":\"\\\$([0-9a-f]+)\"")

        private val GENRES = listOf(
            "Action" to "action",
            "Adult" to "adult",
            "Adventure" to "adventure",
            "Comedy" to "comedy",
            "Drama" to "drama",
            "Eastern" to "eastern",
            "Ecchi" to "ecchi",
            "Fan-fiction" to "fan-fiction",
            "Fantasy" to "fantasy",
            "Harem" to "harem",
            "Historical" to "historical",
            "Horror" to "horror",
            "Josei" to "josei",
            "Martial Arts" to "martial-arts",
            "Mature" to "mature",
            "Mecha" to "mecha",
            "Mystery" to "mystery",
            "Psychological" to "psychological",
            "Romance" to "romance",
            "School Life" to "school-life",
            "Sci-fi" to "sci-fi",
            "Seinen" to "seinen",
            "Shoujo" to "shoujo",
            "Shounen" to "shounen",
            "Slice of Life" to "slice-of-life",
            "Supernatural" to "supernatural",
            "Wuxia" to "wuxia",
            "Xianxia" to "xianxia",
            "Xuanhuan" to "xuanhuan",
        )
    }
}

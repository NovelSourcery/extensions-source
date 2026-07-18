package eu.kanade.tachiyomi.novelextension.en.novelarrow

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
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
class NovelArrow :
    HttpSource(),
    NovelSource {

    override val name = "NovelArrow"
    override val baseUrl = "https://novelarrow.com"
    override val lang = "en"
    override val supportsLatest = true
    override val isNovelSource = true

    private val json = Json { ignoreUnknownKeys = true }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
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
        .build()

    // Next.js only returns the flight payload (details/chapters/content) when this header is set.
    private fun rscHeaders() = headersBuilder().add("RSC", "1").build()

    // Browse

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/genre/action?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = browseParse(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/genre/action?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = browseParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val genre = (filters.firstOrNull { it is GenreFilter } as? GenreFilter)?.selected() ?: "action"
        return GET("$baseUrl/genre/$genre?page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = browseParse(response)

    private fun browseParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        val mangas = doc.select("a[href^=/novel/]:has(img)")
            .distinctBy { it.attr("href") }
            .mapNotNull { a ->
                val img = a.selectFirst("img") ?: return@mapNotNull null
                SManga.create().apply {
                    setUrlWithoutDomain(a.attr("abs:href"))
                    title = img.attr("alt").removeSuffix(" - Novel cover").trim()
                    thumbnail_url = img.attr("abs:src")
                }
            }
            .filter { it.title.isNotBlank() }
        return MangasPage(mangas, mangas.size >= 20)
    }

    // Details

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, rscHeaders())

    override fun mangaDetailsParse(response: Response): SManga {
        val flight = response.body.string()
        return SManga.create().apply {
            title = STRING.decode(OG_TITLE.firstGroup(flight))
                ?.substringBefore(" Novel | Read Online")?.trim()
                ?: STRING.decode(OG_IMAGE_ALT.firstGroup(flight)).orEmpty()
            thumbnail_url = STRING.decode(COVER.firstGroup(flight))
            author = STRING.decode(AUTHOR.firstGroup(flight))
            // The novel's own genres are the "genres" array whose items carry "label"/"href"
            // (a separate "genres" array holds the site-wide genre nav).
            genre = GENRES_ARRAY.findAll(flight)
                .map { it.groupValues[1] }
                .firstOrNull { it.contains("\"label\"") }
                ?.let { body ->
                    GENRE_LABEL.findAll(body).mapNotNull { STRING.decode(it.groupValues[1]) }
                        .map { label -> label.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) } }
                        .distinct().joinToString(", ")
                }
            status = when (STRING.decode(STATUS.firstGroup(flight))?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "dropped", "cancelled" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            description = SYNOPSIS.firstGroup(flight)?.let { arrayBody ->
                STRING.findAll(arrayBody).mapNotNull { STRING.decode(it.groupValues[1]) }
                    .joinToString("\n\n")
            }
        }
    }

    // Chapters

    // The novel page's flight only embeds a slice of chapters; the api-web endpoint returns the
    // full list in one call (limit == total, single page), so use it instead.
    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.substringAfter("/novel/").trim('/').substringBefore('/')
        return GET("$baseUrl/api-web/novels/$slug/chapters?sort=asc", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val slug = response.request.url.pathSegments.getOrNull(2).orEmpty()
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

    // Content

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.encodedPath))

    override fun imageUrlParse(response: Response): String = ""

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

    override fun getFilterList() = FilterList(GenreFilter())

    private class GenreFilter : Filter.Select<String>("Genre", GENRES.map { it.first }.toTypedArray()) {
        fun selected() = GENRES[state].second
    }

    private fun Regex.firstGroup(input: String): String? = find(input)?.groupValues?.getOrNull(1)

    private object STRING {
        // Matches a JSON string body (without the surrounding quotes), honoring backslash escapes.
        private val QUOTED = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        fun findAll(input: String) = QUOTED.findAll(input)
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
        private val SYNOPSIS = Regex("\"synopsisParagraphs\":\\[((?:[^\\[\\]]|\\\\.)*)\\]")
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

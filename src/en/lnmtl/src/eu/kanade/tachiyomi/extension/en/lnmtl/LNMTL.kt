package eu.kanade.tachiyomi.novelextension.en.lnmtl

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document

/**
 * LNMTL (lnmtl.com). Machine-translated Chinese web novels. There is no CatalogueSource here
 * before this - lnmtl.com URLs were only reachable via an on-device custom source, which is why
 * they showed up as lateinit-title crashes in mass-import logs.
 *
 * Browse/details are plain server-rendered Bootstrap HTML. Search has no server endpoint - the
 * site ships a client-side `novels-<hash>.json` full catalogue (prefetch path is only discoverable
 * from a `<script>` tag in the homepage footer, see [searchIndexUrl]) and filters it in JS; we do
 * the same. Chapter lists are the expensive part: the novel page embeds a `lnmtl.volumes` JSON
 * array, and each volume's chapters live behind a separate paginated `/chapter?volumeId=` JSON
 * endpoint - building a full chapter list means one request per volume page.
 *
 * Chapter content is a `<sentence class="translated">` per source sentence, itself containing
 * nested `<w>/<t>` spans (one per translated word/term, `data-title` holds the original Chinese) -
 * Jsoup's `.text()` on the sentence flattens those back into a normal (if roughly MTL'd) sentence.
 */
@Source
abstract class LNMTL :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .add(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        )

    private val json = Json { ignoreUnknownKeys = true }

    /** [SManga.url] is stored as the bare slug under `/novel/`; a stored value starting with
     * "/" is a pre-existing full-path entry and is resolved unchanged. */
    private val mangaPath = SlugPath("/novel/")

    /** Stores [SManga.url] as a bare slug via [mangaPath]. */
    private fun SManga.setSlugUrl(href: String) {
        setUrlWithoutDomain(href)
        url = mangaPath.slug(url)
    }

    // ======================== Browse ========================

    private fun novelListRequest(page: Int, orderBy: String): Request {
        val url = "$baseUrl/novel".toHttpUrl().newBuilder()
            .addQueryParameter("orderBy", orderBy)
            .addQueryParameter("order", "desc")
            .addQueryParameter("filter", "all")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = parseNovelListing(client.newCall(novelListRequest(page, "favourites")).execute())

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseNovelListing(client.newCall(novelListRequest(page, "date")).execute())

    private fun parseNovelListing(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = doc.select("div.media-left a").mapNotNull { a ->
            val href = a.attr("abs:href")
            val img = a.selectFirst("img") ?: return@mapNotNull null
            if (href.isBlank()) return@mapNotNull null
            SManga.create().apply {
                setSlugUrl(href)
                title = img.attr("alt")
                thumbnail_url = img.attr("abs:src")
            }
        }
        val hasNextPage = doc.selectFirst("ul.pagination a[rel=next]") != null
        return MangasPage(mangas, hasNextPage)
    }

    // ======================== Search ========================
    // No server-side search - the site filters a client-side JSON catalogue in JS. The catalogue's
    // url is content-hashed and only discoverable from a script tag in the homepage footer.

    private val searchPageSize = 20

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val indexUrl = searchIndexUrl()
        val body = client.newCall(GET(indexUrl, headers)).execute().use { it.body.string() }
        val all = json.parseToJsonElement(body).jsonArray

        fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val needle = normalize(query)

        val matches = all.mapNotNull { element ->
            val item = element.jsonObject
            val name = item["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (needle.isNotEmpty() && !normalize(name).contains(needle)) return@mapNotNull null
            val slug = item["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SManga.create().apply {
                setSlugUrl("$baseUrl/novel/$slug")
                title = name
                thumbnail_url = item["image"]?.jsonPrimitive?.contentOrNull
            }
        }

        val from = (page - 1) * searchPageSize
        val to = minOf(from + searchPageSize, matches.size)
        if (from >= matches.size) return MangasPage(emptyList(), false)
        return MangasPage(matches.subList(from, to), to < matches.size)
    }

    private fun searchIndexUrl(): String {
        val home = client.newCall(GET(baseUrl, headers)).execute().use { it.asJsoup() }
        val prefetchRegex = Regex("""prefetch:\s*'(/[^']+\.json)""")
        for (script in home.select("footer script")) {
            val match = prefetchRegex.find(script.data()) ?: continue
            return baseUrl + match.groupValues[1]
        }
        throw Exception("Could not find LNMTL search catalogue url")
    }

    // ======================== Details + Chapters ========================

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPath.resolve(manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val manga = SManga.create().apply { this.url = mangaPath.slug(url.encodedPath) }
        val response = client.newCall(buildMangaDetailsRequest(manga)).execute()
        if (!response.isSuccessful) return null
        return parseMangaDetails(response.asJsoup()).apply { this.url = manga.url }
    }

    private fun buildMangaDetailsRequest(manga: SManga): Request = GET(baseUrl + mangaPath.resolve(manga.url), headers)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Details and the chapter volumes list both live on the same novel page - fetch it once.
        val doc = client.newCall(buildMangaDetailsRequest(manga)).execute().asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(doc) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(doc: Document): SManga = SManga.create().apply {
        val cover = doc.selectFirst("img.img-rounded")
        title = cover?.attr("title").orEmpty()
        thumbnail_url = cover?.attr("abs:src")

        author = doc.selectFirst("dt:containsOwn(Authors) ~ dd")?.text()?.trim()

        status = when (doc.selectFirst("dt:containsOwn(Current status) ~ dd")?.text()?.trim()) {
            "Ongoing" -> SManga.ONGOING
            "Completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        genre = doc.select("div.panel-heading:containsOwn(Genres) ~ div.panel-body ul.list-inline li a")
            .joinToString(", ") { it.text().trim() }

        description = doc.select("div.description p").joinToString("\n\n") { it.text().trim() }
            .ifBlank { null }
    }

    // The novel page embeds `lnmtl.volumes = [...]`; each volume's chapters live behind a
    // paginated `/chapter?volumeId=X&page=N` JSON endpoint. Building the full chapter list means
    // walking every volume's every page - there is no single "give me everything" endpoint.
    private fun parseChapterList(doc: Document): List<SChapter> {
        val volumesJson = doc.select("script").map { it.data() }
            .firstNotNullOfOrNull { Regex("""lnmtl\.volumes\s*=\s*(\[.+?])\s*;""").find(it) }
            ?: return emptyList()
        val volumes = json.parseToJsonElement(volumesJson.groupValues[1]).jsonArray

        val chapters = mutableListOf<SChapter>()
        for (volume in volumes) {
            val volumeId = volume.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: continue
            var page = 1
            var lastPage = 1
            do {
                val url = "$baseUrl/chapter".toHttpUrl().newBuilder()
                    .addQueryParameter("volumeId", volumeId)
                    .addQueryParameter("page", page.toString())
                    .build()
                val pageBody = client.newCall(GET(url, headers)).execute().use { it.body.string() }
                val pageJson = json.parseToJsonElement(pageBody).jsonObject
                lastPage = pageJson["last_page"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
                val data = pageJson["data"]?.jsonArray ?: break

                for (chEl in data) {
                    val ch = chEl.jsonObject
                    val slug = ch["slug"]?.jsonPrimitive?.contentOrNull ?: continue
                    val number = ch["number"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                    val title = ch["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    chapters.add(
                        SChapter.create().apply {
                            setUrlWithoutDomain("$baseUrl/chapter/$slug")
                            name = if (title.isNotBlank()) "Chapter ${trimNumber(number)} - $title" else "Chapter ${trimNumber(number)}"
                            chapter_number = number
                        },
                    )
                }
                page++
            } while (page <= lastPage)
        }

        // Volumes/chapters come back oldest-first; the app expects newest-first.
        return chapters.reversed()
    }

    private fun trimNumber(number: Float): String = if (number == number.toInt().toFloat()) number.toInt().toString() else number.toString()

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    // ======================== Content ========================
    // Single metadata page per chapter; the real fetch happens in fetchPageText.

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.newCall(GET("$baseUrl${page.url}", headers)).execute().use { it.asJsoup() }
        return extractChapterText(doc)
    }

    // Sentences are flat siblings in the source HTML (translated/original alternating, no
    // paragraph wrapper) - one <p> per sentence matches the site's own rendering.
    private fun extractChapterText(doc: Document): String = doc.select("sentence.translated")
        .map { it.text().trim().replace('„', '"') }
        .filter { it.isNotEmpty() }
        .joinToString("") { "<p>$it</p>" }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()
}

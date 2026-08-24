package eu.kanade.tachiyomi.novelextension.en.genesisstudio

import android.app.Application
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * GenesisStudio (genesistudio.com). Directus-backed: the full novel catalog is a single
 * `/api/directus/novels` call (only ~100 novels total, no real pagination), novel detail is
 * `/api/directus/novels/by-abbreviation/{abbreviation}`, chapters are
 * `/api/novels-chapter/{novelId}`. Ported from the LNReader plugin
 * (lnreader-plugins-master/plugins/english/genesis.ts); that plugin's chapter-content fetch
 * (extracting a Supabase anon key from a JS chunk, then querying the chapters table directly)
 * is dead - the site locked down anonymous SELECT on `chapter_content` since. Chapter text is
 * still readable though: it's server-rendered into the `/viewer/{chapterId}` (redirects to
 * `/novels/{slug}/chapter-{n}`) page as the single largest `self.__next_f.push([1,"..."])` RSC
 * string literal, so we just decode that instead of touching Supabase.
 *
 * manga.url packs 3 values needed by different endpoints: "{abbreviation}|{novelId}|{slug}".
 * chapter.url packs "{novelId}|{chapterId}".
 */
class GenesisStudio :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "GenesisStudio"
    override val baseUrl = "https://genesistudio.com"
    private val apiUrl = "https://api.genesistudio.com"
    override val lang = "en"
    override val supportsLatest = false
    override val isNovelSource = true

    private val json = Json { ignoreUnknownKeys = true }

    private val listFields = """["id","novel_title","cover","abbreviation","slug","coverFile.filename_disk"]"""

    // ======================== Browse / Search ========================

    override fun popularMangaRequest(page: Int): Request = novelsListRequest()

    // No separate "latest" feed exists (single unpaginated catalog call); supportsLatest is
    // false so these are never actually invoked, but HttpSource requires an implementation.
    override fun latestUpdatesRequest(page: Int): Request = novelsListRequest()
    override fun latestUpdatesParse(response: Response): MangasPage = parseNovelsList(response, null)

    // The catalog is a single unpaginated call; the query rides in the URL fragment (never sent
    // over the wire) so searchMangaParse can filter client-side without a second request.
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = novelsListRequest().let { it.newBuilder().url(it.url.newBuilder().fragment(query).build()).build() }

    private fun novelsListRequest(): Request {
        val url = "$baseUrl/api/directus/novels".toHttpUrl().newBuilder()
            .addQueryParameter("status", "published")
            .addQueryParameter("fields", listFields)
            .addQueryParameter("limit", "-1")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseNovelsList(response, null)

    override fun searchMangaParse(response: Response): MangasPage = parseNovelsList(response, response.request.url.fragment)

    private fun parseNovelsList(response: Response, query: String?): MangasPage {
        val array = json.parseToJsonElement(response.body.string()).jsonArray
        val mangas = array.mapNotNull { element ->
            val item = element.jsonObject
            val abbreviation = item["abbreviation"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val slug = item["slug"]?.jsonPrimitive?.contentOrNull ?: abbreviation
            val title = item["novel_title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SManga.create().apply {
                this.title = title
                this.url = "$abbreviation|$id|$slug"
                thumbnail_url = coverUrl(item)
            }
        }
        val filtered = if (query.isNullOrBlank()) mangas else mangas.filter { normalize(it.title).contains(normalize(query)) }
        return MangasPage(filtered, false)
    }

    private fun coverUrl(item: kotlinx.serialization.json.JsonObject): String? {
        val coverId = item["cover"]?.jsonPrimitive?.contentOrNull ?: return null
        val diskName = item["coverFile"]?.jsonObject?.get("filename_disk")?.jsonPrimitive?.contentOrNull
        val ext = diskName?.substringAfterLast('.', "png") ?: "png"
        return "$apiUrl/storage/v1/object/public/directus/$coverId.$ext"
    }

    private fun normalize(value: String) = value.lowercase().filter { it.isLetterOrDigit() }

    // ======================== Details ========================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novels/${manga.url.split('|').getOrElse(2) { manga.url }}"

    override fun mangaDetailsRequest(manga: SManga): Request {
        val abbreviation = manga.url.substringBefore('|')
        return GET("$baseUrl/api/directus/novels/by-abbreviation/$abbreviation", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val data = json.parseToJsonElement(response.body.string()).jsonObject
        val abbreviation = data["abbreviation"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val id = data["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val slug = data["slug"]?.jsonPrimitive?.contentOrNull ?: abbreviation
        return SManga.create().apply {
            title = data["novel_title"]!!.jsonPrimitive.content
            url = "$abbreviation|$id|$slug"
            thumbnail_url = coverUrl(data)
            description = data["synopsis"]?.jsonPrimitive?.contentOrNull
            author = data["author"]?.jsonPrimitive?.contentOrNull
            genre = data["genres"]?.jsonArray
                ?.mapNotNull { it.jsonObject["genres_id"]?.jsonObject?.get("label")?.jsonPrimitive?.contentOrNull }
                ?.joinToString(", ")
            status = when (data["serialization"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "hiatus" -> SManga.ON_HIATUS
                "dropped", "cancelled" -> SManga.CANCELLED
                "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.split('|').getOrElse(1) { "" }
        return GET("$baseUrl/api/novels-chapter/$id", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val novelId = response.request.url.pathSegments.last()
        val chapters = json.parseToJsonElement(response.body.string())
            .jsonObject["data"]?.jsonObject?.get("chapters")?.jsonArray ?: return emptyList()
        val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)

        return chapters.mapNotNull { element ->
            val item = element.jsonObject
            val chapterId = item["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val number = item["chapter_number"]?.jsonPrimitive?.double ?: 0.0
            val title = item["chapter_title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val unlocked = item["isUnlocked"]?.jsonPrimitive?.boolean ?: true
            if (!unlocked && !showLocked) return@mapNotNull null
            SChapter.create().apply {
                name = buildString {
                    if (!unlocked) append("🔒 ")
                    append("Chapter ${number.toString().removeSuffix(".0")}")
                    if (title.isNotBlank()) append(": $title")
                }
                chapter_number = number.toFloat()
                url = "$novelId|$chapterId"
            }
        }.reversed()
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val chapterId = chapter.url.substringAfter('|')
        return "$baseUrl/viewer/$chapterId"
    }

    // ======================== Content ========================
    // Chapter text is server-rendered into the page as an RSC string literal, not fetched via a
    // JSON API - see class kdoc. The single largest `self.__next_f.push([1,"..."])` payload on
    // the page is reliably the chapter body (verified: ~14000 chars vs ~5000 for the next-largest
    // metadata push).

    override fun pageListRequest(chapter: SChapter): Request = throw UnsupportedOperationException("Not used")
    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException("Not used")
    override fun fetchPageList(chapter: SChapter): rx.Observable<List<Page>> = rx.Observable.just(listOf(Page(0, getChapterUrl(chapter))))

    override fun imageUrlParse(response: Response): String = ""

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(page.url, headers)).execute()
        val html = response.body.string()
        val matches = PUSH_REGEX.findAll(html).map { it.groupValues[1] }.toList()

        // The longest push is usually the chapter body, but for a locked/paywalled chapter (or a
        // real chapter shorter than the route's own metadata push) the server never sends prose at
        // all - the longest push is then Next.js's own route/asset tree (starts with a frame index
        // like `0:{...}` and is full of `_next/static`, `$undefined`, `crossOrigin` markers). Reject
        // those instead of rendering them as garbage chapter text.
        val prose = matches
            .sortedByDescending { it.length }
            .firstOrNull { candidate -> RSC_METADATA_MARKERS.none { candidate.contains(it) } }
            ?: throw Exception("Chapter content is locked or unavailable on GenesisStudio")

        val decoded = runCatching { json.decodeFromString<String>("\"$prose\"") }
            .getOrElse { throw Exception("Failed to decode GenesisStudio chapter content") }
        return decoded
            .substringAfter("\n\n", decoded) // drop the leading "Chapter N. Title" line if present
            .split("\n\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("") { "<p>$it</p>" }
    }

    override fun getFilterList(): FilterList = FilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Include paywalled chapters in the chapter list (their content can't be read without an account)."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    companion object {
        private val PUSH_REGEX = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""")
        private val RSC_METADATA_MARKERS = listOf("_next/static", "\$undefined", "crossOrigin")
        private const val PREF_SHOW_LOCKED = "show_locked_chapters"
    }
}

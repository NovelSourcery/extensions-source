package eu.kanade.tachiyomi.novelextension.en.novelight

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.RefreshContext
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.formattedText
import keiyoushi.utils.setNumber
import keiyoushi.utils.setVolume
import keiyoushi.utils.stripChapterNumberPrefix
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Novelight :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "Novelight"
    override val baseUrl = "https://novelight.net"
    override val lang = "en"
    override val supportsLatest = true
    override val isNovelSource = true
    override val client = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }
    private val hideLocked get() = preferences.getBoolean(PREF_HIDE_LOCKED, false)

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/catalog/?ordering=popularity&page=$page", headers)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/catalog/?ordering=-time_updated&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseCatalog(response)
    override fun latestUpdatesParse(response: Response): MangasPage = parseCatalog(response)

    private fun parseCatalog(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        val novels = doc.select("a.item").mapNotNull { el ->
            val href = el.attr("href").ifBlank { return@mapNotNull null }
            SManga.create().apply {
                title = el.selectFirst("div.title")?.text()?.trim().orEmpty()
                url = "/" + href.trimStart('/')
                thumbnail_url = el.selectFirst("img")?.attr("abs:src")
            }
        }
        val hasNext = doc.selectFirst("a.next, .pagination a[rel=next], a.page-link[rel=next]") != null ||
            novels.isNotEmpty()
        return MangasPage(novels, hasNext)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/catalog/".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseCatalog(response)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = doc.selectFirst(".poster > img")?.attr("abs:src")
            description = doc.selectFirst("section.text-info.section > p, section.text-info.section")?.formattedText()

            var statusText = ""
            var translation = ""
            doc.select("div.mini-info > .item").forEach { item ->
                val type = item.selectFirst(".sub-header")?.text()?.trim()
                val value = item.selectFirst("div.info")?.text()?.trim().orEmpty()
                when (type) {
                    "Status" -> statusText = value.lowercase()
                    "Translation" -> translation = value.lowercase()
                    "Author" -> author = value
                    "Genres" -> genre = item.select("div.info > a").joinToString(", ") { it.text().trim() }
                }
            }
            status = when {
                statusText == "cancelled" -> SManga.CANCELLED
                statusText == "releasing" || translation == "ongoing" -> SManga.ONGOING
                statusText == "completed" && translation == "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    @Serializable
    private class HtmlPayload(val html: String = "")

    private val chapterNumberPattern: Regex = Regex("""^\s*(?:\d+\s*vol\.?\s*)?\d+\s*chapter\s*[-–—]?\s*""", RegexOption.IGNORE_CASE)

    override suspend fun getChapterList(manga: SManga, context: RefreshContext): List<SChapter> {
        val rawBody = client.newCall(GET(baseUrl + manga.url, headers)).execute().body.string()
        val detailDoc = Jsoup.parse(rawBody, baseUrl)
        // csrfmiddlewaretoken is optional on the pagination endpoint.
        val csrf = Regex("""window\.CSRF_TOKEN = "([^"]+)"""").find(rawBody)?.groupValues?.get(1)
        val bookId = Regex("""const OBJECT_BY_COMMENT = ([0-9]+)""").find(rawBody)?.groupValues?.get(1)
            ?: return context.existingChapters
        val totalPages = detailDoc.select("#select-pagination-chapter option").size.coerceAtLeast(1)

        // "Loaded Chapters" count; skip the full fetch when nothing changed.
        val siteTotal = detailDoc.select("div.mini-info .item, .item")
            .firstOrNull { it.selectFirst(".sub-header")?.text()?.contains("Loaded Chapters", true) == true }
            ?.selectFirst(".info")?.text()?.trim()?.toIntOrNull() ?: 0
        if (context.existingChapters.isNotEmpty() && siteTotal > 0 &&
            context.existingChapters.size == siteTotal
        ) {
            return context.existingChapters
        }

        fun ajaxPage(sitePage: Int): List<ChapterDto> {
            val ajaxUrl = "$baseUrl/book/ajax/chapter-pagination".toHttpUrl().newBuilder()
                .apply { if (csrf != null) addQueryParameter("csrfmiddlewaretoken", csrf) }
                .addQueryParameter("book_id", bookId)
                .addQueryParameter("page", sitePage.toString())
                .build()
            val req = GET(
                ajaxUrl,
                headers.newBuilder()
                    .add("Referer", baseUrl + manga.url)
                    .add("X-Requested-With", "XMLHttpRequest")
                    .build(),
            )
            val payload = json.decodeFromString<HtmlPayload>(client.newCall(req).execute().body.string())
            return Jsoup.parse("<html>${payload.html}</html>", baseUrl).select("a").mapNotNull { a ->
                val titleEl = a.selectFirst(".title")
                val fullTitle = titleEl?.text()?.trim().orEmpty()
                // The chapter number is embedded in the title, e.g. "130 chapter - Bronze Blood".
                val volume = Regex("""(\d+)\s*vol\.""", RegexOption.IGNORE_CASE)
                    .find(fullTitle)?.groupValues?.get(1)?.toIntOrNull()
                val number = Regex("""(\d+)\s*chapter""", RegexOption.IGNORE_CASE)
                    .find(fullTitle)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                val title = titleEl?.selectFirst("span")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: fullTitle
                        .replace(chapterNumberPattern, "")
                        .stripChapterNumberPrefix()
                val locked = a.selectFirst(".cost") != null
                if (hideLocked && locked) return@mapNotNull null
                val href = a.attr("href").ifBlank { return@mapNotNull null }
                ChapterDto(
                    name = buildString {
                        if (locked) append("🔒 ")
                        when (volume) {
                            null -> append("Chapter $number")
                            else -> append("Vol. $volume Ch. $number")
                        }

                        title.replace(chapterNumberPattern, "")
                            .takeIf { !isNullOrBlank() }
                            .let { append(" - $it") }
                    },
                    url = "/" + href.trimStart('/'),
                    chapter = number,
                    volume = volume,
                    date = a.selectFirst(".date")?.text()?.trim(),
                )
            }
        }

        suspend fun ajaxPageRetry(sitePage: Int): List<ChapterDto> {
            repeat(3) {
                try {
                    return ajaxPage(sitePage)
                } catch (_: Exception) {
                    delay(200)
                }
            }
            return emptyList()
        }

        // Fetch pages sequentially with a small delay; concurrent/too-fast requests trip the
        // site's rate limiting and pages come back empty.
        val chapters = mutableListOf<ChapterDto>()
        for (p in 1..totalPages) {
            chapters += ajaxPageRetry(p)
            delay(100)
        }

        return chapters.sortedWith(compareBy({ it.chapter }, { it.volume ?: 0 })).map {
            SChapter.create().apply {
                name = it.name
                url = it.url
                chapter_number = it.chapter.toFloat()
                date_upload = parseDate(it.date)
                scanlator = when (it.volume) {
                    null -> "standalone"
                    else -> "volume"
                }

                setNumber(it.chapter.toString())
                it.volume?.let { setVolume(toString()) }
            }
        }.asReversed()
    }

    override fun chapterListParse(response: Response): List<SChapter> = emptyList()

    private fun parseDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        return try {
            DATE_FORMAT.parse(date)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.encodedPath))

    override fun imageUrlParse(response: Response): String = ""

    @Serializable
    private class ReadChapter(val content: String = "")

    override suspend fun fetchPageText(page: Page): String {
        // page.url is /book/chapter/<id>; content comes from the read-chapter JSON endpoint.
        val chapterId = page.url.trimEnd('/').substringAfterLast('/')
        val chapterUrl = baseUrl + "/" + page.url.trimStart('/')
        val req = GET(
            "$baseUrl/book/ajax/read-chapter/$chapterId",
            headers.newBuilder()
                .add("Referer", chapterUrl)
                .add("X-Requested-With", "XMLHttpRequest")
                .build(),
        )
        val data = json.decodeFromString<ReadChapter>(client.newCall(req).execute().body.string())
        if (data.content.isBlank()) return ""
        val doc = Jsoup.parse(data.content, baseUrl)
        doc.select("script, ins, .advertisment, .adsbygoogle").remove()
        val content = doc.selectFirst("div.chapter-text, div[class^=chapter-text]") ?: doc.body()
        return content.html()
            .replace(Regex("""\[\s*N\s*O\s*V\s*E\s*L\s*I\s*G\s*H\s*T\s*]"""), "")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(
            SwitchPreferenceCompat(screen.context).apply {
                key = PREF_HIDE_LOCKED
                title = "Hide locked chapters"
                setDefaultValue(false)
            },
        )
    }

    companion object {
        private const val PREF_HIDE_LOCKED = "pref_hide_locked"
        private val DATE_FORMAT = SimpleDateFormat("dd.MM.yyyy", Locale.US)
    }

    data class ChapterDto(
        val name: String,
        val url: String,
        val chapter: Int,
        val volume: Int?,
        val date: String?,
    )
}

package eu.kanade.tachiyomi.novelextension.en.penguinsquad

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PenguinSquad :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "PenguinSquad"
    override val baseUrl = "https://penguin-squad.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val showPremium: Boolean
        get() = preferences.getBoolean(PREF_SHOW_PREMIUM, false)

    // ---- Browse ----

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/novels", headers)

    override fun popularMangaParse(response: Response): MangasPage = MangasPage(response.asJsoup().parseNovelCards(), false)

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val section = doc.select("section")
            .firstOrNull { it.selectFirst("h2")?.ownText() == "Newly Added" }
            ?: doc
        return MangasPage(section.parseNovelCards(), false)
    }

    // The site has no server-side text search; ?genre= is the only server filter.
    // The query is carried in the URL fragment (never sent to the server) and
    // applied client-side in searchMangaParse.
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/novels".toHttpUrl().newBuilder().apply {
            filters.filterIsInstance<GenreFilter>().firstOrNull()
                ?.selectedGenre()
                ?.let { addQueryParameter("genre", it) }
            if (query.isNotBlank()) {
                fragment(query)
            }
        }.build()
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val query = response.request.url.fragment.orEmpty()
        val cards = response.asJsoup().parseNovelCards()
            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
        return MangasPage(cards, false)
    }

    private fun Element.parseNovelCards(): List<SManga> = select("a[href^=/novels/]:has(h3)")
        .distinctBy { it.attr("href") }
        .map { card ->
            SManga.create().apply {
                url = card.attr("href")
                title = card.selectFirst("h3")!!.text()
                thumbnail_url = card.selectFirst("img")?.absUrl("src")
            }
        }

    // ---- Details ----

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()

        return SManga.create().apply {
            url = "/novels/${response.request.url.pathSegments.last()}"
            title = doc.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = doc.selectFirst("img[src*=/covers/]")?.absUrl("src")
            description = doc.selectFirst("p[class*=line-clamp-3]")?.text()
            genre = doc.select("span[data-slot=badge][data-variant=outline]")
                .eachText()
                .distinct()
                .joinToString(", ")
            author = doc.selectFirst("span:containsOwn(Translated by)")
                ?.text()
                ?.removePrefix("Translated by")
                ?.trim()
            status = when (
                doc.select("span[data-slot=badge][data-variant=default]")
                    .eachText()
                    .firstOrNull { it.lowercase() in STATUS_VALUES }
                    ?.lowercase()
            ) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "dropped" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    // ---- Chapters ----

    @Serializable
    private data class ChapterListDto(
        val novelSlug: String,
        val chapters: List<ChapterDto>,
    )

    @Serializable
    private data class ChapterDto(
        val title: String,
        val slug: String,
        @SerialName("chapter_number") val chapterNumber: Float,
        @SerialName("published_at") val publishedAt: String? = null,
        val premium: Boolean = false,
    )

    override fun chapterListParse(response: Response): List<SChapter> {
        // The full chapter list (free + premium) is embedded in the page's
        // RSC flight data as {"novelSlug": ..., "chapters": [...]}.
        val dto = response.asJsoup().extractNextJs<ChapterListDto>()
            ?: throw Exception("Could not find chapter list in page data")

        return dto.chapters
            .filter { showPremium || !it.premium }
            .map { chapter ->
                SChapter.create().apply {
                    url = "/novels/${dto.novelSlug}/${chapter.slug}"
                    name = buildString {
                        if (chapter.premium) append("🔒 ")
                        append(chapter.title)
                    }
                    chapter_number = chapter.chapterNumber
                    date_upload = parseDate(chapter.publishedAt)
                }
            }
            .sortedByDescending { it.chapter_number }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // published_at looks like "2024-10-06T00:00:00+00:00", sometimes with millis.
    // Offsets are always +00:00, so parse the date-time part as UTC.
    private fun parseDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val normalized = date.substringBefore('+').substringBefore('.')
        return runCatching { dateFormat.parse(normalized)?.time }.getOrNull() ?: 0L
    }

    // ---- Chapter content ----

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString().removePrefix(baseUrl)
        return listOf(Page(0, chapterUrl))
    }

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val content = response.asJsoup().selectFirst("div.reader-content")
            ?: throw Exception("Chapter content not found – this may be a premium chapter")
        return content.html()
    }

    override fun imageUrlParse(response: Response): String = ""

    // ---- Preferences ----

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_PREMIUM
            title = "Show premium chapters"
            summary = "Include premium/locked chapters in the chapter list"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    // ---- Filters ----

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Text search is applied client-side"),
        GenreFilter(),
    )

    private class GenreFilter :
        Filter.Select<String>(
            "Genre",
            arrayOf(
                "All",
                "Academy",
                "Action",
                "Adventure",
                "Comedy",
                "Cultivation",
                "Drama",
                "Fantasy",
                "Horror",
                "Martial Arts",
                "Modern Fantasy",
                "Romance",
                "Sci-Fi",
                "Tragedy",
            ),
        ) {
        fun selectedGenre(): String? = if (state == 0) null else values[state]
    }

    companion object {
        private const val PREF_SHOW_PREMIUM = "show_premium_chapters"
        private val STATUS_VALUES = setOf("ongoing", "completed", "hiatus", "dropped")
    }
}

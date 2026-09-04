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
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class PenguinSquad :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val showPremium: Boolean
        get() = preferences.getBoolean(PREF_SHOW_PREMIUM, false)

    /**
     * The site's novel detail URL shape, as `/novels/<slug>`. [SManga.url] is stored as the
     * bare slug (see [SlugPath]); a stored value starting with "/" is a pre-existing full-path
     * entry from before this source adopted slug storage, and is resolved unchanged regardless
     * of this template.
     */
    protected open val mangaPathTemplate: SlugPath = SlugPath("/novels/")

    // ---- Browse ----

    protected open fun buildPopularMangaRequest(page: Int): Request = GET("$baseUrl/novels", headers)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val popularRequest = buildPopularMangaRequest(page)
        return MangasPage(client.get(popularRequest.url, popularRequest.headers).asJsoup().parseNovelCards(), false)
    }

    protected open fun buildLatestUpdatesRequest(page: Int): Request = GET(baseUrl, headers)

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val latestRequest = buildLatestUpdatesRequest(page)
        val doc = client.get(latestRequest.url, latestRequest.headers).asJsoup()
        val section = doc.select("section")
            .firstOrNull { it.selectFirst("h2")?.ownText() == "Newly Added" }
            ?: doc
        return MangasPage(section.parseNovelCards(), false)
    }

    // The site has no server-side text search; ?genre= is the only server filter.
    // The query is carried in the URL fragment (never sent to the server) and
    // applied client-side in getSearchMangaList.
    protected open fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
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

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val searchRequest = buildSearchMangaRequest(page, query, filters)
        val response = client.get(searchRequest.url, searchRequest.headers)
        val cards = response.asJsoup().parseNovelCards()
            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
        return MangasPage(cards, false)
    }

    private fun Element.parseNovelCards(): List<SManga> = select("a[href^=/novels/]:has(h3)")
        .distinctBy { it.attr("href") }
        .map { card ->
            SManga.create().apply {
                url = mangaPathTemplate.slug(card.attr("href"))
                title = card.selectFirst("h3")!!.text()
                thumbnail_url = card.selectFirst("img")?.absUrl("src")
            }
        }

    // ---- Details ----

    protected open fun buildMangaDetailsRequest(manga: SManga): Request = GET(mangaPathTemplate.absolute(baseUrl, manga.url), headers)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Details and the chapter list both live on the same novel page - fetch it once.
        val mangaDetailsRequest = buildMangaDetailsRequest(manga)
        val response = client.get(mangaDetailsRequest.url, mangaDetailsRequest.headers)
        val doc = response.asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc, response) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(doc) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(doc: org.jsoup.nodes.Document, response: Response): SManga = SManga.create().apply {
        url = mangaPathTemplate.slug("/novels/${response.request.url.pathSegments.last()}")
        title = doc.selectFirst("h1")?.text().orEmpty()
        thumbnail_url = doc.selectFirst("img[src*=/covers/]")?.absUrl("src")
        description = doc.selectFirst("p[class*=line-clamp-3]")?.text()
        genre = doc.select("span[data-slot=badge][data-variant=outline]")
            .eachText()
            .distinct()
            .joinToString()
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

    override fun getMangaUrl(manga: SManga): String = mangaPathTemplate.absolute(baseUrl, manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val manga = SManga.create().apply { this.url = mangaPathTemplate.slug(url.encodedPath) }
        val mangaDetailsRequest = buildMangaDetailsRequest(manga)
        val response = client.get(mangaDetailsRequest.url, mangaDetailsRequest.headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        return parseMangaDetails(response.asJsoup(), response)
    }

    // ---- Chapters ----

    @Serializable
    private class ChapterListDto(
        val novelSlug: String,
        val chapters: List<ChapterDto>,
    )

    @Serializable
    private class ChapterDto(
        val title: String,
        val slug: String,
        @SerialName("chapter_number") val chapterNumber: Float,
        @SerialName("published_at") val publishedAt: String? = null,
        val premium: Boolean = false,
    )

    private fun parseChapterList(doc: org.jsoup.nodes.Document): List<SChapter> {
        // The full chapter list (free + premium) is embedded in the page's
        // RSC flight data as {"novelSlug": ..., "chapters": [...]}.
        val dto = doc.extractNextJs<ChapterListDto>()
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

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    // published_at looks like "2024-10-06T00:00:00+00:00", sometimes with millis.
    // Offsets are always +00:00, so parse the date-time part as UTC.
    private fun parseDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val normalized = date.substringBefore('+').substringBefore('.')
        return runCatching {
            LocalDateTime.parse(normalized, dateFormatter).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    // ---- Chapter content ----

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val response = client.get(baseUrl + page.url, headers)
        val content = response.asJsoup().selectFirst("div.reader-content")
            ?: throw Exception("Chapter content not found – this may be a premium chapter")
        return content.html()
    }

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

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
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

package eu.kanade.tachiyomi.novelextension.en.helioscans

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.formattedText
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.setAltTitles
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale

// Keyoapp CMS, novels only. The whole catalogue is rendered on /series/, filtering happens client-side.
@Source
abstract class HelioScans :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val supportsLatest = false

    override val supportsFilterFetching = true

    private val preferences by getPreferencesLazy()

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(fetchNovelCards().map { it.toSManga() }, false)

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genres = filters.firstInstanceOrNull<GenreFilter>()?.state.orEmpty().filter { it.state }.map { it.name }
        val status = filters.firstInstanceOrNull<StatusFilter>()?.toValue()

        val mangas = fetchNovelCards()
            .filter { card ->
                card.attr("title").contains(query.trim(), ignoreCase = true) &&
                    (status == null || card.attr("data-status").equals(status, ignoreCase = true)) &&
                    card.tags().let { tags -> genres.all { genre -> tags.any { it.equals(genre, ignoreCase = true) } } }
            }
            .map { it.toSManga() }
        return MangasPage(mangas, false)
    }

    private suspend fun fetchNovelCards(): List<Element> = client.get("$baseUrl/series/", headers).asJsoup()
        .select("#searched_series_page > button")
        .filter { it.attr("data-type").equals("novel", ignoreCase = true) }

    private fun Element.tags(): List<String> = runCatching { attr("tags").replace("___", "'").parseAs<List<String>>() }.getOrDefault(emptyList())

    private fun Element.toSManga(): SManga = SManga.create().apply {
        val link = selectFirst("a[href*=\"/series/\"]")!!
        url = link.attr("href").substringAfter("/series/").trimEnd('/')
        title = attr("title").trim().ifEmpty { link.attr("title") }
        thumbnail_url = selectFirst("[style*=background-image]")?.backgroundImage()
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url}/"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != "series") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        val document = client.get("$baseUrl/series/$slug/", headers).asJsoup()
        return parseMangaDetails(document).apply { this.url = slug }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), headers).asJsoup()
        return SMangaUpdate(parseMangaDetails(document), parseChapterList(document))
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("div.grid > h1")!!.text()
        thumbnail_url = document.selectFirst("div[style*=photoURL]")?.backgroundImage()
        author = document.infoField("Author")
        artist = document.infoField("Artist")

        val type = document.infoField("Type")?.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
        val statusText = document.infoField("Status")
        status = when (statusText?.lowercase()) {
            "ongoing", "upcoming" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "dropped" -> SManga.CANCELLED
            "paused", "hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        genre = (listOfNotNull(type) + document.select("div:has(> h1) a[href*=\"genre=\"]").map { it.text().trim(',', ' ') })
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString()

        val altTitles = document.select("div.font-medium:containsOwn(Alternative titles) + div span")
            .map { it.text() }
            .filter { it.isNotEmpty() && it != "No alternative titles." }
        setAltTitles(altTitles)

        val synopsis = (
            document.selectFirst("div.font-bold:containsOwn(Synopsis) + div p")
                ?: document.selectFirst("#expand_content p")
            )?.formattedText()

        description = buildString {
            synopsis?.takeIf { it.isNotEmpty() }?.let { append(it).append("\n\n") }
            type?.let { append("Type: ").append(it).append("\n") }
            statusText?.let { append("Status: ").append(it.replaceFirstChar { c -> c.titlecase(Locale.ENGLISH) }).append("\n") }
            document.infoField("Updated")?.let { append("Updated: ").append(it).append("\n") }
            document.infoField("Created")?.let { append("Created: ").append(it).append("\n") }
            if (altTitles.isNotEmpty()) {
                append("\nAlternative Titles:\n")
                altTitles.joinTo(this, "\n") { "• $it" }
            }
        }.trim().ifEmpty { null }
    }

    private fun Document.infoField(label: String): String? = selectFirst("div.font-medium:has(> span:containsOwn($label)) + div")
        ?.text()
        ?.takeIf { it.isNotEmpty() && it != "N/A" }

    private fun parseChapterList(document: Document): List<SChapter> {
        val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)
        val elements = document.select("#chapters > :is(a, div):not(:has(.text-sm span:matches(Upcoming)))")
        return elements
            .mapIndexedNotNull { index, element ->
                val locked = element.selectFirst(PAID_SELECTOR) != null
                if (locked && !showLocked) return@mapIndexedNotNull null
                val link = element.selectFirst("a[href*=\"/chapter/\"]") ?: return@mapIndexedNotNull null
                SChapter.create().apply {
                    url = link.attr("href").substringAfter("/chapter/").trimEnd('/')
                    val title = element.selectFirst(".text-sm")?.text() ?: link.attr("title")
                    name = if (locked) "🔒 $title" else title
                    // The site's chapter anchors have an unclosed class attribute, so the "c" attribute is unreliable.
                    chapter_number = CHAPTER_NUMBER_REGEX.find(title)?.groupValues?.get(1)?.toFloatOrNull()
                        ?: link.attr("c").toFloatOrNull()
                        ?: (elements.size - index).toFloat()
                    date_upload = dateFormat.tryParseDate(link.attr("d").ifEmpty { element.selectFirst(".text-xs")?.text() })
                }
            }
            .sortedByDescending { it.chapter_number }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/chapter/${chapter.url}/"

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, getChapterUrl(chapter)))

    override suspend fun fetchPageText(page: Page): String {
        val document = client.get(page.url, headers).asJsoup()
        val content = document.selectFirst("#pages div[novel-reader-option]")
            ?: throw Exception("Chapter is locked or unavailable on Helio Scans")
        content.select("script, style, ins").remove()
        return content.html()
    }

    override suspend fun fetchFilterData(): JsonElement {
        val cards = fetchNovelCards()
        return FilterDataDto(
            genres = cards.flatMap { it.tags() }.map { it.trim() }.filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }.sortedBy { it.lowercase() },
            statuses = cards.map { it.attr("data-status").lowercase() }.filter { it.isNotEmpty() }.distinct().sorted(),
        ).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterDataDto>() ?: return FilterList(Filter.Header("Press 'Reset' to load filters"))
        return FilterList(
            StatusFilter(filterData.statuses),
            GenreFilter(filterData.genres),
        )
    }

    private class StatusFilter(private val options: List<String>) : Filter.Select<String>("Status", arrayOf("All") + options.map { it.replaceFirstChar { c -> c.titlecase(Locale.ENGLISH) } }) {
        fun toValue(): String? = options.getOrNull(state - 1)
    }

    private class GenreCheckBox(name: String) : Filter.CheckBox(name)

    private class GenreFilter(genres: List<String>) : Filter.Group<GenreCheckBox>("Genres", genres.map(::GenreCheckBox))

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Include coin-locked chapters in the chapter list (their content can't be read without unlocking them on the site)."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    private fun Element.backgroundImage(): String? = IMG_REGEX.find(attr("style"))?.groupValues?.get(1)
        ?.toHttpUrlOrNull()
        // wsrv.nl resizes on the fly, the listing default of 300px is too blurry for covers
        ?.newBuilder()?.setQueryParameter("w", "480")?.build()?.toString()

    companion object {
        private val CHAPTER_NUMBER_REGEX = Regex("""(?i)\bch(?:apter)?\.?\s*(\d+(?:\.\d+)?)""")
        private const val PREF_SHOW_LOCKED = "show_locked_chapters"
        private const val PAID_SELECTOR = "img[alt~=Coin]"
        private val IMG_REGEX = Regex("""url\(['"]?([^'")]+)""")
        private val dateFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
    }
}

@Serializable
private class FilterDataDto(
    val genres: List<String>,
    val statuses: List<String>,
)

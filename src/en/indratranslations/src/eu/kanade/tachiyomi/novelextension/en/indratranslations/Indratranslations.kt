package eu.kanade.tachiyomi.novelextension.en.indratranslations

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
import keiyoushi.utils.SlugPath
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.setAltTitles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class Indratranslations :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val mangaPath = SlugPath("/", "/")

    override suspend fun getPopularManga(page: Int): MangasPage = parseSeriesListing(client.get("$baseUrl/series/?orderby=views", headers).asJsoup())

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseSeriesListing(client.get("$baseUrl/series/?orderby=update", headers).asJsoup())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/series/".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)

        var orderBy = "new"
        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> filter.toUriPart().takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("genre", it) }
                is TypeFilter -> filter.toUriPart().takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("type", it) }
                is StatusFilter -> filter.toUriPart().takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("status", it) }
                is TagFilter -> filter.toUriPart().takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("tag", it) }
                is AuthorFilter -> filter.toUriPart().takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("author", it) }
                is OrderByFilter -> orderBy = filter.toUriPart()
                else -> {}
            }
        }
        url.addQueryParameter("orderby", orderBy)

        return parseSeriesListing(client.get(url.build(), headers).asJsoup())
    }

    private fun parseSeriesListing(doc: Document): MangasPage {
        val mangas = doc.select("div.series-card").mapNotNull { card ->
            val href = onclickUrlRegex.find(card.attr("onclick"))?.groupValues?.get(1) ?: return@mapNotNull null
            val title = card.selectFirst(".series-card-title")?.text() ?: return@mapNotNull null
            SManga.create().apply {
                this.title = title
                url = mangaPath.slug(href.toHttpUrl().encodedPath)
                thumbnail_url = card.selectFirst(".series-card-cover img")?.attr("abs:src")
                genre = card.selectFirst(".series-card-genres")?.text()
            }
        }
        return MangasPage(mangas, false)
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPath.resolve(manga.url)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = client.get(getMangaUrl(manga), headers).asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(doc) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val response = client.get(url, headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        return parseMangaDetails(response.asJsoup()).apply { this.url = mangaPath.slug(url.encodedPath) }
    }

    private fun parseMangaDetails(doc: Document): SManga = SManga.create().apply {
        title = doc.selectFirst("h1.story-main-title")!!.text()
        thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
        author = doc.selectFirst("div.story-meta-list a[href*=/tac-gia/]")?.text()

        val genres = doc.select("div.story-meta-list a[href*=/series-genre/]").eachText()
        val tags = doc.select("#td-story-tags-scroll a.td-tag-item").eachText().map { it.removePrefix("#") }
        genre = (genres + tags).distinct().filter { it.isNotEmpty() }.joinToString()

        status = when (doc.selectFirst("div.story-meta-list a[href*=/trang-thai/]")?.text()?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "dropped" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        description = doc.select("#story-synopsis p").joinToString("\n\n") { it.text() }

        val altTitle = doc.selectFirst("#td-alt-title-seo")?.text()
        if (!altTitle.isNullOrBlank()) {
            setAltTitles(listOf(altTitle))
            description = buildString {
                append(description.orEmpty())
                append("\n\nAlternative Titles:\n")
                append("• $altTitle")
            }.trim()
        }
    }

    private fun parseChapterList(doc: Document): List<SChapter> {
        val scriptData = doc.select("script").map { it.data() }
            .firstOrNull { CHAPTERS_VAR in it } ?: return emptyList()
        val chapterArray = chapterArrayRegex.find(scriptData)?.groupValues?.get(1) ?: return emptyList()

        val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)
        return chapterArray.parseAs<List<ChapterDto>>()
            .mapNotNull { it.toSChapter(showLocked) }
            .reversed()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.get(baseUrl + page.url, headers).asJsoup()
        val content = doc.selectFirst("#chapter-content-text")
            ?: throw Exception("Chapter content unavailable (locked or removed)")
        content.select("span.td-s-noise").remove()
        return zeroWidthRegex.replace(content.html(), "")
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        OrderByFilter(),
        GenreFilter(),
        TypeFilter(),
        StatusFilter(),
        TagFilter(),
        AuthorFilter(),
    )

    private open class UriPartFilter(name: String, private val vals: Array<Pair<String, String>>) : Filter.Select<String>(name, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class OrderByFilter :
        UriPartFilter(
            "Order By",
            arrayOf(
                "Newest" to "new",
                "Recently Updated" to "update",
                "Most Viewed" to "views",
                "Highest Rated" to "rating",
                "Most Chapters" to "chapters",
                "Most Nominated" to "nominate",
            ),
        )

    private class TypeFilter :
        UriPartFilter(
            "Type",
            arrayOf(
                "All Types" to "",
                "Short Story" to "short",
                "Long Story" to "long",
            ),
        )

    private class StatusFilter :
        UriPartFilter(
            "Status",
            arrayOf(
                "All Status" to "",
                "Completed" to "completed",
                "Dropped" to "dropped",
                "Ongoing" to "ongoing",
            ),
        )

    private class AuthorFilter :
        UriPartFilter(
            "Author",
            arrayOf(
                "All Authors" to "",
                "Indra Team" to "15",
            ),
        )

    private class GenreFilter :
        UriPartFilter(
            "Genre",
            arrayOf(
                "All Genres" to "",
                "Action" to "51",
                "Adventure" to "52",
                "Fantasy" to "10",
                "Harem" to "53",
                "Horror" to "16",
                "Mature" to "33",
                "Martial Arts" to "63",
                "Mystery" to "35",
                "Psychological" to "54",
                "School Life" to "29",
                "Sci-fi" to "36",
                "Slice of Life" to "30",
                "Supernatural" to "55",
                "Uncategorized" to "1",
            ),
        )

    private class TagFilter :
        UriPartFilter(
            "Tag",
            arrayOf(
                "All Tags" to "",
                "Academy" to "21",
                "Dragons" to "64",
                "Survival" to "61",
                "Past Plays a Big Role" to "59",
                "Fantasy Creatures" to "69",
                "Beast Companions" to "68",
                "Chat Rooms" to "67",
                "Possessive Characters" to "66",
                "Dungeons" to "65",
                "Transported to Another World" to "62",
                "Past Trauma" to "60",
                "Apocalypse" to "58",
                "Calm Protagonist" to "57",
                "Aliens" to "56",
                "Mercenaries" to "45",
                "Misunderstandings" to "44",
                "Monsters" to "43",
                "Clever Protagonist" to "42",
                "Corruption" to "41",
                "Gore" to "40",
            ),
        )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Include paid/locked chapters in the chapter list."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    @Serializable
    private class ChapterDto(
        private val num: Int,
        private val price: Int,
        private val title: String,
        private val date: String,
        private val link: String,
    ) {
        fun toSChapter(showLocked: Boolean): SChapter? {
            val locked = price > 0
            if (locked && !showLocked) return null

            return SChapter.create().apply {
                url = link.toHttpUrl().encodedPath
                name = if (locked) "🔒 $title" else title
                chapter_number = num.toFloat()
                date_upload = runCatching {
                    LocalDateTime.parse(date, dateFormat).toInstant(ZoneOffset.UTC).toEpochMilli()
                }.getOrDefault(0L)
            }
        }
    }

    companion object {
        private const val PREF_SHOW_LOCKED = "pref_show_locked_chapters"
        private const val CHAPTERS_VAR = "TD_Story_Chapters"

        private val onclickUrlRegex = Regex("""location\.href='([^']+)'""")
        private val chapterArrayRegex = Regex("""TD_Story_Chapters\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)

        private val zeroWidthRegex = Regex("[\u200B\u200C\u200D\u2060\uFEFF]")

        private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
    }
}

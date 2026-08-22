package eu.kanade.tachiyomi.novelextension.all.quotev

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
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Quotev serves an empty 1-byte body to requests missing standard browser fetch metadata
 * (confirmed live: identical request without Sec-Fetch and Accept-Language headers returns " ");
 * [configureHeaders] adds them.
 *
 * Stories are split into numbered "pages" (`/story/<id>/<slug>/<n>`), a finer unit than the
 * author-named "chapters" shown in the page's own chapter picker - one chapter can span several
 * pages. [SChapter] is modelled on the named chapters, with the start/end page range encoded as a
 * URL fragment, and [fetchPageText] concatenates every page in that range.
 */
@Source
abstract class Quotev :
    KeiSource(),
    NovelSource {

    override val supportsLatest = false

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .add("Accept-Language", "en-US,en;q=0.9")
        .add("Sec-Fetch-Dest", "document")
        .add("Sec-Fetch-Mode", "navigate")
        .add("Sec-Fetch-Site", "none")
        .add("Upgrade-Insecure-Requests", "1")

    // ======================== Popular / Search ========================

    override suspend fun getPopularManga(page: Int): MangasPage = browse(page, section = "", genre = "", media = "", sort = "users", longOnly = false, lid = "")

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val lid = filters.filterIsInstance<LanguageFilter>().firstOrNull()?.toUriPart().orEmpty()

        if (query.isNotBlank()) {
            val url = "$baseUrl/search/$query".toHttpUrl().newBuilder()
                .apply { if (lid.isNotBlank()) addQueryParameter("lid", lid) }
                .apply { if (page > 1) addQueryParameter("page", page.toString()) }
                .build()
            return parseStoryList(client.get(url, headers).asJsoup())
        }

        val section = filters.filterIsInstance<SectionFilter>().firstOrNull()?.toUriPart().orEmpty()
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.toUriPart().orEmpty()
        val media = filters.filterIsInstance<MediaFilter>().firstOrNull()?.toUriPart().orEmpty()
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart().orEmpty()
        val longOnly = filters.filterIsInstance<LengthFilter>().firstOrNull()?.state == true
        return browse(page, section, genre, media, sort, longOnly, lid)
    }

    /**
     * Genre (`/c/<genre>`) only makes sense nested under a section, and media (`/m/<media>`) is a
     * Fanfiction-only facet - both are silently dropped otherwise instead of producing a malformed
     * path (this is what caused `/stories/c/Fiction/c/Harem`, a section hardcoded onto a category
     * filter value that wasn't actually a subcategory of it, to 404 into an empty result set).
     *
     * The site also has a richer bitmask-category filter dialog (`/search?cat=<n>&...`) that can
     * combine multiple categories and exposes a couple genres (Vampires, Wolves) not in the plain
     * nav; single-category selections there just 301-redirect back to these same pretty paths, and
     * combining categories wasn't confirmed to work, so it isn't exposed here. `lid` (language) is
     * pulled from that same dialog though - confirmed live to work appended onto any of these URLs.
     */
    private suspend fun browse(page: Int, section: String, genre: String, media: String, sort: String, longOnly: Boolean, lid: String): MangasPage {
        var path = if (section.isBlank()) "/stories" else "/stories/c/$section"
        if (section.isNotBlank() && genre.isNotBlank()) path += "/c/$genre"
        if (section == "Fanfiction" && media.isNotBlank()) path += "/m/$media"

        val url = (baseUrl + path).toHttpUrl().newBuilder()
            .apply { if (sort.isNotBlank()) addQueryParameter("v", sort) }
            .apply { if (longOnly) addQueryParameter("minLen", "50") }
            .apply { if (lid.isNotBlank()) addQueryParameter("lid", lid) }
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()
        return parseStoryList(client.get(url, headers).asJsoup())
    }

    private fun parseStoryList(doc: Document): MangasPage {
        val cards = doc.select("div.quiz[data-quizid]")
        val mangas = cards.mapNotNull { card ->
            val link = card.selectFirst("h2 a[href*=/story/]") ?: return@mapNotNull null
            SManga.create().apply {
                title = link.text()
                url = link.attr("abs:href").toHttpUrl().encodedPath.removePrefix("/story/")
                thumbnail_url = card.selectFirst("img.logo")?.attr("abs:src")
                author = card.selectFirst("span.author a")?.text()
                description = card.selectFirst("div.descr")?.text()
            }
        }
        val hasNextPage = doc.select("a").any { it.text().contains("Next page", ignoreCase = true) }
        return MangasPage(mangas, hasNextPage)
    }

    // ======================== Details + Chapters ========================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/story/${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = client.get(getMangaUrl(manga), headers).asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(doc, manga.url) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val response = client.get(url, headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        val doc = response.asJsoup()
        return parseMangaDetails(doc).apply {
            this.url = url.encodedPath.removePrefix("/story/").split("/").take(2).joinToString("/")
        }
    }

    private fun parseMangaDetails(doc: Document): SManga = SManga.create().apply {
        title = doc.selectFirst("#quizHeaderTitle h1")?.text() ?: doc.title()
        thumbnail_url = doc.selectFirst("img.logo")?.attr("abs:src")
        author = doc.selectFirst(".quizAuthorList a")?.text()
        description = doc.selectFirst("#qdesct")?.let { formatDescription(it.html()) }
        genre = doc.select("div.quizBoxTags a").eachText().distinct().joinToString()
    }

    /** Description paragraphs are separated by `<br>` rather than `<p>`; mark them before
     * Jsoup.text() collapses whitespace, then restore as real newlines. */
    private fun formatDescription(html: String): String {
        val marked = html.replace(Regex("""<br\s*/?>"""), LINE_BREAK_MARKER)
        return Jsoup.parseBodyFragment(marked).text().replace(LINE_BREAK_MARKER, "\n").trim()
    }

    /** The chapter picker is a `<select name=rid>` with one `<option value=page>` per named
     * chapter; the last chapter's end page isn't known up front, so it's left open-ended and
     * [fetchPageText] instead walks pages until the site stops offering a "next page" link. */
    private fun parseChapterList(doc: Document, storyPath: String): List<SChapter> {
        val entries = doc.select("select[name=rid] option[value]").mapNotNull { option ->
            val startPage = option.attr("value").toIntOrNull() ?: return@mapNotNull null
            startPage to option.text()
        }.ifEmpty { listOf(1 to (doc.selectFirst("#quizSubtitle")?.text() ?: "Chapter 1")) }

        val lastUpdated = doc.selectFirst("time[ts]")?.attr("ts")?.toLongOrNull()?.times(1000L) ?: 0L

        return entries.mapIndexed { index, (startPage, name) ->
            val endPage = entries.getOrNull(index + 1)?.first?.minus(1) ?: OPEN_ENDED
            SChapter.create().apply {
                this.name = name
                url = "/story/$storyPath/$startPage#$endPage"
                chapter_number = (index + 1).toFloat()
                if (index == entries.lastIndex) date_upload = lastUpdated
            }
        }.reversed()
    }

    // ======================== Pages ========================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url.substringBefore("#")

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val storyPath = page.url.substringBefore("#").substringBeforeLast("/")
        val startPage = page.url.substringBefore("#").substringAfterLast("/").toInt()
        val endPage = page.url.substringAfter("#").toInt()

        return buildString {
            var pageNum = startPage
            while (endPage == OPEN_ENDED || pageNum <= endPage) {
                val doc = client.get("$baseUrl$storyPath/$pageNum", headers).asJsoup()
                append(doc.selectFirst("#rescontent")?.html().orEmpty())
                if (doc.selectFirst("#quizPageNext") == null) break
                pageNum++
            }
        }
    }

    // ======================== Filters ========================

    override fun getFilterList(data: JsonElement?) = FilterList(
        SectionFilter(),
        GenreFilter(),
        Filter.Header("Media only applies to the Fanfiction section"),
        MediaFilter(),
        SortFilter(),
        LengthFilter(),
        LanguageFilter(),
    )

    private open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>, state: Int = 0) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), state) {
        fun toUriPart() = vals[state].second
    }

    private class SectionFilter :
        UriPartFilter(
            "Section",
            arrayOf(
                "All" to "",
                "Fiction" to "Fiction",
                "Fanfiction" to "Fanfiction",
                "Nonfiction" to "Nonfiction",
            ),
        )

    private class GenreFilter :
        UriPartFilter(
            "Genre",
            arrayOf(
                "All" to "",
                "Action" to "Action",
                "Adventure" to "Adventure",
                "Anime/Manga" to "Anime--Manga",
                "Biography" to "Biography",
                "Fantasy" to "Fantasy",
                "Historical" to "Historical",
                "Horror" to "Horror",
                "Humor" to "Humor",
                "Mystery" to "Mystery",
                "Poetry" to "Poetry",
                "Realistic" to "Realistic",
                "Romance" to "Romance",
                "Science Fiction" to "Science-Fiction",
                "Short Stories" to "Short-Stories",
                "Supernatural" to "Supernatural",
                "Thriller" to "Thriller",
                "Vampires" to "Vampires",
                "Wolves" to "Wolves",
                "Other" to "Other",
            ),
        )

    private class MediaFilter :
        UriPartFilter(
            "Media (Fanfiction)",
            arrayOf(
                "All" to "",
                "Anime" to "Anime",
                "Manga" to "Manga",
                "TV Shows" to "TV",
                "Cartoons" to "Cartoon",
                "Comics" to "Comic",
                "Books" to "Book",
                "Movies" to "Movie",
                "Music" to "Music",
                "Theater" to "Theater",
                "Real People" to "People",
                "Games" to "Game",
                "Web" to "Web",
                "Other" to "Other",
                "Author/Creator" to "Creator",
                "Fandoms" to "Fandoms",
            ),
        )

    private class SortFilter :
        UriPartFilter(
            "Sort",
            arrayOf(
                "Default" to "",
                "New" to "created",
                "Newly published" to "new",
                "Popular" to "users",
                "All time" to "top",
            ),
            3,
        )

    private class LengthFilter : Filter.CheckBox("Long stories only (50+ pages)")

    /** `lid` values pulled from the site's own filter dialog config (`BrowseFilter({"lang":[...]})`). */
    private class LanguageFilter :
        UriPartFilter(
            "Language",
            arrayOf(
                "Any" to "",
                "English" to "0",
                "Español" to "61",
                "Português" to "21",
                "Deutsch" to "9",
                "Français" to "11",
                "Italiano" to "14",
                "Русский" to "23",
                "中文" to "31",
                "日本語" to "18",
                "한국어" to "12",
                "العربية" to "36",
                "فارسی" to "55",
                "Català" to "5",
                "Čeština" to "6",
                "Dansk" to "8",
                "Eesti" to "44",
                "Hrvatski" to "13",
                "Indonesia" to "3",
                "Latviešu" to "51",
                "Lietuvių" to "15",
                "Magyar" to "16",
                "Malagasy" to "67",
                "Melayu" to "4",
                "Nederlands" to "17",
                "Norsk" to "19",
                "Polski" to "20",
                "Română" to "22",
                "Shqip" to "52",
                "Slovenčina" to "24",
                "Slovenščina" to "25",
                "Srpski" to "34",
                "Suomi" to "26",
                "Svenska" to "27",
                "Tagalog" to "10",
                "Türkçe" to "30",
                "Tiếng Việt" to "29",
                "繁體中文" to "74",
                "עברית" to "35",
                "ภาษาไทย" to "28",
                "हिन्दी" to "37",
                "বাংলা" to "38",
                "Български" to "33",
                "ქართული" to "59",
                "Other" to "1",
            ),
        )

    companion object {
        private const val LINE_BREAK_MARKER = "␈"
        private const val OPEN_ENDED = -1
    }
}

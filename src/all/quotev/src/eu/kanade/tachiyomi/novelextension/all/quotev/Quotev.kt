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

    override suspend fun getPopularManga(page: Int): MangasPage = browse(page, BrowseOptions(sort = "users"))

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val opts = BrowseOptions(
            section = filters.filterIsInstance<SectionFilter>().firstOrNull()?.toUriPart().orEmpty(),
            genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.toUriPart().orEmpty(),
            media = filters.filterIsInstance<MediaFilter>().firstOrNull()?.toUriPart().orEmpty(),
            sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart().orEmpty(),
            minLen = filters.filterIsInstance<MinLengthFilter>().firstOrNull()?.toUriPart().orEmpty(),
            lid = filters.filterIsInstance<LanguageFilter>().firstOrNull()?.toUriPart().orEmpty(),
            excludeGenreId = filters.filterIsInstance<ExcludeGenreFilter>().firstOrNull()?.toUriPart().orEmpty(),
            crossover = filters.filterIsInstance<CrossoverFilter>().firstOrNull()?.toUriPart().orEmpty(),
            realPeople = filters.filterIsInstance<RealPeopleFilter>().firstOrNull()?.toUriPart().orEmpty(),
            completeOnly = filters.filterIsInstance<CompleteFilter>().firstOrNull()?.state == true,
            featured = filters.filterIsInstance<FeaturedFilter>().firstOrNull()?.state == true,
            filterProfanity = filters.filterIsInstance<ProfanityFilter>().firstOrNull()?.state != false,
            filterViolence = filters.filterIsInstance<ViolenceFilter>().firstOrNull()?.state != false,
            mature = filters.filterIsInstance<MatureFilter>().firstOrNull()?.state == true,
            excludeText = filters.filterIsInstance<ExcludeTextFilter>().firstOrNull()?.state.orEmpty(),
        )

        if (query.isNotBlank()) {
            val url = "$baseUrl/search/$query".toHttpUrl().newBuilder()
                .applyBrowseOptions(opts)
                .apply { if (page > 1) addQueryParameter("page", page.toString()) }
                .build()
            return parseStoryList(client.get(url, headers).asJsoup())
        }
        return browse(page, opts)
    }

    private suspend fun browse(page: Int, opts: BrowseOptions): MangasPage {
        var path = if (opts.section.isBlank()) "/stories" else "/stories/c/${opts.section}"
        if (opts.section.isNotBlank() && opts.genre.isNotBlank()) path += "/c/${opts.genre}"
        if (opts.section == "Fanfiction" && opts.media.isNotBlank()) path += "/m/${opts.media}"

        val url = (baseUrl + path).toHttpUrl().newBuilder()
            .applyBrowseOptions(opts)
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()
        return parseStoryList(client.get(url, headers).asJsoup())
    }

    private fun HttpUrl.Builder.applyBrowseOptions(opts: BrowseOptions): HttpUrl.Builder = apply {
        if (opts.sort.isNotBlank()) addQueryParameter("v", opts.sort)
        if (opts.minLen.isNotBlank()) addQueryParameter("minLen", opts.minLen)
        if (opts.lid.isNotBlank()) addQueryParameter("lid", opts.lid)
        if (opts.excludeGenreId.isNotBlank()) addQueryParameter("xcat", opts.excludeGenreId)
        if (opts.section == "Fanfiction" && opts.crossover.isNotBlank()) addQueryParameter("crossover", opts.crossover)
        if (opts.section == "Fanfiction" && opts.realPeople.isNotBlank()) addQueryParameter("rp", opts.realPeople)
        if (opts.completeOnly) addQueryParameter("complete", "1")
        if (opts.featured) addQueryParameter("featured", "1")
        if (!opts.filterProfanity) addQueryParameter("pf", "0")
        if (!opts.filterViolence) addQueryParameter("vf", "0")
        if (opts.mature) addQueryParameter("mf", "1")
        if (opts.excludeText.isNotBlank()) addQueryParameter("xs", opts.excludeText)
    }

    private data class BrowseOptions(
        val section: String = "",
        val genre: String = "",
        val media: String = "",
        val sort: String = "",
        val minLen: String = "",
        val lid: String = "",
        val excludeGenreId: String = "",
        val crossover: String = "",
        val realPeople: String = "",
        val completeOnly: Boolean = false,
        val featured: Boolean = false,
        val filterProfanity: Boolean = true,
        val filterViolence: Boolean = true,
        val mature: Boolean = false,
        val excludeText: String = "",
    )

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

    private fun formatDescription(html: String): String {
        val marked = html.replace(lineBreakRegex, LINE_BREAK_MARKER)
        return Jsoup.parseBodyFragment(marked, baseUrl).text().replace(LINE_BREAK_MARKER, "\n").trim()
    }

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

    override fun getFilterList(data: JsonElement?) = FilterList(
        SectionFilter(),
        GenreFilter(),
        ExcludeGenreFilter(),
        Filter.Header("Media, Crossover and Real People only apply to the Fanfiction section"),
        MediaFilter(),
        CrossoverFilter(),
        RealPeopleFilter(),
        SortFilter(),
        MinLengthFilter(),
        LanguageFilter(),
        CompleteFilter(),
        FeaturedFilter(),
        Filter.Header("Mature requires being logged in on the site; may have no effect here"),
        ProfanityFilter(),
        ViolenceFilter(),
        MatureFilter(),
        ExcludeTextFilter(),
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

    private class MinLengthFilter :
        UriPartFilter(
            "Min length",
            arrayOf(
                "Any" to "",
                "10+ pages" to "10",
                "25+ pages" to "25",
                "50+ pages" to "50",
                "75+ pages" to "75",
                "100+ pages" to "100",
                "200+ pages" to "200",
            ),
        )

    private class ExcludeGenreFilter :
        UriPartFilter(
            "Exclude genre",
            arrayOf(
                "None" to "",
                "Action" to "2",
                "Adventure" to "4",
                "Anime/Manga" to "67108864",
                "Biography" to "2147483648",
                "Fantasy" to "16",
                "Historical" to "2097152",
                "Horror" to "32",
                "Humor" to "1048576",
                "Mystery" to "128",
                "Poetry" to "524288",
                "Realistic" to "256",
                "Romance" to "64",
                "Science Fiction" to "4194304",
                "Short Stories" to "33554432",
                "Supernatural" to "4096",
                "Thriller" to "512",
                "Vampires" to "8388608",
                "Wolves" to "16777216",
            ),
        )

    private class CrossoverFilter :
        UriPartFilter(
            "Crossover (Fanfiction)",
            arrayOf("Any" to "", "Yes" to "1", "No" to "2"),
        )

    private class RealPeopleFilter :
        UriPartFilter(
            "Real people (Fanfiction)",
            arrayOf("Any" to "", "Yes" to "1", "No" to "2"),
        )

    private class CompleteFilter : Filter.CheckBox("Completed only")

    private class FeaturedFilter : Filter.CheckBox("Featured first")

    private class ProfanityFilter : Filter.CheckBox("Filter profanity", true)

    private class ViolenceFilter : Filter.CheckBox("Filter violence", true)

    private class MatureFilter : Filter.CheckBox("Show mature content")

    private class ExcludeTextFilter : Filter.Text("Exclude (words to avoid)")

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
        private val lineBreakRegex = Regex("""<br\s*/?>""")
    }
}

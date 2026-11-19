package eu.kanade.tachiyomi.novelextension.en.readingpia

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
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@Source
abstract class ReadingPia :
    KeiSource(),
    NovelSource {

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList())

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter(1)))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = coroutineScope {
        val url = "$baseUrl/series".toHttpUrl().newBuilder().apply {
            addQueryParameter("search", query.trim())
            addQueryParameter("sort", (filters.firstInstanceOrNull<SortFilter>() ?: SortFilter()).value)
            addQueryParameter("order_by", (filters.firstInstanceOrNull<OrderFilter>() ?: OrderFilter()).value)
            addQueryParameter("status", (filters.firstInstanceOrNull<StatusFilter>() ?: StatusFilter()).value)
            filters.firstInstanceOrNull<GenreFilter>()?.state?.filter { it.state }?.forEach { addQueryParameter("genres[]", it.id) }
            addQueryParameter("page", page.toString())
        }.build()
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        val mangas = document.select(".chapter-collection a[href]").mapNotNull { link ->
            val slug = link.absUrl("href").toHttpUrlOrNull()?.let(::seriesSlug) ?: return@mapNotNull null
            val title = link.selectFirst(".chapter-novel-title")?.text()?.ifEmpty { null } ?: return@mapNotNull null
            async {
                SManga.create().apply {
                    this.url = slug
                    // The listing cuts long titles short with "...".
                    this.title = if (title.endsWith("...")) fetchTitle(slug) else title
                    thumbnail_url = link.selectFirst("img")?.absUrl("src")
                }
            }
        }.awaitAll().distinctBy { it.url }
        MangasPage(mangas, document.selectFirst("a[href*=page=${page + 1}]") != null)
    }

    private suspend fun fetchTitle(slug: String): String = client.get(seriesUrl(slug), Fetch.DOCUMENT).asJsoup().selectFirst(".novel-title")!!.text()

    private fun seriesSlug(url: HttpUrl): String? {
        val segments = url.pathSegments.filter(String::isNotEmpty)
        return segments.getOrNull(1)?.takeIf { segments.first() == "series" }
    }

    private fun seriesUrl(slug: String) = "$baseUrl/series/$slug"

    override fun getMangaUrl(manga: SManga): String = seriesUrl(manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = seriesSlug(url) ?: return null
        return parseDetails(client.get(seriesUrl(slug), Fetch.DOCUMENT).asJsoup(), slug)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(seriesUrl(manga.url), Fetch.DOCUMENT).asJsoup()
        val chapterPath = Regex("""^/${Regex.escape(manga.url)}-chapter-(\d+)$""")
        val chapterList = document.select("a[href]").mapNotNull { link ->
            val path = link.absUrl("href").toHttpUrlOrNull()?.encodedPath ?: return@mapNotNull null
            val number = chapterPath.matchEntire(path)?.groupValues?.get(1) ?: return@mapNotNull null
            val spans = link.select("span")
            val name = spans.firstOrNull()?.text()?.takeIf { CHAPTER_LABEL.containsMatchIn(it) } ?: return@mapNotNull null
            SChapter.create().apply {
                url = path
                this.name = name
                chapter_number = number.toFloat()
                date_upload = parseRelativeDate(spans.last()?.text())
            }
        }.distinctBy { it.url }.sortedByDescending { it.chapter_number }
        return SMangaUpdate(parseDetails(document, manga.url), chapterList)
    }

    private fun parseDetails(document: Document, slug: String) = SManga.create().apply {
        url = slug
        title = document.selectFirst(".novel-title")!!.text()
        thumbnail_url = document.selectFirst(".novel-cover img")?.absUrl("src")
        author = document.selectFirst(".novel-info .text-secondary")?.text()?.replace(BY_PREFIX, "")?.ifEmpty { null }
        status = when (document.selectFirst(".novel-info .badge")?.text()?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        genre = document.select(".novel-tags a[href]").map { it.text() }.filter(String::isNotEmpty).distinct().joinToString()
        // A script fills the synopsis in from a template literal.
        description = SYNOPSIS.find(document.html())?.groupValues?.get(1)
            ?.replace(ESCAPED, "$1")
            ?.let { Jsoup.parseBodyFragment(it).body() }
            ?.let { body -> body.select("p").map { it.text() }.filter(String::isNotEmpty).ifEmpty { listOf(body.text()) } }
            ?.joinToString("\n\n")
            ?.ifEmpty { null }
    }

    private fun parseRelativeDate(text: String?): Long {
        val match = RELATIVE_DATE.find(text?.lowercase() ?: return 0L) ?: return 0L
        val amount = match.groupValues[1].toLongOrNull() ?: 1L
        val unit = when (match.groupValues[2]) {
            "second" -> ChronoUnit.SECONDS
            "minute" -> ChronoUnit.MINUTES
            "hour" -> ChronoUnit.HOURS
            "day" -> ChronoUnit.DAYS
            "week" -> ChronoUnit.WEEKS
            "month" -> ChronoUnit.MONTHS
            else -> ChronoUnit.YEARS
        }
        return ZonedDateTime.now().minus(amount, unit).toInstant().toEpochMilli()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val document = client.get(baseUrl + page.url, Fetch.DOCUMENT).asJsoup()
        // Hidden paragraphs of random characters are mixed into the text.
        document.select("script, style, noscript, ins, iframe, [style*=\"display:none\"], [style*=\"display: none\"]").remove()
        document.select(".advertisement, .advertisement_warpper, .novel-promo-card, .promo-container, .comments").remove()
        val container = document.select("main > div").maxByOrNull { it.select("p").size }
            ?.takeIf { it.select("p").isNotEmpty() }
            ?: throw Exception("Chapter content not found")
        container.select("a").filter { !it.hasText() }.forEach { it.remove() }
        return container.html()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        OrderFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    private open class UriPartFilter(name: String, private val options: Array<Pair<String, String>>, state: Int = 0) : Filter.Select<String>(name, options.map { it.first }.toTypedArray(), state) {
        val value: String
            get() = options[state].second
    }

    private class SortFilter(state: Int = 0) : UriPartFilter("Sort by", arrayOf("Popular" to "popular", "Recent" to "recent", "Chapters" to "chaptes", "Views" to "views"), state)

    private class OrderFilter : UriPartFilter("Order", arrayOf("Descending" to "desc", "Ascending" to "asc"))

    private class StatusFilter : UriPartFilter("Status", arrayOf("All" to "all", "Ongoing" to "ongoing", "Completed" to "completed"))

    private class Genre(name: String, val id: String) : Filter.CheckBox(name)

    private class GenreFilter : Filter.Group<Genre>("Genres", GENRES.map { Genre(it.first, it.second) })

    companion object {
        private val CHAPTER_LABEL = Regex("""^chapter\s+\d+""", RegexOption.IGNORE_CASE)
        private val BY_PREFIX = Regex("""^by\s+""", RegexOption.IGNORE_CASE)
        private val SYNOPSIS = Regex("""fullSynopsis\s*=\s*`([\s\S]*?)`\s*;""")
        private val ESCAPED = Regex("""\\(.)""")
        private val RELATIVE_DATE = Regex("""(\d+|an?)\s+(second|minute|hour|day|week|month|year)s?\s+ago""")
        private val GENRES = listOf(
            "Academy" to "1",
            "Harem" to "2",
            "Fantasy" to "3",
            "Romance" to "4",
            "Transmigration" to "5",
            "Reincarnation" to "6",
            "Action" to "7",
            "Comedy" to "8",
            "Drama" to "9",
            "Regression" to "10",
            "Revenge" to "11",
            "Mystery" to "12",
            "Sci-fi" to "13",
            "Slice of Life" to "14",
            "Supernatural" to "15",
            "Martial Arts" to "16",
            "Wuxia" to "17",
            "Shounen" to "18",
        )
    }
}

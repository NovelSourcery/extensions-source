package eu.kanade.tachiyomi.novelextension.en.asianhobbyist

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
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.formattedText
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Element
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@Source
abstract class AsianHobbyist :
    KeiSource(),
    NovelSource {

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList())

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = client.get(if (page > 1) "$baseUrl/page/$page/" else baseUrl, Fetch.DOCUMENT, ensureSuccess = false)
        if (response.code == 404) {
            response.close()
            return MangasPage(emptyList(), false)
        }
        val document = response.asJsoup()
        val mangas = document.select("ul.series > li.item").mapNotNull { item ->
            val link = item.selectFirst(".title a") ?: return@mapNotNull null
            mangaFrom(link.text(), link.absUrl("href"), item.selectFirst("img"))
        }
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val term = query.trim()
        if (term.isNotEmpty()) {
            if (page > 1 || term.length < 3) return MangasPage(emptyList(), false)
            // Results show the title re-cased around the match; the title attribute keeps the real name.
            val mangas = ajax("gsr", term).select("li").mapNotNull { item ->
                val link = item.selectFirst(".title a") ?: return@mapNotNull null
                mangaFrom(link.attr("title").ifEmpty { link.text() }, link.absUrl("href"), item.selectFirst("img"))
            }
            return MangasPage(mangas.distinctBy { it.url }, false)
        }
        val ranking = (filters.firstInstanceOrNull<RankingFilter>() ?: RankingFilter()).value
            ?: return getLatestUpdates(page)
        if (page > 1) return MangasPage(emptyList(), false)
        val mangas = ajax("fs", ranking).select(".table_pop").mapNotNull { item ->
            val link = item.selectFirst(".table_title a") ?: return@mapNotNull null
            mangaFrom(link.text(), link.absUrl("href"), item.selectFirst(".thumb img"))
        }
        return MangasPage(mangas.distinctBy { it.url }, false)
    }

    // Ranking and search go through admin-ajax, which needs the token every page carries in <meta name="enc">.
    private suspend fun ajax(action: String, src: String) = run {
        val enc = client.get(baseUrl, Fetch.DOCUMENT).asJsoup().selectFirst("meta[name=enc]")?.attr("content")
            ?: throw Exception("Search token not found")
        val body = FormBody.Builder().add("action", action).add("enc", enc).add("src", src).build()
        client.post("$baseUrl/wp-admin/admin-ajax.php", headers, Fetch.XHR, body).asJsoup()
    }

    private fun mangaFrom(title: String, href: String, image: Element?): SManga? {
        val slug = seriesSlug(href.toHttpUrlOrNull() ?: return null) ?: return null
        return SManga.create().apply {
            url = slug
            this.title = title
            thumbnail_url = image?.imageUrl()
        }
    }

    private fun seriesSlug(url: HttpUrl): String? {
        if (!url.host.endsWith(baseHost.removePrefix("www."))) return null
        val segments = url.pathSegments.filter(String::isNotEmpty)
        return segments.getOrNull(1)?.takeIf { segments.first() == "series" }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url}/"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = seriesSlug(url) ?: return null
        return parseDetails(client.get(getMangaUrl(SManga.create().apply { this.url = slug }), Fetch.DOCUMENT).asJsoup().body(), slug)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
        val chapterList = document.select(".releases-wrap .tableBody .row").mapNotNull { row ->
            val link = row.selectFirst("a[href]") ?: return@mapNotNull null
            SChapter.create().apply {
                setUrlWithoutDomain(link.absUrl("href"))
                name = link.text()
                CHAPTER_NUMBER.find(name)?.value?.toFloatOrNull()?.let { chapter_number = it }
                date_upload = parseRelativeDate(row.select(".cell").lastOrNull()?.text())
            }
        }
        if (chapterList.isEmpty()) throw Exception("No chapters found")
        return SMangaUpdate(parseDetails(document.body(), manga.url), chapterList.asReversed())
    }

    private fun parseDetails(document: Element, slug: String) = SManga.create().apply {
        url = slug
        title = document.selectFirst(".info h1.entry-title")!!.text()
        thumbnail_url = document.selectFirst(".info .thumb img")?.imageUrl()
        description = document.selectFirst(".info .description")?.formattedText()
    }

    // Covers are lazy loaded, and series without one show the theme's 404 placeholder.
    private fun Element.imageUrl(): String? = absUrl("data-src").ifEmpty { absUrl("src") }
        .takeIf { it.startsWith("http") && !PLACEHOLDER_COVER.containsMatchIn(it) }
        ?.replace("http://", "https://")

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
        val content = client.get(baseUrl + page.url, Fetch.DOCUMENT).asJsoup().selectFirst(".entry-content")
            ?: throw Exception("Chapter content not found")
        content.select(CHAPTER_JUNK).remove()
        content.select("img[data-src]").forEach { it.attr("src", it.absUrl("data-src")) }
        if (content.text().length < 100) throw Exception("Chapter content not found")
        return content.html()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Ignored when searching by text"),
        RankingFilter(),
    )

    private class RankingFilter : Filter.Select<String>("Sort by", RANKINGS.map { it.first }.toTypedArray()) {
        val value: String?
            get() = RANKINGS[state].second
    }

    private val baseHost by lazy { baseUrl.toHttpUrl().host }

    companion object {
        private val RANKINGS = arrayOf(
            "Latest updates" to null,
            "Weekly ranking" to "1x1",
            "Monthly ranking" to "1x2",
        )
        private const val CHAPTER_JUNK =
            "script, style, noscript, iframe, form, .code-block, .adsbygoogle, #vuukle-quiz, [id^=vuukle]"
        private val PLACEHOLDER_COVER = Regex("""/assets/images/404\.jpg$""")
        private val CHAPTER_NUMBER = Regex("""\d+(?:\.\d+)?""")
        private val RELATIVE_DATE = Regex("""(\d+|an?)\s+(second|minute|hour|day|week|month|year)s?\s+ago""")
    }
}

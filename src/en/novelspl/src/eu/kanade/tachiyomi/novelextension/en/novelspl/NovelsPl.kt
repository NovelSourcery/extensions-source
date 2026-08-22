package eu.kanade.tachiyomi.novelextension.en.novelspl

import eu.kanade.tachiyomi.source.NovelSource
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
import keiyoushi.utils.WebViewSession
import keiyoushi.utils.WebViewTimeoutException
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.setAltTitles
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * novels.pl fronts every page with Anubis, a proof-of-work anti-bot challenge (200 OK, but the
 * body is the challenge shell rather than real content). [getBypassingChallenge] mirrors the
 * Cloudflare-challenge pattern used elsewhere in this repo (see Honeyfeed): solve it once in a
 * real WebView with `useOkHttpNetwork` so the clearance cookie it earns lands in this source's own
 * cookie jar, then retry with a plain request. This could not be verified live in this
 * environment - the actual page markup below was provided by the user from a real solved session,
 * not scraped directly here - so the Anubis-solving path itself needs on-device confirmation.
 */
@Source
abstract class NovelsPl :
    KeiSource(),
    NovelSource {

    override val supportsLatest = false

    private val webViewSession = WebViewSession()

    private suspend fun getBypassingChallenge(url: String): Response {
        var response = client.get(url, headers)
        if (isChallengePage(response)) {
            response.close()
            solveAnubisChallenge(url)
            response = client.get(url, headers)
        }
        return response
    }

    private fun isChallengePage(response: Response): Boolean = "anubis_challenge" in response.peekBody(4096).string()

    private suspend fun solveAnubisChallenge(url: String) {
        try {
            runWebView<Unit>(session = webViewSession, timeout = 30.seconds) {
                useOkHttpNetwork = true
                var resolved = false
                onPageFinished {
                    if (resolved) return@onPageFinished
                    evaluateJs("document.title") { titleJson ->
                        val title = titleJson.parseAs<String>()
                        if (!title.contains("not a bot", ignoreCase = true)) {
                            resolved = true
                            resolve(Unit)
                        }
                        // Otherwise Anubis's own JS is still computing the proof-of-work; it will
                        // navigate again once solved, firing onPageFinished a second time.
                    }
                }
                loadUrl(url)
            }
        } catch (e: WebViewTimeoutException) {
            throw Exception("Novels.pl: could not solve the anti-bot challenge automatically. Please open the site in WebView once, then retry.", e)
        }
    }

    // ======================== Popular / Search ========================

    override suspend fun getPopularManga(page: Int): MangasPage = browse(page, "")

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = browse(page, query)

    /** The site exposes its entire catalog as one large table with no separate paginated listing
     * endpoint; popular/search both page through a single fetch of it. */
    private suspend fun browse(page: Int, query: String): MangasPage {
        val doc = getBypassingChallenge("$baseUrl/listNovels").asJsoup()
        val all = doc.select("a[data-toggle=tooltip][href*=/novel/]")
        val entries: List<Element> = if (query.isNotBlank()) {
            all.filter { it.text().contains(query, ignoreCase = true) }
        } else {
            all
        }

        val from = (page - 1) * PAGE_SIZE
        val mangas = entries.drop(from).take(PAGE_SIZE).map { it.toSManga() }
        return MangasPage(mangas, from + PAGE_SIZE < entries.size)
    }

    private fun Element.toSManga(): SManga {
        val tooltip = Jsoup.parseBodyFragment(attr("title"))
        return SManga.create().apply {
            title = text()
            url = attr("abs:href").toHttpUrl().encodedPath.removePrefix("/novel/")
            thumbnail_url = tooltip.selectFirst("img")?.attr("src")?.let { resolveDataPath(it) }
        }
    }

    private fun resolveDataPath(path: String): String = "$baseUrl/${path.removePrefix("../")}"

    // ======================== Details + Chapters ========================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novel/${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = getBypassingChallenge(getMangaUrl(manga)).asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) parseAllChapters(doc, manga) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? = runCatching {
        val response = getBypassingChallenge(url.toString())
        parseMangaDetails(response.asJsoup()).apply {
            this.url = url.encodedPath.removePrefix("/novel/")
        }
    }.getOrNull()

    private fun parseMangaDetails(doc: Document): SManga = SManga.create().apply {
        title = doc.selectFirst(".panel-title")?.text().orEmpty().removeSuffix("(Web Novel)").removeSuffix("(Light Novel)").trim()
        thumbnail_url = doc.selectFirst(".imageCover img")?.let { resolveDataPath(it.attr("src")) }
        author = doc.selectFirst("a[href^=/author/]")?.text()
        description = doc.selectFirst("p[itemprop=description]")?.text()
        genre = doc.select("div[itemprop=genre] a.label").eachText().distinct().joinToString()

        val altNames = doc.select(".coll a[href*=/novel/]").eachText().filter { it.isNotBlank() && it != title }
        if (altNames.isNotEmpty()) {
            setAltTitles(altNames)
        }
    }

    /** The chapter list is paginated (50/page) with no "show all" mode; fetch every page the
     * "Last" pager link reports, concurrently, and merge. */
    private suspend fun parseAllChapters(firstPageDoc: Document, manga: SManga): List<SChapter> {
        val lastPage = firstPageDoc.select(".pagination a").firstOrNull { it.text() == "Last" }
            ?.attr("href")?.toHttpUrl()?.queryParameter("p")?.toIntOrNull() ?: 1

        val pages = if (lastPage <= 1) {
            listOf(firstPageDoc)
        } else {
            coroutineScope {
                val rest = (2..lastPage).map { p ->
                    async { getBypassingChallenge("${getMangaUrl(manga)}?p=$p").asJsoup() }
                }
                listOf(firstPageDoc) + rest.map { it.await() }
            }
        }

        return pages.flatMap { doc -> doc.select("table tr[id^=c_]") }
            .mapNotNull { row ->
                val link = row.selectFirst("a[href*=Chapter]") ?: return@mapNotNull null
                val order = row.id().removePrefix("c_").toFloatOrNull()
                SChapter.create().apply {
                    name = link.text()
                    url = link.attr("abs:href").toHttpUrl().encodedPath
                    chapter_number = order ?: -1f
                    date_upload = row.select("td").lastOrNull()?.text()?.let { parseDate(it) } ?: 0L
                }
            }
            .distinctBy { it.url }
            .sortedByDescending { it.chapter_number }
    }

    private fun parseDate(date: String): Long = runCatching {
        LocalDate.parse(date, dateFormat).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    // ======================== Pages ========================

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val doc = getBypassingChallenge(baseUrl + page.url).asJsoup()
        val content = doc.selectFirst("#chapter-content") ?: throw Exception("Chapter content not found")
        content.selectFirst("h4")?.remove()
        return content.html()
    }

    companion object {
        private const val PAGE_SIZE = 20
        private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)
    }
}

package eu.kanade.tachiyomi.extension.ar.linovels

import eu.kanade.tachiyomi.source.NovelSource
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
import keiyoushi.utils.SlugPath
import keiyoushi.utils.formattedText
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

@Source
abstract class Linovels :
    KeiSource(),
    NovelSource {

    /** Novels live at "/<slug>/", chapters at "/<slug>-chapter-<number>-<title>/". */
    private val mangaPath = SlugPath("/", "/")

    // The site's WAF answers chapter URLs with 403 "الوصول الآلي غير مسموح" unless the request
    // looks like a browser, and Accept-Language is the header it checks for.
    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .set("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)

        val document = client.get(baseUrl, Fetch.DOCUMENT).asJsoup()
        return MangasPage(parseCards(document.select("#PopularPosts2 a.zm-hc")), false)
    }

    /** The full series catalog is ordered by last update, newest first. */
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/search/label/Series" + if (page > 1) "/page/$page" else ""
        return parseListPage(url)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (page > 1) addPathSegments("page/$page/")
            addQueryParameter("s", query)
            addQueryParameter("search-type", "series")
        }.build()
        return parseListPage(url.toString())
    }

    private suspend fun parseListPage(url: String): MangasPage {
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return MangasPage(
            parseCards(document.select("div.zm-cgrid a.zm-cc")),
            document.selectFirst("a.next.page-numbers") != null,
        )
    }

    private fun parseCards(cards: Elements): List<SManga> = cards.mapNotNull { card ->
        val href = card.absUrl("href")
        val title = card.attr("aria-label")
            .ifEmpty { card.attr("title") }
            .ifEmpty { card.selectFirst("b")?.text().orEmpty() }
        if (href.isEmpty() || title.isEmpty()) return@mapNotNull null

        SManga.create().apply {
            setSlugUrl(mangaPath, href)
            this.title = title
            thumbnail_url = card.coverUrl()
        }
    }

    private fun Element.coverUrl(): String? = selectFirst("img")?.let { img ->
        img.absUrl("data-src").ifEmpty { img.absUrl("src") }.takeIf { it.startsWith("http") }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Details and the chapter list are both rendered on the novel page, so fetch it once.
        val document = client.get(mangaPath.absolute(baseUrl, manga.url), Fetch.DOCUMENT).asJsoup()

        return SMangaUpdate(
            manga = if (fetchDetails) parseDetails(document, manga.url) else manga,
            chapters = if (fetchChapters) parseChapters(document) else chapters,
        )
    }

    private fun parseDetails(document: Document, slug: String) = SManga.create().apply {
        url = slug
        title = document.selectFirst("h1.zm-nv-title")!!.text()
        thumbnail_url = document.selectFirst(".zm-nv-cover")?.coverUrl()
        genre = document.select("a.zm-gchip").joinToString { it.text() }
        description = document.selectFirst("#zm-pane-story [itemprop=description]")?.formattedText()
        status = when {
            document.selectFirst(".zm-meta .zm-chip.completed") != null -> SManga.COMPLETED
            document.selectFirst(".zm-meta .zm-chip.ongoing") != null -> SManga.ONGOING
            document.selectFirst(".zm-meta .zm-chip.hiatus") != null -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    /** The chapter list is rendered newest-first, which is the order the app expects. */
    private fun parseChapters(document: Document): List<SChapter> = document.select("ul#zm-chlist > li").mapNotNull { item ->
        val link = item.selectFirst("a[href]") ?: return@mapNotNull null
        val number = item.attr("data-no").toFloatOrNull()

        SChapter.create().apply {
            setUrlWithoutDomain(link.absUrl("href"))
            name = item.selectFirst("span.tt")?.text()?.ifEmpty { null } ?: "Chapter ${item.attr("data-no")}"
            if (number != null) chapter_number = number
            date_upload = item.attr("data-ts").toLongOrNull()?.times(1000) ?: 0L
        }
    }

    override fun getMangaUrl(manga: SManga): String = mangaPath.absolute(baseUrl, manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.encodedPath.seriesSlug() ?: return null

        val response = client.get(mangaPath.absolute(baseUrl, slug), Fetch.DOCUMENT, ensureSuccess = false)
        if (!response.isSuccessful) return null
        val document = response.asJsoup()
        if (document.selectFirst("h1.zm-nv-title") == null) return null

        return parseDetails(document, slug)
    }

    private fun String.seriesSlug(): String? {
        val segment = trim('/').substringBefore('/')
        if (segment.isEmpty()) return null
        return segment.replace(CHAPTER_SUFFIX_REGEX, "").ifEmpty { null }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val document = client.get(baseUrl + page.url, Fetch.DOCUMENT).asJsoup()

        // The reader container is filled by JS; the chapter body is served HTML-escaped inside
        // this hidden textarea, so it is the only source of the text.
        val raw = document.selectFirst("#nk-raw-data")?.text()?.takeIf { it.isNotBlank() } ?: return ""

        val content = Jsoup.parseBodyFragment(raw, baseUrl).body()
        content.select("script, style, iframe, ins, .ads, .adsbygoogle").remove()
        content.select("p").filter { it.text().isEmpty() && it.select("img").isEmpty() }.forEach { it.remove() }
        return content.html()
    }

    companion object {
        private val CHAPTER_SUFFIX_REGEX = Regex("""-chapter-\d+.*$""")
    }
}

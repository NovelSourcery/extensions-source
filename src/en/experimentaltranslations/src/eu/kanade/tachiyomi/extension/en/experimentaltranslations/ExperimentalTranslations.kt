package eu.kanade.tachiyomi.novelextension.en.experimentaltranslations

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.time.Instant

// A WordPress.com blog without stable theme markup, so everything is read from its public REST API.
// Novels are pages holding a synopsis and a table of contents; chapters are dated posts.
@Source
abstract class ExperimentalTranslations :
    KeiSource(),
    NovelSource {

    override val supportsLatest = false

    private val apiUrl by lazy { "https://public-api.wordpress.com/wp/v2/sites/${baseUrl.toHttpUrl().host}" }

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList())

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    // The site has no search, and only lists a handful of series.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = coroutineScope {
        val pages = async { api("pages?per_page=100&orderby=date&order=desc&_fields=slug,link,title") }
        val listed = async { seriesLinks("finished,oneshots") }
        val novels = pages.await()
            .filter { it.slug !in NON_NOVEL_SLUGS && !it.slug.endsWith("-images") }
            .map { it.title.rendered.decoded() to sitePath(it.link)!! } + listed.await()
        val needle = query.searchKey()
        val mangas = novels.distinctBy { it.second }
            .filter { it.first.searchKey().contains(needle) }
            .map { (title, path) ->
                SManga.create().apply {
                    url = path
                    this.title = title
                }
            }
        MangasPage(mangas, false)
    }

    private suspend fun seriesLinks(slugs: String): List<Pair<String, String>> = client.get("$apiUrl/pages?slug=$slugs&_fields=content", Fetch.XHR).parseAs<List<WpContentDto>>()
        .flatMap { page -> Jsoup.parseBodyFragment(page.content?.rendered.orEmpty(), baseUrl).select("a[href]") }
        .mapNotNull { link -> sitePath(link.attr("href"))?.let { link.text() to it } }
        .filter { it.first.isNotEmpty() }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = sitePath(url.toString()) ?: return null
        return SManga.create().apply {
            this.url = path
            title = findEntry(path)?.title?.rendered?.decoded() ?: return null
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val finished = async { seriesLinks("finished").map { it.second }.toSet() }
        val entry = findEntry(manga.url) ?: throw Exception("Novel not found")
        val body = Jsoup.parseBodyFragment(entry.content?.rendered.orEmpty(), baseUrl).body()
        val title = entry.title.rendered.decoded()
        val details = SManga.create().apply {
            url = manga.url
            this.title = title
            thumbnail_url = body.selectFirst("img[src]")?.absUrl("src")
        }
        val toc = parseToc(body)
        if (toc.isNotEmpty()) parseSynopsis(body, details)
        details.status = if (manga.url in finished.await()) SManga.COMPLETED else SManga.UNKNOWN

        // A oneshot is a single post with no table of contents.
        val chapterList = toc.ifEmpty {
            if (POST_PATH.matches(manga.url)) {
                listOf(
                    SChapter.create().apply {
                        url = manga.url
                        name = title
                        date_upload = Instant.tryParse(entry.date?.let { "${it}Z" })
                    },
                )
            } else {
                emptyList()
            }
        }
        SMangaUpdate(details, chapterList.asReversed())
    }

    // Everything above the "Table of Contents" heading is the synopsis, except the "By:" line and bare links.
    private fun parseSynopsis(body: Element, manga: SManga) {
        val synopsis = mutableListOf<String>()
        for (element in body.children()) {
            if (HEADING.matches(element.normalName())) {
                if (TOC_HEADING.containsMatchIn(element.text())) break
                continue
            }
            if (element.normalName() != "p" && element.normalName() != "blockquote") continue
            element.select("br").forEach { it.after("\n") }
            val text = element.wholeText().trim()
            if (text.isEmpty() || DIVIDER.matches(text)) continue
            AUTHOR.find(text)?.let {
                manga.author = it.groupValues[1].trim()
                continue
            }
            if (text.replace(URL_TEXT, "").isBlank()) continue
            synopsis += text.lines().joinToString("\n") { it.trim() }
        }
        manga.description = synopsis.joinToString("\n\n").ifEmpty { null }
    }

    private fun parseToc(body: Element): List<SChapter> = body.select("a[href]").mapNotNull { link ->
        val path = sitePath(link.attr("href")) ?: return@mapNotNull null
        val post = POST_PATH.matchEntire(path) ?: return@mapNotNull null
        if (post.groupValues[4].endsWith("-images") || link.text().isEmpty()) return@mapNotNull null
        SChapter.create().apply {
            url = path
            name = link.text()
            date_upload = LocalDate.of(post.groupValues[1].toInt(), post.groupValues[2].toInt(), post.groupValues[3].toInt())
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
    }.distinctBy { it.url }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val entry = findEntry(page.url, listOf("posts")) ?: throw Exception("Chapter not found")
        val body = Jsoup.parseBodyFragment(entry.content?.rendered.orEmpty(), baseUrl).body()
        body.select("script, style, .sharedaddy, .jp-relatedposts").remove()
        // Posts open with a paragraph of navigation links.
        body.children().firstOrNull()
            ?.takeIf { it.normalName() == "p" && it.select("a").isNotEmpty() && it.text().replace(URL_TEXT, "").isBlank() }
            ?.remove()
        if (!body.hasText()) throw Exception("Chapter not found")
        return body.html()
    }

    private suspend fun findEntry(path: String, kinds: List<String> = listOf("pages", "posts")): WpEntryDto? {
        val slug = path.trim('/').substringAfterLast('/')
        for (kind in kinds) {
            val found = api("$kind?slug=$slug&_fields=slug,link,date,title,content")
            (found.firstOrNull { sitePath(it.link) == path } ?: found.firstOrNull())?.let { return it }
        }
        return null
    }

    private suspend fun api(route: String): List<WpEntryDto> = client.get("$apiUrl/$route", Fetch.XHR).parseAs()

    // Links point at the custom domain or the original .wordpress.com address, over http or https.
    private fun sitePath(href: String): String? {
        val url = href.trim().toHttpUrlOrNull() ?: (baseUrl + href.trim()).toHttpUrlOrNull() ?: return null
        val name = baseUrl.toHttpUrl().host.substringBefore('.')
        if (url.host.removePrefix("www.") !in setOf("$name.com", "$name.wordpress.com")) return null
        return url.encodedPath.let { if (it.endsWith("/")) it else "$it/" }
    }

    private fun String.decoded() = Jsoup.parse(this).text()

    private fun String.searchKey() = decoded().lowercase().replace('‘', '\'').replace('’', '\'').replace('“', '"').replace('”', '"')

    companion object {
        private val NON_NOVEL_SLUGS = setOf("ongoing", "finished", "oneshots", "about", "contact")
        private val POST_PATH = Regex("""^/(\d{4})/(\d{2})/(\d{2})/([^/]+)/$""")
        private val HEADING = Regex("""h[1-6]""")
        private val TOC_HEADING = Regex("""contents""", RegexOption.IGNORE_CASE)
        private val DIVIDER = Regex("""^[―\-–—\s]+$""")
        private val AUTHOR = Regex("""^By:\s*([^\n]+)""", RegexOption.IGNORE_CASE)
        private val URL_TEXT = Regex("""https?://\S+""")
    }
}

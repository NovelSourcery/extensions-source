package eu.kanade.tachiyomi.multisrc.novelbin

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.stripChapterNumberPrefix
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Sites sharing one novel-bin catalog under different skins, so selectors avoid skin class names.
 * [SManga.url] is the novel slug and [SChapter.url] is "slug/chapter-N"; older stored values with a
 * leading "/novel-bin/" or a full url still resolve.
 */
abstract class NovelBin :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    private fun listingUrl(path: String, page: Int): HttpUrl = "$baseUrl/$path/".toHttpUrl().newBuilder()
        .addQueryParameter("page", page.toString())
        .build()

    override suspend fun getPopularManga(page: Int): MangasPage = parseListing(client.get(listingUrl("monthvisit", page), headers, Fetch.DOCUMENT).asJsoup(), page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseListing(client.get(listingUrl("dayvisit", page), headers, Fetch.DOCUMENT).asJsoup(), page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.state?.trim().orEmpty()
        val url = when {
            query.isNotBlank() -> "$baseUrl/search".toHttpUrl().newBuilder()
                .addQueryParameter("keyword", query)
                .addQueryParameter("page", page.toString())
                .build()
            genre.isNotEmpty() -> listingUrl("genre/${genre.replace(' ', '+')}", page)
            else -> listingUrl(filters.filterIsInstance<SortFilter>().firstOrNull()?.path ?: "monthvisit", page)
        }
        return parseListing(client.get(url, headers, Fetch.DOCUMENT).asJsoup(), page)
    }

    private fun parseListing(doc: Document, page: Int): MangasPage {
        val mangas = doc.select("a.nl2-book-card[href*=/novel-bin/]").map { card ->
            SManga.create().apply {
                url = slugOf(card.attr("href"))
                title = card.attr("title").ifBlank { card.selectFirst("h3")?.text().orEmpty() }
                thumbnail_url = card.selectFirst("img")?.absUrl("src")
                author = card.selectFirst("[class*=author]")?.ownText()?.trim()?.ifEmpty { null }
            }
        }.distinctBy { it.url }
        val nextPage = doc.selectFirst("a[aria-label=Next page]")?.absUrl("href")?.toHttpUrlOrNull()
            ?.queryParameter("page")?.toIntOrNull()
        return MangasPage(mangas, nextPage != null && nextPage > page)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novel-bin/${slugOf(manga.url)}/"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        if (segments.size < 2 || segments[0] != "novel-bin" || segments[1].isEmpty()) return null
        val slug = segments[1]
        return parseDetails(client.get("$baseUrl/novel-bin/$slug/", headers, Fetch.DOCUMENT).asJsoup()).apply { this.url = slug }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = slugOf(manga.url)
        val updatedManga = if (fetchDetails) {
            parseDetails(client.get(getMangaUrl(manga), headers, Fetch.DOCUMENT).asJsoup()).apply { url = slug }
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters) fetchChapters(slug) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseDetails(doc: Document): SManga = SManga.create().apply {
        val main = doc.selectFirst("main") ?: doc
        title = main.selectFirst("h1")?.text().orEmpty()
        thumbnail_url = main.selectFirst("img[alt^=Cover of]")?.absUrl("src")
        author = main.selectFirst("[class*=detail-author]")?.text()?.removePrefix("By ")?.trim()?.ifEmpty { null }
        genre = main.select("header a[href*=/genre/]").joinToString { it.text().trim() }
        status = when (fact(main, "Status")?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        description = main.selectFirst("div[class*=synopsis]")?.let(::synopsis)
    }

    private fun fact(root: Element, label: String): String? = root.select("dt").firstOrNull { it.text() == label }?.nextElementSibling()?.text()

    private fun synopsis(element: Element): String = element.wholeText()
        .lines()
        .joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private suspend fun fetchChapters(slug: String): List<SChapter> {
        val url = "$baseUrl/ajax/chapter-list".toHttpUrl().newBuilder()
            .addQueryParameter("slug", slug)
            .build()
        val list = client.get(url, headers, Fetch.XHR).parseAs<ChapterListDto>()
        return list.chapters.map { chapter ->
            SChapter.create().apply {
                this.url = slugOf(chapter.url)
                name = chapter.title.stripChapterNumberPrefix()
                chapter_number = chapter.index.toFloat()
            }
        }.reversed()
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/novel-bin/${slugOf(chapter.url)}"

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.get("$baseUrl/novel-bin/${slugOf(page.url)}", headers, Fetch.DOCUMENT).asJsoup()
        val content = doc.selectFirst("#chr-content") ?: return ""
        content.select("script, style, ins, iframe, [class*=ads]").remove()
        return content.html()
    }

    /** The part of a stored or site path after "/novel-bin/", without surrounding slashes. */
    private fun slugOf(value: String): String {
        val path = value.toHttpUrlOrNull()?.encodedPath ?: value
        return path.trim('/').removePrefix("novel-bin/")
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("Search text overrides the genre, and the genre overrides the sort."),
        SortFilter(),
        GenreFilter(),
    )

    private class SortFilter : Filter.Select<String>("Sort by", arrayOf("Monthly views", "Daily views", "All-time views")) {
        val path get() = arrayOf("monthvisit", "dayvisit", "allvisit")[state]
    }

    private class GenreFilter : Filter.Text("Genre (e.g. Fantasy)")

    @Serializable
    private class ChapterListDto(val chapters: List<ChapterDto> = emptyList())

    @Serializable
    private class ChapterDto(val title: String, val url: String, val index: Int)
}

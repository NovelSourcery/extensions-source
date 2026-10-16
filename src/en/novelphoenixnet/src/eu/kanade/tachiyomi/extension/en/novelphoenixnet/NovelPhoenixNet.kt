package eu.kanade.tachiyomi.novelextension.en.novelphoenixnet

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.lib.chapterutils.paginatedChapterList
import keiyoushi.network.get
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.formattedText
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class NovelPhoenixNet : ReadNovelFull() {

    override fun popularMangaSelector() = "div.li-row"

    override fun popularMangaNextPageSelector() = "a[rel=next]"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst("h3.tit > a")!!
        setSlugUrl(link.absUrl("href"))
        title = link.attr("title").ifBlank { link.text() }
        thumbnail_url = element.selectFirst(".pic img")?.absUrl("src")
    }

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.selected
        when {
            query.isNotBlank() -> url.addPathSegment("search").addQueryParameter("keyword", query)
            genre != null -> url.addPathSegment("genre").addEncodedPathSegment(genre)
            else -> url.addPathSegment(filters.firstInstanceOrNull<ListingFilter>()?.selected ?: popularPage)
        }
        return GET(url.addQueryParameter("page", page.toString()).build(), headers)
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("Filters are ignored when searching by text"),
        ListingFilter(),
        GenreFilter(),
    )

    // Chapter urls are /<slug>/chapter-....html, novel urls are /<slug>.html.
    override suspend fun getMangaByUrl(url: HttpUrl): SManga {
        val slug = url.pathSegments.first().removeSuffix(".html")
        val manga = SManga.create().apply { this.url = slug }
        return mangaDetailsParse(client.get(getMangaUrl(manga)).asJsoup()).apply { this.url = slug }
    }

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        val info = document.selectFirst(".m-imgtxt")
        title = document.selectFirst(".m-desc h1.tit")!!.text()
        thumbnail_url = info?.selectFirst(".pic img")?.absUrl("src")
        author = info?.select("a[href^='/author/']")?.joinToString { it.text() }?.ifEmpty { null }
        genre = info?.select("a[href^='/genre/']")?.joinToString { it.text() }?.ifEmpty { null }
        status = when (info?.selectFirst(".glyphicon-time + .right")?.text()?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        description = buildString {
            document.selectFirst("#novel-summary-inner")?.formattedText()?.let(::append)
            val stats = listOfNotNull(
                document.selectFirst(".score .vote-summary")?.text()?.let { "Rating: $it" },
                document.selectFirst("#list-chapter")?.attr("data-total-chapters")?.ifBlank { null }?.let { "Chapters: $it" },
                info?.selectFirst(".glyphicon-globe + .right")?.text()?.let { "Language: $it" },
            )
            if (stats.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(stats.joinToString("\n"))
            }
        }
    }

    // Details and the first chapter-list page come from the same novel page.
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val updatedManga = if (fetchDetails) mangaDetailsParse(document) else manga
        val updatedChapters = if (fetchChapters) fetchChapterList(manga, document, chapters) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override fun chapterPageSelector() = "#idData li a"

    private suspend fun fetchChapterList(manga: SManga, detailDoc: Document, existingChapters: List<SChapter>): List<SChapter> {
        val listInfo = detailDoc.selectFirst("#list-chapter")
        val pageCount = listInfo?.attr("data-total-page")?.toIntOrNull() ?: chapterListPageCount(detailDoc)
        // Pages are oldest-first, 40 per page. Chapter urls end in a title slug
        // (chapter-14-escape-from-death-21.html), so numbering comes from list position, not the url.
        val ascending = paginatedChapterList(
            existingChapters = existingChapters,
            siteTotal = listInfo?.attr("data-total-chapters")?.toIntOrNull() ?: siteChapterTotal(detailDoc),
            assumedPageSize = 40,
            sortChapters = { it },
            fetchPage = { page ->
                val doc = if (page == 1) detailDoc else client.get("${getMangaUrl(manga)}?page=$page").asJsoup()
                Pair(parseChapterPage(doc), page < pageCount)
            },
        )
        ascending.forEachIndexed { i, chapter -> chapter.chapter_number = (i + 1).toFloat() }
        return ascending.reversed()
    }

    override suspend fun fetchPageText(page: Page): String = client.get(page.url).asJsoup()
        .selectFirst("#chapter-content")
        ?.html()
        ?: throw Exception("Chapter content not found")

    // The theme's only preference (raw HTML) doesn't apply: content is returned as-is.
    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
}

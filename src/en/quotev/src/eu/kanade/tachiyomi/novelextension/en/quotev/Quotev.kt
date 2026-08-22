package eu.kanade.tachiyomi.novelextension.en.quotev

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

    override suspend fun getPopularManga(page: Int): MangasPage = browse(page, DEFAULT_CATEGORY)

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.state?.trim().orEmpty()
        if (query.isNotBlank()) {
            val url = "$baseUrl/search/$query".toHttpUrl().newBuilder()
                .apply { if (page > 1) addQueryParameter("page", page.toString()) }
                .build()
            return parseStoryList(client.get(url, headers).asJsoup())
        }
        return browse(page, category.ifBlank { DEFAULT_CATEGORY })
    }

    private suspend fun browse(page: Int, category: String): MangasPage {
        val url = "$baseUrl/stories/c/Fiction/c/$category".toHttpUrl().newBuilder()
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
        Filter.Header("Fiction category, e.g. Harem, Alpha, Dark, Cultivation"),
        CategoryFilter(),
    )

    private class CategoryFilter : Filter.Text("Category", DEFAULT_CATEGORY)

    companion object {
        private const val DEFAULT_CATEGORY = "Harem"
        private const val LINE_BREAK_MARKER = "␈"
        private const val OPEN_ENDED = -1
    }
}

package eu.kanade.tachiyomi.novelextension.en.nobadnovel

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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Source
abstract class NoBadNovel :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    override suspend fun getPopularManga(page: Int): MangasPage = browse(page, sort = "createdAt")

    override suspend fun getLatestUpdates(page: Int): MangasPage = browse(page, sort = "updatedAt")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.toUriPart().orEmpty()
        return browse(page, keyword = query, status = status)
    }

    private suspend fun browse(page: Int, sort: String = "", status: String = "", keyword: String = ""): MangasPage {
        val url = "$baseUrl/series/page/$page".toHttpUrl().newBuilder()
            .apply {
                if (sort.isNotEmpty()) addQueryParameter("sort", sort)
                if (status.isNotEmpty()) addQueryParameter("status", status)
                if (keyword.isNotBlank()) addQueryParameter("keyword", keyword)
            }
            .build()
        val doc = client.get(url, headers).asJsoup()

        val mangas = doc.select("h4 > a[href*=/series/]").mapNotNull { link ->
            val title = link.text()
            if (title.isEmpty()) return@mapNotNull null
            SManga.create().apply {
                this.title = title
                this.url = link.attr("abs:href").toHttpUrl().encodedPath.removePrefix("/series/").removeSuffix("/")
                thumbnail_url = link.parent()?.parent()?.selectFirst("img")?.attr("abs:src")
            }
        }
        val hasNextPage = doc.select("a[href*=/series/page/]").any { it.text().toIntOrNull() == page + 1 }
        return MangasPage(mangas, hasNextPage)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = client.get(getMangaUrl(manga), headers).asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(doc) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val response = client.get(url, headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        val doc = response.asJsoup()
        return parseMangaDetails(doc).apply {
            this.url = url.encodedPath.removePrefix("/series/").removeSuffix("/")
        }
    }

    private fun parseMangaDetails(doc: Document): SManga = SManga.create().apply {
        title = doc.selectFirst("h1")!!.text()
        thumbnail_url = doc.selectFirst("main img")?.attr("abs:src")
        author = doc.selectFirst("span:containsOwn(Author:)")?.nextElementSibling()?.text()
        description = doc.selectFirst("#intro .content")?.let { formatDescription(it.html()) }
        status = when (doc.selectFirst(".badge")?.text()) {
            "Completed" -> SManga.COMPLETED
            "Ongoing" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private fun formatDescription(html: String): String {
        val marked = html.replace(lineBreakRegex, LINE_BREAK_MARKER)
        return Jsoup.parseBodyFragment(marked, baseUrl).text().replace(LINE_BREAK_MARKER, "\n").trim()
    }

    private fun parseChapterList(doc: Document): List<SChapter> = doc.select("#chapter-list a[href*=/series/]").map { link ->
        SChapter.create().apply {
            name = link.text()
            url = link.attr("abs:href").toHttpUrl().encodedPath
        }
    }.reversed()

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.get(baseUrl + page.url, headers).asJsoup()
        val content = doc.selectFirst("p.para")?.parent() ?: throw Exception("Chapter content not found")
        content.select("script, ins.adsbygoogle").remove()
        return content.html()
    }

    override fun getFilterList(data: JsonElement?) = FilterList(StatusFilter())

    private class StatusFilter : Filter.Select<String>("Status", arrayOf("All", "Ongoing", "Completed")) {
        fun toUriPart() = when (state) {
            1 -> "OnGoing"
            2 -> "Completed"
            else -> ""
        }
    }

    companion object {
        private const val LINE_BREAK_MARKER = "␈"
        private val lineBreakRegex = Regex("""<br\s*/?>""")
    }
}

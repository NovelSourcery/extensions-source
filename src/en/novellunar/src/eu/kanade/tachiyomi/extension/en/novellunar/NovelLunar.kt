package eu.kanade.tachiyomi.novelextension.en.novellunar

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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities

@Source
abstract class NovelLunar :
    KeiSource(),
    NovelSource {

    override suspend fun getPopularManga(page: Int): MangasPage = getListing("ranking", page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = getListing("latest", page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val url = "$baseUrl/api/novels/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query.trim())
                .build()
            val mangas = client.get(url).parseAs<SearchResponse>().data.map { it.toSManga() }
            return MangasPage(mangas, false)
        }
        return getListing(
            list = filters.firstInstanceOrNull<ListFilter>()?.toValue() ?: "ranking",
            page = page,
            genre = filters.firstInstanceOrNull<GenreFilter>()?.toValue().orEmpty(),
            status = filters.firstInstanceOrNull<StatusFilter>()?.toValue().orEmpty(),
        )
    }

    private suspend fun getListing(list: String, page: Int, genre: String = "", status: String = ""): MangasPage {
        val url = "$baseUrl/$list".toHttpUrl().newBuilder().apply {
            if (genre.isNotEmpty()) addQueryParameter("genre", genre)
            if (status.isNotEmpty()) addQueryParameter("status", status)
            addQueryParameter("page", page.toString())
        }.build()
        return client.get(url).asJsoup().parseListing()
    }

    private fun Document.parseListing(): MangasPage {
        val mangas = select("a[href^=/novel/]:has(img)").map { card ->
            val img = card.selectFirst("img")!!
            SManga.create().apply {
                url = card.attr("href").removePrefix("/novel/").substringBefore("/")
                title = img.attr("alt")
                thumbnail_url = img.absUrl("src")
            }
        }.distinctBy { it.url }
        val hasNextPage = selectFirst("nav[aria-label=Pagination] a[aria-label=\"Next page\"][aria-disabled=false]") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novel/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        if (segments.getOrNull(0) != "novel") return null
        val slug = segments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return parseDetails(client.get("$baseUrl/novel/$slug").asJsoup()).apply { this.url = slug }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val details = if (fetchDetails) async { parseDetails(client.get(getMangaUrl(manga)).asJsoup()) } else null
        val updatedChapters = if (fetchChapters) fetchChapterList(manga.url) else chapters
        SMangaUpdate(details?.await() ?: manga, updatedChapters)
    }

    private fun parseDetails(document: Document): SManga = SManga.create().apply {
        val header = document.selectFirst("h1")!!
        val info = header.parent()!!
        title = header.text()
        thumbnail_url = document.selectFirst("img[src*=img.novellunar.com]")?.absUrl("src")
        author = document.selectFirst("a[href^=/author/]")?.text()

        val statusText = document.selectFirst("img[src*=img.novellunar.com] + span")?.text()?.lowercase()
        status = when {
            statusText == null -> SManga.UNKNOWN
            "ongoing" in statusText -> SManga.ONGOING
            "completed" in statusText -> SManga.COMPLETED
            "hiatus" in statusText -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        val category = info.select("> a[href$=-online-novel]").map { it.text() }
        val tags = document.select("a[href^=/tag/]").map { it.text() }
        genre = (category + tags).filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .joinToString()
            .takeIf { it.isNotEmpty() }

        val stats = info.text()
        description = buildString {
            document.selectFirst("p.whitespace-pre-wrap")?.wholeText()
                ?.replace(EXTRA_NEWLINES_REGEX, "\n\n")
                ?.trim()
                ?.let { append(it) }
            val statLines = buildList {
                RATING_REGEX.find(stats)?.let { add("Rating: ${it.groupValues[1]} (${it.groupValues[2]} reviews)") }
                VIEWS_REGEX.find(stats)?.let { add("Views: ${it.groupValues[1]}") }
                CHAPTERS_REGEX.find(stats)?.let { add("Chapters: ${it.groupValues[1]}") }
                SAVED_REGEX.find(stats)?.let { add("Saved: ${it.groupValues[1]}") }
            }
            if (statLines.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(statLines.joinToString("\n"))
            }
        }.takeIf { it.isNotEmpty() }
    }

    private suspend fun fetchChapterList(slug: String): List<SChapter> = buildList {
        var page = 1
        while (true) {
            val url = "$baseUrl/api/novels/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("novelSlug", slug)
                .addQueryParameter("page", page.toString())
                .addQueryParameter("limit", CHAPTER_PAGE_SIZE.toString())
                .addQueryParameter("sort", "desc")
                .build()
            val data = client.get(url).parseAs<ChaptersResponse>().data
            data.mapTo(this) { it.toSChapter(slug) }
            if (data.size < CHAPTER_PAGE_SIZE) break
            page++
        }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val slug = chapter.url.substringBeforeLast("/")
        val number = chapter.url.substringAfterLast("/")
        return "$baseUrl/novel/$slug/chapter/$number"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, getChapterUrl(chapter)))

    override suspend fun fetchPageText(page: Page): String {
        val content = client.get(page.url).asJsoup().selectFirst("article div[style*=pre-wrap]")
            ?: throw Exception("Chapter content not found")
        return content.wholeText()
            .split(PARAGRAPH_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("") { "<p>${Entities.escape(it).replace("\n", "<br>")}</p>" }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        ListFilter(),
        GenreFilter(),
        StatusFilter(),
    )

    companion object {
        private const val CHAPTER_PAGE_SIZE = 100
        private val PARAGRAPH_SPLIT_REGEX = Regex("""\n\s*\n""")
        private val EXTRA_NEWLINES_REGEX = Regex("""\n{3,}""")
        private val RATING_REGEX = Regex("""([\d.]+)\s*\((\d[\d,]*) reviews?\)""")
        private val VIEWS_REGEX = Regex("""([\d.,]+[KMB]?) views""")
        private val CHAPTERS_REGEX = Regex("""([\d,]+) chapters""")
        private val SAVED_REGEX = Regex("""([\d,]+) saved""")
    }
}

package eu.kanade.tachiyomi.novelextension.en.pawread

import eu.kanade.tachiyomi.network.GET
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
import keiyoushi.utils.SlugPath
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class PawRead :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    /**
     * The site's novel detail URL shape, as `/novel/<slug>`. [SManga.url] is stored as the bare
     * slug (see [SlugPath]); a stored value starting with "/" is a pre-existing full-path entry
     * from before this source adopted slug storage, and is resolved unchanged regardless of
     * this template.
     */
    protected open val mangaPathTemplate: SlugPath = SlugPath("/novel/")

    // ======================== Popular ========================

    protected open fun buildPopularMangaRequest(page: Int): Request = GET("$baseUrl/list/?sort=click&page=$page", headers)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val popularRequest = buildPopularMangaRequest(page)
        val response = client.get(popularRequest.url, popularRequest.headers)
        val doc = response.asJsoup()
        val mangas = parseNovels(doc)
        // Always assume next page exists if we got results
        return MangasPage(mangas, mangas.isNotEmpty())
    }
    // ======================== Latest ========================

    protected open fun buildLatestUpdatesRequest(page: Int): Request = GET("$baseUrl/list/?sort=update&page=$page", headers)

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val latestRequest = buildLatestUpdatesRequest(page)
        val response = client.get(latestRequest.url, latestRequest.headers)
        val doc = response.asJsoup()
        val mangas = parseNovels(doc)
        return MangasPage(mangas, mangas.isNotEmpty())
    }
    // ======================== Search ========================

    protected open fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/search/?keywords=$query&page=$page", headers)
        }

        var url = "$baseUrl/list/"

        val filterValues = mutableListOf<String>()
        var sort = "click"
        var order = ""

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    val genre = filter.toUriPart()
                    if (genre.isNotEmpty()) filterValues.add(genre)
                }

                is StatusFilter -> {
                    val status = filter.toUriPart()
                    if (status.isNotEmpty()) filterValues.add(status)
                }

                is LangFilter -> {
                    val lang = filter.toUriPart()
                    if (lang.isNotEmpty()) filterValues.add(lang)
                }

                is SortFilter -> sort = filter.toUriPart()

                is OrderFilter -> order = if (filter.state) "-" else ""

                else -> {}
            }
        }

        if (filterValues.isNotEmpty()) {
            url += filterValues.joinToString("-") + "/"
        }

        url += "${order}$sort/?page=$page"

        return GET(url, headers)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val searchRequest = buildSearchMangaRequest(page, query, filters)
        val response = client.get(searchRequest.url, searchRequest.headers)
        val doc = response.asJsoup()
        val mangas = parseNovels(doc)
        return MangasPage(mangas, mangas.isNotEmpty())
    }
    // ======================== Parse Novels ========================

    private fun parseNovels(doc: Document): List<SManga> {
        return doc.select(".list-comic a.txtA, .list-comic a.title, .itemBox a.txtA, .itemBox a.title").mapNotNull { element ->
            try {
                val title = element.text()
                if (title.isBlank()) return@mapNotNull null

                val url = element.attr("href")
                val path = url.split("/").filter { it.isNotEmpty() }.take(2).joinToString("/")

                val parent = element.parent() ?: element.parents().firstOrNull()
                val cover = parent?.selectFirst("img")?.attr("src") ?: ""

                SManga.create().apply {
                    this.title = title
                    this.url = mangaPathTemplate.slug("/$path")
                    thumbnail_url = cover
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    // ======================== Details ========================

    protected open fun buildMangaDetailsRequest(manga: SManga): Request {
        val resolved = mangaPathTemplate.resolve(manga.url).let { if (it.endsWith("/")) it else "$it/" }
        return GET(baseUrl + resolved, headers)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Details and the chapter list both live on the same novel page - fetch it once.
        val mangaDetailsRequest = buildMangaDetailsRequest(manga)
        val response = client.get(mangaDetailsRequest.url, mangaDetailsRequest.headers)
        val doc = response.asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(doc, response) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(doc: Document): SManga = SManga.create().apply {
        // Cover and name from #Cover div
        val coverDiv = doc.selectFirst("#Cover")
        val img = coverDiv?.selectFirst("img")
        thumbnail_url = img?.attr("src")

        // Title: try img title, then h1, then <title> tag minus " - PawRead"
        title = img?.attr("title")?.trim()?.ifBlank { null }
            ?: doc.selectFirst("h1")?.text()?.ifBlank { null }
            ?: doc.selectFirst("title")?.text()
                ?.replace(Regex("\\s*-\\s*PawRead.*$", RegexOption.IGNORE_CASE), "")
                ?.trim()
            ?: ""

        val infoItems = doc.select("p.txtItme")
        if (infoItems.size >= 1) {
            status = parseStatus(infoItems[0].text())
        }
        if (infoItems.size >= 2) {
            author = infoItems[1].text()
        }

        // Genres from btn-default links
        genre = doc.select("a.btn-default").joinToString { it.text() }

        // Summary from #full-des
        description = doc.selectFirst("#full-des")?.text()
    }

    private fun parseStatus(text: String): Int = when {
        text.contains("Ongoing", ignoreCase = true) ||
            text.contains("lianzai", ignoreCase = true) -> SManga.ONGOING

        text.contains("Completed", ignoreCase = true) ||
            text.contains("wanjie", ignoreCase = true) -> SManga.COMPLETED

        text.contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS

        else -> SManga.UNKNOWN
    }
    // ======================== Chapters ========================

    private fun parseChapterList(doc: Document, response: Response): List<SChapter> {
        val novelPath = response.request.url.encodedPath.let {
            if (it.endsWith("/")) it.dropLast(1) else it
        }

        return doc.select("div.item-box").mapNotNull { element ->
            try {
                val chapterId = element.attr("onclick")
                    .let { Regex("\\d+").find(it)?.value } ?: return@mapNotNull null

                val chapterPath = "$novelPath/$chapterId.html"

                val spans = element.select("span")
                val chapterName = spans.firstOrNull()?.text() ?: "Chapter $chapterId"

                // Date from second span (format: YYYY.MM.DD)
                val dateStr = spans.getOrNull(1)?.text() ?: ""
                val dateUpload = if (dateStr.contains(".")) {
                    runCatching {
                        LocalDate.parse(dateStr, DATE_FORMAT).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.getOrDefault(0L)
                } else {
                    0L
                }

                if (dateStr.contains("Advanced", ignoreCase = true)) return@mapNotNull null

                SChapter.create().apply {
                    url = chapterPath
                    name = chapterName
                    date_upload = dateUpload
                }
            } catch (e: Exception) {
                null
            }
        }.reversed()
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPathTemplate.resolve(manga.url).let { if (it.endsWith("/")) it else "$it/" }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val manga = SManga.create().apply { this.url = mangaPathTemplate.slug(url.encodedPath) }
        val mangaDetailsRequest = buildMangaDetailsRequest(manga)
        val response = client.get(mangaDetailsRequest.url, mangaDetailsRequest.headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        val doc = response.asJsoup()
        return parseMangaDetails(doc).apply { this.url = manga.url }
    }

    // ======================== Pages ========================

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    // ======================== Novel Content ========================

    override suspend fun fetchPageText(page: Page): String {
        val response = client.get(baseUrl + page.url, headers)
        val doc = response.asJsoup()

        val content = doc.selectFirst("div.main") ?: return ""

        val watermarks = listOf("pawread", "tinyurl", "bit.ly")
        content.select("p").forEach { p ->
            val text = p.text().lowercase()
            if (watermarks.any { text.contains(it) }) {
                p.remove()
            }
        }

        return content.html()
    }
    // ======================== Filters ========================

    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("Filters are ignored when using text search"),
        GenreFilter(),
        StatusFilter(),
        LangFilter(),
        SortFilter(),
        OrderFilter(),
    )

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("All", "Completed", "Ongoing", "Hiatus"),
        ) {
        fun toUriPart() = when (state) {
            1 -> "wanjie"
            2 -> "lianzai"
            3 -> "hiatus"
            else -> ""
        }
    }

    private class LangFilter :
        Filter.Select<String>(
            "Language",
            arrayOf("All", "Chinese", "Korean", "Japanese"),
        ) {
        fun toUriPart() = when (state) {
            1 -> "chinese"
            2 -> "korean"
            3 -> "japanese"
            else -> ""
        }
    }

    private class SortFilter :
        Filter.Select<String>(
            "Sort By",
            arrayOf("Clicks", "Time Updated", "Time Posted"),
        ) {
        fun toUriPart() = when (state) {
            0 -> "click"
            1 -> "update"
            2 -> "post"
            else -> "click"
        }
    }

    private class OrderFilter : Filter.CheckBox("Ascending Order", false)

    private class GenreFilter :
        Filter.Select<String>(
            "Genre",
            genres.map { it.first }.toTypedArray(),
            0,
        ) {
        fun toUriPart() = genres[state].second
    }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.US)

        private val genres = listOf(
            Pair("All", ""),
            Pair("Fantasy", "Fantasy"),
            Pair("Action", "Action"),
            Pair("Xuanhuan", "Xuanhuan"),
            Pair("Romance", "Romance"),
            Pair("Comedy", "Comedy"),
            Pair("Mystery", "Mystery"),
            Pair("Mature", "Mature"),
            Pair("Harem", "Harem"),
            Pair("Wuxia", "Wuxia"),
            Pair("Xianxia", "Xianxia"),
            Pair("Tragedy", "Tragedy"),
            Pair("Sci-fi", "Scifi"),
            Pair("Historical", "Historical"),
            Pair("Ecchi", "Ecchi"),
            Pair("Adventure", "Adventure"),
            Pair("Adult", "Adult"),
            Pair("Supernatural", "Supernatural"),
            Pair("Psychological", "Psychological"),
            Pair("Drama", "Drama"),
            Pair("Horror", "Horror"),
            Pair("Josei", "Josei"),
            Pair("Mecha", "Mecha"),
            Pair("Seinen", "Seinen"),
            Pair("Shoujo", "Shoujo"),
            Pair("Shounen", "Shounen"),
            Pair("Smut", "Smut"),
            Pair("Yaoi", "Yaoi"),
            Pair("Yuri", "Yuri"),
            Pair("Martial Arts", "MartialArts"),
            Pair("School Life", "SchoolLife"),
            Pair("Shoujo Ai", "ShoujoAi"),
            Pair("Shounen Ai", "ShounenAi"),
            Pair("Slice of Life", "SliceofLife"),
            Pair("Gender Bender", "GenderBender"),
            Pair("Sports", "Sports"),
            Pair("Urban", "Urban"),
            Pair("Adventurer", "Adventurer"),
        )
    }
}

package eu.kanade.tachiyomi.novelextension.en.shanghaifantasy

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document

@Source
abstract class ShanghaiFantasy :
    KeiSource(),
    NovelSource {

    override val supportsLatest = false

    private val json = Json { ignoreUnknownKeys = true }

    /** [SManga.url] is stored as the bare slug under "/novel/"; a stored value starting with
     * "/" is a pre-existing full-path entry and is resolved unchanged. */
    private val mangaPath = SlugPath("/novel/")

    // region Popular (listing)

    protected open fun buildPopularMangaRequest(page: Int): Request = GET(
        "$baseUrl/wp-json/fiction/v1/novels/?novelstatus=&term=&page=$page&orderby=&order=",
        headers,
    )

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = buildPopularMangaRequest(page)
        val response = client.get(request.url, request.headers)
        return parseMangaListResponse(response)
    }

    private fun parseMangaListResponse(response: Response): MangasPage {
        val novels = json.decodeFromString<List<ShanghaiNovel>>(response.body.string())
        val mangas = novels.map { novel ->
            SManga.create().apply {
                title = novel.title
                url = mangaPath.slug(novel.permalink.removePrefix(baseUrl))
                thumbnail_url = novel.novelImage
            }
        }
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    // endregion

    // region Search (via listing filters)

    protected open fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var genreParam = ""
        var statusParam = ""

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> if (filter.state > 0) genreParam = GENRE_PARAMS[filter.state]
                is StatusFilter -> if (filter.state > 0) statusParam = STATUS_PARAMS[filter.state]
                else -> {}
            }
        }

        return GET(
            "$baseUrl/wp-json/fiction/v1/novels/?novelstatus=$statusParam&term=$genreParam&page=$page&orderby=&order=&query=$query",
            headers,
        )
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val request = buildSearchMangaRequest(page, query, filters)
        val response = client.get(request.url, request.headers)
        return parseMangaListResponse(response)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // endregion

    // region Details + Chapters

    protected open fun buildMangaDetailsRequest(manga: SManga): Request = GET(mangaPath.absolute(baseUrl, manga.url), headers)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val request = buildMangaDetailsRequest(manga)
        val response = client.get(request.url, request.headers)
        val doc = response.asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) fetchChapterList(doc) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(doc: Document): SManga {
        val summaryEl = doc.selectFirst("div.rounded-xl:nth-child(1)")
        summaryEl?.select("p")?.filter { it.text().isBlank() }?.forEach { it.remove() }
        val rawDesc = summaryEl?.select("p")?.joinToString("\n\n") { it.text() } ?: ""

        return SManga.create().apply {
            title = doc.selectFirst("p.mb-3")?.text() ?: ""
            description = rawDesc
            thumbnail_url = doc.selectFirst("div.mt-10 img")?.attr("data-cfsrc")
            status = when (doc.selectFirst(".ml-5 a p")?.text()) {
                "Ongoing" -> SManga.ONGOING
                "Completed" -> SManga.COMPLETED
                "Hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            author = doc.selectFirst("p.text-sm:nth-child(3)")?.text()
            genre = doc.select("div.mb-3:nth-child(4) span").joinToString { it.text() }
        }
    }

    // endregion

    // region Chapters

    private suspend fun fetchChapterList(doc: Document): List<SChapter> {
        val novelId = doc.selectFirst("#chapterList")?.attr("data-cat") ?: return emptyList()

        val chaptersUrl = "$baseUrl/wp-json/fiction/v1/chapters?category=$novelId&order=asc&page=1&per_page=9999"
        val chapResponse = client.get(chaptersUrl, headers)
        val chapters = json.decodeFromString<List<ShanghaiChapter>>(chapResponse.body.string())

        return chapters.mapIndexedNotNull { index, ch ->
            if (ch.locked) {
                return@mapIndexedNotNull null
            }
            SChapter.create().apply {
                name = ch.title
                url = ch.permalink.removePrefix(baseUrl)
                chapter_number = (index + 1).toFloat()
            }
        }.reversed()
    }

    // endregion

    override fun getMangaUrl(manga: SManga): String = mangaPath.absolute(baseUrl, manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val response = client.get(url, headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        val doc = response.asJsoup()
        return parseMangaDetails(doc).apply { this.url = mangaPath.slug(url.encodedPath) }
    }

    // region Pages

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(baseUrl + chapter.url, headers)
        return listOf(Page(0, response.request.url.toString()))
    }

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.get(if (page.url.startsWith("http")) page.url else baseUrl + page.url, headers).asJsoup()
        val title = doc.selectFirst("div.my-5")?.text() ?: ""
        // "div.flex:nth-child(4)" doesn't match anything on the live page - the real container
        // is "div.contenta" (verified live), which also carries inline AdSense blocks injected
        // between paragraphs (".ai-viewports"/"[data-insertion-position]" wrapper divs).
        val content = doc.selectFirst("div.contenta") ?: return ""
        content.select(".ai-viewports, [data-insertion-position], script, ins.adsbygoogle").remove()
        content.children().first()?.before("<h1>$title</h1>")
        content.select("button").remove()
        content.select("p").filter { it.text().isBlank() }.forEach { it.remove() }
        return content.html()
    }

    // endregion

    // region Filters

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        GenreFilter(),
        StatusFilter(),
    )

    private class GenreFilter :
        Filter.Select<String>(
            "Genre",
            GENRE_NAMES.toTypedArray(),
        )

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            STATUS_NAMES.toTypedArray(),
        )

    companion object {
        private val GENRE_NAMES = listOf(
            "All", "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Harem",
            "Historical", "Horror", "Isekai", "Josei", "Martial Arts", "Mature",
            "Mecha", "Mystery", "Psychological", "Romance", "School Life", "Sci-Fi",
            "Seinen", "Shoujo", "Shounen", "Slice of Life", "Sports", "Supernatural",
            "Tragedy", "Wuxia", "Xianxia", "Xuanhuan",
        )

        private val GENRE_PARAMS = listOf(
            "", "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Harem",
            "Historical", "Horror", "Isekai", "Josei", "Martial Arts", "Mature",
            "Mecha", "Mystery", "Psychological", "Romance", "School Life", "Sci-Fi",
            "Seinen", "Shoujo", "Shounen", "Slice of Life", "Sports", "Supernatural",
            "Tragedy", "Wuxia", "Xianxia", "Xuanhuan",
        )

        private val STATUS_NAMES = listOf("All", "Completed", "Dropped", "Hiatus", "Ongoing", "Pending")
        private val STATUS_PARAMS = listOf("", "Completed", "Dropped", "Hiatus", "Ongoing", "Pending")
    }

    // endregion

    // region Data classes

    @Serializable
    class ShanghaiNovel(
        val title: String = "",
        val permalink: String = "",
        val novelImage: String = "",
    )

    @Serializable
    class ShanghaiChapter(
        val title: String = "",
        val permalink: String = "",
        val locked: Boolean = false,
    )

    // endregion
}

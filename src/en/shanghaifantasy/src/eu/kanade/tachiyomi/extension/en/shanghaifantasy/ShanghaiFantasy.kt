package eu.kanade.tachiyomi.extension.en.shanghaifantasy

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class ShanghaiFantasy :
    HttpSource(),
    NovelSource {

    override val name = "Shanghai Fantasy"
    override val baseUrl = "https://shanghaifantasy.com"
    override val lang = "en"
    override val supportsLatest = false

    override val client = network.cloudflareClient

    private val json = Json { ignoreUnknownKeys = true }

    // region Popular (listing)

    override fun popularMangaRequest(page: Int): Request = GET(
        "$baseUrl/wp-json/fiction/v1/novels/?novelstatus=&term=&page=$page&orderby=&order=",
        headers,
    )

    override fun popularMangaParse(response: Response): MangasPage {
        val novels = json.decodeFromString<List<ShanghaiNovel>>(response.body.string())
        val mangas = novels.map { novel ->
            SManga.create().apply {
                title = novel.title
                url = novel.permalink.removePrefix(baseUrl)
                thumbnail_url = novel.novelImage
            }
        }
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    // endregion

    // region Latest (not supported)

    override fun latestUpdatesRequest(page: Int) = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // endregion

    // region Search (via listing filters)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
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
            "$baseUrl/wp-json/fiction/v1/novels/?novelstatus=$statusParam&term=$genreParam&page=$page&orderby=&order=",
            headers,
        )
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // endregion

    // region Details

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()

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

    override fun chapterListRequest(manga: SManga): Request {
        // We need the novel ID from the page to fetch chapters via API
        return GET("$baseUrl${manga.url}", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val novelId = doc.selectFirst("#chapterList")?.attr("data-cat") ?: return emptyList()

        val chaptersUrl = "$baseUrl/wp-json/fiction/v1/chapters?category=$novelId&order=asc&page=1&per_page=9999"
        val chapResponse = client.newCall(GET(chaptersUrl, headers)).execute()
        val chapters = json.decodeFromString<List<ShanghaiChapter>>(chapResponse.body.string())

        var hasLockedChapters = false
        return chapters.mapIndexedNotNull { index, ch ->
            if (ch.locked) {
                hasLockedChapters = true
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

    // region Pages

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.newCall(GET(page.url, headers)).execute().asJsoup()
        val title = doc.selectFirst("div.my-5")?.text() ?: ""
        val content = doc.selectFirst("div.flex:nth-child(4)") ?: return ""
        content.children().first()?.before("<h1>$title</h1>")
        content.select("button").remove()
        content.select("p").filter { it.text().isBlank() }.forEach { it.remove() }
        return content.html()
    }

    // endregion

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // region Helpers

    private fun Response.asJsoup(): Document = Jsoup.parse(body.string(), request.url.toString())

    // endregion

    // region Filters

    override fun getFilterList(): FilterList = FilterList(
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
    data class ShanghaiNovel(
        val title: String = "",
        val permalink: String = "",
        val novelImage: String = "",
    )

    @Serializable
    data class ShanghaiChapter(
        val title: String = "",
        val permalink: String = "",
        val locked: Boolean = false,
    )

    // endregion
}

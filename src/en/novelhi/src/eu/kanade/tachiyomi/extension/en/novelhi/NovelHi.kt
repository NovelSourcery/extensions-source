package eu.kanade.tachiyomi.novelextension.en.novelhi

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.stripChapterNumberPrefix
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy

class NovelHi :
    HttpSource(),
    NovelSource {

    override val name = "NovelHi"
    override val baseUrl = "https://novelhi.com"
    override val lang = "en"
    override val supportsLatest = false
    override val isNovelSource = true
    override val client = network.cloudflareClient

    private val json: Json by injectLazy()

    @Serializable
    private class ListResponse(val data: ListData? = null)

    @Serializable
    private class ListData(val list: List<NovelData> = emptyList())

    @Serializable
    private class NovelData(
        val bookName: String = "",
        val simpleName: String = "",
        val picUrl: String = "",
        val bookDesc: String = "",
        val authorName: String = "",
        val bookStatus: String = "",
        val genres: List<GenreData> = emptyList(),
    )

    @Serializable
    private class GenreData(val genreName: String = "")

    private fun listRequest(page: Int, keyword: String?, genre: String?, status: String?, period: String?): Request {
        val url = "$baseUrl/book/searchByPageInShelf".toHttpUrl().newBuilder()
            .addQueryParameter("curr", page.toString())
            .addQueryParameter("limit", "10")
        if (!keyword.isNullOrBlank()) url.addQueryParameter("keyword", keyword)
        if (!genre.isNullOrBlank()) url.addQueryParameter("bookGenres[]", genre)
        if (!status.isNullOrBlank()) url.addQueryParameter("bookStatus", status)
        if (!period.isNullOrBlank()) url.addQueryParameter("updatePeriod", period)
        return GET(url.build(), headers)
    }

    private fun parseList(response: Response): MangasPage {
        val data = json.decodeFromString<ListResponse>(response.body.string()).data
        val novels = data?.list.orEmpty().map { item ->
            SManga.create().apply {
                title = item.bookName
                url = "/s/${item.simpleName}"
                thumbnail_url = item.picUrl.takeIf { it.isNotBlank() }
                author = item.authorName
                genre = item.genres.joinToString(", ") { it.genreName }
                description = Jsoup.parse(item.bookDesc.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")).text().trim()
                status = if (item.bookStatus == "1") SManga.COMPLETED else SManga.ONGOING
            }
        }
        return MangasPage(novels, novels.isNotEmpty())
    }

    override fun popularMangaRequest(page: Int): Request = listRequest(page, null, null, null, null)
    override fun popularMangaParse(response: Response): MangasPage = parseList(response)

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = parseList(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.selected()
        val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.selected()
        val period = filters.filterIsInstance<PeriodFilter>().firstOrNull()?.selected()
        return listRequest(page, query.takeIf { it.isNotBlank() }, genre, status, period)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseList(response)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        return SManga.create().apply {
            title = doc.selectFirst("b.layui-icon")?.text()?.trim().orEmpty()
                .ifBlank { doc.selectFirst(".tit h1")?.text()?.trim().orEmpty() }
            thumbnail_url = doc.selectFirst(".cover, .decorate-img")?.attr("abs:src")
            author = doc.selectFirst("a[href*=author], .author a")?.text()?.trim()
            description = doc.selectFirst(".desc, .book-desc, #bookIntro")?.text()?.trim()
        }
    }

    @Serializable
    private class ChapterResponse(val data: ChapterData? = null)

    @Serializable
    private class ChapterData(val list: List<ChapterItem> = emptyList())

    @Serializable
    private class ChapterItem(
        val indexName: String = "",
        val indexNum: String = "",
        val createTime: String = "",
    )

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        val novelPath = response.request.url.encodedPath.trim('/')
        val bookId = doc.selectFirst("#bookId")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        val url = "$baseUrl/book/queryIndexList".toHttpUrl().newBuilder()
            .addQueryParameter("bookId", bookId)
            .addQueryParameter("curr", "1")
            .addQueryParameter("limit", "42121")
            .build()
        val data = json.decodeFromString<ChapterResponse>(client.newCall(GET(url, headers)).execute().body.string()).data

        return data?.list.orEmpty().mapNotNull { ch ->
            val num = ch.indexNum.ifBlank { return@mapNotNull null }
            SChapter.create().apply {
                name = ch.indexName.stripChapterNumberPrefix()
                this.url = "/$novelPath/$num"
                chapter_number = num.toFloatOrNull() ?: -1f
            }
        }.sortedByDescending { it.chapter_number }
    }

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.encodedPath))

    override fun imageUrlParse(response: Response): String = ""

    @Serializable
    private class ContentResponse(val data: ContentData? = null)

    @Serializable
    private class ContentData(val content: String = "")

    override suspend fun fetchPageText(page: Page): String {
        val chapterUrl = baseUrl + "/" + page.url.trimStart('/')
        val doc = Jsoup.parse(client.newCall(GET(chapterUrl, headers)).execute().body.string(), baseUrl)
        val path = doc.selectFirst("#chapterContentPath")?.attr("value")
        val token = doc.selectFirst("#chapterContentToken")?.attr("value")
        if (path.isNullOrBlank() || token.isNullOrBlank()) return ""

        val contentUrl = if (path.startsWith("http")) path else baseUrl + "/" + path.trimStart('/')
        val req = GET(
            "$contentUrl?token=$token",
            headers.newBuilder()
                .add("Referer", chapterUrl)
                .add("X-Requested-With", "XMLHttpRequest")
                .build(),
        )
        val raw = json.decodeFromString<ContentResponse>(client.newCall(req).execute().body.string())
            .data?.content ?: return ""

        // Site encodes letters with ROT13 (HTML tags left intact).
        val decoded = Regex("(<[^>]+>)|([a-zA-Z])").replace(raw) { m ->
            val tag = m.groupValues[1]
            if (tag.isNotEmpty()) {
                tag
            } else {
                val c = m.groupValues[2][0]
                val base = if (c <= 'Z') 'A'.code else 'a'.code
                ((c.code - base + 13) % 26 + base).toChar().toString()
            }
        }
        return decoded
            .replace(Regex("<sent\\b", RegexOption.IGNORE_CASE), "<p")
            .replace(Regex("</sent>", RegexOption.IGNORE_CASE), "</p>")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "")
    }

    override fun getFilterList() = FilterList(
        GenreFilter(),
        StatusFilter(),
        PeriodFilter(),
    )

    private class Pair2(val label: String, val value: String)

    private open class UriSelect(name: String, private val opts: List<Pair2>) : Filter.Select<String>(name, opts.map { it.label }.toTypedArray()) {
        fun selected(): String? = opts.getOrNull(state)?.value?.takeIf { it.isNotBlank() }
    }

    private class GenreFilter :
        UriSelect(
            "Genre",
            listOf(
                Pair2("All", ""), Pair2("Action", "action"), Pair2("Adventure", "adventure"),
                Pair2("Comedy", "comedy"), Pair2("Light Novel", "light-novel"), Pair2("Fanfiction", "fanfiction"),
                Pair2("Fantasy", "fantasy"), Pair2("Game", "game"), Pair2("Gender Bender", "gender-bender"),
                Pair2("Harem", "harem"), Pair2("Historical", "historical"), Pair2("Horror", "horror"),
                Pair2("Martial Arts", "martial-arts"), Pair2("Mature", "mature"), Pair2("Mecha", "mecha"),
                Pair2("Military", "military"), Pair2("Mystery", "mystery"), Pair2("Romance", "romance"),
                Pair2("School Life", "school-life"), Pair2("Sci-fi", "sci-fi"), Pair2("Slice of Life", "slice-of-life"),
                Pair2("Sports", "sports"), Pair2("Supernatural", "supernatural"), Pair2("Tragedy", "tragedy"),
                Pair2("Urban Life", "urban-life"), Pair2("Wuxia", "wuxia"), Pair2("Xianxia", "xianxia"),
                Pair2("Xuanhuan", "xuanhuan"), Pair2("Yaoi", "yaoi"), Pair2("Yuri", "yuri"),
            ),
        )

    private class StatusFilter :
        UriSelect(
            "Status",
            listOf(Pair2("All", ""), Pair2("Ongoing", "0"), Pair2("Completed", "1")),
        )

    private class PeriodFilter :
        UriSelect(
            "Update Period",
            listOf(
                Pair2("All", ""),
                Pair2("3 Days", "3"),
                Pair2("7 Days", "7"),
                Pair2("15 Days", "15"),
                Pair2("30 Days", "30"),
            ),
        )
}

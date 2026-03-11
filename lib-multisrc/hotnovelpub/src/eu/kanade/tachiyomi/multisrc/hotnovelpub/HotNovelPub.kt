package eu.kanade.tachiyomi.multisrc.hotnovelpub

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * Base class for HotNovelPub and regional variants.
 * Uses a JSON API at api.{domain}.
 */
open class HotNovelPub(
    override val name: String,
    override val baseUrl: String,
    override val lang: String = "en",
) : HttpSource(),
    NovelSource {

    override val isNovelSource = true

    override val supportsLatest = true

    private val apiUrl get() = baseUrl.replace("://", "://api.")

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("lang", lang)

    // -- Browse --

    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/books/hot/?page=${page - 1}&limit=20", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val json = JSONObject(response.body.string())
        if (json.optInt("status") != 1) return MangasPage(emptyList(), false)

        val data = json.getJSONObject("data")
        val books = data.getJSONObject("books")
        val booksArray = books.optJSONArray("data") ?: return MangasPage(emptyList(), false)

        val novels = (0 until booksArray.length()).map { i ->
            val book = booksArray.getJSONObject(i)
            SManga.create().apply {
                title = book.getString("name")
                thumbnail_url = baseUrl + book.getString("image")
                url = "/" + book.getString("slug")
            }
        }
        val totalPages = books.optInt("pages_count", 1)
        val currentPage = (response.request.url.queryParameter("page")?.toIntOrNull() ?: 0)
        return MangasPage(novels, currentPage + 1 < totalPages)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/books/new/?page=${page - 1}&limit=20", headers)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // -- Search --

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val body = JSONObject().put("key_search", query).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        return okhttp3.Request.Builder()
            .url("$apiUrl/search")
            .headers(
                headers.newBuilder()
                    .add("Content-Type", "application/json;charset=utf-8")
                    .add("Referer", baseUrl)
                    .add("Origin", baseUrl)
                    .build(),
            )
            .post(body)
            .build()
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val json = JSONObject(response.body.string())
        if (json.optInt("status") != 1) return MangasPage(emptyList(), false)

        val books = json.getJSONObject("data").optJSONArray("books")
            ?: return MangasPage(emptyList(), false)
        val novels = (0 until books.length()).map { i ->
            val book = books.getJSONObject(i)
            SManga.create().apply {
                title = book.getString("name")
                url = "/" + book.getString("slug")
            }
        }
        return MangasPage(novels, false)
    }

    // -- Details --

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/book${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val json = JSONObject(response.body.string())
        val data = json.getJSONObject("data")
        val book = data.getJSONObject("book")
        val tags = data.optJSONObject("tags")

        return SManga.create().apply {
            title = book.getString("name")
            thumbnail_url = baseUrl + book.getString("image")
            description = book.optJSONObject("authorize")?.optString("description")
            author = book.optJSONObject("authorize")?.optString("name")
            status = when (book.optString("status")) {
                "updating" -> SManga.ONGOING
                "completed", "full" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            val tagNames = tags?.optJSONArray("tags_name")
            if (tagNames != null && tagNames.length() > 0) {
                genre = (0 until tagNames.length()).joinToString { tagNames.getString(it) }
            }
        }
    }

    // -- Chapters --

    override fun chapterListRequest(manga: SManga): Request = GET("$apiUrl/book${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val json = JSONObject(response.body.string())
        val chaptersArray = json.getJSONObject("data").optJSONArray("chapters")
            ?: return emptyList()

        return (0 until chaptersArray.length()).map { i ->
            val chapter = chaptersArray.getJSONObject(i)
            SChapter.create().apply {
                url = "/" + chapter.getString("slug")
                name = chapter.getString("title")
                chapter_number = (chapter.optInt("index", i) + 1).toFloat()
            }
        }
    }

    // -- Pages --

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.encodedPath
        return listOf(Page(0, url))
    }

    override fun imageUrlParse(response: Response): String = ""

    override suspend fun fetchPageText(page: Page): String {
        // First try to get content from the HTML page
        val htmlResponse = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val html = htmlResponse.body.string()

        // Then get content from the API endpoint
        val slug = page.url.trimStart('/')
        val apiResponse = client.newCall(GET("$baseUrl/server/getContent?slug=$slug", headers)).execute()
        val apiBody = apiResponse.body.string()
        val json = JSONObject(apiBody)
        val dataArray = json.optJSONArray("data") ?: return ""

        val sb = StringBuilder()
        for (i in 0 until dataArray.length()) {
            sb.append("<p>").append(dataArray.getString(i)).append("</p>")
        }
        return sb.toString().replace(".copy right hot novel pub", "")
    }

    // -- Filters --

    override fun getFilterList() = FilterList(
        SortFilter(),
    )

    private class SortFilter :
        SelectFilter(
            "Sort",
            arrayOf(
                "Hot" to "hot",
                "New" to "new",
                "Completed" to "full",
            ),
        )

    open class SelectFilter(
        name: String,
        private val options: Array<Pair<String, String>>,
    ) : eu.kanade.tachiyomi.source.model.Filter.Select<String>(
        name,
        options.map { it.first }.toTypedArray(),
    ) {
        fun selectedValue(): String = options[state].second
    }
}

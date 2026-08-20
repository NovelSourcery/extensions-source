package eu.kanade.tachiyomi.novelextension.en.novelcool

import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

// NOTE: manga.url is stored as the composite "$visitPath?id=$id" (two unrelated fields jammed
// into one string) - not a clean single-prefix shape, so it's left as full-path storage rather
// than forced through SlugPath.
@Source
abstract class NovelCool :
    KeiSource(),
    NovelSource {

    private val json: Json by injectLazy()

    private val apiUrl = "https://api.novelcool.com"
    private val langCode = "en"

    private val userAgent = "Android/Package:com.zuoyou.novel - Version Name:2.3 - Phone Info:sdk_gphone_x86_64(Android Version:13)"
    private val appId = "202201290625004"
    private val secret = "c73a8590641781f203660afca1d37ada"
    private val packageName = "com.zuoyou.novel"

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this.add("User-Agent", userAgent)

    private fun apiHeaders() = headersBuilder()
        .add("Content-Type", "application/x-www-form-urlencoded")
        .build()

    private fun baseBodyBuilder(): FormBody.Builder = FormBody.Builder()
        .add("appId", appId)
        .add("secret", secret)
        .add("package_name", packageName)
        .add("lang", langCode)

    override suspend fun fetchPageText(page: Page): String {
        // "/chapter/<slug>/<id>/" (current) or "?chapter_id=<id>" (legacy)
        val chapterId = if (page.url.contains("chapter_id=")) {
            page.url.substringAfter("chapter_id=")
        } else {
            page.url.trimEnd('/').substringAfterLast('/')
        }
        val body = baseBodyBuilder()
            .add("chapter_id", chapterId)
            .build()

        val response = client.post("$apiUrl/chapter/info/", apiHeaders(), body)
        val jsonObject = json.parseToJsonElement(response.body.string()).jsonObject
        val info = jsonObject["info"]?.jsonObject ?: return ""
        return info["content"]?.jsonPrimitive?.contentOrNull ?: ""
    }

    private fun buildPopularMangaRequest(page: Int): Request {
        val body = baseBodyBuilder()
            .add("lc_type", "novel")
            .add("page", page.toString())
            .add("page_size", "20")
            .build()

        return POST("$apiUrl/elite/hot", apiHeaders(), body)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = buildPopularMangaRequest(page)
        return parseNovelListResponse(client.post(request.url, request.headers, request.body!!))
    }

    private fun parseNovelListResponse(response: Response): MangasPage {
        val jsonObject = json.parseToJsonElement(response.body.string()).jsonObject
        val list = jsonObject["list"]?.jsonArray ?: return MangasPage(emptyList(), false)

        val mangas = list.mapNotNull { element ->
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val visitPath = obj["visit_path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SManga.create().apply {
                title = name
                thumbnail_url = obj["cover"]?.jsonPrimitive?.contentOrNull
                url = "$visitPath?id=$id"
            }
        }

        return MangasPage(mangas, mangas.isNotEmpty())
    }

    private fun buildLatestUpdatesRequest(page: Int): Request {
        val body = baseBodyBuilder()
            .add("lc_type", "novel")
            .add("page", page.toString())
            .add("page_size", "20")
            .build()

        return POST("$apiUrl/elite/latest", apiHeaders(), body)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = buildLatestUpdatesRequest(page)
        return parseNovelListResponse(client.post(request.url, request.headers, request.body!!))
    }

    private fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.startsWith("http") && query.contains("/novel/")) {
            val keyword = query.substringAfter("/novel/")
                .substringBefore('?')
                .substringBefore('#')
                .removeSuffix(".html")
                .replace('-', ' ')
                .trim()
            if (keyword.isNotBlank()) {
                val body = baseBodyBuilder()
                    .add("keyword", keyword)
                    .add("lc_type", "novel")
                    .add("page", "1")
                    .add("page_size", "20")
                    .build()
                return POST("$apiUrl/book/search/", apiHeaders(), body)
            }
        }
        return buildSearchMangaRequestInternal(page, query, filters)
    }

    private fun buildSearchMangaRequestInternal(page: Int, query: String, filters: FilterList): Request = if (query.isBlank()) {
        val sortBy = filters.find { it is SortByFilter }
            ?.let { it as SortByFilter }
            ?.toApiValue()
            ?: "hot"

        val body = baseBodyBuilder()
            .add("lc_type", "novel")
            .add("page", page.toString())
            .add("page_size", "20")
            .build()

        POST("$apiUrl/elite/$sortBy", apiHeaders(), body)
    } else {
        val body = baseBodyBuilder()
            .add("keyword", query)
            .add("lc_type", "novel")
            .add("page", page.toString())
            .add("page_size", "20")
            .build()

        POST("$apiUrl/book/search/", apiHeaders(), body)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val request = buildSearchMangaRequest(page, query, filters)
        return parseNovelListResponse(client.post(request.url, request.headers, request.body!!))
    }

    // Webview should open the site page, not the JSON API endpoint
    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novel/${manga.url.substringBefore("?id=")}.html"

    // The human-facing URL (/novel/<slug>.html) carries no book_id - the API needs one for every
    // other call - so scrape it off the page itself (embedded in a book-follow-trigger button).
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val visitPath = url.encodedPath.removePrefix("/novel/").removeSuffix(".html").trim('/')
        if (visitPath.isBlank()) return null

        val response = client.get(url, headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        val html = response.body.string()
        val bookId = Regex("""book_id="(\d+)"""").find(html)?.groupValues?.get(1) ?: return null

        return parseMangaDetails(SManga.create().apply { this.url = "$visitPath?id=$bookId" }).apply {
            this.url = "$visitPath?id=$bookId"
        }
    }

    private suspend fun fetchBookInfo(bookId: String): JsonObject {
        val body = baseBodyBuilder().add("book_id", bookId).build()
        val response = client.post("$apiUrl/book/info/", apiHeaders(), body)
        return json.parseToJsonElement(response.body.string()).jsonObject
    }

    private suspend fun parseMangaDetails(manga: SManga): SManga {
        val id = manga.url.substringAfter("?id=")
        var jsonObject = fetchBookInfo(id)
        var info = jsonObject["info"]?.jsonObject

        // The API sometimes returns a stub `info` (no name/cover, just follow-status fields) for
        // a book_id that resolves fine through search - seen on stale ids that have been
        // superseded by a same-language id in `diff_lang`. Retry against that id before giving up.
        if (info == null || info["name"] == null) {
            val fallbackId = jsonObject["diff_lang"]?.jsonArray
                ?.map { it.jsonObject }
                ?.firstOrNull { it["lang"]?.jsonPrimitive?.contentOrNull == langCode }
                ?.get("id")?.jsonPrimitive?.contentOrNull
            if (fallbackId != null) {
                jsonObject = fetchBookInfo(fallbackId)
                info = jsonObject["info"]?.jsonObject
            }
        }

        if (info == null || info["name"] == null) {
            throw Exception(
                "NovelCool has no details for this book (removed/restricted?): " +
                    (jsonObject["error_msg"]?.jsonPrimitive?.contentOrNull ?: jsonObject.toString().take(200)),
            )
        }

        return SManga.create().apply {
            title = info["name"]!!.jsonPrimitive.content
            thumbnail_url = info["cover"]!!.jsonPrimitive.content
            author = info["author"]?.jsonPrimitive?.contentOrNull
            artist = info["artist"]?.jsonPrimitive?.contentOrNull
            description = info["intro"]?.jsonPrimitive?.contentOrNull
            genre = info["category_list"]?.jsonArray?.joinToString { it.jsonPrimitive.content }
            status = if (info["completed"]?.jsonPrimitive?.content == "YES") SManga.COMPLETED else SManga.ONGOING
        }
    }

    private suspend fun parseChapterList(manga: SManga): List<SChapter> {
        val id = manga.url.substringAfter("?id=")
        // The API ignores the query param; it carries the name slug into the chapter url so
        // site chapter paths can be built
        val visitPath = manga.url.substringBefore("?id=").trim('/')
        val body = baseBodyBuilder()
            .add("book_id", id)
            .build()

        val response = client.post("$apiUrl/chapter/book_list/?visit_path=$visitPath", apiHeaders(), body)
        val jsonObject = json.parseToJsonElement(response.body.string()).jsonObject
        val list = jsonObject["list"]?.jsonArray ?: return emptyList()

        return list.mapNotNull { element ->
            val obj = element.jsonObject
            val isLocked = obj["is_locked"]?.jsonPrimitive
            val locked = when {
                isLocked == null -> false
                isLocked.isString -> isLocked.content == "1" || isLocked.content.equals("true", ignoreCase = true)
                else -> isLocked.content.toBoolean()
            }
            if (locked) {
                return@mapNotNull null
            }
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val chapterId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

            SChapter.create().apply {
                name = title
                // Site chapter path; the site only cares about the trailing id,
                // the slug segment is cosmetic
                val slug = listOf(visitPath, name.replace(' ', '-'))
                    .filter { it.isNotBlank() }
                    .joinToString("-")
                    .ifBlank { "chapter" }
                url = "/chapter/$slug/$chapterId/"
                date_upload = obj["last_modify"]?.jsonPrimitive?.content?.toLongOrNull()?.times(1000) ?: 0L
                chapter_number = obj["order_id"]?.jsonPrimitive?.content?.toFloatOrNull() ?: -1f
            }
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = if (fetchDetails) async { parseMangaDetails(manga) } else null
        val chaptersDeferred = if (fetchChapters) async { parseChapterList(manga) } else null

        SMangaUpdate(
            manga = detailsDeferred?.await() ?: manga,
            chapters = chaptersDeferred?.await() ?: chapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // Site chapter path (current) or bare chapter id (legacy entries)
        val url = if (chapter.url.startsWith("/")) {
            baseUrl + chapter.url
        } else {
            "$baseUrl/chapter/chapter/${chapter.url}/"
        }
        return listOf(Page(0, url))
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortByFilter(),
    )

    private class SortByFilter :
        Filter.Sort(
            "Order by",
            arrayOf("Hottest", "Latest", "New Books"),
            Selection(0, false),
        ) {
        fun toApiValue(): String = when (state?.index ?: 0) {
            1 -> "latest"
            2 -> "new_book"
            else -> "hot"
        }
    }
}

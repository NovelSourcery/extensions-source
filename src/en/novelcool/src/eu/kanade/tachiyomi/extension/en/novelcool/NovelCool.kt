package eu.kanade.tachiyomi.novelextension.en.novelcool

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class NovelCool :
    HttpSource(),
    NovelSource {

    override val name = "NovelCool"
    override val baseUrl = "https://novelcool.com"
    override val lang = "en"
    override val supportsLatest = true
    override val isNovelSource = true

    private val json: Json by injectLazy()

    private val apiUrl = "https://api.novelcool.com"
    private val langCode = "en"

    private val userAgent = "Android/Package:com.zuoyou.novel - Version Name:2.3 - Phone Info:sdk_gphone_x86_64(Android Version:13)"
    private val appId = "202201290625004"
    private val secret = "c73a8590641781f203660afca1d37ada"
    private val packageName = "com.zuoyou.novel"

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("User-Agent", userAgent)

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

        val response = client.newCall(POST("$apiUrl/chapter/info/", apiHeaders(), body)).execute()
        val jsonObject = json.parseToJsonElement(response.body.string()).jsonObject
        val info = jsonObject["info"]?.jsonObject ?: return ""
        return info["content"]?.jsonPrimitive?.contentOrNull ?: ""
    }

    override fun popularMangaRequest(page: Int): Request {
        val body = baseBodyBuilder()
            .add("lc_type", "novel")
            .add("page", page.toString())
            .add("page_size", "20")
            .build()

        return POST("$apiUrl/elite/hot", apiHeaders(), body)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val jsonObject = json.parseToJsonElement(response.body.string()).jsonObject
        val list = jsonObject["list"]?.jsonArray ?: return MangasPage(emptyList(), false)

        val mangas = list.map { element ->
            val obj = element.jsonObject
            SManga.create().apply {
                title = obj["name"]!!.jsonPrimitive.content
                thumbnail_url = obj["cover"]!!.jsonPrimitive.content
                url = obj["visit_path"]!!.jsonPrimitive.content + "?id=" + obj["id"]!!.jsonPrimitive.content
            }
        }

        return MangasPage(mangas, mangas.isNotEmpty())
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val body = baseBodyBuilder()
            .add("lc_type", "novel")
            .add("page", page.toString())
            .add("page_size", "20")
            .build()

        return POST("$apiUrl/elite/latest", apiHeaders(), body)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
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
        return searchMangaRequestInternal(page, query, filters)
    }

    private fun searchMangaRequestInternal(page: Int, query: String, filters: FilterList): Request = if (query.isBlank()) {
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

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // Webview should open the site page, not the JSON API endpoint
    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novel/${manga.url.substringBefore("?id=")}.html"

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.substringAfter("?id=")
        val body = baseBodyBuilder()
            .add("book_id", id)
            .build()

        return POST("$apiUrl/book/info/", apiHeaders(), body)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val jsonObject = json.parseToJsonElement(response.body.string()).jsonObject
        val info = jsonObject["info"]!!.jsonObject

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

    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.substringAfter("?id=")
        // The API ignores the query param; it carries the name slug into
        // chapterListParse so site chapter paths can be built
        val visitPath = manga.url.substringBefore("?id=").trim('/')
        val body = baseBodyBuilder()
            .add("book_id", id)
            .build()

        return POST("$apiUrl/chapter/book_list/?visit_path=$visitPath", apiHeaders(), body)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val visitPath = response.request.url.queryParameter("visit_path").orEmpty()
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

            SChapter.create().apply {
                name = obj["title"]!!.jsonPrimitive.content
                // Site chapter path; the site only cares about the trailing id,
                // the slug segment is cosmetic
                val id = obj["id"]!!.jsonPrimitive.content
                val slug = listOf(visitPath, name.replace(' ', '-'))
                    .filter { it.isNotBlank() }
                    .joinToString("-")
                    .ifBlank { "chapter" }
                url = "/chapter/$slug/$id/"
                date_upload = obj["last_modify"]?.jsonPrimitive?.content?.toLongOrNull()?.times(1000) ?: 0L
                chapter_number = obj["order_id"]?.jsonPrimitive?.content?.toFloatOrNull() ?: -1f
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        // Site chapter path (current) or bare chapter id (legacy entries)
        val url = if (chapter.url.startsWith("/")) {
            baseUrl + chapter.url
        } else {
            "$baseUrl/chapter/chapter/${chapter.url}/"
        }
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString(), null))

    override fun imageUrlParse(response: Response) = ""

    override fun getFilterList(): FilterList = FilterList(
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

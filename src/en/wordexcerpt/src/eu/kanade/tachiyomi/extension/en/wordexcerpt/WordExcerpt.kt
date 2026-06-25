package eu.kanade.tachiyomi.novelextension.en.wordexcerpt

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

/**
 * WordExcerpt is a React SPA backed by a public Supabase REST API.
 * All data comes from the `novels` and `chapters` tables.
 */
class WordExcerpt :
    HttpSource(),
    NovelSource {

    override val name = "WordExcerpt"
    override val baseUrl = "https://wordexcerpt.com"
    override val lang = "en"
    override val supportsLatest = true
    override val isNovelSource = true
    override val client = network.cloudflareClient

    private val json: Json by injectLazy()

    private val api = "https://debebcxopcfhukeqweco.supabase.co/rest/v1"
    private val anonKey =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImRlYmViY3hvcGNmaHVrZXF3ZWNvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzA2OTY4NjQsImV4cCI6MjA4NjI3Mjg2NH0._DMgqDOhgT2Z9l4gd0aeCV4dXBARZWRabYDd8__BgEM"

    private val novelSelect =
        "id,title,author_name,synopsis,cover_url,genres,status,view_count,updated_at,slug,chapter_count"

    private val pageSize = 30

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("apikey", anonKey)
        .add("Authorization", "Bearer $anonKey")
        .add("Accept", "application/json")

    @Serializable
    private class Novel(
        val id: String = "",
        val title: String = "",
        val author_name: String? = null,
        val synopsis: String? = null,
        val cover_url: String? = null,
        val genres: List<String> = emptyList(),
        val status: String? = null,
        val slug: String = "",
    )

    @Serializable
    private class Chapter(
        val id: String = "",
        val number: Double = 0.0,
        val title: String = "",
        val content: String? = null,
        val is_free: Boolean = true,
    )

    private fun Novel.toSManga() = SManga.create().apply {
        title = this@toSManga.title
        url = "/$slug"
        thumbnail_url = cover_url
        author = author_name
        description = synopsis
        genre = genres.joinToString(", ")
        status = when (this@toSManga.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "dropped", "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun novelsListRequest(page: Int, order: String, extra: Map<String, String> = emptyMap()): Request {
        val from = (page - 1) * pageSize
        val to = from + pageSize - 1
        val url = "$api/novels".toHttpUrl().newBuilder()
            .addQueryParameter("select", novelSelect)
            .addQueryParameter("status", "neq.draft")
            .addQueryParameter("order", order)
            .apply { extra.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val rangeHeaders = headers.newBuilder()
            .add("Range-Unit", "items")
            .add("Range", "$from-$to")
            .build()
        return GET(url, rangeHeaders)
    }

    private fun parseNovels(response: Response): MangasPage {
        val novels = json.decodeFromString<List<Novel>>(response.body.string())
        return MangasPage(novels.map { it.toSManga() }, novels.size >= pageSize)
    }

    override fun popularMangaRequest(page: Int): Request = novelsListRequest(page, "view_count.desc")
    override fun popularMangaParse(response: Response): MangasPage = parseNovels(response)

    override fun latestUpdatesRequest(page: Int): Request = novelsListRequest(page, "updated_at.desc")
    override fun latestUpdatesParse(response: Response): MangasPage = parseNovels(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = novelsListRequest(page, "view_count.desc", mapOf("title" to "ilike.*$query*"))

    override fun searchMangaParse(response: Response): MangasPage = parseNovels(response)

    private fun slugOf(mangaUrl: String) = mangaUrl.trim('/')

    private fun novelBySlugRequest(slug: String): Request {
        val url = "$api/novels".toHttpUrl().newBuilder()
            .addQueryParameter("select", "$novelSelect,id")
            .addQueryParameter("slug", "eq.$slug")
            .addQueryParameter("limit", "1")
            .build()
        return GET(url, headers)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = novelBySlugRequest(slugOf(manga.url))

    override fun mangaDetailsParse(response: Response): SManga {
        val novel = json.decodeFromString<List<Novel>>(response.body.string()).firstOrNull()
            ?: return SManga.create()
        return novel.toSManga()
    }

    override fun chapterListRequest(manga: SManga): Request = novelBySlugRequest(slugOf(manga.url))

    override fun chapterListParse(response: Response): List<SChapter> {
        val novelId = json.decodeFromString<List<Novel>>(response.body.string()).firstOrNull()?.id
            ?: return emptyList()

        val url = "$api/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("select", "id,number,title,is_free")
            .addQueryParameter("novel_id", "eq.$novelId")
            .addQueryParameter("status", "in.(published,scheduled)")
            .addQueryParameter("order", "number.asc")
            .build()
        val chapters = json.decodeFromString<List<Chapter>>(
            client.newCall(GET(url, headers)).execute().body.string(),
        )

        return chapters.map { ch ->
            val numLabel = if (ch.number % 1.0 == 0.0) ch.number.toInt().toString() else ch.number.toString()
            SChapter.create().apply {
                this.url = "/chapter/${ch.id}"
                name = buildString {
                    if (!ch.is_free) append("🔒 ")
                    append(ch.title.ifBlank { "Chapter $numLabel" })
                }
                chapter_number = ch.number.toFloat()
            }
        }.reversed()
    }

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.encodedPath))

    override fun imageUrlParse(response: Response): String = ""

    override suspend fun fetchPageText(page: Page): String {
        val chapterId = page.url.trim('/').substringAfterLast('/')
        val url = "$api/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("select", "content")
            .addQueryParameter("id", "eq.$chapterId")
            .addQueryParameter("limit", "1")
            .build()
        val chapter = json.decodeFromString<List<Chapter>>(
            client.newCall(GET(url, headers)).execute().body.string(),
        ).firstOrNull()
        return chapter?.content.orEmpty()
    }
}

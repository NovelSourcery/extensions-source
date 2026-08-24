package eu.kanade.tachiyomi.novelextension.en.wordexcerpt

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

/**
 * WordExcerpt is a React SPA backed by a public Supabase REST API.
 * All data comes from the `novels` and `chapters` tables.
 */
@Source
abstract class WordExcerpt :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val api = "https://debebcxopcfhukeqweco.supabase.co/rest/v1"
    private val anonKey =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImRlYmViY3hvcGNmaHVrZXF3ZWNvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzA2OTY4NjQsImV4cCI6MjA4NjI3Mjg2NH0._DMgqDOhgT2Z9l4gd0aeCV4dXBARZWRabYDd8__BgEM"

    private val novelSelect =
        "id,title,author_name,synopsis,cover_url,genres,status,view_count,updated_at,slug,chapter_count"

    private val pageSize = 30

    /**
     * The site's novel detail URL shape, as `/<slug>` (root-level). [SManga.url] is stored as
     * the bare slug (see [SlugPath]); a stored value starting with "/" is a pre-existing
     * full-path entry from before this source adopted slug storage, and is resolved unchanged
     * regardless of this template.
     */
    private val mangaPathTemplate = SlugPath("/")

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
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
        url = mangaPathTemplate.slug("/$slug")
        thumbnail_url = cover_url
        author = author_name
        description = synopsis
        genre = genres.joinToString()
        status = when (this@toSManga.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "dropped", "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private suspend fun novelsList(page: Int, order: String, extra: Map<String, String> = emptyMap()): Response {
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
        return client.get(url, rangeHeaders)
    }

    private fun parseNovels(response: Response): MangasPage {
        val novels = json.decodeFromString<List<Novel>>(response.body.string())
        return MangasPage(novels.map { it.toSManga() }, novels.size >= pageSize)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = parseNovels(novelsList(page, "view_count.desc"))

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseNovels(novelsList(page, "updated_at.desc"))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = parseNovels(novelsList(page, "view_count.desc", mapOf("title" to "ilike.*$query*")))

    private fun slugOf(mangaUrl: String) = mangaPathTemplate.resolve(mangaUrl).trim('/')

    private fun novelBySlugUrl(slug: String): HttpUrl = "$api/novels".toHttpUrl().newBuilder()
        .addQueryParameter("select", "$novelSelect,id")
        .addQueryParameter("slug", "eq.$slug")
        .addQueryParameter("limit", "1")
        .build()

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPathTemplate.resolve(manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.encodedPath.trim('/')
        val response = client.get(novelBySlugUrl(slug), headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        val novel = json.decodeFromString<List<Novel>>(response.body.string()).firstOrNull() ?: return null
        return novel.toSManga()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        // Both details and chapters need the novel's internal id, so resolve the novel row once
        // and fan out from there instead of looking it up twice.
        val novel = client.get(novelBySlugUrl(slugOf(manga.url)), headers)
            .let { json.decodeFromString<List<Novel>>(it.body.string()) }
            .firstOrNull()

        val detailsDeferred = if (fetchDetails) async { novel?.toSManga() ?: manga } else null
        val chaptersDeferred = if (fetchChapters) async { novel?.id?.let { fetchChapterList(it) } ?: chapters } else null

        SMangaUpdate(
            manga = detailsDeferred?.await() ?: manga,
            chapters = chaptersDeferred?.await() ?: chapters,
        )
    }

    private suspend fun fetchChapterList(novelId: String): List<SChapter> {
        val url = "$api/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("select", "id,number,title,is_free")
            .addQueryParameter("novel_id", "eq.$novelId")
            .addQueryParameter("status", "in.(published,scheduled)")
            .addQueryParameter("order", "number.asc")
            .build()
        val chapters = json.decodeFromString<List<Chapter>>(
            client.get(url, headers).body.string(),
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

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val chapterId = page.url.trim('/').substringAfterLast('/')
        val url = "$api/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("select", "content")
            .addQueryParameter("id", "eq.$chapterId")
            .addQueryParameter("limit", "1")
            .build()
        val chapter = json.decodeFromString<List<Chapter>>(
            client.get(url, headers).body.string(),
        ).firstOrNull()
        return chapter?.content.orEmpty()
    }
}

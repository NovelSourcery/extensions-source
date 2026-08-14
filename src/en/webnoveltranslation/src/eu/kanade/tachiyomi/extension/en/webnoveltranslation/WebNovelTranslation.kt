package eu.kanade.tachiyomi.novelextension.en.webnoveltranslation

import android.app.Application
import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.setAltTitles
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * WNTL (formerly "WebNovel Translation", webnoveltranslation.com) moved to wntl.net and is now
 * a JSON API site. Ported from the LNReader WNTL TypeScript plugin.
 */
@Source
abstract class WebNovelTranslation :
    KeiSource(),
    NovelSource {

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)

    // Popular / Latest

    private fun buildPopularMangaRequest(page: Int): Request = GET("$baseUrl/api/novels?page=1", headers)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val novels = client.newCall(buildPopularMangaRequest(page)).execute().parseNovelsArray()
        cacheGenres(novels)
        val mangas = novels.map { it.toSManga() }
        return MangasPage(mangas, hasNextPage = false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // Search

    // The API returns the full catalogue in one call, so search/filter runs client-side.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val novels = client.newCall(buildPopularMangaRequest(page)).execute().parseNovelsArray()
        cacheGenres(novels)

        val selectedGenres = filters.filterIsInstance<GenreFilter>()
            .firstOrNull()
            ?.state
            ?.filter { it.state }
            ?.map { it.name }
            .orEmpty()

        val normalizedQuery = query.normalizeForSearch()

        val filtered = novels.filter { novel ->
            val matchesQuery = normalizedQuery.isEmpty() ||
                novel.title.normalizeForSearch().contains(normalizedQuery) ||
                novel.altTitles.any { it.normalizeForSearch().contains(normalizedQuery) }
            val matchesGenre = selectedGenres.isEmpty() || novel.genres.any { it in selectedGenres }
            matchesQuery && matchesGenre
        }

        return MangasPage(filtered.map { it.toSManga() }, false)
    }

    // Details + Chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        // Details (full catalogue) and chapters live on different endpoints - fire both
        // concurrently when both are needed.
        val detailsDeferred = if (fetchDetails) {
            async {
                val id = manga.novelId()
                val novel = client.newCall(buildPopularMangaRequest(1)).execute().parseNovelsArray()
                    .firstOrNull { it.id == id }
                    ?: return@async manga
                novel.toSManga().apply {
                    if (novel.altTitles.isNotEmpty()) setAltTitles(novel.altTitles)
                }
            }
        } else {
            null
        }
        val chaptersDeferred = if (fetchChapters) async { loadChapterList(manga) } else null

        SMangaUpdate(
            manga = detailsDeferred?.await() ?: manga,
            chapters = chaptersDeferred?.await() ?: chapters,
        )
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.novelId()}"

    private fun loadChapterList(manga: SManga): List<SChapter> {
        val response = client.newCall(GET("$baseUrl/api/chapters/${manga.novelId()}", headers)).execute()
        val novelId = response.request.url.pathSegments.last()
        val chapters = json.parseToJsonElement(response.body.string())
            .jsonObject["chapters"]?.jsonArray
            ?: return emptyList()

        return chapters.mapNotNull { element ->
            val obj = element.jsonObject
            val file = obj["file"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SChapter.create().apply {
                name = obj["title"]?.jsonPrimitive?.contentOrNull ?: "Chapter"
                // Content path consumed by fetchPageText: /api/chapter-content/<novelId>/<file>.
                // Stored slashless to match the path shape older library entries used.
                url = "$novelId/$file"
                chapter_number = obj["number"]?.jsonPrimitive?.doubleOrNull?.toFloat() ?: -1f
                date_upload = parseDate(obj["date"]?.jsonPrimitive?.contentOrNull)
            }
        }.reversed()
    }

    // Web url for a chapter: https://wntl.net/read/<novelId>/<chapterNumberOrFile-without-md>
    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/read/${chapter.url.trimStart('/').removeSuffix(".md")}"

    // Pages / Content

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val path = page.url.trimStart('/')
        val response = client.newCall(GET("$baseUrl/api/chapter-content/$path", headers)).execute()
        val content = response.body.string()
        return content
            .split("\n\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n") { "<p>${it.replace("\n", "<br>")}</p>" }
    }

    // Filters

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = cachedGenres()
        return if (genres.isEmpty()) {
            FilterList(Filter.Header("Open the browse list once to load genres"))
        } else {
            FilterList(GenreFilter(genres))
        }
    }

    private class GenreCheckBox(name: String) : Filter.CheckBox(name)
    private class GenreFilter(genres: List<String>) : Filter.Group<GenreCheckBox>("Genre", genres.map { GenreCheckBox(it) })

    private fun cacheGenres(novels: List<Novel>) {
        val genres = novels.flatMap { it.genres }.toSortedSet()
        if (genres.isNotEmpty()) {
            preferences.edit().putString(PREF_GENRES, genres.joinToString("\n")).apply()
        }
    }

    private fun cachedGenres(): List<String> = preferences.getString(PREF_GENRES, "")?.split("\n")?.filter { it.isNotBlank() }.orEmpty()

    // Helpers

    private data class Novel(
        val id: String,
        val title: String,
        val cover: String?,
        val description: String?,
        val author: String?,
        val genres: List<String>,
        val statuses: List<String>,
        val altTitles: List<String>,
    )

    private fun Response.parseNovelsArray(): List<Novel> {
        val novels = json.parseToJsonElement(body.string())
            .jsonObject["novels"]?.jsonArray
            ?: return emptyList()

        return novels.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Novel(
                id = id,
                title = obj["title"]?.jsonPrimitive?.contentOrNull ?: id,
                cover = obj["cover"]?.jsonPrimitive?.contentOrNull,
                description = obj["description"]?.jsonPrimitive?.contentOrNull,
                author = obj["author"]?.jsonPrimitive?.contentOrNull,
                genres = obj["genre"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                statuses = obj["status"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                altTitles = obj["alternate-title"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                    ?.filter { it.isNotBlank() }
                    .orEmpty(),
            )
        }
    }

    private fun Novel.toSManga(): SManga = SManga.create().apply {
        title = this@toSManga.title
        // Slashless to match the path shape older library entries were saved with.
        url = this@toSManga.id
        thumbnail_url = cover?.let { if (it.startsWith("http")) it else "$baseUrl/${it.trimStart('/')}" }
        author = this@toSManga.author
        description = this@toSManga.description
        genre = genres.joinToString(", ")
        status = when {
            statuses.any { it.equals("Completed", true) } -> SManga.COMPLETED
            statuses.any { it.equals("Ongoing", true) } -> SManga.ONGOING
            statuses.any { it.equals("On-Break", true) || it.equals("Hiatus", true) } -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    private fun SManga.novelId(): String = url.trimStart('/').substringBefore('/')

    private fun String.normalizeForSearch(): String = lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun parseDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        return try {
            dateFormat.parse(date)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    companion object {
        private const val PREF_GENRES = "wntl_genres_cache"
    }
}

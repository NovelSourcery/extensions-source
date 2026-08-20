package eu.kanade.tachiyomi.novelextension.ar.goldenrest

import eu.kanade.tachiyomi.network.GET
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
import keiyoushi.utils.SlugPath
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import kotlin.time.Instant

@Source
abstract class GoldenRest :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    private val json: Json by injectLazy()

    /** [SManga.url] stored as bare manga id via [mangaPathTemplate]. */
    private val mangaPathTemplate = SlugPath("/mangas/")

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .add("Accept", "application/json")
        .add("X-Requested-With", "XMLHttpRequest")

    private fun buildPopularMangaRequest(page: Int): Request = GET("$baseUrl/api/releases?page=$page", headers)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = buildPopularMangaRequest(page)
        return parseReleasesResponse(client.get(request.url, request.headers))
    }

    private fun buildLatestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/releases?page=$page", headers)

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = buildLatestUpdatesRequest(page)
        return parseReleasesResponse(client.get(request.url, request.headers))
    }

    private fun parseReleasesResponse(response: Response): MangasPage {
        val data = json.decodeFromString<ReleasesResponse>(response.body.string())
        val mangas = data.releases
            .filter { it.manga?.is_novel == true }
            .map { release ->
                release.manga!!.toSManga()
            }
            .distinctBy { it.url }
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    private fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.startsWith("ID:")) {
            val id = query.removePrefix("ID:").trim()
            return GET("$baseUrl/api/mangas/$id", headers)
        }

        val body = SearchRequestDto(query, page.toString()).toJsonRequestBody()

        return POST("$baseUrl/api/mangas/search", headers, body)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val request = buildSearchMangaRequest(page, query, filters)
        val response = request.body?.let { client.post(request.url, request.headers, it) }
            ?: client.get(request.url, request.headers)
        val body = response.body.string()

        val mangaResponse = try {
            json.decodeFromString<MangaResponse>(body)
        } catch (_: Exception) {
            null
        }

        if (mangaResponse?.mangaData != null) {
            return MangasPage(listOf(mangaResponse.mangaData.toSManga()), false)
        }

        val searchResponse = try {
            json.decodeFromString<MangaSearchResponse>(body)
        } catch (_: Exception) {
            MangaSearchResponse()
        }

        val mangas = searchResponse.results.map { it.toSManga() }
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    private fun mangaId(manga: SManga): String = mangaPathTemplate.resolve(manga.url).removePrefix("/mangas/").substringBefore("/")

    private fun buildMangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/api/mangas/${mangaId(manga)}", headers)

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPathTemplate.resolve(manga.url)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) {
            val request = buildMangaDetailsRequest(manga)
            val response = client.get(request.url, request.headers)
            val data = json.decodeFromString<MangaResponse>(response.body.string())
            data.mangaData?.toSManga() ?: throw Exception("Manga not found")
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) loadChapterList(manga) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private suspend fun loadChapterList(manga: SManga): List<SChapter> {
        val response = client.get("$baseUrl/api/mangas/${mangaId(manga)}/releases?page=1", headers)
        val data = json.decodeFromString<ReleasesResponse>(response.body.string())
        val seen = mutableSetOf<Float>()

        return data.releases
            .filter { seen.add(it.chapter) }
            .map { release ->
                SChapter.create().apply {
                    url = "/mangas/${release.manga_id}/chapters/${release.id}"
                    name = buildString {
                        append("الفصل ${release.chapter.toInt()}")
                        if (release.volume > 0) append(" (المجلد ${release.volume})")
                        if (release.title.isNotBlank()) append(" - ${release.title}")
                    }
                    chapter_number = release.chapter
                    date_upload = parseDate(release.created_at)
                }
            }
            .sortedByDescending { it.chapter_number }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val releaseId = chapter.url.substringAfterLast("/")
        val response = client.get("$baseUrl/api/releases/$releaseId", headers)
        return listOf(Page(0, response.request.url.encodedPath))
    }

    override suspend fun fetchPageText(page: Page): String {
        val releaseId = page.url.substringAfterLast("/").substringBefore("?")

        val releaseResponse = client.get("$baseUrl/api/releases/$releaseId", headers)
        val releaseBody = releaseResponse.body.string()

        val releaseData = try {
            json.decodeFromString<ReleaseDto>(releaseBody)
        } catch (_: Exception) {
            null
        }

        val novelContent = releaseData?.content
        if (!novelContent.isNullOrBlank()) {
            return novelContent.lines()
                .filter { it.isNotBlank() }
                .joinToString("\n") { "<p>${it.trim()}</p>" }
        }

        val downloadResponse = client.post(
            "$baseUrl/api/releases/$releaseId/download",
            headers,
            "{}".toRequestBody("application/json".toMediaType()),
        )

        val downloadBody = downloadResponse.body.string()

        val imageUrl = try {
            json.decodeFromString<Map<String, Any?>>(downloadBody)["url"]?.toString()
        } catch (_: Exception) {
            null
        }

        if (imageUrl != null) {
            return "<img src=\"$imageUrl\" />"
        }

        return buildString {
            append("<h2>${releaseData?.manga?.title ?: ""}</h2>")
            append("<p>الفصل ${releaseData?.chapter?.toInt() ?: 0}</p>")
            append("<p>${releaseData?.manga?.summary ?: ""}</p>")
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val manga = SManga.create().apply { this.url = mangaPathTemplate.slug(url.encodedPath) }
        val request = buildMangaDetailsRequest(manga)
        val response = client.get(request.url, request.headers)
        if (!response.isSuccessful) return null
        val data = json.decodeFromString<MangaResponse>(response.body.string()).mangaData ?: return null
        return data.toSManga()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        StatusFilter(),
        TypeFilter(),
    )

    private class StatusFilter :
        Filter.Select<String>(
            "الحالة",
            arrayOf("الكل", "مستمر", "مكتمل"),
        )

    private class TypeFilter :
        Filter.Select<String>(
            "النوع",
            arrayOf("الكل", "مانها", "مانهوا", "مانغا", "ويبتون"),
        )

    private val coverUrl = "https://golden.rest"

    private fun MangaDto.toSManga(): SManga = SManga.create().apply {
        url = mangaPathTemplate.slug("/mangas/$id/${this@toSManga.title.toSlug()}")
        title = arabic_title?.takeIf { it.isNotBlank() } ?: this@toSManga.title
        thumbnail_url = if (cover.isNotBlank()) {
            "$coverUrl/uploads/manga/cover/$id/$cover"
        } else {
            ""
        }
        description = summary ?: ""
        author = this@toSManga.authors.firstOrNull()?.name
        artist = this@toSManga.artists.firstOrNull()?.name
        genre = this@toSManga.categories.joinToString { it.name }
        status = when (this@toSManga.storyStatus) {
            1 -> SManga.COMPLETED
            2 -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private fun String.toSlug(): String = this
        .lowercase()
        .trim()
        .replace("[^a-z0-9\\s-]".toRegex(), "")
        .replace("\\s+".toRegex(), "-")
        .replace("-+".toRegex(), "-")
        .trim('-')

    private fun parseDate(dateStr: String): Long = Instant.tryParse(dateStr)
}

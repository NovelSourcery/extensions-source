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
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

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

    override suspend fun getPopularManga(page: Int): MangasPage = parseReleasesResponse(client.newCall(buildPopularMangaRequest(page)).execute())

    private fun buildLatestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/releases?page=$page", headers)

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseReleasesResponse(client.newCall(buildLatestUpdatesRequest(page)).execute())

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

        val body = buildJsonObject {
            put("title", query)
            put("page", page.toString())
        }.toString().toRequestBody("application/json".toMediaType())

        return POST("$baseUrl/api/mangas/search", headers, body)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val response = client.newCall(buildSearchMangaRequest(page, query, filters)).execute()
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

    private fun buildMangaDetailsRequest(manga: SManga): Request {
        val id = mangaPathTemplate.resolve(manga.url).substringAfterLast("/")
        return GET("$baseUrl/api/mangas/$id", headers)
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPathTemplate.resolve(manga.url)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) {
            val response = client.newCall(buildMangaDetailsRequest(manga)).execute()
            val data = json.decodeFromString<MangaResponse>(response.body.string())
            data.mangaData?.toSManga() ?: throw Exception("Manga not found")
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) loadChapterList(manga) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun loadChapterList(manga: SManga): List<SChapter> {
        val id = mangaPathTemplate.resolve(manga.url).substringAfterLast("/")
        val response = client.newCall(GET("$baseUrl/api/mangas/$id/releases?page=1", headers)).execute()
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
        val response = client.newCall(GET("$baseUrl/api/releases/$releaseId", headers)).execute()
        return listOf(Page(0, response.request.url.encodedPath))
    }

    override suspend fun fetchPageText(page: Page): String {
        val releaseId = page.url.substringAfterLast("/").substringBefore("?")

        val releaseResponse = client.newCall(GET("$baseUrl/api/releases/$releaseId", headers)).execute()
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

        val downloadResponse = client.newCall(
            POST(
                "$baseUrl/api/releases/$releaseId/download",
                headers,
                "{}".toRequestBody("application/json".toMediaType()),
            ),
        ).execute()

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

    private val coverUrl = "https://dilar.tube"

    private fun MangaDto.toSManga(): SManga = SManga.create().apply {
        url = mangaPathTemplate.slug("/mangas/$id")
        title = arabic_title?.takeIf { it.isNotBlank() } ?: this@toSManga.title
        thumbnail_url = if (cover.isNotBlank()) {
            "$coverUrl/manga/cover/$id/medium_$cover"
        } else {
            ""
        }
        description = summary ?: ""
        author = this@toSManga.authors.firstOrNull()?.name
        artist = this@toSManga.artists.firstOrNull()?.name
        genre = this@toSManga.categories.joinToString(", ") { it.name }
        status = when (this@toSManga.storyStatus) {
            1 -> SManga.COMPLETED
            2 -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private fun parseDate(dateStr: String): Long = try {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        sdf.parse(dateStr)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }
}

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
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.injectLazy

class GoldenRest :
    HttpSource(),
    NovelSource {

    override val name = "Golden Rest"

    override val baseUrl = "https://golden.rest"

    override val lang = "ar"

    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val json: Json by injectLazy()

    private val apiHeaders by lazy {
        headersBuilder()
            .add("Accept", "application/json")
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/api/releases?page=$page", apiHeaders)

    override fun popularMangaParse(response: Response): MangasPage {
        val data = json.decodeFromString<ReleasesResponse>(response.body.string())
        val mangas = data.releases
            .filter { it.manga != null }
            .map { release ->
                release.manga!!.toSManga()
            }
            .distinctBy { it.url }
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/api/releases?page=$page", apiHeaders)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.startsWith("ID:")) {
            val id = query.removePrefix("ID:").trim()
            return GET("$baseUrl/api/mangas/$id", apiHeaders)
        }

        val body = buildJsonObject {
            put("title", query)
            put("page", page.toString())
        }.toString().toRequestBody("application/json".toMediaType())

        return POST("$baseUrl/api/mangas/search", apiHeaders, body)
    }

    override fun searchMangaParse(response: Response): MangasPage {
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

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = manga.url.substringAfterLast("/")
        return GET("$baseUrl/api/mangas/$id", apiHeaders)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val data = json.decodeFromString<MangaResponse>(response.body.string())
        return data.mangaData?.toSManga() ?: throw Exception("Manga not found")
    }

    override fun chapterListRequest(manga: SManga): Request {
        val id = manga.url.substringAfterLast("/")
        return GET("$baseUrl/api/mangas/$id/releases?page=1", apiHeaders)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
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

    override fun pageListRequest(chapter: SChapter): Request {
        val releaseId = chapter.url.substringAfterLast("/")
        return GET("$baseUrl/api/releases/$releaseId", apiHeaders)
    }

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.encodedPath))

    override suspend fun fetchPageText(page: Page): String {
        val releaseId = page.url.substringAfterLast("/").substringBefore("?")
        val mangaId = page.url.substringAfter("/mangas/").substringBefore("/")

        val releaseResponse = client.newCall(GET("$baseUrl/api/releases/$releaseId", apiHeaders)).execute()
        val releaseBody = releaseResponse.body.string()

        val downloadResponse = client.newCall(
            POST(
                "$baseUrl/api/releases/$releaseId/download",
                apiHeaders,
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

        val releaseData = try {
            json.decodeFromString<ReleaseDto>(releaseBody)
        } catch (_: Exception) {
            null
        }

        return buildString {
            append("<h2>${releaseData?.manga?.title ?: ""}</h2>")
            append("<p>الفصل ${releaseData?.chapter?.toInt() ?: 0}</p>")
            append("<p>${releaseData?.manga?.summary ?: ""}</p>")
        }
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun getFilterList(): FilterList = FilterList(
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

    private fun MangaDto.toSManga(): SManga = SManga.create().apply {
        url = "/mangas/$id"
        title = arabic_title?.takeIf { it.isNotBlank() } ?: this@toSManga.title
        thumbnail_url = "https://golden.rest/uploads/$cover"
        description = summary
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

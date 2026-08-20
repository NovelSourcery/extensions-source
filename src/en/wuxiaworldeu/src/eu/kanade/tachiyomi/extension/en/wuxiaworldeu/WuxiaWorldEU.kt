package eu.kanade.tachiyomi.novelextension.en.wuxiaworldeu

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response

/**
 * mirror https://readnovel.eu
 * WuxiaWorldEU (wuxiaworld.eu). A Django REST + Next.js site, unrelated to wuxiaworld.com or
 * wuxiaworld.site. Browse/search/details all come from the plain `/api/novels/` REST endpoint;
 * chapter content is `/api/getchapter/{chapterSlug}/`. There is no working sort parameter on
 * `/api/novels/` (every `ordering=` value tried returns the same alphabetical order), so popular
 * and latest both fall back to the same listing.
 */
@Source
abstract class WuxiaWorldEU :
    KeiSource(),
    NovelSource {

    override val isNovelSource = true

    private val json = Json { ignoreUnknownKeys = true }

    private val pageSize = 20

    // ======================== Browse / Search ========================

    override suspend fun getPopularManga(page: Int): MangasPage = parseNovelsList(client.get(novelsUrl(page), headers))

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseNovelsList(client.get(novelsUrl(page), headers))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = parseNovelsList(client.get(novelsUrl(page, query), headers))

    private fun novelsUrl(page: Int, query: String? = null): HttpUrl {
        val url = "$baseUrl/api/novels/".toHttpUrl().newBuilder()
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("offset", ((page - 1) * pageSize).toString())
        if (!query.isNullOrBlank()) url.addQueryParameter("search", query)
        return url.build()
    }

    private fun parseNovelsList(response: Response): MangasPage {
        val obj = json.parseToJsonElement(response.body.string()).jsonObject
        val results = obj["results"]?.jsonArray ?: return MangasPage(emptyList(), false)
        val mangas = results.mapNotNull { element ->
            try {
                val item = element.jsonObject
                val slug = item["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                SManga.create().apply {
                    title = item["name"]?.jsonPrimitive?.contentOrNull ?: slug
                    url = slug
                    thumbnail_url = item["image"]?.jsonPrimitive?.contentOrNull
                    description = item["description"]?.jsonPrimitive?.contentOrNull
                    genre = item["categories"]?.jsonArray
                        ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                        ?.joinToString()
                }
            } catch (e: Exception) {
                null
            }
        }
        return MangasPage(mangas, obj["next"]?.jsonPrimitive?.contentOrNull != null)
    }

    // ======================== Details ========================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novel/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.encodedPath.removePrefix("/novel/").trim('/')
        val tempManga = SManga.create().apply { this.url = slug }
        val response = client.get(mangaDetailsUrl(tempManga), headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        val data = json.parseToJsonElement(response.body.string()).jsonObject
        return parseMangaDetails(data).apply { this.url = slug }
    }

    private fun mangaDetailsUrl(manga: SManga): String = "$baseUrl/api/novels/${manga.url}/"

    private fun parseMangaDetails(data: JsonObject): SManga = SManga.create().apply {
        title = data["name"]!!.jsonPrimitive.content
        thumbnail_url = data["image"]?.jsonPrimitive?.contentOrNull
            ?: data["original_image"]?.jsonPrimitive?.contentOrNull
        author = data["author"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
        genre = data["categories"]?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString()
        description = data["description"]?.jsonPrimitive?.contentOrNull
        status = when (data["status"]?.jsonPrimitive?.contentOrNull) {
            "OG" -> SManga.ONGOING
            "CD", "CO" -> SManga.COMPLETED
            "HI" -> SManga.ON_HIATUS
            "CA", "DR" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    // ======================== Chapters ========================
    // No working chapter-list endpoint was found (`/api/chapters` ignores every novel filter
    // param and just returns a global feed). Chapter pages are reliably `{slug}-{n}` for
    // n = 1..chapterCount though (verified against the last chapter's `nextChap: null`), so the
    // list is generated directly from the novel detail's chapter count instead of being scraped.
    // Details and chapters both come from this same endpoint - fetch it once.

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val data = json.parseToJsonElement(client.get(mangaDetailsUrl(manga), headers).body.string()).jsonObject

        val updatedManga = if (fetchDetails) parseMangaDetails(data) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(data) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseChapterList(data: JsonObject): List<SChapter> {
        val slug = data["slug"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val count = data["chapters"]?.jsonPrimitive?.intOrNull ?: return emptyList()
        if (count <= 0) return emptyList()

        return (1..count).map { n ->
            SChapter.create().apply {
                name = "Chapter $n"
                url = "$slug-$n"
                chapter_number = n.toFloat()
            }
        }.reversed()
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/chapter/${chapter.url}"

    // ======================== Content ========================
    // Single metadata page per chapter; the real fetch happens in fetchPageText.
    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val response = client.get("$baseUrl/api/getchapter/${page.url}/", headers)
        val data = json.parseToJsonElement(response.body.string()).jsonObject
        val text = data["text"]?.jsonPrimitive?.contentOrNull ?: return ""
        return text
            // The site's own data has this mojibake in place of apostrophes/quotes site-wide.
            .replace('�', '\'')
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("") { "<p>$it</p>" }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()
}

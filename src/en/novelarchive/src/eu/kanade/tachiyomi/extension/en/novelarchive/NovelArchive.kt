package eu.kanade.tachiyomi.novelextension.en.novelarchive

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
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class NovelArchive :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    // manga.url is stored as a bare opaque id; this template covers only the webview/deeplink
    // shape ("/novel/<id>") - the API endpoint uses a different prefix ("/api/novels/<id>") built
    // directly in buildMangaDetailsUrl, since SlugPath models one canonical resolve/slug pair.
    private val mangaPathTemplate: SlugPath = SlugPath("/novel/")

    private fun buildListUrl(page: Int, sort: String, query: String, filters: FilterList): HttpUrl {
        val url = "$baseUrl/api/novels".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", "24")
            .addQueryParameter("sort", sort)

        if (query.isNotBlank()) url.addQueryParameter("search", query)

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    val include = filter.state.filter { it.isIncluded() }.map { it.name }
                    val exclude = filter.state.filter { it.isExcluded() }.map { it.name }
                    if (include.isNotEmpty()) url.addQueryParameter("genres_include", include.joinToString())
                    if (exclude.isNotEmpty()) url.addQueryParameter("genres_exclude", exclude.joinToString())
                }
                is StatusFilter -> url.addQueryParameter("status", filter.toUriPart())
                is AiFilter -> url.addQueryParameter("ai_generated", filter.toUriPart())
                else -> {}
            }
        }
        return url.build()
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val result = client.get(buildListUrl(page, "popular", "", FilterList()), headers).parseAs<NovelListResponse>()
        return MangasPage(result.novels.map { it.toSManga() }, result.pagination.hasNext)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val result = client.get(buildListUrl(page, "recent", "", FilterList()), headers).parseAs<NovelListResponse>()
        return MangasPage(result.novels.map { it.toSManga() }, result.pagination.hasNext)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart() ?: "recent"
        val result = client.get(buildListUrl(page, sort, query, filters), headers).parseAs<NovelListResponse>()
        return MangasPage(result.novels.map { it.toSManga() }, result.pagination.hasNext)
    }

    private fun absoluteCover(url: String?): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("http") -> url
        else -> baseUrl + url
    }

    private fun cleanGenres(genres: String?): String = genres
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() && it.lowercase() !in GENRE_DENYLIST }
        ?.distinctBy { it.lowercase() }
        ?.joinToString()
        .orEmpty()

    private fun NovelDto.toSManga() = SManga.create().apply {
        title = this@toSManga.title
        url = id
        author = this@toSManga.author
        thumbnail_url = absoluteCover(coverUrl)
        genre = cleanGenres(genres)
    }

    override fun getMangaUrl(manga: SManga): String = mangaPathTemplate.absolute(baseUrl, manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val id = mangaPathTemplate.slug(url.encodedPath).trim('/')
        if (id.isBlank()) return null
        val response = client.get("$baseUrl/api/novels/$id", headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        return response.parseAs<NovelDetailResponse>().novel.toSManga()
    }

    private fun NovelDetailDto.toSManga() = SManga.create().apply {
        title = this@toSManga.title
        url = id
        author = this@toSManga.author
        thumbnail_url = absoluteCover(coverUrl)
        genre = cleanGenres(genres)
        status = when {
            releaseStatus?.contains("complete", ignoreCase = true) == true -> SManga.COMPLETED
            ongoing.equals("ongoing", ignoreCase = true) -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
        description = buildString {
            rating?.takeIf { it > 0 }?.let {
                append("Rating: $it")
                ratingCount?.let { c -> append(" ($c)") }
                append("\n")
            }
            views?.takeIf { it.isNotBlank() }?.let { append("Views: $it\n") }
            totalChapters?.let { append("Chapters: $it\n") }
            val desc = this@toSManga.description?.trim()
            if (!desc.isNullOrBlank()) {
                if (isNotEmpty()) append("\n")
                append(desc)
            }
        }.trim()
    }

    private fun buildMangaDetailsUrl(manga: SManga): String = "$baseUrl/api/novels/${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Details and the chapter list both live in the same API response - fetch it once.
        val dto = client.get(buildMangaDetailsUrl(manga), headers).parseAs<NovelDetailResponse>().novel

        val updatedManga = if (fetchDetails) dto.toSManga() else manga

        val updatedChapters = if (fetchChapters) {
            val novelId = dto.id
            dto.chapterNames.mapIndexed { index, chapterName ->
                val number = index + 1
                SChapter.create().apply {
                    name = chapterName
                    url = "$novelId/$number"
                    chapter_number = number.toFloat()
                }
            }.reversed()
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val (novelId, number) = chapter.url.split("/")
        return "$baseUrl/novel/$novelId/chapters/$number"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val (novelId, number) = page.url.split("/")
        val response = client.get("$baseUrl/api/novels/$novelId/chapters/$number", headers)
        val content = response.parseAs<ChapterContentResponse>().chapter.content.orEmpty()
        return content.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("") { "<p>$it</p>" }
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        StatusFilter(),
        AiFilter(),
        GenreFilter(),
    )

    private class SortFilter :
        Filter.Select<String>(
            "Sort by",
            arrayOf("Recent", "Popular", "Rating", "Views"),
        ) {
        fun toUriPart() = arrayOf("recent", "popular", "rating", "views")[state]
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("All", "Ongoing", "Completed"),
        ) {
        fun toUriPart() = arrayOf("all", "ongoing", "completed")[state]
    }

    private class AiFilter :
        Filter.Select<String>(
            "AI generated",
            arrayOf("Include", "Exclude", "Only"),
        ) {
        fun toUriPart() = arrayOf("include", "exclude", "only")[state]
    }

    private class Genre(name: String) : Filter.TriState(name)
    private class GenreFilter : Filter.Group<Genre>("Genres", GENRES.map { Genre(it) })

    companion object {
        private val GENRE_DENYLIST = setOf(
            "browse",
            "latest novels",
            "completed novels",
            "ongoing novels",
            "all novels",
        )
        private val GENRES = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Gender Bender",
            "Harem", "Historical", "Horror", "Josei", "Martial Arts", "Mature", "Mecha",
            "Mystery", "Psychological", "Romance", "School Life", "Sci-fi", "Seinen", "Shoujo",
            "Shounen", "Slice of Life", "Sports", "Supernatural", "Tragedy", "Video Games", "Xianxia", "Xuanhuan",
        )
    }
}

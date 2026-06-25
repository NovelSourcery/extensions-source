package eu.kanade.tachiyomi.novelextension.en.novelarchive

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class NovelArchive :
    HttpSource(),
    NovelSource {

    override val name = "Novel Archive"
    override val baseUrl = "https://novelarchive.cc"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.client

    private fun listRequest(page: Int, sort: String, query: String, filters: FilterList): Request {
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
        return GET(url.build(), headers)
    }

    override fun popularMangaRequest(page: Int): Request = listRequest(page, "popular", "", FilterList())

    override fun popularMangaParse(response: Response): MangasPage {
        val result = response.parseAs<NovelListResponse>()
        return MangasPage(result.novels.map { it.toSManga() }, result.pagination.hasNext)
    }

    override fun latestUpdatesRequest(page: Int): Request = listRequest(page, "recent", "", FilterList())

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart() ?: "recent"
        return listRequest(page, sort, query, filters)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

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

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novel/${manga.url}"

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/api/novels/${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val dto = response.parseAs<NovelDetailResponse>().novel
        return SManga.create().apply {
            title = dto.title
            url = dto.id
            author = dto.author
            thumbnail_url = absoluteCover(dto.coverUrl)
            genre = cleanGenres(dto.genres)
            status = when {
                dto.releaseStatus?.contains("complete", ignoreCase = true) == true -> SManga.COMPLETED
                dto.ongoing.equals("ongoing", ignoreCase = true) -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
            description = buildString {
                dto.rating?.takeIf { it > 0 }?.let {
                    append("Rating: $it")
                    dto.ratingCount?.let { c -> append(" ($c)") }
                    append("\n")
                }
                dto.views?.takeIf { it.isNotBlank() }?.let { append("Views: $it\n") }
                dto.totalChapters?.let { append("Chapters: $it\n") }
                val desc = dto.description?.trim()
                if (!desc.isNullOrBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(desc)
                }
            }.trim()
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl/api/novels/${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val dto = response.parseAs<NovelDetailResponse>().novel
        val novelId = dto.id
        return dto.chapterNames.mapIndexed { index, chapterName ->
            val number = index + 1
            SChapter.create().apply {
                name = chapterName
                url = "$novelId/$number"
                chapter_number = number.toFloat()
            }
        }.reversed()
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val (novelId, number) = chapter.url.split("/")
        return GET("$baseUrl/api/novels/$novelId/chapters/$number", headers)
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val (novelId, number) = chapter.url.split("/")
        return "$baseUrl/novel/$novelId/chapters/$number"
    }

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

    override suspend fun fetchPageText(page: Page): String {
        val (novelId, number) = page.url.split("/")
        val response = client.newCall(GET("$baseUrl/api/novels/$novelId/chapters/$number", headers)).execute()
        val content = response.parseAs<ChapterContentResponse>().chapter.content.orEmpty()
        return content.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("") { "<p>$it</p>" }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    override fun getFilterList() = FilterList(
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

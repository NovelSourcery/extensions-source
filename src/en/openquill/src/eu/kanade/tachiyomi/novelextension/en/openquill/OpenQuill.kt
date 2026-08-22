package eu.kanade.tachiyomi.novelextension.en.openquill

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
import keiyoushi.utils.formattedText
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import kotlin.time.Instant

/**
 * OpenQuill's browse page is fully client-rendered with no server-side data, but the same-origin
 * `/api/stories` route it calls is open and returns the full catalog (a small site, ~50 stories)
 * in one page - popular/latest/search all filter that single fetch locally rather than relying on
 * unconfirmed server-side query params.
 */
@Source
abstract class OpenQuill :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    // ======================== Popular / Latest / Search ========================

    override suspend fun getPopularManga(page: Int): MangasPage = paginate(fetchAllStories().sortedByDescending { it.realViewCount }, page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = paginate(fetchAllStories().sortedByDescending { it.lastChapterPublishedAt?.let { d -> Instant.parseOrNull(d) } }, page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.toUriPart().orEmpty()
        var stories = fetchAllStories()
        if (query.isNotBlank()) stories = stories.filter { it.title.contains(query, ignoreCase = true) }
        if (genre.isNotEmpty()) stories = stories.filter { story -> story.genres.any { it.genre.name == genre } }
        return paginate(stories, page)
    }

    private suspend fun fetchAllStories(): List<StoryDto> = client.get("$baseUrl/api/stories?page=1&limit=$MAX_STORIES", headers).parseAs<StoryListResponse>().stories

    private fun paginate(stories: List<StoryDto>, page: Int): MangasPage {
        val from = (page - 1) * PAGE_SIZE
        val mangas = stories.drop(from).take(PAGE_SIZE).map { it.toSManga() }
        return MangasPage(mangas, from + PAGE_SIZE < stories.size)
    }

    // ======================== Details + Chapters ========================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/stories/${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Details and the full chapter list both live in the same story API response.
        val story = client.get("$baseUrl/api/stories/${manga.url}", headers).parseAs<StoryDto>()

        val updatedManga = if (fetchDetails) story.toSManga() else manga
        val updatedChapters = if (fetchChapters) {
            story.chapters.filter { it.isPublished }
                .sortedByDescending { it.chapterNumber }
                .map { it.toSChapter(story.slug) }
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.pathSegments.getOrNull(1) ?: return null
        val response = client.get("$baseUrl/api/stories/$slug", headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        return response.parseAs<StoryDto>().toSManga()
    }

    // ======================== Pages ========================

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val match = CHAPTER_URL_REGEX.find(page.url) ?: throw Exception("Malformed chapter url: ${page.url}")
        val (slug, chapterNumber) = match.destructured

        val response = client.get("$baseUrl/api/stories/$slug/chapters/$chapterNumber", headers)
            .parseAs<ChapterContentResponse>()
        val doc = Jsoup.parseBodyFragment(response.chapter.content)
        doc.selectFirst("h1")?.remove()
        return doc.body().html()
    }

    // ======================== Filters ========================

    override fun getFilterList(data: JsonElement?) = FilterList(GenreFilter())

    private class GenreFilter :
        Filter.Select<String>(
            "Genre",
            arrayOf(
                "All", "Action", "Adult", "Adventure", "Boys Love", "Comedy", "Drama", "Ecchi", "Fanfiction",
                "Fantasy", "Gender Bender", "Girls Love", "Harem", "Historical", "Horror", "Isekai", "Josei",
                "LitRPG", "Martial Arts", "Mature", "Mecha", "Mystery", "Psychological", "Romance", "School Life",
                "Sci-fi", "Seinen", "Slice of Life", "Smut", "Sports", "Supernatural", "Tragedy",
            ),
        ) {
        fun toUriPart() = if (state == 0) "" else values[state]
    }

    // ======================== DTOs ========================

    @Serializable
    private class StoryListResponse(val stories: List<StoryDto> = emptyList())

    @Serializable
    private class ChapterContentResponse(val chapter: ChapterContentDto)

    @Serializable
    private class ChapterContentDto(val content: String)

    @Serializable
    private class NameDto(val name: String)

    @Serializable
    private class GenreWrapper(val genre: NameDto)

    @Serializable
    private class AuthorDto(val username: String? = null)

    @Serializable
    private class ChapterDto(
        val chapterNumber: Int,
        val title: String,
        val isPublished: Boolean = true,
        val createdAt: String? = null,
    ) {
        fun toSChapter(storySlug: String): SChapter = SChapter.create().apply {
            val chapterTitle = title
            url = "/stories/$storySlug/chapter/$chapterNumber"
            name = chapterTitle
            chapter_number = chapterNumber.toFloat()
            date_upload = createdAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() } ?: 0L
        }
    }

    @Serializable
    private class StoryDto(
        val title: String,
        val slug: String,
        val description: String? = null,
        val coverImageUrl: String? = null,
        val status: String? = null,
        val author: AuthorDto? = null,
        val genres: List<GenreWrapper> = emptyList(),
        val chapters: List<ChapterDto> = emptyList(),
        val realViewCount: Int = 0,
        val lastChapterPublishedAt: String? = null,
    ) {
        fun toSManga(): SManga {
            val storyTitle = title
            val storyAuthor = author?.username
            val storyStatus = status
            val storyDescription = description
            val storyGenres = genres

            return SManga.create().apply {
                url = slug
                title = storyTitle
                author = storyAuthor
                thumbnail_url = coverImageUrl
                description = storyDescription?.let { html -> Jsoup.parseBodyFragment(html).body().formattedText() }
                genre = storyGenres.map { it.genre.name }.distinct().joinToString()
                status = when (storyStatus) {
                    "COMPLETED" -> SManga.COMPLETED
                    "ONGOING" -> SManga.ONGOING
                    "HIATUS" -> SManga.ON_HIATUS
                    "CANCELLED", "DROPPED" -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 20
        private const val MAX_STORIES = 500
        private val CHAPTER_URL_REGEX = Regex("""/stories/([^/]+)/chapter/(\d+)""")
    }
}

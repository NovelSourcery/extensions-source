package eu.kanade.tachiyomi.novelextension.en.botitranslation

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
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
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.Jsoup

/**
 * botitranslation.com is a pure client-side SPA; all content comes from the shared white-label
 * "StoryWave" backend it calls internally (`api.mystorywave.com`), which is open and
 * unauthenticated. [SManga.url]/[SChapter.url] store the bare numeric API id - the site's own
 * pages are id-only too (`/book/{id}`, `/chapter/{id}`), so no slug bookkeeping is needed.
 */
@Source
abstract class BotiTranslation :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val supportsLatest = false

    private val preferences by getPreferencesLazy()

    // ======================== Popular / Search ========================

    override suspend fun getPopularManga(page: Int): MangasPage = search(page, "", "")

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.toUriPart().orEmpty()
        return search(page, query, genre)
    }

    private suspend fun search(page: Int, query: String, genre: String): MangasPage {
        val url = if (query.isNotBlank()) {
            "$API_URL/books/search".toHttpUrl().newBuilder()
                .addQueryParameter("keyWord", query)
                .addQueryParameter("pageNumber", page.toString())
                .addQueryParameter("pageSize", PAGE_SIZE.toString())
                .build()
        } else {
            "$API_URL/books".toHttpUrl().newBuilder()
                .addQueryParameter("pageNumber", page.toString())
                .addQueryParameter("pageSize", PAGE_SIZE.toString())
                .apply { if (genre.isNotEmpty()) addQueryParameter("genre", genre) }
                .build()
        }
        return parseBookList(client.get(url, headers), page)
    }

    private fun parseBookList(response: Response, page: Int): MangasPage {
        val data = response.parseAs<PageResponse<BookDto>>().data
        val hasNextPage = page * PAGE_SIZE < data.totalCount
        return MangasPage(data.list.map { it.toSManga() }, hasNextPage)
    }

    // ======================== Details + Chapters ========================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/book/${manga.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = manga.url

        val updatedManga = if (fetchDetails) {
            client.get("$API_URL/books/$id", headers).parseAs<SingleResponse<BookDto>>().data.toSManga()
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)
            val allChapters = mutableListOf<ChapterDto>()
            var page = 1
            while (true) {
                val url = "$API_URL/chapters/page".toHttpUrl().newBuilder()
                    .addQueryParameter("bookId", id)
                    .addQueryParameter("pageNumber", page.toString())
                    .addQueryParameter("pageSize", CHAPTER_PAGE_SIZE.toString())
                    .addQueryParameter("sortDirection", "ASC")
                    .build()
                val data = client.get(url, headers).parseAs<PageResponse<ChapterDto>>().data
                allChapters += data.list
                if (page * CHAPTER_PAGE_SIZE >= data.totalCount || data.list.isEmpty()) break
                page++
            }
            allChapters
                .mapNotNull { it.toSChapter(showLocked) }
                .reversed()
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val id = url.pathSegments.lastOrNull { it.isNotEmpty() }?.toIntOrNull() ?: return null
        val response = client.get("$API_URL/books/$id", headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        return response.parseAs<SingleResponse<BookDto>>().data.toSManga()
    }

    // ======================== Pages ========================

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/chapter/${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val chapter = client.get("$API_URL/chapters/${page.url}", headers).parseAs<SingleResponse<ChapterContentDto>>().data
        if (chapter.paywallStatus == "charge") {
            throw Exception("This chapter is locked (premium). Unlock it on the website, or disable \"Show locked chapters\".")
        }
        return Jsoup.parseBodyFragment(chapter.content.orEmpty()).body().html()
    }

    // ======================== Filters ========================

    override fun getFilterList(data: JsonElement?) = FilterList(GenreFilter())

    private class GenreFilter :
        Filter.Select<String>(
            "Genre",
            arrayOf(
                "All", "Fantasy", "Sci-fi", "Sports", "Urban", "Eastern Fantasy", "Horror & Thriller",
                "Video Game", "History", "War", "Urban Romance", "Fantasy Romance", "Historical Romance",
                "Teen", "LGBT+", "Others",
            ),
        ) {
        fun toUriPart() = when (state) {
            1 -> "1"
            2 -> "2"
            3 -> "3"
            4 -> "4"
            5 -> "5"
            6 -> "6"
            7 -> "7"
            8 -> "8"
            9 -> "9"
            10 -> "10"
            11 -> "11"
            12 -> "12"
            13 -> "13"
            14 -> "14"
            15 -> "16"
            else -> ""
        }
    }

    // ======================== Preferences ========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Include premium/locked chapters in the chapter list."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    // ======================== DTOs ========================

    @Serializable
    private class PageResponse<T>(val data: PageData<T>)

    @Serializable
    private class PageData<T>(val list: List<T> = emptyList(), val totalCount: Int = 0)

    @Serializable
    private class SingleResponse<T>(val data: T)

    @Serializable
    private class BookDto(
        val id: Int,
        val title: String,
        val authorPseudonym: String? = null,
        val genreName: String? = null,
        val tag: String? = null,
        val coverImgUrl: String? = null,
        val synopsis: String? = null,
    ) {
        fun toSManga(): SManga = SManga.create().apply {
            val bookTitle = this@BookDto.title
            val bookAuthor = authorPseudonym
            val bookSynopsis = synopsis

            url = id.toString()
            title = bookTitle
            author = bookAuthor
            thumbnail_url = coverImgUrl
            description = bookSynopsis
            val tags = tag?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            genre = (listOfNotNull(genreName) + tags).distinct().joinToString()
        }
    }

    @Serializable
    private class ChapterDto(
        val id: Int,
        val title: String,
        val chapterOrder: Int,
        val paywallStatus: String? = null,
        val publishTime: Long? = null,
    ) {
        fun toSChapter(showLocked: Boolean): SChapter? {
            val locked = paywallStatus == "charge"
            if (locked && !showLocked) return null
            val chapterTitle = title

            return SChapter.create().apply {
                url = id.toString()
                name = if (locked) "🔒 $chapterTitle" else chapterTitle
                chapter_number = chapterOrder.toFloat()
                date_upload = publishTime ?: 0L
            }
        }
    }

    @Serializable
    private class ChapterContentDto(val content: String? = null, val paywallStatus: String? = null)

    companion object {
        private const val API_URL = "https://api.mystorywave.com/story-wave-backend/api/v1/content"
        private const val PAGE_SIZE = 20
        private const val CHAPTER_PAGE_SIZE = 1000
        private const val PREF_SHOW_LOCKED = "pref_show_locked_chapters"
    }
}

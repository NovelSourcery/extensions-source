package eu.kanade.tachiyomi.extension.en.greenz

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
import keiyoushi.utils.setAltTitles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.Jsoup
import kotlin.time.Instant

/**
 * Frontend (greenz.com) is a client-rendered Next.js app with no server-rendered story data; all
 * content comes from the open, unauthenticated `admin.greenz.com` REST API it calls internally.
 * [SManga.url]/[SChapter.url] store the public "/novels/..." path with the numeric API id appended
 * as a URL fragment (never sent to the server) since the API needs the id but the site needs the slug.
 */
@Source
abstract class Greenz :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ======================== Popular / Latest / Search ========================

    override suspend fun getPopularManga(page: Int): MangasPage = parseNovelList(client.get("$API_URL/novels/trending?page=$page&limit=$PAGE_SIZE", headers))

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseNovelList(client.get("$API_URL/novels?page=$page&limit=$PAGE_SIZE", headers))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$API_URL/novels".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
        if (query.isNotBlank()) url.addQueryParameter("q", query)
        filters.filterIsInstance<StatusFilter>().firstOrNull()?.toUriPart()?.takeIf { it.isNotEmpty() }
            ?.let { url.addQueryParameter("status", it) }

        return parseNovelList(client.get(url.build(), headers))
    }

    private fun parseNovelList(response: Response): MangasPage {
        val page = response.parseAs<PageResponse<NovelDto>>().data
        return MangasPage(page.items.map { it.toSManga() }, page.meta.hasNextPage)
    }

    // ======================== Details + Chapters ========================

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url.substringBefore("#")

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = manga.url.substringAfter("#")
        val slug = manga.url.substringBefore("#").substringAfterLast("/")

        val updatedManga = if (fetchDetails) {
            client.get("$API_URL/novels/$id", headers).parseAs<SingleResponse<NovelDto>>().data.toSManga()
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)
            client.get("$API_URL/chapters?novelId=$id&limit=$CHAPTER_LIMIT", headers)
                .parseAs<PageResponse<ChapterDto>>().data.items
                .mapNotNull { it.toSChapter(slug, showLocked) }
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.pathSegments.lastOrNull { it.isNotEmpty() } ?: return null
        val response = client.get("$API_URL/novels?slug=$slug", headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        return response.parseAs<PageResponse<NovelDto>>().data.items.firstOrNull()?.toSManga()
    }

    // ======================== Pages ========================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url.substringBefore("#")

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val chapterId = page.url.substringAfter("#")
        val html = client.get("$API_URL/chapters/$chapterId", headers)
            .parseAs<SingleResponse<ChapterContentDto>>().data.content
            ?: throw Exception("This chapter is locked. Unlock it on the website, or disable \"Show locked chapters\".")

        // Chapter text is exported from Google Docs with heavy inline styling (explicit
        // black text/colors) that would fight the reader's own theme - strip it down to bare
        // paragraphs, keeping blank <p> as <br> to preserve the original line spacing.
        return Jsoup.parseBodyFragment(html).select("p").joinToString("") { p ->
            val text = p.text()
            if (text.isEmpty()) "<br>" else "<p>$text</p>"
        }
    }

    // ======================== Filters ========================

    override fun getFilterList(data: JsonElement?) = FilterList(StatusFilter())

    private class StatusFilter : Filter.Select<String>("Status", arrayOf("All", "Ongoing", "Completed")) {
        fun toUriPart() = when (state) {
            1 -> "ONGOING"
            2 -> "COMPLETED"
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
    private class PageData<T>(val items: List<T>, val meta: PageMeta)

    @Serializable
    private class PageMeta(val hasNextPage: Boolean)

    @Serializable
    private class SingleResponse<T>(val data: T)

    @Serializable
    private class NameDto(val name: String)

    @Serializable
    private class CoverDto(val url: String)

    @Serializable
    private class NovelDto(
        val id: Int,
        val name: String,
        val slug: String,
        val status: String,
        val author: String? = null,
        val description: String? = null,
        val alternativeNames: String? = null,
        val genres: List<NameDto> = emptyList(),
        val tags: List<NameDto> = emptyList(),
        val cover: CoverDto? = null,
    ) {
        fun toSManga(): SManga = SManga.create().apply {
            val novelName = this@NovelDto.name
            val novelAuthor = this@NovelDto.author
            val novelDescription = this@NovelDto.description
            val novelStatus = this@NovelDto.status

            url = "/novels/$slug#$id"
            title = novelName
            thumbnail_url = cover?.let { API_HOST + it.url }
            author = novelAuthor
            description = novelDescription
            genre = (genres.map { it.name } + tags.map { it.name }).distinct().filter { it.isNotEmpty() }.joinToString()
            status = when (novelStatus) {
                "ONGOING" -> SManga.ONGOING
                "COMPLETED" -> SManga.COMPLETED
                "HIATUS" -> SManga.ON_HIATUS
                "DROPPED", "CANCELLED" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }

            if (!alternativeNames.isNullOrBlank()) {
                setAltTitles(listOf(alternativeNames))
                description = buildString {
                    append(description.orEmpty())
                    append("\n\nAlternative Titles:\n")
                    append("• $alternativeNames")
                }.trim()
            }
        }
    }

    @Serializable
    private class ChapterDto(
        val id: Int,
        val name: String,
        val slug: String,
        val isPremium: Boolean,
        val chapterNumber: String,
        val publishedAt: String,
    ) {
        fun toSChapter(novelSlug: String, showLocked: Boolean): SChapter? {
            if (isPremium && !showLocked) return null
            val chapterTitle = name

            return SChapter.create().apply {
                url = "/novels/$novelSlug/$slug#$id"
                this.name = if (isPremium) "🔒 $chapterTitle" else chapterTitle
                chapter_number = chapterNumber.toFloatOrNull() ?: -1f
                date_upload = Instant.parseOrNull(publishedAt)?.toEpochMilliseconds() ?: 0L
            }
        }
    }

    @Serializable
    private class ChapterContentDto(val content: String? = null)

    companion object {
        private const val API_HOST = "https://admin.greenz.com"
        private const val API_URL = "$API_HOST/api"
        private const val PAGE_SIZE = 20
        private const val CHAPTER_LIMIT = 1000
        private const val PREF_SHOW_LOCKED = "pref_show_locked_chapters"
    }
}

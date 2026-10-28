package eu.kanade.tachiyomi.novelextension.en.jadescrolls

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.SourceTracker
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
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.stringOrNull
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLDecoder

@Source
abstract class JadeScrolls :
    KeiSource(),
    NovelSource,
    ConfigurableSource,
    SourceTracker {

    private val apiUrl by lazy { baseUrl.replace("://", "://api.") + "/api" }

    private val preferences by getPreferencesLazy()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList())

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$apiUrl/novels/recently-updated".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .build()
        return client.get(url).parseAs<NovelListDto>().toMangasPage()
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>() ?: SortFilter()
        val url = "$apiUrl/novels/list".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", PAGE_SIZE.toString())
            addQueryParameter("sortBy", sort.toUriPart())
            addQueryParameter("sortOrder", sort.order())
            if (query.isNotBlank()) addQueryParameter("q", query.trim())
            filters.forEach { filter ->
                val param = when (filter) {
                    is GenreFilter -> "genre"
                    is LeadFilter -> "novelLead"
                    is TypeFilter -> "type"
                    is StatusFilter -> "releaseStatus"
                    else -> return@forEach
                }
                (filter as UriPartFilter).toUriPart().takeIf { it.isNotEmpty() }?.let { addQueryParameter(param, it) }
            }
        }.build()
        return client.get(url).parseAs<NovelListDto>().toMangasPage()
    }

    private fun NovelListDto.toMangasPage() = MangasPage(data.map { it.toSManga() }, meta.hasNextPage())

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != "novel") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return fetchNovel(slug).toSMangaDetails()
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novel/${manga.url}"

    private suspend fun fetchNovel(slug: String): NovelDto = client.get("$apiUrl/novels?slug=$slug").parseAs()

    private suspend fun novelId(manga: SManga): String = manga.memo[NovelDto.ID_KEY]?.stringOrNull ?: fetchNovel(manga.url).id

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // The chapter list endpoint needs the novel id, which the details response also provides
        val details = if (fetchDetails || manga.memo[NovelDto.ID_KEY] == null) fetchNovel(manga.url) else null
        val updatedManga = if (fetchDetails) details!!.toSMangaDetails() else manga
        val updatedChapters = if (fetchChapters) fetchChapterList(manga.url, details?.id ?: novelId(manga)) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private suspend fun fetchChapterList(slug: String, id: String): List<SChapter> {
        val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)
        val chapters = mutableListOf<ChapterDto>()
        var page = 1
        do {
            val url = "$apiUrl/novels-chapter/$id/chapters/list".toHttpUrl().newBuilder()
                .addQueryParameter("limit", CHAPTER_PAGE_SIZE.toString())
                .addQueryParameter("page", page.toString())
                .addQueryParameter("status", "PUBLISHED")
                .addQueryParameter("isDeleted", "false")
                .addQueryParameter("sortOrder", "desc")
                .build()
            val result = client.get(url).parseAs<ChapterListDto>()
            chapters += result.data
            page++
        } while (result.meta.hasNextPage())

        return chapters
            .filter { showLocked || !it.isLocked }
            .map { it.toSChapter(slug) }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/novel/${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val (novelSlug, chapterSlug) = page.url.split("/", limit = 2)
        val chapter = client.get("$apiUrl/novels-chapter/$novelSlug/chapters/$chapterSlug").parseAs<ChapterContentDto>()
        if (chapter.userMetadata?.hasAccess == false) {
            throw Exception("This chapter is locked. Log in via WebView with an account that has access.")
        }
        val body = Jsoup.parseBodyFragment(chapter.content.orEmpty()).body()
        // Paragraph spacing is done with empty <p><br></p> lines
        body.select("p").filter { !it.hasText() && it.select("img").isEmpty() }.forEach { it.remove() }
        if (!body.hasText()) throw Exception("Chapter has no content")

        val note = chapter.authorNote?.takeIf { it.isNotBlank() }
        return if (note == null) body.html() else "${body.html()}<hr><p><strong>Author note:</strong></p><p>$note</p>"
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$apiUrl/public/genres").parseAs<List<GenreDto>>().toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<List<GenreDto>>()
        return FilterList(
            listOfNotNull(
                genres?.let(::GenreFilter) ?: Filter.Header("Reset filters to load genres"),
                LeadFilter(),
                TypeFilter(),
                StatusFilter(),
                SortFilter(),
            ),
        )
    }

    override val supportsChapterTracking = false

    override val supportsFavoritesTracking: Boolean
        get() = preferences.getBoolean(PREF_SYNC_BOOKMARKS, false)

    override suspend fun onFavorited(manga: SManga, categories: List<String>) = syncBookmark(manga, "BOOKMARK")

    override suspend fun onUnfavorited(manga: SManga, categories: List<String>) = syncBookmark(manga, "UNBOOKMARK")

    private suspend fun syncBookmark(manga: SManga, action: String) {
        if (!supportsFavoritesTracking) return
        // The site mirrors its XSRF-TOKEN cookie into this header on every API call
        val csrf = client.cookieJar.loadForRequest(apiUrl.toHttpUrl()).firstOrNull { it.name == "XSRF-TOKEN" }?.value
        val requestHeaders = headers.newBuilder().apply {
            csrf?.let { set("X-CSRF-Token", URLDecoder.decode(it, "UTF-8")) }
        }.build()
        client.post("$apiUrl/novels/${novelId(manga)}/user-interaction", requestHeaders, InteractionDto(action).toJsonRequestBody()).close()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Include premium chapters you don't have access to in the chapter list (marked with 🔒)."
            setDefaultValue(false)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SYNC_BOOKMARKS
            title = "Sync library to $name bookmarks"
            summary = "Bookmark/unbookmark novels on $name when you add or remove them from your library. Requires being logged in via WebView."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PAGE_SIZE = 24
        private const val CHAPTER_PAGE_SIZE = 500
        private const val PREF_SHOW_LOCKED = "show_locked_chapters"
        private const val PREF_SYNC_BOOKMARKS = "sync_bookmarks"
    }
}

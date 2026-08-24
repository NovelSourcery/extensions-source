package eu.kanade.tachiyomi.novelextension.en.wattpad

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class Wattpad :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "Wattpad"
    override val baseUrl = "https://www.wattpad.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val json = Json { ignoreUnknownKeys = true }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val includeMature: Boolean
        get() = preferences.getBoolean(PREF_MATURE, false)

    private val excludeLocked: Boolean
        get() = preferences.getBoolean(PREF_EXCLUDE_LOCKED, false)

    private val apiHeaders: Headers
        get() = headersBuilder().add("Referer", "$baseUrl/").build()

    // region Browse (Popular / Latest)

    override fun popularMangaRequest(page: Int): Request = browseRequest("hot", page)

    override fun latestUpdatesRequest(page: Int): Request = browseRequest("new", page)

    private fun browseRequest(filter: String, page: Int, category: Int = 0): Request {
        val offset = (page - 1) * LIMIT
        val mature = if (includeMature) 1 else 0
        val categoryParam = if (category > 0) "&category=$category" else ""
        return GET(
            "$baseUrl/v4/stories?fields=stories(id,title,cover,url),total,nextUrl" +
                "&filter=$filter&language=1&mature=$mature$categoryParam&limit=$LIMIT&offset=$offset",
            apiHeaders,
        )
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = json.decodeFromString<StoriesResponse>(response.body.string())
        val mangas = result.stories.map { it.toSManga() }
        return MangasPage(mangas, result.nextUrl != null)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // endregion

    // region Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val completed = filters.filterIsInstance<StatusFilter>().firstOrNull()?.state == 1

        // No text query: browse the catalog by category/sort instead of hitting the search endpoint.
        if (query.isBlank()) {
            val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.selectedId ?: 0
            val sort = if (completed) "complete" else filters.filterIsInstance<SortFilter>().firstOrNull()?.value ?: "hot"
            return browseRequest(sort, page, category)
        }

        val offset = (page - 1) * LIMIT
        val statusParam = if (completed) "&filter=complete" else ""
        val q = URLEncoder.encode(query, "UTF-8")
        return GET(
            "$baseUrl/v4/search/stories?query=$q$statusParam&free=1" +
                "&fields=stories(title,cover,url),nextUrl&limit=$LIMIT&mature=true&offset=$offset",
            apiHeaders,
        )
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // endregion

    // region Details + Chapters

    override fun mangaDetailsRequest(manga: SManga): Request = storyInfoRequest(manga.url)

    override fun chapterListRequest(manga: SManga): Request = storyInfoRequest(manga.url)

    // Details/chapters come from the api/v3 endpoint, but "open in browser" must point at the
    // human story/part page, not the API URL that getMangaUrl/getChapterUrl default to.
    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter): String {
        val partId = PART_ID_REGEX.find(chapter.url)?.groupValues?.get(1)
        return if (partId != null) "$baseUrl/$partId" else baseUrl
    }

    private fun storyInfoRequest(mangaUrl: String): Request {
        val id = storyId(mangaUrl)
        return GET(
            "$baseUrl/api/v3/stories/$id?fields=id,title,description,cover,completed," +
                "isPaywalled,user(name,fullname),tags,parts(id,title,createDate,restricted)",
            apiHeaders,
        )
    }

    private fun storyId(mangaUrl: String): String = STORY_ID_REGEX.find(mangaUrl)?.groupValues?.get(1)
        ?: throw Exception("Could not resolve Wattpad story id from $mangaUrl")

    override fun mangaDetailsParse(response: Response): SManga {
        val story = json.decodeFromString<StoryDetails>(response.body.string())
        return SManga.create().apply {
            title = story.title
            thumbnail_url = story.cover
            author = story.user?.fullname?.ifBlank { story.user.name } ?: story.user?.name
            genre = story.tags.joinToString()
            status = if (story.completed) SManga.COMPLETED else SManga.ONGOING
            description = buildString {
                if (story.isPaywalled) append("!! Contains Paid Chapters !!\n\n")
                append(story.description)
            }.trim()
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val story = json.decodeFromString<StoryDetails>(response.body.string())
        return story.parts.mapIndexedNotNull { i, part ->
            if (excludeLocked && part.restricted) return@mapIndexedNotNull null
            SChapter.create().apply {
                name = if (part.restricted) "🔒 ${part.title}" else part.title
                url = "/apiv2/storytext?id=${part.id}&include_paragraph_id=1"
                chapter_number = (i + 1).toFloat()
                date_upload = parseDate(part.createDate)
            }
        }.reversed()
    }

    private fun parseDate(dateStr: String): Long = try {
        DATE_FORMAT.parse(dateStr)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }

    // endregion

    // region Pages

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) page.url else baseUrl + page.url
        val html = client.newCall(GET(url, apiHeaders)).execute().body.string()
        if (html.trimStart().startsWith("{\"result\":\"ERROR\"")) {
            return "<p>This chapter is locked or unavailable.</p>"
        }
        return html
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // endregion

    // region Helpers

    private fun WattpadStory.toSManga() = SManga.create().apply {
        title = this@toSManga.title
        url = this@toSManga.url.removePrefix(baseUrl)
        thumbnail_url = cover
    }

    // endregion

    // region Filters

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Category and Sort apply only when the search box is empty"),
        CategoryFilter(),
        SortFilter(),
        StatusFilter(),
    )

    private class CategoryFilter : Filter.Select<String>("Category", CATEGORIES.map { it.first }.toTypedArray()) {
        val selectedId: Int get() = CATEGORIES[state].second
    }

    private class SortFilter : Filter.Select<String>("Sort by", arrayOf("Hot", "New")) {
        val value: String get() = if (state == 1) "new" else "hot"
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Status (completed forces search/browse to finished stories)",
            arrayOf("All", "Completed"),
        )

    // endregion

    // region Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        CheckBoxPreference(screen.context).apply {
            key = PREF_MATURE
            title = "Show mature stories"
            summary = "Include 18+ stories in Popular and Latest"
            setDefaultValue(false)
        }.also(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = PREF_EXCLUDE_LOCKED
            title = "Exclude locked chapters"
            summary = "Hide paid chapters from the chapter list (locked chapters are marked with 🔒)"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    // endregion

    // region Data classes

    @Serializable
    data class StoriesResponse(
        val stories: List<WattpadStory> = emptyList(),
        val nextUrl: String? = null,
    )

    @Serializable
    data class WattpadStory(
        val title: String = "",
        val url: String = "",
        val cover: String = "",
    )

    @Serializable
    data class StoryDetails(
        val title: String = "",
        val description: String = "",
        val cover: String = "",
        val completed: Boolean = false,
        val isPaywalled: Boolean = false,
        val user: WattpadUser? = null,
        val tags: List<String> = emptyList(),
        val parts: List<WattpadPart> = emptyList(),
    )

    @Serializable
    data class WattpadUser(
        val name: String = "",
        val fullname: String = "",
    )

    @Serializable
    data class WattpadPart(
        val id: Long = 0,
        val title: String = "",
        val createDate: String = "",
        val restricted: Boolean = false,
    )

    // endregion

    companion object {
        private const val LIMIT = 20
        private const val PREF_MATURE = "wattpad_show_mature"
        private const val PREF_EXCLUDE_LOCKED = "wattpad_exclude_locked"
        private val STORY_ID_REGEX = Regex("""/(?:story/)?(\d+)""")
        private val PART_ID_REGEX = Regex("""id=(\d+)""")
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        // id values from https://api.wattpad.com/v4/categories
        private val CATEGORIES = listOf(
            "Any" to 0,
            "Teen Fiction" to 1,
            "Poetry" to 2,
            "Fantasy" to 3,
            "Romance" to 4,
            "Science Fiction" to 5,
            "Fanfiction" to 6,
            "Humor" to 7,
            "Mystery / Thriller" to 8,
            "Horror" to 9,
            "Classics" to 10,
            "Adventure" to 11,
            "Paranormal" to 12,
            "Spiritual" to 13,
            "Action" to 14,
            "Non-Fiction" to 16,
            "Short Story" to 17,
            "Vampire" to 18,
            "Random" to 19,
            "General Fiction" to 21,
            "Werewolf" to 22,
            "Historical Fiction" to 23,
            "ChickLit" to 24,
        )
    }
}

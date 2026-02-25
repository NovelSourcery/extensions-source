package eu.kanade.tachiyomi.extension.all.lncrawler

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class LnCrawler :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "LnCrawler"
    override val baseUrl = "https://lncrawler.monster"
    private val apiUrl = "https://api.lncrawler.monster"
    override val lang = "all"
    override val supportsLatest = true
    override val isNovelSource = true

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client = network.cloudflareClient.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)

            response.headers("Set-Cookie").forEach { cookie ->
                if (cookie.startsWith("csrftoken=")) {
                    val token = cookie.substringAfter("csrftoken=").substringBefore(";")
                    preferences.edit().putString(PREF_CSRF_TOKEN, token).apply()
                }
            }

            response
        }
        .build()

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/novels/search/?page=$page&page_size=24&sort_by=popularity&sort_order=desc", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val searchResponse = json.decodeFromString<SearchResponse>(response.body.string())

        val novels = searchResponse.results.map { novel ->
            novelToSManga(novel)
        }

        val hasNextPage = searchResponse.currentPage < searchResponse.totalPages
        return MangasPage(novels, hasNextPage)
    }

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/novels/search/?page=$page&page_size=24&sort_by=last_updated&sort_order=desc", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = StringBuilder("$apiUrl/novels/search/?page=$page&page_size=24")

        if (query.isNotBlank()) {
            url.append("&query=${java.net.URLEncoder.encode(query, "UTF-8")}")
        }

        var sortBy = "popularity"
        var sortOrder = "desc"

        filters.forEach { filter ->
            when (filter) {
                is LanguageFilter -> {
                    if (filter.state > 0) {
                        url.append("&language=${filter.pairValues[filter.state].second}")
                    }
                }

                is SortFilter -> {
                    sortBy = filter.pairValues[filter.state].second
                }

                is SortOrderFilter -> {
                    sortOrder = filter.pairValues[filter.state].second
                }

                is MinRatingFilter -> {
                    if (filter.state.isNotBlank()) {
                        url.append("&min_rating=${filter.state}")
                    }
                }

                is TagFilter -> {
                    filter.state.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { tag ->
                            url.append("&tag=${java.net.URLEncoder.encode(tag, "UTF-8")}")
                        }
                }

                is ExcludeTagFilter -> {
                    filter.state.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { tag ->
                            url.append("&exclude_tag=${java.net.URLEncoder.encode(tag, "UTF-8")}")
                        }
                }

                is AuthorFilter -> {
                    filter.state.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach { author ->
                            url.append("&author=${java.net.URLEncoder.encode(author, "UTF-8")}")
                        }
                }

                else -> {}
            }
        }

        url.append("&sort_by=$sortBy&sort_order=$sortOrder")

        return GET(url.toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.removePrefix("/novels/").substringBefore("/")
        return GET("$apiUrl/novels/$slug/", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val novel = json.decodeFromString<NovelDetail>(response.body.string())

        val source = getPreferredSource(novel)

        return SManga.create().apply {
            url = "/novels/${novel.slug}/${source?.sourceSlug ?: ""}"
            title = novel.title
            thumbnail_url = source?.coverUrl ?: novel.preferedSource?.coverUrl
            author = source?.authors?.joinToString(", ") ?: novel.preferedSource?.authors?.joinToString(", ")

            description = buildString {
                val synopsis = source?.synopsis ?: novel.preferedSource?.synopsis ?: ""
                append(Jsoup.parse(synopsis).text())

                append("\n\n")
                append("Views: ${novel.totalViews} (Weekly: ${novel.weeklyViews})")
                novel.avgRating?.let { append("\nRating: $it (${novel.ratingCount} votes)") }
                append("\nSources: ${novel.sources?.size ?: 1}")

                source?.let { s ->
                    append("\n\nCurrent Source: ${s.sourceName}")
                    append("\nChapters: ${s.chaptersCount}")
                    append("\nVolumes: ${s.volumesCount}")
                }
            }

            genre = source?.tags?.joinToString(", ") ?: novel.preferedSource?.tags?.joinToString(", ") ?: ""

            status = SManga.UNKNOWN
        }
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request {
        val parts = manga.url.removePrefix("/novels/").split("/")
        val novelSlug = parts[0]
        val sourceSlug = parts.getOrNull(1)

        return if (sourceSlug.isNullOrEmpty()) {
            GET("$apiUrl/novels/$novelSlug/", headers)
        } else {
            GET("$apiUrl/novels/$novelSlug/$sourceSlug/chapters/?page=1&page_size=1000", headers)
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()

        return if (body.contains("\"chapters\":")) {
            val chapterResponse = json.decodeFromString<ChapterListResponse>(body)

            chapterResponse.chapters.map { chapter ->
                SChapter.create().apply {
                    url = "/novels/${chapterResponse.novelSlug}/${chapterResponse.sourceSlug}/chapter/${chapter.chapterId}"
                    name = buildString {
                        if (chapter.volumeTitle != null) {
                            append("[${chapter.volumeTitle}] ")
                        }
                        append(chapter.title)
                    }
                    chapter_number = chapter.chapterId.toFloat()
                }
            }.reversed()
        } else {
            val novel = json.decodeFromString<NovelDetail>(body)
            val source = getPreferredSource(novel)

            if (source != null) {
                val chaptersRequest = GET("$apiUrl/novels/${novel.slug}/${source.sourceSlug}/chapters/?page=1&page_size=1000", headers)
                val chaptersResponse = client.newCall(chaptersRequest).execute()
                return chapterListParse(chaptersResponse)
            }

            emptyList()
        }
    }

    // ======================== Pages ========================

    override fun pageListRequest(chapter: SChapter): Request = GET("$apiUrl${chapter.url.replace("/chapter/", "/chapter/")}/", headers)

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

    // ======================== Page Text (Novel) ========================

    override suspend fun fetchPageText(page: Page): String {
        val request = GET(page.url, headers)
        val response = client.newCall(request).execute()
        val chapter = json.decodeFromString<ChapterContent>(response.body.string())

        val content = StringBuilder()

        val document = Jsoup.parse(chapter.body)

        document.body().children().forEach { element ->
            when (element.tagName()) {
                "h1", "h2", "h3" -> {
                    content.append("<h2>${element.text()}</h2>\n")
                }

                "p" -> {
                    val img = element.selectFirst("img")
                    if (img != null) {
                        val imgSrc = img.attr("src")
                        val fullUrl = if (imgSrc.startsWith("images/") && chapter.imagesPath != null) {
                            "${chapter.imagesPath}/${imgSrc.removePrefix("images/")}"
                        } else if (imgSrc.startsWith("http")) {
                            imgSrc
                        } else {
                            "$apiUrl/$imgSrc"
                        }
                        content.append("<img src=\"$fullUrl\">\n")
                    } else {
                        val text = element.text()?.trim()
                        if (!text.isNullOrEmpty()) {
                            content.append("<p>$text</p>\n")
                        }
                    }
                }

                "img" -> {
                    val imgSrc = element.attr("src")
                    val fullUrl = if (imgSrc.startsWith("images/") && chapter.imagesPath != null) {
                        "${chapter.imagesPath}/${imgSrc.removePrefix("images/")}"
                    } else if (imgSrc.startsWith("http")) {
                        imgSrc
                    } else {
                        "$apiUrl/$imgSrc"
                    }
                    content.append("<img src=\"$fullUrl\">\n")
                }

                else -> {
                    val text = element.text()?.trim()
                    if (!text.isNullOrEmpty()) {
                        content.append("<p>$text</p>\n")
                    }
                }
            }
        }

        return content.toString()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // ======================== Filters ========================

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Language"),
        LanguageFilter("Language", languageOptions),
        Filter.Separator(),
        Filter.Header("Sorting"),
        SortFilter("Sort By", sortOptions),
        SortOrderFilter("Order", sortOrderOptions),
        Filter.Separator(),
        Filter.Header("Tags (comma-separated)"),
        TagFilter("Include Tags"),
        ExcludeTagFilter("Exclude Tags"),
        Filter.Separator(),
        MinRatingFilter("Minimum Rating (0-5)"),
        AuthorFilter("Authors (comma-separated)"),
    )

    class LanguageFilter(name: String, internal val pairValues: Array<Pair<String, String>>) : Filter.Select<String>(name, pairValues.map { it.first }.toTypedArray())

    class SortFilter(name: String, internal val pairValues: Array<Pair<String, String>>) : Filter.Select<String>(name, pairValues.map { it.first }.toTypedArray())

    class SortOrderFilter(name: String, internal val pairValues: Array<Pair<String, String>>) : Filter.Select<String>(name, pairValues.map { it.first }.toTypedArray())

    class TagFilter(name: String) : Filter.Text(name)
    class ExcludeTagFilter(name: String) : Filter.Text(name)
    class MinRatingFilter(name: String) : Filter.Text(name)
    class AuthorFilter(name: String) : Filter.Text(name)

    private val languageOptions = arrayOf(
        Pair("Any", ""),
        Pair("English", "en"),
        Pair("French", "fr"),
        Pair("Spanish", "es"),
        Pair("German", "de"),
        Pair("Italian", "it"),
        Pair("Japanese", "ja"),
        Pair("Korean", "ko"),
        Pair("Chinese", "zh"),
        Pair("Portuguese", "pt"),
        Pair("Russian", "ru"),
        Pair("Arabic", "ar"),
        Pair("Hindi", "hi"),
        Pair("Thai", "th"),
        Pair("Vietnamese", "vi"),
        Pair("Indonesian", "id"),
        Pair("Turkish", "tr"),
        Pair("Polish", "pl"),
        Pair("Dutch", "nl"),
        Pair("Swedish", "sv"),
    )

    private val sortOptions = arrayOf(
        Pair("Popularity (All-time)", "popularity"),
        Pair("Trending (Weekly)", "trending"),
        Pair("Rating", "rating"),
        Pair("Last Updated", "last_updated"),
        Pair("Date Added", "date_added"),
        Pair("Title", "title"),
    )

    private val sortOrderOptions = arrayOf(
        Pair("Descending", "desc"),
        Pair("Ascending", "asc"),
    )

    // ======================== Preferences ========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SOURCE_SELECTION
            title = "Source Selection"
            summary = "%s"
            entries = arrayOf("Preferred (API default)", "Most Chapters")
            entryValues = arrayOf("preferred", "most_chapters")
            setDefaultValue("preferred")
        }.also { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_CACHE_TAGS
            title = "Cache Tags"
            summary = "Cache tags for faster filtering (uses more memory)"
            setDefaultValue(true)
        }.also { screen.addPreference(it) }
    }

    // ======================== Helpers ========================

    private fun novelToSManga(novel: NovelSearchResult): SManga = SManga.create().apply {
        url = "/novels/${novel.preferedSource?.novelSlug ?: novel.slug}/${novel.preferedSource?.sourceSlug ?: ""}"
        title = novel.title
        thumbnail_url = novel.preferedSource?.coverMinUrl ?: novel.preferedSource?.coverUrl
        author = novel.preferedSource?.authors?.firstOrNull()
        description = novel.preferedSource?.synopsis?.let { Jsoup.parse(it).text() }
        genre = novel.preferedSource?.tags?.take(5)?.joinToString(", ") ?: ""
    }

    private fun getPreferredSource(novel: NovelDetail): SourceInfo? {
        val sources = novel.sources ?: return novel.preferedSource

        return when (preferences.getString(PREF_SOURCE_SELECTION, "preferred")) {
            "most_chapters" -> sources.maxByOrNull { it.chaptersCount }
            else -> novel.preferedSource
        }
    }

    companion object {
        private const val PREF_SOURCE_SELECTION = "source_selection"
        private const val PREF_CACHE_TAGS = "cache_tags"
        private const val PREF_CSRF_TOKEN = "csrf_token"
    }

    // ======================== Data Classes ========================

    @Serializable
    data class SearchResponse(
        val count: Int,
        @SerialName("total_pages") val totalPages: Int,
        @SerialName("current_page") val currentPage: Int,
        val results: List<NovelSearchResult>,
    )

    @Serializable
    data class NovelSearchResult(
        val id: String,
        val title: String,
        val slug: String = "",
        @SerialName("sources_count") val sourcesCount: Int = 0,
        @SerialName("avg_rating") val avgRating: Double? = null,
        @SerialName("rating_count") val ratingCount: Int = 0,
        @SerialName("total_views") val totalViews: Int = 0,
        @SerialName("weekly_views") val weeklyViews: Int = 0,
        @SerialName("prefered_source") val preferedSource: SourceInfo? = null,
        val languages: List<String>? = null,
        @SerialName("is_bookmarked") val isBookmarked: Boolean? = null,
        @SerialName("comment_count") val commentCount: Int = 0,
    )

    @Serializable
    data class NovelDetail(
        val id: String,
        val title: String,
        val slug: String,
        val sources: List<SourceInfo>? = null,
        @SerialName("created_at") val createdAt: String? = null,
        @SerialName("updated_at") val updatedAt: String? = null,
        @SerialName("avg_rating") val avgRating: Double? = null,
        @SerialName("rating_count") val ratingCount: Int = 0,
        @SerialName("total_views") val totalViews: Int = 0,
        @SerialName("weekly_views") val weeklyViews: Int = 0,
        @SerialName("prefered_source") val preferedSource: SourceInfo? = null,
        @SerialName("similar_novels") val similarNovels: List<NovelSearchResult>? = null,
    )

    @Serializable
    data class SourceInfo(
        val id: String,
        val title: String,
        @SerialName("source_url") val sourceUrl: String? = null,
        @SerialName("source_name") val sourceName: String,
        @SerialName("source_slug") val sourceSlug: String,
        val authors: List<String>? = null,
        val tags: List<String>? = null,
        val language: String? = null,
        val synopsis: String? = null,
        @SerialName("cover_min_url") val coverMinUrl: String? = null,
        @SerialName("cover_url") val coverUrl: String? = null,
        @SerialName("chapters_count") val chaptersCount: Int = 0,
        @SerialName("volumes_count") val volumesCount: Int = 0,
        @SerialName("last_chapter_update") val lastChapterUpdate: String? = null,
        @SerialName("novel_id") val novelId: String? = null,
        @SerialName("novel_slug") val novelSlug: String? = null,
        @SerialName("novel_title") val novelTitle: String? = null,
        @SerialName("latest_available_chapter") val latestAvailableChapter: ChapterInfo? = null,
        @SerialName("overview_url") val overviewUrl: String? = null,
    )

    @Serializable
    data class ChapterInfo(
        val id: Int,
        @SerialName("chapter_id") val chapterId: Int,
        val title: String,
        val url: String? = null,
        val volume: Int? = null,
        @SerialName("volume_title") val volumeTitle: String? = null,
        @SerialName("has_content") val hasContent: Boolean = true,
    )

    @Serializable
    data class ChapterListResponse(
        @SerialName("novel_id") val novelId: String,
        @SerialName("novel_title") val novelTitle: String,
        @SerialName("novel_slug") val novelSlug: String,
        @SerialName("source_id") val sourceId: String,
        @SerialName("source_name") val sourceName: String,
        @SerialName("source_slug") val sourceSlug: String,
        val count: Int,
        @SerialName("total_pages") val totalPages: Int,
        @SerialName("current_page") val currentPage: Int,
        val chapters: List<ChapterInfo>,
    )

    @Serializable
    data class ChapterContent(
        val id: Int,
        @SerialName("chapter_id") val chapterId: Int,
        val title: String,
        @SerialName("novel_title") val novelTitle: String? = null,
        @SerialName("novel_id") val novelId: String? = null,
        @SerialName("novel_slug") val novelSlug: String? = null,
        @SerialName("source_id") val sourceId: String? = null,
        @SerialName("source_name") val sourceName: String? = null,
        @SerialName("source_slug") val sourceSlug: String? = null,
        val body: String,
        @SerialName("prev_chapter") val prevChapter: Int? = null,
        @SerialName("next_chapter") val nextChapter: Int? = null,
        @SerialName("images_path") val imagesPath: String? = null,
        @SerialName("source_overview_image_url") val sourceOverviewImageUrl: String? = null,
    )
}

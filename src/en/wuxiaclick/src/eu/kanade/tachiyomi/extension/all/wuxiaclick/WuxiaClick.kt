package eu.kanade.tachiyomi.extension.en.wuxiaclick

import android.app.Application
import android.content.SharedPreferences
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class WuxiaClick :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "WuxiaClick"
    override val baseUrl = "https://wuxia.click"
    private val apiUrl = "https://wuxiaworld.eu/api"
    override val lang = "all"
    override val supportsLatest = true
    override val isNovelSource = true

    override val client = network.cloudflareClient

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private fun getSearchCache(): JsonArray = try {
        val raw = preferences.getString(SEARCH_CACHE_KEY, null) ?: return JsonArray(emptyList())
        json.parseToJsonElement(raw).jsonArray
    } catch (e: Exception) {
        JsonArray(emptyList())
    }

    private fun appendToSearchCache(items: List<Pair<String, String>>) {
        try {
            val current = getSearchCache().toMutableList()
            val existingSlugs = current.mapNotNull {
                it.jsonObject["slug"]?.let { s ->
                    try {
                        s.jsonPrimitive.content
                    } catch (_: Exception) {
                        null
                    }
                }
            }.toMutableSet()
            for ((slug, title) in items) {
                if (!existingSlugs.contains(slug)) {
                    current.add(
                        buildJsonObject {
                            put("slug", JsonPrimitive(slug))
                            put("title", JsonPrimitive(title))
                        },
                    )
                    existingSlugs.add(slug)
                }
            }
            val arr = JsonArray(current)
            preferences.edit().putString(SEARCH_CACHE_KEY, arr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    private fun clearSearchCache() {
        preferences.edit().remove(SEARCH_CACHE_KEY).apply()
    }

    private fun JsonElement?.asStringOrNull(): String? = try {
        this?.jsonPrimitive?.content
    } catch (e: Exception) {
        null
    }

    private fun extractNovelSlug(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        return when {
            trimmed.contains("/api/novels/") -> {
                trimmed.substringAfter("/api/novels/").substringBefore("/")
            }
            trimmed.contains("/novel/") -> {
                trimmed.substringAfter("/novel/").substringBefore("/")
            }
            else -> trimmed.removePrefix("/").substringAfterLast("/")
        }
    }

    // Track the Next.js build ID for data fetching
    private var buildId: String? = null

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        val offset = (page - 1) * 12
        return GET("$apiUrl/search/?search=&offset=$offset&limit=12&order=-rating", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body.string()
        // Try to parse Next.js dehydrated data first
        val doc = org.jsoup.Jsoup.parse(body)
        val nextScript = doc.selectFirst("script#__NEXT_DATA__") ?: doc.selectFirst("script[id=__NEXT_DATA__]")
        if (nextScript != null) {
            try {
                val nextJson = json.parseToJsonElement(nextScript.html()).jsonObject
                val queries = nextJson["props"]?.jsonObject?.get("pageProps")?.jsonObject
                    ?.get("dehydratedState")?.jsonObject?.get("queries")?.jsonArray
                val results = queries?.firstOrNull()?.jsonObject?.get("state")?.jsonObject?.get("data")?.jsonObject
                    ?.get("results")?.jsonArray

                if (results != null) {
                    val mangas = results.mapNotNull { elem ->
                        try {
                            val obj = elem.jsonObject
                            val slug = obj["slug"]?.jsonPrimitive?.content ?: return@mapNotNull null
                            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                            val img = obj["image"]?.asStringOrNull() ?: ""
                            val categories = obj["categories"]?.jsonArray?.mapNotNull {
                                it.jsonObject["name"]?.let { n ->
                                    try {
                                        n.jsonPrimitive.content
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            } ?: emptyList()

                            SManga.create().apply {
                                url = "/novel/$slug"
                                title = name.trim()
                                thumbnail_url = if (img.startsWith("http")) img else img
                                author = categories.firstOrNull()
                                description = obj["description"]?.asStringOrNull() ?: ""
                                genre = categories.joinToString(", ")
                                status = when (obj["status"]?.asStringOrNull()) {
                                    "CP", "completed", "cp" -> SManga.COMPLETED
                                    "OG", "ongoing", "og" -> SManga.ONGOING
                                    else -> SManga.UNKNOWN
                                }
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }

                    // Append to persistent search cache (slug,title)
                    appendToSearchCache(
                        mangas.mapNotNull { m ->
                            val slug = m.url.removePrefix("/novel/")
                            val t = m.title
                            if (slug.isNotBlank()) Pair(slug, t) else null
                        },
                    )

                    val hasNext = queries.firstOrNull()?.jsonObject?.get("state")?.jsonObject?.get("data")?.jsonObject
                        ?.get("next")?.asStringOrNull() != null

                    return MangasPage(mangas, hasNext)
                }
            } catch (e: Exception) {
            }
        }

        // Fallback to original API parsing
        val searchResponse = json.decodeFromString<SearchResponse>(body)

        val novels = searchResponse.results.map { novel ->
            SManga.create().apply {
                url = "/novel/${novel.slug}"
                title = novel.name
                thumbnail_url = novel.image ?: ""
                author = novel.categories?.firstOrNull()?.name
                description = novel.description
                genre = novel.categories?.joinToString(", ") { it.name } ?: ""
                status = when {
                    novel.chapters >= (novel.numOfChaps ?: 0) -> SManga.COMPLETED
                    else -> SManga.ONGOING
                }
            }
        }

        val hasNextPage = searchResponse.next != null
        // Append fallback results to cache
        appendToSearchCache(
            novels.mapNotNull { m ->
                val slug = m.url.removePrefix("/novel/")
                if (slug.isNotBlank()) Pair(slug, m.title) else null
            },
        )

        return MangasPage(novels, hasNextPage)
    }

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        val offset = (page - 1) * 12
        // Using -last_chapter since -updated_at is not a valid choice
        return GET("$apiUrl/search/?search=&offset=$offset&limit=12&order=-last_chapter", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var order = "-weekly_views"
        val statuses = mutableListOf<String>()
        val includeCategories = mutableListOf<String>()
        val excludeCategories = mutableListOf<String>()
        val includeTags = mutableListOf<String>()
        val excludeTags = mutableListOf<String>()
        val originalLanguages = mutableListOf<String>()
        var minChapters: String? = null
        var maxChapters: String? = null
        var minRating: String? = null
        var minReviews: String? = null
        var maxReviews: String? = null
        var releasedAfter: String? = null
        fun String.csvToSlugList(): List<String> = split(",")
            .map { it.trim().lowercase().replace(" ", "-") }
            .filter { it.isNotBlank() }

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> order = filter.pairValues[filter.state].second

                is StatusFilter -> {
                    filter.state.filter { it.state }.mapTo(statuses) { it.code }
                }

                is OriginalLanguageFilter -> {
                    filter.state.filter { it.state }.mapTo(originalLanguages) { it.code }
                }

                is CategoryTriStateFilter -> {
                    filter.state.filter { it.isIncluded() }.mapTo(includeCategories) { it.slug }
                    filter.state.filter { it.isExcluded() }.mapTo(excludeCategories) { it.slug }
                }

                is TagTriStateFilter -> {
                    filter.state.filter { it.isIncluded() }.mapTo(includeTags) { it.slug }
                    filter.state.filter { it.isExcluded() }.mapTo(excludeTags) { it.slug }
                }

                is IncludeCategoriesTextFilter -> includeCategories.addAll(filter.state.csvToSlugList())
                is ExcludeCategoriesTextFilter -> excludeCategories.addAll(filter.state.csvToSlugList())
                is IncludeTagsTextFilter -> includeTags.addAll(filter.state.csvToSlugList())
                is ExcludeTagsTextFilter -> excludeTags.addAll(filter.state.csvToSlugList())

                is MinChaptersFilter -> minChapters = filter.state.trim().takeIf { it.matches(Regex("\\d+")) }
                is MaxChaptersFilter -> maxChapters = filter.state.trim().takeIf { it.matches(Regex("\\d+")) }
                is MinRatingFilter -> minRating = filter.state.trim().takeIf { it.matches(Regex("\\d+(\\.\\d+)?")) }
                is MinReviewsFilter -> minReviews = filter.state.trim().takeIf { it.matches(Regex("\\d+")) }
                is MaxReviewsFilter -> maxReviews = filter.state.trim().takeIf { it.matches(Regex("\\d+")) }
                is ReleasedAfterFilter -> releasedAfter = filter.state.trim().takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }

                else -> {}
            }
        }

        val hasAdvancedFilters =
            statuses.isNotEmpty() ||
                includeCategories.isNotEmpty() ||
                excludeCategories.isNotEmpty() ||
                includeTags.isNotEmpty() ||
                excludeTags.isNotEmpty() ||
                originalLanguages.isNotEmpty() ||
                minChapters != null ||
                maxChapters != null ||
                minRating != null ||
                minReviews != null ||
                maxReviews != null ||
                releasedAfter != null

        if (hasAdvancedFilters || query.isNotBlank()) {
            val url = buildString {
                append("$baseUrl/advance_search?page=$page")
                if (query.isNotBlank()) append("&search=${java.net.URLEncoder.encode(query, "UTF-8")}")
                statuses.forEach { append("&status=$it") }
                if (minChapters != null) append("&numOfChaps__gt=$minChapters")
                if (maxChapters != null) append("&numOfChaps__lt=$maxChapters")
                if (minRating != null) append("&rating__gt=$minRating")
                if (minReviews != null) append("&min_reviews_count=$minReviews")
                if (maxReviews != null) append("&max_reviews_count=$maxReviews")
                if (includeTags.isNotEmpty()) append("&include_tags=${java.net.URLEncoder.encode(includeTags.distinct().joinToString(","), "UTF-8")}")
                if (excludeTags.isNotEmpty()) append("&exclude_tags=${java.net.URLEncoder.encode(excludeTags.distinct().joinToString(","), "UTF-8")}")
                if (includeCategories.isNotEmpty()) append("&include_categories=${java.net.URLEncoder.encode(includeCategories.distinct().joinToString(","), "UTF-8")}")
                if (excludeCategories.isNotEmpty()) append("&exclude_categories=${java.net.URLEncoder.encode(excludeCategories.distinct().joinToString(","), "UTF-8")}")
                if (releasedAfter != null) append("&novel_created_after=$releasedAfter")
                if (originalLanguages.isNotEmpty()) append("&original_language=${java.net.URLEncoder.encode(originalLanguages.distinct().joinToString(","), "UTF-8")}")
                append("&order=$order")
            }
            return GET(url, headers)
        }

        val searchOffset = (page - 1) * 12
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = buildString {
            append("$apiUrl/search/?search=$encodedQuery&offset=$searchOffset&limit=12&order=$order")
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun getMangaUrl(manga: SManga): String {
        val slug = extractNovelSlug(manga.url)
        return "https://wuxiaworld.eu/novel/$slug"
    }

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = extractNovelSlug(manga.url)
        return GET("$apiUrl/novels/$slug/", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body.string()
        // Prefer parsing __NEXT_DATA__ if present
        val doc = org.jsoup.Jsoup.parse(body)
        val nextScript = doc.selectFirst("script#__NEXT_DATA__") ?: doc.selectFirst("script[id=__NEXT_DATA__]")
        if (nextScript != null) {
            try {
                val nextJson = json.parseToJsonElement(nextScript.html()).jsonObject
                val dataObj = nextJson["props"]?.jsonObject?.get("pageProps")?.jsonObject
                    ?.get("dehydratedState")?.jsonObject?.get("queries")?.jsonArray
                    ?.firstOrNull()?.jsonObject?.get("state")?.jsonObject?.get("data")?.jsonObject

                if (dataObj != null) {
                    val localTitle = dataObj["name"]?.asStringOrNull() ?: ""
                    val localSlug = dataObj["slug"]?.asStringOrNull() ?: ""
                    val localImg = dataObj["image"]?.asStringOrNull()
                    val localAuthor = dataObj["author"]?.jsonObject?.get("name")?.asStringOrNull()
                    val localDescription = dataObj["description"]?.asStringOrNull() ?: ""
                    val localCategories = dataObj["categories"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.asStringOrNull() } ?: emptyList()
                    val statusStr = dataObj["status"]?.asStringOrNull()?.lowercase() ?: ""

                    return SManga.create().apply {
                        url = if (localSlug.isNotBlank()) "/novel/$localSlug" else ""
                        title = localTitle
                        thumbnail_url = localImg
                        author = localAuthor
                        this.description = localDescription
                        genre = localCategories.joinToString(", ")
                        status = when {
                            statusStr.contains("completed") || statusStr.contains("cp") -> SManga.COMPLETED
                            statusStr.contains("ongoing") || statusStr.contains("og") -> SManga.ONGOING
                            else -> SManga.UNKNOWN
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }

        // Fallback to existing JSON API parsing
        val novel = json.decodeFromString<NovelDetail>(body)

        return SManga.create().apply {
            url = "/novel/${novel.slug}"
            title = novel.name
            thumbnail_url = novel.image ?: novel.originalImage
            author = novel.author?.name
            description = buildString {
                append(novel.description)
                val otherNamesList = novel.getOtherNamesList()
                if (otherNamesList.isNotEmpty()) {
                    append("\n\nAlternative Names: ${otherNamesList.joinToString(", ")}")
                }
                novel.rating?.let { rating ->
                    append("\n\nRating: $rating")
                }
                novel.humanViews?.let { views ->
                    append("\nViews: $views")
                }
            }
            genre = buildString {
                novel.categories?.let { cats ->
                    append(cats.joinToString(", ") { it.name })
                }
                novel.tags?.let { tags ->
                    if (tags.isNotEmpty()) {
                        if (isNotEmpty()) append(", ")
                        append(tags.take(10).joinToString(", ") { it.name })
                    }
                }
            }
            status = when (novel.status?.uppercase()) {
                "OG" -> SManga.ONGOING
                "CP" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request {
        val slug = extractNovelSlug(manga.url)
        return GET("$apiUrl/chapters/$slug/", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = json.decodeFromString<List<ChapterInfo>>(response.body.string())

        return chapters.map { chapter ->
            SChapter.create().apply {
                url = "/chapter/${chapter.novSlugChapSlug}"
                name = chapter.title
                chapter_number = chapter.index.toFloat()
                date_upload = parseChapterDate(chapter.timeAdded)
            }
        }.reversed()
    }

    // ======================== Pages ========================

    override fun pageListRequest(chapter: SChapter): Request {
        val slug = chapter.url.removePrefix("/chapter/")
        return GET("$apiUrl/getchapter/$slug/", headers)
    }

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

    // ======================== Page Text (Novel) ========================

    override suspend fun fetchPageText(page: Page): String {
        val request = GET(page.url, headers)
        val response = client.newCall(request).execute()
        val chapter = json.decodeFromString<ChapterContent>(response.body.string())

        val content = StringBuilder()

        content.append("<h2>${chapter.title}</h2>\n")

        val text = chapter.text

        // Split by lines and wrap in paragraphs
        text.split("\n").forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                content.append("<p>$trimmed</p>\n")
            }
        }

        return content.toString()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // ======================== Filters ========================

    override fun getFilterList(): FilterList = FilterList(
        SortFilter("Sort By", sortOptions),
        Filter.Separator(),
        StatusFilter(),
        OriginalLanguageFilter(),
        MinChaptersFilter(),
        MaxChaptersFilter(),
        MinRatingFilter(),
        MinReviewsFilter(),
        MaxReviewsFilter(),
        ReleasedAfterFilter(),
        Filter.Separator(),
        CategoryTriStateFilter(categoryTriStateOptions.map { CategoryTriState(it.first, it.second) }),
        IncludeCategoriesTextFilter("Include Categories Slugs (comma-separated alt)"),
        ExcludeCategoriesTextFilter("Exclude Categories Slugs (comma-separated alt)"),
        Filter.Separator(),
        TagTriStateFilter(tagTriStateOptions.map { TagTriState(it.first, it.second) }),
        IncludeTagsTextFilter("Include Tags Slugs (comma-separated alt)"),
        ExcludeTagsTextFilter("Exclude Tags Slugs (comma-separated alt)"),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val count = getSearchCache().size
        SwitchPreferenceCompat(screen.context).apply {
            key = CLEAR_SEARCH_CACHE_KEY
            title = "Clear Search Result Cache"
            summary = "Toggle to clear cached search slugs/titles ($count items)."
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                clearSearchCache()
                true
            }
        }.also(screen::addPreference)
    }

    class SortFilter(name: String, internal val pairValues: Array<Pair<String, String>>) : Filter.Select<String>(name, pairValues.map { it.first }.toTypedArray())

    class StatusFilter :
        Filter.Group<StatusCheckBox>(
            "Novel Type",
            listOf(
                StatusCheckBox("Completed", "CD"),
                StatusCheckBox("Ongoing", "OG"),
                StatusCheckBox("Hiatus", "HS"),
                StatusCheckBox("Dropped", "DD"),
            ),
        )

    class StatusCheckBox(name: String, val code: String) : Filter.CheckBox(name)

    class OriginalLanguageFilter :
        Filter.Group<LanguageCheckBox>(
            "Original Language",
            listOf(
                LanguageCheckBox("Chinese", "CN"),
                LanguageCheckBox("Japanese", "JP"),
                LanguageCheckBox("English", "EN"),
                LanguageCheckBox("Korean", "KR"),
            ),
        )

    class LanguageCheckBox(name: String, val code: String) : Filter.CheckBox(name)

    class MinChaptersFilter : Filter.Text("Minimum Num of Chapters")
    class MaxChaptersFilter : Filter.Text("Maximum Num of Chapters")
    class MinRatingFilter : Filter.Text("Minimum Rating")
    class MinReviewsFilter : Filter.Text("Minimum Number of Reviews")
    class MaxReviewsFilter : Filter.Text("Maximum Number of Reviews")
    class ReleasedAfterFilter : Filter.Text("Released After (YYYY-MM-DD)")

    class CategoryTriState(name: String, val slug: String) : Filter.TriState(name)
    class CategoryTriStateFilter(categories: List<CategoryTriState>) : Filter.Group<CategoryTriState>("Category Include/Exclude", categories)

    class TagTriState(name: String, val slug: String) : Filter.TriState(name)
    class TagTriStateFilter(tags: List<TagTriState>) : Filter.Group<TagTriState>("Tag Include/Exclude", tags)

    class IncludeCategoriesTextFilter(name: String) : Filter.Text(name)
    class ExcludeCategoriesTextFilter(name: String) : Filter.Text(name)
    class IncludeTagsTextFilter(name: String) : Filter.Text(name)
    class ExcludeTagsTextFilter(name: String) : Filter.Text(name)

    private val sortOptions = arrayOf(
        Pair("Weekly Views", "-weekly_views"),
        Pair("Total Views", "-total_views"),
        Pair("Rating", "-rating"),
        Pair("Chapters", "-numOfChaps"),
        Pair("Recently Updated", "-last_chapter"),
        Pair("Newest", "-created_at"),
        Pair("Name A-Z", "name"),
        Pair("Name Z-A", "-name"),
    )

    private val categoryTriStateOptions = listOf(
        Pair("Action", "action"),
        Pair("Adult", "adult"),
        Pair("Adventure", "adventure"),
        Pair("Comedy", "comedy"),
        Pair("Drama", "drama"),
        Pair("Ecchi", "ecchi"),
        Pair("Fantasy", "fantasy"),
        Pair("Gender Bender", "gender-bender"),
        Pair("Harem", "harem"),
        Pair("Historical", "historical"),
        Pair("Horror", "horror"),
        Pair("Josei", "josei"),
        Pair("Martial Arts", "martial-arts"),
        Pair("Mature", "mature"),
        Pair("Mecha", "mecha"),
        Pair("Mystery", "mystery"),
        Pair("Psychological", "psychological"),
        Pair("Romance", "romance"),
        Pair("School Life", "school-life"),
        Pair("Sci-fi", "sci-fi"),
        Pair("Seinen", "seinen"),
        Pair("Shoujo", "shoujo"),
        Pair("Shoujo Ai", "shoujo-ai"),
        Pair("Shounen", "shounen"),
        Pair("Shounen Ai", "shounen-ai"),
        Pair("Slice of Life", "slice-of-life"),
        Pair("Smut", "smut"),
        Pair("Sports", "sports"),
        Pair("Supernatural", "supernatural"),
        Pair("Tragedy", "tragedy"),
        Pair("Wuxia", "wuxia"),
        Pair("Xianxia", "xianxia"),
        Pair("Xuanhuan", "xuanhuan"),
        Pair("Yaoi", "yaoi"),
        Pair("Yuri", "yuri"),
    )

    private val tagTriStateOptions = listOf(
        Pair("Transmigration", "transmigration"),
        Pair("Weak to Strong", "weak-to-strong"),
        Pair("Love Interest Falls in Love First", "love-interest-falls-in-love-first"),
        Pair("Female Protagonist", "female-protagonist"),
        Pair("Clever Protagonist", "clever-protagonist"),
        Pair("Reincarnation", "reincarnation"),
        Pair("System", "system"),
        Pair("Romance", "romance"),
        Pair("Harem", "harem"),
        Pair("Modern Day", "modern-day"),
    )

    // ======================== Helpers ========================

    private fun parseChapterDate(dateString: String?): Long {
        if (dateString.isNullOrEmpty()) return 0L
        return try {
            val months = mapOf(
                "January" to 0, "February" to 1, "March" to 2, "April" to 3,
                "May" to 4, "June" to 5, "July" to 6, "August" to 7,
                "September" to 8, "October" to 9, "November" to 10, "December" to 11,
            )
            val parts = dateString.split(" ")
            if (parts.size >= 3) {
                val month = months[parts[0]] ?: 0
                val day = parts[1].toIntOrNull() ?: 1
                val year = parts[2].toIntOrNull() ?: 2023
                java.util.Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                }.timeInMillis
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    // ======================== Data Classes ========================

    @Serializable
    data class SearchResponse(
        val count: Int,
        val next: String? = null,
        val previous: String? = null,
        val results: List<NovelSearchResult>,
    )

    @Serializable
    data class NovelSearchResult(
        val name: String,
        val image: String? = null,
        val slug: String,
        val description: String? = null,
        val rating: String? = null,
        val ranking: Int? = null,
        val views: String? = null,
        val chapters: Int = 0,
        val categories: List<Category>? = null,
        val tags: List<Tag>? = null,
        val numOfChaps: Int? = null,
    )

    @Serializable
    data class NovelDetail(
        val slug: String,
        val name: String,
        val description: String? = null,
        val image: String? = null,
        @SerialName("original_image") val originalImage: String? = null,
        val author: Author? = null,
        val categories: List<Category>? = null,
        val tags: List<Tag>? = null,
        val views: String? = null,
        @SerialName("human_views") val humanViews: String? = null,
        val chapters: Int = 0,
        val rating: String? = null,
        val status: String? = null,
        @SerialName("other_names") val otherNames: JsonElement? = null, // Can be array or empty object
        @SerialName("numOfChaps") val numOfChaps: Int? = null,
    ) {
        fun getOtherNamesList(): List<String> = try {
            otherNames?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Serializable
    data class Author(
        val name: String,
        val slug: String? = null,
    )

    @Serializable
    data class Category(
        val name: String,
        val slug: String,
        val title: String? = null,
    )

    @Serializable
    data class Tag(
        val id: Int? = null,
        val name: String,
        val slug: String,
        val title: String? = null,
    )

    @Serializable
    data class ChapterInfo(
        val id: Int,
        val index: Int,
        val title: String,
        val novSlugChapSlug: String,
        val timeAdded: String? = null,
    )

    @Serializable
    data class ChapterContent(
        val index: Int? = null,
        val title: String,
        val text: String,
    )
}

private const val SEARCH_CACHE_KEY = "wuxiaclick_search_cache"
private const val CLEAR_SEARCH_CACHE_KEY = "wuxiaclick_clear_search_cache"

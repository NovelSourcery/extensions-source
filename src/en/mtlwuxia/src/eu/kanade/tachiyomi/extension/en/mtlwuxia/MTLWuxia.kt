package eu.kanade.tachiyomi.novelextension.en.mtlwuxia

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
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.jsonInstance
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class MTLWuxia :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "MTL Wuxia"
    override val baseUrl = "https://mtlwuxia.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val preferences: SharedPreferences by getPreferencesLazy()

    // ---- tRPC helpers ----

    private fun trpcRequest(procedure: String, input: JsonElement?, fragment: String? = null): Request {
        val batchInput = buildJsonObject {
            put(
                "0",
                buildJsonObject {
                    put("json", input ?: JsonNull)
                },
            )
        }
        val url = "$baseUrl/api/trpc/$procedure".toHttpUrl().newBuilder()
            .addQueryParameter("batch", "1")
            .addQueryParameter("input", batchInput.toString())
            .fragment(fragment)
            .build()
        return GET(url, headers)
    }

    // Unwraps [{"result":{"data":{"json":<payload>}}}]
    private fun Response.trpcJson(): JsonElement = jsonInstance.parseToJsonElement(body.string())
        .jsonArray[0]
        .jsonObject["result"]!!
        .jsonObject["data"]!!
        .jsonObject["json"]!!

    // ---- Browse / search ----

    // novel.getAll paginates with an opaque cursor; remember the cursor of each
    // listing (keyed via the request URL fragment) so page N+1 can resume.
    private val listingCursors = mutableMapOf<String, String>()

    private fun novelListRequest(page: Int, baseInput: JsonObject): Request {
        val key = baseInput.toString().hashCode().toString()
        val input = if (page > 1) {
            val cursor = listingCursors[key]
                ?: throw Exception("Pagination cursor lost – please refresh from the first page")
            JsonObject(baseInput + ("cursor" to JsonPrimitive(cursor)))
        } else {
            baseInput
        }
        return trpcRequest("novel.getAll", input, fragment = key)
    }

    private fun novelListParse(response: Response): MangasPage {
        // Runs on a network thread – safe place to fill the genre/tag cache.
        ensureFilterOptionsLoaded()
        val data = jsonInstance.decodeFromJsonElement<NovelListJson>(response.trpcJson())
        response.request.url.fragment?.let { key ->
            data.nextCursor?.let { listingCursors[key] = it } ?: listingCursors.remove(key)
        }
        return MangasPage(data.novels.map { it.toSManga() }, data.nextCursor != null)
    }

    private fun baseListInput(sortBy: String, block: (MutableMap<String, JsonElement>.() -> Unit)? = null): JsonObject {
        val map = mutableMapOf<String, JsonElement>(
            "limit" to JsonPrimitive(20),
            "sortBy" to JsonPrimitive(sortBy),
        )
        block?.invoke(map)
        return JsonObject(map)
    }

    override fun popularMangaRequest(page: Int): Request = novelListRequest(page, baseListInput("views"))

    override fun popularMangaParse(response: Response): MangasPage = novelListParse(response)

    override fun latestUpdatesRequest(page: Int): Request = novelListRequest(page, baseListInput("latest"))

    override fun latestUpdatesParse(response: Response): MangasPage = novelListParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var sortBy = "latest"
        filters.filterIsInstance<SortFilter>().firstOrNull()?.let { sortBy = it.toUriPart() }

        val input = baseListInput(sortBy) {
            if (query.isNotBlank()) {
                put("search", JsonPrimitive(query))
            }
            filters.forEach { filter ->
                when (filter) {
                    is StatusFilter -> filter.toUriPart()?.let { put("status", JsonPrimitive(it)) }
                    is GenreFilter -> filter.selectedSlug()?.let { put("genreSlug", JsonPrimitive(it)) }
                    is TagFilter -> filter.selectedSlug()?.let { put("tagSlug", JsonPrimitive(it)) }
                    else -> Unit
                }
            }
        }
        return novelListRequest(page, input)
    }

    override fun searchMangaParse(response: Response): MangasPage = novelListParse(response)

    // ---- Details ----

    override fun mangaDetailsRequest(manga: SManga): Request = trpcRequest("novel.getBySlug", buildJsonObject { put("slug", manga.slug()) })

    override fun mangaDetailsParse(response: Response): SManga = jsonInstance.decodeFromJsonElement<NovelDto>(response.trpcJson()).toSManga()

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    private fun SManga.slug(): String = url
        .substringAfter("/novel/")
        .substringBefore('/')
        .substringBefore('?')

    // ---- Chapters ----

    override fun chapterListRequest(manga: SManga): Request = chapterPageRequest(manga.slug(), cursor = null)

    private fun chapterPageRequest(slug: String, cursor: String?): Request = trpcRequest(
        "chapter.getListByNovel",
        buildJsonObject {
            put("novelSlug", slug)
            put("limit", 100)
            cursor?.let { put("cursor", it) }
        },
        fragment = slug,
    )

    override fun chapterListParse(response: Response): List<SChapter> {
        val slug = response.request.url.fragment!!
        val chapters = mutableListOf<ChapterDto>()

        var data = jsonInstance.decodeFromJsonElement<ChapterListJson>(response.trpcJson())
        chapters += data.chapters

        // Follow the cursor until the whole list is fetched (limit is capped at 100).
        while (data.nextCursor != null) {
            val next = client.newCall(chapterPageRequest(slug, data.nextCursor)).execute()
            data = jsonInstance.decodeFromJsonElement<ChapterListJson>(next.trpcJson())
            chapters += data.chapters
        }

        return chapters
            .map { chapter ->
                SChapter.create().apply {
                    url = "/novel/$slug/chapter/${chapter.number.toInt()}"
                    name = buildString {
                        append("Chapter ${chapter.number.toInt()}")
                        chapter.title?.takeIf { it.isNotBlank() }?.let { append(" - $it") }
                    }
                    chapter_number = chapter.number
                    date_upload = parseDate(chapter.publishedAt)
                }
            }
            .sortedByDescending { it.chapter_number }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // Dates look like "2026-05-28T00:26:38.819Z"
    private fun parseDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val normalized = date.substringBefore('.').removeSuffix("Z")
        return runCatching { dateFormat.parse(normalized)?.time }.getOrNull() ?: 0L
    }

    // ---- Chapter content ----

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString().removePrefix(baseUrl)
        return listOf(Page(0, chapterUrl))
    }

    @Serializable
    private data class PageChapterDto(
        val content: String,
        val translatorNote: String? = null,
    )

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val chapter = response.asJsoup().extractNextJs<PageChapterDto>()
            ?: throw Exception("Chapter content not found")

        return buildString {
            append(chapter.content)
            chapter.translatorNote?.takeIf { it.isNotBlank() }?.let {
                append("<hr><p><i>Translator note: ")
                append(it)
                append("</i></p>")
            }
        }
    }

    override fun imageUrlParse(response: Response): String = ""

    // ---- Filter metadata (fetched once, cached in preferences) ----

    @Serializable
    private data class FilterOption(val name: String, val slug: String)

    private fun loadCachedOptions(key: String): List<FilterOption> {
        val raw = preferences.getString(key, null) ?: return emptyList()
        return runCatching { jsonInstance.decodeFromString<List<FilterOption>>(raw) }
            .getOrDefault(emptyList())
    }

    private fun filterOptionsCached(): Boolean = loadCachedOptions(PREF_GENRES_CACHE).isNotEmpty() &&
        loadCachedOptions(PREF_TAGS_CACHE).isNotEmpty()

    private fun ensureFilterOptionsLoaded() {
        if (filterOptionsCached()) {
            return
        }

        runCatching {
            // Single batched call: search.getGenres,search.getTags
            val batchInput = buildJsonObject {
                put("0", buildJsonObject { put("json", JsonNull) })
                put("1", buildJsonObject { put("json", JsonNull) })
            }
            val url = "$baseUrl/api/trpc/search.getGenres,search.getTags".toHttpUrl().newBuilder()
                .addQueryParameter("batch", "1")
                .addQueryParameter("input", batchInput.toString())
                .build()
            val results = jsonInstance.parseToJsonElement(
                client.newCall(GET(url, headers)).execute().body.string(),
            ).jsonArray

            fun parseOptions(element: JsonElement): List<FilterOption> = element
                .jsonObject["result"]!!
                .jsonObject["data"]!!
                .jsonObject["json"]!!
                .jsonArray
                .map { jsonInstance.decodeFromJsonElement<FilterOption>(it) }

            val genres = parseOptions(results[0])
            val tags = parseOptions(results[1])

            preferences.edit()
                .putString(PREF_GENRES_CACHE, jsonInstance.encodeToString(genres))
                .putString(PREF_TAGS_CACHE, jsonInstance.encodeToString(tags))
                .apply()
        }
    }

    // ---- Filters ----

    override fun getFilterList(): FilterList {
        // getFilterList can be called on the main thread, so the fetch must not
        // run inline – kick it off in the background and ask the user to reopen.
        if (!filterOptionsCached()) {
            Thread { ensureFilterOptionsLoaded() }.start()
            return FilterList(
                Filter.Header("Loading genre/tag lists – reopen filters in a moment"),
                SortFilter(),
                StatusFilter(),
            )
        }
        return FilterList(
            SortFilter(),
            StatusFilter(),
            GenreFilter(loadCachedOptions(PREF_GENRES_CACHE)),
            TagFilter(loadCachedOptions(PREF_TAGS_CACHE)),
        )
    }

    private class SortFilter :
        Filter.Select<String>(
            "Sort by",
            arrayOf("Latest", "Rating", "Views", "Chapters", "Title"),
        ) {
        fun toUriPart() = when (state) {
            1 -> "rating"
            2 -> "views"
            3 -> "chapters"
            4 -> "title"
            else -> "latest"
        }
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("All", "Ongoing", "Completed", "Hiatus"),
        ) {
        fun toUriPart(): String? = when (state) {
            1 -> "ONGOING"
            2 -> "COMPLETED"
            3 -> "HIATUS"
            else -> null
        }
    }

    private open class OptionSelectFilter(title: String, options: List<FilterOption>) :
        Filter.Select<String>(
            title,
            (listOf("All") + options.map { it.name }).toTypedArray(),
        ) {
        private val slugs = listOf<String?>(null) + options.map { it.slug }

        fun selectedSlug(): String? = slugs.getOrNull(state)
    }

    private class GenreFilter(options: List<FilterOption>) : OptionSelectFilter("Genre", options)

    private class TagFilter(options: List<FilterOption>) : OptionSelectFilter("Tag", options)

    // ---- Preferences ----

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_CLEAR_FILTER_CACHE
            title = "Refresh filter lists"
            summary = "Toggle to clear cached genres and tags. They will be refetched the next time you open the filters."
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                preferences.edit()
                    .remove(PREF_GENRES_CACHE)
                    .remove(PREF_TAGS_CACHE)
                    .apply()
                true
            }
        }.also(screen::addPreference)
    }

    // ---- DTOs ----

    @Serializable
    private data class NovelListJson(
        val novels: List<NovelDto>,
        val nextCursor: String? = null,
    )

    @Serializable
    private data class NovelDto(
        val title: String,
        val slug: String,
        val author: String? = null,
        val synopsis: String? = null,
        val coverImage: String? = null,
        val status: String? = null,
        val totalChapters: Int? = null,
        val views: Int? = null,
        val rating: Double? = null,
        @SerialName("_count") val count: CountDto? = null,
        val genres: List<GenreWrapper> = emptyList(),
        val tags: List<TagWrapper> = emptyList(),
    ) {
        fun toSManga(): SManga = SManga.create().apply {
            url = "/novel/${this@NovelDto.slug}"
            title = this@NovelDto.title
            author = this@NovelDto.author
            description = buildString {
                synopsis?.takeIf { it.isNotBlank() }?.let { append(it) }
                val stats = buildList {
                    totalChapters?.let { add("Chapters: $it") }
                    views?.let { add("Views: $it") }
                    count?.bookmarks?.let { add("Bookmarks: $it") }
                    rating?.let {
                        add("Avg Rating: ${String.format(Locale.US, "%.1f", it)} (${count?.ratings ?: 0})")
                    }
                }
                if (stats.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(stats.joinToString("\n"))
                }
            }
            thumbnail_url = coverImage
            genre = (genres.mapNotNull { it.genre?.name } + tags.mapNotNull { it.tag?.name })
                .distinct()
                .joinToString(", ")
            status = when (this@NovelDto.status?.uppercase()) {
                "ONGOING" -> SManga.ONGOING
                "COMPLETED" -> SManga.COMPLETED
                "HIATUS" -> SManga.ON_HIATUS
                "DROPPED" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    @Serializable
    private data class CountDto(
        val bookmarks: Int? = null,
        val comments: Int? = null,
        val ratings: Int? = null,
    )

    @Serializable
    private data class GenreWrapper(val genre: FilterOption? = null)

    @Serializable
    private data class TagWrapper(val tag: FilterOption? = null)

    @Serializable
    private data class ChapterListJson(
        val chapters: List<ChapterDto>,
        val nextCursor: String? = null,
    )

    @Serializable
    private data class ChapterDto(
        val number: Float,
        val title: String? = null,
        val publishedAt: String? = null,
    )

    companion object {
        private const val PREF_CLEAR_FILTER_CACHE = "mtlwuxia_clear_filter_cache"
        private const val PREF_GENRES_CACHE = "mtlwuxia_genres_cache"
        private const val PREF_TAGS_CACHE = "mtlwuxia_tags_cache"
    }
}

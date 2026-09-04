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
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.jsonInstance
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class MTLWuxia :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    /**
     * The site's novel detail URL shape, as `/novel/<slug>`. [SManga.url] is stored as the bare
     * slug (see [SlugPath]); a stored value starting with "/" is a pre-existing full-path entry
     * from before this source adopted slug storage, and is resolved unchanged regardless of
     * this template.
     */
    private val mangaPathTemplate: SlugPath = SlugPath("/novel/")

    // tRPC input schemas treat an omitted optional field differently from an explicit null
    // (e.g. a zod .optional() cursor), so this overrides the shared instance's null handling
    // just for encoding request inputs.
    private val requestJson = Json(jsonInstance) { explicitNulls = false }

    // ---- tRPC helpers ----

    private fun trpcUrl(procedure: String, input: JsonElement?, fragment: String? = null): HttpUrl {
        val batchInput = mapOf("0" to TrpcJsonWrapper(input ?: JsonNull))
        return "$baseUrl/api/trpc/$procedure".toHttpUrl().newBuilder()
            .addQueryParameter("batch", "1")
            .addQueryParameter("input", jsonInstance.encodeToString(batchInput))
            .fragment(fragment)
            .build()
    }

    @Serializable
    private class TrpcJsonWrapper(val json: JsonElement)

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

    private fun novelListUrl(page: Int, baseInput: JsonObject): HttpUrl {
        val key = baseInput.toString().hashCode().toString()
        val input = if (page > 1) {
            val cursor = listingCursors[key]
                ?: throw Exception("Pagination cursor lost – please refresh from the first page")
            JsonObject(baseInput + ("cursor" to JsonPrimitive(cursor)))
        } else {
            baseInput
        }
        return trpcUrl("novel.getAll", input, fragment = key)
    }

    private fun novelListParse(response: Response): MangasPage {
        // Runs on a network thread – safe place to fill the genre/tag cache.
        ensureFilterOptionsLoaded()
        val data = jsonInstance.decodeFromJsonElement<NovelListJson>(response.trpcJson())
        response.request.url.fragment?.let { key ->
            data.nextCursor?.let { listingCursors[key] = it } ?: listingCursors.remove(key)
        }
        return MangasPage(data.novels.map { it.toSManga(mangaPathTemplate) }, data.nextCursor != null)
    }

    private fun baseListInput(sortBy: String, block: (MutableMap<String, JsonElement>.() -> Unit)? = null): JsonObject {
        val map = mutableMapOf<String, JsonElement>(
            "limit" to JsonPrimitive(20),
            "sortBy" to JsonPrimitive(sortBy),
        )
        block?.invoke(map)
        return JsonObject(map)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = novelListParse(client.get(novelListUrl(page, baseListInput("views")), headers))

    override suspend fun getLatestUpdates(page: Int): MangasPage = novelListParse(client.get(novelListUrl(page, baseListInput("latest")), headers))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
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
        return novelListParse(client.get(novelListUrl(page, input), headers))
    }

    // ---- Details ----

    private fun buildMangaDetailsUrl(manga: SManga): HttpUrl = trpcUrl("novel.getBySlug", SlugInput(manga.slug()).toJsonElement())

    @Serializable
    private class SlugInput(val slug: String)

    override fun getMangaUrl(manga: SManga): String = mangaPathTemplate.absolute(baseUrl, manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val manga = SManga.create().apply { this.url = mangaPathTemplate.slug(url.encodedPath) }
        val response = client.get(buildMangaDetailsUrl(manga), headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        // toSManga() already sets .url from the DTO's own slug field.
        return jsonInstance.decodeFromJsonElement<NovelDto>(response.trpcJson()).toSManga(mangaPathTemplate)
    }

    private fun SManga.slug(): String = mangaPathTemplate.resolve(url)
        .substringAfter("/novel/")
        .substringBefore('/')
        .substringBefore('?')

    // ---- Chapters ----

    private fun chapterPageUrl(slug: String, cursor: String?): HttpUrl = trpcUrl(
        "chapter.getListByNovel",
        ChapterListInput(novelSlug = slug, cursor = cursor).toJsonElement(requestJson),
        fragment = slug,
    )

    @Serializable
    private class ChapterListInput(val novelSlug: String, val limit: Int = 100, val cursor: String? = null)

    private suspend fun fetchAllChapters(slug: String): List<SChapter> {
        val chapters = mutableListOf<ChapterDto>()

        var data = jsonInstance.decodeFromJsonElement<ChapterListJson>(client.get(chapterPageUrl(slug, null), headers).trpcJson())
        chapters += data.chapters

        // Follow the cursor until the whole list is fetched (limit is capped at 100).
        while (data.nextCursor != null) {
            val next = client.get(chapterPageUrl(slug, data.nextCursor), headers)
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

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) {
            jsonInstance.decodeFromJsonElement<NovelDto>(client.get(buildMangaDetailsUrl(manga), headers).trpcJson()).toSManga(mangaPathTemplate)
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters) fetchAllChapters(manga.slug()) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    // Dates look like "2026-05-28T00:26:38.819Z"
    private fun parseDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        val normalized = date.substringBefore('.').removeSuffix("Z")
        return runCatching {
            LocalDateTime.parse(normalized, dateFormatter).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.getOrDefault(0L)
    }

    // ---- Chapter content ----

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    @Serializable
    private class PageChapterDto(
        val content: String,
        val translatorNote: String? = null,
    )

    override suspend fun fetchPageText(page: Page): String {
        val response = client.get(baseUrl + page.url, headers)
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

    // ---- Filter metadata (fetched once, cached in preferences) ----

    @Serializable
    private class FilterOption(val name: String, val slug: String)

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
            val batchInput = mapOf(
                "0" to TrpcJsonWrapper(JsonNull),
                "1" to TrpcJsonWrapper(JsonNull),
            )
            val url = "$baseUrl/api/trpc/search.getGenres,search.getTags".toHttpUrl().newBuilder()
                .addQueryParameter("batch", "1")
                .addQueryParameter("input", jsonInstance.encodeToString(batchInput))
                .build()
            val results = client.newCall(GET(url, headers)).execute().parseAs<JsonElement>().jsonArray

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

    override fun getFilterList(data: JsonElement?): FilterList {
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
    private class NovelListJson(
        val novels: List<NovelDto>,
        val nextCursor: String? = null,
    )

    @Serializable
    private class NovelDto(
        private val title: String,
        private val slug: String,
        private val author: String? = null,
        private val synopsis: String? = null,
        private val coverImage: String? = null,
        private val status: String? = null,
        private val totalChapters: Int? = null,
        private val views: Int? = null,
        private val rating: Double? = null,
        @SerialName("_count") private val count: CountDto? = null,
        private val genres: List<GenreWrapper> = emptyList(),
        private val tags: List<TagWrapper> = emptyList(),
    ) {
        fun toSManga(mangaPathTemplate: SlugPath): SManga = SManga.create().apply {
            url = mangaPathTemplate.slug("/novel/${this@NovelDto.slug}")
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
                .joinToString()
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
    private class CountDto(
        val bookmarks: Int? = null,
        val comments: Int? = null,
        val ratings: Int? = null,
    )

    @Serializable
    private class GenreWrapper(val genre: FilterOption? = null)

    @Serializable
    private class TagWrapper(val tag: FilterOption? = null)

    @Serializable
    private class ChapterListJson(
        val chapters: List<ChapterDto>,
        val nextCursor: String? = null,
    )

    @Serializable
    private class ChapterDto(
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

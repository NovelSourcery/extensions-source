package eu.kanade.tachiyomi.novelextension.en.atsumarunovels

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
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
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Entities
import java.time.Instant
import java.time.ZoneId

// Ported from keiyoushi (Atsumaru)
@Source
abstract class AtsumaruNovels :
    KeiSource(),
    NovelSource,
    ConfigurableSource,
    SourceTracker {

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(2)
    }

    override fun Headers.Builder.configureHeaders() = apply {
        add("Accept", "*/*")
    }

    private val prefs by getPreferencesLazy()

    override suspend fun getPopularManga(page: Int): MangasPage = browse("popular", page, "&timeframe=daily")

    override suspend fun getLatestUpdates(page: Int): MangasPage = browse("recentlyUpdated", page, "")

    private suspend fun browse(endpoint: String, page: Int, extra: String): MangasPage {
        val offset = (page - 1) * BROWSE_LIMIT
        val data = client.get(
            "$baseUrl/api/home2/$endpoint?offset=$offset&limit=$BROWSE_LIMIT&mediums=Novel$extra${adultQuery()}${excludedGenresQuery()}",
        ).parseAs<BrowseMangaDto>()

        return MangasPage(data.items.map { it.toSManga(baseUrl) }, data.items.size >= BROWSE_LIMIT)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val filterBy = mutableListOf("hidden:!=true", "medium:=[`Novel`]")
        var showAdult = adultQuery().isNotEmpty()
        var sortBy: String? = null

        val includedGenres = mutableListOf<String>()
        val excludedGenres = mutableListOf<String>()
        val includedTags = mutableListOf<String>()
        val excludedTags = mutableListOf<String>()

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> filter.state.forEachIndexed { index, state ->
                    when (state.state) {
                        Filter.TriState.STATE_INCLUDE -> includedGenres.add(filter.genreIds[index])
                        Filter.TriState.STATE_EXCLUDE -> excludedGenres.add(filter.genreIds[index])
                    }
                }

                is TagFilters -> filter.state.forEach { letter ->
                    letter.state.forEachIndexed { index, state ->
                        when (state.state) {
                            Filter.TriState.STATE_INCLUDE -> includedTags.add(letter.tagIds[index])
                            Filter.TriState.STATE_EXCLUDE -> excludedTags.add(letter.tagIds[index])
                        }
                    }
                }

                is TypeFilter -> filter.ids.filterIndexed { i, _ -> filter.state[i].state }
                    .takeIf { it.isNotEmpty() }
                    ?.let { types -> filterBy.add("type:=[${types.joinToString(",") { "`$it`" }}]") }

                is StatusFilter -> filter.ids.filterIndexed { i, _ -> filter.state[i].state }
                    .takeIf { it.isNotEmpty() }
                    ?.let { statuses -> filterBy.add("status:=[${statuses.joinToString(",") { "`$it`" }}]") }

                is YearFilter -> filter.state.toIntOrNull()?.let { filterBy.add("releaseYear:=[$it]") }

                is MinChaptersFilter -> filter.state.toIntOrNull()?.let { filterBy.add("chapterCount:>=$it") }

                is SortFilter -> filter.state?.let {
                    sortBy = SortFilter.VALUES[it.index] + ':' + if (it.ascending) "asc" else "desc"
                }

                is AdultFilter -> showAdult = filter.state || showAdult

                is OfficialFilter -> if (filter.state) filterBy.add("officialTranslation:=true")

                else -> {}
            }
        }

        if (includedGenres.isNotEmpty()) filterBy.add(includedGenres.joinToString(" && ") { "genreIds:=`$it`" })
        if (excludedGenres.isNotEmpty()) filterBy.add("genreIds:!=[${excludedGenres.joinToString(",") { "`$it`" }}]")
        if (includedTags.isNotEmpty()) filterBy.add(includedTags.joinToString(" && ") { "tagIds:=`$it`" })
        if (excludedTags.isNotEmpty()) filterBy.add("tagIds:!=[${excludedTags.joinToString(",") { "`$it`" }}]")
        if (!showAdult) filterBy.add("isAdult:=false")

        val url = "$baseUrl/collections/manga/documents/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("q", query.ifEmpty { "*" })
            addQueryParameter("filter_by", filterBy.joinToString(" && "))
            sortBy?.let { addQueryParameter("sort_by", it) }
            if (query.isNotEmpty()) {
                addQueryParameter("query_by", "title,englishTitle,otherNames,authors")
                addQueryParameter("query_by_weights", "4,3,2,1")
                addQueryParameter("num_typos", "4,3,2,1")
            }
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", BROWSE_LIMIT.toString())
        }.build()

        val data = client.get(url).parseAs<SearchResultsDto>()
        return MangasPage(data.hits.map { it.document.toSManga(baseUrl) }, data.hasNextPage())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.getOrNull(0) != "novel") return null
        val id = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return fetchDetails(id).toSMangaDetails(baseUrl).apply { initialized = true }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novel/${manga.url}"

    private suspend fun fetchDetails(id: String): MangaDto = client.get("$baseUrl/api/manga/page?id=$id").parseAs<MangaObjectDto>().mangaPage

    private suspend fun fetchAllChapters(id: String): List<ChapterDto> = client.get("$baseUrl/api/manga/allChapters?mangaId=$id").parseAs<AllChaptersDto>().chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        // Scanlator names live in the details response, so it's needed for the chapter list too
        val detailsDeferred = async { fetchDetails(manga.url) }
        val chaptersDeferred = async { if (fetchChapters) fetchAllChapters(manga.url) else null }

        val details = detailsDeferred.await()
        val chapterDtos = chaptersDeferred.await()

        val updatedManga = if (fetchDetails) details.toSMangaDetails(baseUrl) else manga
        val updatedChapters = chapterDtos?.let { buildChapterList(manga.url, details, it) } ?: chapters

        SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun buildChapterList(mangaId: String, details: MangaDto, dtos: List<ChapterDto>): List<SChapter> {
        val scanlatorMap = details.scanlators?.associate { it.id to it.name }.orEmpty()
        val blacklist = scanlatorBlacklist
        val filtered = if (blacklist.isEmpty()) {
            dtos
        } else {
            dtos.filter { chapter ->
                val name = chapter.scanlationMangaId?.let(scanlatorMap::get)
                name == null || name.trim().lowercase() !in blacklist
            }
        }
        val deduped = if (prefs.getBoolean(PREF_DEDUPLICATE_CHAPTERS, false)) {
            val scores = details.scanlators?.associate { it.id to it.score }.orEmpty()
            filtered.groupBy { it.number }.map { (_, uploads) ->
                uploads.maxWith(
                    compareBy<ChapterDto> { scores[it.scanlationMangaId] ?: Int.MIN_VALUE }
                        .thenBy { it.dateUploadMillis() },
                )
            }
        } else {
            filtered
        }

        return deduped.map { it.toSChapter(mangaId, it.scanlationMangaId?.let(scanlatorMap::get)) }
            .sortedWith(
                compareByDescending<SChapter> { it.chapter_number }
                    .thenBy { it.scanlator }
                    .thenByDescending { it.date_upload },
            )
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/novel/${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val (mangaId, chapterId) = page.url.split("/")
        val url = "$baseUrl/api/read/novelChapter".toHttpUrl().newBuilder()
            .addQueryParameter("mangaId", mangaId)
            .addQueryParameter("chapterId", chapterId)
            .build()

        val paragraphs = client.get(url).parseAs<NovelChapterObjectDto>().readNovelChapter.paragraphs
        if (paragraphs.isEmpty()) throw Exception("Chapter has no content")

        return paragraphs.joinToString("") { "<p>${Entities.escape(it)}</p>" }
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/api/explore/availableFilters").parseAs<FilterData>().toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList {
        val excludedGenres = prefs.getStringSet(PREF_EXCLUDE_GENRES, emptySet()).orEmpty()
        val filters = data?.parseAs<FilterData>()?.getFilterList(excludedGenres).orEmpty()

        return FilterList(
            filters + listOf(
                YearFilter(),
                MinChaptersFilter(),
                SortFilter(),
                AdultFilter(adultQuery().isNotEmpty()),
                OfficialFilter(),
            ),
        )
    }

    private val trackingEnabled: Boolean
        get() = prefs.getBoolean(PREF_ENABLE_TRACKING, false)

    override val supportsChapterTracking: Boolean
        get() = trackingEnabled

    override val supportsFavoritesTracking: Boolean
        get() = trackingEnabled

    override suspend fun onFavorited(manga: SManga, categories: List<String>) {
        if (!trackingEnabled) return
        val type = fetchDetails(manga.url).type ?: DEFAULT_TYPE
        syncBookmark(manga.url, prefs.getString(PREF_LIBRARY_STATUS, DEFAULT_STATUS), type)
    }

    override suspend fun onUnfavorited(manga: SManga, categories: List<String>) {
        if (!trackingEnabled) return
        syncBookmark(manga.url, null, null)
    }

    override suspend fun onChaptersRead(
        manga: SManga,
        changedChapters: List<SChapter>,
        allChapters: List<SChapter>,
        categories: List<String>,
    ) {
        if (!trackingEnabled) return
        val read = changedChapters.filter { it.isRead() }
        if (read.isEmpty()) return

        // syncProgress needs each chapter's scanlation id, which isn't kept on SChapter
        val scanlationIds = fetchAllChapters(manga.url).associate { it.id to it.scanlationMangaId }
        val now = System.currentTimeMillis()
        val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()

        val entries = read.mapNotNull { chapter ->
            val chapterId = chapter.url.substringAfter('/')
            val scanlationId = scanlationIds[chapterId] ?: return@mapNotNull null
            buildJsonObject {
                put("mangaScanlationId", scanlationId)
                put("mangaId", manga.url)
                put("chapterId", chapterId)
                put("page", 0)
                put("frac", 1)
                put("pages", 1)
                put("ts", now)
                put("strip", true)
                put("readMs", 0)
                put("day", day)
            }
        }

        entries.chunked(SYNC_CHUNK_SIZE).forEach { chunk ->
            val body = buildJsonObject { put("progress", buildJsonArray { chunk.forEach(::add) }) }
            client.post("$baseUrl/api/read/syncProgress", body.toJsonRequestBody()).close()
        }

        if (prefs.getBoolean(PREF_MARK_COMPLETED, true) && allChapters.isNotEmpty() && allChapters.all { it.isRead() }) {
            val type = fetchDetails(manga.url).type ?: DEFAULT_TYPE
            syncBookmark(manga.url, "Completed", type)
        }
    }

    /** A null [status] removes the bookmark, mirroring the site's own removeBookmark. */
    private suspend fun syncBookmark(mangaId: String, status: String?, type: String?) {
        val body = buildJsonArray {
            add(
                buildJsonObject {
                    put("mangaId", mangaId)
                    put("status", status)
                    put("type", type)
                    put("synced", false)
                    put("ts", System.currentTimeMillis())
                },
            )
        }
        client.post("$baseUrl/api/user/syncBookmarks", body.toJsonRequestBody()).close()
    }

    private fun SChapter.isRead(): Boolean = try {
        this::class.java.getMethod("getRead").invoke(this) as? Boolean ?: false
    } catch (_: Exception) {
        false
    }

    private fun adultQuery(): String = if (prefs.getBoolean(PREF_SHOW_18, false)) "&adult=1" else ""

    private fun excludedGenresQuery(): String {
        val ids = prefs.getStringSet(PREF_EXCLUDE_GENRES, emptySet()).orEmpty()
        if (ids.isEmpty()) return ""
        return "&excludedTags=${ids.joinToString(",")}"
    }

    private val scanlatorBlacklist: Set<String>
        get() = prefs.getString(PREF_SCANLATOR_BLACKLIST, "").orEmpty()
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_18
            title = "Toggle adult mode"
            summaryOff = "Safe (default)"
            summaryOn = "+18"
        }.let(screen::addPreference)

        val genreFilter = getFilterList().firstInstanceOrNull<GenreFilter>()
        val genres = genreFilter?.state.orEmpty()
        val genreIds = genreFilter?.genreIds.orEmpty()

        MultiSelectListPreference(screen.context).apply {
            key = PREF_EXCLUDE_GENRES
            title = "Exclude Genres from Browse"
            entries = genres.map { it.name }.toTypedArray()
            entryValues = genreIds.toTypedArray()
            setDefaultValue(emptySet<String>())
            setEnabled(genres.isNotEmpty())

            fun updateSummary(selected: Set<String>?) {
                summary = if (selected.isNullOrEmpty()) {
                    "None"
                } else {
                    val entryMap = entryValues.zip(entries).toMap()
                    selected.joinToString { entryMap[it] ?: it }
                }
            }

            updateSummary(prefs.getStringSet(PREF_EXCLUDE_GENRES, emptySet()))

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                updateSummary(newValue as Set<String>)
                true
            }
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DEDUPLICATE_CHAPTERS
            title = "Deduplicate chapters"
            summary = "Show one upload per chapter, from the highest rated group that has it"
            setDefaultValue(false)
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_SCANLATOR_BLACKLIST
            title = "Excluded scanlators"
            summary = "Comma-separated group names to exclude from the chapter list"
            dialogTitle = "Excluded scanlators"
            setDefaultValue("")
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ENABLE_TRACKING
            title = "Enable $name tracking"
            summary = "Sync library bookmarks and read chapters to your $name account. Requires being logged in via WebView."
            setDefaultValue(false)
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LIBRARY_STATUS
            title = "Bookmark status on add to library"
            entries = STATUS_LABELS
            entryValues = STATUS_VALUES
            setDefaultValue(DEFAULT_STATUS)
            summary = "%s"
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_MARK_COMPLETED
            title = "Mark Completed when all chapters are read"
            summary = "Set the site bookmark to Completed once every chapter is read."
            setDefaultValue(true)
        }.let(screen::addPreference)
    }

    companion object {
        private const val PREF_SHOW_18 = "pref_18_mode"
        private const val PREF_EXCLUDE_GENRES = "pref_exclude_genres"
        private const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"
        private const val PREF_SCANLATOR_BLACKLIST = "pref_scanlator_blacklist"
        private const val PREF_ENABLE_TRACKING = "pref_enable_tracking"
        private const val PREF_LIBRARY_STATUS = "pref_library_status"
        private const val PREF_MARK_COMPLETED = "pref_mark_completed"

        private const val BROWSE_LIMIT = 40
        private const val SYNC_CHUNK_SIZE = 500
        private const val DEFAULT_TYPE = "Manga"
        private const val DEFAULT_STATUS = "Reading"

        private val STATUS_VALUES = arrayOf("Reading", "PlanToRead", "ReReading", "OnHold", "Completed", "Dropped")
        private val STATUS_LABELS = arrayOf("Reading", "Plan to Read", "Re-reading", "On Hold", "Completed", "Dropped")
    }
}

package eu.kanade.tachiyomi.novelextension.en.konkon

import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.SourceTracker
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.stripChapterNumberPrefix
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Konkon.ink - runs the same novel platform/API as KuuPress.
 */
class Konkon :
    HttpSource(),
    NovelSource,
    ConfigurableSource,
    SourceTracker {

    override val name = "Konkon"
    override val baseUrl = "https://konkon.ink"
    override val lang = "en"
    override val supportsLatest = true

    private val apiBase = "https://api-k.konkon.ink/api/public"
    private val mediaProxyBase = "https://api-k.konkon.ink"

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val showLockedChapters: Boolean
        get() = preferences.getBoolean(PREF_SHOW_LOCKED, false)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "application/json, text/plain, */*")

    override fun popularMangaRequest(page: Int): Request {
        val url = "$apiBase/novels_trending".toHttpUrl().newBuilder()
            .addQueryParameter("limit", "10")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body.string()
        return parseNovelArrayResponse(body, key = "data", hasMore = false)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiBase/latest-updates".toHttpUrl().newBuilder()
            .addQueryParameter("per_page", "15")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val body = response.body.string()
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonArray ?: JsonArray(emptyList())
            val meta = root["meta"]?.jsonObject
            val currentPage = meta?.int("current_page") ?: 1
            val lastPage = meta?.int("last_page") ?: 1
            parseNovelArray(data, currentPage < lastPage)
        } catch (_: Exception) {
            MangasPage(emptyList(), false)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterQuery = (filters.find { it is SearchQueryFilter } as? SearchQueryFilter)
            ?.state?.trim()
            .orEmpty()
        val term = query.trim().ifBlank { filterQuery }

        if (term.startsWith("http")) {
            val slug = extractSlug(term)
            if (slug.isNotBlank()) {
                val url = "$apiBase/novels/$slug".toHttpUrl().newBuilder()
                    .addQueryParameter("page", "1")
                    .addQueryParameter("per_page", "1")
                    .build()
                return GET(url, headers)
            }
        }

        if (term.isNotBlank()) {
            val url = "$apiBase/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", term)
                .build()
            return GET(url, headers)
        }

        return latestUpdatesRequest(page)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val body = response.body.string()
        val path = response.request.url.encodedPath
        return if (path.contains("/novels/")) {
            // Single-novel lookup from a URL search
            try {
                val root = json.parseToJsonElement(body).jsonObject
                val data = root["data"]?.jsonObject ?: return MangasPage(emptyList(), false)
                val slug = data.str("slug").orEmpty()
                if (slug.isBlank()) return MangasPage(emptyList(), false)
                val manga = SManga.create().apply {
                    title = cleanHtml(data.str("title").orEmpty())
                    url = "/read/$slug"
                    thumbnail_url = coverUrlFrom(data)
                }
                MangasPage(listOf(manga), false)
            } catch (_: Exception) {
                MangasPage(emptyList(), false)
            }
        } else if (path.contains("/search")) {
            parseNovelArrayResponse(body, key = "results", hasMore = false)
        } else {
            try {
                val root = json.parseToJsonElement(body).jsonObject
                val data = root["data"]?.jsonArray ?: JsonArray(emptyList())
                val meta = root["meta"]?.jsonObject
                val currentPage = meta?.int("current_page") ?: 1
                val lastPage = meta?.int("last_page") ?: 1
                parseNovelArray(data, currentPage < lastPage)
            } catch (_: Exception) {
                MangasPage(emptyList(), false)
            }
        }
    }

    /**
     * Extracts the novel slug from the stored site path ("/read/<slug>"), full
     */
    private fun extractSlug(raw: String): String = raw
        .substringBefore('?')
        .substringBefore('#')
        .replace(Regex("^https?://[^/]+"), "")
        .trim('/')
        .removePrefix("novels/")
        .removePrefix("novel/")
        .removePrefix("read/")
        .trim('/')

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = extractSlug(manga.url)
        val url = "$apiBase/novels/$slug".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("per_page", "100")
            .build()
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body.string()
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonObject ?: JsonObject(emptyMap())
            val slug = data.str("slug").orEmpty()
            val authorName = data.str("author_name")?.takeIf { it.isNotBlank() }
                ?: data.str("author_user_name")?.takeIf { it.isNotBlank() }
                ?: data["author"]?.jsonObject?.str("name")?.takeIf { it.isNotBlank() }

            val genres = data["genres"]?.jsonArray
                ?.mapNotNull { it.jsonObject.str("name") }
                ?.filter { it.isNotBlank() }
                .orEmpty()

            val tags = data["tags"]?.jsonArray
                ?.mapNotNull { it.jsonObject.str("name") }
                ?.filter { it.isNotBlank() }
                .orEmpty()

            val infoLines = mutableListOf<String>()
            data.str("tagline")?.takeIf { it.isNotBlank() && !it.equals("null", true) }
                ?.let { infoLines.add(cleanHtml(it)) }
            authorName?.let { infoLines.add("Author: $it") }
            data.str("novel_status")?.takeIf { it.isNotBlank() }?.let { infoLines.add("Status: $it") }
            data.int("total_views")?.let { infoLines.add("Views: $it") }
            data.int("bookmarks_count")?.let { infoLines.add("Bookmarks: $it") }
            data.int("reviews_count")?.let { infoLines.add("Reviews: $it") }
            data["chapters_pagination"]?.jsonObject?.int("total")?.let { infoLines.add("Chapters: $it") }

            val descriptionText = formatDescription(data.str("description").orEmpty())

            cacheBookmarkState(slug, root)

            SManga.create().apply {
                title = cleanHtml(data.str("title").orEmpty())
                url = "/read/$slug"
                author = authorName
                thumbnail_url = coverUrlFrom(data)
                status = when (data.str("novel_status")?.lowercase()) {
                    "ongoing" -> SManga.ONGOING
                    "completed" -> SManga.COMPLETED
                    "hiatus" -> SManga.ON_HIATUS
                    "dropped" -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
                genre = (genres + tags).distinctBy { it.lowercase() }.joinToString(", ")
                description = buildString {
                    if (infoLines.isNotEmpty()) {
                        append(infoLines.joinToString("\n"))
                        append("\n\n")
                    }
                    append(descriptionText)
                }.trim()
            }
        } catch (_: Exception) {
            SManga.create()
        }
    }

    override fun chapterListRequest(manga: SManga): Request = mangaDetailsRequest(manga)

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonObject ?: JsonObject(emptyMap())
        cacheBookmarkState(data.str("slug").orEmpty(), root)
        return parseChaptersFromNovelData(data)
            .sortedByDescending { it.sortKey }
            .map { it.chapter }
    }

    override fun fetchChapterList(manga: SManga): rx.Observable<List<SChapter>> = rx.Observable.fromCallable {
        val slug = extractSlug(manga.url)
        if (slug.isBlank()) return@fromCallable emptyList<SChapter>()

        val chapterMap = linkedMapOf<String, ChapterRecord>()

        fun merge(records: List<ChapterRecord>) {
            records.forEach { record ->
                val existing = chapterMap[record.chapter.url]
                if (existing == null || record.sortKey < existing.sortKey) {
                    chapterMap[record.chapter.url] = record
                }
            }
        }

        val firstData = fetchNovelData(slug, page = 1, perPage = 100) ?: return@fromCallable emptyList<SChapter>()
        val volumeSequenceById = buildVolumeSequenceMap(firstData)
        merge(parseChaptersFromNovelData(firstData, volumeSequenceById = volumeSequenceById))

        val expectedTotal = firstData["chapters_pagination"]?.jsonObject?.int("total") ?: 0

        // Volume-specific paging: preserves Act/Volume order and fills chapters omitted by default payload.
        if (expectedTotal <= 0 || chapterMap.size < expectedTotal) {
            for (volumeId in volumeSequenceById.keys) {
                var page = 1
                var guard = 0
                while (guard < 30) {
                    val data = fetchNovelData(slug, page = page, perPage = 100, volumeId = volumeId) ?: break
                    val records = parseChaptersFromNovelData(
                        data,
                        volumeSequenceById = volumeSequenceById,
                        restrictVolumeId = volumeId,
                    )
                    if (records.isEmpty()) break

                    val before = chapterMap.size
                    merge(records)
                    val added = chapterMap.size - before

                    if (records.size < 100 || added == 0) break
                    page++
                    guard++
                }
            }
        }

        // Fallback to global pagination when still short.
        val lastPage = firstData["chapters_pagination"]?.jsonObject?.int("last_page") ?: 1
        if ((expectedTotal <= 0 || chapterMap.size < expectedTotal) && lastPage > 1) {
            for (page in 2..lastPage) {
                val data = fetchNovelData(slug, page = page, perPage = 100) ?: continue
                merge(parseChaptersFromNovelData(data, volumeSequenceById = volumeSequenceById))
            }
        }

        chapterMap.values
            .sortedByDescending { it.sortKey }
            .map { it.chapter }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        // "/read/chapter/<id>/<slug>" (current) or "/chapter/<id>" (legacy)
        val chapterId = chapter.url.substringBefore('?').trim('/').split('/')
            .firstOrNull { seg -> seg.isNotEmpty() && seg.all(Char::isDigit) }
            ?: chapter.url.substringAfterLast('/')
        return GET("$apiBase/chapters/$chapterId", headers)
    }

    // chapter.url is the site path; route legacy "/chapter/<id>" entries through /read
    override fun getChapterUrl(chapter: SChapter): String = if (chapter.url.startsWith("/read/")) {
        baseUrl + chapter.url
    } else {
        "$baseUrl/read${chapter.url}"
    }

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

    override suspend fun fetchPageText(page: Page): String {
        // page.url may be the API stub (.../chapters/<id>) or the site chapter path
        // (/read/chapter/<id>/<slug>); always resolve to the JSON content endpoint.
        val contentUrl = if (page.url.contains("/api/public/chapters/")) {
            page.url
        } else {
            val chapterId = page.url.substringBefore('?').trim('/').split('/')
                .firstOrNull { seg -> seg.isNotEmpty() && seg.all(Char::isDigit) }
                ?: page.url.substringAfterLast('/')
            "$apiBase/chapters/$chapterId"
        }
        val response = client.newCall(GET(contentUrl, headers)).execute()
        val body = response.body.string()

        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonObject ?: JsonObject(emptyMap())
            val content = data.str("content").orEmpty()
            val authorNote = data.str("author_note").orEmpty().trim()

            if (content.isBlank()) {
                return "No chapter content found."
            }

            if (authorNote.isBlank()) {
                content
            } else {
                val note = Jsoup.parse(authorNote).text()
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("<br>")
                "$content<hr /><p><strong>Author note:</strong></p><p>$note</p>"
            }
        } catch (_: Exception) {
            "Failed to parse chapter content."
        }
    }

    override fun getMangaUrl(manga: SManga): String {
        val slug = extractSlug(manga.url)
        return "$baseUrl/read/$slug"
    }

    override fun imageUrlParse(response: Response): String = ""

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Search"),
        SearchQueryFilter(),
    )


    override val supportsChapterTracking = false

    override val supportsFavoritesTracking: Boolean
        get() = preferences.getBoolean(PREF_ENABLE_TRACKING, false)

    override suspend fun onFavorited(manga: SManga, categories: List<String>) = syncBookmark(manga, desired = true)

    override suspend fun onUnfavorited(manga: SManga, categories: List<String>) = syncBookmark(manga, desired = false)

    private val bookmarkStateCache = mutableMapOf<String, Boolean>()

    private fun cacheBookmarkState(slug: String, root: JsonObject) {
        if (slug.isBlank()) return
        val bookmarked = root["meta"]?.jsonObject?.bool("is_bookmarked") ?: return
        synchronized(bookmarkStateCache) { bookmarkStateCache[slug] = bookmarked }
    }

    /**
     * The /bookmark endpoint is a toggle, so first resolve the current state
     * (cached from the details/chapter list responses, or fetched fresh) and
     * only POST when it differs from the desired one.
     */
    private fun syncBookmark(manga: SManga, desired: Boolean) {
        if (!supportsFavoritesTracking) return
        val slug = extractSlug(manga.url)
        if (slug.isBlank()) return

        val current = synchronized(bookmarkStateCache) { bookmarkStateCache[slug] }
            ?: fetchBookmarkState(slug)
        if (current == desired) return

        postBookmarkToggle(slug)?.let { newState ->
            synchronized(bookmarkStateCache) { bookmarkStateCache[slug] = newState }
        }
    }

    /** Reads meta.is_bookmarked from the novel details endpoint. Null when not logged in. */
    private fun fetchBookmarkState(slug: String): Boolean? = try {
        val url = "$apiBase/novels/$slug".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("per_page", "1")
            .build()
        val response = client.newCall(GET(url, headers)).execute()
        val root = json.parseToJsonElement(response.use { it.body.string() }).jsonObject
        val state = root["meta"]?.jsonObject?.bool("is_bookmarked")
        state?.also { synchronized(bookmarkStateCache) { bookmarkStateCache[slug] = it } }
    } catch (_: Exception) {
        null
    }

    /** POSTs the bookmark toggle and returns the new state, or null on failure. */
    private fun postBookmarkToggle(slug: String): Boolean? = try {
        val requestHeaders = headers.newBuilder().apply {
            // Laravel Sanctum CSRF: mirror the XSRF-TOKEN cookie into the header
            xsrfToken()?.let { set("X-XSRF-TOKEN", it) }
        }.build()
        val response = client.newCall(POST("$apiBase/novels/$slug/bookmark", requestHeaders)).execute()
        val body = response.use { it.body.string() }
        if (!response.isSuccessful) {
            null
        } else {
            json.parseToJsonElement(body).jsonObject["data"]?.jsonObject?.bool("bookmarked")
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Reads the XSRF-TOKEN cookie for the API host, requesting the Sanctum
     * csrf-cookie endpoint first when it's missing.
     */
    private fun xsrfToken(): String? {
        fun read(): String? = client.cookieJar.loadForRequest(apiBase.toHttpUrl())
            .find { it.name == "XSRF-TOKEN" }?.value
        val raw = read() ?: run {
            runCatching {
                client.newCall(GET("$mediaProxyBase/sanctum/csrf-cookie", headers)).execute().close()
            }
            read()
        } ?: return null
        return java.net.URLDecoder.decode(raw, "UTF-8")
    }


    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Include coin-locked chapters you don't have access to in the chapter list (marked with 🔒)."
            setDefaultValue(false)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ENABLE_TRACKING
            title = "Sync library to $name bookmarks"
            summary = "Bookmark/unbookmark novels on $name when you add or remove them from your library. Requires being logged in via WebView."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_SHOW_LOCKED = "pref_show_locked_chapters"
        private const val PREF_ENABLE_TRACKING = "pref_enable_tracking"
    }

    private fun parseNovelArrayResponse(body: String, key: String, hasMore: Boolean): MangasPage = try {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root[key]?.jsonArray ?: JsonArray(emptyList())
        parseNovelArray(data, hasMore)
    } catch (_: Exception) {
        MangasPage(emptyList(), false)
    }

    private fun parseNovelArray(data: JsonArray, hasMore: Boolean): MangasPage {
        val novels = data.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val slug = obj.str("slug") ?: return@mapNotNull null
            val title = cleanHtml(obj.str("title").orEmpty())
            if (title.isBlank()) return@mapNotNull null

            SManga.create().apply {
                this.title = title
                this.url = "/read/$slug"
                this.thumbnail_url = coverUrlFrom(obj)
                this.author = obj.str("author_name")?.takeIf { it.isNotBlank() }
                    ?: obj["author"]?.jsonObject?.str("name")
            }
        }

        return MangasPage(novels, hasMore)
    }

    private fun parseChaptersFromNovelData(
        data: JsonObject,
        volumeSequenceById: Map<Int, Int> = buildVolumeSequenceMap(data),
        restrictVolumeId: Int? = null,
    ): List<ChapterRecord> {
        val records = mutableListOf<ChapterRecord>()

        val volumes = data["volumes"]?.jsonArray ?: JsonArray(emptyList())
        volumes.forEachIndexed { volumeIndex, volElement ->
            val volume = runCatching { volElement.jsonObject }.getOrNull() ?: return@forEachIndexed
            val volumeId = volume.int("id") ?: return@forEachIndexed
            if (restrictVolumeId != null && volumeId != restrictVolumeId) return@forEachIndexed

            val volumeTitle = cleanHtml(volume.str("title").orEmpty())
            val volumeSequence = volumeSequenceById[volumeId] ?: parseVolumeSequence(volume, volumeIndex + 1)

            val chapterArray = volume["chapters"]?.jsonArray ?: JsonArray(emptyList())
            var fallbackSort = 1

            chapterArray.forEach { chapElement ->
                val chapter = runCatching { chapElement.jsonObject }.getOrNull() ?: return@forEach
                val chapterId = chapter.int("id") ?: return@forEach
                val chapterTitle = cleanHtml(chapter.str("title").orEmpty())

                val sortOrder = chapter.int("sort_order")
                    ?: chapter.int("order")
                    ?: chapterSortFromTitle(chapterTitle)
                    ?: fallbackSort
                fallbackSort++

                val chapterNumber = chapterNumberFromTitle(chapterTitle) ?: sortOrder.toFloat()
                val sortKey = volumeSequence.toLong() * 100_000L + sortOrder.toLong()

                val locked = chapter.bool("is_locked") ?: chapter.bool("locked") ?: false
                val hasAccess = chapter.bool("user_has_access") ?: true
                val isLocked = locked && !hasAccess
                if (isLocked && !showLockedChapters) return@forEach

                val baseTitle = if (volumeTitle.isNotBlank() && !chapterTitle.startsWith(volumeTitle, ignoreCase = true)) {
                    "$volumeTitle - $chapterTitle"
                } else {
                    chapterTitle
                }
                val visibleTitle = if (isLocked) {
                    "🔒 $baseTitle"
                } else {
                    baseTitle
                }

                val sChapter = SChapter.create().apply {
                    // Site chapter path: /read/chapter/<id>/<slug> — the slug is
                    // mandatory (404 without); slugify the title when missing
                    val chapterSlug = chapter.str("slug").orEmpty().ifBlank {
                        chapterTitle.lowercase()
                            .replace(Regex("[^a-z0-9]+"), "-")
                            .trim('-')
                    }
                    url = "/read/chapter/$chapterId/$chapterSlug"
                    name = visibleTitle.stripChapterNumberPrefix().ifBlank { "Chapter $sortOrder" }
                    chapter_number = chapterNumber
                    date_upload = parseDate(chapter.str("scheduled_for") ?: chapter.str("created_at"))
                }
                records.add(ChapterRecord(sChapter, sortKey))
            }
        }

        return records
            .distinctBy { it.chapter.url }
            .sortedBy { it.sortKey }
    }

    private fun fetchNovelData(slug: String, page: Int, perPage: Int, volumeId: Int? = null): JsonObject? {
        return try {
            val builder = "$apiBase/novels/$slug".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("per_page", perPage.toString())
            volumeId?.let { builder.addQueryParameter("volume_id", it.toString()) }

            val response = client.newCall(GET(builder.build().toString(), headers)).execute()
            if (!response.isSuccessful) return null
            val root = json.parseToJsonElement(response.body.string()).jsonObject
            cacheBookmarkState(slug, root)
            root["data"]?.jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun buildVolumeSequenceMap(data: JsonObject): Map<Int, Int> {
        val volumeObjects = data["volumes"]?.jsonArray
            ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            .orEmpty()

        val orderValues = volumeObjects.mapNotNull { it.int("order")?.takeIf { order -> order > 0 } }
        val hasMeaningfulOrder = orderValues.distinct().size > 1

        val result = linkedMapOf<Int, Int>()
        volumeObjects.forEachIndexed { index, volume ->
            val volumeId = volume.int("id") ?: return@forEachIndexed
            val title = volume.str("title").orEmpty()
            val seq = when {
                hasMeaningfulOrder -> volume.int("order")?.takeIf { it > 0 }
                else -> null
            } ?: parseActOrVolumeNumber(title)
                ?: (index + 1)
            result[volumeId] = seq
        }
        return result
    }

    private fun parseVolumeSequence(volume: JsonObject, fallback: Int): Int = parseActOrVolumeNumber(volume.str("title").orEmpty())
        ?: volume.int("order")?.takeIf { it > 0 }
        ?: fallback

    private fun parseActOrVolumeNumber(title: String): Int? {
        if (title.isBlank()) return null
        return Regex("""(?i)\b(?:act|vol(?:ume)?)\s*(\d+)\b""")
            .find(title)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun chapterNumberFromTitle(title: String): Float? {
        if (title.isBlank()) return null
        return Regex("""(?i)\bchapter\s*([0-9]+(?:\.[0-9]+)?)\b""")
            .find(title)
            ?.groupValues
            ?.getOrNull(1)
            ?.toFloatOrNull()
    }

    private fun chapterSortFromTitle(title: String): Int? = chapterNumberFromTitle(title)?.toInt()

    private fun coverUrlFrom(obj: JsonObject): String? {
        val preferredKey = obj.str("featured_image_key")
            ?: obj.str("featured_image_thumb_medium_key")
            ?: obj.str("featured_image_thumb_small_key")
        mediaProxyUrlFromKey(preferredKey)?.let { return it }

        val fullUrl = obj.str("featured_image_url")
            ?: obj.str("featured_image_thumb_medium_url")
            ?: obj.str("featured_image_thumb_small_url")
        if (!fullUrl.isNullOrBlank()) return fullUrl

        val fallbackKey = obj.str("featured_image")
        mediaProxyUrlFromKey(fallbackKey)?.let { return it }

        return null
    }

    private fun mediaProxyUrlFromKey(raw: String?): String? {
        val key = normalizeMediaKey(raw) ?: return null
        if (key.startsWith("api/media/k/")) {
            return "$mediaProxyBase/${key.trimStart('/')}"
        }

        val encodedKey = Base64.encodeToString(key.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "$mediaProxyBase/api/media/k/$encodedKey"
    }

    private fun normalizeMediaKey(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null

        if (value.startsWith("http://") || value.startsWith("https://")) {
            val parsed = runCatching { value.toHttpUrl() }.getOrNull() ?: return null
            val path = parsed.encodedPath.trimStart('/')
            if (path.startsWith("api/media/k/")) return path

            return when {
                path.startsWith("storage/") -> path.removePrefix("storage/")
                else -> path
            }.ifBlank { null }
        }

        return when {
            value.startsWith("/storage/") -> value.removePrefix("/storage/")
            value.startsWith("storage/") -> value.removePrefix("storage/")
            else -> value.trimStart('/')
        }.ifBlank { null }
    }

    private fun cleanHtml(text: String): String {
        val decoded = Parser.unescapeEntities(text, false)
        return Jsoup.parse(decoded).text().trim().replace(Regex("\\s+"), " ")
    }

    private fun formatDescription(rawHtml: String): String {
        if (rawHtml.isBlank()) return ""

        val breakToken = "__KONKON_BR__"
        val paragraphToken = "__KONKON_P__"
        val doc = Jsoup.parseBodyFragment(Parser.unescapeEntities(rawHtml, false))

        doc.select("br").forEach { it.after(breakToken) }
        doc.select("p").forEach { it.after(paragraphToken) }

        return doc.text()
            .replace(Regex("\\s*$paragraphToken\\s*"), "\n\n")
            .replace(Regex("\\s*$breakToken\\s*"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun parseDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
    }

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

    private data class ChapterRecord(
        val chapter: SChapter,
        val sortKey: Long,
    )

    private class SearchQueryFilter : Filter.Text("Search query")
}

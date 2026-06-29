package eu.kanade.tachiyomi.novelextension.en.fictionzone

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.setAltTitles
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class FictionZone :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "Fiction Zone"

    override val baseUrl = "https://fictionzone.net"

    override val isNovelSource = true

    private val apiUrl = "$baseUrl/api/__api_party/fictionzone"

    override val lang = "en"

    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val json: Json by injectLazy()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private fun getAccessToken(): String? {
        val cookieManager = android.webkit.CookieManager.getInstance()
        val cookies = cookieManager.getCookie(baseUrl)

        if (cookies != null) {
            val accessTokenMatch = Regex("""fz_access_token=([^;]+)""").find(cookies)
            if (accessTokenMatch != null) {
                return accessTokenMatch.groupValues[1]
            }
            val refreshTokenMatch = Regex("""fz_refresh_token=([^;]+)""").find(cookies)
            if (refreshTokenMatch != null) {
                return refreshTokenMatch.groupValues[1]
            }
        }
        return preferences.getString("fz_access_token", null)
    }

    private fun apiRequest(path: String, method: String = "GET", includeAuth: Boolean = true, bodyJson: JsonObject? = null): Request {
        val timestamp = java.time.Instant.now().toString()
        val headers = buildJsonArray {
            add(
                buildJsonArray {
                    add(JsonPrimitive("content-type"))
                    add(JsonPrimitive("application/json"))
                },
            )
            add(
                buildJsonArray {
                    add(JsonPrimitive("x-request-time"))
                    add(JsonPrimitive(timestamp))
                },
            )
            if (includeAuth) {
                val token = getAccessToken()
                if (token != null) {
                    add(
                        buildJsonArray {
                            add(JsonPrimitive("authorization"))
                            add(JsonPrimitive("Bearer $token"))
                        },
                    )
                }
            }
        }

        val body = buildJsonObject {
            put("path", JsonPrimitive(path))
            put("headers", headers)
            put("method", JsonPrimitive(method))
            if (bodyJson != null) {
                put("body", bodyJson)
            }
        }

        val requestBody = body.toString().toRequestBody("application/json".toMediaType())
        return POST(apiUrl, this.headers, requestBody)
    }

    override fun popularMangaRequest(page: Int): Request = apiRequest("/platform/browse?page=$page&page_size=20&sort_by=bookmark_count&sort_order=desc&include_genres=true")

    override fun popularMangaParse(response: Response): MangasPage {
        val jsonString = response.body.string()
        val jsonObject = json.parseToJsonElement(jsonString).jsonObject
        val data = jsonObject["data"]?.jsonObject ?: return MangasPage(emptyList(), false)
        return parseBrowseData(data)
    }

    private fun parseBrowseData(data: JsonObject): MangasPage {
        val novels = data["novels"]?.jsonArray ?: return MangasPage(emptyList(), false)

        val mangas = novels.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                SManga.create().apply {
                    title = obj["title"]!!.jsonPrimitive.content
                    val slug = obj["slug"]?.jsonPrimitive?.contentOrNull
                    val sourceKey = obj["source_key"]?.jsonPrimitive?.contentOrNull
                    val sourceId = obj["source_id"]?.jsonPrimitive?.contentOrNull

                    url = if (slug != null) {
                        "/novel/$slug"
                    } else if (sourceKey != null && sourceId != null) {
                        "/omniportal/$sourceId/$sourceKey"
                    } else {
                        "/novel/unknown"
                    }

                    // Omniportal entries keep this as the only synopsis source
                    obj["synopsis"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                        description = formatDescription(it)
                    }

                    thumbnail_url = try {
                        val img = obj["image"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                            ?: obj["cover_image"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        if (img != null) {
                            if (img.startsWith("http")) img else "https://cdn.fictionzone.net/insecure/rs:fill:165:250/$img.webp"
                        } else {
                            null
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        // Platform responses carry has_next; omniportal ones only page/total_pages
        // (their has_more field is unreliable)
        val pagination = data["pagination"]?.jsonObject
        val hasNext = pagination?.get("has_next")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: run {
                val page = pagination?.get("page")?.jsonPrimitive?.int
                val totalPages = pagination?.get("total_pages")?.jsonPrimitive?.int
                page != null && totalPages != null && page < totalPages
            }

        return MangasPage(mangas, hasNext)
    }

    override fun latestUpdatesRequest(page: Int): Request = apiRequest("/platform/browse?page=$page&page_size=20&sort_by=created_at&sort_order=desc&include_genres=true")

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // AI semantic search — text query only, other filters don't apply
        val aiSearch = filters.find { it is AiSearchFilter } as? AiSearchFilter
        if (aiSearch?.state == true && query.isNotBlank()) {
            val body = buildJsonObject {
                put("query", JsonPrimitive(query))
                put("limit", JsonPrimitive(20))
                put("offset", JsonPrimitive((page - 1) * 20))
            }
            return apiRequest("/ai/search", "POST", includeAuth = false, bodyJson = body)
        }

        val sourceFilter = filters.find { it is SourceFilter } as? SourceFilter
        val sourceId = sourceFilter?.toUriPart() ?: "fictionzone"

        return when (sourceId) {
            "fictionzone", "all" -> buildPlatformSearchRequest(page, query, filters)

            else -> {
                if (query.isNotEmpty()) {
                    apiRequest("/omniportal/search?source_id=$sourceId&query=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page&translate=en&engine=google-trans", "GET", includeAuth = true)
                } else {
                    apiRequest("/omniportal/browse/genre?source_id=$sourceId&genre=all&page=$page&translate=en&engine=google-trans", "GET", includeAuth = true)
                }
            }
        }
    }

    private fun buildPlatformSearchRequest(page: Int, query: String, filters: FilterList): Request {
        val params = mutableListOf<String>()
        params.add("page=$page")
        params.add("page_size=20")
        params.add("include_genres=true")
        params.add("include_tags=true")

        if (query.isNotEmpty()) {
            params.add("search=${java.net.URLEncoder.encode(query, "UTF-8")}")
            params.add("search_in_synopsis=true")
        }

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    val sort = filter.toUriPart()
                    params.add("sort_by=${sort.first}")
                    params.add("sort_order=${sort.second}")
                }

                is StatusSelectFilter -> {
                    filter.toUriPart()?.let { params.add("status_filter=$it") }
                }

                is WordCountMinFilter -> {
                    filter.state.trim().toIntOrNull()?.let { params.add("word_count_min=$it") }
                }

                is WordCountMaxFilter -> {
                    filter.state.trim().toIntOrNull()?.let { params.add("word_count_max=$it") }
                }

                is GenreFilter -> {
                    val genres = filter.state.filter { it.state }.map { it.id }.joinToString(",")
                    if (genres.isNotEmpty()) params.add("genre_ids=$genres")
                }

                is TagFilter -> {
                    val includeTags = filter.state.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.id }.joinToString(",")
                    val excludeTags = filter.state.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.id }.joinToString(",")
                    if (includeTags.isNotEmpty()) params.add("tag_ids=$includeTags")
                    if (excludeTags.isNotEmpty()) params.add("exclude_tag_ids=$excludeTags")
                }

                else -> {}
            }
        }
        return apiRequest("/platform/browse?${params.joinToString("&")}")
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val jsonString = response.body.string()
        val jsonObject = json.parseToJsonElement(jsonString).jsonObject
        val data = jsonObject["data"]?.jsonObject ?: return MangasPage(emptyList(), false)

        // AI search responses carry data.results instead of data.novels
        data["results"]?.jsonArray?.let { results ->
            val mangas = results.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    SManga.create().apply {
                        title = obj["title"]?.jsonPrimitive?.contentOrNull ?: slug
                        url = "/novel/$slug"
                        description = obj["synopsis"]?.jsonPrimitive?.contentOrNull
                            ?.let { formatDescription(it) }
                        thumbnail_url = obj["cover_image_url"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotBlank() }
                            ?.let { "https://cdn.fictionzone.net/insecure/rs:fill:165:250/$it.webp" }
                    }
                } catch (_: Exception) {
                    null
                }
            }
            return MangasPage(mangas, mangas.size >= 20)
        }

        return parseBrowseData(data)
    }
    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url
    override fun mangaDetailsRequest(manga: SManga): Request {
        if (manga.url.startsWith("/omniportal/")) {
            val parts = manga.url.removePrefix("/omniportal/").split("/")
            val sourceId = parts[0]
            val sourceKey = parts[1]
            return apiRequest("/omniportal/novels/details?source_id=$sourceId&source_key=$sourceKey&translate=en")
        }

        val slug = manga.url.substringAfter("/novel/")
        return apiRequest("/platform/novel-details?slug=$slug")
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val jsonString = response.body.string()
        val jsonObject = json.parseToJsonElement(jsonString).jsonObject

        val dataElement = jsonObject["data"]!!
        val data = if (dataElement.jsonObject.containsKey("novel")) {
            dataElement.jsonObject["novel"]!!.jsonObject
        } else {
            dataElement.jsonObject
        }

        return SManga.create().apply {
            title = data["title"]!!.jsonPrimitive.content

            val altTitlesList = data["alt_titles"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                ?.filter { it.isNotBlank() && it != title }
                ?: emptyList()

            val synopsis = formatDescription(data["synopsis"]?.jsonPrimitive?.contentOrNull.orEmpty())
            description = synopsis
            if (altTitlesList.isNotEmpty()) {
                setAltTitles(altTitlesList)
            }

            val genresList = data["genres"]?.jsonArray?.mapNotNull { element ->
                try {
                    element.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                } catch (e: Exception) {
                    element.jsonPrimitive.contentOrNull
                }
            } ?: emptyList()
            val tagsList = data["tags"]?.jsonArray?.mapNotNull { element ->
                try {
                    element.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                } catch (e: Exception) {
                    element.jsonPrimitive.contentOrNull
                }
            } ?: emptyList()
            genre = (genresList + tagsList).joinToString(", ")

            status = when (data["status"]?.jsonPrimitive?.contentOrNull) {
                "1", "ongoing" -> SManga.ONGOING

                "2", "completed" -> SManga.COMPLETED

                else -> when (data["status"]?.jsonPrimitive?.int) {
                    1 -> SManga.ONGOING
                    2 -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
            }

            val img = data["image"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: data["cover_image"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (img != null) {
                thumbnail_url = if (img.startsWith("http")) img else "https://cdn.fictionzone.net/insecure/rs:fill:165:250/$img.webp"
            }

            author = data["author"]?.jsonPrimitive?.contentOrNull
                ?: data["contributors"]?.jsonArray?.firstOrNull()?.jsonObject?.get("display_name")?.jsonPrimitive?.contentOrNull
        }
    }

    override fun fetchChapterList(manga: SManga): rx.Observable<List<SChapter>> {
        return rx.Observable.fromCallable {
            val isOmniportal = manga.url.startsWith("/omniportal/")

            val (request, novelId, sourceId, sourceKey) = if (isOmniportal) {
                val parts = manga.url.removePrefix("/omniportal/").split("/")
                val srcId = parts[0]
                val srcKey = parts[1]
                val req = apiRequest("/omniportal/novels/chapters?source_id=$srcId&source_key=$srcKey&translate=en&engine=google-trans")
                Quadruple(req, "", srcId, srcKey)
            } else {
                val detailsRequest = mangaDetailsRequest(manga)
                val detailsResponse = client.newCall(detailsRequest).execute()
                val detailsJson = json.parseToJsonElement(detailsResponse.body.string()).jsonObject
                val id = detailsJson["data"]!!.jsonObject["id"]!!.jsonPrimitive.content
                val req = apiRequest("/platform/chapter-lists?novel_id=$id")
                Quadruple(req, id, "", "")
            }

            val response = client.newCall(request).execute()
            val jsonString = response.body.string()
            val jsonObject = json.parseToJsonElement(jsonString).jsonObject
            val data = jsonObject["data"]?.jsonObject ?: return@fromCallable emptyList<SChapter>()
            val chapters = data["chapters"]?.jsonArray?.toMutableList() ?: return@fromCallable emptyList<SChapter>()

            // Omniportal chapter lists are paginated
            if (isOmniportal) {
                val totalPages = data["pagination"]?.jsonObject?.get("total_pages")?.jsonPrimitive?.int ?: 1
                for (p in 2..totalPages) {
                    try {
                        val pageReq = apiRequest(
                            "/omniportal/novels/chapters?source_id=$sourceId&source_key=$sourceKey&translate=en&engine=google-trans&page=$p",
                        )
                        val pageRes = client.newCall(pageReq).execute()
                        val pageData = json.parseToJsonElement(pageRes.body.string()).jsonObject["data"]?.jsonObject
                        pageData?.get("chapters")?.jsonArray?.let { chapters.addAll(it) }
                    } catch (_: Exception) {
                        break
                    }
                }
            }

            chapters.map { element ->
                val obj = element.jsonObject
                SChapter.create().apply {
                    name = obj["title"]!!.jsonPrimitive.content
                    val chapterId = obj["chapter_id"]!!.jsonPrimitive.content

                    // Site chapter paths, so webview works; fetchPageText maps
                    // them back onto the API endpoints
                    url = if (isOmniportal) {
                        val respSourceId = data["source_id"]?.jsonPrimitive?.contentOrNull ?: sourceId
                        val respSourceKey = data["source_key"]?.jsonPrimitive?.contentOrNull ?: sourceKey
                        "/omniportal/$respSourceId/$respSourceKey/$chapterId"
                    } else {
                        val slug = manga.url.removePrefix("/novel/").trim('/')
                        "/novel/$slug/$chapterId?novel_id=$novelId"
                    }

                    date_upload = try {
                        val dateStr = obj["published_date"]?.jsonPrimitive?.contentOrNull
                        if (dateStr != null) dateFormat.parse(dateStr)?.time ?: 0L else 0L
                    } catch (e: Exception) {
                        0L
                    }

                    chapter_number = obj["chapter_number"]?.jsonPrimitive?.double?.toFloat() ?: -1f
                }
            }.reversed()
        }
    }

    override fun chapterListRequest(manga: SManga): Request = throw UnsupportedOperationException()
    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    override fun pageListRequest(chapter: SChapter): Request = throw UnsupportedOperationException("Not used")

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException("Not used")

    override fun fetchPageList(chapter: SChapter): rx.Observable<List<Page>> = rx.Observable.just(listOf(Page(0, chapter.url)))

    // chapter.url is the site path; strip the helper novel_id query for webview
    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url.substringBefore('?')

    override suspend fun fetchPageText(page: Page): String {
        // Current formats (site paths):
        //   Platform:   "/novel/<slug>/<chapter_id>?novel_id=<id>"
        //   Omniportal: "/omniportal/<source_id>/<source_key>/<chapter_id>"
        // Legacy formats (raw API paths) pass through untouched.
        val raw = page.url
        val apiPath = when {
            raw.startsWith("/platform/") -> raw

            // Legacy raw API paths: make sure the engine param is present
            raw.startsWith("/omniportal/chapters/") ->
                if (raw.contains("engine=")) raw else "$raw&engine=google-trans"

            raw.startsWith("/omniportal/") -> {
                val parts = raw.substringBefore('?').trim('/').split("/")
                "/omniportal/chapters/content?source_id=${parts[1]}&source_key=${parts[2]}&chapter_id=${parts[3]}&translate=en&engine=google-trans"
            }

            raw.startsWith("/novel/") -> {
                val novelId = raw.substringAfter("novel_id=", "").substringBefore('&')
                val chapterId = raw.substringBefore('?').trimEnd('/').substringAfterLast('/')
                "/platform/chapter-content?novel_id=$novelId&chapter_id=$chapterId"
            }

            else -> raw
        }

        val requiresAuth = apiPath.contains("/platform/")
        val request = apiRequest(apiPath, "GET", includeAuth = requiresAuth)

        val response = client.newCall(request).execute()
        val jsonString = response.body.string()

        val jsonObject = json.parseToJsonElement(jsonString).jsonObject

        if (jsonObject["success"]?.jsonPrimitive?.boolean != true) {
            val message = jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: "Failed to fetch chapter"
            throw Exception(message)
        }

        val data = jsonObject["data"]?.jsonObject!!
        val content = data["content"]?.jsonPrimitive?.contentOrNull ?: ""
        return normalizeChapterContent(content)
    }

    private fun normalizeChapterContent(content: String): String {
        if (content.isBlank()) return ""

        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        if (looksLikeHtml(normalized)) {
            return normalized
        }

        val paragraphs = normalized
            .split(Regex("\\n\\s*\\n+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (paragraphs.isEmpty()) return ""

        return paragraphs.joinToString("\n") { paragraph ->
            val escaped = escapeHtml(paragraph)
                .replace("\n", "<br>")
            "<p>$escaped</p>"
        }
    }

    private fun looksLikeHtml(text: String): Boolean = Regex("<\\s*(p|br|div|span|h[1-6]|ul|ol|li|blockquote|img|a)\\b", RegexOption.IGNORE_CASE)
        .containsMatchIn(text)

    /**
     * Converts a synopsis into plain text while preserving paragraph breaks:
     * handles raw HTML (<p>, <br>, <div>, <li>), escaped "\n" sequences and
     * plain-text line breaks.
     */
    private fun formatDescription(raw: String): String {
        if (raw.isBlank()) return ""

        val normalized = raw
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        if (!normalized.contains('<')) {
            // Plain text — keep its own line breaks
            return normalized
                .replace(Regex(" *\n *"), "\n")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        }

        val breakToken = "__FZ_BR__"
        val paragraphToken = "__FZ_P__"
        // Keep plain newlines that sit between/inside tags: doc.text() would otherwise drop them,
        // leaving only the <br>/<p> structure and collapsing the synopsis spacing.
        val withBreaks = normalized.replace("\n", "<br>")
        val doc = Jsoup.parseBodyFragment(Parser.unescapeEntities(withBreaks, false))
        doc.select("br").forEach { it.after(breakToken) }
        doc.select("p, div, li").forEach { it.after(paragraphToken) }

        return doc.text()
            .replace(' ', ' ')
            .replace(Regex("\\s*$paragraphToken\\s*"), "\n\n")
            .replace(Regex("\\s*$breakToken\\s*"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun escapeHtml(text: String): String = buildString(text.length + 16) {
        text.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")
    override fun getFilterList(): FilterList {
        val filters = mutableListOf<Filter<*>>()

        filters.add(AiSearchFilter())
        filters.add(Filter.Separator())
        filters.add(SortFilter())
        filters.add(StatusSelectFilter())
        filters.add(WordCountMinFilter())
        filters.add(WordCountMaxFilter())

        val sources = getSources()
        val genres = getGenres()
        val tags = getTags()

        // Populate the caches on first use, or every time when the pref is on
        val alwaysRefresh = preferences.getBoolean("always_refresh_metadata", false)
        if (alwaysRefresh || sources.isEmpty() || genres.isEmpty() || tags.isEmpty()) {
            Thread { refreshMetadata() }.start()
        }

        if (sources.isNotEmpty()) {
            filters.add(Filter.Separator())
            filters.add(Filter.Header("Omniportal sections (browse external portals)"))
            filters.add(SourceFilter(sources))
        }

        if (genres.isNotEmpty()) {
            filters.add(GenreFilter(genres))
        }

        if (tags.isNotEmpty()) {
            filters.add(TagFilter(tags))
        }

        if (sources.isEmpty() && genres.isEmpty() && tags.isEmpty()) {
            filters.add(Filter.Header("Filter data is downloading, reopen filters shortly"))
        }

        return FilterList(filters)
    }

    private fun getSources(): List<Pair<String, String>> {
        val cached = preferences.getString("sources_cache", null) ?: return emptyList()
        return try {
            json.decodeFromString(cached)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getGenres(): List<Pair<String, String>> {
        val cached = preferences.getString("genres_cache", null) ?: return emptyList()
        return try {
            json.decodeFromString(cached)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getTags(): List<Pair<String, String>> {
        val cached = preferences.getString("tags_cache", null) ?: return emptyList()
        return try {
            json.decodeFromString(cached)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun refreshMetadata() {
        try {
            val genresReq = apiRequest("/platform/genres")
            val genresRes = client.newCall(genresReq).execute()
            val genresJson = json.parseToJsonElement(genresRes.body.string()).jsonObject
            val genresData = genresJson["data"]?.jsonArray
            if (genresData != null) {
                val genres = genresData.map {
                    val obj = it.jsonObject
                    Pair(obj["id"]!!.jsonPrimitive.content, obj["name"]!!.jsonPrimitive.content)
                }
                preferences.edit().putString("genres_cache", json.encodeToString(genres)).apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val tagsReq = apiRequest("/platform/tags")
            val tagsRes = client.newCall(tagsReq).execute()
            val tagsJson = json.parseToJsonElement(tagsRes.body.string()).jsonObject
            val tagsData = tagsJson["data"]?.jsonArray
            if (tagsData != null) {
                val tags = tagsData.map {
                    val obj = it.jsonObject
                    Pair(obj["id"]!!.jsonPrimitive.content, obj["name"]!!.jsonPrimitive.content)
                }
                preferences.edit().putString("tags_cache", json.encodeToString(tags)).apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val sourcesReq = apiRequest("/omniportal/sources")
            val sourcesRes = client.newCall(sourcesReq).execute()
            val sourcesJson = json.parseToJsonElement(sourcesRes.body.string()).jsonObject
            val sourcesData = sourcesJson["data"]?.jsonObject?.get("sources")?.jsonArray
            if (sourcesData != null) {
                val sources = sourcesData.map {
                    val obj = it.jsonObject
                    Pair(obj["id"]!!.jsonPrimitive.content, obj["name"]!!.jsonPrimitive.content)
                }
                preferences.edit().putString("sources_cache", json.encodeToString(sources)).apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = "fz_access_token"
            title = "Access Token"
            summary = "Enter your fz_access_token from browser cookies"
            setDefaultValue("")
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = "always_refresh_metadata"
            title = "Always Refresh Metadata"
            summary = "When enabled, fetches latest sources, genres, and tags each time filters are loaded. When disabled, uses cached data."
            setDefaultValue(false)
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = "reset_metadata_cache"
            title = "Reset filter cache"
            summary = "Toggle to clear cached omniportal sources, genres and tags. They re-download the next time filters open."
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    preferences.edit()
                        .remove("sources_cache")
                        .remove("genres_cache")
                        .remove("tags_cache")
                        .apply()
                    false
                } else {
                    true
                }
            }
        }.also(screen::addPreference)
    }

    class SortFilter :
        Filter.Sort(
            "Sort",
            arrayOf(
                "Most Popular",
                "Latest Update",
                "Newest",
                "Most Chapters",
                "Highest Rated",
                "Most Bookmarked",
                "Title A-Z",
                "Title Z-A",
            ),
            Selection(0, false),
        ) {
        fun toUriPart(): Pair<String, String> = when (state?.index) {
            0 -> "bookmark_count" to "desc"
            1 -> "chapter_last_created_at" to "desc"
            2 -> "created_at" to "desc"
            3 -> "chapter_count" to "desc"
            4 -> "rating" to "desc"
            5 -> "bookmark_count" to "desc"
            6 -> "title" to "asc"
            7 -> "title" to "desc"
            else -> "bookmark_count" to "desc"
        }
    }

    class AiSearchFilter : Filter.CheckBox("AI search (semantic, uses the text query only)", false)

    class StatusSelectFilter : Filter.Select<String>("Status", arrayOf("All", "Ongoing", "Completed")) {
        fun toUriPart(): String? = when (state) {
            1 -> "1"
            2 -> "2"
            else -> null
        }
    }

    class WordCountMinFilter : Filter.Text("Min word count")
    class WordCountMaxFilter : Filter.Text("Max word count")

    class GenreFilter(genres: List<Pair<String, String>>) : Filter.Group<GenreCheckBox>("Genres", genres.map { GenreCheckBox(it.first, it.second) })
    class GenreCheckBox(val id: String, name: String) : Filter.CheckBox(name)

    class TagFilter(tags: List<Pair<String, String>>) : Filter.Group<TagCheckBox>("Tags", tags.map { TagCheckBox(it.first, it.second) })
    class TagCheckBox(val id: String, name: String) : Filter.TriState(name)

    class SourceFilter(sources: List<Pair<String, String>>) : Filter.Select<String>("Source", arrayOf("Fiction Zone", "All Sources") + sources.map { it.second }.toTypedArray()) {
        val sourceIds = listOf("fictionzone", "all") + sources.map { it.first }
        fun toUriPart(): String = sourceIds[state]
    }
}

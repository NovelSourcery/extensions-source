package eu.kanade.tachiyomi.novelextension.en.fictionzone

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
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
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.jsonInstance
import keiyoushi.utils.parseAs
import keiyoushi.utils.setAltTitles
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class FictionZone :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    private val apiUrl = "$baseUrl/api/__api_party/fictionzone"

    override val supportsLatest = true

    // Every request (browse/details/chapters/content, including all omniportal proxy calls)
    // hits this single api-party endpoint, which 429s hard under mass-import's sequential load.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(1, 1.seconds)

    private val json: Json by injectLazy()

    // The api-party envelope omits "query"/"body" entirely when absent; the shared jsonInstance
    // doesn't guarantee that for null fields, so this overrides just that behavior for encoding.
    private val requestJson = Json(jsonInstance) { explicitNulls = false }

    private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

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

    private fun apiRequest(path: String, method: String = "GET", includeAuth: Boolean = true, bodyJson: JsonElement? = null): Request {
        val timestamp = java.time.Instant.now().toString()
        val apiHeaders = buildList {
            add(listOf("content-type", "application/json"))
            add(listOf("x-request-time", timestamp))
            if (includeAuth) {
                getAccessToken()?.let { token -> add(listOf("authorization", "Bearer $token")) }
            }
        }

        val pathOnly = path.substringBefore('?')
        val queryString = path.substringAfter('?', "")
        val queryMap = queryString.takeIf { it.isNotEmpty() }
            ?.split('&')
            ?.filter { it.isNotEmpty() }
            ?.associate { pair ->
                val key = pair.substringBefore('=')
                val value = java.net.URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
                key to value
            }
            ?.takeIf { it.isNotEmpty() }

        val envelope = ApiPartyRequestDto(
            path = pathOnly,
            query = queryMap,
            headers = apiHeaders,
            method = method,
            body = bodyJson,
        )

        return POST(apiUrl, this.headers, envelope.toJsonRequestBody(requestJson))
    }

    private fun buildPopularMangaRequest(page: Int): Request = apiRequest("/platform/browse?page=$page&page_size=20&sort_by=bookmark_count&sort_order=desc&include_genres=true")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = buildPopularMangaRequest(page)
        val envelope = client.post(request.url, request.headers, request.body!!).parseAs<ApiEnvelope<BrowseData>>()
        return parseBrowseData(envelope.data ?: return MangasPage(emptyList(), false))
    }

    private fun parseBrowseData(data: BrowseData): MangasPage {
        val mangas = data.novels.map { novel ->
            SManga.create().apply {
                title = novel.title

                url = if (novel.slug != null) {
                    novel.slug!!
                } else if (novel.sourceKey != null && novel.sourceId != null) {
                    "${novel.sourceId}/${novel.sourceKey}"
                } else {
                    "unknown"
                }

                // Omniportal entries keep this as the only synopsis source
                novel.synopsis?.takeIf { it.isNotBlank() }?.let { description = formatDescription(it) }

                val img = novel.image?.takeIf { it.isNotBlank() } ?: novel.coverImage?.takeIf { it.isNotBlank() }
                thumbnail_url = img?.let { if (it.startsWith("http")) it else "https://cdn.fictionzone.net/insecure/rs:fill:165:250/$it.webp" }
            }
        }

        // Platform responses carry has_next; omniportal ones only page/total_pages
        // (their has_more field is unreliable)
        val hasNext = data.pagination?.hasNext
            ?: run {
                val page = data.pagination?.page
                val totalPages = data.pagination?.totalPages
                page != null && totalPages != null && page < totalPages
            }

        return MangasPage(mangas, hasNext)
    }

    private fun buildLatestUpdatesRequest(page: Int): Request = apiRequest("/platform/browse?page=$page&page_size=20&sort_by=created_at&sort_order=desc&include_genres=true")

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = buildLatestUpdatesRequest(page)
        val envelope = client.post(request.url, request.headers, request.body!!).parseAs<ApiEnvelope<BrowseData>>()
        return parseBrowseData(envelope.data ?: return MangasPage(emptyList(), false))
    }

    private fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // AI semantic search — text query only, other filters don't apply
        val aiSearch = filters.find { it is AiSearchFilter } as? AiSearchFilter
        if (aiSearch?.state == true && query.isNotBlank()) {
            val body = json.encodeToJsonElement(AiSearchRequestDto(query, 20, (page - 1) * 20)).jsonObject
            // The endpoint requires a logged-in fz_access_token regardless - confirmed live, it
            // 401s even for a plain unauthenticated request - so the token (if the user has set
            // one) must actually be attached, unlike the includeAuth=false this used to pass.
            return apiRequest("/ai/search", "POST", includeAuth = true, bodyJson = body)
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

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val request = buildSearchMangaRequest(page, query, filters)
        val response = client.post(request.url, request.headers, request.body!!)
        val data = response.parseAs<ApiEnvelope<BrowseData>>().data ?: return MangasPage(emptyList(), false)

        // AI search responses carry data.results instead of data.novels
        data.results?.let { results ->
            val mangas = results.filter { it.slug.isNotBlank() }.map { result ->
                SManga.create().apply {
                    title = result.title ?: result.slug
                    url = result.slug
                    description = result.synopsis?.let { formatDescription(it) }
                    thumbnail_url = result.coverImageUrl
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "https://cdn.fictionzone.net/insecure/rs:fill:165:250/$it.webp" }
                }
            }
            return MangasPage(mangas, mangas.size >= 20)
        }

        return parseBrowseData(data)
    }

    // manga.url stores the bare identifier: a novel slug (single segment, e.g. "some-slug") or an
    // omniportal "<sourceId>/<sourceKey>" pair (two segments) - distinguished by segment count,
    // since the two id shapes can't share one fixed SlugPath prefix. A stored value starting with
    // "/" is a pre-existing full-path entry from before this migration and is resolved unchanged.
    private fun resolveMangaPath(stored: String): String = when {
        stored.startsWith("/") -> stored
        '/' in stored -> "/omniportal/$stored"
        else -> "/novel/$stored"
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + resolveMangaPath(manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        // Omniportal entries are proxied external sources with no direct site page to paste.
        if (!path.startsWith("/novel/")) return null
        val slug = path.removePrefix("/novel/").trim('/')
        val request = buildMangaDetailsRequest(SManga.create().apply { this.url = slug })
        val response = client.post(request.url, request.headers, request.body!!, ensureSuccess = false)
        if (!response.isSuccessful) return null
        return parseMangaDetails(response).apply { this.url = slug }
    }

    private fun buildMangaDetailsRequest(manga: SManga): Request {
        val resolved = resolveMangaPath(manga.url)
        if (resolved.startsWith("/omniportal/")) {
            val parts = resolved.removePrefix("/omniportal/").split("/")
            val sourceId = parts[0]
            val sourceKey = parts[1]
            return apiRequest("/omniportal/novels/details?source_id=$sourceId&source_key=$sourceKey&translate=en")
        }

        val slug = resolved.substringAfter("/novel/")
        return apiRequest("/platform/novel-details?slug=$slug")
    }

    private fun parseMangaDetails(response: Response): SManga {
        val dataElement = json.parseToJsonElement(response.body.string()).jsonObject["data"]!!.jsonObject
        // Observed shape has the novel flat under "data"; "data.novel" is a defensive fallback
        // for a shape this session couldn't verify (needs omniportal auth) - see Dto.kt.
        val data = if ("novel" in dataElement) {
            json.decodeFromJsonElement<NovelDetailsWrapperDto>(dataElement).novel ?: NovelDetailsDto()
        } else {
            json.decodeFromJsonElement<NovelDetailsDto>(dataElement)
        }

        return SManga.create().apply {
            title = data.title

            val altTitlesList = data.altTitles
                .map { it.trim() }
                .filter { it.isNotBlank() && it != title }

            description = formatDescription(data.synopsis.orEmpty())
            if (altTitlesList.isNotEmpty()) {
                setAltTitles(altTitlesList)
            }

            val genresList = data.genres.mapNotNull { it.nameOrSelf() }
            val tagsList = data.tags.mapNotNull { it.nameOrSelf() }
            genre = (genresList + tagsList).joinToString()

            status = when (data.status?.jsonPrimitive?.contentOrNull) {
                "1", "ongoing" -> SManga.ONGOING

                "2", "completed" -> SManga.COMPLETED

                else -> when (data.status?.jsonPrimitive?.intOrNull) {
                    1 -> SManga.ONGOING
                    2 -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
            }

            val img = data.image?.takeIf { it.isNotBlank() } ?: data.coverImage?.takeIf { it.isNotBlank() }
            if (img != null) {
                thumbnail_url = if (img.startsWith("http")) img else "https://cdn.fictionzone.net/insecure/rs:fill:165:250/$img.webp"
            }

            author = data.author ?: data.contributors.firstOrNull()?.displayName
        }
    }

    private suspend fun loadChapterList(manga: SManga): List<SChapter> {
        val resolvedMangaUrl = resolveMangaPath(manga.url)
        val isOmniportal = resolvedMangaUrl.startsWith("/omniportal/")

        val (request, novelId, sourceId, sourceKey) = if (isOmniportal) {
            val parts = resolvedMangaUrl.removePrefix("/omniportal/").split("/")
            val srcId = parts[0]
            val srcKey = parts[1]
            val req = apiRequest("/omniportal/novels/chapters?source_id=$srcId&source_key=$srcKey&translate=en&engine=google-trans")
            Quadruple(req, "", srcId, srcKey)
        } else {
            val detailsRequest = buildMangaDetailsRequest(manga)
            val detailsResponse = client.post(detailsRequest.url, detailsRequest.headers, detailsRequest.body!!)
            val id = json.parseToJsonElement(detailsResponse.body.string()).jsonObject["data"]!!.jsonObject["id"]!!.jsonPrimitive.content
            val req = apiRequest("/platform/chapter-lists?novel_id=$id")
            Quadruple(req, id, "", "")
        }

        val response = client.post(request.url, request.headers, request.body!!)
        val data = response.parseAs<ApiEnvelope<ChapterListData>>().data ?: return emptyList()
        val chapters = data.chapters.toMutableList()

        // Omniportal chapter lists are paginated
        if (isOmniportal) {
            val totalPages = data.pagination?.totalPages ?: 1
            for (p in 2..totalPages) {
                try {
                    val pageReq = apiRequest(
                        "/omniportal/novels/chapters?source_id=$sourceId&source_key=$sourceKey&translate=en&engine=google-trans&page=$p",
                    )
                    val pageRes = client.post(pageReq.url, pageReq.headers, pageReq.body!!)
                    pageRes.parseAs<ApiEnvelope<ChapterListData>>().data?.chapters?.let { chapters.addAll(it) }
                } catch (_: Exception) {
                    break
                }
            }
        }

        return chapters.map { chapter ->
            SChapter.create().apply {
                name = chapter.title

                // Site chapter paths, so webview works; fetchPageText maps
                // them back onto the API endpoints
                url = if (isOmniportal) {
                    val respSourceId = data.sourceId ?: sourceId
                    val respSourceKey = data.sourceKey ?: sourceKey
                    "/omniportal/$respSourceId/$respSourceKey/${chapter.chapterId}"
                } else {
                    val slug = resolvedMangaUrl.removePrefix("/novel/").trim('/')
                    "/novel/$slug/${chapter.chapterId}?novel_id=$novelId"
                }

                // API format is "yyyy-MM-dd HH:mm:ss" (confirmed live) - a bare "yyyy-MM-dd"
                // formatter here silently fails to parse it (trailing time left unconsumed) and
                // every chapter date was defaulting to 0L/epoch.
                date_upload = chapter.publishedDate?.let { dateStr ->
                    runCatching {
                        LocalDateTime.parse(dateStr, dateFormat).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.getOrDefault(0L)
                } ?: 0L

                chapter_number = chapter.chapterNumber?.toFloat() ?: -1f
            }
        }.reversed()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        // Details and chapters live on different endpoints - fire both concurrently when both
        // are needed.
        val detailsDeferred = if (fetchDetails) {
            async {
                val request = buildMangaDetailsRequest(manga)
                parseMangaDetails(client.post(request.url, request.headers, request.body!!))
            }
        } else {
            null
        }
        val chaptersDeferred = if (fetchChapters) async { loadChapterList(manga) } else null

        SMangaUpdate(
            manga = detailsDeferred?.await() ?: manga,
            chapters = chaptersDeferred?.await() ?: chapters,
        )
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    // chapter.url is the site path; strip the helper novel_id query for webview
    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url.substringBefore('?')

    /**
     * Fetches chapter content either by scraping the HTML page (recommended)
     * or via the API, depending on user preference.
     */
    override suspend fun fetchPageText(page: Page): String {
        val chapterUrl = page.url
        val fullUrl = baseUrl + chapterUrl

        // Omniportal chapters are rendered client-side: the HTML page only ships a teaser that
        // cuts off mid-chapter (e.g. at "[Clone Number 2]"). The omniportal API returns the full
        // translated text, so always prefer it there, falling back to HTML only if it fails.
        if (chapterUrl.startsWith("/omniportal/")) {
            try {
                val apiContent = fetchFromApi(chapterUrl)
                if (apiContent.isNotBlank()) return apiContent
            } catch (_: Exception) {
                // fall through to HTML
            }
            return try {
                fetchFromHtml(fullUrl)
            } catch (_: Exception) {
                ""
            }
        }

        // Read user preference: "html" (default) or "api"
        val method = preferences.getString("content_fetch_method", "html") ?: "html"

        // If user prefers HTML, try that first
        if (method == "html") {
            try {
                val htmlContent = fetchFromHtml(fullUrl)
                if (htmlContent.isNotBlank()) {
                    return htmlContent
                }
            } catch (e: Exception) {
                // Log error and fall back to API
            }
        }

        // Fallback: API method
        return fetchFromApi(chapterUrl)
    }

    /**
     * Scrape the chapter content from the HTML page.
     */
    private suspend fun fetchFromHtml(fullUrl: String): String {
        val response = client.get(fullUrl, headers)
        val doc = response.asJsoup()

        // Find the main content container
        var contentElement = doc.selectFirst(".chapter-text")
        if (contentElement == null) {
            contentElement = doc.selectFirst(".chapter-article .chapter-text")
        }

        if (contentElement == null) {
            // Fallback: extract all <p> tags from the body (rare)
            val paragraphs = doc.select("p")
            if (paragraphs.isNotEmpty()) {
                return paragraphs
                    .filterNot { p -> p.parent()?.hasClass("ad-slot") == true || p.parent()?.hasClass("advertisement") == true }
                    .joinToString("") { it.outerHtml() }
            }
            throw Exception("Could not find any chapter content on the page")
        }

        // Remove unwanted elements (ads, scripts, etc.)
        contentElement.select(".ad-slot").remove()
        contentElement.select(".advertisement").remove()
        contentElement.select("script, style").remove()

        val contentHtml = contentElement.html()
        if (contentHtml.isBlank()) {
            // If the container is empty, try extracting from its <p> tags
            val paragraphs = contentElement.select("p")
            if (paragraphs.isNotEmpty()) {
                return paragraphs.joinToString("") { it.outerHtml() }
            }
            throw Exception("Content container is empty")
        }

        return contentHtml
    }

    /**
     * Fetch chapter content via the API .
     */
    private suspend fun fetchFromApi(chapterUrl: String): String {
        val apiPath = when {
            chapterUrl.startsWith("/omniportal/") -> {
                val parts = chapterUrl.substringBefore('?').trim('/').split("/")
                "/omniportal/chapters/content?source_id=${parts[1]}&source_key=${parts[2]}&chapter_id=${parts[3]}&translate=en&engine=google-trans"
            }
            chapterUrl.startsWith("/novel/") -> {
                val novelId = chapterUrl.substringAfter("novel_id=", "").substringBefore('&')
                val chapterId = chapterUrl.substringBefore('?').trimEnd('/').substringAfterLast('/')
                "/platform/chapter-content?novel_id=$novelId&chapter_id=$chapterId"
            }
            else -> chapterUrl
        }

        val request = apiRequest(apiPath, "GET", includeAuth = true)
        val response = client.post(request.url, request.headers, request.body!!)
        val envelope = response.parseAs<ApiEnvelope<ChapterContentDto>>()

        if (envelope.success != true) {
            throw Exception(envelope.message ?: "Failed to fetch chapter")
        }

        return normalizeChapterContent(envelope.data?.content ?: "")
    }

    // This remains for synopsis formatting, not for chapter content.
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
     * Converts a synopsis into plain text while preserving paragraph breaks.
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

    override fun getFilterList(data: JsonElement?): FilterList {
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
            val genresData = client.newCall(genresReq).execute().parseAs<ApiEnvelope<List<NamedIdDto>>>().data
            if (genresData != null) {
                val genres = genresData.map { Pair(it.idString(), it.name) }
                preferences.edit().putString("genres_cache", json.encodeToString(genres)).apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val tagsReq = apiRequest("/platform/tags")
            val tagsData = client.newCall(tagsReq).execute().parseAs<ApiEnvelope<List<NamedIdDto>>>().data
            if (tagsData != null) {
                val tags = tagsData.map { Pair(it.idString(), it.name) }
                preferences.edit().putString("tags_cache", json.encodeToString(tags)).apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            val sourcesReq = apiRequest("/omniportal/sources")
            val sourcesData = client.newCall(sourcesReq).execute().parseAs<ApiEnvelope<SourcesData>>().data?.sources
            if (sourcesData != null) {
                val sources = sourcesData.map { Pair(it.idString(), it.name) }
                preferences.edit().putString("sources_cache", json.encodeToString(sources)).apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Access token
        EditTextPreference(screen.context).apply {
            key = "fz_access_token"
            title = "Access Token"
            summary = "Enter your fz_access_token from browser cookies"
            setDefaultValue("")
        }.also(screen::addPreference)

        // Content fetch method
        ListPreference(screen.context).apply {
            key = "content_fetch_method"
            title = "Chapter content source"
            entries = arrayOf("HTML (recommended)", "API")
            entryValues = arrayOf("html", "api")
            setDefaultValue("html")
            summary = "HTML gives full content; API is faster but may cut off long chapters."
        }.also(screen::addPreference)

        // Refresh metadata
        SwitchPreferenceCompat(screen.context).apply {
            key = "always_refresh_metadata"
            title = "Always Refresh Metadata"
            summary = "When enabled, fetches latest sources, genres, and tags each time filters are loaded. When disabled, uses cached data."
            setDefaultValue(false)
        }.also(screen::addPreference)

        // Reset cache
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

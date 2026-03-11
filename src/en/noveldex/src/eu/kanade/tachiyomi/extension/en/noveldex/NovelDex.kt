package eu.kanade.tachiyomi.extension.en.noveldex

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * NovelDex.io - Novel reading extension
 * Uses /api/series for listings and RSC (React Server Components) for detail/chapter pages.
 */
class NovelDex :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "NovelDex"
    override val baseUrl = "https://noveldex.io"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient
    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    // RSC headers - required for React Server Component response
    // Minimal approach (NovelsHub style): just rsc:1 + Accept: */*
    // Avoid next-router-prefetch, next-url, _rsc param — they trigger 403
    private fun rscHeaders(): Headers = headers.newBuilder()
        .add("rsc", "1")
        .add("Accept", "*/*")
        .build()

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/api/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")
            .addQueryParameter("sort", "popular")
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseApiResponse(response.body.string())

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/api/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")
            // no sort param = recently updated
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseApiResponse(response.body.string())

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/api/series".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", "24")

            if (query.isNotBlank()) {
                addQueryParameter("search", query)
            }

            val genres = mutableListOf<String>()
            val exGenres = mutableListOf<String>()
            val tags = mutableListOf<String>()
            val exTags = mutableListOf<String>()
            val types = mutableListOf<String>()
            val statusList = mutableListOf<String>()

            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> {
                        val sort = filter.toValue()
                        if (sort != null) addQueryParameter("sort", sort)
                    }

                    is StatusFilter -> {
                        filter.state.forEach { if (it.state) statusList.add(it.value) }
                    }

                    is TypeFilter -> {
                        filter.state.forEach { if (it.state) types.add(it.value) }
                    }

                    is GenreFilter -> {
                        filter.state.forEach {
                            when {
                                it.isIncluded() -> genres.add(it.value)
                                it.isExcluded() -> exGenres.add(it.value)
                            }
                        }
                    }

                    is TagFilter -> {
                        filter.state.forEach {
                            when {
                                it.isIncluded() -> tags.add(it.value)
                                it.isExcluded() -> exTags.add(it.value)
                            }
                        }
                    }

                    is ChapterCountMinFilter -> {
                        val min = filter.state.trim()
                        if (min.isNotEmpty()) addQueryParameter("ch_min", min)
                    }

                    is ChapterCountMaxFilter -> {
                        val max = filter.state.trim()
                        if (max.isNotEmpty()) addQueryParameter("ch_max", max)
                    }

                    is HasImagesFilter -> {
                        if (filter.state) addQueryParameter("images", "true")
                    }

                    else -> {}
                }
            }

            if (genres.isNotEmpty()) addQueryParameter("genre", genres.joinToString(","))
            if (exGenres.isNotEmpty()) addQueryParameter("exgenre", exGenres.joinToString(","))
            if (tags.isNotEmpty()) addQueryParameter("tag", tags.joinToString(","))
            if (exTags.isNotEmpty()) addQueryParameter("extag", exTags.joinToString(","))
            if (types.isNotEmpty()) addQueryParameter("type", types.joinToString(","))
            if (statusList.isNotEmpty()) addQueryParameter("status", statusList.joinToString(","))
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseApiResponse(response.body.string())

    private fun parseApiResponse(body: String): MangasPage {
        // Guard: if the response is HTML (e.g. Cloudflare challenge), bail out
        val trimmed = body.trimStart()
        if (trimmed.startsWith("<") || trimmed.startsWith("<!DOCTYPE")) {
            return MangasPage(emptyList(), false)
        }

        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val dataArray = root["data"]?.jsonArray ?: return MangasPage(emptyList(), false)
            val meta = root["meta"]?.jsonObject

            val novels = dataArray.mapNotNull { element ->
                try {
                    val obj = element.jsonObject
                    val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

                    val title = obj["title"]?.jsonPrimitive?.contentOrNull
                        ?.let { cleanTitleText(it) }
                        ?: return@mapNotNull null
                    val cover = obj["coverImage"]?.jsonPrimitive?.contentOrNull
                        ?.let { if (it.startsWith("/")) baseUrl + it else it }
                    val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "WEB_NOVEL"
                    val urlType = typeToUrlSegment(type)

                    SManga.create().apply {
                        this.title = title
                        this.url = "/series/$urlType/$slug"
                        this.thumbnail_url = cover
                    }
                } catch (e: Exception) {
                    null
                }
            }

            val hasMore = meta?.get("hasMore")?.jsonPrimitive?.booleanOrNull ?: false

            MangasPage(novels, hasMore)
        } catch (_: Exception) {
            MangasPage(emptyList(), false)
        }
    }

    /**
     * Map API type field to URL path segment.
     */
    private fun typeToUrlSegment(type: String): String = when (type) {
        "WEB_NOVEL" -> "novel"
        "MANHWA" -> "manhwa"
        "MANGA" -> "manga"
        "MANHUA" -> "manhua"
        "WEBTOON" -> "webtoon"
        else -> "novel"
    }

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", rscHeaders())

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body.string()

        // RSC response contains a JSON fragment with series data
        // Pattern: "series":{ ... } inside the RSC payload
        val seriesJsonMatch = Regex(""""series"\s*:\s*(\{.+?"similarSeries"\s*:\s*\[.*?\]\s*\}[^}]*\})""", RegexOption.DOT_MATCHES_ALL)
            .find(body)

        if (seriesJsonMatch != null) {
            return try {
                parseMangaFromJson(seriesJsonMatch.groupValues[1], body)
            } catch (e: Exception) {
                parseMangaFromRaw(body)
            }
        }

        return parseMangaFromRaw(body)
    }

    private fun parseMangaFromJson(seriesJson: String, fullBody: String): SManga {
        // Extract the series object – it's embedded in RSC, parse key fields with regex
        return SManga.create().apply {
            title = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(seriesJson)
                ?.groupValues?.get(1)?.unescape()?.let { cleanTitleText(it) } ?: ""
            val rawDescription = Regex(""""description"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(seriesJson)
                ?.groupValues?.get(1)?.unescape()

            val altTitle = Regex(""""altTitle"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(seriesJson)
                ?.groupValues?.get(1)?.unescape()?.takeIf { it.isNotBlank() }?.let { cleanTitleText(it) }

            // aliases is a JSON array
            val aliasesMatch = Regex(""""aliases"\s*:\s*\[(.*?)\]""").find(seriesJson)
            val aliases = aliasesMatch?.groupValues?.get(1)
                ?.let { Regex(""""((?:[^"\\]|\\.)*)"""").findAll(it).map { m -> m.groupValues[1].unescape() }.toList() }
                ?.filter { it.isNotBlank() }

            val originalTitle = Regex(""""originalTitle"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(seriesJson)
                ?.groupValues?.get(1)?.unescape()?.takeIf { it.isNotBlank() }
                ?.let { cleanTitleText(it) }
            description = buildString {
                altTitle?.let { append("Alt Title: $it\n") }
                originalTitle?.let { append("Original: $it\n") }
                aliases?.takeIf { it.isNotEmpty() }?.let { append("Also known as: ${it.joinToString(", ")}\n") }
                if (isNotEmpty()) append("\n")
                rawDescription?.let { append(it) }
            }.trim().takeIf { it.isNotBlank() }

            val coverImg = Regex(""""coverImage"\s*:\s*"((?:[^"\\]|\\.]*)"""").find(seriesJson)
                ?.groupValues?.get(1)?.unescape()
            thumbnail_url = coverImg?.let { if (it.startsWith("/")) baseUrl + it else it }

            // Author from "team" object name field
            author = Regex(""""team"\s*:\s*\{[^}]*"name"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(seriesJson)
                ?.groupValues?.get(1)?.unescape()

            // Genres: array of {"name":"Action","slug":"action","color":"..."}
            val genresSection = Regex(""""genres"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(seriesJson)
            val genreNames = genresSection?.groupValues?.get(1)?.let {
                Regex(""""name"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(it).map { m -> m.groupValues[1].unescape() }.toList()
            } ?: emptyList()

            // Tags: array of {"name":"Academy","slug":"academy"}
            val tagsSection = Regex(""""tags"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(seriesJson)
            val tagNames = tagsSection?.groupValues?.get(1)?.let {
                Regex(""""name"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(it).map { m -> m.groupValues[1].unescape() }.toList()
            } ?: emptyList()

            genre = (genreNames + tagNames).joinToString(", ").takeIf { it.isNotBlank() }

            val statusStr = Regex(""""status"\s*:\s*"([A-Z_]+)"""").find(seriesJson)
                ?.groupValues?.get(1)
            status = when (statusStr) {
                "ONGOING" -> SManga.ONGOING
                "COMPLETED" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    private fun parseMangaFromRaw(body: String): SManga = SManga.create().apply {
        // Use title near slug to avoid matching RSS/meta tag titles like "X — New Chapters"
        val seriesTitle = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*"slug"\s*:""").find(body)
            ?.groupValues?.get(1)?.unescape()
            ?: Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body)
                ?.groupValues?.get(1)?.unescape()
        title = cleanTitleText(seriesTitle ?: "")

        val desc = Regex(""""description"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body)
            ?.groupValues?.get(1)?.unescape()
        val altTitle = Regex(""""altTitle"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body)
            ?.groupValues?.get(1)?.unescape()?.takeIf { it.isNotBlank() }?.let { cleanTitleText(it) }

        val originalTitle = Regex(""""originalTitle"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body)
            ?.groupValues?.get(1)?.unescape()?.takeIf { it.isNotBlank() }?.let { cleanTitleText(it) }

        val aliasesMatch = Regex(""""aliases"\s*:\s*\[(.*?)\]""").find(body)
        val aliases = aliasesMatch?.groupValues?.get(1)
            ?.let { Regex(""""((?:[^"\\]|\\.)*)"""").findAll(it).map { m -> m.groupValues[1].unescape() }.toList() }
            ?.filter { it.isNotBlank() }

        description = buildString {
            altTitle?.let { append("Alt Title: $it\n") }
            originalTitle?.let { append("Original: $it\n") }
            aliases?.takeIf { it.isNotEmpty() }?.let { append("Also known as: ${it.joinToString(", ")}\n") }
            if (isNotEmpty()) append("\n")
            desc?.let { append(it) }
        }.trim().takeIf { it.isNotBlank() }

        val coverImg = Regex(""""coverImage"\s*:\s*"(/[^"]+)"""").find(body)?.groupValues?.get(1)
        thumbnail_url = coverImg?.let { baseUrl + it }

        author = Regex(""""team"\s*:\s*\{[^}]*"name"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body)
            ?.groupValues?.get(1)?.unescape()

        val genresSection = Regex(""""genres"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(body)
        genre = genresSection?.groupValues?.get(1)?.let {
            Regex(""""name"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(it)
                .map { m -> m.groupValues[1].unescape() }
                .joinToString(", ")
        }

        val statusStr = Regex(""""status"\s*:\s*"([A-Z_]+)"""").find(body)?.groupValues?.get(1)
        status = when (statusStr) {
            "ONGOING" -> SManga.ONGOING
            "COMPLETED" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request {
        // Fetch the detail page RSC — contains allChapters/chapters/chapterCount
        return GET("$baseUrl${manga.url}", rscHeaders())
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()
        val requestUrl = response.request.url.encodedPath

        // Extract type and slug: /series/{type}/{slug} or /series/{type}/{slug}/chapter/1
        val slugMatch = Regex("""/series/([^/]+)/([^/?]+)""").find(requestUrl)
        val seriesType = slugMatch?.groupValues?.get(1) ?: "novel"
        val novelSlug = slugMatch?.groupValues?.get(2) ?: ""

        val chapters = mutableListOf<SChapter>()
        val showLocked = preferences.getBoolean(SHOW_LOCKED_PREF_KEY, true)

        // PRIMARY: "allChapters":[{...},...] — full list with titles + lock status
        val allChaptersMatch = Regex(""""allChapters"\s*:\s*(\[.*?\])(?=\s*[,}])""", RegexOption.DOT_MATCHES_ALL)
            .find(body)

        if (allChaptersMatch != null) {
            try {
                val arr = json.parseToJsonElement(allChaptersMatch.groupValues[1]).jsonArray
                parseChaptersFromArray(arr, seriesType, novelSlug, chapters, showLocked)
                if (chapters.isNotEmpty()) {
                    return chapters.sortedByDescending { it.chapter_number }
                }
            } catch (_: Exception) {}
        }

        // SECONDARY: "chapters":[...] (paginated, ~100 per page) with pagination support
        val chaptersArrayMatch = Regex(""""chapters"\s*:\s*(\[.*?\])(?=\s*[,}])""", RegexOption.DOT_MATCHES_ALL)
            .find(body)
        if (chaptersArrayMatch != null) {
            try {
                val arr = json.parseToJsonElement(chaptersArrayMatch.groupValues[1]).jsonArray
                parseChaptersFromArray(arr, seriesType, novelSlug, chapters, showLocked)
            } catch (_: Exception) {}
        }

        // Pagination: extract totalPages and fetch remaining pages
        if (chapters.isNotEmpty()) {
            val totalPages = Regex(""""totalPages"\s*:\s*(\d+)""").find(body)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 1

            if (totalPages > 1) {
                for (page in 2..totalPages) {
                    try {
                        val pageUrl = "$baseUrl/series/$seriesType/$novelSlug?page=$page"
                        val pageResponse = client.newCall(GET(pageUrl, rscHeaders())).execute()
                        val pageBody = pageResponse.body.string()

                        val pageChaptersMatch = Regex(""""chapters"\s*:\s*(\[.*?\])(?=\s*[,}])""", RegexOption.DOT_MATCHES_ALL)
                            .find(pageBody)
                        if (pageChaptersMatch != null) {
                            val arr = json.parseToJsonElement(pageChaptersMatch.groupValues[1]).jsonArray
                            parseChaptersFromArray(arr, seriesType, novelSlug, chapters, showLocked)
                        }
                    } catch (_: Exception) {}
                }
            }

            return chapters.distinctBy { it.chapter_number }.sortedByDescending { it.chapter_number }
        }

        // TERTIARY: chapterCount from series{} — build sequential list
        val chapterCount = Regex(""""chapterCount"\s*:\s*(\d+)""").find(body)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0

        if (chapterCount > 0 && novelSlug.isNotEmpty()) {
            for (n in 1..chapterCount) {
                chapters.add(
                    SChapter.create().apply {
                        url = "/series/$seriesType/$novelSlug/chapter/$n"
                        name = "Chapter $n"
                        chapter_number = n.toFloat()
                    },
                )
            }
        }

        // FALLBACK: If detail page RSC returned no chapters, try chapter/1 page
        if (chapters.isEmpty() && novelSlug.isNotEmpty() && !requestUrl.contains("/chapter/")) {
            try {
                val chapterOneRequest = GET("$baseUrl/series/$seriesType/$novelSlug/chapter/1", rscHeaders())
                val chapterOneResponse = client.newCall(chapterOneRequest).execute()
                val chapterOneBody = chapterOneResponse.body.string()

                val ch1AllChapters = Regex(""""allChapters"\s*:\s*(\[.*?\])(?=\s*[,}])""", RegexOption.DOT_MATCHES_ALL)
                    .find(chapterOneBody)
                if (ch1AllChapters != null) {
                    val arr = json.parseToJsonElement(ch1AllChapters.groupValues[1]).jsonArray
                    parseChaptersFromArray(arr, seriesType, novelSlug, chapters, showLocked)
                }
            } catch (_: Exception) {}
        }

        // API FALLBACK: Use the listing API to get at least partial chapters
        if (chapters.isEmpty() && novelSlug.isNotEmpty()) {
            try {
                val apiUrl = "$baseUrl/api/series".toHttpUrl().newBuilder()
                    .addQueryParameter("page", "1")
                    .addQueryParameter("limit", "1")
                    .addQueryParameter("slug", novelSlug)
                    .build()
                val apiResponse = client.newCall(GET(apiUrl, headers)).execute()
                val apiBody = apiResponse.body.string()
                val apiRoot = json.parseToJsonElement(apiBody).jsonObject
                val dataArray = apiRoot["data"]?.jsonArray

                dataArray?.forEach { element ->
                    val obj = element.jsonObject
                    val apiSlug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    if (apiSlug != novelSlug) return@forEach

                    obj["chapters"]?.jsonArray?.let { arr ->
                        parseChaptersFromArray(arr, seriesType, novelSlug, chapters, showLocked)
                    }
                }
            } catch (_: Exception) {}
        }

        return chapters.distinctBy { it.chapter_number }.sortedByDescending { it.chapter_number }
    }

    /**
     * Parse chapter entries from a JSON array and add to the chapters list.
     * Filters locked chapters based on the user preference.
     */
    private fun parseChaptersFromArray(
        arr: JsonArray,
        seriesType: String,
        novelSlug: String,
        chapters: MutableList<SChapter>,
        showLocked: Boolean,
    ) {
        arr.forEach { elem ->
            try {
                val obj = elem.jsonObject
                val number = obj["number"]?.jsonPrimitive?.intOrNull ?: return@forEach
                val chTitle = obj["title"]?.jsonPrimitive?.contentOrNull ?: "Chapter $number"
                val publishedAt = obj["publishedAt"]?.jsonPrimitive?.contentOrNull
                val isLocked = obj["isLocked"]?.jsonPrimitive?.booleanOrNull ?: false

                if (!showLocked && isLocked) return@forEach

                chapters.add(
                    SChapter.create().apply {
                        url = "/series/$seriesType/$novelSlug/chapter/$number"
                        name = if (isLocked) "\uD83D\uDD12 $chTitle" else chTitle
                        chapter_number = number.toFloat()
                        date_upload = publishedAt?.let {
                            try {
                                dateFormat.parse(it)?.time ?: 0L
                            } catch (_: Exception) {
                                0L
                            }
                        } ?: 0L
                    },
                )
            } catch (_: Exception) {}
        }
    }

    // ======================== Pages ========================

    override fun pageListRequest(chapter: SChapter): Request {
        val chapterPath = if (chapter.url.startsWith("http")) chapter.url.removePrefix(baseUrl) else chapter.url
        return GET("$baseUrl$chapterPath", rscHeaders())
    }

    override fun pageListParse(response: Response): List<Page> {
        // Store chapter path (without query) for fetchPageText
        val path = response.request.url.encodedPath
        return listOf(Page(0, path))
    }

    override fun imageUrlParse(response: Response): String = ""

    // ======================== Novel Content ========================

    override suspend fun fetchPageText(page: Page): String {
        val chapterPath = page.url

        // Approach 1: RSC headers (React Server Component wire format)
        // NovelDex uses Next.js App Router — chapter content lives in RSC T-tags,
        // NOT in the visible HTML DOM (which only has loading skeletons).
        try {
            val rscResponse = client.newCall(GET("$baseUrl$chapterPath", rscHeaders())).execute()
            val rscBody = rscResponse.body.string()
            val rscContent = extractFromRscBody(rscBody)
            if (rscContent != null) return rscContent
        } catch (_: Exception) {}

        // Approach 2: Normal HTML fetch (fallback)
        try {
            val htmlResponse = client.newCall(GET("$baseUrl$chapterPath", headers)).execute()
            val htmlBody = htmlResponse.body.string()
            val doc = Jsoup.parse(htmlBody)

            // Try common chapter content selectors
            val content = doc.selectFirst(
                "div.chapter-content, div.prose, article.chapter, " +
                    "div[class*=chapter-text], div[class*=chapterContent], " +
                    "div[class*=reading-content], div[class*=novelContent]",
            )?.html()
            if (!content.isNullOrBlank() && content.length > 50) return content

            // Try __NEXT_DATA__ JSON (Pages Router)
            val nextDataScript = doc.selectFirst("script#__NEXT_DATA__")?.data()
            if (nextDataScript != null) {
                val extracted = extractContentFromNextData(nextDataScript)
                if (extracted != null && extracted.length > 50) return extracted
            }

            // Try RSC extraction on HTML body (content is inside self.__next_f.push scripts)
            val rscContent = extractFromRscBody(htmlBody)
            if (rscContent != null) return rscContent
        } catch (_: Exception) {}

        return ""
    }

    /**
     * Extract chapter content from RSC wire format body.
     *
     * RSC T-tags have format: `KEY:TSIZE,<content>` where:
     * - KEY is a hex identifier (e.g. "d", "4c", "1c")
     * - SIZE is the hex byte-length of the content
     * - Content follows immediately after the comma
     *
     * Strategy:
     * 1. Targeted: find "content":"$KEY" reference and resolve the T-tag directly
     * 2. Fallback: score all T-tags to find the one with actual chapter HTML
     */
    private fun extractFromRscBody(body: String): String? {
        // --- Targeted extraction: find "content":"$KEY" and resolve T-tag ---
        val contentMarker = "\"content\":\"\$"
        val contentIdx = body.indexOf(contentMarker)
        if (contentIdx >= 0) {
            val keyStart = contentIdx + contentMarker.length
            val keyEnd = body.indexOf('"', keyStart)
            if (keyEnd > keyStart && keyEnd - keyStart <= 4) {
                val key = body.substring(keyStart, keyEnd)
                val tTagContent = resolveTTag(body, key)
                if (tTagContent != null && tTagContent.length > 50) return tTagContent
            }
        }

        // --- Fallback: scan all T-tags with scoring ---
        val tTagRegex = Regex("""[0-9a-fA-F]+:T([0-9a-fA-F]+),""")
        val allTTags = tTagRegex.findAll(body).toList()

        var bestContent: String? = null
        var bestScore = 0

        for (match in allTTags) {
            val sizeBytes = match.groupValues[1].toIntOrNull(16) ?: continue
            if (sizeBytes < 100) continue

            val contentStart = match.range.last + 1
            if (contentStart >= body.length) continue

            val rawContent = extractTTagBytes(body, contentStart, sizeBytes)
            val content = stripWatermark(rawContent)

            if (content.isBlank() || content.length < 50) continue
            if (content.trimStart().startsWith("<script") || content.trimStart().startsWith("{")) continue

            var score = content.length / 10
            score += Regex("<p[ >]").findAll(content).count() * 50
            score += Regex("<br").findAll(content).count() * 5
            if (content.contains("function ") || content.contains("var ") || content.contains("window.")) score -= 500
            if (content.contains("<script")) score -= 1000

            if (score > bestScore && content.length > 50) {
                bestScore = score
                bestContent = content
            }
        }

        return bestContent
    }

    /**
     * Resolve a T-tag by its key (e.g. "d" → find "d:T2041," and extract content).
     */
    private fun resolveTTag(body: String, key: String): String? {
        // T-tags appear at line start: "\nKEY:TSIZE,"
        val prefix = "$key:T"
        var idx = body.indexOf("\n$prefix")
        if (idx >= 0) {
            idx++ // skip newline
        } else if (body.startsWith(prefix)) {
            idx = 0
        } else {
            return null
        }

        val sizeStart = idx + prefix.length
        val commaIdx = body.indexOf(',', sizeStart)
        if (commaIdx <= sizeStart || commaIdx - sizeStart > 8) return null

        val sizeBytes = body.substring(sizeStart, commaIdx).toIntOrNull(16) ?: return null
        if (sizeBytes < 50) return null

        val rawContent = extractTTagBytes(body, commaIdx + 1, sizeBytes)
        return stripWatermark(rawContent)
    }

    /**
     * Extract exactly [sizeBytes] UTF-8 bytes from [body] starting at [charStart].
     */
    private fun extractTTagBytes(body: String, charStart: Int, sizeBytes: Int): String {
        if (charStart >= body.length) return ""
        val remaining = body.substring(charStart)
        val bytes = remaining.toByteArray(Charsets.UTF_8)
        val len = sizeBytes.coerceAtMost(bytes.size)
        return String(bytes, 0, len, Charsets.UTF_8)
    }

    /**
     * Strip invisible Unicode watermark/fingerprinting characters from content.
     * NovelDex wraps chapter text with zero-width characters for tracking.
     */
    private fun stripWatermark(text: String): String = text
        .replace(Regex("[\uFEFF\u200B\u200C\u200D\u200E\u200F\u034F\u2060-\u2064\u2069\uFFFE]+"), "")
        .trim()

    /**
     * Try to extract chapter content from __NEXT_DATA__ JSON (Pages Router).
     */
    private fun extractContentFromNextData(jsonStr: String): String? {
        return try {
            val root = json.parseToJsonElement(jsonStr).jsonObject
            val props = root["props"]?.jsonObject?.get("pageProps")?.jsonObject ?: return null
            for (key in listOf("content", "chapterContent", "body", "text", "html")) {
                props[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.length > 50 }?.let { return it }
            }
            val chapter = props["chapter"]?.jsonObject
            if (chapter != null) {
                for (key in listOf("content", "body", "text", "html")) {
                    chapter[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.length > 50 }?.let { return it }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // ======================== Preferences ========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_LOCKED_PREF_KEY
            title = "Show locked chapters"
            summary = "When disabled, chapters that require coins to unlock will be hidden from the chapter list."
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    /**
     * Clean title text by removing " — New Chapters" suffix and trailing hyphens.
     */
    internal fun cleanTitleText(text: String): String = text.replace(" — New Chapters", "").replace(" - New Chapters", "").replace(" — New Chapters", "").trim()

    companion object {
        private const val SHOW_LOCKED_PREF_KEY = "show_locked_chapters"
    }

    // ======================== Filters ========================

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Sort & Filters"),
        SortFilter("Sort", sortOptions),
        Filter.Separator(),
        HasImagesFilter(),
        ChapterCountMinFilter(),
        ChapterCountMaxFilter(),
        Filter.Separator(),
        StatusFilter("Status", statusOptions),
        TypeFilter("Type", typeOptions),
        GenreFilter("Genres", genreList),
        TagFilter("Tags", tagList),
    )

    class SortFilter(name: String, private val options: List<Pair<String, String>>) : Filter.Select<String>(name, options.map { it.second }.toTypedArray()) {
        fun toValue(): String? = if (state == 0) null else options.getOrNull(state)?.first
    }

    class StatusFilter(name: String, options: List<StatusEntry>) : Filter.Group<StatusCheckBox>(name, options.map { StatusCheckBox(it.label, it.value) })

    class StatusCheckBox(name: String, val value: String) : Filter.CheckBox(name)

    class TypeFilter(name: String, options: List<TypeEntry>) : Filter.Group<TypeCheckBox>(name, options.map { TypeCheckBox(it.label, it.value) })

    class TypeCheckBox(name: String, val value: String) : Filter.CheckBox(name)

    class GenreFilter(name: String, entries: List<GenreEntry>) : Filter.Group<GenreTriState>(name, entries.map { GenreTriState(it.label, it.slug) })

    class GenreTriState(name: String, val value: String) : Filter.TriState(name)

    class TagFilter(name: String, entries: List<TagEntry>) : Filter.Group<TagTriState>(name, entries.map { TagTriState(it.label, it.slug) })

    class TagTriState(name: String, val value: String) : Filter.TriState(name)

    class ChapterCountMinFilter : Filter.Text("Min Chapters", "")
    class ChapterCountMaxFilter : Filter.Text("Max Chapters", "")
    class HasImagesFilter : Filter.CheckBox("Has Images", false)

    data class StatusEntry(val value: String, val label: String)
    data class TypeEntry(val value: String, val label: String)
    data class GenreEntry(val slug: String, val label: String)
    data class TagEntry(val slug: String, val label: String)

    // sort=popular → Most Popular, no sort → Recently Updated, sort=views, sort=longest, sort=rating, sort=newest
    private val sortOptions = listOf(
        Pair("", "Recently Updated"),
        Pair("popular", "Most Popular"),
        Pair("newest", "Newest"),
        Pair("views", "Most Views"),
        Pair("longest", "Longest"),
        Pair("rating", "Top Rated"),
    )

    private val statusOptions = listOf(
        StatusEntry("ONGOING", "Ongoing"),
        StatusEntry("COMPLETED", "Completed"),
        StatusEntry("DROPPED", "Dropped"),
        StatusEntry("CANCELLED", "Cancelled"),
        StatusEntry("HIATUS", "Hiatus"),
        StatusEntry("MASS_RELEASED", "Mass Released"),
        StatusEntry("COMING_SOON", "Coming Soon"),
    )

    private val typeOptions = listOf(
        TypeEntry("WEB_NOVEL", "Web Novel"),
        TypeEntry("MANHWA", "Manhwa"),
        TypeEntry("MANGA", "Manga"),
        TypeEntry("MANHUA", "Manhua"),
        TypeEntry("WEBTOON", "Webtoon"),
    )

    // Genre slugs from API response (genres[].slug field)
    private val genreList = listOf(
        GenreEntry("action", "Action"),
        GenreEntry("adventure", "Adventure"),
        GenreEntry("comedy", "Comedy"),
        GenreEntry("drama", "Drama"),
        GenreEntry("fantasy", "Fantasy"),
        GenreEntry("harem", "Harem"),
        GenreEntry("horror", "Horror"),
        GenreEntry("isekai", "Isekai"),
        GenreEntry("josei", "Josei"),
        GenreEntry("martial-arts", "Martial Arts"),
        GenreEntry("mature", "Mature"),
        GenreEntry("mecha", "Mecha"),
        GenreEntry("mystery", "Mystery"),
        GenreEntry("psychological", "Psychological"),
        GenreEntry("reincarnation", "Reincarnation"),
        GenreEntry("romance", "Romance"),
        GenreEntry("school-life", "School Life"),
        GenreEntry("sci-fi", "Sci-Fi"),
        GenreEntry("seinen", "Seinen"),
        GenreEntry("shoujo", "Shoujo"),
        GenreEntry("shounen", "Shounen"),
        GenreEntry("slice-of-life", "Slice of Life"),
        GenreEntry("sports", "Sports"),
        GenreEntry("supernatural", "Supernatural"),
        GenreEntry("thriller", "Thriller"),
        GenreEntry("tragedy", "Tragedy"),
        GenreEntry("wuxia", "Wuxia"),
        GenreEntry("xianxia", "Xianxia"),
        GenreEntry("yaoi", "Yaoi"),
        GenreEntry("yuri", "Yuri"),
        GenreEntry("adult", "Adult"),
        GenreEntry("ecchi", "Ecchi"),
        GenreEntry("smut", "Smut"),
        GenreEntry("dark-fantasy", "Dark Fantasy"),
        GenreEntry("cultivation", "Cultivation"),
        GenreEntry("historical", "Historical"),
        GenreEntry("military", "Military"),
        GenreEntry("system", "System"),
        GenreEntry("regression", "Regression"),
        GenreEntry("apocalypse", "Apocalypse"),
        GenreEntry("murim", "Murim"),
        GenreEntry("kingdom-building", "Kingdom Building"),
        GenreEntry("tower-climbing", "Tower Climbing"),
        GenreEntry("revenge", "Revenge"),
        GenreEntry("overpowered", "Overpowered"),
        GenreEntry("transmigration", "Transmigration"),
        GenreEntry("bl", "BL"),
        GenreEntry("gl", "GL"),
        GenreEntry("omegaverse", "Omegaverse"),
        GenreEntry("political", "Political"),
        GenreEntry("war", "War"),
        GenreEntry("zombie", "Zombie"),
        GenreEntry("vampire", "Vampire"),
        GenreEntry("cyberpunk", "Cyberpunk"),
        GenreEntry("dystopia", "Dystopia"),
        GenreEntry("survival", "Survival"),
        GenreEntry("game-world", "Game World"),
        GenreEntry("virtual-reality", "Virtual Reality"),
        GenreEntry("mmorpg", "MMORPG"),
        GenreEntry("idol", "Idol"),
        GenreEntry("entertainment-industry", "Entertainment Industry"),
        GenreEntry("cooking", "Cooking"),
        GenreEntry("medical", "Medical"),
        GenreEntry("business", "Business"),
        GenreEntry("urban-fantasy", "Urban Fantasy"),
        GenreEntry("modern-fantasy", "Modern Fantasy"),
    )

    // Tag slugs from API response (tags[].slug field)
    private val tagList = listOf(
        TagEntry("abandoned-children", "Abandoned Children"),
        TagEntry("ability-steal", "Ability Steal"),
        TagEntry("academy", "Academy"),
        TagEntry("aristocracy", "Aristocracy"),
        TagEntry("beautiful-female-lead", "Beautiful Female Lead"),
        TagEntry("calm-protagonist", "Calm Protagonist"),
        TagEntry("first-time-intercourse", "First-time Intercourse"),
        TagEntry("game-elements", "Game Elements"),
        TagEntry("hiding-true-abilities", "Hiding True Abilities"),
        TagEntry("magic-beasts", "Magic Beasts"),
        TagEntry("multiple-pov", "Multiple POV"),
        TagEntry("obsessive-love", "Obsessive Love"),
        TagEntry("summoning-magic", "Summoning Magic"),
        TagEntry("weak-to-strong", "Weak to Strong"),
        TagEntry("wizards", "Wizards"),
        TagEntry("yandere", "Yandere"),
        TagEntry("male-protagonist", "Male Protagonist"),
        TagEntry("female-protagonist", "Female Protagonist"),
        TagEntry("clever-protagonist", "Clever Protagonist"),
        TagEntry("royalty", "Royalty"),
        TagEntry("demons", "Demons"),
        TagEntry("monsters", "Monsters"),
        TagEntry("knights", "Knights"),
        TagEntry("elves", "Elves"),
        TagEntry("dragons", "Dragons"),
        TagEntry("necromancer", "Necromancer"),
        TagEntry("blacksmith", "Blacksmith"),
        TagEntry("healer", "Healer"),
        TagEntry("reincarnated-in-game-world", "Reincarnated in Game World"),
        TagEntry("second-chance", "Second Chance"),
        TagEntry("possessive-characters", "Possessive Characters"),
        TagEntry("love-triangle", "Love Triangle"),
        TagEntry("reverse-harem", "Reverse Harem"),
        TagEntry("hidden-identity", "Hidden Identity"),
        TagEntry("genius-protagonist", "Genius Protagonist"),
        TagEntry("overpowered-protagonist", "Overpowered Protagonist"),
        TagEntry("farming", "Farming"),
        TagEntry("childcare", "Childcare"),
        TagEntry("streaming", "Streaming"),
        TagEntry("gambling", "Gambling"),
        TagEntry("time-travel", "Time Travel"),
        TagEntry("alternate-history", "Alternate History"),
    )
}

private fun String.unescape(): String = this
    .replace("\\\"", "\"")
    .replace("\\n", "\n")
    .replace("\\r", "")
    .replace("\\/", "/")
    .replace("\\\\", "\\")
    .replace("\\t", "\t")

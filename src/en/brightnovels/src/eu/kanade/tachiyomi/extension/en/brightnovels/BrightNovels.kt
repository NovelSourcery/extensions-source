package eu.kanade.tachiyomi.extension.en.brightnovels

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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder

class BrightNovels :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "Bright Novels"
    override val baseUrl = "https://brightnovels.com"
    override val lang = "en"
    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Accept", "text/html, application/xhtml+xml")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override val client = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    @kotlinx.serialization.Serializable
    private data class CachedOption(val name: String, val value: String)

    override fun popularMangaRequest(page: Int): Request = seriesRequest(
        page = page,
        baseParams = mapOf(
            "sort" to "popular",
            "order" to "desc",
        ),
    )

    override fun popularMangaParse(response: Response): MangasPage = parseSeriesListing(response)

    override fun latestUpdatesRequest(page: Int): Request = seriesRequest(
        page = page,
        baseParams = mapOf(
            "sort" to "latest_upload",
            "order" to "desc",
        ),
    )

    override fun latestUpdatesParse(response: Response): MangasPage = parseSeriesListing(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.length >= 3 && !hasActiveFilters(filters)) {
            val searchUrl = "$baseUrl/api/search".toHttpUrl().newBuilder()
                .addQueryParameter("query", query)
                .build()
                .toString()
            return GET(searchUrl, headers)
        }

        val params = mutableMapOf<String, String>()
        params["order"] = "desc"

        if (query.isNotBlank()) {
            params["search"] = query
        }

        val selectedGenres = mutableSetOf<String>()
        val selectedTags = mutableSetOf<String>()
        var selectedCountry: String? = null

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> selectedGenres.addAll(filter.state.filter { it.state }.map { it.value })
                is GenreInputFilter -> selectedGenres.addAll(resolveOptionInputs(filter.state, genreOptions()).map { it.value })

                is TagFilter -> selectedTags.addAll(filter.state.filter { it.state }.map { it.value })
                is TagInputFilter -> selectedTags.addAll(resolveOptionInputs(filter.state, tagOptions()).map { it.value })

                is OriginFilter -> selectedCountry = filter.selectedValue()
                is CountryInputFilter -> selectedCountry = resolveOptionInputs(filter.state, countryOptions()).firstOrNull()?.value

                is StoryStatusFilter -> {
                    val value = filter.toUriPart()
                    if (value != "") {
                        params["story_state"] = value
                    }
                }

                is TypeFilter -> {
                    val value = filter.toUriPart()
                    if (value != "") {
                        params["type"] = value
                    }
                }

                is SortFilter -> params["sort"] = filter.toUriPart()
                is OrderFilter -> params["order"] = filter.toUriPart()
                else -> Unit
            }
        }

        if (selectedGenres.isNotEmpty()) {
            params["genres"] = selectedGenres.joinToString(",")
            params["genre_mode"] = "or"
        }

        if (selectedTags.isNotEmpty()) {
            params["tags"] = selectedTags.joinToString(",")
        }

        if (!selectedCountry.isNullOrBlank()) {
            params["origin"] = selectedCountry.orEmpty()
        }

        return seriesRequest(page, params)
    }

    override fun searchMangaParse(response: Response): MangasPage = if (response.request.url.encodedPath.contains("/api/search")) {
        parseApiSearch(response)
    } else {
        parseSeriesListing(response)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = decodePathSegment(
            manga.url.substringAfter("/series/")
                .substringBefore("?")
                .trim('/'),
        )
        return inertiaRequest("$baseUrl/series/${encodePathSegment(slug)}")
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body.string()
        val page = extractInertiaProps(body, response.headers)
        val series = page["series"].asObject() ?: JsonObject(emptyMap())
        cacheFilterMetadata(page, series)

        val genres = series["genres"].asArray()
            ?.mapNotNull { it.asObject()?.string("name") }
            ?.joinToString(", ")
            .orEmpty()

        val coverObj = series["cover"].asObject()

        return SManga.create().apply {
            title = series.string("title") ?: ""
            url = "/series/${encodePathSegment(series.string("slug").orEmpty())}"
            description = formatDescription(series.string("description").orEmpty())
            thumbnail_url = coverObj?.string("url")?.let(::absoluteUrl).orEmpty()
            genre = genres
            status = when (series.string("story_state")?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                "dropped" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val slug = decodePathSegment(
            manga.url.substringAfter("/series/")
                .substringBefore("?")
                .trim('/'),
        )
        return inertiaRequest("$baseUrl/series/${encodePathSegment(slug)}")
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val fallbackSeriesSlug = response.request.url.pathSegments
            .lastOrNull()
            ?.let(::decodePathSegment)
            .orEmpty()
        val body = response.body.string()
        val page = extractInertiaProps(body, response.headers)
        cacheFilterMetadata(page, page["series"].asObject())
        val series = page["series"].asObject() ?: JsonObject(emptyMap())
        val seriesSlug = series.string("slug")
            ?.takeIf { it.isNotBlank() }
            ?: fallbackSeriesSlug

        val showPremium = preferences.getBoolean(PREF_SHOW_PREMIUM, false)

        if (seriesSlug.isBlank()) {
            return extractChapterObjects(page, series)
                .mapNotNull { chapterElement -> chapterToSChapter(chapterElement, seriesSlug, showPremium) }
                .sortedByDescending { it.chapter_number }
        }

        val chapterElements = linkedMapOf<String, JsonElement>()

        fun merge(elements: List<JsonElement>) {
            elements.forEach { element ->
                val chapterObj = element.asObject() ?: return@forEach
                val key = chapterObj.string("slug") ?: chapterObj.string("id") ?: return@forEach
                chapterElements[key] = element
            }
        }

        if (showPremium) {
            merge(fetchChapterObjectsFromEndpoint(seriesSlug, premium = true))
        }
        merge(fetchChapterObjectsFromEndpoint(seriesSlug, premium = false))

        if (chapterElements.isEmpty()) {
            merge(extractChapterObjects(page, series))
        }

        return chapterElements.values
            .mapNotNull { chapterToSChapter(it, seriesSlug, showPremium) }
            .sortedByDescending { it.chapter_number }
    }

    override fun pageListRequest(chapter: SChapter): Request = inertiaRequest(absoluteUrl(chapter.url))

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString(), null))

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(inertiaRequest(page.url)).execute()
        val body = response.body.string()
        val props = extractInertiaProps(body, response.headers)
        val chapter = props["chapter"].asObject() ?: return ""

        return chapter.string("content")
            ?: Jsoup.parse(chapter.string("title").orEmpty()).text()
    }

    override fun getMangaUrl(manga: SManga): String {
        val slug = decodePathSegment(
            manga.url.substringAfter("/series/")
                .substringBefore("?")
                .trim('/'),
        )
        return "$baseUrl/series/${encodePathSegment(slug)}"
    }

    override fun imageUrlParse(response: Response) = ""

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val showPremiumPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_PREMIUM
            title = "Show premium chapters"
            summary = "Include premium/locked chapters in the chapter list"
            setDefaultValue(false)
        }
        screen.addPreference(showPremiumPref)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_CLEAR_FILTER_CACHE
            title = "Clear cached filter lists"
            summary = "Toggle this to clear cached genres, tags, and countries. They will be rebuilt from the next page you open."
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                preferences.edit()
                    .remove(PREF_GENRES_CACHE)
                    .remove(PREF_TAGS_CACHE)
                    .remove(PREF_COUNTRIES_CACHE)
                    .apply()
                true
            }
        }.also(screen::addPreference)
    }

    override fun getFilterList(): FilterList {
        ensureFilterMetadataLoaded()
        return FilterList(
            Filter.Header("Bright Novels filters"),
            Filter.Separator(),
            GenreFilter(genreOptions()),
            StoryStatusFilter(),
            TypeFilter(),
            OriginFilter(countryOptions()),
            SortFilter(),
            OrderFilter(),
            Filter.Separator(),
            Filter.Header("Extra filter inputs"),
            TagInputFilter(),
            TagFilter(tagOptions()),
            GenreInputFilter(),
            CountryInputFilter(),
        )
    }

    private fun cacheFilterMetadata(page: JsonObject, series: JsonObject? = null) {
        mergeCachedOptions(PREF_GENRES_CACHE, extractOptions(page["genres"].asArray(), "name", "slug"))
        mergeCachedOptions(PREF_TAGS_CACHE, extractOptions(page["tags"].asArray(), "name", "slug"))
        mergeCachedOptions(PREF_COUNTRIES_CACHE, extractOptions(page["origins"].asArray(), "name", "slug"))

        series?.let {
            mergeCachedOptions(PREF_GENRES_CACHE, extractOptions(it["genres"].asArray(), "name", "slug"))
        }
    }

    private fun genreOptions(): List<CachedOption> = mergeOptions(DEFAULT_GENRES, loadCachedOptions(PREF_GENRES_CACHE))

    private fun tagOptions(): List<CachedOption> = mergeOptions(DEFAULT_TAGS, loadCachedOptions(PREF_TAGS_CACHE))

    private fun countryOptions(): List<CachedOption> = mergeOptions(DEFAULT_COUNTRIES, loadCachedOptions(PREF_COUNTRIES_CACHE))

    private fun mergeOptions(vararg sources: List<CachedOption>): List<CachedOption> {
        val merged = linkedMapOf<String, CachedOption>()
        sources.forEach { source ->
            source.forEach { option ->
                merged.putIfAbsent(option.value.lowercase(), option)
            }
        }
        return merged.values.toList()
    }

    private fun loadCachedOptions(key: String): List<CachedOption> {
        val raw = preferences.getString(key, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<CachedOption>>(raw) }.getOrDefault(emptyList())
    }

    private fun mergeCachedOptions(key: String, options: List<CachedOption>) {
        if (options.isEmpty()) return
        val merged = linkedMapOf<String, CachedOption>()
        loadCachedOptions(key).forEach { merged[it.value.lowercase()] = it }
        options.forEach { merged[it.value.lowercase()] = it }
        preferences.edit().putString(key, json.encodeToString(merged.values.toList())).apply()
    }

    private fun extractOptions(array: JsonArray?, nameKey: String, valueKey: String): List<CachedOption> {
        if (array == null || array.isEmpty()) return emptyList()
        return array.mapNotNull { element ->
            val obj = element.asObject() ?: return@mapNotNull null
            val name = obj.string(nameKey)?.trim().orEmpty()
            val value = obj.string(valueKey)?.trim().orEmpty().ifBlank { name }
            if (name.isBlank() || value.isBlank()) null else CachedOption(name, value)
        }
    }

    private fun resolveOptionInputs(input: String, options: List<CachedOption>): List<CachedOption> {
        if (input.isBlank()) return emptyList()
        val byName = options.associateBy { it.name.trim().lowercase() }
        val byValue = options.associateBy { it.value.trim().lowercase() }
        return input.split(",").mapNotNull { token ->
            val raw = token.trim()
            if (raw.isBlank()) return@mapNotNull null
            byName[raw.lowercase()] ?: byValue[raw.lowercase()] ?: CachedOption(raw, raw)
        }
    }

    private fun ensureFilterMetadataLoaded() {
        val hasGenres = loadCachedOptions(PREF_GENRES_CACHE).isNotEmpty()
        val hasTags = loadCachedOptions(PREF_TAGS_CACHE).isNotEmpty()
        val hasCountries = loadCachedOptions(PREF_COUNTRIES_CACHE).isNotEmpty()
        if (hasGenres && hasTags && hasCountries) return

        runCatching {
            val url = "$baseUrl/series".toHttpUrl().newBuilder()
                .addQueryParameter("page", "1")
                .addQueryParameter("sort", "latest_upload")
                .addQueryParameter("order", "desc")
                .build()
                .toString()
            val response = client.newCall(inertiaRequest(url)).execute()
            val body = response.body.string()
            val page = extractInertiaProps(body, response.headers)
            cacheFilterMetadata(page, page["series"].asObject())
        }
    }

    private fun hasActiveFilters(filters: FilterList): Boolean = filters.any { filter ->
        when (filter) {
            is GenreFilter -> filter.state.any { it.state }
            is TagFilter -> filter.state.any { it.state }
            is OriginFilter -> filter.state > 0
            is GenreInputFilter -> filter.state.isNotBlank()
            is TagInputFilter -> filter.state.isNotBlank()
            is CountryInputFilter -> filter.state.isNotBlank()
            is StoryStatusFilter -> filter.state > 0
            is TypeFilter -> filter.state > 0
            is SortFilter -> filter.state > 0
            is OrderFilter -> filter.state > 0
            else -> false
        }
    }

    private class GenreInputFilter : Filter.Text("Genres (comma-separated)")

    private class TagInputFilter : Filter.Text("Tags (comma-separated)")

    private class CountryInputFilter : Filter.Text("Country (comma-separated)")

    private fun parseSeriesListing(response: Response): MangasPage {
        val body = response.body.string()
        val page = extractInertiaProps(body, response.headers)
        cacheFilterMetadata(page, page["series"].asObject())

        val seriesPage = page["seriesList"].asObject()
            ?: page["series"].asObject()
            ?: JsonObject(emptyMap())
        val data = seriesPage["data"].asArray() ?: JsonArray(emptyList())

        val mangas = data.mapNotNull { it.toSeriesManga() }
        val hasNext = seriesPage["next_page_url"].asPrimitive()?.contentOrNull != null

        return MangasPage(mangas, hasNext)
    }

    private fun parseApiSearch(response: Response): MangasPage {
        val root = runCatching { json.parseToJsonElement(response.body.string()) }
            .getOrNull()
            .asObject()
            ?: return MangasPage(emptyList(), false)

        root["props"].asObject()?.let { cacheFilterMetadata(it, it["series"].asObject()) }

        val series = when (val data = root["data"]) {
            is JsonArray -> data
            is JsonObject -> data["series"].asArray() ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }

        val mangas = series.mapNotNull { it.toSeriesManga() }
        return MangasPage(mangas, false)
    }

    private fun JsonElement.toSeriesManga(): SManga? {
        val obj = this.asObject() ?: return null
        val slug = obj.string("slug") ?: return null

        val cover = obj["cover"].asObject()
        val coverUrl = cover?.string("url")
            ?: cover?.string("thumbnail_url")

        return SManga.create().apply {
            title = obj.string("title") ?: "Unknown Title"
            url = "/series/${encodePathSegment(slug)}"
            thumbnail_url = coverUrl?.let(::absoluteUrl).orEmpty()
        }
    }

    private fun extractInertiaProps(body: String, headers: Headers): JsonObject {
        captureXsrfToken(headers)

        parseInertiaJsonProps(body)?.let { return it }
        parseInertiaHtmlProps(body)?.let { return it }

        return JsonObject(emptyMap())
    }

    private fun parseInertiaJsonProps(body: String): JsonObject? {
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val root = when (element) {
            is JsonObject -> element
            is JsonPrimitive -> {
                val nested = element.contentOrNull?.trim().orEmpty()
                if (nested.startsWith("{") && nested.endsWith("}")) {
                    runCatching { json.parseToJsonElement(nested) }.getOrNull().asObject()
                } else {
                    null
                }
            }
            else -> null
        } ?: return null

        root["version"].asPrimitive()?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { preferences.edit().putString(PREF_INERTIA_VERSION, it).apply() }

        return extractPropsFromInertiaRoot(root)
    }

    private fun parseInertiaHtmlProps(body: String): JsonObject? {
        val candidates = linkedSetOf<String>()
        val doc = Jsoup.parse(body)
        doc.selectFirst("#app")?.attr("data-page")
            ?.takeIf { it.isNotBlank() }
            ?.let { candidates.add(it) }

        val doubleQuoteMatches = Regex(
            """data-page\s*=\s*"([^"]*)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(body).map { it.groupValues[1] }
        candidates.addAll(doubleQuoteMatches)

        val singleQuoteMatches = Regex(
            """data-page\s*=\s*'([^']*)'""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(body).map { it.groupValues[1] }
        candidates.addAll(singleQuoteMatches)

        candidates.forEach { raw ->
            val variants = listOf(
                raw,
                Parser.unescapeEntities(raw, true),
                Parser.unescapeEntities(raw, false),
            ).distinct().filter { it.isNotBlank() }

            variants.forEach { jsonText ->
                val root = runCatching { json.parseToJsonElement(jsonText) }.getOrNull().asObject() ?: return@forEach
                root["version"].asPrimitive()?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { preferences.edit().putString(PREF_INERTIA_VERSION, it).apply() }
                extractPropsFromInertiaRoot(root)?.let { return it }
            }
        }

        return null
    }

    private fun extractPropsFromInertiaRoot(root: JsonObject): JsonObject? {
        val directProps = root["props"].asObject()
        if (directProps != null) return directProps

        val pageProps = root["page"].asObject()?.get("props").asObject()
        if (pageProps != null) return pageProps

        val dataProps = root["data"].asObject()?.get("props").asObject()
        return dataProps
    }

    private fun absoluteUrl(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("//") -> "https:$path"
        else -> "$baseUrl/${path.trimStart('/')}"
    }

    private fun encodePathSegment(value: String): String = "$baseUrl/".toHttpUrl().newBuilder()
        .addPathSegment(value)
        .build()
        .encodedPath
        .substringAfterLast('/')

    private fun decodePathSegment(value: String): String = runCatching {
        URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)

    private fun parseDate(date: String?): Long {
        if (date.isNullOrBlank()) return 0L
        return try {
            java.time.Instant.parse(date).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    private fun formatDescription(rawHtml: String): String {
        if (rawHtml.isBlank()) return ""

        val breakToken = "__BRIGHTNOVELS_BR__"
        val paragraphToken = "__BRIGHTNOVELS_P__"
        val doc = Jsoup.parseBodyFragment(rawHtml)

        doc.select("br").forEach { it.after(breakToken) }
        doc.select("p").forEach { it.after(paragraphToken) }

        return doc.text()
            .replace(Regex("\\s*$paragraphToken\\s*"), "\n\n")
            .replace(Regex("\\s*$breakToken\\s*"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun captureXsrfToken(headers: Headers) {
        val xsrfCookie = headers.values("set-cookie")
            .firstOrNull { it.startsWith("XSRF-TOKEN=", ignoreCase = true) }
            ?.substringAfter("=", "")
            ?.substringBefore(";", "")
            .orEmpty()

        if (xsrfCookie.isNotBlank()) {
            val decoded = runCatching { URLDecoder.decode(xsrfCookie, "UTF-8") }.getOrDefault(xsrfCookie)
            preferences.edit().putString(PREF_XSRF_TOKEN, decoded).apply()
        }
    }

    private fun extractChapterObjects(props: JsonObject, series: JsonObject): List<JsonElement> {
        val candidates = listOf(
            series["chapters"],
            props["chapters"],
            props["chapterList"],
            props["seriesChapters"],
        )

        candidates.forEach { candidate ->
            val chapterArray = when (candidate) {
                is JsonArray -> candidate
                is JsonObject -> candidate["data"].asArray() ?: candidate["chapters"].asArray()
                else -> null
            } ?: return@forEach

            if (chapterArray.isNotEmpty()) {
                return chapterArray.toList()
            }
        }

        return emptyList()
    }

    private fun parseChapterObjectsFromBody(body: String, headers: Headers): List<JsonElement> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull().asObject()
        val directChapters = root
            ?.get("chapters")
            .asArray()
            ?.toList()
            .orEmpty()

        if (directChapters.isNotEmpty()) return directChapters

        val props = extractInertiaProps(body, headers)
        val series = props["series"].asObject() ?: JsonObject(emptyMap())
        val chapters = extractChapterObjects(props, series)

        if (chapters.isNotEmpty()) return chapters

        val fallbackChapters = root
            ?.get("data")
            .asObject()
            ?.get("chapters")
            .asArray()
            ?.toList()
            .orEmpty()

        return fallbackChapters
    }

    private fun chapterToSChapter(
        chapterElement: JsonElement,
        fallbackSeriesSlug: String,
        showPremium: Boolean,
    ): SChapter? {
        val chapter = chapterElement.asObject() ?: return null
        val isPremium = chapter["is_premium"].asPrimitive()?.contentOrNull?.toBooleanStrictOrNull() ?: false
        if (isPremium && !showPremium) return null

        val chapterSeriesSlug = chapter["series"]
            .asObject()
            ?.string("slug")
            ?.takeIf { it.isNotBlank() }
            ?: fallbackSeriesSlug
        if (chapterSeriesSlug.isBlank()) return null

        val chapterSlug = chapter.string("slug") ?: return null
        val chapterNumber = chapter["number"].asPrimitive()?.contentOrNull?.toFloatOrNull()
            ?: chapterSlug.toFloatOrNull()
            ?: -1f
        val chapterName = chapter.string("name")
            ?: chapter.string("title")
            ?: "Chapter ${chapter.string("number") ?: ""}".trim()

        return SChapter.create().apply {
            name = chapterName
            url = "/series/${encodePathSegment(chapterSeriesSlug)}/${encodePathSegment(chapterSlug)}"
            chapter_number = chapterNumber
            date_upload = parseDate(chapter.string("index_at"))
        }
    }

    private fun chapterEndpointRequest(seriesSlug: String, premium: Boolean): Request {
        val tab = if (premium) "premium" else "free"
        val encodedSlug = encodePathSegment(seriesSlug)
        val url = "$baseUrl/series/$encodedSlug/chapters/$tab".toHttpUrl().newBuilder()
            .addQueryParameter("loaded", "0")
            .addQueryParameter("sort_order", "desc")
            .build()
            .toString()
        return inertiaRequest(url)
    }

    private fun fetchChapterObjectsFromEndpoint(seriesSlug: String, premium: Boolean): List<JsonElement> = try {
        val response = client.newCall(chapterEndpointRequest(seriesSlug, premium)).execute()
        parseChapterObjectsFromBody(response.body.string(), response.headers)
    } catch (_: Exception) {
        emptyList()
    }

    private fun seriesRequest(page: Int, baseParams: Map<String, String>): Request {
        val builder = "$baseUrl/series".toHttpUrl().newBuilder()
        baseParams.forEach { (key, value) ->
            if (value.isNotBlank()) {
                builder.addQueryParameter(key, value)
            }
        }
        builder.addQueryParameter("page", page.toString())
        return inertiaRequest(builder.build().toString())
    }

    private fun inertiaRequest(url: String): Request {
        val inertiaVersion = preferences.getString(PREF_INERTIA_VERSION, null)?.takeIf { it.isNotBlank() }
        val xsrfToken = preferences.getString(PREF_XSRF_TOKEN, null)?.takeIf { it.isNotBlank() }

        // Bootstrap flow:
        // 1) if we don't know the version yet, use plain HTML request and parse #app[data-page]
        // 2) once version is known, send Inertia headers to get JSON props directly
        val requestHeaders = if (inertiaVersion == null) {
            val builder = headersBuilder()
                .removeAll("X-Inertia")
                .removeAll("X-Inertia-Version")
                .removeAll("X-Inertia-Partial-Component")
                .removeAll("X-Inertia-Partial-Data")
                .removeAll("X-Requested-With")
            xsrfToken?.let { builder.set("X-XSRF-TOKEN", it) }
            builder.build()
        } else {
            val builder = headersBuilder()
                .set("X-Inertia", "true")
                .set("X-Requested-With", "XMLHttpRequest")
                .set("X-Inertia-Version", inertiaVersion)
                .removeAll("X-Inertia-Partial-Component")
                .removeAll("X-Inertia-Partial-Data")
            xsrfToken?.let { builder.set("X-XSRF-TOKEN", it) }
            builder.build()
        }

        return Request.Builder()
            .url(url)
            .headers(requestHeaders)
            .get()
            .build()
    }

    private fun JsonObject.string(key: String): String? = this[key].asPrimitive()?.contentOrNull

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

    private fun JsonElement?.asPrimitive(): JsonPrimitive? = this as? JsonPrimitive

    private class GenreCheckBox(name: String, val value: String) : Filter.CheckBox(name)

    private class GenreFilter(options: List<CachedOption>) :
        Filter.Group<GenreCheckBox>(
            "Genres",
            options.map { GenreCheckBox(it.name, it.value) },
        )

    private class TagCheckBox(name: String, val value: String) : Filter.CheckBox(name)

    private class TagFilter(options: List<CachedOption>) :
        Filter.Group<TagCheckBox>(
            "Tags",
            options.map { TagCheckBox(it.name, it.value) },
        )

    private class StoryStatusFilter :
        Filter.Select<String>(
            "Story status",
            arrayOf("All", "Ongoing", "Completed", "Hiatus", "Dropped"),
        ) {
        fun toUriPart() = when (state) {
            1 -> "ongoing"
            2 -> "completed"
            3 -> "hiatus"
            4 -> "dropped"
            else -> ""
        }
    }

    private class TypeFilter :
        Filter.Select<String>(
            "Type",
            arrayOf("All", "Web Novel", "Light Novel", "Novel"),
        ) {
        fun toUriPart() = when (state) {
            1 -> "web_novel"
            2 -> "light_novel"
            3 -> "novel"
            else -> ""
        }
    }

    private class OriginFilter(options: List<CachedOption>) :
        Filter.Select<String>(
            "Country",
            (listOf("All") + options.map { it.name }).toTypedArray(),
        ) {
        private val optionValues = listOf("") + options.map { it.value }

        fun selectedValue(): String? = optionValues.getOrNull(state)?.takeIf { it.isNotBlank() }
    }

    private class SortFilter :
        Filter.Select<String>(
            "Sort by",
            arrayOf("Latest Upload", "Popular", "Title", "Release Year"),
        ) {
        fun toUriPart() = when (state) {
            0 -> "latest_upload"
            1 -> "popular"
            2 -> "title"
            3 -> "release_year"
            else -> "latest_upload"
        }
    }

    private class OrderFilter :
        Filter.Select<String>(
            "Order",
            arrayOf("Descending", "Ascending"),
        ) {
        fun toUriPart() = when (state) {
            1 -> "asc"
            else -> "desc"
        }
    }

    companion object {
        private const val PREF_SHOW_PREMIUM = "show_premium_chapters"
        private const val PREF_INERTIA_VERSION = "inertia_version"
        private const val PREF_XSRF_TOKEN = "xsrf_token"
        private const val PREF_CLEAR_FILTER_CACHE = "brightnovels_clear_filter_cache"
        private const val PREF_GENRES_CACHE = "brightnovels_genres_cache"
        private const val PREF_TAGS_CACHE = "brightnovels_tags_cache"
        private const val PREF_COUNTRIES_CACHE = "brightnovels_countries_cache"

        private val DEFAULT_GENRES = listOf(
            CachedOption("Action", "action"),
            CachedOption("Adult", "adult"),
            CachedOption("Adventure", "adventure"),
            CachedOption("Comedy", "comedy"),
            CachedOption("Drama", "drama"),
            CachedOption("Fantasy", "fantasy"),
            CachedOption("Historical", "historical"),
            CachedOption("Horror", "horror"),
            CachedOption("Martial Arts", "martial-arts"),
            CachedOption("Mature", "mature"),
            CachedOption("Mystery", "mystery"),
            CachedOption("Romance", "romance"),
            CachedOption("School Life", "school-life"),
            CachedOption("Sci-fi", "sci-fi"),
            CachedOption("Slice of Life", "slice-of-life"),
            CachedOption("Supernatural", "supernatural"),
            CachedOption("Tragedy", "tragedy"),
            CachedOption("Wuxia", "wuxia"),
            CachedOption("Xianxia", "xianxia"),
            CachedOption("Yaoi", "yaoi"),
            CachedOption("Yuri", "yuri"),
        )

        private val DEFAULT_COUNTRIES = listOf(
            CachedOption("China", "china"),
            CachedOption("Japan", "japan"),
            CachedOption("Korea", "korea"),
        )

        private val DEFAULT_TAGS = listOf(
            CachedOption("Academy", "academy"),
            CachedOption("Action", "action"),
            CachedOption("Adventure", "adventure"),
            CachedOption("Cultivation", "cultivation"),
            CachedOption("Dungeons", "dungeons"),
            CachedOption("Fantasy World", "fantasy-world"),
            CachedOption("Game Elements", "game-elements"),
            CachedOption("Harem", "harem"),
            CachedOption("Magic", "magic"),
            CachedOption("Martial Arts", "martial-arts"),
            CachedOption("Overpowered Protagonist", "overpowered-protagonist"),
            CachedOption("Reincarnation", "reincarnation"),
            CachedOption("Romance", "romance"),
            CachedOption("School Life", "school-life"),
            CachedOption("Slice of Life", "slice-of-life"),
            CachedOption("System", "system"),
            CachedOption("Transmigration", "transmigration"),
            CachedOption("Weak to Strong", "weak-to-strong"),
            CachedOption("Yaoi", "yaoi"),
            CachedOption("Yuri", "yuri"),
        )
    }
}

package eu.kanade.tachiyomi.novelextension.en.lnori

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class Lnori :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "Lnori"
    override val baseUrl = "https://lnori.com"
    override val lang = "all"
    override val supportsLatest = true
    override val isNovelSource = true

    override val client = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private var cachedNovels: List<NovelData>? = null
    private var cacheTimestamp: Long = 0
    private val cacheLifetime = DAY_MILLIS

    @Serializable
    data class NovelData(
        val id: String,
        val title: String,
        val author: String,
        val tags: List<String>,
        val rel: Int,
        val date: String,
        val volumes: Int,
        val url: String,
        val coverUrl: String,
        val description: String,
    )

    // ======================== Helper: Resolve Image URL ========================

    private fun resolveImageUrl(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "$baseUrl$url"
        else -> "$baseUrl/$url"
    }

    // ======================== Load All Data ========================

    private fun loadAllNovels(): List<NovelData> {
        val currentTime = System.currentTimeMillis()
        if (cachedNovels != null && currentTime - cacheTimestamp < cacheLifetime) {
            updateFilterCache(cachedNovels!!)
            return cachedNovels!!
        }

        loadCachedNovels()?.let { cached ->
            if (currentTime - loadCachedNovelsTimestamp() < cacheLifetime) {
                cachedNovels = cached
                cacheTimestamp = loadCachedNovelsTimestamp()
                updateFilterCache(cached)
                return cached
            }
        }

        val novels = try {
            // Lnori exposes the entire series catalog at /library — fetch that page
            val response = client.newCall(GET("$baseUrl/library", headers)).execute()
            val html = response.body.string()
            val document = Jsoup.parse(html)

            // Primary parsing: article.card elements with data-* attributes
            parseFromLibrary(document)
                .ifEmpty { parseFromJsonScript(html, document) }
                .ifEmpty { parseFromArticleElements(document) }
                .ifEmpty { parseFromLinksAndTitles(document) }
                .also {
                    if (it.isNotEmpty()) {
                        saveCachedNovels(it, currentTime)
                    }
                }
        } catch (_: Exception) {
            cachedNovels ?: loadCachedNovels().orEmpty()
        }

        // Update cached filters (tags, authors) from results
        try {
            updateFilterCache(novels)
        } catch (_: Exception) {}

        cachedNovels = novels
        cacheTimestamp = currentTime
        return novels
    }

    /** Try to extract novels from embedded JSON (__NEXT_DATA__ or similar window.* scripts) */
    private fun parseFromJsonScript(html: String, document: Document): List<NovelData> {
        val novels = mutableListOf<NovelData>()

        // Try __NEXT_DATA__ (Next.js)
        val nextDataScript = document.selectFirst("script#__NEXT_DATA__")
        if (nextDataScript != null) {
            try {
                val root = json.parseToJsonElement(nextDataScript.html()).jsonObject
                // Walk down props.pageProps to find an array of novels
                val pageProps = root["props"]?.jsonObject?.get("pageProps")?.jsonObject
                // Try common keys
                for (key in listOf("novels", "books", "series", "items", "data", "posts")) {
                    val arr = pageProps?.get(key)?.jsonArray
                    if (arr != null && arr.isNotEmpty()) {
                        arr.forEach { elem ->
                            parseJsonNovelItem(elem.jsonObject)?.let { novels.add(it) }
                        }
                        if (novels.isNotEmpty()) return novels
                    }
                }
                // Deep search for any array with slug/title fields
                val found = findNovelArrayInJson(root)
                if (found.isNotEmpty()) return found
            } catch (_: Exception) {}
        }

        // Try window.__DATA__ = {...}  or  window.__NOVELS__ = [...]
        val scriptBodies = document.select("script:not([src])").map { it.html() }
        for (scriptBody in scriptBodies) {
            val jsonMatch = Regex("""window\.__(?:DATA|NOVELS|BOOKS|SERIES|APP_DATA)__\s*=\s*(\{[\s\S]*?\});?\s*\n""")
                .find(scriptBody)
                ?: Regex("""window\.__(?:DATA|NOVELS|BOOKS|SERIES|APP_DATA)__\s*=\s*(\[[\s\S]*?\]);?\s*\n""")
                    .find(scriptBody)
            if (jsonMatch != null) {
                try {
                    val root = json.parseToJsonElement(jsonMatch.groupValues[1])
                    val found = findNovelArrayInJson(root)
                    if (found.isNotEmpty()) return found
                } catch (_: Exception) {}
            }

            // Try JSON-LD with @type Book/CreativeWorkSeries
            if (scriptBody.contains("\"@type\"") && (scriptBody.contains("Book") || scriptBody.contains("Novel"))) {
                try {
                    val root = json.parseToJsonElement(scriptBody.trim())
                    val arr = when {
                        root.jsonObject.containsKey("@graph") -> root.jsonObject["@graph"]!!.jsonArray
                        root is JsonArray -> root.jsonArray
                        else -> null
                    }
                    arr?.forEach { elem ->
                        parseJsonLdItem(elem.jsonObject)?.let { novels.add(it) }
                    }
                } catch (_: Exception) {}
            }
        }

        return novels
    }

    private fun findNovelArrayInJson(element: JsonElement): List<NovelData> {
        val novels = mutableListOf<NovelData>()
        try {
            when {
                element is JsonArray -> {
                    if (element.size > 0) {
                        val first = element[0].jsonObject
                        if (first.containsKey("title") || first.containsKey("slug") || first.containsKey("name")) {
                            element.forEach { parseJsonNovelItem(it.jsonObject)?.let { n -> novels.add(n) } }
                            if (novels.isNotEmpty()) return novels
                        }
                    }
                    // recurse
                    for (e in element) {
                        val found = findNovelArrayInJson(e)
                        if (found.isNotEmpty()) return found
                    }
                }

                element is JsonObject -> {
                    for ((_, v) in element) {
                        val found = findNovelArrayInJson(v)
                        if (found.isNotEmpty()) return found
                    }
                }

                else -> {}
            }
        } catch (_: Exception) {}
        return novels
    }

    private fun parseJsonNovelItem(obj: JsonObject): NovelData? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull
            ?: obj["slug"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull
            ?: obj["name"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val author = obj["author"]?.jsonPrimitive?.contentOrNull ?: ""
        val tagsRaw = try {
            obj["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: id
        val cover = obj["coverImage"]?.jsonPrimitive?.contentOrNull
            ?: obj["cover"]?.jsonPrimitive?.contentOrNull ?: ""
        val volumes = obj["volumeCount"]?.jsonPrimitive?.intOrNull
            ?: obj["volumes"]?.jsonPrimitive?.intOrNull ?: 1
        val date = obj["updatedAt"]?.jsonPrimitive?.contentOrNull
            ?: obj["publishedAt"]?.jsonPrimitive?.contentOrNull ?: ""
        val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
        return NovelData(
            id = id, title = title, author = author, tags = tagsRaw,
            rel = 0, date = date, volumes = volumes,
            url = "/$slug/", coverUrl = resolveImageUrl(cover), description = desc,
        )
    }

    private fun parseJsonLdItem(obj: JsonObject): NovelData? {
        val type = obj["@type"]?.jsonPrimitive?.contentOrNull ?: return null
        if (!type.contains("Book") && !type.contains("Novel") && !type.contains("Series")) return null
        val title = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return null
        val author = try {
            obj["author"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: ""
        } catch (_: Exception) {
            ""
        }
        val cover = try {
            obj["image"]?.jsonPrimitive?.contentOrNull ?: ""
        } catch (_: Exception) {
            ""
        }
        val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
        val slug = url.trimEnd('/').substringAfterLast('/')
        return NovelData(
            id = slug, title = title, author = author, tags = emptyList(),
            rel = 0, date = "", volumes = 1,
            url = url.removePrefix(baseUrl).ifEmpty { "/$slug/" },
            coverUrl = resolveImageUrl(cover), description = desc,
        )
    }

    /** Try article elements with data-* attributes or generic article/card structure */
    private fun parseFromArticleElements(document: Document): List<NovelData> {
        // Prefer elements with data-id; fall back to any article
        val cards = document.select("[data-id]").ifEmpty {
            document.select("article, .card, .book-item, .novel-item, li.item")
        }
        return cards.mapNotNull { card ->
            // ID: prefer data-id, otherwise derive from URL
            val dataId = card.attr("data-id")
            val link = card.selectFirst("a[href]")
            val href = link?.attr("href") ?: ""

            val id = dataId.ifEmpty {
                // derive from href: /series/3336/slug ? "3336-slug" or last path segment
                href.trimEnd('/').split("/").filter { it.isNotEmpty() }.takeLast(2)
                    .joinToString("-").ifEmpty { return@mapNotNull null }
            }

            val title = card.attr("data-t").ifEmpty {
                card.selectFirst("h3, h2, h4, [class*=title], [class*=name]")?.text()?.trim()
                    ?: link?.text()?.trim()?.takeIf { it.length > 2 }
                    ?: return@mapNotNull null
            }

            val author = card.attr("data-a").ifEmpty {
                card.selectFirst("[class*=author], .author")?.text()?.trim() ?: ""
            }
            val tagsRaw = card.attr("data-tags").ifEmpty { card.attr("data-tag") }
            val tags = tagsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val rel = card.attr("data-rel").toIntOrNull() ?: 0
            val date = card.attr("data-d").ifEmpty { card.attr("data-date") }
            val volumes = card.attr("data-v").toIntOrNull() ?: 1
            val cardUrl = href.ifEmpty { "/$id/" }
            val imgEl = card.selectFirst("img")
            val cover = imgEl?.let { img ->
                img.attr("data-src").ifEmpty { null }
                    ?: img.attr("srcset").split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotEmpty() }
                    ?: img.attr("src").ifEmpty { null }
            }?.let { resolveImageUrl(it) } ?: ""
            NovelData(
                id = id, title = title, author = author, tags = tags,
                rel = rel, date = date, volumes = volumes,
                url = if (cardUrl.startsWith("/")) cardUrl else "/$cardUrl",
                coverUrl = cover,
                description = card.selectFirst("[class*=desc], [class*=synopsis]")?.text() ?: "",
            )
        }
    }

    /** Last resort: extract from any card-like links with titles */
    private fun parseFromLinksAndTitles(document: Document): List<NovelData> {
        return document.select("article a[href], .card a[href], .book-item a[href], a[href*='/series/']")
            .distinctBy { it.attr("href") }
            .mapNotNull { link ->
                val href = link.attr("href").takeIf { it.contains("/") } ?: return@mapNotNull null
                val title = link.selectFirst("h3, h2, h4, [class*=title]")?.text()?.trim()
                    ?: link.attr("title").trim().takeIf { it.length > 2 }
                    ?: link.text().trim().takeIf { it.length > 3 }
                    ?: return@mapNotNull null
                // Extract ID from href: /series/3336/slug ? "3336-slug"
                val pathParts = href.trimEnd('/').split("/").filter { it.isNotEmpty() }
                val slug = pathParts.takeLast(2).joinToString("-").ifEmpty { href.trimEnd('/').substringAfterLast('/') }
                val img = link.selectFirst("img")
                val cover = img?.attr("src")?.ifEmpty { img.attr("data-src") }?.let { resolveImageUrl(it) } ?: ""
                NovelData(
                    id = slug, title = title, author = "", tags = emptyList(),
                    rel = 0, date = "", volumes = 1,
                    url = if (href.startsWith("/")) href else "/$href",
                    coverUrl = cover, description = "",
                )
            }
    }

    private fun novelDataToSManga(novel: NovelData): SManga = SManga.create().apply {
        url = novel.url.let {
            when {
                it.startsWith("http") -> it.removePrefix(baseUrl)
                it.startsWith("/") -> it
                else -> "/$it"
            }
        }
        title = novel.title
        thumbnail_url = novel.coverUrl
        author = novel.author
        genre = novel.tags.joinToString(", ")
        description = novel.description
    }

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val novels = loadAllNovels()
        val sorted = novels.sortedByDescending { it.rel }
        val mangas = sorted.map { novelDataToSManga(it) }
        return MangasPage(mangas, false)
    }

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val novels = loadAllNovels()
        val sorted = novels.sortedByDescending { it.date }
        val mangas = sorted.map { novelDataToSManga(it) }
        return MangasPage(mangas, false)
    }

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET(baseUrl, headers)

    override fun searchMangaParse(response: Response): MangasPage = MangasPage(emptyList(), false)

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): rx.Observable<MangasPage> = rx.Observable.fromCallable {
        var novels = loadAllNovels()

        if (query.isNotBlank()) {
            val queryLower = query.lowercase()
            novels = novels.filter { novel ->
                novel.title.lowercase().contains(queryLower) ||
                    novel.author.lowercase().contains(queryLower) ||
                    novel.description.lowercase().contains(queryLower)
            }
        }

        filters.forEach { filter ->
            when (filter) {
                is TagFilter -> {
                    // Collect include/exclude lists from tri-state tags
                    val includes = filter.state.filterIsInstance<TagTriState>().filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.name.lowercase() }
                    val excludes = filter.state.filterIsInstance<TagTriState>().filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.name.lowercase() }

                    if (includes.isNotEmpty()) {
                        novels = novels.filter { novel ->
                            val novelTags = novel.tags.map { it.lowercase() }
                            includes.all { inc -> novelTags.any { it.contains(inc) } }
                        }
                    }

                    if (excludes.isNotEmpty()) {
                        novels = novels.filter { novel ->
                            val novelTags = novel.tags.map { it.lowercase() }
                            excludes.none { ex -> novelTags.any { it.contains(ex) } }
                        }
                    }
                }

                is AuthorSelect -> {
                    val idx = filter.state
                    if (idx != null && idx > 0) {
                        val author = filter.values[idx]
                        novels = novels.filter { it.author.equals(author, ignoreCase = true) }
                    }
                }

                is Filter.Text -> {
                    // Keep text inputs as quick alternatives. We look for the author text field specifically.
                    if (filter.name == "Author (text)" && filter.state.isNotBlank()) {
                        val authorQuery = filter.state.lowercase()
                        novels = novels.filter { it.author.lowercase().contains(authorQuery) || it.title.lowercase().contains(authorQuery) }
                    }

                    if (filter.name == "Minimum Volumes" && filter.state.isNotBlank()) {
                        val minVols = filter.state.toIntOrNull() ?: 0
                        novels = novels.filter { it.volumes >= minVols }
                    }
                }

                is SortFilter -> {
                    novels = when (filter.state) {
                        0 -> novels.sortedByDescending { it.rel }
                        1 -> novels.sortedByDescending { it.date }
                        2 -> novels.sortedBy { it.date }
                        3 -> novels.sortedBy { it.title }
                        4 -> novels.sortedByDescending { it.title }
                        5 -> novels.sortedByDescending { it.volumes }
                        else -> novels
                    }
                }

                else -> {}
            }
        }

        MangasPage(novels.map { novelDataToSManga(it) }, false)
    }

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            // Title: try multiple selectors
            title = document.selectFirst(
                "h1.s-title, h1.series-title, h1[class*=title], h1[class*=series], h1",
            )?.text()?.trim() ?: ""

            author = document.selectFirst("p.author, .author, [itemprop='author'], [class*=author]")
                ?.text()?.trim()
                ?.removePrefix("Author:")?.trim()

            val imgEl = document.selectFirst(
                "figure.cover-wrap img, figure img, picture img, " +
                    ".cover img, img[class*=cover], img[alt*=cover i], " +
                    ".series-cover img, header img",
            )
            thumbnail_url = imgEl?.let { img ->
                img.attr("srcset").split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotEmpty() }
                    ?: img.attr("data-src").ifEmpty { null }
                    ?: img.attr("src").ifEmpty { null }
            }?.let { resolveImageUrl(it) }

            description = document.selectFirst(
                "p.description, .series-description, .synopsis, [itemprop='description'], " +
                    ".desc, .summary, [class*=description], [class*=synopsis]",
            )?.text()?.trim() ?: run {
                // Fallback: find paragraph with >80 chars that's likely the description
                document.select("p").firstOrNull { it.text().length > 80 }?.text()?.trim()
            }

            // Genres/Tags
            genre = document.select(
                "nav.tags-box a.tag, .tags a, .genre-tag, a[class*=tag], " +
                    "[class*=genre] a, [class*=tag] a",
            )
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")
                .ifEmpty { null }

            // Status
            val statusText = document.selectFirst(".status, [data-status], [class*=status]")
                ?.text()?.lowercase()
            status = when {
                statusText == null -> SManga.UNKNOWN
                statusText.contains("complet") -> SManga.COMPLETED
                statusText.contains("ongoing") || statusText.contains("publishing") -> SManga.ONGOING
                statusText.contains("hiatus") -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    /** Parse the Lnori /library page which contains all series as <article class="card"> */
    private fun parseFromLibrary(document: Document): List<NovelData> {
        val cards = document.select("article.card")
        return cards.mapNotNull { card ->
            val id = card.attr("data-id").ifEmpty { return@mapNotNull null }

            val title = card.attr("data-t").ifEmpty {
                card.selectFirst(".card-title span, .card-title, .card-title a")?.text()?.trim()
                    ?: return@mapNotNull null
            }

            val author = card.attr("data-a").ifEmpty {
                card.selectFirst(".popup-author, .author")?.text()?.trim()?.split("(")?.firstOrNull() ?: ""
            }

            val year = card.attr("data-d").ifEmpty { card.selectFirst(".card-meta .year-badge")?.text()?.trim() ?: "" }

            val volumes = card.attr("data-v").toIntOrNull()
                ?: card.selectFirst(".card-meta span")?.text()?.filter { it.isDigit() }?.toIntOrNull()
                ?: 1

            val tags = card.attr("data-tags").ifEmpty {
                card.select(".popup-tag").map { it.text().trim() }.joinToString(",")
            }
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val rel = card.attr("data-rel").toIntOrNull() ?: 0

            val href = card.selectFirst(".stretched-link, .card-cover a, .card-title a")?.attr("href")
                ?: "/series/$id/"

            val imgEl = card.selectFirst(".card-cover img, img")
            val cover = imgEl?.let { img ->
                img.attr("data-src").ifEmpty {
                    img.attr("srcset").split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
                        ?: img.attr("src")
                }
            } ?: ""

            val desc = card.selectFirst(".popup-description")?.text()?.trim() ?: ""

            NovelData(
                id = id,
                title = title,
                author = author,
                tags = tags,
                rel = rel,
                date = year,
                volumes = volumes,
                url = if (href.startsWith("http")) {
                    href.removePrefix(baseUrl)
                } else if (href.startsWith("/")) {
                    href
                } else {
                    "/$href"
                },
                coverUrl = resolveImageUrl(cover),
                description = desc,
            )
        }
    }

    private fun updateFilterCache(novels: List<NovelData>) {
        val allTags = novels.flatMap { it.tags }.map { it.trim() }.filter { it.isNotEmpty() }.map { it.lowercase() }.distinct().sorted()
        val allAuthors = novels.map { it.author.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

        val cachedTags = loadCachedTags()
        val cachedAuthors = loadCachedAuthors()

        if (allTags != cachedTags || allAuthors != cachedAuthors) {
            try {
                preferences.edit()
                    .putString(FILTERS_TAGS_KEY, json.encodeToString(allTags))
                    .putString(FILTERS_AUTHORS_KEY, json.encodeToString(allAuthors))
                    .putLong(FILTERS_CACHE_TIME_KEY, System.currentTimeMillis())
                    .apply()
            } catch (_: Exception) {}
        }
    }

    private fun loadCachedNovels(): List<NovelData>? {
        val raw = preferences.getString(LIBRARY_CACHE_KEY, null) ?: return null
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadCachedNovelsTimestamp(): Long = preferences.getLong(LIBRARY_CACHE_TIME_KEY, 0L)

    private fun saveCachedNovels(novels: List<NovelData>, timestamp: Long) {
        try {
            preferences.edit()
                .putString(LIBRARY_CACHE_KEY, json.encodeToString(novels))
                .putLong(LIBRARY_CACHE_TIME_KEY, timestamp)
                .apply()
        } catch (_: Exception) {}
    }

    private fun clearLibraryCache() {
        try {
            preferences.edit()
                .remove(LIBRARY_CACHE_KEY)
                .remove(LIBRARY_CACHE_TIME_KEY)
                .remove(FILTERS_TAGS_KEY)
                .remove(FILTERS_AUTHORS_KEY)
                .remove(FILTERS_CACHE_TIME_KEY)
                .apply()
        } catch (_: Exception) {}
        cachedNovels = null
        cacheTimestamp = 0
    }

    private fun loadCachedTags(): List<String> {
        val raw = preferences.getString(FILTERS_TAGS_KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadCachedAuthors(): List<String> {
        val raw = preferences.getString(FILTERS_AUTHORS_KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ======================== Chapters (Volumes) ========================

    override fun chapterListRequest(manga: SManga): Request {
        val url = if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val html = response.body.string()
        val document = Jsoup.parse(html)
        val chapters = mutableListOf<SChapter>()

        // URL format: /series/{novelId}/{slug}  e.g. /series/3336/mushoku-tensei-...
        val pathSegments = response.request.url.pathSegments.filter { it.isNotEmpty() }
        val novelId = pathSegments.getOrNull(1) ?: pathSegments.firstOrNull() ?: ""
        val novelSlug = pathSegments.lastOrNull() ?: novelId
        val basePath = response.request.url.encodedPath.trimEnd('/')

        // STRATEGY 1: Volume/chapter cards � broad selector
        val cards = document.select(
            "div.vol-grid article, " +
                ".volumes-list article, " +
                ".chapter-list li, " +
                "article[class*=card], " +
                "ul.volumes li, " +
                ".vol-grid > *, " +
                "[class*=vol-grid] article, " +
                "[class*=volume] article, " +
                "[class*=volume] li",
        )

        if (cards.isNotEmpty()) {
            cards.forEachIndexed { index, card ->
                val link = card.selectFirst("figure a, h3.c-title a, h3 a, h2 a, a[href]") ?: return@forEachIndexed
                val href = link.attr("href")
                if (href.isBlank()) return@forEachIndexed

                val titleText = card.selectFirst("h3.c-title a, h3 a, .c-title, h3, h2, .card-title")
                    ?.text()?.trim() ?: link.text().trim()

                val volumeNum = Regex("""(?:vol(?:ume)?\.?\s*|#|v)?\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(titleText)?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
                val subtitle = card.selectFirst("p.card-sub, .subtitle, p.card-description, p")
                    ?.text()?.trim() ?: ""

                val chapterUrl = when {
                    href.startsWith("http") -> href.removePrefix(baseUrl)
                    href.startsWith("/") -> href
                    else -> "/$href".replace("//", "/")
                }

                chapters.add(
                    SChapter.create().apply {
                        url = chapterUrl
                        name = if (subtitle.isNotEmpty() && subtitle != titleText) {
                            "Volume $volumeNum: $subtitle"
                        } else {
                            titleText.ifEmpty { "Volume $volumeNum" }
                        }
                        chapter_number = volumeNum.toFloat()
                    },
                )
            }
        }

        // STRATEGY 2: Try JSON-LD / embedded JSON with volume data
        if (chapters.isEmpty()) {
            val jsonLd = document.selectFirst("script[type='application/ld+json']")
            if (jsonLd != null) {
                try {
                    val obj = json.parseToJsonElement(jsonLd.html().trim()).jsonObject
                    val hasPart = obj["hasPart"]?.jsonArray ?: obj["bookEdition"]?.jsonArray
                    hasPart?.forEachIndexed { index, elem ->
                        try {
                            val part = elem.jsonObject
                            val partName = part["name"]?.jsonPrimitive?.contentOrNull ?: "Volume ${index + 1}"
                            val partUrl = part["url"]?.jsonPrimitive?.contentOrNull
                                ?.removePrefix(baseUrl) ?: return@forEachIndexed
                            chapters.add(
                                SChapter.create().apply {
                                    url = partUrl
                                    name = partName
                                    chapter_number = (index + 1).toFloat()
                                },
                            )
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }

        // STRATEGY 3: Any link inside the current page that goes deeper
        if (chapters.isEmpty()) {
            val seen = mutableSetOf<String>()
            document.select("a[href]").forEach { link ->
                val href = link.attr("href")
                // Match links like /series/3336/mushoku-tensei/1/ or /series/3336/mushoku-tensei/volume-1/
                if (href.startsWith(basePath + "/") || href.startsWith(basePath.trimEnd('/') + "/")) {
                    val childPath = if (href.startsWith("/")) href else "/$href"
                    if (seen.add(childPath)) {
                        val title = link.text().trim().ifEmpty {
                            link.attr("title").trim().ifEmpty {
                                "Volume ${seen.size}"
                            }
                        }
                        val numMatch = Regex("""(\d+)""").findAll(childPath).lastOrNull()
                        val num = numMatch?.groupValues?.get(1)?.toFloatOrNull() ?: seen.size.toFloat()
                        chapters.add(
                            SChapter.create().apply {
                                url = childPath
                                name = title
                                chapter_number = num
                            },
                        )
                    }
                }
            }
        }

        return chapters.distinctBy { it.url }.reversed()
    }

    // ======================== Pages ========================

    override fun pageListRequest(chapter: SChapter): Request {
        val url = if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

    // ======================== Novel Content ========================

    override suspend fun fetchPageText(page: Page): String {
        val request = GET(page.url, headers)
        val response = client.newCall(request).execute()
        val html = response.body.string()
        val document = Jsoup.parse(html)

        // Extract the main content container and return its HTML directly
        val mainContent = document.selectFirst("main#main-content, main, article.content-body, .reader-content, .content")

        return if (mainContent != null) {
            mainContent.html()
        } else {
            // Fallback: extract all chapter sections
            val sections = document.select("section.chapter, .chapter-section, article.chapter")
            sections.html()
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // ======================== Filters ========================

    override fun getFilterList(): FilterList {
        val header = Filter.Header("Search is performed locally from homepage data")
        val separator = Filter.Separator()

        var cachedTags = loadCachedTags()
        var cachedAuthors = loadCachedAuthors()

        if (cachedTags.isEmpty() || cachedAuthors.isEmpty()) {
            try {
                loadAllNovels()
            } catch (_: Exception) {
                // Keep fallback filters below if the priming request fails.
            }
            cachedTags = loadCachedTags()
            cachedAuthors = loadCachedAuthors()
        }

        val tagGroup: Filter<*> = if (cachedTags.isNotEmpty()) TagFilter("Tags", cachedTags) else SimpleText("Tags (comma separated)")

        val authorSelect: Filter<*> = if (cachedAuthors.isNotEmpty()) {
            val values = arrayOf("Any") + cachedAuthors.toTypedArray()
            AuthorSelect("Author", values)
        } else {
            SimpleText("Author")
        }

        // Keep free-text author input as quick alternative
        val authorText = SimpleText("Author (text)")

        val minVolumes = MinVolumesFilter("Minimum Volumes")
        val sort = SortFilter("Sort By", sortOptions)

        return FilterList(
            header,
            separator,
            tagGroup,
            authorSelect,
            authorText,
            minVolumes,
            sort,
        )
    }

    private class TagTriState(name: String, val value: String) : Filter.TriState(name)

    private class TagFilter(name: String, tags: List<String>) : Filter.Group<TagTriState>(name, tags.map { TagTriState(it, it) })

    private class SimpleText(name: String) : Filter.Text(name)

    private class AuthorSelect(name: String, values: Array<String>) : Filter.Select<String>(name, values)

    private class MinVolumesFilter(name: String) : Filter.Text(name)

    private class SortFilter(name: String, values: Array<String>) : Filter.Select<String>(name, values)

    private val sortOptions = arrayOf(
        "Popularity",
        "Newest",
        "Oldest",
        "Title A-Z",
        "Title Z-A",
        "Most Volumes",
    )

    companion object {
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
        private const val LIBRARY_CACHE_KEY = "lnori_library_cache"
        private const val LIBRARY_CACHE_TIME_KEY = "lnori_library_cache_time"
        private const val FILTERS_TAGS_KEY = "lnori_tags_cache"
        private const val FILTERS_AUTHORS_KEY = "lnori_authors_cache"
        private const val FILTERS_CACHE_TIME_KEY = "lnori_filters_cache_time"
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = "lnori_clear_cache"
            title = "Clear cached library data"
            summary = "Toggle to clear cached series, tags, and authors. They will be rebuilt from the next library load."
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, _ ->
                clearLibraryCache()
                true
            }
        }.also(screen::addPreference)
    }
}

package eu.kanade.tachiyomi.extension.all.lnori

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
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
import uy.kohesive.injekt.injectLazy

class Lnori :
    HttpSource(),
    NovelSource {

    override val name = "Lnori"
    override val baseUrl = "https://lnori.com"
    override val lang = "all"
    override val supportsLatest = true
    override val isNovelSource = true

    override val client = network.cloudflareClient

    private val json: Json by injectLazy()

    private var cachedNovels: List<NovelData>? = null
    private var cacheTimestamp: Long = 0
    private val cacheLifetime = 10 * 60 * 1000 // 10 minutes

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
            return cachedNovels!!
        }

        val response = client.newCall(GET(baseUrl, headers)).execute()
        val html = response.body.string()
        val document = Jsoup.parse(html)

        // Strategy 1: __NEXT_DATA__ / embedded JSON script with book array
        val novels = parseFromJsonScript(html, document)
            .ifEmpty { parseFromArticleElements(document) }
            .ifEmpty { parseFromLinksAndTitles(document) }

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
                // derive from href: /series/3336/slug → "3336-slug" or last path segment
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
                // Extract ID from href: /series/3336/slug → "3336-slug"
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
                    if (filter.state.isNotBlank()) {
                        val tags = filter.state.split(",")
                            .map { it.trim().lowercase() }
                            .filter { it.isNotEmpty() }
                        novels = novels.filter { novel ->
                            val novelTags = novel.tags.map { it.lowercase() }
                            tags.all { tag -> novelTags.any { it.contains(tag) } }
                        }
                    }
                }

                is AuthorFilter -> {
                    if (filter.state.isNotBlank()) {
                        val authorQuery = filter.state.lowercase()
                        novels = novels.filter { it.author.lowercase().contains(authorQuery) }
                    }
                }

                is MinVolumesFilter -> {
                    if (filter.state.isNotBlank()) {
                        val minVols = filter.state.toIntOrNull() ?: 0
                        novels = novels.filter { it.volumes >= minVols }
                    }
                }

                is SortFilter -> {
                    novels = when (filter.state) {
                        0 -> novels.sortedByDescending { it.rel }

                        // Popular
                        1 -> novels.sortedByDescending { it.date }

                        // Newest
                        2 -> novels.sortedBy { it.date }

                        // Oldest
                        3 -> novels.sortedBy { it.title }

                        // A-Z
                        4 -> novels.sortedByDescending { it.title }

                        // Z-A
                        5 -> novels.sortedByDescending { it.volumes }

                        // Most volumes
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

            // Author — may be in a <p class="author"> or adjacent to an "Author:" label
            author = document.selectFirst("p.author, .author, [itemprop='author'], [class*=author]")
                ?.text()?.trim()
                ?.removePrefix("Author:")?.trim()

            // Cover image — Astro sites often use <picture> or <img> with srcset
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

            // Description — look for synopsis, description, or longest paragraph
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

        // STRATEGY 1: Volume/chapter cards — broad selector
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

        return chapters.distinctBy { it.url }
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

        val content = StringBuilder()

        // Try structured JSON-LD data first
        val jsonLdScript = document.selectFirst("script#app-data[type='application/ld+json']")
        if (jsonLdScript != null) {
            try {
                val jsonText = jsonLdScript.html()
                val chapterMatches = Regex(""""name"\s*:\s*"([^"]+)"\s*,\s*"url"\s*:\s*"([^"]+)"""")
                    .findAll(jsonText)

                for (match in chapterMatches) {
                    val chapterName = match.groupValues[1]
                    val pageId = match.groupValues[2].removePrefix("#")

                    val section = document.selectFirst("section.chapter#$pageId") ?: continue

                    val images = section.select("picture img")
                    if (images.isNotEmpty() && section.select("p").isEmpty()) {
                        images.forEach { img ->
                            val imgUrl = resolveImageUrl(img.attr("src"))
                            content.append("<img src=\"$imgUrl\" alt=\"$chapterName\">\n")
                        }
                    } else {
                        content.append("<h2>$chapterName</h2>\n")
                        section.select("p").forEach { p ->
                            val text = p.text().trim()
                            if (text.isNotEmpty()) content.append("<p>$text</p>\n")
                        }
                        section.select("picture img").forEach { img ->
                            val imgUrl = resolveImageUrl(img.attr("src"))
                            content.append("<img src=\"$imgUrl\">\n")
                        }
                    }
                    content.append("\n<hr>\n\n")
                }
            } catch (_: Exception) { }
        }

        // Fallback: iterate sections directly
        if (content.isEmpty()) {
            document.select("section.chapter, .chapter-section, article.chapter").forEach { section ->
                val chTitle = section.selectFirst("h2.chapter-title, h2, h3")?.text()?.trim()
                if (!chTitle.isNullOrEmpty()) content.append("<h2>$chTitle</h2>\n")

                section.select("picture img, img.chapter-img").forEach { img ->
                    val imgUrl = resolveImageUrl(img.attr("src").ifEmpty { img.attr("data-src") })
                    if (imgUrl.isNotEmpty()) content.append("<img src=\"$imgUrl\">\n")
                }

                section.select("p").forEach { p ->
                    val text = p.text().trim()
                    if (text.isNotEmpty()) content.append("<p>$text</p>\n")
                }
                content.append("\n")
            }
        }

        // Final fallback: main content area
        if (content.isEmpty()) {
            document.selectFirst("main#main-content, main, .reader-content, .content")
                ?.select("p")
                ?.forEach { p ->
                    val text = p.text().trim()
                    if (text.isNotEmpty()) content.append("<p>$text</p>\n")
                }
        }

        return content.toString()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // ======================== Filters ========================

    override fun getFilterList(): FilterList = FilterList(
        Filter.Header("Search is performed locally from homepage data"),
        Filter.Separator(),
        TagFilter("Tags (comma separated)"),
        AuthorFilter("Author"),
        MinVolumesFilter("Minimum Volumes"),
        SortFilter("Sort By", sortOptions),
    )

    class TagFilter(name: String) : Filter.Text(name)
    class AuthorFilter(name: String) : Filter.Text(name)
    class MinVolumesFilter(name: String) : Filter.Text(name)
    class SortFilter(name: String, values: Array<String>) : Filter.Select<String>(name, values)

    private val sortOptions = arrayOf(
        "Popularity",
        "Newest",
        "Oldest",
        "Title A-Z",
        "Title Z-A",
        "Most Volumes",
    )
}

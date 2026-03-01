package eu.kanade.tachiyomi.extension.en.noveldex

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
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
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * NovelDex.io - Novel reading extension
 * Uses /api/series for listings and RSC (React Server Components) for detail/chapter pages.
 */
class NovelDex :
    HttpSource(),
    NovelSource {

    override val name = "NovelDex"
    override val baseUrl = "https://noveldex.io"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient
    private val json: Json by injectLazy()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    // RSC headers - required for React Server Component response
    // Must include Next-Router-Prefetch, Next-Url and _rsc param to avoid 403
    private fun rscHeaders(path: String = "/"): Headers = headers.newBuilder()
        .add("rsc", "1")
        .add("next-router-prefetch", "1")
        .add("next-url", path)
        .add("Accept", "*/*")
        .build()

    private fun rscUrl(path: String): String = "$baseUrl$path?_rsc=1"

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
        val root = json.parseToJsonElement(body).jsonObject
        val dataArray = root["data"]?.jsonArray ?: return MangasPage(emptyList(), false)
        val meta = root["meta"]?.jsonObject

        val novels = dataArray.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val cover = obj["coverImage"]?.jsonPrimitive?.contentOrNull
                    ?.let { if (it.startsWith("/")) baseUrl + it else it }

                SManga.create().apply {
                    this.title = title
                    this.url = "/series/novel/$slug"
                    this.thumbnail_url = cover
                }
            } catch (e: Exception) {
                null
            }
        }

        val hasMore = meta?.get("hasMore")?.jsonPrimitive?.booleanOrNull ?: false

        return MangasPage(novels, hasMore)
    }

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request = GET(rscUrl(manga.url), rscHeaders(manga.url))

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
                ?.groupValues?.get(1)?.unescape() ?: ""

            val rawDescription = Regex(""""description"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(seriesJson)
                ?.groupValues?.get(1)?.unescape()

            val altTitle = Regex(""""altTitle"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(seriesJson)
                ?.groupValues?.get(1)?.unescape()?.takeIf { it.isNotBlank() }

            // aliases is a JSON array
            val aliasesMatch = Regex(""""aliases"\s*:\s*\[(.*?)\]""").find(seriesJson)
            val aliases = aliasesMatch?.groupValues?.get(1)
                ?.let { Regex(""""((?:[^"\\]|\\.)*)"""").findAll(it).map { m -> m.groupValues[1].unescape() }.toList() }
                ?.filter { it.isNotBlank() }

            val originalTitle = Regex(""""originalTitle"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(seriesJson)
                ?.groupValues?.get(1)?.unescape()?.takeIf { it.isNotBlank() }

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
        title = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body)
            ?.groupValues?.get(1)?.unescape() ?: ""

        val desc = Regex(""""description"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body)
            ?.groupValues?.get(1)?.unescape()
        val altTitle = Regex(""""altTitle"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body)
            ?.groupValues?.get(1)?.unescape()?.takeIf { it.isNotBlank() }

        description = buildString {
            altTitle?.let { append("Alt Title: $it\n\n") }
            desc?.let { append(it) }
        }.takeIf { it.isNotBlank() }

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
        // chapter/1 RSC contains full "allChapters" array with all titles + isLocked.
        // Browser sends next-url = novel page path (not chapter path) as the Referer context.
        val chapterOnePath = manga.url + "/chapter/1"
        val novelPath = manga.url
        return GET(rscUrl(chapterOnePath), rscHeaders(novelPath))
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()
        val requestUrl = response.request.url.encodedPath  // /series/novel/{slug}/chapter/1

        // Extract slug: /series/novel/{slug}/chapter/1 → {slug}
        val slugMatch = Regex("""/series/novel/([^/]+)/chapter/""").find(requestUrl)
            ?: Regex("""/series/novel/([^/?]+)""").find(requestUrl)
        val novelSlug = slugMatch?.groupValues?.get(1) ?: ""

        val chapters = mutableListOf<SChapter>()

        // PRIMARY: "allChapters":[{...},...] — full list with titles + lock status
        val allChaptersMatch = Regex(""""allChapters"\s*:\s*(\[.*?\])(?=\s*,"totalChapters")""", RegexOption.DOT_MATCHES_ALL)
            .find(body)

        if (allChaptersMatch != null) {
            try {
                val arr = json.parseToJsonElement(allChaptersMatch.groupValues[1]).jsonArray
                arr.forEach { elem ->
                    try {
                        val obj = elem.jsonObject
                        val number = obj["number"]?.jsonPrimitive?.intOrNull ?: return@forEach
                        val chTitle = obj["title"]?.jsonPrimitive?.contentOrNull ?: "Chapter $number"
                        val isLocked = obj["isLocked"]?.jsonPrimitive?.booleanOrNull ?: false
                        chapters.add(
                            SChapter.create().apply {
                                url = "/series/novel/$novelSlug/chapter/$number"
                                name = if (isLocked) "🔒 $chTitle" else chTitle
                                chapter_number = number.toFloat()
                            },
                        )
                    } catch (_: Exception) {}
                }
                if (chapters.isNotEmpty()) {
                    return chapters.sortedByDescending { it.chapter_number }
                }
            } catch (_: Exception) {}
        }

        // SECONDARY: chapterCount from series{} — build sequential list
        val chapterCount = Regex(""""chapterCount"\s*:\s*(\d+)""").find(body)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0

        if (chapterCount > 0 && novelSlug.isNotEmpty()) {
            for (n in 1..chapterCount) {
                chapters.add(
                    SChapter.create().apply {
                        url = "/series/novel/$novelSlug/chapter/$n"
                        name = "Chapter $n"
                        chapter_number = n.toFloat()
                    },
                )
            }
            return chapters.sortedByDescending { it.chapter_number }
        }

        // TERTIARY: "chapters":[...] (up to 100, with dates)
        val chaptersArrayMatch = Regex(""""chapters"\s*:\s*(\[.*?\])(?=\s*,"characters")""", RegexOption.DOT_MATCHES_ALL)
            .find(body)
        if (chaptersArrayMatch != null) {
            try {
                val arr = json.parseToJsonElement(chaptersArrayMatch.groupValues[1]).jsonArray
                arr.forEach { elem ->
                    try {
                        val obj = elem.jsonObject
                        val number = obj["number"]?.jsonPrimitive?.intOrNull ?: return@forEach
                        val chTitle = obj["title"]?.jsonPrimitive?.contentOrNull ?: "Chapter $number"
                        val publishedAt = obj["publishedAt"]?.jsonPrimitive?.contentOrNull
                        val isLocked = obj["isLocked"]?.jsonPrimitive?.booleanOrNull ?: false
                        chapters.add(
                            SChapter.create().apply {
                                url = "/series/novel/$novelSlug/chapter/$number"
                                name = if (isLocked) "🔒 $chTitle" else chTitle
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
            } catch (_: Exception) {}
        }

        return chapters.distinctBy { it.chapter_number }.sortedByDescending { it.chapter_number }
    }

    // ======================== Pages ========================

    override fun pageListRequest(chapter: SChapter): Request {
        // chapter.url = /series/novel/{slug}/chapter/{number}
        // Browser sends next-url = novel page (without /chapter/N) as Referer context
        val chapterPath = if (chapter.url.startsWith("http")) chapter.url.removePrefix(baseUrl) else chapter.url
        val novelPath = chapterPath.substringBefore("/chapter/")
        return GET(rscUrl(chapterPath), rscHeaders(novelPath))
    }

    override fun pageListParse(response: Response): List<Page> {
        // Store chapter path (without query) for fetchPageText
        val path = response.request.url.encodedPath
        return listOf(Page(0, path))
    }

    override fun imageUrlParse(response: Response): String = ""

    // ======================== Novel Content ========================

    override suspend fun fetchPageText(page: Page): String {
        // page.url = /series/novel/{slug}/chapter/{number}
        val chapterPath = page.url
        val novelPath = chapterPath.substringBefore("/chapter/")
        val rscRequest = GET(rscUrl(chapterPath), rscHeaders(novelPath))
        val response = client.newCall(rscRequest).execute()
        val body = response.body.string()

        // RSC T-tag wire format (confirmed from network inspection):
        //   KEY:THEX,﻿[WATERMARK_ZW]<p><b>Translator:...</b></p>
        //     <h1>Chapter N: Title</h1>
        //     <p>paragraph...</p>
        //     ...
        //     <p>last paragraph</p>﻿[WATERMARK_ZW]2:[next RSC...]
        //
        // The content blob is between two \uFEFF-led watermark sequences.
        // Find the T-tag, skip past the first watermark, extract until the second watermark.

        val tTagRegex = Regex("""[0-9a-f]+:T[0-9a-fA-F]+,""")
        val tTagMatch = tTagRegex.find(body)

        if (tTagMatch != null) {
            val afterTTag = tTagMatch.range.last + 1
            // Skip first watermark: starts with \uFEFF, ends with \u200D\uFEFF
            // Content begins after the first \u200D\uFEFF pair
            val watermarkEnd = body.indexOf('\uFEFF', afterTTag + 1) // second BOM after the T-tag BOM
            val contentStart = if (watermarkEnd != -1) watermarkEnd + 1 else afterTTag

            // Find opening HTML tag — should be <p> or <h1>
            val firstTag = body.indexOf('<', contentStart)
            if (firstTag != -1) {
                // Content ends at the NEXT \uFEFF (start of the trailing watermark)
                val contentEnd = body.indexOf('\uFEFF', firstTag + 10)
                val rawContent = if (contentEnd != -1) {
                    body.substring(firstTag, contentEnd)
                } else {
                    // Trim to last </p> as safety
                    val lastP = body.indexOf("</p>", firstTag)
                    if (lastP != -1) body.substring(firstTag, lastP + 4) else body.substring(firstTag)
                }

                // Trim trailing garbage (non-HTML after last block tag)
                val lastClose = maxOf(
                    rawContent.lastIndexOf("</p>"),
                    rawContent.lastIndexOf("</div>"),
                    rawContent.lastIndexOf("</h1>"),
                    rawContent.lastIndexOf("</h2>"),
                )
                val content = if (lastClose != -1) {
                    rawContent.substring(0, lastClose + rawContent.substring(lastClose).indexOf('>') + 1)
                } else {
                    rawContent
                }.trim()

                if (content.length > 30) return content
            }
        }

        // Fallback 1: find any run of <p> tags in the body
        val firstP = body.indexOf("<p>").takeIf { it != -1 }
            ?: body.indexOf("<p ").takeIf { it != -1 }
        if (firstP != null) {
            val endBom = body.indexOf('\uFEFF', firstP + 10)
            val raw = if (endBom != -1) body.substring(firstP, endBom) else body.substring(firstP)
            val lastP = raw.lastIndexOf("</p>")
            val content = if (lastP != -1) raw.substring(0, lastP + 4) else raw
            if (content.length > 30) return content.trim()
        }

        // Fallback 2: parse with Jsoup
        val doc = Jsoup.parse(body)
        return doc.selectFirst("div.chapter-text, div.prose, article, main")?.html()
            ?: doc.select("p").filter { it.text().length > 20 }.joinToString("\n") { "<p>${it.text()}</p>" }
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

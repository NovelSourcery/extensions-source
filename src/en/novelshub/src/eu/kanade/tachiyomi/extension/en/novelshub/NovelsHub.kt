package eu.kanade.tachiyomi.extension.en.novelshub

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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

/**
 * NovelsHub.org - Novel reading extension
 * Uses RSC (React Server Components) for data fetching
 * @see instructions.html for RSC parsing details
 */
class NovelsHub :
    HttpSource(),
    NovelSource {

    override val name = "NovelsHub"
    override val baseUrl = "https://novelshub.org"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient
    private val json: Json by injectLazy()

    // RSC headers - required for x-component response
    private fun rscHeaders(): Headers = headers.newBuilder()
        .add("rsc", "1")
        .add("Accept", "*/*")
        .build()
    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/?page=$page", rscHeaders())

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body.string()
        val novels = mutableListOf<SManga>()
        val doc = Jsoup.parse(body)

        // Per instructions.html: Popular novels from wrapper elements with cover image
        // Selector: div.wrapper img.cover-image + h1 for title
        doc.select("div.wrapper").forEach { wrapper ->
            try {
                val img = wrapper.selectFirst("img.cover-image, img[alt*=Cover]")
                val cover = img?.attr("src")?.ifEmpty { null } ?: img?.attr("data-nimg")
                val titleElement = wrapper.selectFirst("h1")
                val title = titleElement?.text()?.trim() ?: return@forEach

                val link = wrapper.parent()?.closest("a[href*=/series/]")
                    ?: wrapper.selectFirst("a[href*=/series/]")
                val url = link?.attr("href")?.replace(baseUrl, "") ?: return@forEach

                novels.add(
                    SManga.create().apply {
                        this.title = title
                        this.url = url
                        thumbnail_url = cover
                    },
                )
            } catch (e: Exception) {
            }
        }

        // Per instructions.html: Latest novels from figure elements
        // figure.relative > a[href*=/series/] with img and title link
        doc.select("figure.relative, figure").forEach { figure ->
            try {
                val link = figure.selectFirst("a[href*=/series/]") ?: return@forEach
                val url = link.attr("href").replace(baseUrl, "")
                if (url.isEmpty() || novels.any { it.url == url }) return@forEach

                val img = figure.selectFirst("img")
                val cover = img?.attr("src")?.ifEmpty { null } ?: img?.attr("data-nimg")

                val titleLink = figure.selectFirst("a.text-sm, a.font-bold, a[title]")
                    ?: figure.select("a[href*=/series/]").lastOrNull()
                val title = titleLink?.attr("title")?.ifEmpty { null }
                    ?: titleLink?.text()?.trim()
                    ?: link.attr("title")
                    ?: return@forEach

                novels.add(
                    SManga.create().apply {
                        this.title = title
                        this.url = url
                        thumbnail_url = cover
                    },
                )
            } catch (e: Exception) {
            }
        }

        doc.select(".card-group, div[class*=card]").forEach { card ->
            try {
                val link = card.selectFirst("a[href*=/series/]") ?: return@forEach
                val url = link.attr("href").replace(baseUrl, "")
                if (url.isEmpty() || novels.any { it.url == url }) return@forEach

                val img = card.selectFirst("img")
                val cover = img?.attr("src")?.ifEmpty { null }

                val title = link.attr("title")?.ifEmpty { null }
                    ?: card.selectFirst("a.font-bold, .line-clamp-2")?.text()?.trim()
                    ?: return@forEach

                novels.add(
                    SManga.create().apply {
                        this.title = title
                        this.url = url
                        thumbnail_url = cover
                    },
                )
            } catch (e: Exception) {
            }
        }

        if (novels.isEmpty()) {
            // Pattern to extract novel data: "slug":"xxx","postTitle":"xxx"
            val slugTitlePattern = Regex(""""slug"\s*:\s*"([^"]+)"\s*,\s*"postTitle"\s*:\s*"([^"]+)"""")
            val imagePattern = Regex(""""featuredImage"\s*:\s*"([^"]+)"""")

            slugTitlePattern.findAll(body).forEach { match ->
                try {
                    val slug = match.groupValues[1]
                    val title = match.groupValues[2]

                    if (slug.startsWith("chapter-")) return@forEach

                    val startIdx = maxOf(0, match.range.first - 200)
                    val endIdx = minOf(body.length, match.range.last + 500)
                    val nearbyText = body.substring(startIdx, endIdx)
                    val cover = imagePattern.find(nearbyText)?.groupValues?.get(1)

                    novels.add(
                        SManga.create().apply {
                            this.title = title
                            url = "/series/$slug"
                            thumbnail_url = cover
                        },
                    )
                } catch (e: Exception) {
                }
            }
        }

        return MangasPage(novels.distinctBy { it.url }, novels.size >= 10)
    }
    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)
    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // https://api.novelshub.org/api/query?page=1&perPage=24&searchTerm=world&genreIds=2,21&seriesType=MANHWA&seriesStatus=ONGOING
        val params = mutableListOf<String>()
        params.add("page=$page")
        params.add("perPage=24")

        if (query.isNotBlank()) {
            params.add("searchTerm=${URLEncoder.encode(query, "UTF-8")}")
        }

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    val sort = filter.toValue()
                    if (sort != null) {
                        params.add("orderBy=${sort.first}")
                        params.add("orderDirection=${sort.second}")
                    }
                }

                is GenreFilter -> {
                    val genres = filter.state.filter { it.state != Filter.TriState.STATE_IGNORE }
                        .filterIsInstance<GenreCheckBox>()
                        .map { it.id }
                        .joinToString(",")
                    if (genres.isNotEmpty()) {
                        params.add("genreIds=$genres")
                    }
                }

                is StatusFilter -> {
                    val status = filter.toValue()
                    if (!status.isNullOrEmpty()) {
                        params.add("seriesStatus=$status")
                    }
                }

                is TypeFilter -> {
                    val type = filter.toValue()
                    if (!type.isNullOrEmpty()) {
                        params.add("seriesType=$type")
                    }
                }

                else -> {}
            }
        }

        val url = "https://api.novelshub.org/api/query?${params.joinToString("&")}"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val body = response.body.string()

        if (response.request.url.host == "api.novelshub.org") {
            return parseApiResponse(body)
        }

        // Otherwise use the standard HTML parsing
        return popularMangaParse(
            Response.Builder()
                .body(okhttp3.ResponseBody.create(null, body))
                .request(response.request)
                .protocol(response.protocol)
                .code(response.code)
                .message(response.message)
                .build(),
        )
    }

    private fun parseApiResponse(body: String): MangasPage {
        val novels = mutableListOf<SManga>()

        try {
            val jsonElement = json.parseToJsonElement(body)
            val rootObj = jsonElement.jsonObject

            val items = rootObj["posts"]?.jsonArray
                ?: rootObj["data"]?.jsonObject?.get("series")?.jsonArray

            items?.forEach { item ->
                try {
                    val obj = item.jsonObject
                    val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val title = obj["postTitle"]?.jsonPrimitive?.contentOrNull
                        ?: obj["title"]?.jsonPrimitive?.contentOrNull
                        ?: return@forEach
                    val cover = obj["featuredImage"]?.jsonPrimitive?.contentOrNull
                        ?: obj["featuredImageCL"]?.jsonPrimitive?.contentOrNull

                    novels.add(
                        SManga.create().apply {
                            this.title = title
                            url = "/series/$slug"
                            thumbnail_url = cover
                        },
                    )
                } catch (e: Exception) {
                }
            }

            // Use totalCount from root for pagination (API returns { posts: [...], totalCount: N })
            val totalCount = rootObj["totalCount"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val postsCount = items?.size ?: 0

            val hasNext = if (totalCount != null) {
                // Extract current page from the original request URL
                val perPage = 24
                postsCount >= perPage && novels.size < totalCount
            } else {
                // Fallback: check nested pagination object
                val pagination = rootObj["pagination"]?.jsonObject
                    ?: rootObj["data"]?.jsonObject?.get("pagination")?.jsonObject
                val currentPage = pagination?.get("currentPage")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
                val totalPages = pagination?.get("totalPages")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
                currentPage < totalPages
            }

            return MangasPage(novels, hasNext)
        } catch (e: Exception) {
            val slugTitlePattern = Regex(""""slug"\s*:\s*"([^"]+)"\s*,\s*"postTitle"\s*:\s*"([^"]+)"""")
            val imagePattern = Regex(""""featuredImage"\s*:\s*"([^"]+)"""")

            slugTitlePattern.findAll(body).forEach { match ->
                try {
                    val slug = match.groupValues[1]
                    val title = match.groupValues[2]
                    if (slug.startsWith("chapter-")) return@forEach

                    val startIdx = maxOf(0, match.range.first - 200)
                    val endIdx = minOf(body.length, match.range.last + 500)
                    val nearbyText = body.substring(startIdx, endIdx)
                    val cover = imagePattern.find(nearbyText)?.groupValues?.get(1)

                    novels.add(
                        SManga.create().apply {
                            this.title = title
                            url = "/series/$slug"
                            thumbnail_url = cover
                        },
                    )
                } catch (e: Exception) {
                }
            }
        }

        return MangasPage(novels.distinctBy { it.url }, novels.size >= 10)
    }
    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, rscHeaders())

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body.string()

        return SManga.create().apply {
            title = Regex(""""postTitle"\s*:\s*"([^"]+)"""").find(body)
                ?.groupValues?.get(1) ?: ""

            val postContent = Regex(""""postContent"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body)
                ?.groupValues?.get(1)
                ?.replace("\\\"", "\"")
                ?.replace("\\n", "\n")
                ?.replace("\\/", "/")
            val descText = postContent?.let { Jsoup.parse(it).text() }

            val altTitles = Regex(""""alternativeTitles"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body)
                ?.groupValues?.get(1)
                ?.replace("\\\"", "\"")
                ?.replace("\\n", "\n")
                ?.takeIf { it.isNotBlank() }

            description = buildString {
                altTitles?.let { append("Alternative Titles: $it\n\n") }
                descText?.let { append(it) }
            }.takeIf { it.isNotBlank() }

            thumbnail_url = Regex(""""featuredImage"\s*:\s*"([^"]+)"""").find(body)
                ?.groupValues?.get(1)
                ?: Regex(""""ImageObject"[^}]*"url"\s*:\s*"([^"]+)"""").find(body)
                    ?.groupValues?.get(1)

            author = Regex(""""author"\s*:\s*"([^"]+)"""").find(body)
                ?.groupValues?.get(1)

            val genresMatch = Regex(""""genres"\s*:\s*\[(.*?)\]""").find(body)
            genre = genresMatch?.groupValues?.get(1)?.let { genresStr ->
                Regex(""""name"\s*:\s*"([^"]+)"""").findAll(genresStr)
                    .map { it.groupValues[1].trim() }
                    .joinToString(", ")
            }

            val statusStr = Regex(""""seriesStatus"\s*:\s*"([^"]+)"""").find(body)
                ?.groupValues?.get(1)
            status = when (statusStr?.uppercase()) {
                "ONGOING" -> SManga.ONGOING
                "COMPLETED" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }
    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, rscHeaders())

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()
        val novelPath = response.request.url.encodedPath

        val chapters = mutableListOf<SChapter>()

        // Per instructions.html: Look for "_count":{"chapters":N} for total chapter count
        val totalChapters = Regex(""""_count"\s*:\s*\{[^}]*"chapters"\s*:\s*(\d+)""").find(body)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""Chapters\s*\(\s*(\d+)\s*\)""").find(body)
                ?.groupValues?.get(1)?.toIntOrNull()
            ?: 0

        val novelSlug = Regex(""""slug"\s*:\s*"([^"]+)"""").find(body)
            ?.groupValues?.get(1)
            ?: novelPath.split("/").lastOrNull { it.isNotEmpty() }
            ?: return emptyList()

        // RSC format has: {"id":152012,"slug":"chapter-166","number":166,"title":"",..."mangaPost":{...},...}
        Regex(""""id":\d+,"slug":"(chapter-\d+)","number":(\d+)""")
            .findAll(body)
            .forEach { match ->
                val slug = match.groupValues[1]
                val number = match.groupValues[2].toIntOrNull() ?: return@forEach
                chapters.add(
                    SChapter.create().apply {
                        url = "/series/$novelSlug/$slug"
                        name = "Chapter $number"
                        chapter_number = number.toFloat()
                    },
                )
            }

        if (chapters.isEmpty() && totalChapters > 0) {
            for (chapterNum in 1..totalChapters) {
                chapters.add(
                    SChapter.create().apply {
                        url = "/series/$novelSlug/chapter-$chapterNum"
                        name = "Chapter $chapterNum"
                        chapter_number = chapterNum.toFloat()
                    },
                )
            }
        }

        return chapters.reversed()
    }
    // ======================== Pages ========================

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString().removePrefix(baseUrl)
        return listOf(Page(0, chapterUrl))
    }

    override fun imageUrlParse(response: Response): String = ""
    // ======================== Novel Content ========================

    override suspend fun fetchPageText(page: Page): String {
        // Per instructions.html: Use RSC request with rsc:1 header
        val rscRequest = GET(baseUrl + page.url, rscHeaders())
        val response = client.newCall(rscRequest).execute()
        val body = response.body.string()

        // RSC T-tag format: NUMBER:THEX,<p>content</p>
        // Example: 21:T4844,<p>-----------------------------------------------------------------</p><p>Translator...
        val tTagPattern = Regex("""\d+:T[0-9a-f]+,(<p>.*)""", RegexOption.DOT_MATCHES_ALL)
        val tTagMatch = tTagPattern.find(body)

        if (tTagMatch != null) {
            var content = tTagMatch.groupValues[1]

            val lastP = content.lastIndexOf("</p>")
            if (lastP != -1) {
                content = content.substring(0, lastP + 4)
            }

            content = content.replace(Regex("""<p>-+</p>"""), "")
            content = content.replace(Regex("""<p>Translator:.*?</p>""", RegexOption.IGNORE_CASE), "")
            content = content.replace(Regex("""<p>Chapter:.*?</p>""", RegexOption.IGNORE_CASE), "")
            content = content.replace(Regex("""<p>Chapter Title:.*?</p>""", RegexOption.IGNORE_CASE), "")

            if (content.contains(":[") || content.contains("\":")) {
                val jsonStart = content.indexOf("\":[")
                if (jsonStart != -1) {
                    content = content.substring(0, jsonStart)
                    val lastValidP = content.lastIndexOf("</p>")
                    if (lastValidP != -1) {
                        content = content.substring(0, lastValidP + 4)
                    }
                }
            }

            return content.trim()
        }

        // Alternative pattern: look for "content" field reference
        val contentPattern = Regex(""""content"\s*:\s*"\$(\d+)"""")
        val contentMatch = contentPattern.find(body)
        if (contentMatch != null) {
            val contentRef = contentMatch.groupValues[1]
            val refPattern = Regex("""$contentRef:T[0-9a-f]+,(.+?)(?=\d+:\[|\d+:|$)""", RegexOption.DOT_MATCHES_ALL)
            val refMatch = refPattern.find(body)
            if (refMatch != null) {
                return refMatch.groupValues[1].trim()
            }
        }

        val paragraphs = Regex("""<p>([^<]*(?:(?!</p>)<[^<]*)*)</p>""").findAll(body)
            .map { it.value }
            .filter { p ->
                !p.contains("---") &&
                    !p.contains("Translator:") &&
                    !p.contains("Chapter Title:") &&
                    !p.startsWith("<p>Chapter:") &&
                    p.length > 20
            }
            .toList()

        if (paragraphs.isNotEmpty()) {
            return paragraphs.joinToString("\n")
        }

        // Last resort: Try HTML parsing
        val doc = Jsoup.parse(body)
        return doc.selectFirst("div.prose, article, .chapter-content")?.html() ?: ""
    }

    override fun getFilterList(): FilterList = FilterList(
        SortFilter("Sort", sortOptions),
        StatusFilter("Status", statusOptions),
        TypeFilter("Type", typeOptions),
        GenreFilter("Genres", genreList),
    )

    class SortFilter(name: String, private val options: List<Triple<String, String, String>>) : Filter.Select<String>(name, options.map { it.third }.toTypedArray()) {
        fun toValue(): Pair<String, String>? = if (state == 0) {
            null
        } else {
            options.getOrNull(state)?.let { it.first to it.second }
        }
    }

    class StatusFilter(name: String, private val options: List<Pair<String, String>>) : Filter.Select<String>(name, arrayOf("All") + options.map { it.second }.toTypedArray()) {
        fun toValue(): String? = if (state == 0) null else options.getOrNull(state - 1)?.first
    }

    class TypeFilter(name: String, private val options: List<Pair<String, String>>) : Filter.Select<String>(name, arrayOf("All") + options.map { it.second }.toTypedArray()) {
        fun toValue(): String? = if (state == 0) null else options.getOrNull(state - 1)?.first
    }

    class GenreFilter(name: String, genres: List<Pair<String, String>>) : Filter.Group<Filter.TriState>(name, genres.map { GenreCheckBox(it.second, it.first) })

    class GenreCheckBox(name: String, val id: String) : Filter.TriState(name)

    // Triple: (orderBy field, orderDirection, display name)
    private val sortOptions = listOf(
        Triple("", "", "Default"),
        Triple("lastChapterAddedAt", "desc", "Latest Update"),
        Triple("createdAt", "desc", "Recently Added"),
        Triple("createdAt", "asc", "Oldest"),
        Triple("postTitle", "asc", "A-Z"),
        Triple("postTitle", "desc", "Z-A"),
        Triple("views", "desc", "Most Views"),
    )

    private val statusOptions = listOf(
        Pair("ONGOING", "Ongoing"),
        Pair("COMPLETED", "Completed"),
        Pair("DROPPED", "Dropped"),
        Pair("CANCELLED", "Cancelled"),
        Pair("HIATUS", "Hiatus"),
        Pair("MASS RELEASED", "Mass Released"),
        Pair("COMING SOON", "Coming Soon"),
    )

    private val typeOptions = listOf(
        Pair("NOVEL", "Novel"),
        Pair("MANHWA", "Manhwa"),
        Pair("MANGA", "Manga"),
        Pair("MANHUA", "Manhua"),
    )

    private val genreList = listOf(
        Pair("1", "Reincarnation"),
        Pair("2", "System"),
        Pair("3", "Mystery"),
        Pair("4", "Action"),
        Pair("5", "Detective Conan"),
        Pair("6", "Isekai"),
        Pair("7", "Weak to Strong"),
        Pair("8", "Anime"),
        Pair("9", "Romance"),
        Pair("10", "School Life"),
        Pair("11", "Wuxia"),
        Pair("12", "Fantasy"),
        Pair("13", "Drama"),
        Pair("14", "Comedy"),
        Pair("15", "Martial Arts"),
        Pair("16", "Supernatural"),
        Pair("17", "Cunning Protagonist"),
        Pair("18", "Light Novel"),
        Pair("19", "Military"),
        Pair("20", "Harem"),
        Pair("21", "Modern Day"),
        Pair("22", "Transmigration"),
        Pair("23", "Urban Fantasy"),
        Pair("24", "Adopted Sister"),
        Pair("25", "Male Protagonist"),
        Pair("26", "Faction Building"),
        Pair("27", "Superpowers"),
        Pair("28", "Science"),
        Pair("29", " Fiction"),
        Pair("30", "Space-Time Travel"),
        Pair("31", " Dimensional Travel"),
        Pair("32", "MAGIC"),
        Pair("33", "WIZARDS"),
        Pair("34", "Adult"),
        Pair("35", "Life "),
        Pair("36", "Adventure "),
        Pair("37", " Shounen"),
        Pair("38", "Psychological"),
        Pair("39", "Academy "),
        Pair("40", "Character Growth "),
        Pair("41", "Game "),
        Pair("42", "Elements "),
        Pair("43", "Transported into a Game World"),
        Pair("44", "Gender Bender "),
        Pair("45", "Slice of Life"),
        Pair("46", "Sports"),
        Pair("47", "Revenge"),
        Pair("48", "Hard Work"),
        Pair("49", "Survival"),
        Pair("50", "Historical"),
        Pair("51", "Healing Romance"),
        Pair("52", "shoujo"),
        Pair("53", "Possession"),
        Pair("54", "Regression"),
        Pair("55", "Seinen"),
        Pair("56", "Sci-Fi"),
        Pair("57", "Tragedy"),
        Pair("58", "Shounen"),
        Pair("59", "Mature"),
        Pair("60", "cultivation-elements"),
        Pair("61", "secret-organization"),
        Pair("62", "Horror"),
        Pair("63", "weak-to-strong"),
        Pair("64", "Crime"),
        Pair("65", "Police"),
        Pair("66", "Urban Life"),
        Pair("67", "Workplace"),
        Pair("68", "Finance"),
        Pair("69", "Business Management"),
        Pair("70", "wall-street"),
        Pair("71", "beautiful-female-leads"),
        Pair("72", "wealth-building"),
        Pair("73", "stock-market"),
        Pair("74", "Second Chance"),
        Pair("75", "silicon-valley"),
        Pair("76", "financial-warfare"),
        Pair("77", "Dystopia"),
        Pair("78", "Another World"),
        Pair("79", "Thriller"),
        Pair("80", "Genius Protagonist"),
        Pair("81", "Business / Management"),
        Pair("82", "gallery"),
        Pair("83", "Investor"),
        Pair("84", "Obsession"),
        Pair("85", "Misunderstandings"),
        Pair("86", "Ecchi"),
        Pair("87", "Yuri"),
        Pair("88", "Shoujo AI"),
        Pair("89", "summoned to a tower, gallery system"),
        Pair("90", "game element"),
        Pair("91", "Xianxia"),
        Pair("92", "Serial Killers"),
        Pair("93", "Murders"),
        Pair("94", "Unconditional Love"),
        Pair("95", "Demons "),
        Pair("96", "Regret"),
        Pair("97", "Josei"),
        Pair("98", "murim"),
        Pair("99", "Dark Fantasy"),
        Pair("100", "Game World"),
        Pair("101", "religious"),
        Pair("102", "TerritoryManagement"),
        Pair("103", "Genius"),
        Pair("104", "Scoundrel"),
        Pair("105", "Nobility"),
        Pair("106", "Tower Climbing"),
        Pair("107", "Professional"),
        Pair("108", "Overpowered"),
        Pair("109", "Singer"),
        Pair("110", "Veteran"),
        Pair("111", "Effort"),
        Pair("112", "Manager"),
        Pair("113", "Supernatural Ability"),
        Pair("114", "Devour or Absorption"),
        Pair("115", "Artifact"),
        Pair("116", "Mortal Path"),
        Pair("117", "Decisive and Ruthless"),
        Pair("118", "Idol"),
        Pair("119", "Heroes"),
        Pair("120", "Cultivation"),
        Pair("121", "Love Triangle"),
        Pair("122", "First Love"),
        Pair("123", "Reverse Harem"),
        Pair("124", "One-Sided Love"),
        Pair("125", "Smut"),
        Pair("126", "War"),
        Pair("127", "Apocalypse"),
        Pair("128", "Chaos"),
        Pair("129", "Magic and sword"),
        Pair("130", "Mecha "),
        Pair("131", "Actor"),
        Pair("132", "MMORPG"),
        Pair("133", "Virtual Reality"),
        Pair("134", "Xuanhuan "),
        Pair("135", "Yaoi"),
        Pair("136", "matur"),
        Pair("137", "ghoststory"),
        Pair("138", "GL"),
        Pair("139", "Necrosmith"),
        Pair("140", "Necromancer"),
        Pair("141", "Blacksmith"),
        Pair("142", "artist"),
        Pair("143", "Childcare"),
        Pair("144", "Streaming"),
        Pair("145", "All-Rounder"),
        Pair("146", "OP(Munchkin)"),
        Pair("147", "gambling"),
        Pair("148", "money"),
        Pair("149", "r18"),
        Pair("150", "Tsundere"),
        Pair("151", "Proactive Protagonist"),
        Pair("152", " Cute Story"),
        Pair("153", "Alternate Universe"),
        Pair("154", "Movie"),
        Pair("155", "adhesion"),
        Pair("156", "illusion"),
        Pair("157", "Villain role"),
        Pair("158", "ModernFantasy"),
        Pair("159", "hunter"),
        Pair("160", "TS"),
        Pair("161", "munchkin"),
        Pair("162", "tower"),
        Pair("163", "hyundai"),
        Pair("164", "modern fantasy"),
        Pair("165", "alchemy"),
        Pair("166", "worldwar"),
        Pair("167", "WarHero"),
        Pair("168", "#AlternativeHistory"),
        Pair("169", "famous famaily"),
        Pair("170", "dark"),
        Pair("171", "yandere"),
        Pair("172", "ghost"),
        Pair("173", "catfight"),
        Pair("174", "sauce"),
        Pair("175", "food"),
        Pair("176", "cook"),
        Pair("177", "cyberpunk"),
        Pair("178", "mind control"),
        Pair("179", "hypnosis"),
        Pair("180", "# Mukbang/Cooking"),
        Pair("181", "fusion"),
        Pair("182", "Awakening"),
        Pair("183", "Farming"),
        Pair("184", "Pure Love"),
        Pair("185", "slave"),
        Pair("186", "Kingdom Building"),
        Pair("187", "Political"),
        Pair("188", "Redemption"),
        Pair("189", "Ai"),
        Pair("190", "showbiz"),
        Pair("191", "Orthodox"),
        Pair("192", "EntertainmentIndustry"),
        Pair("193", "writer"),
        Pair("194", "Healing"),
        Pair("195", "Medical"),
        Pair("196", "Mana"),
        Pair("197", "Medieval"),
        Pair("198", "Schemes "),
        Pair("199", "love"),
        Pair("200", "Marriage "),
        Pair("201", "netrori"),
        Pair("202", "gods"),
        Pair("203", "crazy love interest "),
        Pair("204", "MMA"),
        Pair("205", "ice age"),
        Pair("206", "management"),
        Pair("207", "Female Protagonist"),
        Pair("208", "Royalty"),
        Pair("209", "Mob Protagonist"),
        Pair("210", "climbing"),
        Pair("211", "middleAge"),
        Pair("212", "romance fantasy"),
        Pair("213", "cooking"),
        Pair("214", "return"),
        Pair("215", "northern air force"),
        Pair("216", "National Management"),
        Pair("217", "#immortality"),
        Pair("218", "Fist Techniques"),
        Pair("219", "Retired Expert"),
        Pair("220", "Returnee"),
        Pair("221", "Hidden Identity"),
        Pair("222", "Zombie"),
        Pair("223", "Knight"),
        Pair("224", "NTL"),
        Pair("225", "bitcoins"),
        Pair("226", "crypto"),
        Pair("227", "actia"),
        Pair("228", "Brainwashing"),
        Pair("229", "Tentacles"),
        Pair("230", "Slime"),
        Pair("231", "cultivators"),
        Pair("232", "bully"),
        Pair("233", "#university"),
        Pair("234", "BL"),
        Pair("235", "Omegaverse"),
        Pair("236", "Girl's Love"),
        Pair("237", "theater"),
        Pair("238", "Broadcasting"),
        Pair("239", "Success"),
        Pair("240", "Internet Broadcasting"),
        Pair("241", "rape"),
        Pair("242", "Madman"),
        Pair("243", "Soccer"),
        Pair("244", "#SoloProtagonist"),
        Pair("245", "#Underworld"),
        Pair("246", "#Politics"),
        Pair("247", "#Army"),
        Pair("248", "#ThreeKingdoms"),
        Pair("249", "#Conspiracy"),
        Pair("250", " Possessive Characters"),
        Pair("251", "European Ambience"),
        Pair("252", "Love Interest Falls in Love First"),
        Pair("253", "Reincarnated in a Game World"),
        Pair("254", "Male Yandere"),
        Pair("255", "Handsome Male Lead "),
        Pair("256", "Monsters "),
        Pair("257", "Urban Legend"),
        Pair("258", "modern"),
        Pair("259", "summoning"),
        Pair("260", "LightNovel"),
        Pair("261", "vampire"),
        Pair("262", "GameDevelopment"),
        Pair("263", "Normalization"),
        Pair("264", "GameFantasy"),
        Pair("265", "VirtualReality"),
        Pair("266", "Infinite Money Glitch"),
        Pair("267", "Tycoon"),
        Pair("268", "#CampusLife"),
        Pair("269", "#Regression"),
        Pair("270", "#Chaebol"),
        Pair("271", "#Business"),
        Pair("272", "#RealEstate"),
        Pair("273", "#Revenge"),
        Pair("274", "#Healing"),
        Pair("275", "SF"),
        Pair("276", "Community"),
        Pair("277", "Anomaly"),
        Pair("278", "CosmicHorror"),
        Pair("279", "CreepypastaUniverse"),
        Pair("280", "growth"),
        Pair("281", "Bingyi"),
        Pair("282", "Healer"),
        Pair("283", "#TSHeroine"),
        Pair("284", "#management"),
        Pair("285", "#GoldenSun"),
        Pair("286", "GrowthMunchkin"),
        Pair("287", "Fundamentals"),
        Pair("288", "broadcast"),
        Pair("289", "Luck"),
        Pair("290", "Investment"),
        Pair("291", "Divorced"),
        Pair("292", "#mercenary"),
        Pair("293", "#Art"),
        Pair("294", "#All-Rounder"),
        Pair("295", "#EntertainmentIndustry"),
        Pair("296", "#Music"),
        Pair("297", "Villain"),
        Pair("298", "Psychopath"),
        Pair("299", "Battle Royale"),
        Pair("300", "Progression"),
        Pair("301", "Billionaire"),
        Pair("302", "Beast Tamer"),
        Pair("303", "#HighIntensity"),
        Pair("304", "#Enterprise"),
        Pair("305", "#Growth"),
        Pair("306", "#Obsession"),
        Pair("307", "#Multiverse"),
        Pair("308", "#Academy"),
        Pair("309", "#NTL"),
        Pair("310", "#MaleOriented"),
        Pair("311", "#Possession"),
        Pair("312", "#Isekai"),
        Pair("313", "#Idol"),
        Pair("314", "#Filming"),
        Pair("315", "#Training"),
        Pair("316", "Hitler"),
        Pair("317", "Early Modern"),
        Pair("318", "Alternate History"),
        Pair("319", "Salvation"),
        Pair("320", "fate"),
        Pair("321", "DevotedMaleLead"),
        Pair("322", "PowerfulMaleLead"),
        Pair("323", "StrongAbility"),
        Pair("324", "gate"),
        Pair("325", "childbirth"),
        Pair("326", "Hetrosexual"),
        Pair("327", "ClubOwner"),
        Pair("328", "SlowPaced"),
        Pair("329", "Western"),
        Pair("330", "Cheat"),
        Pair("331", "Gunslinger"),
        Pair("332", "Pure Romance"),
        Pair("333", "Humiliation"),
        Pair("334", "#Territory"),
        Pair("335", "Assistant"),
        Pair("336", "Rich"),
        Pair("337", "#Zombie"),
        Pair("338", "#StatusWindow"),
        Pair("339", "#Apocalypse"),
        Pair("340", "#GirlGroup"),
        Pair("341", "Labyrinth"),
        Pair("342", "Gender Reversal"),
    )
}

// Safe JSON extension functions - return null instead of throwing
private val kotlinx.serialization.json.JsonElement.jsonObjectOrNull: kotlinx.serialization.json.JsonObject?
    get() = this as? kotlinx.serialization.json.JsonObject

private val kotlinx.serialization.json.JsonElement.jsonObject: kotlinx.serialization.json.JsonObject
    get() = this as? kotlinx.serialization.json.JsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())

private val kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull: kotlinx.serialization.json.JsonPrimitive?
    get() = this as? kotlinx.serialization.json.JsonPrimitive

private val kotlinx.serialization.json.JsonElement.jsonPrimitive: kotlinx.serialization.json.JsonPrimitive
    get() = this as? kotlinx.serialization.json.JsonPrimitive ?: kotlinx.serialization.json.JsonPrimitive("")

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = try {
        if (isString) content else content.takeIf { it != "null" }
    } catch (e: Exception) {
        null
    }

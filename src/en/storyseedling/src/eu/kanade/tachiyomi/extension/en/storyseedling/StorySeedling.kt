package eu.kanade.tachiyomi.extension.en.storyseedling

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy

/**
 * StorySeedling novel source - ported from LN Reader plugin
 * @see https://github.com/LNReader/lnreader-plugins StorySeedling.ts
 * Uses AJAX API with FormData for chapter list (series_toc action)
 */
class StorySeedling : HttpSource(), NovelSource {

    override val name = "StorySeedling"
    override val baseUrl = "https://storyseedling.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient
    private val json: Json by injectLazy()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Referer", "$baseUrl/")

    // ======================== Turnstile Detection ========================

    /**
     * LN Reader: Detect Cloudflare Turnstile captcha
     * Throws exception if detected to prompt webview open
     */
    private fun checkTurnstile(doc: Document) {
        // LN Reader checks for turnstile in page title or content
        val hasTurnstile = doc.selectFirst("script[src*='challenges.cloudflare.com/turnstile']") != null ||
            doc.title().contains("Turnstile", ignoreCase = true) ||
            doc.selectFirst("[cf-turnstile-response]") != null
        if (hasTurnstile) {
            throw Exception("Cloudflare Turnstile detected, please open in WebView")
        }
    }

    // ======================== Post Value Cache ========================

    /**
     * LN Reader: The browse page has a dynamic post value that must be extracted
     * Format: browse('xxxxx') in div[ax-load][x-data] attribute
     */
    @Volatile
    private var cachedPostValue: String? = null

    private fun getPostValue(): String {
        cachedPostValue?.let { return it }

        // Fetch browse page to extract dynamic post value
        val browseResponse = client.newCall(GET("$baseUrl/browse", headers)).execute()
        val doc = Jsoup.parse(browseResponse.body.string())

        // LN Reader: Extract from div[ax-load][x-data] with format browse('xxxxx')
        val xData = doc.selectFirst("div[ax-load][x-data*=browse]")?.attr("x-data") ?: ""
        val postValue = Regex("""browse\s*\(\s*['"]([^'"]+)['"]\s*\)""").find(xData)?.groupValues?.get(1) ?: "browse"

        cachedPostValue = postValue
        return postValue
    }

    // ======================== Popular/Browse ========================

    override fun popularMangaRequest(page: Int): Request {
        // LN Reader: Uses browse() post value from page, with fetch_browse action
        // NOTE: This is called for both "Popular" and when filters are used without search text
        // Filters should be handled here too, not just in search
        val postValue = getPostValue()
        return POST(
            "$baseUrl/ajax",
            headers,
            FormBody.Builder()
                .add("search", "")
                .add("orderBy", "recent")
                .add("curpage", page.toString())
                .add("post", postValue)
                .add("action", "fetch_browse")
                .build(),
        )
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val responseBody = response.body.string()
        if (responseBody.isBlank()) return MangasPage(emptyList(), false)

        return try {
            val jsonData = json.parseToJsonElement(responseBody).jsonObject
            val dataObj = jsonData["data"]?.jsonObject ?: return MangasPage(emptyList(), false)
            val posts = dataObj["posts"]?.jsonArray ?: return MangasPage(emptyList(), false)

            // Get pagination info from JSON response
            val currentPage = dataObj["page"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            val totalPages = dataObj["pages"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1

            val mangas = posts.mapNotNull { post ->
                try {
                    val postObj = post.jsonObject
                    val title = postObj["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val cover = postObj["thumbnail"]?.jsonPrimitive?.content ?: ""
                    val permalink = postObj["permalink"]?.jsonPrimitive?.content ?: return@mapNotNull null

                    SManga.create().apply {
                        this.title = title
                        thumbnail_url = cover
                        url = permalink.replace(baseUrl, "")
                    }
                } catch (e: Exception) {
                    null
                }
            }

            // Use pages count from response instead of checking if size == 10
            val hasNextPage = currentPage < totalPages
            MangasPage(mangas, hasNextPage)
        } catch (e: Exception) {
            MangasPage(emptyList(), false)
        }
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var orderBy = "recent"
        var status = ""
        val includeGenres = mutableListOf<String>()
        val excludeGenres = mutableListOf<String>()
        val includeTags = mutableListOf<String>()
        val excludeTags = mutableListOf<String>()
        var tagsMode = "and" // default AND for tags

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> orderBy = filter.toUriPart()
                is StatusFilter -> status = filter.toUriPart()
                is GenreFilter -> {
                    filter.state.forEach { genre ->
                        when {
                            genre.isIncluded() -> includeGenres.add(genre.id)
                            genre.isExcluded() -> excludeGenres.add(genre.id)
                        }
                    }
                }
                is TagFilter -> {
                    filter.state.forEach { tag ->
                        when {
                            tag.isIncluded() -> includeTags.add(tag.id)
                            tag.isExcluded() -> excludeTags.add(tag.id)
                        }
                    }
                }
                is TagsModeFilter -> tagsMode = filter.toUriPart()
                else -> {}
            }
        }

        val postValue = getPostValue()
        val body = FormBody.Builder()
            .add("search", query)
            .add("orderBy", orderBy)
            .add("curpage", page.toString())
            .add("post", postValue)
            .add("action", "fetch_browse")

        if (status.isNotEmpty()) body.add("status", status)
        includeGenres.forEach { body.add("includeGenres[]", it) }
        excludeGenres.forEach { body.add("excludeGenres[]", it) }
        includeTags.forEach { body.add("includeTags[]", it) }
        excludeTags.forEach { body.add("excludeTags[]", it) }
        if (includeTags.isNotEmpty() || excludeTags.isNotEmpty()) {
            body.add("tagsMode", tagsMode)
        }

        return POST("$baseUrl/ajax", headers, body.build())
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // ======================== Details ========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        // Check for Turnstile
        checkTurnstile(doc)

        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim() ?: ""

            // LN Reader: img[x-ref="art"].w-full.rounded.shadow-md
            val coverUrl = doc.selectFirst("img[x-ref=\"art\"].w-full.rounded.shadow-md")?.attr("src")
            if (coverUrl != null) {
                thumbnail_url = if (coverUrl.startsWith("http")) coverUrl else "$baseUrl$coverUrl"
            }

            // Get author from "Written by" section
            // Format: <div class="mb-1 leading-7"><span>Written by</span><a href="...">AuthorName</a></div>
            author = doc.selectFirst("div.mb-1.leading-7:has(span:contains(Written by)) a")?.text()?.trim()

            // Get genres from both main genre section and additional tags
            val mainGenres = doc.select(
                "section[x-data=\"{ tab: location.hash.substr(1) || 'chapters' }\"].relative > div > div > div.flex.flex-wrap > a",
            ).map { it.text().trim() }

            // Additional tags from order-3 section with tag links
            // Format: <a href="https://storyseedling.com/browse/?includeTags%5B%5D=XXX" class="...">#+TagName</a>
            val additionalTags = doc.select("div.order-3 div.flex.flex-wrap a[href*=includeTags]")
                .map { it.text().replace("#", "").trim() }

            genre = (mainGenres + additionalTags).distinct().filter { it.isNotBlank() }.joinToString(", ")

            // Description from the order-3 lg:grid-in-content section
            // Format: <div class="order-3 lg:grid-in-content"><div x-data="{ expanded: false }">...<p>...</p></div>
            val descContainer = doc.selectFirst("div.order-3.lg\\:grid-in-content div[x-data*=expanded] div.mb-4.order-2")
                ?: doc.selectFirst("div.order-3.lg\\:grid-in-content div[x-data] div.grid div.mb-4")
                ?: doc.selectFirst("div.order-3 div[x-data] div.mb-4.order-2")

            description = descContainer?.let { container ->
                container.select("p").joinToString("\n\n") { it.text().trim() }
            }?.ifEmpty {
                doc.select("div.mb-4.text-base p, div.synopsis p")
                    .joinToString("\n\n") { it.text().trim() }
            } ?: doc.select("div.mb-4.text-base p, div.synopsis p")
                .joinToString("\n\n") { it.text().trim() }
                .ifEmpty { doc.selectFirst(".prose, .description")?.text()?.trim() }

            status = when {
                doc.text().contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                doc.text().contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    // ======================== Chapter List ========================

    /**
     * LN Reader: Extracts toc data from x-data attribute
     * Format: toc('dataNovelId', 'dataNovelN') - e.g., toc('000000', 'xxxxxxxxxx')
     */
    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())

        // Check for Turnstile
        checkTurnstile(doc)

        // LN Reader: Extract toc data from x-data attribute - div[ax-load][x-data*=toc]
        // Format: toc('dataNovelId', 'dataNovelN')
        val xData = doc.selectFirst("div[ax-load][x-data*=toc]")?.attr("x-data")
            ?: doc.selectFirst(".bg-accent div[ax-load][x-data]")?.attr("x-data")
            ?: doc.selectFirst("[x-data*=toc]")?.attr("x-data")
            ?: ""

        // Parse toc('dataNovelId', 'dataNovelN') format
        val tocMatch = Regex("""toc\s*\(\s*['"]([^'"]+)['"],\s*['"]([^'"]+)['"]\)""").find(xData)
        val dataNovelId = tocMatch?.groupValues?.get(1)
        val dataNovelN = tocMatch?.groupValues?.get(2)

        if (dataNovelId != null && dataNovelN != null) {
            try {
                // LN Reader: Fetch chapters via AJAX with series_toc action
                // FormData: post=dataNovelN, id=dataNovelId, action=series_toc
                val ajaxResponse = client.newCall(
                    POST(
                        "$baseUrl/ajax",
                        headers,
                        FormBody.Builder()
                            .add("post", dataNovelN)
                            .add("id", dataNovelId)
                            .add("action", "series_toc")
                            .build(),
                    ),
                ).execute()

                val responseBody = ajaxResponse.body.string()
                if (responseBody.isNotBlank()) {
                    val jsonData = json.parseToJsonElement(responseBody).jsonObject
                    val chaptersData = jsonData["data"]

                    // LN Reader: Handle JSON array format [{title, url, date, slug, is_locked}, ...]
                    when {
                        chaptersData?.let { it is kotlinx.serialization.json.JsonArray } == true -> {
                            val chapters = chaptersData.jsonArray.mapNotNull { chapterJson ->
                                try {
                                    val chapterObj = chapterJson.jsonObject
                                    val url = chapterObj["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                                    val title = chapterObj["title"]?.jsonPrimitive?.content ?: ""
                                    val slug = chapterObj["slug"]?.jsonPrimitive?.content ?: ""
                                    val isLocked = chapterObj["is_locked"]?.jsonPrimitive?.content == "true"

                                    SChapter.create().apply {
                                        this.url = url.replace(baseUrl, "")
                                        this.name = if (isLocked) "🔒 $title" else title
                                        date_upload = 0L
                                        chapter_number = slug.toFloatOrNull() ?: 0f
                                    }
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            if (chapters.isNotEmpty()) return chapters
                        }
                        // HTML string format (fallback)
                        chaptersData?.jsonPrimitive?.isString == true -> {
                            val chaptersHtml = chaptersData.jsonPrimitive.content
                            if (chaptersHtml.isNotBlank()) {
                                val chaptersDoc = Jsoup.parse(chaptersHtml)

                                val chapters = chaptersDoc.select("a[href*='/chapter/']").mapNotNull { element ->
                                    try {
                                        val url = element.attr("href").replace(baseUrl, "")
                                        val name = element.text().trim()

                                        SChapter.create().apply {
                                            this.url = url
                                            this.name = name
                                            date_upload = 0L
                                        }
                                    } catch (e: Exception) {
                                        null
                                    }
                                }.reversed()

                                if (chapters.isNotEmpty()) return chapters
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Fall through to HTML parsing fallback
            }
        }

        // Fallback to HTML parsing if AJAX fails
        return doc.select("div[x-show=\"tab === 'chapters'\"] a[href*='/chapter/'], a[href*='/chapter/']").mapNotNull { element ->
            try {
                val url = element.attr("href").replace(baseUrl, "")
                val name = element.text().trim()
                if (name.isBlank()) return@mapNotNull null

                SChapter.create().apply {
                    this.url = url
                    this.name = name
                    date_upload = 0L
                }
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.url }.reversed()
    }

    // ======================== Pages ========================

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString().removePrefix(baseUrl)
        return listOf(Page(0, chapterUrl))
    }

    // ======================== Novel Content ========================

    /**
     * LN Reader: Chapter content is in div.justify-center > div.mb-4
     * Note: StorySeedling uses Turnstile protection on chapter pages
     * Content is loaded dynamically via loadChapter() JavaScript function
     */
    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = Jsoup.parse(response.body.string())

        // Check for Turnstile - StorySeedling uses loadChapter() with Turnstile
        // The pattern is: x-data="loadChapter('sitekey', 'chapterId')"
        val hasLoadChapter = doc.selectFirst("div[x-data*=loadChapter]") != null
        if (hasLoadChapter) {
            // Check for Turnstile first
            checkTurnstile(doc)
            // If no Turnstile detected but loadChapter exists, content requires JavaScript
            throw Exception("Chapter content requires WebView (Turnstile protection). Please read in WebView.")
        }

        // Check for standard Turnstile
        checkTurnstile(doc)

        // LN Reader: div.justify-center > div.mb-4
        // Try multiple approaches to find content
        val content = doc.selectFirst("div.justify-center > div.mb-4")?.html()
            ?: doc.select("div.justify-center").firstOrNull()
                ?.select("> div.mb-4")?.firstOrNull()?.html()
            // Fallback: find div.mb-4 that's inside justify-center
            ?: doc.select("div.mb-4").firstOrNull { element ->
                element.parent()?.hasClass("justify-center") == true
            }?.html()
            // Last fallback: any content div
            ?: doc.selectFirst(".prose")?.html()
            ?: ""

        // Filter out HC content like TypeScript plugin does
        // Remove any span containing "storyseedling" or "story seedling" (case insensitive)
        val cleanedDoc = Jsoup.parse(content)
        cleanedDoc.select("span").forEach { span ->
            val text = span.text().lowercase()
            if (text.contains("storyseedling") || text.contains("story seedling")) {
                span.text("")
            }
        }

        return cleanedDoc.html()
    }

    // Image URL - not used for novels
    override fun imageUrlParse(response: Response): String = ""

    override fun getFilterList() = FilterList(
        SortFilter(),
        StatusFilter(),
        Filter.Header("Genres (tap to include, tap again to exclude)"),
        GenreFilter(),
        Filter.Header("Tags (tap to include, tap again to exclude)"),
        TagsModeFilter(),
        TagFilter(),
    )

    private class SortFilter : Filter.Select<String>(
        "Order By",
        arrayOf("Recent", "Popular", "Alphabetical", "Rating"),
    ) {
        fun toUriPart() = when (state) {
            0 -> "recent"
            1 -> "views"
            2 -> "title"
            3 -> "rating"
            else -> "recent"
        }
    }

    private class StatusFilter : Filter.Select<String>(
        "Status",
        arrayOf("All", "Ongoing", "Completed", "Hiatus", "Cancelled"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "ongoing"
            2 -> "completed"
            3 -> "hiatus"
            4 -> "cancelled"
            else -> ""
        }
    }

    private class Genre(name: String, val id: String) : Filter.TriState(name)
    private class GenreFilter : Filter.Group<Genre>(
        "Genres",
        listOf(
            Genre("Action", "111"),
            Genre("Adult", "183"),
            Genre("Adventure", "112"),
            Genre("BL", "207"),
            Genre("Comedy", "153"),
            Genre("Drama", "115"),
            Genre("Ecchi", "170"),
            Genre("Fantasy", "114"),
            Genre("Harem", "956"),
            Genre("Historical", "178"),
            Genre("Horror", "254"),
            Genre("Josei", "472"),
            Genre("Martial Arts", "1329"),
            Genre("Mature", "427"),
            Genre("Mecha", "1481"),
            Genre("Mystery", "645"),
            Genre("Psychological", "515"),
            Genre("Reincarnation", "1031"),
            Genre("Romance", "108"),
            Genre("School Life", "545"),
            Genre("Sci-Fi", "113"),
            Genre("Seinen", "708"),
            Genre("Shoujo", "228"),
            Genre("Shoujo Ai", "1403"),
            Genre("Shounen", "246"),
            Genre("Shounen Ai", "718"),
            Genre("Slice of Life", "157"),
            Genre("Smut", "736"),
            Genre("Sports", "966"),
            Genre("Supernatural", "995"),
            Genre("Tragedy", "985"),
            Genre("Xianxia", "245"),
            Genre("Xuanhuan", "428"),
            Genre("Yaoi", "184"),
            Genre("Yuri", "182"),
        ),
    )

    private class TagsModeFilter : Filter.Select<String>(
        "Tags Mode",
        arrayOf("AND (all selected)", "OR (any selected)"),
    ) {
        fun toUriPart() = if (state == 0) "and" else "or"
    }

    private class Tag(name: String, val id: String) : Filter.TriState(name)
    private class TagFilter : Filter.Group<Tag>(
        "Tags",
        listOf(
            Tag("+15", "1435"),
            Tag("+18", "2213"),
            Tag("1vs1", "2281"),
            Tag("Abandoned Children", "445"),
            Tag("Ability Steal", "262"),
            Tag("Absent Parents", "263"),
            Tag("Academy", "689"),
            Tag("Accelerated Growth", "317"),
            Tag("Acting", "748"),
            Tag("Adapted to Anime", "792"),
            Tag("Adapted to Drama", "848"),
            Tag("Adapted to Manga", "265"),
            Tag("Adapted to Manhwa", "1598"),
            Tag("Adopted Protagonist", "917"),
            Tag("Adventurers", "266"),
            Tag("Age Progression", "447"),
            Tag("Alchemy", "357"),
            Tag("Alternate World", "1070"),
            Tag("Amnesia", "1161"),
            Tag("Ancient China", "500"),
            Tag("Ancient Times", "670"),
            Tag("Angels", "344"),
            Tag("Animal Characteristics", "1137"),
            Tag("Anti-Hero Lead", "2702"),
            Tag("Aristocracy", "1630"),
            Tag("Artifact Refining", "2281"),
            Tag("Beautiful Female Lead", "445"),
            Tag("Beastkin", "2281"),
            Tag("Betrayal", "445"),
            Tag("Cheats", "445"),
            Tag("Childhood Friends", "1238"),
            Tag("Clever Protagonist", "689"),
            Tag("Cold Protagonist", "827"),
            Tag("Complex Family Relationships", "447"),
            Tag("Cultivation", "262"),
            Tag("Cunning Protagonist", "263"),
            Tag("Demons", "344"),
            Tag("Dense Protagonist", "1161"),
            Tag("Dragons", "344"),
            Tag("Dungeon", "689"),
            Tag("Dwarfs", "344"),
            Tag("Early Romance", "1070"),
            Tag("Easy Going Life", "157"),
            Tag("Elves", "344"),
            Tag("Evil Gods", "344"),
            Tag("Evil Protagonist", "827"),
            Tag("Fairies", "344"),
            Tag("Family", "447"),
            Tag("Female Protagonist", "183"),
            Tag("Game Elements", "689"),
            Tag("God Protagonist", "827"),
            Tag("Gods", "344"),
            Tag("Gore", "254"),
            Tag("Guilds", "689"),
            Tag("Hard-Working Protagonist", "317"),
            Tag("Hated Protagonist", "827"),
            Tag("Hidden Abilities", "262"),
            Tag("Hiding True Identity", "748"),
            Tag("Human-Nonhuman Relationship", "1137"),
            Tag("Kingdom Building", "917"),
            Tag("Knights", "266"),
            Tag("Late Romance", "1070"),
            Tag("Level System", "689"),
            Tag("Love Interest Falls in Love First", "1070"),
            Tag("Magic", "357"),
            Tag("Male Protagonist", "112"),
            Tag("Master-Servant Relationship", "447"),
            Tag("Military", "266"),
            Tag("Modern Day", "670"),
            Tag("Monster Girls", "1137"),
            Tag("Monsters", "344"),
            Tag("Multiple POV", "447"),
            Tag("Multiple Protagonists", "447"),
            Tag("Multiple Realms", "1070"),
            Tag("Multiple Reincarnated Individuals", "1031"),
            Tag("Nobles", "917"),
            Tag("Non-human Protagonist", "1137"),
            Tag("OP MC", "827"),
            Tag("Orphans", "445"),
            Tag("Overpowered Protagonist", "827"),
            Tag("Pets", "1137"),
            Tag("Politics", "917"),
            Tag("Possession", "1031"),
            Tag("Power Couple", "827"),
            Tag("Pregnancy", "447"),
            Tag("Previous Life Talent", "1031"),
            Tag("Protagonist Strong from the Start", "827"),
            Tag("R-15", "1435"),
            Tag("R-18", "2213"),
            Tag("Rebirth", "1031"),
            Tag("Reincarnated in Another World", "1031"),
            Tag("Revenge", "827"),
            Tag("Reverse Harem", "956"),
            Tag("Royalty", "917"),
            Tag("Ruthless Protagonist", "827"),
            Tag("S*x", "2213"),
            Tag("Scheming", "748"),
            Tag("Second Chance", "1031"),
            Tag("Secret Identity", "748"),
            Tag("Secretive Protagonist", "748"),
            Tag("Servants", "447"),
            Tag("Slaves", "447"),
            Tag("Slow Growth at Start", "317"),
            Tag("Slow Romance", "1070"),
            Tag("Smart MC", "689"),
            Tag("Spirit Users", "357"),
            Tag("Spirits", "344"),
            Tag("Strong Female Lead", "183"),
            Tag("Strong Male Lead", "112"),
            Tag("Strong to Stronger", "317"),
            Tag("Survival", "266"),
            Tag("Sword And Magic", "357"),
            Tag("Sword Wielder", "266"),
            Tag("System", "689"),
            Tag("Transmigration", "1031"),
            Tag("Transported to Another World", "1070"),
            Tag("Underestimated Protagonist", "827"),
            Tag("Unique Cultivation Technique", "262"),
            Tag("Vampires", "344"),
            Tag("Villainess", "827"),
            Tag("Weak to Strong", "317"),
            Tag("Wealthy Characters", "917"),
            Tag("Wizards", "357"),
            Tag("World Travel", "1070"),
        ),
    )
}

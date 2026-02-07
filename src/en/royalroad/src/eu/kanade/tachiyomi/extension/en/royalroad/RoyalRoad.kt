package eu.kanade.tachiyomi.extension.en.royalroad

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
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
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class RoyalRoad : HttpSource(), NovelSource, ConfigurableSource {

    override val name = "Royal Road"
    override val baseUrl = "https://www.royalroad.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Novel source implementation
    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(page.url, headers)).execute()
        val body = response.body.string()
        val url = response.request.url.toString()

        val doc = Jsoup.parse(body, url)

        // Handle CAPTCHA cases
        val title = doc.select("title").text().trim().lowercase()
        val blockedTitles = listOf(
            "bot verification",
            "just a moment...",
            "redirecting...",
            "un instant...",
            "you are being redirected...",
        )
        if (blockedTitles.contains(title)) {
            throw Exception("Captcha detected, please open in webview.")
        }

        return parseChapterContent(doc)
    }

    private fun extractVariable(script: String, variableName: String): String {
        val startPattern = "window.$variableName = "
        val startIndex = script.indexOf(startPattern)
        if (startIndex == -1) return "[]"

        var currIndex = startIndex + startPattern.length
        if (currIndex >= script.length) return "[]"

        // Find start of array
        while (currIndex < script.length && script[currIndex] != '[') {
            currIndex++
        }
        if (currIndex >= script.length) return "[]"

        var bracketCount = 0
        val jsonStart = currIndex
        var inString = false
        var escape = false

        while (currIndex < script.length) {
            val char = script[currIndex]
            if (escape) {
                escape = false
            } else if (char == '\\') {
                escape = true
            } else if (char == '"') {
                inString = !inString
            } else if (!inString) {
                if (char == '[') {
                    bracketCount++
                } else if (char == ']') {
                    bracketCount--
                    if (bracketCount == 0) {
                        return script.substring(jsonStart, currIndex + 1)
                    }
                } else if (char == ';') {
                    if (bracketCount == 0) return script.substring(jsonStart, currIndex)
                }
            }
            currIndex++
        }
        return "[]"
    }

    private fun parseChapterContent(doc: Document): String {
        val hiddenClass = doc.select("style")
            .first()
            ?.html()
            ?.let { style ->
                Regex("""\.(\S+)\s*\{[^}]*display\s*:\s*none""")
                    .find(style)
                    ?.groupValues
                    ?.get(1)
            }

        val chapterElement = doc.selectFirst(".chapter-content") ?: return ""
        val noteElements = doc.select(".author-note-portlet")

        val beforeNotes = mutableListOf<String>()
        val afterNotes = mutableListOf<String>()

        noteElements.forEach { note ->
            if (note.elementSiblingIndex() < chapterElement.elementSiblingIndex()) {
                beforeNotes.add(note.html())
            } else {
                afterNotes.add(note.html())
            }
        }

        val result = StringBuilder()

        if (beforeNotes.isNotEmpty()) {
            result.append("<div class='author-note-before'>")
            result.append(beforeNotes.joinToString("\n"))
            result.append("</div>")
            result.append("<hr class='notes-separator'>")
        }

        result.append(processChapterHtml(chapterElement, hiddenClass))

        if (afterNotes.isNotEmpty()) {
            result.append("<hr class='notes-separator'>")
            result.append("<div class='author-note-after'>")
            result.append(afterNotes.joinToString("\n"))
            result.append("</div>")
        }

        return result.toString()
    }

    private fun processChapterHtml(element: Element, hiddenClass: String?): String {
        if (hiddenClass != null) {
            element.select(".$hiddenClass").remove()
        }

        // Return raw HTML without escaping - the WebView/reader will handle it properly
        // The TS version does streaming with selective text escaping, but for Jsoup,
        // we just return the HTML as-is since it's already properly formatted
        return element.html()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Add preferences if needed
    }

    // Popular novels
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/fictions/search?page=$page&orderBy=popularity", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelsFromSearch(doc)
    }

    // Latest updates
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/fictions/search?page=$page&orderBy=last_update", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelsFromSearch(doc)
    }

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val params = mutableMapOf<String, String>()
        params["page"] = page.toString()

        if (query.isNotEmpty()) {
            params["title"] = query
        }

        filters.forEach { filter ->
            when (filter) {
                is KeywordFilter -> {
                    if (filter.state.isNotEmpty()) {
                        params["keyword"] = filter.state
                    }
                }
                is AuthorFilter -> {
                    if (filter.state.isNotEmpty()) {
                        params["author"] = filter.state
                    }
                }
                is GenreFilter -> {
                    filter.state.forEach { genre ->
                        when {
                            genre.isIncluded() -> params.appendList("tagsAdd", genre.id)
                            genre.isExcluded() -> params.appendList("tagsRemove", genre.id)
                        }
                    }
                }
                is TagFilter -> {
                    filter.state.forEach { tag ->
                        when {
                            tag.isIncluded() -> params.appendList("tagsAdd", tag.id)
                            tag.isExcluded() -> params.appendList("tagsRemove", tag.id)
                        }
                    }
                }
                is ContentWarningFilter -> {
                    filter.state.forEach { warning ->
                        when {
                            warning.isIncluded() -> params.appendList("tagsAdd", warning.id)
                            warning.isExcluded() -> params.appendList("tagsRemove", warning.id)
                        }
                    }
                }
                is MinPagesFilter -> {
                    if (filter.state.isNotEmpty() && filter.state != "0") {
                        params["minPages"] = filter.state
                    }
                }
                is MaxPagesFilter -> {
                    if (filter.state.isNotEmpty() && filter.state != "20000") {
                        params["maxPages"] = filter.state
                    }
                }
                is MinRatingFilter -> {
                    if (filter.state.isNotEmpty() && filter.state != "0.0") {
                        params["minRating"] = filter.state
                    }
                }
                is MaxRatingFilter -> {
                    if (filter.state.isNotEmpty() && filter.state != "5.0") {
                        params["maxRating"] = filter.state
                    }
                }
                is StatusFilter -> {
                    if (filter.state != 0) {
                        params["status"] = filter.toUriPart()
                    }
                }
                is OrderByFilter -> {
                    if (filter.state != 0) {
                        params["orderBy"] = filter.toUriPart()
                    }
                }
                is DirFilter -> {
                    if (filter.state != 0) {
                        params["dir"] = filter.toUriPart()
                    }
                }
                is TypeFilter -> {
                    if (filter.state != 0) {
                        params["type"] = filter.toUriPart()
                    }
                }
                else -> Unit
            }
        }

        params["page"] = page.toString()
        val queryString = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        return GET("$baseUrl/fictions/search?$queryString", headers)
    }

    private fun MutableMap<String, String>.appendList(key: String, value: String) {
        val current = this[key]
        this[key] = if (current.isNullOrEmpty()) value else "$current,$value"
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelsFromSearch(doc)
    }

    private fun parseNovelsFromSearch(doc: Document): MangasPage {
        val novels = doc.select(".fiction-list-item").mapNotNull { element ->
            // Find the fiction link - usually in an <a> tag wrapping the content
            val linkElement = element.selectFirst("a[href*=/fiction/]") ?: return@mapNotNull null
            val href = linkElement.attr("href")

            // Extract the fiction path
            val fictionPath = extractFictionPath(href)
            if (fictionPath.isEmpty()) return@mapNotNull null

            // Get the thumbnail image
            val imgElement = element.selectFirst("img")

            SManga.create().apply {
                title = imgElement?.attr("alt") ?: linkElement.attr("title") ?: "Unknown Title"
                thumbnail_url = imgElement?.attr("src")?.let { src ->
                    when {
                        src.startsWith("http") -> src
                        src.startsWith("//") -> "https:$src"
                        src.startsWith("/") -> "$baseUrl$src"
                        else -> "$baseUrl/$src"
                    }
                } ?: ""
                url = fictionPath
            }
        }

        val hasNextPage = doc.select(".page-link:contains(Next), .pagination a:contains(Next)").isNotEmpty() ||
            doc.select("a:contains(›):not(:has(*))").isNotEmpty()

        return MangasPage(novels, hasNextPage)
    }

    private fun extractFictionPath(url: String): String {
        val cleanUrl = url.replace(Regex("""royalroad\.comfiction/"""), "royalroad.com/fiction/")
        // Extract the fiction/slug part from various URL formats
        val patterns = listOf(
            Regex("""/fiction/(\d+)/([^/?#]+)"""), // /fiction/12345/slug
            Regex("""/fiction/([^/?#]+)"""), // /fiction/slug
            Regex("""^fiction/(\d+)/([^/?#]+)"""), // fiction/12345/slug
            Regex("""^fiction/([^/?#]+)"""), // fiction/slug
        )

        for (pattern in patterns) {
            pattern.find(url)?.let { match ->
                return if (match.groupValues.size >= 3) {
                    "fiction/${match.groupValues[1]}/${match.groupValues[2]}"
                } else {
                    "fiction/${match.groupValues[1]}"
                }
            }
        }

        return ""
    }

    // Manga details
    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$baseUrl/${manga.url.trimStart('/')}", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        val title = doc.selectFirst("h1[property=name]")?.text()
            ?: doc.selectFirst("h1")?.text() ?: ""
        val author = doc.selectFirst("h4[property=author] a")?.text()
            ?: doc.select("a[href^=/profile/]").first()?.text() ?: ""

        // Description - get the hidden full description if available
        val description = doc.selectFirst("div.description div.hidden-content")?.text()
            ?: doc.selectFirst("div.description")?.text()
            ?: ""

        val status = doc.selectFirst("span.label-sm.bg-blue-dark, span.label-sm.bg-yellow, span.label-sm.bg-success")?.text()?.let { statusText ->
            when (statusText.uppercase()) {
                "ONGOING" -> SManga.ONGOING
                "HIATUS" -> SManga.ON_HIATUS
                "COMPLETED" -> SManga.COMPLETED
                "DROPPED" -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        } ?: SManga.UNKNOWN

        val cover = doc.selectFirst("img.thumbnail, div.cover-art-container img")?.attr("src")?.let { src ->
            when {
                src.startsWith("http") -> src
                src.startsWith("/") -> "$baseUrl$src"
                else -> "$baseUrl/$src"
            }
        } ?: ""

        val genres = doc.select("span.tags a, a.fiction-tag").joinToString(", ") { it.text() }

        return SManga.create().apply {
            this.title = title
            this.author = author
            this.description = description
            this.status = status
            this.thumbnail_url = cover
            this.genre = genres
        }
    }

    // Chapter list
    override fun chapterListRequest(manga: SManga): Request {
        // We already have the data from manga details
        return GET("$baseUrl/${manga.url.trimStart('/')}", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())

        // Extract chapters and volumes from script content
        val scriptContent = doc.select("script").joinToString("\n") { it.html() }

        val chaptersJson = extractVariable(scriptContent, "chapters")
        val volumesJson = extractVariable(scriptContent, "volumes")

        val chapters = try {
            json.decodeFromString<List<ChapterEntry>>(chaptersJson)
        } catch (e: Exception) {
            emptyList()
        }

        val volumes = try {
            json.decodeFromString<List<VolumeEntry>>(volumesJson)
        } catch (e: Exception) {
            emptyList()
        }

        val volumeMap = volumes.associateBy { it.id }

        return chapters.mapNotNull { chapter ->
            val volume = volumeMap[chapter.volumeId]
            // Use the full URL path - RoyalRoad needs the complete URL format
            // URL format: fiction/{id}/{slug}/chapter/{chapterId}/{chapterSlug}
            val chapterUrl = chapter.url.removePrefix("/")

            SChapter.create().apply {
                name = chapter.title
                url = chapterUrl
                date_upload = parseDate(chapter.date)
                chapter_number = chapter.order.toFloat()
                if (volume != null) {
                    name = "${volume.title} - $name"
                }
            }
        }
    }

    private fun parseDate(dateString: String): Long {
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .parse(dateString)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // Page list - return single page with the chapter URL
    override fun pageListRequest(chapter: SChapter): Request {
        // chapter.url already contains 'fiction/' prefix, e.g., 'fiction/137985/chapter/12345678'
        return GET("$baseUrl/${chapter.url}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        return listOf(Page(0, response.request.url.toString(), null))
    }

    override fun imageUrlParse(response: Response) = ""

    // Filters
    override fun getFilterList() = FilterList(
        Filter.Header("Search Filters"),
        Filter.Separator(),
        KeywordFilter(),
        AuthorFilter(),
        GenreFilter(),
        TagFilter(),
        ContentWarningFilter(),
        MinPagesFilter(),
        MaxPagesFilter(),
        MinRatingFilter(),
        MaxRatingFilter(),
        StatusFilter(),
        OrderByFilter(),
        DirFilter(),
        TypeFilter(),
    )

    // Filter classes
    private class KeywordFilter : Filter.Text("Keyword (title or description)")
    private class AuthorFilter : Filter.Text("Author")

    private class Genre(name: String, val id: String) : Filter.TriState(name)
    private class GenreFilter : Filter.Group<Genre>(
        "Genres",
        listOf(
            Genre("Action", "action"),
            Genre("Adventure", "adventure"),
            Genre("Comedy", "comedy"),
            Genre("Contemporary", "contemporary"),
            Genre("Drama", "drama"),
            Genre("Fantasy", "fantasy"),
            Genre("Historical", "historical"),
            Genre("Horror", "horror"),
            Genre("Mystery", "mystery"),
            Genre("Psychological", "psychological"),
            Genre("Romance", "romance"),
            Genre("Satire", "satire"),
            Genre("Sci-fi", "sci_fi"),
            Genre("Short Story", "one_shot"),
            Genre("Tragedy", "tragedy"),
        ),
    )

    private class Tag(name: String, val id: String) : Filter.TriState(name)
    private class TagFilter : Filter.Group<Tag>(
        "Tags",
        listOf(
            Tag("Anti-Hero Lead", "anti-hero_lead"),
            Tag("Artificial Intelligence", "artificial_intelligence"),
            Tag("Attractive Lead", "attractive_lead"),
            Tag("Cyberpunk", "cyberpunk"),
            Tag("Dungeon", "dungeon"),
            Tag("Dystopia", "dystopia"),
            Tag("Female Lead", "female_lead"),
            Tag("First Contact", "first_contact"),
            Tag("GameLit", "gamelit"),
            Tag("Gender Bender", "gender_bender"),
            Tag("Genetically Engineered", "genetically_engineered"),
            Tag("Grimdark", "grimdark"),
            Tag("Hard Sci-fi", "hard_sci-fi"),
            Tag("Harem", "harem"),
            Tag("High Fantasy", "high_fantasy"),
            Tag("LitRPG", "litrpg"),
            Tag("Low Fantasy", "low_fantasy"),
            Tag("Magic", "magic"),
            Tag("Male Lead", "male_lead"),
            Tag("Martial Arts", "martial_arts"),
            Tag("Multiple Lead Characters", "multiple_lead"),
            Tag("Mythos", "mythos"),
            Tag("Non-Human Lead", "non-human_lead"),
            Tag("Portal Fantasy / Isekai", "summoned_hero"),
            Tag("Post Apocalyptic", "post_apocalyptic"),
            Tag("Progression", "progression"),
            Tag("Reader Interactive", "reader_interactive"),
            Tag("Reincarnation", "reincarnation"),
            Tag("Ruling Class", "ruling_class"),
            Tag("School Life", "school_life"),
            Tag("Secret Identity", "secret_identity"),
            Tag("Slice of Life", "slice_of_life"),
            Tag("Soft Sci-fi", "soft-sci-fi"),
            Tag("Space Opera", "space_opera"),
            Tag("Sports", "sports"),
            Tag("Steampunk", "steampunk"),
            Tag("Strategy", "strategy"),
            Tag("Strong Lead", "strong_lead"),
            Tag("Super Heroes", "super_heroes"),
            Tag("Supernatural", "supernatural"),
            Tag("Technologically Engineered", "technologically_engineered"),
            Tag("Time Loop", "loop"),
            Tag("Time Travel", "time_travel"),
            Tag("Urban Fantasy", "urban_fantasy"),
            Tag("Villainous Lead", "villainous_lead"),
            Tag("Virtual Reality", "virtual_reality"),
            Tag("War and Military", "war_and_military"),
            Tag("Wuxia", "wuxia"),
            Tag("Xianxia", "xianxia"),
        ),
    )

    private class ContentWarning(name: String, val id: String) : Filter.TriState(name)
    private class ContentWarningFilter : Filter.Group<ContentWarning>(
        "Content Warnings",
        listOf(
            ContentWarning("Profanity", "profanity"),
            ContentWarning("Sexual Content", "sexuality"),
            ContentWarning("Graphic Violence", "graphic_violence"),
            ContentWarning("Sensitive Content", "sensitive"),
            ContentWarning("AI-Assisted Content", "ai_assisted"),
            ContentWarning("AI-Generated Content", "ai_generated"),
        ),
    )

    private class MinPagesFilter : Filter.Text("Min Pages")
    private class MaxPagesFilter : Filter.Text("Max Pages")
    private class MinRatingFilter : Filter.Text("Min Rating (0.0 - 5.0)")
    private class MaxRatingFilter : Filter.Text("Max Rating (0.0 - 5.0)")

    private class StatusFilter : Filter.Select<String>(
        "Status",
        arrayOf(
            "All",
            "Completed",
            "Dropped",
            "Ongoing",
            "Hiatus",
            "Stub",
        ),
    ) {
        fun toUriPart() = when (state) {
            1 -> "COMPLETED"
            2 -> "DROPPED"
            3 -> "ONGOING"
            4 -> "HIATUS"
            5 -> "STUB"
            else -> "ALL"
        }
    }

    private class OrderByFilter : Filter.Select<String>(
        "Order by",
        arrayOf(
            "Relevance",
            "Popularity",
            "Average Rating",
            "Last Update",
            "Release Date",
            "Followers",
            "Number of Pages",
            "Views",
            "Title",
            "Author",
        ),
    ) {
        fun toUriPart() = when (state) {
            0 -> "relevance"
            1 -> "popularity"
            2 -> "rating"
            3 -> "last_update"
            4 -> "release_date"
            5 -> "followers"
            6 -> "length"
            7 -> "views"
            8 -> "title"
            9 -> "author"
            else -> "relevance"
        }
    }

    private class DirFilter : Filter.Select<String>(
        "Direction",
        arrayOf("Descending", "Ascending"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "asc"
            else -> "desc"
        }
    }

    private class TypeFilter : Filter.Select<String>(
        "Type",
        arrayOf("All", "Fan Fiction", "Original"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "fanfiction"
            2 -> "original"
            else -> "ALL"
        }
    }
}

// Data classes for JSON parsing
@Serializable
private data class ChapterEntry(
    val id: Int,
    val volumeId: Int? = null,
    val title: String,
    val slug: String = "",
    val date: String,
    val order: Int,
    val visible: Int = 1,
    val subscriptionTiers: String? = null,
    val doesNotRollOver: Boolean = false,
    val isUnlocked: Boolean = true,
    val url: String,
)

@Serializable
private data class VolumeEntry(
    val id: Int,
    val title: String,
    val cover: String,
    val order: Int,
)

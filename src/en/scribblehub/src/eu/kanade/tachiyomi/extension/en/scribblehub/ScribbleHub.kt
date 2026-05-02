package eu.kanade.tachiyomi.extension.en.scribblehub

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
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
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar

class ScribbleHub :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "Scribble Hub"
    override val baseUrl = "https://www.scribblehub.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular novels
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/series-finder/?sf=1&sort=ratings&order=desc&pg=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelsFromSearch(doc)
    }

    // Latest updates
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest-series/?pg=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelsFromSearch(doc)
    }

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = if (query.isNotEmpty()) {
        // Text search - ScribbleHub uses 'pgi' for text search pagination
        GET("$baseUrl/?s=${query.replace(" ", "+")}&post_type=fictionposts&pgi=$page", headers)
    } else {
        // Filter search - uses pg parameter
        val url = buildFilterUrl(page, filters)
        GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelsFromSearch(doc)
    }

    private fun parseNovelsFromSearch(doc: Document): MangasPage {
        val novels = doc.select("div.search_main_box").mapNotNull { element ->
            val titleElement = element.select(".search_title > a").first() ?: return@mapNotNull null
            val novelUrl = titleElement.attr("href")

            SManga.create().apply {
                title = titleElement.text()
                thumbnail_url = element.select(".search_img img").attr("src")
                url = novelUrl.removePrefix(baseUrl)
            }
        }

        // ScribbleHub pagination: Check for next page link (»)
        // The pagination uses <a class="page-link next">»</a> or numbered links after current
        // Also check script for items count vs currentPage
        val hasNextPage = run {
            // Method 1: Look for » or Next link
            if (doc.select("a.page-link:contains(»)").isNotEmpty()) return@run true
            if (doc.select("a.page-link.next").isNotEmpty()) return@run true

            // Method 2: Check pagination script data
            val scripts = doc.select("script").html()
            val itemsMatch = Regex("""items:\s*(\d+)""").find(scripts)
            val currentMatch = Regex("""currentPage:\s*['"]?(\d+)['"]?""").find(scripts)
            if (itemsMatch != null && currentMatch != null) {
                val totalPages = itemsMatch.groupValues[1].toIntOrNull() ?: 0
                val currentPage = currentMatch.groupValues[1].toIntOrNull() ?: 1
                if (currentPage < totalPages) return@run true
            }

            // Method 3: If we got results, check if there's any page link beyond page 1
            doc.select("a.page-link[href]").any { link ->
                val href = link.attr("href")
                val pageNum = Regex("""pg=(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                pageNum != null && pageNum > 1
            }
        }

        return MangasPage(novels, hasNextPage)
    }

    // Manga details
    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            title = doc.select(".fic_title").text().ifEmpty { "Untitled" }

            // Get high-res cover - try data-src first for lazy loading
            val coverElement = doc.select(".fic_image img").first()
            thumbnail_url = coverElement?.let { img ->
                img.attr("data-src").ifEmpty { img.attr("src") }
            } ?: ""

            author = doc.select(".auth_name_fic").text().trim()
            // Collect genres and tags (tags are in .wi_fic_showtags a.stag)
            val genresFromPage = doc.select(".fic_genre").map { it.text().trim() }.filter { it.isNotEmpty() }
            val tagsFromPage = doc.select(".wi_fic_showtags a.stag, .wi_fic_showtags_inner a.stag").map { it.text().trim() }.filter { it.isNotEmpty() }
            val allGenres = (genresFromPage + tagsFromPage).distinct()
            genre = allGenres.joinToString(", ")

            // Extract status from stats
            val statsText = doc.select(".rnd_stats").text().lowercase()
            status = when {
                statsText.contains("ongoing") -> SManga.ONGOING
                statsText.contains("completed") -> SManga.COMPLETED
                statsText.contains("hiatus") -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }

            description = doc.select(".wi_fic_desc").text().trim()
        }
    }

    // Chapter list
    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())

        // Extract novel ID from the page - try multiple methods
        val novelId = extractNovelId(doc, response.request.url.encodedPath)
        if (novelId.isEmpty()) return emptyList()

        // Fetch full chapter list via AJAX (pagenum=-1 means all chapters)
        val formBody = FormBody.Builder()
            .add("action", "wi_getreleases_pagination")
            .add("pagenum", "-1")
            .add("mypostid", novelId)
            .build()

        val chaptersRequest = POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody)
        val chaptersResponse = client.newCall(chaptersRequest).execute()
        val chaptersHtml = chaptersResponse.body.string()

        val chaptersDoc = Jsoup.parse(chaptersHtml)

        return chaptersDoc.select(".toc_w, li.toc_w").mapNotNull { element ->
            val link = element.select("a").first() ?: return@mapNotNull null
            val chapterUrl = link.attr("href")
            val chapterName = element.select(".toc_a").first()?.text() ?: link.text()
            val dateText = element.select(".fic_date_pub").text().trim()

            SChapter.create().apply {
                name = chapterName.trim()
                url = chapterUrl.removePrefix(baseUrl)
                date_upload = parseRelativeDate(dateText)
            }
        }
    }

    private fun extractNovelId(doc: Document, urlPath: String): String {
        // Method 1: Extract from URL path (e.g., /series/1135722/novel-name/ -> 1135722)
        val pathParts = urlPath.removePrefix("/").split("/")
        if (pathParts.size >= 2) {
            val idFromPath = pathParts[1]
            if (idFromPath.all { it.isDigit() }) {
                return idFromPath
            }
        }

        // Method 2: Try multiple HTML selectors
        return listOf(
            doc.select("input[name='mypostid']").attr("value"),
            doc.select("[data-nid]").attr("data-nid"),
            doc.select("#mypostid").attr("value"),
            doc.select("input#mypostid").attr("value"),
            // Try to find it in script tags
            doc.select("script").text().let { scripts ->
                Regex("""mypostid['":\s]+(\d+)""").find(scripts)?.groupValues?.get(1) ?: ""
            },
        ).firstOrNull { it.isNotEmpty() } ?: ""
    }

    private fun parseRelativeDate(dateText: String): Long {
        if (dateText.isEmpty()) return 0L

        return try {
            val calendar = Calendar.getInstance()
            val parts = dateText.split(" ")
            if (parts.size >= 2) {
                val amount = parts[0].toInt()
                val unit = parts[1].lowercase()

                when {
                    unit.contains("second") -> calendar.add(Calendar.SECOND, -amount)
                    unit.contains("minute") -> calendar.add(Calendar.MINUTE, -amount)
                    unit.contains("hour") -> calendar.add(Calendar.HOUR, -amount)
                    unit.contains("day") -> calendar.add(Calendar.DAY_OF_MONTH, -amount)
                    unit.contains("week") -> calendar.add(Calendar.WEEK_OF_YEAR, -amount)
                    unit.contains("month") -> calendar.add(Calendar.MONTH, -amount)
                    unit.contains("year") -> calendar.add(Calendar.YEAR, -amount)
                }
            }
            calendar.timeInMillis
        } catch (e: Exception) {
            0L
        }
    }

    // Page list
    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        // Return single page with chapter URL
        return listOf(Page(0, response.request.url.toString(), null))
    }

    // Novel source implementation
    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(page.url, headers)).execute()
        val body = response.body.string()
        val doc = Jsoup.parse(body, page.url)

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

        // Return chapter content
        return doc.select("div.chp_raw").html()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Add preferences if needed
    }

    override fun imageUrlParse(response: Response) = ""

    // Filters
    override fun getFilterList() = FilterList(
        Filter.Header("NOTE: Filters are ignored if using text search!"),
        Filter.Separator(),
        SortFilter(),
        OrderFilter(),
        StatusFilter(),
        GenreFilter(),
        ContentWarningFilter(),
        GenreOperatorFilter(),
        ContentWarningOperatorFilter(),
        Filter.Header("Tags (select from list or use text inputs below)"),
        TagFilter(),
        TagOperatorFilter(),
        TagIncludeTextFilter(),
        TagExcludeTextFilter(),
    )

    private fun buildFilterUrl(page: Int, filters: FilterList): String {
        val sortFilter = filters.findInstance<SortFilter>()!!
        val orderFilter = filters.findInstance<OrderFilter>()!!
        val statusFilter = filters.findInstance<StatusFilter>()!!
        val genreFilter = filters.findInstance<GenreFilter>()!!
        val contentWarningFilter = filters.findInstance<ContentWarningFilter>()!!
        val genreOperatorFilter = filters.findInstance<GenreOperatorFilter>()!!
        val contentWarningOperatorFilter = filters.findInstance<ContentWarningOperatorFilter>()!!
        val tagFilter = filters.findInstance<TagFilter>()!!
        val tagOperatorFilter = filters.findInstance<TagOperatorFilter>()!!
        val tagIncludeTextFilter = filters.findInstance<TagIncludeTextFilter>()!!
        val tagExcludeTextFilter = filters.findInstance<TagExcludeTextFilter>()!!

        return buildString {
            append("$baseUrl/series-finder/?sf=1")

            // Add genres
            val includedGenres = genreFilter.state.filter { it.isIncluded() }.map { it.id }
            val excludedGenres = genreFilter.state.filter { it.isExcluded() }.map { it.id }
            if (includedGenres.isNotEmpty()) {
                append("&gi=").append(includedGenres.joinToString(","))
                append("&mgi=").append(genreOperatorFilter.toUriPart())
            }
            if (excludedGenres.isNotEmpty()) {
                append("&ge=").append(excludedGenres.joinToString(","))
            }

            // Add content warnings
            val includedWarnings = contentWarningFilter.state.filter { it.isIncluded() }.map { it.id }
            val excludedWarnings = contentWarningFilter.state.filter { it.isExcluded() }.map { it.id }
            if (includedWarnings.isNotEmpty()) {
                append("&cti=").append(includedWarnings.joinToString(","))
                append("&mct=").append(contentWarningOperatorFilter.toUriPart())
            }
            if (excludedWarnings.isNotEmpty()) {
                append("&cte=").append(excludedWarnings.joinToString(","))
            }

            // Add status
            if (statusFilter.state != 0) {
                append("&cp=").append(statusFilter.toUriPart())
            }

            // Add tags from list
            val includedTags = tagFilter.state.filter { it.isIncluded() }.map { it.value }
            val excludedTags = tagFilter.state.filter { it.isExcluded() }.map { it.value }

            // Add tags from text inputs (comma-separated, case-insensitive matching)
            val tagMap = tagFilter.state.associate { it.name.lowercase() to it.value }
            val includeTextTags = tagIncludeTextFilter.state.split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .mapNotNull { tagMap[it] }
            val excludeTextTags = tagExcludeTextFilter.state.split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .mapNotNull { tagMap[it] }

            val allIncludedTags = (includedTags + includeTextTags).distinct()
            val allExcludedTags = (excludedTags + excludeTextTags).distinct()

            if (allIncludedTags.isNotEmpty()) {
                append("&tgi=").append(allIncludedTags.joinToString(","))
                append("&mtgi=").append(tagOperatorFilter.toUriPart())
            }
            if (allExcludedTags.isNotEmpty()) {
                append("&tge=").append(allExcludedTags.joinToString(","))
            }

            // Add sort and order
            append("&sort=").append(sortFilter.toUriPart())
            append("&order=").append(orderFilter.toUriPart())
            append("&pg=$page")
        }
    }

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    // Filter classes
    private class SortFilter :
        Filter.Select<String>(
            "Sort Results By",
            arrayOf(
                "Ratings",
                "Chapters",
                "Chapters per Week",
                "Date Added",
                "Favorites",
                "Last Updated",
                "Number of Ratings",
                "Pages",
                "Pageviews",
                "Readers",
                "Reviews",
                "Total Words",
            ),
        ) {
        fun toUriPart() = when (state) {
            0 -> "ratings"
            1 -> "chapters"
            2 -> "frequency"
            3 -> "dateadded"
            4 -> "favorites"
            5 -> "lastchdate"
            6 -> "numofrate"
            7 -> "pages"
            8 -> "pageviews"
            9 -> "readers"
            10 -> "reviews"
            11 -> "totalwords"
            else -> "ratings"
        }
    }

    private class OrderFilter :
        Filter.Select<String>(
            "Order",
            arrayOf("Descending", "Ascending"),
        ) {
        fun toUriPart() = when (state) {
            0 -> "desc"
            1 -> "asc"
            else -> "desc"
        }
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Story Status",
            arrayOf("All", "Ongoing", "Completed", "Hiatus"),
        ) {
        fun toUriPart() = when (state) {
            1 -> "1"

            // Ongoing
            2 -> "2"

            // Completed
            3 -> "3"

            // Hiatus
            else -> "" // All
        }
    }

    private class Genre(name: String, val id: String) : Filter.TriState(name)
    private class GenreFilter :
        Filter.Group<Genre>(
            "Genres (0=ignore, 1=include, 2=exclude)",
            listOf(
                Genre("Action", "9"),
                Genre("Adult", "902"),
                Genre("Adventure", "8"),
                Genre("Boys Love", "891"),
                Genre("Comedy", "7"),
                Genre("Drama", "903"),
                Genre("Ecchi", "904"),
                Genre("Fanfiction", "38"),
                Genre("Fantasy", "19"),
                Genre("Gender Bender", "905"),
                Genre("Girls Love", "892"),
                Genre("Harem", "1015"),
                Genre("Historical", "21"),
                Genre("Horror", "22"),
                Genre("Isekai", "37"),
                Genre("Josei", "906"),
                Genre("LitRPG", "1180"),
                Genre("Martial Arts", "907"),
                Genre("Mature", "20"),
                Genre("Mecha", "908"),
                Genre("Mystery", "909"),
                Genre("Psychological", "910"),
                Genre("Romance", "6"),
                Genre("School Life", "911"),
                Genre("Sci-fi", "912"),
                Genre("Seinen", "913"),
                Genre("Slice of Life", "914"),
                Genre("Smut", "915"),
                Genre("Sports", "916"),
                Genre("Supernatural", "5"),
                Genre("Tragedy", "901"),
            ),
        )

    private class ContentWarning(name: String, val id: String) : Filter.TriState(name)
    private class ContentWarningFilter :
        Filter.Group<ContentWarning>(
            "Mature Content (0=ignore, 1=include, 2=exclude)",
            listOf(
                ContentWarning("Gore", "48"),
                ContentWarning("Sexual Content", "50"),
                ContentWarning("Strong Language", "49"),
            ),
        )

    private class GenreOperatorFilter :
        Filter.Select<String>(
            "Genres Operator",
            arrayOf("And", "Or"),
        ) {
        fun toUriPart() = when (state) {
            0 -> "and"
            1 -> "or"
            else -> "and"
        }
    }

    private class ContentWarningOperatorFilter :
        Filter.Select<String>(
            "Mature Content Operator",
            arrayOf("And", "Or"),
        ) {
        fun toUriPart() = when (state) {
            0 -> "and"
            1 -> "or"
            else -> "and"
        }
    }

    private class ExcludableCheckBox(name: String, val value: String) : Filter.TriState(name)

    private class TagFilter :
        Filter.Group<ExcludableCheckBox>(
            "Tags",
            listOf(
                ExcludableCheckBox("Abandoned Children", "119"),
                ExcludableCheckBox("Ability Steal", "120"),
                ExcludableCheckBox("Absent Parents", "121"),
                ExcludableCheckBox("Abusive Characters", "122"),
                ExcludableCheckBox("Academy", "123"),
                ExcludableCheckBox("Accelerated Growth", "124"),
                ExcludableCheckBox("Acting", "125"),
                ExcludableCheckBox("Adopted Children", "137"),
                ExcludableCheckBox("Adopted Protagonist", "138"),
                ExcludableCheckBox("Adultery", "139"),
                ExcludableCheckBox("Adventurers", "140"),
                ExcludableCheckBox("Affair", "141"),
                ExcludableCheckBox("Age Progression", "142"),
                ExcludableCheckBox("Age Regression", "143"),
                ExcludableCheckBox("Aggressive Characters", "144"),
                ExcludableCheckBox("Alchemy", "145"),
                ExcludableCheckBox("Aliens", "146"),
                ExcludableCheckBox("All-Girls School", "147"),
                ExcludableCheckBox("Alternate World", "148"),
                ExcludableCheckBox("Amnesia", "149"),
                ExcludableCheckBox("Amusement Park", "150"),
                ExcludableCheckBox("Ancient China", "152"),
                ExcludableCheckBox("Ancient Times", "153"),
                ExcludableCheckBox("Androgynous Characters", "154"),
                ExcludableCheckBox("Androids", "155"),
                ExcludableCheckBox("Angels", "156"),
                ExcludableCheckBox("Animal Characteristics", "157"),
                ExcludableCheckBox("Animal Rearing", "158"),
                ExcludableCheckBox("Anti-Magic", "159"),
                ExcludableCheckBox("Anti-social Protagonist", "160"),
                ExcludableCheckBox("Antihero Protagonist", "161"),
                ExcludableCheckBox("Antique Shop", "162"),
                ExcludableCheckBox("Apartment Life", "163"),
                ExcludableCheckBox("Apathetic Protagonist", "164"),
                ExcludableCheckBox("Apocalypse", "165"),
                ExcludableCheckBox("Appearance Changes", "166"),
                ExcludableCheckBox("Appearance Different from Actual Age", "167"),
                ExcludableCheckBox("Archery", "168"),
                ExcludableCheckBox("Aristocracy", "169"),
                ExcludableCheckBox("Arms Dealers", "170"),
                ExcludableCheckBox("Army", "171"),
                ExcludableCheckBox("Army Building", "172"),
                ExcludableCheckBox("Arranged Marriage", "173"),
                ExcludableCheckBox("Arrogant Characters", "174"),
                ExcludableCheckBox("Artifact Crafting", "175"),
                ExcludableCheckBox("Artifacts", "176"),
                ExcludableCheckBox("Artificial Intelligence", "177"),
                ExcludableCheckBox("Artists", "178"),
                ExcludableCheckBox("Assassins", "179"),
                ExcludableCheckBox("Astrologers", "180"),
                ExcludableCheckBox("Autism", "181"),
                ExcludableCheckBox("Automatons", "182"),
                ExcludableCheckBox("Average-looking Protagonist", "183"),
                ExcludableCheckBox("Awkward Protagonist", "185"),
                ExcludableCheckBox("Bands", "186"),
                ExcludableCheckBox("Based on a Movie", "187"),
                ExcludableCheckBox("Based on a Song", "188"),
                ExcludableCheckBox("Based on a Video Game", "190"),
                ExcludableCheckBox("Based on a Visual Novel", "191"),
                ExcludableCheckBox("Based on an Anime", "192"),
                ExcludableCheckBox("Battle Academy", "193"),
                ExcludableCheckBox("Battle Competition", "194"),
                ExcludableCheckBox("BDSM", "195"),
                ExcludableCheckBox("Beast Companions", "196"),
                ExcludableCheckBox("Beastkin", "197"),
                ExcludableCheckBox("Beasts", "198"),
                ExcludableCheckBox("Beautiful Couple", "199"),
                ExcludableCheckBox("Beautiful Female Lead", "200"),
                ExcludableCheckBox("Betrayal", "202"),
                ExcludableCheckBox("Bickering Couple", "203"),
                ExcludableCheckBox("Biochip", "204"),
                ExcludableCheckBox("Biography", "205"),
                ExcludableCheckBox("Bisexual Protagonist", "206"),
                ExcludableCheckBox("Black Belly", "207"),
                ExcludableCheckBox("Blackmail", "208"),
                ExcludableCheckBox("Blacksmith", "209"),
                ExcludableCheckBox("Blind Dates", "210"),
                ExcludableCheckBox("Blind Protagonist", "211"),
                ExcludableCheckBox("Blood Manipulation", "212"),
                ExcludableCheckBox("Bloodlines", "213"),
                ExcludableCheckBox("Body Swap", "214"),
                ExcludableCheckBox("Body Tempering", "215"),
                ExcludableCheckBox("Body-double", "216"),
                ExcludableCheckBox("Bodyguards", "217"),
                ExcludableCheckBox("Books", "218"),
                ExcludableCheckBox("Bookworm", "219"),
                ExcludableCheckBox("Boss-Subordinate Relationship", "220"),
                ExcludableCheckBox("Boy's Love Subplot", "760"),
                ExcludableCheckBox("Brainwashing", "221"),
                ExcludableCheckBox("Broken Engagement", "223"),
                ExcludableCheckBox("Brother Complex", "224"),
                ExcludableCheckBox("Brotherhood", "225"),
                ExcludableCheckBox("Buddhism", "226"),
                ExcludableCheckBox("Bullying", "227"),
                ExcludableCheckBox("Business Management", "228"),
                ExcludableCheckBox("Businessmen", "229"),
                ExcludableCheckBox("Butlers", "230"),
                ExcludableCheckBox("Calm Protagonist", "231"),
                ExcludableCheckBox("Cannibalism", "232"),
                ExcludableCheckBox("Card Games", "233"),
                ExcludableCheckBox("Carefree Protagonist", "234"),
                ExcludableCheckBox("Caring Protagonist", "235"),
                ExcludableCheckBox("Cautious Protagonist", "236"),
                ExcludableCheckBox("Celebrities", "237"),
                ExcludableCheckBox("Character Growth", "238"),
                ExcludableCheckBox("Charismatic Protagonist", "239"),
                ExcludableCheckBox("Charming Protagonist", "240"),
                ExcludableCheckBox("Chat Rooms", "241"),
                ExcludableCheckBox("Cheating", "20892"),
                ExcludableCheckBox("Cheats", "242"),
                ExcludableCheckBox("Chefs", "243"),
                ExcludableCheckBox("Child Abuse", "244"),
                ExcludableCheckBox("Child Protagonist", "245"),
                ExcludableCheckBox("Childcare", "246"),
                ExcludableCheckBox("Childhood Friends", "247"),
                ExcludableCheckBox("Childhood Love", "248"),
                ExcludableCheckBox("Childhood Promise", "249"),
                ExcludableCheckBox("Childish Protagonist", "250"),
                ExcludableCheckBox("Chuunibyou", "251"),
                ExcludableCheckBox("Clan Building", "252"),
                ExcludableCheckBox("Classic", "253"),
                ExcludableCheckBox("Clever Protagonist", "254"),
                ExcludableCheckBox("Clingy Lover", "255"),
                ExcludableCheckBox("Clones", "256"),
                ExcludableCheckBox("Clubs", "257"),
                ExcludableCheckBox("Clumsy Love Interests", "258"),
                ExcludableCheckBox("Co-Workers", "259"),
                ExcludableCheckBox("Cohabitation", "260"),
                ExcludableCheckBox("Cold Love Interests", "261"),
                ExcludableCheckBox("Cold Protagonist", "262"),
                ExcludableCheckBox("Collection of Short Stories", "263"),
                ExcludableCheckBox("College/University", "264"),
                ExcludableCheckBox("Coma", "265"),
                ExcludableCheckBox("Comedic Undertone", "266"),
                ExcludableCheckBox("Coming of Age", "267"),
                ExcludableCheckBox("Complex Family Relationships", "268"),
                ExcludableCheckBox("Conditional Power", "269"),
                ExcludableCheckBox("Confident Protagonist", "270"),
                ExcludableCheckBox("Confinement", "271"),
                ExcludableCheckBox("Conflicting Loyalties", "272"),
                ExcludableCheckBox("Conspiracies", "273"),
                ExcludableCheckBox("Contracts", "274"),
                ExcludableCheckBox("Cooking", "275"),
                ExcludableCheckBox("Corruption", "276"),
                ExcludableCheckBox("Cosmic Wars", "277"),
                ExcludableCheckBox("Cosplay", "278"),
                ExcludableCheckBox("Couple Growth", "279"),
                ExcludableCheckBox("Court Official", "280"),
                ExcludableCheckBox("Cousins", "281"),
                ExcludableCheckBox("Cowardly Protagonist", "282"),
                ExcludableCheckBox("Crafting", "283"),
                ExcludableCheckBox("Crazy Protagonist", "1534"),
                ExcludableCheckBox("Crime", "284"),
                ExcludableCheckBox("Criminals", "285"),
                ExcludableCheckBox("Cross-dressing", "286"),
                ExcludableCheckBox("Crossover", "287"),
                ExcludableCheckBox("Cruel Characters", "288"),
                ExcludableCheckBox("Cryostasis", "289"),
                ExcludableCheckBox("Cultivation", "290"),
                ExcludableCheckBox("Cunning Protagonist", "292"),
                ExcludableCheckBox("Curious Protagonist", "293"),
                ExcludableCheckBox("Curses", "294"),
                ExcludableCheckBox("Cute Children", "295"),
                ExcludableCheckBox("Cute Protagonist", "296"),
                ExcludableCheckBox("Cute Story", "297"),
                ExcludableCheckBox("Cyberpunk", "960"),
                ExcludableCheckBox("Dancers", "298"),
                ExcludableCheckBox("Dao Companion", "299"),
                ExcludableCheckBox("Dao Comprehension", "300"),
                ExcludableCheckBox("Daoism", "301"),
                ExcludableCheckBox("Dark", "302"),
                ExcludableCheckBox("Dead Protagonist", "303"),
                ExcludableCheckBox("Death", "304"),
                ExcludableCheckBox("Death of Loved Ones", "305"),
                ExcludableCheckBox("Debts", "306"),
                ExcludableCheckBox("Delinquents", "307"),
                ExcludableCheckBox("Delusions", "308"),
                ExcludableCheckBox("Demi-Humans", "309"),
                ExcludableCheckBox("Demon Lord", "310"),
                ExcludableCheckBox("Demonic Cultivation Technique", "311"),
                ExcludableCheckBox("Demons", "312"),
                ExcludableCheckBox("Dense Protagonist", "313"),
                ExcludableCheckBox("Depictions of Cruelty", "314"),
                ExcludableCheckBox("Depression", "315"),
                ExcludableCheckBox("Destiny", "316"),
                ExcludableCheckBox("Detectives", "317"),
                ExcludableCheckBox("Determined Protagonist", "318"),
                ExcludableCheckBox("Devoted Love Interests", "319"),
                ExcludableCheckBox("Different Social Status", "320"),
                ExcludableCheckBox("Disabilities", "321"),
                ExcludableCheckBox("Discrimination", "322"),
                ExcludableCheckBox("Disfigurement", "323"),
                ExcludableCheckBox("Dishonest Protagonist", "324"),
                ExcludableCheckBox("Distrustful Protagonist", "325"),
                ExcludableCheckBox("Divination", "326"),
                ExcludableCheckBox("Divine Protection", "327"),
                ExcludableCheckBox("Divorce", "328"),
                ExcludableCheckBox("Doctors", "329"),
                ExcludableCheckBox("Dolls/Puppets", "330"),
                ExcludableCheckBox("Domestic Affairs", "331"),
                ExcludableCheckBox("Doting Love Interests", "332"),
                ExcludableCheckBox("Doting Older Siblings", "333"),
                ExcludableCheckBox("Doting Parents", "334"),
                ExcludableCheckBox("Dragon Riders", "335"),
                ExcludableCheckBox("Dragon Slayers", "336"),
                ExcludableCheckBox("Dragons", "337"),
                ExcludableCheckBox("Dreams", "338"),
                ExcludableCheckBox("Drugs", "339"),
                ExcludableCheckBox("Druids", "340"),
                ExcludableCheckBox("Dungeon Master", "341"),
                ExcludableCheckBox("Dungeons", "342"),
                ExcludableCheckBox("Dwarfs", "343"),
                ExcludableCheckBox("Dystopia", "344"),
                ExcludableCheckBox("e-Sports", "345"),
                ExcludableCheckBox("Early Romance", "346"),
                ExcludableCheckBox("Earth Invasion", "347"),
                ExcludableCheckBox("Easy Going Life", "348"),
                ExcludableCheckBox("Economics", "349"),
                ExcludableCheckBox("Editors", "350"),
                ExcludableCheckBox("Eidetic Memory", "351"),
                ExcludableCheckBox("Elderly Protagonist", "352"),
                ExcludableCheckBox("Elemental Magic", "353"),
                ExcludableCheckBox("Elves", "354"),
                ExcludableCheckBox("Emotionally Weak Protagonist", "355"),
                ExcludableCheckBox("Empires", "356"),
                ExcludableCheckBox("Enemies Become Allies", "357"),
                ExcludableCheckBox("Enemies Become Lovers", "358"),
                ExcludableCheckBox("Engagement", "359"),
                ExcludableCheckBox("Engineer", "360"),
                ExcludableCheckBox("Enlightenment", "361"),
                ExcludableCheckBox("Episodic", "362"),
                ExcludableCheckBox("Eunuch", "363"),
                ExcludableCheckBox("European Ambience", "364"),
                ExcludableCheckBox("Evil Gods", "365"),
                ExcludableCheckBox("Evil Organizations", "366"),
                ExcludableCheckBox("Evil Protagonist", "367"),
                ExcludableCheckBox("Evil Religions", "368"),
                ExcludableCheckBox("Evolution", "369"),
                ExcludableCheckBox("Exhibitionism", "370"),
                ExcludableCheckBox("Exorcism", "371"),
                ExcludableCheckBox("Eye Powers", "372"),
                ExcludableCheckBox("Fairies", "373"),
                ExcludableCheckBox("Fallen Angels", "374"),
                ExcludableCheckBox("Fallen Nobility", "375"),
                ExcludableCheckBox("Familial Love", "376"),
                ExcludableCheckBox("Familiars", "377"),
                ExcludableCheckBox("Family", "378"),
                ExcludableCheckBox("Family Business", "379"),
                ExcludableCheckBox("Family Conflict", "380"),
                ExcludableCheckBox("Famous Parents", "381"),
                ExcludableCheckBox("Famous Protagonist", "382"),
                ExcludableCheckBox("Fanaticism", "383"),
                ExcludableCheckBox("Fantasy Creatures", "385"),
                ExcludableCheckBox("Fantasy World", "386"),
                ExcludableCheckBox("Farming", "387"),
                ExcludableCheckBox("Fast Cultivation", "388"),
                ExcludableCheckBox("Fast Learner", "389"),
                ExcludableCheckBox("Fat Protagonist", "390"),
                ExcludableCheckBox("Fat to Fit", "391"),
                ExcludableCheckBox("Fated Lovers", "392"),
                ExcludableCheckBox("Fearless Protagonist", "393"),
                ExcludableCheckBox("Female Master", "395"),
                ExcludableCheckBox("Female Protagonist", "396"),
                ExcludableCheckBox("Female to Male", "397"),
                ExcludableCheckBox("Feng Shui", "398"),
                ExcludableCheckBox("Firearms", "399"),
                ExcludableCheckBox("First Love", "400"),
                ExcludableCheckBox("First-time Intercourse", "401"),
                ExcludableCheckBox("Flashbacks", "402"),
                ExcludableCheckBox("Fleet Battles", "403"),
                ExcludableCheckBox("Folklore", "404"),
                ExcludableCheckBox("Forced into a Relationship", "405"),
                ExcludableCheckBox("Forced Living Arrangements", "406"),
                ExcludableCheckBox("Forced Marriage", "407"),
                ExcludableCheckBox("Forgetful Protagonist", "408"),
                ExcludableCheckBox("Former Hero", "409"),
                ExcludableCheckBox("Fourth Wall", "1150"),
                ExcludableCheckBox("Fox Spirits", "410"),
                ExcludableCheckBox("Friends Become Enemies", "411"),
                ExcludableCheckBox("Friendship", "412"),
                ExcludableCheckBox("Fujoshi", "413"),
                ExcludableCheckBox("Futanari", "414"),
                ExcludableCheckBox("Futuristic Setting", "415"),
                ExcludableCheckBox("Galge", "416"),
                ExcludableCheckBox("Gambling", "417"),
                ExcludableCheckBox("Game Elements", "418"),
                ExcludableCheckBox("Game Ranking System", "419"),
                ExcludableCheckBox("Gamers", "420"),
                ExcludableCheckBox("Gangs", "421"),
                ExcludableCheckBox("Gate to Another World", "422"),
                ExcludableCheckBox("Genderless Protagonist", "423"),
                ExcludableCheckBox("Generals", "424"),
                ExcludableCheckBox("Genetic Modifications", "425"),
                ExcludableCheckBox("Genies", "426"),
                ExcludableCheckBox("Genius Protagonist", "427"),
                ExcludableCheckBox("Ghosts", "428"),
                ExcludableCheckBox("Girl's Love Subplot", "759"),
                ExcludableCheckBox("Gladiators", "429"),
                ExcludableCheckBox("Glasses-wearing Love Interests", "430"),
                ExcludableCheckBox("Glasses-wearing Protagonist", "431"),
                ExcludableCheckBox("Goblins", "432"),
                ExcludableCheckBox("God Protagonist", "433"),
                ExcludableCheckBox("God-human Relationship", "434"),
                ExcludableCheckBox("Goddesses", "435"),
                ExcludableCheckBox("Godly Powers", "436"),
                ExcludableCheckBox("Gods", "437"),
                ExcludableCheckBox("Golems", "438"),
                ExcludableCheckBox("Gore", "439"),
                ExcludableCheckBox("Grave Keepers", "440"),
                ExcludableCheckBox("Grinding", "441"),
                ExcludableCheckBox("Guardian Relationship", "442"),
                ExcludableCheckBox("Guilds", "443"),
                ExcludableCheckBox("Gunfighters", "444"),
                ExcludableCheckBox("Hackers", "445"),
                ExcludableCheckBox("Half-human Protagonist", "446"),
                ExcludableCheckBox("Handjob", "447"),
                ExcludableCheckBox("Handsome Male Lead", "448"),
                ExcludableCheckBox("Hard-Working Protagonist", "449"),
                ExcludableCheckBox("Harem-seeking Protagonist", "450"),
                ExcludableCheckBox("Harsh Training", "451"),
                ExcludableCheckBox("Hated Protagonist", "452"),
                ExcludableCheckBox("Healers", "453"),
                ExcludableCheckBox("Healing", "942"),
                ExcludableCheckBox("Heartwarming", "454"),
                ExcludableCheckBox("Heaven", "455"),
                ExcludableCheckBox("Heavenly Tribulation", "456"),
                ExcludableCheckBox("Hell", "457"),
                ExcludableCheckBox("Helpful Protagonist", "458"),
                ExcludableCheckBox("Herbalist", "459"),
                ExcludableCheckBox("Heroes", "460"),
                ExcludableCheckBox("Heterochromia", "461"),
                ExcludableCheckBox("Hidden Abilities", "462"),
                ExcludableCheckBox("Hiding True Abilities", "463"),
                ExcludableCheckBox("Hiding True Identity", "464"),
                ExcludableCheckBox("Hikikomori", "465"),
                ExcludableCheckBox("Homunculus", "466"),
                ExcludableCheckBox("Honest Protagonist", "467"),
                ExcludableCheckBox("Hospital", "468"),
                ExcludableCheckBox("Hot-blooded Protagonist", "469"),
                ExcludableCheckBox("Human Experimentation", "470"),
                ExcludableCheckBox("Human Weapon", "471"),
                ExcludableCheckBox("Human-Nonhuman Relationship", "472"),
                ExcludableCheckBox("Humanoid Protagonist", "473"),
                ExcludableCheckBox("Hunters", "474"),
                ExcludableCheckBox("Hypnotism", "475"),
                ExcludableCheckBox("Identity Crisis", "476"),
                ExcludableCheckBox("Imaginary Friend", "477"),
                ExcludableCheckBox("Immortals", "478"),
                ExcludableCheckBox("Imperial Harem", "479"),
                ExcludableCheckBox("Incest", "480"),
                ExcludableCheckBox("Incubus", "481"),
                ExcludableCheckBox("Indecisive Protagonist", "482"),
                ExcludableCheckBox("Industrialization", "483"),
                ExcludableCheckBox("Inferiority Complex", "484"),
                ExcludableCheckBox("Inheritance", "485"),
                ExcludableCheckBox("Inscriptions", "486"),
                ExcludableCheckBox("Insects", "487"),
                ExcludableCheckBox("Interconnected Storylines", "488"),
                ExcludableCheckBox("Interdimensional Travel", "489"),
                ExcludableCheckBox("Introverted Protagonist", "490"),
                ExcludableCheckBox("Investigations", "491"),
                ExcludableCheckBox("Invisibility", "492"),
                ExcludableCheckBox("Jack of All Trades", "493"),
                ExcludableCheckBox("Jealousy", "494"),
                ExcludableCheckBox("Jiangshi", "495"),
                ExcludableCheckBox("Jobless Class", "496"),
                ExcludableCheckBox("Kidnappings", "498"),
                ExcludableCheckBox("Kind Love Interests", "499"),
                ExcludableCheckBox("Kingdom Building", "500"),
                ExcludableCheckBox("Kingdoms", "501"),
                ExcludableCheckBox("Knights", "502"),
                ExcludableCheckBox("Kuudere", "503"),
                ExcludableCheckBox("Lack of Common Sense", "504"),
                ExcludableCheckBox("Language Barrier", "505"),
                ExcludableCheckBox("Late Romance", "506"),
                ExcludableCheckBox("Lawyers", "507"),
                ExcludableCheckBox("Lazy Protagonist", "508"),
                ExcludableCheckBox("Leadership", "509"),
                ExcludableCheckBox("Legends", "510"),
                ExcludableCheckBox("Level System", "511"),
                ExcludableCheckBox("Library", "512"),
                ExcludableCheckBox("Limited Lifespan", "513"),
                ExcludableCheckBox("Living Abroad", "514"),
                ExcludableCheckBox("Living Alone", "515"),
                ExcludableCheckBox("Loli", "516"),
                ExcludableCheckBox("Loneliness", "517"),
                ExcludableCheckBox("Loner Protagonist", "518"),
                ExcludableCheckBox("Long Separations", "519"),
                ExcludableCheckBox("Long-distance Relationship", "520"),
                ExcludableCheckBox("Lost Civilizations", "521"),
                ExcludableCheckBox("Lottery", "522"),
                ExcludableCheckBox("Love at First Sight", "523"),
                ExcludableCheckBox("Love Interest Falls in Love First", "524"),
                ExcludableCheckBox("Love Rivals", "525"),
                ExcludableCheckBox("Love Triangles", "526"),
                ExcludableCheckBox("Lovers Reunited", "527"),
                ExcludableCheckBox("Low-key Protagonist", "528"),
                ExcludableCheckBox("Loyal Subordinates", "529"),
                ExcludableCheckBox("Lucky Protagonist", "530"),
                ExcludableCheckBox("Magic", "531"),
                ExcludableCheckBox("Magic Beasts", "532"),
                ExcludableCheckBox("Magic Formations", "533"),
                ExcludableCheckBox("Magical Girls", "534"),
                ExcludableCheckBox("Magical Space", "535"),
                ExcludableCheckBox("Magical Technology", "536"),
                ExcludableCheckBox("Maids", "537"),
                ExcludableCheckBox("Male Protagonist", "538"),
                ExcludableCheckBox("Male to Female", "539"),
                ExcludableCheckBox("Male Yandere", "540"),
                ExcludableCheckBox("Management", "541"),
                ExcludableCheckBox("Mangaka", "542"),
                ExcludableCheckBox("Manipulative Characters", "543"),
                ExcludableCheckBox("Manly Gay Couple", "544"),
                ExcludableCheckBox("Marriage", "545"),
                ExcludableCheckBox("Marriage of Convenience", "546"),
                ExcludableCheckBox("Martial Spirits", "547"),
                ExcludableCheckBox("Masochistic Characters", "548"),
                ExcludableCheckBox("Master-Disciple Relationship", "549"),
                ExcludableCheckBox("Master-Servant Relationship", "550"),
                ExcludableCheckBox("Masturbation", "551"),
                ExcludableCheckBox("Matriarchy", "552"),
                ExcludableCheckBox("Mature Protagonist", "553"),
                ExcludableCheckBox("Medical Knowledge", "554"),
                ExcludableCheckBox("Medieval", "555"),
                ExcludableCheckBox("Mercenaries", "556"),
                ExcludableCheckBox("Merchants", "557"),
                ExcludableCheckBox("Military", "558"),
                ExcludableCheckBox("Mind Break", "559"),
                ExcludableCheckBox("Mind Control", "560"),
                ExcludableCheckBox("Misandry", "561"),
                ExcludableCheckBox("Mismatched Couple", "562"),
                ExcludableCheckBox("Misunderstandings", "563"),
                ExcludableCheckBox("MMORPG", "564"),
                ExcludableCheckBox("Mob Protagonist", "565"),
                ExcludableCheckBox("Models", "566"),
                ExcludableCheckBox("Modern Day", "567"),
                ExcludableCheckBox("Modern Fantasy", "1268"),
                ExcludableCheckBox("Modern Knowledge", "568"),
                ExcludableCheckBox("Modern Time", "1536"),
                ExcludableCheckBox("Money Grubber", "569"),
                ExcludableCheckBox("Monster Girls", "570"),
                ExcludableCheckBox("Monster Society", "571"),
                ExcludableCheckBox("Monster Tamer", "572"),
                ExcludableCheckBox("Monsters", "573"),
                ExcludableCheckBox("Movies", "574"),
                ExcludableCheckBox("Mpreg", "575"),
                ExcludableCheckBox("Multiple Identities", "576"),
                ExcludableCheckBox("Multiple Personalities", "577"),
                ExcludableCheckBox("Multiple POV", "578"),
                ExcludableCheckBox("Multiple Protagonists", "579"),
                ExcludableCheckBox("Multiple Realms", "580"),
                ExcludableCheckBox("Multiple Reincarnated Individuals", "581"),
                ExcludableCheckBox("Multiple Timelines", "582"),
                ExcludableCheckBox("Multiple Transported Individuals", "583"),
                ExcludableCheckBox("Murders", "584"),
                ExcludableCheckBox("Music", "585"),
                ExcludableCheckBox("Mutated Creatures", "586"),
                ExcludableCheckBox("Mutations", "587"),
                ExcludableCheckBox("Mute Character", "588"),
                ExcludableCheckBox("Mysterious Family Background", "589"),
                ExcludableCheckBox("Mysterious Illness", "590"),
                ExcludableCheckBox("Mysterious Past", "591"),
                ExcludableCheckBox("Mystery Solving", "592"),
                ExcludableCheckBox("Mythical Beasts", "593"),
                ExcludableCheckBox("Mythology", "594"),
                ExcludableCheckBox("Naive Protagonist", "595"),
                ExcludableCheckBox("Narcissistic Protagonist", "596"),
                ExcludableCheckBox("Nationalism", "597"),
                ExcludableCheckBox("Near-Death Experience", "598"),
                ExcludableCheckBox("Necromancer", "599"),
                ExcludableCheckBox("Neet", "600"),
                ExcludableCheckBox("Netorare", "601"),
                ExcludableCheckBox("Netorase", "602"),
                ExcludableCheckBox("Netori", "603"),
                ExcludableCheckBox("Nightmares", "604"),
                ExcludableCheckBox("Ninjas", "605"),
                ExcludableCheckBox("Nobles", "606"),
                ExcludableCheckBox("Non-human Protagonist", "1428"),
                ExcludableCheckBox("Non-humanoid Protagonist", "607"),
                ExcludableCheckBox("Non-linear Storytelling", "608"),
                ExcludableCheckBox("Nudity", "609"),
                ExcludableCheckBox("Nurses", "610"),
                ExcludableCheckBox("Obsessive Love", "611"),
                ExcludableCheckBox("Office Romance", "612"),
                ExcludableCheckBox("Older Love Interests", "613"),
                ExcludableCheckBox("Omegaverse", "614"),
                ExcludableCheckBox("Oneshot", "615"),
                ExcludableCheckBox("Online Romance", "616"),
                ExcludableCheckBox("Onmyouji", "617"),
                ExcludableCheckBox("Orcs", "618"),
                ExcludableCheckBox("Organized Crime", "619"),
                ExcludableCheckBox("Orphans", "621"),
                ExcludableCheckBox("Otaku", "622"),
                ExcludableCheckBox("Otome Game", "623"),
                ExcludableCheckBox("Outcasts", "624"),
                ExcludableCheckBox("Outdoor Intercourse", "625"),
                ExcludableCheckBox("Outer Space", "626"),
                ExcludableCheckBox("Overpowered Protagonist", "627"),
                ExcludableCheckBox("Overprotective Siblings", "628"),
                ExcludableCheckBox("Pacifist Protagonist", "629"),
                ExcludableCheckBox("Paizuri", "630"),
                ExcludableCheckBox("Pansexual Protagonist", "1037"),
                ExcludableCheckBox("Parallel Worlds", "631"),
                ExcludableCheckBox("Parasites", "632"),
                ExcludableCheckBox("Parent Complex", "633"),
                ExcludableCheckBox("Parody", "634"),
                ExcludableCheckBox("Part-Time Job", "635"),
                ExcludableCheckBox("Past Plays a Big Role", "636"),
                ExcludableCheckBox("Past Trauma", "637"),
                ExcludableCheckBox("Persistent Love Interests", "638"),
                ExcludableCheckBox("Personality Changes", "639"),
                ExcludableCheckBox("Perverted Protagonist", "640"),
                ExcludableCheckBox("Pets", "641"),
                ExcludableCheckBox("Pharmacist", "642"),
                ExcludableCheckBox("Philosophical", "643"),
                ExcludableCheckBox("Phobias", "644"),
                ExcludableCheckBox("Phoenixes", "645"),
                ExcludableCheckBox("Photography", "646"),
                ExcludableCheckBox("Pill Based Cultivation", "647"),
                ExcludableCheckBox("Pill Concocting", "648"),
                ExcludableCheckBox("Pilots", "649"),
                ExcludableCheckBox("Pirates", "650"),
                ExcludableCheckBox("Playboys", "651"),
                ExcludableCheckBox("Playful Protagonist", "652"),
                ExcludableCheckBox("Poetry", "653"),
                ExcludableCheckBox("Poisons", "654"),
                ExcludableCheckBox("Police", "655"),
                ExcludableCheckBox("Polite Protagonist", "656"),
                ExcludableCheckBox("Politics", "657"),
                ExcludableCheckBox("Polyandry", "658"),
                ExcludableCheckBox("Polygamy", "659"),
                ExcludableCheckBox("Poor Protagonist", "660"),
                ExcludableCheckBox("Poor to Rich", "661"),
                ExcludableCheckBox("Popular Love Interests", "662"),
                ExcludableCheckBox("Possession", "663"),
                ExcludableCheckBox("Possessive Characters", "664"),
                ExcludableCheckBox("Post-apocalyptic", "665"),
                ExcludableCheckBox("Power Couple", "666"),
                ExcludableCheckBox("Power Struggle", "667"),
                ExcludableCheckBox("Pragmatic Protagonist", "668"),
                ExcludableCheckBox("Precognition", "669"),
                ExcludableCheckBox("Pregnancy", "670"),
                ExcludableCheckBox("Pretend Lovers", "671"),
                ExcludableCheckBox("Previous Life Talent", "672"),
                ExcludableCheckBox("Priestesses", "673"),
                ExcludableCheckBox("Priests", "674"),
                ExcludableCheckBox("Prison", "675"),
                ExcludableCheckBox("Proactive Protagonist", "676"),
                ExcludableCheckBox("Programmer", "677"),
                ExcludableCheckBox("Prophecies", "678"),
                ExcludableCheckBox("Prostitutes", "679"),
                ExcludableCheckBox("Protagonist Falls in Love First", "680"),
                ExcludableCheckBox("Protagonist Loyal to Love Interest", "681"),
                ExcludableCheckBox("Protagonist Strong from the Start", "682"),
                ExcludableCheckBox("Protagonist with Multiple Bodies", "683"),
                ExcludableCheckBox("Psychic Powers", "684"),
                ExcludableCheckBox("Psychopaths", "685"),
                ExcludableCheckBox("Puppeteers", "686"),
                ExcludableCheckBox("Quiet Characters", "687"),
                ExcludableCheckBox("Quirky Characters", "688"),
                ExcludableCheckBox("R-15", "689"),
                ExcludableCheckBox("R-18", "690"),
                ExcludableCheckBox("Race Change", "691"),
                ExcludableCheckBox("Racism", "692"),
                ExcludableCheckBox("Rape", "693"),
                ExcludableCheckBox("Rebellion", "695"),
                ExcludableCheckBox("Reincarnated as a Monster", "696"),
                ExcludableCheckBox("Reincarnated as an Object", "697"),
                ExcludableCheckBox("Reincarnated into a Game World", "698"),
                ExcludableCheckBox("Reincarnated into Another World", "699"),
                ExcludableCheckBox("Reincarnation", "700"),
                ExcludableCheckBox("Religions", "701"),
                ExcludableCheckBox("Reluctant Protagonist", "702"),
                ExcludableCheckBox("Reporters", "703"),
                ExcludableCheckBox("Restaurant", "704"),
                ExcludableCheckBox("Resurrection", "705"),
                ExcludableCheckBox("Returning from Another World", "706"),
                ExcludableCheckBox("Revenge", "707"),
                ExcludableCheckBox("Reverse Harem", "708"),
                ExcludableCheckBox("Reverse Rape", "709"),
                ExcludableCheckBox("Rich to Poor", "710"),
                ExcludableCheckBox("Righteous Protagonist", "711"),
                ExcludableCheckBox("Rivalry", "712"),
                ExcludableCheckBox("Romantic Subplot", "713"),
                ExcludableCheckBox("Roommates", "714"),
                ExcludableCheckBox("Royalty", "715"),
                ExcludableCheckBox("RPG", "1089"),
                ExcludableCheckBox("Ruthless Protagonist", "716"),
                ExcludableCheckBox("Sadistic Characters", "717"),
                ExcludableCheckBox("Saints", "718"),
                ExcludableCheckBox("Salaryman", "719"),
                ExcludableCheckBox("Samurai", "720"),
                ExcludableCheckBox("Satire", "1976"),
                ExcludableCheckBox("Saving the World", "721"),
                ExcludableCheckBox("Scheming", "722"),
                ExcludableCheckBox("Schizophrenia", "723"),
                ExcludableCheckBox("Scientists", "724"),
                ExcludableCheckBox("Sculptors", "725"),
                ExcludableCheckBox("Sealed Power", "726"),
                ExcludableCheckBox("Second Chance", "727"),
                ExcludableCheckBox("Secret Crush", "728"),
                ExcludableCheckBox("Secret Identity", "729"),
                ExcludableCheckBox("Secret Organizations", "730"),
                ExcludableCheckBox("Secret Relationship", "731"),
                ExcludableCheckBox("Secretive Protagonist", "732"),
                ExcludableCheckBox("Secrets", "733"),
                ExcludableCheckBox("Sect Development", "734"),
                ExcludableCheckBox("Seduction", "735"),
                ExcludableCheckBox("Seeing Things Other Humans Can't", "736"),
                ExcludableCheckBox("Selfish Protagonist", "737"),
                ExcludableCheckBox("Selfless Protagonist", "738"),
                ExcludableCheckBox("Seme Protagonist", "739"),
                ExcludableCheckBox("Senpai-Kouhai Relationship", "740"),
                ExcludableCheckBox("Sentient Objects", "741"),
                ExcludableCheckBox("Sentimental Protagonist", "742"),
                ExcludableCheckBox("Serial Killers", "743"),
                ExcludableCheckBox("Servants", "744"),
                ExcludableCheckBox("Seven Deadly Sins", "745"),
                ExcludableCheckBox("Seven Virtues", "746"),
                ExcludableCheckBox("Sex Friends", "747"),
                ExcludableCheckBox("Sexual Abuse", "749"),
                ExcludableCheckBox("Sexual Cultivation Technique", "750"),
                ExcludableCheckBox("Shameless Protagonist", "751"),
                ExcludableCheckBox("Shapeshifters", "752"),
                ExcludableCheckBox("Sharing A Body", "753"),
                ExcludableCheckBox("Sharp-tongued Characters", "754"),
                ExcludableCheckBox("Shield User", "755"),
                ExcludableCheckBox("Shikigami", "756"),
                ExcludableCheckBox("Short Story", "757"),
                ExcludableCheckBox("Shota", "758"),
                ExcludableCheckBox("Showbiz", "761"),
                ExcludableCheckBox("Shy Characters", "762"),
                ExcludableCheckBox("Sibling Rivalry", "763"),
                ExcludableCheckBox("Siblings", "765"),
                ExcludableCheckBox("Siblings Care", "764"),
                ExcludableCheckBox("Siblings Not Related by Blood", "766"),
                ExcludableCheckBox("Sickly Characters", "767"),
                ExcludableCheckBox("Sign Language", "768"),
                ExcludableCheckBox("Singers", "769"),
                ExcludableCheckBox("Single Parent", "770"),
                ExcludableCheckBox("Sister Complex", "771"),
                ExcludableCheckBox("Skill Assimilation", "772"),
                ExcludableCheckBox("Skill Books", "773"),
                ExcludableCheckBox("Skill Creation", "774"),
                ExcludableCheckBox("Slave Harem", "775"),
                ExcludableCheckBox("Slave Protagonist", "776"),
                ExcludableCheckBox("Slaves", "777"),
                ExcludableCheckBox("Sleeping", "778"),
                ExcludableCheckBox("Slow Growth at Start", "779"),
                ExcludableCheckBox("Slow Romance", "780"),
                ExcludableCheckBox("Smart Couple", "781"),
                ExcludableCheckBox("Social Outcasts", "782"),
                ExcludableCheckBox("Soldiers", "783"),
                ExcludableCheckBox("Soul Power", "784"),
                ExcludableCheckBox("Souls", "785"),
                ExcludableCheckBox("Spatial Manipulation", "786"),
                ExcludableCheckBox("Spear Wielder", "787"),
                ExcludableCheckBox("Special Abilities", "788"),
                ExcludableCheckBox("Special Forces", "497"),
                ExcludableCheckBox("Spies", "789"),
                ExcludableCheckBox("Spirit Advisor", "790"),
                ExcludableCheckBox("Spirit Users", "791"),
                ExcludableCheckBox("Spirits", "792"),
                ExcludableCheckBox("Stalkers", "793"),
                ExcludableCheckBox("Stockholm Syndrome", "794"),
                ExcludableCheckBox("Stoic Characters", "795"),
                ExcludableCheckBox("Store Owner", "796"),
                ExcludableCheckBox("Straight Seme", "797"),
                ExcludableCheckBox("Straight Uke", "798"),
                ExcludableCheckBox("Strategic Battles", "799"),
                ExcludableCheckBox("Strategist", "800"),
                ExcludableCheckBox("Strength-based Social Hierarchy", "801"),
                ExcludableCheckBox("Strong Love Interests", "802"),
                ExcludableCheckBox("Strong to Stronger", "803"),
                ExcludableCheckBox("Stubborn Protagonist", "804"),
                ExcludableCheckBox("Student Council", "805"),
                ExcludableCheckBox("Student-Teacher Relationship", "806"),
                ExcludableCheckBox("Subtle Romance", "807"),
                ExcludableCheckBox("Succubus", "808"),
                ExcludableCheckBox("Sudden Strength Gain", "809"),
                ExcludableCheckBox("Sudden Wealth", "810"),
                ExcludableCheckBox("Suicides", "811"),
                ExcludableCheckBox("Summoned Hero", "812"),
                ExcludableCheckBox("Summoning Magic", "813"),
                ExcludableCheckBox("Superheroes", "44746"),
                ExcludableCheckBox("Survival", "814"),
                ExcludableCheckBox("Survival Game", "815"),
                ExcludableCheckBox("Sword And Magic", "816"),
                ExcludableCheckBox("Sword Wielder", "817"),
                ExcludableCheckBox("System Administrator", "818"),
                ExcludableCheckBox("Teachers", "819"),
                ExcludableCheckBox("Teamwork", "820"),
                ExcludableCheckBox("Technological Gap", "821"),
                ExcludableCheckBox("Tentacles", "822"),
                ExcludableCheckBox("Terminal Illness", "823"),
                ExcludableCheckBox("Terrorists", "824"),
                ExcludableCheckBox("Thieves", "825"),
                ExcludableCheckBox("Threesome", "826"),
                ExcludableCheckBox("Thriller", "827"),
                ExcludableCheckBox("Time Loop", "828"),
                ExcludableCheckBox("Time Manipulation", "829"),
                ExcludableCheckBox("Time Paradox", "830"),
                ExcludableCheckBox("Time Skip", "831"),
                ExcludableCheckBox("Time Travel", "832"),
                ExcludableCheckBox("Timid Protagonist", "833"),
                ExcludableCheckBox("Tomboyish Female Lead", "834"),
                ExcludableCheckBox("Torture", "835"),
                ExcludableCheckBox("Toys", "836"),
                ExcludableCheckBox("Tragic Past", "837"),
                ExcludableCheckBox("Transformation Ability", "838"),
                ExcludableCheckBox("Transgender", "1088"),
                ExcludableCheckBox("Transmigration", "839"),
                ExcludableCheckBox("Transplanted Memories", "840"),
                ExcludableCheckBox("Transported into a Game World", "841"),
                ExcludableCheckBox("Transported into Another World", "842"),
                ExcludableCheckBox("Transported Modern Structure", "843"),
                ExcludableCheckBox("Trap", "844"),
                ExcludableCheckBox("Tribal Society", "845"),
                ExcludableCheckBox("Trickster", "846"),
                ExcludableCheckBox("Trolls", "1071"),
                ExcludableCheckBox("Tsundere", "847"),
                ExcludableCheckBox("Twins", "848"),
                ExcludableCheckBox("Twisted Personality", "849"),
                ExcludableCheckBox("Ugly Protagonist", "850"),
                ExcludableCheckBox("Ugly to Beautiful", "851"),
                ExcludableCheckBox("Unconditional Love", "852"),
                ExcludableCheckBox("Underestimated Protagonist", "853"),
                ExcludableCheckBox("Unique Cultivation Technique", "854"),
                ExcludableCheckBox("Unique Weapon User", "855"),
                ExcludableCheckBox("Unique Weapons", "856"),
                ExcludableCheckBox("Unlucky Protagonist", "857"),
                ExcludableCheckBox("Unreliable Narrator", "858"),
                ExcludableCheckBox("Unrequited Love", "859"),
                ExcludableCheckBox("Valkyries", "860"),
                ExcludableCheckBox("Vampires", "861"),
                ExcludableCheckBox("Villainess Noble Girls", "862"),
                ExcludableCheckBox("Virtual Reality", "863"),
                ExcludableCheckBox("Vocaloid", "864"),
                ExcludableCheckBox("Voice Actors", "865"),
                ExcludableCheckBox("Voyeurism", "866"),
                ExcludableCheckBox("Waiters", "867"),
                ExcludableCheckBox("War Records", "868"),
                ExcludableCheckBox("Wars", "869"),
                ExcludableCheckBox("Weak Protagonist", "870"),
                ExcludableCheckBox("Weak to Strong", "871"),
                ExcludableCheckBox("Wealthy Characters", "872"),
                ExcludableCheckBox("Werebeasts", "873"),
                ExcludableCheckBox("Wishes", "874"),
                ExcludableCheckBox("Witches", "875"),
                ExcludableCheckBox("Wizards", "876"),
                ExcludableCheckBox("World Hopping", "877"),
                ExcludableCheckBox("World Invasion", "1036"),
                ExcludableCheckBox("World Travel", "878"),
                ExcludableCheckBox("World Tree", "879"),
                ExcludableCheckBox("Writers", "880"),
                ExcludableCheckBox("Wuxia", "1143"),
                ExcludableCheckBox("Xianxia", "1142"),
                ExcludableCheckBox("Xuanhuan", "1141"),
                ExcludableCheckBox("Yandere", "881"),
                ExcludableCheckBox("Youkai", "882"),
                ExcludableCheckBox("Younger Brothers", "883"),
                ExcludableCheckBox("Younger Love Interests", "884"),
                ExcludableCheckBox("Younger Sisters", "885"),
                ExcludableCheckBox("Zombies", "886"),
            ),
        )

    private class TagOperatorFilter :
        Filter.Select<String>(
            "Tags Operator",
            arrayOf("And", "Or"),
        ) {
        fun toUriPart() = when (state) {
            0 -> "and"
            1 -> "or"
            else -> "and"
        }
    }

    private class TagIncludeTextFilter : Filter.Text("Include Tags (comma-separated, e.g: academy, acting)")

    private class TagExcludeTextFilter : Filter.Text("Exclude Tags (comma-separated, e.g: harem, tragedy)")
}

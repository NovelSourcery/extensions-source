package eu.kanade.tachiyomi.extension.en.novelupdates

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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

class NovelUpdates : HttpSource(), NovelSource, ConfigurableSource {

    override val name = "Novel Updates"
    override val baseUrl = "https://www.novelupdates.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Novel source implementation
    override suspend fun fetchPageText(page: Page): String {
        // NovelUpdates is an aggregator - chapters link to external sites
        // We need to fetch the external chapter and parse its content
        val chapterUrl = page.url

        val response = client.newCall(GET(chapterUrl, headers)).execute()
        val body = response.body.string()
        val url = response.request.url.toString()
        val domainParts = url.lowercase().split("/")[2].split(".")

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

        if (preferences.getBoolean(PREF_RETURN_FULL_HTML, false)) {
            return body
        }

        // Try to extract chapter content based on the domain
        return getChapterBody(doc, domainParts, url)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val fullHtmlPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_RETURN_FULL_HTML
            title = "Return full HTML"
            summary = "If enabled, returns the full chapter HTML without extracting the content. Useful for custom parsers."
            setDefaultValue(false)
        }
        screen.addPreference(fullHtmlPref)
    }

    companion object {
        private const val PREF_RETURN_FULL_HTML = "pref_return_full_html"
    }

    private fun getChapterBody(doc: Document, domain: List<String>, url: String): String {
        val unwanted = listOf("app", "blogspot", "casper", "wordpress", "www")
        val targetDomain = domain.find { !unwanted.contains(it) }

        var chapterTitle = ""
        var chapterContent = ""

        when (targetDomain) {
            "scribblehub" -> {
                doc.select(".wi_authornotes").remove()
                chapterTitle = doc.select(".chapter-title").first()?.text() ?: ""
                chapterContent = doc.select(".chp_raw").html()
            }
            "webnovel" -> {
                chapterTitle = doc.select(".cha-tit .pr .dib").first()?.text() ?: ""
                chapterContent = doc.select(".cha-words").html().ifEmpty {
                    doc.select("._content").html()
                }
            }
            "wuxiaworld" -> {
                doc.select(".MuiLink-root").remove()
                chapterTitle = doc.select("h4 span").first()?.text() ?: ""
                chapterContent = doc.select(".chapter-content").html()
            }
            "hostednovel" -> {
                chapterTitle = doc.select("#chapter-title").first()?.text() ?: ""
                chapterContent = doc.select("#chapter-content").html()
            }
            "royalroad" -> {
                chapterTitle = doc.select("h1").first()?.text() ?: ""
                chapterContent = doc.select(".chapter-content").html()
            }
            "akutranslations" -> {
                chapterTitle = doc.select(".entry-title").first()?.text() ?: ""
                chapterContent = doc.select(".entry-content").html()
                doc.selectFirst(".entry-content")?.select("nav, .sharedaddy, .wp-block-separator")?.remove()
            }
            "brightnovels" -> {
                chapterTitle = doc.select(".entry-title").first()?.text() ?: ""
                chapterContent = doc.select(".entry-content").html()
                doc.selectFirst(".entry-content")?.select(".code-block, script, .adsbygoogle")?.remove()
            }
            "chrysanthemumgarden" -> {
                chapterTitle = doc.select(".entry-title").first()?.text() ?: ""
                chapterContent = doc.select(".entry-content").html()
                doc.selectFirst(".entry-content")?.select(".jum")?.remove()
            }
            "crickets" -> {
                chapterTitle = doc.select(".entry-title").first()?.text() ?: ""
                chapterContent = doc.select(".entry-content").html()
            }
            "rainofsnow" -> {
                chapterTitle = doc.select("h1.entry-title").first()?.text() ?: ""
                chapterContent = doc.select("div.entry-content").html()
                doc.selectFirst("div.entry-content")?.select(".sharedaddy, .wpcnt")?.remove()
            }
            "scribblehubvn" -> {
                chapterTitle = doc.select(".entry-title").first()?.text() ?: ""
                chapterContent = doc.select(".entry-content").html()
            }
            "opentl", "opentranslate" -> {
                chapterTitle = doc.select(".entry-title").first()?.text() ?: ""
                chapterContent = doc.select(".entry-content").html()
            }
            "volare" -> {
                chapterTitle = doc.select(".entry-title").first()?.text() ?: ""
                chapterContent = doc.select(".entry-content, .chapter-content").html()
                doc.selectFirst(".entry-content")?.select(".announcements")?.remove()
            }
            "4slashfour", "fourslashfour" -> {
                chapterTitle = doc.select("h1.entry-title").first()?.text() ?: ""
                chapterContent = doc.select(".entry-content").html()
            }
            "flyinglines" -> {
                chapterTitle = doc.select(".entry-title").first()?.text() ?: ""
                chapterContent = doc.select(".text_story, .entry-content").html()
            }
            "isekaisoul" -> {
                chapterTitle = doc.select(".entry-title").first()?.text() ?: ""
                chapterContent = doc.select(".entry-content").html()
                doc.selectFirst(".entry-content")?.select(".code-block, .adsbygoogle, ins")?.remove()
            }
            "woopread" -> {
                chapterTitle = doc.select(".chapter__title, h1").first()?.text() ?: ""
                chapterContent = doc.select(".chapter__content, .text_story").html()
            }
            "firstkissnovel" -> {
                chapterTitle = doc.select(".chr-title, .chapter-title").first()?.text() ?: ""
                chapterContent = doc.select(".chr-content, .chapter-content").html()
            }
            "foxteller" -> {
                chapterTitle = doc.select(".entry-title, .chapter-title").first()?.text() ?: ""
                chapterContent = doc.select(".entry-content, .chapter-content").html()
                doc.selectFirst(".entry-content")?.select(".wp-block-separator, script")?.remove()
            }
            else -> {
                // Generic fallback - try common selectors
                val contentSelectors = listOf(
                    ".chapter-content",
                    ".entry-content",
                    ".post-content",
                    ".content",
                    "#content",
                    ".chapter__content",
                    ".text_story",
                    "article",
                )

                for (selector in contentSelectors) {
                    val content = doc.select(selector).html()
                    if (content.isNotEmpty() && content.length > 100) {
                        chapterContent = content
                        break
                    }
                }

                // Try to find title
                val titleSelectors = listOf(
                    ".chapter-title",
                    ".entry-title",
                    "h1",
                    "h2",
                    ".title",
                )
                for (selector in titleSelectors) {
                    val title = doc.select(selector).first()?.text()
                    if (!title.isNullOrEmpty()) {
                        chapterTitle = title
                        break
                    }
                }
            }
        }

        // Fallback to body content
        if (chapterContent.isEmpty()) {
            doc.select("nav, header, footer, .hidden, script, style").remove()
            chapterContent = doc.select("body").html()
        }

        return if (chapterTitle.isNotEmpty()) {
            "<h2>$chapterTitle</h2><hr><br>$chapterContent"
        } else {
            chapterContent
        }
    }

    // Popular novels
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/series-ranking/?rank=popmonth&pg=$page", headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelsFromSearch(doc)
    }

    // Latest updates
    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/series-finder/?sf=1&sort=sdate&order=desc&pg=$page", headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelsFromSearch(doc)
    }

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotEmpty()) {
            val searchTerm = query.replace(Regex("[''']"), "'").replace(Regex("\\s+"), "+")
            "$baseUrl/series-finder/?sf=1&sh=$searchTerm&sort=srank&order=asc&pg=$page"
        } else {
            buildFilterUrl(page, filters)
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelsFromSearch(doc)
    }

    private fun parseNovelsFromSearch(doc: Document): MangasPage {
        val novels = doc.select("div.search_main_box_nu").mapNotNull { element ->
            val titleElement = element.select(".search_title > a").first() ?: return@mapNotNull null
            val novelUrl = titleElement.attr("href")

            SManga.create().apply {
                title = titleElement.text()
                thumbnail_url = element.select("img").attr("src")
                url = novelUrl.removePrefix(baseUrl)
            }
        }

        // Check if there's a next page
        val hasNextPage = doc.select(".digg_pagination a.next_page").isNotEmpty() ||
            doc.select(".pagination a:contains(Next)").isNotEmpty()

        return MangasPage(novels, hasNextPage)
    }

    // Manga details
    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())
        return SManga.create().apply {
            title = doc.select(".seriestitlenu").text().ifEmpty { "Untitled or invalid" }
            thumbnail_url = doc.select(".wpb_wrapper img").attr("src")

            author = doc.select("#authtag").joinToString(", ") { it.text().trim() }

            genre = doc.select("#seriesgenre a").joinToString(", ") { it.text() }

            status = when {
                doc.select("#editstatus").text().contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                doc.select("#editstatus").text().contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            val type = doc.select("#showtype").text().trim()
            val summary = doc.select("#editdescription").text().trim()

            // Extract tags from a.genre elements (tags section)
            val tags = doc.select("#showtags a.genre").joinToString(", ") { it.text() }

            // Append tags to genre
            if (tags.isNotEmpty()) {
                genre = if (genre.isNullOrEmpty()) tags else "$genre, $tags"
            }

            description = buildString {
                append(summary)
                if (type.isNotEmpty()) {
                    append("\n\nType: $type")
                }
                if (tags.isNotEmpty()) {
                    append("\n\nTags: $tags")
                }
            }
        }
    }

    // Chapter list
    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())

        // Get the novel ID for fetching chapters
        val novelId = doc.select("input#mypostid").attr("value")
        if (novelId.isEmpty()) return emptyList()

        // Fetch chapters via AJAX
        val formBody = FormBody.Builder()
            .add("action", "nd_getchapters")
            .add("mygrr", "0")
            .add("mypostid", novelId)
            .build()

        val chaptersRequest = POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody)
        val chaptersResponse = client.newCall(chaptersRequest).execute()
        val chaptersHtml = chaptersResponse.body.string()

        val chaptersDoc = Jsoup.parse(chaptersHtml)

        return chaptersDoc.select("li.sp_li_chp").mapNotNull { element ->
            val chapterName = element.text()
                .replace("v", "volume ")
                .replace("c", " chapter ")
                .replace("part", "part ")
                .replace("ss", "SS")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                .trim()

            val chapterLink = element.select("a").first()?.nextElementSibling()?.attr("href")
                ?: return@mapNotNull null

            val fullUrl = if (chapterLink.startsWith("//")) {
                "https:$chapterLink"
            } else {
                chapterLink
            }

            SChapter.create().apply {
                name = chapterName
                url = fullUrl // Store the full external URL
                date_upload = 0L
            }
        }.reversed()
    }

    // Page list - return single page with the chapter URL
    override fun pageListRequest(chapter: SChapter): Request {
        // chapter.url contains the external chapter URL
        return GET(chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        // Return single page with the chapter URL for fetchPageText
        return listOf(Page(0, response.request.url.toString(), null))
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
        LanguageFilter(),
        NovelTypeFilter(),
        Filter.Header("Tags (ignored if sort=Latest Added)"),
        TagFilter(),
        TagOperatorFilter(),
        TagIncludeTextFilter(),
        TagExcludeTextFilter(),
        Filter.Header("Reading List (ignored if sort=Latest Added, -1=all, 0=reading, others=custom lists)"),
        ReadingListTextFilter(),
        ReadingListModeFilter(),
    )

    private fun buildFilterUrl(page: Int, filters: FilterList): String {
        val sortFilter = filters.findInstance<SortFilter>()!!
        val orderFilter = filters.findInstance<OrderFilter>()!!
        val statusFilter = filters.findInstance<StatusFilter>()!!
        val genreFilter = filters.findInstance<GenreFilter>()!!
        val languageFilter = filters.findInstance<LanguageFilter>()!!
        val novelTypeFilter = filters.findInstance<NovelTypeFilter>()!!
        val tagFilter = filters.findInstance<TagFilter>()!!
        val tagOperatorFilter = filters.findInstance<TagOperatorFilter>()!!
        val tagIncludeTextFilter = filters.findInstance<TagIncludeTextFilter>()!!
        val tagExcludeTextFilter = filters.findInstance<TagExcludeTextFilter>()!!
        val readingListTextFilter = filters.findInstance<ReadingListTextFilter>()!!
        val readingListModeFilter = filters.findInstance<ReadingListModeFilter>()!!

        val sortValue = sortFilter.toUriPart()
        val orderValue = orderFilter.toUriPart()

        return when {
            sortValue == "popmonth" || sortValue == "popular" -> {
                buildString {
                    append("$baseUrl/series-ranking/?rank=$sortValue")

                    // Series ranking supports genre, language, and status filters
                    val includedGenres = genreFilter.state.filter { it.isIncluded() }.map { it.id }
                    val excludedGenres = genreFilter.state.filter { it.isExcluded() }.map { it.id }
                    if (includedGenres.isNotEmpty()) {
                        append("&gi=").append(includedGenres.joinToString(","))
                    }
                    if (excludedGenres.isNotEmpty()) {
                        append("&ge=").append(excludedGenres.joinToString(","))
                    }

                    val selectedLanguages = languageFilter.state.filter { it.state }.map { it.id }
                    if (selectedLanguages.isNotEmpty()) {
                        append("&org=").append(selectedLanguages.joinToString(","))
                    }

                    if (statusFilter.state != 0) {
                        append("&ss=").append(statusFilter.toUriPart())
                    }

                    append("&pg=$page")
                }
            }
            sortValue == "latest" -> {
                buildString {
                    append("$baseUrl/latest-series/?st=1")

                    val includedGenres = genreFilter.state.filter { it.isIncluded() }.map { it.id }
                    val excludedGenres = genreFilter.state.filter { it.isExcluded() }.map { it.id }
                    if (includedGenres.isNotEmpty()) {
                        append("&gi=").append(includedGenres.joinToString(","))
                        append("&mgi=and")
                    }
                    if (excludedGenres.isNotEmpty()) {
                        append("&ge=").append(excludedGenres.joinToString(","))
                    }

                    val selectedLanguages = languageFilter.state.filter { it.state }.map { it.id }
                    if (selectedLanguages.isNotEmpty()) {
                        append("&org=").append(selectedLanguages.joinToString(","))
                    }

                    append("&pg=$page")
                }
            }
            else -> {
                buildString {
                    append("$baseUrl/series-finder/?sf=1")

                    val includedGenres = genreFilter.state.filter { it.isIncluded() }.map { it.id }
                    val excludedGenres = genreFilter.state.filter { it.isExcluded() }.map { it.id }
                    if (includedGenres.isNotEmpty()) {
                        append("&gi=").append(includedGenres.joinToString(","))
                        append("&mgi=and")
                    }
                    if (excludedGenres.isNotEmpty()) {
                        append("&ge=").append(excludedGenres.joinToString(","))
                    }

                    val selectedLanguages = languageFilter.state.filter { it.state }.map { it.id }
                    if (selectedLanguages.isNotEmpty()) {
                        append("&org=").append(selectedLanguages.joinToString(","))
                    }

                    val selectedNovelTypes = novelTypeFilter.state.filter { it.state }.map { it.id }
                    if (selectedNovelTypes.isNotEmpty()) {
                        append("&nt=").append(selectedNovelTypes.joinToString(","))
                    }

                    if (statusFilter.state != 0) {
                        append("&ss=").append(statusFilter.toUriPart())
                    }

                    // Add tags (not for Latest Added)
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

                    // Add reading list (not for Latest Added)
                    val readingListIds = readingListTextFilter.state.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    if (readingListIds.isNotEmpty()) {
                        append("&hd=").append(readingListIds.joinToString(","))
                        append("&mRLi=").append(readingListModeFilter.toUriPart())
                    }

                    append("&sort=$sortValue")
                    append("&order=$orderValue")
                    append("&pg=$page")
                }
            }
        }
    }

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    private class SortFilter : Filter.Select<String>(
        "Sort Results By",
        arrayOf(
            "Popular (Month)",
            "Popular (All)",
            "Latest Added",
            "Last Updated",
            "Rating",
            "Rank",
            "Reviews",
            "Chapters",
            "Title",
            "Readers",
            "Frequency",
        ),
    ) {
        fun toUriPart() = when (state) {
            0 -> "popmonth"
            1 -> "popular"
            2 -> "latest"
            3 -> "sdate"
            4 -> "srate"
            5 -> "srank"
            6 -> "sreview"
            7 -> "srel"
            8 -> "abc"
            9 -> "sread"
            10 -> "sfrel"
            else -> "popmonth"
        }
    }

    private class OrderFilter : Filter.Select<String>(
        "Order (Not for Popular)",
        arrayOf("Descending", "Ascending"),
    ) {
        fun toUriPart() = when (state) {
            0 -> "desc"
            1 -> "asc"
            else -> "desc"
        }
    }

    private class StatusFilter : Filter.Select<String>(
        "Story Status (Translation)",
        arrayOf("All", "Completed", "Ongoing", "Hiatus"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "2"
            2 -> "3"
            3 -> "4"
            else -> ""
        }
    }

    private class Genre(name: String, val id: String) : Filter.TriState(name)

    private class GenreFilter : Filter.Group<Genre>(
        "Genres (0=ignore, 1=include, 2=exclude)",
        listOf(
            Genre("Action", "8"),
            Genre("Adult", "280"),
            Genre("Adventure", "13"),
            Genre("Comedy", "17"),
            Genre("Drama", "9"),
            Genre("Ecchi", "292"),
            Genre("Fantasy", "5"),
            Genre("Gender Bender", "168"),
            Genre("Harem", "3"),
            Genre("Historical", "330"),
            Genre("Horror", "343"),
            Genre("Josei", "324"),
            Genre("Martial Arts", "14"),
            Genre("Mature", "4"),
            Genre("Mecha", "10"),
            Genre("Mystery", "245"),
            Genre("Psychological", "486"),
            Genre("Romance", "15"),
            Genre("School Life", "6"),
            Genre("Sci-fi", "11"),
            Genre("Seinen", "18"),
            Genre("Shoujo", "157"),
            Genre("Shoujo Ai", "851"),
            Genre("Shounen", "12"),
            Genre("Shounen Ai", "1692"),
            Genre("Slice of Life", "7"),
            Genre("Smut", "281"),
            Genre("Sports", "1357"),
            Genre("Supernatural", "16"),
            Genre("Tragedy", "132"),
            Genre("Wuxia", "479"),
            Genre("Xianxia", "480"),
            Genre("Xuanhuan", "3954"),
            Genre("Yaoi", "560"),
            Genre("Yuri", "922"),
        ),
    )

    private class Language(name: String, val id: String) : Filter.CheckBox(name)

    private class LanguageFilter : Filter.Group<Language>(
        "Language",
        listOf(
            Language("Chinese", "495"),
            Language("Filipino", "9181"),
            Language("Indonesian", "9179"),
            Language("Japanese", "496"),
            Language("Khmer", "18657"),
            Language("Korean", "497"),
            Language("Malaysian", "9183"),
            Language("Thai", "9954"),
            Language("Vietnamese", "9177"),
        ),
    )

    private class NovelType(name: String, val id: String) : Filter.CheckBox(name)

    private class NovelTypeFilter : Filter.Group<NovelType>(
        "Novel Type (Not for Popular)",
        listOf(
            NovelType("Light Novel", "2443"),
            NovelType("Published Novel", "26874"),
            NovelType("Web Novel", "2444"),
        ),
    )

    private class ExcludableCheckBox(name: String, val value: String) : Filter.TriState(name)

    private class TagOperatorFilter : Filter.Select<String>(
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

    private class ReadingListTextFilter : Filter.Text("Reading List IDs (comma-separated, e.g: -1,0,3)")

    private class ReadingListModeFilter : Filter.Select<String>(
        "Reading List Mode",
        arrayOf("Include", "Exclude"),
    ) {
        fun toUriPart() = when (state) {
            0 -> "include"
            1 -> "exclude"
            else -> "include"
        }
    }
    private class TagFilter : Filter.Group<ExcludableCheckBox>(
        "Tags",
        listOf(
            ExcludableCheckBox("Abandoned Children", "0"),
            ExcludableCheckBox("Ability Steal", "1"),
            ExcludableCheckBox("Absent Parents", "2"),
            ExcludableCheckBox("Abusive Characters", "3"),
            ExcludableCheckBox("Academy", "4"),
            ExcludableCheckBox("Accelerated Growth", "5"),
            ExcludableCheckBox("Acting", "6"),
            ExcludableCheckBox("Adapted from Manga", "7"),
            ExcludableCheckBox("Adapted from Manhua", "8"),
            ExcludableCheckBox("Adapted to Anime", "9"),
            ExcludableCheckBox("Adapted to Drama", "10"),
            ExcludableCheckBox("Adapted to Drama CD", "11"),
            ExcludableCheckBox("Adapted to Game", "12"),
            ExcludableCheckBox("Adapted to Manga", "13"),
            ExcludableCheckBox("Adapted to Manhua", "14"),
            ExcludableCheckBox("Adapted to Manhwa", "15"),
            ExcludableCheckBox("Adapted to Movie", "16"),
            ExcludableCheckBox("Adapted to Visual Novel", "17"),
            ExcludableCheckBox("Adopted Children", "18"),
            ExcludableCheckBox("Adopted Protagonist", "19"),
            ExcludableCheckBox("Adultery", "20"),
            ExcludableCheckBox("Adventurers", "21"),
            ExcludableCheckBox("Affair", "22"),
            ExcludableCheckBox("Age Progression", "23"),
            ExcludableCheckBox("Age Regression", "24"),
            ExcludableCheckBox("Aggressive Characters", "25"),
            ExcludableCheckBox("Alchemy", "26"),
            ExcludableCheckBox("Aliens", "27"),
            ExcludableCheckBox("All-Girls School", "28"),
            ExcludableCheckBox("Alternate World", "29"),
            ExcludableCheckBox("Amnesia", "30"),
            ExcludableCheckBox("Amusement Park", "31"),
            ExcludableCheckBox("Anal", "32"),
            ExcludableCheckBox("Ancient China", "33"),
            ExcludableCheckBox("Ancient Times", "34"),
            ExcludableCheckBox("Androgynous Characters", "35"),
            ExcludableCheckBox("Androids", "36"),
            ExcludableCheckBox("Angels", "37"),
            ExcludableCheckBox("Animal Characteristics", "38"),
            ExcludableCheckBox("Animal Rearing", "39"),
            ExcludableCheckBox("Anti-Magic", "40"),
            ExcludableCheckBox("Anti-social Protagonist", "41"),
            ExcludableCheckBox("Antihero Protagonist", "42"),
            ExcludableCheckBox("Antique Shop", "43"),
            ExcludableCheckBox("Apartment Life", "44"),
            ExcludableCheckBox("Apathetic Protagonist", "45"),
            ExcludableCheckBox("Apocalypse", "46"),
            ExcludableCheckBox("Appearance Changes", "47"),
            ExcludableCheckBox("Appearance Different from Actual Age", "48"),
            ExcludableCheckBox("Archery", "49"),
            ExcludableCheckBox("Aristocracy", "50"),
            ExcludableCheckBox("Arms Dealers", "51"),
            ExcludableCheckBox("Army", "52"),
            ExcludableCheckBox("Army Building", "53"),
            ExcludableCheckBox("Arranged Marriage", "54"),
            ExcludableCheckBox("Arrogant Characters", "55"),
            ExcludableCheckBox("Artifact Crafting", "56"),
            ExcludableCheckBox("Artifacts", "57"),
            ExcludableCheckBox("Artificial Intelligence", "58"),
            ExcludableCheckBox("Artists", "59"),
            ExcludableCheckBox("Assassins", "60"),
            ExcludableCheckBox("Astrologers", "61"),
            ExcludableCheckBox("Autism", "62"),
            ExcludableCheckBox("Automatons", "63"),
            ExcludableCheckBox("Average-looking Protagonist", "64"),
            ExcludableCheckBox("Award-winning Work", "65"),
            ExcludableCheckBox("Awkward Protagonist", "66"),
            ExcludableCheckBox("Bands", "67"),
            ExcludableCheckBox("Based on a Movie", "68"),
            ExcludableCheckBox("Based on a Song", "69"),
            ExcludableCheckBox("Based on a TV Show", "70"),
            ExcludableCheckBox("Based on a Video Game", "71"),
            ExcludableCheckBox("Based on a Visual Novel", "72"),
            ExcludableCheckBox("Based on an Anime", "73"),
            ExcludableCheckBox("Battle Academy", "74"),
            ExcludableCheckBox("Battle Competition", "75"),
            ExcludableCheckBox("BDSM", "76"),
            ExcludableCheckBox("Beast Companions", "77"),
            ExcludableCheckBox("Beastkin", "78"),
            ExcludableCheckBox("Beasts", "79"),
            ExcludableCheckBox("Beautiful Female Lead", "80"),
            ExcludableCheckBox("Bestiality", "81"),
            ExcludableCheckBox("Betrayal", "82"),
            ExcludableCheckBox("Bickering Couple", "83"),
            ExcludableCheckBox("Biochip", "84"),
            ExcludableCheckBox("Bisexual Protagonist", "85"),
            ExcludableCheckBox("Black Belly", "86"),
            ExcludableCheckBox("Blackmail", "87"),
            ExcludableCheckBox("Blacksmith", "88"),
            ExcludableCheckBox("Blind Dates", "89"),
            ExcludableCheckBox("Blind Protagonist", "90"),
            ExcludableCheckBox("Blood Manipulation", "91"),
            ExcludableCheckBox("Bloodlines", "92"),
            ExcludableCheckBox("Body Swap", "93"),
            ExcludableCheckBox("Body Tempering", "94"),
            ExcludableCheckBox("Body-double", "95"),
            ExcludableCheckBox("Bodyguards", "96"),
            ExcludableCheckBox("Books", "97"),
            ExcludableCheckBox("Bookworm", "98"),
            ExcludableCheckBox("Boss-Subordinate Relationship", "99"),
            ExcludableCheckBox("Brainwashing", "100"),
            ExcludableCheckBox("Breast Fetish", "101"),
            ExcludableCheckBox("Broken Engagement", "102"),
            ExcludableCheckBox("Brother Complex", "103"),
            ExcludableCheckBox("Brotherhood", "104"),
            ExcludableCheckBox("Buddhism", "105"),
            ExcludableCheckBox("Bullying", "106"),
            ExcludableCheckBox("Business Management", "107"),
            ExcludableCheckBox("Businessmen", "108"),
            ExcludableCheckBox("Butlers", "109"),
            ExcludableCheckBox("Calm Protagonist", "110"),
            ExcludableCheckBox("Cannibalism", "111"),
            ExcludableCheckBox("Card Games", "112"),
            ExcludableCheckBox("Carefree Protagonist", "113"),
            ExcludableCheckBox("Caring Protagonist", "114"),
            ExcludableCheckBox("Cautious Protagonist", "115"),
            ExcludableCheckBox("Celebrities", "116"),
            ExcludableCheckBox("Character Growth", "117"),
            ExcludableCheckBox("Charismatic Protagonist", "118"),
            ExcludableCheckBox("Charming Protagonist", "119"),
            ExcludableCheckBox("Chat Rooms", "120"),
            ExcludableCheckBox("Cheats", "121"),
            ExcludableCheckBox("Chefs", "122"),
            ExcludableCheckBox("Child Abuse", "123"),
            ExcludableCheckBox("Child Protagonist", "124"),
            ExcludableCheckBox("Childcare", "125"),
            ExcludableCheckBox("Childhood Friends", "126"),
            ExcludableCheckBox("Childhood Love", "127"),
            ExcludableCheckBox("Childhood Promise", "128"),
            ExcludableCheckBox("Childish Protagonist", "129"),
            ExcludableCheckBox("Chuunibyou", "130"),
            ExcludableCheckBox("Clan Building", "131"),
            ExcludableCheckBox("Classic", "132"),
            ExcludableCheckBox("Clever Protagonist", "133"),
            ExcludableCheckBox("Clingy Lover", "134"),
            ExcludableCheckBox("Clones", "135"),
            ExcludableCheckBox("Clubs", "136"),
            ExcludableCheckBox("Clumsy Love Interests", "137"),
            ExcludableCheckBox("Co-Workers", "138"),
            ExcludableCheckBox("Cohabitation", "139"),
            ExcludableCheckBox("Cold Love Interests", "140"),
            ExcludableCheckBox("Cold Protagonist", "141"),
            ExcludableCheckBox("Collection of Short Stories", "142"),
            ExcludableCheckBox("College/University", "143"),
            ExcludableCheckBox("Coma", "144"),
            ExcludableCheckBox("Comedic Undertone", "145"),
            ExcludableCheckBox("Coming of Age", "146"),
            ExcludableCheckBox("Complex Family Relationships", "147"),
            ExcludableCheckBox("Conditional Power", "148"),
            ExcludableCheckBox("Confident Protagonist", "149"),
            ExcludableCheckBox("Confinement", "150"),
            ExcludableCheckBox("Conflicting Loyalties", "151"),
            ExcludableCheckBox("Contracts", "152"),
            ExcludableCheckBox("Cooking", "153"),
            ExcludableCheckBox("Corruption", "154"),
            ExcludableCheckBox("Cosmic Wars", "155"),
            ExcludableCheckBox("Cosplay", "156"),
            ExcludableCheckBox("Couple Growth", "157"),
            ExcludableCheckBox("Court Official", "158"),
            ExcludableCheckBox("Cousins", "159"),
            ExcludableCheckBox("Cowardly Protagonist", "160"),
            ExcludableCheckBox("Crafting", "161"),
            ExcludableCheckBox("Crime", "162"),
            ExcludableCheckBox("Criminals", "163"),
            ExcludableCheckBox("Cross-dressing", "164"),
            ExcludableCheckBox("Crossover", "165"),
            ExcludableCheckBox("Cruel Characters", "166"),
            ExcludableCheckBox("Cryostasis", "167"),
            ExcludableCheckBox("Cultivation", "168"),
            ExcludableCheckBox("Cunnilingus", "169"),
            ExcludableCheckBox("Cunning Protagonist", "170"),
            ExcludableCheckBox("Curious Protagonist", "171"),
            ExcludableCheckBox("Curses", "172"),
            ExcludableCheckBox("Cute Children", "173"),
            ExcludableCheckBox("Cute Protagonist", "174"),
            ExcludableCheckBox("Cute Story", "175"),
            ExcludableCheckBox("Dancers", "176"),
            ExcludableCheckBox("Dao Companion", "177"),
            ExcludableCheckBox("Dao Comprehension", "178"),
            ExcludableCheckBox("Daoism", "179"),
            ExcludableCheckBox("Dark", "180"),
            ExcludableCheckBox("Dead Protagonist", "181"),
            ExcludableCheckBox("Death", "182"),
            ExcludableCheckBox("Death of Loved Ones", "183"),
            ExcludableCheckBox("Debts", "184"),
            ExcludableCheckBox("Delinquents", "185"),
            ExcludableCheckBox("Delusions", "186"),
            ExcludableCheckBox("Demi-Humans", "187"),
            ExcludableCheckBox("Demon Lord", "188"),
            ExcludableCheckBox("Demonic Cultivation Technique", "189"),
            ExcludableCheckBox("Demons", "190"),
            ExcludableCheckBox("Dense Protagonist", "191"),
            ExcludableCheckBox("Depictions of Cruelty", "192"),
            ExcludableCheckBox("Depression", "193"),
            ExcludableCheckBox("Destiny", "194"),
            ExcludableCheckBox("Detectives", "195"),
            ExcludableCheckBox("Determined Protagonist", "196"),
            ExcludableCheckBox("Devoted Love Interests", "197"),
            ExcludableCheckBox("Different Social Status", "198"),
            ExcludableCheckBox("Disabilities", "199"),
            ExcludableCheckBox("Discrimination", "200"),
            ExcludableCheckBox("Disfigurement", "201"),
            ExcludableCheckBox("Dishonest Protagonist", "202"),
            ExcludableCheckBox("Distrustful Protagonist", "203"),
            ExcludableCheckBox("Divination", "204"),
            ExcludableCheckBox("Divine Protection", "205"),
            ExcludableCheckBox("Divorce", "206"),
            ExcludableCheckBox("Doctors", "207"),
            ExcludableCheckBox("Dolls/Puppets", "208"),
            ExcludableCheckBox("Domestic Affairs", "209"),
            ExcludableCheckBox("Doting Love Interests", "210"),
            ExcludableCheckBox("Doting Older Siblings", "211"),
            ExcludableCheckBox("Doting Parents", "212"),
            ExcludableCheckBox("Dragon Riders", "213"),
            ExcludableCheckBox("Dragon Slayers", "214"),
            ExcludableCheckBox("Dragons", "215"),
            ExcludableCheckBox("Dreams", "216"),
            ExcludableCheckBox("Drugs", "217"),
            ExcludableCheckBox("Druids", "218"),
            ExcludableCheckBox("Dungeon Master", "219"),
            ExcludableCheckBox("Dungeons", "220"),
            ExcludableCheckBox("Dwarfs", "221"),
            ExcludableCheckBox("Dystopia", "222"),
            ExcludableCheckBox("e-Sports", "223"),
            ExcludableCheckBox("Early Romance", "224"),
            ExcludableCheckBox("Earth Invasion", "225"),
            ExcludableCheckBox("Easy Going Life", "226"),
            ExcludableCheckBox("Economics", "227"),
            ExcludableCheckBox("Editors", "228"),
            ExcludableCheckBox("Eidetic Memory", "229"),
            ExcludableCheckBox("Elderly Protagonist", "230"),
            ExcludableCheckBox("Elemental Magic", "231"),
            ExcludableCheckBox("Elves", "232"),
            ExcludableCheckBox("Emotionally Weak Protagonist", "233"),
            ExcludableCheckBox("Empires", "234"),
            ExcludableCheckBox("Enemies Become Allies", "235"),
            ExcludableCheckBox("Enemies Become Lovers", "236"),
            ExcludableCheckBox("Engagement", "237"),
            ExcludableCheckBox("Engineer", "238"),
            ExcludableCheckBox("Enlightenment", "239"),
            ExcludableCheckBox("Episodic", "240"),
            ExcludableCheckBox("Eunuch", "241"),
            ExcludableCheckBox("European Ambience", "242"),
            ExcludableCheckBox("Evil Gods", "243"),
            ExcludableCheckBox("Evil Organizations", "244"),
            ExcludableCheckBox("Evil Protagonist", "245"),
            ExcludableCheckBox("Evil Religions", "246"),
            ExcludableCheckBox("Evolution", "247"),
            ExcludableCheckBox("Exhibitionism", "248"),
            ExcludableCheckBox("Exorcism", "249"),
            ExcludableCheckBox("Eye Powers", "250"),
            ExcludableCheckBox("Fairies", "251"),
            ExcludableCheckBox("Fallen Angels", "252"),
            ExcludableCheckBox("Fallen Nobility", "253"),
            ExcludableCheckBox("Familial Love", "254"),
            ExcludableCheckBox("Familiars", "255"),
            ExcludableCheckBox("Family", "256"),
            ExcludableCheckBox("Family Business", "257"),
            ExcludableCheckBox("Family Conflict", "258"),
            ExcludableCheckBox("Famous Parents", "259"),
            ExcludableCheckBox("Famous Protagonist", "260"),
            ExcludableCheckBox("Fanaticism", "261"),
            ExcludableCheckBox("Fanfiction", "262"),
            ExcludableCheckBox("Fantasy Creatures", "263"),
            ExcludableCheckBox("Fantasy World", "264"),
            ExcludableCheckBox("Farming", "265"),
            ExcludableCheckBox("Fast Cultivation", "266"),
            ExcludableCheckBox("Fast Learner", "267"),
            ExcludableCheckBox("Fat Protagonist", "268"),
            ExcludableCheckBox("Fat to Fit", "269"),
            ExcludableCheckBox("Fated Lovers", "270"),
            ExcludableCheckBox("Fearless Protagonist", "271"),
            ExcludableCheckBox("Fellatio", "272"),
            ExcludableCheckBox("Female Master", "273"),
            ExcludableCheckBox("Female Protagonist", "274"),
            ExcludableCheckBox("Female to Male", "275"),
            ExcludableCheckBox("Feng Shui", "276"),
            ExcludableCheckBox("Firearms", "277"),
            ExcludableCheckBox("First Love", "278"),
            ExcludableCheckBox("First-time Intercourse", "279"),
            ExcludableCheckBox("Flashbacks", "280"),
            ExcludableCheckBox("Fleet Battles", "281"),
            ExcludableCheckBox("Folklore", "282"),
            ExcludableCheckBox("Forced into a Relationship", "283"),
            ExcludableCheckBox("Forced Living Arrangements", "284"),
            ExcludableCheckBox("Forced Marriage", "285"),
            ExcludableCheckBox("Forgetful Protagonist", "286"),
            ExcludableCheckBox("Former Hero", "287"),
            ExcludableCheckBox("Found Family", "288"),
            ExcludableCheckBox("Fox Spirits", "289"),
            ExcludableCheckBox("Friends Become Enemies", "290"),
            ExcludableCheckBox("Friendship", "291"),
            ExcludableCheckBox("Fujoshi", "292"),
            ExcludableCheckBox("Futanari", "293"),
            ExcludableCheckBox("Futuristic Setting", "294"),
            ExcludableCheckBox("Galge", "295"),
            ExcludableCheckBox("Gambling", "296"),
            ExcludableCheckBox("Game Elements", "297"),
            ExcludableCheckBox("Game Ranking System", "298"),
            ExcludableCheckBox("Gamers", "299"),
            ExcludableCheckBox("Gangs", "300"),
            ExcludableCheckBox("Gate to Another World", "301"),
            ExcludableCheckBox("Genderless Protagonist", "302"),
            ExcludableCheckBox("Generals", "303"),
            ExcludableCheckBox("Genetic Modifications", "304"),
            ExcludableCheckBox("Genies", "305"),
            ExcludableCheckBox("Genius Protagonist", "306"),
            ExcludableCheckBox("Ghosts", "307"),
            ExcludableCheckBox("Gladiators", "308"),
            ExcludableCheckBox("Glasses-wearing Love Interests", "309"),
            ExcludableCheckBox("Glasses-wearing Protagonist", "310"),
            ExcludableCheckBox("Goblins", "311"),
            ExcludableCheckBox("God Protagonist", "312"),
            ExcludableCheckBox("God-human Relationship", "313"),
            ExcludableCheckBox("Goddesses", "314"),
            ExcludableCheckBox("Godly Powers", "315"),
            ExcludableCheckBox("Gods", "316"),
            ExcludableCheckBox("Golems", "317"),
            ExcludableCheckBox("Gore", "318"),
            ExcludableCheckBox("Grave Keepers", "319"),
            ExcludableCheckBox("Grinding", "320"),
            ExcludableCheckBox("Guardian Relationship", "321"),
            ExcludableCheckBox("Guideverse", "322"),
            ExcludableCheckBox("Guilds", "323"),
            ExcludableCheckBox("Gunfighters", "324"),
            ExcludableCheckBox("Hackers", "325"),
            ExcludableCheckBox("Half-human Protagonist", "326"),
            ExcludableCheckBox("Handjob", "327"),
            ExcludableCheckBox("Handsome Male Lead", "328"),
            ExcludableCheckBox("Hard-Working Protagonist", "329"),
            ExcludableCheckBox("Harem-seeking Protagonist", "330"),
            ExcludableCheckBox("Harsh Training", "331"),
            ExcludableCheckBox("Hated Protagonist", "332"),
            ExcludableCheckBox("Healers", "333"),
            ExcludableCheckBox("Heartwarming", "334"),
            ExcludableCheckBox("Heaven", "335"),
            ExcludableCheckBox("Heavenly Tribulation", "336"),
            ExcludableCheckBox("Hell", "337"),
            ExcludableCheckBox("Helpful Protagonist", "338"),
            ExcludableCheckBox("Herbalist", "339"),
            ExcludableCheckBox("Heroes", "340"),
            ExcludableCheckBox("Heterochromia", "341"),
            ExcludableCheckBox("Hidden Abilities", "342"),
            ExcludableCheckBox("Hiding True Abilities", "343"),
            ExcludableCheckBox("Hiding True Identity", "344"),
            ExcludableCheckBox("Hikikomori", "345"),
            ExcludableCheckBox("Homunculus", "346"),
            ExcludableCheckBox("Honest Protagonist", "347"),
            ExcludableCheckBox("Hospital", "348"),
            ExcludableCheckBox("Hot-blooded Protagonist", "349"),
            ExcludableCheckBox("Human Experimentation", "350"),
            ExcludableCheckBox("Human Weapon", "351"),
            ExcludableCheckBox("Human-Nonhuman Relationship", "352"),
            ExcludableCheckBox("Humanoid Protagonist", "353"),
            ExcludableCheckBox("Hunters", "354"),
            ExcludableCheckBox("Hypnotism", "355"),
            ExcludableCheckBox("Identity Crisis", "356"),
            ExcludableCheckBox("Imaginary Friend", "357"),
            ExcludableCheckBox("Immortals", "358"),
            ExcludableCheckBox("Imperial Harem", "359"),
            ExcludableCheckBox("Incest", "360"),
            ExcludableCheckBox("Incubus", "361"),
            ExcludableCheckBox("Indecisive Protagonist", "362"),
            ExcludableCheckBox("Industrialization", "363"),
            ExcludableCheckBox("Inferiority Complex", "364"),
            ExcludableCheckBox("Inheritance", "365"),
            ExcludableCheckBox("Inscriptions", "366"),
            ExcludableCheckBox("Insects", "367"),
            ExcludableCheckBox("Interconnected Storylines", "368"),
            ExcludableCheckBox("Interdimensional Travel", "369"),
            ExcludableCheckBox("Introverted Protagonist", "370"),
            ExcludableCheckBox("Investigations", "371"),
            ExcludableCheckBox("Invisibility", "372"),
            ExcludableCheckBox("Jack of All Trades", "373"),
            ExcludableCheckBox("Jealousy", "374"),
            ExcludableCheckBox("Jiangshi", "375"),
            ExcludableCheckBox("Jobless Class", "376"),
            ExcludableCheckBox("JSDF", "377"),
            ExcludableCheckBox("Kidnappings", "378"),
            ExcludableCheckBox("Kind Love Interests", "379"),
            ExcludableCheckBox("Kingdom Building", "380"),
            ExcludableCheckBox("Kingdoms", "381"),
            ExcludableCheckBox("Knights", "382"),
            ExcludableCheckBox("Kuudere", "383"),
            ExcludableCheckBox("Lack of Common Sense", "384"),
            ExcludableCheckBox("Language Barrier", "385"),
            ExcludableCheckBox("Late Romance", "386"),
            ExcludableCheckBox("Lawyers", "387"),
            ExcludableCheckBox("Lazy Protagonist", "388"),
            ExcludableCheckBox("Leadership", "389"),
            ExcludableCheckBox("Legends", "390"),
            ExcludableCheckBox("Level System", "391"),
            ExcludableCheckBox("Library", "392"),
            ExcludableCheckBox("Life Extension System", "393"),
            ExcludableCheckBox("Limited Lifespan", "394"),
            ExcludableCheckBox("Livestreaming", "395"),
            ExcludableCheckBox("Living Abroad", "396"),
            ExcludableCheckBox("Living Alone", "397"),
            ExcludableCheckBox("Loli", "398"),
            ExcludableCheckBox("Loneliness", "399"),
            ExcludableCheckBox("Loner Protagonist", "400"),
            ExcludableCheckBox("Long Separations", "401"),
            ExcludableCheckBox("Long-distance Relationship", "402"),
            ExcludableCheckBox("Lost Civilizations", "403"),
            ExcludableCheckBox("Lottery", "404"),
            ExcludableCheckBox("Love at First Sight", "405"),
            ExcludableCheckBox("Love Interest Falls in Love First", "406"),
            ExcludableCheckBox("Love Rivals", "407"),
            ExcludableCheckBox("Love Triangles", "408"),
            ExcludableCheckBox("Lovers Reunited", "409"),
            ExcludableCheckBox("Low-key Protagonist", "410"),
            ExcludableCheckBox("Loyal Subordinates", "411"),
            ExcludableCheckBox("Lucky Protagonist", "412"),
            ExcludableCheckBox("Magic", "413"),
            ExcludableCheckBox("Magic Beasts", "414"),
            ExcludableCheckBox("Magic Formations", "415"),
            ExcludableCheckBox("Magical Girls", "416"),
            ExcludableCheckBox("Magical Space", "417"),
            ExcludableCheckBox("Magical Technology", "418"),
            ExcludableCheckBox("Maids", "419"),
            ExcludableCheckBox("Male Protagonist", "420"),
            ExcludableCheckBox("Male to Female", "421"),
            ExcludableCheckBox("Male Yandere", "422"),
            ExcludableCheckBox("Management", "423"),
            ExcludableCheckBox("Mangaka", "424"),
            ExcludableCheckBox("Manipulative Characters", "425"),
            ExcludableCheckBox("Manly Gay Couple", "426"),
            ExcludableCheckBox("Marriage", "427"),
            ExcludableCheckBox("Marriage of Convenience", "428"),
            ExcludableCheckBox("Martial Spirits", "429"),
            ExcludableCheckBox("Masochistic Characters", "430"),
            ExcludableCheckBox("Master-Disciple Relationship", "431"),
            ExcludableCheckBox("Master-Servant Relationship", "432"),
            ExcludableCheckBox("Masturbation", "433"),
            ExcludableCheckBox("Matriarchy", "434"),
            ExcludableCheckBox("Mature Protagonist", "435"),
            ExcludableCheckBox("Medical Knowledge", "436"),
            ExcludableCheckBox("Medieval", "437"),
            ExcludableCheckBox("Mercenaries", "438"),
            ExcludableCheckBox("Merchants", "439"),
            ExcludableCheckBox("Military", "440"),
            ExcludableCheckBox("Mind Break", "441"),
            ExcludableCheckBox("Mind Control", "442"),
            ExcludableCheckBox("Misandry", "443"),
            ExcludableCheckBox("Mismatched Couple", "444"),
            ExcludableCheckBox("Mistaken Identity", "445"),
            ExcludableCheckBox("Misunderstandings", "446"),
            ExcludableCheckBox("MMORPG", "447"),
            ExcludableCheckBox("Mob Protagonist", "448"),
            ExcludableCheckBox("Models", "449"),
            ExcludableCheckBox("Modern Day", "450"),
            ExcludableCheckBox("Modern Knowledge", "451"),
            ExcludableCheckBox("Money Grubber", "452"),
            ExcludableCheckBox("Monster Girls", "453"),
            ExcludableCheckBox("Monster Society", "454"),
            ExcludableCheckBox("Monster Tamer", "455"),
            ExcludableCheckBox("Monsters", "456"),
            ExcludableCheckBox("Movies", "457"),
            ExcludableCheckBox("Mpreg", "458"),
            ExcludableCheckBox("Multiple Identities", "459"),
            ExcludableCheckBox("Multiple Personalities", "460"),
            ExcludableCheckBox("Multiple POV", "461"),
            ExcludableCheckBox("Multiple Protagonists", "462"),
            ExcludableCheckBox("Multiple Realms", "463"),
            ExcludableCheckBox("Multiple Reincarnated Individuals", "464"),
            ExcludableCheckBox("Multiple Timelines", "465"),
            ExcludableCheckBox("Multiple Transported Individuals", "466"),
            ExcludableCheckBox("Murders", "467"),
            ExcludableCheckBox("Music", "468"),
            ExcludableCheckBox("Mutated Creatures", "469"),
            ExcludableCheckBox("Mutations", "470"),
            ExcludableCheckBox("Mute Character", "471"),
            ExcludableCheckBox("Mysterious Family Background", "472"),
            ExcludableCheckBox("Mysterious Illness", "473"),
            ExcludableCheckBox("Mysterious Past", "474"),
            ExcludableCheckBox("Mystery Solving", "475"),
            ExcludableCheckBox("Mythical Beasts", "476"),
            ExcludableCheckBox("Mythology", "477"),
            ExcludableCheckBox("Naive Protagonist", "478"),
            ExcludableCheckBox("Narcissistic Protagonist", "479"),
            ExcludableCheckBox("Nationalism", "480"),
            ExcludableCheckBox("Near-Death Experience", "481"),
            ExcludableCheckBox("Necromancer", "482"),
            ExcludableCheckBox("Neet", "483"),
            ExcludableCheckBox("Netorare", "484"),
            ExcludableCheckBox("Netorase", "485"),
            ExcludableCheckBox("Netori", "486"),
            ExcludableCheckBox("Nightmares", "487"),
            ExcludableCheckBox("Ninjas", "488"),
            ExcludableCheckBox("Nobles", "489"),
            ExcludableCheckBox("Non-humanoid Protagonist", "490"),
            ExcludableCheckBox("Non-linear Storytelling", "491"),
            ExcludableCheckBox("Nudity", "492"),
            ExcludableCheckBox("Nurses", "493"),
            ExcludableCheckBox("Obsessive Love", "494"),
            ExcludableCheckBox("Office Romance", "495"),
            ExcludableCheckBox("Older Love Interests", "496"),
            ExcludableCheckBox("Omegaverse", "497"),
            ExcludableCheckBox("Oneshot", "498"),
            ExcludableCheckBox("Online Romance", "499"),
            ExcludableCheckBox("Onmyouji", "500"),
            ExcludableCheckBox("Orcs", "501"),
            ExcludableCheckBox("Organized Crime", "502"),
            ExcludableCheckBox("Orgy", "503"),
            ExcludableCheckBox("Orphans", "504"),
            ExcludableCheckBox("Otaku", "505"),
            ExcludableCheckBox("Otome Game", "506"),
            ExcludableCheckBox("Outcasts", "507"),
            ExcludableCheckBox("Outdoor Intercourse", "508"),
            ExcludableCheckBox("Outer Space", "509"),
            ExcludableCheckBox("Overpowered Protagonist", "510"),
            ExcludableCheckBox("Overprotective Siblings", "511"),
            ExcludableCheckBox("Pacifist Protagonist", "512"),
            ExcludableCheckBox("Paizuri", "513"),
            ExcludableCheckBox("Parallel Worlds", "514"),
            ExcludableCheckBox("Parasites", "515"),
            ExcludableCheckBox("Parent Complex", "516"),
            ExcludableCheckBox("Parody", "517"),
            ExcludableCheckBox("Part-Time Job", "518"),
            ExcludableCheckBox("Past Plays a Big Role", "519"),
            ExcludableCheckBox("Past Trauma", "520"),
            ExcludableCheckBox("Persistent Love Interests", "521"),
            ExcludableCheckBox("Personality Changes", "522"),
            ExcludableCheckBox("Perverted Protagonist", "523"),
            ExcludableCheckBox("Pets", "524"),
            ExcludableCheckBox("Pharmacist", "525"),
            ExcludableCheckBox("Philosophical", "526"),
            ExcludableCheckBox("Phobias", "527"),
            ExcludableCheckBox("Phoenixes", "528"),
            ExcludableCheckBox("Photography", "529"),
            ExcludableCheckBox("Pill Based Cultivation", "530"),
            ExcludableCheckBox("Pill Concocting", "531"),
            ExcludableCheckBox("Pilots", "532"),
            ExcludableCheckBox("Pirates", "533"),
            ExcludableCheckBox("Playboys", "534"),
            ExcludableCheckBox("Playful Protagonist", "535"),
            ExcludableCheckBox("Poetry", "536"),
            ExcludableCheckBox("Poisons", "537"),
            ExcludableCheckBox("Police", "538"),
            ExcludableCheckBox("Polite Protagonist", "539"),
            ExcludableCheckBox("Politics", "540"),
            ExcludableCheckBox("Polyandry", "541"),
            ExcludableCheckBox("Polygamy", "542"),
            ExcludableCheckBox("Poor Protagonist", "543"),
            ExcludableCheckBox("Poor to Rich", "544"),
            ExcludableCheckBox("Popular Love Interests", "545"),
            ExcludableCheckBox("Possession", "546"),
            ExcludableCheckBox("Possessive Characters", "547"),
            ExcludableCheckBox("Post-apocalyptic", "548"),
            ExcludableCheckBox("Power Couple", "549"),
            ExcludableCheckBox("Power Struggle", "550"),
            ExcludableCheckBox("Pragmatic Protagonist", "551"),
            ExcludableCheckBox("Precognition", "552"),
            ExcludableCheckBox("Pregnancy", "553"),
            ExcludableCheckBox("Pretend Lovers", "554"),
            ExcludableCheckBox("Previous Life Talent", "555"),
            ExcludableCheckBox("Priestesses", "556"),
            ExcludableCheckBox("Priests", "557"),
            ExcludableCheckBox("Prison", "558"),
            ExcludableCheckBox("Proactive Protagonist", "559"),
            ExcludableCheckBox("Programmer", "560"),
            ExcludableCheckBox("Prophecies", "561"),
            ExcludableCheckBox("Prostitutes", "562"),
            ExcludableCheckBox("Protagonist Falls in Love First", "563"),
            ExcludableCheckBox("Protagonist Strong from the Start", "564"),
            ExcludableCheckBox("Protagonist with Multiple Bodies", "565"),
            ExcludableCheckBox("Psychic Powers", "566"),
            ExcludableCheckBox("Psychopaths", "567"),
            ExcludableCheckBox("Puppeteers", "568"),
            ExcludableCheckBox("Quiet Characters", "569"),
            ExcludableCheckBox("Quirky Characters", "570"),
            ExcludableCheckBox("R-15", "571"),
            ExcludableCheckBox("R-18", "572"),
            ExcludableCheckBox("Race Change", "573"),
            ExcludableCheckBox("Racism", "574"),
            ExcludableCheckBox("Rape", "575"),
            ExcludableCheckBox("Rape Victim Becomes Lover", "576"),
            ExcludableCheckBox("Rebellion", "577"),
            ExcludableCheckBox("Reincarnated as a Monster", "578"),
            ExcludableCheckBox("Reincarnated as an Object", "579"),
            ExcludableCheckBox("Reincarnated in a Game World", "580"),
            ExcludableCheckBox("Reincarnated in Another World", "581"),
            ExcludableCheckBox("Reincarnation", "582"),
            ExcludableCheckBox("Religions", "583"),
            ExcludableCheckBox("Reluctant Protagonist", "584"),
            ExcludableCheckBox("Reporters", "585"),
            ExcludableCheckBox("Restaurant", "586"),
            ExcludableCheckBox("Resurrection", "587"),
            ExcludableCheckBox("Returning from Another World", "588"),
            ExcludableCheckBox("Revenge", "589"),
            ExcludableCheckBox("Reverse Harem", "590"),
            ExcludableCheckBox("Reverse Rape", "591"),
            ExcludableCheckBox("Reversible Couple", "592"),
            ExcludableCheckBox("Rich to Poor", "593"),
            ExcludableCheckBox("Righteous Protagonist", "594"),
            ExcludableCheckBox("Rivalry", "595"),
            ExcludableCheckBox("Romantic Subplot", "596"),
            ExcludableCheckBox("Roommates", "597"),
            ExcludableCheckBox("Royalty", "598"),
            ExcludableCheckBox("Ruthless Protagonist", "599"),
            ExcludableCheckBox("Sadistic Characters", "600"),
            ExcludableCheckBox("Saints", "601"),
            ExcludableCheckBox("Salaryman", "602"),
            ExcludableCheckBox("Samurai", "603"),
            ExcludableCheckBox("Saving the World", "604"),
            ExcludableCheckBox("Schemes And Conspiracies", "605"),
            ExcludableCheckBox("Schizophrenia", "606"),
            ExcludableCheckBox("Scientists", "607"),
            ExcludableCheckBox("Sculptors", "608"),
            ExcludableCheckBox("Sealed Power", "609"),
            ExcludableCheckBox("Second Chance", "610"),
            ExcludableCheckBox("Secret Crush", "611"),
            ExcludableCheckBox("Secret Identity", "612"),
            ExcludableCheckBox("Secret Organizations", "613"),
            ExcludableCheckBox("Secret Relationship", "614"),
            ExcludableCheckBox("Secretive Protagonist", "615"),
            ExcludableCheckBox("Secrets", "616"),
            ExcludableCheckBox("Sect Development", "617"),
            ExcludableCheckBox("Seduction", "618"),
            ExcludableCheckBox("Seeing Things Other Humans Can't", "619"),
            ExcludableCheckBox("Selfish Protagonist", "620"),
            ExcludableCheckBox("Selfless Protagonist", "621"),
            ExcludableCheckBox("Seme Protagonist", "622"),
            ExcludableCheckBox("Senpai-Kouhai Relationship", "623"),
            ExcludableCheckBox("Sentient Objects", "624"),
            ExcludableCheckBox("Sentimental Protagonist", "625"),
            ExcludableCheckBox("Serial Killers", "626"),
            ExcludableCheckBox("Servants", "627"),
            ExcludableCheckBox("Seven Deadly Sins", "628"),
            ExcludableCheckBox("Seven Virtues", "629"),
            ExcludableCheckBox("Sex Friends", "630"),
            ExcludableCheckBox("Sex Slaves", "631"),
            ExcludableCheckBox("Sexual Abuse", "632"),
            ExcludableCheckBox("Sexual Cultivation Technique", "633"),
            ExcludableCheckBox("Shameless Protagonist", "634"),
            ExcludableCheckBox("Shapeshifters", "635"),
            ExcludableCheckBox("Sharing A Body", "636"),
            ExcludableCheckBox("Sharp-tongued Characters", "637"),
            ExcludableCheckBox("Shield User", "638"),
            ExcludableCheckBox("Shikigami", "639"),
            ExcludableCheckBox("Short Story", "640"),
            ExcludableCheckBox("Shota", "641"),
            ExcludableCheckBox("Shoujo-Ai Subplot", "642"),
            ExcludableCheckBox("Shounen-Ai Subplot", "643"),
            ExcludableCheckBox("Showbiz", "644"),
            ExcludableCheckBox("Shy Characters", "645"),
            ExcludableCheckBox("Sibling Rivalry", "646"),
            ExcludableCheckBox("Sibling's Care", "647"),
            ExcludableCheckBox("Siblings", "648"),
            ExcludableCheckBox("Siblings Not Related by Blood", "649"),
            ExcludableCheckBox("Sickly Characters", "650"),
            ExcludableCheckBox("Sign Language", "651"),
            ExcludableCheckBox("Singers", "652"),
            ExcludableCheckBox("Single Parent", "653"),
            ExcludableCheckBox("Sister Complex", "654"),
            ExcludableCheckBox("Skill Assimilation", "655"),
            ExcludableCheckBox("Skill Books", "656"),
            ExcludableCheckBox("Skill Creation", "657"),
            ExcludableCheckBox("Slave Harem", "658"),
            ExcludableCheckBox("Slave Protagonist", "659"),
            ExcludableCheckBox("Slaves", "660"),
            ExcludableCheckBox("Sleeping", "661"),
            ExcludableCheckBox("Slow Growth at Start", "662"),
            ExcludableCheckBox("Slow Romance", "663"),
            ExcludableCheckBox("Smart Couple", "664"),
            ExcludableCheckBox("Social Outcasts", "665"),
            ExcludableCheckBox("Soldiers", "666"),
            ExcludableCheckBox("Soul Power", "667"),
            ExcludableCheckBox("Souls", "668"),
            ExcludableCheckBox("Spatial Manipulation", "669"),
            ExcludableCheckBox("Spear Wielder", "670"),
            ExcludableCheckBox("Special Abilities", "671"),
            ExcludableCheckBox("Spies", "672"),
            ExcludableCheckBox("Spirit Advisor", "673"),
            ExcludableCheckBox("Spirit Users", "674"),
            ExcludableCheckBox("Spirits", "675"),
            ExcludableCheckBox("Stalkers", "676"),
            ExcludableCheckBox("Stockholm Syndrome", "677"),
            ExcludableCheckBox("Stoic Characters", "678"),
            ExcludableCheckBox("Store Owner", "679"),
            ExcludableCheckBox("Straight Seme", "680"),
            ExcludableCheckBox("Straight Uke", "681"),
            ExcludableCheckBox("Strategic Battles", "682"),
            ExcludableCheckBox("Strategist", "683"),
            ExcludableCheckBox("Strength-based Social Hierarchy", "684"),
            ExcludableCheckBox("Strong Love Interests", "685"),
            ExcludableCheckBox("Strong to Stronger", "686"),
            ExcludableCheckBox("Stubborn Protagonist", "687"),
            ExcludableCheckBox("Student Council", "688"),
            ExcludableCheckBox("Student-Teacher Relationship", "689"),
            ExcludableCheckBox("Succubus", "690"),
            ExcludableCheckBox("Sudden Strength Gain", "691"),
            ExcludableCheckBox("Sudden Wealth", "692"),
            ExcludableCheckBox("Suicides", "693"),
            ExcludableCheckBox("Summoned Hero", "694"),
            ExcludableCheckBox("Summoning Magic", "695"),
            ExcludableCheckBox("Survival", "696"),
            ExcludableCheckBox("Survival Game", "697"),
            ExcludableCheckBox("Sword And Magic", "698"),
            ExcludableCheckBox("Sword Wielder", "699"),
            ExcludableCheckBox("System Administrator", "700"),
            ExcludableCheckBox("Teachers", "701"),
            ExcludableCheckBox("Teamwork", "702"),
            ExcludableCheckBox("Technological Gap", "703"),
            ExcludableCheckBox("Tentacles", "704"),
            ExcludableCheckBox("Terminal Illness", "705"),
            ExcludableCheckBox("Terrorists", "706"),
            ExcludableCheckBox("Thieves", "707"),
            ExcludableCheckBox("Threesome", "708"),
            ExcludableCheckBox("Thriller", "709"),
            ExcludableCheckBox("Time Loop", "710"),
            ExcludableCheckBox("Time Manipulation", "711"),
            ExcludableCheckBox("Time Paradox", "712"),
            ExcludableCheckBox("Time Skip", "713"),
            ExcludableCheckBox("Time Travel", "714"),
            ExcludableCheckBox("Timid Protagonist", "715"),
            ExcludableCheckBox("Tomboyish Female Lead", "716"),
            ExcludableCheckBox("Torture", "717"),
            ExcludableCheckBox("Toys", "718"),
            ExcludableCheckBox("Tragic Past", "719"),
            ExcludableCheckBox("Transformation Ability", "720"),
            ExcludableCheckBox("Transmigration", "721"),
            ExcludableCheckBox("Transplanted Memories", "722"),
            ExcludableCheckBox("Transported into a Game World", "723"),
            ExcludableCheckBox("Transported Modern Structure", "724"),
            ExcludableCheckBox("Transported to Another World", "725"),
            ExcludableCheckBox("Trap", "726"),
            ExcludableCheckBox("Tribal Society", "727"),
            ExcludableCheckBox("Trickster", "728"),
            ExcludableCheckBox("Tsundere", "729"),
            ExcludableCheckBox("Twins", "730"),
            ExcludableCheckBox("Twisted Personality", "731"),
            ExcludableCheckBox("Ugly Protagonist", "732"),
            ExcludableCheckBox("Ugly to Beautiful", "733"),
            ExcludableCheckBox("Unconditional Love", "734"),
            ExcludableCheckBox("Underestimated Protagonist", "735"),
            ExcludableCheckBox("Unique Cultivation Technique", "736"),
            ExcludableCheckBox("Unique Weapon User", "737"),
            ExcludableCheckBox("Unique Weapons", "738"),
            ExcludableCheckBox("Unlimited Flow", "739"),
            ExcludableCheckBox("Unlucky Protagonist", "740"),
            ExcludableCheckBox("Unreliable Narrator", "741"),
            ExcludableCheckBox("Unrequited Love", "742"),
            ExcludableCheckBox("Valkyries", "743"),
            ExcludableCheckBox("Vampires", "744"),
            ExcludableCheckBox("Villainess Noble Girls", "745"),
            ExcludableCheckBox("Virtual Reality", "746"),
            ExcludableCheckBox("Vocaloid", "747"),
            ExcludableCheckBox("Voice Actors", "748"),
            ExcludableCheckBox("Voyeurism", "749"),
            ExcludableCheckBox("Waiters", "750"),
            ExcludableCheckBox("War Records", "751"),
            ExcludableCheckBox("Wars", "752"),
            ExcludableCheckBox("Weak Protagonist", "753"),
            ExcludableCheckBox("Weak to Strong", "754"),
            ExcludableCheckBox("Wealthy Characters", "755"),
            ExcludableCheckBox("Werebeasts", "756"),
            ExcludableCheckBox("Wishes", "757"),
            ExcludableCheckBox("Witches", "758"),
            ExcludableCheckBox("Wizards", "759"),
            ExcludableCheckBox("World Hopping", "760"),
            ExcludableCheckBox("World Travel", "761"),
            ExcludableCheckBox("World Tree", "762"),
            ExcludableCheckBox("Writers", "763"),
            ExcludableCheckBox("Yandere", "764"),
            ExcludableCheckBox("Youkai", "765"),
            ExcludableCheckBox("Younger Brothers", "766"),
            ExcludableCheckBox("Younger Love Interests", "767"),
            ExcludableCheckBox("Younger Sisters", "768"),
            ExcludableCheckBox("Zombies", "769"),
        ),
    )
}

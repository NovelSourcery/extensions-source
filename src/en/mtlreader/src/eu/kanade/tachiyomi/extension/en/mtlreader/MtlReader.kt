package eu.kanade.tachiyomi.extension.en.mtlreader

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MtlReader :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "MTL Reader"
    override val baseUrl = "https://mtlreader.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val preferences: SharedPreferences by getPreferencesLazy()

    // Note: isNovel=true is set in build.gradle

    private val reverseChapterList: Boolean
        get() = preferences.getBoolean(PREF_REVERSE_CHAPTERS, false)

    private var searchToken: String? = null

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/novels?sort=popular&page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelList(doc)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/novels?sort=latest&page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (searchToken == null) {
            val tokenResponse = client.newCall(GET(baseUrl, headers)).execute()
            val tokenDoc = Jsoup.parse(tokenResponse.body.string())
            searchToken = tokenDoc.selectFirst("input[name=_token]")?.attr("value")
        }

        val url = if (searchToken.isNullOrEmpty()) {
            "$baseUrl/search?input=${java.net.URLEncoder.encode(query, "UTF-8")}"
        } else {
            "$baseUrl/search?_token=$searchToken&input=${java.net.URLEncoder.encode(query, "UTF-8")}"
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    private fun parseNovelList(doc: Document): MangasPage {
        val novels = doc.select(".property_item").mapNotNull { element ->
            try {
                val link = element.selectFirst("h5 a, a[href*=/novels/]")
                    ?: return@mapNotNull null

                val href = link.attr("href")
                if (href.isEmpty() || !href.contains("/novels/")) return@mapNotNull null

                val url = href.removePrefix(baseUrl)

                val img = element.selectFirst("img")
                val imgAltTitle = img?.attr("alt")?.trim()

                val title = imgAltTitle?.takeIf { it.isNotEmpty() && it != "cover" }
                    ?: link.text().trim()
                        .ifEmpty { element.selectFirst("h5")?.text()?.trim() ?: "" }

                if (title.isEmpty()) return@mapNotNull null

                val cover = img?.attr("src")?.ifEmpty { null }

                SManga.create().apply {
                    this.url = url
                    this.title = title
                    thumbnail_url = cover?.let {
                        if (it.startsWith("http")) it else "$baseUrl$it"
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        val hasNextPage = doc.selectFirst("li.page-item a[rel=next]") != null ||
            doc.selectFirst(".pagination .next") != null ||
            doc.selectFirst("a.page-link:contains(Next)") != null
        return MangasPage(novels, hasNextPage)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            title = doc.selectFirst(".agent-title")?.text()?.trim() ?: ""

            thumbnail_url = doc.selectFirst(".agent-p-img > img")?.attr("src")
                ?: doc.selectFirst("img.thumbnail")?.attr("src")
                ?: doc.selectFirst(".property_img img")?.attr("src")

            description = doc.selectFirst("#editdescription")?.text()?.trim()
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst(".novel-description")?.text()
                ?: ""

            author = doc.selectFirst("i.fa-user")?.parent()?.text()
                ?.replace("Author:", "")?.trim()
                ?.replace("Author：", "")?.trim()
                ?: doc.selectFirst(".novel-author a")?.text()
                ?: doc.selectFirst(".author")?.text()

            val aliasesElement = doc.select(".agent-p-contact div.mb-2:contains(Aliases:)").firstOrNull()
            val aliasesText = aliasesElement?.text()
                ?.replace("Aliases:", "")?.trim()
                ?.replace("Aliases：", "")?.trim()

            if (!aliasesText.isNullOrEmpty()) {
                genre = "Alt Title: $aliasesText"
            }

            val genres = doc.select(".novel-genre a, .genre a").map { it.text() }
            if (genres.isNotEmpty()) {
                genre = if (genre.isNullOrEmpty()) {
                    genres.joinToString(", ")
                } else {
                    "$genre | ${genres.joinToString(", ")}"
                }
            }

            status = SManga.UNKNOWN
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val allChapters = mutableListOf<SChapter>()
        var page = 1
        var hasNextPage = true
        val mangaUrl = response.request.url.encodedPath
        var currentDoc = Jsoup.parse(response.body.string())

        while (hasNextPage) {
            val chapters = parseChaptersFromPage(currentDoc)
            allChapters.addAll(chapters)

            val nextPageElement = currentDoc.selectFirst(
                """
                .page-item a[rel="next"],
                .pagination-scrollbar a.page-link:contains(›)
                """.trimIndent(),
            )

            if (nextPageElement != null && nextPageElement.hasAttr("href")) {
                try {
                    val nextPageUrl = nextPageElement.attr("href")
                    val resp = client.newCall(GET(nextPageUrl, headers)).execute()
                    currentDoc = Jsoup.parse(resp.body.string())
                    page++

                    if (page > 100) break
                } catch (e: Exception) {
                    break
                }
            } else {
                hasNextPage = false
            }
        }

        return if (reverseChapterList) allChapters.reversed() else allChapters
    }

    private fun parseChaptersFromPage(doc: Document): List<SChapter> {
        return doc.select("table.table-hover tbody tr, table.table tbody tr").mapNotNull { row ->
            try {
                val link = row.selectFirst("a[href*=/chapters/]") ?: return@mapNotNull null
                val chapterUrl = link.attr("href").replace(baseUrl, "")
                val chapterName = link.text().trim()
                val dateText = row.selectFirst("td:last-child")?.text()?.trim() ?: ""

                SChapter.create().apply {
                    url = chapterUrl
                    name = chapterName
                    date_upload = parseRelativeDate(dateText)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.encodedPath
        return listOf(Page(0, url))
    }

    override fun imageUrlParse(response: Response): String = ""

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = Jsoup.parse(response.body.string())

        doc.select("ins, script, .mtlreader, .fb-like, nav, header, footer, .ads, noscript").remove()

        var contentDiv: Element? = null

        contentDiv = doc.selectFirst("div[style*=\"font-family: Arial\"][style*=\"font-size: 18px\"]")

        if (contentDiv == null) {
            contentDiv = doc.selectFirst("div[style*=\"font-size: 18px\"]")
        }

        // MTLReader often uses classes like "a1b2c3d4e5f6" for content divs
        if (contentDiv == null) {
            val hashClassRegex = Regex("^[0-9a-f]{8,}$", RegexOption.IGNORE_CASE)
            val allDivs = doc.select("div[class]")
            for (div in allDivs) {
                val classNames = div.classNames()
                val hasHashClass = classNames.any { hashClassRegex.matches(it) }
                if (hasHashClass) {
                    val text = div.text().trim()
                    if (text.length > 100) {
                        contentDiv = div
                        break
                    }
                }
            }
        }

        if (contentDiv == null) {
            val titleContainer = doc.selectFirst("div.container:has(div[style*=\"font-size: 30px\"])")
            if (titleContainer != null) {
                val nextContainer = titleContainer.nextElementSibling()
                if (nextContainer != null && nextContainer.tagName() == "div" && nextContainer.hasClass("container")) {
                    val potentialDivs = nextContainer.select("div")
                    for (div in potentialDivs) {
                        val text = div.text().trim()
                        if (text.length > 500) { // Substantial chapter content
                            contentDiv = div
                            break
                        }
                    }
                }
            }
        }

        if (contentDiv == null) {
            var maxTextLength = 0
            var bestDiv: Element? = null

            val allDivs = doc.select("div")
            for (div in allDivs) {
                if (div.hasClass("container") || div.hasClass("row") || div.hasClass("col")) continue

                val text = div.ownText().trim() // Use ownText to avoid counting nested div text
                val fullText = div.text().trim()

                val paragraphCount = div.select("p").size
                val contentScore = if (paragraphCount > 5) fullText.length else text.length

                if (contentScore > maxTextLength && fullText.length > 500) {
                    maxTextLength = contentScore
                    bestDiv = div
                }
            }

            contentDiv = bestDiv
        }

        return contentDiv?.html()?.trim() ?: ""
    }

    private fun parseRelativeDate(dateStr: String): Long {
        if (dateStr.isEmpty()) return 0L

        val calendar = Calendar.getInstance()
        val date = dateStr.lowercase(Locale.US)

        return try {
            when {
                date.contains("ago") -> {
                    val match = Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""").find(date)
                    if (match != null) {
                        val amount = match.groupValues[1].toInt()
                        val unit = match.groupValues[2]

                        when (unit) {
                            "second" -> calendar.add(Calendar.SECOND, -amount)
                            "minute" -> calendar.add(Calendar.MINUTE, -amount)
                            "hour" -> calendar.add(Calendar.HOUR_OF_DAY, -amount)
                            "day" -> calendar.add(Calendar.DAY_OF_MONTH, -amount)
                            "week" -> calendar.add(Calendar.WEEK_OF_YEAR, -amount)
                            "month" -> calendar.add(Calendar.MONTH, -amount)
                            "year" -> calendar.add(Calendar.YEAR, -amount)
                        }
                        calendar.timeInMillis
                    } else {
                        0L
                    }
                }

                else -> {
                    try {
                        SimpleDateFormat("MMM d, yyyy", Locale.US).parse(dateStr)?.time ?: 0L
                    } catch (e: Exception) {
                        0L
                    }
                }
            }
        } catch (e: Exception) {
            0L
        }
    }

    override fun getFilterList(): FilterList = FilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_REVERSE_CHAPTERS
            title = "Reverse Chapter List"
            summary = "Show chapters in oldest-to-newest order instead of newest-to-oldest."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_REVERSE_CHAPTERS = "pref_reverse_chapters"
    }
}

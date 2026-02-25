package eu.kanade.tachiyomi.extension.en.novelsknight

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class NovelsKnight :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "Novels Knight"
    override val baseUrl = "https://novelsknight.punchmanga.online"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/series/?page=$page&order=popular", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelList(doc)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/series/?page=$page&order=update", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/series/".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            if (query.isNotEmpty()) {
                addQueryParameter("s", query)
            }

            filters.forEach { filter ->
                when (filter) {
                    is StatusFilter -> {
                        val status = filter.toUriPart()
                        if (status.isNotEmpty()) {
                            addQueryParameter("status", status)
                        }
                    }

                    is OrderFilter -> addQueryParameter("order", filter.toUriPart())

                    is GenreFilter -> {
                        filter.state.filter { it.state }.forEach { genre ->
                            addQueryParameter("genre[]", genre.uriPart)
                        }
                    }

                    is TypeFilter -> {
                        filter.state.filter { it.state }.forEach { type ->
                            addQueryParameter("type[]", type.uriPart)
                        }
                    }

                    else -> {}
                }
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    private fun parseNovelList(doc: Document): MangasPage {
        val novels = doc.select("article.maindet").mapNotNull { article ->
            try {
                val thumbDiv = article.selectFirst("div.mdthumb") ?: return@mapNotNull null
                val link = thumbDiv.selectFirst("a[href]") ?: return@mapNotNull null
                val img = thumbDiv.selectFirst("img")

                val url = link.attr("href").replace(baseUrl, "")
                val title = img?.attr("alt") ?: img?.attr("title") ?: link.attr("oldtitle") ?: link.attr("title") ?: ""
                val cover = img?.attr("src")

                SManga.create().apply {
                    this.url = url
                    this.title = title.trim()
                    thumbnail_url = cover
                }
            } catch (e: Exception) {
                null
            }
        }

        val hasNextPage = doc.selectFirst("a.r:contains(Next)") != null ||
            doc.selectFirst("a.next.page-numbers") != null ||
            doc.selectFirst(".hpage a.r") != null ||
            doc.selectFirst(".pagination a:contains(next)") != null ||
            doc.selectFirst(".wp-pagenavi a.nextpostslink") != null
        return MangasPage(novels, hasNextPage)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            // Title from h1.entry-title
            title = doc.selectFirst("h1.entry-title")?.text()?.trim() ?: ""

            // Cover from div.sertothumb img
            thumbnail_url = doc.selectFirst("div.sertothumb img")?.attr("src")

            // Description from div.sersysn div.sersys
            description = doc.selectFirst("div.sersysn div.sersys")?.text()?.trim()

            // Author from span.sername:contains(Author) + span.serval
            author = doc.selectFirst("div.serl:has(span.sername:contains(Author)) span.serval")?.text()?.trim()
                ?: doc.selectFirst("div.serl:has(span.sername:contains(Author)) span.serval a")?.text()?.trim()

            // Genre from div.sertogenre a
            genre = doc.select("div.sertogenre a").joinToString(", ") { it.text().trim() }

            // Status from div.sertostat span
            val statusText = doc.selectFirst("div.sertostat span")?.text()?.lowercase() ?: ""
            status = when {
                statusText.contains("ongoing") -> SManga.ONGOING
                statusText.contains("completed") -> SManga.COMPLETED
                statusText.contains("hiatus") -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    // Per LN Reader plugin: reverseChapters=true
    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())

        // Per LN Reader plugin: reverseChapters=true - so we DO reverse the chapter list
        return doc.select("div.eplister ul li").mapNotNull { li ->
            try {
                val link = li.selectFirst("a[href]") ?: return@mapNotNull null
                val chapterUrl = link.attr("href").replace(baseUrl, "")

                val chapterNum = li.selectFirst("div.epl-num")?.text()?.trim() ?: ""
                val chapterTitle = li.selectFirst("div.epl-title")?.text()?.trim() ?: ""
                // Date from div.epl-date
                val dateText = li.selectFirst("div.epl-date")?.text()?.trim() ?: ""

                SChapter.create().apply {
                    url = chapterUrl
                    name = if (chapterTitle.isNotEmpty()) {
                        if (chapterNum.isNotEmpty()) "$chapterNum - $chapterTitle" else chapterTitle
                    } else {
                        chapterNum.ifEmpty { "Chapter" }
                    }
                    date_upload = parseDate(dateText)
                }
            } catch (e: Exception) {
                null
            }
        }.reversed() // Per LN Reader plugin: reverseChapters=true
    }

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.encodedPath
        return listOf(Page(0, url))
    }

    override fun imageUrlParse(response: Response): String = ""

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val html = response.body.string()
        val doc = Jsoup.parse(html)

        doc.select("script, ins, .ads, noscript").remove()

        // Primary: Use epcontent selector
        val epcontent = doc.selectFirst("div.epcontent.entry-content[itemprop=text]")
            ?: doc.selectFirst("div.epcontent[itemprop=text]")
            ?: doc.selectFirst("div.entry-content[itemprop=text]")
            ?: doc.selectFirst("div.epcontent.entry-content")
            ?: doc.selectFirst("div.epcontent")
            ?: doc.selectFirst("div.entry-content")

        if (preferences.getBoolean(PREF_RAW_HTML, false)) {
            return epcontent?.html() ?: doc.html()
        }

        if (epcontent != null) {
            epcontent.select(".bottomnav, .chapternav, .nextprev").remove()
            return epcontent.html()
        }

        val contentMatch = Regex("""<div[^>]*class="epcontent[^>]*>(.*?)<div[^>]*class="?bottomnav""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)

        if (contentMatch != null) {
            val paragraphs = Regex("""<p[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(contentMatch)
                .map { "<p>${it.groupValues[1]}</p>" }
                .joinToString("\n")
            if (paragraphs.isNotEmpty()) return paragraphs
        }

        return ""
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val rawHtmlPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_RAW_HTML
            title = "Return raw HTML"
            summary = "If enabled, returns the raw HTML of the chapter content instead of parsed text. Useful for custom parsers."
            setDefaultValue(false)
        }
        screen.addPreference(rawHtmlPref)
    }

    companion object {
        private const val PREF_RAW_HTML = "pref_raw_html"
    }

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isEmpty()) return 0L
        return try {
            SimpleDateFormat("MMMM d, yyyy", Locale.US).parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            try {
                SimpleDateFormat("MMM d, yyyy", Locale.US).parse(dateStr)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        }
    }

    override fun getFilterList(): FilterList = FilterList(
        StatusFilter(),
        OrderFilter(),
        Filter.Header("Genres (multiple selection)"),
        GenreFilter(),
        Filter.Header("Types (multiple selection)"),
        TypeFilter(),
    )

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("All", "Ongoing", "Hiatus", "Completed"),
        ) {
        fun toUriPart() = when (state) {
            1 -> "ongoing"
            2 -> "hiatus"
            3 -> "completed"
            else -> ""
        }
    }

    private class OrderFilter :
        Filter.Select<String>(
            "Order by",
            arrayOf("Default", "A-Z", "Z-A", "Latest Update", "Latest Added", "Popular", "Rating"),
        ) {
        fun toUriPart() = when (state) {
            1 -> "title"
            2 -> "titlereverse"
            3 -> "update"
            4 -> "latest"
            5 -> "popular"
            6 -> "rating"
            else -> ""
        }
    }

    private class GenreCheckBox(name: String, val uriPart: String) : Filter.CheckBox(name)

    private class GenreFilter :
        Filter.Group<GenreCheckBox>(
            "Genres",
            listOf(
                GenreCheckBox("City", "city"),
                GenreCheckBox("Cultivation", "cultivation"),
                GenreCheckBox("Faloo", "faloo"),
                GenreCheckBox("Fan fiction", "fan-fiction"),
                GenreCheckBox("Fanfiction", "fanfiction"),
                GenreCheckBox("Fanqie", "fanqie"),
                GenreCheckBox("Fantasy", "fantasy"),
                GenreCheckBox("Horror", "horror"),
                GenreCheckBox("Immortal", "immortal"),
                GenreCheckBox("Infinite Heavens", "infinite-heavens"),
                GenreCheckBox("Infinity", "infinity"),
                GenreCheckBox("Light novel", "light-novel"),
                GenreCheckBox("Martial arts", "martial-arts"),
                GenreCheckBox("Military history", "military-history"),
                GenreCheckBox("Novel", "novel"),
                GenreCheckBox("Qidian", "qidian"),
                GenreCheckBox("Rebirth", "rebirth"),
                GenreCheckBox("Romance", "romance"),
                GenreCheckBox("Sci-fi online game", "sc-fi-online-game"),
                GenreCheckBox("Science fiction online games", "sci-fi-online-games"),
                GenreCheckBox("Sport", "sport"),
                GenreCheckBox("Sports", "sports"),
                GenreCheckBox("System", "system"),
                GenreCheckBox("Time travel", "time-travel"),
                GenreCheckBox("Travel", "travel"),
                GenreCheckBox("Unlimited Heavens", "unlimited-heavens"),
                GenreCheckBox("Urban", "urban"),
            ),
        )

    private class TypeCheckBox(name: String, val uriPart: String) : Filter.CheckBox(name)

    private class TypeFilter :
        Filter.Group<TypeCheckBox>(
            "Types",
            listOf(
                TypeCheckBox("Chinese", "chinesse"),
                TypeCheckBox("Ciweimao", "ciweimao"),
                TypeCheckBox("Entertainment", "entertainment"),
                TypeCheckBox("Faloo", "faloo"),
                TypeCheckBox("Fan fiction", "fan-fiction"),
                TypeCheckBox("Fanqie", "fanqie"),
                TypeCheckBox("Fantasy", "fantasy"),
                TypeCheckBox("Harry Potter", "harry-potter"),
                TypeCheckBox("Horror", "horror"),
                TypeCheckBox("Martial arts", "martial-arts"),
                TypeCheckBox("Marvel", "marvel"),
                TypeCheckBox("Naruto", "naruto"),
                TypeCheckBox("NBA", "nba"),
                TypeCheckBox("Novel", "novel"),
                TypeCheckBox("One Piece", "one-piece"),
                TypeCheckBox("Online game", "online-game"),
                TypeCheckBox("Qidian", "qidian"),
                TypeCheckBox("Qimao", "qimao"),
                TypeCheckBox("Romance", "romance"),
                TypeCheckBox("Sci-Fi", "sci-fi"),
                TypeCheckBox("Urban", "urban"),
                TypeCheckBox("Web Novel", "web-novel"),
            ),
        )
}

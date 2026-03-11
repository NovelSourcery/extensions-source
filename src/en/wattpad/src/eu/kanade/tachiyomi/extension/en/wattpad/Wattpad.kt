package eu.kanade.tachiyomi.extension.en.wattpad

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
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
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Wattpad :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "Wattpad"
    override val baseUrl = "https://www.wattpad.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val json = Json { ignoreUnknownKeys = true }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val excludeLocked: Boolean
        get() = preferences.getBoolean(PREF_EXCLUDE_LOCKED, false)

    private val apiHeaders: Headers
        get() = headersBuilder()
            .add("Authorization", "IwKhVmNM7VXhnsVb0BabhS")
            .build()

    // region Popular

    override fun popularMangaRequest(page: Int): Request {
        val offset = (page - 1) * 20
        return GET(
            "https://api.wattpad.com/v5/hotlist?tags=&language=1&limit=20&offset=$offset",
            apiHeaders,
        )
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = json.decodeFromString<WattpadStoriesResponse>(response.body.string())
        val mangas = result.stories.map { it.toSManga() }
        return MangasPage(mangas, mangas.size >= 20)
    }

    // endregion

    // region Latest

    override fun latestUpdatesRequest(page: Int): Request {
        val offset = (page - 1) * 20
        return GET(
            "$baseUrl/v4/stories?fields=stories(id,user(name,avatar,fullname),title,cover,description,mature,completed,voteCount,readCount,categories,url,numParts,rankings,firstPartId,tags,isPaywalled),nextUrl,total&filter=new&language=1&mature=0&query=%23&limit=20&offset=$offset",
            apiHeaders,
        )
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // endregion

    // region Search

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val offset = (page - 1) * 20
        var statusParam = ""

        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> if (filter.state == 1) statusParam = "&filter=complete"
                else -> {}
            }
        }

        return GET(
            "$baseUrl/v4/search/stories?query=$query$statusParam&free=1&fields=stories(title,cover,url),nexturl&limit=20&mature=true&offset=$offset",
            headers,
        )
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // endregion

    // region Details

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headersBuilder().add("Referer", "$baseUrl/").build())

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()

        val isPaid = doc.selectFirst("[data-testid=block-part-icon]") != null
        val rawDesc = doc.selectFirst(".glL-c")?.text() ?: ""
        val description = if (isPaid) "!! Contains Paid Chapters !!\n$rawDesc" else rawDesc

        val statusText = doc.selectFirst(".typography-label-small-semi")?.text()
        val status = when (statusText) {
            "Complete" -> SManga.COMPLETED
            "Ongoing" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }

        return SManga.create().apply {
            title = doc.selectFirst(".gF-N5")?.text() ?: ""
            this.description = description
            thumbnail_url = doc.select(".cover__BlyZa")?.attr("src")
            author = doc.selectFirst(".af6dp")?.text()
            genre = doc.select("a[href*=/stories/] span.typography-label-small-semi, .tag-items a, .AMIOO a")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString()
            this.status = status
        }
    }

    // endregion

    // region Chapters

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        return doc.select(".pPt69 .Y26Ib ul li").mapNotNull { el ->
            val linkEl = el.selectFirst("a") ?: return@mapNotNull null
            val titleText = el.selectFirst("a div div")?.text() ?: ""
            val isLocked = el.selectFirst("[data-testid=block-part-icon]") != null

            if (isLocked && excludeLocked) return@mapNotNull null

            val displayTitle = if (isLocked) "\uD83D\uDD12 $titleText" else titleText
            val dateText = el.selectFirst(".bSGSB, [class*=bSGSB]")?.text()?.trim()

            SChapter.create().apply {
                name = displayTitle
                url = linkEl.attr("href").removePrefix(baseUrl)
                chapter_number = (el.elementSiblingIndex() + 1).toFloat()
                date_upload = parseDate(dateText)
            }
        }.reversed()
    }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return try {
            DATE_FORMAT.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    // endregion

    // region Pages

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

    override suspend fun fetchPageText(page: Page): String {
        val url = "$baseUrl${page.url}"
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
        val title = doc.selectFirst("header h1")?.text() ?: ""

        // Extract chapter text from window.prefetched JSON
        val scriptContent = doc.select("script").map { it.data() }
            .firstOrNull { it.contains("window.prefetched") } ?: return "<h1>$title</h1>"

        val jsonStr = Regex("""window\.prefetched\s*=\s*(\{.+?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            .find(scriptContent)?.groupValues?.get(1) ?: return "<h1>$title</h1>"

        val prefetched = try {
            json.decodeFromString<PrefetchedData>(jsonStr)
        } catch (_: Exception) {
            return "<h1>$title</h1>"
        }

        val decoded = org.jsoup.parser.Parser.unescapeEntities(prefetched.storyText, false)
        return "<h1>$title</h1>$decoded"
    }

    @Serializable
    data class PrefetchedData(
        @kotlinx.serialization.SerialName("storyText") val storyText: String = "",
    )

    // endregion

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    // region Helpers

    private fun WattpadStory.toSManga() = SManga.create().apply {
        title = this@toSManga.title
        url = this@toSManga.url.removePrefix("https://www.wattpad.com")
        thumbnail_url = cover
    }

    private fun Response.asJsoup(): Document = Jsoup.parse(body.string(), request.url.toString())

    // endregion

    // region Filters

    override fun getFilterList(): FilterList = FilterList(
        GenreFilter(),
        OrderByFilter(),
        StatusFilter(),
    )

    private class GenreFilter :
        Filter.Select<String>(
            "Genre",
            GENRE_VALUES.toTypedArray(),
        )

    private class OrderByFilter :
        Filter.Select<String>(
            "Order by",
            arrayOf("Hot", "New"),
        )

    private class StatusFilter :
        Filter.Select<String>(
            "Status (search only)",
            arrayOf("All", "Completed"),
        )

    companion object {
        private const val PREF_EXCLUDE_LOCKED = "wattpad_exclude_locked"
        private val DATE_FORMAT = SimpleDateFormat("EEE, MMM d, yyyy", Locale.US)

        private val GENRE_VALUES = listOf(
            "Adventure", "Contemporarylit", "Diverselit", "Fanfiction", "Fantasy",
            "Historicalfiction", "Horror", "Humor", "Lgbt", "Mystery", "Newadult",
            "Nonfiction", "Paranormal", "Poetry", "Romance", "Sciencefiction",
            "Shortstory", "Teenfiction", "Thriller", "Werewolf",
        )
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        CheckBoxPreference(screen.context).apply {
            key = PREF_EXCLUDE_LOCKED
            title = "Exclude locked chapters"
            summary = "Hide chapters that require payment"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    // endregion

    // region Data classes

    @Serializable
    data class WattpadStoriesResponse(
        val stories: List<WattpadStory> = emptyList(),
    )

    @Serializable
    data class WattpadStory(
        val title: String = "",
        val url: String = "",
        val cover: String = "",
    )

    // endregion
}

package eu.kanade.tachiyomi.novelextension.en.crimsonscrolls

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.formattedText
import keiyoushi.utils.stripChapterNumberPrefix
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class CrimsonScrolls :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "Crimson Scrolls"
    override val baseUrl = "https://crimsonscrolls.net"
    override val lang = "en"
    override val supportsLatest = false
    override val isNovelSource = true
    override val client = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val hideLocked get() = preferences.getBoolean(PREF_HIDE_LOCKED, false)

    private fun ajaxRequest(action: String, params: Map<String, String>): Request {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            addFormDataPart("action", action)
            params.forEach { (k, v) -> addFormDataPart(k, v) }
        }.build()
        return POST("$baseUrl/wp-admin/admin-ajax.php", headers, body)
    }

    @Serializable
    private class AjaxHtml(val html: String = "")

    private fun parseNovels(response: Response): List<SManga> {
        val payload = json.decodeFromString<AjaxHtml>(response.body.string())
        val doc = Jsoup.parse(payload.html, baseUrl)
        return doc.select("a.live-search-item, div.novel-list-card").mapNotNull { el ->
            val href = el.selectFirst("a")?.attr("abs:href")
                ?: el.attr("abs:href").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            SManga.create().apply {
                title = el.selectFirst("div.live-search-title, h3.novel-title")?.text()
                    ?.trim()?.split(" ")?.filter { it.isNotEmpty() }?.joinToString(" ").orEmpty()
                setUrlWithoutDomain(href)
                thumbnail_url = el.selectFirst("img.live-search-cover, div.novel-cover img")?.attr("abs:src")
            }
        }
    }

    override fun popularMangaRequest(page: Int): Request = ajaxRequest("load_novels", mapOf("page" to page.toString()))

    override fun popularMangaParse(response: Response): MangasPage {
        val novels = parseNovels(response)
        return MangasPage(novels, novels.isNotEmpty())
    }

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = ajaxRequest("live_novel_search", mapOf("query" to query))

    override fun searchMangaParse(response: Response): MangasPage = MangasPage(parseNovels(response), false)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        val info = doc.selectFirst("#single-novel-content-wrapper") ?: doc
        return SManga.create().apply {
            title = info.selectFirst("h1")?.text()?.trim().orEmpty()
            thumbnail_url = info.selectFirst("img")?.let {
                it.attr("abs:data-src").ifEmpty { it.attr("abs:src") }
            }
            description = info.selectFirst("#synopsis-full")?.formattedText()
            author = info.selectFirst("strong")?.nextElementSibling()?.text()?.trim()
            genre = info.select(".cs-genre-chip").joinToString(", ") { it.text().trim() }
            status = when (info.selectFirst(".cs-nsb-badge")?.text()?.trim()?.lowercase()) {
                "ongoing" -> SManga.ONGOING
                "hiatus" -> SManga.ON_HIATUS
                "dropped", "cancelled" -> SManga.CANCELLED
                "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        }
    }

    @Serializable
    private class ChapterPage(
        val items: List<ChapterItem> = emptyList(),
        val total_pages: Int = 1,
        val page: Int = 1,
    )

    @Serializable
    private class ChapterItem(
        val title: String = "",
        val url: String = "",
        val locked: Boolean = false,
    )

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        val novelId = doc.selectFirst("#chapter-list")?.attr("data-novel")
            ?.takeIf { it.isNotBlank() } ?: return emptyList()

        val items = mutableListOf<ChapterItem>()
        var page = 1
        while (true) {
            val apiUrl = "$baseUrl/wp-json/cs/v1/novels/$novelId/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("per_page", "75")
                .addQueryParameter("order", "asc")
                .addQueryParameter("page", page.toString())
                .build()
            val data = json.decodeFromString<ChapterPage>(
                client.newCall(GET(apiUrl, headers)).execute().body.string(),
            )
            if (data.items.isEmpty()) break
            items += data.items
            if (hideLocked && data.items.any { it.locked }) break
            if (data.page >= data.total_pages) break
            page++
        }

        return items.mapIndexedNotNull { index, item ->
            if (hideLocked && item.locked) return@mapIndexedNotNull null
            val slug = item.url.toHttpUrl().pathSegments.getOrNull(1) ?: return@mapIndexedNotNull null
            SChapter.create().apply {
                name = item.title.stripChapterNumberPrefix().let { if (item.locked) "🔒 $it" else it }
                url = "/chapter/$slug"
                chapter_number = (index + 1).toFloat()
            }
        }.reversed()
    }

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.encodedPath))

    override fun imageUrlParse(response: Response): String = ""

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) page.url else baseUrl + page.url
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
        val content = doc.selectFirst("#chapter-display") ?: return ""
        listOf("hr.cs-attrib-divider", "div.cs-attrib", "p.cs-chapter-attrib").forEach { sel ->
            content.select(sel).lastOrNull()?.remove()
        }
        return content.formattedText()
    }

    private fun Response.asJsoup(): Document = Jsoup.parse(body.string(), request.url.toString())

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(
            SwitchPreferenceCompat(screen.context).apply {
                key = PREF_HIDE_LOCKED
                title = "Hide locked chapters"
                setDefaultValue(false)
            },
        )
    }

    companion object {
        private const val PREF_HIDE_LOCKED = "pref_hide_locked"
    }
}

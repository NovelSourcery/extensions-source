package eu.kanade.tachiyomi.novelextension.en.crimsonscrolls

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.formattedText
import keiyoushi.utils.stripChapterNumberPrefix
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class CrimsonScrolls :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.US)

    /** [SManga.url] stored as bare slug under "/novel/"; a stored value starting with "/" is a
     * pre-existing full-path entry and is resolved unchanged. */
    private val mangaPath = SlugPath("/novel/")

    /** Stores [SManga.url] as a bare slug via [mangaPath]. */
    private fun SManga.setSlugUrl(href: String) = setSlugUrl(mangaPath, href)

    // Highest tier to include; the API returns each tier separately, so we request every tier
    // from free up to (and including) the selected one and merge the results.
    private val maxTierIndex: Int
        get() = TIERS.indexOf(preferences.getString(PREF_MAX_TIER, "free")).coerceAtLeast(0)

    // Browse

    private fun parseBrowse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        val mangas = doc.select("article.cs-browse-card").mapNotNull { card ->
            val link = card.selectFirst(".cs-browse-card__body h2 a, a.cs-browse-card__cover")
                ?: return@mapNotNull null
            val href = link.attr("abs:href").ifBlank { return@mapNotNull null }
            SManga.create().apply {
                setSlugUrl(href)
                title = card.selectFirst(".cs-browse-card__body h2 a")?.let { it.attr("title").ifBlank { it.text() } }
                    ?.trim().orEmpty()
                thumbnail_url = card.selectFirst(".cs-browse-card__cover img")?.let {
                    it.attr("abs:data-src").ifBlank { it.attr("abs:src") }
                }
            }
        }
        // The browse page renders the recently-updated set in one shot (further pages load via a
        // JS endpoint that no longer works), so there is no server-side next page to follow.
        return MangasPage(mangas, hasNextPage = false)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = parseBrowse(client.newCall(GET("$baseUrl/novels/recently-updated/", headers)).execute())

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/novels/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .build()
        return parseBrowse(client.newCall(GET(url, headers)).execute())
    }

    // Details + Chapters

    private fun buildMangaDetailsRequest(manga: SManga): Request = GET(baseUrl + mangaPath.resolve(manga.url), headers)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Details and the chapter list both live on the same novel page - fetch it once.
        val response = client.newCall(buildMangaDetailsRequest(manga)).execute()

        val updatedManga = if (fetchDetails) parseMangaDetails(response) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(response) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = doc.selectFirst(".cs-cover img")?.let {
                it.attr("abs:data-src").ifBlank { it.attr("abs:src") }
            }
            description = doc.selectFirst(".cs-about-synopsis .cs-prose, .cs-about-synopsis")?.formattedText()
            author = doc.select(".cs-novel-details dl div, dl div")
                .firstOrNull { it.selectFirst("dt")?.text()?.equals("Author", true) == true }
                ?.selectFirst("dd")?.text()
            genre = doc.select(".cs-detail-genres a").joinToString { it.text() }
            status = when (doc.selectFirst(".cs-cover-status")?.text()?.lowercase()) {
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
        val pages: Int = 1,
        val page: Int = 1,
    )

    @Serializable
    private class ChapterItem(
        val number: String = "",
        val title: String = "",
        val url: String = "",
        val date: String = "",
    )

    private fun parseChapterList(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string(), baseUrl)
        val novelId = doc.selectFirst("[data-novel-chapters]")?.attr("data-novel-chapters")
            ?.takeIf { it.isNotBlank() } ?: return emptyList()

        val items = linkedMapOf<String, ChapterItem>()
        for (tierIndex in 0..maxTierIndex) {
            val tier = TIERS[tierIndex]
            var page = 1
            while (true) {
                val apiUrl = "$baseUrl/wp-json/crimsonscrolls/v2/novel-chapters".toHttpUrl().newBuilder()
                    .addQueryParameter("novel_id", novelId)
                    .addQueryParameter("tier", tier)
                    .addQueryParameter("page", page.toString())
                    .addQueryParameter("per_page", "100")
                    .addQueryParameter("search", "")
                    .addQueryParameter("order", "DESC")
                    .build()
                val data = runCatching {
                    json.decodeFromString<ChapterPage>(
                        client.newCall(GET(apiUrl, headers)).execute().body.string(),
                    )
                }.getOrNull() ?: break

                data.items.forEach { item -> items[item.url] = item }
                if (data.items.isEmpty() || data.page >= data.pages) break
                page++
            }
        }

        return items.values.mapNotNull { item ->
            val href = item.url.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SChapter.create().apply {
                setUrlWithoutDomain(href)
                name = item.title.stripChapterNumberPrefix().ifBlank { "Chapter ${item.number}" }
                chapter_number = item.number.toFloatOrNull() ?: -1f
                date_upload = parseDate(item.date)
            }
        }.sortedByDescending { it.chapter_number }
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPath.resolve(manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        val response = client.newCall(GET(baseUrl + path, headers)).execute()
        if (!response.isSuccessful) return null
        return parseMangaDetails(response).apply { setSlugUrl(mangaPath, path) }
    }

    // Pages / Content

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.newCall(GET(baseUrl + chapter.url, headers)).execute()
        return listOf(Page(0, response.request.url.encodedPath))
    }

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) page.url else baseUrl + page.url
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
        val article = doc.selectFirst("article.cs-reader") ?: doc.selectFirst("#chapter-display") ?: return ""
        // Strip reader chrome, ads, tier gate, comments and inline copy-protection watermarks,
        // leaving only the story paragraphs.
        article.select(
            "header, nav, form, section, aside, figure, script, style, svg, ins, " +
                ".cs-chapter-ad, .cs-home-ad, .cs-kofi-widget, .cs-copy-watermark, " +
                ".cs-reader-bar, .cs-reader-tools, .cs-reader-nav-button, .cs-reader-toc",
        ).remove()
        return article.formattedText()
    }

    private fun Response.asJsoup(): Document = Jsoup.parse(body.string(), request.url.toString())

    private fun parseDate(date: String): Long {
        if (date.isBlank()) return 0L
        return runCatching { dateFormat.parse(date)?.time ?: 0L }.getOrDefault(0L)
    }

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_MAX_TIER
            title = "Include chapters up to tier"
            entries = arrayOf("Free only", "Tier 1", "Tier 2", "Tier 3", "Tier 4")
            entryValues = TIERS.toTypedArray()
            setDefaultValue("free")
            summary = "Requests chapters from Free up to the selected tier. Higher tiers require an " +
                "unlocked account; locked chapters won't return content."
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_MAX_TIER = "pref_max_tier"
        private val TIERS = listOf("free", "tier_1", "tier_2", "tier_3", "tier_4")
    }
}

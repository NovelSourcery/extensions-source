package eu.kanade.tachiyomi.novelextension.en.novelonomicon

import android.util.Base64
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class Novelonomicon :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList())

    override val supportsLatest = false

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    // The novels page lists the whole catalogue at once, with the data the site sorts and filters by.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.state ?: 0
        val status = filters.firstInstanceOrNull<StatusFilter>()?.value
        val term = query.trim().lowercase()
        val cards = client.get("$baseUrl/novels/", Fetch.DOCUMENT).asJsoup().select(".ag-hub-card[data-ag-hub-type=novel]")
            .filter { card -> status == null || card.selectFirst(".ag-novel-home-status")?.hasClass("ag-novel-status-$status") == true }
            .filter { card -> card.selectFirst(".ag-hub-card-title")?.text()?.lowercase()?.contains(term) == true }
        val sorted = when (sort) {
            1 -> cards.sortedByDescending { it.attr("data-ag-hub-added").toLongOrNull() ?: 0L }
            2 -> cards.sortedBy { it.selectFirst(".ag-hub-card-title")!!.text().lowercase() }
            else -> cards.sortedByDescending { it.attr("data-ag-hub-updated").toLongOrNull() ?: 0L }
        }
        val mangas = sorted.mapNotNull { card ->
            SManga.create().apply {
                url = novelSlug(card.attr("data-ag-hub-link")) ?: return@mapNotNull null
                title = card.selectFirst(".ag-hub-card-title")!!.text()
                thumbnail_url = card.select(".ag-hub-card-media img").imageUrl()
            }
        }
        return MangasPage(mangas, false)
    }

    private fun novelSlug(href: String): String? {
        val segments = href.toHttpUrlOrNull()?.pathSegments?.filter(String::isNotEmpty) ?: return null
        return segments.getOrNull(1)?.takeIf { segments.first() == "novels" }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novels/${manga.url}/"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = novelSlug(url.toString()) ?: return null
        return parseDetails(fetchNovelPage(slug), slug)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = fetchNovelPage(manga.url)
        val locked = lockedChapters(document)
        val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)
        val chapterList = document.select("a.ag-novel-chapter-card[href]")
            .sortedByDescending { it.attr("data-order-index").toIntOrNull() ?: 0 }
            .mapNotNull { card ->
                val isLocked = card.absUrl("href") in locked
                if (isLocked && !showLocked) return@mapNotNull null
                SChapter.create().apply {
                    setUrlWithoutDomain(card.absUrl("href"))
                    val label = card.selectFirst(".ag-novel-chapter-card-num")?.text()?.ifEmpty { null } ?: card.attr("title")
                    name = if (isLocked) "🔒 $label" else label
                    card.attr("data-number").toFloatOrNull()?.let { chapter_number = it }
                    date_upload = (card.selectFirst("[data-ag-timestamp]")?.attr("data-ag-timestamp")?.toLongOrNull() ?: 0L) * 1000
                }
            }
            .distinctBy { it.url }
        return SMangaUpdate(parseDetails(document, manga.url), chapterList)
    }

    private suspend fun fetchNovelPage(slug: String): Document = client.get("$baseUrl/novels/$slug/", Fetch.DOCUMENT).asJsoup()

    private fun parseDetails(document: Document, slug: String) = SManga.create().apply {
        url = slug
        title = document.selectFirst(".ag-novel-home-title")!!.text()
        thumbnail_url = document.select(".ag-novel-home-cover img").imageUrl()
        genre = document.select(".ag-novel-home-info .ag-novel-home-tag").map { it.text() }.filter(String::isNotEmpty).joinToString()
        description = document.select(".ag-novel-home-synopsis-body > p").map { it.text() }.filter(String::isNotEmpty)
            .joinToString("\n\n").ifEmpty { null }
        document.select(".ag-novel-home-meta > div").forEach { row ->
            val value = row.selectFirst("dd")?.text() ?: return@forEach
            when (row.selectFirst("dt")?.text()?.lowercase()) {
                "author" -> author = value
                "status" -> status = when (value.lowercase()) {
                    "ongoing" -> SManga.ONGOING
                    "completed" -> SManga.COMPLETED
                    "hiatus" -> SManga.ON_HIATUS
                    "dropped" -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
            }
        }
    }

    // Early-access chapters and their unlock times come from an inline script, usually base64 encoded.
    private fun lockedChapters(document: Document): Set<String> {
        val script = document.selectFirst("#aguq-chapter-list-js-extra") ?: return emptySet()
        val source = BASE64_SCRIPT.find(script.attr("src"))?.groupValues?.get(1)
            ?.let { String(Base64.decode(it, Base64.DEFAULT)) }
            ?: script.data()
        val now = System.currentTimeMillis()
        return LOCK_ENTRY.findAll(source)
            .filter { it.groupValues[2].toLong() > now }
            .map { it.groupValues[1].replace("\\/", "/") }
            .toSet()
    }

    private fun List<Element>.imageUrl(): String? = asSequence()
        .map { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }
        .firstOrNull { it.startsWith("http") && !it.contains("missingcover") }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val response = client.get(baseUrl + page.url, Fetch.DOCUMENT, ensureSuccess = false)
        if (response.code == 403) {
            response.close()
            throw Exception("Novelonomicon blocked this connection for opening too many chapters. Wait about an hour and retry.")
        }
        val content = response.asJsoup().selectFirst(".ag-content-body") ?: throw Exception("Chapter content not found")
        if (content.selectFirst(".aguq-paywall") != null) throw Exception("This chapter isn't unlocked yet. Unlock it on Novelonomicon first.")
        content.select(".ag-ad-zone, ins, script, style, iframe, noscript").remove()
        content.select("img[data-src]").forEach { it.attr("src", it.absUrl("data-src")) }
        return content.html()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(0),
        StatusFilter(),
    )

    private class SortFilter(state: Int = 0) : Filter.Select<String>("Sort by", arrayOf("Latest updated", "Newest", "Name (A-Z)"), state)

    private class StatusFilter : Filter.Select<String>("Status", STATUSES.map { it.first }.toTypedArray()) {
        val value: String?
            get() = STATUSES[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Include early-access chapters that haven't unlocked yet (marked with 🔒)."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_SHOW_LOCKED = "show_locked_chapters"
        private val STATUSES = arrayOf(
            "All" to null,
            "Ongoing" to "ongoing",
            "Completed" to "completed",
            "On Hiatus" to "hiatus",
            "Dropped" to "dropped",
        )
        private val BASE64_SCRIPT = Regex("""^data:[^,]*;base64,(.*)$""")
        private val LOCK_ENTRY = Regex(""""href":"([^"]+)","unlockAt":(\d+)""")
    }
}

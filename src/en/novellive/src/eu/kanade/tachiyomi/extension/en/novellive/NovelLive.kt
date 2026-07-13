package eu.kanade.tachiyomi.novelextension.en.novellive

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.chapterutils.paginatedChapterList
import keiyoushi.utils.formattedText
import keiyoushi.utils.stripChapterNumberPrefix
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * novellive.app — FreeWebNovel/ReadNovelFull-style engine (lightnovelpub.me is a page-1-only
 * mirror that redirects deeper pages here). Browse cards are `div.li-row`; chapter list is
 * paginated at /book/<slug>/<page> (page count from #indexselect). Chapter urls follow the
 * stable /book/<slug>/chapter-<n> pattern, so the default "fast" mode synthesizes the list from
 * the latest chapter number instead of fetching every index page.
 */
class NovelLive :
    ReadNovelFull(
        name = "NovelLive",
        baseUrl = "https://novellive.app",
        lang = "en",
    ) {

    private val prefs: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val accurateChapters get() = prefs.getBoolean(PREF_ACCURATE, false)

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/list/most-popular-novels/$page", headers)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/list/latest-novels/$page", headers)

    override fun popularMangaSelector() = "div.ul-list1 div.li-row, div.li-row"

    // Base picks the cover anchor first (matches a.cover/a[title] earlier in DOM) -> empty title.
    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val link = element.selectFirst(".txt h3.tit > a, h3.tit > a") ?: return@apply
        setUrlWithoutDomain(link.attr("abs:href"))
        title = link.attr("title").ifBlank { link.text().trim() }
        thumbnail_url = element.selectFirst(".pic img")?.let {
            it.attr("abs:data-src").ifEmpty { it.attr("abs:src") }.ifEmpty { it.attr("src") }
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/search".toHttpUrl().newBuilder().addQueryParameter("keyword", query).build()
        return GET(url, headers)
    }

    override fun getFilterList() = FilterList()

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst(".m-desc h1.tit, h1.tit")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:novel:novel_name]")?.attr("content").orEmpty()
        thumbnail_url = document.selectFirst(".m-imgtxt .pic img, .pic img")?.let {
            it.attr("abs:data-src").ifEmpty { it.attr("abs:src") }.ifEmpty { it.attr("src") }
        }
        author = document.select(".m-imgtxt a[href*=/author/]").joinToString { it.text().trim() }
            .ifBlank { document.selectFirst("meta[property=og:novel:author]")?.attr("content")?.trim() }
        genre = document.select(".m-imgtxt a[href*=/genres/]").joinToString { it.text().trim() }
            .ifBlank {
                document.selectFirst("meta[property=og:novel:genre]")?.attr("content")
                    ?.split(",")?.joinToString(", ") { g -> g.trim().lowercase().replaceFirstChar(Char::uppercase) }
                    .orEmpty()
            }
        status = when (
            document.selectFirst(".m-imgtxt .item:has(.glyphicon-time) .s1, meta[property=og:novel:status]")
                ?.let { it.text().ifBlank { it.attr("content") } }?.trim()?.lowercase()
        ) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        description = document.selectFirst(".m-desc .txt .inner, .m-desc .inner")?.let { el ->
            el.select("script, style").remove()
            el.formattedText()
        }?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        @Suppress("DEPRECATION")
        val updatedManga = if (fetchDetails) mangaDetailsParse(client.newCall(mangaDetailsRequest(manga)).execute()) else manga
        val updatedChapters = if (fetchChapters) fetchNovelLiveChapterList(manga, chapters) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private suspend fun fetchNovelLiveChapterList(manga: SManga, existingChapters: List<SChapter>): List<SChapter> {
        val novelPath = manga.url.trimEnd('/')
        val detailDoc = client.newCall(GET(baseUrl + novelPath, headers)).execute().asJsoup()
        val options = detailDoc.select("#indexselect option")
        val totalPages = options.size.coerceAtLeast(1)

        // Latest chapter number: prefer the last #indexselect option's upper bound
        // (e.g. "C.2321 - C.2334" -> 2334), else the newest entry in div.m-newest1.
        val latestNum = options.lastOrNull()?.text()
            ?.let { Regex("""(\d+)\D*$""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: detailDoc.selectFirst("div.m-newest1 ul.ul-list5 a[href*=/chapter-]")
                ?.attr("href")?.let { Regex("""chapter-(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: 0

        // Fast mode (default): synthesize from the stable /book/<slug>/chapter-<n> url pattern.
        if (!accurateChapters && latestNum > 0) {
            return (latestNum downTo 1).map { n ->
                SChapter.create().apply {
                    setUrlWithoutDomain("$novelPath/chapter-$n")
                    name = "Chapter $n"
                    chapter_number = n.toFloat()
                }
            }
        }

        // Accurate mode: fetch every index page for real chapter titles.
        // Pages are oldest-first (#indexselect C.1-C.40, C.41-C.80, ...). Keep fetch order, then
        // number by position (href chapter numbers are unreliable) and present newest-first.
        val ascending = paginatedChapterList(
            existingChapters = existingChapters,
            siteTotal = latestNum,
            assumedPageSize = 40,
            sortChapters = { it },
            fetchPage = { page ->
                val doc = if (page == 1) {
                    detailDoc
                } else {
                    client.newCall(GET("$baseUrl$novelPath/$page", headers)).execute().asJsoup()
                }
                val chapters = doc.select("ul.ul-list5 li a").mapNotNull { a ->
                    val href = a.attr("abs:href").ifBlank { return@mapNotNull null }
                    SChapter.create().apply {
                        setUrlWithoutDomain(href)
                        name = a.attr("title").ifBlank { a.text().trim() }.stripChapterNumberPrefix()
                    }
                }
                Pair(chapters, page < totalPages)
            },
        )
        ascending.forEachIndexed { i, ch -> ch.chapter_number = (i + 1).toFloat() }
        return ascending.reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)
        screen.addPreference(
            SwitchPreferenceCompat(screen.context).apply {
                key = PREF_ACCURATE
                title = "Accurate chapter list"
                summary = "Fetch every index page for real chapter titles. Slower on long novels. " +
                    "When off (default), the list is built quickly from the latest chapter number."
                setDefaultValue(false)
            },
        )
    }

    companion object {
        private const val PREF_ACCURATE = "pref_accurate_chapters"
    }
}

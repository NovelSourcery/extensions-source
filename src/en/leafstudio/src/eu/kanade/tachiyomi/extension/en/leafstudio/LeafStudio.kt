package eu.kanade.tachiyomi.novelextension.en.leafstudio

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * LeafStudio (leafstudio.site). Plain WordPress-theme HTML, no API. As of 2026-08 the site has
 * moved to a paid "emerald" unlock model - most/all chapters are `.premium_chap` (login-gated),
 * only `.free_chap` ones (if any remain) return real prose; fetching a locked chapter just
 * returns the site's own login prompt, so locked chapters are hidden from the list by default.
 */
@Source
abstract class LeafStudio :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    override val supportsLatest = false

    /** [SManga.url] is stored as the bare slug (site has no fixed path segment before it); a
     * stored value starting with "/" that resolves to more than one segment is a pre-existing
     * full-path entry and is resolved unchanged. */
    private val mangaPath = SlugPath("/")

    /** Stores [SManga.url] as a bare slug via [mangaPath], reusing [setUrlWithoutDomain]'s
     * domain-stripping for a raw absolute href. */
    private fun SManga.setSlugUrl(href: String) {
        setUrlWithoutDomain(href)
        url = mangaPath.slug(url)
    }

    // ======================== Browse / Search ========================

    private fun buildPopularMangaUrl(page: Int): String {
        val path = if (page > 1) "/novels/page/$page" else "/novels"
        return "$baseUrl$path"
    }

    private fun buildLatestUpdatesUrl(page: Int): String = buildPopularMangaUrl(page)

    private fun buildSearchMangaUrl(page: Int, query: String, filters: FilterList): HttpUrl {
        val path = if (page > 1) "/novels/page/$page" else "/novels"
        return "$baseUrl$path".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("type", "")
            .addQueryParameter("language", "")
            .addQueryParameter("status", "")
            .addQueryParameter("sort", "")
            .build()
    }

    override suspend fun getPopularManga(page: Int): MangasPage = parseNovelList(client.get(buildPopularMangaUrl(page), headers).asJsoup())

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseNovelList(client.get(buildLatestUpdatesUrl(page), headers).asJsoup())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = parseNovelList(client.get(buildSearchMangaUrl(page, query, filters), headers).asJsoup())

    private fun parseNovelList(document: Document): MangasPage {
        val mangas = document.select("a.novel-item").map { element ->
            SManga.create().apply {
                setSlugUrl(element.attr("abs:href"))
                title = element.selectFirst("p.novel-item-title")?.text().orEmpty()
                thumbnail_url = element.selectFirst("img.novel-item-Cover")?.attr("abs:src")
            }
        }
        // The site has a single small catalog; /novels/page/N repeats page 1 (no real pagination).
        return MangasPage(mangas, false)
    }

    // ======================== Details + Chapters ========================

    private fun buildMangaDetailsUrl(manga: SManga): String = baseUrl + mangaPath.resolve(manga.url)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Details and the chapter list both live on the same novel page - fetch it once.
        val doc = client.get(buildMangaDetailsUrl(manga), headers).asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(doc) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.title")?.text().orEmpty()
        thumbnail_url = document.selectFirst("img#novel_cover")?.attr("abs:src")
        description = document.select("div.desc_div > p").joinToString("\n\n") { it.text() }.ifBlank { null }
        genre = document.select("div#tags_div > a.novel_genre").joinToString { it.text() }.ifBlank { null }
        status = when (document.selectFirst("a#novel_status")?.text()) {
            "Active" -> SManga.ONGOING
            "Completed" -> SManga.COMPLETED
            "Hiatus" -> SManga.ON_HIATUS
            "Dropped", "Cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    // Both free and premium(locked) chapters are listed - locked ones are prefixed so the reader
    // can see they exist, even though fetching one currently just returns a login prompt. Hidden
    // by default via PREF_SHOW_LOCKED since they aren't actually readable.
    private fun parseChapterList(document: Document): List<SChapter> {
        val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)
        return document.select("a.chap").mapNotNull { element ->
            val href = element.attr("abs:href")
            if (href.isBlank()) return@mapNotNull null
            val locked = element.hasClass("premium_chap")
            if (locked && !showLocked) return@mapNotNull null
            val rawName = element.selectFirst("p")?.text().orEmpty()
            SChapter.create().apply {
                setUrlWithoutDomain(href)
                name = if (locked) "🔒 $rawName" else rawName
                chapter_number = Regex("""Chapter\s+(\d+(?:\.\d+)?)""").find(rawName)
                    ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            }
        }
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPath.resolve(manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val manga = SManga.create().apply { this.url = mangaPath.slug(url.encodedPath) }
        val response = client.get(buildMangaDetailsUrl(manga), headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        return parseMangaDetails(response.asJsoup()).apply { this.url = manga.url }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    // ======================== Content ========================
    // Single text page fetched once in fetchPageText, matching the ReadNovelFull multisrc idiom.

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(baseUrl + chapter.url, headers)
        return listOf(Page(0, response.request.url.encodedPath))
    }

    override suspend fun fetchPageText(page: Page): String {
        val pageUrl = if (page.url.startsWith("http")) page.url else baseUrl + page.url
        val response = client.get(pageUrl, headers)
        val document = response.asJsoup()
        return document.select("article > p.chapter_content").joinToString("") { paragraphToHtml(it) }
    }

    private fun paragraphToHtml(element: Element): String {
        val html = element.html().trim()
        return if (html.isBlank()) "" else "<p>$html</p>"
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Include premium/login-gated chapters in the chapter list. They aren't " +
                "actually readable - fetching one just returns the site's login prompt."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_SHOW_LOCKED = "pref_show_locked_chapters"
    }
}

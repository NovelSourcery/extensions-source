package eu.kanade.tachiyomi.novelextension.en.lightnoveltranslation

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
import keiyoushi.utils.formattedText
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class LightNovelTranslation :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    override val supportsLatest = true

    /** [SManga.url] is stored as the bare slug under `/novel/`; a stored value starting with
     * "/" is a pre-existing full-path entry and is resolved unchanged. */
    private val mangaPath = SlugPath("/novel/")

    private fun buildBrowseUrl(page: Int, sortBy: String, query: String? = null): HttpUrl = "$baseUrl/browse/".toHttpUrl().newBuilder().apply {
        if (page > 1) addPathSegments("page/$page/")
        addQueryParameter("sortby", sortBy)
        if (!query.isNullOrBlank()) addQueryParameter("field-search", query)
    }.build()

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaListResponse(client.get(buildBrowseUrl(page, "most-liked"), headers))

    private fun parseMangaListResponse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = doc.select(".read_list-story-item").mapNotNull { element ->
            try {
                val link = element.selectFirst(".wrap_item_content h3 a, .item_thumb a") ?: return@mapNotNull null
                val url = link.attr("href")
                val title = link.text().ifBlank { link.attr("aria-label").ifBlank { link.attr("title") } }
                if (url.isBlank() || title.isBlank()) return@mapNotNull null
                val cover = element.selectFirst(".item_thumb img")?.attr("abs:src") ?: ""

                SManga.create().apply {
                    this.title = title
                    this.url = mangaPath.slug(url.removePrefix(baseUrl))
                    thumbnail_url = cover
                }
            } catch (e: Exception) {
                null
            }
        }
        val hasNextPage = mangas.isNotEmpty() && doc.selectFirst("a.next.page-numbers") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaListResponse(client.get(buildBrowseUrl(page, "most-recent"), headers))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = parseMangaListResponse(client.get(buildBrowseUrl(page, "most-recent", query.trim()), headers))

    private fun buildMangaDetailsUrl(manga: SManga): String = mangaPath.absolute(baseUrl, manga.url)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = client.get(buildMangaDetailsUrl(manga), headers).asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) fetchChapterList(doc) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(doc: Document): SManga = SManga.create().apply {
        thumbnail_url = doc.selectFirst("div.novel-image img")?.attr("abs:src")
        title = doc.selectFirst("div.novel_title h3")?.text() ?: ""

        val statusText = doc.selectFirst("div.novel_status")?.text() ?: ""
        status = when {
            statusText.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS
            statusText.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        author = doc.selectFirst("div.novel_detail_info li:contains(Author)")
            ?.text()?.substringAfter("Author")?.removePrefix(":")?.trim()
        genre = doc.select(".novel_tags_item span").joinToString { it.text() }.ifBlank { null }
        description = doc.selectFirst("div.novel_text")?.formattedText()
    }

    private suspend fun fetchChapterList(doc: Document): List<SChapter> {
        val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)
        val tocUrl = doc.selectFirst("#lnt-progressive-toc")?.attr("abs:data-url")?.ifBlank { null }
        val volumes = doc.select("section.lnt-volume[data-volume]")
        val volumeRows: List<Pair<String?, List<Element>>> = if (volumes.isEmpty() || tocUrl == null) {
            listOf(null to doc.select("li.chapter-item"))
        } else {
            val semaphore = Semaphore(4)
            coroutineScope {
                volumes.map { section ->
                    async {
                        semaphore.withPermit {
                            val name = section.selectFirst(".lnt-volume-link")?.ownText()?.trim()
                            name to fetchVolumeRows(section, tocUrl)
                        }
                    }
                }.awaitAll()
            }
        }
        val multiVolume = volumeRows.size > 1

        var counter = 0f
        return volumeRows
            .flatMap { (volumeName, rows) ->
                rows.mapNotNull { element ->
                    val link = element.selectFirst("a") ?: return@mapNotNull null
                    val chapterUrl = link.attr("abs:href")
                    if (chapterUrl.isBlank()) return@mapNotNull null

                    val locked = !element.hasClass("unlock") &&
                        (element.hasClass("lock") || element.selectFirst(".lock, .premium, i.fa-lock") != null)
                    if (locked && !showLocked) return@mapNotNull null

                    counter++
                    val title = link.text()
                    val name = if (multiVolume && !volumeName.isNullOrBlank()) "$volumeName: $title" else title

                    SChapter.create().apply {
                        url = chapterUrl.removePrefix(baseUrl)
                        this.name = if (locked) "🔒 $name" else name
                        chapter_number = counter
                    }
                }
            }
            .distinctBy { it.url }
            .reversed()
    }

    private suspend fun fetchVolumeRows(section: Element, tocUrl: String): List<Element> {
        var fragment: Element? = section.selectFirst(".lnt-volume-panel")?.takeIf { it.attr("data-loaded") == "1" }
        var nextUrl: HttpUrl? = tocUrl.toHttpUrl().newBuilder()
            .setQueryParameter("lnt_volume", section.attr("data-volume"))
            .build()
        val rows = mutableListOf<Element>()
        val seenPages = mutableSetOf<String>()
        while (true) {
            if (fragment == null) {
                val url = nextUrl ?: break
                if (!seenPages.add(url.toString())) break
                fragment = fetchTocFragment(url) ?: break
            }
            rows += fragment.select("li.chapter-item")
            nextUrl = fragment.select("a.lnt-page-link")
                .firstOrNull { it.text().contains("Next", ignoreCase = true) }
                ?.attr("abs:href")?.ifBlank { null }?.toHttpUrl()
                ?: break
            fragment = null
        }
        return rows
    }

    private suspend fun fetchTocFragment(url: HttpUrl): Element? {
        val fragmentUrl = url.newBuilder().setQueryParameter("lnt_fragment", "toc").build()
        val jsonHeaders = headers.newBuilder().set("Accept", "application/json").build()
        val html = client.get(fragmentUrl, jsonHeaders).parseAs<JsonObject>()["data"]
            ?.jsonObject?.get("html")?.jsonPrimitive?.content ?: return null
        return Jsoup.parseBodyFragment(html, baseUrl).body()
    }

    override fun getMangaUrl(manga: SManga): String = mangaPath.absolute(baseUrl, manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val manga = SManga.create().apply { this.url = mangaPath.slug(url.encodedPath) }
        val response = client.get(buildMangaDetailsUrl(manga), headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        return parseMangaDetails(response.asJsoup()).apply { this.url = manga.url }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val response = client.get(baseUrl + page.url, headers)
        val doc = response.asJsoup()

        val content = doc.selectFirst("div.text_story") ?: return ""
        content.select("div.ads_content").remove()

        return content.html()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Include premium/locked chapters in the chapter list."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_SHOW_LOCKED = "pref_show_locked_chapters"
    }
}

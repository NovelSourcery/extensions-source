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
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.formattedText
import keiyoushi.utils.getPreferencesLazy
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Response

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

    private fun buildPopularMangaUrl(page: Int): String = "$baseUrl/read/page/$page?sortby=most-liked"

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaListResponse(client.get(buildPopularMangaUrl(page), headers))

    private fun parseMangaListResponse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = doc.select("div.read_list-story-item").mapNotNull { element ->
            try {
                val link = element.selectFirst(".item_thumb a") ?: return@mapNotNull null
                val url = link.attr("href")
                val title = link.attr("title").ifEmpty { link.text() }
                val cover = element.selectFirst(".item_thumb img")?.attr("src") ?: ""

                SManga.create().apply {
                    this.title = title
                    this.url = mangaPath.slug(url.removePrefix(baseUrl))
                    thumbnail_url = cover
                }
            } catch (e: Exception) {
                null
            }
        }
        val hasNextPage = doc.selectFirst("a.next.page-numbers, a:contains(Next)") != null ||
            mangas.size >= 20
        return MangasPage(mangas, hasNextPage)
    }

    private fun buildLatestUpdatesUrl(page: Int): String = "$baseUrl/read/page/$page?sortby=most-recent"

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaListResponse(client.get(buildLatestUpdatesUrl(page), headers))

    private fun buildSearchMangaBody(query: String): FormBody = FormBody.Builder()
        .add("field-search", query)
        .build()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val mangas = parseMangaListResponse(client.post("$baseUrl/read", headers, buildSearchMangaBody(query))).mangas
        return MangasPage(mangas, false)
    }

    private fun buildMangaDetailsUrl(manga: SManga): String = mangaPath.absolute(baseUrl, manga.url)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Details and the chapter list both live on the same novel page - fetch it once.
        val response = client.get(buildMangaDetailsUrl(manga), headers)
        val doc = response.asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc, response) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(doc) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private suspend fun parseMangaDetails(doc: org.jsoup.nodes.Document, response: Response): SManga = SManga.create().apply {
        thumbnail_url = doc.selectFirst("div.novel-image img")?.attr("src")
        title = doc.selectFirst("div.novel_title h3")?.text() ?: ""

        val statusText = doc.selectFirst("div.novel_status")?.text() ?: ""
        status = when {
            statusText.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
            statusText.contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS
            statusText.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        author = doc.selectFirst("div.novel_detail_info li")
            ?.takeIf { it.text().contains("Author", ignoreCase = true) }
            ?.text()?.substringAfter("Author")?.replace(":", "")?.trim()

        val descUrl = response.request.url.toString().replace("?tab=table_contents", "")
        try {
            val descDoc = client.get(descUrl, headers).asJsoup()
            description = descDoc.selectFirst("div.novel_text")?.formattedText()
        } catch (e: Exception) {
            description = ""
        }
    }

    // Chapters are grouped into per-volume <div class="accordition_item"> blocks, each with its
    // own "Volume N" heading and a chapter list whose displayed index ("1:", "2:", ...) resets at
    // every volume - so it can't be used as a global chapter_number. Without chapter_number set
    // at all (as before), chapters from different volumes sorted inconsistently. Walk the volume
    // blocks in document order instead, assigning a running counter across all of them and
    // prefixing the volume name when there's more than one.
    private fun parseChapterList(doc: org.jsoup.nodes.Document): List<SChapter> {
        val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)
        val volumeBlocks = doc.select(".accordition_item")
        val chapterListElements = if (volumeBlocks.isNotEmpty()) {
            volumeBlocks.map { it.selectFirst(".accordition_item_title")?.text()?.trim() to it }
        } else {
            listOf(null to doc)
        }
        val multiVolume = chapterListElements.size > 1

        var counter = 0f
        return chapterListElements
            .flatMap { (volumeName, container) ->
                container.select("li.chapter-item, ul.chapter-list li, li[class*=chapter-item]").mapNotNull { element ->
                    try {
                        val link = element.selectFirst("a") ?: return@mapNotNull null
                        val chapterUrl = link.attr("href")
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
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            .distinctBy { it.url }
            .reversed()
    }

    override fun getMangaUrl(manga: SManga): String = mangaPath.absolute(baseUrl, manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val manga = SManga.create().apply { this.url = mangaPath.slug(url.encodedPath) }
        val response = client.get(buildMangaDetailsUrl(manga), headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        return parseMangaDetails(response.asJsoup(), response).apply { this.url = manga.url }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(baseUrl + chapter.url, headers)
        return listOf(Page(0, response.request.url.encodedPath))
    }

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

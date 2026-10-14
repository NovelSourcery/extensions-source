package eu.kanade.tachiyomi.multisrc.nzoseries

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
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.formattedText
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.stringOrNull
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.util.Calendar

abstract class NzoSeries :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    /** Path of the page carrying the browse form (and its `browse('<nonce>')` component). */
    protected open val browsePath = "/browse/"

    override val supportsFilterFetching = true

    protected val preferences by getPreferencesLazy()

    @Volatile
    private var browseNonce: String? = null

    override suspend fun getPopularManga(page: Int): MangasPage = browse(page, listOf("search" to "", "orderBy" to "recent"))

    override suspend fun getLatestUpdates(page: Int): MangasPage = browse(page, listOf("search" to "", "orderBy" to "latest"))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val params = mutableListOf("search" to query.trim())
        var orderBy = "recent"
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> orderBy = filter.value
                is StatusFilter -> params += filter.param to filter.value
                is TriStateGroup -> filter.addParams(params)
                is TagsModeFilter -> if (filter.state) params += "tagsMode" to "on"
                else -> {}
            }
        }
        params += "orderBy" to orderBy
        return browse(page, params)
    }

    private suspend fun browse(page: Int, params: List<Pair<String, String>>): MangasPage {
        var response = fetchBrowse(page, params, nonce(refresh = false))
        // The nonce expires server-side; a stale one is rejected with success=false.
        if (!response.success) response = fetchBrowse(page, params, nonce(refresh = true))
        if (!response.success) throw Exception(response.data.stringOrNull ?: "Browse request failed")

        val data = response.data.parseAs<BrowseDataDto>()
        val mangas = data.posts.map { post ->
            SManga.create().apply {
                url = mangaUrlFromPermalink(post.permalink)
                title = Parser.unescapeEntities(post.title, false)
                thumbnail_url = post.thumbnail?.let { if (it.startsWith("/")) baseUrl + it else it }
            }
        }
        return MangasPage(mangas, page < data.pages)
    }

    private suspend fun fetchBrowse(page: Int, params: List<Pair<String, String>>, nonce: String): BrowseResponseDto {
        val body = FormBody.Builder().apply {
            params.forEach { (key, value) -> add(key, value) }
            add("curpage", page.toString())
            add("post", nonce)
            add("action", "fetch_browse")
        }.build()
        return client.post("$baseUrl/ajax", headers, body).parseAs()
    }

    private suspend fun nonce(refresh: Boolean): String {
        if (!refresh) browseNonce?.let { return it }
        val document = client.get(baseUrl + browsePath, headers).asJsoup()
        return document.browseNonce().also { browseNonce = it }
    }

    private fun Document.browseNonce(): String {
        val xData = selectFirst("[x-data^=\"browse(\"]")!!.attr("x-data")
        return QUOTED_ARG_REGEX.find(xData)!!.groupValues[1]
    }

    protected open fun mangaUrlFromPermalink(permalink: String): String = seriesId(permalink)

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url}/"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "series") return null
        val id = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        val seriesUrl = "$baseUrl/series/$id/"
        val document = client.get(seriesUrl, headers).asJsoup()
        return parseMangaDetails(document, fetchRawChapters(document)).apply {
            this.url = mangaUrlFromPermalink(seriesUrl)
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), headers).asJsoup()
        val rawChapters = fetchRawChapters(document)
        val showLocked = preferences.getBoolean(showLockedPrefKey, false)
        val updatedChapters = rawChapters.filter { showLocked || !it.locked }.map { it.toSChapter() }
        return SMangaUpdate(parseMangaDetails(document, rawChapters), updatedChapters)
    }

    private fun parseMangaDetails(document: Document, chapters: List<RawChapter>): SManga = SManga.create().apply {
        val heading = document.selectFirst("h1")!!
        title = heading.text()
        thumbnail_url = document.selectFirst("img[x-ref=art]")?.absUrl("src")
        // ReadHive renders the author as a plain span, StorySeedling as "Written by <a>".
        author = heading.nextElementSibling()?.let { it.selectFirst("a") ?: it }?.text()?.takeIf { it.isNotEmpty() }

        val statusText = heading.parent()?.selectFirst("span.text-sm")?.text()
        status = when (statusText?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled", "dropped" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        val genres = document.select("a[href^=/genre/], a[href*=includeGenres]").eachText()
        val tags = document.select("a[href^=/tag/], a[href*=includeTags]").map { it.text().removePrefix("#") }
        genre = (genres + tags).distinct().joinToString().takeIf { it.isNotEmpty() }

        val synopsis = document.selectFirst(SYNOPSIS_SELECTOR)?.clone()?.apply {
            select(".code-block").remove()
        }?.formattedText()

        val stats = document.select("strong + small").mapNotNull { label ->
            val value = label.previousElementSibling()!!.text()
            value.takeUnless { it.isEmpty() || it == "N/A" || label.text() == "Chapters" }?.let { "${label.text()}: $it" }
        }
        val lockedCount = chapters.count { it.locked }

        description = buildString {
            synopsis?.takeIf { it.isNotEmpty() }?.let { append(it).append("\n\n") }
            stats.forEach { append(it).append('\n') }
            statusText?.let { append("Status: ").append(it).append('\n') }
            append("Chapters: ").append(chapters.size)
            if (lockedCount > 0) append(" (").append(lockedCount).append(" premium)")
        }
    }

    /** Chapter url stored for a chapter link; [getChapterUrl] must invert it. */
    protected open fun chapterUrlFromLink(link: String): String = link.substringAfter("/series/").trim('/')

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/series/${chapter.url}/"

    private suspend fun fetchRawChapters(document: Document): List<RawChapter> {
        // Newer installs load the table of contents through ajax instead of rendering it.
        val toc = document.selectFirst("[x-data^=\"toc(\"]")?.attr("x-data")
            ?.let { QUOTED_ARG_REGEX.findAll(it).map { match -> match.groupValues[1] }.toList() }
            ?.takeIf { it.size == 2 }
            ?: return parseRenderedChapters(document)

        val body = FormBody.Builder()
            .add("post", toc[1])
            .add("id", toc[0])
            .add("action", "series_toc")
            .build()
        val data = client.post("$baseUrl/ajax", headers, body).parseAs<TocResponseDto>().data
        return data.mapIndexed { index, chapter ->
            RawChapter(chapterUrlFromLink(chapter.url), chapter.title, chapter.isLocked, index + 1f, parseRelativeDate(chapter.date))
        }.reversed()
    }

    private fun parseRenderedChapters(document: Document): List<RawChapter> = document.select(RENDERED_CHAPTER_SELECTOR).map { row ->
        val link = row.absUrl("href")
        RawChapter(
            url = chapterUrlFromLink(link),
            name = row.selectFirst("span.ml-1")!!.text(),
            // Premium (coin) chapters carry a price badge before the chapter title.
            locked = row.children().any { it.tagName() == "span" && it.hasClass("bg-red") },
            number = link.trimEnd('/').substringAfterLast('/').toFloatOrNull() ?: -1f,
            date = parseRelativeDate(row.selectFirst("span.text-xs")?.text()),
        )
    }

    private class RawChapter(val url: String, val name: String, val locked: Boolean, val number: Float, val date: Long) {
        fun toSChapter() = SChapter.create().apply {
            url = this@RawChapter.url
            name = if (locked) "🔒 ${this@RawChapter.name}" else this@RawChapter.name
            chapter_number = number
            date_upload = date
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, getChapterUrl(chapter)))

    override suspend fun fetchPageText(page: Page): String {
        val document = client.get(page.url, headers).asJsoup()
        val loader = document.selectFirst("[x-data^=\"loadChapter(\"]")
        return if (loader != null) fetchLoadedContent(page.url, loader) else parseRenderedContent(document)
    }

    private fun parseRenderedContent(document: Document): String {
        val content = document.selectFirst("div.prose > div.mb-4") ?: throw Exception("Chapter content not found")
        if (content.selectFirst(".mycred-sell-visitor") != null) {
            throw Exception("Premium chapter: unlock it on the site in WebView")
        }
        content.select(".code-block, script, input, div.flex-col.text-xs").remove()
        content.select("span[style]").unwrap()
        return content.html()
    }

    private suspend fun fetchLoadedContent(chapterUrl: String, loader: Element): String {
        val nonce = QUOTED_ARG_REGEX.findAll(loader.attr("x-data")).last().groupValues[1]
        val contentHeaders = headers.newBuilder().set("X-Nonce", nonce).build()
        val response = client.post("${chapterUrl.trimEnd('/')}/content", contentHeaders, ContentRequestDto(captchaResponse = "").toJsonRequestBody(), ensureSuccess = false)
        if (!response.isSuccessful) {
            val error = response.parseAs<ContentErrorDto>()
            throw Exception(if (error.captcha) "Captcha required: open the chapter in WebView" else error.message ?: "Chapter is locked")
        }

        val document = response.asJsoup()
        // Decoy spans (watermarks, junk class names) are pushed off-screen by the inline stylesheet.
        val hiddenClasses = document.select("style").flatMap { HIDDEN_CLASS_REGEX.findAll(it.data()).map { match -> match.groupValues[1] } }.toSet()
        val content = document.body()
        content.select("style").remove()
        content.select("span[class]").filter { it.className() in hiddenClasses }.forEach { it.remove() }
        content.select("span[style]").unwrap()
        return decodeGlyphs(content.html())
    }

    // Letters are swapped for Kangxi radicals (U+2F42 onwards, A-Z then a-z) to defeat scraping.
    private fun decodeGlyphs(text: String): String = buildString(text.length) {
        text.forEach { char ->
            val index = char.code - GLYPH_BASE
            append(if (index in GLYPH_ALPHABET.indices) GLYPH_ALPHABET[index] else char)
        }
    }

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get(baseUrl + browsePath, headers).asJsoup()
        browseNonce = document.browseNonce()
        return FilterDataDto(
            orders = document.selectOptions("orderBy")
                // Sites without a working latest listing also drop the matching order.
                .filter { supportsLatest || it.value != "latest" },
            statuses = document.selectOptions("status"),
            genres = document.select("button").mapNotNull { button ->
                val id = GENRE_ID_REGEX.find(button.attr("@click"))?.groupValues?.get(1) ?: return@mapNotNull null
                FilterOptionDto(id, button.text())
            },
            tags = document.selectOptions("includeTags"),
        ).toJsonElement()
    }

    private fun Document.selectOptions(ref: String) = select("select[x-ref=$ref] option").map { FilterOptionDto(it.attr("value"), it.text()) }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterDataDto>() ?: return FilterList()
        return FilterList(
            buildList {
                add(SortFilter(filterData.orders))
                if (filterData.statuses.isNotEmpty()) add(StatusFilter(filterData.statuses))
                add(GenreFilter(filterData.genres))
                add(TagsModeFilter())
                add(TagFilter(filterData.tags))
            },
        )
    }

    protected open val showLockedPrefKey = PREF_SHOW_LOCKED

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = showLockedPrefKey
            title = "Show locked chapters"
            summary = "Include premium chapters in the chapter list (their content can't be read without unlocking them on the site)."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    private fun parseRelativeDate(text: String?): Long {
        // Future dates ("in 6 months") are premium early-access chapters; leave them undated.
        val match = RELATIVE_DATE_REGEX.find(text ?: return 0L) ?: return 0L
        val amount = match.groupValues[1].toIntOrNull() ?: 1
        val field = when (match.groupValues[2]) {
            "second" -> Calendar.SECOND
            "minute" -> Calendar.MINUTE
            "hour" -> Calendar.HOUR_OF_DAY
            "day" -> Calendar.DAY_OF_YEAR
            "week" -> Calendar.WEEK_OF_YEAR
            "month" -> Calendar.MONTH
            else -> Calendar.YEAR
        }
        return Calendar.getInstance().apply { add(field, -amount) }.timeInMillis
    }

    companion object {
        private const val PREF_SHOW_LOCKED = "show_locked_chapters"
        private const val RENDERED_CHAPTER_SELECTOR = "div[x-show*=releases] a[href]:has(span.ml-1)"
        private const val SYNOPSIS_SELECTOR = "div[x-show*=about] > div.mb-4, div[x-data*=expanded] div.mb-4.order-2"
        private const val GLYPH_BASE = 0x2F42
        private const val GLYPH_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private val QUOTED_ARG_REGEX = Regex("""'([^']*)'""")
        private val GENRE_ID_REGEX = Regex("""genreState\((\d+)\)""")
        private val HIDDEN_CLASS_REGEX = Regex("""\.(cls[0-9a-f]+)\s*\{[^}]*-9999px""")
        private val SERIES_ID_REGEX = Regex("""/series/([^/]+)""")
        private val RELATIVE_DATE_REGEX = Regex("""(\d+|an?)\s+(second|minute|hour|day|week|month|year)s?\s+ago""")

        fun seriesId(url: String): String = SERIES_ID_REGEX.find(url)!!.groupValues[1]
    }
}

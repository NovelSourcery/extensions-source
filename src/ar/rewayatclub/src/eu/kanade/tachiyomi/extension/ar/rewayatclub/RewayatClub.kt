package eu.kanade.tachiyomi.novelextension.ar.rewayatclub

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class RewayatClub :
    HttpSource(),
    NovelSource {

    override val name = "Rewayat Club"
    override val baseUrl = "https://rewayat.club"
    override val lang = "ar"
    override val supportsLatest = true
    override val isNovelSource = true
    override val client = network.client

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val apiUrl = "https://api.rewayat.club"

    private var currentNovelSlug = ""
    private var cachedTranslators: List<String> = emptyList()
    private var currentFilterList: FilterList = FilterList()

    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/api/novels?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val body = json.decodeFromString<NovelsResponse>(response.body.string())
        val novels = body.results.map { it.toSManga() }
        return MangasPage(novels, body.next != null)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/api/novels?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return GET("$apiUrl/api/novels?page=$page&search=$q", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/api/novels${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val bodyStr = response.body.string()
        val item = json.decodeFromString<NovelItem>(bodyStr)
        currentNovelSlug = item.slug
        cachedTranslators = item.contributors.map { it.username }.filter { it.isNotBlank() }.distinct().sorted()
        return SManga.create().apply {
            url = "/${item.slug}"
            title = item.arabic
            thumbnail_url = "$apiUrl${item.poster_url}"
            description = item.about
            genre = item.genre.joinToString { it.arabic }
            status = when (item.get_novel_status) {
                "مكتملة" -> SManga.COMPLETED
                "مستمرة" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun getFilterList(): FilterList {
        if (cachedTranslators.isEmpty() && currentNovelSlug.isNotEmpty()) {
            fetchTranslators(currentNovelSlug)
        }

        if (cachedTranslators.isEmpty()) {
            currentFilterList = FilterList(
                Filter.Header("جاري تحميل المساهمين..."),
            )
            return currentFilterList
        }

        val checkboxes = cachedTranslators.map { TranslatorCheckBox(it) }

        currentFilterList = FilterList(
            Filter.Header("المساهمون - اختر من تريد إخفاء فصوله"),
            Filter.Separator(),
            TranslatorBlockGroup(checkboxes),
        )
        return currentFilterList
    }

    private fun fetchTranslators(slug: String) {
        try {
            val resp = client.newCall(GET("$apiUrl/api/novels/$slug", headers)).execute()
            val item = json.decodeFromString<NovelItem>(resp.body.string())
            currentNovelSlug = item.slug
            cachedTranslators = item.contributors.map { it.username }.filter { it.isNotBlank() }.distinct().sorted()
        } catch (_: Exception) {
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        if (currentNovelSlug.isEmpty()) {
            currentNovelSlug = manga.url.trimStart('/')
        }
        return GET("$apiUrl/api/chapters${manga.url}/?ordering=-number&page=1&page_size=500", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val blocked = currentFilterList.filterIsInstance<TranslatorBlockGroup>()
            .firstOrNull()?.state
            ?.filterIsInstance<TranslatorCheckBox>()
            ?.filter { it.state }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

        val allChapters = mutableListOf<ChapterItem>()
        val novelSlug = response.request.url.encodedPath.substringAfter("/api/chapters/").trimEnd('/')

        val firstBody = json.decodeFromString<ChaptersResponse>(response.body.string())
        allChapters.addAll(firstBody.results)
        var nextUrl = firstBody.next

        while (nextUrl != null) {
            val pageResp = client.newCall(GET(nextUrl, headers)).execute()
            val body = json.decodeFromString<ChaptersResponse>(pageResp.body.string())
            pageResp.close()
            allChapters.addAll(body.results)
            nextUrl = body.next
        }

        val filtered = if (blocked.isNotEmpty()) {
            allChapters.filter { ch -> ch.uploader?.username !in blocked }
        } else {
            allChapters
        }

        return filtered.map { ch ->
            SChapter.create().apply {
                url = "/novel/$novelSlug/${ch.number}"
                name = ch.title
                scanlator = ch.uploader?.username
                chapter_number = ch.number.toFloat()
                date_upload = DATE_FORMAT.tryParse(ch.date)
            }
        }.sortedByDescending { it.chapter_number }
    }

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.encodedPath
        return listOf(Page(0, url))
    }

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.newCall(GET("$baseUrl${page.url}", headers)).execute().asJsoup()

        val nuxtScript = doc.select("script").firstOrNull { it.html().contains("window.__NUXT__") }
        if (nuxtScript != null) {
            val scriptHtml = nuxtScript.html()
            val nuxtContent = extractNuxtContent(scriptHtml)
            if (nuxtContent.isNotEmpty()) return nuxtContent
        }

        val contentEl = doc.selectFirst(
            ".chapter-content, .entry-content, .reading-content, [data-v-53cd2902] .v-card:last-child",
        )
        if (contentEl != null) {
            contentEl.select(
                "script, style, nav, footer, header, .ads, .navigation, .chapter-nav, .prev-next, .share, .comments, .breadcrumb, .v-data-table, table",
            ).remove()
            val paragraphs = contentEl.select("p")
            if (paragraphs.isNotEmpty()) {
                return paragraphs.joinToString("\n\n") { it.html().trim() }
            }
            return contentEl.html().trim()
        }

        return ""
    }

    private fun extractNuxtContent(scriptHtml: String): String {
        val startMarker = "e.content=\""
        val startIdx = scriptHtml.indexOf(startMarker)
        if (startIdx < 0) return ""

        val valueStart = startIdx + startMarker.length
        val sb = StringBuilder()
        var i = valueStart
        val len = scriptHtml.length

        while (i < len) {
            val c = scriptHtml[i]
            when {
                c == '\\' && i + 1 < len -> {
                    when (scriptHtml[i + 1]) {
                        'u' -> {
                            if (i + 5 < len) {
                                val hex = scriptHtml.substring(i + 2, i + 6)
                                val cp = hex.toIntOrNull(16)
                                if (cp != null) {
                                    sb.appendCodePoint(cp)
                                    i += 6
                                    continue
                                }
                            }
                            sb.append('\\')
                            i++
                        }
                        'n' -> {
                            sb.append('\n')
                            i += 2
                        }
                        'r' -> {
                            sb.append('\r')
                            i += 2
                        }
                        't' -> {
                            sb.append('\t')
                            i += 2
                        }
                        '\\' -> {
                            sb.append('\\')
                            i += 2
                        }
                        '"' -> {
                            sb.append('"')
                            i += 2
                        }
                        '\'' -> {
                            sb.append('\'')
                            i += 2
                        }
                        '/' -> {
                            sb.append('/')
                            i += 2
                        }
                        '0' -> {
                            sb.append('\u0000')
                            i += 2
                        }
                        else -> {
                            sb.append(c)
                            i++
                        }
                    }
                }
                c == '"' -> {
                    val next = if (i + 1 < len) scriptHtml[i + 1] else ';'
                    if (next == ';' || next == ')' || next == '\n' || next == '\r' || next == '}') {
                        break
                    }
                    sb.append(c)
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }

        val raw = sb.toString().trim()
        if (raw.isEmpty()) return ""

        if (raw.startsWith("<")) {
            val doc = org.jsoup.Jsoup.parseBodyFragment(raw)
            val paragraphs = doc.select("p")
            if (paragraphs.isNotEmpty()) {
                return paragraphs.joinToString("\n\n") { it.html().trim() }
            }
            return doc.body().html().trim()
        }

        return raw
    }

    override fun imageUrlParse(response: Response): String = ""

    private fun NovelItem.toSManga() = SManga.create().apply {
        url = "/$slug"
        title = arabic
        thumbnail_url = "$apiUrl${poster_url}"
        genre = this@toSManga.genre.joinToString { it.arabic }
        status = when (get_novel_status) {
            "مكتملة" -> SManga.COMPLETED
            "مستمرة" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private class TranslatorCheckBox(name: String) : Filter.CheckBox(name)

    private class TranslatorBlockGroup(checkboxes: List<TranslatorCheckBox>) : Filter.Group<TranslatorCheckBox>("المساهمون", checkboxes)

    @Serializable
    data class NovelsResponse(
        val count: Int = 0,
        val next: String? = null,
        val results: List<NovelItem> = emptyList(),
    )

    @Serializable
    data class NovelItem(
        val arabic: String = "",
        val english: String = "",
        val about: String = "",
        val slug: String = "",
        @SerialName("poster_url") val poster_url: String = "",
        val genre: List<GenreItem> = emptyList(),
        @SerialName("get_novel_status") val get_novel_status: String = "",
        val contributors: List<ContributorItem> = emptyList(),
    )

    @Serializable
    data class ContributorItem(
        val username: String = "",
        val id: Int = 0,
    )

    @Serializable
    data class GenreItem(val arabic: String = "")

    @Serializable
    data class ChaptersResponse(
        val count: Int = 0,
        val next: String? = null,
        val results: List<ChapterItem> = emptyList(),
    )

    @Serializable
    data class ChapterItem(
        val number: Int = 0,
        val title: String = "",
        val date: String = "",
        @SerialName("novel_slug") val novel_slug: String = "",
        val uploader: UploaderItem? = null,
    )

    @Serializable
    data class UploaderItem(
        val username: String = "",
        val id: Int = 0,
    )

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }
}

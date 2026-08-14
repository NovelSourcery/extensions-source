package eu.kanade.tachiyomi.novelextension.ar.rewayatclub

import eu.kanade.tachiyomi.network.GET
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
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class RewayatClub :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val apiUrl = "https://api.rewayat.club"

    private var cachedTranslators: List<String> = emptyList()
    private var currentFilterList: FilterList = FilterList()

    /** [SManga.url] stored as a bare slug via [mangaPathTemplate]. */
    private val mangaPathTemplate = SlugPath("/novel/")

    private fun buildPopularMangaRequest(page: Int): Request = GET("$apiUrl/api/novels?page=$page", headers)

    override suspend fun getPopularManga(page: Int): MangasPage = parseNovelsResponse(client.newCall(buildPopularMangaRequest(page)).execute())

    private fun buildLatestUpdatesRequest(page: Int): Request = GET("$apiUrl/api/novels?page=$page", headers)

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseNovelsResponse(client.newCall(buildLatestUpdatesRequest(page)).execute())

    private fun parseNovelsResponse(response: Response): MangasPage {
        val body = json.decodeFromString<NovelsResponse>(response.body.string())
        val novels = body.results.map { it.toSManga() }
        return MangasPage(novels, body.next != null)
    }

    private fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return GET("$apiUrl/api/novels?page=$page&search=$q", headers)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = parseNovelsResponse(client.newCall(buildSearchMangaRequest(page, query, filters)).execute())

    private fun buildMangaDetailsRequest(manga: SManga): Request {
        val slug = mangaPathTemplate.resolve(manga.url).substringAfterLast("/")
        return GET("$apiUrl/api/novels/$slug", headers)
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPathTemplate.resolve(manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val manga = SManga.create().apply { this.url = mangaPathTemplate.slug(url.encodedPath) }
        val response = client.newCall(buildMangaDetailsRequest(manga)).execute()
        if (!response.isSuccessful) return null
        return json.decodeFromString<NovelItem>(response.body.string()).toSManga()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = mangaPathTemplate.resolve(manga.url).substringAfterLast("/")

        val updatedManga = if (fetchDetails) {
            val response = client.newCall(buildMangaDetailsRequest(manga)).execute()
            val item = json.decodeFromString<NovelItem>(response.body.string())
            cachedTranslators = item.contributors.map { it.username }.filter { it.isNotBlank() }.distinct().sorted()
            SManga.create().apply {
                url = mangaPathTemplate.slug("/novel/${item.slug}")
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
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) fetchChapterList(slug) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override fun getFilterList(data: JsonElement?): FilterList {
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

    private fun fetchChapterList(novelSlug: String): List<SChapter> {
        val blocked = currentFilterList.filterIsInstance<TranslatorBlockGroup>()
            .firstOrNull()?.state
            ?.filterIsInstance<TranslatorCheckBox>()
            ?.filter { it.state }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

        val allChapters = mutableListOf<ChapterItem>()

        var nextUrl: String? = "$apiUrl/api/chapters/$novelSlug/?ordering=-number&page=1&page_size=500"
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
                date_upload = runCatching { DATE_FORMAT.parse(ch.date)?.time }.getOrNull() ?: 0L
            }
        }.sortedByDescending { it.chapter_number }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = "$baseUrl${chapter.url}"
        return listOf(Page(0, url))
    }

    override suspend fun fetchPageText(page: Page): String {
        val parts = page.url.trim('/').split("/")
        val slug = parts.getOrNull(parts.size - 2).orEmpty()
        val number = parts.lastOrNull().orEmpty()
        if (slug.isNotEmpty() && number.isNotEmpty()) {
            val apiText = runCatching {
                val resp = client.newCall(GET("$apiUrl/api/chapters/$slug/$number/", headers)).execute()
                val item = json.decodeFromString<ChapterDetail>(resp.body.string())
                parseChapterContent(item.content)
            }.getOrNull()
            if (!apiText.isNullOrEmpty()) return apiText
        }
        return parseChapterWebPage(page)
    }

    private fun parseChapterContent(content: List<List<String>>): String {
        if (content.isEmpty()) return ""
        val html = content.flatten().joinToString("\n")
        val doc = org.jsoup.Jsoup.parseBodyFragment(html)
        val paragraphs = doc.select("p").mapNotNull { it.text().ifEmpty { null } }
        return if (paragraphs.isNotEmpty()) paragraphs.joinToString("\n\n") else doc.text()
    }

    private suspend fun parseChapterWebPage(page: Page): String {
        val doc = client.newCall(GET("$baseUrl${page.url}", headers)).execute().asJsoup()

        val nuxtScript = doc.select("script").firstOrNull { it.html().contains("window.__NUXT__") }
        if (nuxtScript != null) {
            val nuxtContent = extractNuxtContent(nuxtScript.html())
            if (nuxtContent.isNotEmpty()) return nuxtContent
        }

        val contentEl = doc.selectFirst(
            ".v-card__text.unselectable, .pre-formatted, .chapter-content, .entry-content, .reading-content",
        )
        if (contentEl != null) {
            contentEl.select(
                "script, style, nav, footer, header, .ads, .navigation, .chapter-nav, .prev-next, .share, .comments, .breadcrumb, .v-data-table, table",
            ).remove()
            val paragraphs = contentEl.select("p").mapNotNull { it.text().ifEmpty { null } }
            if (paragraphs.isNotEmpty()) {
                return paragraphs.joinToString("\n\n")
            }
            return contentEl.text()
        }

        return ""
    }

    private fun extractNuxtContent(scriptHtml: String): String {
        val match = Regex("""[A-Za-z_$][A-Za-z0-9_$]*\.content=""").find(scriptHtml) ?: return ""
        val valueStart = match.range.first + match.value.length
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

    private fun NovelItem.toSManga() = SManga.create().apply {
        url = mangaPathTemplate.slug("/novel/$slug")
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
    data class ChapterDetail(
        val id: Int = 0,
        val number: Int = 0,
        val title: String = "",
        val content: List<List<String>> = emptyList(),
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

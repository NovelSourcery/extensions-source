package eu.kanade.tachiyomi.novelextension.ar.mknov

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
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import okhttp3.Headers
import okhttp3.HttpUrl
import org.jsoup.Jsoup

@Source
abstract class Mknov :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Site is behind Cloudflare; KeiSource's client already carries a Cloudflare interceptor. */
    override fun Headers.Builder.configureHeaders(): Headers.Builder = this

    // ======================== Catalog / Latest / Search ========================

    private fun worksUrl(): String = "$baseUrl/api/works?limit=5000"

    override suspend fun getPopularManga(page: Int): MangasPage = parseWorks(client.get(worksUrl(), headers).body.string(), "")

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseWorks(client.get(worksUrl(), headers).body.string(), "")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = parseWorks(client.get(worksUrl(), headers).body.string(), query)

    private fun parseWorks(body: String, query: String): MangasPage {
        val works = try {
            json.decodeFromString<List<Work>>(body)
        } catch (e: Exception) {
            emptyList()
        }

        val paired = works.map { it to it.toSManga() }

        // The API has no server-side search: it returns the full catalog in one shot.
        // We passed the whole catalog via ?limit=5000, so filter that locally by query.
        // Browse paths call with query="" so the main screen always shows the full list.
        val filtered = if (query.isNotBlank()) {
            val q = query.trim()
            paired.filter { (w, m) ->
                (m.title + " " + w.title + " " + w.slug).contains(q, ignoreCase = true) ||
                    w.author.orEmpty().contains(q, ignoreCase = true)
            }.map { it.second }
        } else {
            paired.map { it.second }
        }

        return MangasPage(filtered, false)
    }

    private fun Work.toSManga(): SManga {
        val statusText = status
        val authorText = author
        val genresText = genres.joinToString(", ")
        val descText = description
        val img = image
        val id = this.id
        return SManga.create().apply {
            title = titleAr.ifEmpty { title }
            url = "/novel/$id"
            this.author = authorText?.ifEmpty { null }
            thumbnail_url = img.toAbsoluteUrl()
            status = when (statusText) {
                "مكتملة" -> SManga.COMPLETED
                "متوقفة" -> SManga.ON_HIATUS
                else -> SManga.ONGOING
            }
            description = descText
            genre = genresText
        }
    }

    // ======================== Details ========================

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = client.get(baseUrl + manga.url, headers).asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) loadChapterList(manga) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val response = client.get(url, headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        val doc = response.asJsoup()
        return parseMangaDetails(doc).apply { this.url = url.encodedPath }
    }

    private fun parseMangaDetails(doc: org.jsoup.nodes.Document): SManga {
        val html = doc.outerHtml()

        // Extract the work (donghua) JSON embedded in the RSC flight payload.
        val work = extractDonghua(html)
        if (work != null) {
            return work.toSManga()
        }

        // Fallback: simplest parse from the page.
        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim() ?: "Unknown"
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
        }
    }

    private fun extractDonghua(html: String): Work? {
        // Reassemble the React Server Component flight payload:
        //   <script>self.__next_f.push([N,"..."])</script>
        val sb = StringBuilder()
        val pushRe = Regex("self\\.__next_f\\.push\\((\\[.*?\\])\\)\\s*</script>", setOf(RegexOption.DOT_MATCHES_ALL))
        for (m in pushRe.findAll(html)) {
            val arr = m.groupValues[1]
            try {
                val parsed = json.parseToJsonElement(arr).jsonArray
                // The strings that hold the flight JSON are the items after the leading number.
                for (k in 1 until parsed.size) {
                    val el = parsed[k]
                    val content = (el as? JsonPrimitive)?.contentOrNull
                    if (content != null) {
                        sb.append(content)
                    }
                }
            } catch (_: Exception) {
            }
        }
        val flight = sb.toString()

        // The work object is embedded as  {...,"donghua":{...}} inside the flight JSON
        // (double-escaped in the raw push string).
        val key = "\"donghua\""
        val idx = flight.indexOf(key)
        if (idx < 0) return null
        val brace = flight.indexOf('{', idx)
        if (brace < 0) return null

        val obj = extractBalancedJson(flight, brace) ?: return null
        return try {
            json.decodeFromString<Work>(obj)
        } catch (_: Exception) {
            null
        }
    }

    /** Extract a balanced {...} JSON object starting at `start` (the '{' index). */
    private fun extractBalancedJson(s: String, start: Int): String? {
        var depth = 0
        var inStr = false
        var esc = false
        var i = start
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (esc) {
                    esc = false
                } else if (c == '\\') {
                    esc = true
                } else if (c == '"') {
                    inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return s.substring(start, i + 1)
                        }
                    }
                    else -> {}
                }
            }
            i++
        }
        return null
    }

    // ======================== Chapters ========================

    private suspend fun loadChapterList(manga: SManga): List<SChapter> {
        val id = manga.url.substringAfterLast('/')
        val body = client.get("$baseUrl/api/works/$id/chapters", headers, ensureSuccess = false).body.string()

        val chaptersResponse = try {
            json.decodeFromString<ChaptersResponse>(body)
        } catch (e: Exception) {
            return emptyList()
        }

        val novelId = manga.url.substringAfterLast('/')

        val chapters = chaptersResponse.volumes.flatMap { vol ->
            vol.chapters.map { ch ->
                SChapter.create().apply {
                    url = "/novel/$novelId/chapter/${ch.id}"
                    name = buildString {
                        append("الفصل ${ch.chapterNumber.formatChapterNumber()}")
                        if (ch.chapterTitle.isNotEmpty()) {
                            append(": ${ch.chapterTitle}")
                        }
                    }
                    chapter_number = ch.chapterNumber
                }
            }
        }

        return chapters.sortedByDescending { it.chapter_number }
    }

    // ======================== Chapter content ========================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) {
            page.url
        } else {
            baseUrl + page.url
        }

        val html = client.get(url, headers).body.string()

        // Jsoup parses HTML entities (&quot; etc.) so quotes become real chars.
        val doc = Jsoup.parse(html)

        // 1) Body text: the scrambled box.
        val box = doc.selectFirst("div.whitespace-pre-wrap") ?: return cleanFallback(html)
        val rawBody = box.wholeText()

        // 2) Font URL from the inline @font-face in a <style> tag.
        val fontUrl = doc.select("style").firstNotNullOfOrNull { st ->
            val re = Regex("""src:url\("([^"]+\.woff2[^"]*)"\)""")
            re.find(st.html())?.groupValues?.get(1)
        } ?: return rawBody

        // 3) Fetch the font and decode.
        val fontAbs = if (fontUrl.startsWith("http")) fontUrl else baseUrl + fontUrl
        val woff2 = try {
            client.get(fontAbs, headers).body.bytes()
        } catch (e: Exception) {
            return rawBody
        }

        val decoded = try {
            val font = Woff2Decoder.decode(woff2)
            decodeChapterBody(rawBody, font)
        } catch (e: Exception) {
            rawBody
        }

        return decoded
    }

    private fun decodeChapterBody(body: String, font: Woff2Decoder.DecodedFont): String {
        val out = StringBuilder(body.length)
        for (c in body) {
            val name = font.cmap[c.code]?.let { font.post[it] }
            if (name != null) {
                val real = decodeGlyphName(name)
                if (real != null) {
                    out.append(real)
                }
            } else {
                out.append(c)
            }
        }
        // Collapse runs of blanks, keep newlines.
        return out.toString().replace(Regex("[ \\t\u200b]{2,}"), " ").replace(Regex("\n{3,}"), "\n\n")
    }

    private fun cleanFallback(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("script, style, nav, header, footer, .ad, ins, iframe").remove()
        return doc.body()?.text()?.trim() ?: ""
    }

    // ======================== Filters ========================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("مملكه الروايات – الفهرس الكامل"),
        Filter.Separator(),
        Filter.Header("يتم عرض جميع الروايات الأحدث أولاً"),
    )

    // ======================== Helpers ========================

    private fun String?.toAbsoluteUrl(): String? = when {
        this.isNullOrEmpty() -> null
        startsWith("http") -> this
        startsWith("//") -> "https:" + this
        startsWith("/") -> baseUrl + this
        else -> this
    }

    /** "52.99" → "52.99", "52.0" → "52", "3.5" → "3.5" */
    private fun Float.formatChapterNumber(): String = if (this % 1f == 0f) toInt().toString() else toString()

    // ======================== Data classes ========================

    @Serializable
    data class Work(
        val id: Long = 0,
        val slug: String = "",
        val titleAr: String = "",
        val title: String = "",
        val author: String? = null,
        val translator: String? = null,
        val gradient: String = "",
        val genres: List<String> = emptyList(),
        val year: Int? = null,
        val totalChapters: Int = 0,
        val status: String = "",
        val description: String = "",
        val image: String = "",
        val totalViews: Long = 0,
    )

    @Serializable
    data class ChaptersResponse(
        val volumes: List<Volume> = emptyList(),
    )

    @Serializable
    data class Volume(
        val volumeNumber: Int = 0,
        val title: String = "",
        val chapters: List<Chapter> = emptyList(),
    )

    @Serializable
    data class Chapter(
        @SerialName("id")
        val id: Long = 0,
        @SerialName("chapterNumber")
        val chapterNumber: Float = 0f,
        @SerialName("chapterTitle")
        val chapterTitle: String = "",
        @SerialName("publishDate")
        val publishDate: String = "",
        @SerialName("views")
        val views: Int = 0,
    )
}

package eu.kanade.tachiyomi.novelextension.ar.realmnovel

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
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * realmnovel.com — عالم الروايات بالعربي.
 *
 * The web side is fully server-rendered (no JS API). The site gates chapters
 * behind the mobile app: only the first `maxFree` chapters (50 by default) are
 * readable in a normal browser; later chapters return an HTTP 403
 * when fetched without the app session.
 *
 * This source therefore exposes the public catalog, search, novel details and
 * the **free chapter preview** (1..maxFree). Gated chapters are not returned.
 */
@Source
abstract class RealmNovel :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this

    // ======================== Catalog / Latest / Search ========================

    override suspend fun getPopularManga(page: Int): MangasPage = parseNovelCards(client.get(pageRequest(page), headers).asJsoup())

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseNovelCards(client.get(pageRequest(page), headers).asJsoup())

    private fun pageRequest(page: Int): String {
        val url = if (page <= 1) {
            "$baseUrl/"
        } else {
            "$baseUrl/?page=$page"
        }
        return url
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/?q=${query.trim().replace(" ", "+")}"
        return parseNovelCards(client.get(url, headers).asJsoup())
    }

    /** Parse the homepage / search results: `.g3card` novel cards. */
    private fun parseNovelCards(doc: Document): MangasPage {
        val novels = doc.select("a.g3card").mapNotNull { card ->
            val link = card.attr("href")
            val id = link.substringAfterLast('/')
            if (id.isEmpty() || !id.matches(Regex("[a-f0-9]{24}"))) return@mapNotNull null

            SManga.create().apply {
                title = card.selectFirst(".g3title")?.text()?.trim() ?: "?"
                url = link
                thumbnail_url = card.selectFirst("img")?.attr("src")?.toAbsoluteUrl()

                // English subtitle, e.g. "Shadow Slave"
                val en = card.selectFirst(".g3sub")?.text()?.trim()
                if (!en.isNullOrEmpty()) {
                    genre = en
                }

                // Chapter count
                card.selectFirst(".g3chaps")?.text()?.trim()?.let { chaps ->
                    val n = chaps.replace(Regex("[^0-9]"), "").toIntOrNull()
                    if (n != null) {
                        description = "الفصول: $n"
                    }
                }
            }
        }

        val hasNext = !doc.select("a[href*='?page=']").isEmpty()
        return MangasPage(novels, hasNext)
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
        val updatedChapters = if (fetchChapters) parseChapterList(doc) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val response = client.get(url, headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        val doc = response.asJsoup()
        return parseMangaDetails(doc).apply { this.url = url.encodedPath }
    }

    private fun parseMangaDetails(doc: Document): SManga {
        val track = parseTrackVars(doc)

        val title = track["title"] ?: doc.selectFirst("h1")?.text()?.trim() ?: "?"
        val en = track["titleEn"]

        return SManga.create().apply {
            this.title = title
            url = "/novel/" + (track["id"] ?: "")

            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrl()

            // Genres from JSON-LD
            genre = doc.select("script[type=application/ld+json]").mapNotNull { script ->
                runCatching {
                    val obj = Json.parseToJsonElement(script.data()).jsonObject
                    obj["genre"]?.jsonArray?.joinToString(", ") { it.toString() }
                }.getOrNull()
            }.firstOrNull()

            status = when (track["status"]) {
                "مكتملة" -> SManga.COMPLETED
                "مستمرة" -> SManga.ONGOING
                else -> SManga.ONGOING
            }

            description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
                ?: track["chapters"]?.let { "الفصول: $it" }
        }
    }

    /** Parse all `__RN_TRACK={...}` variables (JSON-ish) into a map. */
    private fun parseTrackVars(doc: Document): Map<String, String> {
        val re = Regex("__RN_TRACK=\\{([^}]+)\\}")
        val map = mutableMapOf<String, String>()
        for (m in re.findAll(doc.html())) {
            for (pair in m.groupValues[1].split(",").map { it.trim() }) {
                val kv = pair.split(":")
                if (kv.size == 2) {
                    map[kv[0].trim()] = kv[1].trim().trim('"', '\'', ' ')
                }
            }
        }
        return map
    }

    // ======================== Chapters ========================

    private fun parseChapterList(doc: Document): List<SChapter> {
        val track = parseTrackVars(doc)

        // Novels may set a custom free cap. Default 50.
        val maxFree = track["maxFree"]?.toIntOrNull() ?: 50

        val chapters = doc.select("a.chapter-row").mapNotNull { link ->
            val href = link.attr("href")
            val m = Regex("""/novel/([a-f0-9]{24})/chapter/(\d+)""").find(href) ?: return@mapNotNull null
            val num = m.groupValues[2].toInt()
            if (num > maxFree) return@mapNotNull null // gated/paywalled

            val label = link.selectFirst("span")?.text()?.trim().orEmpty()
            SChapter.create().apply {
                url = href
                // "الفصل 1 — الفصل 1" → "الفصل 1"
                name = if (label.startsWith("الفصل $num")) "الفصل $num" else label.ifEmpty { "الفصل $num" }
                chapter_number = num.toFloat()
            }
        }.distinctBy { it.url }

        return chapters.sortedByDescending { it.chapter_number }
    }

    // ======================== Chapter content ========================

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) {
            page.url
        } else {
            baseUrl + page.url
        }

        val response = client.newCall(GET(url, headers)).execute()
        if (!response.isSuccessful) {
            response.close()
            throw Exception("رئيس الـ 403: هذا الفصل متوفر فقط داخل التطبيق")
        }
        val doc = Jsoup.parse(response.body.string())

        val content = doc.selectFirst(".chapter-content") ?: return ""
        content.select("script, style").remove()

        // Keep paragraph structure: each <p> becomes a line.
        val paragraphs = content.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
        return if (paragraphs.isEmpty()) {
            content.text().trim()
        } else {
            paragraphs.joinToString("\n")
        }
    }

    // ======================== Filters ========================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("ملاحظة: يتوفر فقط الفصول المجانية (حتى 50)"),
        Filter.Separator(),
        Filter.Header("باقي الفصول تتطلب تطبيق الموقع (مدفوعة)"),
    )

    // ======================== Helpers ========================

    private fun String?.toAbsoluteUrl(): String? = when {
        this.isNullOrEmpty() -> null
        startsWith("http") -> this
        startsWith("//") -> "https:" + this
        startsWith("/") -> baseUrl + this
        else -> this
    }
}

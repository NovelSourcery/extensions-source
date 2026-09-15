package eu.kanade.tachiyomi.novelextension.ar.galaxynovels

import android.util.Log
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class GalaxyNovels :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    private companion object {
        const val TAG = "GalaxyNovels"
    }

    // Explicit instance with ignoreUnknownKeys — the injected app-wide Json is not
    // guaranteed to tolerate the extra keys in the WorReader manifest/pack payloads.
    private val json = Json { ignoreUnknownKeys = true }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(WorReaderCookieInterceptor(baseUrl))

    private fun String?.toAbsoluteUrl(): String? = when {
        this.isNullOrEmpty() -> null
        startsWith("http") -> this
        startsWith("/") -> baseUrl + this
        else -> this
    }

    private class WorReaderCookieInterceptor(private val base: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val original = chain.request()
            val newRequest = original.newBuilder()
                .header("Cookie", "wor_reader_js=1")
                .header("Referer", "$base/")
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                )
                .header("Accept-Language", "ar,en-US;q=0.7,en;q=0.3")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Upgrade-Insecure-Requests", "1")
                .build()
            return chain.proceed(newRequest)
        }
    }

    // ======================== Popular/Latest ========================

    private fun buildPopularMangaRequest(page: Int): Request {
        val url = "$baseUrl/novels/".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "popular")
            .addQueryParameter("period", "all")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = buildPopularMangaRequest(page)
        return parseNovelList(client.get(request.url, request.headers))
    }

    private fun buildLatestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/recent/".toHttpUrl().newBuilder()
            // The /recent/ archive paginates with recent_page, not page
            .addQueryParameter("recent_page", page.toString())
            .build()
        return GET(url, headers)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = buildLatestUpdatesRequest(page)
        return parseNovelList(client.get(request.url, request.headers))
    }

    private fun parseNovelList(response: Response): MangasPage {
        val doc = response.asJsoup()

        val novels = doc.select("article.wor-novel-card").mapNotNull { card ->
            val link = card.selectFirst("a.wor-novel-card__cover") ?: return@mapNotNull null
            val title = card.selectFirst("h3 a")?.text()
                ?: link.attr("aria-label").trim().ifEmpty { return@mapNotNull null }

            SManga.create().apply {
                this.title = title
                url = link.attr("href").removePrefix(baseUrl)
                val img = card.selectFirst("img.wor-cover-img")
                thumbnail_url = img?.attr("data-src")?.toAbsoluteUrl()
                    ?: img?.attr("src")?.toAbsoluteUrl()
            }
        }

        val hasNextPage = doc.selectFirst("a:contains(التالي)") != null

        return MangasPage(novels, hasNextPage)
    }

    // ======================== Search ========================

    private fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            val url = "$baseUrl/library/".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                // The WorReader library page paginates with library_page, not page
                .addQueryParameter("library_page", page.toString())
                .build()
            return GET(url, headers)
        }

        val url = "$baseUrl/library/".toHttpUrl().newBuilder()
            // Filter-only browsing on /library/ also paginates via library_page
            .addQueryParameter("library_page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    val included = filter.state.filter { it.isIncluded() }
                    if (included.isNotEmpty()) {
                        included.forEach { genre ->
                            url.addQueryParameter("genre[]", genre.name)
                        }
                    }
                }
                is StatusFilter -> {
                    val selected = filter.state.filter { it.state }
                    if (selected.isNotEmpty()) {
                        selected.forEach { status ->
                            url.addQueryParameter("status[]", status.value)
                        }
                    }
                }
                is SortFilter -> url.addQueryParameter("sort", filter.toUriPart())
                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val searchRequest = buildSearchMangaRequest(page, query, filters)
        val response = client.get(searchRequest.url, searchRequest.headers)
        val doc = response.asJsoup()

        val novels = doc.select("article.wor-library-card, article.wor-novel-card").mapNotNull { card ->
            val titleLink = card.selectFirst("h2.wor-library-card__title a, h3 a")
                ?: card.selectFirst("a.wor-library-card__cover, a[href*=novel]") ?: return@mapNotNull null
            val imgElement = card.selectFirst("img.wor-cover-img, img")
            val href = titleLink.attr("href")
            val title = titleLink.text().ifEmpty {
                titleLink.attr("aria-label").trim().ifEmpty { return@mapNotNull null }
            }
            val relativeUrl = href.removePrefix(baseUrl)

            if (relativeUrl.isNotEmpty() && title.isNotEmpty()) {
                SManga.create().apply {
                    this.title = title
                    url = relativeUrl
                    thumbnail_url = imgElement?.attr("data-src")?.toAbsoluteUrl()
                        ?: imgElement?.attr("src")?.toAbsoluteUrl()
                }
            } else {
                null
            }
        }

        val hasNextPage = doc.selectFirst("a:contains(التالي)") != null

        return MangasPage(novels, hasNextPage)
    }

    // ======================== Novel Details + Chapters ========================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        val response = client.get(url, headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        val doc = response.asJsoup()
        return parseMangaDetails(doc).apply { this.url = path }
    }

    private fun parseMangaDetails(doc: org.jsoup.nodes.Document): SManga = SManga.create().apply {
        title = doc.selectFirst("h1")?.text() ?: "No Title"

        val img = doc.selectFirst("img.wor-cover-img")
        thumbnail_url = img?.attr("data-src")?.toAbsoluteUrl()
            ?: img?.attr("src")?.toAbsoluteUrl()

        author = doc.selectFirst(".wor-single-hero__meta-text span")?.text()

        val genres = doc.select(".wor-tag-pill").map { it.text() }
        genre = genres.joinToString()

        val statusText = doc.selectFirst(".wor-cover-status")?.text()?.lowercase()
        status = when {
            statusText?.contains("مستمرة") == true -> SManga.ONGOING
            statusText?.contains("مكتملة") == true -> SManga.COMPLETED
            statusText?.contains("متوقفة") == true -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        description = doc.selectFirst(".wor-single-summary__text")?.let { element ->
            element.select("script, style").remove()
            element.text()
        }

        val chapterCountText = doc.select(".wor-single-stats__item")
            .firstOrNull { it.text().contains("عدد الفصول") }
            ?.selectFirst("strong")?.text()
        val chapterCount = chapterCountText?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0

        if (chapterCount > 0) {
            description = buildString {
                append(description.orEmpty())
                append("\n\nعدد الفصول: $chapterCount")
            }
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = client.get(baseUrl + manga.url, headers, ensureSuccess = false).asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) fetchChapterList(manga, doc) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private suspend fun fetchChapterList(manga: SManga, doc: org.jsoup.nodes.Document): List<SChapter> = // The app can cancel getMangaUpdate mid-fetch (background update + foreground
        // detail refresh overlap). Wrap the whole network chain in NonCancellable so a
        // cancelled coroutine doesn't abort the manifest/pack fetch and drop us to
        // the 30-chapter HTML fallback.
        withContext(NonCancellable) {
            val novelId = doc.selectFirst("[data-novel-id]")?.attr("data-novel-id")
                ?: doc.selectFirst("[data-wor-current-novel]")?.attr("data-novel-id")

            // Fallback: try to derive the novel id from the URL slug if the page lacks the attr.
            if (novelId == null) {
                Log.w(TAG, "fetchChapterList: detail page has no data-novel-id; manga.url=${manga.url}")
            } else {
                Log.i(TAG, "fetchChapterList: novelId=$novelId for ${manga.url}")
            }

            // Step 1: try the WorReader v2 manifest + pack endpoints (they return ALL chapters).
            if (novelId != null) {
                try {
                    val manifestUrl = "$baseUrl/wp-content/uploads/wor-reader-cache/chapters/manifest/novel-$novelId.json"
                    val manifestResp = client.get(manifestUrl, headers, ensureSuccess = false)
                    if (manifestResp.isSuccessful) {
                        val manifestBody = manifestResp.body.string()
                        val manifest = json.decodeFromString<ManifestResponse>(manifestBody)
                        Log.i(TAG, "fetchChapterList: manifest ok total=${manifest.total} pack_url=${manifest.pack_url}")

                        val packUrl = manifest.pack_url
                        if (packUrl.isNotBlank()) {
                            val packResp = client.get(packUrl, headers, ensureSuccess = false)
                            if (packResp.isSuccessful) {
                                val packBody = packResp.body.string()
                                val pack = json.decodeFromString<PackResponse>(packBody)
                                Log.i(TAG, "fetchChapterList: pack ok chapters=${pack.chapters.size} total=${pack.total}")
                                return@withContext pack.chapters.toChapterList()
                            } else {
                                Log.w(TAG, "fetchChapterList: pack HTTP ${packResp.code}")
                            }
                        }

                        if (manifest.live_tail.isNotEmpty()) {
                            Log.i(TAG, "fetchChapterList: using live_tail size=${manifest.live_tail.size}")
                            return@withContext manifest.live_tail.toChapterList()
                        }
                    } else {
                        Log.w(TAG, "fetchChapterList: manifest HTTP ${manifestResp.code}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "fetchChapterList: manifest/pack error", e)
                }
            }

            // Step 2: fallback — the old legacy JSON endpoint.
            if (novelId != null) {
                try {
                    val resp = client.get(
                        "$baseUrl/wp-content/uploads/wor-reader-cache/chapters/novel-$novelId.json",
                        headers,
                        ensureSuccess = false,
                    )
                    if (resp.isSuccessful) {
                        val chaptersResponse = json.decodeFromString<ChaptersResponse>(resp.body.string())
                        Log.i(TAG, "fetchChapterList: legacy ok chapters=${chaptersResponse.chapters.size}")
                        return@withContext chaptersResponse.chapters.toChapterList()
                    } else {
                        Log.w(TAG, "fetchChapterList: legacy HTTP ${resp.code}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "fetchChapterList: legacy error", e)
                }
            }

            // Step 3: last resort — parse the detail page HTML (has the ~30 most recent chapters).
            Log.w(TAG, "fetchChapterList: falling back to HTML chapters")
            val htmlDoc = if (doc.select("article.wor-novel-chapter-item").isEmpty()) {
                client.get(baseUrl + manga.url, headers, ensureSuccess = false).asJsoup()
            } else {
                doc
            }
            return@withContext parseChaptersFromHtml(htmlDoc)
        }

    private fun List<ChapterData>.toChapterList(): List<SChapter> = map { chapter ->
        SChapter.create().apply {
            url = chapter.url.removePrefix(baseUrl)
            name = chapter.label.ifEmpty { "الفصل ${chapter.number}" }
            if (chapter.title.isNotEmpty()) {
                name += " - ${chapter.title}"
            }
            chapter_number = chapter.number.toFloatOrNull() ?: chapter.position.toFloat()
            date_upload = parseDate(chapter.date_iso)
        }
    }.sortedByDescending { it.chapter_number }

    private fun parseChaptersFromHtml(doc: org.jsoup.nodes.Document): List<SChapter> {
        return doc.select("article.wor-novel-chapter-item").mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val chapterNum = item.selectFirst(".wor-novel-chapter-item__num")?.text()
                ?.toFloatOrNull() ?: -1f
            val title = item.selectFirst("h3 a")?.text() ?: ""
            val dateText = item.selectFirst("time")?.attr("datetime") ?: ""

            SChapter.create().apply {
                url = link.attr("href").removePrefix(baseUrl)
                name = title.ifEmpty { "الفصل ${chapterNum.toInt()}" }
                chapter_number = chapterNum
                date_upload = parseDate(dateText)
            }
        }.sortedByDescending { it.chapter_number }
    }

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isEmpty()) return 0L
        return try {
            val formats = listOf(
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH),
                SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH),
            )
            for (format in formats) {
                try {
                    return format.parse(dateStr)?.time ?: continue
                } catch (_: Exception) {
                    continue
                }
            }
            0L
        } catch (_: Exception) {
            0L
        }
    }

    // ======================== Chapter Content ========================

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) {
            page.url
        } else {
            baseUrl + page.url
        }

        val requestHeaders = Headers.Builder()
            .add("Cookie", "wor_reader_js=1")
            .add("Referer", "$baseUrl/")
            .add(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            )
            .add("Accept-Language", "ar,en-US;q=0.7,en;q=0.3")
            .add("Sec-Fetch-Dest", "document")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "same-origin")
            .add("Upgrade-Insecure-Requests", "1")
            .build()

        val response = client.get(url, requestHeaders)
        val doc = response.asJsoup()

        val content = doc.selectFirst(
            ".wor-chapter-content, .entry-content, .chapter-content, .post-content, article .content",
        ) ?: doc.selectFirst("article")

        if (content == null) {
            val body = doc.body()
            body.select("script, style, ins, iframe, .ads, .ad-unit, [data-ad-position], nav, header, footer").remove()
            return body.html()
        }

        content.select("script, style, ins, iframe, .ads, .ad-unit, [data-ad-position]").remove()

        return content.html()
    }

    // ======================== Filters ========================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("الفلاتر متاحة فقط مع البحث بالتصنيفات"),
        SortFilter(),
        StatusFilter(),
        Filter.Separator(),
        Filter.Header("التصنيفات"),
        GenreFilter(),
    )

    private class SortFilter :
        Filter.Select<String>(
            "الترتيب",
            arrayOf("الافتراضي", "حسب الاسم", "الأكثر مشاهدة", "حسب الترتيب", "الأكثر فصولاً"),
        ) {
        fun toUriPart(): String = when (state) {
            1 -> "name"
            2 -> "views"
            3 -> "rank"
            4 -> "chapters"
            else -> ""
        }
    }

    private class StatusCheckBox(name: String, val value: String) : Filter.CheckBox(name)

    private class StatusFilter :
        Filter.Group<StatusCheckBox>(
            "الحالة",
            listOf(
                StatusCheckBox("مستمرة", "ongoing"),
                StatusCheckBox("متوقفة", "hiatus"),
                StatusCheckBox("مكتملة", "completed"),
            ),
        )

    private class GenreTriState(name: String) : Filter.TriState(name)

    private class GenreFilter :
        Filter.Group<GenreTriState>(
            "التصنيفات",
            listOf(
                "أكشن", "البطل ذكر", "البطل انثى", "الزراعة", "الهجرة",
                "بناء القواعد", "تاريخي", "تشويق", "خيال", "خيال علمي",
                "دراما", "رعب", "رعب بالغ", "سحر", "شونين",
                "عسكري", "غموض", "فانتازيا", "فنون قتالية", "قتال",
                "قوى خارقة", "كوميديا", "لعبة", "محاكي", "مغامرة",
                "مهارات القتال", "نظام", "نفسي",
            ).map { GenreTriState(it) },
        )

    // ======================== Data Classes ========================

    @Serializable
    class ChaptersResponse(
        val chapters: List<ChapterData> = emptyList(),
    )

    /** WorReader v2 manifest: metadata + a link to the full chapter pack. */
    @Serializable
    class ManifestResponse(
        val schema: Int = 0,
        val novel_id: Long = 0,
        val total: Int = 0,
        val live_tail_count: Int = 0,
        val pack: String = "",
        @SerialName("pack_url")
        val pack_url: String = "",
        val live_tail: List<ChapterData> = emptyList(),
    )

    /** WorReader v2 pack: the full chapter list for a novel. */
    @Serializable
    class PackResponse(
        val version: JsonElement = JsonNull,
        val novel_id: Long = 0,
        val total: Int = 0,
        val chapters: List<ChapterData> = emptyList(),
    )

    @Serializable
    class ChapterData(
        val id: Long = 0,
        val position: Int = 0,
        val number: String = "",
        val label: String = "",
        val title: String = "",
        val url: String = "",
        val date: String = "",
        @SerialName("date_iso")
        val date_iso: String = "",
        val views: Int = 0,
        val comments: Int = 0,
    )
}

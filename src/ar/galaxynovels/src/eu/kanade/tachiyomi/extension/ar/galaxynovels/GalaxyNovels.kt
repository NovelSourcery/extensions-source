package eu.kanade.tachiyomi.novelextension.ar.galaxynovels

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class GalaxyNovels :
    HttpSource(),
    NovelSource {

    override val name = "Galaxy Novels"
    override val baseUrl = "https://galaxynovels.com"
    override val lang = "ar"
    override val supportsLatest = true

    private val json: Json by injectLazy()

    override val isNovelSource = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor(WorReaderCookieInterceptor(baseUrl))
        .build()

    override fun imageUrlParse(response: Response): String = ""

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

    override fun popularMangaRequest(page: Int): Request {
        val url = "$baseUrl/novels/".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "popular")
            .addQueryParameter("period", "all")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage = parseNovelList(response)

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/recent/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = parseNovelList(response)

    private fun parseNovelList(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())

        val novels = doc.select("article.wor-novel-card").mapNotNull { card ->
            val link = card.selectFirst("a.wor-novel-card__cover") ?: return@mapNotNull null
            val title = card.selectFirst("h3 a")?.text()?.trim()
                ?: link.attr("aria-label").trim().ifEmpty { return@mapNotNull null }

            SManga.create().apply {
                this.title = title
                url = link.attr("href").removePrefix(baseUrl)
                thumbnail_url = card.selectFirst("img.wor-cover-img")?.attr("src").toAbsoluteUrl()
            }
        }

        val hasNextPage = doc.selectFirst("a:contains(التالي)") != null

        return MangasPage(novels, hasNextPage)
    }

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            val url = "$baseUrl/library/".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .build()
            return GET(url, headers)
        }

        val url = "$baseUrl/library/".toHttpUrl().newBuilder()

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

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())

        val novels = doc.select("article.wor-novel-card").mapNotNull { card ->
            val link = card.selectFirst("a[href*=novel]") ?: return@mapNotNull null
            val title = card.selectFirst("h3 a")?.text()?.trim()
                ?: link.attr("aria-label").trim().ifEmpty { return@mapNotNull null }

            SManga.create().apply {
                this.title = title
                url = link.attr("href").removePrefix(baseUrl)
                thumbnail_url = card.selectFirst("img")?.attr("src").toAbsoluteUrl()
            }
        }

        val hasNextPage = doc.selectFirst("a:contains(التالي)") != null

        return MangasPage(novels, hasNextPage)
    }

    // ======================== Novel Details ========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text()?.trim() ?: "No Title"

            thumbnail_url = doc.selectFirst("img.wor-cover-img")?.attr("src").toAbsoluteUrl()

            author = doc.selectFirst(".wor-single-hero__meta-text span")?.text()?.trim()

            val genres = doc.select(".wor-tag-pill").map { it.text().trim() }
            genre = genres.joinToString(", ")

            val statusText = doc.selectFirst(".wor-cover-status")?.text()?.lowercase()
            status = when {
                statusText?.contains("مستمرة") == true -> SManga.ONGOING
                statusText?.contains("مكتملة") == true -> SManga.COMPLETED
                statusText?.contains("متوقفة") == true -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }

            description = doc.selectFirst(".wor-single-summary__text")?.let { element ->
                element.select("script, style").remove()
                element.text().trim()
            }

            val chapterCountText = doc.select(".wor-single-stats__item")
                .firstOrNull { it.text().contains("عدد الفصول") }
                ?.selectFirst("strong")?.text()?.trim()
            val chapterCount = chapterCountText?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: 0

            if (chapterCount > 0) {
                description = buildString {
                    append(description.orEmpty())
                    append("\n\nعدد الفصول: $chapterCount")
                }
            }
        }
    }

    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request {
        val response = client.newCall(GET(baseUrl + manga.url, headers)).execute()
        val doc = Jsoup.parse(response.body.string())

        val novelId = doc.selectFirst("[data-novel-id]")?.attr("data-novel-id")
            ?: doc.selectFirst("[data-wor-current-novel]")?.attr("data-novel-id")

        val chaptersUrl = if (novelId != null) {
            "$baseUrl/wp-content/uploads/wor-reader-cache/chapters/novel-$novelId.json"
        } else {
            null
        }

        return if (chaptersUrl != null) {
            GET(chaptersUrl, headers)
        } else {
            GET(baseUrl + manga.url, headers)
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()

        return try {
            val chaptersResponse = json.decodeFromString<ChaptersResponse>(body)
            chaptersResponse.chapters.map { chapter ->
                SChapter.create().apply {
                    url = chapter.url.removePrefix(baseUrl)
                    name = chapter.label.ifEmpty { "الفصل ${chapter.number}" }
                    if (chapter.title.isNotEmpty()) {
                        name += " - ${chapter.title}"
                    }
                    number = chapter.number.toFloatOrNull() ?: chapter.position.toFloat()
                    date_upload = parseDate(chapter.date_iso)
                }
            }.sortedByDescending { it.chapter_number }
        } catch (_: Exception) {
            parseChaptersFromHtml(body)
        }
    }

    private fun parseChaptersFromHtml(html: String): List<SChapter> {
        val doc = Jsoup.parse(html)

        return doc.select("article.wor-novel-chapter-item").mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val chapterNum = item.selectFirst(".wor-novel-chapter-item__num")?.text()?.trim()
                ?.toFloatOrNull() ?: -1f
            val title = item.selectFirst("h3 a")?.text()?.trim() ?: ""
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

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.encodedPath))

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) {
            page.url
        } else {
            baseUrl + page.url
        }

        val request = Request.Builder()
            .url(url)
            .header("Cookie", "wor_reader_js=1")
            .header("Referer", "$baseUrl/")
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

        val response = client.newCall(request).execute()
        val doc = Jsoup.parse(response.body.string())

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

    override fun getFilterList(): FilterList = FilterList(
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
    data class ChaptersResponse(
        val chapters: List<ChapterData> = emptyList(),
    )

    @Serializable
    data class ChapterData(
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

package eu.kanade.tachiyomi.multisrc.syosetu

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.RateLimited
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.lib.chapterutils.checkCloudflare
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

enum class SiteType {
    NCODE, // ncode.syosetu.com
    NOVEL18, // novel18.syosetu.com
    NOC, // noc.syosetu.com (Nocturne)
    MNLT, // mnlt.syosetu.com (Moonlight)
    MID, // mid.syosetu.com (Midnight)
}

abstract class SyosetuBase(
    protected val siteType: SiteType,
    protected val isAdult: Boolean = false,
    protected val supportsRanking: Boolean = true,
) : KeiSource(),
    NovelSource,
    RateLimited {

    override val isNovelSource = true

    override val minimumDelayMillis = 700L
    override val recommendedDelayMillis = 1000L
    override val recommendedPermits = 2

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(minimumDelayMillis, recommendedPermits)

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.ROOT)
    private val mangaPath = SlugPath("/")

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPath.resolve(manga.url)
    override fun getChapterUrl(chapter: SChapter): String = baseUrl + mangaPath.resolve(chapter.url)

    // novel18/mid/mnlt gate their listings behind an age-confirmation cookie
    private fun requestHeaders(): Headers = if (isAdult) {
        headers.newBuilder().add("Cookie", "over18=yes").build()
    } else {
        headers
    }

    // ---------- Popular / Latest ----------
    // NOC/MNLT/MID have no ranking pages - only search is available on those sites
    override suspend fun getPopularManga(page: Int): MangasPage = if (supportsRanking) {
        fetchNovelList(buildRankingUrl(page, "total", "", "total"))
    } else {
        MangasPage(emptyList(), false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = if (supportsRanking) {
        fetchNovelList(buildRankingUrl(page, "daily", "", "total"))
    } else {
        MangasPage(emptyList(), false)
    }

    // ---------- Search ----------
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = if (query.isNotBlank()) {
        fetchNovelList(buildSearchUrl(page, query))
    } else if (supportsRanking) {
        fetchNovelList(buildFilteredSearchUrl(page, filters))
    } else {
        MangasPage(emptyList(), false)
    }

    // ---------- URL builders ----------
    protected open fun buildRankingUrl(page: Int, ranking: String, genre: String, modifier: String): String {
        val base = "$baseUrl/rank/list/type/${ranking}_$modifier/"
        val url = if (genre.isEmpty()) {
            base
        } else {
            val genrePart = if (genre == "1" || genre == "2" || genre == "o") "isekailist" else "genrelist"
            val modifierPart = if (modifier == "total") "" else "_$modifier"
            "$baseUrl/rank/$genrePart/type/${ranking}_${genre}$modifierPart/"
        }
        return "$url?p=$page"
    }

    protected open fun buildSearchUrl(page: Int, query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchPath = when (siteType) {
            SiteType.NCODE, SiteType.NOVEL18 -> "search.php"
            SiteType.NOC, SiteType.MNLT, SiteType.MID -> "search/search/search.php"
        }
        return "$baseUrl/$searchPath?word=$encoded&p=$page"
    }

    protected open fun buildFilteredSearchUrl(page: Int, filters: FilterList): String {
        var ranking = "total"
        var genre = ""
        var modifier = "total"
        filters.forEach { filter ->
            when (filter) {
                is RankingFilter -> ranking = filter.toUriPart()
                is GenreFilter -> genre = filter.toUriPart()
                is ModifierFilter -> modifier = filter.toUriPart()
                else -> {}
            }
        }
        return buildRankingUrl(page, ranking, genre, modifier)
    }

    // ---------- Fetch & parse ----------
    private suspend fun fetchNovelList(url: String): MangasPage {
        val response = client.get(url, requestHeaders())
        val doc = response.asJsoup()
        checkCloudflare(doc)
        return parseNovelList(doc)
    }

    protected open fun parseNovelList(doc: Document): MangasPage {
        val elements = when (siteType) {
            SiteType.NCODE, SiteType.NOVEL18 -> doc.select(".c-card, .searchkekka_box, .p-ranklist-item")
            SiteType.NOC, SiteType.MNLT, SiteType.MID -> doc.select(".searchkekka_box, .rank_h, .ranking_top5box ul li, div.in_box div.searchkekka_box")
        }.ifEmpty { doc.select("div:has(a.tl)") }

        val novels = elements.mapNotNull { element ->
            val link = element.selectFirst("a.tl")
                ?: element.selectFirst(".novel_h a.tl")
                ?: element.selectFirst("a[href*='/n']")
                ?: element.selectFirst("a")
                ?: return@mapNotNull null

            val title = link.attr("title").ifEmpty { link.text() }.trim()
            val href = link.attr("abs:href")
            if (title.isBlank() || href.isBlank()) return@mapNotNull null

            val coverImg = element.selectFirst("img")
            val coverUrl = coverImg?.attr("abs:data-src")?.takeIf { it.isNotBlank() }
                ?: coverImg?.attr("abs:src")?.takeIf { it.isNotBlank() }

            SManga.create().apply {
                this.title = title
                url = mangaPath.slug(href.toHttpUrl().encodedPath)
                thumbnail_url = coverUrl
            }
        }

        val hasNextPage = when (siteType) {
            SiteType.NCODE, SiteType.NOVEL18 -> doc.selectFirst("a.c-pager__item--next, a[rel=next]") != null
            SiteType.NOC, SiteType.MNLT, SiteType.MID -> doc.selectFirst("a.next, .pager a[href*='p=']") != null
        }

        return MangasPage(novels, hasNextPage)
    }

    // ---------- Novel details ----------
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        val response = client.get(baseUrl + path, requestHeaders(), ensureSuccess = false)
        if (!response.isSuccessful) return null
        val doc = response.asJsoup()
        checkCloudflare(doc)
        return mangaDetailsParse(doc).apply {
            this.url = mangaPath.slug(path)
        }
    }

    private fun mangaDetailsParse(doc: Document): SManga = SManga.create().apply {
        title = doc.selectFirst(".p-novel__title, h1.p-novel__title, .novel_title")?.text()?.trim()
            ?: "No Title"
        author = doc.selectFirst(".p-novel__author a, .p-novel__author, .novel_author a")?.text()?.trim()
            ?.replace("作者：", "") ?: "Unknown"
        description = doc.selectFirst("#novel_ex")?.html()?.trim()
            ?: doc.selectFirst(".p-novel__synopsis")?.text()?.trim() ?: ""

        val cover = doc.selectFirst(".p-novel__cover img, .novel_img img, .book-cover img")
        thumbnail_url = cover?.attr("abs:data-src")?.takeIf { it.isNotBlank() }
            ?: cover?.attr("abs:src")?.takeIf { it.isNotBlank() }

        genre = doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?.split(" ")?.joinToString() ?: ""

        val statusText = doc.selectFirst(".c-announce, .novel_status")?.text()?.trim() ?: ""
        status = when {
            statusText.contains("完結") -> SManga.COMPLETED
            statusText.contains("連載中") || statusText.contains("未完結") -> SManga.ONGOING
            statusText.contains("更新されていません") -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    // ---------- fetchMangaUpdate ----------
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get(baseUrl + mangaPath.resolve(manga.url), requestHeaders())
        val doc = response.asJsoup()
        checkCloudflare(doc)

        val updatedManga = if (fetchDetails) mangaDetailsParse(doc).apply { this.url = manga.url } else manga
        val updatedChapters = if (fetchChapters) fetchChapterListFromDoc(manga.url, doc) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    // ---------- Chapters ----------
    private suspend fun fetchChapterListFromDoc(mangaUrl: String, doc: Document): List<SChapter> {
        val totalChapters = doc.selectFirst(".p-infotop-type__allep")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
            ?: doc.selectFirst("#gotochapno")?.attr("max")?.toIntOrNull()
            ?: 0

        if (totalChapters == 0) {
            val isOneShot = doc.selectFirst(".p-infotop-type__type")?.text()?.contains("短編") == true
            if (!isOneShot) return emptyList()

            val title = doc.selectFirst(".p-novel__title")?.text()?.trim() ?: "One-shot"
            return listOf(
                SChapter.create().apply {
                    name = title
                    url = mangaUrl
                    date_upload = 0
                    chapter_number = 1f
                },
            )
        }

        val basePath = mangaPath.resolve(mangaUrl)
        val pageCount = ceil(totalChapters / 100.0).toInt().coerceAtLeast(1)

        val chapters = mutableListOf<SChapter>()
        var chapterNumber = 1f
        for (page in 1..pageCount) {
            val pageDoc = if (page == 1) {
                doc
            } else {
                val response = client.get("$baseUrl$basePath?p=$page", requestHeaders())
                response.asJsoup()
            }
            checkCloudflare(pageDoc)

            pageDoc.select(".p-eplist__sublist").forEach { element ->
                val link = element.selectFirst("a") ?: return@forEach
                val chapterUrl = mangaPath.slug(link.attr("abs:href").toHttpUrl().encodedPath)
                val chapterName = link.text().trim()
                val dateText = element.selectFirst(".p-eplist__update")?.text()?.trim() ?: ""

                chapters.add(
                    SChapter.create().apply {
                        name = chapterName
                        url = chapterUrl
                        date_upload = parseDate(dateText)
                        chapter_number = chapterNumber
                    },
                )
                chapterNumber++
            }
        }

        return chapters.sortedBy { it.chapter_number }
    }

    private fun parseDate(dateStr: String): Long = runCatching {
        LocalDateTime.parse(dateStr, dateFormat).atZone(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    // ---------- Chapter content ----------
    override suspend fun fetchPageText(page: Page): String {
        val response = client.get(baseUrl + mangaPath.resolve(page.url), requestHeaders())
        val doc = response.asJsoup()
        checkCloudflare(doc)

        val contentDiv = doc.selectFirst("#novel_honbun, .p-novel__text:not(.p-novel__text--preface):not(.p-novel__text--afterword)")
            ?: doc.selectFirst(".p-novel__body")
        return contentDiv?.html() ?: ""
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    // ---------- Filters ----------
    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Ranking"),
        RankingFilter(),
        Filter.Header("Genre"),
        GenreFilter(),
        Filter.Header("Modifier"),
        ModifierFilter(),
    )

    private class RankingFilter :
        Filter.Select<String>(
            "Rank by",
            arrayOf("日間", "週間", "月間", "四半期", "年間", "累計"),
        ) {
        fun toUriPart(): String = when (state) {
            0 -> "daily"
            1 -> "weekly"
            2 -> "monthly"
            3 -> "quarter"
            4 -> "yearly"
            5 -> "total"
            else -> "total"
        }
    }

    private class GenreFilter :
        Filter.Select<String>(
            "Genre",
            arrayOf(
                "総ジャンル",
                "異世界転生/転移〔恋愛〕",
                "異世界転生/転移〔ファンタジー〕",
                "異世界転生/転移〔文芸・SF・その他〕",
                "異世界〔恋愛〕",
                "現実世界〔恋愛〕",
                "ハイファンタジー〔ファンタジー〕",
                "ローファンタジー〔ファンタジー〕",
                "純文学〔文芸〕",
                "ヒューマンドラマ〔文芸〕",
                "歴史〔文芸〕",
                "推理〔文芸〕",
                "ホラー〔文芸〕",
                "アクション〔文芸〕",
                "コメディー〔文芸〕",
                "VRゲーム〔SF〕",
                "宇宙〔SF〕",
                "空想科学〔SF〕",
                "パニック〔SF〕",
                "童話〔その他〕",
                "詩〔その他〕",
                "エッセイ〔その他〕",
                "その他〔その他〕",
            ),
        ) {
        fun toUriPart(): String = when (state) {
            0 -> ""
            1 -> "1"
            2 -> "2"
            3 -> "o"
            4 -> "101"
            5 -> "102"
            6 -> "201"
            7 -> "202"
            8 -> "301"
            9 -> "302"
            10 -> "303"
            11 -> "304"
            12 -> "305"
            13 -> "306"
            14 -> "307"
            15 -> "401"
            16 -> "402"
            17 -> "403"
            18 -> "404"
            19 -> "9901"
            20 -> "9902"
            21 -> "9903"
            22 -> "9999"
            else -> ""
        }
    }

    private class ModifierFilter :
        Filter.Select<String>(
            "Modifier",
            arrayOf("すべて", "連載中", "完結済", "短編"),
        ) {
        fun toUriPart(): String = when (state) {
            0 -> "total"
            1 -> "r"
            2 -> "er"
            3 -> "t"
            else -> "total"
        }
    }
}

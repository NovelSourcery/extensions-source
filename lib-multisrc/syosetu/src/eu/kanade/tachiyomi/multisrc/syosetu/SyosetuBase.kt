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
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
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

@Serializable
class Novel18ApiEntry(
    val allcount: Int? = null,
    val title: String = "",
    val ncode: String = "",
    val writer: String = "",
    val story: String = "",
)

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

    // NOC/MID/MNLT are genre-curated ranking & search fronts over novel18.syosetu.com - every
    // novel/chapter/detail page they link to actually lives on novel18.syosetu.com itself
    // (their own hosts 404 on a novel path), so all content fetches must go there instead of
    // browseBaseUrl/baseUrl.
    private val contentBaseUrl = when (siteType) {
        SiteType.NOC, SiteType.MID, SiteType.MNLT -> "https://novel18.syosetu.com"
        else -> baseUrl
    }

    override fun getMangaUrl(manga: SManga): String = contentBaseUrl + mangaPath.resolve(manga.url)
    override fun getChapterUrl(chapter: SChapter): String = contentBaseUrl + mangaPath.resolve(chapter.url)

    // novel18/mid/mnlt gate their listings behind an age-confirmation cookie
    private fun requestHeaders(): Headers = if (isAdult) {
        headers.newBuilder().add("Cookie", "over18=yes").build()
    } else {
        headers
    }

    // ---------- Popular / Latest ----------
    // NOC/MNLT/MID do have their own ranking pages (/rank/list/type/{period}_{modifier}/), just
    // no genre breakdown and no all-time "累計" period (only daily/weekly/monthly/quarter/yearly
    // - "total" 404s there), and no ranking-based notion of "latest" - that comes from the
    // site's own search listing sorted by order=new instead (see buildOrderedListUrl).
    // NOVEL18 has neither a ranking nor a search HTML page anymore (both 404 even with the
    // age cookie) - it's browsed through the public novel18api JSON API instead.
    override suspend fun getPopularManga(page: Int): MangasPage = when {
        siteType == SiteType.NOVEL18 -> fetchNovel18ApiList(page, order = "hyoka")
        siteType == SiteType.NOC || siteType == SiteType.MID || siteType == SiteType.MNLT ->
            fetchNovelList(buildRankingUrl(page, "daily", "", "total"))
        supportsRanking -> fetchNovelList(buildRankingUrl(page, "total", "", "total"))
        else -> MangasPage(emptyList(), false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = when {
        siteType == SiteType.NOVEL18 -> fetchNovel18ApiList(page, order = "new")
        siteType == SiteType.NOC || siteType == SiteType.MID || siteType == SiteType.MNLT ->
            fetchNovelList(buildOrderedListUrl(page, "new"))
        supportsRanking -> fetchNovelList(buildRankingUrl(page, "daily", "", "total"))
        else -> MangasPage(emptyList(), false)
    }

    // ---------- Search ----------
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = when {
        siteType == SiteType.NOVEL18 -> fetchNovel18ApiList(page, order = "hyoka", word = query.takeIf { it.isNotBlank() })
        query.isNotBlank() -> fetchNovelList(buildSearchUrl(page, query))
        supportsRanking -> fetchNovelList(buildFilteredSearchUrl(page, filters))
        else -> MangasPage(emptyList(), false)
    }

    // ---------- NOVEL18 JSON API browsing ----------
    private val novel18ApiUrl = "https://api.syosetu.com/novel18api/api/"
    private val novel18PageSize = 20

    private suspend fun fetchNovel18ApiList(page: Int, order: String, word: String? = null): MangasPage {
        val apiUrl = novel18ApiUrl.toHttpUrl().newBuilder()
            .addQueryParameter("out", "json")
            .addQueryParameter("order", order)
            .addQueryParameter("lim", novel18PageSize.toString())
            .addQueryParameter("st", (((page - 1) * novel18PageSize) + 1).toString())
            .apply { if (!word.isNullOrBlank()) addQueryParameter("word", word) }
            .build()

        val entries = client.get(apiUrl, headers).parseAs<List<Novel18ApiEntry>>()
        val allCount = entries.firstOrNull()?.allcount ?: 0
        val novels = entries.filter { it.ncode.isNotEmpty() }.map { entry ->
            SManga.create().apply {
                title = entry.title
                author = entry.writer
                description = entry.story
                url = mangaPath.slug("/${entry.ncode.lowercase()}/")
            }
        }
        return MangasPage(novels, page * novel18PageSize < allCount)
    }

    // ---------- URL builders ----------
    // NCODE novel pages live on ncode.syosetu.com, but ranking/search for that catalog is only
    // served from the separate yomou.syosetu.com host (ncode.syosetu.com/rank|search 404s).
    // NOC/MNLT/MID serve both from their own host.
    private val browseBaseUrl = if (siteType == SiteType.NCODE) "https://yomou.syosetu.com" else baseUrl

    protected open fun buildRankingUrl(page: Int, ranking: String, genre: String, modifier: String): String {
        val base = "$browseBaseUrl/rank/list/type/${ranking}_$modifier/"
        val url = if (genre.isEmpty()) {
            base
        } else {
            val genrePart = if (genre == "1" || genre == "2" || genre == "o") "isekailist" else "genrelist"
            val modifierPart = if (modifier == "total") "" else "_$modifier"
            "$browseBaseUrl/rank/$genrePart/type/${ranking}_${genre}$modifierPart/"
        }
        return "$url?p=$page"
    }

    protected open fun buildSearchUrl(page: Int, query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchPath = when (siteType) {
            SiteType.NCODE, SiteType.NOVEL18 -> "search.php"
            SiteType.NOC, SiteType.MNLT, SiteType.MID -> "search/search/search.php"
        }
        return "$browseBaseUrl/$searchPath?word=$encoded&p=$page"
    }

    // Wordless search listing sorted by order (new/hyoka/...) - used as "latest"/"popular" for
    // NOC/MID/MNLT, which have no chronological concept on their ranking pages.
    private fun buildOrderedListUrl(page: Int, order: String): String {
        val searchPath = when (siteType) {
            SiteType.NCODE, SiteType.NOVEL18 -> "search.php"
            SiteType.NOC, SiteType.MNLT, SiteType.MID -> "search/search/search.php"
        }
        return "$browseBaseUrl/$searchPath?type=&order=$order&p=$page"
    }

    protected open fun buildFilteredSearchUrl(page: Int, filters: FilterList): String {
        var ranking = "daily"
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
            // yomou.syosetu.com's ranking pager has no distinct "next" class (title="次の50作品へ"
            // on a plain .c-pager__item), and its search pager uses .nextlink - neither is
            // "a.c-pager__item--next"/"a[rel=next]" (that pattern belongs to the chapter-list
            // pager on ncode.syosetu.com detail pages, a different page entirely).
            SiteType.NCODE, SiteType.NOVEL18 -> doc.selectFirst("a.nextlink, a.c-pager__item[title*=次]") != null
            SiteType.NOC, SiteType.MNLT, SiteType.MID -> doc.selectFirst("a.next, .pager a[href*='p=']") != null
        }

        return MangasPage(novels, hasNextPage)
    }

    // ---------- Novel details ----------
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        val response = client.get(contentBaseUrl + path, requestHeaders(), ensureSuccess = false)
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
        val response = client.get(contentBaseUrl + mangaPath.resolve(manga.url), requestHeaders())
        val doc = response.asJsoup()
        checkCloudflare(doc)

        val updatedManga = if (fetchDetails) mangaDetailsParse(doc).apply { this.url = manga.url } else manga
        val updatedChapters = if (fetchChapters) fetchChapterListFromDoc(manga.url, doc) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    // ---------- Chapters ----------
    private suspend fun fetchChapterListFromDoc(mangaUrl: String, doc: Document): List<SChapter> {
        // One-shots have no .p-eplist__sublist at all - the whole thing is read directly off
        // the manga details page itself (.p-novel__body, same as fetchPageText's fallback).
        if (doc.select(".p-eplist__sublist").isEmpty()) {
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
        // Chapter lists are paginated 100-per-page; the "last page" link in the pager (absent
        // entirely when everything already fits on page 1) tells us how many pages to walk.
        val pageCount = doc.selectFirst("a.c-pager__item--last")
            ?.attr("href")
            ?.let { Regex("""[?&]p=(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: 1

        val chapters = mutableListOf<SChapter>()
        var chapterNumber = 1f
        for (page in 1..pageCount) {
            val pageDoc = if (page == 1) {
                doc
            } else {
                val response = client.get("$contentBaseUrl$basePath?p=$page", requestHeaders())
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

    private val dateRegex = Regex("""\d{4}/\d{2}/\d{2} \d{2}:\d{2}""")

    // Revision-edited chapters append a "(改)" note with its own timestamp after the original
    // publish date (e.g. "2020/01/01 12:00\n（改）"), which LocalDateTime.parse rejects outright
    // since it requires an exact match - pull just the leading date out first.
    private fun parseDate(dateStr: String): Long = runCatching {
        val match = dateRegex.find(dateStr) ?: return@runCatching 0L
        LocalDateTime.parse(match.value, dateFormat).atZone(ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    // ---------- Chapter content ----------
    override suspend fun fetchPageText(page: Page): String {
        val response = client.get(contentBaseUrl + mangaPath.resolve(page.url), requestHeaders())
        val doc = response.asJsoup()
        checkCloudflare(doc)

        val contentDiv = doc.selectFirst("#novel_honbun, .p-novel__text:not(.p-novel__text--preface):not(.p-novel__text--afterword)")
            ?: doc.selectFirst(".p-novel__body")
        return contentDiv?.html() ?: ""
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    // ---------- Filters ----------
    // These only affect the no-query browse list (buildFilteredSearchUrl). NOVEL18 never
    // reaches them - it's always browsed through the JSON API instead. NOC/MID/MNLT do have
    // real ranking pages, but only per-period (no all-time "累計") and with no genre
    // breakdown (/rank/genrelist|isekailist/... 404s on those hosts) - NCODE is the only site
    // with the full ranking+genre combination.
    override fun getFilterList(data: JsonElement?): FilterList = when (siteType) {
        SiteType.NCODE -> FilterList(
            Filter.Header("Ranking"),
            RankingFilter(includeAllTime = true),
            Filter.Header("Genre"),
            GenreFilter(),
            Filter.Header("Modifier"),
            ModifierFilter(),
        )
        SiteType.NOC, SiteType.MID, SiteType.MNLT -> FilterList(
            Filter.Header("Ranking"),
            RankingFilter(includeAllTime = false),
            Filter.Header("Modifier"),
            ModifierFilter(),
        )
        SiteType.NOVEL18 -> FilterList()
    }

    private class RankingFilter(includeAllTime: Boolean) :
        Filter.Select<String>(
            "Rank by",
            if (includeAllTime) {
                arrayOf("日間", "週間", "月間", "四半期", "年間", "累計")
            } else {
                arrayOf("日間", "週間", "月間", "四半期", "年間")
            },
        ) {
        fun toUriPart(): String = when (state) {
            0 -> "daily"
            1 -> "weekly"
            2 -> "monthly"
            3 -> "quarter"
            4 -> "yearly"
            5 -> "total"
            else -> "daily"
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

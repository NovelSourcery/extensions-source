package eu.kanade.tachiyomi.novelextension.en.wuxiadreams

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.setAltTitles
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale

class WuxiaDreams :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "WuxiaDreams"
    override val baseUrl = "https://wuxiadreams.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.client

    private val preferences by getPreferencesLazy()

    private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

    private val pageRegex = Regex("""Page\s+(\d+)\s+of\s+(\d+)""")

    private fun listRequest(page: Int, sort: String, query: String): Request {
        val url = "$baseUrl/novels".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
        if (query.isNotBlank()) {
            url.addQueryParameter("q", query)
        } else {
            url.addQueryParameter("sort", sort)
        }
        return GET(url.build(), headers)
    }

    override fun popularMangaRequest(page: Int): Request = listRequest(page, "score", "")
    override fun latestUpdatesRequest(page: Int): Request = listRequest(page, "update", "")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.toUriPart() ?: "score"
        return listRequest(page, sort, query)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = doc.select("a[href^=/novel/]:has(h3)").mapNotNull { el ->
            val href = el.attr("href")
            if (href.contains("/chapter")) return@mapNotNull null
            val img = el.selectFirst("img")
            SManga.create().apply {
                title = el.selectFirst("h3")?.text()
                    ?: img?.attr("alt").orEmpty()
                url = href
                thumbnail_url = img?.attr("abs:src")
                status = parseStatus(el.selectFirst("span")?.text())
            }
        }
        return MangasPage(mangas, hasNextPage(doc))
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    private fun hasNextPage(doc: Document): Boolean {
        val match = pageRegex.find(doc.text()) ?: return false
        return match.groupValues[1].toInt() < match.groupValues[2].toInt()
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        return SManga.create().apply {
            title = doc.selectFirst("h1")?.text().orEmpty()
            thumbnail_url = doc.selectFirst("img[src*=/covers/]")?.attr("abs:src")
            author = doc.selectFirst("a[href^=/author/]")?.text()
            genre = doc.select("a[href^=/genre/], a[href^=/tag/]")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
                .joinToString()
            status = parseStatus(statValue(doc, "Status"))

            val altTitle = doc.selectFirst("h1")?.nextElementSibling()
                ?.takeIf { it.tagName() == "h2" }?.text()?.trim()
            if (!altTitle.isNullOrEmpty() && altTitle != title) setAltTitles(listOf(altTitle))

            description = buildString {
                statValue(doc, "Views")?.let { append("Views: $it\n") }
                statValue(doc, "Score")?.let { append("Score: $it\n") }
                val synopsis = doc.selectFirst("h3:contains(Synopsis) ~ div.prose")?.text()
                    ?: doc.selectFirst("div.prose")?.text()
                if (!synopsis.isNullOrBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(synopsis)
                }
            }.trim()
        }
    }

    private fun statValue(doc: Document, label: String): String? = doc
        .select("span:matchesOwn(^$label$)").firstOrNull()
        ?.nextElementSibling()?.text()?.trim()?.takeIf { it.isNotEmpty() }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val doc = client.newCall(GET("$baseUrl${manga.url}?page=1&sort=desc", headers)).execute().asJsoup()

        if (preferences.getBoolean(PREF_FAST_CHAPTERS, true)) {
            val total = statValue(doc, "Chapters")?.replace(",", "")?.toIntOrNull()
            if (total != null && total > 0) {
                return (total downTo 1).map { n ->
                    SChapter.create().apply {
                        name = "Chapter $n"
                        url = "${manga.url}/chapter-$n"
                        chapter_number = n.toFloat()
                    }
                }
            }
        }

        val chapters = mutableListOf<SChapter>()
        chapters += parseChapters(doc)
        val totalPages = pageRegex.find(doc.text())?.groupValues?.get(2)?.toIntOrNull() ?: 1
        for (page in 2..totalPages) {
            val pageDoc = client.newCall(
                GET("$baseUrl${manga.url}?page=$page&sort=desc", headers),
            ).execute().asJsoup()
            chapters += parseChapters(pageDoc)
        }
        return chapters
    }

    private fun parseChapters(doc: Document): List<SChapter> = doc.select("section.scroll-mt-24 a[href*=/chapter-]").map { el ->
        SChapter.create().apply {
            name = el.selectFirst("span.line-clamp-1")?.text()?.trim()
                ?: el.text().trim()
            url = el.attr("href")
            date_upload = el.select("span").firstOrNull { it.text().contains(",") }
                ?.let { runCatching { dateFormat.parse(it.text())?.time }.getOrNull() } ?: 0L
            chapter_number = Regex("""chapter-(\d+)""").find(url)
                ?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) page.url else baseUrl + page.url
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
        val article = doc.selectFirst("article.chapter-content-container") ?: return ""
        article.select("script, style, ins, [data-ad-slot-728x90], .adsbygoogle").remove()
        return article.html()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun parseStatus(text: String?) = when (text?.lowercase()?.trim()) {
        "completed" -> SManga.COMPLETED
        "ongoing" -> SManga.ONGOING
        "hiatus" -> SManga.ON_HIATUS
        "dropped" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    override fun getFilterList() = FilterList(SortFilter())

    private class SortFilter :
        Filter.Select<String>(
            "Sort by",
            arrayOf("Score", "Latest Update"),
        ) {
        fun toUriPart() = arrayOf("score", "update")[state]
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_FAST_CHAPTERS
            title = "Fast chapter list"
            summary = "Build the chapter list from the total count instead of paging every list page. " +
                "Faster, but chapters are named \"Chapter N\" with no upload dates."
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_FAST_CHAPTERS = "pref_fast_chapters"
    }
}

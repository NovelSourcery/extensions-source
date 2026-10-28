package eu.kanade.tachiyomi.novelextension.en.bytetranslations

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
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.formattedText
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.stripChapterNumberPrefix
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@Source
abstract class ByteTranslations :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList())

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(OrderFilter().apply { state = 4 }))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val listUrl = "$baseUrl/all-novels/".toHttpUrl().newBuilder().apply {
            if (page > 1) addPathSegments("page/$page/")
            addQueryParameter("novel_search", query.trim())
            addQueryParameter("novel_genre", filters.firstInstanceOrNull<GenreFilter>()?.toUriPart().orEmpty())
            addQueryParameter("novel_status", filters.firstInstanceOrNull<StatusFilter>()?.toUriPart().orEmpty())
            addQueryParameter("novel_orderby", (filters.firstInstanceOrNull<OrderFilter>() ?: OrderFilter()).toUriPart())
        }.build()
        val document = client.get(listUrl).asJsoup()
        val mangas = document.select("article.novel-list-card").map { card ->
            SManga.create().apply {
                url = card.selectFirst("a.novel-list-card-link")!!.absUrl("href").toHttpUrl().novelSlug()!!
                title = card.selectFirst(".novel-list-card-title")!!.text()
                thumbnail_url = card.selectFirst("img")?.imageUrl()
            }
        }
        return MangasPage(mangas, document.selectFirst(".all-novels-pagination a.next") != null)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = url.novelSlug() ?: return null
        return parseDetails(client.get(novelUrl(slug)).asJsoup(), slug)
    }

    // Novel pages live under /novel/{slug}/, chapters under /{slug}/chapter-N/
    private fun HttpUrl.novelSlug(): String? {
        val segments = pathSegments.filter { it.isNotEmpty() }
        return when {
            segments.firstOrNull() == "novel" -> segments.getOrNull(1)
            segments.size == 2 && segments[1].startsWith("chapter-") -> segments[0]
            else -> null
        }
    }

    private fun novelUrl(slug: String) = "$baseUrl/novel/$slug/"

    override fun getMangaUrl(manga: SManga): String = novelUrl(manga.url)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(novelUrl(manga.url)).asJsoup()
        return SMangaUpdate(parseDetails(document, manga.url), parseChapters(document))
    }

    private fun parseDetails(document: Document, slug: String) = SManga.create().apply {
        url = slug
        title = document.selectFirst("h1.novel-title")!!.text()
        thumbnail_url = document.selectFirst(".novel-cover-container img")?.imageUrl()
        author = document.selectFirst(".novel-author")?.text()?.removePrefix("Author:")?.trim()?.ifEmpty { null }
        genre = document.select(".novel-tag").joinToString { it.text() }
        status = when (document.selectFirst(".cover-status")?.text()?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus", "on hold" -> SManga.ON_HIATUS
            "dropped", "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        description = buildString {
            document.selectFirst(".novel-description")?.formattedText()?.takeIf { it.isNotEmpty() }?.let { append(it).append("\n\n") }
            document.select(".novel-stats .stat-item").forEach { stat ->
                val label = stat.selectFirst(".stat-label")?.text() ?: return@forEach
                val value = stat.selectFirst(".stat-value")?.text() ?: return@forEach
                append("$label: $value\n")
            }
            document.select(".schedule-days .day.active").map { it.attr("title").substringBefore(" (") }
                .takeIf { it.isNotEmpty() }
                ?.let { append("Release schedule: ${it.joinToString()}\n") }
        }.trim()
    }

    private fun parseChapters(document: Document): List<SChapter> {
        val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)
        return document.select("a.bt-ch.chapter-row")
            .mapNotNull { row ->
                val locked = !row.hasClass("bt-ch--free")
                if (locked && !showLocked) return@mapNotNull null
                val number = row.selectFirst(".bt-ch-num")!!.text()
                val title = row.selectFirst(".bt-ch-title")?.text().orEmpty()
                SChapter.create().apply {
                    setUrlWithoutDomain(row.absUrl("href"))
                    val chapterName = chapterName(number, title)
                    name = if (locked) "🔒 $chapterName" else chapterName
                    chapter_number = row.attr("data-chapter-number").toFloatOrNull() ?: -1f
                    date_upload = parseRelativeDate(row.selectFirst(".bt-ch-date")?.text())
                }
            }
            .distinctBy { it.url }
            .sortedByDescending { it.chapter_number }
    }

    private fun chapterName(number: String, title: String): String {
        if (title.isEmpty() || CHAPTER_ONLY_REGEX.matches(title)) return number
        return title.stripChapterNumberPrefix()
    }

    private fun parseRelativeDate(text: String?): Long {
        val match = RELATIVE_DATE_REGEX.find(text?.lowercase() ?: return 0L) ?: return 0L
        val amount = match.groupValues[1].toLongOrNull() ?: 1L
        val unit = when (match.groupValues[2]) {
            "second", "sec" -> ChronoUnit.SECONDS
            "minute", "min" -> ChronoUnit.MINUTES
            "hour" -> ChronoUnit.HOURS
            "day" -> ChronoUnit.DAYS
            "week" -> ChronoUnit.WEEKS
            "month" -> ChronoUnit.MONTHS
            else -> ChronoUnit.YEARS
        }
        return ZonedDateTime.now().minus(amount, unit).toInstant().toEpochMilli()
    }

    private fun Element.imageUrl(): String? = absUrl("data-src").ifEmpty { absUrl("src") }.takeIf { it.startsWith("http") }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val document = client.get(baseUrl + page.url).asJsoup()
        val restriction = document.selectFirst(".access-restriction")
        if (restriction != null) {
            val reason = restriction.selectFirst("p")?.text()?.ifEmpty { null } ?: "This chapter is locked."
            throw Exception("$reason Log in via WebView with an account that has access.")
        }
        val content = document.selectFirst(".actual-chapter-content") ?: throw Exception("Chapter content not found")
        content.select("script, style, ins, .read-ahead-card").remove()
        content.select("p").filter { !it.hasText() && it.select("img").isEmpty() }.forEach { it.remove() }
        return content.html()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        GenreFilter(),
        StatusFilter(),
        OrderFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Include advanced (Patreon) chapters in the chapter list (marked with 🔒)."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_SHOW_LOCKED = "show_locked_chapters"
        private val CHAPTER_ONLY_REGEX = Regex("""(?i)chapter\s*[\d.]+""")
        private val RELATIVE_DATE_REGEX = Regex("""(\d+|an?)\s+(second|sec|minute|min|hour|day|week|month|year)s?\s+ago""")
    }
}

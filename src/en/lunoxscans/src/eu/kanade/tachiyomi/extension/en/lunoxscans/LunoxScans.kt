package eu.kanade.tachiyomi.novelextension.en.lunoxscans

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.SlugPath
import keiyoushi.utils.formattedText
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.setAltTitles
import keiyoushi.utils.tryParseDate
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class LunoxScans : MadaraNovel() {

    // Required by the theme's tracking helpers, which resolve SManga.url (the bare slug) through it.
    override val mangaPathTemplate = SlugPath("/series/", "/")

    private val preferences by getPreferencesLazy()

    override fun buildPopularMangaRequest(page: Int): Request = GET("$baseUrl/page/$page/?s=&post_type=wp-manga&m_orderby=views", headers)

    override suspend fun getPopularManga(page: Int): MangasPage = parseListing(buildPopularMangaRequest(page))

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseListing(buildLatestUpdatesRequest(page))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = parseListing(buildSearchMangaRequest(page, query, filters))

    private suspend fun parseListing(request: Request): MangasPage {
        val document = client.get(request.url, headers).asJsoup()
        val mangas = document.select(".page-item-detail").map { card ->
            val link = card.selectFirst(".post-title a")!!
            SManga.create().apply {
                url = link.slug()
                title = link.text()
                thumbnail_url = card.selectFirst("img")?.imgUrl()
            }
        }
        return MangasPage(mangas, document.selectFirst("a.nextpostslink") != null)
    }

    // The theme narrows the return type to non-null, so unrecognized URLs throw instead.
    override suspend fun getMangaByUrl(url: HttpUrl): SManga {
        val slug = url.pathSegments.takeIf { it.firstOrNull() == "series" }?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() } ?: throw Exception("Unsupported URL")
        return parseDetails(client.get("$baseUrl/series/$slug/", headers).asJsoup()).apply { this.url = slug }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaUrl = getMangaUrl(manga)
        val document = client.get(mangaUrl, headers).asJsoup()
        val updatedManga = if (fetchDetails) parseDetails(document) else manga
        extractPostId(document)?.let { cachePostId(mangaPathTemplate.resolve(manga.url), it) }

        val updatedChapters = if (fetchChapters) {
            val chapterHeaders = headersBuilder().set("Referer", mangaUrl).build()
            val chapterDocument = client.post("${mangaUrl}ajax/chapters/", chapterHeaders, FormBody.Builder().build()).asJsoup()
            parseChapters(chapterDocument, manga.url)
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst(".post-title h1")!!.text()
        thumbnail_url = document.selectFirst(".summary_image img")?.imgUrl()

        val items = document.select(".post-content_item").mapNotNull { item ->
            val label = item.selectFirst("h5")?.text()?.lowercase() ?: return@mapNotNull null
            val value = item.selectFirst(".summary-content") ?: return@mapNotNull null
            label to value
        }.toMap()

        author = document.select(".author-content a").eachText().joinToString().ifEmpty { null }
        artist = document.select(".artist-content a").eachText().joinToString().ifEmpty { null }
        genre = (document.select(".genres-content a").eachText() + document.select(".tags-content a").eachText())
            .distinct().joinToString().ifEmpty { null }

        status = when (items["status"]?.text()?.lowercase()) {
            null -> SManga.UNKNOWN
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "on hold", "hiatus" -> SManga.ON_HIATUS
            "canceled", "cancelled", "dropped" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        val altTitles = items["alternative"]?.text()
            ?.split("|", ";")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.equals(title, ignoreCase = true) }
            .orEmpty()
        if (altTitles.isNotEmpty()) setAltTitles(altTitles)

        val synopsis = document.selectFirst("div.summary__content")?.let { summary ->
            summary.clone().apply {
                // SEO boilerplate repeating the title and alt names
                children().firstOrNull()?.takeIf { it.text().startsWith("Read novel") }?.remove()
            }.formattedText()
        }

        description = buildString {
            synopsis?.takeIf { it.isNotEmpty() }?.let { append(it).append("\n\n") }

            val rating = document.selectFirst("#averagerate")?.text()
            val votes = document.selectFirst("#countrate")?.text()
            if (!rating.isNullOrEmpty()) {
                append("Rating: ").append(rating).append("/5")
                if (!votes.isNullOrEmpty()) append(" (").append(votes).append(" votes)")
                append("\n")
            }
            items["rank"]?.text()?.let { rank ->
                VIEWS_REGEX.find(rank)?.let { append("Views: ").append(it.groupValues[1]).append("\n") }
                rank.substringBefore(",").takeIf { !it.equals("N/A", ignoreCase = true) && it.isNotBlank() }
                    ?.let { append("Rank: ").append(it).append("\n") }
            }
            items["type"]?.text()?.takeIf { it.isNotEmpty() }?.let { append("Type: ").append(it).append("\n") }
            items["release"]?.text()?.takeIf { it.isNotEmpty() }?.let { append("Release: ").append(it).append("\n") }

            if (altTitles.isNotEmpty()) {
                append("\nAlternative Titles:\n")
                append(altTitles.joinToString("\n") { "• $it" })
            }
        }.trim()
    }

    private fun parseChapters(document: Document, mangaSlug: String): List<SChapter> {
        val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)
        val chapters = document.select("a.lunox-chapter-item").mapNotNull { item ->
            val locked = item.hasClass("premium")
            if (locked && !showLocked) return@mapNotNull null

            val rawName = item.attr("data-name").trim().ifEmpty { item.selectFirst(".lunox-chapter-name")!!.text() }
            // Locked chapters link to "#"; their slug follows the same pattern as the free ones.
            val chapterSlug = if (locked) {
                rawName.lowercase().replace(NON_SLUG_REGEX, "-").trim('-')
            } else {
                item.absUrl("href").trimEnd('/').substringAfterLast('/')
            }

            SChapter.create().apply {
                url = "/series/$mangaSlug/$chapterSlug/"
                name = if (locked) "🔒 $rawName" else rawName
                chapter_number = CHAPTER_NUMBER_REGEX.find(rawName)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
                date_upload = item.selectFirst(".lunox-chapter-date")?.text().orEmpty().let { date ->
                    if (date.contains("ago")) parseDate(date) else DATE_FORMAT.tryParseDate(date, ZoneOffset.UTC)
                }
            }
        }
        return if (reverseChapterList) chapters.reversed() else chapters
    }

    private fun Element.slug(): String = absUrl("href").trimEnd('/').substringAfterLast('/')

    private fun Element.imgUrl(): String? = attr("abs:data-src").ifEmpty { null } ?: attr("abs:src").ifEmpty { null }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Include coin-locked chapters in the chapter list (their content can't be read without unlocking them on the site)."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_SHOW_LOCKED = "show_locked_chapters"
        private val VIEWS_REGEX = Regex("""([\d.,]+\s*[KMB]?)\s+views""", RegexOption.IGNORE_CASE)
        private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")
        private val NON_SLUG_REGEX = Regex("[^a-z0-9]+")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.ENGLISH)
    }
}

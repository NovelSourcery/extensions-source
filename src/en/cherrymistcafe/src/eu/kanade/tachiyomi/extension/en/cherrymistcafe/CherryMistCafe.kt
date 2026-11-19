package eu.kanade.tachiyomi.novelextension.en.cherrymistcafe

import android.util.Base64
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Instant

@Source
abstract class CherryMistCafe :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    private val apiHeaders: Headers by lazy { headers.newBuilder().set("X-CM-Reader", "1").build() }

    override suspend fun getPopularManga(page: Int): MangasPage = seriesPage(page) { addQueryParameter("sort", "popular") }

    override suspend fun getLatestUpdates(page: Int): MangasPage = seriesPage(page) {}

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) return seriesPage(page) { addQueryParameter("q", query.trim()) }
        val sort = filters.firstInstanceOrNull<SortFilter>()?.state ?: 0
        val status = filters.firstInstanceOrNull<StatusFilter>()?.value
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.value
        if (status == null && genre == null) return if (sort == 0) getPopularManga(page) else getLatestUpdates(page)

        // The API cannot filter, so filter the whole catalogue like the site does.
        val matches = api("series").parseAs<List<SeriesDto>>()
            .filter { status == null || it.storyStatus?.lowercase() == status }
            .filter { series -> genre == null || (series.genres.orEmpty() + series.tags.orEmpty()).any { it.equals(genre, ignoreCase = true) } }
            .let { rows ->
                if (sort == 0) {
                    rows.sortedByDescending { it.libraryCount + it.trackingCount }
                } else {
                    rows.sortedByDescending { Instant.tryParse(it.lastReleaseAt ?: it.updatedAt) }
                }
            }
        val from = (page - 1) * PAGE_SIZE
        return MangasPage(matches.drop(from).take(PAGE_SIZE).map { it.toSManga() }, matches.size > from + PAGE_SIZE)
    }

    private suspend fun seriesPage(page: Int, query: HttpUrl.Builder.() -> Unit): MangasPage {
        val url = "$baseUrl/api/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .apply(query)
            .build()
        val result = client.get(url, apiHeaders, Fetch.XHR).parseAs<SeriesPageDto>()
        return MangasPage(result.rows.map { it.toSManga() }, page * PAGE_SIZE < result.total)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/story/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/chapter/${chapter.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments.filter(String::isNotEmpty)
        val slug = segments.getOrNull(1) ?: return null
        return when (segments.first()) {
            "story" -> fetchSeries(slug).toDetails()
            "chapter" -> {
                val seriesSlug = api("chapters/by-slug/$slug").parseAs<ChapterSlugDto>().seriesSlug
                fetchSeries(seriesSlug).toDetails()
            }
            else -> null
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val series = fetchSeries(manga.url)
        val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)
        val chapterList = if (fetchChapters) {
            api("chapters?series_id=${series.id}").parseAs<List<ChapterDto>>()
                .filter { it.status == "published" && it.slug != null }
                .mapNotNull { chapter ->
                    // Paid, password and early-access chapters cannot be read without an account.
                    val locked = (chapter.priceType != null && chapter.priceType != "free") ||
                        chapter.hasAccessPassword != 0 ||
                        (chapter.isLocked != 0 && series.chaptersLocked != 0)
                    if (locked && !showLocked) return@mapNotNull null
                    SChapter.create().apply {
                        url = chapter.slug!!
                        val title = chapter.title?.trim()?.ifEmpty { null } ?: "Chapter ${chapter.chapterNumber}"
                        name = if (locked) "🔒 $title" else title
                        chapter.chapterNumber?.let { chapter_number = it }
                        date_upload = Instant.tryParse(chapter.publishedAt ?: chapter.createdAt)
                    }
                }
                .asReversed()
        } else {
            chapters
        }
        return SMangaUpdate(if (fetchDetails) series.toDetails() else manga, chapterList)
    }

    private suspend fun fetchSeries(slug: String): SeriesDto = api("series/$slug").parseAs()

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val id = api("chapters/by-slug/${page.url}").parseAs<ChapterIdDto>().id
        var chapter = api("chapters/$id").parseAs<ChapterContentDto>()
        // The body is withheld until a reveal ticket, signed with the page's key, is sent back.
        if ((chapter.bodyWithheld || chapter.content.isNullOrBlank()) && chapter.revealTicket != null && chapter.revealKey != null) {
            val jti = chapter.revealTicket!!.split('.').getOrNull(1)
                ?.let { String(Base64.decode(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)) }
                ?.parseAs<TicketPayloadDto>()?.jti
                ?: throw Exception("Chapter reveal ticket not understood")
            val request = RevealRequestDto(
                ticket = chapter.revealTicket!!,
                proof = hmacSha256Hex(chapter.revealKey!!, "chapter-reveal.v2.$id.$jti"),
                proofScheme = "v2",
                frames = 25,
                elapsedMs = 650,
                visible = true,
                focused = true,
            )
            chapter = client.post("$baseUrl/api/chapters/$id/reveal", apiHeaders, Fetch.XHR, request.toJsonRequestBody()).parseAs()
        }
        val parts = listOfNotNull(chapter.foreword, chapter.content, chapter.afterword)
            .map(::cleanChapterHtml)
            .filter(String::isNotEmpty)
        if (parts.isEmpty()) throw Exception("Chapter content not found")
        return parts.joinToString("<hr>")
    }

    private fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.ISO_8859_1), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.ISO_8859_1)).joinToString("") { "%02x".format(it) }
    }

    // Text is watermarked with invisible characters, scrambled class names and reversed spans.
    private fun cleanChapterHtml(raw: String): String {
        val text = raw.replace(WATERMARK, "").replace(COMMENTS, "").replace(' ', ' ')
        val body = Jsoup.parseBodyFragment(wrapPlainParagraphs(text)).body()
        body.select("script, style, iframe, ruby").remove()
        body.select("[data-fcnc-rev=1]").forEach { it.replaceWith(TextNode(it.text().reversed())) }
        body.select("span").filter { !it.hasText() && it.select("img").isEmpty() }.forEach(Element::remove)
        body.select("[class]").forEach { element ->
            val classes = element.classNames().filterNot { TX_CLASS.matches(it) }
            if (classes.isEmpty()) element.removeAttr("class") else element.classNames(classes.toSet())
        }
        body.select("p").filter { !it.hasText() && it.select("img, hr").isEmpty() }.forEach(Element::remove)
        return body.html().trim()
    }

    private fun wrapPlainParagraphs(raw: String): String {
        val blocks = raw.split(PARAGRAPH_BREAK).map(String::trim).filter(String::isNotEmpty)
        fun wrap(block: String) = if (BLOCK_TAG.containsMatchIn(block)) block else "<p>${block.replace(NEWLINE, "<br>")}</p>"
        return if (blocks.size < 2) wrap(raw) else blocks.joinToString("\n", transform = ::wrap)
    }

    private suspend fun api(path: String) = client.get("$baseUrl/api/$path", apiHeaders, Fetch.XHR)

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Filters are ignored when searching by text"),
        SortFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    private class SortFilter : Filter.Select<String>("Sort by", arrayOf("Popularity", "Latest update"))

    private open class OptionalSelect(name: String, private val options: Array<String>) : Filter.Select<String>(name, arrayOf("Any") + options) {
        val value: String?
            get() = if (state == 0) null else options[state - 1]
    }

    private class StatusFilter : OptionalSelect("Status", arrayOf("ongoing", "completed", "hiatus", "dropped"))

    private class GenreFilter :
        OptionalSelect(
            "Genre",
            arrayOf("Action", "BG", "BL", "Comedy", "Drama", "Fantasy", "Historical", "Modern", "Mystery", "Rofan", "Romance", "Slice of Life"),
        )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Include paid, password protected and early-access chapters (marked with 🔒)."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PAGE_SIZE = 24
        private const val PREF_SHOW_LOCKED = "show_locked_chapters"
        private val WATERMARK = Regex("[\uFE00-\uFE0F\u200B-\u200F\u202A-\u202E\u2060\uFEFF\u00AD]")
        private val COMMENTS = Regex("""<!--[\s\S]*?-->""")
        private val TX_CLASS = Regex("""^tx-[a-z2-7]+$""", RegexOption.IGNORE_CASE)
        private val PARAGRAPH_BREAK = Regex("""\r?\n\s*\r?\n""")
        private val NEWLINE = Regex("""\r?\n""")
        private val BLOCK_TAG = Regex("""<(p|div|ul|ol|li|h[1-6]|blockquote|table|pre|figure|section|article|hr)[\s>/]""", RegexOption.IGNORE_CASE)
    }
}

package eu.kanade.tachiyomi.novelextension.en.kdtnovels

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
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class KdtNovels :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val supportsLatest = false

    override val supportsFilterFetching = true

    private val preferences by getPreferencesLazy()

    private val mangaPath = SlugPath("/series/", "/")
    private val chapterPath = SlugPath("/", "/")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/?page=$page", headers).asJsoup()
        return document.parseSeriesPage(page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .apply {
                if (query.isNotBlank()) addQueryParameter("q", query)
                filters.firstInstanceOrNull<GenreFilter>()?.toValue()?.let { addQueryParameter("genre", it) }
                filters.firstInstanceOrNull<StatusFilter>()?.toValue()?.let { addQueryParameter("status", it) }
                filters.firstInstanceOrNull<TypeFilter>()?.toValue()?.let { addQueryParameter("novel_type", it) }
            }.build()

        val document = client.get(url, headers).asJsoup()
        return document.parseSeriesPage(page)
    }

    private fun Document.parseSeriesPage(page: Int): MangasPage {
        val mangas = select("a[href^=\"/series/\"]:has(h3)").map { card ->
            SManga.create().apply {
                title = card.selectFirst("h3")!!.text()
                url = mangaPath.slug(card.attr("href"))
                thumbnail_url = decodeImgSrc(card.selectFirst("img")?.attr("src"))
            }
        }
        val hasNextPage = selectFirst("a[href*=\"page=${page + 1}\"]") != null
        return MangasPage(mangas, hasNextPage)
    }

    override fun getMangaUrl(manga: SManga): String = mangaPath.absolute(baseUrl, manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!url.encodedPath.startsWith("/series/")) return null
        val slug = mangaPath.slug(url.encodedPath)
        val response = client.get(mangaPath.absolute(baseUrl, slug), headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        return parseMangaDetails(response.asJsoup()).apply { this.url = slug }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(mangaPath.absolute(baseUrl, manga.url), headers).asJsoup()
        val updatedManga = parseMangaDetails(document)
        val updatedChapters = document.extractNextJs<ChapterVolumesDto>()?.let { parseChapterList(it) }.orEmpty()
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1")!!.text()
        thumbnail_url = decodeImgSrc(document.selectFirst(COVER_SELECTOR)?.attr("src"))

        val infoParagraph = document.select("p")
            .firstOrNull { it.selectFirst("svg") == null && it.selectFirst("span") != null }
        val infoSpans = infoParagraph?.select("span").orEmpty()
        author = infoSpans.getOrNull(0)?.text()?.takeIf { it.isNotEmpty() }
        artist = infoSpans.getOrNull(1)?.text()?.takeIf { it.isNotEmpty() }

        genre = document.select("a[href*=\"/search/?genre=\"]").eachText().joinToString().takeIf { it.isNotEmpty() }

        val badge = document.selectFirst("h1")?.parent()?.selectFirst("span")?.ownText()
        status = when (badge?.lowercase()) {
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "dropped" -> SManga.CANCELLED
            else -> SManga.ONGOING
        }

        // The RSC synopsis is sometimes an outlined text chunk split across scripts, so read the rendered DOM.
        description = document.selectFirst("div.reader-content")?.formattedText()
    }

    private fun parseChapterList(data: ChapterVolumesDto): List<SChapter> {
        val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)
        // Chapter numbers restart every volume, so number by overall position to keep volumes ordered.
        return data.groups
            .flatMap { group -> group.chapters.map { group.label to it } }
            .mapIndexedNotNull { index, (volume, chapter) -> chapter.toSChapter(volume, index + 1f, showLocked) }
            .reversed()
    }

    override fun getChapterUrl(chapter: SChapter): String = chapterPath.absolute(baseUrl, chapter.url)

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapterPath.absolute(baseUrl, chapter.url)))

    override suspend fun fetchPageText(page: Page): String {
        val document = client.get(page.url, headers).asJsoup()
        // Illustration-only chapters have no div.text-content, just image placeholders in the article.
        val content = document.selectFirst("article.reader-content div.text-content")
            ?: document.selectFirst("article.reader-content")
            ?: throw Exception("Chapter content is locked or unavailable on KDT Novels")
        // Illustrations are JS-rendered placeholders; the CDN rejects requests without a Referer, the site's /img/ proxy doesn't.
        content.select("span.kdt-ilus[data-ilus]").forEach { span ->
            span.replaceWith(Element("img").attr("src", baseUrl + span.attr("data-ilus")))
        }
        return content.html()
    }

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/search/", headers).asJsoup()
        return FilterDataDto(
            genres = document.select("select[aria-label*=genre] option[value]:not([value=\"\"])").map {
                FilterOptionDto(it.attr("value"), it.text().replace(COUNT_SUFFIX_REGEX, ""))
            },
            statuses = document.linkOptions("status"),
            types = document.linkOptions("novel_type"),
        ).toJsonElement()
    }

    private fun Document.linkOptions(param: String): List<FilterOptionDto> = select("a[href^=\"/search/?$param=\"]")
        .map { FilterOptionDto(it.attr("href").substringAfter("$param="), it.text()) }
        .distinctBy { it.value }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterDataDto>() ?: return FilterList()
        return FilterList(
            GenreFilter(filterData.genres),
            StatusFilter(filterData.statuses),
            TypeFilter(filterData.types),
        )
    }

    private open class SelectFilter(name: String, private val options: List<FilterOptionDto>) : Filter.Select<String>(name, arrayOf("All") + options.map { it.name }) {
        fun toValue(): String? = options.getOrNull(state - 1)?.value
    }

    private class GenreFilter(options: List<FilterOptionDto>) : SelectFilter("Genre", options)
    private class StatusFilter(options: List<FilterOptionDto>) : SelectFilter("Status", options)
    private class TypeFilter(options: List<FilterOptionDto>) : SelectFilter("Type", options)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Include paywalled chapters in the chapter list (their content can't be read without unlocking them on the site)."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_SHOW_LOCKED = "show_locked_chapters"
        private const val COVER_SELECTOR = "button[aria-label*=\"view full size\"] img[src]"
        private val COUNT_SUFFIX_REGEX = Regex("""\s*\(\d+\)$""")
    }
}

private fun Element.formattedText(): String {
    val node = clone()
    node.select("br").forEach { it.after(BREAK_TOKEN) }
    node.select("p, div, li").filter { it !== node }.forEach { it.after(PARAGRAPH_TOKEN) }
    return node.text()
        .replace("​", "")
        .replace(Regex("""\s*$PARAGRAPH_TOKEN\s*"""), "\n\n")
        .replace(Regex("""\s*$BREAK_TOKEN\s*"""), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

private const val BREAK_TOKEN = "__KDT_BR__"
private const val PARAGRAPH_TOKEN = "__KDT_P__"

private fun decodeImgSrc(src: String?): String? {
    val encoded = src?.substringAfter("/img/", "")?.takeIf { it.isNotEmpty() } ?: return null
    val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
    return try {
        String(Base64.decode(padded, Base64.DEFAULT))
    } catch (_: IllegalArgumentException) {
        null
    }
}

@Serializable
private class FilterDataDto(
    val genres: List<FilterOptionDto>,
    val statuses: List<FilterOptionDto>,
    val types: List<FilterOptionDto>,
)

@Serializable
private class FilterOptionDto(val value: String, val name: String)

@Serializable
private class ChapterVolumesDto(val groups: List<VolumeGroupDto>)

@Serializable
private class VolumeGroupDto(val label: String = "", val chapters: List<ChapterDto>)

@Serializable
private class ChapterDto(
    private val slug: String,
    @SerialName("chapter_no") private val chapterNo: String,
    private val subtitle: String = "",
    private val locked: Boolean = false,
) {
    fun toSChapter(volume: String, number: Float, showLocked: Boolean): SChapter? {
        if (locked && !showLocked) return null
        return SChapter.create().apply {
            url = slug
            chapter_number = number
            name = buildString {
                if (locked) append("🔒 ")
                if (volume.isNotBlank()) append(volume).append(" ")
                append("Ch. ")
                append(chapterNo)
                if (subtitle.isNotBlank()) {
                    append(": ")
                    append(subtitle)
                }
            }
        }
    }
}

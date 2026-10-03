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
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

@Source
abstract class KdtNovels :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val supportsLatest = false

    private val preferences by getPreferencesLazy()

    private val mangaPath = SlugPath("/series/", "/")
    private val chapterPath = SlugPath("/", "/")

    override suspend fun getPopularManga(page: Int): MangasPage = fetchListingPage(page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchListingPage(page)

    private suspend fun fetchListingPage(page: Int): MangasPage {
        val document = client.get("$baseUrl/?page=$page", headers).asJsoup()
        val mangas = document.extractSeriesGrid()
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .apply {
                if (query.isNotBlank()) addQueryParameter("q", query)
                filters.forEach { filter ->
                    when (filter) {
                        is GenreFilter -> filter.toValue()?.let { addQueryParameter("genre", it) }
                        is StatusFilter -> filter.toValue()?.let { addQueryParameter("status", it) }
                        is TypeFilter -> filter.toValue()?.let { addQueryParameter("novel_type", it) }
                        else -> {}
                    }
                }
            }.build()

        val document = client.get(url, headers).asJsoup()
        val mangas = document.extractSeriesGrid()
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    private fun Document.extractSeriesGrid(): List<SManga> {
        val grid = extractNextJs<JsonElement>(SERIES_GRID_PREDICATE) ?: return emptyList()
        val cards = (grid as? JsonObject)?.get("children")?.jsonArray ?: return emptyList()
        return cards.mapNotNull(::parseSeriesCard)
    }

    private fun parseSeriesCard(card: JsonElement): SManga? {
        val props = card.reactProps() ?: return null
        val href = props["href"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = card.findReactElement("h3")?.reactProps()?.get("children")?.jsonPrimitive?.contentOrNull
            ?: return null
        val cover = card.findReactElement("img")?.reactProps()?.get("src")?.jsonPrimitive?.contentOrNull

        return SManga.create().apply {
            this.title = title
            url = mangaPath.slug(href)
            thumbnail_url = decodeImgSrc(cover)
        }
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
        author = infoSpans.getOrNull(0)?.text()?.takeIf { it.isNotBlank() }
        artist = infoSpans.getOrNull(1)?.text()?.takeIf { it.isNotBlank() }

        genre = document.select("a[href*=\"/search/?genre=\"]").eachText().joinToString().takeIf { it.isNotBlank() }

        val badge = document.selectFirst("h1")?.parent()?.selectFirst("span")?.ownText()
        status = when (badge?.lowercase()) {
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "dropped" -> SManga.CANCELLED
            else -> SManga.ONGOING
        }

        description = document.extractNextJs<SynopsisDto>()?.html?.replace("\r\n", "\n")?.trim()
    }

    private fun parseChapterList(data: ChapterVolumesDto): List<SChapter> {
        val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)
        return data.groups
            .flatMap { it.chapters }
            .mapNotNull { it.toSChapter(showLocked) }
            .reversed()
    }

    override fun getChapterUrl(chapter: SChapter): String = chapterPath.absolute(baseUrl, chapter.url)

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapterPath.absolute(baseUrl, chapter.url)))

    override suspend fun fetchPageText(page: Page): String {
        val document = client.get(page.url, headers).asJsoup()
        val content = document.selectFirst("div.text-content")
            ?: throw Exception("Chapter content is locked or unavailable on KDT Novels")
        return content.html()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        GenreFilter(),
        StatusFilter(),
        TypeFilter(),
    )

    private open class SelectFilter(name: String, private val options: List<Pair<String, String>>) : Filter.Select<String>(name, options.map { it.second }.toTypedArray()) {
        fun toValue(): String? = options.getOrNull(state)?.first?.takeIf { it.isNotEmpty() }
    }

    private class GenreFilter : SelectFilter("Genre", genreOptions)
    private class StatusFilter : SelectFilter("Status", statusOptions)
    private class TypeFilter : SelectFilter("Type", typeOptions)

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

        private val SERIES_GRID_PREDICATE: (JsonElement) -> Boolean = { element ->
            element is JsonObject &&
                (element["children"] as? JsonArray)
                    ?.firstOrNull()
                    ?.reactProps()
                    ?.get("href")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.startsWith("/series/") == true
        }

        private val statusOptions = listOf(
            "" to "All",
            "ongoing" to "Ongoing",
            "completed" to "Completed",
        )

        private val typeOptions = listOf(
            "" to "All",
            "light-novel-jp" to "Light Novel (JP)",
            "web-novel" to "Web Novel",
        )

        private val genreOptions = listOf("" to "All") + listOf(
            "action" to "Action",
            "adult" to "Adult",
            "adventure" to "Adventure",
            "comedy" to "Comedy",
            "drama" to "Drama",
            "ecchi" to "Ecchi",
            "fantasy" to "Fantasy",
            "gender-bender" to "Gender Bender",
            "genderswap" to "Genderswap",
            "harem" to "Harem",
            "horror" to "Horror",
            "isekai" to "Isekai",
            "magic" to "Magic",
            "martial-arts" to "Martial Arts",
            "mature" to "Mature",
            "mecha" to "Mecha",
            "monster-girls" to "Monster Girls",
            "monsters" to "Monsters",
            "mystery" to "Mystery",
            "psychological" to "Psychological",
            "reincarnation" to "Reincarnation",
            "romance" to "Romance",
            "school-life" to "School Life",
            "sci-fi" to "Sci-fi",
            "seinen" to "Seinen",
            "shounen" to "Shounen",
            "slice-of-life" to "Slice of Life",
            "smut" to "Smut",
            "supernatural" to "Supernatural",
            "survival" to "Survival",
            "time-travel" to "Time Travel",
            "tragedy" to "Tragedy",
            "yuri" to "Yuri",
        )
    }
}

private fun JsonElement.reactProps(): JsonObject? = (this as? JsonArray)
    ?.takeIf { it.size >= 4 && (it[0] as? JsonPrimitive)?.contentOrNull == "$" }
    ?.get(3) as? JsonObject

private fun JsonElement.reactType(): String? = (this as? JsonArray)?.takeIf { it.size >= 4 }?.get(1)?.jsonPrimitive?.contentOrNull

private fun JsonElement.findReactElement(type: String): JsonElement? {
    if (reactType() == type) return this
    val children: Iterable<JsonElement> = when (this) {
        is JsonArray -> this
        is JsonObject -> this.values
        else -> return null
    }
    for (child in children) {
        child.findReactElement(type)?.let { return it }
    }
    return null
}

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
private class SynopsisDto(val html: String)

@Serializable
private class ChapterVolumesDto(val groups: List<VolumeGroupDto>)

@Serializable
private class VolumeGroupDto(val chapters: List<ChapterDto>)

@Serializable
private class ChapterDto(
    val slug: String,
    @SerialName("chapter_no") val chapterNo: String,
    val subtitle: String = "",
    val locked: Boolean = false,
)

private fun ChapterDto.toSChapter(showLocked: Boolean): SChapter? {
    if (locked && !showLocked) return null
    return SChapter.create().apply {
        url = slug
        chapter_number = chapterNo.toFloatOrNull() ?: -1f
        name = buildString {
            if (locked) append("🔒 ")
            append("Ch. ")
            append(chapterNo)
            if (subtitle.isNotBlank()) {
                append(": ")
                append(subtitle)
            }
        }
    }
}

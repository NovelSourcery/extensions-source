package eu.kanade.tachiyomi.novelextension.en.atsumarunovels

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.setAltTitles
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Instant
import kotlin.time.toJavaInstant

@Serializable
class BrowseMangaDto(
    val items: List<MangaDto>,
)

@Serializable
class MangaObjectDto(
    val mangaPage: MangaDto,
)

@Serializable
class SearchResultsDto(
    private val page: Int,
    private val found: Int,
    val hits: List<SearchMangaDto>,
    @SerialName("request_params") private val requestParams: RequestParamsDto,
) {
    fun hasNextPage(): Boolean = page * requestParams.perPage < found

    @Serializable
    class SearchMangaDto(
        val document: MangaDto,
    )

    @Serializable
    class RequestParamsDto(
        @SerialName("per_page") val perPage: Int,
    )
}

@Serializable
class MangaDto(
    private val id: String,
    private val title: String,
    @JsonNames("poster", "image")
    private val imagePath: JsonElement,
    private val largeImage: String? = null,

    private val englishTitle: String? = null,
    private val authors: JsonElement? = null,
    private val synopsis: String? = null,
    private val genres: JsonElement? = null,
    private val tags: JsonElement? = null,
    private val released: Long? = null,
    private val status: String? = null,
    val type: String? = null,
    private val views: JsonElement? = null,
    private val otherNames: List<String>? = null,
    private val avgRating: Float? = null,
    private val totalChapterCount: Int? = null,
    val scanlators: List<ScanlatorDto>? = null,
) {
    private fun getImagePath(): String? {
        val url = largeImage ?: when (imagePath) {
            is JsonPrimitive -> imagePath.content
            is JsonObject -> (imagePath["largeImage"] ?: imagePath["image"])?.jsonPrimitive?.content
            else -> null
        }
        return url?.removePrefix("/")?.removePrefix("static/")
    }

    private fun parseNames(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.content
                is JsonObject -> item["name"]?.jsonPrimitive?.content
                else -> null
            }
        }
        else -> emptyList()
    }

    private fun parseAuthorsWithType(element: JsonElement?): List<Pair<String, String?>> = when (element) {
        is JsonArray -> element.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> Pair(item.content, null)
                is JsonObject -> {
                    val name = item["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    Pair(name, item["type"]?.jsonPrimitive?.content)
                }
                else -> null
            }
        }
        else -> emptyList()
    }

    fun toSManga(baseUrl: String): SManga = SManga.create().apply {
        url = id
        title = this@MangaDto.title
        thumbnail_url = getImagePath()?.let {
            val url = when {
                it.startsWith("http") -> it
                it.startsWith("//") -> "https:$it"
                else -> "$baseUrl/static/$it"
            }
            url.replaceFirst(PROTOCOL_REGEX, "https://")
        }
    }

    fun toSMangaDetails(baseUrl: String): SManga = toSManga(baseUrl).apply {
        val altNames = (listOfNotNull(englishTitle) + otherNames.orEmpty())
            .filter { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
            .distinctBy { it.lowercase() }

        description = buildString {
            synopsis?.lines()?.joinToString("\n") { it.trim() }?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { append(it).append("\n\n") }

            avgRating?.takeIf { it > 0 }?.let { append("Rating: %.2f/10\n".format(Locale.ENGLISH, it)) }
            views?.jsonPrimitive?.content?.let { append("Views: $it\n") }
            totalChapterCount?.takeIf { it > 0 }?.let { append("Chapters: $it\n") }
            released?.takeIf { it > 0 }?.let {
                val year = Instant.fromEpochMilliseconds(it).toJavaInstant().atZone(ZoneId.systemDefault()).year
                append("Year: $year\n")
            }

            if (altNames.isNotEmpty()) {
                append("\nAlternative Titles:\n")
                append(altNames.joinToString("\n") { "• $it" })
            }
        }.trim()

        genre = (parseNames(genres) + parseNames(tags)).distinct().joinToString()

        val authorsList = parseAuthorsWithType(authors)
        author = authorsList.filter { it.second == "Author" || it.second == null }.joinToString { it.first }
        artist = authorsList.filter { it.second == "Artist" }.joinToString { it.first }.ifEmpty { null }

        status = when (this@MangaDto.status?.lowercase()?.trim()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "canceled", "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        setAltTitles(altNames)
    }

    @Serializable
    class ScanlatorDto(
        val id: String,
        val name: String,
        val score: Int,
    )

    companion object {
        private val PROTOCOL_REGEX = Regex("^https?:?//")
    }
}

@Serializable
class AllChaptersDto(
    val chapters: List<ChapterDto>,
)

@Serializable
class ChapterDto(
    val id: String,
    val number: Float,
    private val title: String,
    val scanlationMangaId: String? = null,
    @SerialName("createdAt") private val date: JsonElement? = null,
) {
    fun dateUploadMillis(): Long = when (date) {
        is JsonPrimitive -> date.longOrNull ?: Instant.tryParse(date.content)
        else -> 0L
    }

    fun toSChapter(mangaId: String, scanlatorName: String?): SChapter = SChapter.create().apply {
        url = "$mangaId/$id"
        chapter_number = number
        name = title
        scanlator = scanlatorName
        date_upload = dateUploadMillis()
    }
}

@Serializable
class NovelChapterObjectDto(
    val readNovelChapter: NovelChapterDto,
)

@Serializable
class NovelChapterDto(
    val paragraphs: List<String>,
)

@Serializable
class FilterData(
    private val genres: List<Filter>?,
    private val tags: List<Filter>?,
    private val types: List<Filter>?,
    private val statuses: List<Filter>?,
) {
    fun getFilterList(excludedGenreIds: Set<String> = emptySet()) = buildList {
        genres?.let { add(GenreFilter(it.map { Genre(it.name, it.id) }, excludedGenreIds)) }
        tags?.let { add(TagFilters(it.map { Tag(it.name, it.id) })) }
        types?.let { add(TypeFilter(it.map { Type(it.name, it.id) })) }
        statuses?.let { add(StatusFilter(it.map { Status(it.name, it.id) })) }
    }
}

@Serializable
class Filter(
    val id: String,
    val name: String,
)

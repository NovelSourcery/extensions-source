package eu.kanade.tachiyomi.novelextension.en.requiemtranslations

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.setAltTitles
import keiyoushi.utils.stripChapterNumberPrefix
import keiyoushi.utils.tryParseZonedDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.format.DateTimeFormatter

private const val COVER_URL = "https://pb.requiemtls.com/api/files/pbc_218332259"
private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSX")
private val leadingNumberRegex = Regex("""^#?\d+(\.\d+)?\.\s*""")
private val extraNewlinesRegex = Regex("\n{3,}")

@Serializable
class RecordsDto<T>(
    val page: Int = 1,
    val totalPages: Int = 1,
    val totalItems: Int = 0,
    val items: List<T>,
)

@Serializable
class NamedDto(val name: String)

@Serializable
class GenreDto(val id: String, val name: String)

@Serializable
class SeriesExpandDto(
    val genres: List<NamedDto> = emptyList(),
    val tags: List<NamedDto> = emptyList(),
    val writers: List<NamedDto> = emptyList(),
    val artists: List<NamedDto> = emptyList(),
    val types: List<NamedDto> = emptyList(),
)

@Serializable
class SeriesDto(
    val id: String,
    private val slug: String,
    private val title: String,
    private val thumbnail: String? = null,
    private val synopsis: String? = null,
    private val status: String? = null,
    private val score: Double = 0.0,
    @SerialName("release_year") private val releaseYear: String? = null,
    @SerialName("native_lang") private val nativeLang: String? = null,
    @SerialName("alt_titles") private val altTitles: String? = null,
    private val mature: Boolean = false,
    private val created: String? = null,
    private val updated: String? = null,
    private val expand: SeriesExpandDto? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@SeriesDto.title
        thumbnail_url = thumbnail?.takeIf { it.isNotBlank() }?.let { "$COVER_URL/$id/$it" }
    }

    fun toSMangaDetails(chapterCount: Int) = toSManga().apply {
        val names = expand ?: SeriesExpandDto()
        author = names.writers.joinToString { it.name }.ifEmpty { null }
        artist = names.artists.joinToString { it.name }.ifEmpty { null }
        genre = (names.genres + names.types + names.tags).map { it.name }
            .distinctBy(String::lowercase)
            .joinToString()
            .ifEmpty { null }
        status = when (this@SeriesDto.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        val alternatives = altTitles?.lines()?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        setAltTitles(alternatives)
        description = buildString {
            synopsis?.replace("\r", "")?.replace(extraNewlinesRegex, "\n\n")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append(it).append("\n\n")
            }
            if (score > 0) append("Score: ").append(score.toString().removeSuffix(".0")).append('\n')
            releaseYear?.takeIf { it.isNotBlank() }?.let { append("Year: ").append(it).append('\n') }
            nativeLang?.takeIf { it.isNotBlank() }?.let { append("Language: ").append(it).append('\n') }
            this@SeriesDto.status?.takeIf { it.isNotBlank() }?.let { append("Status: ").append(it).append('\n') }
            if (mature) append("R18: Yes\n")
            append("Chapters: ").append(chapterCount).append('\n')
            created?.let { append("Posted: ").append(it.substringBefore(' ')).append('\n') }
            updated?.let { append("Updated: ").append(it.substringBefore(' ')).append('\n') }
            if (alternatives.isNotEmpty()) {
                append("\nAlternative Titles:\n")
                append(alternatives.joinToString("\n") { "• $it" })
            }
        }.trim()
    }
}

@Serializable
class ChapterDto(
    @SerialName("chapter_number") private val chapterNumber: String? = null,
    @SerialName("chapter_title") private val chapterTitle: String? = null,
    private val slug: String? = null,
    @SerialName("published_at") private val publishedAt: String? = null,
) {
    val number: Float get() = chapterNumber?.toFloatOrNull() ?: -1f

    fun toSChapter() = SChapter.create().apply {
        url = slug!!
        chapter_number = number
        val label = chapterNumber?.takeIf { it.isNotBlank() }
        val title = chapterTitle?.trim()
            ?.replace(leadingNumberRegex, "")
            ?.stripChapterNumberPrefix()
            ?.takeIf { it.isNotEmpty() && it != label }
        name = when {
            label == null -> title ?: slug
            title == null -> "Ch. $label"
            else -> "Ch. $label: $title"
        }
        date_upload = dateFormat.tryParseZonedDateTime(publishedAt)
    }
}

@Serializable
class ChapterContentDto(val content: String? = null)

@Serializable
class ChapterSeriesExpandDto(val series: ChapterSeriesSlugDto)

@Serializable
class ChapterSeriesSlugDto(val slug: String)

@Serializable
class ChapterSeriesDto(private val expand: ChapterSeriesExpandDto? = null) {
    val slug: String? get() = expand?.series?.slug
}

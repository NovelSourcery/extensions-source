package eu.kanade.tachiyomi.novelextension.en.novelshaven

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.setAltTitles
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Serializable
class SeriesDto(
    private val title: String,
    @SerialName("cover_url") private val coverUrl: String? = null,
    @SerialName("optimized_cover_url") private val optimizedCoverUrl: String? = null,
    private val synopsis: String? = null,
    private val status: String? = null,
    private val author: String? = null,
    @SerialName("original_language") private val originalLanguage: String? = null,
    @SerialName("original_title") private val originalTitle: String? = null,
    @SerialName("created_on") private val createdOn: String? = null,
    @SerialName("updated_on") private val updatedOn: String? = null,
    @SerialName("novel_updates_url") private val novelUpdatesUrl: String? = null,
    @SerialName("release_volume") private val releaseVolume: Int? = null,
    @SerialName("release_frequency") private val releaseFrequency: Int? = null,
    @SerialName("bookmark_count") private val bookmarkCount: Int? = null,
    @SerialName("view_count_all") private val viewCountAll: Int? = null,
    @SerialName("view_count_monthly") private val viewCountMonthly: Int? = null,
    @SerialName("view_count_weekly") private val viewCountWeekly: Int? = null,
    @SerialName("rank_all") private val rankAll: Int? = null,
    @SerialName("rank_weekly") private val rankWeekly: Int? = null,
    @SerialName("chapter_count") private val chapterCount: Int? = null,
    @SerialName("content_rating") private val contentRating: String? = null,
    @SerialName("series_genres") private val seriesGenres: List<GenreLinkDto> = emptyList(),
    @SerialName("series_tags") private val seriesTags: List<TagLinkDto> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        title = this@SeriesDto.title
        thumbnail_url = optimizedCoverUrl ?: coverUrl
        author = this@SeriesDto.author?.takeIf { it.isNotBlank() }
        status = when (this@SeriesDto.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "dropped" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        genre = (seriesGenres.map { it.genres.name } + seriesTags.map { it.tags.name })
            .distinct()
            .joinToString()
            .takeIf { it.isNotEmpty() }

        val altTitles = listOfNotNull(originalTitle?.takeIf { it.isNotBlank() && it != title })
        setAltTitles(altTitles)

        description = buildString {
            synopsis?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it).append("\n\n") }
            viewCountAll?.let { append("Views: ").append(it) }
            viewCountMonthly?.let { append(" (monthly: ").append(it) }
            viewCountWeekly?.let { append(", weekly: ").append(it).append(')') }
            if (viewCountAll != null) append('\n')
            rankAll?.let { append("Rank: #").append(it) }
            rankWeekly?.let { append(" (weekly: #").append(it).append(')') }
            if (rankAll != null) append('\n')
            bookmarkCount?.let { append("Bookmarks: ").append(it).append('\n') }
            chapterCount?.let { append("Chapters: ").append(it).append('\n') }
            if (releaseVolume != null && releaseFrequency != null) {
                append("Release rate: ").append(releaseVolume).append(" ch / ")
                append(if (releaseFrequency == 1) "day" else "$releaseFrequency days").append('\n')
            }
            originalLanguage?.takeIf { it.isNotBlank() }?.let {
                append("Original language: ").append(it.replaceFirstChar(Char::uppercase)).append('\n')
            }
            contentRating?.takeIf { it.isNotBlank() }?.let { append("Content rating: ").append(it).append('\n') }
            createdOn?.let { append("Added: ").append(it).append('\n') }
            updatedOn?.let { append("Updated: ").append(it.substringBefore('T')).append('\n') }
            novelUpdatesUrl?.takeIf { it.isNotBlank() }?.let { append("NovelUpdates: ").append(it).append('\n') }
            if (altTitles.isNotEmpty()) {
                append("\nAlternative Titles:\n")
                append(altTitles.joinToString("\n") { "• $it" })
            }
        }.trim()
    }
}

@Serializable
class GenreLinkDto(val genres: NameDto)

@Serializable
class TagLinkDto(val tags: NameDto)

@Serializable
class NameDto(val name: String)

@Serializable
class ChapterDto(
    private val title: String? = null,
    @SerialName("is_premium") val isPremium: Boolean = false,
    @SerialName("is_padding") val isPadding: Boolean = false,
    @SerialName("chapter_num") private val chapterNum: Float,
    @SerialName("created_on") private val createdOn: String? = null,
) {
    fun toSChapter(slug: String) = SChapter.create().apply {
        val number = chapterNum.toString().removeSuffix(".0")
        url = "$slug/chapter-$number"
        chapter_number = chapterNum
        name = buildString {
            if (isPremium) append("🔒 ")
            append("Chapter ").append(number)
            title?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
        }
        date_upload = DATE_FORMAT.tryParseDate(createdOn, ZoneOffset.UTC)
    }
}

@Serializable
class FilterDataDto(
    val genres: List<String>,
    val tags: List<String>,
)

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

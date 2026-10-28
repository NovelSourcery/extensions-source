package eu.kanade.tachiyomi.novelextension.en.jadescrolls

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.formattedText
import keiyoushi.utils.setNumber
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import java.text.NumberFormat
import java.util.Locale
import kotlin.time.Instant

@Serializable
class NovelListDto(
    val data: List<NovelDto>,
    val meta: MetaDto,
)

@Serializable
class MetaDto(
    private val page: Int,
    @SerialName("total_pages") private val totalPages: Int,
) {
    fun hasNextPage() = page < totalPages
}

@Serializable
class NovelDto(
    val id: String,
    private val title: String,
    private val slug: String,
    private val synopsis: String? = null,
    @SerialName("author_name") private val authorName: String? = null,
    @SerialName("translator_name") private val translatorName: String? = null,
    @SerialName("cover_image") private val coverImage: String? = null,
    @SerialName("chapters_count") private val chaptersCount: Int? = null,
    @SerialName("chapters_word_count") private val chaptersWordCount: Long? = null,
    @SerialName("release_date") private val releaseDate: String? = null,
    private val metadata: NovelMetadataDto? = null,
    private val genres: List<GenreDto> = emptyList(),
    @SerialName("sub_genres") private val subGenres: List<GenreDto> = emptyList(),
    @SerialName("likes_count") private val likesCount: Long? = null,
    @SerialName("bookmark_count") private val bookmarkCount: Long? = null,
    @SerialName("total_views") private val totalViews: Long? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@NovelDto.title
        thumbnail_url = coverImage
        memo = buildJsonObject { put(ID_KEY, id) }
    }

    fun toSMangaDetails() = toSManga().apply {
        author = authorName?.takeIf { it.isNotBlank() }
        genre = (genres + subGenres).map { it.name }.distinct().joinToString()
        status = when (metadata?.releaseStatus) {
            "ONGOING", "COMINGSOON" -> SManga.ONGOING
            "COMPLETED" -> SManga.COMPLETED
            "HIATUS" -> SManga.ON_HIATUS
            "CANCELLED" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        description = buildString {
            synopsis?.let { Jsoup.parseBodyFragment(it).body().formattedText() }
                ?.takeIf { it.isNotBlank() }
                ?.let { append(it).append("\n\n") }

            val numbers = NumberFormat.getIntegerInstance(Locale.US)
            metadata?.averageRating?.takeIf { it > 0 }?.let { append("Rating: %.2f/5\n".format(Locale.US, it)) }
            totalViews?.let { append("Views: ${numbers.format(it)}\n") }
            likesCount?.let { append("Likes: ${numbers.format(it)}\n") }
            bookmarkCount?.let { append("Bookmarks: ${numbers.format(it)}\n") }
            chaptersCount?.let { append("Chapters: ${numbers.format(it)}\n") }
            chaptersWordCount?.takeIf { it > 0 }?.let { append("Words: ${numbers.format(it)}\n") }
            translatorName?.takeIf { it.isNotBlank() }?.let { append("Translator: $it\n") }
            metadata?.type?.let { append("Type: ${it.titleCase()}\n") }
            metadata?.language?.takeIf { it.isNotBlank() }?.let { append("Original language: $it\n") }
            metadata?.novelLead?.let { append("Lead: ${it.titleCase()}\n") }
            metadata?.contentRating?.let { append("Content rating: ${it.titleCase()}\n") }
            metadata?.licenseName?.takeIf { it.isNotBlank() && it != "NA" }?.let { append("License: $it\n") }
            releaseDate?.take(10)?.let { append("Released: $it\n") }
        }.trim()
    }

    companion object {
        const val ID_KEY = "id"
    }
}

private fun String.titleCase() = lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

@Serializable
class NovelMetadataDto(
    @SerialName("average_rating") val averageRating: Double? = null,
    val language: String? = null,
    @SerialName("novel_lead") val novelLead: String? = null,
    @SerialName("license_name") val licenseName: String? = null,
    @SerialName("content_rating") val contentRating: String? = null,
    val type: String? = null,
    @SerialName("release_status") val releaseStatus: String? = null,
)

@Serializable
class GenreDto(
    val id: String,
    val name: String,
)

@Serializable
class ChapterListDto(
    val data: List<ChapterDto>,
    val meta: MetaDto,
)

@Serializable
class ChapterDto(
    private val title: String,
    private val slug: String,
    @SerialName("chapter_number") private val chapterNumber: Float? = null,
    private val type: String? = null,
    @SerialName("publish_at") private val publishAt: String? = null,
    @SerialName("user_metadata") private val userMetadata: UserMetadataDto? = null,
) {
    val isLocked get() = type != "FREE" && userMetadata?.hasAccess != true

    fun toSChapter(novelSlug: String) = SChapter.create().apply {
        url = "$novelSlug/$slug"
        name = if (isLocked) "🔒 $title" else title
        chapterNumber?.let {
            chapter_number = it
            setNumber(it.toString().removeSuffix(".0"))
        }
        date_upload = Instant.tryParse(publishAt)
    }
}

@Serializable
class UserMetadataDto(
    @SerialName("has_access") val hasAccess: Boolean = false,
)

@Serializable
class ChapterContentDto(
    val content: String? = null,
    @SerialName("author_note") val authorNote: String? = null,
    @SerialName("user_metadata") val userMetadata: UserMetadataDto? = null,
)

@Serializable
class InteractionDto(
    @Suppress("unused") private val action: String,
)

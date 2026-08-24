package eu.kanade.tachiyomi.novelextension.ar.azora

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
class PostsResponse(
    val novelPosts: List<PostDto> = emptyList(),
    @SerialName("novelTotalCount") val novelTotalCount: Int = 0,
)

@Serializable
class PostDto(
    val id: Int = 0,
    val slug: String = "",
    @SerialName("postTitle") val postTitle: String = "",
    @SerialName("alternativeTitles") val alternativeTitles: String = "",
    @SerialName("featuredImage") val featuredImage: String = "",
    @SerialName("seriesStatus") val seriesStatus: String = "",
    @SerialName("postContent") val postContent: String = "",
    val genres: List<GenreDto> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        url = "/series/$slug"
        title = postTitle
        thumbnail_url = featuredImage
        author = alternativeTitles.trim()
        genre = genres.joinToString { it.name }
        status = when (seriesStatus.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class GenreDto(val name: String = "")

@Serializable
class ChaptersResponse(
    val post: ChaptersPostDto = ChaptersPostDto(),
)

@Serializable
class ChaptersPostDto(
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
class ChapterDto(
    val slug: String = "",
    val number: Int = 0,
    val title: String = "",
    @SerialName("createdAt") val createdAt: String = "",
    @SerialName("isAccessible") val isAccessible: Boolean = true,
    @SerialName("isLocked") val isLocked: Boolean = false,
    @SerialName("isPermanentlyLocked") val isPermanentlyLocked: Boolean = false,
    val price: Int = 0,
) {
    fun toSChapter(seriesSlug: String) = SChapter.create().apply {
        url = "/series/$seriesSlug/$slug"
        name = title.ifEmpty { "الفصل ${this@ChapterDto.number}" }
        chapter_number = this@ChapterDto.number.toFloat()
        date_upload = dateFormat.tryParse(createdAt)
    }
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

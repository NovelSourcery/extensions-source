package eu.kanade.tachiyomi.novelextension.en.novelarchive

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class NovelListResponse(
    val novels: List<NovelDto>,
    val pagination: Pagination,
)

@Serializable
class Pagination(
    @SerialName("has_next") val hasNext: Boolean = false,
)

@Serializable
class NovelDto(
    val id: String,
    val title: String,
    val author: String? = null,
    val genres: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    val description: String? = null,
)

@Serializable
class NovelDetailResponse(
    val novel: NovelDetailDto,
)

@Serializable
class NovelDetailDto(
    val id: String,
    val title: String,
    val author: String? = null,
    val genres: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    val description: String? = null,
    @SerialName("chapter_names") val chapterNames: List<String> = emptyList(),
    @SerialName("total_chapters") val totalChapters: String? = null,
    val views: String? = null,
    val rating: Double? = null,
    @SerialName("rating_count") val ratingCount: Int? = null,
    @SerialName("release_status") val releaseStatus: String? = null,
    val ongoing: String? = null,
)

@Serializable
class ChapterContentResponse(
    val chapter: ChapterContentDto,
)

@Serializable
class ChapterContentDto(
    val content: String? = null,
)

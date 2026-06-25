package eu.kanade.tachiyomi.novelextension.en.wetriedtls

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class QueryResponse(
    val meta: Meta,
    val data: List<SeriesDto>,
)

@Serializable
class Meta(
    @SerialName("next_page_url") val nextPageUrl: String? = null,
)

@Serializable
class SeriesDto(
    val id: Int,
    val title: String,
    val description: String? = null,
    @SerialName("alternative_names") val alternativeNames: String? = null,
    @SerialName("series_slug") val seriesSlug: String,
    val thumbnail: String? = null,
    val status: String? = null,
    val rating: Double? = null,
    val author: String? = null,
    val studio: String? = null,
    @SerialName("release_year") val releaseYear: String? = null,
    val tags: List<TagDto> = emptyList(),
)

@Serializable
class TagDto(
    val name: String,
)

@Serializable
class ChaptersResponse(
    val meta: Meta,
    val data: List<ChapterDto>,
)

@Serializable
class ChapterDto(
    @SerialName("chapter_slug") val chapterSlug: String,
    @SerialName("chapter_name") val chapterName: String,
    @SerialName("chapter_title") val chapterTitle: String? = null,
    val index: String,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
class ChapterContentResponse(
    val chapter: ChapterContentDto,
)

@Serializable
class ChapterContentDto(
    @SerialName("chapter_content") val chapterContent: String? = null,
)

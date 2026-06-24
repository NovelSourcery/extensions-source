package eu.kanade.tachiyomi.novelextension.ar.goldenrest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReleasesResponse(
    val releases: List<ReleaseDto> = emptyList(),
)

@Serializable
data class ReleaseDto(
    val id: Long = 0,
    val manga_id: Long = 0,
    val chapter: Float = 0f,
    val volume: Int = 0,
    val title: String = "",
    val team_name: String = "",
    val chapterization_id: Long = 0,
    val created_at: String = "",
    val content: String? = null,
    val manga: MangaDto? = null,
)

@Serializable
data class MangaResponse(
    val mangaData: MangaDto? = null,
)

@Serializable
data class MangaSearchResponse(
    val results: List<MangaDto> = emptyList(),
)

@Serializable
data class MangaDto(
    val id: Long = 0,
    val title: String = "",
    val summary: String? = null,
    val cover: String = "",
    val is_novel: Boolean = false,
    @SerialName("story_status") val storyStatus: Int = 0,
    val authors: List<AuthorDto> = emptyList(),
    val artists: List<AuthorDto> = emptyList(),
    val categories: List<CategoryDto> = emptyList(),
    val type: TypeDto? = null,
    val arabic_title: String? = null,
    val english: String? = null,
)

@Serializable
data class AuthorDto(
    val name: String = "",
    val role: String = "",
)

@Serializable
data class CategoryDto(
    val id: Long = 0,
    val name: String = "",
)

@Serializable
data class TypeDto(
    val name: String = "",
)

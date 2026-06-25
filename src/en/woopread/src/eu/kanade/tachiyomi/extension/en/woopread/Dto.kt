package eu.kanade.tachiyomi.novelextension.en.woopread

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ListResponse(
    val novels: List<NovelDto>,
    val totalCount: Int = 0,
)

@Serializable
class NovelDto(
    val id: String,
    val slug: String,
    val title: String,
    val cover: String? = null,
    val rating: String? = null,
    val author: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val status: String? = null,
    val language: String? = null,
    val views: Int? = null,
) {
    val displayGenres get() = (genres + tags).distinct()
}

@Serializable
class ChapterDto(
    val slug: String,
    val title: String,
    val number: Int,
    @SerialName("publishDate") val publishDate: String? = null,
)

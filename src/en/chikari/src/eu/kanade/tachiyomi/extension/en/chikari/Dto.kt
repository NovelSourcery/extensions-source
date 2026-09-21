package eu.kanade.tachiyomi.novelextension.en.chikari

import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.setAltTitles
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class NovelListDto(
    val items: List<NovelDto>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

@Serializable
class NovelDto(
    val slug: String,
    val title: String,
    @SerialName("cover_url") val coverUrl: String = "",
    val status: String = "",
    @SerialName("chapter_count") val chapterCount: Int = 0,
    val description: String = "",
    val genres: List<NamedSlugDto> = emptyList(),
    val tags: List<NamedIdDto> = emptyList(),
    val authors: List<AuthorDto> = emptyList(),
    @SerialName("alt_titles") val altTitles: List<String> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@NovelDto.title
        thumbnail_url = coverUrl.takeIf { it.isNotEmpty() }
    }

    fun toSMangaDetails() = toSManga().apply {
        author = authors
            .filter { it.role.equals("author", ignoreCase = true) }
            .joinToString { it.name }
            .ifEmpty { authors.joinToString { it.name } }
            .takeIf { it.isNotEmpty() }
        description = buildString {
            append(this@NovelDto.description)
            if (altTitles.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative Titles:\n")
                append(altTitles.joinToString("\n") { "• $it" })
            }
        }.trim().takeIf { it.isNotEmpty() }
        genre = (genres.map { it.name } + tags.map { it.name })
            .distinct()
            .joinToString()
            .takeIf { it.isNotEmpty() }
        status = when (this@NovelDto.status.lowercase()) {
            "releasing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled", "canceled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        if (altTitles.isNotEmpty()) {
            setAltTitles(altTitles)
        }
    }
}

@Serializable
class NamedSlugDto(
    val slug: String,
    val name: String,
)

@Serializable
class NamedIdDto(
    val id: Int,
    val name: String,
)

@Serializable
class AuthorDto(
    val name: String,
    val slug: String = "",
    val role: String = "",
)

@Serializable
class ChapterListDto(
    val items: List<ChapterDto>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)

@Serializable
class ChapterDto(
    val number: Double,
    val title: String = "",
    val volume: String = "",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
class ChapterReadDto(
    val number: Double,
    val title: String = "",
    val body: String = "",
    val locked: Boolean = false,
    @SerialName("lock_reason") val lockReason: String = "",
)

@Serializable
class FilterDataDto(
    val genres: List<NamedSlugDto>,
    val tags: List<NamedIdDto>,
)

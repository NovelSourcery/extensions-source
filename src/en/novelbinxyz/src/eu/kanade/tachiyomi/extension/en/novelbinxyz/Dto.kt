package eu.kanade.tachiyomi.novelextension.en.novelbinxyz

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ListPageDto(
    val data: List<ListNovelDto>,
    @SerialName("has_more") val hasMore: Boolean,
)

@Serializable
class ListNovelDto(
    private val title: String,
    private val slug: String,
    private val cover: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@ListNovelDto.title
        thumbnail_url = cover
    }
}

@Serializable
class SearchNovelDto(
    private val title: String,
    private val slug: String,
    private val cover: String? = null,
    @SerialName("alternative_titles") private val alternativeTitles: String? = null,
) {
    fun matches(term: String) = title.lowercase().contains(term) || alternativeTitles?.lowercase()?.contains(term) == true

    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@SearchNovelDto.title
        thumbnail_url = cover
    }
}

@Serializable
class NovelDto(
    val id: Int,
    val slug: String,
    private val title: String,
    private val author: String? = null,
    private val description: String? = null,
    private val cover: String? = null,
    private val genres: List<String> = emptyList(),
    private val status: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@NovelDto.title
        author = this@NovelDto.author
        description = this@NovelDto.description
        thumbnail_url = cover
        genre = genres.joinToString()
        status = when (this@NovelDto.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class ChapterDto(
    val id: Int,
    private val title: String,
) {
    // The reader numbers chapters from 1 in its url while the API counts from 0.
    fun toSChapter(slug: String) = SChapter.create().apply {
        url = "$slug/chapter-${id + 1}"
        name = title
    }
}

@Serializable
class ChapterContentDto(
    val content: String? = null,
)

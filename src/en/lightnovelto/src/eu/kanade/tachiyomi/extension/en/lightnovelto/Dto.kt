package eu.kanade.tachiyomi.novelextension.en.lightnovelto

import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
class GenreDto(val name: String, val slug: String)

@Serializable
class ChapterListDto(val chapters: List<ChapterDto>)

@Serializable
class ChapterDto(
    private val slug: String,
    private val number: Float,
    private val title: String? = null,
    private val createdAt: String? = null,
) {
    fun toSChapter(novelSlug: String) = SChapter.create().apply {
        url = "$novelSlug/$slug"
        name = title?.takeIf { it.isNotBlank() } ?: "Chapter ${number.toString().removeSuffix(".0")}"
        chapter_number = number
        date_upload = Instant.tryParse(createdAt)
    }
}

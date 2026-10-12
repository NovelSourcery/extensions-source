package eu.kanade.tachiyomi.novelextension.en.novellunar

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
class SearchResponse(val data: List<SearchNovelDto>)

@Serializable
class SearchNovelDto(
    private val slug: String,
    private val title: String,
    private val coverImage: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@SearchNovelDto.title
        thumbnail_url = coverImage
    }
}

@Serializable
class ChaptersResponse(val data: List<ChapterDto>)

@Serializable
class ChapterDto(
    private val title: String? = null,
    private val chapterNumber: Float,
    private val createdAt: String? = null,
) {
    fun toSChapter(novelSlug: String) = SChapter.create().apply {
        val number = chapterNumber.toString().removeSuffix(".0")
        url = "$novelSlug/$number"
        chapter_number = chapterNumber
        name = title?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("Chapter", ignoreCase = true)) it else "Chapter $number - $it" }
            ?: "Chapter $number"
        date_upload = Instant.tryParse(createdAt)
    }
}

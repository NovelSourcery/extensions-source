package eu.kanade.tachiyomi.novelextension.ar.riwayatarab

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** `GET https://api.riwayatarab.com/api/novels` */
@Serializable
class NovelListResponse(
    val novels: List<NovelDto> = emptyList(),
    val pagination: PaginationDto = PaginationDto(),
)

@Serializable
class PaginationDto(
    val currentPage: Int = 1,
    val totalPages: Int = 0,
    val totalCount: Int = 0,
    val hasNext: Boolean = false,
    val hasPrev: Boolean = false,
)

/**
 * `GET https://api.riwayatarab.com/api/novels/{slug}` and the entries of the list endpoint.
 *
 * [slug] is already percent-encoded by the API when the title is Arabic (e.g.
 * `%d8%b5%d8%b9%d9%88%d8%af-%d8%a8%d8%b1%d8%a7%d9%86%d8%af%d8%b1`), and is stored in
 * [SManga.url] in exactly that form so it can be dropped into every API path unchanged.
 */
@Serializable
class NovelDto(
    val id: String = "",
    val title: String = "",
    val slug: String = "",
    val author: String? = null,
    val description: String = "",
    @SerialName("cover_image") val coverImage: String = "",
    val status: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("category_name") val categoryName: String? = null,
    @SerialName("total_chapters") val totalChapters: Int = 0,
    @SerialName("latest_chapter_number") val latestChapterNumber: Int? = null,
) {
    fun toSManga(): SManga = SManga.create().apply {
        url = this@NovelDto.slug
        title = this@NovelDto.title
        thumbnail_url = this@NovelDto.coverImage
        author = this@NovelDto.author?.takeUnless { it == "غير محدد" }.orEmpty()
        description = this@NovelDto.description
        genre = this@NovelDto.tags.joinToString()
        this.status = when (this@NovelDto.status.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
    }
}

/** `GET .../api/novels/{slug}/chapters?page=N&limit=100` */
@Serializable
class ChapterListResponse(
    val chapters: List<ChapterDto> = emptyList(),
    val pagination: PaginationDto = PaginationDto(),
)

@Serializable
class ChapterDto(
    val id: String = "",
    val title: String = "",
    val slug: String = "",
    @SerialName("chapter_number") val chapterNumber: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
) {
    fun toSChapter(seriesSlug: String): SChapter = SChapter.create().apply {
        url = "/novel/$seriesSlug/chapter/$slug"
        name = title.ifBlank { "الفصل $chapterNumber" }
        chapter_number = chapterNumber.toFloat()
        date_upload = Instant.parseOrNull(createdAt)?.toEpochMilliseconds() ?: 0L
    }
}

/** `GET .../api/novels/{slug}/chapter/{chapterSlug}` */
@Serializable
class ChapterResponse(
    val chapter: ChapterContentDto = ChapterContentDto(),
)

@Serializable
class ChapterContentDto(
    val title: String = "",
    val slug: String = "",
    /** Plain text with `\r\n` line breaks — not HTML. */
    val content: String = "",
)

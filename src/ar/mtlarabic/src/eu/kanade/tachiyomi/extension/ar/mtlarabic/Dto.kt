package eu.kanade.tachiyomi.novelextension.ar.mtlarabic

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** [SManga.url] holds the numeric novel id behind this prefix. */
const val ID_PREFIX = "id-"

@Serializable
class ListingResponse(
    val items: List<ListingItem> = emptyList(),
    val pagination: ListingPagination = ListingPagination(),
)

@Serializable
class ListingPagination(
    val currentPage: Int = 1,
    val totalPages: Int = 1,
    val pageSize: Int = 12,
)

@Serializable
class ListingItem(
    val id: Int = 0,
    val name: String = "",
    val type: String = "",
    val status: String = "",
    /** A bare file name; the site serves covers from /images/novels/. */
    val image: String = "",
) {
    fun toSManga() = SManga.create().apply {
        // The numeric id is stored rather than the slug: slugs are Arabic and contain
        // characters that have to be percent-encoded, and hand-encoding them is where this
        // goes wrong. The id round-trips through any url and needs no encoding.
        url = ID_PREFIX + id
        title = name
        thumbnail_url = coverUrl(image)
        genre = type
        status = mangaStatus(this@ListingItem.status)
    }
}

@Serializable
class NovelDetails(
    val id: Int = 0,
    val slug: String = "",
    val name: String = "",
    val originalName: String = "",
    val description: String = "",
    val type: String = "",
    val status: String = "",
    val image: String = "",
    val totalChapters: Int = 0,
)

@Serializable
class ChaptersResponse(
    val chapters: List<ChapterDto> = emptyList(),
    val pagination: ChapterPagination = ChapterPagination(),
)

@Serializable
class ChapterPagination(
    val totalChapters: Int = 0,
    val totalPages: Int = 1,
    val currentPage: Int = 1,
)

@Serializable
class ChapterDto(
    val number: Int = 0,
    val title: String = "",
    val approvalDate: String = "",
) {
    fun toSChapter(novelSlug: String) = SChapter.create().apply {
        url = "/$novelSlug/$number"
        name = title.ifBlank { "الفصل $number" }
        chapter_number = number.toFloat()
        date_upload = Instant.tryParse(approvalDate)
    }
}

/** The reader's JSON island, which carries the chapter body as plain text. */
@Serializable
class ChapterPage(
    val content: String = "",
)

fun coverUrl(fileName: String): String {
    if (fileName.isEmpty()) return ""
    if (fileName.startsWith("http")) return fileName
    return "/images/novels/$fileName"
}

/** The site writes exactly these two status literals and nothing else. */
fun mangaStatus(raw: String): Int = when (raw) {
    "مستمرة" -> SManga.ONGOING
    "مكتملة" -> SManga.COMPLETED
    else -> SManga.UNKNOWN
}

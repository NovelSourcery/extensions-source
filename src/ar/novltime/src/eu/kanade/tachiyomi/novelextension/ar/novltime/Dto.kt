package eu.kanade.tachiyomi.novelextension.ar.novltime

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** The public asset host; the API returns cover paths without a scheme or host. */
internal const val SITE_URL = "https://novltime.com"

/**
 * The genre keys exposed by `GET /api/v1/genres` (plus `romance`, which the site links to but the
 * endpoint currently omits), in the order the site lists them, with their Arabic labels.
 */
internal val GENRE_LABELS: Map<String, String> = linkedMapOf(
    "action" to "أكشن",
    "adventure" to "مغامرة",
    "comedy" to "كوميديا",
    "drama" to "دراما",
    "fantasy" to "فانتازيا",
    "historical" to "تاريخي",
    "horror" to "رعب",
    "litrpg" to "LitRPG",
    "mystery" to "غموض",
    "romance" to "رومانسي",
    "scifi" to "خيال علمي",
    "sliceoflife" to "شريحة من الحياة",
    "system" to "نظام",
    "thriller" to "إثارة",
    "urban" to "فانتازيا حضرية",
    "wuxia" to "ووشيا",
    "xianxia" to "شيانشيا",
)

internal fun genreLabel(key: String): String = GENRE_LABELS[key] ?: key

/** `GET /api/v1/novels?…` — the listing and search endpoints share this envelope. */
@Serializable
class NovelListResponse(
    val data: List<NovelDto> = emptyList(),
    val meta: PageMeta = PageMeta(),
)

/** `GET /api/v1/novels/{slug}` */
@Serializable
class NovelDetailResponse(
    val data: NovelDto? = null,
)

/**
 * Cursor pagination metadata. The API only accepts `page=1`; every following page is requested
 * with the [nextCursor] of the previous one.
 */
@Serializable
class PageMeta(
    val page: Int = 1,
    val limit: Int = 0,
    val total: Int = 0,
    val totalPages: Int = 0,
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
)

/** An entry of the listing, search and details endpoints. */
@Serializable
class NovelDto(
    val id: String = "",
    val title: String = "",
    val slug: String = "",
    val author: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val descriptionShort: String? = null,
    val descriptionLong: String? = null,
    @SerialName("coverURL") val coverUrl: String? = null,
    val completionStatus: String? = null,
    val stats: NovelStats = NovelStats(),
) {
    fun toSManga(): SManga = SManga.create().apply {
        url = this@NovelDto.slug
        title = this@NovelDto.title
        thumbnail_url = coverUrl?.let { if (it.startsWith("http")) it else SITE_URL + it }
        author = this@NovelDto.author.orEmpty()
        description = descriptionLong?.takeIf { it.isNotBlank() } ?: descriptionShort.orEmpty()
        genre = this@NovelDto.genres.joinToString { genreLabel(it) }
        status = when (this@NovelDto.completionStatus?.uppercase()) {
            "COMPLETED" -> SManga.COMPLETED
            "ONGOING" -> SManga.ONGOING
            "HIATUS" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class NovelStats(
    val views: Int = 0,
    val followers: Int = 0,
    @SerialName("avgRating") val averageRating: Double = 0.0,
    @SerialName("ratingCount") val ratingCount: Int = 0,
    @SerialName("chapterCount") val chapterCount: Int = 0,
)

/** `GET /api/v1/novels/{id}/chapters/published?limit=…&cursor=…` */
@Serializable
class ChapterListResponse(
    val data: List<ChapterDto> = emptyList(),
    val meta: PageMeta = PageMeta(),
)

@Serializable
class ChapterDto(
    val id: String = "",
    val title: String = "",
    val slug: String = "",
    val order: Int = 0,
    val wordCount: Int = 0,
    val publishedAt: String? = null,
) {
    fun toSChapter(novelSlug: String): SChapter = SChapter.create().apply {
        url = "/ar/novel/$novelSlug/chapter/$slug"
        name = title.ifBlank { "الفصل $order" }
        chapter_number = order.toFloat()
        date_upload = publishedAt?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() } ?: 0L
    }
}

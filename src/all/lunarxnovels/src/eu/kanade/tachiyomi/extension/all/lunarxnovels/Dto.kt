package eu.kanade.tachiyomi.novelextension.all.lunarxnovels

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.parseAs
import keiyoushi.utils.setAltTitles
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Community catalog (novels uploaded to Lunar itself)

@Serializable
class SearchResponse(
    val novels: List<NovelDto> = emptyList(),
    val page: Int = 0,
    @SerialName("total_pages") val totalPages: Int = 0,
)

@Serializable
class RecentResponse(
    val data: List<NovelDto> = emptyList(),
)

@Serializable
class TitleResponse(
    val novel: NovelDto,
)

@Serializable
class NovelDto(
    private val slug: String,
    private val title: String,
    private val description: String? = null,
    @SerialName("cover_url") private val coverUrl: String? = null,
    private val genres: String? = null,
    private val themes: String? = null,
    private val demographic: String? = null,
    @SerialName("publication_status") private val publicationStatus: String? = null,
    @SerialName("publication_year") private val publicationYear: Int? = null,
    private val publisher: String? = null,
    private val author: String? = null,
    private val artist: String? = null,
    @SerialName("alternative_titles") private val alternativeTitles: String? = null,
    @SerialName("original_language") private val originalLanguage: String? = null,
    private val rating: String? = null,
) {
    fun toSManga(): SManga = SManga.create().apply {
        url = slug
        title = this@NovelDto.title
        thumbnail_url = coverUrl
        author = this@NovelDto.author?.trim()?.ifEmpty { null }
        artist = this@NovelDto.artist?.trim()?.ifEmpty { null }
        status = parseStatus(publicationStatus)
        genre = buildList {
            demographic?.takeUnless { it.isBlank() || it == "none" || it == "other" }
                ?.let { add(it.replaceFirstChar(Char::titlecase)) }
            addAll(genres.toStringList())
            addAll(themes.toStringList())
        }.filter { it.isNotBlank() }.distinct().joinToString().ifEmpty { null }

        val altTitles = alternativeTitles.toStringList().filter { it != this@NovelDto.title }
        setAltTitles(altTitles)
        description = buildString {
            description?.trim()?.let(::append)
            val stats = listOfNotNull(
                rating?.ifBlank { null }?.let { "Content rating: $it" },
                publisher?.ifBlank { null }?.let { "Publisher: $it" },
                publicationYear?.let { "Year: $it" },
                originalLanguage?.ifBlank { null }?.let { "Original language: ${it.uppercase()}" },
            )
            if (stats.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(stats.joinToString("\n"))
            }
            if (altTitles.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative Titles:\n")
                append(altTitles.joinToString("\n") { "• $it" })
            }
        }
    }
}

@Serializable
class ChapterListResponse(
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
class ChapterDto(
    val chapter: Float,
    @SerialName("chapter_title") private val chapterTitle: String? = null,
    val language: String = "en",
    @SerialName("uploaded_at") private val uploadedAt: Long? = null,
    @SerialName("is_coming_soon") val isComingSoon: Boolean = false,
) {
    val number get() = chapter.toString().removeSuffix(".0")

    fun toSChapter(slug: String, locked: Boolean, multiLanguage: Boolean): SChapter = SChapter.create().apply {
        url = "/novel/$slug/$number?lang=$language"
        val base = "Chapter $number"
        val title = chapterTitle?.trim().orEmpty()
        name = (if (locked) "🔒 " else "") + when {
            title.isEmpty() -> base
            title.startsWith(base, ignoreCase = true) -> title
            else -> "$base: $title"
        }
        chapter_number = chapter
        date_upload = uploadedAt ?: 0L
        if (multiLanguage) scanlator = language.uppercase()
    }
}

@Serializable
class PointsLockResponse(
    val locks: List<PointsLock> = emptyList(),
    val owned: List<String> = emptyList(),
)

@Serializable
class PointsLock(val unit: String)

@Serializable
class GateResponse(
    val gates: List<Gate> = emptyList(),
)

@Serializable
class Gate(
    val chapter: String? = null,
    @SerialName("require_account") val requireAccount: Boolean = false,
    @SerialName("require_guild") val requireGuild: Boolean = false,
    @SerialName("require_level") val requireLevel: Int = 0,
)

@Serializable
class PasswordInfoResponse(
    @SerialName("chapter_passwords") val chapterPasswords: List<ChapterPassword> = emptyList(),
    @SerialName("has_series_password") val hasSeriesPassword: Boolean = false,
)

@Serializable
class ChapterPassword(
    @SerialName("chapter_number") val chapterNumber: String? = null,
    val language: String? = null,
)

@Serializable
class ChapterContentDto(
    @SerialName("session_data") val sessionData: String? = null,
    val content: String? = null,
    val slug: String? = null,
)

@Serializable
class ErrorDto(
    val message: String? = null,
    val error: String? = null,
)

// Legacy catalog (scraped by Lunar from NovelPhoenix, served from its own endpoints)

@Serializable
class LegacySearchResponse(
    val results: LegacyResults,
)

@Serializable
class LegacyResults(
    val novels: List<LegacyNovelItem> = emptyList(),
    @SerialName("has_next_page") val hasNextPage: Boolean = false,
)

@Serializable
class LegacyNovelItem(
    private val slug: String,
    private val title: String,
    @SerialName("cover_path") private val coverPath: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@LegacyNovelItem.title
        thumbnail_url = coverPath
    }
}

@Serializable
class LegacyNovelResponse(
    val novel: LegacyNovelDto? = null,
)

@Serializable
class LegacyNovelDto(
    private val slug: String,
    val title: String,
    @SerialName("alternative_title") private val alternativeTitle: String? = null,
    private val author: String? = null,
    val chapters: String? = null,
    private val status: String? = null,
    private val genres: String? = null,
    @SerialName("cover_url") private val coverUrl: String? = null,
    private val description: String? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@LegacyNovelDto.title
        thumbnail_url = coverUrl?.ifBlank { null }
        author = this@LegacyNovelDto.author?.ifBlank { null }
        status = parseStatus(this@LegacyNovelDto.status)
        // genres arrive as a Python list repr: "['Action', 'Fantasy']"
        genre = genres?.let { PYTHON_STRING.findAll(it).map { m -> m.groupValues[1] }.joinToString() }?.ifEmpty { null }
        val altTitles = listOfNotNull(alternativeTitle?.trim()?.takeIf { it.isNotEmpty() && it != this@LegacyNovelDto.title })
        setAltTitles(altTitles)
        description = buildString {
            this@LegacyNovelDto.description?.trim()?.let(::append)
            chapters?.toIntOrNull()?.takeIf { it > 0 }?.let {
                if (isNotEmpty()) append("\n\n")
                append("Chapters: $it")
            }
            if (altTitles.isNotEmpty()) {
                append("\n\nAlternative Titles:\n")
                append(altTitles.joinToString("\n") { "• $it" })
            }
        }
    }
}

@Serializable
class LegacyTitlesResponse(
    val titles: List<String> = emptyList(),
)

@Serializable
class LegacyChapterResponse(
    val chapter: LegacyChapterDto? = null,
    val error: String? = null,
)

@Serializable
class LegacyChapterDto(
    @SerialName("chapter_title") val chapterTitle: String? = null,
    val content: String? = null,
    @SerialName("word_count") val wordCount: Int = 0,
)

private val PYTHON_STRING = Regex("""'([^']*)'""")

private fun parseStatus(status: String?) = when (status?.lowercase()) {
    "ongoing", "upcoming" -> SManga.ONGOING
    "completed" -> SManga.COMPLETED
    "hiatus" -> SManga.ON_HIATUS
    "cancelled" -> SManga.CANCELLED
    else -> SManga.UNKNOWN
}

// Several list fields are JSON-encoded strings; a few rows hold a bare comma list instead.
private fun String?.toStringList(): List<String> {
    if (isNullOrBlank()) return emptyList()
    return try {
        parseAs<List<String>>()
    } catch (_: Exception) {
        split(",").map { it.trim() }
    }.filter { it.isNotBlank() }
}

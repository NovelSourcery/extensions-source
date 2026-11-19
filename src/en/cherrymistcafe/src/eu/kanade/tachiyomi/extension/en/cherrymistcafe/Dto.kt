package eu.kanade.tachiyomi.novelextension.en.cherrymistcafe

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup

@Serializable
class SeriesPageDto(
    val rows: List<SeriesDto>,
    val total: Int,
)

@Serializable
class SeriesDto(
    val id: Long,
    val slug: String,
    val title: String,
    private val synopsis: String? = null,
    @SerialName("short_synopsis") private val shortSynopsis: String? = null,
    @SerialName("cover_image_url") private val coverImageUrl: String? = null,
    @SerialName("cover_thumb_url") private val coverThumbUrl: String? = null,
    @SerialName("author_name") private val authorName: String? = null,
    @SerialName("original_author") private val originalAuthor: String? = null,
    @SerialName("translator_name") private val translatorName: String? = null,
    @SerialName("alternative_title") private val alternativeTitle: String? = null,
    @SerialName("story_status") val storyStatus: String? = null,
    @SerialName("chapters_locked") val chaptersLocked: Int = 0,
    @SerialName("library_count") val libraryCount: Int = 0,
    @SerialName("tracking_count") val trackingCount: Int = 0,
    @SerialName("total_views") private val totalViews: Long? = null,
    @SerialName("total_chapters") private val totalChapters: Int? = null,
    @SerialName("last_release_at") val lastReleaseAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val genres: List<String>? = null,
    val tags: List<String>? = null,
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@SeriesDto.title
        thumbnail_url = coverImageUrl ?: coverThumbUrl
    }

    fun toDetails() = toSManga().apply {
        author = originalAuthor ?: authorName
        artist = translatorName
        genre = (genres.orEmpty() + tags.orEmpty()).map(String::trim).filter(String::isNotEmpty).distinct().joinToString()
        status = when (storyStatus?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed", "oneshot" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "dropped", "cancelled", "canceled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        description = buildString {
            (synopsis ?: shortSynopsis)?.let { html ->
                val body = Jsoup.parseBodyFragment(html.replace(COMMENTS, "")).body()
                body.select("p").forEach { it.after("\n\n") }
                body.select("br").forEach { it.after("\n") }
                append(body.wholeText().replace(TRAILING_SPACES, "\n").replace(EXTRA_LINES, "\n\n").trim())
            }
            alternativeTitle?.takeIf { it.isNotBlank() }?.let { append("\n\nAlternative title: $it") }
            totalChapters?.let { append("\n\nChapters: $it") }
            totalViews?.let { append("\nViews: $it") }
            append("\nIn libraries: $libraryCount")
        }.trim()
    }

    companion object {
        private val COMMENTS = Regex("""<!--[\s\S]*?-->""")
        private val TRAILING_SPACES = Regex("""[ \t]+\n""")
        private val EXTRA_LINES = Regex("""\n{3,}""")
    }
}

@Serializable
class ChapterDto(
    val id: Long,
    val title: String? = null,
    val slug: String? = null,
    @SerialName("chapter_number") val chapterNumber: Float? = null,
    val status: String? = null,
    @SerialName("price_type") val priceType: String? = null,
    @SerialName("is_locked") val isLocked: Int = 0,
    @SerialName("has_access_password") val hasAccessPassword: Int = 0,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
class ChapterIdDto(
    val id: Long,
)

@Serializable
class ChapterContentDto(
    val content: String? = null,
    val foreword: String? = null,
    val afterword: String? = null,
    val bodyWithheld: Boolean = false,
    val revealTicket: String? = null,
    val revealKey: String? = null,
)

@Serializable
class RevealRequestDto(
    private val ticket: String,
    private val proof: String,
    private val proofScheme: String,
    private val frames: Int,
    private val elapsedMs: Int,
    private val visible: Boolean,
    private val focused: Boolean,
)

@Serializable
class TicketPayloadDto(
    val jti: String? = null,
)

@Serializable
class ChapterSlugDto(
    @SerialName("series_slug") val seriesSlug: String,
)

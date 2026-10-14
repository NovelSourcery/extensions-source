package eu.kanade.tachiyomi.multisrc.ikennovel

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.formattedText
import keiyoushi.utils.setAltTitles
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import kotlin.time.Instant

@Serializable
class SearchResponse(
    val posts: List<Post>,
    val totalCount: Int,
)

@Serializable
class PostResponse(
    private val totalChapterCount: Int? = null,
    val post: Post,
) {
    fun toSManga() = post.toSManga(totalChapterCount)
}

@Serializable
class ChaptersResponse(
    val post: ChapterListPost,
)

@Serializable
class ChapterListPost(
    val chapters: List<Chapter>,
)

@Serializable
class ChapterResponse(
    val chapter: ChapterContent,
)

@Serializable
class ChapterContent(
    val content: String? = null,
)

@Serializable
class Genre(
    val id: Int,
    val name: String,
)

@Serializable
class Post(
    val id: Int,
    private val slug: String,
    private val postTitle: String,
    private val postContent: String? = null,
    @SerialName("isNovel") private val rawIsNovel: Boolean = false,
    private val seriesType: String? = null,
    private val seriesStatus: String? = null,
    private val featuredImage: String? = null,
    private val alternativeTitles: String? = null,
    private val author: String? = null,
    private val artist: String? = null,
    private val releaseDate: String? = null,
    private val genres: List<Genre> = emptyList(),
    private val totalViews: Long? = null,
    private val averageRating: Double? = null,
    private val totalRatings: Int? = null,
    @SerialName("_count") private val count: PostCount? = null,
) {
    val isNovel: Boolean
        get() = rawIsNovel || seriesType.equals("NOVEL", ignoreCase = true)

    fun toSManga(totalChapterCount: Int? = null) = SManga.create().apply {
        url = slug
        title = postTitle
        thumbnail_url = featuredImage
        author = this@Post.author?.trim()?.takeIf { it.isNotEmpty() }
        artist = this@Post.artist?.trim()?.takeIf { it.isNotEmpty() }
        genre = genres.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct().joinToString().takeIf { it.isNotEmpty() }
        status = when (seriesStatus) {
            "ONGOING", "COMING_SOON", "MASS_RELEASED" -> SManga.ONGOING
            "COMPLETED" -> SManga.COMPLETED
            "CANCELLED", "DROPPED" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        memo = buildJsonObject { put("id", id) }

        val altNames = alternativeTitles.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        setAltTitles(altNames)

        description = buildString {
            postContent?.takeIf { it.isNotBlank() }?.let {
                append(Jsoup.parseBodyFragment(it).body().formattedText())
            }

            val stats = buildList {
                if (averageRating != null && (totalRatings ?: 0) > 0) add("Rating: $averageRating/10 ($totalRatings ratings)")
                totalViews?.let { add("Views: $it") }
                count?.bookmarks?.let { add("Bookmarks: $it") }
                (totalChapterCount ?: count?.chapters)?.let { add("Chapters: $it") }
                releaseDate?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Release: $it") }
            }
            if (stats.isNotEmpty()) {
                append("\n\n")
                append(stats.joinToString("\n"))
            }

            if (altNames.isNotEmpty()) {
                append("\n\nAlternative Titles:\n")
                append(altNames.joinToString("\n") { "• $it" })
            }
        }.trim()
    }
}

@Serializable
class PostCount(
    val bookmarks: Int? = null,
    val chapters: Int? = null,
)

@Serializable
class Chapter(
    private val id: Int,
    private val slug: String,
    private val number: JsonPrimitive,
    private val title: String? = null,
    private val createdAt: String? = null,
    @SerialName("isLocked") private val rawIsLocked: Boolean? = false,
    private val isAccessible: Boolean? = true,
) {
    val isLocked: Boolean
        get() = rawIsLocked == true || isAccessible == false

    fun toSChapter(seriesSlug: String) = SChapter.create().apply {
        val numberText = number.content
        url = id.toString()
        chapter_number = numberText.toFloatOrNull() ?: -1f
        name = buildString {
            if (isLocked) append("🔒 ")
            append("Chapter ")
            append(numberText)
            title?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Chapter $numberText", ignoreCase = true) }?.let {
                append(" - ")
                append(it)
            }
        }
        date_upload = Instant.tryParse(createdAt)
        memo = buildJsonObject {
            put("seriesSlug", seriesSlug)
            put("slug", slug)
        }
    }
}

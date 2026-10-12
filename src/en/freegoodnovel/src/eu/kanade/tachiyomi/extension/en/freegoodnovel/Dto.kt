package eu.kanade.tachiyomi.novelextension.en.freegoodnovel

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParseZonedDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSX")
private val webSlugRegex = Regex("[^a-z0-9]+")
private val paragraphBreakRegex = Regex("""\n\s*\n""")
private val extraNewlinesRegex = Regex("\n{3,}")

@Serializable
class RecordsDto<T>(
    val page: Int = 1,
    val totalPages: Int = 1,
    val items: List<T>,
)

@Serializable
class NovelDto(
    private val id: String,
    private val title: String,
    private val author: String? = null,
    private val synopsis: String? = null,
    private val genre: String? = null,
    private val status: String? = null,
    private val cover: String? = null,
    private val views: Long = 0,
    private val readers: Long = 0,
    private val rating: Double = 0.0,
    @SerialName("upload_date") private val uploadDate: String? = null,
    private val tags: List<String> = emptyList(),
    @SerialName("chapter_count") private val chapterCount: Int = 0,
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        url = id
        title = this@NovelDto.title
        author = this@NovelDto.author?.takeIf { it.isNotBlank() }
        thumbnail_url = cover?.takeIf { it.isNotBlank() }?.let { "$baseUrl/api/files/novels/$id/$it" }
        genre = (listOfNotNull(this@NovelDto.genre?.takeIf { it.isNotBlank() }) + tags.map(String::trim))
            .distinctBy(String::lowercase)
            .joinToString()
        status = when (this@NovelDto.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        description = buildString {
            synopsis?.replace("\r", "")?.replace(extraNewlinesRegex, "\n\n")?.trim()?.let { append(it) }
            val stats = buildList {
                if (rating > 0) add("Rating: ${rating.toString().removeSuffix(".0")}")
                if (views > 0) add("Views: $views")
                if (readers > 0) add("Readers: $readers")
                if (chapterCount > 0) add("Chapters: $chapterCount")
                uploadDate?.takeIf { it.isNotBlank() }?.let { add("Uploaded: $it") }
            }
            if (stats.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(stats.joinToString("\n"))
            }
        }
    }
}

@Serializable
class ChapterDto(
    @SerialName("chapter_no") private val chapterNo: Int,
    private val created: String? = null,
) {
    fun toSChapter(novelId: String) = SChapter.create().apply {
        url = "$novelId/$chapterNo"
        // Site chapter titles are just "<novel title> <n>" repeated, so they carry no information.
        name = "Chapter $chapterNo"
        chapter_number = chapterNo.toFloat()
        date_upload = dateFormat.tryParseZonedDateTime(created)
    }
}

@Serializable
class ChapterContentDto(val content: String)

@Serializable
class TagsDto(val tags: List<String> = emptyList())

fun String.toWebSlug() = lowercase().replace(webSlugRegex, "-").trim('-')

fun String.toParagraphHtml(): String = replace("\r", "")
    .split(paragraphBreakRegex)
    .map(String::trim)
    .filter(String::isNotEmpty)
    .joinToString("\n") { paragraph ->
        val p = Element("p")
        paragraph.lines().forEachIndexed { index, line ->
            if (index > 0) p.appendElement("br")
            p.appendText(line.trim())
        }
        p.outerHtml()
    }

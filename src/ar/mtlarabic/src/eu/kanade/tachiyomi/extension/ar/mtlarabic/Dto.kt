package eu.kanade.tachiyomi.novelextension.ar.mtlarabic

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Response
import kotlin.time.Instant

/** [SManga.url] holds the numeric novel id behind this prefix. */
const val ID_PREFIX = "id-"

/** The only place the site serves real JSON; everything else is HTML with an island in it. */
val islandJson = Json { ignoreUnknownKeys = true }

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
    fun toSManga(baseUrl: String) = SManga.create().apply {
        // The numeric id is stored rather than the slug: slugs are Arabic and contain
        // characters that have to be percent-encoded, and hand-encoding them is where this
        // goes wrong. The id round-trips through any url and needs no encoding.
        url = ID_PREFIX + id
        title = name
        thumbnail_url = coverUrl(baseUrl, image)
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

fun coverUrl(baseUrl: String, fileName: String): String {
    if (fileName.isEmpty()) return ""
    if (fileName.startsWith("http")) return fileName
    // The listing and the detail page both send a bare file name, never a path: the cover lives at
    // /images/novels/<name>. It has to be an absolute url -- a leading-slash path is not a valid
    // thumbnail_url and renders as an empty cover.
    return baseUrl + "/images/novels/$fileName"
}

/** The site writes exactly these two status literals and nothing else. */
fun mangaStatus(raw: String): Int = when (raw) {
    "مستمرة" -> SManga.ONGOING
    "مكتملة" -> SManga.COMPLETED
    else -> SManga.UNKNOWN
}

/**
 * `/novels`, `/novel-details` and the reader all answer with a full HTML page whose payload is a
 * JSON island in a `<script type="application/json">` tag. Parsing the response body directly
 * hands the JSON decoder a document that starts with `<!DOCTYPE html>`.
 *
 * The reader page carries two islands, so the first one is taken: the chapter body lives in the
 * earlier tag, and the later one is the novel's chapter index, which has no `content` field.
 */
private val ISLAND = Regex("""<script[^>]*type=["']application/json["'][^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)

/** Pulls the first island out of an HTML page, for use as a `String.parseAs` transform. */
fun extractIsland(html: String): String = ISLAND.find(html)?.groupValues?.get(1)
    ?: throw IllegalStateException("No JSON island in the page")

/** Parses a response body that is an HTML page with the JSON embedded in a script tag. */
inline fun <reified T> Response.parseAsIsland(json: Json = islandJson): T = parseAs(json) { extractIsland(it) }

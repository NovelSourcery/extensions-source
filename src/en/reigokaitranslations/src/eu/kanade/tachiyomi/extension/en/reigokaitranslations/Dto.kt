package eu.kanade.tachiyomi.novelextension.en.reigokaitranslations

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.time.Instant

@Serializable
class RenderedDto(val rendered: String)

@Serializable
class PageDto(
    val slug: String,
    private val link: String,
    private val title: RenderedDto,
    private val content: RenderedDto,
) {
    fun document(): Document = Jsoup.parseBodyFragment(content.rendered, link)

    fun toSManga(document: Document, fallbackTitle: String?): SManga? {
        val pageTitle = Jsoup.parse(title.rendered).text().ifEmpty { null }
            ?: document.selectFirst("h1, h2, h3, h4")?.text()?.ifEmpty { null }
            ?: fallbackTitle
            ?: return null
        val host = link.toHttpUrl().host
        return SManga.create().apply {
            url = link.toHttpUrl().encodedPath
            this.title = pageTitle
            thumbnail_url = document.selectFirst("img[src]")?.absUrl("src")?.replace(LEGACY_IMAGE_HOST, LEGACY_IMAGE_TARGET)
            status = STATUS_REGEX.find(document.text())?.groupValues?.get(1)?.lowercase().let {
                when {
                    it == null -> SManga.UNKNOWN
                    it.startsWith("complete") -> SManga.COMPLETED
                    it == "ongoing" -> SManga.ONGOING
                    it == "hiatus" -> SManga.ON_HIATUS
                    it == "dropped" -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
            }
            description = document.body().children()
                .takeWhile { el -> el.select("a[href]").none { it.isChapterLink(host) } }
                .map { it.text() }
                .filter { it.isNotEmpty() }
                // Drops trailing "Chapters:" / "Glossary:" labels that introduce the chapter list.
                .dropLastWhile { it.endsWith(":") }
                .joinToString("\n\n")
                .ifEmpty { null }
        }
    }

    fun chapters(document: Document): List<SChapter> {
        val host = link.toHttpUrl().host
        return document.select("a[href]")
            .filter { it.isChapterLink(host) }
            .distinctBy { it.absUrl("href").toHttpUrl().postSlug() }
            .mapNotNull { a ->
                val name = a.text().removePrefix("–").trim().ifEmpty { return@mapNotNull null }
                val path = a.absUrl("href").toHttpUrl().encodedPath
                SChapter.create().apply {
                    url = path
                    this.name = name
                    date_upload = PATH_DATE_FORMAT.tryParseDate(path.postDate(), ZoneOffset.UTC)
                }
            }
    }
}

@Serializable
class PostCategoriesDto(val categories: List<Int>)

@Serializable
class PostContentDto(val content: RenderedDto)

@Serializable
class PostDto(
    @SerialName("date_gmt") private val dateGmt: String,
    private val link: String,
    private val title: RenderedDto,
) {
    fun toSChapter() = SChapter.create().apply {
        url = link.toHttpUrl().encodedPath
        name = Jsoup.parse(title.rendered).text().replaceFirst(SERIES_PREFIX_REGEX, "")
        date_upload = Instant.tryParse("${dateGmt}Z")
    }
}

private fun Element.isChapterLink(host: String): Boolean {
    val url = absUrl("href").toHttpUrlOrNull() ?: return false
    return url.host == host && CHAPTER_PATH_REGEX.matches(url.encodedPath)
}

fun HttpUrl.postSlug(): String = encodedPathSegments.last { it.isNotEmpty() }.lowercase()

/** Chapter posts live at `/yyyy/MM/dd/<slug>/`; returns the `yyyy/MM/dd` part. */
fun String.postDate(): String? = CHAPTER_PATH_REGEX.matchEntire(this)?.groupValues?.get(1)

val CHAPTER_PATH_REGEX = Regex("""^/(\d{4}/\d{2}/\d{2})/[^/]+/?$""")

private val PATH_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd")

// Images from the blog's wordpress.com days 302 to another host, which the cover loader doesn't follow.
// Not all of them were migrated to the site's own uploads, so point at the redirect target directly.
private const val LEGACY_IMAGE_HOST = "https://isekailunatic.files.wordpress.com/"
private const val LEGACY_IMAGE_TARGET = "https://isekailunatic.wordpress.com/wp-content/uploads/"

private val STATUS_REGEX = Regex("""Translation\s*:\s*(\w+)""", RegexOption.IGNORE_CASE)

// Post titles are prefixed with the series' short name, e.g. "VAMP – Chapter 16: ...".
private val SERIES_PREFIX_REGEX = Regex("""^[A-Za-z]{2,10}\s*[–-]\s+""")

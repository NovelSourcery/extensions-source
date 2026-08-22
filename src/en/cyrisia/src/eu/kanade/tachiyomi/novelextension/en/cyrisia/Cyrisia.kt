package eu.kanade.tachiyomi.novelextension.en.cyrisia

import android.util.Base64
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.setAltTitles
import keiyoushi.zip.Entry
import keiyoushi.zip.MAX_EOCD_SEARCH
import keiyoushi.zip.ZipDirectory
import keiyoushi.zip.dataRange
import keiyoushi.zip.range
import keiyoushi.zip.readZipDirectory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import okio.buffer
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import keiyoushi.zip.readEntry as parseZipEntry

/**
 * Cyrisia is a personal/community EPUB library: series are static uploaded volumes rather than
 * per-chapter web content, so each EPUB is treated as one [SChapter] whose "page text" is the
 * concatenation of the volume's own internal chapter documents.
 *
 * The EPUB file itself needs no login - confirmed live, an anonymous request succeeds as long as
 * `Referer` starts with `$baseUrl/read/` (the site's own reader page for that volume); a bare or
 * missing `Referer` gets a 403.
 */
@Source
abstract class Cyrisia :
    KeiSource(),
    NovelSource {

    override val supportsLatest = false

    // ======================== Popular / Search ========================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val all = fetchBookshelf().sortedBy { it.name.lowercase() }
        return paginate(all, page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val all = fetchBookshelf().filter { it.name.contains(query, ignoreCase = true) }
        return paginate(all, page)
    }

    private suspend fun fetchBookshelf(): List<BookshelfEntry> = client.get("$baseUrl/api/bookshelf", headers).parseAs()

    private fun paginate(list: List<BookshelfEntry>, page: Int): MangasPage {
        val from = (page - 1) * PAGE_SIZE
        val mangas = list.drop(from).take(PAGE_SIZE).map { entry ->
            SManga.create().apply {
                url = entry.name
                title = entry.name
                thumbnail_url = entry.cover?.let { baseUrl + it.escapeCoverUrl() }
            }
        }
        return MangasPage(mangas, from + PAGE_SIZE < list.size)
    }

    // The bookshelf API's `cover` field is a query string it built itself, but escaping is
    // inconsistent per entry - confirmed live, some titles with "+" or raw spaces in their name
    // come back completely unescaped (e.g. "...Second + Children...Kobo].epub" with a literal
    // space and "+"), which OkHttp/Coil then reject outright. Percent-encode just those two
    // unsafe characters; existing %XX sequences are left alone.
    private fun String.escapeCoverUrl(): String = replace(" ", "%20").replace("+", "%2B")

    // ======================== Details + Chapters ========================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series".toHttpUrl().newBuilder().addPathSegment(manga.url).build().toString()

    // e.g. https://cyrisia.com/series/ReZero%20-%20Starting%20Life%20in%20Another%20World -
    // pathSegments are already percent-decoded, matching the raw series name used as manga.url
    // elsewhere (see getMangaUrl, which re-encodes it).
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.getOrNull(0) != "series") return null
        val seriesName = url.pathSegments.getOrNull(1) ?: return null
        return fetchManga(seriesName)
    }

    private suspend fun fetchManga(seriesName: String): SManga {
        val metadataUrl = "$baseUrl/api/metadata".toHttpUrl().newBuilder().addQueryParameter("series", seriesName).build()
        val meta = runCatching { client.get(metadataUrl, headers).parseAs<MetadataDto>() }.getOrNull()
        return buildSManga(seriesName, meta)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val seriesName = manga.url

        val updatedManga = if (fetchDetails) fetchManga(seriesName) else manga

        val updatedChapters = if (fetchChapters) {
            val entry = fetchBookshelf().firstOrNull { it.name == seriesName }
            entry?.epubs?.mapIndexed { index, filename ->
                SChapter.create().apply {
                    url = "$baseUrl/bibi-bookshelf".toHttpUrl().newBuilder()
                        .addPathSegment(seriesName).addPathSegment(filename).build().encodedPath
                    name = filename.removeSuffix(".epub")
                    chapter_number = (index + 1).toFloat()
                }
            }?.reversed() ?: chapters
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun buildSManga(seriesName: String, meta: MetadataDto?): SManga = SManga.create().apply {
        url = seriesName
        title = meta?.titleEn ?: meta?.romaji ?: seriesName
        thumbnail_url = meta?.coverUrl
        description = meta?.synopsis
        genre = (meta?.genres.orEmpty() + meta?.tags.orEmpty()).distinct().joinToString()
        status = when (meta?.publicationStatus) {
            "ongoing" -> SManga.ONGOING
            "finished", "completed" -> SManga.COMPLETED
            "cancelled" -> SManga.CANCELLED
            "hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        val altTitles = listOfNotNull(meta?.romaji, meta?.titleJa, meta?.aliases)
            .filter { it.isNotBlank() && it != title }
            .distinct()
        if (altTitles.isNotEmpty()) {
            setAltTitles(altTitles)
        }
    }

    // ======================== Pages ========================

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    // Cyrisia has no chapters of its own - one SChapter is a whole EPUB volume, so re-opening it
    // (e.g. going back and reopening the same volume) would otherwise redo the full directory +
    // per-entry range-fetch dance for a result that hasn't changed. Cache only the most recently
    // read volume - small and bounded, unlike caching every volume ever opened.
    private var lastPage: Pair<String, String>? = null

    override suspend fun fetchPageText(page: Page): String {
        lastPage?.let { (url, text) -> if (url == page.url) return text }

        val segments = (baseUrl + page.url).toHttpUrl().pathSegments
        val seriesName = segments.getOrNull(1)
        val volumeFilename = segments.getOrNull(2)

        // The file endpoint 403s without a Referer pointing at the site's own reader page for
        // this volume - see the class doc. It needs no other auth.
        val readerReferer = if (seriesName != null && volumeFilename != null) {
            "$baseUrl/read".toHttpUrl().newBuilder().addPathSegment(seriesName).addPathSegment(volumeFilename).build().toString()
        } else {
            "$baseUrl/read/"
        }
        val zipHeaders = headers.newBuilder().set("Referer", readerReferer).build()

        // The EPUB is streamed via HTTP Range requests (directory + only the entries actually
        // needed) instead of downloading the whole archive into memory.
        val zipUrl = baseUrl + page.url
        val directory = fetchZipDirectory(zipUrl, zipHeaders)

        // cyrisia.com's file server ignores the Range header on some responses and returns the
        // whole EPUB with 200 instead of 206 (confirmed live) - a naive reader then misreads the
        // response as if it started at the requested offset, corrupting every entry after the
        // first. Once that happens the "ranged" response already holds the entire file, so cache
        // it and slice later entries out of it instead of re-requesting (and re-downloading the
        // whole file) per entry.
        var wholeFile: ByteArray? = null

        fun readEntry(name: String): ByteArray? {
            val entry = directory.entries.firstOrNull { it.name == name } ?: return null

            wholeFile?.let { return sliceZipEntry(it, entry) }

            val response = client.newCall(GET(zipUrl, zipHeaders).newBuilder().range(entry.dataRange).build()).execute()
            val body = response.body.bytes()
            if (response.code != 206) {
                wholeFile = body
                return sliceZipEntry(body, entry)
            }
            return parseZipEntry(Buffer().write(body), entry.compressedSize, entry.method).buffer().readByteArray()
        }

        val containerXml = readEntry("META-INF/container.xml") ?: throw Exception("Not a valid EPUB: missing container.xml")
        val opfPath = Jsoup.parse(String(containerXml), "", Parser.xmlParser())
            .selectFirst("rootfile")?.attr("full-path")
            ?: throw Exception("Not a valid EPUB: missing OPF rootfile")
        val opfDir = opfPath.substringBeforeLast("/", "")

        val opfDoc = readEntry(opfPath)?.let { Jsoup.parse(String(it), "", Parser.xmlParser()) }
            ?: throw Exception("Not a valid EPUB: missing OPF package document")

        val hrefById = opfDoc.select("manifest > item").associate { it.attr("id") to it.attr("href") }
        val spineHrefs = opfDoc.select("spine > itemref")
            .mapNotNull { hrefById[it.attr("idref")] }
            .filterNot { href -> SKIP_SPINE_ITEM_REGEX.containsMatchIn(href) }

        // Stylesheets/scripts referenced from each chapter's <head> - deduped by resolved path,
        // since every chapter in a volume typically links the same shared files.
        val cssJsPaths = LinkedHashSet<String>()

        // Caps how much image data gets base64-inlined into the returned HTML: a per-image limit
        // (skip a single oversized illustration/scan) and a running total for the whole volume
        // (stop inlining once a volume's images alone would bloat the page past a sane size),
        // so a volume can't blow up memory just by having many/large images.
        var inlinedImageBytes = 0L
        fun inlineImage(baseDir: String, relative: String): String? {
            if (relative.isEmpty() || relative.startsWith("data:")) return null
            val path = resolveZipPath(baseDir, relative)
            val entry = directory.entries.firstOrNull { it.name == path } ?: return null
            if (entry.compressedSize > MAX_INLINE_IMAGE_BYTES || inlinedImageBytes + entry.compressedSize > MAX_TOTAL_INLINE_IMAGE_BYTES) return null
            val bytes = readEntry(path) ?: return null
            inlinedImageBytes += bytes.size
            return "data:${mimeTypeFor(path)};base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        }

        val text = spineHrefs.joinToString("<hr>") { href ->
            val entryPath = resolveZipPath(opfDir, href)
            val bytes = readEntry(entryPath) ?: return@joinToString ""
            val entryDir = entryPath.substringBeforeLast("/", "")
            val doc = Jsoup.parse(String(bytes))

            doc.head().select("link, script").forEach { el ->
                val relHref = when {
                    el.tagName() == "link" && el.attr("rel").equals("stylesheet", ignoreCase = true) -> el.attr("href")
                    el.tagName() == "script" -> el.attr("src")
                    else -> ""
                }
                if (relHref.isNotEmpty()) cssJsPaths += resolveZipPath(entryDir, relHref)
            }

            val body = doc.body()
            body.select("img[src]").forEach { img ->
                val inlined = inlineImage(entryDir, img.attr("src"))
                if (inlined != null) img.attr("src", inlined) else img.remove()
            }
            body.select("image").forEach { image ->
                val attrName = if (image.hasAttr("xlink:href")) {
                    "xlink:href"
                } else if (image.hasAttr("href")) {
                    "href"
                } else {
                    return@forEach
                }
                val inlined = inlineImage(entryDir, image.attr(attrName))
                if (inlined != null) image.attr(attrName, inlined) else image.remove()
            }
            body.html()
        }

        val cssJs = buildString {
            for (path in cssJsPaths) {
                val bytes = readEntry(path) ?: continue
                if (path.endsWith(".css", ignoreCase = true)) {
                    append("<style>").append(String(bytes)).append("</style>")
                } else {
                    append("<script>").append(String(bytes)).append("</script>")
                }
            }
        }

        val fullText = cssJs + text
        lastPage = page.url to fullText
        return fullText
    }

    // Resolves a relative EPUB-internal path (which may use "../") against a directory, without
    // ever escaping above the archive root.
    private fun resolveZipPath(baseDir: String, relative: String): String {
        if (relative.startsWith("/")) return resolveZipPath("", relative.removePrefix("/"))
        val parts = ArrayDeque<String>()
        for (segment in (if (baseDir.isEmpty()) relative else "$baseDir/$relative").split("/")) {
            when (segment) {
                "", "." -> {}
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    private fun mimeTypeFor(path: String): String = when (path.substringAfterLast(".", "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "bmp" -> "image/bmp"
        else -> "application/octet-stream"
    }

    // Same 200-instead-of-206 quirk as readEntry, but for the initial directory (EOCD/central
    // directory) request: cyrisia.com's file server sometimes ignores the suffix Range request and
    // returns the whole file with no Content-Range header, so fall back to the body's own length.
    private suspend fun fetchZipDirectory(url: String, zipHeaders: Headers): ZipDirectory {
        val rangeHeaders = zipHeaders.newBuilder().set("Range", "bytes=-$MAX_EOCD_SEARCH").build()
        val response = client.get(url, rangeHeaders)
        val tail = response.body.bytes()
        val total = response.header("Content-Range")?.substringAfterLast("/")?.toLongOrNull() ?: tail.size.toLong()
        return readZipDirectory(tail, total) { range ->
            client.newCall(GET(url, zipHeaders).newBuilder().range(range).build()).execute().body.source()
        }
    }

    // Slices one entry's local header + data out of an already-fully-downloaded archive.
    private fun sliceZipEntry(body: ByteArray, entry: Entry): ByteArray {
        val from = entry.localHeaderOffset.toInt()
        val len = minOf(entry.dataRange.last - entry.dataRange.first + 1, (body.size - from).toLong()).toInt()
        return parseZipEntry(Buffer().write(body, from, len), entry.compressedSize, entry.method).buffer().readByteArray()
    }

    // ======================== DTOs ========================

    @Serializable
    private class BookshelfEntry(
        val name: String,
        val epubs: List<String> = emptyList(),
        val cover: String? = null,
    )

    @Serializable
    private class MetadataDto(
        @SerialName("title_en") val titleEn: String? = null,
        val romaji: String? = null,
        @SerialName("title_ja") val titleJa: String? = null,
        val aliases: String? = null,
        val synopsis: String? = null,
        val genres: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        @SerialName("cover_url") val coverUrl: String? = null,
        @SerialName("publication_status") val publicationStatus: String? = null,
    )

    companion object {
        private const val PAGE_SIZE = 24
        private const val MAX_INLINE_IMAGE_BYTES = 5L * 1024 * 1024
        private const val MAX_TOTAL_INLINE_IMAGE_BYTES = 60L * 1024 * 1024

        private val SKIP_SPINE_ITEM_REGEX = Regex("cover|nav\\.x?html", RegexOption.IGNORE_CASE)
    }
}

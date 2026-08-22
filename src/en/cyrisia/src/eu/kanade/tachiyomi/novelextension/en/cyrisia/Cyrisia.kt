package eu.kanade.tachiyomi.novelextension.en.cyrisia

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.lib.cookieinterceptor.CookieInterceptor
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.setAltTitles
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Cyrisia is a personal/community EPUB library: series are static uploaded volumes rather than
 * per-chapter web content, so each EPUB is treated as one [SChapter] whose "page text" is the
 * concatenation of the volume's own internal chapter documents.
 *
 * Downloading a volume requires a real logged-in session - the download endpoint returns
 * `{"ok":false,"error":"auth_required"}` for anonymous requests even with a valid CSRF token,
 * confirmed live. There is no login flow here; the user must paste their own session cookie
 * (`name=value`, copied from browser DevTools after logging in) into the source preferences.
 * Without it, browsing/search/details still work (those endpoints are public), but opening any
 * chapter will fail.
 */
@Source
abstract class Cyrisia :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val supportsLatest = false

    private val preferences by getPreferencesLazy()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder {
        val cookie = preferences.getString(PREF_SESSION_COOKIE, null)?.split("=", limit = 2)
        if (cookie?.size == 2) {
            addInterceptor(CookieInterceptor(baseUrl.toHttpUrl().host, cookie[0].trim() to cookie[1].trim()))
        }
        return this
    }

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
                thumbnail_url = baseUrl + entry.cover
            }
        }
        return MangasPage(mangas, from + PAGE_SIZE < list.size)
    }

    // ======================== Details + Chapters ========================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series".toHttpUrl().newBuilder().addPathSegment(manga.url).build().toString()

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val seriesName = manga.url

        val updatedManga = if (fetchDetails) {
            val metadataUrl = "$baseUrl/api/metadata".toHttpUrl().newBuilder().addQueryParameter("series", seriesName).build()
            val meta = runCatching { client.get(metadataUrl, headers).parseAs<MetadataDto>() }.getOrNull()
            buildSManga(seriesName, meta)
        } else {
            manga
        }

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

    override suspend fun fetchPageText(page: Page): String {
        authorizeDownload(page.url)

        val epubBytes = client.get(baseUrl + page.url, headers).body.bytes()
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(epubBytes)).use { zis ->
            generateSequence { zis.nextEntry }.forEach { entry ->
                if (!entry.isDirectory) entries[entry.name] = zis.readBytes()
                zis.closeEntry()
            }
        }

        val containerXml = entries["META-INF/container.xml"] ?: throw Exception("Not a valid EPUB: missing container.xml")
        val opfPath = Jsoup.parse(String(containerXml), "", Parser.xmlParser())
            .selectFirst("rootfile")?.attr("full-path")
            ?: throw Exception("Not a valid EPUB: missing OPF rootfile")
        val opfDir = opfPath.substringBeforeLast("/", "")

        val opfDoc = entries[opfPath]?.let { Jsoup.parse(String(it), "", Parser.xmlParser()) }
            ?: throw Exception("Not a valid EPUB: missing OPF package document")

        val hrefById = opfDoc.select("manifest > item").associate { it.attr("id") to it.attr("href") }
        val spineHrefs = opfDoc.select("spine > itemref")
            .mapNotNull { hrefById[it.attr("idref")] }
            .filterNot { href -> SKIP_SPINE_ITEM_REGEX.containsMatchIn(href) }

        return spineHrefs.joinToString("<hr>") { href ->
            val entryPath = if (opfDir.isEmpty()) href else "$opfDir/$href"
            val bytes = entries[entryPath] ?: return@joinToString ""
            val body = Jsoup.parse(String(bytes)).body()
            body.select("img, svg, script, style").remove()
            body.html()
        }
    }

    private suspend fun authorizeDownload(chapterUrl: String) {
        val segments = (baseUrl + chapterUrl).toHttpUrl().pathSegments
        val seriesName = segments.getOrNull(1) ?: return
        val volumeFilename = segments.getOrNull(2) ?: return

        val csrf = runCatching { client.get("$baseUrl/api/account/csrf", headers).parseAs<CsrfDto>().csrf }.getOrNull() ?: return
        val body = ReadRequest(seriesName, volumeFilename).toJsonRequestBody()
        val authHeaders = headers.newBuilder().add("x-csrf-token", csrf).build()
        runCatching { client.post("$baseUrl/api/account/read", authHeaders, body) }
    }

    // ======================== Preferences ========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_SESSION_COOKIE
            title = "Session cookie"
            summary = "Required to open chapters. Log into cyrisia.com in a browser, open DevTools > " +
                "Application/Storage > Cookies, copy the session cookie's name and value (not " +
                "cyrisia_csrf), and enter them here as name=value."
            dialogTitle = title
        }.also(screen::addPreference)
    }

    // ======================== DTOs ========================

    @Serializable
    private class BookshelfEntry(
        val name: String,
        val epubs: List<String> = emptyList(),
        val cover: String = "",
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

    @Serializable
    private class CsrfDto(val csrf: String)

    @Serializable
    private class ReadRequest(
        @SerialName("series_name") val seriesName: String,
        @SerialName("volume_filename") val volumeFilename: String,
    )

    companion object {
        private const val PAGE_SIZE = 24
        private const val PREF_SESSION_COOKIE = "pref_session_cookie"

        private val SKIP_SPINE_ITEM_REGEX = Regex("cover|nav\\.x?html", RegexOption.IGNORE_CASE)
    }
}

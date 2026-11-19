package eu.kanade.tachiyomi.novelextension.en.nyxtranslation

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneOffset

@Source
abstract class NyXTranslation :
    KeiSource(),
    NovelSource {

    override val supportsLatest = false

    // The WordPress.com edge answers OkHttp's HTTP/2 requests with a 403 bot check.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = protocols(listOf(Protocol.HTTP_1_1))

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList())

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    // Every series is linked from the site menu, grouped under its status page.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val section = filters.firstInstanceOrNull<SectionFilter>()?.value
        val terms = query.lowercase().split(WHITESPACE).filter(String::isNotEmpty)
        val mangas = novelList()
            .filter { section == null || it.status == section }
            .filter { entry -> terms.all { entry.title.lowercase().contains(it) } }
            .map { entry ->
                SManga.create().apply {
                    url = entry.slug
                    title = entry.title
                }
            }
        return MangasPage(mangas, false)
    }

    private class NovelEntry(val slug: String, val title: String, val status: Int)

    private suspend fun novelList(): List<NovelEntry> = client.get(baseUrl, Fetch.DOCUMENT).asJsoup().select("#top-menu a").mapNotNull { link ->
        val slug = slugOf(link.attr("href"))?.takeIf { it !in NON_NOVEL_SLUGS } ?: return@mapNotNull null
        val title = link.text().ifEmpty { return@mapNotNull null }
        val status = link.parents().filter { it.normalName() == "li" }
            .firstNotNullOfOrNull { li -> li.selectFirst("> a")?.attr("href")?.let(::slugOf)?.let(SECTIONS::get) }
            ?: SManga.UNKNOWN
        NovelEntry(slug, title, status)
    }.distinctBy { it.slug }

    // Novel pages sit directly under the root, also on the blog's old address.
    private fun slugOf(href: String): String? = sitePath(href)?.split('/')?.singleOrNull()

    private fun sitePath(href: String): String? {
        val url = href.toHttpUrlOrNull() ?: (baseUrl + href).toHttpUrlOrNull() ?: return null
        if (url.host.removePrefix("www.") !in SITE_HOSTS) return null
        return url.pathSegments.filter(String::isNotEmpty).joinToString("/").ifEmpty { null }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/${manga.url}/"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/${chapter.url}/"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = slugOf(url.toString()) ?: return null
        return novelList().firstOrNull { it.slug == slug }?.let { entry ->
            SManga.create().apply {
                this.url = entry.slug
                title = entry.title
            }
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val entries = async { novelList() }
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
        val content = document.selectFirst(".entry-content") ?: throw Exception("Novel page not found")
        content.select("script, style, noscript, .sharedaddy, #jp-post-flair").remove()
        val entry = entries.await().firstOrNull { it.slug == manga.url }

        val details = SManga.create().apply {
            url = manga.url
            title = entry?.title ?: document.selectFirst("h1.entry-title")!!.text()
            thumbnail_url = content.selectFirst("img[src^=http]")?.attr("src")
            status = entry?.status ?: SManga.UNKNOWN
        }
        val chapterList = mutableListOf<SChapter>()
        val seen = mutableSetOf<String>()
        val summary = mutableListOf<String>()
        var volume = ""
        var label = ""
        var inDescription = false
        var inContents = false
        for (block in content.select("p, h1, h2, h3, h4, h5, h6")) {
            if (block.select("p, h1, h2, h3, h4, h5, h6").size > 1) continue
            val text = block.text()
            var hasChapter = false
            for (link in block.select("a[href]")) {
                val path = sitePath(link.attr("href")) ?: continue
                val date = CHAPTER_PATH.matchEntire(path) ?: continue
                val linkText = link.text()
                if (linkText.isEmpty()) continue
                hasChapter = true
                if (!seen.add(path)) continue
                // Generic link texts ("Part 1") are named after the volume and section headings above them.
                val parts = if (GENERIC_CHAPTER_NAME.matches(linkText)) {
                    buildList {
                        if (volume.isNotEmpty() && !label.startsWith(volume, ignoreCase = true)) add(volume)
                        if (label.isNotEmpty()) add(label)
                        if (!label.contains(linkText, ignoreCase = true)) add(linkText)
                    }
                } else {
                    listOf(linkText)
                }
                chapterList += SChapter.create().apply {
                    url = path
                    name = parts.joinToString(" – ")
                    date_upload = LocalDate.of(date.groupValues[1].toInt(), date.groupValues[2].toInt(), date.groupValues[3].toInt())
                        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                }
            }
            if (hasChapter || text.isEmpty()) continue
            when {
                inContents -> if (VOLUME_LABEL.matches(text)) {
                    volume = text.removeSuffix(":").trim()
                    label = ""
                } else if (text.length <= 80 && !text.startsWith("you can read", ignoreCase = true)) {
                    label = text
                }
                INFO_LINE.containsMatchIn(text) -> parseInfo(block, details)
                text.equals("description", ignoreCase = true) -> inDescription = true
                SECTION_END.containsMatchIn(text) -> {
                    inDescription = false
                    inContents = TOC_HEADING.containsMatchIn(text)
                }
                inDescription -> summary += text
            }
        }
        details.description = summary.joinToString("\n\n").ifEmpty { null }
        SMangaUpdate(details, chapterList.asReversed())
    }

    private fun parseInfo(block: Element, manga: SManga) {
        block.html().split(LINE_BREAK).map { Jsoup.parseBodyFragment(it).text() }.forEach { line ->
            val field = INFO_FIELD.matchEntire(line) ?: return@forEach
            val value = field.groupValues[2].trim()
            when (field.groupValues[1].lowercase()) {
                "genre" -> manga.genre = value.split(',').map(String::trim).filter(String::isNotEmpty).joinToString()
                "author" -> manga.author = value
                "artist" -> manga.artist = value.ifEmpty { null }
            }
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val content = client.get("$baseUrl/${page.url}/", Fetch.DOCUMENT).asJsoup().selectFirst(".entry-content")
            ?: throw Exception("Chapter content not found")
        content.select("script, style, iframe, noscript, form, .sharedaddy, #jp-post-flair, .wp-block-spacer, #wordads-inline-marker").remove()
        content.select("p").filter { paragraph ->
            val links = paragraph.select("a")
            (links.isNotEmpty() && links.all { NAVIGATION_LINK.containsMatchIn(it.text()) }) ||
                (!paragraph.hasText() && paragraph.select("img").isEmpty())
        }.forEach { it.remove() }
        if (content.select("img").isEmpty() && content.text().length < 200) throw Exception("Chapter content not found")
        return content.html()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(SectionFilter())

    private class SectionFilter : Filter.Select<String>("Status", arrayOf("All", "Active", "Completed", "On Hold", "Dropped")) {
        val value: Int?
            get() = listOf(null, SManga.ONGOING, SManga.COMPLETED, SManga.ON_HIATUS, SManga.CANCELLED)[state]
    }

    companion object {
        private val SITE_HOSTS = setOf("nyx-translation.com", "nyxtranslation.home.blog")
        private val SECTIONS = mapOf(
            "active" to SManga.ONGOING,
            "completed" to SManga.COMPLETED,
            "on-hold" to SManga.ON_HIATUS,
            "dropped" to SManga.CANCELLED,
        )
        private val NON_NOVEL_SLUGS = setOf("novel-list", "jp-novel", "kr-novel", "patronage", "contact") + SECTIONS.keys
        private val WHITESPACE = Regex("""\s+""")
        private val CHAPTER_PATH = Regex("""^(\d{4})/(\d{2})/(\d{2})/[^/]+$""")
        private val GENERIC_CHAPTER_NAME =
            Regex("""^(?:part\s*\d+|chapter\s*\d+|prologue|epilogue|afterword|illustrations?|interlude|extra)$""", RegexOption.IGNORE_CASE)
        private val VOLUME_LABEL = Regex("""^(?:volume|vol\.?)\s*\d+\s*:?$""", RegexOption.IGNORE_CASE)
        private val NAVIGATION_LINK = Regex("""^(?:<<|«)?\s*(?:previous|next|table of contents?)""", RegexOption.IGNORE_CASE)
        private val INFO_LINE = Regex("""^(?:type|genre|author|artist)\s*:""", RegexOption.IGNORE_CASE)
        private val INFO_FIELD = Regex("""^(type|genre|author|artist)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)
        private val SECTION_END = Regex("""^(?:alternative names?|table of contents?)""", RegexOption.IGNORE_CASE)
        private val TOC_HEADING = Regex("""^table of contents?""", RegexOption.IGNORE_CASE)
        private val LINE_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    }
}

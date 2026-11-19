package eu.kanade.tachiyomi.novelextension.en.machineslicedbread

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
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import kotlin.time.Instant

@Source
abstract class MachineSlicedBread :
    KeiSource(),
    NovelSource {

    override val supportsLatest = false

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList())

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val section = filters.firstInstanceOrNull<SectionFilter>()?.value
        val tokens = query.lowercase().split(WHITESPACE).filter(String::isNotEmpty)
        val mangas = catalogue()
            .filter { section == null || it.section == section }
            .filter { entry -> tokens.all { entry.title.lowercase().contains(it) } }
            .map { entry ->
                SManga.create().apply {
                    url = entry.path
                    title = entry.title
                }
            }
        return MangasPage(mangas, false)
    }

    private class Entry(val path: String, val title: String, val section: String?)

    // The home page lists every series under section headings.
    private suspend fun catalogue(): List<Entry> {
        val content = client.get(baseUrl, Fetch.DOCUMENT).asJsoup().selectFirst(".entry-content") ?: return emptyList()
        var section: String? = null
        return content.select("h2, h3, ul").flatMap { element ->
            if (element.normalName() != "ul") {
                section = sectionOf(element.text()) ?: section
                return@flatMap emptyList()
            }
            element.select("> li > a[href]").mapNotNull { link ->
                val path = sitePath(link.attr("href")) ?: return@mapNotNull null
                link.text().ifEmpty { null }?.let { Entry(path, it, section) }
            }
        }.distinctBy { it.path.canonical() }
    }

    private fun sectionOf(heading: String): String? {
        val text = heading.lowercase()
        return when {
            "hiatus" in text || "dropped" in text -> "hiatus"
            "completed" in text -> "completed"
            "ongoing" in text -> "ongoing"
            "caught up" in text -> "caught-up"
            "original" in text -> "originals"
            else -> null
        }
    }

    // Links mix http, https and www, and a few pages are only reachable by ?page_id.
    private fun sitePath(href: String): String? {
        val url = href.toHttpUrlOrNull() ?: (baseUrl + href).toHttpUrlOrNull() ?: return null
        if (url.host.removePrefix("www.") != baseHost) return null
        val path = url.encodedPath.replace(SLASHES, "/")
        val pageId = url.queryParameter("page_id")
        return when {
            path == "/" && pageId != null -> "/?page_id=$pageId"
            path == "/" || url.query != null -> null
            else -> path
        }
    }

    private fun String.canonical() = trimEnd('/').lowercase()

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = sitePath(url.toString())?.canonical() ?: return null
        return catalogue().firstOrNull { it.path.canonical() == path }?.let { entry ->
            SManga.create().apply {
                this.url = entry.path
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
        val listed = async { catalogue().firstOrNull { it.path.canonical() == manga.url.canonical() } }
        val document = client.get(baseUrl + manga.url, Fetch.DOCUMENT).asJsoup()
        val content = document.selectFirst(".entry-content") ?: throw Exception("Novel page not found")
        content.select("script, style, .sharedaddy, #jp-post-flair").remove()
        content.select("br").forEach { it.replaceWith(TextNode("\n")) }
        val title = document.selectFirst("h1.entry-title")?.text() ?: manga.title

        val seen = mutableSetOf(manga.url.canonical())
        val chapterList = content.select("a[href]").mapNotNull { link ->
            val path = sitePath(link.attr("href"))?.takeIf { !NOT_CHAPTER.containsMatchIn(it) } ?: return@mapNotNull null
            // "(direct link)" follows the link that carries the chapter name.
            val name = link.text().takeIf { it.isNotEmpty() && !DIRECT_LINK.matches(it) }
                ?: link.previousElementSiblings().firstOrNull { it.normalName() == "a" }?.text()
            if (name.isNullOrEmpty() || NOT_CHAPTER.containsMatchIn(name) || !seen.add(path.canonical())) return@mapNotNull null
            SChapter.create().apply {
                url = path
                this.name = name
                CHAPTER_NUMBER.find(name)?.groupValues?.get(1)?.toFloatOrNull()?.let { chapter_number = it }
            }
        }.toMutableList()
        chapterList.lastOrNull()?.let { last -> chapterList += newerChapters(last).filter { seen.add(it.url.canonical()) } }

        // Some series are a single long page instead of a table of contents.
        val singlePage = chapterList.isEmpty() && content.text().length > 8000 &&
            content.select("a[href]").count { !SOCIAL_LINK.containsMatchIn(it.attr("href")) } <= 2
        if (singlePage) {
            chapterList += SChapter.create().apply {
                url = manga.url
                name = title
            }
        }
        if (chapterList.isEmpty()) throw Exception("No chapters found")

        val chapterPaths = chapterList.map { it.url }.toSet()
        val synopsis = mutableListOf<String>()
        if (!singlePage) {
            for (element in content.children().filter { it.normalName() == "p" || it.normalName() == "div" }) {
                val links = element.select("a[href]")
                if (links.any { sitePath(it.attr("href")) in chapterPaths }) break
                if (links.isNotEmpty()) continue
                val line = element.text()
                if (line.isEmpty() || SYNOPSIS_SKIP.containsMatchIn(line)) continue
                if (synopsis.isNotEmpty() && synopsis.sumOf { it.length } + line.length > SUMMARY_LIMIT) break
                synopsis += line
            }
        }

        val details = SManga.create().apply {
            url = manga.url
            this.title = title
            author = content.wholeText().lines().firstNotNullOfOrNull { AUTHOR.matchEntire(it.trim())?.groupValues?.get(1) }
            description = synopsis.joinToString("\n\n").ifEmpty { null }
            thumbnail_url = content.select("img[src]").map { it.absUrl("src") }
                .firstOrNull { it.startsWith("http") && !PLACEHOLDER_COVER.containsMatchIn(it) }
            status = when (listed.await()?.section) {
                "caught-up", "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
        SMangaUpdate(details, chapterList.asReversed())
    }

    // Tables of contents are not always updated, so look for later "<prefix>-<n>" pages through the REST API.
    private suspend fun newerChapters(last: SChapter): List<SChapter> {
        val match = NUMBERED_SLUG.matchEntire(last.url.trim('/').substringAfterLast('/')) ?: return emptyList()
        val prefix = match.groupValues[1]
        val lastNumber = match.groupValues[2].toInt()
        val found = sortedMapOf<Int, SChapter>()
        for (page in 1..MAX_REST_PAGES) {
            val url = "$baseUrl/wp-json/wp/v2/pages".toHttpUrl().newBuilder()
                .addQueryParameter("search", prefix.replace('-', ' '))
                .addQueryParameter("per_page", "100")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("orderby", "date")
                .addQueryParameter("order", "desc")
                .addQueryParameter("_fields", "link,date,title")
                .build()
            val response = client.get(url, Fetch.XHR, ensureSuccess = false)
            if (!response.isSuccessful) {
                response.close()
                break
            }
            val items = response.parseAs<List<WpPageDto>>()
            var reachedListed = false
            for (item in items) {
                val path = sitePath(item.link) ?: continue
                val number = path.trim('/').substringAfterLast('/').removePrefix(prefix)
                    .takeIf { path.trim('/').substringAfterLast('/').startsWith(prefix) }
                    ?.toIntOrNull() ?: continue
                if (number <= lastNumber) {
                    reachedListed = true
                    continue
                }
                found[number] = SChapter.create().apply {
                    this.url = path
                    name = Jsoup.parse(item.title.rendered).text().replace(BEFORE_CHAPTER, "")
                        .replaceFirstChar { it.uppercase() }.ifEmpty { "Chapter $number" }
                    chapter_number = number.toFloat()
                    date_upload = Instant.tryParse(item.date?.let { "${it}Z" })
                }
            }
            if (reachedListed || items.size < 100) break
        }
        return found.values.toList()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String = readChapter(page.url, followRedirect = true)

    private suspend fun readChapter(path: String, followRedirect: Boolean): String {
        val document = client.get(baseUrl + path, Fetch.DOCUMENT).asJsoup()
        if (document.selectFirst("form.post-password-form") != null) {
            throw Exception("This chapter is password protected. See \"What's with the Passwords?\" on the site.")
        }
        val content = document.selectFirst(".entry-content") ?: throw Exception("Chapter content not found")
        content.select(CHAPTER_JUNK).remove()
        content.select("p, div").filter { it.text().let { text -> text.isNotEmpty() && text.length < 60 && CHAPTER_NAVIGATION.matches(text) } }
            .forEach(Element::remove)
        // Some chapter pages only link to where the chapter moved.
        if (content.text().length < 100) {
            val target = content.select("a[href]").mapNotNull { sitePath(it.attr("href")) }
                .firstOrNull { it.canonical() != path.canonical() }
            if (followRedirect && target != null) return readChapter(target, followRedirect = false)
            throw Exception("Chapter content not found")
        }
        return content.html()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(SectionFilter())

    private class SectionFilter : Filter.Select<String>("Section", SECTIONS.map { it.first }.toTypedArray()) {
        val value: String?
            get() = SECTIONS[state].second
    }

    private val baseHost by lazy { baseUrl.toHttpUrl().host.removePrefix("www.") }

    companion object {
        private val SECTIONS = arrayOf(
            "All" to null,
            "Caught up with the author" to "caught-up",
            "Ongoing translations" to "ongoing",
            "Completed translations" to "completed",
            "Hiatus / dropped" to "hiatus",
            "Originals" to "originals",
        )
        private const val SUMMARY_LIMIT = 2500
        private const val MAX_REST_PAGES = 15
        private const val CHAPTER_JUNK = "script, style, noscript, iframe, form, nav, .tap-nav, .sharedaddy, #jp-post-flair, " +
            ".wpcnt, .adsbygoogle, .post-navigation, .nav-links"
        private val WHITESPACE = Regex("""\s+""")
        private val SLASHES = Regex("""/{2,}""")
        private val NOT_CHAPTER = Regex("""glossary|whats-with-the-passwords""", RegexOption.IGNORE_CASE)
        private val DIRECT_LINK = Regex("""^\(?\s*direct link\s*\)?$""", RegexOption.IGNORE_CASE)
        private val SOCIAL_LINK = Regex("""patreon|discord|ko-fi|syosetu|amazon|twitter|x\.com""", RegexOption.IGNORE_CASE)
        private val PLACEHOLDER_COVER = Regex("""notavailable""", RegexOption.IGNORE_CASE)
        private val CHAPTER_NUMBER = Regex("""^(?:chapter\s+)?(\d+(?:\.\d+)?)\b""", RegexOption.IGNORE_CASE)
        private val NUMBERED_SLUG = Regex("""^(.*-)(\d+)$""")
        private val BEFORE_CHAPTER = Regex("""^.*?(?=chapter\s+\d)""", RegexOption.IGNORE_CASE)
        private val AUTHOR = Regex("""^Aut(?:h)?or\s*:\s*(.+?)$""", RegexOption.IGNORE_CASE)
        private val SYNOPSIS_SKIP =
            Regex("""^(?:raw|translat(?:ion|or|ed)|edit(?:or|ed)|proofread|author|autor|chapters?|table of contents|toc|note)\b""", RegexOption.IGNORE_CASE)
        private val CHAPTER_NAVIGATION = Regex(
            """^[\s|‹›«»<>\[\]()\-–—·]*(?:(?:(?:previous|prev|next|back)(?:\s+chapter)?|toc|table of contents|index|top)[\s|‹›«»<>\[\]()\-–—·]*)+$""",
            RegexOption.IGNORE_CASE,
        )
    }
}

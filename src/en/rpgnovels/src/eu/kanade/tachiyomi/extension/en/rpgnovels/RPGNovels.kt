package eu.kanade.tachiyomi.novelextension.en.rpgnovels

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
import org.jsoup.nodes.TextNode

@Source
abstract class RPGNovels :
    KeiSource(),
    NovelSource {

    override val supportsLatest = false

    // The WordPress.com edge answers OkHttp's HTTP/2 requests with a 403 bot check.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = protocols(listOf(Protocol.HTTP_1_1))

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList())

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    // Every series is linked from the "Active Projects" and "Completed Projects" menus.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val status = (filters.firstInstanceOrNull<ProjectFilter>() ?: ProjectFilter()).value
        val terms = query.lowercase().split(WHITESPACE).filter(String::isNotEmpty)
        val mangas = novelList()
            .filter { status == null || it.completed == status }
            .filter { entry -> terms.all { entry.title.lowercase().contains(it) } }
            .map { entry ->
                SManga.create().apply {
                    url = entry.slug
                    title = entry.title
                }
            }
        return MangasPage(mangas, false)
    }

    private class NovelEntry(val slug: String, val title: String, val completed: Boolean)

    private suspend fun novelList(): List<NovelEntry> = client.get(baseUrl, Fetch.DOCUMENT).asJsoup().select("nav li")
        .flatMap { menu ->
            val group = PROJECT_MENU.matchEntire(menu.selectFirst("> a")?.text().orEmpty()) ?: return@flatMap emptyList()
            val completed = group.groupValues[1].equals("completed", ignoreCase = true)
            menu.select("ul a").mapNotNull { link ->
                val slug = slugOf(link.attr("href")) ?: return@mapNotNull null
                link.text().ifEmpty { null }?.let { NovelEntry(slug, it, completed) }
            }
        }
        .distinctBy { it.slug }

    // Pages and posts sit directly under the root, also on the blog's old wordpress.com address.
    private fun slugOf(href: String): String? {
        val url = href.toHttpUrlOrNull() ?: (baseUrl + href).toHttpUrlOrNull() ?: return null
        if (url.host.removePrefix("www.") !in SITE_HOSTS) return null
        return url.pathSegments.filter(String::isNotEmpty).singleOrNull()
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
        content.select("script, style, noscript, form, .wp-block-spacer, #comments, .wp-block-comments, #respond").remove()

        val novels = entries.await()
        val entry = novels.firstOrNull { it.slug == manga.url }
        val novelSlugs = novels.map { it.slug }.toSet()
        val chapterList = content.select("li a").mapNotNull { link ->
            val href = link.attr("href")
            if ('?' in href || '#' in href) return@mapNotNull null
            val slug = slugOf(href)?.takeIf { it != manga.url && it !in novelSlugs } ?: return@mapNotNull null
            SChapter.create().apply {
                url = slug
                name = link.text().ifEmpty { return@mapNotNull null }
            }
        }.distinctBy { it.url }

        val details = SManga.create().apply {
            url = manga.url
            title = document.selectFirst(".wp-block-post-title")?.text()?.ifEmpty { null } ?: entry?.title ?: manga.title
            status = when (entry?.completed) {
                true -> SManga.COMPLETED
                false -> SManga.ONGOING
                null -> SManga.UNKNOWN
            }
            thumbnail_url = content.selectFirst("img[src]")?.absUrl("src")
        }
        // The synopsis and credits come before the first chapter list.
        val firstList = content.selectFirst("ul")
        val summary = mutableListOf<String>()
        for (element in content.select("h1, h2, h3, h4, p, ul")) {
            if (element == firstList) break
            if (element.normalName() == "ul") continue
            element.select("br").forEach { it.replaceWith(TextNode(" ")) }
            val text = element.text()
            if (text.isEmpty()) continue
            AUTHOR_LINE.matchEntire(text)?.let {
                details.author = it.groupValues[1].trim()
                continue
            }
            if (element.normalName() != "p" || SKIPPED_SUMMARY_LINE.containsMatchIn(text)) continue
            if (element.select("a").text() == text) continue
            summary += text
        }
        details.description = summary.joinToString("\n\n").ifEmpty { null }

        SMangaUpdate(details, chapterList.asReversed())
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val content = client.get("$baseUrl/${page.url}/", Fetch.DOCUMENT).asJsoup().selectFirst(".entry-content")
            ?: throw Exception("Chapter content not found")
        content.select(CHAPTER_JUNK).remove()
        content.select("p, div").filter { block ->
            if (block.select("img").isNotEmpty()) return@filter false
            val text = block.text()
            text.isEmpty() || CHAPTER_NAVIGATION.matches(text) || (SUPPORT_NOTE.containsMatchIn(text) && text.length < 400)
        }.forEach { it.remove() }
        if (content.select("img").isEmpty() && content.text().length < 200) throw Exception("Chapter content not found")
        return content.html()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(ProjectFilter())

    private class ProjectFilter : Filter.Select<String>("Projects", PROJECTS.map { it.first }.toTypedArray()) {
        val value: Boolean?
            get() = PROJECTS[state].second
    }

    companion object {
        private val SITE_HOSTS = setOf("rpgnovels.com", "rpgnoob.wordpress.com")
        private val PROJECTS = arrayOf("All" to null, "Active" to false, "Completed" to true)
        private val WHITESPACE = Regex("""\s+""")
        private val PROJECT_MENU = Regex("""^(active|completed) projects$""", RegexOption.IGNORE_CASE)
        private val CHAPTER_NAVIGATION = Regex(
            """^[\s|]*(?:(?:previous|prev|next)(?:\s+chapter)?|toc|table of contents)(?:[\s|]+(?:(?:previous|prev|next)(?:\s+chapter)?|toc|table of contents))*[\s|]*$""",
            RegexOption.IGNORE_CASE,
        )
        private val SUPPORT_NOTE = Regex("""^please consider supporting me""", RegexOption.IGNORE_CASE)
        private val AUTHOR_LINE = Regex("""^author\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
        private val SKIPPED_SUMMARY_LINE = Regex("""^(?:illustrators?\s*:|volume\s+\d+)""", RegexOption.IGNORE_CASE)
        private const val CHAPTER_JUNK =
            "script, style, iframe, noscript, form, hr, .wp-block-spacer, .sharedaddy, #jp-post-flair, #comments, .wp-block-comments, #wordads-inline-marker"
    }
}

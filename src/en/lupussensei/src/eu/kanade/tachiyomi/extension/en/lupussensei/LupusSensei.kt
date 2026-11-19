package eu.kanade.tachiyomi.novelextension.en.lupussensei

import eu.kanade.tachiyomi.source.NovelSource
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
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Safelist

@Source
abstract class LupusSensei :
    KeiSource(),
    NovelSource {

    override val supportsLatest = false

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(withCovers(catalogue()), false)

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    // The blog search only finds chapter posts, so match the series in the menu instead.
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val tokens = query.lowercase().split(WHITESPACE).filter(String::isNotEmpty)
        val matches = catalogue().filter { manga -> tokens.all { manga.title.lowercase().contains(it) } }
        return MangasPage(withCovers(matches), false)
    }

    // Every series is a "_Title" entry in the "Novels" menu, followed by its "__Chapter List" page.
    private suspend fun catalogue(): List<SManga> {
        var title: String? = null
        return client.get(baseUrl, Fetch.DOCUMENT).asJsoup().select("#menu li a").mapNotNull { link ->
            val text = link.text()
            if (link.attr("href") == "#") {
                title = text.takeIf { it.startsWith("_") && !it.startsWith("__") }?.removePrefix("_")?.trim()
                return@mapNotNull null
            }
            val seriesTitle = title?.takeIf { CHAPTER_LIST_ENTRY.containsMatchIn(text) } ?: return@mapNotNull null
            title = null
            val url = link.absUrl("href").toHttpUrlOrNull()?.takeIf { it.host == baseHost } ?: return@mapNotNull null
            SManga.create().apply {
                this.url = url.encodedPath
                this.title = seriesTitle
            }
        }.distinctBy { it.url }
    }

    // The menu has no images; the chapter-list label feed carries a thumbnail per series.
    private suspend fun withCovers(mangas: List<SManga>): List<SManga> {
        if (mangas.isEmpty()) return mangas
        val covers = client.get("$baseUrl/feeds/posts/default/-/Chapterlist?alt=json&max-results=50", Fetch.XHR)
            .parseAs<FeedDto>().feed.entry.orEmpty()
            .mapNotNull { entry ->
                val path = entry.link.firstOrNull { it.rel == "alternate" }?.href?.toHttpUrlOrNull()?.encodedPath
                val thumbnail = entry.thumbnail?.url?.replace(THUMBNAIL_SIZE, "/s640/")
                if (path == null || thumbnail == null) null else path to thumbnail
            }.toMap()
        return mangas.onEach { it.thumbnail_url = covers[it.url] }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseHost) return null
        return catalogue().firstOrNull { it.url == url.encodedPath }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val body = client.get(baseUrl + manga.url, Fetch.DOCUMENT).asJsoup().selectFirst(".post-body")
            ?: throw Exception("Chapter list not found")
        body.select("script, style").remove()
        val details = SManga.create().apply {
            url = manga.url
            title = manga.title
            thumbnail_url = body.select("img[src^=http]").firstOrNull()?.attr("src") ?: manga.thumbnail_url
        }
        return SMangaUpdate(details, parseChapters(body).asReversed())
    }

    // Episodes are grouped under plain-text "Chapter N" headings.
    private fun parseChapters(body: Element): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        val seen = mutableSetOf<String>()
        var group = ""
        fun walk(node: Node) {
            when (node) {
                is TextNode -> CHAPTER_GROUP.find(node.text().trim())?.let { group = it.groupValues[1] }
                is Element -> {
                    if (node.normalName() != "a") {
                        node.childNodes().forEach(::walk)
                        return
                    }
                    val url = chapterUrl(node.absUrl("href")) ?: return
                    val name = node.text()
                    if (name.isEmpty() || NOT_CHAPTER.containsMatchIn(name) || !seen.add(url)) return
                    chapters += SChapter.create().apply {
                        this.url = url
                        this.name = if (group.isNotEmpty() && EPISODE.containsMatchIn(name)) "$group - $name" else name
                        EPISODE_NUMBER.find(name)?.groupValues?.get(1)?.toFloatOrNull()?.let { chapter_number = it }
                    }
                }
            }
        }
        body.childNodes().forEach(::walk)
        return chapters
    }

    // Chapters of the oldest series still live on the translator's earlier blog.
    private fun chapterUrl(href: String): String? {
        val url = href.toHttpUrlOrNull() ?: return null
        if (!POST_PATH.matches(url.encodedPath)) return null
        return when (url.host) {
            baseHost -> url.encodedPath
            OLD_BLOG_HOST -> "https://$OLD_BLOG_HOST${url.encodedPath}"
            else -> null
        }
    }

    override fun getChapterUrl(chapter: SChapter): String = if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, getChapterUrl(chapter)))

    override suspend fun fetchPageText(page: Page): String {
        val body = client.get(page.url, Fetch.DOCUMENT).asJsoup().selectFirst(".post-body") ?: throw Exception("Chapter content not found")
        body.select(".adsbygoogle").remove()
        // Posts are pasted from Word and carry heavy inline styling.
        val content = Jsoup.parseBodyFragment(Jsoup.clean(body.html(), page.url, Safelist.relaxed().preserveRelativeLinks(true))).body()
        content.select("span, font").unwrap()
        content.select("p, div").filter { it.select("img, hr").isEmpty() && !it.hasText() }.forEach(Element::remove)
        if (content.text().length < 100) throw Exception("Chapter content not found")
        return content.html()
    }

    private val baseHost by lazy { baseUrl.toHttpUrl().host }

    companion object {
        private const val OLD_BLOG_HOST = "isekaimynoghra.blogspot.com"
        private val WHITESPACE = Regex("""\s+""")
        private val CHAPTER_LIST_ENTRY = Regex("""^__\s*chapter""", RegexOption.IGNORE_CASE)
        private val THUMBNAIL_SIZE = Regex("""/s\d+(-w\d+-h\d+)?(-c)?/""")
        private val POST_PATH = Regex("""^/\d{4}/\d{2}/[^/]+\.html$""")
        private val NOT_CHAPTER = Regex("""^(?:spoilers?\b|synopsis$|.*\bchapters$)""", RegexOption.IGNORE_CASE)
        private val CHAPTER_GROUP = Regex("""^(Chapter\s+\d+)\b""", RegexOption.IGNORE_CASE)
        private val EPISODE = Regex("""^ep\b""", RegexOption.IGNORE_CASE)
        private val EPISODE_NUMBER = Regex("""^ep\.?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    }
}

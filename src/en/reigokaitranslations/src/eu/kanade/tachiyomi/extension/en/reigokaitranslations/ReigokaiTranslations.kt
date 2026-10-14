package eu.kanade.tachiyomi.novelextension.en.reigokaitranslations

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

@Source
abstract class ReigokaiTranslations :
    KeiSource(),
    NovelSource {

    override val supportsLatest = false

    private val apiUrl get() = "$baseUrl/wp-json/wp/v2"

    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(fetchNovels(), false)

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val term = query.trim().lowercase()
        return MangasPage(fetchNovels().filter { term in it.title.lowercase() }, false)
    }

    private suspend fun fetchNovels(): List<SManga> {
        val host = baseUrl.toHttpUrl().host
        val menu = client.get(baseUrl).asJsoup()
            .select("#primary-menu li:not(.menu-item-has-children) > a[href]")
            .mapNotNull { a ->
                val url = a.absUrl("href").toHttpUrlOrNull()?.takeIf { it.host == host } ?: return@mapNotNull null
                url.postSlug() to a.text()
            }
            .filter { (slug, _) -> slug != PATREON_HUB_SLUG }
            .distinctBy { it.first }

        val url = "$apiUrl/pages".toHttpUrl().newBuilder()
            .addEncodedQueryParameter("slug", menu.joinToString(",") { it.first })
            .addQueryParameter("per_page", "100")
            .addQueryParameter("_fields", PAGE_FIELDS)
            .build()
        val pages = client.get(url).parseAs<List<PageDto>>().associateBy { it.slug }

        return menu.mapNotNull { (slug, label) ->
            pages[slug]?.let { it.toSManga(it.document(), label) }
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.encodedPathSegments.none { it.isNotEmpty() }) return null
        val pageUrl = "$apiUrl/pages".toHttpUrl().newBuilder()
            .addQueryParameter("_fields", PAGE_FIELDS)
            .apply {
                if (CHAPTER_PATH_REGEX.matches(url.encodedPath)) {
                    addQueryParameter("search", url.pathSegments.last { it.isNotEmpty() })
                } else {
                    addEncodedQueryParameter("slug", url.postSlug())
                }
            }
            .build()
        val page = client.get(pageUrl).parseAs<List<PageDto>>().firstOrNull() ?: return null
        return page.toSManga(page.document(), null)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val url = "$apiUrl/pages".toHttpUrl().newBuilder()
            .addEncodedQueryParameter("slug", getMangaUrl(manga).toHttpUrl().postSlug())
            .addQueryParameter("_fields", PAGE_FIELDS)
            .build()
        val page = client.get(url).parseAs<List<PageDto>>().firstOrNull() ?: throw Exception("Novel not found")
        val document = page.document()
        return SMangaUpdate(
            manga = page.toSManga(document, manga.title) ?: manga,
            chapters = if (fetchChapters) withCategoryPosts(page.chapters(document)) else chapters,
        )
    }

    private suspend fun withCategoryPosts(toc: List<SChapter>): List<SChapter> {
        val newest = toc.sortedByDescending { it.url.postDate() }.take(3)
        val category = newest.firstNotNullOfOrNull { fetchCategory(it.url) } ?: return toc.asReversed()
        val since = newest.first().url.postDate()!!.replace('/', '-')

        val known = toc.mapTo(HashSet()) { getChapterUrl(it).toHttpUrl().postSlug() }
        val extra = mutableListOf<SChapter>()
        var page = 1
        do {
            val url = "$apiUrl/posts".toHttpUrl().newBuilder()
                .addQueryParameter("categories", category.toString())
                .addQueryParameter("after", "${since}T00:00:00")
                .addQueryParameter("order", "asc")
                .addQueryParameter("per_page", "100")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("_fields", "date_gmt,link,title")
                .build()
            val response = client.get(url)
            val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 1
            response.parseAs<List<PostDto>>()
                .map { it.toSChapter() }
                .filterTo(extra) { getChapterUrl(it).toHttpUrl().postSlug() !in known }
        } while (page++ < totalPages)

        return (toc + extra).asReversed()
    }

    private suspend fun fetchCategory(chapterPath: String): Int? {
        val url = "$apiUrl/posts".toHttpUrl().newBuilder()
            .addEncodedQueryParameter("slug", "$baseUrl$chapterPath".toHttpUrl().postSlug())
            .addQueryParameter("_fields", "categories")
            .build()
        return client.get(url).parseAs<List<PostCategoriesDto>>()
            .firstOrNull()?.categories?.firstOrNull { it != UNCATEGORIZED_ID }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, getChapterUrl(chapter)))

    override suspend fun fetchPageText(page: Page): String {
        val url = "$apiUrl/posts".toHttpUrl().newBuilder()
            .addEncodedQueryParameter("slug", page.url.toHttpUrl().postSlug())
            .addQueryParameter("_fields", "content")
            .build()
        val content = client.get(url).parseAs<List<PostContentDto>>().firstOrNull()?.content?.rendered
            ?: throw Exception("Chapter not found")
        val document = Jsoup.parseBodyFragment(content, page.url)
        document.select("p:has(a)")
            .filter { p -> p.select("a").all { NAV_LINK_REGEX.matches(it.text()) } }
            .forEach { it.remove() }
        return document.body().html()
    }

    companion object {
        private const val PAGE_FIELDS = "slug,link,title,content"
        private const val PATREON_HUB_SLUG = "donate"
        private const val UNCATEGORIZED_ID = 1

        private val NAV_LINK_REGEX = Regex("""(?i)(previous|next)( chapter)?|index|toc|table of contents""")
    }
}

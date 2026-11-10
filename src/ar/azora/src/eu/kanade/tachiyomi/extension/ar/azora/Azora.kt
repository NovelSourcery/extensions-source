package eu.kanade.tachiyomi.novelextension.ar.azora

import eu.kanade.tachiyomi.network.GET
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
import keiyoushi.utils.SlugPath
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder

@Source
abstract class Azora :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    private val apiUrl = "https://api.azorafly.com"
    private val perPage = 39

    private val mangaPathTemplate = SlugPath("/series/")

    override fun getMangaUrl(manga: SManga): String = mangaPathTemplate.absolute(baseUrl, manga.url)

    private val postIdCache = mutableMapOf<String, Int>()

    private fun buildListRequest(page: Int, tag: String, query: String): Request {
        val term = URLEncoder.encode(query, "UTF-8")
        return GET("$apiUrl/api/posts?page=$page&perPage=$perPage&searchTerm=$term&isNovel=true&tag=$tag", headers)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangasPage(buildListRequest(page, "hot", ""), page)

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangasPage(buildListRequest(page, "new", ""), page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = parseMangasPage(buildListRequest(page, "new", query), page)

    private suspend fun parseMangasPage(request: Request, page: Int): MangasPage {
        val body = client.get(request.url, request.headers).parseAs<PostsResponse>()
        return MangasPage(body.novelPosts.map { it.toSManga() }, body.novelTotalCount > page * perPage)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val response = client.get(url, headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        // Raw body needed for the RSC `post:$R[...]` extraction below, in addition to Jsoup
        // parsing for the HTML fallback path - asJsoup() would consume it without giving the
        // string back.
        val html = response.body.string()
        return parseMangaDetails(html).apply { this.url = mangaPathTemplate.slug(url.encodedPath) }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = mangaPathTemplate.slug(mangaPathTemplate.resolve(manga.url))
        val html = client.get(mangaPathTemplate.absolute(baseUrl, manga.url), headers).body.string()
        val post = extractPost(html)
        post?.let { postIdCache[it.slug] = it.id }

        val updatedManga = if (fetchDetails) parseMangaDetails(html, post).apply { this.url = manga.url } else manga
        val updatedChapters = if (fetchChapters) {
            val postId = post?.id ?: resolvePostId(slug)
            if (postId == null) chapters else fetchChapterList(postId, slug)
        } else {
            chapters
        }
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(html: String, post: PostDto? = extractPost(html)): SManga {
        val doc = Jsoup.parse(html)
        return SManga.create().apply {
            title = post?.postTitle?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("h1[itemProp=name]")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: ""
            thumbnail_url = post?.featuredImage?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("img[itemProp=image]")?.attr("abs:src")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            description = post?.postContent?.takeIf { it.isNotBlank() }
                ?.let { Jsoup.parseBodyFragment(it).text() }
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            genre = post?.genres?.takeIf { it.isNotEmpty() }?.joinToString { it.name }
                ?: doc.select("a[itemProp=genre]").joinToString { it.text() }
            status = when (post?.seriesStatus?.lowercase() ?: "") {
                "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            author = post?.alternativeTitles?.trim()
        }
    }

    private suspend fun resolvePostId(slug: String): Int? {
        postIdCache[slug]?.let { return it }
        val post = runCatching {
            val html = client.get("$baseUrl/series/$slug", headers).body.string()
            extractPost(html)
        }.getOrNull() ?: return null
        postIdCache[slug] = post.id
        return post.id
    }

    private suspend fun fetchChapterList(postId: Int, slug: String): List<SChapter> {
        val response = client.get("$apiUrl/api/chapters?postId=$postId&skip=0&take=all&order=desc", headers)
        // Locked/paid chapters are kept on purpose. Most Azora novels are fully locked, so the old
        // `isAccessible && !isLocked && !isPermanentlyLocked && price <= 0` filter produced an
        // empty list and the app showed "لا يوجد فصول" for those series. The reader page for a
        // locked chapter explains that it is locked instead of returning nothing.
        return response.parseAs<ChaptersResponse>().post.chapters
            .filter { it.slug.isNotBlank() }
            .map { it.toSChapter(slug) }
            .sortedByDescending { it.chapter_number }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.get(baseUrl + page.url, headers).asJsoup()
        val content = doc.selectFirst("div.novel-reader-content")
            ?: return "هذا الفصل مقفل. افتح الفصل من موقع Azora لقراءته."
        return content.select("p")
            .mapNotNull { p -> p.text().takeIf { it.isNotBlank() } }
            .dropWhile { it.all { c -> c == '*' || c.isWhitespace() } || it.startsWith("الفصل ") }
            .joinToString("\n\n")
    }

    // ---------- RSC flight payload decoding ----------
    // The series page is no longer an Astro app: the post metadata now lives in the inline React
    // Server Components payload as `post:$R[89]={id:2004,slug:"…",postTitle:"…",…}`. It is a JS
    // object literal (unquoted keys, `!0` booleans), not JSON, so the few fields we need are read
    // with targeted regexes and the rest of the details come from the rendered HTML.

    private fun extractPost(html: String): PostDto? {
        val marker = html.indexOf("post:${'$'}R[")
        if (marker < 0) return null
        val start = html.indexOf('{', marker)
        if (start < 0) return null
        val payload = html.substring(start, minOf(html.length, start + PAYLOAD_WINDOW))

        val id = ID_FIELD.find(payload)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return PostDto(
            id = id,
            slug = stringField("slug", payload).orEmpty(),
            postTitle = stringField("postTitle", payload).orEmpty(),
            featuredImage = stringField("featuredImage", payload).orEmpty(),
            seriesStatus = stringField("seriesStatus", payload).orEmpty(),
            postContent = stringField("postContent", payload).orEmpty(),
        )
    }

    private fun stringField(name: String, payload: String): String? = Regex("${Regex.escape(name)}:\"((?:[^\"\\\\]|\\\\.)*)\"")
        .find(payload)
        ?.groupValues
        ?.get(1)
        ?.let(::unescapeJsString)

    /** Undoes the `\x3C`-style escapes React uses inside the flight payload strings. */
    private fun unescapeJsString(value: String): String = value
        .replace("\\x3C", "<")
        .replace("\\x3E", ">")
        .replace("\\x26", "&")
        .replace("\\x3c", "<")
        .replace("\\x3e", ">")
        .replace("\\u003C", "<")
        .replace("\\u003E", ">")
        .replace("\\u0026", "&")
        .replace("\\\"", "\"")
        .replace("\\/", "/")
        .replace("\\n", "\n")
        .replace("\\r", "\r")

    private companion object {
        /** Enough to reach the post fields, which sit at the very start of the object. */
        const val PAYLOAD_WINDOW = 8_000

        val ID_FIELD = Regex("""\bid:(\d+)""")
    }
}

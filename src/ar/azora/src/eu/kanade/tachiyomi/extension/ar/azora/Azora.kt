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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLEncoder

@Source
abstract class Azora :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    private val apiUrl = "https://api.azorafly.com"
    private val perPage = 39

    private val mangaPathTemplate = SlugPath("/series/")

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPathTemplate.resolve(manga.url)

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
        // Raw body needed for the <astro-island> regex extraction below, in addition to Jsoup
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
        val html = client.get(baseUrl + mangaPathTemplate.resolve(manga.url), headers).body.string()
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
            title = post?.postTitle
                ?: doc.selectFirst("h1[itemProp=name]")?.text()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: ""
            thumbnail_url = post?.featuredImage
                ?: doc.selectFirst("img[itemProp=image]")?.attr("abs:src")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            description = post?.postContent
                ?.let { Jsoup.parseBodyFragment(it).text() }
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
            genre = post?.genres?.joinToString { it.name }
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
        return response.parseAs<ChaptersResponse>().post.chapters
            .filter { it.isAccessible && !it.isLocked && !it.isPermanentlyLocked && it.price <= 0 }
            .map { it.toSChapter(slug) }
            .sortedByDescending { it.chapter_number }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.get(baseUrl + page.url, headers).asJsoup()
        val content = doc.selectFirst("div.novel-reader-content") ?: return ""
        return content.select("p")
            .mapNotNull { p -> p.text().takeIf { it.isNotBlank() } }
            .dropWhile { it.all { c -> c == '*' || c.isWhitespace() } || it.startsWith("الفصل ") }
            .joinToString("\n\n")
    }

    // ---------- Astro island JSON decoding ----------
    // The site is an Astro app: post metadata for the series page is embedded as an escaped JSON
    // blob in a <astro-island props="..."> tag rather than exposed over the public API, so it has
    // to be extracted from the raw HTML and decoded from Astro's tagged-array wire format.

    private fun extractPost(html: String): PostDto? = extractSeriesPanelProps(html)?.let { props ->
        runCatching {
            val decoded = decodeAstroProps(props)
            (decoded["post"] as? JsonObject)?.parseAs<PostDto>()
        }.getOrNull()
    }

    private val astroIslandRegex = Regex("""<astro-island\b([^>]*)>""")

    private fun extractSeriesPanelProps(html: String): String? {
        for (match in astroIslandRegex.findAll(html)) {
            val attrs = match.groupValues[1]
            if ("&quot;name&quot;:&quot;SeriesChaptersPanelIsland&quot;" in attrs) {
                val props = Regex("""props="([^"]*)"""").find(attrs)?.groupValues?.get(1)
                if (props != null) return Parser.unescapeEntities(props, false)
            }
        }
        return null
    }

    private fun decodeAstroProps(rawProps: String): JsonObject {
        val root = rawProps.parseAs<JsonElement>().jsonObject
        return JsonObject(root.mapValues { (_, value) -> decodeAstroNode(value) })
    }

    private fun decodeAstroNode(node: JsonElement): JsonElement {
        if (node !is JsonArray || node.size < 2) return node
        val tag = node[0].jsonPrimitive.contentOrNull ?: return node
        val payload = node[1]
        return when (tag) {
            "1" -> JsonArray(payload.jsonArray.map { decodeAstroNode(it) })
            else -> when (payload) {
                is JsonObject -> JsonObject(payload.mapValues { (_, value) -> decodeAstroNode(value) })
                is JsonArray -> JsonArray(payload.jsonArray.map { decodeAstroNode(it) })
                else -> payload
            }
        }
    }
}

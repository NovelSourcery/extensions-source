package eu.kanade.tachiyomi.novelextension.ar.azora

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

class Azora :
    HttpSource(),
    NovelSource {

    override val name = "Azora"
    override val baseUrl = "https://azorafly.com"
    override val lang = "ar"
    override val supportsLatest = true
    override val isNovelSource = true
    override val client = network.client

    private val apiUrl = "https://api.azorafly.com"
    private val perPage = 39

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val postIdCache = mutableMapOf<String, Int>()
    private var currentNovelSlug = ""

    override fun popularMangaRequest(page: Int): Request = GET("$apiUrl/api/posts?page=$page&perPage=$perPage&searchTerm=&isNovel=true&tag=hot", headers)

    override fun popularMangaParse(response: Response): MangasPage = parseMangasPage(response)

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiUrl/api/posts?page=$page&perPage=$perPage&searchTerm=&isNovel=true&tag=new", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangasPage(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return GET("$apiUrl/api/posts?page=$page&perPage=$perPage&searchTerm=$q&isNovel=true&tag=new", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangasPage(response)

    private fun parseMangasPage(response: Response): MangasPage {
        val body = json.decodeFromString<PostsResponse>(response.body.string())
        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        return MangasPage(body.novelPosts.map { it.toSManga() }, body.novelTotalCount > page * perPage)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body.string()
        val doc = Jsoup.parse(body)

        val post = extractPost(body)

        if (post != null) {
            postIdCache[post.slug] = post.id
        }

        return SManga.create().apply {
            url = response.request.url.encodedPath
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

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val slug = manga.url.removePrefix("/series/").removeSuffix("/")
        currentNovelSlug = slug
        val postId = resolvePostId(slug) ?: return emptyList()
        val body = client.newCall(GET("$apiUrl/api/chapters?postId=$postId&skip=0&take=all&order=desc", headers))
            .execute()
            .use { it.body.string() }
        return parseChapters(body, slug)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body.string()
        return parseChapters(body, currentNovelSlug.ifEmpty { "/" })
    }

    private fun parseChapters(body: String, slug: String): List<SChapter> = json.decodeFromString<ChaptersResponse>(body).post.chapters
        .filter { it.isAccessible && !it.isLocked && !it.isPermanentlyLocked && it.price <= 0 }
        .map { it.toSChapter(slug) }
        .sortedByDescending { it.chapter_number }

    private fun resolvePostId(slug: String): Int? {
        postIdCache[slug]?.let { return it }
        val post = runCatching {
            val html = client.newCall(GET("$baseUrl/series/$slug", headers)).execute().use { it.body.string() }
            extractPost(html)
        }.getOrNull() ?: return null
        postIdCache[slug] = post.id
        return post.id
    }

    private fun extractPost(body: String): PostDto? = extractSeriesPanelProps(body)?.let { props ->
        runCatching {
            val decoded = decodeAstroProps(props)
            (decoded["post"] as? JsonObject)?.let { json.decodeFromJsonElement<PostDto>(it) }
        }.getOrNull()
    }

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.encodedPath
        return listOf(Page(0, url))
    }

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.newCall(GET("$baseUrl${page.url}", headers)).execute().asJsoup()
        val content = doc.selectFirst("div.novel-reader-content") ?: return ""
        return content.select("p")
            .mapNotNull { text -> text.text().takeIf { it.isNotBlank() } }
            .dropWhile { it.all { c -> c == '*' || c.isWhitespace() } || it.startsWith("الفصل ") }
            .joinToString("\n\n")
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

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
        val root = json.parseToJsonElement(rawProps).jsonObject
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

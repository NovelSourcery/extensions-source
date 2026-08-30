package eu.kanade.tachiyomi.novelextension.all.calibre

import android.app.Application
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.util.Base64

/**
 * baseUrl is user-configured (a personal Calibre content server): the DSL's `source { baseUrl {
 * custom(...) } }` declaration generates the actual `baseUrl` override and its preference entry
 * (backed by [keiyoushi.source.CustomUrlPreferences]) - this class must not override baseUrl
 * itself or declare its own URL preference.
 */
@Source
abstract class Calibre :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val supportsLatest = true

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    private val json = Json { ignoreUnknownKeys = true }

    private val keepTags = setOf(
        "p", "br", "h1", "h2", "h3", "h4", "h5", "h6",
        "i", "b", "em", "strong", "blockquote", "ul", "ol", "li",
    )

    /** [SManga.url] stored as bare book id via [mangaPathTemplate]. */
    private val mangaPathTemplate = SlugPath("/ajax/book/")

    override fun Headers.Builder.configureHeaders(): Headers.Builder {
        val user = preferences.getString(PREF_USER, "").orEmpty()
        if (user.isNotBlank()) {
            val pass = preferences.getString(PREF_PASS, "").orEmpty()
            val token = Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
            add("Authorization", "Basic $token")
        }
        return this
    }

    private fun buildPopularMangaRequest(page: Int): Request = browseRequest(page, "title", "asc")

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = buildPopularMangaRequest(page)
        val response = client.get(request.url, request.headers)
        val result = json.decodeFromString<SearchResponse>(response.body.string())
        val novels = booksMetadata(result.bookIds)
        return MangasPage(novels, result.bookIds.size >= LIMIT)
    }

    private fun buildLatestUpdatesRequest(page: Int): Request = browseRequest(page, "timestamp", "desc")

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = buildLatestUpdatesRequest(page)
        val response = client.get(request.url, request.headers)
        val result = json.decodeFromString<SearchResponse>(response.body.string())
        val novels = booksMetadata(result.bookIds)
        return MangasPage(novels, result.bookIds.size >= LIMIT)
    }

    private fun browseRequest(page: Int, sort: String, order: String): Request {
        val offset = (page - 1) * LIMIT
        return GET(
            "$baseUrl/ajax/search?num=$LIMIT&offset=$offset&sort=$sort&sort_order=$order",
            headers,
        )
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val offset = (page - 1) * LIMIT
        var sort = "title"
        var order = "asc"
        val terms = mutableListOf<String>()
        if (query.isNotBlank()) terms.add(query.trim())

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sort = SORT_VALUES[filter.state]
                is OrderFilter -> order = if (filter.state == 1) "desc" else "asc"
                is FieldFilter -> if (filter.state.isNotBlank()) {
                    terms.add("${filter.field}:\"${filter.state.trim()}\"")
                }
                is DateAddedFilter -> DATE_ADDED_QUERY[filter.state]?.let { terms.add(it) }
                else -> {}
            }
        }

        val calibreQuery = URLEncoder.encode(terms.joinToString(" and "), "UTF-8")
        val response = client.get(
            "$baseUrl/ajax/search?query=$calibreQuery&num=$LIMIT&offset=$offset&sort=$sort&sort_order=$order",
            headers,
        )
        val result = json.decodeFromString<SearchResponse>(response.body.string())
        val novels = booksMetadata(result.bookIds)
        return MangasPage(novels, result.bookIds.size >= LIMIT)
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        OrderFilter(),
        DateAddedFilter(),
        FieldFilter("Tags", "tags"),
        FieldFilter("Author", "authors"),
        FieldFilter("Series", "series"),
        FieldFilter("Publisher", "publisher"),
        FieldFilter("Language", "languages"),
    )

    private class SortFilter : Filter.Select<String>("Sort by", SORT_LABELS)

    private class OrderFilter : Filter.Select<String>("Order", arrayOf("Ascending", "Descending"))

    private class DateAddedFilter : Filter.Select<String>("Date added", DATE_ADDED_LABELS)

    private class FieldFilter(name: String, val field: String) : Filter.Text(name)

    private suspend fun booksMetadata(ids: List<Long>): List<SManga> {
        if (ids.isEmpty()) return emptyList()
        val response = client.get("$baseUrl/ajax/books?ids=${ids.joinToString(",")}", headers).body.string()
        val books = json.decodeFromString<Map<String, BookMetadata>>(response)
        return ids.mapNotNull { id ->
            val book = books[id.toString()] ?: return@mapNotNull null
            SManga.create().apply {
                title = book.title
                url = mangaPathTemplate.slug("/ajax/book/$id")
                thumbnail_url = "$baseUrl/get/cover/$id"
            }
        }
    }

    private fun buildMangaDetailsRequest(manga: SManga): Request = GET(mangaPathTemplate.absolute(baseUrl, manga.url), headers)

    override fun getMangaUrl(manga: SManga): String = mangaPathTemplate.absolute(baseUrl, manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = mangaPathTemplate.slug(url.encodedPath)
        val manga = SManga.create().apply { this.url = path }
        val request = buildMangaDetailsRequest(manga)
        val response = client.get(request.url, request.headers)
        if (!response.isSuccessful) return null
        val id = bookId(mangaPathTemplate.resolve(manga.url))
        val book = json.decodeFromString<BookMetadata>(response.body.string())
        return bookMetadataToManga(id, book).apply { this.url = path }
    }

    private fun bookMetadataToManga(id: String, book: BookMetadata): SManga = SManga.create().apply {
        title = book.title
        thumbnail_url = "$baseUrl/get/cover/$id"
        author = book.authors.joinToString()
        genre = book.tags.joinToString()
        description = book.comments?.let { stripHtml(it) }
        status = SManga.UNKNOWN
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val id = bookId(mangaPathTemplate.resolve(manga.url))
        val request = buildMangaDetailsRequest(manga)
        val response = client.get(request.url, request.headers)
        val book = json.decodeFromString<BookMetadata>(response.body.string())

        val updatedManga = if (fetchDetails) bookMetadataToManga(id, book) else manga

        val updatedChapters = if (fetchChapters) fetchChapterList(id, book) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private suspend fun fetchChapterList(id: String, book: BookMetadata): List<SChapter> {
        val format = (book.formats.firstOrNull() ?: "epub").lowercase()

        val manifest = fetchManifest(id, format) ?: return emptyList()
        val base = "/book-file/$id/$format/${manifest.bookHash.size}/${manifest.bookHash.mtime}/"

        val toc = flattenToc(manifest.toc)
        val entries = if (toc.isNotEmpty()) {
            toc.mapIndexed { i, item -> (item.title ?: "Chapter ${i + 1}") to item.dest!! }
        } else {
            manifest.spine
                .filterNot { it.contains("titlepage", ignoreCase = true) }
                .mapIndexed { i, name -> "Chapter ${i + 1}" to name }
        }

        return entries.mapIndexed { i, (title, dest) ->
            SChapter.create().apply {
                name = title
                url = base + dest
                chapter_number = (i + 1).toFloat()
            }
        }.reversed()
    }

    // The viewer manifest is produced by an async render job; poll until the spine appears.
    private suspend fun fetchManifest(id: String, format: String): BookManifest? {
        val url = "$baseUrl/book-manifest/$id/$format"
        repeat(15) {
            val body = client.get(url, headers).body.string()
            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (obj?.containsKey("spine") == true) {
                return json.decodeFromString<BookManifest>(body)
            }
            delay(500)
        }
        return null
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(baseUrl + chapter.url, headers)
        return listOf(Page(0, response.request.url.encodedPath))
    }

    override suspend fun fetchPageText(page: Page): String {
        var url = page.url
        var response = client.get(baseUrl + url, headers)
        // A cached chapter URL embeds the book file's size/mtime; if the book was re-imported
        // those change and the old URL 404s. Re-resolve via a fresh manifest and retry once.
        if (response.code == 404) {
            response.close()
            url = refreshBookFileUrl(url) ?: return ""
            response = client.get(baseUrl + url, headers)
        }
        val body = response.body.string()
        val tree = runCatching {
            json.decodeFromString<TreeFile>(body).tree
        }.getOrNull() ?: return ""
        // Resource root for this book file, e.g. /book-file/<id>/<fmt>/<size>/<mtime>/
        val resourceRoot = BOOKFILE_ROOT_REGEX.find(url)?.value.orEmpty()
        return renderNode(findBody(tree) ?: tree, resourceRoot)
    }

    private suspend fun refreshBookFileUrl(staleUrl: String): String? {
        val (id, format, name) = BOOKFILE_REGEX.find(staleUrl)?.destructured ?: return null
        val manifest = fetchManifest(id, format) ?: return null
        return "/book-file/$id/$format/${manifest.bookHash.size}/${manifest.bookHash.mtime}/$name"
    }

    private fun findBody(node: TreeNode): TreeNode? {
        if (node.n == "body") return node
        node.c.forEach { child -> findBody(child)?.let { return it } }
        return null
    }

    private fun renderNode(node: TreeNode, resourceRoot: String): String {
        val inner = buildString {
            append(node.x.orEmpty())
            node.c.forEach { child ->
                append(renderNode(child, resourceRoot))
                append(child.l.orEmpty())
            }
        }
        val tag = node.n
        return when {
            tag == null || tag == "body" || tag == "html" -> inner
            tag == "br" -> "<br>"
            tag == "img" -> renderImg(node, resourceRoot)
            tag in keepTags -> "<$tag>$inner</$tag>"
            else -> inner
        }
    }

    private fun renderImg(node: TreeNode, resourceRoot: String): String {
        val src = resolveImageSrc(node.attr("src"), resourceRoot) ?: return ""
        val alt = node.attr("alt").orEmpty()
        return "<img src=\"$src\" alt=\"$alt\">"
    }

    // Calibre inlines most images as data: URIs; external resources use a
    // "<link_uid>|<base64(path)>|" reference that maps onto the book-file tree.
    private fun resolveImageSrc(src: String?, resourceRoot: String): String? {
        if (src.isNullOrBlank()) return null
        if (src.startsWith("data:")) return src
        val encoded = RESOURCE_REF_REGEX.find(src)?.groupValues?.get(1)
        val path = if (encoded != null) {
            runCatching { String(Base64.getDecoder().decode(encoded)) }.getOrNull() ?: return null
        } else {
            src
        }
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        if (resourceRoot.isBlank()) return null
        return baseUrl + resourceRoot + path.trimStart('/')
    }

    private fun TreeNode.attr(name: String): String? = a.firstOrNull {
        it.size >= 2 && (it[0] as? JsonPrimitive)?.contentOrNull == name
    }?.let { (it[1] as? JsonPrimitive)?.contentOrNull }

    private fun flattenToc(toc: TocItem): List<TocItem> {
        val result = mutableListOf<TocItem>()
        fun walk(item: TocItem) {
            if (item.dest != null) result.add(item)
            item.children.forEach(::walk)
        }
        toc.children.forEach(::walk)
        return result
    }

    private fun bookId(path: String): String = BOOK_ID_REGEX.find(path)?.groupValues?.get(1)
        ?: throw Exception("Could not resolve Calibre book id from $path")

    private fun stripHtml(html: String): String = html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_USER
            title = "Username"
            summary = "Optional, only if the server requires login"
            setDefaultValue("")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PASS
            title = "Password"
            summary = "Optional, only if the server requires login"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    @Serializable
    private class SearchResponse(
        @SerialName("book_ids") val bookIds: List<Long> = emptyList(),
        @SerialName("total_num") val totalNum: Int = 0,
    )

    @Serializable
    private class BookMetadata(
        val title: String = "",
        val authors: List<String> = emptyList(),
        val comments: String? = null,
        val tags: List<String> = emptyList(),
        val formats: List<String> = emptyList(),
    )

    @Serializable
    private class BookManifest(
        val spine: List<String> = emptyList(),
        val toc: TocItem = TocItem(),
        @SerialName("book_hash") val bookHash: BookHash = BookHash(),
    )

    @Serializable
    private class BookHash(
        val size: Long = 0,
        val mtime: Long = 0,
    )

    @Serializable
    private class TocItem(
        val title: String? = null,
        val dest: String? = null,
        val children: List<TocItem> = emptyList(),
    )

    @Serializable
    private class TreeFile(
        val tree: TreeNode = TreeNode(),
    )

    @Serializable
    private class TreeNode(
        val n: String? = null,
        // Attribute pairs; entries are [name, value] with an occasional trailing flag.
        val a: List<List<JsonElement>> = emptyList(),
        val x: String? = null,
        val l: String? = null,
        val c: List<TreeNode> = emptyList(),
    )

    companion object {
        private const val LIMIT = 30
        private const val PREF_USER = "calibre_username"
        private const val PREF_PASS = "calibre_password"
        private val BOOK_ID_REGEX = Regex("""/book/(\d+)""")
        private val SORT_LABELS = arrayOf(
            "Title",
            "Date added",
            "Date published",
            "Author",
            "Rating",
            "Last modified",
        )
        private val SORT_VALUES = listOf(
            "title",
            "timestamp",
            "pubdate",
            "authors",
            "rating",
            "last_modified",
        )
        private val DATE_ADDED_LABELS = arrayOf("Any", "Last 7 days", "Last 30 days", "This year")
        private val DATE_ADDED_QUERY = mapOf(
            1 to "date:>=7daysago",
            2 to "date:>=30daysago",
            3 to "date:>=thisyear",
        )
        private val BOOKFILE_REGEX = Regex("""/book-file/(\d+)/([^/]+)/\d+/\d+/(.+)""")
        private val BOOKFILE_ROOT_REGEX = Regex("""^/book-file/\d+/[^/]+/\d+/\d+/""")
        private val RESOURCE_REF_REGEX = Regex("""^[^|]+\|([^|]+)\|""")
    }
}

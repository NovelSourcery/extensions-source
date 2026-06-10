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
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.util.Base64

class Calibre :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "Calibre"
    override val lang = "all"
    override val supportsLatest = true
    override val isNovelSource = true

    private val preferences = Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)

    override val baseUrl: String
        get() {
            val raw = preferences.getString(PREF_URL, "").orEmpty().trim().trimEnd('/')
            return when {
                raw.isEmpty() -> ""
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                else -> "http://$raw"
            }
        }

    private val json = Json { ignoreUnknownKeys = true }

    private val keepTags = setOf(
        "p", "br", "h1", "h2", "h3", "h4", "h5", "h6",
        "i", "b", "em", "strong", "blockquote", "ul", "ol", "li",
    )

    override fun headersBuilder(): Headers.Builder {
        val builder = Headers.Builder().add("Referer", "$baseUrl/")
        val user = preferences.getString(PREF_USER, "").orEmpty()
        if (user.isNotBlank()) {
            val pass = preferences.getString(PREF_PASS, "").orEmpty()
            val token = Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
            builder.add("Authorization", "Basic $token")
        }
        return builder
    }

    override fun popularMangaRequest(page: Int): Request = browseRequest(page, "title", "asc")

    override fun latestUpdatesRequest(page: Int): Request = browseRequest(page, "timestamp", "desc")

    private fun browseRequest(page: Int, sort: String, order: String): Request {
        val offset = (page - 1) * LIMIT
        return GET(
            "$baseUrl/ajax/search?num=$LIMIT&offset=$offset&sort=$sort&sort_order=$order",
            headers,
        )
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val result = json.decodeFromString<SearchResponse>(response.body.string())
        val novels = booksMetadata(result.bookIds)
        return MangasPage(novels, result.bookIds.size >= LIMIT)
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
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
        return GET(
            "$baseUrl/ajax/search?query=$calibreQuery&num=$LIMIT&offset=$offset&sort=$sort&sort_order=$order",
            headers,
        )
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    override fun getFilterList() = FilterList(
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

    private fun booksMetadata(ids: List<Long>): List<SManga> {
        if (ids.isEmpty()) return emptyList()
        val response = client.newCall(
            GET("$baseUrl/ajax/books?ids=${ids.joinToString(",")}", headers),
        ).execute().body.string()
        val books = json.decodeFromString<Map<String, BookMetadata>>(response)
        return ids.mapNotNull { id ->
            val book = books[id.toString()] ?: return@mapNotNull null
            SManga.create().apply {
                title = book.title
                url = "/ajax/book/$id"
                thumbnail_url = "$baseUrl/get/cover/$id"
            }
        }
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val book = json.decodeFromString<BookMetadata>(response.body.string())
        val id = bookId(response.request.url.encodedPath)
        return SManga.create().apply {
            title = book.title
            thumbnail_url = "$baseUrl/get/cover/$id"
            author = book.authors.joinToString()
            genre = book.tags.joinToString()
            description = book.comments?.let { stripHtml(it) }
            status = SManga.UNKNOWN
        }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        val id = bookId(manga.url)
        val bookJson = client.newCall(GET("$baseUrl/ajax/book/$id", headers)).execute().body.string()
        val book = json.decodeFromString<BookMetadata>(bookJson)
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
            val body = client.newCall(GET(url, headers)).execute().body.string()
            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            if (obj?.containsKey("spine") == true) {
                return json.decodeFromString<BookManifest>(body)
            }
            delay(500)
        }
        return null
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.encodedPath))

    override suspend fun fetchPageText(page: Page): String {
        var url = page.url
        var response = client.newCall(GET(baseUrl + url, headers)).execute()
        // A cached chapter URL embeds the book file's size/mtime; if the book was re-imported
        // those change and the old URL 404s. Re-resolve via a fresh manifest and retry once.
        if (response.code == 404) {
            response.close()
            url = refreshBookFileUrl(url) ?: return ""
            response = client.newCall(GET(baseUrl + url, headers)).execute()
        }
        val body = response.body.string()
        val tree = runCatching {
            json.decodeFromString<TreeFile>(body).tree
        }.getOrNull() ?: return ""
        return renderNode(findBody(tree) ?: tree)
    }

    private suspend fun refreshBookFileUrl(staleUrl: String): String? {
        val (id, format, name) = BOOKFILE_REGEX.find(staleUrl)?.destructured ?: return null
        val manifest = fetchManifest(id, format) ?: return null
        return "/book-file/$id/$format/${manifest.bookHash.size}/${manifest.bookHash.mtime}/$name"
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun findBody(node: TreeNode): TreeNode? {
        if (node.n == "body") return node
        node.c.forEach { child -> findBody(child)?.let { return it } }
        return null
    }

    private fun renderNode(node: TreeNode): String {
        val inner = buildString {
            append(node.x.orEmpty())
            node.c.forEach { child ->
                append(renderNode(child))
                append(child.l.orEmpty())
            }
        }
        val tag = node.n
        return when {
            tag == null || tag == "body" || tag == "html" -> inner
            tag == "br" -> "<br>"
            tag in keepTags -> "<$tag>$inner</$tag>"
            else -> inner
        }
    }

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
            key = PREF_URL
            title = "Server URL"
            summary = "e.g. http://192.168.1.10:8080/"
            dialogTitle = "Calibre content server URL"
            setDefaultValue("")
        }.also(screen::addPreference)

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
    private data class SearchResponse(
        @SerialName("book_ids") val bookIds: List<Long> = emptyList(),
        @SerialName("total_num") val totalNum: Int = 0,
    )

    @Serializable
    private data class BookMetadata(
        val title: String = "",
        val authors: List<String> = emptyList(),
        val comments: String? = null,
        val tags: List<String> = emptyList(),
        val formats: List<String> = emptyList(),
    )

    @Serializable
    private data class BookManifest(
        val spine: List<String> = emptyList(),
        val toc: TocItem = TocItem(),
        @SerialName("book_hash") val bookHash: BookHash = BookHash(),
    )

    @Serializable
    private data class BookHash(
        val size: Long = 0,
        val mtime: Long = 0,
    )

    @Serializable
    private data class TocItem(
        val title: String? = null,
        val dest: String? = null,
        val children: List<TocItem> = emptyList(),
    )

    @Serializable
    private data class TreeFile(
        val tree: TreeNode = TreeNode(),
    )

    @Serializable
    private data class TreeNode(
        val n: String? = null,
        val x: String? = null,
        val l: String? = null,
        val c: List<TreeNode> = emptyList(),
    )

    companion object {
        private const val LIMIT = 30
        private const val PREF_URL = "calibre_url"
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
    }
}

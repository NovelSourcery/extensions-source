package eu.kanade.tachiyomi.extension.all.lnori

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class Lnori : HttpSource(), NovelSource {

    override val name = "Lnori"
    override val baseUrl = "https://lnori.qzz.io"
    override val lang = "all"
    override val supportsLatest = true
    override val isNovelSource = true

    override val client = network.cloudflareClient

    private val json: Json by injectLazy()

    // Cache for all novels loaded from homepage
    private var cachedNovels: List<NovelData>? = null
    private var cacheTimestamp: Long = 0
    private val cacheLifetime = 10 * 60 * 1000 // 10 minutes

    @Serializable
    data class NovelData(
        val id: String,
        val title: String,
        val author: String,
        val tags: List<String>,
        val rel: Int, // Relevance/release order
        val date: String,
        val volumes: Int,
        val url: String,
        val coverUrl: String,
        val description: String,
    )

    @Serializable
    data class ChapterInfo(
        val name: String,
        val url: String,
    )

    // ======================== Load All Data ========================

    private fun loadAllNovels(): List<NovelData> {
        val currentTime = System.currentTimeMillis()
        if (cachedNovels != null && currentTime - cacheTimestamp < cacheLifetime) {
            return cachedNovels!!
        }

        val response = client.newCall(GET(baseUrl, headers)).execute()
        val document = Jsoup.parse(response.body.string())

        val novels = document.select("article.card").mapNotNull { card ->
            parseCardToNovelData(card)
        }

        cachedNovels = novels
        cacheTimestamp = currentTime
        return novels
    }

    private fun parseCardToNovelData(card: Element): NovelData? {
        val id = card.attr("data-id").ifEmpty { return null }
        val title = card.attr("data-t").ifEmpty { return null }
        val author = card.attr("data-a")
        val tags = card.attr("data-tags").split(",").filter { it.isNotEmpty() }
        val rel = card.attr("data-rel").toIntOrNull() ?: 0
        val date = card.attr("data-d")
        val volumes = card.attr("data-v").toIntOrNull() ?: 1

        val link = card.selectFirst("a")?.attr("href") ?: "/$id/"
        // Cover URL: try data-src (lazy load), srcset, or src; also handle relative URLs
        val imgElement = card.selectFirst("img")
        val coverUrl = imgElement?.let { img ->
            img.attr("data-src").ifEmpty { null }
                ?: img.attr("srcset").split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
                ?: img.attr("src").ifEmpty { null }
        }?.let { url ->
            when {
                url.startsWith("http") -> url
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> "https://img.lnori.qzz.io${url.removePrefix("/image")}"
                else -> "https://img.lnori.qzz.io/$url"
            }
        } ?: ""
        val description = card.selectFirst("p.card-description")?.text() ?: ""

        return NovelData(
            id = id,
            title = title,
            author = author,
            tags = tags,
            rel = rel,
            date = date,
            volumes = volumes,
            url = link,
            coverUrl = coverUrl,
            description = description,
        )
    }

    private fun novelDataToSManga(novel: NovelData): SManga {
        return SManga.create().apply {
            url = novel.url.let { if (it.startsWith("/")) it else "/$it" }
            title = novel.title.split(" ").joinToString(" ") {
                it.replaceFirstChar { c -> c.uppercaseChar() }
            }
            thumbnail_url = novel.coverUrl
            author = novel.author
            genre = novel.tags.joinToString(", ")
            description = novel.description
        }
    }

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request {
        return GET(baseUrl, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val novels = loadAllNovels()
        // Sort by relevance (rel) - higher is more popular
        val sorted = novels.sortedByDescending { it.rel }
        val mangas = sorted.map { novelDataToSManga(it) }
        return MangasPage(mangas, false) // All loaded at once, no pagination
    }

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request = popularMangaRequest(page)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val novels = loadAllNovels()
        // Sort by date (newest first)
        val sorted = novels.sortedByDescending { it.date }
        val mangas = sorted.map { novelDataToSManga(it) }
        return MangasPage(mangas, false)
    }

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET(baseUrl, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        // This will be overridden by fetchSearchManga
        return MangasPage(emptyList(), false)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): rx.Observable<MangasPage> {
        return rx.Observable.fromCallable {
            var novels = loadAllNovels()

            // Apply search query
            if (query.isNotBlank()) {
                val queryLower = query.lowercase()
                novels = novels.filter { novel ->
                    novel.title.contains(queryLower) ||
                        novel.author.lowercase().contains(queryLower) ||
                        novel.description.lowercase().contains(queryLower)
                }
            }

            // Apply filters
            filters.forEach { filter ->
                when (filter) {
                    is TagFilter -> {
                        if (filter.state.isNotBlank()) {
                            val tags = filter.state.split(",")
                                .map { it.trim().lowercase() }
                                .filter { it.isNotEmpty() }

                            novels = novels.filter { novel ->
                                val novelTags = novel.tags.map { it.lowercase() }
                                tags.all { tag -> novelTags.any { it.contains(tag) } }
                            }
                        }
                    }
                    is AuthorFilter -> {
                        if (filter.state.isNotBlank()) {
                            val authorQuery = filter.state.lowercase()
                            novels = novels.filter { it.author.lowercase().contains(authorQuery) }
                        }
                    }
                    is MinVolumesFilter -> {
                        if (filter.state.isNotBlank()) {
                            val minVols = filter.state.toIntOrNull() ?: 0
                            novels = novels.filter { it.volumes >= minVols }
                        }
                    }
                    is SortFilter -> {
                        novels = when (filter.state) {
                            0 -> novels.sortedByDescending { it.rel } // Popularity
                            1 -> novels.sortedByDescending { it.date } // Newest
                            2 -> novels.sortedBy { it.date } // Oldest
                            3 -> novels.sortedBy { it.title } // A-Z
                            4 -> novels.sortedByDescending { it.title } // Z-A
                            5 -> novels.sortedByDescending { it.volumes } // Most volumes
                            else -> novels
                        }
                    }
                    else -> {}
                }
            }

            val mangas = novels.map { novelDataToSManga(it) }
            MangasPage(mangas, false)
        }
    }

    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())

        return SManga.create().apply {
            // Title from h1.s-title
            title = document.selectFirst("h1.s-title")?.text()?.trim() ?: ""

            // Author from p.author
            author = document.selectFirst("p.author")?.text()?.trim()

            // Cover from hero section - handle multiple image sources
            val imgElement = document.selectFirst("figure.cover-wrap img, figure.cover-wrap picture source, picture source, img.cover")
            thumbnail_url = imgElement?.let { img ->
                img.attr("srcset").split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
                    ?: img.attr("data-src").ifEmpty { null }
                    ?: img.attr("src").ifEmpty { null }
            }?.let { url ->
                when {
                    url.startsWith("http") -> url
                    url.startsWith("//") -> "https:$url"
                    url.startsWith("/") -> "https://img.lnori.qzz.io${url.removePrefix("/image")}"
                    else -> "https://img.lnori.qzz.io/$url"
                }
            }

            // Description
            description = document.selectFirst("p.description")?.text()?.trim()

            // Genres/tags from .tags-box a
            genre = document.select("nav.tags-box a.tag")
                .mapNotNull { it.text()?.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")

            // Status - check volume count or parse page info
            val volumeCount = document.select("div.vol-grid article.card").size
            status = if (volumeCount > 0) SManga.UNKNOWN else SManga.UNKNOWN
        }
    }

    // ======================== Chapters (Volumes) ========================

    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string())
        val chapters = mutableListOf<SChapter>()

        // Get the novel ID from the URL (e.g., /3336/ -> 3336)
        val novelId = response.request.url.pathSegments.firstOrNull { it.isNotEmpty() } ?: ""

        // Each volume is treated as a chapter
        document.select("div.vol-grid article.card").forEachIndexed { index, card ->
            val link = card.selectFirst("figure a, h3.c-title a") ?: return@forEachIndexed
            val volumeNum = card.selectFirst("h3.c-title a")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull() ?: (index + 1)
            val subtitle = card.selectFirst("p.card-sub")?.text()?.trim() ?: ""

            // Get the volume/book ID from the link
            val href = link.attr("href")
            val bookId = href.replace(Regex("[^0-9]"), "").ifEmpty { null }

            chapters.add(
                SChapter.create().apply {
                    // Construct proper URL: /novelId/bookId
                    url = if (bookId != null && novelId.isNotEmpty()) {
                        "/$novelId/$bookId"
                    } else {
                        href.let { h ->
                            when {
                                h.startsWith("http") -> h.removePrefix(baseUrl)
                                h.startsWith("/") -> if (novelId.isNotEmpty() && !h.drop(1).contains("/")) "/$novelId$h" else h
                                else -> if (novelId.isNotEmpty()) "/$novelId/$h" else "/$h"
                            }
                        }
                    }
                    name = if (subtitle.isNotEmpty()) {
                        "Volume $volumeNum - $subtitle"
                    } else {
                        "Volume $volumeNum"
                    }
                    chapter_number = volumeNum.toFloat()
                },
            )
        }

        return chapters
    }

    // ======================== Pages ========================

    override fun pageListRequest(chapter: SChapter): Request {
        val url = if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        return listOf(Page(0, response.request.url.toString()))
    }

    // ======================== Page Text (Novel) ========================

    override suspend fun fetchPageText(page: Page): String {
        val request = GET(page.url, headers)
        val response = client.newCall(request).execute()
        val html = response.body.string()
        val document = Jsoup.parse(html)

        val content = StringBuilder()

        // Try to get chapter structure from JSON-LD data
        val jsonLdScript = document.selectFirst("script#app-data[type='application/ld+json']")
        if (jsonLdScript != null) {
            try {
                val jsonText = jsonLdScript.html()
                // Parse chapters from hasPart array
                val chapterMatches = Regex(""""name"\s*:\s*"([^"]+)"\s*,\s*"url"\s*:\s*"([^"]+)"""")
                    .findAll(jsonText)

                for (match in chapterMatches) {
                    val chapterName = match.groupValues[1]
                    val pageId = match.groupValues[2].removePrefix("#")

                    // Get corresponding section content
                    val section = document.selectFirst("section.chapter#$pageId") ?: continue

                    // Check if it's an image section
                    val images = section.select("picture img")
                    if (images.isNotEmpty() && section.select("p").isEmpty()) {
                        // Image-only section
                        images.forEach { img ->
                            val imgUrl = img.attr("src").let { if (it.startsWith("http")) it else baseUrl + it }
                            content.append("<img src=\"$imgUrl\" alt=\"$chapterName\">\n")
                        }
                    } else {
                        // Text content
                        content.append("<h2>$chapterName</h2>\n")
                        section.select("p").forEach { p ->
                            val text = p.text()?.trim()
                            if (!text.isNullOrEmpty()) {
                                content.append("<p>$text</p>\n")
                            }
                        }
                        // Also include any inline images
                        section.select("picture img").forEach { img ->
                            val imgUrl = img.attr("src").let { if (it.startsWith("http")) it else baseUrl + it }
                            content.append("<img src=\"$imgUrl\">\n")
                        }
                    }
                    content.append("\n<hr>\n\n")
                }
            } catch (e: Exception) {
                // Fallback to simple parsing
            }
        }

        // Fallback: parse all sections directly
        if (content.isEmpty()) {
            document.select("section.chapter").forEach { section ->
                val title = section.selectFirst("h2.chapter-title")?.text()?.trim()
                if (title != null) {
                    content.append("<h2>$title</h2>\n")
                }

                // Get images
                section.select("picture img").forEach { img ->
                    val imgUrl = img.attr("src").let { if (it.startsWith("http")) it else baseUrl + it }
                    content.append("<img src=\"$imgUrl\">\n")
                }

                // Get text
                section.select("p").forEach { p ->
                    val text = p.text()?.trim()
                    if (!text.isNullOrEmpty()) {
                        content.append("<p>$text</p>\n")
                    }
                }

                content.append("\n")
            }
        }

        // Final fallback: get main content
        if (content.isEmpty()) {
            val mainContent = document.selectFirst("main#main-content, main")
            mainContent?.select("p")?.forEach { p ->
                val text = p.text()?.trim()
                if (!text.isNullOrEmpty()) {
                    content.append("<p>$text</p>\n")
                }
            }
        }

        return content.toString()
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // ======================== Filters ========================

    override fun getFilterList(): FilterList {
        return FilterList(
            Filter.Header("Local filters - data loaded from homepage"),
            Filter.Separator(),
            TagFilter("Tags (comma separated)"),
            AuthorFilter("Author"),
            MinVolumesFilter("Minimum Volumes"),
            SortFilter("Sort By", sortOptions),
        )
    }

    class TagFilter(name: String) : Filter.Text(name)
    class AuthorFilter(name: String) : Filter.Text(name)
    class MinVolumesFilter(name: String) : Filter.Text(name)
    class SortFilter(name: String, values: Array<String>) : Filter.Select<String>(name, values)

    private val sortOptions = arrayOf(
        "Popularity",
        "Newest",
        "Oldest",
        "Title A-Z",
        "Title Z-A",
        "Most Volumes",
    )
}

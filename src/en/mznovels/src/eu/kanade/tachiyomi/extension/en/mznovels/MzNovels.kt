package eu.kanade.tachiyomi.novelextension.en.mznovels

import android.app.Application
import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.formattedText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/**
 * MZ Novels source - ported from LN Reader plugin
 * @see https://github.com/LNReader/lnreader-plugins mznovels.ts
 * Features: Ranking filters, author notes settings, AJAX browse
 */
@Source
abstract class MzNovels :
    KeiSource(),
    NovelSource {

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    /**
     * The site's novel detail URL shape, as `/novel/<slug>`. [SManga.url] is stored as the bare
     * slug (see [SlugPath]); a stored value starting with "/" is a pre-existing full-path entry
     * from before this source adopted slug storage, and is resolved unchanged regardless of
     * this template.
     */
    private val mangaPathTemplate: SlugPath = SlugPath("/novel/")

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(if (page.url.startsWith("http")) page.url else baseUrl + page.url, headers)).execute()
        val html = response.body.string()
        val doc = Jsoup.parse(html)
        checkCaptcha(doc)

        val content = doc.selectFirst("div.formatted-content") ?: return ""

        content.select("div.chapter-ad-banner").remove()

        val authorNotesMode = preferences.getString("author_notes", "footnotes") ?: "footnotes"
        val authorNotes = content.select(".author-feedback")

        when (authorNotesMode) {
            "inline" -> {
                // Leave author notes inline (default behavior)
            }

            "footnotes" -> {
                // Move author notes to end as footnotes
                val footnotes = mutableListOf<String>()
                authorNotes.forEachIndexed { index, noteElement ->
                    val noteContent = noteElement.selectFirst(".note_content")?.html() ?: return@forEachIndexed
                    footnotes.add("<div><strong>Note ${index + 1}:</strong> $noteContent</div>")
                    noteElement.html("<sup>[Note ${index + 1}]</sup>")
                }
                if (footnotes.isNotEmpty()) {
                    content.append("<hr><h3>Author Notes</h3>")
                    footnotes.forEach { content.append(it) }
                }
            }

            "none" -> {
                authorNotes.remove()
            }
        }

        return content.html()
    }
    // ======================== Popular/Browse ========================

    private fun buildPopularMangaRequest(page: Int): Request = buildSearchMangaRequest(page, "", getFilterList(null))

    override suspend fun getPopularManga(page: Int): MangasPage = parseSearchResponse(client.newCall(buildPopularMangaRequest(page)).execute())

    private fun buildLatestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest-updates/?page=$page", headers)

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = client.newCall(buildLatestUpdatesRequest(page)).execute()
        return parseNovelList(response, 1) // page is already in URL
    }
    // ======================== Search ========================

    private fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotEmpty()) {
            return GET("$baseUrl/search/?q=$query&page=$page", headers)
        }

        // Ranking filters
        var rankType = "original"
        var rankPeriod = "daily"

        filters.forEach { filter ->
            when (filter) {
                is RankTypeFilter -> rankType = filter.toUriPart()
                is RankPeriodFilter -> rankPeriod = filter.toUriPart()
                else -> {}
            }
        }

        return GET("$baseUrl/rankings/$rankType?period=$rankPeriod&page=$page", headers)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = parseSearchResponse(client.newCall(buildSearchMangaRequest(page, query, filters)).execute())

    private fun parseSearchResponse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())

        // Detect page number from pagination to avoid repeated final pages
        val currentPage = doc.selectFirst("div.pagination > span.active")?.text()?.toIntOrNull() ?: 1
        val requestedPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1

        // MZ Novels repeats the final page if you request beyond max
        if (currentPage != requestedPage) {
            return MangasPage(emptyList(), false)
        }

        return parseNovelList(response, requestedPage)
    }

    private fun parseNovelList(response: Response, pageNo: Int): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        checkCaptcha(doc)

        val novels = doc.select("ul.search-results-list > li.search-result-item:not(.ad-result-item)").mapNotNull { element ->
            val titleElement = element.selectFirst("h2.search-result-title") ?: return@mapNotNull null
            val title = titleElement.text().trim()
            val linkElement = element.selectFirst("a.search-result-title-link") ?: return@mapNotNull null
            val novelUrl = linkElement.attr("href")

            if (novelUrl.isEmpty() || title.isEmpty()) return@mapNotNull null

            val coverUrl = element.selectFirst("img.search-result-image")?.attr("src") ?: ""

            SManga.create().apply {
                this.title = title
                this.url = mangaPathTemplate.slug(novelUrl.removePrefix(baseUrl))
                thumbnail_url = when {
                    coverUrl.isEmpty() -> ""
                    coverUrl.startsWith("http") -> coverUrl
                    coverUrl == "/media/avatars/default.png" -> ""
                    else -> baseUrl + coverUrl
                }
            }
        }

        val hasNextPage = doc.selectFirst(".pagination .next:not(.disabled)") != null
        return MangasPage(novels, hasNextPage)
    }
    // ======================== Novel Details + Chapters ========================

    private fun buildMangaDetailsRequest(manga: SManga): Request = GET(baseUrl + mangaPathTemplate.resolve(manga.url), headers)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.newCall(buildMangaDetailsRequest(manga)).execute()
        val html = response.body.string()

        val updatedManga = if (fetchDetails) parseMangaDetails(Jsoup.parse(html)) else manga
        val updatedChapters = if (fetchChapters) fetchAllChapters(response) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(doc: Document): SManga {
        checkCaptcha(doc)

        return SManga.create().apply {
            title = doc.selectFirst("h1.novel-title")?.text()?.trim() ?: "Untitled"

            thumbnail_url = doc.selectFirst("img#novel-cover-image")?.attr("src")?.let { src ->
                when {
                    src.startsWith("http") -> src
                    src == "/media/avatars/default.png" -> ""
                    else -> baseUrl + src
                }
            } ?: ""

            // Category: Original, Translated, Fanfiction
            val categoryStr = doc.selectFirst("span.category-value")?.text() ?: ""
            val category = when (categoryStr) {
                "Original" -> "original"
                "Translated" -> "translated"
                "Fanfiction" -> "fanfiction"
                else -> null
            }

            // Author
            var authorText = doc.selectFirst("p.novel-author > a")?.text()?.trim() ?: "Unknown"
            if (category == "translated") {
                val origAuthorElement = doc.selectFirst("p:contains(Original Author)")
                if (origAuthorElement != null) {
                    val origAuthor = origAuthorElement.nextElementSibling()?.text()?.trim() ?: "Unknown"
                    val translator = authorText
                    authorText = "$origAuthor (Original) / $translator (Translator)"
                }
            }
            author = authorText

            // Genres + Category + Tags
            val tags = mutableListOf<String>()
            if (category != null) tags.add(category.replaceFirstChar { it.uppercase() })

            doc.select("div.genres-container > a.genre").forEach { tags.add(it.text().trim()) }
            doc.select("div.tags-container > a.tag").forEach { tags.add(it.text().trim()) }
            genre = tags.joinToString(", ")

            // Status
            val statusIndicator = doc.selectFirst("span.status-indicator")
            status = when {
                statusIndicator?.hasClass("completed") == true -> SManga.COMPLETED
                else -> SManga.ONGOING
            }

            // Summary
            description = doc.selectFirst("p.summary-text")?.formattedText()?.trim() ?: "<no description>"

            // Rating (optional)
            val ratingStr = doc.selectFirst("span.rating-score")?.text()
            if (ratingStr != null) {
                description = "Rating: $ratingStr/5\n\n$description"
            }
        }
    }
    // ======================== Chapters ========================

    private fun fetchAllChapters(firstResponse: Response): List<SChapter> {
        val doc = Jsoup.parse(firstResponse.body.string())
        checkCaptcha(doc)

        val chapters = mutableListOf<SChapter>()
        var pageNo = 1

        val lastPageLink = doc.selectFirst("div#chapters .pagination")
            ?.children()
            ?.lastOrNull { it.tagName() == "a" }
            ?.attr("href")
        val maxPage = lastPageLink?.split("=")?.lastOrNull()?.toIntOrNull() ?: 1

        var currentDoc = doc
        while (pageNo <= maxPage) {
            if (pageNo > 1) {
                val pageUrl = "${firstResponse.request.url}?chapters_page=$pageNo"
                val pageResponse = client.newCall(GET(pageUrl, headers)).execute()
                currentDoc = Jsoup.parse(pageResponse.body.string())
            }

            currentDoc.select("ul.chapter-list > li.chapter-item").forEach { element ->
                val chapterLink = element.selectFirst("a.chapter-link") ?: return@forEach
                val chapterTitle = chapterLink.text().trim()
                val chapterUrl = chapterLink.attr("href")

                chapters.add(
                    SChapter.create().apply {
                        name = chapterTitle
                        url = chapterUrl.removePrefix(baseUrl)
                        date_upload = 0L
                    },
                )
            }

            pageNo++
        }

        // Chapter list is newest-first on the site; keep that order and number descending.
        return chapters.mapIndexed { index, chapter ->
            chapter.apply { chapter_number = (chapters.size - index).toFloat() }
        }
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPathTemplate.resolve(manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = mangaPathTemplate.slug(url.encodedPath)
        val tempManga = SManga.create().apply { this.url = slug }
        val response = client.newCall(buildMangaDetailsRequest(tempManga)).execute()
        if (!response.isSuccessful) return null
        return parseMangaDetails(Jsoup.parse(response.body.string())).apply { this.url = slug }
    }

    // ======================== Chapter Content ========================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.newCall(GET(baseUrl + chapter.url, headers)).execute()
        return listOf(Page(0, response.request.url.toString()))
    }

    // ======================== Captcha Detection ========================

    private fun checkCaptcha(doc: Document) {
        if (doc.title().contains("Captcha", ignoreCase = true) ||
            doc.selectFirst("title")?.text()?.contains("Captcha") == true
        ) {
            throw Exception("Captcha error, please open in webview")
        }
    }
    // ======================== Filters ========================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        RankTypeFilter(),
        RankPeriodFilter(),
    )

    private class RankTypeFilter :
        Filter.Select<String>(
            "Ranking Type",
            arrayOf("Original", "Translated", "Fanfiction"),
        ) {
        fun toUriPart(): String = when (state) {
            0 -> "original"
            1 -> "translated"
            2 -> "fanfiction"
            else -> "original"
        }
    }

    private class RankPeriodFilter :
        Filter.Select<String>(
            "Ranking Period",
            arrayOf("Daily", "Weekly", "Monthly"),
        ) {
        fun toUriPart(): String = when (state) {
            0 -> "daily"
            1 -> "weekly"
            2 -> "monthly"
            else -> "daily"
        }
    }
    // Note: Author notes setting stored in SharedPreferences
    // Access via: preferences.getString("author_notes", "footnotes")
    // Possible values: "inline", "footnotes", "none"
}

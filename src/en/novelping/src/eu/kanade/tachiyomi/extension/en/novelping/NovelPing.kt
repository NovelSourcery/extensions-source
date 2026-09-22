package eu.kanade.tachiyomi.novelextension.en.novelping

import eu.kanade.tachiyomi.network.GET
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
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.firstInstance
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * novelping.com — server-rendered novel site.
 *
 * Browse/search hit the `/ajax/search-results` fragment endpoint (advanced=1); details are
 * scraped from `/novel/<slug>`; the full chapter list comes from
 * `/ajax/chapter-archive?novelId=<slug>` in a single request.
 */
@Source
abstract class NovelPing :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true
    override val supportsFilterFetching = true

    private val novelPath: SlugPath = SlugPath("/novel/")

    // ---------------------------------------------------------------------
    // Browse / search
    // ---------------------------------------------------------------------

    private fun buildListingRequest(page: Int, sort: String?): Request {
        val url = "$baseUrl/sort/popular".toHttpUrl().newBuilder().apply {
            if (sort != null) addQueryParameter("sort", sort)
            if (page > 1) addQueryParameter("page", page.toString())
        }.build()
        return GET(url, headers)
    }

    private fun buildBrowseRequest(page: Int, sort: String?): Request {
        val url = "$baseUrl/ajax/search-results".toHttpUrl().newBuilder().apply {
            addQueryParameter("advanced", "1")
            addQueryParameter("page", page.toString())
            if (sort != null) addQueryParameter("sort", sort)
        }.build()
        return GET(url, headers)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = buildListingRequest(page, sort = null)
        return parseBrowse(client.get(request.url, request.headers))
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = buildListingRequest(page, sort = "LASTEST")
        return parseBrowse(client.get(request.url, request.headers))
    }
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/ajax/search-results".toHttpUrl().newBuilder().apply {
            addQueryParameter("advanced", "1")
            addQueryParameter("page", page.toString())

            if (query.isNotBlank()) addQueryParameter("keyword", query)

            filters.firstInstanceOrNull<SortFilter>()?.toUriPart()?.let { addQueryParameter("sort", it) }

            val genres = filters.firstInstanceOrNull<GenreFilter>()?.state.orEmpty()
            genres.forEach { option ->
                when (option.state) {
                    Filter.TriState.STATE_INCLUDE -> addQueryParameter("genres", option.id)
                    Filter.TriState.STATE_EXCLUDE -> addQueryParameter("genres_exclude", option.id)
                    else -> {}
                }
            }
            if (genres.any { it.state != Filter.TriState.STATE_IGNORE }) {
                addQueryParameter("genre_mode", filters.firstInstance<GenreModeFilter>().toUriPart())
            }

            val tags = filters.firstInstanceOrNull<TagFilter>()?.state.orEmpty()
            tags.forEach { option ->
                when (option.state) {
                    Filter.TriState.STATE_INCLUDE -> addQueryParameter("tags", option.id)
                    Filter.TriState.STATE_EXCLUDE -> addQueryParameter("tags_exclude", option.id)
                    else -> {}
                }
            }
            if (tags.any { it.state != Filter.TriState.STATE_IGNORE }) {
                addQueryParameter("tag_mode", filters.firstInstance<TagModeFilter>().toUriPart())
            }

            filters.firstInstanceOrNull<StatusFilter>()?.toUriPart()?.let { addQueryParameter("status", it) }
            filters.firstInstanceOrNull<LanguageFilter>()?.toUriPart()?.let { addQueryParameter("language", it) }

            fun putText(filter: Filter<String>?, key: String) {
                val v = filter?.state?.trim().orEmpty()
                if (v.isNotEmpty()) addQueryParameter(key, v)
            }
            putText(filters.firstInstanceOrNull<StartYearFilter>(), "start_year")
            putText(filters.firstInstanceOrNull<EndYearFilter>(), "end_year")
            putText(filters.firstInstanceOrNull<AuthorFilter>(), "author")
            putText(filters.firstInstanceOrNull<AuthorExcludeFilter>(), "author_exclude")
            putText(filters.firstInstanceOrNull<MinChaptersFilter>(), "min_chapters")
            putText(filters.firstInstanceOrNull<MaxChaptersFilter>(), "max_chapters")
        }.build()

        return parseBrowse(client.get(url, headers))
    }

    private fun parseBrowse(response: Response): MangasPage {
        val doc = response.asJsoup()

        val mangas = doc.select("div.row:has(.novel-title a)").mapNotNull { row ->
            val link = row.selectFirst(".novel-title a") ?: return@mapNotNull null
            val href = link.attr("href")
            if (href.isBlank()) return@mapNotNull null
            val slug = extractNovelSlug(link.attr("abs:href")) ?: return@mapNotNull null

            SManga.create().apply {
                url = slug
                title = link.text().trim()
                thumbnail_url = row.selectFirst("img[src]")?.attr("abs:src")
                author = row.selectFirst(".author")?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
            }
        }.filter { it.title.isNotBlank() }

        return MangasPage(mangas, mangas.size >= 20)
    }

    // ---------------------------------------------------------------------
    // Details
    // ---------------------------------------------------------------------

    private fun buildMangaDetailsRequest(manga: SManga): Request = GET(novelPath.absolute(baseUrl, mangaSlug(manga.url)), headers)

    override fun getMangaUrl(manga: SManga): String = novelPath.absolute(baseUrl, mangaSlug(manga.url))

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val slug = extractNovelSlug(url) ?: return null
        val tempManga = SManga.create().apply { this.url = slug }
        val req = buildMangaDetailsRequest(tempManga)
        val res = client.get(req.url, req.headers, ensureSuccess = false)
        if (!res.isSuccessful) return null
        return parseMangaDetails(res.asJsoup()).apply { this.url = slug }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) {
            val req = buildMangaDetailsRequest(manga)
            parseMangaDetails(client.get(req.url, req.headers).asJsoup())
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters) loadChapterList(manga) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(doc: Document): SManga = SManga.create().apply {
        val main = doc.selectFirst(".col-novel-main") ?: doc

        title = main.selectFirst("h3[itemprop=name]")?.text()?.trim()
            ?: main.selectFirst(".info-holder .title")?.text()?.trim()
            ?: ""

        thumbnail_url = main.selectFirst("meta[itemprop=image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: main.selectFirst("img.novel-cover-loading")?.attr("abs:src")

        author = main.selectFirst("[itemprop=author] meta[itemprop=name]")?.attr("content")
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: main.select(".info-meta li")
                .firstOrNull { it.selectFirst("h3")?.text()?.startsWith("Author", ignoreCase = true) == true }
                ?.selectFirst("a")?.text()?.trim()

        val genreNames = main.select("meta[itemprop=genre]").mapNotNull { m ->
            m.attr("content").substringAfterLast('/').takeIf { it.isNotBlank() }
        }
        val tagNames = main.select(".tag-container .meta-chip").map { it.text().trim() }
        genre = (genreNames + tagNames)
            .map { it.replace('-', ' ').split(' ').joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) } }
            .distinct()
            .joinToString()
            .ifBlank { null }

        status = when (
            main.select(".info-meta li")
                .firstOrNull { it.selectFirst("h3")?.text()?.startsWith("Status", ignoreCase = true) == true }
                ?.selectFirst("a")?.text()?.trim()?.lowercase()
        ) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled", "dropped" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        description = buildDescription(main)
    }

    private fun buildDescription(main: Element): String? {
        val rating = main.selectFirst("#rateVal")?.attr("value")?.takeIf { it.isNotBlank() }

        val stats = main.select(".rate-info span, .rate-info div")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .filter { it.contains("view", ignoreCase = true) || it.contains("chapter", ignoreCase = true) }
            .distinct()

        val header = buildList {
            if (rating != null) add("Rating: $rating/10")
            addAll(stats)
        }.joinToString("\n")

        val synopsis = sequenceOf(
            ".desc-text",
            "[itemprop=description]",
            ".desc .desc-text",
        ).mapNotNull { selector ->
            main.selectFirst(selector)?.let(::descriptionText)
        }.firstOrNull { it.isNotBlank() }.orEmpty()

        return listOf(header, synopsis)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .ifBlank { null }
    }

    private fun descriptionText(element: Element): String {
        val paragraphs = element.select("p")
            .map { it.text() }
            .filter { it.isNotBlank() }

        return if (paragraphs.isNotEmpty()) {
            paragraphs.joinToString("\n\n")
        } else {
            element.text()
        }
    }

    // ---------------------------------------------------------------------
    // Chapters
    // ---------------------------------------------------------------------

    private suspend fun loadChapterList(manga: SManga): List<SChapter> {
        val slug = mangaSlug(manga.url)

        val doc = client.get("$baseUrl/ajax/chapter-archive?novelId=$slug", headers).asJsoup()

        return doc.select("li[data-chapter-item]").mapNotNull { li ->
            val link = li.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = li.selectFirst(".chapter-title")?.text()?.trim()
                ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            SChapter.create().apply {
                url = href.removePrefix(baseUrl).ifEmpty { href }
                this.name = name
                chapter_number = CHAPTER_NUM.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            }
        }.reversed()
    }

    private fun mangaSlug(storedUrl: String): String = extractNovelSlug(storedUrl)?.let { it } ?: storedUrl.trim('/')

    private fun extractNovelSlug(url: HttpUrl): String? {
        val segments = url.pathSegments
        val pathIndex = segments.indexOfFirst { it == "book" || it == "novel" }
        return segments.getOrNull(pathIndex + 1)?.takeIf { it.isNotBlank() }
    }

    private fun extractNovelSlug(url: String): String? {
        val parsedUrl = runCatching {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                url.toHttpUrl()
            } else {
                "$baseUrl/${url.trimStart('/')}".toHttpUrl()
            }
        }.getOrNull() ?: return null
        return extractNovelSlug(parsedUrl)
    }

    // ---------------------------------------------------------------------
    // Chapter content
    // ---------------------------------------------------------------------

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val url = if (page.url.startsWith("http")) page.url else baseUrl + page.url
        val doc = client.get(url, headers).asJsoup()

        val content = doc.selectFirst(
            "#chr-content, #chapter-content, .chapter-content, .chr-c, .chapter-text, .reading-content",
        ) ?: return ""

        content.select(".js-ad-slot, [data-ad-slot]").remove()
        content.select(".ad-slot, .advertisement, .ads").remove()
        content.select("script, style").remove()

        return content.html().trim()
    }

    // ---------------------------------------------------------------------
    // Filters
    // ---------------------------------------------------------------------

    override suspend fun fetchFilterData(): JsonElement {
        val doc = client.get("$baseUrl/search?advanced=1", headers).asJsoup()

        fun picker(name: String): List<FilterOption> {
            val root = doc.selectFirst("[data-picker-name=$name]") ?: return emptyList()
            return root.select("input[type=checkbox]").mapNotNull { input ->
                val value = input.attr("value").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = input.parent()?.selectFirst("span")?.text()?.trim() ?: value
                FilterOption(label = label, id = value)
            }
        }

        return FilterData(
            genres = picker("genres"),
            tags = picker("tags"),
        ).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.let { runCatching { it.parseAs<FilterData>() }.getOrNull() }
            ?: FilterData()

        return FilterList(
            SortFilter(),
            Filter.Separator(),
            GenreModeFilter(),
            GenreFilter(filterData.genres.map { GenreOption(it.label, it.id) }),
            Filter.Separator(),
            TagModeFilter(),
            TagFilter(filterData.tags.map { TagOption(it.label, it.id) }),
            Filter.Separator(),
            StatusFilter(),
            LanguageFilter(),
            StartYearFilter(),
            EndYearFilter(),
            AuthorFilter(),
            AuthorExcludeFilter(),
            MinChaptersFilter(),
            MaxChaptersFilter(),
        )
    }

    private inline fun <reified T : Filter<*>> FilterList.firstInstanceOrNull(): T? = find { it is T } as? T

    companion object {
        private val CHAPTER_NUM = Regex("""Chapter\s+([\d.]+)""", RegexOption.IGNORE_CASE)
    }
}

package eu.kanade.tachiyomi.novelextension.en.asiannovel

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.injectLazy

@Source
abstract class AsianNovel :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    private val json: Json by injectLazy()

    /** [SManga.url] stored as bare slug under "/story/"; a stored value starting with "/" is a
     * pre-existing full-path entry and is resolved unchanged. */
    private val mangaPath = SlugPath("/story/")

    // ======================== Popular ========================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val pageStr = if (page > 1) "page/$page/" else ""
        val response = client.get("$baseUrl/$pageStr?s=&post_type=fcn_story&orderby=comment_count&order=desc", headers)
        return parseSearchResults(response.asJsoup())
    }

    // ======================== Latest ========================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val pageStr = if (page > 1) "page/$page/" else ""
        val response = client.get("$baseUrl/$pageStr?s=&post_type=fcn_story&orderby=modified&order=desc", headers)
        return parseSearchResults(response.asJsoup())
    }

    // ======================== Search ========================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = buildString {
            if (page > 1) {
                append("$baseUrl/page/$page/?")
            } else {
                append("$baseUrl/?")
            }

            append("s=${java.net.URLEncoder.encode(query, "UTF-8")}")
            append("&post_type=fcn_story") // Search only stories

            var sortBy = "modified"
            var sortOrder = "desc"
            val genres = mutableListOf<Int>()
            val excludeGenres = mutableListOf<Int>()
            val tags = mutableListOf<Int>()
            val excludeTags = mutableListOf<Int>()

            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> {
                        sortBy = sortOptions[filter.state].second
                    }

                    is OrderFilter -> {
                        sortOrder = orderOptions[filter.state].second
                    }

                    is AgeRatingFilter -> {
                        if (filter.state > 0) {
                            append("&age_rating=${ageRatingOptions[filter.state].second}")
                        }
                    }

                    is StatusFilter -> {
                        if (filter.state > 0) {
                            append("&story_status=${statusOptions[filter.state].second}")
                        }
                    }

                    is MinWordsFilter -> {
                        if (filter.state > 0) {
                            append("&miw=${minWordOptions[filter.state].second}")
                        }
                    }

                    is MaxWordsFilter -> {
                        if (filter.state > 0) {
                            append("&maw=${maxWordOptions[filter.state].second}")
                        }
                    }

                    is GenreFilter -> {
                        filter.state.forEachIndexed { index, triState ->
                            when (triState.state) {
                                Filter.TriState.STATE_INCLUDE -> genres.add(genreList[index].second)
                                Filter.TriState.STATE_EXCLUDE -> excludeGenres.add(genreList[index].second)
                            }
                        }
                    }

                    is TagFilter -> {
                        filter.state.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { tagName ->
                            tagList.find { it.first.equals(tagName, ignoreCase = true) }?.let { tag ->
                                tags.add(tag.second)
                            }
                        }
                    }

                    is ExcludeTagFilter -> {
                        filter.state.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { tagName ->
                            tagList.find { it.first.equals(tagName, ignoreCase = true) }?.let { tag ->
                                excludeTags.add(tag.second)
                            }
                        }
                    }

                    is AuthorFilter -> {
                        if (filter.state.isNotBlank()) {
                            append("&author_name=${java.net.URLEncoder.encode(filter.state, "UTF-8")}")
                        }
                    }

                    else -> {}
                }
            }

            append("&orderby=$sortBy&order=$sortOrder")

            if (genres.isNotEmpty()) {
                append("&genres=${genres.joinToString(",")}")
            } else {
                append("&genres=")
            }

            if (tags.isNotEmpty()) {
                append("&tags=${tags.joinToString(",")}")
            } else {
                append("&tags=")
            }

            if (excludeGenres.isNotEmpty()) {
                append("&ex_genres=${excludeGenres.joinToString(",")}")
            } else {
                append("&ex_genres=")
            }

            if (excludeTags.isNotEmpty()) {
                append("&ex_tags=${excludeTags.joinToString(",")}")
            } else {
                append("&ex_tags=")
            }
        }

        val response = client.get(url, headers)
        return parseSearchResults(response.asJsoup())
    }

    // ======================== Details + Chapters ========================

    private fun buildMangaDetailsRequest(manga: SManga): Request = GET(baseUrl + mangaPath.resolve(manga.url), headers)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Details and the chapter list both live on the same story page - fetch it once.
        val request = buildMangaDetailsRequest(manga)
        val response = client.get(request.url, request.headers)
        val document = response.asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(document, response) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(document) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document, response: Response): SManga {
        val jsonLd = document.selectFirst("script[type=application/ld+json]:contains(Book)")?.data()
        if (jsonLd != null) {
            try {
                val schema = json.decodeFromString<SchemaBook>(jsonLd)
                return SManga.create().apply {
                    url = mangaPath.slug(response.request.url.encodedPath)
                    title = schema.name
                    thumbnail_url = schema.image?.firstOrNull()
                    author = schema.author?.name
                    description = schema.description
                    genre = schema.genre?.joinToString()
                    status = SManga.UNKNOWN
                }
            } catch (_: Exception) {}
        }

        return SManga.create().apply {
            url = mangaPath.slug(response.request.url.encodedPath)

            title = document.selectFirst("h1.story__identity-title")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: ""

            thumbnail_url = document.selectFirst(".story__thumbnail img")?.absUrl("src")

            author = document.selectFirst(".story__identity-meta .author")?.text()

            description = document.selectFirst(".story__summary p")?.text()

            genre = document.select(".story__taxonomies .tag-pill").map { it.text() }.joinToString()

            val statusText = document.selectFirst(".story__meta .story__status")?.text()?.lowercase() ?: ""
            status = when {
                statusText.contains("ongoing") -> SManga.ONGOING
                statusText.contains("completed") -> SManga.COMPLETED
                statusText.contains("hiatus") -> SManga.ON_HIATUS
                statusText.contains("canceled") -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    private fun parseChapterList(document: Document): List<SChapter> {
        val chapters = document.select(".chapter-group__list-item a").mapNotNull { element ->
            val href = element.attr("href")
            if (href.isBlank() || !href.contains("/chapter/")) return@mapNotNull null

            SChapter.create().apply {
                url = java.net.URL(href).path
                name = element.text()

                val dateText = element.parent()?.selectFirst("time")?.text() ?: ""
                date_upload = parseDateString(dateText)
            }
        }

        return chapters.reversed()
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPath.resolve(manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val manga = SManga.create().apply { this.url = mangaPath.slug(url.encodedPath) }
        val request = buildMangaDetailsRequest(manga)
        val response = client.get(request.url, request.headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        val document = response.asJsoup()
        return parseMangaDetails(document, response)
    }

    // ======================== Pages ========================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(baseUrl + chapter.url, headers)
        return listOf(Page(0, response.request.url.toString()))
    }

    // ======================== Page Text (Novel) ========================

    override suspend fun fetchPageText(page: Page): String {
        val response = client.get(if (page.url.startsWith("http")) page.url else baseUrl + page.url, headers)
        val document = response.asJsoup()

        val contentSection = document.selectFirst("#chapter-content, .chapter__content")

        return buildString {
            contentSection?.let { section ->
                val wrapper = section.selectFirst(".resize-font, .chapter-formatting") ?: section

                wrapper.children().forEach { element ->
                    if (element.hasClass("adsbygoogle") || element.attr("id").contains("ad", ignoreCase = true) ||
                        element.tagName() == "script" ||
                        element.tagName() == "ins"
                    ) {
                        return@forEach
                    }

                    when (element.tagName()) {
                        "p" -> {
                            val text = element.text()
                            if (!text.isNullOrEmpty()) {
                                append("<p>$text</p>\n")
                            }
                        }

                        "h1", "h2", "h3" -> {
                            append("<h3>${element.text()}</h3>\n")
                        }

                        "img" -> {
                            val src = element.absUrl("src")
                            if (src.isNotEmpty()) {
                                append("<img src=\"$src\">\n")
                            }
                        }
                    }
                }
            }
        }
    }

    // ======================== Filters ========================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter("Sort By", sortOptions.map { it.first }.toTypedArray()),
        OrderFilter("Order", orderOptions.map { it.first }.toTypedArray()),
        Filter.Separator(),
        AgeRatingFilter("Age Rating", ageRatingOptions.map { it.first }.toTypedArray()),
        StatusFilter("Status", statusOptions.map { it.first }.toTypedArray()),
        Filter.Separator(),
        MinWordsFilter("Min Words", minWordOptions.map { it.first }.toTypedArray()),
        MaxWordsFilter("Max Words", maxWordOptions.map { it.first }.toTypedArray()),
        Filter.Separator(),
        Filter.Header("Genres (tap to include, tap again to exclude)"),
        GenreFilter("Genres", genreList.map { it.first }),
        Filter.Separator(),
        Filter.Header("Tags (comma-separated names)"),
        TagFilter("Include Tags"),
        ExcludeTagFilter("Exclude Tags"),
        Filter.Separator(),
        AuthorFilter("Author Name"),
    )

    class SortFilter(name: String, values: Array<String>) : Filter.Select<String>(name, values)
    class OrderFilter(name: String, values: Array<String>) : Filter.Select<String>(name, values)
    class AgeRatingFilter(name: String, values: Array<String>) : Filter.Select<String>(name, values)
    class StatusFilter(name: String, values: Array<String>) : Filter.Select<String>(name, values)
    class MinWordsFilter(name: String, values: Array<String>) : Filter.Select<String>(name, values)
    class MaxWordsFilter(name: String, values: Array<String>) : Filter.Select<String>(name, values)
    class TagFilter(name: String) : Filter.Text(name)
    class ExcludeTagFilter(name: String) : Filter.Text(name)
    class AuthorFilter(name: String) : Filter.Text(name)

    class GenreFilter(name: String, genres: List<String>) :
        Filter.Group<Filter.TriState>(
            name,
            genres.map { GenreTriState(it) },
        )
    class GenreTriState(name: String) : Filter.TriState(name)

    private val sortOptions = listOf(
        Pair("Relevance", "relevance"),
        Pair("Published", "date"),
        Pair("Updated", "modified"),
        Pair("Title", "title"),
        Pair("Comments", "comment_count"),
    )

    private val orderOptions = listOf(
        Pair("Descending", "desc"),
        Pair("Ascending", "asc"),
    )

    private val ageRatingOptions = listOf(
        Pair("Any", "Any"),
        Pair("Everyone", "Everyone"),
        Pair("Teen", "Teen"),
        Pair("Mature", "Mature"),
        Pair("Adult", "Adult"),
    )

    private val statusOptions = listOf(
        Pair("Any", "Any"),
        Pair("Completed", "Completed"),
        Pair("Ongoing", "Ongoing"),
        Pair("Oneshot", "Oneshot"),
        Pair("Hiatus", "Hiatus"),
        Pair("Canceled", "Canceled"),
    )

    private val minWordOptions = listOf(
        Pair("Minimum", "0"),
        Pair("1,000 Words", "1000"),
        Pair("5,000 Words", "5000"),
        Pair("10,000 Words", "10000"),
        Pair("25,000 Words", "25000"),
        Pair("50,000 Words", "50000"),
        Pair("100,000 Words", "100000"),
        Pair("250,000 Words", "250000"),
        Pair("500,000 Words", "500000"),
        Pair("1,000,000 Words", "1000000"),
    )

    private val maxWordOptions = listOf(
        Pair("Maximum", "0"),
        Pair("1,000 Words", "1000"),
        Pair("5,000 Words", "5000"),
        Pair("10,000 Words", "10000"),
        Pair("25,000 Words", "25000"),
        Pair("50,000 Words", "50000"),
        Pair("100,000 Words", "100000"),
        Pair("250,000 Words", "250000"),
        Pair("500,000 Words", "500000"),
        Pair("1,000,000 Words", "1000000"),
    )

    private val genreList = listOf(
        Pair("Action", 7),
        Pair("Adult", 13),
        Pair("Adventure", 16),
        Pair("BL", 34),
        Pair("Comedy", 9),
        Pair("Drama", 11),
        Pair("Ecchi", 30),
        Pair("Fantasy", 6),
        Pair("Gender Bender", 20),
        Pair("GL&Lesbian", 35),
        Pair("Harem", 19),
        Pair("Historical", 17),
        Pair("Horror", 21),
        Pair("Josei", 31),
        Pair("Martial Arts", 33),
        Pair("Mature", 32),
        Pair("Mecha", 22),
        Pair("Mystery", 18),
        Pair("Psychological", 23),
        Pair("Romance", 8),
        Pair("School Life", 24),
        Pair("Sci-fi", 25),
        Pair("Seinen", 804),
        Pair("Shoujo Ai", 806),
        Pair("Shounen", 809),
        Pair("Shounen Ai", 810),
        Pair("Slice of Life", 26),
        Pair("Smut", 27),
        Pair("Sports", 28),
        Pair("Supernatural", 14),
        Pair("Tragedy", 29),
        Pair("Wuxia", 12),
        Pair("Xianxia", 10),
        Pair("Xuanhuan", 15),
        Pair("Yaoi", 807),
        Pair("Yuri", 808),
    )

    private val tagList = listOf(
        Pair("Academy", 41),
        Pair("Alchemy", 63),
        Pair("Apocalypse", 83),
        Pair("Cultivation", 205),
        Pair("Dragons", 252),
        Pair("Demons", 227),
        Pair("Fantasy World", 301),
        Pair("Female Protagonist", 311),
        Pair("Harem", 19),
        Pair("Magic", 449),
        Pair("Male Protagonist", 456),
        Pair("Modern Day", 486),
        Pair("Overpowered Protagonist", 546),
        Pair("Reincarnation", 618),
        Pair("Romance", 8),
        Pair("System", 734),
        Pair("Time Travel", 748),
        Pair("Transmigration", 755),
        Pair("Virtual Reality", 780),
        Pair("Weak to Strong", 788),
    )
    // ======================== Helpers ========================

    private fun parseSearchResults(document: Document): MangasPage {
        val novels = document.select(".card__body").mapNotNull { card ->
            val titleElement = card.selectFirst(".card__title a") ?: return@mapNotNull null

            SManga.create().apply {
                url = mangaPath.slug(java.net.URL(titleElement.absUrl("href")).path)
                title = titleElement.text()
                thumbnail_url = card.selectFirst(".card__image img")?.absUrl("src")
                author = card.selectFirst(".author")?.text()

                val statusText = card.selectFirst(".card__footer-status")?.text()?.lowercase() ?: ""
                status = when {
                    statusText.contains("ongoing") -> SManga.ONGOING
                    statusText.contains("completed") -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }

                genre = card.select(".card__tag-list .tag-pill").map { it.text() }.joinToString()
            }
        }

        val hasNextPage = document.selectFirst(".pagination .next.page-numbers") != null

        return MangasPage(novels, hasNextPage)
    }

    private fun parseDateString(dateStr: String): Long = try {
        val cleanDate = dateStr.trim()
        when {
            cleanDate.matches(Regex("\\d{2}/\\d{2}/\\d{4}")) -> {
                val parts = cleanDate.split("/")
                val month = parts[0].toInt()
                val day = parts[1].toInt()
                val year = parts[2].toInt()
                java.util.Calendar.getInstance().apply {
                    set(year, month - 1, day)
                }.timeInMillis
            }

            else -> 0L
        }
    } catch (_: Exception) {
        0L
    }

    // ======================== Data Classes ========================

    @Serializable
    class SchemaBook(
        val name: String,
        val description: String? = null,
        val author: SchemaAuthor? = null,
        val image: List<String>? = null,
        val genre: List<String>? = null,
    )

    @Serializable
    class SchemaAuthor(
        val name: String,
    )
}

package eu.kanade.tachiyomi.novelextension.en.honeyfeed

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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

@Source
abstract class Honeyfeed :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .add("Accept", "text/html, application/xhtml+xml")
        .add("Turbolinks-Referrer", baseUrl)

    private val logoUrl = "https://www.honeyfeed.fm/assets/main/pages/home/logo-honey-bomon-70595250eae88d365db99bd83ecdc51c917f32478fa535a6b3b6cffb9357c1b4.png"

    /** [SManga.url] is stored as the bare id under `/novels/`; a stored value starting with
     * "/" is a pre-existing full-path entry and is resolved unchanged. */
    private val mangaPath = SlugPath("/novels/")

    // region Popular

    private fun buildPopularMangaRequest(page: Int): Request = GET("$baseUrl/ranking/monthly?page=$page", headers)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val doc = client.newCall(buildPopularMangaRequest(page)).execute().asJsoup()
        val mangas = parseNovelList(doc)
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    // endregion

    // region Latest

    private fun buildLatestUpdatesRequest(page: Int): Request = GET("$baseUrl/novels?page=$page", headers)

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val doc = client.newCall(buildLatestUpdatesRequest(page)).execute().asJsoup()
        val mangas = parseNovelList(doc)
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    // endregion

    // region Search

    private fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/search/novel_title?k=$query&page=$page", headers)
        }

        var genreParam = ""
        var sortPath = "/ranking/monthly"
        var adultPath = ""

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> if (filter.state > 0) genreParam = GENRE_PARAMS[filter.state]
                is SortByFilter -> sortPath = SORT_BY_PATHS[filter.state]
                is AdultFilter -> when (filter.state) {
                    2 -> adultPath = "/nsfw"
                    else -> {}
                }
                else -> {}
            }
        }

        val url = if (adultPath.isNotEmpty()) {
            "$baseUrl$adultPath$sortPath?page=$page${genreParam.ifEmpty { "" }}"
        } else if (genreParam == "All" || genreParam.isEmpty()) {
            "$baseUrl$sortPath?page=$page"
        } else {
            "$baseUrl$sortPath?page=$page$genreParam"
        }

        return GET(url, headers)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val doc = client.newCall(buildSearchMangaRequest(page, query, filters)).execute().asJsoup()
        val mangas = parseNovelList(doc)
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    // endregion

    // region Details + Chapters

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        // Details and chapters live on different pages - fire both concurrently when both are needed.
        val detailsDeferred = if (fetchDetails) {
            async { parseMangaDetails(client.newCall(GET(baseUrl + mangaPath.resolve(manga.url), headers)).execute()) }
        } else {
            null
        }
        val chaptersDeferred = if (fetchChapters) {
            async { parseChapterList(client.newCall(GET(baseUrl + mangaPath.resolve(manga.url) + "/chapters", headers)).execute()) }
        } else {
            null
        }

        SMangaUpdate(
            manga = detailsDeferred?.await() ?: manga,
            chapters = chaptersDeferred?.await() ?: chapters,
        )
    }

    private fun parseMangaDetails(response: Response): SManga {
        val doc = response.asJsoup()
        doc.select("#wrap-button-remove-blur").remove()

        return SManga.create().apply {
            title = doc.selectFirst("div.mt8")?.text() ?: ""
            description = doc.selectFirst(".wrap-novel-body")?.text()
            thumbnail_url = doc.selectFirst(".wrap-img-novel-mask img")?.attr("src") ?: logoUrl
            status = when (doc.selectFirst("span.pr8")?.text()) {
                "Ongoing" -> SManga.ONGOING
                "Finished" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            author = doc.selectFirst("span.text-break-all.f14")?.text()
            genre = doc.selectFirst("div.wrap-novel-genres")?.select("a.btn-genre-link")
                ?.joinToString { it.text() }
        }
    }

    private fun parseChapterList(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        return doc.select("#wrap-chapter .list-chapter .list-group-item a").mapIndexed { index, el ->
            SChapter.create().apply {
                val date = el.selectFirst("div.f12")?.text() ?: ""
                val chTitle = el.selectFirst("div.text-bold")?.text() ?: ""
                name = "[$date] $chTitle"
                url = el.attr("href")
                chapter_number = (index + 1).toFloat()
            }
        }.reversed()
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPath.resolve(manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        val response = client.newCall(GET(baseUrl + path, headers)).execute()
        if (!response.isSuccessful) return null
        return parseMangaDetails(response).apply { this.url = mangaPath.slug(path) }
    }

    // endregion

    // region Pages

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.newCall(GET(if (chapter.url.startsWith("http")) chapter.url else baseUrl + chapter.url, headers)).execute()
        return listOf(Page(0, response.request.url.toString()))
    }

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.newCall(GET(if (page.url.startsWith("http")) page.url else baseUrl + page.url, headers)).execute().asJsoup()
        val title = doc.selectFirst("h1")?.text() ?: ""
        val body = doc.selectFirst(".wrap-body") ?: return ""
        body.select("#wrap-button-remove-blur").remove()
        body.children().first()?.before("<h1>$title</h1>")
        return body.html()
    }

    // endregion

    // region Helpers

    private fun parseNovelList(doc: Document): List<SManga> {
        val container = doc.selectFirst(".list-unit-novel") ?: return emptyList()
        return container.select(".novel-unit-type-h.row").map { el ->
            SManga.create().apply {
                title = el.selectFirst("h3")?.text() ?: ""
                thumbnail_url = el.selectFirst("img")?.attr("src") ?: logoUrl
                val href = el.selectFirst(".wrap-novel-links a")?.attr("href")
                    ?.removePrefix(baseUrl) ?: ""
                url = mangaPath.slug(href)
            }
        }
    }

    private fun Response.asJsoup(): Document = Jsoup.parse(body.string(), request.url.toString())

    // endregion

    // region Filters

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        GenreFilter(),
        SortByFilter(),
        AdultFilter(),
    )

    private class GenreFilter :
        Filter.Select<String>(
            "Genre",
            GENRE_NAMES.toTypedArray(),
        )

    private class SortByFilter :
        Filter.Select<String>(
            "Sort By",
            arrayOf("Monthly Ranking", "Weekly Ranking", "New Novels"),
        )

    private class AdultFilter :
        Filter.Select<String>(
            "Adult",
            arrayOf("None", "No", "Only"),
        )

    companion object {
        private val GENRE_NAMES = listOf(
            "All", "Action", "Adventure", "Boys Love", "Comedy", "Crime", "Culinary",
            "Cyberpunk", "Drama", "Ecchi", "Fantasy", "Game", "Girls Love", "Gun Action",
            "Harem", "Historical", "Horror", "Isekai", "LGBTQ+", "LitRPG", "Magic",
            "Martial Arts", "Mecha", "Military / War", "Music", "Mystery", "Paranormal",
            "Philosophical", "Post-Apocalyptic", "Psychological", "Romance", "School",
            "Sci-Fi", "Seinen", "Shoujo", "Shounen", "Slice of Life", "Sports",
            "Supernatural", "Survival", "Thriller", "Time travel", "Tragedy", "Western",
        )

        private val GENRE_PARAMS = listOf(
            "All",
            "&genre_id=1", "&genre_id=2", "&genre_id=49", "&genre_id=5", "&genre_id=14",
            "&genre_id=6", "&genre_id=67", "&genre_id=9", "&genre_id=10", "&genre_id=11",
            "&genre_id=13", "&genre_id=47", "&genre_id=16", "&genre_id=17", "&genre_id=19",
            "&genre_id=20", "&genre_id=63", "&genre_id=72", "&genre_id=68", "&genre_id=26",
            "&genre_id=28", "&genre_id=29", "&genre_id=30", "&genre_id=32", "&genre_id=33",
            "&genre_id=70", "&genre_id=36", "&genre_id=66", "&genre_id=38", "&genre_id=40",
            "&genre_id=42", "&genre_id=43", "&genre_id=44", "&genre_id=46", "&genre_id=48",
            "&genre_id=50", "&genre_id=52", "&genre_id=53", "&genre_id=45", "&genre_id=55",
            "&genre_id=69", "&genre_id=65", "&genre_id=71",
        )

        private val SORT_BY_PATHS = arrayOf("/ranking/monthly", "/ranking/weekly", "/novels")
    }

    // endregion
}

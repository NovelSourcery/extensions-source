package eu.kanade.tachiyomi.novelextension.en.novelsonline

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
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import org.jsoup.nodes.Document

@Source
abstract class NovelsOnline :
    KeiSource(),
    NovelSource {

    override val supportsLatest = false

    override suspend fun getPopularManga(page: Int): MangasPage = parseNovelList(client.get("$baseUrl/top-novel/$page", headers))

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val form = buildSearchForm(query, filters)
        if (form == null) return getPopularManga(page)
        if (page > 1) return MangasPage(emptyList(), false)
        return parseNovelList(client.post("$baseUrl/detailed-search", headers, form))
    }

    private fun buildSearchForm(query: String, filters: FilterList): FormBody? {
        val novelType = filters.filterIsInstance<NovelTypeFilter>().firstOrNull()?.state.orEmpty().filter { it.state }
        val language = filters.filterIsInstance<LanguageFilter>().firstOrNull()?.state.orEmpty().filter { it.state }
        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.state.orEmpty().filter { it.state }
        val completed = filters.filterIsInstance<CompletedFilter>().firstOrNull()?.toUriPart().orEmpty()

        if (query.isBlank() && novelType.isEmpty() && language.isEmpty() && genre.isEmpty() && completed.isEmpty()) {
            return null
        }

        val builder = FormBody.Builder().add("search", "1")
        if (query.isNotBlank()) builder.add("keyword", query)
        novelType.forEach { builder.add("include[novel_type][]", it.name) }
        language.forEach { builder.add("include[language][]", it.name) }
        genre.forEach { builder.add("include[genre][]", it.id) }
        if (completed.isNotEmpty()) builder.add("include[completed][]", completed)
        return builder.build()
    }

    private fun parseNovelList(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = doc.select(".top-novel-block").mapNotNull { element ->
            try {
                val link = element.selectFirst("h2 a") ?: return@mapNotNull null
                val url = link.attr("href").toPath()
                val cover = element.selectFirst(".top-novel-cover img")?.attr("src")

                SManga.create().apply {
                    title = element.selectFirst("h2")?.text() ?: return@mapNotNull null
                    this.url = url
                    thumbnail_url = cover?.let { if (it.startsWith("http")) it else "$baseUrl/${it.trimStart('/')}" }
                }
            } catch (e: Exception) {
                null
            }
        }
        return MangasPage(mangas, mangas.isNotEmpty())
    }

    private fun String.toPath(): String = if (startsWith("http")) {
        try {
            val url = toHttpUrl()
            buildString {
                append(url.encodedPath)
                url.encodedQuery?.let {
                    append('?')
                    append(it)
                }
            }
        } catch (e: Exception) {
            this
        }
    } else {
        this
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = buildString {
            append(url.encodedPath)
            url.encodedQuery?.let {
                append('?')
                append(it)
            }
        }
        val response = client.get(baseUrl + path, headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        return parseMangaDetails(response.asJsoup()).apply { this.url = path }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = client.get(baseUrl + manga.url, headers).asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc).apply { this.url = manga.url } else manga
        val updatedChapters = if (fetchChapters) parseChapterList(doc) else chapters
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(doc: Document): SManga = SManga.create().apply {
        title = doc.selectFirst("h1")?.text() ?: ""
        thumbnail_url = doc.selectFirst(".novel-cover a > img")?.attr("src")

        doc.select(".novel-detail-item").forEach { item ->
            val label = item.selectFirst("h6")?.text().orEmpty()
            val body = item.selectFirst(".novel-detail-body") ?: return@forEach

            when (label) {
                "Description" -> description = body.text()
                "Genre" -> genre = body.select("li").joinToString { it.text() }
                "Author(s)" -> author = body.select("li").joinToString { it.text() }
                "Status" -> status = when {
                    body.text().contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                    body.text().contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                    body.text().contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS
                    else -> SManga.UNKNOWN
                }
            }
        }
    }

    private fun parseChapterList(doc: Document): List<SChapter> {
        val elements = doc.select("ul.chapter-chs > li > a")
        return elements.mapIndexedNotNull { index, link ->
            val href = link.attr("href")
            if (href.isBlank()) return@mapIndexedNotNull null
            SChapter.create().apply {
                name = link.text()
                url = href.toPath()
                chapter_number = (index + 1).toFloat()
            }
        }.reversed()
    }

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.get(baseUrl + page.url, headers).asJsoup()
        return doc.selectFirst("#contentall")?.html() ?: ""
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        NovelTypeFilter(),
        LanguageFilter(),
        GenreFilter(),
        CompletedFilter(),
    )

    private class NovelTypeCheckBox(name: String) : Filter.CheckBox(name)
    private class NovelTypeFilter :
        Filter.Group<NovelTypeCheckBox>(
            "Novel Type",
            listOf("Web Novel", "Light Novel", "Chinese Novel", "Korean Novel").map { NovelTypeCheckBox(it) },
        )

    private class LanguageCheckBox(name: String) : Filter.CheckBox(name)
    private class LanguageFilter :
        Filter.Group<LanguageCheckBox>(
            "Language",
            listOf("Chinese", "Japanese", "Korean").map { LanguageCheckBox(it) },
        )

    private class Genre(name: String, val id: String) : Filter.CheckBox(name)
    private class GenreFilter :
        Filter.Group<Genre>(
            "Genre",
            listOf(
                Genre("Action", "4"), Genre("Adventure", "1"), Genre("Celebrity", "39"), Genre("Comedy", "12"),
                Genre("Drama", "6"), Genre("Ecchi", "47"), Genre("Fantasy", "2"), Genre("Gender Bender", "14"),
                Genre("Harem", "45"), Genre("Historical", "22"), Genre("Horror", "31"), Genre("Josei", "21"),
                Genre("Martial Arts", "18"), Genre("Mature", "46"), Genre("Mecha", "30"), Genre("Mystery", "7"),
                Genre("Psychological", "8"), Genre("Romance", "9"), Genre("School Life", "10"), Genre("Sci-fi", "3"),
                Genre("Seinen", "23"), Genre("Shotacon", "35"), Genre("Shoujo", "11"), Genre("Shoujo Ai", "34"),
                Genre("Shounen", "5"), Genre("Shounen Ai", "32"), Genre("Slice of Life", "13"), Genre("Sports", "33"),
                Genre("Supernatural", "25"), Genre("Tragedy", "24"), Genre("Wuxia", "17"), Genre("Xianxia", "20"),
                Genre("Xuanhuan", "38"), Genre("Yaoi", "16"), Genre("Yuri", "27"),
            ),
        )

    private class CompletedFilter : Filter.Select<String>("Completed", arrayOf("Any", "Yes", "No")) {
        fun toUriPart() = when (state) {
            1 -> "yes"
            2 -> "no"
            else -> ""
        }
    }
}

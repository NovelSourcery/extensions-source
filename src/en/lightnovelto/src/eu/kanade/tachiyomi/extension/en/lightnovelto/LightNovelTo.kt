package eu.kanade.tachiyomi.novelextension.en.lightnovelto

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
import keiyoushi.utils.formattedText
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document

@Source
abstract class LightNovelTo :
    KeiSource(),
    NovelSource {

    override val supportsFilterFetching = true

    override suspend fun getPopularManga(page: Int): MangasPage = getNovelList(page, "popular")

    override suspend fun getLatestUpdates(page: Int): MangasPage = getNovelList(page, "latest")

    private suspend fun getNovelList(page: Int, sort: String): MangasPage {
        val url = "$baseUrl/novels".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("page", page.toString())
            .build()
        return client.get(url).asJsoup().parseNovelList(page)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        // /search renders an empty prompt without a query, /novels takes the same filters
        val path = if (query.isBlank()) "novels" else "search"
        val url = "$baseUrl/$path".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("q", query.trim())
            filters.forEach {
                when (it) {
                    is UriPartFilter -> it.addTo(this)
                    is GenreFilter -> it.addTo(this)
                    else -> {}
                }
            }
            addQueryParameter("page", page.toString())
        }.build()
        return client.get(url).asJsoup().parseNovelList(page)
    }

    private fun Document.parseNovelList(page: Int): MangasPage {
        val mangas = select("a.ln-ncard[href^=/novel/]").map { card ->
            SManga.create().apply {
                url = card.attr("href").removePrefix("/novel/")
                title = card.selectFirst("h3")!!.text()
                thumbnail_url = card.selectFirst("img")?.absUrl("src")
            }
        }.distinctBy { it.url }
        val nextPage = (page + 1).toString()
        val hasNextPage = select("a[href*=page=]").any {
            it.absUrl("href").toHttpUrlOrNull()?.queryParameter("page") == nextPage
        }
        return MangasPage(mangas, hasNextPage)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novel/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "novel") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        val document = client.get("$baseUrl/novel/$slug").asJsoup()
        return parseMangaDetails(document).apply { this.url = slug }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val details = async {
            if (fetchDetails) parseMangaDetails(client.get(getMangaUrl(manga)).asJsoup()) else manga
        }
        val chapterList = async {
            if (fetchChapters) {
                val url = "$baseUrl/api/novel-chapters".toHttpUrl().newBuilder()
                    .addQueryParameter("slug", manga.url)
                    .build()
                client.get(url).parseAs<ChapterListDto>().chapters
                    .map { it.toSChapter(manga.url) }
                    .reversed()
            } else {
                chapters
            }
        }
        SMangaUpdate(details.await(), chapterList.await())
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.ln-dtitle")!!.text()
        thumbnail_url = document.selectFirst(".ln-dcv img")?.absUrl("src")

        val meta = document.select(".ln-meta p").associate {
            it.selectFirst(".ln-k")?.text().orEmpty() to it.children().last()?.text().orEmpty()
        }
        val type = meta["Type"]?.takeIf { it.isNotEmpty() }
        author = meta["Author"]?.takeIf { it.isNotEmpty() }
        status = when (meta["Status"]?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        genre = (listOfNotNull(type) + document.select(".ln-tagrow a[href^=/genre/]").eachText())
            .distinct()
            .joinToString()
            .takeIf { it.isNotEmpty() }

        val stats = document.select(".ln-facts > span:not(.ln-warn)").mapNotNull { fact ->
            val value = fact.selectFirst("b")?.text() ?: return@mapNotNull null
            when (val label = fact.ownText()) {
                "views" -> "Views: $value"
                "chapters" -> "Chapters: $value"
                "words" -> "Words: $value"
                else -> if (label.startsWith("(")) "Rating: $value/5 $label" else null
            }
        }

        description = buildString {
            document.selectFirst(".ln-syn")?.formattedText()?.takeIf { it.isNotEmpty() }?.let {
                append(it)
                append("\n\n")
            }
            stats.forEach { append(it).append('\n') }
            type?.let { append("Type: ").append(it).append('\n') }
            meta["Status"]?.takeIf { it.isNotEmpty() }?.let {
                append("Status: ").append(it.replaceFirstChar(Char::uppercase))
            }
        }.trim()
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/novel/${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, getChapterUrl(chapter)))

    override suspend fun fetchPageText(page: Page): String {
        val content = client.get(page.url).asJsoup().selectFirst("div.ln-prose") ?: return ""
        content.select("script, style, ins").remove()
        return content.html()
    }

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/api/genres").parseAs<List<GenreDto>>().toJsonElement()

    override fun getFilterList(data: JsonElement?): FilterList {
        val filters = mutableListOf<Filter<*>>(
            SortFilter(),
            TypeFilter(),
            StatusFilter(),
            TranslationFilter(),
        )
        data?.parseAs<List<GenreDto>>()?.takeIf { it.isNotEmpty() }?.let {
            filters += Filter.Separator()
            filters += GenreFilter(it)
        }
        return FilterList(filters)
    }
}

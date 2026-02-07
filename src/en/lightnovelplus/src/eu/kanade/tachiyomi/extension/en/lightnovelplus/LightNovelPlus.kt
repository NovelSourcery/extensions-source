package eu.kanade.tachiyomi.extension.en.lightnovelplus

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.Request

class LightNovelPlus : ReadNovelFull(
    name = "LightNovelPlus",
    baseUrl = "https://lightnovelplus.com",
    lang = "en",
) {
    // LightNovelPlus has a very different URL structure from the standard ReadNovelFull
    override val latestPage = "last_release"
    override val searchPage = "book/search.html"
    override val novelListing = "book/bookclass.html"
    override val chapterListing = "get_chapter_list"
    override val chapterParam = "bookId"
    override val pageParam = "page_num"
    override val searchKey = "keyword"

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/book/bookclass.html?$pageParam=$page", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/$latestPage?$pageParam=$page", headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/$searchPage?keyword=$query&$pageParam=$page", headers)
        }

        var typePath = ""
        var genreId = ""

        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> typePath = filter.toUriPart()
                is GenreFilter -> genreId = filter.toUriPart()
                else -> {}
            }
        }

        // Genre takes priority
        return if (genreId.isNotEmpty()) {
            GET("$baseUrl/book/bookclass.html?category_id=$genreId&$pageParam=$page", headers)
        } else if (typePath.isNotEmpty()) {
            GET("$baseUrl/$typePath?$pageParam=$page", headers)
        } else {
            GET("$baseUrl/book/bookclass.html?$pageParam=$page", headers)
        }
    }

    // ======================== Filters ========================

    override fun getFilterList() = FilterList(
        Filter.Header("Type filters"),
        TypeFilter(),
        Filter.Header("Genre filters"),
        GenreFilter(),
    )

    private class TypeFilter : Filter.Select<String>(
        "Type",
        arrayOf("All", "Hot Novel", "Completed Novel"),
        0,
    ) {
        fun toUriPart() = when (state) {
            1 -> "hot_novel"
            2 -> "completed_novel"
            else -> ""
        }
    }

    private class GenreFilter : Filter.Select<String>(
        "Genre",
        genres.map { it.first }.toTypedArray(),
        0,
    ) {
        fun toUriPart() = genres[state].second
    }

    override fun getGenreList() = genres.drop(1).map { Genre(it.first, it.second) }

    companion object {
        private val genres = listOf(
            Pair("All", ""),
            Pair("Fantasy", "60"),
            Pair("Action", "132"),
            Pair("Sci-fi", "61"),
            Pair("Romance", "59"),
            Pair("Adventure", "62"),
            Pair("Xuanhuan", "64"),
            Pair("Modern", "66"),
            Pair("Mystery", "63"),
            Pair("Historical", "74"),
            Pair("LGBT+", "182"),
            Pair("Fantasy Romance", "134"),
            Pair("Video Games", "243"),
            Pair("Sci-fi Romance", "252"),
            Pair("Historical Romance", "256"),
            Pair("Magical Realism", "331"),
            Pair("Eastern Fantasy", "334"),
            Pair("Contemporary Romance", "344"),
            Pair("Games", "503"),
            Pair("Urban", "504"),
            Pair("Harem", "517"),
        )
    }
}

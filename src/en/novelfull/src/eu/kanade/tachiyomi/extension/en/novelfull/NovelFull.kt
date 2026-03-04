package eu.kanade.tachiyomi.extension.en.novelfull

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.Request

class NovelFull :
    ReadNovelFull(
        name = "NovelFull",
        baseUrl = "https://novelfull.com",
        lang = "en",
    ) {
    override val latestPage = "latest-release-novel"
    override val searchPage = "search"
    override val chapterListing = "ajax-chapter-option"

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/most-popular?page=$page", headers)
    // ======================== Filters ========================

    override fun getFilterList() = FilterList(
        Filter.Header("Type filters"),
        TypeFilter(),
        Filter.Header("Genre filters"),
        GenreFilter(),
    )

    private class TypeFilter :
        Filter.Select<String>(
            "Type",
            arrayOf("Most Popular", "Hot Novel", "Completed Novel"),
            0,
        ) {
        fun toUriPart() = when (state) {
            0 -> "most-popular"
            1 -> "hot-novel"
            2 -> "completed-novel"
            else -> "most-popular"
        }
    }

    private class GenreFilter :
        Filter.Select<String>(
            "Genre",
            genres.map { it.first }.toTypedArray(),
            0,
        ) {
        fun toUriPart() = genres[state].second
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/search?keyword=$query&page=$page", headers)
        }

        var typePath = ""
        var genrePath = ""

        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> typePath = filter.toUriPart()
                is GenreFilter -> genrePath = filter.toUriPart()
                else -> {}
            }
        }

        // Genre takes priority over type
        return if (genrePath.isNotEmpty()) {
            GET("$baseUrl/$genrePath?page=$page", headers)
        } else {
            GET("$baseUrl/$typePath?page=$page", headers)
        }
    }

    override fun getGenreList() = genres.drop(1).map { Genre(it.first, it.second) }

    companion object {
        private val genres = listOf(
            Pair("All", ""),
            Pair("Shounen", "genre/Shounen"),
            Pair("Harem", "genre/Harem"),
            Pair("Comedy", "genre/Comedy"),
            Pair("Martial Arts", "genre/Martial+Arts"),
            Pair("School Life", "genre/School+Life"),
            Pair("Mystery", "genre/Mystery"),
            Pair("Shoujo", "genre/Shoujo"),
            Pair("Romance", "genre/Romance"),
            Pair("Sci-fi", "genre/Sci-fi"),
            Pair("Gender Bender", "genre/Gender+Bender"),
            Pair("Mature", "genre/Mature"),
            Pair("Fantasy", "genre/Fantasy"),
            Pair("Horror", "genre/Horror"),
            Pair("Drama", "genre/Drama"),
            Pair("Tragedy", "genre/Tragedy"),
            Pair("Supernatural", "genre/Supernatural"),
            Pair("Ecchi", "genre/Ecchi"),
            Pair("Xuanhuan", "genre/Xuanhuan"),
            Pair("Adventure", "genre/Adventure"),
            Pair("Action", "genre/Action"),
            Pair("Psychological", "genre/Psychological"),
            Pair("Xianxia", "genre/Xianxia"),
            Pair("Wuxia", "genre/Wuxia"),
            Pair("Historical", "genre/Historical"),
            Pair("Slice of Life", "genre/Slice+of+Life"),
            Pair("Seinen", "genre/Seinen"),
            Pair("Lolicon", "genre/Lolicon"),
            Pair("Adult", "genre/Adult"),
            Pair("Josei", "genre/Josei"),
            Pair("Sports", "genre/Sports"),
            Pair("Smut", "genre/Smut"),
            Pair("Mecha", "genre/Mecha"),
            Pair("Yaoi", "genre/Yaoi"),
            Pair("Shounen Ai", "genre/Shounen+Ai"),
            Pair("History", "genre/History"),
            Pair("Martial", "genre/Martial"),
        )
    }
}

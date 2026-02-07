package eu.kanade.tachiyomi.extension.en.kdtnovels

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.Request

class KdtNovels : LightNovelWPNovel(
    baseUrl = "https://kdtnovels.net",
    name = "KDT Novels",
    lang = "en",
) {
    // ======================== Filters ========================

    override fun getFilterList(): FilterList = FilterList(
        StatusFilter(),
        SortFilter(),
        GenreFilter(),
        TypeFilter(),
    )

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var url = "$baseUrl/$seriesPath?page=$page"

        if (query.isNotBlank()) {
            url += "&s=${query.replace(" ", "+")}"
        }

        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> {
                    if (filter.state != 0) {
                        url += "&status=${filter.toUriPart()}"
                    }
                }
                is SortFilter -> {
                    if (filter.state != 0) {
                        url += "&order=${filter.toUriPart()}"
                    }
                }
                is GenreFilter -> {
                    filter.state.filter { it.state }.forEach { genre ->
                        url += "&genre[]=${genre.value}"
                    }
                }
                is TypeFilter -> {
                    filter.state.filter { it.state }.forEach { type ->
                        url += "&type[]=${type.value}"
                    }
                }
                else -> {}
            }
        }

        return GET(url, headers)
    }

    private class StatusFilter : Filter.Select<String>(
        "Status",
        arrayOf("All", "Ongoing", "Hiatus", "Completed"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "ongoing"
            2 -> "hiatus"
            3 -> "completed"
            else -> ""
        }
    }

    private class SortFilter : Filter.Select<String>(
        "Order by",
        arrayOf("Default", "A-Z", "Z-A", "Latest Update", "Latest Added", "Popular", "Rating"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "title"
            2 -> "titlereverse"
            3 -> "update"
            4 -> "latest"
            5 -> "popular"
            6 -> "rating"
            else -> ""
        }
    }

    private class GenreCheckBox(val value: String, name: String) : Filter.CheckBox(name)

    private class GenreFilter : Filter.Group<GenreCheckBox>(
        "Genres",
        listOf(
            GenreCheckBox("action", "Action"),
            GenreCheckBox("adult", "Adult"),
            GenreCheckBox("adventure", "Adventure"),
            GenreCheckBox("comedy", "Comedy"),
            GenreCheckBox("drama", "Drama"),
            GenreCheckBox("ecchi", "Ecchi"),
            GenreCheckBox("fantasy", "Fantasy"),
            GenreCheckBox("gender-bender", "Gender Bender"),
            GenreCheckBox("genderswap", "Genderswap"),
            GenreCheckBox("harem", "Harem"),
            GenreCheckBox("isekai", "Isekai"),
            GenreCheckBox("martial-arts", "Martial Arts"),
            GenreCheckBox("mature", "Mature"),
            GenreCheckBox("monster-girls", "Monster Girls"),
            GenreCheckBox("monsters", "Monsters"),
            GenreCheckBox("reincarnation", "Reincarnation"),
            GenreCheckBox("romance", "Romance"),
            GenreCheckBox("school-life", "School Life"),
            GenreCheckBox("seinen", "Seinen"),
            GenreCheckBox("shounen", "Shounen"),
            GenreCheckBox("slice-of-life", "Slice of Life"),
            GenreCheckBox("survival", "Survival"),
        ),
    )

    private class TypeCheckBox(val value: String, name: String) : Filter.CheckBox(name)

    private class TypeFilter : Filter.Group<TypeCheckBox>(
        "Type",
        listOf(
            TypeCheckBox("light-novel-jp", "Light Novel (JP)"),
            TypeCheckBox("web-novel", "Web Novel"),
        ),
    )
}

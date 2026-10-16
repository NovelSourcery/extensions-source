package eu.kanade.tachiyomi.novelextension.all.lunarxnovels

import eu.kanade.tachiyomi.source.model.Filter

open class SelectFilter(name: String, private val options: Array<Pair<String, String?>>) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selected: String? get() = options[state].second
}

class CatalogFilter :
    SelectFilter(
        "Catalog",
        arrayOf(
            "Lunar" to null,
            "Legacy (text search only)" to "legacy",
        ),
    )

class SortFilter :
    SelectFilter(
        "Sort",
        arrayOf(
            "Popular" to "popular",
            "Relevance" to "relevance",
        ),
    )

class StatusFilter :
    SelectFilter(
        "Status",
        arrayOf(
            "Any" to null,
            "Ongoing" to "ongoing",
            "Completed" to "completed",
            "Hiatus" to "hiatus",
        ),
    )

class OriginFilter :
    SelectFilter(
        "Origin",
        arrayOf(
            "Any" to null,
            "Japanese" to "JP",
            "Korean" to "KR",
            "Chinese" to "CN",
        ),
    )

class GenreFilter : Filter.Group<Filter.CheckBox>("Genres", GENRES.map { object : Filter.CheckBox(it) {} }) {
    val selected get() = state.filter { it.state }.map { it.name }
}

private val GENRES = listOf(
    "Action",
    "Adult",
    "Adventure",
    "Comedy",
    "Drama",
    "Ecchi",
    "Fantasy",
    "Gender Bender",
    "General Novel",
    "Harem",
    "Historical",
    "Horror",
    "Isekai",
    "Josei",
    "Light Novel",
    "Martial Arts",
    "Mature",
    "Mecha",
    "Mystery",
    "Psychological",
    "Romance",
    "School Life",
    "Sci-Fi",
    "Seinen",
    "Shoujo",
    "Shounen",
    "Slice of Life",
    "Smut",
    "Sports",
    "Supernatural",
    "Thriller",
    "Tragedy",
    "Web Novel CN",
    "Web Novel KR",
    "Xianxia",
    "Yaoi",
    "Yuri",
)

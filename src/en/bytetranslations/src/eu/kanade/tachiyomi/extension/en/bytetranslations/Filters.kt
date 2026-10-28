package eu.kanade.tachiyomi.novelextension.en.bytetranslations

import eu.kanade.tachiyomi.source.model.Filter

open class UriPartFilter(name: String, private val vals: Array<Pair<String, String>>) : Filter.Select<String>(name, vals.map { it.first }.toTypedArray()) {
    fun toUriPart() = vals[state].second
}

class GenreFilter :
    UriPartFilter(
        "Genre",
        arrayOf(
            "All" to "",
            "Action" to "action",
            "Adventure" to "adventure",
            "Drama" to "drama",
            "Fantasy" to "fantasy",
            "Harem" to "harem",
            "Mystery" to "mistery",
        ),
    )

class StatusFilter :
    UriPartFilter(
        "Status",
        arrayOf("All" to "", "Completed" to "completed", "Ongoing" to "ongoing"),
    )

class OrderFilter :
    UriPartFilter(
        "Order by",
        arrayOf(
            "Newest first" to "date_desc",
            "Oldest first" to "date_asc",
            "Title A-Z" to "title_asc",
            "Title Z-A" to "title_desc",
            "Last updated" to "modified_desc",
        ),
    )

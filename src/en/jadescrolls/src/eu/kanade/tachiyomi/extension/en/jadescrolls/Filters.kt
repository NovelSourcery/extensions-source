package eu.kanade.tachiyomi.novelextension.en.jadescrolls

import eu.kanade.tachiyomi.source.model.Filter

open class UriPartFilter(name: String, private val vals: Array<Pair<String, String>>, state: Int = 0) : Filter.Select<String>(name, vals.map { it.first }.toTypedArray(), state) {
    fun toUriPart() = vals[state].second
}

class GenreFilter(genres: List<GenreDto>) :
    UriPartFilter(
        "Genre",
        arrayOf("All" to "") + genres.sortedBy { it.name }.map { it.name to it.id },
    )

class LeadFilter :
    UriPartFilter(
        "Lead",
        arrayOf("All" to "", "Male" to "MALE", "Female" to "FEMALE"),
    )

class TypeFilter :
    UriPartFilter(
        "Content type",
        arrayOf("All" to "", "Translated" to "TRANSLATED", "Original" to "ORIGINAL"),
    )

class StatusFilter :
    UriPartFilter(
        "Status",
        arrayOf(
            "All" to "",
            "Ongoing" to "ONGOING",
            "Coming soon" to "COMINGSOON",
            "Completed" to "COMPLETED",
            "Hiatus" to "HIATUS",
            "Cancelled" to "CANCELLED",
        ),
    )

class SortFilter :
    Filter.Sort(
        "Sort by",
        SORTS.map { it.first }.toTypedArray(),
        Selection(0, false),
    ) {
    fun toUriPart() = SORTS[state?.index ?: 0].second
    fun order() = if (state?.ascending == true) "asc" else "desc"

    companion object {
        private val SORTS = arrayOf(
            "Popular this week" to "weekly_views",
            "Total views" to "views",
            "Rating" to "rating",
            "Latest" to "created_at",
            "Last updated" to "updated_at",
            "Chapter count" to "chapter_count",
            "Title" to "title",
        )
    }
}

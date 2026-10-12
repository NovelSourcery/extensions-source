package eu.kanade.tachiyomi.novelextension.en.freegoodnovel

import eu.kanade.tachiyomi.source.model.Filter

open class SelectFilter(name: String, private val options: Array<Pair<String, String?>>) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    fun toValue(): String? = options[state].second
}

class SortFilter :
    SelectFilter(
        "Sort by",
        arrayOf(
            "Most viewed" to "-views",
            "Newest" to "-created",
            "Recently updated" to "-updated",
            "Top rated" to "-rating",
            "Most readers" to "-readers",
            "Most chapters" to "-chapter_count",
            "Title" to "title",
        ),
    )

class StatusFilter :
    SelectFilter(
        "Status",
        arrayOf("All" to null, "Ongoing" to "Ongoing", "Completed" to "Completed"),
    )

class GenreFilter :
    SelectFilter(
        "Genre",
        arrayOf(
            "All" to null,
            "Romance" to "Romance",
            "Fantasy" to "Fantasy",
            "Drama" to "Drama",
            "Mystery" to "Mystery",
            "Sci-Fi" to "Sci-Fi",
            "Thriller" to "Thriller",
        ),
    )

class Tag(name: String) : Filter.TriState(name)

class TagFilter(tags: List<String>) : Filter.Group<Tag>("Tags", tags.map(::Tag))

package eu.kanade.tachiyomi.novelextension.en.requiemtranslations

import eu.kanade.tachiyomi.source.model.Filter

open class SelectFilter(name: String, private val options: Array<Pair<String, String?>>) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    fun toValue(): String? = options[state].second
}

class SortFilter :
    SelectFilter(
        "Sort",
        arrayOf(
            "Newest first" to "-created",
            "Oldest first" to "created",
            "Title (A → Z)" to "title",
            "Title (Z → A)" to "-title",
            "Highest score" to "-score",
            "Lowest score" to "score",
        ),
    )

class StatusFilter :
    SelectFilter(
        "Status",
        arrayOf(
            "All" to null,
            "Ongoing" to "Ongoing",
            "Hiatus" to "Hiatus",
            "Completed" to "Completed",
        ),
    )

class R18Filter :
    SelectFilter(
        "R18",
        arrayOf(
            "All" to null,
            "Hide R18" to "false",
            "Only R18" to "true",
        ),
    )

class GenreCheckBox(name: String, val id: String) : Filter.CheckBox(name)

class GenreFilter(genres: List<GenreDto>) : Filter.Group<GenreCheckBox>("Genres (all must match)", genres.map { GenreCheckBox(it.name, it.id) }) {
    fun selected() = state.filter { it.state }.map { it.id }
}

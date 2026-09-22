package eu.kanade.tachiyomi.novelextension.en.novelping

import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.serialization.Serializable

@Serializable
internal class FilterOption(val label: String, val id: String)

@Serializable
internal class FilterData(
    val genres: List<FilterOption> = emptyList(),
    val tags: List<FilterOption> = emptyList(),
)

class SortFilter :
    Filter.Select<String>(
        "Sort",
        arrayOf(
            "Popular this week",
            "Last updated",
            "Recently added",
            "Top (most viewed)",
            "Top rated",
            "Most chapters",
        ),
    ) {
    // null => default sort (Popular this week) — no query parameter.
    fun toUriPart(): String? = when (state) {
        1 -> "LASTEST"
        2 -> "NEW"
        3 -> "ALL_TIME"
        4 -> "RATING"
        5 -> "CHAPTERS"
        else -> null
    }
}

class GenreModeFilter : Filter.Select<String>("Genre Match", arrayOf("Match All (AND)", "Match Any (OR)")) {
    fun toUriPart() = if (state == 0) "AND" else "OR"
}

class GenreOption(name: String, val id: String) : Filter.TriState(name)
class GenreFilter(options: List<GenreOption>) : Filter.Group<GenreOption>("Genres", options)

class TagModeFilter : Filter.Select<String>("Tag Match", arrayOf("Match All (AND)", "Match Any (OR)")) {
    fun toUriPart() = if (state == 0) "AND" else "OR"
}

class TagOption(name: String, val id: String) : Filter.TriState(name)
class TagFilter(options: List<TagOption>) : Filter.Group<TagOption>("Tags", options)

class StatusFilter : Filter.Select<String>("Status", arrayOf("All Statuses", "Ongoing", "Completed")) {
    fun toUriPart(): String? = arrayOf(null, "ongoing", "completed")[state]
}

class LanguageFilter : Filter.Select<String>("Language", arrayOf("All Languages", "English", "Chinese", "Japanese", "Korean")) {
    fun toUriPart(): String? = arrayOf(null, "EN", "CN", "JP", "KR")[state]
}

class StartYearFilter : Filter.Text("Start Year")
class EndYearFilter : Filter.Text("End Year")
class AuthorFilter : Filter.Text("Author")
class AuthorExcludeFilter : Filter.Text("Author Exclude")
class MinChaptersFilter : Filter.Text("Min Chapters", "0")
class MaxChaptersFilter : Filter.Text("Max Chapters")

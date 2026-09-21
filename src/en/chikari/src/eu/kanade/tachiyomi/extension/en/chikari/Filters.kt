package eu.kanade.tachiyomi.novelextension.en.chikari

import eu.kanade.tachiyomi.source.model.Filter

internal class SortFilter :
    Filter.Select<String>(
        "Sort",
        SORT_OPTIONS.map { it.first }.toTypedArray(),
        0,
    ) {
    fun toUriPart() = SORT_OPTIONS[state].second

    companion object {
        private val SORT_OPTIONS = listOf(
            "Popular" to "popular",
            "Trending" to "trending",
            "Top rated" to "top_rated",
            "Updated" to "updated",
            "Added" to "added",
            "Chapters" to "chapters",
        )
    }
}

internal class StatusFilter :
    Filter.Select<String>(
        "Status",
        STATUS_OPTIONS.map { it.first }.toTypedArray(),
        0,
    ) {
    fun toUriPart() = STATUS_OPTIONS[state].second

    companion object {
        private val STATUS_OPTIONS = listOf(
            "Any" to "",
            "Releasing" to "releasing",
            "Completed" to "completed",
        )
    }
}

internal class AdultFilter :
    Filter.Select<String>(
        "Adult",
        ADULT_OPTIONS.map { it.first }.toTypedArray(),
        0,
    ) {
    fun mode() = when (state) {
        1 -> AdultMode.SAFE
        2 -> AdultMode.ADULT
        else -> AdultMode.BOTH
    }

    companion object {
        private val ADULT_OPTIONS = listOf(
            "Both" to "both",
            "Safe only" to "safe",
            "Adult only" to "adult",
        )
    }
}

internal enum class AdultMode {
    BOTH,
    SAFE,
    ADULT,
}

internal class GenreFilter(genres: List<NamedSlugDto>) :
    Filter.Group<GenreTriState>(
        "Genres",
        genres.map { GenreTriState(it.name, it.slug) },
    )

internal class GenreTriState(name: String, val slug: String) : Filter.TriState(name)

internal class TagFilter(tags: List<NamedIdDto>) :
    Filter.Group<TagTriState>(
        "Tags",
        tags.map { TagTriState(it.name, it.id) },
    )

internal class TagTriState(name: String, val id: Int) : Filter.TriState(name)

package eu.kanade.tachiyomi.novelextension.en.novelshaven

import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl

interface UrlFilter {
    fun addTo(url: HttpUrl.Builder)
}

open class SelectFilter(
    name: String,
    private val param: String,
    private val options: Array<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()),
    UrlFilter {
    override fun addTo(url: HttpUrl.Builder) {
        url.addQueryParameter(param, options[state].second)
    }
}

class SortFilter :
    Filter.Sort("Sort by", SORT_OPTIONS.map { it.first }.toTypedArray(), Selection(0, false)),
    UrlFilter {
    override fun addTo(url: HttpUrl.Builder) {
        val selection = state ?: Selection(0, false)
        url.addQueryParameter("sort", SORT_OPTIONS[selection.index].second)
        url.addQueryParameter("order", if (selection.ascending) "ascending" else "descending")
    }

    companion object {
        private val SORT_OPTIONS = arrayOf(
            "Last Updated" to "last-updated",
            "Newest" to "newest",
            "Daily Rank" to "daily-rank",
            "Weekly Rank" to "weekly-rank",
            "Monthly Rank" to "monthly-rank",
            "All Time Rank" to "all-time-rank",
            "Bookmarks" to "bookmark",
            "Total Chapters" to "total-chapters",
        )
    }
}

class StatusFilter :
    SelectFilter(
        "Status",
        "status",
        arrayOf(
            "All" to "all",
            "Ongoing" to "ongoing",
            "Completed" to "completed",
            "Hiatus" to "hiatus",
            "Dropped" to "dropped",
        ),
    )

class LanguageFilter :
    SelectFilter(
        "Original language",
        "language",
        arrayOf(
            "All" to "all",
            "Chinese" to "chinese",
            "Japanese" to "japanese",
            "Korean" to "korean",
        ),
    )

private val MATCH_MODES = arrayOf("And" to "and", "Or" to "or")

class GenreMatchFilter : SelectFilter("Genre match mode", "genreMatchmode", MATCH_MODES)

class TagMatchFilter : SelectFilter("Tag match mode", "tagMatchmode", MATCH_MODES.reversedArray())

class TriStateOption(name: String) : Filter.TriState(name)

open class TriStateGroup(
    name: String,
    private val includeParam: String,
    private val excludeParam: String,
    values: List<String>,
) : Filter.Group<TriStateOption>(name, values.map(::TriStateOption)),
    UrlFilter {
    override fun addTo(url: HttpUrl.Builder) {
        state.forEach {
            when (it.state) {
                Filter.TriState.STATE_INCLUDE -> url.addQueryParameter(includeParam, it.name)
                Filter.TriState.STATE_EXCLUDE -> url.addQueryParameter(excludeParam, it.name)
            }
        }
    }
}

class GenreFilter(genres: List<String>) : TriStateGroup("Genres", "genres", "excludeGenres", genres)

class TagFilter(tags: List<String>) : TriStateGroup("Tags", "tags", "excludeTags", tags)

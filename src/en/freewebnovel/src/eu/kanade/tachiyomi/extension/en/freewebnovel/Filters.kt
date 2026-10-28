package eu.kanade.tachiyomi.novelextension.en.freewebnovel

import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl

interface UrlFilter {
    fun addToUrl(url: HttpUrl.Builder)
}

open class SelectFilter(name: String, private val param: String, private val vals: Array<Pair<String, String>>) :
    Filter.Select<String>(name, vals.map { it.first }.toTypedArray()),
    UrlFilter {
    override fun addToUrl(url: HttpUrl.Builder) {
        url.addQueryParameter(param, vals[state].second)
    }
}

class CheckBoxValue(name: String, val value: String) : Filter.CheckBox(name)

open class CheckBoxGroup(name: String, private val param: String, options: List<Pair<String, String>>) :
    Filter.Group<CheckBoxValue>(name, options.map { CheckBoxValue(it.first, it.second) }),
    UrlFilter {
    override fun addToUrl(url: HttpUrl.Builder) {
        state.filter { it.state }.forEach { url.addQueryParameter(param, it.value) }
    }
}

class LanguageFilter :
    CheckBoxGroup(
        "Language",
        "language[]",
        listOf("Chinese" to "1", "Korean" to "2", "Japanese" to "3", "English" to "4"),
    )

class GenresFilter : CheckBoxGroup("Genres", "genre[]", GENRES.map { it to it })

class GenreMatchFilter :
    SelectFilter(
        "Genre match",
        "genre_match",
        arrayOf("All selected" to "all", "Any selected" to "any", "Exclude selected" to "exclude"),
    )

class ContentRatingFilter :
    SelectFilter(
        "Content rating",
        "content_rating",
        arrayOf(
            "All" to "",
            "General Audiences" to "general",
            "Parental Guidance Suggested" to "guidance",
            "Suggestive Content" to "suggestive",
            "Explicit Content (18+)" to "adults-only",
        ),
    )

class LastUpdatedFilter :
    SelectFilter(
        "Last updated",
        "last_updated",
        arrayOf(
            "Any time" to "",
            "Within 24 hours" to "24-hours",
            "Within 7 days" to "7-days",
            "Within 30 days" to "30-days",
            "Within 3 months" to "3-months",
        ),
    )

class ChaptersFilter :
    SelectFilter(
        "Chapters",
        "chapters",
        arrayOf(
            "All" to "",
            "< 50" to "under-50",
            "50 - 100" to "50-100",
            "100 - 200" to "100-200",
            "200 - 500" to "200-500",
            "500 - 1000" to "500-1000",
            "> 1000" to "over-1000",
        ),
    )

class RatingFilter :
    SelectFilter(
        "Rating",
        "rating",
        arrayOf("Any rating" to "", "3+ stars" to "3", "4+ stars" to "4", "4.5+ stars" to "4.5"),
    )

class NovelStatusFilter :
    SelectFilter(
        "Status",
        "status",
        arrayOf("All" to "", "Ongoing" to "ongoing", "Completed" to "completed"),
    )

class SortFilter :
    SelectFilter(
        "Sort by",
        "sort",
        arrayOf(
            "Most Popular" to "popular",
            "Last Updated" to "updated",
            "Highest Rated" to "rating",
            "Most Collected" to "collected",
            "Most Chapters" to "chapters",
            "Title A - Z" to "title",
        ),
    )

private val GENRES = listOf(
    "Action", "Adult", "Adventure", "Comedy", "Drama", "Eastern", "Ecchi", "Fan-fic", "Fantasy", "Game",
    "Gender Bender", "Harem", "Historical", "Horror", "Josei", "Martial Arts", "Mature", "Mecha", "Mystery",
    "Psychological", "Reincarnation", "Romance", "School Life", "Sci-fi", "Seinen", "Shoujo", "Shounen Ai",
    "Shounen", "Slice of Life", "Smut", "Sports", "Supernatural", "System", "Tragedy", "Wuxia", "Xianxia",
    "Xuanhuan", "Yaoi",
)

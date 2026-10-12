package eu.kanade.tachiyomi.multisrc.ikennovel

import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl

typealias Options = List<Pair<String, String>>

interface UrlPartFilter {
    fun addUrlParameter(url: HttpUrl.Builder)
}

abstract class SelectFilter(
    name: String,
    private val urlParameter: String,
    private val options: Options,
    defaultValue: String? = null,
) : Filter.Select<String>(
    name,
    options.map { it.first }.toTypedArray(),
    options.indexOfFirst { it.second == defaultValue }.coerceAtLeast(0),
),
    UrlPartFilter {
    override fun addUrlParameter(url: HttpUrl.Builder) {
        url.addQueryParameter(urlParameter, options[state].second)
    }
}

class StatusFilter(options: Options) : SelectFilter("Status", "seriesStatus", options)

class SortFilter(options: Options, defaultValue: String? = null) : SelectFilter("Sort", "orderBy", options, defaultValue)

class SortDirectionFilter :
    SelectFilter(
        "Sort direction",
        "orderDirection",
        listOf("Descending" to "desc", "Ascending" to "asc"),
    )

class GenreCheckBox(name: String, val value: String) : Filter.CheckBox(name)

class GenreFilter(genres: Options) :
    Filter.Group<GenreCheckBox>("Genres", genres.map { GenreCheckBox(it.first, it.second) }),
    UrlPartFilter {
    override fun addUrlParameter(url: HttpUrl.Builder) {
        val checked = state.filter { it.state }.map { it.value }
        if (checked.isNotEmpty()) url.addQueryParameter("genreIds", checked.joinToString(","))
    }
}

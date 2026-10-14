package eu.kanade.tachiyomi.novelextension.en.lightnovelto

import eu.kanade.tachiyomi.source.model.Filter
import okhttp3.HttpUrl

open class UriPartFilter(
    name: String,
    private val param: String,
    private val options: Array<Pair<String, String>>,
) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    fun addTo(url: HttpUrl.Builder) {
        options[state].second.takeIf { it.isNotEmpty() }?.let { url.addQueryParameter(param, it) }
    }
}

class SortFilter :
    UriPartFilter(
        "Sort by",
        "sort",
        arrayOf(
            "Popular" to "popular",
            "Top rated" to "rating",
            "Recently updated" to "latest",
            "Recently added" to "newest",
            "Most chapters" to "chapters",
            "A-Z" to "title",
        ),
    )

class TypeFilter :
    UriPartFilter(
        "Type",
        "type",
        arrayOf(
            "All" to "",
            "Light Novel" to "light_novel",
            "Web Novel" to "web_novel",
            "Published" to "published",
        ),
    )

class StatusFilter :
    UriPartFilter(
        "Status",
        "status",
        arrayOf(
            "All" to "",
            "Ongoing" to "ongoing",
            "Completed" to "completed",
            "Hiatus" to "hiatus",
            "Cancelled" to "cancelled",
        ),
    )

class TranslationFilter :
    UriPartFilter(
        "Translation",
        "translation",
        arrayOf(
            "All" to "",
            "Official" to "official",
            "Fan translation" to "fan",
            "Machine translated" to "mtl",
        ),
    )

class GenreTriState(name: String, val slug: String) : Filter.TriState(name)

class GenreFilter(genres: List<GenreDto>) : Filter.Group<GenreTriState>("Genres", genres.map { GenreTriState(it.name, it.slug) }) {
    fun addTo(url: HttpUrl.Builder) {
        state.forEach {
            when (it.state) {
                Filter.TriState.STATE_INCLUDE -> url.addQueryParameter("genre", it.slug)
                Filter.TriState.STATE_EXCLUDE -> url.addQueryParameter("genre_ex", it.slug)
            }
        }
    }
}

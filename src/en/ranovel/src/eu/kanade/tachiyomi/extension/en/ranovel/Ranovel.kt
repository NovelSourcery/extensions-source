package eu.kanade.tachiyomi.extension.en.ranovel

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.Request

class Ranovel :
    MadaraNovel(
        baseUrl = "https://ranovel.com",
        name = "Ranovel",
        lang = "en",
    ) {
    override val useNewChapterEndpointDefault = true
    // ======================== Filters ========================

    override fun getFilterList(): FilterList = FilterList(
        OrderFilter(),
        GenreFilter(),
        StatusFilter(),
        AdultFilter(),
        AuthorFilter(),
        ArtistFilter(),
        ReleaseFilter(),
    )

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        var url = "$baseUrl/page/$page/?s=${query.replace(" ", "+")}&post_type=wp-manga"

        filters.forEach { filter ->
            when (filter) {
                is OrderFilter -> {
                    if (filter.state != 0) {
                        url += "&m_orderby=${filter.toUriPart()}"
                    }
                }

                is GenreFilter -> {
                    filter.state.filter { it.state }.forEach { genre ->
                        url += "&genre[]=${genre.value}"
                    }
                }

                is StatusFilter -> {
                    filter.state.filter { it.state }.forEach { status ->
                        url += "&status[]=${status.value}"
                    }
                }

                is AdultFilter -> {
                    val adult = filter.toUriPart()
                    if (adult.isNotEmpty()) {
                        url += "&adult=$adult"
                    }
                }

                is AuthorFilter -> {
                    if (filter.state.isNotBlank()) {
                        url += "&author=${filter.state.replace(" ", "+")}"
                    }
                }

                is ArtistFilter -> {
                    if (filter.state.isNotBlank()) {
                        url += "&artist=${filter.state.replace(" ", "+")}"
                    }
                }

                is ReleaseFilter -> {
                    if (filter.state.isNotBlank()) {
                        url += "&release=${filter.state}"
                    }
                }

                else -> {}
            }
        }

        return GET(url, headers)
    }

    private class OrderFilter :
        Filter.Select<String>(
            "Order by",
            arrayOf("Relevance", "Latest", "A-Z", "Rating", "Trending", "Most Views", "New"),
        ) {
        fun toUriPart() = when (state) {
            1 -> "latest"
            2 -> "alphabet"
            3 -> "rating"
            4 -> "trending"
            5 -> "views"
            6 -> "new-manga"
            else -> ""
        }
    }

    private class GenreCheckBox(val value: String, name: String) : Filter.CheckBox(name)

    private class GenreFilter :
        Filter.Group<GenreCheckBox>(
            "Genres",
            listOf(
                GenreCheckBox("action", "Action"),
                GenreCheckBox("adventure", "Adventure"),
                GenreCheckBox("comedy", "Comedy"),
                GenreCheckBox("drama", "Drama"),
                GenreCheckBox("ecchi", "Ecchi"),
                GenreCheckBox("fantasy", "Fantasy"),
                GenreCheckBox("gender-bender", "Gender Bender"),
                GenreCheckBox("harem", "Harem"),
                GenreCheckBox("historical", "Historical"),
                GenreCheckBox("horror", "Horror"),
                GenreCheckBox("josei", "Josei"),
                GenreCheckBox("martial-arts", "Martial Arts"),
                GenreCheckBox("mature", "Mature"),
                GenreCheckBox("mystery", "Mystery"),
                GenreCheckBox("psychological", "Psychological"),
                GenreCheckBox("romance", "Romance"),
                GenreCheckBox("school-life", "School Life"),
                GenreCheckBox("sci-fi", "Sci-fi"),
                GenreCheckBox("seinen", "Seinen"),
                GenreCheckBox("shoujo", "Shoujo"),
                GenreCheckBox("shounen", "Shounen"),
                GenreCheckBox("slice-of-life", "Slice of Life"),
                GenreCheckBox("sports", "Sports"),
                GenreCheckBox("supernatural", "Supernatural"),
                GenreCheckBox("tragedy", "Tragedy"),
                GenreCheckBox("updating", "Updating"),
                GenreCheckBox("wuxia", "Wuxia"),
                GenreCheckBox("xuanhuan", "Xuanhuan"),
                GenreCheckBox("yuri", "Yuri"),
            ),
        )

    private class StatusCheckBox(val value: String, name: String) : Filter.CheckBox(name)

    private class StatusFilter :
        Filter.Group<StatusCheckBox>(
            "Status",
            listOf(
                StatusCheckBox("on-going", "OnGoing"),
                StatusCheckBox("end", "Completed"),
                StatusCheckBox("canceled", "Canceled"),
                StatusCheckBox("on-hold", "On Hold"),
                StatusCheckBox("upcoming", "Upcoming"),
            ),
        )

    private class AdultFilter :
        Filter.Select<String>(
            "Adult content",
            arrayOf("All", "None adult content", "Only adult content"),
        ) {
        fun toUriPart() = when (state) {
            1 -> "0"
            2 -> "1"
            else -> ""
        }
    }

    private class AuthorFilter : Filter.Text("Author")

    private class ArtistFilter : Filter.Text("Artist")

    private class ReleaseFilter : Filter.Text("Year of Release")
}

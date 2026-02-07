package eu.kanade.tachiyomi.extension.en.srankmanga

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

class SrankManga : MadaraNovel(
    baseUrl = "https://srankmanga.com",
    name = "Srank Manga",
    lang = "en",
) {
    override val useNewChapterEndpointDefault = true

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("s", query)
            addQueryParameter("post_type", "wp-manga")

            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> {
                        filter.state.forEachIndexed { index, genre ->
                            if (genre.state) {
                                addQueryParameter("genre[$index]", genre.id)
                            }
                        }
                    }
                    is StatusFilter -> {
                        val status = filter.toUriPart()
                        if (status.isNotEmpty()) {
                            addQueryParameter("status[]", status)
                        }
                    }
                    is SortFilter -> {
                        val sort = filter.toUriPart()
                        if (sort.isNotEmpty()) {
                            addQueryParameter("m_orderby", sort)
                        }
                    }
                    is GenreConditionFilter -> {
                        if (filter.state == 1) {
                            addQueryParameter("op", "1")
                        }
                    }
                    else -> {}
                }
            }
        }.build()

        val finalUrl = if (page > 1) {
            "$baseUrl/page/$page/?${url.query}"
        } else {
            url.toString()
        }

        return GET(finalUrl, headers)
    }

    override fun getFilterList() = FilterList(
        GenreFilter(),
        GenreConditionFilter(),
        StatusFilter(),
        SortFilter(),
    )

    private class GenreFilter : Filter.Group<Genre>(
        "Genre",
        listOf(
            Genre("Academy", "academy"),
            Genre("Accelerated Growth", "accelerated-growth"),
            Genre("Action", "action"),
            Genre("Adventure", "adventure"),
            Genre("Ag Jung Seon", "ag-jung-seon"),
            Genre("Bloodline", "bloodline"),
            Genre("Can Can", "can-can"),
            Genre("Comedy", "comedy"),
            Genre("Drama", "drama"),
            Genre("Eastern", "eastern"),
            Genre("Ecchi", "ecchi"),
            Genre("Fantasy", "fantasy"),
            Genre("Free", "free"),
            Genre("Gender Bender", "gender-bender"),
            Genre("Harem", "harem"),
            Genre("Historical", "historical"),
            Genre("Horror", "horror"),
            Genre("Isekai", "isekai"),
            Genre("Josei", "josei"),
            Genre("Martial Arts", "martial-arts"),
            Genre("Mature", "mature"),
            Genre("Mecha", "mecha"),
            Genre("Mystery", "mystery"),
            Genre("Psychological", "psychological"),
            Genre("Romance", "romance"),
            Genre("School Life", "school-life"),
            Genre("Sci-fi", "sci-fi"),
            Genre("Seinen", "seinen"),
            Genre("Shoujo", "shoujo"),
            Genre("Shounen", "shounen"),
            Genre("Slice of Life", "slice-of-life"),
            Genre("Smut", "smut"),
            Genre("Sports", "sports"),
            Genre("Supernatural", "supernatural"),
            Genre("Tragedy", "tragedy"),
            Genre("Wuxia", "wuxia"),
            Genre("Xianxia", "xianxia"),
            Genre("Xuanhuan", "xuanhuan"),
            Genre("Yaoi", "yaoi"),
            Genre("Yuri", "yuri"),
        ),
    )

    private class Genre(name: String, val id: String) : Filter.CheckBox(name)

    private class GenreConditionFilter : Filter.Select<String>(
        "Genre Condition",
        arrayOf("OR (having one of selected)", "AND (having all selected)"),
    )

    private class StatusFilter : Filter.Select<String>(
        "Status",
        arrayOf("All", "OnGoing", "Completed", "Canceled", "On Hold", "Upcoming"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "on-going"
            2 -> "end"
            3 -> "canceled"
            4 -> "on-hold"
            5 -> "upcoming"
            else -> ""
        }
    }

    private class SortFilter : Filter.Select<String>(
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
}

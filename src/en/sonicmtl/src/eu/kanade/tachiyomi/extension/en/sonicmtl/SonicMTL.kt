package eu.kanade.tachiyomi.extension.en.sonicmtl

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

class SonicMTL : MadaraNovel(
    baseUrl = "https://www.sonicmtl.com",
    name = "Sonic MTL",
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
                    is AdultFilter -> {
                        val adult = filter.toUriPart()
                        if (adult.isNotEmpty()) {
                            addQueryParameter("adult", adult)
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

            if (page > 1) {
                // For pagination, add page to path
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
        AdultFilter(),
        SortFilter(),
    )

    private class GenreFilter : Filter.Group<Genre>(
        "Genre",
        listOf(
            Genre("Action", "action"),
            Genre("Adult", "adult"),
            Genre("Adventure", "adventure"),
            Genre("Comedy", "comedy"),
            Genre("Cooking", "cooking"),
            Genre("Detective", "detective"),
            Genre("Doujinshi", "doujinshi"),
            Genre("Drama", "drama"),
            Genre("Ecchi", "ecchi"),
            Genre("Fan-Fiction", "fan-fiction"),
            Genre("Fantasy", "fantasy"),
            Genre("Gender Bender", "gender-bender"),
            Genre("Harem", "harem"),
            Genre("Historical", "historical"),
            Genre("Horror", "horror"),
            Genre("Josei", "josei"),
            Genre("Live action", "live-action"),
            Genre("Manga", "manga"),
            Genre("Manhua", "manhua"),
            Genre("Manhwa", "manhwa"),
            Genre("Martial Arts", "martial-arts"),
            Genre("Mature", "mature"),
            Genre("Mecha", "mecha"),
            Genre("Mystery", "mystery"),
            Genre("One shot", "one-shot"),
            Genre("Psychological", "psychological"),
            Genre("Romance", "romance"),
            Genre("School Life", "school-life"),
            Genre("Sci-fi", "sci-fi"),
            Genre("Seinen", "seinen"),
            Genre("Shoujo", "shoujo"),
            Genre("Shoujo Ai", "shoujo-ai"),
            Genre("Shounen", "shounen"),
            Genre("Shounen Ai", "shounen-ai"),
            Genre("Slice of Life", "slice-of-life"),
            Genre("Smut", "smut"),
            Genre("Soft Yaoi", "soft-yaoi"),
            Genre("Soft Yuri", "soft-yuri"),
            Genre("Sports", "sports"),
            Genre("Supernatural", "supernatural"),
            Genre("Tragedy", "tragedy"),
            Genre("Urban Life", "urban-life"),
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

    private class AdultFilter : Filter.Select<String>(
        "Adult Content",
        arrayOf("All", "No Adult", "Only Adult"),
    ) {
        fun toUriPart() = when (state) {
            1 -> "0"
            2 -> "1"
            else -> ""
        }
    }

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

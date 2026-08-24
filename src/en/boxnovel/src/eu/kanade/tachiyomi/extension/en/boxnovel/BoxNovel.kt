package eu.kanade.tachiyomi.novelextension.en.boxnovel

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

class BoxNovel :
    MadaraNovel(
        baseUrl = "https://novelnice.com",
        name = "BoxNovel",
        lang = "en",
    ) {
    override val useNewChapterEndpointDefault = true
    override val reverseChapterListDefault = true

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/page/$page/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("post_type", "wp-manga")

        filters.forEach { filter ->
            when (filter) {
                is AuthorFilter -> if (filter.state.isNotBlank()) {
                    url.addQueryParameter("author", filter.state)
                }

                is ArtistFilter -> if (filter.state.isNotBlank()) {
                    url.addQueryParameter("artist", filter.state)
                }

                is YearFilter -> if (filter.state.isNotBlank()) {
                    url.addQueryParameter("release", filter.state)
                }

                is AdultFilter -> {
                    val value = adultOptions[filter.state].second
                    if (value.isNotEmpty()) {
                        url.addQueryParameter("adult", value)
                    }
                }

                is OrderByFilter -> {
                    val value = orderByOptions[filter.state].second
                    if (value.isNotEmpty()) {
                        url.addQueryParameter("m_orderby", value)
                    }
                }

                is GenreConditionFilter -> if (filter.state) {
                    url.addQueryParameter("op", "1")
                }

                is StatusFilter ->
                    filter.state
                        .filter { it.state }
                        .forEach { url.addQueryParameter("status[]", it.value) }

                is GenreFilter ->
                    filter.state
                        .filter { it.state }
                        .forEach { url.addQueryParameter("genre[]", it.value) }

                else -> {}
            }
        }

        return GET(url.build(), headers)
    }

    override fun getFilterList() = FilterList(
        AuthorFilter(),
        ArtistFilter(),
        YearFilter(),
        AdultFilter(),
        OrderByFilter(),
        GenreConditionFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    private class AuthorFilter : Filter.Text("Author")
    private class ArtistFilter : Filter.Text("Artist")
    private class YearFilter : Filter.Text("Year of Released")

    private class AdultFilter : Filter.Select<String>("Adult content", adultOptions.map { it.first }.toTypedArray())

    private class OrderByFilter : Filter.Select<String>("Order by", orderByOptions.map { it.first }.toTypedArray())

    private class GenreConditionFilter : Filter.CheckBox("Having all selected genres")

    private class GenreCheckBox(name: String, val value: String) : Filter.CheckBox(name)

    private class StatusFilter : Filter.Group<GenreCheckBox>("Status", statusOptions.map { GenreCheckBox(it.first, it.second) })

    private class GenreFilter : Filter.Group<GenreCheckBox>("Genre", genreOptions.map { GenreCheckBox(it.first, it.second) })

    companion object {
        private val adultOptions = listOf(
            Pair("All", ""),
            Pair("None adult content", "0"),
            Pair("Only adult content", "1"),
        )

        private val orderByOptions = listOf(
            Pair("Relevance", ""),
            Pair("Latest", "latest"),
            Pair("A-Z", "alphabet"),
            Pair("Rating", "rating"),
            Pair("Trending", "trending"),
            Pair("Most Views", "views"),
            Pair("New", "new-manga"),
        )

        private val statusOptions = listOf(
            Pair("OnGoing", "on-going"),
            Pair("Completed", "end"),
            Pair("Canceled", "canceled"),
            Pair("On Hold", "on-hold"),
            Pair("Upcoming", "upcoming"),
        )

        private val genreOptions = listOf(
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Anime & Comics", "anime-comics"),
            Pair("Comedy", "comedy"),
            Pair("Drama", "drama"),
            Pair("Eastern", "eastern"),
            Pair("Fan-fiction", "fan-fiction"),
            Pair("Fanfiction", "fanfiction"),
            Pair("Fantasy", "fantasy"),
            Pair("Game", "game"),
            Pair("Games", "games"),
            Pair("Gender Bender", "gender-bender"),
            Pair("General", "general"),
            Pair("Harem", "harem"),
            Pair("Historical", "historical"),
            Pair("Horror", "horror"),
            Pair("Isekai", "isekai"),
            Pair("Josei", "josei"),
            Pair("LitRPG", "litrpg"),
            Pair("Magic", "magic"),
            Pair("Magical Realism", "magical-realism"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mature", "mature"),
            Pair("Mecha", "mecha"),
            Pair("Modern Life", "modern-life"),
            Pair("Mystery", "mystery"),
            Pair("Other", "other"),
            Pair("Psychological", "psychological"),
            Pair("Reincarnation", "reincarnation"),
            Pair("Romance", "romance"),
            Pair("School Life", "school-life"),
            Pair("Sci-fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Shounen Ai", "shounen-ai"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Smut", "smut"),
            Pair("Sports", "sports"),
            Pair("Supernatural", "supernatural"),
            Pair("System", "system"),
            Pair("Thriller", "thriller"),
            Pair("Tragedy", "tragedy"),
            Pair("Urban", "urban"),
            Pair("Urban Life", "urban-life"),
            Pair("Video Games", "video-games"),
            Pair("War", "war"),
            Pair("Wuxia", "wuxia"),
            Pair("Xianxia", "xianxia"),
            Pair("Xuanhuan", "xuanhuan"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        )
    }
}

package eu.kanade.tachiyomi.novelextension.en.translatino

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * TranslatinOtaku / WebNovelTranslations - Madara-based novel site.
 */
class TranslatinOtaku :
    MadaraNovel(
        baseUrl = "https://translatinotaku.net",
        name = "Translatin Otaku",
        lang = "en",
    ) {
    override val useNewChapterEndpointDefault = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/page/$page/?s=&post_type=wp-manga&m_orderby=trending", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val urlBuilder = "$baseUrl/page/$page/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("post_type", "wp-manga")

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter ->
                    filter.state
                        .filter { it.state }
                        .forEach { urlBuilder.addQueryParameter("genre[]", it.id) }

                is GenreConditionFilter -> if (filter.state == 1) {
                    urlBuilder.addQueryParameter("op", "1")
                }

                is AuthorFilter -> if (filter.state.isNotBlank()) {
                    urlBuilder.addQueryParameter("author", filter.state)
                }

                is ArtistFilter -> if (filter.state.isNotBlank()) {
                    urlBuilder.addQueryParameter("artist", filter.state)
                }

                is ReleaseYearFilter -> if (filter.state.isNotBlank()) {
                    urlBuilder.addQueryParameter("release", filter.state)
                }

                is AdultContentFilter -> {
                    val value = filter.toUriPart()
                    if (value.isNotEmpty()) urlBuilder.addQueryParameter("adult", value)
                }

                is StatusGroupFilter ->
                    filter.state
                        .filter { it.state }
                        .forEach { urlBuilder.addQueryParameter("status[]", it.id) }

                else -> {}
            }
        }

        return GET(urlBuilder.build().toString(), headers)
    }

    // ======================== Filters ========================

    override fun getFilterList() = FilterList(
        GenreFilter(getGenreList()),
        GenreConditionFilter(),
        AuthorFilter(),
        ArtistFilter(),
        ReleaseYearFilter(),
        AdultContentFilter(),
        StatusGroupFilter(getStatusList()),
    )

    private class GenreCheckBox(name: String, val id: String) : Filter.CheckBox(name)
    private class GenreFilter(genres: List<Pair<String, String>>) :
        Filter.Group<GenreCheckBox>(
            "Genres",
            genres.map { GenreCheckBox(it.first, it.second) },
        )

    private class GenreConditionFilter :
        Filter.Select<String>(
            "Genres condition",
            arrayOf("OR (having one)", "AND (having all)"),
            0,
        )

    private class AuthorFilter : Filter.Text("Author")
    private class ArtistFilter : Filter.Text("Artist")
    private class ReleaseYearFilter : Filter.Text("Year of Release")

    private class AdultContentFilter :
        Filter.Select<String>(
            "Adult content",
            arrayOf("All", "None adult", "Only adult"),
            0,
        ) {
        fun toUriPart() = when (state) {
            1 -> "0"
            2 -> "1"
            else -> ""
        }
    }

    private class StatusCheckBox(name: String, val id: String) : Filter.CheckBox(name)
    private class StatusGroupFilter(statuses: List<Pair<String, String>>) :
        Filter.Group<StatusCheckBox>(
            "Status",
            statuses.map { StatusCheckBox(it.first, it.second) },
        )

    private fun getGenreList() = listOf(
        "Action" to "action", "Adult" to "adult", "Adventure" to "adventure",
        "Anime" to "anime", "Cartoon" to "cartoon", "Comedy" to "comedy",
        "Comic" to "comic", "Cooking" to "cooking", "Detective" to "detective",
        "Doujinshi" to "doujinshi", "Drama" to "drama", "Ecchi" to "ecchi",
        "Fantasy" to "fantasy", "Gender Bender" to "gender-bender", "Harem" to "harem",
        "Historical" to "historical", "Horror" to "horror", "Josei" to "josei",
        "Live action" to "live-action", "Novel" to "manga", "Manhua" to "manhua",
        "Manhwa" to "manhwa", "Martial Arts" to "martial-arts", "Mature" to "mature",
        "Mecha" to "mecha", "Mystery" to "mystery", "One shot" to "one-shot",
        "Psychological" to "psychological", "Romance" to "romance", "School Life" to "school-life",
        "Sci-fi" to "sci-fi", "Seinen" to "seinen", "Shoujo" to "shoujo",
        "Shoujo Ai" to "shoujo-ai", "Shounen" to "shounen", "Shounen Ai" to "shounen-ai",
        "Slice of Life" to "slice-of-life", "Smut" to "smut", "Soft Yaoi" to "soft-yaoi",
        "Soft Yuri" to "soft-yuri", "Sports" to "sports", "Supernatural" to "supernatural",
        "Tragedy" to "tragedy", "Webtoon" to "webtoon", "Yaoi" to "yaoi", "Yuri" to "yuri",
    )

    private fun getStatusList() = listOf(
        "OnGoing" to "on-going",
        "Completed" to "end",
        "Canceled" to "canceled",
        "On Hold" to "on-hold",
        "Upcoming" to "upcoming",
    )
}

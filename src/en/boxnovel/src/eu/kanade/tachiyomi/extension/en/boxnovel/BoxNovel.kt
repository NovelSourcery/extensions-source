package eu.kanade.tachiyomi.extension.en.boxnovel

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.Request

class BoxNovel : ReadNovelFull(
    name = "BoxNovel",
    baseUrl = "https://novlove.com",
    lang = "en",
) {
    override val latestPage = "sort/nov-love-daily-update"

    // BoxNovel uses path-based filtering instead of query parameters
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            return super.searchMangaRequest(page, query, filters)
        }

        // Handle filters
        var path = ""
        filters.forEach { filter ->
            when (filter) {
                is TypeFilter -> {
                    if (filter.state > 0) {
                        path = typeOptions[filter.state].second
                    }
                }
                is GenreFilter -> {
                    if (filter.state > 0) {
                        path = genreOptions[filter.state].second
                    }
                }
                else -> {}
            }
        }

        // Default to popular if no filter selected
        if (path.isEmpty()) {
            path = "sort/nov-love-popular"
        }

        // Add page number
        val url = if (page > 1) {
            "$baseUrl/$path?page=$page"
        } else {
            "$baseUrl/$path"
        }

        return GET(url, headers)
    }

    override fun getFilterList() = FilterList(
        Filter.Header("Filters are ignored with text search"),
        Filter.Separator(),
        TypeFilter(),
        GenreFilter(),
    )

    private class TypeFilter : Filter.Select<String>(
        "Type",
        typeOptions.map { it.first }.toTypedArray(),
    )

    private class GenreFilter : Filter.Select<String>(
        "Genre",
        genreOptions.map { it.first }.toTypedArray(),
    )

    companion object {
        private val typeOptions = listOf(
            Pair("All", ""),
            Pair("Hot Novel", "sort/nov-love-hot"),
            Pair("Completed Novel", "sort/nov-love-complete"),
            Pair("Most Popular", "sort/nov-love-popular"),
        )

        private val genreOptions = listOf(
            Pair("All", ""),
            Pair("Action", "nov-love-genres/action"),
            Pair("Adventure", "nov-love-genres/adventure"),
            Pair("Anime & Comics", "nov-love-genres/anime-&-comics"),
            Pair("Comedy", "nov-love-genres/comedy"),
            Pair("Drama", "nov-love-genres/drama"),
            Pair("Eastern", "nov-love-genres/eastern"),
            Pair("Fan-fiction", "nov-love-genres/fan-fiction"),
            Pair("Fanfiction", "nov-love-genres/fanfiction"),
            Pair("Fantasy", "nov-love-genres/fantasy"),
            Pair("Game", "nov-love-genres/game"),
            Pair("Games", "nov-love-genres/games"),
            Pair("Gender Bender", "nov-love-genres/gender-bender"),
            Pair("General", "nov-love-genres/general"),
            Pair("Harem", "nov-love-genres/harem"),
            Pair("Historical", "nov-love-genres/historical"),
            Pair("Horror", "nov-love-genres/horror"),
            Pair("Isekai", "nov-love-genres/isekai"),
            Pair("Josei", "nov-love-genres/josei"),
            Pair("LitRPG", "nov-love-genres/litrpg"),
            Pair("Magic", "nov-love-genres/magic"),
            Pair("Magical Realism", "nov-love-genres/magical-realism"),
            Pair("Martial Arts", "nov-love-genres/martial-arts"),
            Pair("Mature", "nov-love-genres/mature"),
            Pair("Mecha", "nov-love-genres/mecha"),
            Pair("Modern Life", "nov-love-genres/modern-life"),
            Pair("Mystery", "nov-love-genres/mystery"),
            Pair("Other", "nov-love-genres/other"),
            Pair("Psychological", "nov-love-genres/psychological"),
            Pair("Reincarnation", "nov-love-genres/reincarnation"),
            Pair("Romance", "nov-love-genres/romance"),
            Pair("School Life", "nov-love-genres/school-life"),
            Pair("Sci-fi", "nov-love-genres/sci-fi"),
            Pair("Seinen", "nov-love-genres/seinen"),
            Pair("Shoujo", "nov-love-genres/shoujo"),
            Pair("Shoujo Ai", "nov-love-genres/shoujo-ai"),
            Pair("Shounen", "nov-love-genres/shounen"),
            Pair("Shounen Ai", "nov-love-genres/shounen-ai"),
            Pair("Slice of Life", "nov-love-genres/slice-of-life"),
            Pair("Smut", "nov-love-genres/smut"),
            Pair("Sports", "nov-love-genres/sports"),
            Pair("Supernatural", "nov-love-genres/supernatural"),
            Pair("System", "nov-love-genres/system"),
            Pair("Thriller", "nov-love-genres/thriller"),
            Pair("Tragedy", "nov-love-genres/tragedy"),
            Pair("Urban", "nov-love-genres/urban"),
            Pair("Urban Life", "nov-love-genres/urban-life"),
            Pair("Video Games", "nov-love-genres/video-games"),
            Pair("War", "nov-love-genres/war"),
            Pair("Wuxia", "nov-love-genres/wuxia"),
            Pair("Xianxia", "nov-love-genres/xianxia"),
            Pair("Xuanhuan", "nov-love-genres/xuanhuan"),
            Pair("Yaoi", "nov-love-genres/yaoi"),
            Pair("Yuri", "nov-love-genres/yuri"),
        )
    }
}

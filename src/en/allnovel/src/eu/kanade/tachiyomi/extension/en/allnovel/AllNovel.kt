package eu.kanade.tachiyomi.extension.en.allnovel

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull

class AllNovel :
    ReadNovelFull(
        name = "AllNovel",
        baseUrl = "https://allnovel.org",
        lang = "en",
    ) {
    override val latestPage = "latest-release-novel"
    override val searchPage = "search"
    override val chapterListing = "ajax-chapter-option"

    override fun getTypeOptions() = listOf(
        "Most Popular" to "most-popular",
        "Hot Novel" to "hot-novel",
        "Completed Novel" to "completed-novel",
    )

    override fun getGenreOptions() = listOf(
        "All" to "",
        "Shounen" to "genre/Shounen",
        "Harem" to "genre/Harem",
        "Comedy" to "genre/Comedy",
        "Martial Arts" to "genre/Martial+Arts",
        "School Life" to "genre/School+Life",
        "Mystery" to "genre/Mystery",
        "Shoujo" to "genre/Shoujo",
        "Romance" to "genre/Romance",
        "Sci-fi" to "genre/Sci-fi",
        "Gender Bender" to "genre/Gender+Bender",
        "Mature" to "genre/Mature",
        "Fantasy" to "genre/Fantasy",
        "Horror" to "genre/Horror",
        "Drama" to "genre/Drama",
        "Tragedy" to "genre/Tragedy",
        "Supernatural" to "genre/Supernatural",
        "Ecchi" to "genre/Ecchi",
        "Xuanhuan" to "genre/Xuanhuan",
        "Adventure" to "genre/Adventure",
        "Action" to "genre/Action",
        "Psychological" to "genre/Psychological",
        "Xianxia" to "genre/Xianxia",
        "Wuxia" to "genre/Wuxia",
        "Historical" to "genre/Historical",
        "Slice of Life" to "genre/Slice+of+Life",
        "Seinen" to "genre/Seinen",
        "Lolicon" to "genre/Lolicon",
        "Adult" to "genre/Adult",
        "Josei" to "genre/Josei",
        "Sports" to "genre/Sports",
        "Smut" to "genre/Smut",
        "Mecha" to "genre/Mecha",
        "Yaoi" to "genre/Yaoi",
        "Shounen Ai" to "genre/Shounen+Ai",
        "History" to "genre/History",
        "Reincarnation" to "genre/Reincarnation",
    )
}

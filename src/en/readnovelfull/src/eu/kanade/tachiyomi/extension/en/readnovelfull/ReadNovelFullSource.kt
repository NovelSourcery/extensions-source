package eu.kanade.tachiyomi.extension.en.readnovelfull

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull as ReadNovelFullBase

class ReadNovelFullSource :
    ReadNovelFullBase(
        name = "ReadNovelFull",
        baseUrl = "https://readnovelfull.com",
        lang = "en",
    ) {
    override val popularPage = "novel-list/most-popular-novel"
    override val latestPage = "novel-list/latest-release-novel"
    override val searchPage = "novel-list/search"

    override fun getTypeOptions() = listOf(
        "Most Popular" to "novel-list/most-popular-novel",
        "Hot Novel" to "novel-list/hot-novel",
        "Completed Novel" to "novel-list/completed-novel",
    )

    override fun getGenreOptions() = listOf(
        "All" to "",
        "Action" to "genres/action",
        "Adult" to "genres/adult",
        "Adventure" to "genres/adventure",
        "Comedy" to "genres/comedy",
        "Drama" to "genres/drama",
        "Eastern" to "genres/eastern",
        "Ecchi" to "genres/ecchi",
        "Fantasy" to "genres/fantasy",
        "Game" to "genres/game",
        "Gender Bender" to "genres/gender+bender",
        "Harem" to "genres/harem",
        "Historical" to "genres/historical",
        "Horror" to "genres/horror",
        "Josei" to "genres/josei",
        "Lolicon" to "genres/lolicon",
        "Martial Arts" to "genres/martial+arts",
        "Mature" to "genres/mature",
        "Mecha" to "genres/mecha",
        "Modern Life" to "genres/modern+life",
        "Mystery" to "genres/mystery",
        "Psychological" to "genres/psychological",
        "Reincarnation" to "genres/reincarnation",
        "Romance" to "genres/romance",
        "School Life" to "genres/school+life",
        "Sci-fi" to "genres/sci-fi",
        "Seinen" to "genres/seinen",
        "Shoujo" to "genres/shoujo",
        "Shounen" to "genres/shounen",
        "Slice of Life" to "genres/slice+of+life",
        "Smut" to "genres/smut",
        "Sports" to "genres/sports",
        "Supernatural" to "genres/supernatural",
        "System" to "genres/system",
        "Thriller" to "genres/thriller",
        "Tragedy" to "genres/tragedy",
        "Transmigration" to "genres/transmigration",
    )
}

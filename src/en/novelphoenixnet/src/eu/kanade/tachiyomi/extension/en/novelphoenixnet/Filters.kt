package eu.kanade.tachiyomi.novelextension.en.novelphoenixnet

import eu.kanade.tachiyomi.source.model.Filter

open class SelectFilter(name: String, private val options: Array<Pair<String, String>>) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    val selected: String? get() = options[state].second.ifEmpty { null }
}

class ListingFilter :
    SelectFilter(
        "Listing",
        arrayOf(
            "Most Popular" to "most-popular",
            "Latest Release" to "latest-release-novel",
            "New Novels" to "new-novel",
            "Completed" to "completed-novel",
        ),
    )

// Values are the site's already-encoded /genre/<value> path segments.
class GenreFilter :
    SelectFilter(
        "Genre (overrides listing)",
        arrayOf(
            "None" to "",
            "Action" to "Action",
            "Adult" to "Adult",
            "Adventure" to "Adventure",
            "Comedy" to "Comedy",
            "Drama" to "Drama",
            "Eastern" to "Eastern",
            "Ecchi" to "Ecchi",
            "Fan-fiction" to "Fan-fiction",
            "Fantasy" to "Fantasy",
            "Game" to "Game",
            "Gender Bender" to "Gender+Bender",
            "General" to "General",
            "Harem" to "Harem",
            "Hentai" to "Hentai",
            "Historical" to "Historical",
            "Horror" to "Horror",
            "Isekai" to "Isekai",
            "Josei" to "Josei",
            "LGBT+" to "LGBT%2B",
            "Magic" to "Magic",
            "Magical" to "Magical",
            "Magical Realism" to "Magical+Realism",
            "Manhua" to "Manhua",
            "Martial" to "Martial",
            "Martial Arts" to "Martial+Arts",
            "Mature" to "Mature",
            "Mecha" to "Mecha",
            "Military" to "Military",
            "Mystery" to "Mystery",
            "Other" to "Other",
            "Psychological" to "Psychological",
            "Reincarnation" to "Reincarnation",
            "Romance" to "Romance",
            "School" to "School",
            "School Life" to "School+Life",
            "Sci-fi" to "Sci-fi",
            "Seinen" to "Seinen",
            "Shoujo" to "Shoujo",
            "Shounen" to "Shounen",
            "Shounen Ai" to "Shounen+Ai",
            "Slice of Life" to "Slice+of+Life",
            "Smut" to "Smut",
            "Sports" to "Sports",
            "Supernatural" to "Supernatural",
            "System" to "System",
            "Tragedy" to "Tragedy",
            "Urban" to "Urban",
            "Video games" to "Video+games",
            "War" to "War",
            "Wuxia" to "Wuxia",
            "Xianxia" to "Xianxia",
            "Xuanhuan" to "Xuanhuan",
            "Yaoi" to "Yaoi",
            "Yuri" to "Yuri",
        ),
    )

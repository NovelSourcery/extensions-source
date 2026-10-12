package eu.kanade.tachiyomi.novelextension.en.xnovel

import eu.kanade.tachiyomi.source.model.Filter

class SortFilter(default: String = "field_update") :
    Filter.Select<String>(
        "Order by",
        SORT_OPTIONS.map { it.first }.toTypedArray(),
        SORT_OPTIONS.indexOfFirst { it.second == default },
    ) {
    val value get() = SORT_OPTIONS[state].second
}

class GenreTriState(name: String, val slug: String) : Filter.TriState(name)

class GenreFilter :
    Filter.Group<GenreTriState>(
        "Genres",
        GENRES.map { (slug, name) -> GenreTriState(name, slug) },
    ) {
    val included get() = state.filter { it.isIncluded() }.map { it.slug }
    val excluded get() = state.filter { it.isExcluded() }.map { it.slug }
}

private val SORT_OPTIONS = listOf(
    "Recently Updated" to "field_update",
    "Most Follows" to "field_follow",
    "Most Reviews" to "field_review",
    "Most Comments" to "field_comment",
    "Most Chapters" to "field_chapter",
    "Title" to "field_name",
)

// Mirrors the site's static genre table; slugs are what the API stores in info_meta_Genre.
val GENRES = linkedMapOf(
    "action" to "Action",
    "adult" to "Adult",
    "adventure" to "Adventure",
    "anime-&-comics" to "Anime & Comics",
    "comedy" to "Comedy",
    "drama" to "Drama",
    "eastern" to "Eastern",
    "ecchi" to "Ecchi",
    "fan-fiction" to "Fan-Fiction",
    "fantasy" to "Fantasy",
    "game" to "Game",
    "gender-bender" to "Gender Bender",
    "harem" to "Harem",
    "historical" to "Historical",
    "horror" to "Horror",
    "isekai" to "Isekai",
    "josei" to "Josei",
    "lgbt+" to "LGBT+",
    "litrpg" to "LitRPG",
    "magic" to "Magic",
    "magical-realism" to "Magical Realism",
    "martial-arts" to "Martial Arts",
    "mature" to "Mature",
    "mecha" to "Mecha",
    "military" to "Military",
    "modern-life" to "Modern Life",
    "mystery" to "Mystery",
    "other" to "Other",
    "psychological" to "Psychological",
    "reincarnation" to "Reincarnation",
    "romance" to "Romance",
    "school-life" to "School Life",
    "sci-fi" to "Sci-fi",
    "seinen" to "Seinen",
    "shoujo" to "Shoujo",
    "shoujo-ai" to "Shoujo Ai",
    "shounen" to "Shounen",
    "shounen-ai" to "Shounen Ai",
    "slice-of-life" to "Slice of Life",
    "smut" to "Smut",
    "sports" to "Sports",
    "supernatural" to "Supernatural",
    "system" to "System",
    "thriller" to "Thriller",
    "tragedy" to "Tragedy",
    "urban" to "Urban",
    "video-games" to "Video Games",
    "war" to "War",
    "wuxia" to "Wuxia",
    "xianxia" to "Xianxia",
    "xuanhuan" to "Xuanhuan",
    "yaoi" to "Yaoi",
    "yuri" to "Yuri",
)

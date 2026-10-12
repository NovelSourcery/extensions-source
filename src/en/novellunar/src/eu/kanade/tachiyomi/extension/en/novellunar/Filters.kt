package eu.kanade.tachiyomi.novelextension.en.novellunar

import eu.kanade.tachiyomi.source.model.Filter

open class SelectFilter(name: String, private val options: Array<Pair<String, String>>) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
    fun toValue(): String = options[state].second
}

class ListFilter :
    SelectFilter(
        "List",
        arrayOf(
            "Ranking" to "ranking",
            "Latest" to "latest",
            "New" to "new",
            "Completed" to "completed",
        ),
    )

class StatusFilter :
    SelectFilter(
        "Status",
        arrayOf(
            "All" to "",
            "Ongoing" to "Ongoing",
            "Completed" to "Completed",
        ),
    )

class GenreFilter : SelectFilter("Genre", arrayOf("All" to "") + GENRES.map { it to it })

private val GENRES = listOf(
    "Action", "Adventure", "Comedy", "Drama", "Eastern", "Fan Fiction", "Fantasy", "Game",
    "Gender Bender", "Harem", "Historical", "Horror", "Isekai", "Josei", "Magic", "Martial Arts",
    "Mecha", "Military", "Mystery", "Other", "Psychological", "Reincarnation", "Romance",
    "School Life", "Sci-Fi", "Seinen", "Shoujo", "Shounen", "Slice of Life", "Sports", "Supernatural",
    "System", "Tragedy", "Urban", "Video Games", "War", "Wuxia", "Xianxia", "Xuanhuan", "Werewolf",
    "Badboy", "Possessive", "Rejected", "Vampire", "Baby", "Teen", "Kidnapping", "Betrayal",
    "Love-Triangle", "bully love", "Adult", "Mature", "Smut", "Yaoi", "Litrpg", "Ecchi", "Shounen ai",
    "Yuri", "Anime & comics", "Thriller", "Shoujo ai", "Magical realism", "Modern Life", "Chinese", "H",
    "Lolicon", "Xuanhua", "Transmigration", "Fanfiction", "Hentai", "Gender", "Bender", "History", "Martial",
)

package eu.kanade.tachiyomi.novelextension.en.allnovelfull

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.Request

@Serializable
private class GenreEntry(val name: String, val path: String)

@Source
abstract class Novgo : ReadNovelFull() {
    override val latestPage = "latest-release-novel"
    override val searchPage = "search"

    override val chaptersPaginated = true
    override val chapterListPageSize = 50

    override fun chapterListPageRequest(manga: SManga, page: Int): Request {
        val base = mangaPathTemplate.absolute(baseUrl, manga.url).trimEnd('/')
        val url = if (page <= 1) base else "$base?page=$page"
        return GET(url, headers)
    }

    override fun popularMangaNextPageSelector() = "${super.popularMangaNextPageSelector()}, a[rel=next]"

    override fun getTypeOptions() = listOf(
        "All" to "all",
        "Latest Release" to "latest-release-novel",
        "New Novel" to "new-novel",
        "Most Popular" to "most-popular",
        "Hot Novel" to "hot-novel",
        "Completed Novel" to "completed-novel",
    )

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement {
        val doc = client.newCall(GET(baseUrl, headers)).execute().asJsoup()
        return doc.select("ul.fwn-genres-mega-list li a").mapNotNull { a ->
            val name = a.text().trim()
            val path = a.attr("href").trim().removePrefix("/")
            if (name.isBlank() || path.isBlank()) null else GenreEntry(name, path)
        }.toJsonElement()
    }

    override fun getGenreList(data: JsonElement?): List<Genre> {
        val fetched = data
            ?.let { runCatching { it.parseAs<List<GenreEntry>>() }.getOrNull() }
            ?.filter { it.name.isNotBlank() && it.path.isNotBlank() }

        if (fetched.isNullOrEmpty()) return staticGenres
        return listOf(Genre("All", "")) + fetched.map { Genre(it.name, it.path) }
    }

    companion object {
        private val staticGenres = listOf(
            Genre("All", ""),
            Genre("Harem", "genre/Harem"),
            Genre("Gender Bender", "genre/Gender+Bender"),
            Genre("Sci-fi", "genre/Sci-fi"),
            Genre("Mature", "genre/Mature"),
            Genre("Drama", "genre/Drama"),
            Genre("Tragedy", "genre/Tragedy"),
            Genre("Shounen", "genre/Shounen"),
            Genre("Horror", "genre/Horror"),
            Genre("Mystery", "genre/Mystery"),
            Genre("Shoujo", "genre/Shoujo"),
            Genre("Psychological", "genre/Psychological"),
            Genre("Adventure", "genre/Adventure"),
            Genre("School Life", "genre/School+Life"),
            Genre("Xuanhuan", "genre/Xuanhuan"),
            Genre("Comedy", "genre/Comedy"),
            Genre("Ecchi", "genre/Ecchi"),
            Genre("Martial Arts", "genre/Martial+Arts"),
            Genre("Action", "genre/Action"),
            Genre("Fantasy", "genre/Fantasy"),
            Genre("Romance", "genre/Romance"),
            Genre("Supernatural", "genre/Supernatural"),
            Genre("Xianxia", "genre/Xianxia"),
            Genre("Wuxia", "genre/Wuxia"),
            Genre("Historical", "genre/Historical"),
            Genre("Slice of Life", "genre/Slice+of+Life"),
            Genre("Adult", "genre/Adult"),
            Genre("Josei", "genre/Josei"),
            Genre("Sports", "genre/Sports"),
            Genre("Smut", "genre/Smut"),
            Genre("Mecha", "genre/Mecha"),
            Genre("Yaoi", "genre/Yaoi"),
            Genre("Shounen Ai", "genre/Shounen+Ai"),
            Genre("Magical Realism", "genre/Magical+Realism"),
            Genre("Martial", "genre/Martial"),
            Genre("Game", "genre/Game"),
            Genre("Yuri", "genre/Yuri"),
            Genre("Magical", "genre/Magical"),
            Genre("Reincarnation", "genre/Reincarnation"),
            Genre("LGBT+", "genre/LGBT%2B"),
            Genre("Manhua", "genre/Manhua"),
            Genre("Traged", "genre/Traged"),
            Genre("Isekai", "genre/Isekai"),
            Genre("Magic", "genre/Magic"),
            Genre("Eastern", "genre/Eastern"),
            Genre("System", "genre/System"),
            Genre("Hentai", "genre/Hentai"),
            Genre("School", "genre/School"),
            Genre("Urban", "genre/Urban"),
            Genre("Fan-fiction", "genre/Fan-fiction"),
            Genre("General", "genre/General"),
            Genre("Military", "genre/Military"),
            Genre("Seinen", "genre/Seinen"),
            Genre("Video games", "genre/Video+games"),
            Genre("Other", "genre/Other"),
            Genre("War", "genre/War"),
        )
    }
}

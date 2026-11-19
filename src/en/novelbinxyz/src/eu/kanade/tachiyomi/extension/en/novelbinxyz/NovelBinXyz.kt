package eu.kanade.tachiyomi.novelextension.en.novelbinxyz

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Response

@Source
abstract class NovelBinXyz :
    KeiSource(),
    NovelSource {

    // The site's JSON lives on BunnyCDN, which answers 403 without the site's Origin.
    private val apiHeaders: Headers by lazy { headers.newBuilder().set("Origin", baseUrl).build() }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val novels = api("lists/web_popular.json").parseAs<List<ListNovelDto>>()
        return MangasPage(novels.map { it.toSManga() }, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = listPage("latest_$page")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) {
            filters.firstInstanceOrNull<GenreFilter>()?.value?.let { return listPage("genre_${it}_$page") }
            filters.firstInstanceOrNull<StatusFilter>()?.value?.let { return listPage("status_${it}_$page") }
            return getPopularManga(page)
        }
        // The site searches one index file client side, so do the same.
        val term = query.trim().lowercase()
        val matches = api("lists/web_search.json").parseAs<List<SearchNovelDto>>().filter { it.matches(term) }
        val from = (page - 1) * SEARCH_PAGE_SIZE
        return MangasPage(
            matches.drop(from).take(SEARCH_PAGE_SIZE).map { it.toSManga() },
            matches.size > from + SEARCH_PAGE_SIZE,
        )
    }

    private suspend fun listPage(name: String): MangasPage {
        val list = api("lists/web_$name.json").parseAs<ListPageDto>()
        return MangasPage(list.data.map { it.toSManga() }, list.hasMore)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novel/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/read/${chapter.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.firstOrNull() !in URL_PREFIXES) return null
        val slug = segments.getOrNull(1) ?: return null
        return fetchNovel(slug).toSManga()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val novel = fetchNovel(manga.url)
        val chapterList = if (fetchChapters) {
            api("chapters/${novel.id}.json").parseAs<List<ChapterDto>>()
                .sortedByDescending { it.id }
                .map { it.toSChapter(novel.slug) }
        } else {
            chapters
        }
        return SMangaUpdate(if (fetchDetails) novel.toSManga() else manga, chapterList)
    }

    private suspend fun fetchNovel(slug: String): NovelDto = api("novel/$slug.json").parseAs()

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val novel = fetchNovel(page.url.substringBefore('/'))
        val index = page.url.substringAfterLast("chapter-").toInt() - 1
        return api("read/${novel.id}/$index.json").parseAs<ChapterContentDto>().content
            ?.takeIf { it.isNotBlank() }
            ?: throw Exception("Chapter not found")
    }

    private suspend fun api(path: String): Response = client.get("$API_URL/$path", apiHeaders, Fetch.XHR)

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Filters are ignored when searching by text"),
        GenreFilter(),
        StatusFilter(),
    )

    private open class UriPartFilter(name: String, private val options: Array<Pair<String, String>>) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val value: String?
            get() = options[state].second.ifEmpty { null }
    }

    private class StatusFilter :
        UriPartFilter(
            "Status (ignored with a genre)",
            arrayOf("All" to "", "Ongoing" to "ongoing", "Completed" to "completed"),
        )

    private class GenreFilter : UriPartFilter("Genre", GENRES)

    companion object {
        private const val API_URL = "https://boxnovela.b-cdn.net"
        private const val SEARCH_PAGE_SIZE = 50
        private val URL_PREFIXES = setOf("novel", "read")
        private val GENRES = arrayOf(
            "All" to "",
            "Action" to "action",
            "Adventure" to "adventure",
            "Comedy" to "comedy",
            "Drama" to "drama",
            "Eastern" to "eastern",
            "Fan Fiction" to "fan-fiction",
            "Fantasy" to "fantasy",
            "Game" to "game",
            "Harem" to "harem",
            "Historical" to "historical",
            "Horror" to "horror",
            "Isekai" to "isekai",
            "Josei" to "josei",
            "LitRPG" to "litrpg",
            "Magic" to "magic",
            "Magical Realism" to "magical-realism",
            "Martial Arts" to "martial-arts",
            "Mature" to "mature",
            "Mecha" to "mecha",
            "Military" to "military",
            "Modern Life" to "modern-life",
            "Mystery" to "mystery",
            "Other" to "other",
            "Psychological" to "psychological",
            "Reincarnation" to "reincarnation",
            "Romance" to "romance",
            "School Life" to "school-life",
            "Sci-Fi" to "sci-fi",
            "Seinen" to "seinen",
            "Shoujo" to "shoujo",
            "Shoujo Ai" to "shoujo-ai",
            "Shounen" to "shounen",
            "Shounen Ai" to "shounen-ai",
            "Slice of Life" to "slice-of-life",
            "Sports" to "sports",
            "Supernatural" to "supernatural",
            "System" to "system",
            "Thriller" to "thriller",
            "Tragedy" to "tragedy",
            "Urban" to "urban",
            "War" to "war",
            "Wuxia" to "wuxia",
            "Xianxia" to "xianxia",
            "Xuanhuan" to "xuanhuan",
            "Yaoi" to "yaoi",
            "Yuri" to "yuri",
        )
    }
}

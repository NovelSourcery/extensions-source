package eu.kanade.tachiyomi.extension.en.fenrirealm

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Fenrirealm :
    HttpSource(),
    NovelSource {

    override val name = "Fenrirealm"
    override val baseUrl = "https://fenrirealm.com"
    override val lang = "en"
    override val supportsLatest = true

    // isNovelSource is provided by NovelSource interface with default value true

    override val client = network.cloudflareClient

    private val json: Json by injectLazy()

    // API base URL - from instructions.txt: /api/new/v2
    private val apiBaseUrl = "$baseUrl/api/new/v2"

    override fun popularMangaRequest(page: Int): Request = GET("$apiBaseUrl/home/popular-series", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val novels = json.decodeFromString<List<NovelDto>>(response.body.string())
        return MangasPage(novels.map { it.toSManga(baseUrl) }, false)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$apiBaseUrl/series?page=$page&per_page=20&status=any&sort=latest", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val result = json.decodeFromString<SearchResponse>(response.body.string())
        val hasNextPage = result.meta.currentPage < result.meta.lastPage
        return MangasPage(result.data.map { it.toSManga(baseUrl) }, hasNextPage)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiBaseUrl/series".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", "20")
            if (query.isNotEmpty()) {
                addQueryParameter("search", query)
            }

            filters.forEach { filter ->
                when (filter) {
                    is StatusFilter -> addQueryParameter("status", filter.toUriPart())

                    is SortFilter -> addQueryParameter("sort", filter.toUriPart())

                    is TypeFilter -> {
                        val type = filter.toUriPart()
                        if (type.isNotEmpty()) {
                            addQueryParameter("type", type)
                        }
                    }

                    is GenreFilter -> {
                        filter.state.filter { it.isIncluded() }.forEach { genre ->
                            addQueryParameter("genres[]", genre.id.toString())
                        }
                        filter.state.filter { it.isExcluded() }.forEach { genre ->
                            addQueryParameter("exclude_genres[]", genre.id.toString())
                        }
                    }

                    is TagFilter -> {
                        filter.state.filter { it.isIncluded() }.forEach { tag ->
                            addQueryParameter("tags[]", tag.id.toString())
                        }
                        filter.state.filter { it.isExcluded() }.forEach { tag ->
                            addQueryParameter("exclude_tags[]", tag.id.toString())
                        }
                    }

                    else -> {}
                }
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = latestUpdatesParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.removePrefix("/").removeSuffix("/")
        return GET("$apiBaseUrl/series?search=$slug&per_page=1", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val result = json.decodeFromString<SearchResponse>(response.body.string())
        return result.data.firstOrNull()?.toSManga(baseUrl) ?: SManga.create()
    }

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.removePrefix("/").removeSuffix("/")
        return GET("$apiBaseUrl/series/$slug/chapters", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = json.decodeFromString<List<ChapterApiDto>>(response.body.string())
        val slug = response.request.url.pathSegments.dropLast(1).lastOrNull() ?: ""

        return chapters.mapIndexed { index, chapter ->
            SChapter.create().apply {
                url = "/series/$slug/chapter-${chapter.number}"
                name = buildString {
                    val isLocked = chapter.locked?.price?.let { it > 0 } ?: false
                    if (isLocked) append("ðŸ”’ ")
                    append("Chapter ${chapter.number}")
                    if (!chapter.title.isNullOrBlank() && chapter.title != "Chapter ${chapter.number}") {
                        append(" - ${chapter.title}")
                    }
                }
                chapter_number = chapter.number.toFloat()
                date_upload = parseDate(chapter.createdAt)
            }
        }.sortedByDescending { it.chapter_number }
    }

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString().removePrefix(baseUrl)
        return listOf(Page(0, chapterUrl))
    }

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = Jsoup.parse(response.body.string())
        return doc.selectFirst("div[id^=reader-area]")?.html()
            ?: doc.selectFirst("#reader-area")?.html()
            ?: doc.selectFirst("div.content-area")?.html()
            ?: doc.selectFirst("div.epcontent.entry-content")?.html()
            ?: doc.selectFirst("div.entry-content")?.html()
            ?: ""
    }

    override fun getFilterList(): FilterList = FilterList(
        StatusFilter(),
        SortFilter(),
        TypeFilter(),
        Filter.Header("Include/Exclude Genres (Tap to toggle)"),
        GenreFilter(),
        Filter.Header("Include/Exclude Tags (Tap to toggle)"),
        TagFilter(),
    )

    // Image URL - not used for novels
    override fun imageUrlParse(response: Response): String = ""

    private fun parseDate(dateStr: String?): Long {
        if (dateStr == null) return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US).parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            try {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateStr)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        }
    }

    // Data classes
    @Serializable
    data class SearchResponse(
        val data: List<NovelDto>,
        val meta: MetaDto,
    )

    @Serializable
    data class MetaDto(
        @SerialName("current_page") val currentPage: Int,
        @SerialName("last_page") val lastPage: Int,
        @SerialName("per_page") val perPage: Int,
        val total: Int,
    )

    @Serializable
    data class NovelDto(
        val id: Int,
        val title: String,
        val slug: String,
        @SerialName("alt_title") val altTitle: String? = null,
        val description: String? = null,
        val type: String? = null,
        val genres: List<GenreDto>? = null,
        val tags: List<TagDto>? = null,
        val cover: String? = null,
        @SerialName("cover_data_url") val coverDataUrl: String? = null,
        val author: AuthorDto? = null,
        @SerialName("chapters_count") val chaptersCount: Int? = null,
        val status: String? = null,
    ) {
        fun toSManga(baseUrl: String): SManga = SManga.create().apply {
            url = "/$slug"
            this.title = this@NovelDto.title
            thumbnail_url = if (!cover.isNullOrEmpty()) {
                if (cover.startsWith("http")) cover else "$baseUrl/$cover"
            } else {
                null
            }
            this.description = buildString {
                this@NovelDto.description?.let {
                    // Sanitize HTML from description
                    val cleanDesc = Jsoup.parse(it).text()
                    append(cleanDesc)
                }
                altTitle?.let {
                    if (it.isNotEmpty()) {
                        if (isNotEmpty()) append("\n\n")
                        append("Alternative Title: $it")
                    }
                }
                chaptersCount?.let {
                    if (isNotEmpty()) append("\n")
                    append("Chapters: $it")
                }
            }
            this@NovelDto.author?.let { authorDto ->
                this.author = authorDto.name ?: authorDto.username
            }
            this@NovelDto.genres?.let { genreList ->
                genre = genreList.joinToString(", ") { g -> g.name }
            }
            this.status = when (this@NovelDto.status?.lowercase()) {
                "on-going", "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }
    }

    @Serializable
    data class GenreDto(
        val id: Int,
        val name: String,
        val slug: String,
    )

    @Serializable
    data class TagDto(
        val id: Int,
        val name: String,
        val slug: String,
    )

    @Serializable
    data class AuthorDto(
        val username: String? = null,
        val name: String? = null,
    )

    @Serializable
    data class ChapterDto(
        val id: Int? = null,
        val title: String,
        val slug: String,
        val url: String? = null,
        @SerialName("chapter_number") val chapterNumber: Int? = null,
        @SerialName("created_at") val createdAt: String? = null,
    )

    @Serializable
    data class ChapterApiDto(
        val number: Int,
        val title: String? = null,
        val slug: String? = null,
        @SerialName("created_at") val createdAt: String? = null,
        val locked: LockedDto? = null,
    )

    @Serializable
    data class LockedDto(
        val price: Int? = null,
    )

    private class StatusFilter :
        Filter.Select<String>(
            "Status",
            arrayOf("Any", "Ongoing", "Completed"),
        ) {
        fun toUriPart() = when (state) {
            0 -> "any"
            1 -> "on-going"
            2 -> "completed"
            else -> "any"
        }
    }

    private class SortFilter :
        Filter.Select<String>(
            "Sort",
            arrayOf("Latest", "Popular"),
        ) {
        fun toUriPart() = when (state) {
            0 -> "latest"
            1 -> "popular"
            else -> "latest"
        }
    }

    private class TypeFilter :
        Filter.Select<String>(
            "Type",
            arrayOf("All", "Light Novel", "Web Novel", "Novel", "Original Novel"),
        ) {
        fun toUriPart() = when (state) {
            0 -> ""
            1 -> "light_novel"
            2 -> "web_novel"
            3 -> "novel"
            4 -> "original_novel"
            else -> ""
        }
    }

    private class GenreCheckBox(val id: Int, name: String) : Filter.TriState(name)

    private class GenreFilter :
        Filter.Group<GenreCheckBox>(
            "Genres",
            listOf(
                GenreCheckBox(1, "Action"),
                GenreCheckBox(2, "Adult"),
                GenreCheckBox(3, "Adventure"),
                GenreCheckBox(4, "Comedy"),
                GenreCheckBox(5, "Drama"),
                GenreCheckBox(6, "Ecchi"),
                GenreCheckBox(7, "Fantasy"),
                GenreCheckBox(8, "Gender Bender"),
                GenreCheckBox(9, "Harem"),
                GenreCheckBox(10, "Historical"),
                GenreCheckBox(11, "Horror"),
                GenreCheckBox(12, "Josei"),
                GenreCheckBox(13, "Martial Arts"),
                GenreCheckBox(14, "Mature"),
                GenreCheckBox(15, "Mecha"),
                GenreCheckBox(16, "Mystery"),
                GenreCheckBox(17, "Psychological"),
                GenreCheckBox(18, "Romance"),
                GenreCheckBox(19, "School Life"),
                GenreCheckBox(20, "Sci-fi"),
                GenreCheckBox(21, "Seinen"),
                GenreCheckBox(22, "Shoujo"),
                GenreCheckBox(23, "Shoujo Ai"),
                GenreCheckBox(24, "Shounen"),
                GenreCheckBox(25, "Shounen Ai"),
                GenreCheckBox(26, "Slice of Life"),
                GenreCheckBox(27, "Smut"),
                GenreCheckBox(28, "Sports"),
                GenreCheckBox(29, "Supernatural"),
                GenreCheckBox(30, "Tragedy"),
                GenreCheckBox(31, "Wuxia"),
                GenreCheckBox(32, "Xianxia"),
                GenreCheckBox(33, "Xuanhuan"),
                GenreCheckBox(34, "Yaoi"),
                GenreCheckBox(35, "Yuri"),
            ),
        )

    private class TagCheckBox(val id: Int, name: String) : Filter.TriState(name)

    private class TagFilter :
        Filter.Group<TagCheckBox>(
            "Tags",
            listOf(
                TagCheckBox(5, "Academy"),
                TagCheckBox(22, "Adventurers"),
                TagCheckBox(47, "Apocalypse"),
                TagCheckBox(75, "Battle Academy"),
                TagCheckBox(111, "Calm Protagonist"),
                TagCheckBox(118, "Character Growth"),
                TagCheckBox(122, "Cheats"),
                TagCheckBox(169, "Cultivation"),
                TagCheckBox(191, "Demons"),
                TagCheckBox(215, "Dragons"),
                TagCheckBox(265, "Fantasy World"),
                TagCheckBox(298, "Game Elements"),
                TagCheckBox(307, "Genius Protagonist"),
                TagCheckBox(317, "Gods"),
                TagCheckBox(324, "Guilds"),
                TagCheckBox(341, "Heroes"),
                TagCheckBox(392, "Level System"),
                TagCheckBox(413, "Magic"),
                TagCheckBox(420, "Male Protagonist"),
                TagCheckBox(456, "Monsters"),
                TagCheckBox(510, "Overpowered Protagonist"),
                TagCheckBox(582, "Reincarnation"),
                TagCheckBox(610, "Second Chance"),
                TagCheckBox(671, "Special Abilities"),
                TagCheckBox(696, "Survival"),
                TagCheckBox(699, "Sword Wielder"),
                TagCheckBox(725, "Transported to Another World"),
                TagCheckBox(746, "Virtual Reality"),
                TagCheckBox(754, "Weak to Strong"),
            ),
        )
}

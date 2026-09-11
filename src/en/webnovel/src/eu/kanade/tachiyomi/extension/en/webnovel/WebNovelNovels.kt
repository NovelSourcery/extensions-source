package eu.kanade.tachiyomi.novelextension.en.webnovel

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Source
abstract class WebNovelNovels :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val isNovelSource = true

    private val mangaPath = SlugPath("/book/")

    /** Stores [SManga.url] as a bare slug via [mangaPath]. */
    private fun SManga.setSlugUrl(href: String) = setSlugUrl(mangaPath, href)

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this
        .set("Accept-Language", "en-US,en;q=0.9")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("Referer", baseUrl)
        .set("User-Agent", userAgent)

    // Popular
    override suspend fun getPopularManga(page: Int): MangasPage = parsePopularOrLatest(client.get("$baseUrl/stories/novel?orderBy=1&pageIndex=$page", headers))

    private fun parsePopularOrLatest(response: Response): MangasPage {
        val document = response.asJsoup()
        val finalUrl = response.request.url.toString()
        val isMobile = finalUrl.contains("m.webnovel.com")

        val mangas = mutableListOf<SManga>()

        if (isMobile) {
            document.select("a[href*='/book/']").forEach { link ->
                val href = link.attr("href")
                if (href.isBlank() || href.contains("/chapter/")) return@forEach

                val title = link.attr("title").ifEmpty {
                    link.selectFirst("img")?.attr("alt") ?: ""
                }.ifEmpty {
                    link.parent()?.selectFirst("h3, h4, .title, p")?.text() ?: ""
                }
                if (title.isBlank()) return@forEach

                val img = link.selectFirst("img") ?: link.parent()?.selectFirst("img")
                val imgSrc = img?.let { imgEl ->
                    imgEl.attr("data-original").ifEmpty { imgEl.attr("data-src") }.ifEmpty { imgEl.attr("src") }
                } ?: ""

                mangas.add(
                    SManga.create().apply {
                        this.title = title
                        setSlugUrl(href.replace("m.webnovel.com", "www.webnovel.com"))
                        thumbnail_url = if (imgSrc.isNotEmpty()) {
                            if (imgSrc.startsWith("http")) imgSrc else "https:$imgSrc"
                        } else {
                            null
                        }
                    },
                )
            }

            val seen = mutableSetOf<String>()
            mangas.removeAll { !seen.add(it.url) }
        } else {
            document.select(".j_category_wrapper li").forEach { element ->
                val thumb = element.selectFirst(".g_thumb") ?: return@forEach
                val img = element.selectFirst(".g_thumb > img") ?: return@forEach

                mangas.add(
                    SManga.create().apply {
                        title = thumb.attr("title").ifEmpty { img.attr("alt") }
                        setSlugUrl(thumb.attr("href"))
                        thumbnail_url = img.attr("data-original").let { src ->
                            if (src.isNotEmpty()) "https:$src" else "https:" + img.attr("src")
                        }
                    },
                )
            }
        }

        val hasNextPage = if (mangas.isEmpty()) {
            false
        } else if (isMobile) {
            mangas.size >= 10 || document.select("[class*=load], [class*=more], [class*=page]").isNotEmpty()
        } else {
            mangas.size >= 10 || document.select(".j_page, .pagination, [class*=page]").isNotEmpty()
        }

        return MangasPage(mangas, hasNextPage)
    }

    // Latest
    override suspend fun getLatestUpdates(page: Int): MangasPage = parsePopularOrLatest(client.get("$baseUrl/stories/novel?orderBy=5&pageIndex=$page", headers))

    // Search
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            val searchType = filters.firstInstanceOrNull<SearchTypeFilter>()?.toUriPart() ?: defaultSearchType
            return when (searchType) {
                "both" -> coroutineScope {
                    val novelDeferred = async { parseSearchResponse(client.get(searchUrl(query, "novel", page), headers)) }
                    val fanficDeferred = async { parseSearchResponse(client.get(searchUrl(query, "fanfic", page), headers)) }
                    val novel = novelDeferred.await()
                    val fanfic = fanficDeferred.await()
                    MangasPage(novel.mangas + fanfic.mangas, novel.hasNextPage || fanfic.hasNextPage)
                }
                else -> parseSearchResponse(client.get(searchUrl(query, searchType, page), headers))
            }
        }

        // Filters
        var gender = "1" // Male default
        var genre = ""
        var status = "0"
        var sort = "1"
        var type = "0"
        var browseFanfic = false
        var fanficGenre = ""

        filters.forEach { filter ->
            when (filter) {
                is BrowseTypeFilter -> browseFanfic = filter.state == 1

                is FanficGenreFilter -> fanficGenre = filter.toUriPart()

                is GenderFilter -> gender = filter.toUriPart()

                is SortFilter -> sort = filter.toUriPart()

                is StatusFilter -> status = filter.toUriPart()

                is TypeFilter -> type = filter.toUriPart()

                is MaleGenreFilter -> {
                    if (gender == "1" && filter.state != 0) {
                        genre = filter.toUriPart()
                    }
                }

                is FemaleGenreFilter -> {
                    if (gender == "2" && filter.state != 0) {
                        genre = filter.toUriPart()
                    }
                }

                else -> {}
            }
        }

        if (browseFanfic) {
            val path = if (fanficGenre.isNotEmpty()) "stories/$fanficGenre" else "stories/fanfic"
            val fanficBuilder = "$baseUrl/$path".toHttpUrl().newBuilder()
                .addQueryParameter("bookStatus", status)
                .addQueryParameter("orderBy", sort)
                .addQueryParameter("pageIndex", page.toString())
            applyContentType(fanficBuilder, type)
            return parseSearchResponse(client.get(fanficBuilder.build().toString(), headers))
        }

        if (genre.isNotEmpty()) {
            val genreBuilder = "$baseUrl/stories/$genre".toHttpUrl().newBuilder()
                .addQueryParameter("bookStatus", status)
                .addQueryParameter("orderBy", sort)
                .addQueryParameter("pageIndex", page.toString())
            applyContentType(genreBuilder, type)
            return parseSearchResponse(client.get(genreBuilder.build().toString(), headers))
        }

        val builder = "$baseUrl/stories".toHttpUrl().newBuilder()
            .addPathSegment("novel")
            .addQueryParameter("gender", gender)
            .addQueryParameter("bookStatus", status)
            .addQueryParameter("orderBy", sort)
            .addQueryParameter("pageIndex", page.toString())
        applyContentType(builder, type)

        return parseSearchResponse(client.get(builder.build().toString(), headers))
    }

    private fun searchUrl(query: String, type: String, page: Int): String = "$baseUrl/search?keywords=$query&type=$type&pageIndex=$page"

    private fun applyContentType(builder: HttpUrl.Builder, type: String) {
        if (type == "3") {
            builder.addQueryParameter("translateMode", "3").addQueryParameter("sourceType", "1")
        } else if (type != "0") {
            builder.addQueryParameter("sourceType", type)
        }
    }

    private fun parseSearchResponse(response: Response): MangasPage {
        val document = response.asJsoup()
        val finalUrl = response.request.url.toString()
        val isMobile = finalUrl.contains("m.webnovel.com")
        val isSearch = finalUrl.contains("/search")

        val mangas = mutableListOf<SManga>()

        if (isMobile) {
            document.select("a[href*='/book/']").forEach { link ->
                val href = link.attr("href")
                if (href.isBlank() || href.contains("/chapter/")) return@forEach

                val title = link.attr("title").ifEmpty {
                    link.selectFirst("img")?.attr("alt") ?: ""
                }.ifEmpty {
                    link.parent()?.selectFirst("h3, h4, .title, p")?.text() ?: ""
                }
                if (title.isBlank()) return@forEach

                val img = link.selectFirst("img") ?: link.parent()?.selectFirst("img")
                val imgSrc = img?.let { imgEl ->
                    imgEl.attr("data-original").ifEmpty { imgEl.attr("data-src") }.ifEmpty { imgEl.attr("src") }
                } ?: ""

                mangas.add(
                    SManga.create().apply {
                        this.title = title
                        setSlugUrl(href.replace("m.webnovel.com", "www.webnovel.com"))
                        thumbnail_url = if (imgSrc.isNotEmpty()) {
                            if (imgSrc.startsWith("http")) imgSrc else "https:$imgSrc"
                        } else {
                            null
                        }
                    },
                )
            }

            val seen = mutableSetOf<String>()
            mangas.removeAll { !seen.add(it.url) }
        } else {
            val selector = if (isSearch) ".j_list_container li" else ".j_category_wrapper li"
            val imgAttr = if (isSearch) "src" else "data-original"

            document.select(selector).forEach { element ->
                val thumb = element.selectFirst(".g_thumb") ?: return@forEach
                val img = element.selectFirst(".g_thumb > img") ?: return@forEach

                mangas.add(
                    SManga.create().apply {
                        title = thumb.attr("title").ifEmpty { img.attr("alt") }
                        setSlugUrl(thumb.attr("href"))
                        val imgSrc = if (isSearch) img.attr("src") else img.attr("data-original").ifEmpty { img.attr("src") }
                        thumbnail_url = if (imgSrc.startsWith("http")) imgSrc else "https:$imgSrc"
                    },
                )
            }
        }
        val hasNextPage = mangas.isNotEmpty()
        return MangasPage(mangas, hasNextPage)
    }

    // Details
    private fun parseMangaDetails(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst(".g_thumb > img")?.attr("alt") ?: "No Title"
            thumbnail_url = "https:" + document.selectFirst(".g_thumb > img")?.attr("src")
            val synopsisEl = document.selectFirst(".j_synopsis")
            val synopsisText = synopsisEl?.select("p")?.map { p ->
                val raw = p.html()
                val withBr = raw.replace(Regex("(?i)<br\\s*/?>"), "\n")
                val cleaned = withBr.replace(Regex("<[^>]+>"), "")
                cleaned.lines().joinToString("\n") { it.trim() }.trim()
            }?.filter { it.isNotBlank() }?.joinToString("\n\n")
                ?: document.select(".j_synopsis > p").map { p ->
                    val raw = p.html()
                    val withBr = raw.replace(Regex("(?i)<br\\s*/?>"), "\n")
                    val cleaned = withBr.replace(Regex("<[^>]+>"), "")
                    cleaned.lines().joinToString("\n") { it.trim() }.trim()
                }.filter { it.isNotBlank() }.joinToString("\n\n")
            description = synopsisText.ifBlank { synopsisEl?.text().orEmpty() }
            author = document.select(".det-info .c_s").firstOrNull { it.text().contains("Author") }?.nextElementSibling()?.text()
            val tags = document.select(".j_tagWrap .m-tags a")
                .map { it.text().replace("#", "").trim() }
                .filter { it.isNotEmpty() }

            genre = if (tags.isNotEmpty()) {
                tags.joinToString()
            } else {
                document.select(".det-hd-detail > .det-hd-tag").attr("title")
            }
            status = when (document.select(".det-hd-detail svg").firstOrNull { it.attr("title") == "Status" }?.nextElementSibling()?.text()) {
                "Completed" -> SManga.COMPLETED
                "Ongoing" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }

            val extras = mutableListOf<String>()

            val ratingValue = document.selectFirst("p._score strong")?.text()
            val ratingsCount = document.selectFirst("p._score small")?.text()?.removePrefix("(")?.removeSuffix(")")
            if (!ratingValue.isNullOrEmpty()) {
                extras.add("Rating: ${ratingValue}${if (!ratingsCount.isNullOrEmpty()) " ($ratingsCount)" else ""}")
            }

            val views = document.select(".det-hd-detail svg").firstOrNull { it.attr("title") == "View" }?.nextElementSibling()?.text()
            if (!views.isNullOrEmpty()) extras.add("Views: $views")

            val reviewScoreElements = document.select(".rev-score-list li")
            if (reviewScoreElements.isNotEmpty()) {
                val scoreLines = reviewScoreElements.mapNotNull { li ->
                    val name = li.selectFirst("strong")?.text() ?: return@mapNotNull null
                    val full = li.select(".g_star svg._on").size
                    val half = li.select(".g_star svg._half").size
                    val score = full + half * 0.5
                    "$name: $score/5"
                }
                if (scoreLines.isNotEmpty()) {
                    extras.add("Review Scores:\n" + scoreLines.joinToString("; "))
                }
            }

            if (extras.isNotEmpty()) {
                val extraText = extras.joinToString("\n\n")
                description = listOf(description, extraText).filter { !it.isNullOrBlank() }.joinToString("\n\n")
            }
        }
    }

    // Chapters
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val detailsDeferred = if (fetchDetails) {
            async { parseMangaDetails(client.get(mangaPath.absolute(baseUrl, manga.url), headers)) }
        } else {
            null
        }
        val chaptersDeferred = if (fetchChapters) {
            async { parseChapterList(client.get(mangaPath.absolute(baseUrl, manga.url) + "/catalog", headers)) }
        } else {
            null
        }

        SMangaUpdate(
            manga = detailsDeferred?.await() ?: manga,
            chapters = chaptersDeferred?.await() ?: chapters,
        )
    }

    private fun parseChapterList(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()
        document.select(".volume-item").forEach { volumeItem ->
            val originalVolumeName = volumeItem.first()?.text().orEmpty()
            val volumeNameMatch = Regex("Volume\\s(\\d+)").find(originalVolumeName)
            val volumeName = volumeNameMatch?.let { "Volume ${it.groupValues[1]}" } ?: "Unknown Volume"

            volumeItem.select("li").forEach { li ->
                val a = li.selectFirst("a") ?: return@forEach
                val chapterName = a.attr("title").trim().ifEmpty { "No Title Found" }

                val isLocked = li.select("svg").isNotEmpty()
                if (isLocked && excludeLocked) return@forEach

                val chapter = SChapter.create().apply {
                    name = if (isLocked) "$volumeName: $chapterName 🔒" else "$volumeName: $chapterName"
                    setUrlWithoutDomain(a.attr("abs:href"))
                    chapter_number = (chapters.size + 1).toFloat()
                }
                chapters.add(chapter)
            }
        }

        return chapters.asReversed().mapIndexed { index, chapter ->
            chapter.apply {
                chapter_number = (chapters.size - index).toFloat()
            }
        }
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val excludeLocked: Boolean
        get() = preferences.getBoolean(PREF_EXCLUDE_LOCKED, true)

    private val userAgent: String
        get() = preferences.getString(PREF_USER_AGENT, DEFAULT_USER_AGENT)?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT

    private val defaultSearchType: String
        get() = preferences.getString(PREF_DEFAULT_SEARCH_TYPE, "novel") ?: "novel"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        CheckBoxPreference(screen.context).apply {
            key = PREF_EXCLUDE_LOCKED
            title = "Exclude locked chapters"
            summary = "Hide chapters that are locked or paid."
            setDefaultValue(true)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_DEFAULT_SEARCH_TYPE
            title = "Default search type"
            summary = "Used for keyword search when no Search Type filter is explicitly set"
            entries = arrayOf("Novel", "Fan-fic", "Both")
            entryValues = arrayOf("novel", "fanfic", "both")
            setDefaultValue("novel")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_USER_AGENT
            title = "User-Agent"
            setDefaultValue(DEFAULT_USER_AGENT)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_EXCLUDE_LOCKED = "webnovel_exclude_locked"
        private const val PREF_DEFAULT_SEARCH_TYPE = "webnovel_default_search_type"
        private const val PREF_USER_AGENT = "webnovel_user_agent"
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    }

    // Pages - novel content - return single page with chapter URL for text fetching
    override fun getMangaUrl(manga: SManga): String = mangaPath.absolute(baseUrl, manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val response = client.get(url.toString(), headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        return parseMangaDetails(response).apply { setSlugUrl(url.encodedPath) }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    // Novel content
    override suspend fun fetchPageText(page: Page): String {
        val response = client.get(baseUrl + page.url, headers)
        val document = response.asJsoup()

        // Remove bloat elements (same as TS plugin)
        document.select(".para-comment").remove()

        // TS plugin: .cha-tit + .cha-words
        val title = document.selectFirst(".cha-tit")?.html() ?: ""
        val content = document.selectFirst(".cha-words")?.html() ?: ""

        return if (title.isNotEmpty() || content.isNotEmpty()) {
            "$title$content"
        } else {
            // Fallback
            document.selectFirst(".cha-content")?.html() ?: ""
        }
    }

    // Filters
    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("only used for keyword search"),
        SearchTypeFilter(searchTypeIndex(defaultSearchType)),
        Filter.Separator(),
        BrowseTypeFilter(),
        FanficGenreFilter(),
        Filter.Header("^ Fan-fic Category only used when Browse Type above is Fan-fic"),
        Filter.Separator(),
        GenderFilter(),
        MaleGenreFilter(),
        FemaleGenreFilter(),
        StatusFilter(),
        SortFilter(),
        TypeFilter(),
    )

    private fun searchTypeIndex(value: String) = when (value) {
        "fanfic" -> 1
        "both" -> 2
        else -> 0
    }

    private class SearchTypeFilter(state: Int = 0) : Filter.Select<String>("Search Type", arrayOf("Novel", "Fan-fic", "Both"), state) {
        fun toUriPart() = when (state) {
            1 -> "fanfic"
            2 -> "both"
            else -> "novel"
        }
    }

    private class BrowseTypeFilter : Filter.Select<String>("Browse Type", arrayOf("Novel", "Fan-fic"), 0)

    private class FanficGenreFilter :
        Filter.Select<String>(
            "Fan-fic Category",
            arrayOf("All", "Anime & Comics", "Video Games", "Celebrities", "Music & Bands", "Movies", "Book & Literature", "TV", "Theater", "Others"),
            0,
        ) {
        private val vals = arrayOf(
            "",
            "fanfic-anime-comics",
            "fanfic-video-games",
            "fanfic-celebrities",
            "fanfic-music-bands",
            "fanfic-movies",
            "fanfic-book-literature",
            "fanfic-tv",
            "fanfic-theater",
            "fanfic-others",
        )
        fun toUriPart() = vals[state]
    }

    private class GenderFilter : Filter.Select<String>("Gender", arrayOf("Male", "Female"), 0) {
        fun toUriPart() = if (state == 0) "1" else "2"
    }

    private class MaleGenreFilter :
        Filter.Select<String>(
            "Male Genres",
            arrayOf("All", "Action", "ACG", "Eastern", "Fantasy", "Games", "History", "Horror", "Realistic", "Sci-fi", "Sports", "Urban", "War"),
            0,
        ) {
        private val vals = arrayOf(
            "1", "novel-action-male", "novel-acg-male", "novel-eastern-male", "novel-fantasy-male",
            "novel-games-male", "novel-history-male", "novel-horror-male", "novel-realistic-male",
            "novel-scifi-male", "novel-sports-male", "novel-urban-male", "novel-war-male",
        )
        fun toUriPart() = vals[state]
    }

    private class FemaleGenreFilter :
        Filter.Select<String>(
            "Female Genres",
            arrayOf("All", "Fantasy", "General", "History", "LGBT+", "Sci-fi", "Teen", "Urban"),
            0,
        ) {
        private val vals = arrayOf(
            "2",
            "novel-fantasy-female",
            "novel-general-female",
            "novel-history-female",
            "novel-lgbt-female",
            "novel-scifi-female",
            "novel-teen-female",
            "novel-urban-female",
        )
        fun toUriPart() = vals[state]
    }

    private class StatusFilter : Filter.Select<String>("Status", arrayOf("All", "Ongoing", "Completed"), 0) {
        fun toUriPart() = when (state) {
            1 -> "1"
            2 -> "2"
            else -> "0"
        }
    }

    private class SortFilter :
        Filter.Select<String>(
            "Sort By",
            arrayOf("Popular", "Recommended", "Most Collections", "Rating", "Time Updated"),
            0,
        ) {
        fun toUriPart() = (state + 1).toString()
    }

    private class TypeFilter : Filter.Select<String>("Type", arrayOf("All", "Translate", "Original", "MTL"), 0) {
        fun toUriPart() = state.toString()
    }
}

package eu.kanade.tachiyomi.extension.en.webnovel

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class WebNovelNovels :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "Webnovel Novels"

    override val baseUrl = "https://www.webnovel.com"

    override val lang = "en"

    override val supportsLatest = true

    override val isNovelSource = true

    override val client = network.cloudflareClient

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .set("Accept-Language", "en-US,en;q=0.9")
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        .set("Referer", baseUrl)

    // Popular
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/stories/novel?orderBy=1&pageIndex=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())
        val finalUrl = response.request.url.toString()
        val isMobile = finalUrl.contains("m.webnovel.com")

        // Mobile and desktop sites have different structures
        // Mobile: redirected from www.webnovel.com to m.webnovel.com
        // Try multiple selectors to handle both formats
        val mangas = mutableListOf<SManga>()

        if (isMobile) {
            // Mobile site - Try multiple selector patterns
            // Pattern 1: Novel list items with a[href*=book]
            document.select("a[href*='/book/']").forEach { link ->
                val href = link.attr("href")
                if (href.isBlank() || href.contains("/chapter/")) return@forEach

                val title = link.attr("title").ifEmpty {
                    link.selectFirst("img")?.attr("alt") ?: ""
                }.ifEmpty {
                    link.parent()?.selectFirst("h3, h4, .title, p")?.text()?.trim() ?: ""
                }
                if (title.isBlank()) return@forEach

                val img = link.selectFirst("img") ?: link.parent()?.selectFirst("img")
                val imgSrc = img?.let { imgEl ->
                    imgEl.attr("data-original").ifEmpty { imgEl.attr("data-src") }.ifEmpty { imgEl.attr("src") }
                } ?: ""

                mangas.add(
                    SManga.create().apply {
                        this.title = title
                        setUrlWithoutDomain(href.replace("m.webnovel.com", "www.webnovel.com"))
                        thumbnail_url = if (imgSrc.isNotEmpty()) {
                            if (imgSrc.startsWith("http")) imgSrc else "https:$imgSrc"
                        } else {
                            null
                        }
                    },
                )
            }

            // Deduplicate by URL
            val seen = mutableSetOf<String>()
            mangas.removeAll { !seen.add(it.url) }
        } else {
            // Desktop site - TS ref: .j_category_wrapper li with .g_thumb
            document.select(".j_category_wrapper li").forEach { element ->
                val thumb = element.selectFirst(".g_thumb") ?: return@forEach
                val img = element.selectFirst(".g_thumb > img") ?: return@forEach

                mangas.add(
                    SManga.create().apply {
                        title = thumb.attr("title").ifEmpty { img.attr("alt") }
                        setUrlWithoutDomain(thumb.attr("href"))
                        thumbnail_url = img.attr("data-original").let { src ->
                            if (src.isNotEmpty()) "https:$src" else "https:" + img.attr("src")
                        }
                    },
                )
            }
        }

        // TS ref: Pagination should continue while results exist and pagination elements present
        // Check for pagination controls in both mobile and desktop formats
        val hasNextPage = if (mangas.isEmpty()) {
            false
        } else if (isMobile) {
            // Mobile: Check for "Load more" or pagination indicators
            mangas.size >= 10 || document.select("[class*=load], [class*=more], [class*=page]").isNotEmpty()
        } else {
            // Desktop: Check for pagination elements or assume more if we got results
            mangas.size >= 10 || document.select(".j_page, .pagination, [class*=page]").isNotEmpty()
        }

        return MangasPage(mangas, hasNextPage)
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/stories/novel?orderBy=5&pageIndex=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query.isNotBlank()) {
            return GET("$baseUrl/search?keywords=$query&pageIndex=$page", headers)
        }

        // Filters
        var gender = "1" // Male default
        var genre = ""
        var status = "0"
        var sort = "1"
        var type = "0"

        filters.forEach { filter ->
            when (filter) {
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

        val builder = "$baseUrl/stories".toHttpUrl().newBuilder()

        if (genre.isNotEmpty()) {
            return GET("$baseUrl/stories/$genre?bookStatus=$status&orderBy=$sort&pageIndex=$page", headers)
        } else {
            builder.addPathSegment("novel")
            builder.addQueryParameter("gender", gender)
        }

        if (type != "3") {
            if (type != "0") builder.addQueryParameter("sourceType", type)
        } else {
            builder.addQueryParameter("translateMode", "3")
            builder.addQueryParameter("sourceType", "1")
        }

        builder.addQueryParameter("bookStatus", status)
        builder.addQueryParameter("orderBy", sort)
        builder.addQueryParameter("pageIndex", page.toString())

        return GET(builder.build().toString(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = Jsoup.parse(response.body.string())
        val finalUrl = response.request.url.toString()
        val isMobile = finalUrl.contains("m.webnovel.com")
        val isSearch = finalUrl.contains("/search")

        val mangas = mutableListOf<SManga>()

        if (isMobile) {
            // Mobile site - Try multiple selector patterns
            document.select("a[href*='/book/']").forEach { link ->
                val href = link.attr("href")
                if (href.isBlank() || href.contains("/chapter/")) return@forEach

                val title = link.attr("title").ifEmpty {
                    link.selectFirst("img")?.attr("alt") ?: ""
                }.ifEmpty {
                    link.parent()?.selectFirst("h3, h4, .title, p")?.text()?.trim() ?: ""
                }
                if (title.isBlank()) return@forEach

                val img = link.selectFirst("img") ?: link.parent()?.selectFirst("img")
                val imgSrc = img?.let { imgEl ->
                    imgEl.attr("data-original").ifEmpty { imgEl.attr("data-src") }.ifEmpty { imgEl.attr("src") }
                } ?: ""

                mangas.add(
                    SManga.create().apply {
                        this.title = title
                        setUrlWithoutDomain(href.replace("m.webnovel.com", "www.webnovel.com"))
                        thumbnail_url = if (imgSrc.isNotEmpty()) {
                            if (imgSrc.startsWith("http")) imgSrc else "https:$imgSrc"
                        } else {
                            null
                        }
                    },
                )
            }

            // Deduplicate by URL
            val seen = mutableSetOf<String>()
            mangas.removeAll { !seen.add(it.url) }
        } else {
            // Desktop site - Search uses .j_list_container with 'src', category uses .j_category_wrapper with 'data-original'
            val selector = if (isSearch) ".j_list_container li" else ".j_category_wrapper li"
            val imgAttr = if (isSearch) "src" else "data-original"

            document.select(selector).forEach { element ->
                val thumb = element.selectFirst(".g_thumb") ?: return@forEach
                val img = element.selectFirst(".g_thumb > img") ?: return@forEach

                mangas.add(
                    SManga.create().apply {
                        title = thumb.attr("title").ifEmpty { img.attr("alt") }
                        setUrlWithoutDomain(thumb.attr("href"))
                        // Search uses 'src', category uses 'data-original'
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
    override fun mangaDetailsParse(response: Response): SManga {
        val document = Jsoup.parse(response.body.string())
        return SManga.create().apply {
            title = document.selectFirst(".g_thumb > img")?.attr("alt") ?: "No Title"
            thumbnail_url = "https:" + document.selectFirst(".g_thumb > img")?.attr("src")
            description = document.select(".j_synopsis > p").joinToString("\n") { it.text() }
            author = document.select(".det-info .c_s").firstOrNull { it.text().contains("Author") }?.nextElementSibling()?.text()
            genre = document.select(".det-hd-detail > .det-hd-tag").attr("title")
            status = when (document.select(".det-hd-detail svg").firstOrNull { it.attr("title") == "Status" }?.nextElementSibling()?.text()?.trim()) {
                "Completed" -> SManga.COMPLETED
                "Ongoing" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    // Chapters
    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url + "/catalog", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = Jsoup.parse(response.body.string())
        val chapters = mutableListOf<SChapter>()
        document.select(".volume-item").forEach { volumeItem ->
            val originalVolumeName = volumeItem.first().text().trim()
            val volumeNameMatch = Regex("Volume\\s(\\d+)").find(originalVolumeName)
            val volumeName = volumeNameMatch?.let { "Volume ${it.groupValues[1]}" } ?: "Unknown Volume"

            volumeItem.select("li").forEach { li ->
                val a = li.selectFirst("a") ?: return@forEach
                val chapterName = a.attr("title").trim().ifEmpty { "No Title Found" }

                val isLocked = li.select("svg").isNotEmpty()
                if (isLocked && excludeLocked) return@forEach

                val chapter = SChapter.create().apply {
                    name = if (isLocked) "$volumeName: $chapterName 🔒" else "$volumeName: $chapterName"
                    setUrlWithoutDomain(a.attr("href"))
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

    // Preferences - hide locked chapters
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val excludeLocked: Boolean
        get() = preferences.getBoolean(PREF_EXCLUDE_LOCKED, false)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        CheckBoxPreference(screen.context).apply {
            key = PREF_EXCLUDE_LOCKED
            title = "Exclude locked chapters"
            summary = "Hide chapters that are locked or paid"
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_EXCLUDE_LOCKED = "webnovel_exclude_locked"
    }

    // Pages - novel content - return single page with chapter URL for text fetching
    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString().removePrefix(baseUrl)
        return listOf(Page(0, chapterUrl))
    }

    override fun imageUrlParse(response: Response): String = ""

    // Novel content
    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val document = Jsoup.parse(response.body.string())

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
    override fun getFilterList() = FilterList(
        GenderFilter(),
        MaleGenreFilter(),
        FemaleGenreFilter(),
        StatusFilter(),
        SortFilter(),
        TypeFilter(),
    )

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

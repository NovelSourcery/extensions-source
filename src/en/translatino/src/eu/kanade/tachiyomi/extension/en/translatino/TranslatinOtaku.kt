package eu.kanade.tachiyomi.extension.en.translatino

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

/**
 * TranslatinOtaku / WebNovelTranslations - Madara-based novel site
 * Uses advanced search with GET for page 1, POST admin-ajax.php for subsequent pages
 */
class TranslatinOtaku :
    HttpSource(),
    NovelSource {

    override val name = "Translatin Otaku"
    override val baseUrl = "https://translatinotaku.net/"
    override val lang = "en"
    override val supportsLatest = true
    override val isNovelSource = true

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request = if (page == 1) {
        GET("$baseUrl/?s=&post_type=wp-manga&m_orderby=trending", headers)
    } else {
        buildAjaxRequest(page, "", emptyMap())
    }

    override fun popularMangaParse(response: Response): MangasPage = if (response.request.url.toString().contains("admin-ajax")) {
        parseAjaxResponse(response)
    } else {
        parseFirstPage(response)
    }

    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request = if (page == 1) {
        GET("$baseUrl/?s=&post_type=wp-manga&m_orderby=latest", headers)
    } else {
        buildAjaxRequest(page, "", emptyMap())
    }

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val params = mutableMapOf<String, String>()
        val genres = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        var genreOp = ""
        var author = ""
        var artist = ""
        var release = ""
        var adult = ""

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    filter.state.filter { it.state }.forEach { genres.add(it.id) }
                }

                is GenreConditionFilter -> genreOp = if (filter.state == 1) "1" else ""

                is AuthorFilter -> author = filter.state

                is ArtistFilter -> artist = filter.state

                is ReleaseYearFilter -> release = filter.state

                is AdultContentFilter -> adult = filter.toUriPart()

                is StatusFilter -> {
                    filter.state.filter { it.state }.forEach { statuses.add(it.id) }
                }

                else -> {}
            }
        }

        if (page == 1) {
            val urlBuilder = baseUrl.toHttpUrl().newBuilder()
            urlBuilder.addQueryParameter("s", query)
            urlBuilder.addQueryParameter("post_type", "wp-manga")
            genres.forEach { urlBuilder.addQueryParameter("genre[]", it) }
            if (genreOp.isNotEmpty()) urlBuilder.addQueryParameter("op", genreOp)
            if (author.isNotEmpty()) urlBuilder.addQueryParameter("author", author)
            if (artist.isNotEmpty()) urlBuilder.addQueryParameter("artist", artist)
            if (release.isNotEmpty()) urlBuilder.addQueryParameter("release", release)
            if (adult.isNotEmpty()) urlBuilder.addQueryParameter("adult", adult)
            statuses.forEach { urlBuilder.addQueryParameter("status[]", it) }
            return GET(urlBuilder.build().toString(), headers)
        } else {
            params["s"] = query
            genres.forEach { params["genre[]"] = it }
            if (genreOp.isNotEmpty()) params["op"] = genreOp
            if (author.isNotEmpty()) params["author"] = author
            if (artist.isNotEmpty()) params["artist"] = artist
            if (release.isNotEmpty()) params["release"] = release
            if (adult.isNotEmpty()) params["adult"] = adult
            statuses.forEachIndexed { i, s -> params["status[$i]"] = s }
            return buildAjaxRequest(page, query, params)
        }
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    // ======================== Ajax Helpers ========================

    private fun buildAjaxRequest(page: Int, query: String, extraParams: Map<String, String>): Request {
        val body = FormBody.Builder()
            .add("action", "madara_load_more")
            .add("page", (page - 1).toString())
            .add("template", "madara-core/content/content-search")
            .add("vars[s]", query)
            .add("vars[orderby]", "")
            .add("vars[paged]", "1")
            .add("vars[template]", "search")
            .add("vars[meta_query][0][relation]", "AND")
            .add("vars[meta_query][relation]", "AND")
            .add("vars[post_type]", "wp-manga")
            .add("vars[post_status]", "publish")
            .add("vars[manga_archives_item_layout]", "default")

        extraParams.forEach { (key, value) ->
            body.add("vars[$key]", value)
        }

        return POST("$baseUrl/wp-admin/admin-ajax.php", headers, body.build())
    }

    private fun parseFirstPage(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        val novels = doc.select(".c-tabs-item__content, .row.c-tabs-item__content").mapNotNull { element ->
            val link = element.selectFirst(".post-title a, .post-title h3 a") ?: return@mapNotNull null
            val img = element.selectFirst("img")
            SManga.create().apply {
                title = link.text().trim()
                setUrlWithoutDomain(link.attr("href"))
                thumbnail_url = img?.let { imgEl ->
                    val src = imgEl.attr("data-src").ifEmpty {
                        imgEl.attr("data-lazy-src").ifEmpty {
                            imgEl.attr("srcset").split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
                                ?: imgEl.attr("src")
                        }
                    }
                    if (src.contains("placeholder") || src.isBlank()) null else src
                }
            }
        }
        val hasNext = doc.selectFirst(".nav-previous a, a.nextpostslink, .wp-pagenavi a.nextpostslink") != null ||
            doc.select(".page-item:not(.active):last-child a").isNotEmpty() ||
            doc.selectFirst("a.next.page-numbers") != null ||
            doc.select(".pagination a").any { it.text() == "2" || it.text() == "»" || it.text().contains("Next") } ||
            novels.size >= 12 // If we got a full page, likely more exist
        return MangasPage(novels, hasNext)
    }

    private fun parseAjaxResponse(response: Response): MangasPage {
        val html = response.body.string()
        if (html.isBlank() || html == "0") return MangasPage(emptyList(), false)

        val doc = Jsoup.parse(html)
        val novels = doc.select(".c-tabs-item__content, .row.c-tabs-item__content").mapNotNull { element ->
            val link = element.selectFirst(".post-title a, .post-title h3 a") ?: return@mapNotNull null
            val img = element.selectFirst("img")
            SManga.create().apply {
                title = link.text().trim()
                setUrlWithoutDomain(link.attr("href"))
                thumbnail_url = img?.let { imgEl ->
                    val src = imgEl.attr("data-src").ifEmpty {
                        imgEl.attr("data-lazy-src").ifEmpty {
                            imgEl.attr("srcset").split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
                                ?: imgEl.attr("src")
                        }
                    }
                    if (src.contains("placeholder") || src.isBlank()) null else src
                }
            }
        }
        return MangasPage(novels, novels.isNotEmpty())
    }

    // ======================== Details ========================

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())
        return SManga.create().apply {
            title = doc.selectFirst(".post-title h1")?.text()?.trim() ?: ""
            thumbnail_url = doc.selectFirst(".summary_image img")?.let {
                it.attr("data-lazy-src").ifEmpty { it.attr("data-src") }.ifEmpty { it.attr("src") }
            }
            description = doc.selectFirst("div.summary__content")?.text()?.trim()
            author = doc.select(".post-content_item").find { it.selectFirst("h5")?.text()?.contains("Author", ignoreCase = true) == true }?.selectFirst(".summary-content")?.text()?.trim()
            genre = doc.select(".post-content_item").find { it.selectFirst("h5")?.text()?.contains("Genre", ignoreCase = true) == true }?.select(".summary-content a")?.joinToString(", ") { it.text().trim() }
            status = doc.select(".post-content_item").find { it.selectFirst("h5")?.text()?.contains("Status", ignoreCase = true) == true }?.selectFirst(".summary-content")?.text()?.let {
                when {
                    it.contains("OnGoing", ignoreCase = true) -> SManga.ONGOING
                    it.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
            } ?: SManga.UNKNOWN
        }
    }

    // ======================== Chapters ========================

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())
        val mangaUrl = response.request.url.encodedPath

        val body = FormBody.Builder().build()
        val chapResponse = client.newCall(
            POST("$baseUrl${mangaUrl}ajax/chapters/", headers, body),
        ).execute()
        val html = chapResponse.body.string()

        if (html == "0" || html.isBlank()) return emptyList()

        val chapDoc = Jsoup.parse(html)
        return chapDoc.select(".wp-manga-chapter").mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            SChapter.create().apply {
                setUrlWithoutDomain(link.attr("href"))
                name = link.text().trim()
                date_upload = element.selectFirst(".chapter-release-date")?.text()?.let { parseDate(it) } ?: 0L
            }
        }.reversed()
    }

    private fun parseDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        val number = Regex("\\d+").find(dateStr)?.value?.toIntOrNull() ?: return 0L
        val calendar = java.util.Calendar.getInstance()
        when {
            dateStr.contains("hour", ignoreCase = true) -> calendar.add(java.util.Calendar.HOUR_OF_DAY, -number)
            dateStr.contains("day", ignoreCase = true) -> calendar.add(java.util.Calendar.DAY_OF_MONTH, -number)
            dateStr.contains("week", ignoreCase = true) -> calendar.add(java.util.Calendar.WEEK_OF_YEAR, -number)
            dateStr.contains("month", ignoreCase = true) -> calendar.add(java.util.Calendar.MONTH, -number)
            dateStr.contains("year", ignoreCase = true) -> calendar.add(java.util.Calendar.YEAR, -number)
        }
        return calendar.timeInMillis
    }

    // ======================== Pages ========================

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

    override fun imageUrlParse(response: Response) = ""

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(page.url, headers)).execute()
        val doc = Jsoup.parse(response.body.string())
        doc.select("div.ads, script, ins, .adsbygoogle, .code-block").remove()
        return doc.selectFirst(".text-left, .reading-content, .entry-content")?.html() ?: ""
    }

    // ======================== Filters ========================

    override fun getFilterList() = FilterList(
        Filter.Header("Filters ignored when using text search"),
        GenreFilter(getGenreList()),
        GenreConditionFilter(),
        AuthorFilter(),
        ArtistFilter(),
        ReleaseYearFilter(),
        AdultContentFilter(),
        StatusFilter(getStatusList()),
    )

    private class GenreCheckBox(name: String, val id: String) : Filter.CheckBox(name)
    private class GenreFilter(genres: List<Pair<String, String>>) :
        Filter.Group<GenreCheckBox>(
            "Genres",
            genres.map { GenreCheckBox(it.first, it.second) },
        )

    private class GenreConditionFilter :
        Filter.Select<String>(
            "Genres condition",
            arrayOf("OR (having one)", "AND (having all)"),
            0,
        )

    private class AuthorFilter : Filter.Text("Author")
    private class ArtistFilter : Filter.Text("Artist")
    private class ReleaseYearFilter : Filter.Text("Year of Release")

    private class AdultContentFilter :
        Filter.Select<String>(
            "Adult content",
            arrayOf("All", "None adult", "Only adult"),
            0,
        ) {
        fun toUriPart() = when (state) {
            1 -> "0"
            2 -> "1"
            else -> ""
        }
    }

    private class StatusCheckBox(name: String, val id: String) : Filter.CheckBox(name)
    private class StatusFilter(statuses: List<Pair<String, String>>) :
        Filter.Group<StatusCheckBox>(
            "Status",
            statuses.map { StatusCheckBox(it.first, it.second) },
        )

    private fun getGenreList() = listOf(
        "Action" to "action", "Adult" to "adult", "Adventure" to "adventure",
        "Anime" to "anime", "Cartoon" to "cartoon", "Comedy" to "comedy",
        "Comic" to "comic", "Cooking" to "cooking", "Detective" to "detective",
        "Doujinshi" to "doujinshi", "Drama" to "drama", "Ecchi" to "ecchi",
        "Fantasy" to "fantasy", "Gender Bender" to "gender-bender", "Harem" to "harem",
        "Historical" to "historical", "Horror" to "horror", "Josei" to "josei",
        "Live action" to "live-action", "Novel" to "manga", "Manhua" to "manhua",
        "Manhwa" to "manhwa", "Martial Arts" to "martial-arts", "Mature" to "mature",
        "Mecha" to "mecha", "Mystery" to "mystery", "One shot" to "one-shot",
        "Psychological" to "psychological", "Romance" to "romance", "School Life" to "school-life",
        "Sci-fi" to "sci-fi", "Seinen" to "seinen", "Shoujo" to "shoujo",
        "Shoujo Ai" to "shoujo-ai", "Shounen" to "shounen", "Shounen Ai" to "shounen-ai",
        "Slice of Life" to "slice-of-life", "Smut" to "smut", "Soft Yaoi" to "soft-yaoi",
        "Soft Yuri" to "soft-yuri", "Sports" to "sports", "Supernatural" to "supernatural",
        "Tragedy" to "tragedy", "Webtoon" to "webtoon", "Yaoi" to "yaoi", "Yuri" to "yuri",
    )

    private fun getStatusList() = listOf(
        "OnGoing" to "on-going",
        "Completed" to "end",
        "Canceled" to "canceled",
        "On Hold" to "on-hold",
        "Upcoming" to "upcoming",
    )
}

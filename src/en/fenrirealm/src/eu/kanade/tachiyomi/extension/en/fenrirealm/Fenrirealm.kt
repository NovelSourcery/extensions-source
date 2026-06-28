package eu.kanade.tachiyomi.novelextension.en.fenrirealm

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.setAltTitles
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

private val INVISIBLE_CHARS = Regex("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]")

private fun htmlToPlainTextPreserveBreaks(html: String): String {
    val document = Jsoup.parseBodyFragment(html)
    val body = document.body()
    val output = StringBuilder()

    appendNodeText(body, output)

    return output.toString()
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

private fun appendNodeText(node: Node, output: StringBuilder) {
    when (node) {
        is TextNode -> output.append(node.text())
        is Element -> {
            val tagName = node.normalName()
            val blockElement = tagName in setOf(
                "article",
                "blockquote",
                "div",
                "li",
                "p",
                "section",
            )

            if (tagName == "br" || tagName == "hr") {
                if (output.isNotEmpty() && !output.endsWith("\n")) {
                    output.append('\n')
                }
                return
            }

            val startLength = output.length
            if (blockElement && output.isNotEmpty() && !output.endsWith("\n")) {
                output.append('\n')
            }

            node.childNodes().forEach { child ->
                appendNodeText(child, output)
            }

            if (blockElement && output.length > startLength && !output.endsWith("\n")) {
                output.append('\n')
            }
        }
        else -> node.childNodes().forEach { child ->
            appendNodeText(child, output)
        }
    }
}

object FlexibleStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value)
        }
    }

    override fun deserialize(decoder: Decoder): String? {
        if (decoder is JsonDecoder) {
            return when (val element = decoder.decodeJsonElement()) {
                JsonNull -> null
                is JsonPrimitive -> element.content
                else -> element.toString()
            }
        }

        return runCatching { decoder.decodeString() }.getOrNull()
    }
}

class Fenrirealm :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val name = "Fenrirealm"
    override val baseUrl = "https://fenrirealm.com"
    override val lang = "en"
    override val supportsLatest = true

    // isNovelSource is provided by NovelSource interface with default value true

    override val client = network.cloudflareClient

    private val json: Json by injectLazy()
    private val preferences: SharedPreferences by getPreferencesLazy()

    private val hideLockedChapters: Boolean
        get() = preferences.getBoolean(PREF_HIDE_LOCKED_CHAPTERS, true)

    // API base URL - from instructions.txt: /api/new/v2
    private val apiBaseUrl = "$baseUrl/api/new/v2"

    // Chapter Number Prefix
    private val titlePrefixRE = Regex("^chapter [0-9]+(?: [^[:alnum:]] |)", RegexOption.IGNORE_CASE)

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
        if (query.startsWith("http") && query.contains("/series/")) {
            val slug = query.substringAfter("/series/")
                .substringBefore('?')
                .substringBefore('#')
                .trim('/')
                .substringBefore('/')
            if (slug.isNotBlank()) {
                return GET("$apiBaseUrl/series/$slug", headers)
            }
        }

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

    override fun searchMangaParse(response: Response): MangasPage {
        val lastSegment = response.request.url.encodedPath.trimEnd('/').substringAfterLast('/')
        if (lastSegment != "series") {
            return try {
                val manga = json.decodeFromString<NovelDto>(response.body.string()).toSManga(baseUrl)
                MangasPage(listOf(manga), false)
            } catch (_: Exception) {
                MangasPage(emptyList(), false)
            }
        }
        return latestUpdatesParse(response)
    }

    override fun getMangaUrl(manga: SManga): String {
        val slug = manga.url.trim('/').substringAfterLast('/')
        return "$baseUrl/series/$slug"
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.removePrefix("/").removeSuffix("/").removePrefix("series/")
        return GET("$apiBaseUrl/series/$slug", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body.string()

        return runCatching {
            json.decodeFromString<NovelDto>(body).toSManga(baseUrl)
        }.getOrElse {
            runCatching {
                json.decodeFromString<SearchResponse>(body).data.firstOrNull()?.toSManga(baseUrl)
            }.getOrNull() ?: fallbackMangaDetails(response)
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.removePrefix("/").removeSuffix("/").removePrefix("series/")
        return GET("$apiBaseUrl/series/$slug/chapters", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val chapters = json.decodeFromString<List<ChapterApiDto>>(response.body.string())
        val slug = response.request.url.pathSegments.dropLast(1).lastOrNull() ?: ""

        return chapters.sortedWith(
            compareBy({ it.group?.index ?: 0 }, { it.number }),
        ).mapIndexedNotNull { index, chapter ->
            val isLocked = chapter.locked?.price?.let { it > 0 } ?: false
            if (hideLockedChapters && isLocked) return@mapIndexedNotNull null

            SChapter.create().apply {
                url = buildString {
                    append("/series/$slug")
                    chapter.group?.slug?.let {
                        append("/$it")
                    }
                    append("/chapter-${chapter.number}")
                }

                name = buildString {
                    if (isLocked) append("🔒 ")

                    val group = chapter.group
                    when {
                        group?.abbr != null -> append("${group.abbr} Ch. ${chapter.number}")
                        group?.index != null -> append("Vol. ${group.index} Ch. ${chapter.number}")
                        else -> append("Chapter ${chapter.number}")
                    }

                    chapter.title
                        ?.replace(titlePrefixRE, "")
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.let { append(" - $it") }
                }
                chapter_number = (index + 1).toFloat()
                date_upload = parseDate(chapter.createdAt)
            }
        }.asReversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_HIDE_LOCKED_CHAPTERS
            title = "Hide locked chapters"
            summary = "Exclude chapters that require payment"
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    override fun pageListParse(response: Response): List<Page> {
        val chapterUrl = response.request.url.toString().removePrefix(baseUrl)
        return listOf(Page(0, chapterUrl))
    }

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val body = response.body.string()

        // Try DOM extraction first (more reliable for rendered HTML)
        val chapterContent = extractChapterContentFromDOM(body)
        if (chapterContent.isNotBlank()) {
            return chapterContent
        }

        return ""
    }

    private fun extractChapterContentFromDOM(body: String): String {
        val doc = Jsoup.parse(body)

        // Prefer the actual chapter body over the outer reader wrapper.
        val readerArea = doc.selectFirst("div.reader-area[id^=reader-area]")
            ?: doc.selectFirst("div.reader-area")
            ?: doc.selectFirst("div[role=region][id^=reader-area]")
            ?: return ""

        // Strip real comment/reaction sections structurally. Never match on prose
        // text: a chapter sentence containing the word "comment" used to cut the
        // rest of the chapter off.
        readerArea.select("#comments").forEach { it.remove() }
        readerArea.select("h3:containsOwn(What do you think)").forEach { heading ->
            val section = heading.parents().firstOrNull { it.parent() === readerArea }
            (section ?: heading).remove()
        }

        return readerArea.children().joinToString("") { it.outerHtml() }
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

    private fun fallbackMangaDetails(response: Response): SManga {
        val searchedSlug = response.request.url.pathSegments.lastOrNull()
            ?.trim()
            ?.removePrefix("/")
            ?.removeSuffix("/")
            .orEmpty()

        return SManga.create().apply {
            url = if (searchedSlug.isNotBlank()) "/series/$searchedSlug" else "/"
            title = searchedSlug
                .substringAfterLast('/')
                .replace('-', ' ')
                .trim()
                .ifBlank { "Unknown Title" }
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
        val subscribers: Int? = null,
        val stats: StatsDto? = null,
        val schedules: SchedulesDto? = null,
        val notices: List<NoticeDto>? = null,
        @SerialName("global_note") val globalNote: String? = null,
    ) {
        fun toSManga(baseUrl: String): SManga = SManga.create().apply {
            url = "/series/$slug"
            this.title = this@NovelDto.title.replace(INVISIBLE_CHARS, "").trim()
            thumbnail_url = if (!cover.isNullOrEmpty()) {
                if (cover.startsWith("http")) cover else "$baseUrl/$cover"
            } else {
                null
            }

            val altTitles = altTitle
                ?.split("\n", "/", " | ", ";")
                ?.map { Jsoup.parse(it).text().trim() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                .orEmpty()

            setAltTitles(altTitles)

            val apiDescription = this@NovelDto.description?.let { htmlToPlainTextPreserveBreaks(it) }.orEmpty()

            this.description = buildString {
                val synopsis = apiDescription
                if (synopsis.isNotBlank()) {
                    append(synopsis)
                }
                chaptersCount?.let {
                    if (isNotEmpty()) append("\n")
                    append("Chapters: $it")
                }

                subscribers?.let {
                    if (isNotEmpty()) append("\n")
                    append("Subscribers: $it")
                }

                stats?.let { statsDto ->
                    appendStatsSection(statsDto)
                }

                notices?.takeIf { it.isNotEmpty() }?.let { noticeList ->
                    if (isNotEmpty()) append("\n")
                    append("Notices:")
                    noticeList.forEach { notice ->
                        notice.message?.let { messageHtml ->
                            htmlToPlainTextPreserveBreaks(messageHtml)
                        }?.takeIf { it.isNotBlank() }?.let { message ->
                            append("\n- ")
                            append(message)
                        }
                    }
                }

                globalNote?.let { noteHtml ->
                    htmlToPlainTextPreserveBreaks(noteHtml)
                }?.takeIf { it.isNotBlank() }?.let { note ->
                    if (isNotEmpty()) append("\n")
                    append("Global note: ")
                    append(note)
                }
            }
            this@NovelDto.author?.let { authorDto ->
                this.author = authorDto.name ?: authorDto.username
            }

            genre = buildList {
                this@NovelDto.genres?.forEach { add(it.name) }
                this@NovelDto.tags?.forEach { add(it.name) }
            }.map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(", ")

            this.status = when (this@NovelDto.status?.lowercase()) {
                "on-going", "ongoing" -> SManga.ONGOING
                "completed" -> SManga.COMPLETED
                "hiatus" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
        }

        private fun StringBuilder.appendStatsSection(statsDto: StatsDto) {
            statsDto.totalViews?.let {
                if (isNotEmpty()) append("\n")
                append("Total views: $it")
            }
            statsDto.dailyViews?.let {
                if (isNotEmpty()) append("\n")
                append("Daily views: $it")
            }
            statsDto.weeklyViews?.let {
                if (isNotEmpty()) append("\n")
                append("Weekly views: $it")
            }
            statsDto.monthlyViews?.let {
                if (isNotEmpty()) append("\n")
                append("Monthly views: $it")
            }
            statsDto.authorStats?.let { authorStats ->
                if (isNotEmpty()) append("\n")
                append("Author stats:")
                authorStats.totalWorks?.let { append("\n- Works: $it") }
                authorStats.totalFollowers?.let { append("\n- Followers: $it") }
                authorStats.totalViews?.let { append("\n- Views: $it") }
                authorStats.memberSince?.takeIf { it.isNotBlank() }?.let { append("\n- Member since: $it") }
            }
            statsDto.chapterFrequency?.let { chapterFrequency ->
                if (isNotEmpty()) append("\n")
                append("Chapter frequency:")
                chapterFrequency.chaptersPerWeek?.let { append("\n- Chapters per week: $it") }
                chapterFrequency.frequencyText?.takeIf { it.isNotBlank() }?.let { append("\n- Frequency: $it") }
                chapterFrequency.lastChapterDaysAgo?.let { append("\n- Last chapter days ago: ${formatDecimal(it)}") }
                chapterFrequency.latestReleaseAt?.takeIf { it.isNotBlank() }?.let { append("\n- Latest release: $it") }
                chapterFrequency.previousReleaseAt?.takeIf { it.isNotBlank() }?.let { append("\n- Previous release: $it") }
                chapterFrequency.gapBetweenLastReleasesDays?.let { append("\n- Gap between last releases days: ${formatDecimal(it)}") }
                chapterFrequency.sampleSize?.let { append("\n- Sample size: $it") }
                chapterFrequency.averageIntervalHours?.let { append("\n- Average interval hours: ${formatDecimal(it)}") }
            }
            statsDto.seriesAge?.let { seriesAge ->
                if (isNotEmpty()) append("\n")
                append("Series age:")
                seriesAge.startYear?.let { append("\n- Start year: $it") }
                seriesAge.yearsActive?.let { append("\n- Years active: $it") }
                seriesAge.ageText?.takeIf { it.isNotBlank() }?.let { append("\n- Age: $it") }
            }
        }

        private fun formatDecimal(value: Double): String {
            val rounded = String.format(Locale.US, "%.2f", value)
            return rounded.trimEnd('0').trimEnd('.')
        }
    }

    @Serializable
    data class StatsDto(
        @SerialName("total_views") val totalViews: Int? = null,
        @SerialName("daily_views") val dailyViews: Int? = null,
        @SerialName("weekly_views") val weeklyViews: Int? = null,
        @SerialName("monthly_views") val monthlyViews: Int? = null,
        @SerialName("author_stats") val authorStats: AuthorStatsDto? = null,
        @SerialName("chapter_frequency") val chapterFrequency: ChapterFrequencyDto? = null,
        @SerialName("series_age") val seriesAge: SeriesAgeDto? = null,
    )

    @Serializable
    data class AuthorStatsDto(
        @SerialName("total_works") val totalWorks: Int? = null,
        @SerialName("total_followers") val totalFollowers: Int? = null,
        @SerialName("total_views") val totalViews: Int? = null,
        @SerialName("member_since") @Serializable(with = FlexibleStringSerializer::class) val memberSince: String? = null,
    )

    @Serializable
    data class ChapterFrequencyDto(
        @SerialName("chapters_per_week") val chaptersPerWeek: Double? = null,
        @SerialName("frequency_text") val frequencyText: String? = null,
        @SerialName("last_chapter_days_ago") val lastChapterDaysAgo: Double? = null,
        @SerialName("latest_release_at") val latestReleaseAt: String? = null,
        @SerialName("previous_release_at") val previousReleaseAt: String? = null,
        @SerialName("gap_between_last_releases_days") val gapBetweenLastReleasesDays: Double? = null,
        @SerialName("sample_size") val sampleSize: Int? = null,
        @SerialName("average_interval_hours") val averageIntervalHours: Double? = null,
    )

    @Serializable
    data class SeriesAgeDto(
        @SerialName("start_year") val startYear: Int? = null,
        @SerialName("years_active") val yearsActive: Int? = null,
        @SerialName("age_text") val ageText: String? = null,
    )

    @Serializable
    data class SchedulesDto(
        val days: List<String>? = null,
        val values: List<Int>? = null,
        val time: List<String>? = null,
        val timezone: String? = null,
    )

    @Serializable
    data class NoticeDto(
        val message: String? = null,
    )

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
    data class GroupDto(
        val index: Int? = null,
        val slug: String? = null,
        val name: String? = null,
        @SerialName("abbreviation") val abbr: String? = null,
    )

    @Serializable
    data class ChapterApiDto(
        val number: Int,
        val title: String? = null,
        val slug: String? = null,
        val group: GroupDto? = null,
        @SerialName("created_at") val createdAt: String? = null,
        @SerialName("updated_at") val updatedAt: String? = null,
        val locked: LockedDto? = null,
    )

    @Serializable
    data class LockedDto(
        val price: Int? = null,
        @SerialName("unlocked_at") val unlockedAt: String? = null,
    )

    companion object {
        private const val PREF_HIDE_LOCKED_CHAPTERS = "fenrirealm_hide_locked_chapters"
    }

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

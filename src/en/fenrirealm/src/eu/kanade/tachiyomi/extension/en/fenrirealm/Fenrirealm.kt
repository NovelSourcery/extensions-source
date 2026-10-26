package eu.kanade.tachiyomi.novelextension.en.fenrirealm

import android.content.SharedPreferences
import android.util.Base64
import android.webkit.CookieManager
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
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.setAltTitles
import keiyoushi.utils.setNumber
import keiyoushi.utils.setVolume
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.seconds

private val INVISIBLE_CHARS = Regex("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]")

private fun htmlToPlainTextPreserveBreaks(html: String): String {
    val document = Jsoup.parseBodyFragment(html)
    val body = document.body()
    return buildString { appendNodeText(body, this) }
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

@Suppress("unused", "SpellCheckingInspection")
@Source
abstract class Fenrirealm :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val supportsLatest = true
    override val supportsFilterFetching = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val hideLockedChapters: Boolean
        get() = preferences.getBoolean(PREF_HIDE_LOCKED_CHAPTERS, true)

    // API base URL - from instructions.txt: /api/new/v2
    private val apiBaseUrl = "$baseUrl/api/new/v2"

    // Chapter Number Prefix
    private val titlePrefixRE = Regex("""^\s*Ch(?:apter|\.)?\s+\d+\s*(?:\W+\s+)?""", RegexOption.IGNORE_CASE)

    /**
     * The site's novel detail URL shape, as `/series/<slug>`. [SManga.url] is stored as the bare
     * slug (see [SlugPath]); a stored value starting with "/" is a pre-existing full-path entry
     * from before this source adopted slug storage, and is resolved unchanged regardless of
     * this template.
     */
    private val mangaPathTemplate = SlugPath("/series/")

    private fun buildPopularMangaRequest(page: Int): Request = GET("$apiBaseUrl/home/popular-series", headers)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = buildPopularMangaRequest(page)
        val novels = client.get(request.url, request.headers).parseAs<List<NovelDto>>()
        return MangasPage(novels.map { it.toSManga(baseUrl, mangaPathTemplate) }, false)
    }

    private fun buildLatestUpdatesRequest(page: Int): Request = GET("$apiBaseUrl/series?page=$page&per_page=20&status=any&sort=latest", headers)

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val request = buildLatestUpdatesRequest(page)
        return parseSearchResultsResponse(client.get(request.url, request.headers))
    }

    private fun parseSearchResultsResponse(response: Response): MangasPage {
        val result = response.parseAs<SearchResponse>()
        val hasNextPage = result.meta.currentPage < result.meta.lastPage
        return MangasPage(result.data.map { it.toSManga(baseUrl, mangaPathTemplate) }, hasNextPage)
    }

    // Resolves URL-shaped search queries (e.g. "share this novel" links) via the standard
    // getMangaByUrl hook instead of special-casing it inside search.
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!url.encodedPath.contains("/series/")) return null
        val slug = url.encodedPath.substringAfter("/series/").trim('/').substringBefore('/')
        if (slug.isBlank()) return null
        val response = client.get("$apiBaseUrl/series/$slug", headers)
        return runCatching { response.parseAs<NovelDto>().toSManga(baseUrl, mangaPathTemplate) }.getOrNull()
    }

    private fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$apiBaseUrl/series".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", "20")
            if (query.isNotEmpty()) {
                addQueryParameter("search", query)
            }

            filters.forEach { filter ->
                when (filter) {
                    is StatusFilter -> filter.value.takeIf(String::isNotEmpty)?.let { addQueryParameter("statuses[]", it) }

                    is SortFilter -> addQueryParameter("sort", filter.value)

                    is TypeFilter -> filter.value.takeIf(String::isNotEmpty)?.let { addQueryParameter("type", it) }

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

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val request = buildSearchMangaRequest(page, query, filters)
        return parseSearchResultsResponse(client.get(request.url, request.headers))
    }

    override fun getMangaUrl(manga: SManga): String {
        val slug = mangaPathTemplate.resolve(manga.url).trim('/').substringAfterLast('/')
        return "$baseUrl/series/$slug"
    }

    private fun buildMangaDetailsRequest(manga: SManga): Request {
        val slug = mangaPathTemplate.resolve(manga.url).removePrefix("/").removeSuffix("/").removePrefix("series/")
        return GET("$apiBaseUrl/series/$slug", headers)
    }

    private fun buildChapterListRequest(manga: SManga): Request {
        val slug = mangaPathTemplate.resolve(manga.url).removePrefix("/").removeSuffix("/").removePrefix("series/")
        return GET("$apiBaseUrl/series/$slug/chapters", headers)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        // Details and chapters live on different endpoints - fire both concurrently when both
        // are needed.
        val detailsDeferred = if (fetchDetails) {
            async {
                val request = buildMangaDetailsRequest(manga)
                parseMangaDetails(client.get(request.url, request.headers))
            }
        } else {
            null
        }
        val chaptersDeferred = if (fetchChapters) {
            async {
                val request = buildChapterListRequest(manga)
                parseChapterList(client.get(request.url, request.headers))
            }
        } else {
            null
        }

        SMangaUpdate(
            manga = detailsDeferred?.await() ?: manga,
            chapters = chaptersDeferred?.await() ?: chapters,
        )
    }

    private fun parseMangaDetails(response: Response): SManga {
        val body = response.body.string()

        return runCatching {
            body.parseAs<NovelDto>().toSManga(baseUrl, mangaPathTemplate)
        }.getOrElse {
            runCatching {
                body.parseAs<SearchResponse>().data.firstOrNull()?.toSManga(baseUrl, mangaPathTemplate)
            }.getOrNull() ?: fallbackMangaDetails(response)
        }
    }

    private fun parseChapterList(response: Response): List<SChapter> {
        val chapters = response.parseAs<List<ChapterApiDto>>()
        val slug = response.request.url.pathSegments.dropLast(1).lastOrNull() ?: ""

        return chapters.sortedWith(
            compareBy({ it.group?.index ?: 0 }, { it.number }, { it.part ?: 1 }),
        ).mapIndexedNotNull { index, chapter ->
            val isLocked = (chapter.locked?.price ?: 0) > 0
            if (hideLockedChapters && isLocked) return@mapIndexedNotNull null

            SChapter.create().apply {
                url = buildString {
                    append("/series/$slug")
                    chapter.group?.slug?.let { append("/$it") }
                    append("/chapter-${chapter.number}")
                    chapter.part?.let { append("-$it") }
                }

                name = buildString {
                    if (isLocked) append("🔒 ")

                    when (val vol = chapter.group?.index) {
                        null -> append("Chapter ${chapter.number}")
                        else -> append("Vol. $vol Ch. ${chapter.number}")
                    }

                    chapter.part?.let { append(".$it") }

                    chapter.title
                        ?.replace(titlePrefixRE, "")
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                        ?.let { append(" - $it") }
                }
                @Suppress("DEPRECATION")
                chapter_number = (index + 1).toFloat()
                date_upload = parseDate(chapter.createdAt)

                setNumber(
                    buildString {
                        append(chapter.number)
                        chapter.part?.let { append(".$it") }
                    },
                )

                chapter.group?.index
                    ?.let { setVolume(it.toString()) }
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

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    // Read token + decryption flow copied from the LNReader plugin:
    // https://github.com/7ui77/lnreader-plugins/blob/f00c4858b46a737a7157ce2e5844808a7476800f/plugins/english/fenrirrealm.ts
    override suspend fun fetchPageText(page: Page): String {
        val apiUrl = buildString {
            append(apiBaseUrl)
            append(page.url.substringBeforeLast('/'))
            append('/')
            append(page.url.substringAfterLast('/').removePrefix("chapter-").replace('-', '.'))
        }

        var (chapter, issuedToken) = fetchChapter(apiUrl)
        if (chapter.contentFormat == "locked") {
            if (chapter.isPaid) throw Exception("This chapter is locked. Unlock it on Fenrir Realm first.")
            if (!mintReadToken(chapter)) {
                solveReadChallenge(baseUrl + page.url)
                if (!mintReadToken(chapter)) throw Exception(VERIFY_MESSAGE)
            }
            fetchChapter(apiUrl).let {
                chapter = it.first
                issuedToken = it.second
            }
        }

        val content = when (chapter.contentFormat) {
            "locked" -> throw Exception(VERIFY_MESSAGE)
            "encrypted" -> {
                val token = issuedToken ?: activeReadToken() ?: throw Exception(VERIFY_MESSAGE)
                chapter.decrypt(token) ?: throw Exception(VERIFY_MESSAGE)
            }
            else -> (chapter.content as? JsonPrimitive)?.content.orEmpty()
        }
        return formatChapterContent(content)
    }

    private suspend fun fetchChapter(apiUrl: String): Pair<ChapterDto, String?> {
        val response = client.get(apiUrl, chapterHeaders, ensureSuccess = false)
        if (!response.isSuccessful) {
            response.close()
            if (response.code == 429) throw Exception(RATE_LIMIT_MESSAGE)
            throw Exception("HTTP error ${response.code}")
        }
        val issuedToken = response.header("x-read-token-issued")?.takeIf(String::isNotEmpty)
        return response.parseAs<ChapterDto>() to issuedToken
    }

    private val chapterHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Accept", "application/json")
            .set("X-Accepts-Encrypted-Content", "3")
            .build()
    }

    private suspend fun mintReadToken(chapter: ChapterDto): Boolean {
        val target = chapter.remintChapterUuid ?: chapter.remintChapterId?.toString() ?: chapter.uuid ?: chapter.id.toString()
        val response = client.post(
            "$baseUrl/api/read-token",
            headers,
            ReadTokenRequest(target).toJsonRequestBody(),
            ensureSuccess = false,
        )
        if (!response.isSuccessful) {
            response.close()
            return false
        }
        return response.parseAs<ReadTokenDto>().isActive
    }

    private suspend fun activeReadToken(): String? {
        val response = client.get("$baseUrl/api/read-token", headers, ensureSuccess = false)
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        return response.parseAs<ReadTokenDto>().takeIf { it.isActive }?.token
    }

    // Lets the site's Turnstile set the fr_read cookie.
    private suspend fun solveReadChallenge(chapterUrl: String) {
        val cookieManager = CookieManager.getInstance()
        val initialCookie = readCookie(cookieManager)
        runCatching {
            runWebView<Unit>(timeout = 45.seconds) {
                headers["User-Agent"]?.let { userAgent = it }
                blockImages = true
                poll {
                    val cookie = readCookie(cookieManager)
                    if (cookie != null && cookie != initialCookie) resolve(Unit)
                }
                loadUrl(chapterUrl)
            }
        }.onFailure { throw Exception(VERIFY_MESSAGE) }
    }

    private fun readCookie(cookieManager: CookieManager): String? = cookieManager.getCookie(baseUrl)
        ?.split(';')
        ?.map(String::trim)
        ?.firstOrNull { it.startsWith("fr_read=") }

    private fun formatChapterContent(content: String): String {
        val html = runCatching { content.parseAs<TipTapNode>() }.getOrNull()
            ?.takeIf { it.type == "doc" || it.type == "systemWindow" }
            ?.let(::tipTapToHtml)
            ?: Jsoup.parseBodyFragment(content).body().apply {
                select("[aria-hidden=true], .reader-attribution, [data-fr-attr], style, script").remove()
                select("p, div, span, small")
                    .filter { el -> el.text().trim().let { it.startsWith("Copy reference:") || it.startsWith("Bookmark:") } }
                    .forEach { it.remove() }
            }.html()
        return html.replace(INVISIBLE_CHARS, "")
    }

    private fun tipTapToHtml(doc: TipTapNode): String = doc.content.orEmpty().joinToString("\n") { node ->
        when (node.type) {
            "paragraph" -> "<p>${node.content.orEmpty().joinToString("", transform = ::tipTapInline)}</p>"
            "heading" -> {
                val level = node.attrs?.level ?: 1
                "<h$level>${node.content.orEmpty().joinToString("") { it.text.orEmpty() }}</h$level>"
            }
            else -> ""
        }
    }

    private fun tipTapInline(node: TipTapNode): String = when (node.type) {
        "hardBreak" -> "<br>"
        "text" -> node.marks.orEmpty().fold(node.text.orEmpty()) { text, mark ->
            when (mark.type) {
                "bold" -> "<b>$text</b>"
                "italic" -> "<i>$text</i>"
                "underline" -> "<u>$text</u>"
                "strike" -> "<s>$text</s>"
                "link" -> "<a href=\"${mark.attrs?.href.orEmpty()}\">$text</a>"
                else -> text
            }
        }
        else -> ""
    }

    override suspend fun fetchFilterData(): JsonElement = coroutineScope {
        val genres = async { client.get("$apiBaseUrl/taxonomies/genres", headers).parseAs<List<TaxonomyDto>>() }
        val tags = async { client.get("$apiBaseUrl/taxonomies/tags", headers).parseAs<List<TaxonomyDto>>() }
        FilterDataDto(
            genres = genres.await().map { TaxonomyDto(it.id, it.name) },
            tags = tags.await().filter { (it.seriesCount ?: 0) > 0 }.map { TaxonomyDto(it.id, it.name) }.sortedBy { it.name },
        ).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.let { runCatching { it.parseAs<FilterDataDto>() }.getOrNull() }
        return FilterList(
            buildList {
                add(StatusFilter())
                add(SortFilter())
                add(TypeFilter())
                if (filterData == null) {
                    add(Filter.Header("Press 'Reset' to load genres and tags"))
                } else {
                    add(Filter.Header("Include/Exclude Genres (Tap to toggle)"))
                    add(GenreFilter(filterData.genres.map { TaxonomyTriState(it.id, it.name) }))
                    add(Filter.Header("Include/Exclude Tags (Tap to toggle)"))
                    add(TagFilter(filterData.tags.map { TaxonomyTriState(it.id, it.name) }))
                }
            },
        )
    }

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
            url = if (searchedSlug.isNotBlank()) mangaPathTemplate.slug("/series/$searchedSlug") else ""
            title = searchedSlug
                .substringAfterLast('/')
                .replace('-', ' ')
                .trim()
                .ifBlank { "Unknown Title" }
        }
    }

    // Data classes
    @Serializable
    class SearchResponse(
        val data: List<NovelDto>,
        val meta: MetaDto,
    )

    @Serializable
    class MetaDto(
        @SerialName("current_page") val currentPage: Int,
        @SerialName("last_page") val lastPage: Int,
        @SerialName("per_page") val perPage: Int,
        val total: Int,
    )

    @Serializable
    class NovelDto(
        private val id: Int,
        private val title: String,
        private val slug: String,
        @SerialName("alt_title") private val altTitle: String? = null,
        private val description: String? = null,
        private val type: String? = null,
        private val genres: List<GenreDto>? = null,
        private val tags: List<TagDto>? = null,
        private val cover: String? = null,
        @SerialName("cover_data_url") private val coverDataUrl: String? = null,
        @SerialName("user") private val author: AuthorDto? = null,
        @SerialName("chapters_count") private val chaptersCount: Int? = null,
        private val status: String? = null,
        private val subscribers: Int? = null,
        private val stats: StatsDto? = null,
        private val schedules: SchedulesDto? = null,
        private val notices: List<NoticeDto>? = null,
        @SerialName("global_note") private val globalNote: String? = null,
    ) {
        fun toSManga(baseUrl: String, mangaPathTemplate: SlugPath): SManga = SManga.create().apply {
            url = mangaPathTemplate.slug("/series/$slug")
            this.title = this@NovelDto.title.replace(INVISIBLE_CHARS, "").trim()
            thumbnail_url = if (!cover.isNullOrEmpty()) {
                if (cover.startsWith("http")) cover else "$baseUrl/$cover"
            } else {
                null
            }

            val altTitles = altTitle
                ?.split("\n", "/", " | ", ";")
                ?.map { Jsoup.parse(it).text() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                .orEmpty()

            setAltTitles(altTitles)

            val apiDescription = this@NovelDto.description?.let { htmlToPlainTextPreserveBreaks(it) }.orEmpty()

            this.description = buildString {
                if (apiDescription.isNotBlank()) {
                    append(apiDescription)
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

            this.author = this@NovelDto.author?.let { authorDto ->
                authorDto.name ?: authorDto.username
            }

            genre = buildList {
                this@NovelDto.genres?.forEach { add(it.name) }
                this@NovelDto.tags?.forEach { add(it.name) }
            }.map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString()

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
    class ChapterDto(
        val id: Int,
        val uuid: String? = null,
        val content: JsonElement = JsonNull,
        @SerialName("content_format") val contentFormat: String? = null,
        @SerialName("content_scheme") private val contentScheme: String? = null,
        @SerialName("remint_chapter_id") val remintChapterId: Int? = null,
        @SerialName("remint_chapter_uuid") val remintChapterUuid: String? = null,
        private val locked: LockedDto? = null,
        private val bought: BoughtDto? = null,
        private val bypass: Boolean = false,
    ) {
        val isPaid: Boolean
            get() = (locked?.price ?: 0) > 0 && bought?.boughtAt == null && !bypass

        fun decrypt(token: String): String? = runCatching {
            val isV2 = contentScheme == "fragmented-v2"
            val vid = if (isV2) tokenVid(token) else ""
            val cipherText = when (content) {
                is JsonArray -> unshuffle(
                    content.map { it.jsonPrimitive.content },
                    if (isV2) "$token:$vid:$id:fr-frag-v2" else "$token:$id:fr-frag-v1",
                )
                is JsonPrimitive -> content.content
                else -> return null
            }
            val raw = Base64.decode(cipherText, Base64.DEFAULT)
            val keySeed = if (isV2 && vid.isNotEmpty()) "$token:$vid:$id:fr-content-v2" else "$token:$id:fr-content-v1"
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sha256(keySeed), "AES"), GCMParameterSpec(128, raw, 0, 12))
            String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }.getOrNull()

        private fun tokenVid(token: String): String = runCatching {
            val payload = Base64.decode(token.substringBefore('.'), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            String(payload, Charsets.UTF_8).parseAs<ReadTokenPayloadDto>().vid
        }.getOrNull().orEmpty()

        private fun unshuffle(fragments: List<String>, seed: String): String {
            if (fragments.size < 2) return fragments.joinToString("")
            val digest = ByteBuffer.wrap(sha256(seed)).order(ByteOrder.LITTLE_ENDIAN)
            val state = IntArray(4) { digest.getInt(it * 4) }
            val perm = IntArray(fragments.size) { it }
            for (i in perm.lastIndex downTo 1) {
                val j = ((xorshift128(state).toLong() and 0xFFFFFFFFL) % (i + 1)).toInt()
                perm[i] = perm[j].also { perm[j] = perm[i] }
            }
            val restored = arrayOfNulls<String>(fragments.size)
            fragments.forEachIndexed { i, fragment -> restored[perm[i]] = fragment }
            return restored.joinToString("")
        }

        private fun xorshift128(state: IntArray): Int {
            val t = state[0]
            var r = t xor (t shl 11)
            r = r xor (r ushr 8)
            val o = state[3]
            state[0] = state[1]
            state[1] = state[2]
            state[2] = o
            return (o xor (o ushr 19) xor r).also { state[3] = it }
        }

        private fun sha256(input: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    }

    @Serializable
    class BoughtDto(
        @SerialName("bought_at") val boughtAt: String? = null,
    )

    @Serializable
    class ReadTokenRequest(
        @SerialName("chapter_id") private val chapterId: String,
    )

    @Serializable
    class ReadTokenDto(
        private val active: Boolean = false,
        val token: String? = null,
    ) {
        val isActive: Boolean
            get() = active && !token.isNullOrEmpty()
    }

    @Serializable
    class ReadTokenPayloadDto(
        val vid: String = "",
    )

    @Serializable
    class TipTapNode(
        val type: String? = null,
        val text: String? = null,
        val attrs: TipTapAttrs? = null,
        val marks: List<TipTapNode>? = null,
        val content: List<TipTapNode>? = null,
    )

    @Serializable
    class TipTapAttrs(
        val level: Int? = null,
        val href: String? = null,
    )

    @Serializable
    class TaxonomyDto(
        val id: Int,
        val name: String,
        @SerialName("series_count") val seriesCount: Int? = null,
    )

    @Serializable
    class FilterDataDto(
        val genres: List<TaxonomyDto>,
        val tags: List<TaxonomyDto>,
    )

    @Serializable
    class StatsDto(
        @SerialName("total_views") val totalViews: Int? = null,
        @SerialName("daily_views") val dailyViews: Int? = null,
        @SerialName("weekly_views") val weeklyViews: Int? = null,
        @SerialName("monthly_views") val monthlyViews: Int? = null,
        @SerialName("author_stats") val authorStats: AuthorStatsDto? = null,
        @SerialName("chapter_frequency") val chapterFrequency: ChapterFrequencyDto? = null,
        @SerialName("series_age") val seriesAge: SeriesAgeDto? = null,
    )

    @Serializable
    class AuthorStatsDto(
        @SerialName("total_works") val totalWorks: Int? = null,
        @SerialName("total_followers") val totalFollowers: Int? = null,
        @SerialName("total_views") val totalViews: Int? = null,
        @SerialName("member_since") @Serializable(with = FlexibleStringSerializer::class) val memberSince: String? = null,
    )

    @Serializable
    class ChapterFrequencyDto(
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
    class SeriesAgeDto(
        @SerialName("start_year") val startYear: Int? = null,
        @SerialName("years_active") val yearsActive: Int? = null,
        @SerialName("age_text") val ageText: String? = null,
    )

    @Serializable
    class SchedulesDto(
        val days: List<String>? = null,
        val values: List<Int>? = null,
        val time: List<String>? = null,
        val timezone: String? = null,
    )

    @Serializable
    class NoticeDto(
        val message: String? = null,
    )

    @Serializable
    class GenreDto(
        val id: Int,
        val name: String,
        val slug: String,
    )

    @Serializable
    class TagDto(
        val id: Int,
        val name: String,
        val slug: String,
    )

    @Serializable
    class AuthorDto(
        val username: String? = null,
        val name: String? = null,
    )

    @Serializable
    class GroupDto(
        val index: Int? = null,
        val slug: String? = null,
        val name: String? = null,
        @SerialName("abbreviation") val abbr: String? = null,
    )

    @Serializable
    class ChapterApiDto(
        val number: Int,
        val part: Int?,
        val title: String? = null,
        val slug: String? = null,
        val group: GroupDto? = null,
        @SerialName("created_at") val createdAt: String? = null,
        @SerialName("updated_at") val updatedAt: String? = null,
        val locked: LockedDto? = null,
    )

    @Serializable
    class LockedDto(
        val price: Int? = null,
        @SerialName("unlocked_at") val unlockedAt: String? = null,
    )

    companion object {
        private const val PREF_HIDE_LOCKED_CHAPTERS = "fenrirealm_hide_locked_chapters"
        private const val RATE_LIMIT_MESSAGE = "Rate limited by Fenrir Realm. Open the chapter in WebView, solve the check, then retry."
        private const val VERIFY_MESSAGE = "Couldn't verify access. Open the chapter in WebView, wait for it to load, then retry."
    }

    private open class UriSelectFilter(name: String, private val options: Array<Pair<String, String>>, state: Int = 0) : Filter.Select<String>(name, options.map { it.first }.toTypedArray(), state) {
        val value: String
            get() = options[state].second
    }

    private class StatusFilter :
        UriSelectFilter(
            "Status",
            arrayOf(
                "All" to "",
                "Ongoing" to "on-going",
                "Completed" to "completed",
                "Hiatus" to "hiatus",
                "On Hold" to "on-hold",
                "Dropped" to "dropped",
            ),
        )

    private class SortFilter :
        UriSelectFilter(
            "Sort",
            arrayOf(
                "Latest" to "latest",
                "Trending" to "trending",
                "Popular" to "popular",
                "Updated" to "updated",
                "Free" to "free",
                "Premium" to "premium",
                "A-Z" to "title",
                "Oldest" to "oldest",
            ),
        )

    private class TypeFilter :
        UriSelectFilter(
            "Type",
            arrayOf(
                "All" to "",
                "Light Novel" to "light_novel",
                "Web Novel" to "web_novel",
                "Novel" to "novel",
                "Original" to "original_novel",
            ),
        )

    private class TaxonomyTriState(val id: Int, name: String) : Filter.TriState(name)

    private class GenreFilter(genres: List<TaxonomyTriState>) : Filter.Group<TaxonomyTriState>("Genres", genres)

    private class TagFilter(tags: List<TaxonomyTriState>) : Filter.Group<TaxonomyTriState>("Tags", tags)
}

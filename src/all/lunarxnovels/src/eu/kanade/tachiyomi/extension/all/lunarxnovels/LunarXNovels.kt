package eu.kanade.tachiyomi.novelextension.all.lunarxnovels

import android.webkit.CookieManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.URLDecoder

@Source
abstract class LunarXNovels :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    private val apiHost by lazy { API_URL.toHttpUrl().host }

    // Logging in through WebView stores the site's bearer token in an "Authorization" cookie;
    // forwarding it lets the API serve chapters the account has unlocked.
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor { chain ->
        val request = chain.request()
        val token = if (request.url.host == apiHost) authToken() else null
        chain.proceed(if (token == null) request else request.newBuilder().header("Authorization", token).build())
    }.rateLimit(2) { it.host == apiHost }

    private fun authToken(): String? = CookieManager.getInstance().getCookie(baseUrl)
        ?.let { AUTH_COOKIE.find(it)?.groupValues?.get(1) }
        ?.let { URLDecoder.decode(it, "UTF-8") }
        ?.ifBlank { null }

    // ============================== Browse ================================

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList())

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$API_URL/api/novels/recently-updated".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .build()
        val novels = client.get(url).parseAs<RecentResponse>().data
        return MangasPage(novels.map { it.toSManga() }, novels.size == PAGE_SIZE)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (filters.firstInstanceOrNull<CatalogFilter>()?.selected == "legacy") {
            if (query.isBlank()) return MangasPage(emptyList(), false)
            val url = "$API_URL/api/novels/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", "1")
                .build()
            // Later pages of this endpoint come back as a bare empty array.
            return MangasPage(client.get(url).parseAs<LegacySearchResponse>().results.novels.map { it.toSManga() }, false)
        }

        val url = "$API_URL/api/novels/db/search".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", PAGE_SIZE.toString())
            if (query.isNotBlank()) addQueryParameter("query", query)
            if (lang != "all") addQueryParameter("language", lang)
            filters.firstInstanceOrNull<StatusFilter>()?.selected?.let { addQueryParameter("status", it) }
            filters.firstInstanceOrNull<OriginFilter>()?.selected?.let { addQueryParameter("country", it) }
            filters.firstInstanceOrNull<GenreFilter>()?.selected?.takeIf { it.isNotEmpty() }
                ?.let { addQueryParameter("genres", it.joinToString(",")) }
            val sort = filters.firstInstanceOrNull<SortFilter>()?.selected ?: if (query.isBlank()) "popular" else "relevance"
            addQueryParameter("sort", sort)
        }.build()
        val result = client.get(url).parseAs<SearchResponse>()
        return MangasPage(result.novels.map { it.toSManga() }, result.page < result.totalPages)
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        CatalogFilter(),
        SortFilter(),
        StatusFilter(),
        OriginFilter(),
        GenreFilter(),
    )

    // ============================== Details ===============================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novel/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.size < 2 || segments[0] != "novel") return null
        return fetchDetails(segments[1]).apply { this.url = segments[1] }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val details = if (fetchDetails) async { fetchDetails(manga.url) } else null
        val chapterList = if (fetchChapters) async { fetchChapters(manga.url) } else null
        SMangaUpdate(details?.await() ?: manga, chapterList?.await() ?: chapters)
    }

    // Lunar's own novels live under /title; anything else is a legacy (NovelPhoenix-scraped) entry.
    private suspend fun fetchDetails(slug: String): SManga {
        val response = client.get("$API_URL/api/novels/title/$slug", ensureSuccess = false)
        if (response.isSuccessful) return response.parseAs<TitleResponse>().novel.toSManga()
        response.close()

        val legacy = fetchLegacyNovel(slug) ?: throw Exception("Novel not found")
        return legacy.toSManga()
    }

    private suspend fun fetchLegacyNovel(slug: String): LegacyNovelDto? {
        val url = "$API_URL/api/novels".toHttpUrl().newBuilder().addQueryParameter("slug", slug).build()
        return client.get(url).parseAs<LegacyNovelResponse>().novel
            ?.takeUnless { it.title == "Error fetching novel" }
    }

    // ============================== Chapters ==============================

    private suspend fun fetchChapters(slug: String): List<SChapter> {
        val all = client.get("$API_URL/api/novels/chapters/$slug").parseAs<ChapterListResponse>().chapters
        // Same rule as the site: the legacy list is only used when Lunar has no chapters of its own.
        if (all.isEmpty()) return if (lang == "all" || lang == "en") fetchLegacyChapters(slug) else emptyList()

        val chapters = all.filter { !it.isComingSoon && (lang == "all" || it.language == lang) }
        if (chapters.isEmpty()) return emptyList()

        val isLocked = fetchLockRule(slug, chapters)
        val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)
        val multiLanguage = chapters.distinctBy { it.language }.size > 1
        return chapters.sortedByDescending { it.chapter }
            .mapNotNull { chapter ->
                val locked = isLocked(chapter)
                if (locked && !showLocked) null else chapter.toSChapter(slug, locked, multiLanguage)
            }
    }

    private suspend fun fetchLockRule(slug: String, chapters: List<ChapterDto>): (ChapterDto) -> Boolean = coroutineScope {
        val points = async { client.get("$API_URL/api/points-lock/novel/$slug").parseAs<PointsLockResponse>() }
        val gates = async { client.get("$API_URL/api/novel-gate/$slug").parseAs<GateResponse>().gates }
        val passwords = async { client.get("$API_URL/api/novels/password/info/$slug").parseAs<PasswordInfoResponse>() }

        val pointsInfo = points.await()
        val gateList = gates.await()
        val passwordInfo = passwords.await()
        val loggedIn = authToken() != null
        val newestFirst = chapters.map { it.chapter }.distinct().sortedDescending()
        val ownsSeries = "series" in pointsInfo.owned

        fun pointsLocked(chapter: ChapterDto) = !ownsSeries && "ch:${chapter.number}" !in pointsInfo.owned &&
            pointsInfo.locks.any { lock ->
                when {
                    lock.unit == "all" -> true
                    lock.unit.startsWith("latest:") ->
                        chapter.chapter in newestFirst.take(lock.unit.substringAfter(':').toIntOrNull() ?: 0)
                    lock.unit.startsWith("ch:") -> lock.unit.removePrefix("ch:") == chapter.number
                    // vol:* units only apply to EPUB volumes, which this source doesn't list.
                    else -> false
                }
            }

        fun gated(chapter: ChapterDto) = gateList.any { gate ->
            (gate.chapter == "__ALL__" || gate.chapter == chapter.number) &&
                ((gate.requireAccount && !loggedIn) || gate.requireGuild || gate.requireLevel > 0)
        }

        fun passwordLocked(chapter: ChapterDto) = passwordInfo.hasSeriesPassword ||
            passwordInfo.chapterPasswords.any {
                it.chapterNumber == chapter.number && (it.language == null || it.language == chapter.language)
            }

        val rule: (ChapterDto) -> Boolean = { pointsLocked(it) || gated(it) || passwordLocked(it) }

        rule
    }

    // Legacy chapters are numbered 1..N, with titles[N - 1] as the title of chapter N.
    private suspend fun fetchLegacyChapters(slug: String): List<SChapter> {
        val response = client.get("$API_URL/api/novels/chapter-titles?slug=$slug", ensureSuccess = false)
        val titles = if (response.isSuccessful) {
            response.parseAs<LegacyTitlesResponse>().titles
        } else {
            response.close()
            emptyList()
        }
        val count = titles.size.takeIf { it > 0 } ?: fetchLegacyNovel(slug)?.chapters?.toIntOrNull() ?: 0

        return (count downTo 1).map { number ->
            SChapter.create().apply {
                // No ?lang= marks a legacy chapter for fetchPageText.
                url = "/novel/$slug/$number"
                name = titles.getOrNull(number - 1)?.ifBlank { null } ?: "Chapter $number"
                chapter_number = number.toFloat()
            }
        }
    }

    // =============================== Pages ================================

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val chapterUrl = (baseUrl + page.url).toHttpUrl()
        val (slug, number) = chapterUrl.pathSegments.takeLast(2)
        val language = chapterUrl.queryParameter("lang")
        val content = if (language == null) fetchLegacyContent(slug, number) else fetchContent(chapterUrl, slug, number, language)
        return content.toHtml()
    }

    private suspend fun fetchContent(chapterUrl: HttpUrl, slug: String, number: String, language: String): String {
        val seeds = LunarCrypto.extractSeeds(client.get(chapterUrl).asJsoup())
        val url = "$API_URL/api/novels/r".toHttpUrl().newBuilder()
            .addPathSegment(LunarCrypto.mint(seeds, slug, number))
            .addQueryParameter("language", language)
            .build()

        val response = client.get(url, ensureSuccess = false)
        if (!response.isSuccessful) {
            val code = response.code
            val error = runCatching { response.parseAs<ErrorDto>() }.getOrNull()
            throw Exception(error?.message ?: error?.error ?: "HTTP $code")
        }
        val dto = response.parseAs<ChapterContentDto>()
        // A response for slug "unknown" is the site's decoy for a token minted from stale seeds.
        if (dto.slug == "unknown") throw Exception("Chapter token rejected, try again")

        // The plain "content" field is a truncated preview; the full text is in session_data.
        val full = dto.sessionData?.let { LunarCrypto.decryptSession(it, seeds).parseAs<ChapterContentDto>().content }
        return full ?: throw Exception("Chapter content not found")
    }

    private suspend fun fetchLegacyContent(slug: String, number: String): String {
        val url = "$API_URL/api/novels".toHttpUrl().newBuilder()
            .addQueryParameter("slug", slug)
            .addQueryParameter("chapter", number)
            .build()
        val chapter = client.get(url).parseAs<LegacyChapterResponse>().chapter
        val content = chapter?.content
        if (content.isNullOrBlank() || chapter.chapterTitle == "Error fetching chapter" || chapter.wordCount == 0) {
            throw Exception("Chapter not available")
        }
        return content
    }

    // Chapters are light markdown: blank-line paragraphs, **bold** and inline <center> blocks.
    private fun String.toHtml(): String = split(PARAGRAPH_BREAK)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("") { paragraph ->
            val html = paragraph.replace(BOLD, "<strong>$1</strong>").replace("\n", "<br>")
            if (html.startsWith("<center>")) html else "<p>$html</p>"
        }

    // ============================= Preferences ============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "List chapters behind ad points, passwords or account gates (marked with 🔒). " +
                "Chapters unlocked on the website become readable after logging in through WebView."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val API_URL = "https://api.lunarx.to"
        private const val PAGE_SIZE = 30
        private const val PREF_SHOW_LOCKED = "pref_show_locked"
        private val AUTH_COOKIE = Regex("""(?:^|;\s*)Authorization=([^;]+)""")
        private val PARAGRAPH_BREAK = Regex("""\n\s*\n""")
        private val BOLD = Regex("""\*\*(.+?)\*\*""")
    }
}

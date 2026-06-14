package eu.kanade.tachiyomi.novelextension.en.novelupdates

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.SourceTracker
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
// mostly ported from LNReader
class NovelUpdates :
    HttpSource(),
    NovelSource,
    ConfigurableSource,
    SourceTracker {

    override val name = "Novel Updates"
    override val baseUrl = "https://www.novelupdates.com"
    override val lang = "en"
    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override suspend fun fetchPageText(page: Page): String {
        val chapterUrl = if (page.url.startsWith("http")) page.url else baseUrl + page.url

        val response = client.newCall(GET(chapterUrl, headers)).execute()
        val body = response.body.string()
        val url = response.request.url.toString()
        val domainParts = url.lowercase().split("/")[2].split(".")

        val doc = Jsoup.parse(body, url)

        val title = doc.select("title").text().trim().lowercase()
        val blockedTitles = listOf(
            "bot verification",
            "just a moment...",
            "redirecting...",
            "un instant...",
            "you are being redirected...",
        )
        if (blockedTitles.contains(title)) {
            throw Exception("Captcha detected, please open in webview.")
        }

        if (!response.isSuccessful) {
            throw Exception("Failed to fetch ${response.request.url}: ${response.code} ${response.message}")
        }

        if (preferences.getBoolean(PREF_RETURN_FULL_HTML, false)) {
            return body
        }

        return getChapterBody(doc, domainParts, url)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val fullHtmlPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_RETURN_FULL_HTML
            title = "Return full HTML"
            summary = "If enabled, returns the full chapter HTML without extracting the content. Useful for custom parsers."
            setDefaultValue(false)
        }
        screen.addPreference(fullHtmlPref)

        val enableTrackingPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ENABLE_TRACKING
            title = "Enable NovelUpdates tracking"
            summary = "Master switch. When on, the source pushes events to your NovelUpdates reading list. Sub-toggles below pick what to push."
            setDefaultValue(false)
        }
        screen.addPreference(enableTrackingPref)

        val trackLastReadPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_TRACK_LAST_READ
            title = "Sync last-read chapter"
            summary = "Tick the latest read chapter."
            setDefaultValue(true)
        }
        screen.addPreference(trackLastReadPref)

        val trackNotesPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_TRACK_NOTES
            title = "Sync \"total chapters read\" notes"
            summary = "Also write \"total chapters read: N\" into the entry's notes field. Useful if you read off-list."
            setDefaultValue(false)
        }
        screen.addPreference(trackNotesPref)

        val markUnreadPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_TRACK_UNREAD
            title = "Push unread events"
            summary = "Also un-tick chapters on NovelUpdates when you mark them unread locally."
            setDefaultValue(false)
        }
        screen.addPreference(markUnreadPref)

        val protectHighestPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_PROTECT_HIGHEST
            title = "Don't override highest tracked chapter"
            summary = "Skip pushing a chapter that's lower than the highest chapter already tracked for this novel."
            setDefaultValue(true)
        }
        screen.addPreference(protectHighestPref)

        val resetCachePref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_RESET_CACHE_TOGGLE
            title = "Reset tracking cache"
            summary = "Toggle on then off to clear cached novel IDs and highest-tracked-chapter records."
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    preferences.edit()
                        .remove(PREF_NOVEL_ID_CACHE)
                        .remove(PREF_HIGHEST_CACHE)
                        .putBoolean(PREF_RESET_CACHE_TOGGLE, false)
                        .apply()
                    synchronized(novelIdCache) { novelIdCache.clear() }
                    false
                } else {
                    true
                }
            }
        }
        screen.addPreference(resetCachePref)
    }

    override val supportsChapterTracking: Boolean
        get() = preferences.getBoolean(PREF_ENABLE_TRACKING, false)

    override val supportsFavoritesTracking: Boolean
        get() = false

    override suspend fun onChaptersRead(
        manga: SManga,
        changedChapters: List<SChapter>,
        allChapters: List<SChapter>,
        categories: List<String>,
    ) {
        if (!preferences.getBoolean(PREF_ENABLE_TRACKING, false)) return
        val target = changedChapters
            .filter { it.chapter_number > 0f }
            .maxByOrNull { it.chapter_number }
            ?: changedChapters.lastOrNull()
            ?: return

        if (preferences.getBoolean(PREF_PROTECT_HIGHEST, true)) {
            val cached = highestTrackedNumber(cacheKey(manga.url))
            if (cached != null && target.chapter_number <= cached) return
        }

        val novelId = resolveNovelId(manga) ?: return

        var anySuccess = false

        if (preferences.getBoolean(PREF_TRACK_LAST_READ, true)) {
            anySuccess = syncChapter(novelId, target, checked = "yes") || anySuccess
        }

        if (preferences.getBoolean(PREF_TRACK_NOTES, false)) {
            val chapterCount = target.chapter_number.toInt().coerceAtLeast(1)
            anySuccess = updateNotesProgress(novelId, chapterCount) || anySuccess
        }

        if (anySuccess) {
            recordHighestTracked(cacheKey(manga.url), target.chapter_number)
        }
    }

    override suspend fun onChaptersUnread(
        manga: SManga,
        changedChapters: List<SChapter>,
        allChapters: List<SChapter>,
        categories: List<String>,
    ) {
        if (!preferences.getBoolean(PREF_ENABLE_TRACKING, false)) return
        if (!preferences.getBoolean(PREF_TRACK_UNREAD, false)) return
        val target = changedChapters
            .filter { it.chapter_number > 0f }
            .minByOrNull { it.chapter_number }
            ?: changedChapters.firstOrNull()
            ?: return
        val novelId = resolveNovelId(manga) ?: return

        if (preferences.getBoolean(PREF_TRACK_LAST_READ, true)) {
            if (syncChapter(novelId, target, checked = "no")) {
                forgetHighestTracked(cacheKey(manga.url))
            }
        }
    }

    override suspend fun onFavorited(manga: SManga, categories: List<String>) = Unit

    override suspend fun onUnfavorited(manga: SManga, categories: List<String>) = Unit

    private fun syncChapter(novelId: String, chapter: SChapter, checked: String): Boolean {
        val chapterId = Regex("/(\\d+)/").find(chapter.url)?.groupValues?.get(1) ?: return false
        val url = "$baseUrl/readinglist_update.php?rid=$chapterId&sid=$novelId&checked=$checked"
        return try {
            client.newCall(GET(url, headers)).execute().use { resp -> resp.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    private fun updateNotesProgress(novelId: String, chapters: Int): Boolean = try {
        val getBody = FormBody.Builder()
            .add("action", "wi_notestagsfic")
            .add("strSID", novelId)
            .build()
        val getResponse = client.newCall(
            POST("$baseUrl/wp-admin/admin-ajax.php", headers, getBody),
        ).execute()
        val responseText = getResponse.use { it.body.string() }
        val cleaned = responseText.trim().replace(Regex("\\}\\s*0+$"), "}")
        val existingNotes = Regex("\"notes\"\\s*:\\s*\"([^\"]*)\"").find(cleaned)?.groupValues?.get(1) ?: ""
        val existingTags = Regex("\"tags\"\\s*:\\s*\"([^\"]*)\"").find(cleaned)?.groupValues?.get(1) ?: ""

        val pattern = Regex("total\\s+chapters\\s+read:\\s*\\d+", RegexOption.IGNORE_CASE)
        val replacement = "total chapters read: $chapters"
        val updatedNotes = when {
            pattern.containsMatchIn(existingNotes) -> existingNotes.replace(pattern, replacement)
            existingNotes.isEmpty() -> replacement
            else -> "$existingNotes<br/>$replacement"
        }

        val updateBody = FormBody.Builder()
            .add("action", "wi_rlnotes")
            .add("strSID", novelId)
            .add("strNotes", updatedNotes)
            .add("strTags", existingTags)
            .build()
        client.newCall(
            POST("$baseUrl/wp-admin/admin-ajax.php", headers, updateBody),
        ).execute().use { resp -> resp.isSuccessful }
    } catch (e: Exception) {
        false
    }

    private fun resolveNovelId(manga: SManga): String? {
        val key = cacheKey(manga.url)
        loadNovelIdCache()[key]?.let { return it }
        synchronized(novelIdCache) {
            novelIdCache[key]?.let { return it }
        }

        val url = if (manga.url.startsWith("http")) manga.url else baseUrl + manga.url
        val resolved: String? = try {
            val response = client.newCall(GET(url)).execute()
            val doc = Jsoup.parse(response.use { it.body.string() }, url)
            val shortlink: String = doc.select("link[rel=shortlink]").attr("href")
            val shortlinkId: String? = Regex("\\?p=(\\d+)").find(shortlink)?.groupValues?.get(1)
            val fallbackId: String? = doc.select("input#mypostid").attr("value")
                .takeIf { value -> value.isNotEmpty() }
            shortlinkId ?: fallbackId
        } catch (e: Exception) {
            null
        }
        if (resolved != null) {
            persistNovelId(key, resolved)
        }
        return resolved
    }

    private fun cacheKey(url: String): String = url.removePrefix(baseUrl).substringBefore('?').trimEnd('/')

    private fun loadNovelIdCache(): Map<String, String> {
        synchronized(novelIdCache) {
            if (novelIdCache.isEmpty()) {
                val raw = preferences.getString(PREF_NOVEL_ID_CACHE, "") ?: ""
                raw.split('\n').forEach { line ->
                    val sep = line.indexOf('|')
                    if (sep > 0) novelIdCache[line.substring(0, sep)] = line.substring(sep + 1)
                }
            }
            return novelIdCache.toMap()
        }
    }

    private fun persistNovelId(url: String, id: String) {
        synchronized(novelIdCache) {
            novelIdCache[url] = id
            val serialized = novelIdCache.entries.joinToString("\n") { "${it.key}|${it.value}" }
            preferences.edit().putString(PREF_NOVEL_ID_CACHE, serialized).apply()
        }
    }

    private fun highestTrackedNumber(mangaUrl: String): Float? {
        val raw = preferences.getString(PREF_HIGHEST_CACHE, "") ?: ""
        raw.split('\n').forEach { line ->
            val sep = line.indexOf('|')
            if (sep > 0 && line.substring(0, sep) == mangaUrl) {
                return line.substring(sep + 1).toFloatOrNull()
            }
        }
        return null
    }

    private fun recordHighestTracked(mangaUrl: String, value: Float) {
        val raw = preferences.getString(PREF_HIGHEST_CACHE, "") ?: ""
        val rebuilt = StringBuilder()
        var replaced = false
        raw.split('\n').filter { it.isNotEmpty() }.forEach { line ->
            val sep = line.indexOf('|')
            if (sep > 0 && line.substring(0, sep) == mangaUrl) {
                if (!replaced) {
                    rebuilt.append(mangaUrl).append('|').append(value).append('\n')
                    replaced = true
                }
            } else {
                rebuilt.append(line).append('\n')
            }
        }
        if (!replaced) rebuilt.append(mangaUrl).append('|').append(value).append('\n')
        preferences.edit().putString(PREF_HIGHEST_CACHE, rebuilt.toString()).apply()
    }

    private fun forgetHighestTracked(mangaUrl: String) {
        val raw = preferences.getString(PREF_HIGHEST_CACHE, "") ?: return
        val rebuilt = raw.split('\n')
            .filter { line ->
                val sep = line.indexOf('|')
                sep <= 0 || line.substring(0, sep) != mangaUrl
            }
            .joinToString("\n")
        preferences.edit().putString(PREF_HIGHEST_CACHE, rebuilt).apply()
    }

    private val novelIdCache = mutableMapOf<String, String>()

    companion object {
        private const val PREF_RETURN_FULL_HTML = "pref_return_full_html"
        private const val PREF_ENABLE_TRACKING = "pref_enable_tracking"
        private const val PREF_TRACK_LAST_READ = "pref_track_last_read"
        private const val PREF_TRACK_NOTES = "pref_track_notes"
        private const val PREF_TRACK_UNREAD = "pref_track_unread"
        private const val PREF_PROTECT_HIGHEST = "pref_protect_highest"
        private const val PREF_RESET_CACHE_TOGGLE = "pref_reset_cache_toggle"
        private const val PREF_NOVEL_ID_CACHE = "pref_novel_id_cache"
        private const val PREF_HIGHEST_CACHE = "pref_highest_tracked_cache"
    }

    private fun getChapterBody(doc: Document, domain: List<String>, chapterUrl: String): String {
        val unwanted = listOf("app", "blogspot", "casper", "wordpress", "www")
        val targetDomain = domain.find { !unwanted.contains(it) }

        var chapterTitle = ""
        var chapterContent = ""
        var chapterText = ""

        // --- WordPress Detection ---
        val matches = { selector: String, attr: String?, regex: Regex ->
            doc.select(selector).any { el ->
                val value = if (attr != null) el.attr(attr) else (el.html().ifEmpty { el.text() })
                regex.containsMatchIn(value.lowercase())
            }
        }

        var isWordPress = listOf(
            matches("meta[name=\"generator\"]", "content", Regex("wordpress|site kit")),
            matches("link, script, img", "src", Regex("/wp-content/|/wp-includes/")),
            matches("link", "href", Regex("/wp-content/|/wp-includes/")),
            matches("link[rel=\"https://api.w.org/\"]", "href", Regex(".*")),
            matches("link[rel=\"EditURI\"]", "href", Regex("xmlrpc\\.php")),
            matches("body", "class", Regex("wp-admin|wp-custom-logo|logged-in")),
            matches("script", null, Regex("wp-embed|wp-emoji|wp-block")),
        ).any { it }

        var isBlogspot = listOf(
            matches("meta[name=\"generator\"]", "content", Regex("blogger")),
            matches("meta[name=\"google-adsense-platform-domain\"]", "content", Regex("blogspot")),
            matches("link[rel=\"alternate\"]", "href", Regex("blogger\\.com/feeds|blogspot\\.com/feeds")),
            matches("link", "href", Regex("www\\.blogger\\.com/static|www\\.blogger\\.com/dyn-css")),
            matches("script", null, Regex("_WidgetManager\\._Init|_WidgetManager\\._RegisterWidget")),
        ).any { it }

        // Outlier sites that should NOT use the platform auto-detection path
        // Last edited in version 3 by Batorian - 03/06/2026
        val outliers = listOf(
            "asuratls",
            "fictionread",
            "hiraethtranslation",
            "infinitenoveltranslations",
            "leafstudio",
            "machineslicedbread",
            "mirilu",
            "novelworldtranslations",
            "sacredtexttranslations",
            "stabbingwithasyringe",
            "tinytranslation",
            "vampiramtl",
        )
        if (domain.any { outliers.contains(it) }) {
            isWordPress = false
            isBlogspot = false
        }

        // Platform-specific bloat/title/content config
        data class PlatformConfig(
            val bloat: List<String>,
            val title: List<String>,
            val content: List<String>,
        )

        val platformConfig = mapOf(
            "wordpress" to PlatformConfig(
                bloat = listOf(
                    ".ad", ".author-avatar", ".chapter-warning", ".entry-meta",
                    ".ezoic-ad", ".mb-center", ".modern-footnotes-footnote__note",
                    ".patreon-widget", ".post-cats", ".pre-bar", ".sharedaddy",
                    ".sidebar", ".swg-button-v2-light", ".wp-block-buttons",
                    ".wp-dark-mode-switcher", ".wp-next-post-navi",
                    "#hpk", "#jp-post-flair", "#textbox",
                ),
                title = listOf(
                    ".entry-title", ".chapter__title", ".title-content",
                    ".wp-block-post-title", ".title_story", "#chapter-heading",
                    ".chapter-title", "head title", "h1:first-of-type",
                    "h2:first-of-type", ".active",
                ),
                content = listOf(
                    ".chapter__content", ".entry-content", ".text_story",
                    ".post-content", ".contenta", ".single_post",
                    ".main-content", ".reader-content", "#content",
                    "#the-content", "article.post", ".chp_raw",
                ),
            ),
            "blogspot" to PlatformConfig(
                bloat = listOf(".button-container", ".ChapterNav", ".ch-bottom", ".separator"),
                title = listOf(".entry-title", ".post-title", "head title"),
                content = listOf(".content-post", ".entry-content", ".post-body"),
            ),
        )

        if (!isWordPress && !isBlogspot) {
            // Per-site extraction
            when (targetDomain) {
                // Last edited in version 3 by Batorian - 03/06/2026
                "akutranslations" -> {
                    val apiUrl = chapterUrl.replace("/novel", "/api/novel")
                    val response = client.newCall(GET(apiUrl, headers)).execute()
                    val json = response.body.string()
                    val contentMatch = Regex(""""content"\s*:\s*"([\s\S]*?)(?<!\\)"""").find(json)
                    val rawContent = contentMatch?.groupValues?.get(1)
                        ?: throw Exception("Invalid API response structure.")
                    chapterContent = rawContent
                        .replace("\\n", "\n").replace("\\\"", "\"")
                        .trim().split(Regex("\n+"))
                        .map { it.trim() }.filter { it.isNotEmpty() }
                        .joinToString("\n") { "<p>$it</p>" }
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "asuratls" -> {
                    val titleElement = doc.select(".post-body div b").first()
                    chapterTitle = titleElement?.text() ?: ""
                    titleElement?.remove()
                    chapterContent = doc.select(".post-body").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "brightnovels" -> {
                    val dataPage = doc.select("#app").attr("data-page")
                    if (dataPage.isNullOrEmpty()) throw Exception("data-page attribute not found on Bright Novels.")
                    // Extract title and content from JSON
                    val titleMatch = Regex(""""title"\s*:\s*"([^"]+)"""").find(dataPage)
                    val contentMatch = Regex(""""content"\s*:\s*"([\s\S]*?)(?<!\\)",""").find(dataPage)
                    val extractedTitle = titleMatch?.groupValues?.get(1) ?: ""
                    val extractedContent = contentMatch?.groupValues?.get(1)
                        ?.replace("\\u003c", "<")?.replace("\\u003e", ">")
                        ?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                    val cleaned = Jsoup.parse(extractedContent).also { it.select("script, style").remove() }.html()
                    chapterText = "<h2>$extractedTitle</h2><hr><br>$cleaned"
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "canonstory" -> {
                    val parts = chapterUrl.split("/")
                    if (parts.size < 7) throw Exception("Invalid chapter URL structure")
                    val novelSlug = parts[4]
                    val chapterSlug = parts[6]
                    val apiUrl = "${parts[0]}//${parts[2]}/api/public/chapter-by-slug/$novelSlug/$chapterSlug"
                    val response = client.newCall(GET(apiUrl, headers)).execute()
                    val json = response.body.string()
                    val chapterNumberMatch = Regex(""""chapterNumber"\s*:\s*(\d+)""").find(json)
                    val titleMatch = Regex(""""title"\s*:\s*"([^"]+)"""").find(json)
                    val contentMatch = Regex(""""content"\s*:\s*"([\s\S]*?)(?<!\\)"""").find(json)
                    val chapterNumber = chapterNumberMatch?.groupValues?.get(1) ?: ""
                    val title = titleMatch?.groupValues?.get(1) ?: ""
                    val content = contentMatch?.groupValues?.get(1)?.replace("\\n", "\n") ?: ""
                    chapterTitle = if (title.isNotEmpty()) "Chapter $chapterNumber - $title" else "Chapter $chapterNumber"
                    chapterContent = content.replace("\n", "<br>")
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "daoist" -> {
                    chapterTitle = doc.select(".chapter__title").first()?.text() ?: ""
                    doc.select("span.patreon-lock-icon").remove()
                    doc.select("img[data-src]").forEach { el ->
                        val dataSrc = el.attr("data-src")
                        if (dataSrc.isNotEmpty()) {
                            el.attr("src", dataSrc)
                            el.removeAttr("data-src")
                        }
                    }
                    chapterContent = doc.select(".chapter__content").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "dreamy-translations" -> {
                    chapterTitle = doc.select("h1 > span").first()?.text() ?: ""
                    val content = doc.select(".chapter-content > div").first()
                    content?.select("em")?.forEach { em -> em.wrap("<p></p>") }
                    chapterContent = content?.html() ?: ""
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "fictionread" -> {
                    listOf(".content > style", ".highlight-ad-container", ".meaning", ".word")
                        .forEach { doc.select(it).remove() }
                    chapterTitle = doc.select(".title-image span").first()?.text() ?: ""
                    doc.select(".content").first()?.children()?.forEach { el ->
                        if (el.attr("id").contains("Chaptertitle-info")) {
                            el.remove()
                            return@forEach
                        }
                    }
                    chapterContent = doc.select(".content").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "genesistudio" -> {
                    val apiUrl = "$chapterUrl/__data.json?x-sveltekit-invalidated=001"
                    val response = client.newCall(GET(apiUrl, headers)).execute()
                    val json = response.body.string()
                    // Parse nodes array and look for the data node with content/notes/footnotes
                    val dataNodeMatch = Regex(""""type":"data","data":\{([^}]+)\}""").find(json)
                    // Simplified: look for the content key mapping in the JSON
                    // The structure is complex; extract the full content as-is
                    val contentMatch = Regex(""""content":"([\s\S]*?)","notes"""").find(json)
                    val notesMatch = Regex(""""notes":"([\s\S]*?)","footnotes"""").find(json)
                    val footnotesMatch = Regex(""""footnotes":"([\s\S]*?)"""").find(json)
                    val content = contentMatch?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                    val notes = notesMatch?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                    val footnotes = footnotesMatch?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                    chapterText = content +
                        (if (notes.isNotEmpty()) "<h2>Notes</h2><br>$notes" else "") +
                        footnotes
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "greenz" -> {
                    val chapterSlug = chapterUrl.split("/").last()
                    val apiUrl = "https://greenz.com/api/chapters/slug/$chapterSlug"
                    val response = client.newCall(GET(apiUrl, headers)).execute()
                    val json = response.body.string()
                    val nameMatch = Regex(""""name"\s*:\s*"([^"]+)"""").find(json)
                    val numMatch = Regex(""""chapterNumber"\s*:\s*(\d+)""").find(json)
                    val contentMatch = Regex(""""content"\s*:\s*"([\s\S]*?)(?<!\\)"""").find(json)
                    val chapterName = nameMatch?.groupValues?.get(1) ?: ""
                    val chapterNumber = numMatch?.groupValues?.get(1) ?: ""
                    val rawContent = contentMatch?.groupValues?.get(1)
                        ?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                    chapterTitle = "Chapter $chapterNumber - $chapterName"
                    chapterContent = Jsoup.parse(rawContent).html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "hiraethtranslation" -> {
                    chapterTitle = doc.select("li.active").first()?.text() ?: ""
                    chapterContent = doc.select(".text-left").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "hostednovel" -> {
                    chapterTitle = doc.select("#chapter-title").first()?.text() ?: ""
                    chapterContent = doc.select("#chapter-content").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "infinitenoveltranslations" -> {
                    val redirectUrl = doc.select("article > p > a").first()?.attr("href") ?: ""
                    val targetDoc = if (redirectUrl.isNotEmpty()) {
                        val resp = client.newCall(GET(redirectUrl, headers)).execute()
                        Jsoup.parse(resp.body.string(), redirectUrl)
                    } else {
                        doc
                    }
                    chapterContent = targetDoc.select(".entry-content").html()
                    chapterTitle = targetDoc.select(".entry-title").text()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "inoveltranslation" -> {
                    listOf("header", "section").forEach { doc.select(it).remove() }
                    chapterText = doc.select(".styles_content__JHK8G").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                // mii translates
                "isotls" -> {
                    listOf("footer", "header", "nav", ".ezoic-ad", ".ezoic-adpicker-ad", ".ezoic-videopicker-video")
                        .forEach { doc.select(it).remove() }
                    chapterTitle = doc.select("head title").first()?.text() ?: ""
                    chapterContent = doc.select("main article").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "ko-fi" -> {
                    val scriptHtml = doc.select("script:containsData(shadowDom.innerHTML)").html()
                    val match = Regex("""shadowDom\.innerHTML \+= '(<div.*?)';""").find(scriptHtml)
                    if (match != null) chapterText = match.groupValues[1]
                }

                // Last edited in version 5 by Batorian - 14/06/2026
                "konkon" -> {
                    // chapterUrl: https://konkon.ink/read/chapter/22528/chapter-131-...
                    // API:        https://api-k.konkon.ink/api/public/chapters/22528
                    val chapterId = chapterUrl.split("/")[5]
                    val apiUrl = "https://api-k.konkon.ink/api/public/chapters/$chapterId"

                    val response = client.newCall(GET(apiUrl, headers)).execute()
                    if (!response.isSuccessful) throw Exception("Failed to fetch chapter: ${response.code}")

                    val json = response.body.string()

                    val isLocked = Regex(""""locked"\s*:\s*true""").containsMatchIn(json)
                    if (isLocked) throw Exception("Chapter is locked. Please open in webview and log in.")

                    val titleMatch = Regex(""""title"\s*:\s*"([^"]+)"""").find(json)
                    val contentMatch = Regex(""""content"\s*:\s*"([\s\S]*?)(?<!\\)",""").find(json)

                    chapterTitle = titleMatch?.groupValues?.get(1)
                        ?.replace("\\u2026", "…")
                        ?.replace("\\u2019", "\u2019")
                        ?: ""
                    chapterContent = contentMatch?.groupValues?.get(1)
                        ?.replace("\\/", "/")
                        ?.replace("\\\"", "\"")
                        ?: throw Exception("Could not extract chapter content.")
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "leafstudio" -> {
                    chapterTitle = doc.select(".title").first()?.text() ?: ""
                    chapterContent = doc.select(".chapter_content").joinToString("") { it.outerHtml() }
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "machineslicedbread" -> {
                    val urlParts = chapterUrl.split("/").filter { it.isNotEmpty() }
                    val pathSegments = urlParts.drop(2)
                    val targetDoc = if (pathSegments.size == 1) {
                        val redirectPath = doc.select(".entry-content a").first()?.attr("href")
                            ?: throw Exception("Chapter path not found.")
                        val resp = client.newCall(GET(redirectPath, headers)).execute()
                        Jsoup.parse(resp.body.string(), redirectPath)
                    } else {
                        doc
                    }
                    chapterText = targetDoc.select(".entry-content").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "mirilu" -> {
                    doc.select("#jp-post-flair").remove()
                    val titleElement = doc.select(".entry-content p strong").first()
                    chapterTitle = titleElement?.text() ?: ""
                    titleElement?.remove()
                    chapterContent = doc.select(".entry-content").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "mythoriatales" -> {
                    // Fetch script-2 to get the Next.js Server Action hash
                    val scriptHtml = doc.select("script:containsData(script-2)").joinToString("") { it.html() }
                    if (scriptHtml.isEmpty()) throw Exception("Failed to find script-2")

                    // Match all instances of the script-2 pattern
                    val matches2 = Regex(""""script-2.*?[^_]+([^"\\]+)""").findAll(scriptHtml).toList()

                    // Emulate JS `matches[1]?.[1]`: get the second match, then grab its capture group
                    val rawScriptPath = matches2.getOrNull(1)?.groupValues?.get(1)
                        ?: throw Exception("Failed to extract script-2 URL")

                    // Clean up any remaining escaped slashes from NextJS payload formatting
                    val scriptPath = rawScriptPath.replace("\\/", "/").trimStart('/')

                    // Build URL safely via resolve()
                    val scriptUrl = chapterUrl.toHttpUrl().resolve("/$scriptPath")?.toString()
                        ?: throw Exception("Failed to build valid script URL")

                    val scriptText = client.newCall(GET(scriptUrl, headers)).execute().body.string()
                    val actionHash = Regex("[a-f0-9]{42}").find(scriptText)?.value
                        ?: throw Exception("Failed to extract ACTION_HASH")

                    val urlParts2 = chapterUrl.split("/")
                    val slug = urlParts2[4]
                    val chapterNum = urlParts2[6].toIntOrNull() ?: 0

                    // Essential headers to prevent NextJS from dropping/blocking the action pipeline
                    val rscHeaders = headers.newBuilder()
                        .set("Accept", "text/x-component")
                        .set("Content-Type", "text/plain;charset=UTF-8")
                        .set("next-action", actionHash)
                        .set("Origin", "https://" + chapterUrl.toHttpUrl().host)
                        .set("Referer", chapterUrl)
                        .build()

                    // Next.js expects arguments prefixed with their array position index '0='
                    val rscBody = """["$slug",$chapterNum]""".toRequestBody("text/plain;charset=UTF-8".toMediaType())

                    val rscResponse = client.newCall(
                        okhttp3.Request.Builder().url(chapterUrl).headers(rscHeaders).post(rscBody).build(),
                    ).execute()

                    if (!rscResponse.isSuccessful) throw Exception("Failed to fetch chapter: ${rscResponse.code}")

                    val rscText = rscResponse.body.string().replace(Regex("""(\d+:[{TE])"""), "\n$1")
                    val segments = rscText.split(Regex("""\n(?=\d+:[{TE])"""))

                    val contentSegment = segments
                        .filter { Regex("""^\d+:T""").containsMatchIn(it) && !it.startsWith("0:") }
                        .joinToString("") { it.replace(Regex("""^\d+:T[0-9a-f]+,"""), "") }

                    if (contentSegment.isEmpty()) throw Exception("Could not find chapter content segment in stream.")

                    val metaSegment = segments.find { it.startsWith("1:") }
                    if (metaSegment != null) {
                        try {
                            val metaJson = metaSegment.substring(2)
                            val titleMatch2 = Regex(""""title"\s*:\s*"([^"]+)"""").find(metaJson)
                            val numMatch2 = Regex(""""chapterNumber"\s*:\s*(\d+)""").find(metaJson)
                            val t = titleMatch2?.groupValues?.get(1)
                            val n = numMatch2?.groupValues?.get(1)?.toIntOrNull() ?: chapterNum
                            if (t != null) chapterTitle = "Chapter $n: $t"
                        } catch (_: Exception) { }
                    }
                    if (chapterTitle.isEmpty()) chapterTitle = "Chapter $chapterNum"

                    chapterContent = contentSegment.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString("\n") { "<p>$it</p>" }
                        .replace(Regex("""\[dialogue\s+speaker="([^"]*)"\](.*?)\[/dialogue\]""", RegexOption.IGNORE_CASE), "$1: $2")
                        .replace(Regex("""\[sfx\].*?\[/sfx\]""", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("""\[/?(dialogue|sfx)[^\]]*\]""", RegexOption.IGNORE_CASE), "")
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "novelplex" -> {
                    doc.select(".passingthrough_adreminder").remove()
                    chapterTitle = doc.select(".halChap--jud").first()?.text() ?: ""
                    chapterContent = doc.select(".halChap--kontenInner").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "novelshub" -> {
                    val segments2 = chapterUrl.split("/")
                    val novelSlug = segments2[segments2.size - 2]
                    val chapterSlug2 = segments2.last()
                    val apiUrl = "https://api.novelshub.org/api/chapter?mangaslug=$novelSlug&chapterslug=$chapterSlug2"
                    val response = client.newCall(GET(apiUrl, headers)).execute()
                    val json = response.body.string()
                    val numMatch = Regex(""""number"\s*:\s*(\d+)""").find(json)
                    val contentMatch = Regex(""""content"\s*:\s*"([\s\S]*?)(?<!\\)"""").find(json)
                    val chapterNumber = numMatch?.groupValues?.get(1) ?: ""
                    val rawContent = contentMatch?.groupValues?.get(1)
                        ?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                    chapterTitle = "Chapter $chapterNumber"
                    val contentDoc = Jsoup.parse(rawContent)
                    contentDoc.select("div").forEach { el ->
                        val style = el.attr("style")
                        if (style.isEmpty()) return@forEach
                        when {
                            Regex("border:.*#ff6b00").containsMatchIn(style) ->
                                el.removeAttr("style").addClass("novels-hub_box_orange")
                            Regex("color:.*#ff6b00.*text-transform:.*uppercase").containsMatchIn(style) ->
                                el.removeAttr("style").addClass("novels-hub_box-title_orange")
                            Regex("color:.*white.*border-top:.*#ff6b00").containsMatchIn(style) ->
                                el.removeAttr("style").addClass("novels-hub_box-text_orange")
                            Regex("border:.*#00ff88").containsMatchIn(style) ->
                                el.removeAttr("style").addClass("novels-hub_box_green")
                            Regex("color:.*#00ff88.*text-transform:.*uppercase").containsMatchIn(style) ->
                                el.removeAttr("style").addClass("novels-hub_box-title_green")
                            Regex("border-left:.*#00ff88").containsMatchIn(style) ->
                                el.removeAttr("style").addClass("novels-hub_comment_green")
                            Regex("border:.*#0066ff").containsMatchIn(style) ->
                                el.removeAttr("style").addClass("novels-hub_box_blue")
                            Regex("color:.*#0099ff.*text-transform:.*uppercase").containsMatchIn(style) ->
                                el.removeAttr("style").addClass("novels-hub_box-title_blue")
                            Regex("color:.*#d0d0d0").containsMatchIn(style) ->
                                el.removeAttr("style").addClass("novels-hub_box-text_blue")
                        }
                    }
                    contentDoc.select("span").forEach { el ->
                        val style = el.attr("style")
                        if (style.isEmpty()) return@forEach
                        when {
                            Regex("color:.*#ff6b6b").containsMatchIn(style) ->
                                el.removeAttr("style").addClass("novels-hub_text_red")
                            Regex("color:.*#4d9fff").containsMatchIn(style) ->
                                el.removeAttr("style").addClass("novels-hub_text_blue")
                            Regex("color:.*#a78bfa").containsMatchIn(style) ->
                                el.removeAttr("style").addClass("novels-hub_text_purple")
                        }
                    }
                    chapterContent = contentDoc.html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "novelworldtranslations" -> {
                    doc.select(".separator img").remove()
                    doc.select(".entry-content a").filter { el ->
                        el.attr("href").contains("https://novelworldtranslations.blogspot.com")
                    }.forEach { it.parent()?.remove() }
                    chapterTitle = doc.select(".entry-title").first()?.text() ?: ""
                    val rawContent = doc.select(".entry-content").html()
                        .replace("&nbsp;", "").replace("\n", "<br>")
                    val contentDoc = Jsoup.parse(rawContent)
                    contentDoc.select("span, p, div").forEach { el ->
                        if (el.text().trim().isEmpty()) el.remove()
                    }
                    chapterContent = contentDoc.html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "patreon" -> {
                    doc.select("#track-click, [class*=\"hidden \"]").remove()
                    chapterTitle = doc.select("h1[data-tag=\"post-title\"]").text()
                    chapterContent = doc.select("[data-tag=\"post-card\"] [class*=\"PaddingTop\"]").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "r-p-d" -> {
                    val parts = chapterUrl.split("/")
                    val resolveUrl = "${parts[0]}//${parts[2]}/resolve?p=/${parts.drop(3).joinToString("/")}"
                    val resolveResponse = client.newCall(GET(resolveUrl, headers)).execute()
                    val resolveJson = resolveResponse.body.string()
                    val locationMatch = Regex(""""location"\s*:\s*"([^"]+)"""").find(resolveJson)
                    val location = locationMatch?.groupValues?.get(1) ?: chapterUrl
                    val parts2 = location.split("/")
                    val base = "${parts2[0]}//${parts2[2]}"

                    val metaResponse = client.newCall(
                        GET("$base/api/chapter-meta?seriesSlug=${parts2[4]}&chapterSlug=${parts2[5]}", headers),
                    ).execute()
                    val metaJson = metaResponse.body.string()
                    val idMatch = Regex(""""id"\s*:\s*(\d+)""").find(metaJson)
                    val id = idMatch?.groupValues?.get(1) ?: throw Exception("Failed to get chapter id")

                    val tokenResponse = client.newCall(GET("$base/api/chapters/$id/parts-token", headers)).execute()
                    val tokenJson = tokenResponse.body.string()
                    val tokenMatch = Regex(""""token"\s*:\s*"([^"]+)"""").find(tokenJson)
                    val token = tokenMatch?.groupValues?.get(1) ?: throw Exception("Failed to get token")

                    var total = 1
                    var i = 1
                    while (i <= total) {
                        val partResponse = client.newCall(
                            GET("$base/api/chapters/$id/parts?index=$i&token=$token", headers),
                        ).execute()
                        val partJson = partResponse.body.string()
                        val markdownMatch = Regex(""""markdown"\s*:\s*"([\s\S]*?)(?<!\\)"""").find(partJson)
                        val totalMatch = Regex(""""total"\s*:\s*(\d+)""").find(partJson)
                        val markdown = markdownMatch?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                        total = totalMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        chapterText += "<p>" + markdown.replace("\n\n", "</p><p>") + "</p>"
                        i++
                    }
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "raeitranslations" -> {
                    val parts = chapterUrl.split("/")
                    val apiUrl = "${parts[0]}//api.${parts[2]}/api/chapters/single?id=${parts[3]}&num=${parts[4]}"
                    val response = client.newCall(GET(apiUrl, headers)).execute()
                    val json = response.body.string()
                    val chapTagMatch = Regex(""""chapTag"\s*:\s*"([^"]+)"""").find(json)
                    val chapTitleMatch = Regex(""""chapTitle"\s*:\s*"([^"]+)"""").find(json)
                    val bodyMatch = Regex(""""body"\s*:\s*"([\s\S]*?)(?<!\\)"""").find(json)
                    val noteMatch = Regex(""""note"\s*:\s*"([\s\S]*?)(?<!\\)"""").find(json)
                    val novelHeadMatch = Regex(""""novelHead"\s*:\s*"([\s\S]*?)(?<!\\)"""").find(json)
                    val chapTag = chapTagMatch?.groupValues?.get(1) ?: ""
                    val chapTitle = chapTitleMatch?.groupValues?.get(1) ?: ""
                    val body = bodyMatch?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                    val note = noteMatch?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                    val novelHead = novelHeadMatch?.groupValues?.get(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                    val titleElement = "Chapter $chapTag"
                    chapterTitle = if (chapTitle.isNotEmpty()) "$titleElement - $chapTitle" else titleElement
                    chapterContent = listOf(novelHead, "<br><hr><br>", body, "<br><hr><br>Translator's Note:<br>", note)
                        .joinToString("").replace("\n", "<br>")
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "rainofsnow" -> {
                    val displayedDiv = doc.select(".bb-item").firstOrNull { el ->
                        el.attr("style").contains("display: block") || el.attr("style").contains("display:block")
                    }
                    val snowDoc = Jsoup.parse(displayedDiv?.html() ?: "")
                    listOf(".responsivevoice-button", ".zoomdesc-cont p img", ".zoomdesc-cont p noscript")
                        .forEach { snowDoc.select(it).remove() }
                    val titleElement2 = snowDoc.select(".scroller h2").first()
                    if (titleElement2 != null) {
                        chapterTitle = titleElement2.text()
                        titleElement2.remove()
                    }
                    chapterContent = snowDoc.select(".zoomdesc-cont").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "readingpia" -> {
                    listOf(".ezoic-ad", ".ezoic-adpicker-ad", ".ez-video-wrap").forEach { doc.select(it).remove() }
                    chapterText = doc.select(".chapter-body").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "redoxtranslation" -> {
                    val chapterId = chapterUrl.split("/").last()
                    chapterTitle = "Chapter $chapterId"
                    val txtUrl = "${chapterUrl.split("chapter")[0]}txt/$chapterId.txt"
                    val text = client.newCall(GET(txtUrl, headers)).execute().body.string()
                    chapterContent = text.split("\n").joinToString("<br>") { sentence ->
                        when {
                            sentence.contains("{break}") -> "<br> <p>****</p>"
                            else ->
                                sentence
                                    .replace(Regex("""\*\*(.*?)\*\*"""), "<strong>$1</strong>")
                                    .replace(Regex("""\+\+(.*?)\+\+"""), "<em>$1</em>")
                        }
                    }
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "sacredtexttranslations" -> {
                    listOf(".entry-content blockquote", ".entry-content div", ".reaction-buttons")
                        .forEach { doc.select(it).remove() }
                    chapterTitle = doc.select(".entry-title").first()?.text() ?: ""
                    chapterContent = doc.select(".entry-content").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "scribblehub" -> {
                    doc.select(".wi_authornotes").remove()
                    chapterTitle = doc.select(".chapter-title").first()?.text() ?: ""
                    chapterContent = doc.select(".chp_raw").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "skydemonorder" -> {
                    val ageCheck = doc.select("main").text().lowercase()
                    if (ageCheck.contains("age verification required")) {
                        throw Exception("Age verification required, please open in webview.")
                    }
                    chapterTitle = doc.select("header .font-medium.text-sm").first()?.text()?.trim() ?: ""
                    chapterContent = doc.select("#chapter-body").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "stabbingwithasyringe" -> {
                    val redirectUrl2 = doc.select(".entry-content a").first()?.attr("href") ?: ""
                    val targetDoc2 = if (redirectUrl2.isNotEmpty()) {
                        val resp = client.newCall(GET(redirectUrl2, headers)).execute()
                        Jsoup.parse(resp.body.string(), redirectUrl2)
                    } else {
                        doc
                    }
                    listOf(".has-inline-color", ".wp-block-buttons", ".wpcnt", "#jp-post-flair")
                        .forEach { targetDoc2.select(it).remove() }
                    val titleElement3 = targetDoc2.select(".entry-content h3").first()
                    if (titleElement3 != null) {
                        chapterTitle = titleElement3.text()
                        titleElement3.remove()
                    }
                    chapterContent = targetDoc2.select(".entry-content").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "tinytranslation" -> {
                    listOf(".content noscript", ".google_translate_element", ".navigate", ".post-views", "br")
                        .forEach { doc.select(it).remove() }
                    val titleEl = doc.select(".title-content").first()
                    chapterTitle = titleEl?.text() ?: ""
                    titleEl?.remove()
                    chapterContent = doc.select(".content").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "tumblr" -> {
                    chapterText = doc.select(".post").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "vampiramtl" -> {
                    val redirectUrl3 = doc.select(".entry-content a").first()?.attr("href") ?: ""
                    val targetDoc3 = if (redirectUrl3.isNotEmpty()) {
                        val resp = client.newCall(GET(chapterUrl + redirectUrl3, headers)).execute()
                        Jsoup.parse(resp.body.string(), chapterUrl + redirectUrl3)
                    } else {
                        doc
                    }
                    chapterTitle = targetDoc3.select(".entry-title").first()?.text() ?: ""
                    chapterContent = targetDoc3.select(".entry-content").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "wattpad" -> {
                    chapterTitle = doc.select(".h2").first()?.text() ?: ""
                    chapterContent = doc.select(".part-content pre").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "webnovel" -> {
                    chapterTitle = doc.select(".cha-tit .pr .dib").first()?.text() ?: ""
                    chapterContent = doc.select(".cha-words").html().ifEmpty {
                        doc.select("._content").html()
                    }
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "wetriedtls" -> {
                    val scriptContent = doc.select("script:containsData(p dir=)").html().ifEmpty {
                        doc.select("script:containsData(u003c)").html()
                    }
                    if (scriptContent.isNotEmpty()) {
                        val pushIdx = scriptContent.indexOf(".push(") + ".push(".length
                        val lastParen = scriptContent.lastIndexOf(")")
                        if (pushIdx in 1 until lastParen) {
                            // The second element of the pushed array is the HTML
                            val jsonStr = scriptContent.substring(pushIdx, lastParen)
                            val secondElemMatch = Regex("""^\[.*?,([\s\S]*)\]$""").find(jsonStr.trim())
                            chapterText = secondElemMatch?.groupValues?.get(1)?.trim()?.removeSurrounding("\"") ?: ""
                        }
                    }
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "wuxiaworld" -> {
                    doc.select(".MuiLink-root").remove()
                    chapterTitle = doc.select("h4 span").first()?.text() ?: ""
                    chapterContent = doc.select(".chapter-content").html()
                }

                // Last edited in version 3 by Batorian - 03/06/2026
                "yoru" -> {
                    val chapterId = chapterUrl.split("/").last()
                    val apiUrl = "https://pxp-main-531j.onrender.com/api/v1/book_chapters/$chapterId/content"
                    val jsonUrl = client.newCall(GET(apiUrl, headers)).execute().body.string().trim().removeSurrounding("\"")
                    chapterText = client.newCall(GET(jsonUrl, headers)).execute().body.string()
                }

                else -> {
                    // Generic fallback - try common selectors
                    val contentSelectors = listOf(
                        ".chapter-content",
                        ".entry-content",
                        ".post-content",
                        ".content",
                        "#content",
                        ".chapter__content",
                        ".text_story",
                        "article",
                    )
                    for (selector in contentSelectors) {
                        val content = doc.select(selector).html()
                        if (content.isNotEmpty() && content.length > 100) {
                            chapterContent = content
                            break
                        }
                    }
                    val titleSelectors = listOf(".chapter-title", ".entry-title", "h1", "h2", ".title")
                    for (selector in titleSelectors) {
                        val t = doc.select(selector).first()?.text()
                        if (!t.isNullOrEmpty()) {
                            chapterTitle = t
                            break
                        }
                    }
                }
            }
        } else {
            val config = if (isWordPress) platformConfig["wordpress"]!! else platformConfig["blogspot"]!!

            // Remove platform-specific bloat
            config.bloat.forEach { doc.select(it).remove() }

            // Extract Title
            var resolvedTitle = config.title
                .map { doc.select(it).first()?.text()?.trim() ?: "" }
                .firstOrNull { it.isNotEmpty() }

            // Handle Subtitles
            val chapterSubtitle = doc.select(".cat-series").first()?.text()
                ?: doc.select("h1.leading-none ~ span").first()?.text()
                ?: doc.select(".breadcrumb .active").first()?.text()
                ?: ""
            if (chapterSubtitle.isNotEmpty()) resolvedTitle = chapterSubtitle

            chapterTitle = resolvedTitle ?: ""

            // Extract Content
            chapterContent = config.content
                .mapNotNull { sel ->
                    val el = doc.select(sel).first()
                    el?.html()?.takeIf { el.text().trim().length > 50 }
                }
                .firstOrNull() ?: ""
        }

        if (chapterText.isEmpty()) {
            chapterText = if (chapterTitle.isNotEmpty()) {
                "<h2>$chapterTitle</h2><hr><br>$chapterContent"
            } else {
                chapterContent
            }
        }

        // Fallback content extraction
        if (chapterText.isEmpty()) {
            doc.select("nav, header, footer, .hidden").remove()
            chapterText = doc.select("body").html()
        }

        // Convert relative URLs to absolute
        val url = chapterUrl.toHttpUrl()
        val baseOrigin = "${url.scheme}://${url.host}${if (url.port != HttpUrl.defaultPort(url.scheme)) ":${url.port}" else ""}"

        chapterText = chapterText.replace("href=\"/", "href=\"$baseOrigin/")

        // Process images — replace lazy-load attributes and strip noscript
        val processedDoc = Jsoup.parse(chapterText)
        processedDoc.select("noscript").remove()
        processedDoc.select("img").forEach { el ->
            val lazySrc = el.attr("data-lazy-src")
            if (lazySrc.isNotEmpty()) el.attr("src", lazySrc)
            val lazySrcset = el.attr("data-lazy-srcset")
            if (lazySrcset.isNotEmpty()) el.attr("srcset", lazySrcset)
            if (el.hasClass("lazyloaded")) el.removeClass("lazyloaded")
        }

        return processedDoc.html()
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/series-ranking/?rank=popmonth&pg=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelsFromSearch(doc)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/series-finder/?sf=1&sort=sdate&order=desc&pg=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelsFromSearch(doc)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = if (query.isNotEmpty()) {
            // Split on '*' and use the longest segment, matching TS behaviour
            val longestTerm = query.split("*").maxByOrNull { it.length } ?: query
            val searchTerm = longestTerm.replace(Regex("[''']"), "'").replace(Regex("\\s+"), "+")
            "$baseUrl/series-finder/?sf=1&sh=$searchTerm&sort=srank&order=asc&pg=$page"
        } else {
            buildFilterUrl(page, filters)
        }
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())
        return parseNovelsFromSearch(doc)
    }

    private fun parseNovelsFromSearch(doc: Document): MangasPage {
        val novels = doc.select("div.search_main_box_nu").mapNotNull { element ->
            val titleElement = element.select(".search_title > a").first() ?: return@mapNotNull null
            val novelUrl = titleElement.attr("href")

            SManga.create().apply {
                title = titleElement.text()
                thumbnail_url = element.select("img").attr("src")
                url = novelUrl.removePrefix(baseUrl)
            }
        }

        val hasNextPage = doc.select(".digg_pagination a.next_page").isNotEmpty() ||
            doc.select(".pagination a:contains(Next)").isNotEmpty()

        return MangasPage(novels, hasNextPage)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = Jsoup.parse(response.body.string())

        runCatching {
            val mangaPath = cacheKey(response.request.url.encodedPath)
            val shortlink = doc.select("link[rel=shortlink]").attr("href")
            val novelId = Regex("\\?p=(\\d+)").find(shortlink)?.groupValues?.get(1)
                ?: doc.select("input#mypostid").attr("value").takeIf { it.isNotEmpty() }
            if (!novelId.isNullOrEmpty()) {
                persistNovelId(mangaPath, novelId)
            }
        }

        return SManga.create().apply {
            title = doc.select(".seriestitlenu").text().ifEmpty { "Untitled or invalid" }
            thumbnail_url = doc.select(".wpb_wrapper img").attr("src")

            author = doc.select("#authtag").joinToString(", ") { it.text().trim() }

            genre = doc.select("#seriesgenre a").joinToString(", ") { it.text() }

            status = when {
                doc.select("#editstatus").text().contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                doc.select("#editstatus").text().contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            val type = doc.select("#showtype").text().trim()
            val summary = doc.select("#editdescription").text().trim()

            val tags = doc.select("#showtags a.genre").joinToString(", ") { it.text() }

            // Append tags to genre
            if (tags.isNotEmpty()) {
                genre = if (genre.isNullOrEmpty()) tags else "$genre, $tags"
            }

            description = buildString {
                append(summary)
                if (type.isNotEmpty()) {
                    append("\n\nType: $type")
                }
                if (tags.isNotEmpty()) {
                    append("\n\nTags: $tags")
                }
            }
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = Jsoup.parse(response.body.string())

        val novelId = doc.select("input#mypostid").attr("value")
        if (novelId.isEmpty()) return emptyList()

        runCatching {
            persistNovelId(cacheKey(response.request.url.encodedPath), novelId)
        }

        val formBody = FormBody.Builder()
            .add("action", "nd_getchapters")
            .add("mygrr", "0")
            .add("mypostid", novelId)
            .build()

        val chaptersRequest = POST("$baseUrl/wp-admin/admin-ajax.php", headers, formBody)
        val chaptersResponse = client.newCall(chaptersRequest).execute()
        val chaptersHtml = chaptersResponse.body.string()

        val chaptersDoc = Jsoup.parse(chaptersHtml)

        return chaptersDoc.select("li.sp_li_chp").mapNotNull { element ->
            val chapterName = element.text()
                .replace("v", "volume ")
                .replace("c", " chapter ")
                .replace("part", "part ")
                .replace("ss", "SS")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                .trim()

            val chapterLink = element.select("a").first()?.nextElementSibling()?.attr("href")
                ?: return@mapNotNull null

            val fullUrl = if (chapterLink.startsWith("//")) {
                "https:$chapterLink"
            } else {
                chapterLink
            }

            SChapter.create().apply {
                name = chapterName
                url = fullUrl
                date_upload = 0L
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request = GET(chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString(), null))

    override fun imageUrlParse(response: Response) = ""

    override fun getFilterList() = FilterList(
        Filter.Header("NOTE: Filters are ignored if using text search!"),
        Filter.Separator(),
        SortFilter(),
        OrderFilter(),
        StatusFilter(),
        GenreFilter(),
        LanguageFilter(),
        NovelTypeFilter(),
        Filter.Header("Tags (ignored if sort=Latest Added)"),
        TagFilter(),
        TagOperatorFilter(),
        TagIncludeTextFilter(),
        TagExcludeTextFilter(),
        Filter.Header("Reading List (ignored if sort=Latest Added, -1=all, 0=reading, others=custom lists)"),
        ReadingListTextFilter(),
        ReadingListModeFilter(),
    )

    private fun buildFilterUrl(page: Int, filters: FilterList): String {
        val sortFilter = filters.findInstance<SortFilter>()!!
        val orderFilter = filters.findInstance<OrderFilter>()!!
        val statusFilter = filters.findInstance<StatusFilter>()!!
        val genreFilter = filters.findInstance<GenreFilter>()!!
        val languageFilter = filters.findInstance<LanguageFilter>()!!
        val novelTypeFilter = filters.findInstance<NovelTypeFilter>()!!
        val tagFilter = filters.findInstance<TagFilter>()!!
        val tagOperatorFilter = filters.findInstance<TagOperatorFilter>()!!
        val tagIncludeTextFilter = filters.findInstance<TagIncludeTextFilter>()!!
        val tagExcludeTextFilter = filters.findInstance<TagExcludeTextFilter>()!!
        val readingListTextFilter = filters.findInstance<ReadingListTextFilter>()!!
        val readingListModeFilter = filters.findInstance<ReadingListModeFilter>()!!

        val sortValue = sortFilter.toUriPart()
        val orderValue = orderFilter.toUriPart()

        return when {
            sortValue == "popmonth" || sortValue == "popular" -> {
                buildString {
                    append("$baseUrl/series-ranking/?rank=$sortValue")

                    // Series ranking supports genre, language, and status filters
                    val includedGenres = genreFilter.state.filter { it.isIncluded() }.map { it.id }
                    val excludedGenres = genreFilter.state.filter { it.isExcluded() }.map { it.id }
                    if (includedGenres.isNotEmpty()) {
                        append("&gi=").append(includedGenres.joinToString(","))
                    }
                    if (excludedGenres.isNotEmpty()) {
                        append("&ge=").append(excludedGenres.joinToString(","))
                    }

                    val selectedLanguages = languageFilter.state.filter { it.state }.map { it.id }
                    if (selectedLanguages.isNotEmpty()) {
                        append("&org=").append(selectedLanguages.joinToString(","))
                    }

                    if (statusFilter.state != 0) {
                        append("&ss=").append(statusFilter.toUriPart())
                    }

                    append("&pg=$page")
                }
            }

            sortValue == "latest" -> {
                buildString {
                    append("$baseUrl/latest-series/?st=1")

                    val includedGenres = genreFilter.state.filter { it.isIncluded() }.map { it.id }
                    val excludedGenres = genreFilter.state.filter { it.isExcluded() }.map { it.id }
                    if (includedGenres.isNotEmpty()) {
                        append("&gi=").append(includedGenres.joinToString(","))
                        append("&mgi=and")
                    }
                    if (excludedGenres.isNotEmpty()) {
                        append("&ge=").append(excludedGenres.joinToString(","))
                    }

                    val selectedLanguages = languageFilter.state.filter { it.state }.map { it.id }
                    if (selectedLanguages.isNotEmpty()) {
                        append("&org=").append(selectedLanguages.joinToString(","))
                    }

                    append("&pg=$page")
                }
            }

            else -> {
                buildString {
                    append("$baseUrl/series-finder/?sf=1")

                    val includedGenres = genreFilter.state.filter { it.isIncluded() }.map { it.id }
                    val excludedGenres = genreFilter.state.filter { it.isExcluded() }.map { it.id }
                    if (includedGenres.isNotEmpty()) {
                        append("&gi=").append(includedGenres.joinToString(","))
                        append("&mgi=and")
                    }
                    if (excludedGenres.isNotEmpty()) {
                        append("&ge=").append(excludedGenres.joinToString(","))
                    }

                    val selectedLanguages = languageFilter.state.filter { it.state }.map { it.id }
                    if (selectedLanguages.isNotEmpty()) {
                        append("&org=").append(selectedLanguages.joinToString(","))
                    }

                    val selectedNovelTypes = novelTypeFilter.state.filter { it.state }.map { it.id }
                    if (selectedNovelTypes.isNotEmpty()) {
                        append("&nt=").append(selectedNovelTypes.joinToString(","))
                    }

                    if (statusFilter.state != 0) {
                        append("&ss=").append(statusFilter.toUriPart())
                    }

                    val includedTags = tagFilter.state.filter { it.isIncluded() }.map { it.value }
                    val excludedTags = tagFilter.state.filter { it.isExcluded() }.map { it.value }

                    val tagMap = tagFilter.state.associate { it.name.lowercase() to it.value }
                    val includeTextTags = tagIncludeTextFilter.state.split(",")
                        .map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() }
                        .mapNotNull { tagMap[it] }
                    val excludeTextTags = tagExcludeTextFilter.state.split(",")
                        .map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() }
                        .mapNotNull { tagMap[it] }

                    val allIncludedTags = (includedTags + includeTextTags).distinct()
                    val allExcludedTags = (excludedTags + excludeTextTags).distinct()

                    if (allIncludedTags.isNotEmpty()) {
                        append("&tgi=").append(allIncludedTags.joinToString(","))
                        append("&mtgi=").append(tagOperatorFilter.toUriPart())
                    }
                    if (allExcludedTags.isNotEmpty()) {
                        append("&tge=").append(allExcludedTags.joinToString(","))
                    }

                    val readingListIds = readingListTextFilter.state.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    if (readingListIds.isNotEmpty()) {
                        append("&hd=").append(readingListIds.joinToString(","))
                        append("&mRLi=").append(readingListModeFilter.toUriPart())
                    }

                    append("&sort=$sortValue")
                    append("&order=$orderValue")
                    append("&pg=$page")
                }
            }
        }
    }

    private inline fun <reified T> Iterable<*>.findInstance() = find { it is T } as? T

    private class SortFilter :
        Filter.Select<String>(
            "Sort Results By",
            arrayOf(
                "Popular (Month)",
                "Popular (All)",
                "Latest Added",
                "Last Updated",
                "Rating",
                "Rank",
                "Reviews",
                "Chapters",
                "Title",
                "Readers",
                "Frequency",
            ),
        ) {
        fun toUriPart() = when (state) {
            0 -> "popmonth"
            1 -> "popular"
            2 -> "latest"
            3 -> "sdate"
            4 -> "srate"
            5 -> "srank"
            6 -> "sreview"
            7 -> "srel"
            8 -> "abc"
            9 -> "sread"
            10 -> "sfrel"
            else -> "popmonth"
        }
    }

    private class OrderFilter :
        Filter.Select<String>(
            "Order (Not for Popular)",
            arrayOf("Descending", "Ascending"),
        ) {
        fun toUriPart() = when (state) {
            0 -> "desc"
            1 -> "asc"
            else -> "desc"
        }
    }

    private class StatusFilter :
        Filter.Select<String>(
            "Story Status (Translation)",
            arrayOf("All", "Completed", "Ongoing", "Hiatus"),
        ) {
        fun toUriPart() = when (state) {
            1 -> "2"
            2 -> "3"
            3 -> "4"
            else -> ""
        }
    }

    private class Genre(name: String, val id: String) : Filter.TriState(name)

    private class GenreFilter :
        Filter.Group<Genre>(
            "Genres (0=ignore, 1=include, 2=exclude)",
            listOf(
                Genre("Action", "8"),
                Genre("Adult", "280"),
                Genre("Adventure", "13"),
                Genre("Comedy", "17"),
                Genre("Drama", "9"),
                Genre("Ecchi", "292"),
                Genre("Fantasy", "5"),
                Genre("Gender Bender", "168"),
                Genre("Harem", "3"),
                Genre("Historical", "330"),
                Genre("Horror", "343"),
                Genre("Josei", "324"),
                Genre("Martial Arts", "14"),
                Genre("Mature", "4"),
                Genre("Mecha", "10"),
                Genre("Mystery", "245"),
                Genre("Psychological", "486"),
                Genre("Romance", "15"),
                Genre("School Life", "6"),
                Genre("Sci-fi", "11"),
                Genre("Seinen", "18"),
                Genre("Shoujo", "157"),
                Genre("Shoujo Ai", "851"),
                Genre("Shounen", "12"),
                Genre("Shounen Ai", "1692"),
                Genre("Slice of Life", "7"),
                Genre("Smut", "281"),
                Genre("Sports", "1357"),
                Genre("Supernatural", "16"),
                Genre("Tragedy", "132"),
                Genre("Wuxia", "479"),
                Genre("Xianxia", "480"),
                Genre("Xuanhuan", "3954"),
                Genre("Yaoi", "560"),
                Genre("Yuri", "922"),
            ),
        )

    private class Language(name: String, val id: String) : Filter.CheckBox(name)

    private class LanguageFilter :
        Filter.Group<Language>(
            "Language",
            listOf(
                Language("Chinese", "495"),
                Language("Filipino", "9181"),
                Language("Indonesian", "9179"),
                Language("Japanese", "496"),
                Language("Khmer", "18657"),
                Language("Korean", "497"),
                Language("Malaysian", "9183"),
                Language("Thai", "9954"),
                Language("Vietnamese", "9177"),
            ),
        )

    private class NovelType(name: String, val id: String) : Filter.CheckBox(name)

    private class NovelTypeFilter :
        Filter.Group<NovelType>(
            "Novel Type (Not for Popular)",
            listOf(
                NovelType("Light Novel", "2443"),
                NovelType("Published Novel", "26874"),
                NovelType("Web Novel", "2444"),
            ),
        )

    private class ExcludableCheckBox(name: String, val value: String) : Filter.TriState(name)

    private class TagOperatorFilter :
        Filter.Select<String>(
            "Tags Operator",
            arrayOf("And", "Or"),
        ) {
        fun toUriPart() = when (state) {
            0 -> "and"
            1 -> "or"
            else -> "and"
        }
    }

    private class TagIncludeTextFilter : Filter.Text("Include Tags (comma-separated, e.g: academy, acting)")

    private class TagExcludeTextFilter : Filter.Text("Exclude Tags (comma-separated, e.g: harem, tragedy)")

    private class ReadingListTextFilter : Filter.Text("Reading List IDs (comma-separated, e.g: -1,0,3)")

    private class ReadingListModeFilter :
        Filter.Select<String>(
            "Reading List Mode",
            arrayOf("Include", "Exclude"),
        ) {
        fun toUriPart() = when (state) {
            0 -> "include"
            1 -> "exclude"
            else -> "include"
        }
    }
    private class TagFilter :
        Filter.Group<ExcludableCheckBox>(
            "Tags",
            listOf(
                ExcludableCheckBox("Abandoned Children", "0"),
                ExcludableCheckBox("Ability Steal", "1"),
                ExcludableCheckBox("Absent Parents", "2"),
                ExcludableCheckBox("Abusive Characters", "3"),
                ExcludableCheckBox("Academy", "4"),
                ExcludableCheckBox("Accelerated Growth", "5"),
                ExcludableCheckBox("Acting", "6"),
                ExcludableCheckBox("Adapted from Manga", "7"),
                ExcludableCheckBox("Adapted from Manhua", "8"),
                ExcludableCheckBox("Adapted to Anime", "9"),
                ExcludableCheckBox("Adapted to Drama", "10"),
                ExcludableCheckBox("Adapted to Drama CD", "11"),
                ExcludableCheckBox("Adapted to Game", "12"),
                ExcludableCheckBox("Adapted to Manga", "13"),
                ExcludableCheckBox("Adapted to Manhua", "14"),
                ExcludableCheckBox("Adapted to Manhwa", "15"),
                ExcludableCheckBox("Adapted to Movie", "16"),
                ExcludableCheckBox("Adapted to Visual Novel", "17"),
                ExcludableCheckBox("Adopted Children", "18"),
                ExcludableCheckBox("Adopted Protagonist", "19"),
                ExcludableCheckBox("Adultery", "20"),
                ExcludableCheckBox("Adventurers", "21"),
                ExcludableCheckBox("Affair", "22"),
                ExcludableCheckBox("Age Progression", "23"),
                ExcludableCheckBox("Age Regression", "24"),
                ExcludableCheckBox("Aggressive Characters", "25"),
                ExcludableCheckBox("Alchemy", "26"),
                ExcludableCheckBox("Aliens", "27"),
                ExcludableCheckBox("All-Girls School", "28"),
                ExcludableCheckBox("Alternate World", "29"),
                ExcludableCheckBox("Amnesia", "30"),
                ExcludableCheckBox("Amusement Park", "31"),
                ExcludableCheckBox("Anal", "32"),
                ExcludableCheckBox("Ancient China", "33"),
                ExcludableCheckBox("Ancient Times", "34"),
                ExcludableCheckBox("Androgynous Characters", "35"),
                ExcludableCheckBox("Androids", "36"),
                ExcludableCheckBox("Angels", "37"),
                ExcludableCheckBox("Animal Characteristics", "38"),
                ExcludableCheckBox("Animal Rearing", "39"),
                ExcludableCheckBox("Anti-Magic", "40"),
                ExcludableCheckBox("Anti-social Protagonist", "41"),
                ExcludableCheckBox("Antihero Protagonist", "42"),
                ExcludableCheckBox("Antique Shop", "43"),
                ExcludableCheckBox("Apartment Life", "44"),
                ExcludableCheckBox("Apathetic Protagonist", "45"),
                ExcludableCheckBox("Apocalypse", "46"),
                ExcludableCheckBox("Appearance Changes", "47"),
                ExcludableCheckBox("Appearance Different from Actual Age", "48"),
                ExcludableCheckBox("Archery", "49"),
                ExcludableCheckBox("Aristocracy", "50"),
                ExcludableCheckBox("Arms Dealers", "51"),
                ExcludableCheckBox("Army", "52"),
                ExcludableCheckBox("Army Building", "53"),
                ExcludableCheckBox("Arranged Marriage", "54"),
                ExcludableCheckBox("Arrogant Characters", "55"),
                ExcludableCheckBox("Artifact Crafting", "56"),
                ExcludableCheckBox("Artifacts", "57"),
                ExcludableCheckBox("Artificial Intelligence", "58"),
                ExcludableCheckBox("Artists", "59"),
                ExcludableCheckBox("Assassins", "60"),
                ExcludableCheckBox("Astrologers", "61"),
                ExcludableCheckBox("Autism", "62"),
                ExcludableCheckBox("Automatons", "63"),
                ExcludableCheckBox("Average-looking Protagonist", "64"),
                ExcludableCheckBox("Award-winning Work", "65"),
                ExcludableCheckBox("Awkward Protagonist", "66"),
                ExcludableCheckBox("Bands", "67"),
                ExcludableCheckBox("Based on a Movie", "68"),
                ExcludableCheckBox("Based on a Song", "69"),
                ExcludableCheckBox("Based on a TV Show", "70"),
                ExcludableCheckBox("Based on a Video Game", "71"),
                ExcludableCheckBox("Based on a Visual Novel", "72"),
                ExcludableCheckBox("Based on an Anime", "73"),
                ExcludableCheckBox("Battle Academy", "74"),
                ExcludableCheckBox("Battle Competition", "75"),
                ExcludableCheckBox("BDSM", "76"),
                ExcludableCheckBox("Beast Companions", "77"),
                ExcludableCheckBox("Beastkin", "78"),
                ExcludableCheckBox("Beasts", "79"),
                ExcludableCheckBox("Beautiful Female Lead", "80"),
                ExcludableCheckBox("Bestiality", "81"),
                ExcludableCheckBox("Betrayal", "82"),
                ExcludableCheckBox("Bickering Couple", "83"),
                ExcludableCheckBox("Biochip", "84"),
                ExcludableCheckBox("Bisexual Protagonist", "85"),
                ExcludableCheckBox("Black Belly", "86"),
                ExcludableCheckBox("Blackmail", "87"),
                ExcludableCheckBox("Blacksmith", "88"),
                ExcludableCheckBox("Blind Dates", "89"),
                ExcludableCheckBox("Blind Protagonist", "90"),
                ExcludableCheckBox("Blood Manipulation", "91"),
                ExcludableCheckBox("Bloodlines", "92"),
                ExcludableCheckBox("Body Swap", "93"),
                ExcludableCheckBox("Body Tempering", "94"),
                ExcludableCheckBox("Body-double", "95"),
                ExcludableCheckBox("Bodyguards", "96"),
                ExcludableCheckBox("Books", "97"),
                ExcludableCheckBox("Bookworm", "98"),
                ExcludableCheckBox("Boss-Subordinate Relationship", "99"),
                ExcludableCheckBox("Brainwashing", "100"),
                ExcludableCheckBox("Breast Fetish", "101"),
                ExcludableCheckBox("Broken Engagement", "102"),
                ExcludableCheckBox("Brother Complex", "103"),
                ExcludableCheckBox("Brotherhood", "104"),
                ExcludableCheckBox("Buddhism", "105"),
                ExcludableCheckBox("Bullying", "106"),
                ExcludableCheckBox("Business Management", "107"),
                ExcludableCheckBox("Businessmen", "108"),
                ExcludableCheckBox("Butlers", "109"),
                ExcludableCheckBox("Calm Protagonist", "110"),
                ExcludableCheckBox("Cannibalism", "111"),
                ExcludableCheckBox("Card Games", "112"),
                ExcludableCheckBox("Carefree Protagonist", "113"),
                ExcludableCheckBox("Caring Protagonist", "114"),
                ExcludableCheckBox("Cautious Protagonist", "115"),
                ExcludableCheckBox("Celebrities", "116"),
                ExcludableCheckBox("Character Growth", "117"),
                ExcludableCheckBox("Charismatic Protagonist", "118"),
                ExcludableCheckBox("Charming Protagonist", "119"),
                ExcludableCheckBox("Chat Rooms", "120"),
                ExcludableCheckBox("Cheats", "121"),
                ExcludableCheckBox("Chefs", "122"),
                ExcludableCheckBox("Child Abuse", "123"),
                ExcludableCheckBox("Child Protagonist", "124"),
                ExcludableCheckBox("Childcare", "125"),
                ExcludableCheckBox("Childhood Friends", "126"),
                ExcludableCheckBox("Childhood Love", "127"),
                ExcludableCheckBox("Childhood Promise", "128"),
                ExcludableCheckBox("Childish Protagonist", "129"),
                ExcludableCheckBox("Chuunibyou", "130"),
                ExcludableCheckBox("Clan Building", "131"),
                ExcludableCheckBox("Classic", "132"),
                ExcludableCheckBox("Clever Protagonist", "133"),
                ExcludableCheckBox("Clingy Lover", "134"),
                ExcludableCheckBox("Clones", "135"),
                ExcludableCheckBox("Clubs", "136"),
                ExcludableCheckBox("Clumsy Love Interests", "137"),
                ExcludableCheckBox("Co-Workers", "138"),
                ExcludableCheckBox("Cohabitation", "139"),
                ExcludableCheckBox("Cold Love Interests", "140"),
                ExcludableCheckBox("Cold Protagonist", "141"),
                ExcludableCheckBox("Collection of Short Stories", "142"),
                ExcludableCheckBox("College/University", "143"),
                ExcludableCheckBox("Coma", "144"),
                ExcludableCheckBox("Comedic Undertone", "145"),
                ExcludableCheckBox("Coming of Age", "146"),
                ExcludableCheckBox("Complex Family Relationships", "147"),
                ExcludableCheckBox("Conditional Power", "148"),
                ExcludableCheckBox("Confident Protagonist", "149"),
                ExcludableCheckBox("Confinement", "150"),
                ExcludableCheckBox("Conflicting Loyalties", "151"),
                ExcludableCheckBox("Contracts", "152"),
                ExcludableCheckBox("Cooking", "153"),
                ExcludableCheckBox("Corruption", "154"),
                ExcludableCheckBox("Cosmic Wars", "155"),
                ExcludableCheckBox("Cosplay", "156"),
                ExcludableCheckBox("Couple Growth", "157"),
                ExcludableCheckBox("Court Official", "158"),
                ExcludableCheckBox("Cousins", "159"),
                ExcludableCheckBox("Cowardly Protagonist", "160"),
                ExcludableCheckBox("Crafting", "161"),
                ExcludableCheckBox("Crime", "162"),
                ExcludableCheckBox("Criminals", "163"),
                ExcludableCheckBox("Cross-dressing", "164"),
                ExcludableCheckBox("Crossover", "165"),
                ExcludableCheckBox("Cruel Characters", "166"),
                ExcludableCheckBox("Cryostasis", "167"),
                ExcludableCheckBox("Cultivation", "168"),
                ExcludableCheckBox("Cunnilingus", "169"),
                ExcludableCheckBox("Cunning Protagonist", "170"),
                ExcludableCheckBox("Curious Protagonist", "171"),
                ExcludableCheckBox("Curses", "172"),
                ExcludableCheckBox("Cute Children", "173"),
                ExcludableCheckBox("Cute Protagonist", "174"),
                ExcludableCheckBox("Cute Story", "175"),
                ExcludableCheckBox("Dancers", "176"),
                ExcludableCheckBox("Dao Companion", "177"),
                ExcludableCheckBox("Dao Comprehension", "178"),
                ExcludableCheckBox("Daoism", "179"),
                ExcludableCheckBox("Dark", "180"),
                ExcludableCheckBox("Dead Protagonist", "181"),
                ExcludableCheckBox("Death", "182"),
                ExcludableCheckBox("Death of Loved Ones", "183"),
                ExcludableCheckBox("Debts", "184"),
                ExcludableCheckBox("Delinquents", "185"),
                ExcludableCheckBox("Delusions", "186"),
                ExcludableCheckBox("Demi-Humans", "187"),
                ExcludableCheckBox("Demon Lord", "188"),
                ExcludableCheckBox("Demonic Cultivation Technique", "189"),
                ExcludableCheckBox("Demons", "190"),
                ExcludableCheckBox("Dense Protagonist", "191"),
                ExcludableCheckBox("Depictions of Cruelty", "192"),
                ExcludableCheckBox("Depression", "193"),
                ExcludableCheckBox("Destiny", "194"),
                ExcludableCheckBox("Detectives", "195"),
                ExcludableCheckBox("Determined Protagonist", "196"),
                ExcludableCheckBox("Devoted Love Interests", "197"),
                ExcludableCheckBox("Different Social Status", "198"),
                ExcludableCheckBox("Disabilities", "199"),
                ExcludableCheckBox("Discrimination", "200"),
                ExcludableCheckBox("Disfigurement", "201"),
                ExcludableCheckBox("Dishonest Protagonist", "202"),
                ExcludableCheckBox("Distrustful Protagonist", "203"),
                ExcludableCheckBox("Divination", "204"),
                ExcludableCheckBox("Divine Protection", "205"),
                ExcludableCheckBox("Divorce", "206"),
                ExcludableCheckBox("Doctors", "207"),
                ExcludableCheckBox("Dolls/Puppets", "208"),
                ExcludableCheckBox("Domestic Affairs", "209"),
                ExcludableCheckBox("Doting Love Interests", "210"),
                ExcludableCheckBox("Doting Older Siblings", "211"),
                ExcludableCheckBox("Doting Parents", "212"),
                ExcludableCheckBox("Dragon Riders", "213"),
                ExcludableCheckBox("Dragon Slayers", "214"),
                ExcludableCheckBox("Dragons", "215"),
                ExcludableCheckBox("Dreams", "216"),
                ExcludableCheckBox("Drugs", "217"),
                ExcludableCheckBox("Druids", "218"),
                ExcludableCheckBox("Dungeon Master", "219"),
                ExcludableCheckBox("Dungeons", "220"),
                ExcludableCheckBox("Dwarfs", "221"),
                ExcludableCheckBox("Dystopia", "222"),
                ExcludableCheckBox("e-Sports", "223"),
                ExcludableCheckBox("Early Romance", "224"),
                ExcludableCheckBox("Earth Invasion", "225"),
                ExcludableCheckBox("Easy Going Life", "226"),
                ExcludableCheckBox("Economics", "227"),
                ExcludableCheckBox("Editors", "228"),
                ExcludableCheckBox("Eidetic Memory", "229"),
                ExcludableCheckBox("Elderly Protagonist", "230"),
                ExcludableCheckBox("Elemental Magic", "231"),
                ExcludableCheckBox("Elves", "232"),
                ExcludableCheckBox("Emotionally Weak Protagonist", "233"),
                ExcludableCheckBox("Empires", "234"),
                ExcludableCheckBox("Enemies Become Allies", "235"),
                ExcludableCheckBox("Enemies Become Lovers", "236"),
                ExcludableCheckBox("Engagement", "237"),
                ExcludableCheckBox("Engineer", "238"),
                ExcludableCheckBox("Enlightenment", "239"),
                ExcludableCheckBox("Episodic", "240"),
                ExcludableCheckBox("Eunuch", "241"),
                ExcludableCheckBox("European Ambience", "242"),
                ExcludableCheckBox("Evil Gods", "243"),
                ExcludableCheckBox("Evil Organizations", "244"),
                ExcludableCheckBox("Evil Protagonist", "245"),
                ExcludableCheckBox("Evil Religions", "246"),
                ExcludableCheckBox("Evolution", "247"),
                ExcludableCheckBox("Exhibitionism", "248"),
                ExcludableCheckBox("Exorcism", "249"),
                ExcludableCheckBox("Eye Powers", "250"),
                ExcludableCheckBox("Fairies", "251"),
                ExcludableCheckBox("Fallen Angels", "252"),
                ExcludableCheckBox("Fallen Nobility", "253"),
                ExcludableCheckBox("Familial Love", "254"),
                ExcludableCheckBox("Familiars", "255"),
                ExcludableCheckBox("Family", "256"),
                ExcludableCheckBox("Family Business", "257"),
                ExcludableCheckBox("Family Conflict", "258"),
                ExcludableCheckBox("Famous Parents", "259"),
                ExcludableCheckBox("Famous Protagonist", "260"),
                ExcludableCheckBox("Fanaticism", "261"),
                ExcludableCheckBox("Fanfiction", "262"),
                ExcludableCheckBox("Fantasy Creatures", "263"),
                ExcludableCheckBox("Fantasy World", "264"),
                ExcludableCheckBox("Farming", "265"),
                ExcludableCheckBox("Fast Cultivation", "266"),
                ExcludableCheckBox("Fast Learner", "267"),
                ExcludableCheckBox("Fat Protagonist", "268"),
                ExcludableCheckBox("Fat to Fit", "269"),
                ExcludableCheckBox("Fated Lovers", "270"),
                ExcludableCheckBox("Fearless Protagonist", "271"),
                ExcludableCheckBox("Fellatio", "272"),
                ExcludableCheckBox("Female Master", "273"),
                ExcludableCheckBox("Female Protagonist", "274"),
                ExcludableCheckBox("Female to Male", "275"),
                ExcludableCheckBox("Feng Shui", "276"),
                ExcludableCheckBox("Firearms", "277"),
                ExcludableCheckBox("First Love", "278"),
                ExcludableCheckBox("First-time Intercourse", "279"),
                ExcludableCheckBox("Flashbacks", "280"),
                ExcludableCheckBox("Fleet Battles", "281"),
                ExcludableCheckBox("Folklore", "282"),
                ExcludableCheckBox("Forced into a Relationship", "283"),
                ExcludableCheckBox("Forced Living Arrangements", "284"),
                ExcludableCheckBox("Forced Marriage", "285"),
                ExcludableCheckBox("Forgetful Protagonist", "286"),
                ExcludableCheckBox("Former Hero", "287"),
                ExcludableCheckBox("Found Family", "288"),
                ExcludableCheckBox("Fox Spirits", "289"),
                ExcludableCheckBox("Friends Become Enemies", "290"),
                ExcludableCheckBox("Friendship", "291"),
                ExcludableCheckBox("Fujoshi", "292"),
                ExcludableCheckBox("Futanari", "293"),
                ExcludableCheckBox("Futuristic Setting", "294"),
                ExcludableCheckBox("Galge", "295"),
                ExcludableCheckBox("Gambling", "296"),
                ExcludableCheckBox("Game Elements", "297"),
                ExcludableCheckBox("Game Ranking System", "298"),
                ExcludableCheckBox("Gamers", "299"),
                ExcludableCheckBox("Gangs", "300"),
                ExcludableCheckBox("Gate to Another World", "301"),
                ExcludableCheckBox("Genderless Protagonist", "302"),
                ExcludableCheckBox("Generals", "303"),
                ExcludableCheckBox("Genetic Modifications", "304"),
                ExcludableCheckBox("Genies", "305"),
                ExcludableCheckBox("Genius Protagonist", "306"),
                ExcludableCheckBox("Ghosts", "307"),
                ExcludableCheckBox("Gladiators", "308"),
                ExcludableCheckBox("Glasses-wearing Love Interests", "309"),
                ExcludableCheckBox("Glasses-wearing Protagonist", "310"),
                ExcludableCheckBox("Goblins", "311"),
                ExcludableCheckBox("God Protagonist", "312"),
                ExcludableCheckBox("God-human Relationship", "313"),
                ExcludableCheckBox("Goddesses", "314"),
                ExcludableCheckBox("Godly Powers", "315"),
                ExcludableCheckBox("Gods", "316"),
                ExcludableCheckBox("Golems", "317"),
                ExcludableCheckBox("Gore", "318"),
                ExcludableCheckBox("Grave Keepers", "319"),
                ExcludableCheckBox("Grinding", "320"),
                ExcludableCheckBox("Guardian Relationship", "321"),
                ExcludableCheckBox("Guideverse", "322"),
                ExcludableCheckBox("Guilds", "323"),
                ExcludableCheckBox("Gunfighters", "324"),
                ExcludableCheckBox("Hackers", "325"),
                ExcludableCheckBox("Half-human Protagonist", "326"),
                ExcludableCheckBox("Handjob", "327"),
                ExcludableCheckBox("Handsome Male Lead", "328"),
                ExcludableCheckBox("Hard-Working Protagonist", "329"),
                ExcludableCheckBox("Harem-seeking Protagonist", "330"),
                ExcludableCheckBox("Harsh Training", "331"),
                ExcludableCheckBox("Hated Protagonist", "332"),
                ExcludableCheckBox("Healers", "333"),
                ExcludableCheckBox("Heartwarming", "334"),
                ExcludableCheckBox("Heaven", "335"),
                ExcludableCheckBox("Heavenly Tribulation", "336"),
                ExcludableCheckBox("Hell", "337"),
                ExcludableCheckBox("Helpful Protagonist", "338"),
                ExcludableCheckBox("Herbalist", "339"),
                ExcludableCheckBox("Heroes", "340"),
                ExcludableCheckBox("Heterochromia", "341"),
                ExcludableCheckBox("Hidden Abilities", "342"),
                ExcludableCheckBox("Hiding True Abilities", "343"),
                ExcludableCheckBox("Hiding True Identity", "344"),
                ExcludableCheckBox("Hikikomori", "345"),
                ExcludableCheckBox("Homunculus", "346"),
                ExcludableCheckBox("Honest Protagonist", "347"),
                ExcludableCheckBox("Hospital", "348"),
                ExcludableCheckBox("Hot-blooded Protagonist", "349"),
                ExcludableCheckBox("Human Experimentation", "350"),
                ExcludableCheckBox("Human Weapon", "351"),
                ExcludableCheckBox("Human-Nonhuman Relationship", "352"),
                ExcludableCheckBox("Humanoid Protagonist", "353"),
                ExcludableCheckBox("Hunters", "354"),
                ExcludableCheckBox("Hypnotism", "355"),
                ExcludableCheckBox("Identity Crisis", "356"),
                ExcludableCheckBox("Imaginary Friend", "357"),
                ExcludableCheckBox("Immortals", "358"),
                ExcludableCheckBox("Imperial Harem", "359"),
                ExcludableCheckBox("Incest", "360"),
                ExcludableCheckBox("Incubus", "361"),
                ExcludableCheckBox("Indecisive Protagonist", "362"),
                ExcludableCheckBox("Industrialization", "363"),
                ExcludableCheckBox("Inferiority Complex", "364"),
                ExcludableCheckBox("Inheritance", "365"),
                ExcludableCheckBox("Inscriptions", "366"),
                ExcludableCheckBox("Insects", "367"),
                ExcludableCheckBox("Interconnected Storylines", "368"),
                ExcludableCheckBox("Interdimensional Travel", "369"),
                ExcludableCheckBox("Introverted Protagonist", "370"),
                ExcludableCheckBox("Investigations", "371"),
                ExcludableCheckBox("Invisibility", "372"),
                ExcludableCheckBox("Jack of All Trades", "373"),
                ExcludableCheckBox("Jealousy", "374"),
                ExcludableCheckBox("Jiangshi", "375"),
                ExcludableCheckBox("Jobless Class", "376"),
                ExcludableCheckBox("JSDF", "377"),
                ExcludableCheckBox("Kidnappings", "378"),
                ExcludableCheckBox("Kind Love Interests", "379"),
                ExcludableCheckBox("Kingdom Building", "380"),
                ExcludableCheckBox("Kingdoms", "381"),
                ExcludableCheckBox("Knights", "382"),
                ExcludableCheckBox("Kuudere", "383"),
                ExcludableCheckBox("Lack of Common Sense", "384"),
                ExcludableCheckBox("Language Barrier", "385"),
                ExcludableCheckBox("Late Romance", "386"),
                ExcludableCheckBox("Lawyers", "387"),
                ExcludableCheckBox("Lazy Protagonist", "388"),
                ExcludableCheckBox("Leadership", "389"),
                ExcludableCheckBox("Legends", "390"),
                ExcludableCheckBox("Level System", "391"),
                ExcludableCheckBox("Library", "392"),
                ExcludableCheckBox("Life Extension System", "393"),
                ExcludableCheckBox("Limited Lifespan", "394"),
                ExcludableCheckBox("Livestreaming", "395"),
                ExcludableCheckBox("Living Abroad", "396"),
                ExcludableCheckBox("Living Alone", "397"),
                ExcludableCheckBox("Loli", "398"),
                ExcludableCheckBox("Loneliness", "399"),
                ExcludableCheckBox("Loner Protagonist", "400"),
                ExcludableCheckBox("Long Separations", "401"),
                ExcludableCheckBox("Long-distance Relationship", "402"),
                ExcludableCheckBox("Lost Civilizations", "403"),
                ExcludableCheckBox("Lottery", "404"),
                ExcludableCheckBox("Love at First Sight", "405"),
                ExcludableCheckBox("Love Interest Falls in Love First", "406"),
                ExcludableCheckBox("Love Rivals", "407"),
                ExcludableCheckBox("Love Triangles", "408"),
                ExcludableCheckBox("Lovers Reunited", "409"),
                ExcludableCheckBox("Low-key Protagonist", "410"),
                ExcludableCheckBox("Loyal Subordinates", "411"),
                ExcludableCheckBox("Lucky Protagonist", "412"),
                ExcludableCheckBox("Magic", "413"),
                ExcludableCheckBox("Magic Beasts", "414"),
                ExcludableCheckBox("Magic Formations", "415"),
                ExcludableCheckBox("Magical Girls", "416"),
                ExcludableCheckBox("Magical Space", "417"),
                ExcludableCheckBox("Magical Technology", "418"),
                ExcludableCheckBox("Maids", "419"),
                ExcludableCheckBox("Male Protagonist", "420"),
                ExcludableCheckBox("Male to Female", "421"),
                ExcludableCheckBox("Male Yandere", "422"),
                ExcludableCheckBox("Management", "423"),
                ExcludableCheckBox("Mangaka", "424"),
                ExcludableCheckBox("Manipulative Characters", "425"),
                ExcludableCheckBox("Manly Gay Couple", "426"),
                ExcludableCheckBox("Marriage", "427"),
                ExcludableCheckBox("Marriage of Convenience", "428"),
                ExcludableCheckBox("Martial Spirits", "429"),
                ExcludableCheckBox("Masochistic Characters", "430"),
                ExcludableCheckBox("Master-Disciple Relationship", "431"),
                ExcludableCheckBox("Master-Servant Relationship", "432"),
                ExcludableCheckBox("Masturbation", "433"),
                ExcludableCheckBox("Matriarchy", "434"),
                ExcludableCheckBox("Mature Protagonist", "435"),
                ExcludableCheckBox("Medical Knowledge", "436"),
                ExcludableCheckBox("Medieval", "437"),
                ExcludableCheckBox("Mercenaries", "438"),
                ExcludableCheckBox("Merchants", "439"),
                ExcludableCheckBox("Military", "440"),
                ExcludableCheckBox("Mind Break", "441"),
                ExcludableCheckBox("Mind Control", "442"),
                ExcludableCheckBox("Misandry", "443"),
                ExcludableCheckBox("Mismatched Couple", "444"),
                ExcludableCheckBox("Mistaken Identity", "445"),
                ExcludableCheckBox("Misunderstandings", "446"),
                ExcludableCheckBox("MMORPG", "447"),
                ExcludableCheckBox("Mob Protagonist", "448"),
                ExcludableCheckBox("Models", "449"),
                ExcludableCheckBox("Modern Day", "450"),
                ExcludableCheckBox("Modern Knowledge", "451"),
                ExcludableCheckBox("Money Grubber", "452"),
                ExcludableCheckBox("Monster Girls", "453"),
                ExcludableCheckBox("Monster Society", "454"),
                ExcludableCheckBox("Monster Tamer", "455"),
                ExcludableCheckBox("Monsters", "456"),
                ExcludableCheckBox("Movies", "457"),
                ExcludableCheckBox("Mpreg", "458"),
                ExcludableCheckBox("Multiple Identities", "459"),
                ExcludableCheckBox("Multiple Personalities", "460"),
                ExcludableCheckBox("Multiple POV", "461"),
                ExcludableCheckBox("Multiple Protagonists", "462"),
                ExcludableCheckBox("Multiple Realms", "463"),
                ExcludableCheckBox("Multiple Reincarnated Individuals", "464"),
                ExcludableCheckBox("Multiple Timelines", "465"),
                ExcludableCheckBox("Multiple Transported Individuals", "466"),
                ExcludableCheckBox("Murders", "467"),
                ExcludableCheckBox("Music", "468"),
                ExcludableCheckBox("Mutated Creatures", "469"),
                ExcludableCheckBox("Mutations", "470"),
                ExcludableCheckBox("Mute Character", "471"),
                ExcludableCheckBox("Mysterious Family Background", "472"),
                ExcludableCheckBox("Mysterious Illness", "473"),
                ExcludableCheckBox("Mysterious Past", "474"),
                ExcludableCheckBox("Mystery Solving", "475"),
                ExcludableCheckBox("Mythical Beasts", "476"),
                ExcludableCheckBox("Mythology", "477"),
                ExcludableCheckBox("Naive Protagonist", "478"),
                ExcludableCheckBox("Narcissistic Protagonist", "479"),
                ExcludableCheckBox("Nationalism", "480"),
                ExcludableCheckBox("Near-Death Experience", "481"),
                ExcludableCheckBox("Necromancer", "482"),
                ExcludableCheckBox("Neet", "483"),
                ExcludableCheckBox("Netorare", "484"),
                ExcludableCheckBox("Netorase", "485"),
                ExcludableCheckBox("Netori", "486"),
                ExcludableCheckBox("Nightmares", "487"),
                ExcludableCheckBox("Ninjas", "488"),
                ExcludableCheckBox("Nobles", "489"),
                ExcludableCheckBox("Non-humanoid Protagonist", "490"),
                ExcludableCheckBox("Non-linear Storytelling", "491"),
                ExcludableCheckBox("Nudity", "492"),
                ExcludableCheckBox("Nurses", "493"),
                ExcludableCheckBox("Obsessive Love", "494"),
                ExcludableCheckBox("Office Romance", "495"),
                ExcludableCheckBox("Older Love Interests", "496"),
                ExcludableCheckBox("Omegaverse", "497"),
                ExcludableCheckBox("Oneshot", "498"),
                ExcludableCheckBox("Online Romance", "499"),
                ExcludableCheckBox("Onmyouji", "500"),
                ExcludableCheckBox("Orcs", "501"),
                ExcludableCheckBox("Organized Crime", "502"),
                ExcludableCheckBox("Orgy", "503"),
                ExcludableCheckBox("Orphans", "504"),
                ExcludableCheckBox("Otaku", "505"),
                ExcludableCheckBox("Otome Game", "506"),
                ExcludableCheckBox("Outcasts", "507"),
                ExcludableCheckBox("Outdoor Intercourse", "508"),
                ExcludableCheckBox("Outer Space", "509"),
                ExcludableCheckBox("Overpowered Protagonist", "510"),
                ExcludableCheckBox("Overprotective Siblings", "511"),
                ExcludableCheckBox("Pacifist Protagonist", "512"),
                ExcludableCheckBox("Paizuri", "513"),
                ExcludableCheckBox("Parallel Worlds", "514"),
                ExcludableCheckBox("Parasites", "515"),
                ExcludableCheckBox("Parent Complex", "516"),
                ExcludableCheckBox("Parody", "517"),
                ExcludableCheckBox("Part-Time Job", "518"),
                ExcludableCheckBox("Past Plays a Big Role", "519"),
                ExcludableCheckBox("Past Trauma", "520"),
                ExcludableCheckBox("Persistent Love Interests", "521"),
                ExcludableCheckBox("Personality Changes", "522"),
                ExcludableCheckBox("Perverted Protagonist", "523"),
                ExcludableCheckBox("Pets", "524"),
                ExcludableCheckBox("Pharmacist", "525"),
                ExcludableCheckBox("Philosophical", "526"),
                ExcludableCheckBox("Phobias", "527"),
                ExcludableCheckBox("Phoenixes", "528"),
                ExcludableCheckBox("Photography", "529"),
                ExcludableCheckBox("Pill Based Cultivation", "530"),
                ExcludableCheckBox("Pill Concocting", "531"),
                ExcludableCheckBox("Pilots", "532"),
                ExcludableCheckBox("Pirates", "533"),
                ExcludableCheckBox("Playboys", "534"),
                ExcludableCheckBox("Playful Protagonist", "535"),
                ExcludableCheckBox("Poetry", "536"),
                ExcludableCheckBox("Poisons", "537"),
                ExcludableCheckBox("Police", "538"),
                ExcludableCheckBox("Polite Protagonist", "539"),
                ExcludableCheckBox("Politics", "540"),
                ExcludableCheckBox("Polyandry", "541"),
                ExcludableCheckBox("Polygamy", "542"),
                ExcludableCheckBox("Poor Protagonist", "543"),
                ExcludableCheckBox("Poor to Rich", "544"),
                ExcludableCheckBox("Popular Love Interests", "545"),
                ExcludableCheckBox("Possession", "546"),
                ExcludableCheckBox("Possessive Characters", "547"),
                ExcludableCheckBox("Post-apocalyptic", "548"),
                ExcludableCheckBox("Power Couple", "549"),
                ExcludableCheckBox("Power Struggle", "550"),
                ExcludableCheckBox("Pragmatic Protagonist", "551"),
                ExcludableCheckBox("Precognition", "552"),
                ExcludableCheckBox("Pregnancy", "553"),
                ExcludableCheckBox("Pretend Lovers", "554"),
                ExcludableCheckBox("Previous Life Talent", "555"),
                ExcludableCheckBox("Priestesses", "556"),
                ExcludableCheckBox("Priests", "557"),
                ExcludableCheckBox("Prison", "558"),
                ExcludableCheckBox("Proactive Protagonist", "559"),
                ExcludableCheckBox("Programmer", "560"),
                ExcludableCheckBox("Prophecies", "561"),
                ExcludableCheckBox("Prostitutes", "562"),
                ExcludableCheckBox("Protagonist Falls in Love First", "563"),
                ExcludableCheckBox("Protagonist Strong from the Start", "564"),
                ExcludableCheckBox("Protagonist with Multiple Bodies", "565"),
                ExcludableCheckBox("Psychic Powers", "566"),
                ExcludableCheckBox("Psychopaths", "567"),
                ExcludableCheckBox("Puppeteers", "568"),
                ExcludableCheckBox("Quiet Characters", "569"),
                ExcludableCheckBox("Quirky Characters", "570"),
                ExcludableCheckBox("R-15", "571"),
                ExcludableCheckBox("R-18", "572"),
                ExcludableCheckBox("Race Change", "573"),
                ExcludableCheckBox("Racism", "574"),
                ExcludableCheckBox("Rape", "575"),
                ExcludableCheckBox("Rape Victim Becomes Lover", "576"),
                ExcludableCheckBox("Rebellion", "577"),
                ExcludableCheckBox("Reincarnated as a Monster", "578"),
                ExcludableCheckBox("Reincarnated as an Object", "579"),
                ExcludableCheckBox("Reincarnated in a Game World", "580"),
                ExcludableCheckBox("Reincarnated in Another World", "581"),
                ExcludableCheckBox("Reincarnation", "582"),
                ExcludableCheckBox("Religions", "583"),
                ExcludableCheckBox("Reluctant Protagonist", "584"),
                ExcludableCheckBox("Reporters", "585"),
                ExcludableCheckBox("Restaurant", "586"),
                ExcludableCheckBox("Resurrection", "587"),
                ExcludableCheckBox("Returning from Another World", "588"),
                ExcludableCheckBox("Revenge", "589"),
                ExcludableCheckBox("Reverse Harem", "590"),
                ExcludableCheckBox("Reverse Rape", "591"),
                ExcludableCheckBox("Reversible Couple", "592"),
                ExcludableCheckBox("Rich to Poor", "593"),
                ExcludableCheckBox("Righteous Protagonist", "594"),
                ExcludableCheckBox("Rivalry", "595"),
                ExcludableCheckBox("Romantic Subplot", "596"),
                ExcludableCheckBox("Roommates", "597"),
                ExcludableCheckBox("Royalty", "598"),
                ExcludableCheckBox("Ruthless Protagonist", "599"),
                ExcludableCheckBox("Sadistic Characters", "600"),
                ExcludableCheckBox("Saints", "601"),
                ExcludableCheckBox("Salaryman", "602"),
                ExcludableCheckBox("Samurai", "603"),
                ExcludableCheckBox("Saving the World", "604"),
                ExcludableCheckBox("Schemes And Conspiracies", "605"),
                ExcludableCheckBox("Schizophrenia", "606"),
                ExcludableCheckBox("Scientists", "607"),
                ExcludableCheckBox("Sculptors", "608"),
                ExcludableCheckBox("Sealed Power", "609"),
                ExcludableCheckBox("Second Chance", "610"),
                ExcludableCheckBox("Secret Crush", "611"),
                ExcludableCheckBox("Secret Identity", "612"),
                ExcludableCheckBox("Secret Organizations", "613"),
                ExcludableCheckBox("Secret Relationship", "614"),
                ExcludableCheckBox("Secretive Protagonist", "615"),
                ExcludableCheckBox("Secrets", "616"),
                ExcludableCheckBox("Sect Development", "617"),
                ExcludableCheckBox("Seduction", "618"),
                ExcludableCheckBox("Seeing Things Other Humans Can't", "619"),
                ExcludableCheckBox("Selfish Protagonist", "620"),
                ExcludableCheckBox("Selfless Protagonist", "621"),
                ExcludableCheckBox("Seme Protagonist", "622"),
                ExcludableCheckBox("Senpai-Kouhai Relationship", "623"),
                ExcludableCheckBox("Sentient Objects", "624"),
                ExcludableCheckBox("Sentimental Protagonist", "625"),
                ExcludableCheckBox("Serial Killers", "626"),
                ExcludableCheckBox("Servants", "627"),
                ExcludableCheckBox("Seven Deadly Sins", "628"),
                ExcludableCheckBox("Seven Virtues", "629"),
                ExcludableCheckBox("Sex Friends", "630"),
                ExcludableCheckBox("Sex Slaves", "631"),
                ExcludableCheckBox("Sexual Abuse", "632"),
                ExcludableCheckBox("Sexual Cultivation Technique", "633"),
                ExcludableCheckBox("Shameless Protagonist", "634"),
                ExcludableCheckBox("Shapeshifters", "635"),
                ExcludableCheckBox("Sharing A Body", "636"),
                ExcludableCheckBox("Sharp-tongued Characters", "637"),
                ExcludableCheckBox("Shield User", "638"),
                ExcludableCheckBox("Shikigami", "639"),
                ExcludableCheckBox("Short Story", "640"),
                ExcludableCheckBox("Shota", "641"),
                ExcludableCheckBox("Shoujo-Ai Subplot", "642"),
                ExcludableCheckBox("Shounen-Ai Subplot", "643"),
                ExcludableCheckBox("Showbiz", "644"),
                ExcludableCheckBox("Shy Characters", "645"),
                ExcludableCheckBox("Sibling Rivalry", "646"),
                ExcludableCheckBox("Sibling's Care", "647"),
                ExcludableCheckBox("Siblings", "648"),
                ExcludableCheckBox("Siblings Not Related by Blood", "649"),
                ExcludableCheckBox("Sickly Characters", "650"),
                ExcludableCheckBox("Sign Language", "651"),
                ExcludableCheckBox("Singers", "652"),
                ExcludableCheckBox("Single Parent", "653"),
                ExcludableCheckBox("Sister Complex", "654"),
                ExcludableCheckBox("Skill Assimilation", "655"),
                ExcludableCheckBox("Skill Books", "656"),
                ExcludableCheckBox("Skill Creation", "657"),
                ExcludableCheckBox("Slave Harem", "658"),
                ExcludableCheckBox("Slave Protagonist", "659"),
                ExcludableCheckBox("Slaves", "660"),
                ExcludableCheckBox("Sleeping", "661"),
                ExcludableCheckBox("Slow Growth at Start", "662"),
                ExcludableCheckBox("Slow Romance", "663"),
                ExcludableCheckBox("Smart Couple", "664"),
                ExcludableCheckBox("Social Outcasts", "665"),
                ExcludableCheckBox("Soldiers", "666"),
                ExcludableCheckBox("Soul Power", "667"),
                ExcludableCheckBox("Souls", "668"),
                ExcludableCheckBox("Spatial Manipulation", "669"),
                ExcludableCheckBox("Spear Wielder", "670"),
                ExcludableCheckBox("Special Abilities", "671"),
                ExcludableCheckBox("Spies", "672"),
                ExcludableCheckBox("Spirit Advisor", "673"),
                ExcludableCheckBox("Spirit Users", "674"),
                ExcludableCheckBox("Spirits", "675"),
                ExcludableCheckBox("Stalkers", "676"),
                ExcludableCheckBox("Stockholm Syndrome", "677"),
                ExcludableCheckBox("Stoic Characters", "678"),
                ExcludableCheckBox("Store Owner", "679"),
                ExcludableCheckBox("Straight Seme", "680"),
                ExcludableCheckBox("Straight Uke", "681"),
                ExcludableCheckBox("Strategic Battles", "682"),
                ExcludableCheckBox("Strategist", "683"),
                ExcludableCheckBox("Strength-based Social Hierarchy", "684"),
                ExcludableCheckBox("Strong Love Interests", "685"),
                ExcludableCheckBox("Strong to Stronger", "686"),
                ExcludableCheckBox("Stubborn Protagonist", "687"),
                ExcludableCheckBox("Student Council", "688"),
                ExcludableCheckBox("Student-Teacher Relationship", "689"),
                ExcludableCheckBox("Succubus", "690"),
                ExcludableCheckBox("Sudden Strength Gain", "691"),
                ExcludableCheckBox("Sudden Wealth", "692"),
                ExcludableCheckBox("Suicides", "693"),
                ExcludableCheckBox("Summoned Hero", "694"),
                ExcludableCheckBox("Summoning Magic", "695"),
                ExcludableCheckBox("Survival", "696"),
                ExcludableCheckBox("Survival Game", "697"),
                ExcludableCheckBox("Sword And Magic", "698"),
                ExcludableCheckBox("Sword Wielder", "699"),
                ExcludableCheckBox("System Administrator", "700"),
                ExcludableCheckBox("Teachers", "701"),
                ExcludableCheckBox("Teamwork", "702"),
                ExcludableCheckBox("Technological Gap", "703"),
                ExcludableCheckBox("Tentacles", "704"),
                ExcludableCheckBox("Terminal Illness", "705"),
                ExcludableCheckBox("Terrorists", "706"),
                ExcludableCheckBox("Thieves", "707"),
                ExcludableCheckBox("Threesome", "708"),
                ExcludableCheckBox("Thriller", "709"),
                ExcludableCheckBox("Time Loop", "710"),
                ExcludableCheckBox("Time Manipulation", "711"),
                ExcludableCheckBox("Time Paradox", "712"),
                ExcludableCheckBox("Time Skip", "713"),
                ExcludableCheckBox("Time Travel", "714"),
                ExcludableCheckBox("Timid Protagonist", "715"),
                ExcludableCheckBox("Tomboyish Female Lead", "716"),
                ExcludableCheckBox("Torture", "717"),
                ExcludableCheckBox("Toys", "718"),
                ExcludableCheckBox("Tragic Past", "719"),
                ExcludableCheckBox("Transformation Ability", "720"),
                ExcludableCheckBox("Transmigration", "721"),
                ExcludableCheckBox("Transplanted Memories", "722"),
                ExcludableCheckBox("Transported into a Game World", "723"),
                ExcludableCheckBox("Transported Modern Structure", "724"),
                ExcludableCheckBox("Transported to Another World", "725"),
                ExcludableCheckBox("Trap", "726"),
                ExcludableCheckBox("Tribal Society", "727"),
                ExcludableCheckBox("Trickster", "728"),
                ExcludableCheckBox("Tsundere", "729"),
                ExcludableCheckBox("Twins", "730"),
                ExcludableCheckBox("Twisted Personality", "731"),
                ExcludableCheckBox("Ugly Protagonist", "732"),
                ExcludableCheckBox("Ugly to Beautiful", "733"),
                ExcludableCheckBox("Unconditional Love", "734"),
                ExcludableCheckBox("Underestimated Protagonist", "735"),
                ExcludableCheckBox("Unique Cultivation Technique", "736"),
                ExcludableCheckBox("Unique Weapon User", "737"),
                ExcludableCheckBox("Unique Weapons", "738"),
                ExcludableCheckBox("Unlimited Flow", "739"),
                ExcludableCheckBox("Unlucky Protagonist", "740"),
                ExcludableCheckBox("Unreliable Narrator", "741"),
                ExcludableCheckBox("Unrequited Love", "742"),
                ExcludableCheckBox("Valkyries", "743"),
                ExcludableCheckBox("Vampires", "744"),
                ExcludableCheckBox("Villainess Noble Girls", "745"),
                ExcludableCheckBox("Virtual Reality", "746"),
                ExcludableCheckBox("Vocaloid", "747"),
                ExcludableCheckBox("Voice Actors", "748"),
                ExcludableCheckBox("Voyeurism", "749"),
                ExcludableCheckBox("Waiters", "750"),
                ExcludableCheckBox("War Records", "751"),
                ExcludableCheckBox("Wars", "752"),
                ExcludableCheckBox("Weak Protagonist", "753"),
                ExcludableCheckBox("Weak to Strong", "754"),
                ExcludableCheckBox("Wealthy Characters", "755"),
                ExcludableCheckBox("Werebeasts", "756"),
                ExcludableCheckBox("Wishes", "757"),
                ExcludableCheckBox("Witches", "758"),
                ExcludableCheckBox("Wizards", "759"),
                ExcludableCheckBox("World Hopping", "760"),
                ExcludableCheckBox("World Travel", "761"),
                ExcludableCheckBox("World Tree", "762"),
                ExcludableCheckBox("Writers", "763"),
                ExcludableCheckBox("Yandere", "764"),
                ExcludableCheckBox("Youkai", "765"),
                ExcludableCheckBox("Younger Brothers", "766"),
                ExcludableCheckBox("Younger Love Interests", "767"),
                ExcludableCheckBox("Younger Sisters", "768"),
                ExcludableCheckBox("Zombies", "769"),
            ),
        )
}

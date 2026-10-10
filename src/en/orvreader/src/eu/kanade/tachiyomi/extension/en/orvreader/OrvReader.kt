package eu.kanade.tachiyomi.novelextension.en.orvreader

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
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Source for https://orv.pages.dev.
 *
 * The reader strips `<style>` tags but honors inline `style=""` attributes, so the site's
 * two stylesheets are fetched, parsed into rules, and merged into each element's inline
 * style. Theme classes are force-overridden to the site's own defaults, matching what
 * `reader.js` does at runtime — the raw HTML often carries stale `themeN` classes that
 * would otherwise win over the current default.
 */
@Source
abstract class OrvReader :
    KeiSource(),
    NovelSource {

    override val supportsLatest = false

    // --- Popular ---------------------------------------------------------------------------

    override suspend fun getPopularManga(page: Int): MangasPage {
        // Only a single page of results — the site has exactly three stories.
        if (page > 1) return MangasPage(emptyList(), false)

        val doc = client.get("$baseUrl/stories/").asJsoup()
        val mangas = doc.select("a.card").mapNotNull { card ->
            val slug = card.absUrl("href")
                .trimEnd('/')
                .substringAfterLast('/')
                .takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val title = card.selectFirst("p")
                ?.text()
                .orEmpty()
                .ifBlank { slug }

            SManga.create().apply {
                url = slug
                this.title = title
                thumbnail_url = card.selectFirst("img")
                    ?.absUrl("src")
                    ?.takeIf { it.isNotBlank() }
            }
        }

        return MangasPage(mangas, hasNextPage = false)
    }

    // --- Unsupported endpoints -------------------------------------------------------------

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException("Latest is not supported for $name")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = throw UnsupportedOperationException("Search is not supported for $name")

    // --- URL builders ----------------------------------------------------------------------

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/stories/${manga.url}/"

    override fun getChapterUrl(chapter: SChapter): String {
        val (slug, chapterId) = splitChapterUrl(chapter.url) ?: return "$baseUrl/stories/"
        return "$baseUrl/stories/$slug/read/$chapterId"
    }

    // --- Details + chapter list ------------------------------------------------------------

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        if (!fetchDetails && !fetchChapters) {
            return@coroutineScope SMangaUpdate(manga, chapters)
        }

        // Details live in the ToC page HTML; the chapter list lives in a separate JSON.
        // Fire both concurrently — the JSON fetch does not depend on the HTML.
        val detailsDeferred = async {
            client.get("$baseUrl/stories/${manga.url}/").asJsoup()
        }
        val chaptersDeferred = if (fetchChapters) {
            async {
                client.get("$baseUrl/meta/${manga.url}.json").parseAs<List<ChapterEntry>>()
            }
        } else {
            null
        }

        val doc = detailsDeferred.await()

        SMangaUpdate(
            manga = if (fetchDetails) parseMangaDetails(doc, manga) else manga,
            chapters = if (fetchChapters) {
                parseChapterList(doc, chaptersDeferred!!.await(), manga.url)
            } else {
                chapters
            },
        )
    }

    private fun parseMangaDetails(doc: Document, manga: SManga): SManga = manga.apply {
        doc.selectFirst("div.cover img")
            ?.absUrl("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { thumbnail_url = it }

        doc.selectFirst("div.status p")
            ?.text()
            ?.takeIf { it.isNotBlank() }
            ?.let { title = it }

        // Second <p> in div.status packs "Author: … <br> Chapters: … <br> Status: …".
        val metaText = doc.select("div.status p").getOrNull(1)?.text().orEmpty()

        META_AUTHOR.find(metaText)
            ?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { author = it }

        META_STATUS.find(metaText)
            ?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { raw ->
                status = when (raw.lowercase()) {
                    "ongoing", "on-going" -> SManga.ONGOING
                    "completed" -> SManga.COMPLETED
                    "hiatus" -> SManga.ON_HIATUS
                    "cancelled", "canceled" -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
            }

        doc.selectFirst("div.synopsis p")
            ?.text()
            ?.takeIf { it.isNotBlank() }
            ?.let { description = it }
    }

    /**
     * Returns chapters in descending order (Ch 551 → Ch 1), which is what the app expects.
     * The JSON is ascending, so the mapped list is reversed at the end.
     */
    private fun parseChapterList(
        detailsDoc: Document,
        entries: List<ChapterEntry>,
        slug: String,
    ): List<SChapter> {
        if (entries.isEmpty()) return emptyList()

        val start = parseStartChapterNumber(detailsDoc, entries)
            ?: throw IllegalStateException("Could not determine first chapter number for $slug")

        return entries.mapIndexed { position, entry ->
            val number = start + position
            SChapter.create().apply {
                url = "$slug/ch_$number"
                name = entry.title.ifBlank { "Chapter $number" }
                chapter_number = number.toFloat()
            }
        }.asReversed()
    }

    /**
     * The first chapter number anchors the sequence:
     * - Primary source: the "Read" button's `href` on the ToC page (`./read/ch_N`).
     * - Fallback: the leading `Ch N` from the first JSON entry's title.
     *
     * ORV's ToC gives `ch_1` and Sequel's gives `ch_553`, so this cannot be hardcoded to 1.
     */
    private fun parseStartChapterNumber(doc: Document, entries: List<ChapterEntry>): Int? {
        doc.selectFirst("a#read-a")
            ?.attr("href")
            ?.let { href ->
                CHAPTER_NUM.find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            }

        entries.firstOrNull()
            ?.title
            ?.let { title ->
                TITLE_NUM.find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            }

        return null
    }

    // --- Chapter content -------------------------------------------------------------------

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val (slug, chapterId) = splitChapterUrl(page.url)
            ?: throw IllegalArgumentException("Invalid chapter url: ${page.url}")

        // No trailing slash: the site serves ch_N.html as a "file", and a trailing slash
        // would resolve `../../../assets/...` one directory too deep, breaking every image.
        val chapterUrl = "$baseUrl/stories/$slug/read/$chapterId"

        val (css, doc) = coroutineScope {
            val cssDeferred = async { fetchSiteCss() }
            val docDeferred = async { client.get(chapterUrl).asJsoup() }
            cssDeferred.await() to docDeferred.await()
        }

        // The chapter body lives in `article.orv_main`, but the cover and mobile-title
        // images are siblings inside `div.main`. Select the whole subtree, then remove
        // navigation, ads, and comments.
        val main = doc.selectFirst("div.main") ?: return ""
        main.select(".change-ch, .evadav, #chapter-banner, #comments, .giscus").remove()

        // Relative URLs would break in the reader's WebView, which has no base URL context.
        val base = chapterUrl.toHttpUrl()
        main.select("[src]").forEach { el ->
            el.attr("src", base.resolve(el.attr("src"))?.toString() ?: el.attr("src"))
        }
        main.select("[href]").forEach { el ->
            el.attr("href", base.resolve(el.attr("href"))?.toString() ?: el.attr("href"))
        }

        applyThemeClasses(main)

        val (variables, rules) = parseCss(css)
        applyInlineStyles(main, rules)

        // The wrapper carries the `:root` variables and any `body { … }` defaults. Inline
        // custom properties inherit, so `var(--primary)` etc. resolve throughout.
        val bodyDefaults = rules.filter { it.selector == "body" }.joinToString("; ") { it.declarations }
        val wrapperStyle = buildString {
            if (bodyDefaults.isNotEmpty()) append(bodyDefaults).append("; ")
            append(variables)
        }.trim().trimEnd(';')

        return "<div style=\"$wrapperStyle\">${main.outerHtml()}</div>"
    }

    private suspend fun fetchSiteCss(): String = coroutineScope {
        val readerCss = async {
            runCatching { client.get("$baseUrl/assets/reader.css").body.string() }.getOrDefault("")
        }
        val richCss = async {
            runCatching { client.get("$baseUrl/assets/reader-rich-text.css").body.string() }.getOrDefault("")
        }
        readerCss.await() + "\n" + richCss.await()
    }

    /**
     * Mirrors the site's `classChangeTheme` — clears existing `themeN` classes and adds
     * the site's default for that base class. The raw HTML often carries a stale theme
     * class from build time, which would otherwise win over the current default.
     */
    private fun applyThemeClasses(root: Element) {
        for ((baseClass, themeClass) in THEME_DEFAULTS) {
            root.select(".$baseClass").forEach { el ->
                val current = el.classNames().filter { it.startsWith("theme") }
                if (current.size == 1 && current.first() == themeClass) return@forEach
                current.forEach { el.removeClass(it) }
                el.addClass(themeClass)
            }
        }
    }

    private fun applyInlineStyles(root: Element, rules: List<CssRule>) {
        val elements = ArrayList<Element>(1 + root.select("*").size).apply {
            add(root)
            addAll(root.select("*"))
        }

        for (element in elements) {
            val matched = rules.filter { elementMatches(element, it.selector) }
            if (matched.isEmpty()) continue

            val newDeclarations = matched.joinToString("; ") { markImportant(it.declarations) }
            val existing = element.attr("style").trim().trimEnd(';')
            element.attr(
                "style",
                if (existing.isEmpty()) newDeclarations else "$existing; $newDeclarations",
            )
        }
    }

    private fun elementMatches(element: Element, selectorGroup: String): Boolean = selectorGroup.split(",").any { raw ->
        val selector = raw.trim()
        when {
            selector.isEmpty() -> false
            selector == "*" -> true
            selector == "body" -> false // applied to the wrapper instead
            else -> try {
                element.`is`(selector)
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Appends `!important` to each declaration. Inline `!important` is the highest-
     * priority author declaration, which is required here because the reader's own base
     * stylesheet overrides plain inline styles. `font-family` and custom properties are
     * excluded — the first so the user's reader font survives, the second because a
     * declaration like `--x: 1` cannot carry `!important`.
     */
    private fun markImportant(declarations: String): String = declarations.split(";")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("; ") { decl ->
            val prop = decl.substringBefore(":").trim().lowercase()
            when {
                prop == "font-family" -> decl
                prop.startsWith("--") -> decl
                decl.endsWith("!important", ignoreCase = true) -> decl
                else -> "$decl !important"
            }
        }

    /**
     * Parses a stylesheet into (`:root` variables, list of [CssRule]).
     *
     * Jsoup has no CSS parser, and the site's stylesheets are flat rules with no nesting,
     * so a small regex-based extraction is sufficient. `@media` blocks are stripped first
     * (see [stripMediaQueries]); pseudo-elements and interactive pseudo-classes cannot be
     * expressed as inline styles and are dropped.
     */
    private fun parseCss(css: String): Pair<String, List<CssRule>> {
        val noComments = css.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        val cleaned = stripMediaQueries(noComments)

        val variables = Regex(""":root\s*\{([^}]*)\}""").find(cleaned)
            ?.groupValues?.get(1)
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.trimEnd(';')
            .orEmpty()

        val ruleRegex = Regex("""([^{}@][^{}]*?)\s*\{([^{}]*)\}""")
        val rules = ruleRegex.findAll(cleaned).mapNotNull { match ->
            val selector = match.groupValues[1].trim()
            val declarations = match.groupValues[2].trim().replace(Regex("\\s+"), " ")
            when {
                selector.isEmpty() || declarations.isEmpty() -> null
                selector == ":root" -> null
                selector.contains("::") -> null
                selector.contains(":hover") -> null
                selector.contains(":active") -> null
                selector.contains(":focus") -> null
                selector.contains(":checked") -> null
                selector.contains(":disabled") -> null
                selector.startsWith("@") -> null
                else -> CssRule(selector, declarations)
            }
        }.toList()

        return variables to rules
    }

    /**
     * Removes `@media` (and any other brace-scoped at-rule) blocks. Their inner rules are
     * otherwise extracted and applied unconditionally — `reader.css` ends with an
     * `@media print` block that resets `.orv_main { margin: 0; padding: 0 }`, which
     * without this would override the correct `margin: 0 auto`.
     */
    private fun stripMediaQueries(css: String): String {
        val sb = StringBuilder(css.length)
        var i = 0
        while (i < css.length) {
            val mediaStart = css.indexOf("@media", i)
            if (mediaStart == -1) {
                sb.append(css, i, css.length)
                break
            }
            sb.append(css, i, mediaStart)

            var j = mediaStart
            while (j < css.length && css[j] != '{') j++
            if (j >= css.length) break

            var depth = 1
            j++
            while (j < css.length && depth > 0) {
                when (css[j]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                j++
            }
            i = j
        }
        return sb.toString()
    }

    /** Splits "orv/ch_1" → ("orv", "ch_1"). */
    private fun splitChapterUrl(url: String): Pair<String, String>? {
        val parts = url.split('/', limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return parts[0] to parts[1]
    }

    /**
     * One entry from `/meta/<slug>.json`. Only `title` is consumed — `index` and
     * `discussion` are ignored because position within the array already encodes ordering
     * and chapter numbers come from [parseStartChapterNumber].
     */
    @Serializable
    class ChapterEntry(val title: String)

    private class CssRule(val selector: String, val declarations: String)

    companion object {
        /** Base class → theme class. Values mirror the site's own default form selections. */
        private val THEME_DEFAULTS = mapOf(
            "orv_system" to "theme5",
            "orv_window" to "theme3",
            "orv_constellation" to "theme2",
            "orv_outergod" to "theme2",
            "orv_quote" to "theme2",
            "orv_notice" to "theme2",
            "orv_box" to "theme2",
        )

        private val META_AUTHOR = Regex("""Author:\s*(.+?)(?:\s+Chapters:|\s+Status:|$)""")
        private val META_STATUS = Regex("""Status:\s*(.+?)$""")
        private val CHAPTER_NUM = Regex("""ch_(\d+)""")
        private val TITLE_NUM = Regex("""^Ch\s+(\d+)""")
    }
}

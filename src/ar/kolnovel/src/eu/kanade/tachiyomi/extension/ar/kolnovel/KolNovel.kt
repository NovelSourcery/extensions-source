package eu.kanade.tachiyomi.novelextension.ar.kolnovel

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.SlugPath
import keiyoushi.utils.formattedText
import keiyoushi.utils.setAltTitles
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Kol Novel (kolnovel.com) was rebuilt on a custom Starless theme.
 *
 * Chapter lists are no longer in the legacy `.eplister` structure the LightNovelWP base expects,
 * so chapters never appeared ("الفصول لاتظهر"). The novel page now lists chapters in
 * `li.kol-series-chapter` rows, paginated with `?chapter_volume=__kol_all__&chapter_page=N`,
 * and chapter bodies live in `#kol_content.epcontent.entry-content` (still parsed by [fetchPageText]).
 */
@Source
abstract class KolNovel : LightNovelWPNovel() {
    override val reverseChapters = true

    // Novel pages live directly under "/series/<slug>" on this site (the root "/<slug>" path 404s),
    // but SlugPath("/") still stores "/series/<slug>" unchanged since removePrefix("/") only strips
    // the single leading slash, so existing slugs resolve correctly.
    override val mangaPathTemplate = SlugPath("/")

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val pageUrl = mangaPathTemplate.absolute(baseUrl, manga.url)
        val response: Response = client.newCall(GET(pageUrl, headers)).execute()
        val doc = response.asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(doc) else manga
        val updatedChapters = if (fetchChapters) parseAllChapters(doc, pageUrl) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    /**
     * Parses the chapter list from the novel page, walking every page of the `__kol_all__` volume
     * so all chapters are returned, not just the first page.
     */
    private fun parseAllChapters(doc: Document, pageUrl: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()

        doc.select("li.kol-series-chapter").forEach { element ->
            parseChapterRow(element, baseUrl)?.let { chapters.add(it) }
        }

        val totalPages = doc
            .selectFirst("details[data-kol-chapter-volume='__kol_all__']")
            ?.attr("data-kol-volume-pages")
            ?.toIntOrNull()
            ?: 1

        for (page in 2..totalPages) {
            // pageUrl (the novel detail page) has no existing query, so appending is safe.
            val url = "$pageUrl?chapter_volume=__kol_all__&chapter_page=$page"

            val pageResponse = client.newCall(GET(url, headers)).execute()
            val pageDoc = pageResponse.asJsoup()
            pageDoc.select("li.kol-series-chapter").forEach { element ->
                parseChapterRow(element, baseUrl)?.let { chapters.add(it) }
            }
        }

        return if (reverseChapters) chapters.reversed() else chapters
    }

    private fun parseChapterRow(element: Element, baseUrl: String): SChapter? {
        val link = element.selectFirst("a.kol-series-chapter__link") ?: return null
        val url = link.attr("href").removePrefix(baseUrl)
        if (url.isBlank()) return null

        val numberStr = element.selectFirst("[data-kol-chapter-number]")?.attr("data-kol-chapter-number") ?: ""
        val title = element.selectFirst("[data-kol-chapter-title]")?.text()?.trim() ?: ""
        val dateStr = element.selectFirst("time[data-kol-chapter-date]")?.attr("datetime") ?: ""

        val name = if (title.isNotBlank()) title else "Chapter $numberStr"
        val chapterNumber = numberStr.toFloatOrNull()

        return SChapter.create().apply {
            this.url = url
            this.name = name
            date_upload = parseKolDate(dateStr)
            if (chapterNumber != null) {
                chapter_number = chapterNumber
            }
        }
    }

    private fun parseKolDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX", // 2026-11-08T22:32:55+02:00
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
            "dd MMMM yyyy",
            "dd/MM/yyyy",
            "MM/dd/yyyy",
        )
        for (format in formats) {
            try {
                val time = SimpleDateFormat(format, Locale.US).parse(dateStr)?.time
                if (time != null) return time
            } catch (_: Exception) {
                // Try the next format.
            }
        }
        return 0L
    }

    override fun parseMangaDetails(doc: Document): SManga = SManga.create().apply {
        title = doc.selectFirst(".kol-series-editorial__title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.substringBefore("–")
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown Title"

        val altTitle = doc.selectFirst(".kol-series-editorial__alt-title")?.text()?.trim()
        if (altTitle?.isNotBlank() == true) {
            setAltTitles(listOfNotNull(altTitle))
        }

        author = doc.selectFirst(".kol-series-editorial__byline a")?.text()?.trim() ?: ""

        genre = doc.select(".kol-series-editorial__genres a").joinToString { it.text().trim() }

        description = doc.selectFirst(
            ".kol-series-editorial__prose[itemprop=description], .kol-series-editorial__synopsis .entry-content, [itemprop=description]",
        )?.formattedText()?.trim() ?: ""

        val statusText = doc
            .select(".kol-series-editorial__quick-facts .kol-series-editorial__quick-fact--status dd")
            .firstOrNull()?.text()?.trim()
        status = when (statusText) {
            "مستمرة" -> SManga.ONGOING
            "مكتملة" -> SManga.COMPLETED
            "متوقفة", "موقوفة" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        val cover = doc.selectFirst(".kol-series-editorial__cover img, .kol-series-editorial__cover")
        thumbnail_url = parseImageUrl(cover)
    }

    override suspend fun fetchPageText(page: Page): String {
        val response = client.get(baseUrl + page.url, headers)
        val doc = response.asJsoup()

        // Remove code blocks
        doc.select(".epcontent .code-block").remove()

        // Extract CSS class names from article > style and remove matching paragraphs
        val styleText = doc.select("article > style").text()
        val classPattern = Regex("""\.\w+(?=\s*[,{])""")
        classPattern.findAll(styleText).forEach { match ->
            val selector = "p${match.value}"
            doc.select(selector).remove()
        }

        // Remove navigation, ads, and non-content elements
        doc.select(
            ".unlock-buttons, .ads, script, style, .sharedaddy, .su-spoiler-title, " +
                "noscript, ins, .adsbygoogle, iframe, [id*=google], [class*=google], " +
                ".chapter-navigation, .prev-next, .navigation, .post-nav, " +
                ".related-novels, .recommendations, .sidebar, .widget, " +
                ".author-info, .author-box, .post-author, " +
                ".comments, .comment-section, #comments, " +
                ".footer, .post-footer, .entry-footer, " +
                ".breadcrumb, .breadcrumbs, .post-categories, .post-tags, " +
                ".share-buttons, .social-share, .sharethis, " +
                ".rating, .post-rating, .star-rating, " +
                ".chapter-actions, .download-chapter",
        ).remove()

        val content = doc.select(".epcontent.entry-content").maxByOrNull {
            it.select("p").sumOf { p -> p.text().length }
        } ?: return ""

        // Remove paragraphs that are too short (likely spam/ads)
        content.select("p").forEach { p ->
            val text = p.text()

            // Skip empty paragraphs
            if (text.isEmpty()) {
                p.remove()
                return@forEach
            }

            // Remove very short paragraphs (likely spam labels)
            if (text.length < 10) {
                p.remove()
                return@forEach
            }

            // Remove paragraphs that are mostly English (spam)
            val arabicCount = text.count { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' || it in 'ﭐ'..'﷿' || it in 'ﹰ'..'\uFEFF' }
            val totalCount = text.replace("\\s".toRegex(), "").length
            if (totalCount > 0 && arabicCount.toFloat() / totalCount < 0.2f) {
                p.remove()
                return@forEach
            }

            // Remove common spam patterns
            val spamPatterns = listOf(
                "subscribe", "follow us", "discord", "patreon",
                "donate", "support us", "bookmark", "rate this",
                "chapter list", "table of contents", "next chapter",
                "previous chapter", "read more", "click here",
                "register", "sign up", "log in", "login",
                "join us", "telegram", "facebook", "twitter",
                "instagram", "youtube", "tiktok",
            )
            val lowerText = text.lowercase()
            if (spamPatterns.any { pattern -> lowerText.contains(pattern) }) {
                p.remove()
                return@forEach
            }

            // Remove paragraphs that are just numbers or chapter references
            if (text.matches(Regex("^\\d+$")) || text.matches(Regex("^(chapter|فصل)\\s*\\d+$", RegexOption.IGNORE_CASE))) {
                p.remove()
                return@forEach
            }
        }

        // Remove any remaining non-content divs
        content.select("div").forEach { div ->
            val text = div.text()
            if (text.isEmpty() || text.length < 20) {
                // Check if this div has no meaningful content
                val hasImages = div.select("img").isNotEmpty()
                if (!hasImages) {
                    div.remove()
                }
            }
        }

        return content.html()
    }
}

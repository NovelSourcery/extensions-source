package eu.kanade.tachiyomi.novelextension.ar.kolnovel

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Page

/**
 * Kol Novel uses CSS style-based obfuscation to hide spam paragraphs.
 * The article > style block defines classes whose matching paragraphs should be removed.
 */
class KolNovel :
    LightNovelWPNovel(
        baseUrl = "https://kolnovel.com",
        name = "Kol Novel",
        lang = "ar",
    ) {
    override val reverseChapters = true

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
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
            val text = p.text().trim()

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
            val arabicCount = text.count { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\uFB50'..'\uFDFF' || it in '\uFE70'..'\uFEFF' }
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
            val text = div.text().trim()
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

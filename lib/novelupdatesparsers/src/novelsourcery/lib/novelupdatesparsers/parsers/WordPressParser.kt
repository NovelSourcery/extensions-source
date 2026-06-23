package novelsourcery.lib.novelupdatesparsers.parsers

import novelsourcery.lib.novelupdatesparsers.SiteParser
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class WordPressParser : SiteParser {
    private val bloat = listOf(
        ".ad", ".author-avatar", ".chapter-warning", ".entry-meta",
        ".ezoic-ad", ".mb-center", ".modern-footnotes-footnote__note",
        ".patreon-widget", ".post-cats", ".pre-bar", ".sharedaddy",
        ".sidebar", ".swg-button-v2-light", ".wp-block-buttons",
        ".wp-dark-mode-switcher", ".wp-next-post-navi",
        "#hpk", "#jp-post-flair", "#textbox",
    )
    private val titleSelectors = listOf(
        ".entry-title", ".chapter__title", ".title-content",
        ".wp-block-post-title", ".title_story", "#chapter-heading",
        ".chapter-title", "head title", "h1:first-of-type",
        "h2:first-of-type", ".active",
    )
    private val contentSelectors = listOf(
        ".chapter__content", ".entry-content", ".text_story",
        ".post-content", ".contenta", ".single_post",
        ".main-content", ".reader-content", "#content",
        "#the-content", "article.post", ".chp_raw",
    )

    override fun canHandle(doc: Document, url: HttpUrl): Boolean = matches(doc, "meta[name=\"generator\"]", "content", Regex("wordpress|site kit")) ||
        matches(doc, "link, script, img", "src", Regex("/wp-content/|/wp-includes/")) ||
        matches(doc, "link", "href", Regex("/wp-content/|/wp-includes/")) ||
        matches(doc, "link[rel=\"https://api.w.org/\"]", "href", Regex(".*")) ||
        matches(doc, "link[rel=\"EditURI\"]", "href", Regex("xmlrpc\\.php")) ||
        matches(doc, "body", "class", Regex("wp-admin|wp-custom-logo|logged-in")) ||
        matches(doc, "script", null, Regex("wp-embed|wp-emoji|wp-block"))

    override fun parse(doc: Document, url: HttpUrl, client: OkHttpClient, headers: Headers): String {
        bloat.forEach { doc.select(it).remove() }

        var resolvedTitle = titleSelectors
            .mapNotNull { doc.select(it).first()?.text()?.trim()?.takeIf { t -> t.isNotEmpty() } }
            .firstOrNull()

        val chapterSubtitle = doc.select(".cat-series").first()?.text()
            ?: doc.select("h1.leading-none ~ span").first()?.text()
            ?: doc.select(".breadcrumb .active").first()?.text()
            ?: ""
        if (chapterSubtitle.isNotEmpty()) resolvedTitle = chapterSubtitle

        val title = resolvedTitle ?: ""

        val content = contentSelectors
            .mapNotNull { sel ->
                val el = doc.select(sel).first()
                el?.html()?.takeIf { el.text().trim().length > 50 }
            }
            .firstOrNull() ?: ""

        return if (title.isNotEmpty() && content.isNotEmpty()) {
            "<h2>$title</h2><hr><br>$content"
        } else {
            content
        }
    }

    private fun matches(doc: Document, selector: String, attr: String?, regex: Regex): Boolean = doc.select(selector).any { el: Element ->
        val value = if (attr != null) el.attr(attr) else (el.html().ifEmpty { el.text() })
        regex.containsMatchIn(value.lowercase())
    }
}

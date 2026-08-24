package eu.kanade.tachiyomi.novelextension.en.foxaholic

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Page
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class Foxaholic :
    MadaraNovel(
        baseUrl = "https://www.foxaholic.com",
        name = "Foxaholic",
        lang = "en",
    ) {
    // Uses new chapter endpoint (/ajax/chapters/) which returns clean chapter HTML
    // The old admin-ajax.php endpoint returns the full page instead of chapter list
    override val useNewChapterEndpointDefault = true

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val htmlString = response.body.string()
        val doc = Jsoup.parse(htmlString)

        android.util.Log.d("Foxaholic", "=== RECEIVED HTML ===")
        android.util.Log.d("Foxaholic", "HTML length: ${htmlString.length}")
        android.util.Log.d("Foxaholic", "HTML preview (first 2000 chars):\n${htmlString.take(2000)}")

        // Check what elements exist
        android.util.Log.d("Foxaholic", "=== ELEMENT SEARCH ===")
        android.util.Log.d("Foxaholic", ".reading-content found: ${doc.selectFirst(".reading-content") != null}")
        android.util.Log.d("Foxaholic", ".entry-content found: ${doc.selectFirst(".entry-content") != null}")
        android.util.Log.d("Foxaholic", ".text-left found: ${doc.selectFirst(".text-left") != null}")

        // Port JavaScript approach: querySelector('.reading-content')?.innerHTML
        // Extract content FIRST, then clean up ads within the HTML
        // This prevents removing parent containers that contain actual content
        val contentElement = doc.selectFirst(".reading-content") ?: doc.selectFirst(".entry-content")
            ?: doc.selectFirst(".text-left")

        if (contentElement == null) {
            android.util.Log.e("Foxaholic", "No content element found!")
            android.util.Log.e("Foxaholic", "Available classes in body: ${doc.body().select("div[class]").map { it.className() }.distinct().take(20)}")
            return ""
        }

        android.util.Log.d("Foxaholic", "=== BEFORE CLEANUP ===")
        android.util.Log.d("Foxaholic", "Selected element: ${contentElement.tagName()}.${contentElement.className()}")
        android.util.Log.d("Foxaholic", "Children count: ${contentElement.children().size}")
        android.util.Log.d("Foxaholic", "Children tags: ${contentElement.children().map { it.tagName() + "." + it.className() }}")
        android.util.Log.d("Foxaholic", "Paragraph count BEFORE: ${contentElement.select("p").size}")
        android.util.Log.d("Foxaholic", "Content HTML length BEFORE: ${contentElement.html().length}")
        android.util.Log.d("Foxaholic", "Content preview (first 1000 chars):\n${contentElement.html().take(1000)}")

        // EXTRACT PARAGRAPHS DIRECTLY - DON'T remove ad divs first!
        // The paragraphs are nested inside the ad containers, so we need to extract them before removal
        val extractedContent = mutableListOf<String>()

        contentElement.select("p, blockquote").forEach { element ->
            // Skip toolbar and button paragraphs
            val parentId = element.parent()?.id() ?: ""
            val parentClass = element.parent()?.className() ?: ""

            if (parentId.contains("text-chapter-toolbar", ignoreCase = true)) return@forEach
            if (parentClass.contains("unlock-button", ignoreCase = true)) return@forEach
            if (parentClass.contains("wp-block-button", ignoreCase = true)) return@forEach

            extractedContent.add(element.outerHtml())
        }

        android.util.Log.d("Foxaholic", "=== AFTER EXTRACTION ===")
        android.util.Log.d("Foxaholic", "Extracted ${extractedContent.size} content elements")

        // Join extracted content
        var content = extractedContent.joinToString("\n")

        android.util.Log.d("Foxaholic", "Content extracted - length: ${content.length}, paragraphs: ${contentElement.select("p").size}")

        // Remove any inline scripts
        content = content.replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
        // Remove adsbygoogle calls
        content = content.replace(Regex("""\(adsbygoogle[^)]*\)[^;]*;?"""), "")
        // Remove empty divs
        content = content.replace(Regex("""<div[^>]*>\s*</div>"""), "")

        return content.trim()
    }

    /**
     * Count the total length of paragraph content in an element.
     * Used to determine which content block has the most actual text.
     */
    private fun countContentParagraphs(element: Element): Int = element.select("p").sumOf { p ->
        val text = p.text()
        // Only count paragraphs with substantial content (not just ads or buttons)
        if (text.length > 20) text.length else 0
    }
}

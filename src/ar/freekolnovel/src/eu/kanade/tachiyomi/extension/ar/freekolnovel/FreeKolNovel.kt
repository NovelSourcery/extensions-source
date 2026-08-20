package eu.kanade.tachiyomi.novelextension.ar.freekolnovel

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import eu.kanade.tachiyomi.source.model.Page
import keiyoushi.annotation.Source
import keiyoushi.network.get

/**
 * Free Kol Novel uses the same CSS obfuscation as Kol Novel.
 */
@Source
abstract class FreeKolNovel : LightNovelWPNovel() {
    override val reverseChapters = true

    override suspend fun fetchPageText(page: Page): String {
        val response = client.get(baseUrl + page.url, headers)
        val doc = response.asJsoup()

        doc.select(".epcontent .code-block").remove()

        val styleText = doc.select("article > style").text()
        val classPattern = Regex("""\.\w+(?=\s*[,{])""")
        classPattern.findAll(styleText).forEach { match ->
            val selector = "p${match.value}"
            doc.select(selector).remove()
        }

        doc.select(
            ".unlock-buttons, .ads, script, style, .sharedaddy, .su-spoiler-title, " +
                "noscript, ins, .adsbygoogle, iframe, [id*=google], [class*=google]",
        ).remove()

        val content = doc.select(".epcontent.entry-content").maxByOrNull {
            it.select("p").sumOf { p -> p.text().length }
        } ?: return ""

        return content.html()
    }
}

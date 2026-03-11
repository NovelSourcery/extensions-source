package eu.kanade.tachiyomi.extension.ar.freekolnovel

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Page

/**
 * Free Kol Novel uses the same CSS obfuscation as Kol Novel.
 */
class FreeKolNovel :
    LightNovelWPNovel(
        baseUrl = "https://free.kolnovel.com",
        name = "Free Kol Novel",
        lang = "ar",
    ) {
    override val reverseChapters = true

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
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

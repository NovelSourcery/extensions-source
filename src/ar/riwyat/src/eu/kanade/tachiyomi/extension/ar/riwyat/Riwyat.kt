package eu.kanade.tachiyomi.extension.ar.riwyat

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Page

class Riwyat :
    MadaraNovel(
        baseUrl = "https://cenele.com",
        name = "Riwyat",
        lang = "ar",
    ) {
    override val useNewChapterEndpointDefault = true

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = response.asJsoup()

        // Remove hidden spam spans (opacity:0, position:fixed)
        doc.select("span[style*=opacity: 0][style*=position: fixed]").remove()
        doc.select("span[style*=opacity:0][style*=position:fixed]").remove()

        // Standard content extraction
        doc.select(
            "div.ads, div.unlock-buttons, sub, script, ins, .adsbygoogle, .code-block, noscript, " +
                "iframe, [id*='-ad-'], [class*='-ad-'], .ad-container",
        ).remove()

        val contentElement = listOf(
            doc.selectFirst(".text-left"),
            doc.selectFirst(".text-right"),
            doc.selectFirst(".reading-content .text-left"),
            doc.selectFirst(".reading-content .text-right"),
            doc.selectFirst(".entry-content"),
            doc.selectFirst(".reading-content"),
        ).filterNotNull().maxByOrNull { element ->
            element.select("p").sumOf { it.text().length }
        }

        return contentElement?.html()?.trim() ?: ""
    }
}

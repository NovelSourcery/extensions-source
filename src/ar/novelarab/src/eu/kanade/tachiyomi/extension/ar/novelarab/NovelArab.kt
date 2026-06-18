package eu.kanade.tachiyomi.novelextension.ar.novelarab

import eu.kanade.tachiyomi.multisrc.madaranovel.MadaraNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Page

class NovelArab :
    MadaraNovel(
        baseUrl = "https://novelarab.com",
        name = "Novel Arab",
        lang = "ar",
    ) {
    override val useNewChapterEndpointDefault = true
    override val reverseChapterListDefault = true

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = response.asJsoup()

        checkCaptcha(doc, baseUrl + page.url)

        doc.select(
            "div.ads, div.unlock-buttons, sub, script, ins, .adsbygoogle, .code-block, noscript, " +
                "iframe, .foxaholic-google-tag-manager-body, div[id*=google], div[class*=google], " +
                "div[id^=bg-ssp], .adx-zone, .adx-head, [id*='-ad-'], [class*='-ad-'], .ad-container, " +
                ".sharedaddy, .su-spoiler-title",
        ).remove()

        val contentElement = listOf(
            doc.selectFirst(".text-left"),
            doc.selectFirst(".text-right"),
            doc.selectFirst(".reading-content .text-left"),
            doc.selectFirst(".reading-content .text-right"),
            doc.selectFirst(".entry-content"),
            doc.selectFirst(".reading-content"),
            doc.selectFirst(".chapter-content"),
        ).filterNotNull().maxByOrNull { element ->
            element.select("p").sumOf { it.text().length }
        }

        return contentElement?.html()?.trim() ?: ""
    }
}

package eu.kanade.tachiyomi.extension.en.systemtranslation

import eu.kanade.tachiyomi.multisrc.lightnovelwpnovel.LightNovelWPNovel
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

class SystemTranslation : LightNovelWPNovel(
    baseUrl = "https://systemtranslation.com",
    name = "System Translation",
    lang = "en",
) {
    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .add("Referer", baseUrl)

    // Latest updates from homepage
    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) baseUrl else "$baseUrl/page/$page/"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val doc = Jsoup.parse(response.body.string())

        // Parse latest novels from homepage using div.listupd article.bs structure
        val novels = doc.select(".listupd article.bs, div.listupd div.excstf article.bs").mapNotNull { element ->
            try {
                val linkElement = element.selectFirst("a.tip, a[itemprop=url], .bsx a") ?: return@mapNotNull null
                val url = linkElement.attr("href")
                val title = element.selectFirst("span.ntitle, .tt span.ntitle")?.text()
                    ?: linkElement.attr("title")
                    ?: linkElement.attr("oldtitle")
                    ?: return@mapNotNull null

                val image = element.selectFirst("img.ts-post-image, img.wp-post-image, img")
                val cover = image?.attr("src")
                    ?: image?.attr("data-src")
                    ?: image?.attr("data-lazy-src")
                    ?: ""

                SManga.create().apply {
                    this.title = title.trim()
                    this.url = when {
                        url.startsWith(baseUrl) -> url.removePrefix(baseUrl)
                        url.startsWith("http://") || url.startsWith("https://") -> {
                            try { java.net.URI(url).path } catch (e: Exception) { url }
                        }
                        url.startsWith("/") -> url
                        else -> "/$url"
                    }
                    thumbnail_url = cover
                }
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.url }

        // Check for pagination using div.hpage next button
        val hasNextPage = doc.selectFirst(".hpage a.r, div.hpage a.r, .hpage a:contains(Next)") != null

        return MangasPage(novels, hasNextPage)
    }
}

package eu.kanade.tachiyomi.extension.en.lightnoveltranslation

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

class LightNovelTranslation :
    HttpSource(),
    NovelSource {

    override val name = "Light Novel Translations"
    override val baseUrl = "https://lightnovelstranslations.com"
    override val lang = "en"
    override val supportsLatest = true
    override val isNovelSource = true
    override val client = network.cloudflareClient
    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/read/page/$page?sortby=most-liked", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val mangas = doc.select("div.read_list-story-item").mapNotNull { element ->
            try {
                val link = element.selectFirst(".item_thumb a") ?: return@mapNotNull null
                val url = link.attr("href")
                val title = link.attr("title").ifEmpty { link.text().trim() }
                val cover = element.selectFirst(".item_thumb img")?.attr("src") ?: ""

                SManga.create().apply {
                    this.title = title
                    this.url = url.removePrefix(baseUrl)
                    thumbnail_url = cover
                }
            } catch (e: Exception) {
                null
            }
        }
        val hasNextPage = doc.selectFirst("a.next.page-numbers, a:contains(Next)") != null ||
            mangas.size >= 20
        return MangasPage(mangas, hasNextPage)
    }
    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/read/page/$page?sortby=most-recent", headers)

    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)
    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val body = FormBody.Builder()
            .add("field-search", query)
            .build()
        return POST("$baseUrl/read", headers, body)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val mangas = popularMangaParse(response).mangas
        return MangasPage(mangas, false)
    }
    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()

        return SManga.create().apply {
            thumbnail_url = doc.selectFirst("div.novel-image img")?.attr("src")
            title = doc.selectFirst("div.novel_title h3")?.text()?.trim() ?: ""

            val statusText = doc.selectFirst("div.novel_status")?.text()?.trim() ?: ""
            status = when {
                statusText.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                statusText.contains("Hiatus", ignoreCase = true) -> SManga.ON_HIATUS
                statusText.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }

            author = doc.selectFirst("div.novel_detail_info li")
                ?.takeIf { it.text().contains("Author", ignoreCase = true) }
                ?.text()?.substringAfter("Author")?.replace(":", "")?.trim()

            val descUrl = response.request.url.toString().replace("?tab=table_contents", "")
            try {
                val descDoc = client.newCall(GET(descUrl, headers)).execute().asJsoup()
                description = descDoc.selectFirst("div.novel_text p")?.text()?.trim()
            } catch (e: Exception) {
                description = ""
            }
        }
    }
    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        return doc.select("li.chapter-item.unlock").mapNotNull { element ->
            try {
                val link = element.selectFirst("a") ?: return@mapNotNull null
                val chapterUrl = link.attr("href")
                if (chapterUrl.isBlank()) return@mapNotNull null

                SChapter.create().apply {
                    url = chapterUrl.removePrefix(baseUrl)
                    name = link.text().trim()
                }
            } catch (e: Exception) {
                null
            }
        }.reversed()
    }
    // ======================== Pages ========================

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.encodedPath))

    override fun imageUrlParse(response: Response): String = ""
    // ======================== Novel Content ========================

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = response.asJsoup()

        val content = doc.selectFirst("div.text_story") ?: return ""
        content.select("div.ads_content").remove()

        return content.html()
    }

    private fun Response.asJsoup() = Jsoup.parse(body.string())
}

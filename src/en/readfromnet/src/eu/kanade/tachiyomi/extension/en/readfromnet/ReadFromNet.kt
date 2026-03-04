package eu.kanade.tachiyomi.extension.en.readfromnet

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URLEncoder

/**
 * ReadFromNet novel source - ported from LN Reader plugin
 * @see https://github.com/LNReader/lnreader-plugins readfrom.ts
 */
class ReadFromNet :
    HttpSource(),
    NovelSource {

    override val name = "ReadFromNet"

    override val baseUrl = "https://readfrom.net"

    override val lang = "en"

    override val supportsLatest = true
    // ======================== Popular ========================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/allbooks/page/$page/", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val novels = parseNovels(doc, isSearch = false)
        val hasNextPage = doc.selectFirst("div.navigation a:contains(Next)") != null
        return MangasPage(novels, hasNextPage)
    }
    // ======================== Latest ========================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/last_added_books/page/$page/", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)
    // ======================== Search ========================

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/build_in_search/?q=$encodedQuery", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        // LN Reader: search uses "div.text > article.box" selector
        val novels = parseNovels(doc, isSearch = true)
        return MangasPage(novels, false) // Search doesn't support pagination
    }
    // ======================== Parsing ========================

    private fun parseNovels(doc: Document, isSearch: Boolean): List<SManga> {
        // LN Reader uses different selectors for search vs browse
        val selector = if (isSearch) "div.text > article.box" else "#dle-content > article.box"

        return doc.select(selector).mapNotNull { element ->
            try {
                val titleElement = element.selectFirst("h2.title a") ?: return@mapNotNull null
                val title = titleElement.text().trim()
                // LN Reader: .replace('https://readfrom.net/', '').replace(/^\//, '')
                var url = titleElement.attr("href")

                // Simple replacement as per LN Reader TS
                // replace('https://readfrom.net/', '').replace(/^\//, '')
                url = url.replace("https://readfrom.net/", "")
                    .replace(Regex("^/"), "")

                val cover = element.selectFirst("img")?.attr("src") ?: ""

                SManga.create().apply {
                    this.title = title
                    this.url = url
                    thumbnail_url = cover
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    // ======================== Details ========================

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl/${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()

        return SManga.create().apply {
            // LN Reader: splits by ", \n\n" and takes first part
            title = doc.selectFirst("center > h2.title")?.text()
                ?.split(", \n\n")?.firstOrNull()?.trim() ?: ""

            thumbnail_url = doc.selectFirst("article.box > div > center > div > a > img")?.attr("src")

            val descElement = doc.selectFirst("div.text3, div.text5")
            descElement?.select(".coll-ellipsis, a")?.remove()
            // Include hidden content (from .coll-hidden span)
            val hiddenContent = descElement?.selectFirst("span.coll-hidden")?.text() ?: ""
            var desc = (descElement?.text()?.trim() ?: "") + " " + hiddenContent

            // LN Reader: Add series info if present (center > b:has(a) with /series.html link)
            val seriesElement = doc.select("center > b:has(a)").firstOrNull { el ->
                el.selectFirst("a")?.attr("href")?.startsWith("/series.html") == true
            }
            if (seriesElement != null) {
                desc = "${seriesElement.text().trim()}\n\n$desc"
            }
            description = desc.trim()

            author = doc.select("h4 > a").firstOrNull()?.text()?.trim()
            genre = doc.select("h2 > a")
                .toList()
                .filter { it.attr("title").startsWith("Genre - ") }
                .joinToString(", ") { it.text().trim() }

            // LN Reader: checks for status text
            status = when {
                doc.text().contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                doc.text().contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }
    // ======================== Chapters ========================

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl/${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val chapters = mutableListOf<SChapter>()
        val novelPath = response.request.url.encodedPath.trimStart('/')

        // LN Reader: First chapter is the page itself (page 1)
        chapters.add(
            SChapter.create().apply {
                name = "1"
                url = novelPath
                chapter_number = 1f
            },
        )

        // LN Reader: Get pagination from div.pages > a
        doc.selectFirst("div.pages")?.select("> a")?.forEachIndexed { index, element ->
            // LN Reader: .replace('https://readfrom.net/', '').replace(/^\//, '')
            var chapterUrl = element.attr("href")
                .replace("https://readfrom.net", "")
                .replace(baseUrl, "")

            if (!chapterUrl.startsWith("/")) {
                chapterUrl = "/$chapterUrl"
            }

            val chapterName = element.text().trim()

            chapters.add(
                SChapter.create().apply {
                    name = chapterName
                    url = chapterUrl
                    chapter_number = (index + 2).toFloat()
                },
            )
        }

        return chapters.reversed()
    }
    // ======================== Pages ========================

    override fun pageListParse(response: Response): List<Page> = listOf(Page(0, response.request.url.toString()))

    override fun imageUrlParse(response: Response): String = ""
    // ======================== Novel Content ========================

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(page.url, headers)).execute()
        val doc = response.asJsoup()

        val textElement = doc.selectFirst("#textToRead") ?: return ""

        // LN Reader: Remove empty spans and center elements
        textElement.select("span:empty, center").remove()

        val chapterHtml = StringBuilder()
        var paragraph = StringBuilder()

        // LN Reader: Process child nodes, accumulating text into paragraphs
        // When hitting an Element node, flush the paragraph and add the element
        textElement.childNodes().forEach { node ->
            when {
                node is TextNode -> {
                    val content = node.text().trim()
                    if (content.isNotEmpty()) {
                        paragraph.append(content).append(" ")
                    }
                }

                node is Element -> {
                    // Flush accumulated text as paragraph
                    if (paragraph.isNotEmpty()) {
                        chapterHtml.append("<p>").append(paragraph.toString().trim()).append("</p>")
                        paragraph = StringBuilder()
                    }
                    if (node.tagName() != "br") {
                        chapterHtml.append(node.outerHtml())
                    }
                }
            }
        }

        if (paragraph.isNotEmpty()) {
            chapterHtml.append("<p>").append(paragraph.toString().trim()).append("</p>")
        }

        return chapterHtml.toString()
    }

    private fun Response.asJsoup(): Document = Jsoup.parse(body.string())
}

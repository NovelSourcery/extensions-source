package eu.kanade.tachiyomi.novelextension.ar.rewayatfans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response

class RewayatFans :
    HttpSource(),
    NovelSource {

    override val name = "Rewayat Fans"
    override val baseUrl = "https://rewayatfans.com"
    override val lang = "ar"
    override val supportsLatest = true
    override val isNovelSource = true

    private fun String.toRelativeUrl(): String = when {
        startsWith("http://rewayatfans.com") -> removePrefix("http://rewayatfans.com")
        startsWith("https://rewayatfans.com") -> removePrefix("https://rewayatfans.com")
        startsWith("/") -> this
        else -> "/$this"
    }

    private fun parseNovelList(document: org.jsoup.nodes.Document): List<SManga> {
        return document.select("figure.wp-block-image").mapNotNull { figure ->
            val captionLink = figure.selectFirst("figcaption a[href]")
                ?: return@mapNotNull null
            val imgElement = figure.selectFirst("img")
            val href = captionLink.attr("href")
            val title = captionLink.text().trim()
            val relativeUrl = href.toRelativeUrl()
            if (relativeUrl.isNotEmpty() && title.isNotEmpty()) {
                SManga.create().apply {
                    url = relativeUrl
                    this.title = title
                    thumbnail_url = imgElement?.attr("data-orig-file")
                        ?: imgElement?.attr("src")
                        ?: ""
                }
            } else {
                null
            }
        }.distinctBy { it.url }
    }

    override fun popularMangaRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/%d9%82%d8%a7%d8%a6%d9%85%d8%a9-%d8%a7%d9%84%d8%b1%d9%88%d8%a7%d9%8a%d8%a7%d8%aa/"
        } else {
            "$baseUrl/%d9%82%d8%a7%d8%a6%d9%85%d8%a9-%d8%a7%d9%84%d8%b1%d9%88%d8%a7%d9%8a%d8%a7%d8%aa/page/$page/"
        }
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val novels = parseNovelList(document)
        val hasNextPage = document.selectFirst(".page-links a.post-page-numbers") != null
        return MangasPage(novels, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            baseUrl
        } else {
            "$baseUrl/page/$page/"
        }
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val novels = parseNovelList(document)
        return MangasPage(novels, false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/?s=$query", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val novels = document.select("article.entry, article").mapNotNull { item ->
            val titleLink = item.selectFirst("h2.entry-title a[href]")
                ?: item.selectFirst("header a[href]")
                ?: return@mapNotNull null
            val imgElement = item.selectFirst("figure.post-thumbnail img, figure img")
            val href = titleLink.attr("href")
            val title = titleLink.text().trim()
            val relativeUrl = href.toRelativeUrl()
            if (relativeUrl.isNotEmpty() && title.isNotEmpty()) {
                SManga.create().apply {
                    url = relativeUrl
                    this.title = title
                    thumbnail_url = imgElement?.attr("data-orig-file")
                        ?: imgElement?.attr("src")
                        ?: ""
                }
            } else {
                null
            }
        }.distinctBy { it.url }
        return MangasPage(novels, false)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.selectFirst("h1.entry-title")
                ?.text()?.trim()
                ?: document.selectFirst("header.entry-header h2.entry-title")
                    ?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")
                    ?.attr("content")
                    ?.substringBefore(" – روايات فانز")
                    ?.trim()
                ?: ""
            thumbnail_url = document.select("meta[property=og:image]").attr("content")
            description = document.select("meta[property=og:description]").attr("content").trim()
            if (description.isNullOrBlank()) {
                description = document.select(".entry-content p").firstOrNull()?.text()?.trim()
            }
            status = SManga.UNKNOWN
            update_strategy = UpdateStrategy.ALWAYS_UPDATE
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()

        document.select(".entry-content ul.wp-block-list li a[href], .entry-content p a[href]").forEach { link ->
            val href = link.attr("href")
            val text = link.text().trim()
            val relativeUrl = href.toRelativeUrl()

            if (relativeUrl.isNotEmpty() && text.matches(Regex("^\\d+.*")) && !relativeUrl.contains("/page/")) {
                val exists = chapters.any { it.url == relativeUrl }
                if (!exists) {
                    chapters.add(
                        SChapter.create().apply {
                            url = relativeUrl
                            name = "الفصل $text"
                            chapter_number = text.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 0f
                        },
                    )
                }
            }
        }

        return chapters.reversed()
    }

    override fun pageListParse(response: Response): List<Page> {
        val url = response.request.url.encodedPath
        return listOf(Page(0, url))
    }

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val document = response.asJsoup()
        val content = document.selectFirst(".entry-content") ?: return ""
        content.select(
            ".wp-block-spacer, .wp-block-buttons, .wp-block-image, " +
                "script, style, .sharedaddy, .jetpack-related-posts",
        ).remove()
        val paragraphs = content.select("p").filter { p ->
            val text = p.text().trim()
            text.isNotEmpty() && !text.startsWith("السابق") && !text.startsWith("التالي")
        }
        return paragraphs.joinToString("<br><br>") { it.html() }
    }

    override fun imageUrlParse(response: Response): String = ""
}

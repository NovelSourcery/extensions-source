package eu.kanade.tachiyomi.extension.en.novelbin

import eu.kanade.tachiyomi.multisrc.readnovelfull.ReadNovelFull
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Element

class NovelBin :
    ReadNovelFull(
        name = "NovelBin",
        baseUrl = "https://novelbin.com",
        lang = "en",
    ) {
    override val latestPage = "sort/latest"

    override fun popularMangaSelector() = "div.col-xs-12.col-md-8 div.row[itemscope], div.list-thumb div.col-xs-4, div.list-novel div.row"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        element.selectFirst("div.col-title h3 a")?.let { link ->
            title = link.attr("title").ifEmpty { link.text().trim() }
            setUrlWithoutDomain(link.attr("abs:href"))
            return@apply
        }

        element.selectFirst("a[href]")?.let { link ->
            setUrlWithoutDomain(link.attr("abs:href"))
            title = link.attr("title").ifEmpty {
                element.selectFirst("h3, .caption h3")?.text()?.trim() ?: ""
            }
        }
        thumbnail_url = element.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
    }

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun mangaDetailsParse(document: org.jsoup.nodes.Document): SManga = SManga.create().apply {
        document.selectFirst("div.books, div.book")?.let { info ->
            thumbnail_url = info.selectFirst("img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            }
            title = document.selectFirst("h3.title")?.text()?.trim() ?: ""
        }

        document.select("div.info div").forEach { element ->
            val text = element.text()
            when {
                text.contains("Author", ignoreCase = true) -> {
                    author = element.select("a").joinToString { it.text().trim() }
                        .ifEmpty { text.substringAfter(":").trim() }
                }

                text.contains("Genre", ignoreCase = true) -> {
                    genre = element.select("a").joinToString { it.text().trim() }
                }

                text.contains("Status", ignoreCase = true) -> {
                    status = when {
                        text.contains("Ongoing", ignoreCase = true) -> SManga.ONGOING
                        text.contains("Completed", ignoreCase = true) -> SManga.COMPLETED
                        else -> SManga.UNKNOWN
                    }
                }
            }
        }

        description = document.selectFirst("div.desc-text")?.text()?.trim()
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val novelId = document.selectFirst("div#rating")?.attr("data-novel-id")

        if (novelId != null) {
            try {
                val ajaxUrl = "$baseUrl/ajax/chapter-archive?novelId=$novelId"
                val ajaxHeaders = headers.newBuilder().add("Referer", response.request.url.toString()).build()
                val ajaxResponse = client.newCall(okhttp3.Request.Builder().url(ajaxUrl).headers(ajaxHeaders).build()).execute()
                val ajaxDocument = ajaxResponse.asJsoup()

                val chapters = ajaxDocument.select("ul.list-chapter li a").mapIndexedNotNull { index, element ->
                    val chapterUrl = element.attr("abs:href")
                    if (chapterUrl.isBlank()) return@mapIndexedNotNull null

                    SChapter.create().apply {
                        setUrlWithoutDomain(chapterUrl)
                        name = element.attr("title").ifEmpty { element.text().trim() }
                        chapter_number = (index + 1).toFloat()
                    }
                }

                if (chapters.isNotEmpty()) {
                    return chapters.reversed()
                }
            } catch (_: Exception) {
            }
        }

        return document.select("ul.list-chapter li a").mapIndexedNotNull { index, element ->
            val chapterUrl = element.attr("abs:href")
            if (chapterUrl.isBlank()) return@mapIndexedNotNull null

            SChapter.create().apply {
                setUrlWithoutDomain(chapterUrl)
                name = element.attr("title").ifEmpty { element.text().trim() }
                chapter_number = (index + 1).toFloat()
            }
        }.reversed()
    }

    // Content parsing
    override suspend fun fetchPageText(page: eu.kanade.tachiyomi.source.model.Page): String {
        val response = client.newCall(okhttp3.Request.Builder().url(page.url).headers(headers).build()).execute()
        val document = response.asJsoup()

        val content = document.selectFirst("div#chr-content, div#chapter-content, div.chapter-content")
        if (content != null) {
            content.select("div.ads, script, ins, .adsbygoogle").remove()
            return content.html()
        }

        return ""
    }
}

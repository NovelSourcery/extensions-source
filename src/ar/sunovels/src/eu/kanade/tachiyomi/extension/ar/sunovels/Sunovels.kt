package eu.kanade.tachiyomi.novelextension.ar.sunovels

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response

class Sunovels :
    HttpSource(),
    NovelSource {

    override val name = "Sunovels"
    override val baseUrl = "https://sunovels.com"
    override val lang = "ar"
    override val supportsLatest = true
    override val isNovelSource = true
    override val client = network.client

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/library?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val novels = doc.select("li.list-item").mapNotNull { item ->
            val link = item.selectFirst("a[href*=/novel/]") ?: return@mapNotNull null
            val title = item.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
            val img = item.selectFirst("img")
            val thumbnail = img?.attr("data-src")
                ?: img?.attr("data-lazy-src")
                ?: img?.attr("src")?.takeIf {
                    it.isNotEmpty() && !it.contains("placeholder")
                }
            SManga.create().apply {
                url = link.attr("href")
                this.title = title
                thumbnail_url = thumbnail
            }
        }.distinctBy { it.url }
        val hasNextPage = doc.selectFirst("li.next a[aria-disabled=false]") != null
        return MangasPage(novels, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/library?page=$page&sort=latest", headers)

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return GET("$baseUrl/search/?title=$q", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val doc = response.asJsoup()
        val novels = doc.select("li.list-item").mapNotNull { item ->
            val link = item.selectFirst("a[href*=/novel/]") ?: return@mapNotNull null
            val title = item.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
            val img = item.selectFirst("img")
            val thumbnail = img?.attr("data-src")
                ?: img?.attr("data-lazy-src")
                ?: img?.attr("src")?.takeIf {
                    it.isNotEmpty() && !it.contains("placeholder")
                }
            SManga.create().apply {
                url = link.attr("href")
                this.title = title
                thumbnail_url = thumbnail
            }
        }.distinctBy { it.url }
        return MangasPage(novels, false)
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val doc = response.asJsoup()
        val html = doc.html()
        return SManga.create().apply {
            val novelH1 = doc.selectFirst(".info h1, .novel-header h1, .main-head h1")
            val novelH3 = doc.selectFirst(".info h3, .novel-header h3, .main-head h3")
            title = novelH3?.text()?.trim()?.ifEmpty { null }
                ?: novelH1?.text()?.trim()?.ifEmpty { null }
                ?: doc.selectFirst("meta[property=og:title]")
                    ?.attr("content")
                    ?.removePrefix("رواية ")
                    ?.substringBefore(" | شمس الروايات")
                    ?.substringBefore(" | Sunovels")
                    ?.trim()
                ?: doc.title()
                    .removePrefix("رواية ")
                    .substringBefore(" | شمس الروايات")
                    .substringBefore(" | Sunovels")
                    .trim()
            status = when {
                doc.selectFirst(".top.Ongoing, .Ongoing") != null -> SManga.ONGOING
                doc.selectFirst(".top.Completed, .Completed") != null -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            val imgMatch = Regex("\"image\":\"([^\"]*)\"").find(html)
            thumbnail_url = imgMatch?.groupValues?.get(1)?.let {
                if (it.startsWith("/")) "$baseUrl$it" else it
            } ?: doc.selectFirst(".cover img, figure.cover img, .img-container img")?.attr("src")?.let {
                if (it.startsWith("/")) "$baseUrl$it" else it
            }
            genre = doc.select(".badge, .category, .tag")
                .mapNotNull { it.text().trim().takeIf { t -> t.isNotEmpty() } }
                .distinct()
                .joinToString(", ")
            description = doc.selectFirst(".description, .info-section .description")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:description]")
                    ?.attr("content")?.trim()
                ?: ""
        }
    }

    override fun chapterListRequest(manga: SManga): Request = GET("$baseUrl${manga.url}", headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val doc = response.asJsoup()
        val html = doc.html()
        val chapters = mutableListOf<SChapter>()
        val slug = response.request.url.toString().substringAfterLast("/novel/").substringBefore("?")
        val chapterPattern = Regex("\"chapters\":\\[([^\\]]*)\\]")
        val chapterMatch = chapterPattern.find(html)
        if (chapterMatch != null) {
            val chapterBlock = chapterMatch.groupValues[1]
            val chapRegex = Regex(
                "\"id\":(\\d+),\"number\":(\\d+),\"title\":\"([^\"]*)\",\"slug\":\"([^\"]*)\"",
            )
            chapRegex.findAll(chapterBlock).forEach { match ->
                val number = match.groupValues[2].toFloatOrNull() ?: 0f
                val title = match.groupValues[3].ifEmpty { match.groupValues[2] }
                val chapSlug = match.groupValues[4]
                chapters.add(
                    SChapter.create().apply {
                        url = "/novel/$slug/chapter/$chapSlug"
                        name = "الفصل $number: $title"
                        chapter_number = number
                    },
                )
            }
        }
        if (chapters.isEmpty()) {
            doc.select("a[href*=chapter]").forEach { link ->
                val href = link.attr("href")
                if (href.isEmpty()) return@forEach
                val text = link.text().trim()
                if (text.isEmpty()) return@forEach
                val exists = chapters.any { it.url == href }
                if (!exists) {
                    chapters.add(
                        SChapter.create().apply {
                            url = href
                            name = text
                            chapter_number = Regex(
                                "chapter[-\\s]?(\\d+)",
                                RegexOption.IGNORE_CASE,
                            ).find(href)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
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
        val doc = client.newCall(GET("$baseUrl${page.url}", headers)).execute().asJsoup()
        val html = doc.html()
        val contentMatch = Regex(
            "\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"",
        ).find(html)
        if (contentMatch != null) {
            return contentMatch.groupValues[1]
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("\\u003C", "<")
                .replace("\\u003E", ">")
                .trim()
        }
        val content = doc.selectFirst(
            ".chapter-content, .content, .entry-content, .post-content, article, .text",
        ) ?: return ""
        content.select(
            "script, style, .ads, .navigation, .chapter-nav, " +
                ".social-share, .comments, nav, footer",
        ).remove()
        return content.html().trim()
    }

    override fun imageUrlParse(response: Response): String = ""
}

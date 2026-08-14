package eu.kanade.tachiyomi.novelextension.ar.rewayatfans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import okhttp3.HttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class RewayatFans :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    /** [SManga.url] stored as a bare slug (root-level WP post permalink, no fixed prefix). */
    private val mangaPathTemplate = SlugPath("/")

    private fun String.toRelativeUrl(): String = when {
        startsWith("http://rewayatfans.com") -> removePrefix("http://rewayatfans.com")
        startsWith("https://rewayatfans.com") -> removePrefix("https://rewayatfans.com")
        startsWith("/") -> this
        else -> "/$this"
    }

    private fun Element.thumbnailUrl(): String = selectFirst("img")?.let { img ->
        img.attr("data-orig-file")
            .takeIf { it.isNotEmpty() }
            ?: img.attr("data-large-file")
                .takeIf { it.isNotEmpty() }
            ?: img.attr("src")
                .takeIf { it.isNotEmpty() }
            ?: ""
    } ?: ""

    private fun parseNovelList(document: Document): List<SManga> {
        return document.select("figure.wp-block-image").mapNotNull { figure ->
            val captionLink = figure.selectFirst("figcaption a[href]")
                ?: return@mapNotNull null
            val imgElement = figure.selectFirst("img")
            val href = captionLink.attr("href")
            val title = captionLink.text()
            val relativeUrl = href.toRelativeUrl()
            if (relativeUrl.isNotEmpty() && title.isNotEmpty()) {
                SManga.create().apply {
                    url = mangaPathTemplate.slug(relativeUrl)
                    this.title = title
                    thumbnail_url = figure.thumbnailUrl()
                }
            } else {
                null
            }
        }.distinctBy { it.url }
    }

    private fun buildPopularMangaRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/%d9%82%d8%a7%d8%a6%d9%85%d8%a9-%d8%a7%d9%84%d8%b1%d9%88%d8%a7%d9%8a%d8%a7%d8%aa/"
        } else {
            "$baseUrl/%d9%82%d8%a7%d8%a6%d9%85%d8%a9-%d8%a7%d9%84%d8%b1%d9%88%d8%a7%d9%8a%d8%a7%d8%aa/page/$page/"
        }
        return GET(url, headers)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.newCall(buildPopularMangaRequest(page)).execute()
        val document = response.asJsoup()
        val novels = parseNovelList(document)
        val hasNextPage = document.selectFirst(".page-links a.post-page-numbers") != null
        return MangasPage(novels, hasNextPage)
    }

    private fun buildLatestUpdatesRequest(page: Int): Request {
        val url = if (page == 1) {
            baseUrl
        } else {
            "$baseUrl/page/$page/"
        }
        return GET(url, headers)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = client.newCall(buildLatestUpdatesRequest(page)).execute()
        val document = response.asJsoup()
        val novels = parseNovelList(document)
        val hasNextPage = document.selectFirst(".page-links a.post-page-numbers") != null
        return MangasPage(novels, hasNextPage)
    }

    private fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/?s=$query", headers)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val response = client.newCall(buildSearchMangaRequest(page, query, filters)).execute()
        val document = response.asJsoup()
        val novels = document.select("article.entry, article").mapNotNull { item ->
            val titleLink = item.selectFirst("h2.entry-title a[href]")
                ?: item.selectFirst("header a[href]")
                ?: return@mapNotNull null
            val imgElement = item.selectFirst("figure.post-thumbnail img, figure img")
            val href = titleLink.attr("href")
            val title = titleLink.text()
            val relativeUrl = href.toRelativeUrl()
            if (relativeUrl.isNotEmpty() && title.isNotEmpty()) {
                SManga.create().apply {
                    url = mangaPathTemplate.slug(relativeUrl)
                    this.title = title
                    thumbnail_url = imgElement?.attr("data-orig-file")
                        ?: imgElement?.attr("data-large-file")
                        ?: imgElement?.attr("src")
                        ?: ""
                }
            } else {
                null
            }
        }.distinctBy { it.url }
        val hasNextPage = document.selectFirst(".page-links a.post-page-numbers") != null
        return MangasPage(novels, hasNextPage)
    }

    private fun buildMangaDetailsRequest(manga: SManga): Request = GET(baseUrl + mangaPathTemplate.resolve(manga.url), headers)

    override fun getMangaUrl(manga: SManga): String = baseUrl + mangaPathTemplate.resolve(manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val manga = SManga.create().apply { this.url = mangaPathTemplate.slug(url.encodedPath) }
        val response = client.newCall(buildMangaDetailsRequest(manga)).execute()
        if (!response.isSuccessful) return null
        return parseMangaDetails(response.asJsoup()).apply { this.url = manga.url }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.newCall(buildMangaDetailsRequest(manga)).execute()
        val document = response.asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(document) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(document) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.entry-title")
            ?.text()
            ?: document.selectFirst("header.entry-header h2.entry-title")
                ?.text()
            ?: document.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.substringBefore(" – روايات فانز")
                ?.trim()
            ?: ""
        thumbnail_url = document.select("meta[property=og:image]").attr("content")
        description = document.select("meta[property=og:description]").attr("content").trim()
        if (description.isNullOrBlank()) {
            description = document.select(".entry-content p").firstOrNull()?.text()
        }
        status = SManga.UNKNOWN
        update_strategy = UpdateStrategy.ALWAYS_UPDATE
    }

    private fun parseChapterList(document: Document): List<SChapter> {
        val chapters = mutableListOf<SChapter>()

        document.select(".entry-content ul.wp-block-list li a[href], .entry-content p a[href]").forEach { link ->
            val href = link.attr("href")
            val text = link.text()
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

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val document = response.asJsoup()
        val content = document.selectFirst(".entry-content") ?: return ""
        content.select(
            ".wp-block-spacer, .wp-block-buttons, .wp-block-image, " +
                "script, style, .sharedaddy, .jetpack-related-posts",
        ).remove()
        val paragraphs = content.select("p").filter { p ->
            val text = p.text()
            text.isNotEmpty() && !text.startsWith("السابق") && !text.startsWith("التالي")
        }
        return paragraphs.joinToString("<br><br>") { it.html() }
    }
}

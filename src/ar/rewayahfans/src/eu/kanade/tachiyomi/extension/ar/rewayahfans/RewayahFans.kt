package eu.kanade.tachiyomi.novelextension.ar.rewayahfans

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
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.SlugPath
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class RewayahFans :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    private companion object {
        /**
         * WordPress.com's edge rejects the app's default (OkHttp / mobile random) User-Agent with
         * a 403 for this host. A plain desktop Chrome UA plus the usual navigation headers gets a
         * 200, so both are forced here. See [BrowserHeadersInterceptor].
         */
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        const val HTML_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"

        const val ACCEPT_LANGUAGE = "ar,en-US;q=0.9,en;q=0.8"

        const val CLIENT_HINTS = "\"Chromium\";v=\"140\", \"Not=A?Brand\";v=\"24\", \"Google Chrome\";v=\"140\""
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = addInterceptor(BrowserHeadersInterceptor())

    private class BrowserHeadersInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response = chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", HTML_ACCEPT)
                .header("Accept-Language", ACCEPT_LANGUAGE)
                .header("sec-ch-ua", CLIENT_HINTS)
                .header("sec-ch-ua-mobile", "?0")
                .header("sec-ch-ua-platform", "\"Windows\"")
                .header("Upgrade-Insecure-Requests", "1")
                .build(),
        )
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("User-Agent", BROWSER_USER_AGENT)
        .set("Accept", HTML_ACCEPT)
        .set("Accept-Language", ACCEPT_LANGUAGE)
        .set("sec-ch-ua", CLIENT_HINTS)
        .set("sec-ch-ua-mobile", "?0")
        .set("sec-ch-ua-platform", "\"Windows\"")
        .set("Upgrade-Insecure-Requests", "1")

    /** [SManga.url] stored as a bare slug (root-level WP post permalink, no fixed prefix). */
    private val mangaPathTemplate = SlugPath("/")

    private fun String.toRelativeUrl(): String = when {
        startsWith("http://rewayahfans.net") -> removePrefix("http://rewayahfans.net")
        startsWith("https://rewayahfans.net") -> removePrefix("https://rewayahfans.net")
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

    private suspend fun parseNovelList(document: Document): List<SManga> {
        val novels = document.select("figure.wp-block-image").mapNotNull { figure ->
            val link = figure.selectFirst("figcaption a[href]")
                ?: figure.selectFirst("a[href]")
                ?: return@mapNotNull null
            // Entries on the home page have no <figcaption>: their title is the heading rendered
            // right after the figure. On the /list/ archive some figures lack both, and those get
            // their title from the WP REST API (or, as a last resort, from the decoded slug).
            val heading = figure.nextElementSibling()?.selectFirst("h3 a[href], h2 a[href]")
            val title = link.text().trim().takeIf { it.isNotEmpty() && figure.selectFirst("figcaption a[href]") != null }
                ?: heading?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: ""
            val relativeUrl = link.attr("href").toRelativeUrl()
            if (relativeUrl.isEmpty()) return@mapNotNull null
            SManga.create().apply {
                url = mangaPathTemplate.slug(relativeUrl)
                this.title = title
                thumbnail_url = figure.thumbnailUrl()
            }
        }.distinctBy { it.url }

        val untitled = novels.filter { it.title.isBlank() }
        if (untitled.isNotEmpty()) {
            val titles = fetchPageTitles(untitled.map { it.url })
            untitled.forEach { manga ->
                manga.title = titles[manga.url.trim('/')] ?: decodeSlug(manga.url)
            }
        }

        return novels
    }

    /**
     * The archive's `<figure>` blocks do not always carry a caption, but the site itself is a
     * WordPress install and exposes the page titles over REST. A single batched request resolves
     * every missing title.
     */
    private suspend fun fetchPageTitles(slugs: Collection<String>): Map<String, String> {
        if (slugs.isEmpty()) return emptyMap()
        val url = "$baseUrl/wp-json/wp/v2/pages".toHttpUrl().newBuilder()
            .addQueryParameter("_fields", "slug,title")
            .apply { slugs.forEach { addEncodedQueryParameter("slug[]", it.trim('/')) } }
            .build()
        return runCatching {
            client.get(url, headers).parseAs<List<WpTitlePage>>()
                .mapNotNull { page ->
                    page.title?.rendered?.takeIf { it.isNotBlank() }?.let { page.slug.trim('/') to it }
                }
                .toMap()
        }.getOrDefault(emptyMap())
    }

    private fun decodeSlug(slug: String): String = runCatching {
        java.net.URLDecoder.decode(slug.trim('/'), "UTF-8")
    }.getOrDefault(slug.trim('/'))
        .replace('-', ' ')
        .trim()
        .ifEmpty { slug }

    private fun buildPopularMangaRequest(page: Int): Request {
        val url = if (page == 1) {
            "$baseUrl/%d9%82%d8%a7%d8%a6%d9%85%d8%a9-%d8%a7%d9%84%d8%b1%d9%88%d8%a7%d9%8a%d8%a7%d8%aa/"
        } else {
            "$baseUrl/%d9%82%d8%a7%d8%a6%d9%85%d8%a9-%d8%a7%d9%84%d8%b1%d9%88%d8%a7%d9%8a%d8%a7%d8%aa/page/$page/"
        }
        return GET(url, headers)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val request = buildPopularMangaRequest(page)
        val response = client.get(request.url, request.headers)
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
        val request = buildLatestUpdatesRequest(page)
        val response = client.get(request.url, request.headers)
        val document = response.asJsoup()
        val novels = parseNovelList(document)
        val hasNextPage = document.selectFirst(".page-links a.post-page-numbers") != null
        return MangasPage(novels, hasNextPage)
    }

    private fun buildSearchMangaRequest(page: Int, query: String, filters: FilterList): Request = GET("$baseUrl/?s=$query", headers)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val request = buildSearchMangaRequest(page, query, filters)
        val response = client.get(request.url, request.headers)
        val document = response.asJsoup()
        val novels = document.select("li.wp-block-post").mapNotNull { item ->
            val titleLink = item.selectFirst("h2.wp-block-post-title a[href]")
                ?: return@mapNotNull null
            val imgElement = item.selectFirst("figure.wp-block-post-featured-image img")
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

    private fun buildMangaDetailsRequest(manga: SManga): Request = GET(mangaPathTemplate.absolute(baseUrl, manga.url), headers)

    override fun getMangaUrl(manga: SManga): String = mangaPathTemplate.absolute(baseUrl, manga.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val manga = SManga.create().apply { this.url = mangaPathTemplate.slug(url.encodedPath) }
        val request = buildMangaDetailsRequest(manga)
        val response = client.get(request.url, request.headers)
        if (!response.isSuccessful) return null
        return parseMangaDetails(response.asJsoup()).apply { this.url = manga.url }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val request = buildMangaDetailsRequest(manga)
        val response = client.get(request.url, request.headers)
        val document = response.asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(document) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(document) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.wp-block-post-title")
            ?.text()
            ?: run {
                val pageTitle = document.title()
                    .substringBefore(" - الصفحة الرئيسية")
                    .substringBefore(" – الصفحة الرئيسية")
                    .substringBefore(" - روايه فانز")
                    .substringBefore(" – روايه فانز")
                    .trim()
                if (pageTitle.isNotEmpty()) {
                    pageTitle
                } else {
                    document.selectFirst("meta[property=og:title]")
                        ?.attr("content")
                        ?.substringBefore(" - الصفحة الرئيسية")
                        ?.substringBefore(" – الصفحة الرئيسية")
                        ?.substringBefore(" - روايه فانز")
                        ?.substringBefore(" – روايه فانز")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: document.selectFirst("h1")?.text()
                        ?: ""
                }
            }
        thumbnail_url = document.select("meta[property=og:image]").attr("content")
        description = document.select("meta[property=og:description]").attr("content").trim()
        if (description.isNullOrBlank()) {
            description = document.select(".entry-content p").firstOrNull()?.text()
        }
        status = SManga.UNKNOWN
        update_strategy = UpdateStrategy.ALWAYS_UPDATE
    }

    private fun parseChapterList(document: Document): List<SChapter> {
        val chapters = document.select("p.has-medium-font-size a[href]").mapNotNull { link ->
            val href = link.attr("href")
            val text = link.text()
            val relativeUrl = href.toRelativeUrl()
            if (relativeUrl.isNotEmpty() && text.isNotEmpty() && !relativeUrl.contains("/page/")) {
                SChapter.create().apply {
                    url = relativeUrl
                    name = "الفصل $text"
                    chapter_number = text.replace(Regex("[^0-9.]"), "").toFloatOrNull() ?: 0f
                }
            } else {
                null
            }
        }
        return chapters.reversed()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val response = client.get(baseUrl + page.url, headers)
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

/** Minimal shape of a `/wp-json/wp/v2/pages?_fields=slug,title` entry. */
@Serializable
class WpTitlePage(
    val slug: String = "",
    val title: WpRenderedTitle? = null,
)

@Serializable
class WpRenderedTitle(val rendered: String = "")

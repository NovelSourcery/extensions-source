package eu.kanade.tachiyomi.novelextension.en.bakatsuki

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

@Source
abstract class BakaTsuki :
    KeiSource(),
    NovelSource {

    private val apiUrl = "$baseUrl/project/api.php"
    private val pagePrefix = "$baseUrl/project/index.php?title="

    override val supportsLatest = true

    override fun Headers.Builder.configureHeaders(): Headers.Builder = this.set("Referer", "$baseUrl/")

    private var lastKey = ""
    private val continueByKey = mutableMapOf<String, String>()

    private fun categoryRequest(page: Int, category: String): Request {
        lastKey = "$category#$page"
        val url = apiUrl.toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("list", "categorymembers")
            .addQueryParameter("cmtitle", category)
            .addQueryParameter("cmtype", "page")
            .addQueryParameter("cmlimit", "100")
            .addQueryParameter("format", "json")
        continueByKey[lastKey]?.let { url.addQueryParameter("cmcontinue", it) }
        return GET(url.build(), headers)
    }

    private fun parseCategoryResponse(response: Response): MangasPage {
        val result = response.parseAs<CategoryResponse>()
        val cmcontinue = result.continueData?.cmcontinue
        if (cmcontinue != null) {
            val (cat, pageStr) = lastKey.split("#")
            continueByKey["$cat#${pageStr.toInt() + 1}"] = cmcontinue
        }
        val mangas = result.query.categorymembers.map { member ->
            SManga.create().apply {
                title = member.title
                url = member.title.replace(" ", "_")
            }
        }
        return MangasPage(mangas, cmcontinue != null)
    }

    override suspend fun getPopularManga(page: Int): MangasPage = parseCategoryResponse(client.newCall(categoryRequest(page, "Category:Completed Project")).execute())

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseCategoryResponse(client.newCall(categoryRequest(page, "Category:Active Projects")).execute())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return getPopularManga(page)
        val requestUrl = apiUrl.toHttpUrl().newBuilder()
            .addQueryParameter("action", "opensearch")
            .addQueryParameter("search", query)
            .addQueryParameter("limit", "50")
            .addQueryParameter("namespace", "0")
            .addQueryParameter("format", "json")
        val array = client.newCall(GET(requestUrl.build(), headers)).execute().parseAs<JsonArray>()
        val titles = array.getOrNull(1)?.jsonArray ?: return MangasPage(emptyList(), false)
        val mangas = titles.mapNotNull { it.jsonPrimitive.content }
            .filter { !it.contains(":") }
            .map { t ->
                SManga.create().apply {
                    title = t
                    url = t.replace(" ", "_")
                }
            }
        return MangasPage(mangas, false)
    }

    private fun ParseResponse.orThrow(title: String): ParseData = parse ?: throw Exception(
        error?.let { "Baka-Tsuki: ${it.info} ($title)" } ?: "Baka-Tsuki: empty response for $title",
    )

    private fun parseRequest(title: String): Request {
        val url = apiUrl.toHttpUrl().newBuilder()
            .addQueryParameter("action", "parse")
            .addQueryParameter("page", title.removePrefix("/").replace("_", " "))
            .addQueryParameter("prop", "text")
            .addQueryParameter("format", "json")
        return GET(url.build(), headers)
    }

    override fun getMangaUrl(manga: SManga): String = pagePrefix + manga.url

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // Details and the chapter list both come from the same MediaWiki "parse" call.
        val response = client.newCall(parseRequest(manga.url)).execute()

        val updatedManga = if (fetchDetails) parseMangaDetails(response) else manga
        val updatedChapters = if (fetchChapters) parseChapterList(response) else chapters

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    private fun parseMangaDetails(response: Response): SManga {
        val result = response.parseAs<ParseResponse>().orThrow(response.request.url.queryParameter("page").orEmpty())
        val doc = Jsoup.parse(result.text.content, "$baseUrl/project/")
        return SManga.create().apply {
            title = result.title
            thumbnail_url = doc.selectFirst("img")?.absUrl("src")
                ?.replace(Regex("""(width|height)=\d+"""), "width=800")
            author = doc.select(".mw-headline").firstOrNull { it.text().contains(" by ", ignoreCase = true) }
                ?.text()?.substringAfter(" by ")?.trim()
            description = doc.selectFirst("#Story_Synopsis")?.parent()
                ?.let { heading ->
                    heading.nextElementSiblings().takeWhile { it.tagName() != "h2" }
                        .filter { it.tagName() == "p" }
                        .joinToString("\n\n") { it.text() }
                        .trim()
                }?.takeIf { it.isNotEmpty() }
        }
    }

    private fun parseChapterList(response: Response): List<SChapter> {
        val result = response.parseAs<ParseResponse>().orThrow(response.request.url.queryParameter("page").orEmpty())
        val projectTitle = result.title.replace(" ", "_")
        val doc = Jsoup.parse(result.text.content, "$baseUrl/project/")

        val chapters = mutableListOf<SChapter>()
        val seen = mutableSetOf<String>()
        var counter = 0

        doc.select("a[href*=title=]").forEach { a ->
            val chapterTitle = a.absUrl("href").toHttpUrl().queryParameter("title")
                ?: return@forEach
            if (!chapterTitle.startsWith("$projectTitle:")) return@forEach
            if (chapterTitle.contains("Illustrations", ignoreCase = true)) return@forEach
            val text = a.text().trim()
            if (!CHAPTER_REGEX.containsMatchIn(text)) return@forEach
            if (!seen.add(chapterTitle)) return@forEach

            val volume = VOLUME_REGEX.find(chapterTitle)?.groupValues?.get(1)
            counter++
            chapters.add(
                SChapter.create().apply {
                    name = if (volume != null) "Volume $volume - $text" else text
                    url = chapterTitle
                    chapter_number = counter.toFloat()
                },
            )
        }
        return chapters.asReversed()
    }

    override fun getChapterUrl(chapter: SChapter): String = pagePrefix + chapter.url.replace(" ", "_")

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.newCall(parseRequest(chapter.url)).execute()
        return listOf(Page(0, response.request.url.toString()))
    }

    override suspend fun fetchPageText(page: Page): String {
        val title = if (page.url.contains("api.php")) {
            page.url.toHttpUrl().queryParameter("page") ?: page.url
        } else {
            page.url
        }
        val result = client.newCall(parseRequest(title)).execute().parseAs<ParseResponse>().orThrow(title)
        val doc = Jsoup.parse(result.text.content, "$baseUrl/project/")
        doc.select(".wikitable, .mw-editsection, .printfooter, #toc, .toc, .navbox, .reference, style, script").remove()
        doc.select("img").forEach { img ->
            img.attr("src", img.absUrl("src").replace(Regex("""(width|height)=\d+"""), "width=800"))
        }
        return doc.selectFirst(".mw-parser-output")?.html().orEmpty()
    }

    companion object {
        private val CHAPTER_REGEX = Regex(
            """(?i)\b(prologue|chapter|epilogue|afterword|interlude|side[\s_]story|part|final)\b""",
        )
        private val VOLUME_REGEX = Regex("""Volume[_\s](\d+)""")
    }
}

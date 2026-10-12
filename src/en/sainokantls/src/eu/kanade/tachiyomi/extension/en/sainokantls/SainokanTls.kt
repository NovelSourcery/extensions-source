package eu.kanade.tachiyomi.novelextension.en.sainokantls

import android.util.Base64
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.formattedText
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.TextNode

@Source
abstract class SainokanTls :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val supportsLatest = false

    override val supportsFilterFetching = true

    private val preferences by getPreferencesLazy()

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList())

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val requestUrl = "$baseUrl/novels".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("search", query.trim())
            filters.filterIsInstance<SelectFilter>().forEach { filter ->
                filter.toValue()?.let { addQueryParameter(filter.param, it) }
            }
            addQueryParameter("page", page.toString())
        }.build()

        val document = client.get(requestUrl).asJsoup()
        val mangas = document.select("a[href*=/novel/]:has(h3)").map { card ->
            SManga.create().apply {
                url = card.absUrl("href").toHttpUrl().pathSegments.last()
                title = card.selectFirst("h3")!!.text()
                author = card.selectFirst("p.text-secondary")?.text()
                thumbnail_url = card.selectFirst("img")?.absUrl("src")
            }
        }
        return MangasPage(mangas, document.selectFirst("a[rel=next]") != null)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novel/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments
        if (segments.size < 2 || segments[0] != "novel") return null
        val slug = segments[1]
        return parseMangaDetails(client.get("$baseUrl/novel/$slug").asJsoup()).apply { this.url = slug }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return SMangaUpdate(parseMangaDetails(document), parseChapterList(document, manga.url))
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1")!!.text()
        thumbnail_url = document.selectFirst("main img[src*=/storage/covers/]")?.absUrl("src")
        author = document.infoRow("fa-user")
        val type = document.infoRow("fa-tag")
        val statusText = document.infoRow("fa-signal")
        status = when (statusText?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "on hiatus", "hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        genre = buildList {
            type?.let(::add)
            addAll(document.select("a[href*=/novels?category=]").eachText())
        }.joinToString().ifEmpty { null }

        val lockedCount = document.select(LOCKED_ROW_SELECTOR).size
        val illustrations = document.selectFirst("i.fa-images + span")?.text()
        // Synopsis uses whitespace-pre-line, so line breaks are raw newlines rather than <br>.
        val synopsis = document.selectFirst("h3:containsOwn(Synopsis) + p")?.let { p ->
            if (p.childrenSize() > 0) {
                p.formattedText()
            } else {
                p.wholeText().lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
            }
        }
        description = buildString {
            synopsis?.let { append(it).append("\n\n") }
            type?.let { append("Type: ").append(it).append('\n') }
            statusText?.let { append("Status: ").append(it).append('\n') }
            document.infoRow("fa-calendar")?.let { append(it).append('\n') }
            document.infoRow("fa-book")?.let { append(it).append('\n') }
            if (lockedCount > 0) append("Locked chapters: ").append(lockedCount).append('\n')
            illustrations?.let { append("Illustrations: ").append(it.substringBefore(' ')).append('\n') }
            document.selectFirst("span:containsOwn(followers)")?.ownText()?.substringBefore(' ')
                ?.let { append("Followers: ").append(it).append('\n') }
        }.trim()
    }

    private fun Document.infoRow(icon: String): String? = selectFirst("h1 ~ div i.$icon + span")?.text()?.ifEmpty { null }

    private fun parseChapterList(document: Document, mangaSlug: String): List<SChapter> {
        val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)
        return document.select("div[x-data*=newestFirst] div.p-3.bg-page")
            .mapIndexedNotNull { index, row ->
                val link = row.selectFirst("a[href]")
                when {
                    link != null -> SChapter.create().apply {
                        url = link.absUrl("href").toHttpUrl().pathSegments.last()
                        name = link.text()
                        chapter_number = index + 1f
                    }
                    showLocked -> SChapter.create().apply {
                        // Locked rows carry no link, so key them by position under the novel.
                        url = "$mangaSlug/$LOCKED_MARKER/${index + 1}"
                        name = "🔒 " + row.selectFirst("span.font-medium")!!.text()
                        chapter_number = index + 1f
                    }
                    else -> null
                }
            }
            .reversed()
    }

    override fun getChapterUrl(chapter: SChapter): String = if (chapter.isLockedPlaceholder()) {
        "$baseUrl/novel/${chapter.url.substringBefore('/')}"
    } else {
        "$baseUrl/${chapter.url}"
    }

    private fun SChapter.isLockedPlaceholder() = url.contains("/$LOCKED_MARKER/")

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, getChapterUrl(chapter)))

    override suspend fun fetchPageText(page: Page): String {
        if (page.url.contains("/novel/")) throw Exception(LOCKED_MESSAGE)
        val document = client.get(page.url).asJsoup()
        val content = document.selectFirst("#chapter-content") ?: throw Exception(LOCKED_MESSAGE)
        // Text is split into base64 chunks interleaved with aria-hidden decoy words.
        content.select("[aria-hidden=true]").remove()
        content.select("span.cdh-chunk[data-content]").forEach { span ->
            val text = String(Base64.decode(span.attr("data-content"), Base64.DEFAULT), Charsets.UTF_8)
            span.replaceWith(TextNode(text))
        }
        return content.html()
    }

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get("$baseUrl/novels").asJsoup()
        return FILTER_PARAMS.map { param ->
            FilterDto(
                param,
                document.select("select[name=$param] option[value]:not([value=\"\"])")
                    .map { OptionDto(it.attr("value"), it.text()) },
            )
        }.toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filters = data?.parseAs<List<FilterDto>>() ?: return FilterList()
        return FilterList(filters.map { SelectFilter(it.param, it.options) })
    }

    private class SelectFilter(val param: String, private val options: List<OptionDto>) :
        Filter.Select<String>(
            FILTER_TITLES[param] ?: param,
            arrayOf("Any") + options.map { it.name },
        ) {
        fun toValue(): String? = options.getOrNull(state - 1)?.value
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Include paywalled chapters in the chapter list (their content can't be read without unlocking them on the site)."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_SHOW_LOCKED = "show_locked_chapters"
        private const val LOCKED_MARKER = "locked"
        private const val LOCKED_MESSAGE = "This chapter is locked. Unlock it on Sainokan TLS to read it."
        private const val LOCKED_ROW_SELECTOR = "div[x-data*=newestFirst] div.p-3.bg-page.opacity-75"
        private val FILTER_PARAMS = listOf("category", "type", "status")
        private val FILTER_TITLES = mapOf("category" to "Genre", "type" to "Type", "status" to "Status")
    }
}

@Serializable
private class FilterDto(val param: String, val options: List<OptionDto>)

@Serializable
private class OptionDto(val value: String, val name: String)

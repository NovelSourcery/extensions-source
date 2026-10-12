package eu.kanade.tachiyomi.multisrc.ikennovel

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
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.intOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.stringOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode

abstract class IkenNovel :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    protected open val apiUrl: String
        get() = baseUrl.replace("https://", "https://api.")

    protected open val perPage: Int = 18

    private val preferences by getPreferencesLazy()

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter(sortOptions, "totalViews")))

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter(sortOptions)))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$apiUrl/api/query".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("perPage", perPage.toString())
            addQueryParameter("searchTerm", query.trim())
            addQueryParameter("seriesType", "NOVEL")
            filters.filterIsInstance<UrlPartFilter>().forEach { it.addUrlParameter(this) }
        }.build()

        val data = client.get(url).parseAs<SearchResponse>()
        val mangas = data.posts.filter { it.isNovel }.map { it.toSManga() }
        return MangasPage(mangas, data.totalCount > page * perPage)
    }

    override val supportsFilterFetching = true

    protected open val statusOptions: Options = listOf(
        "All" to "",
        "Ongoing" to "ONGOING",
        "Completed" to "COMPLETED",
        "Canceled" to "CANCELLED",
        "Dropped" to "DROPPED",
        "Coming Soon" to "COMING_SOON",
        "Mass Released" to "MASS_RELEASED",
    )

    protected open val sortOptions: Options = listOf(
        "Latest Update" to "lastChapterAddedAt",
        "Popularity" to "totalViews",
        "Date Added" to "createdAt",
        "Chapter Count" to "chaptersCount",
        "A-Z" to "postTitle",
    )

    override suspend fun fetchFilterData(): JsonElement = client.get("$apiUrl/api/genres").parseAs<JsonElement>()

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<List<Genre>>()?.map { it.name.trim() to it.id.toString() }.orEmpty()
        return FilterList(
            buildList<Filter<*>> {
                add(StatusFilter(statusOptions))
                add(SortFilter(sortOptions))
                add(SortDirectionFilter())
                if (genres.isNotEmpty()) {
                    add(Filter.Separator())
                    add(GenreFilter(genres))
                }
            },
        )
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != "series") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return getPost(slug).toSManga()
    }

    private suspend fun getPost(slug: String): PostResponse {
        val url = "$apiUrl/api/post".toHttpUrl().newBuilder()
            .addQueryParameter("postSlug", slug)
            .build()
        return client.get(url).parseAs<PostResponse>()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val knownId = manga.memo["id"]?.intOrNull
        val details = if (fetchDetails || knownId == null) async { getPost(manga.url) } else null
        val postId = knownId ?: details!!.await().post.id

        val updatedChapters = if (fetchChapters) {
            client.get("$apiUrl/api/chapters?postId=$postId").parseAs<ChaptersResponse>()
                .post.chapters
                .filter { showLockedChapters || !it.isLocked }
                .map { it.toSChapter(manga.url) }
        } else {
            chapters
        }

        SMangaUpdate(details?.await()?.toSManga() ?: manga, updatedChapters)
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val seriesSlug = chapter.memo["seriesSlug"]?.stringOrNull ?: return baseUrl
        val slug = chapter.memo["slug"]?.stringOrNull ?: return baseUrl
        return "$baseUrl/series/$seriesSlug/$slug"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, "$apiUrl/api/chapter?chapterId=${chapter.url}"))

    override suspend fun fetchPageText(page: Page): String {
        val chapter = client.get(page.url).parseAs<ChapterResponse>().chapter
        val content = chapter.content?.takeIf { it.isNotBlank() }
            ?: throw Exception("This chapter is locked. Unlock it on the website to read it.")

        val body = Jsoup.parseBodyFragment(content, baseUrl).body()
        // Translator intro text is prepended as plain text before the HTML paragraphs.
        body.childNodes().filterIsInstance<TextNode>().filterNot { it.isBlank() }.forEach { node ->
            node.text().trim().split(LINE_BREAK_REGEX).filter { it.isNotBlank() }.reversed().forEach {
                node.after("<p>${Entities.escape(it.trim())}</p>")
            }
            node.remove()
        }
        return body.html()
    }

    private val showLockedChapters get() = preferences.getBoolean(SHOW_LOCKED_CHAPTER_PREF_KEY, false)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = SHOW_LOCKED_CHAPTER_PREF_KEY
            title = "Show locked chapters"
            summary = "Include paywalled chapters in the chapter list (their content can't be read without unlocking them on the site)."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    companion object {
        private const val SHOW_LOCKED_CHAPTER_PREF_KEY = "pref_show_locked_chapters"
        private val LINE_BREAK_REGEX = Regex("""\n+""")
    }
}

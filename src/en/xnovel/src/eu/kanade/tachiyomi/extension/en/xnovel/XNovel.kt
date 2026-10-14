package eu.kanade.tachiyomi.novelextension.en.xnovel

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.graphQLBody
import keiyoushi.utils.parseGraphQLAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody
import org.jsoup.nodes.Entities

@Source
abstract class XNovel :
    KeiSource(),
    NovelSource {

    private val apiUrl get() = "$baseUrl/n2/"

    override suspend fun getPopularManga(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter("field_follow")))

    override suspend fun getLatestUpdates(page: Int): MangasPage = getSearchMangaList(page, "", FilterList(SortFilter()))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genres = filters.firstInstanceOrNull<GenreFilter>()
        val select = SearchSelect(
            word = query.trim(),
            page = page,
            size = PAGE_SIZE,
            sortby = filters.firstInstanceOrNull<SortFilter>()?.value ?: "field_update",
            incGenres = genres?.included.orEmpty(),
            excGenres = genres?.excluded.orEmpty(),
        )
        val result = query(graphQLBody(SEARCH_QUERY, variables = SearchVariables(select))).parseGraphQLAs<SearchData>().result
        return MangasPage(result.items.map { it.toSManga(baseUrl) }, (result.paging.next ?: 0) > page)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        Filter.Separator(),
        GenreFilter(),
    )

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val id = url.pathSegments.firstOrNull()?.let(ID_REGEX::find)?.groupValues?.get(1) ?: return null
        return fetchNovel(id)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val details = async { if (fetchDetails) fetchNovel(manga.url) else manga }
        val chapterList = async { if (fetchChapters) fetchChapters(manga.url) else chapters }
        SMangaUpdate(details.await(), chapterList.await())
    }

    private suspend fun fetchNovel(id: String): SManga = query(graphQLBody(NOVEL_QUERY, variables = IdVariables(id)))
        .parseGraphQLAs<NovelData>().node.toDetailedSManga(baseUrl)

    private suspend fun fetchChapters(novelId: String): List<SChapter> = query(graphQLBody(CHAPTER_LIST_QUERY, variables = NovelIdVariables(novelId)))
        .parseGraphQLAs<ChapterListData>().chapters
        .map { it.toSChapter(novelId) }
        .reversed()

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/${chapter.url}"

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, getChapterUrl(chapter)))

    override suspend fun fetchPageText(page: Page): String {
        val chapterId = page.url.substringAfterLast("/")
        val paragraphs = query(graphQLBody(CHAPTER_CONTENT_QUERY, variables = IdVariables(chapterId)))
            .parseGraphQLAs<ChapterContentData>().node?.data?.sources
            ?.firstNotNullOfOrNull { source -> source.data.content?.takeIf { it.isNotEmpty() } }
            ?: return ""
        return paragraphs.joinToString("") { "<p>${Entities.escape(it)}</p>" }
    }

    private suspend fun query(body: RequestBody) = client.post(apiUrl, body)

    companion object {
        private const val PAGE_SIZE = 30
        private val ID_REGEX = Regex("""^(\d+)(?:-|$)""")
    }
}

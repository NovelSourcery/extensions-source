package eu.kanade.tachiyomi.novelextension.en.freegoodnovel

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Source
abstract class FreeGoodNovel :
    KeiSource(),
    NovelSource {

    override val supportsFilterFetching = true

    private val apiUrl get() = "$baseUrl/api/collections"

    override suspend fun getPopularManga(page: Int): MangasPage = fetchNovels(page, "-views", null)

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchNovels(page, "-created", null)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val conditions = buildList {
            if (query.isNotBlank()) {
                val term = query.trim().escapeFilter()
                add("(title~\"$term\"||author~\"$term\")")
            }
            filters.firstInstanceOrNull<StatusFilter>()?.toValue()?.let { add("status=\"$it\"") }
            filters.firstInstanceOrNull<GenreFilter>()?.toValue()?.let { add("genre=\"$it\"") }
            filters.firstInstanceOrNull<TagFilter>()?.state?.forEach {
                when {
                    it.isIncluded() -> add("tags~\"${it.name.escapeFilter()}\"")
                    it.isExcluded() -> add("tags!~\"${it.name.escapeFilter()}\"")
                }
            }
        }
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toValue() ?: "-views"
        return fetchNovels(page, sort, conditions.joinToString("&&").ifEmpty { null })
    }

    private suspend fun fetchNovels(page: Int, sort: String, filter: String?): MangasPage {
        val url = "$apiUrl/novels/records".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("perPage", PER_PAGE.toString())
            .addQueryParameter("sort", sort)
            .addQueryParameter("fields", NOVEL_FIELDS)
            .apply { filter?.let { addQueryParameter("filter", it) } }
            .build()
        val data = client.get(url).parseAs<RecordsDto<NovelDto>>()
        return MangasPage(data.items.map { it.toSManga(baseUrl) }, data.page < data.totalPages)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/novel/${manga.title.toWebSlug()}/${manga.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.pathSegments.firstOrNull() != "novel") return null
        val id = url.pathSegments.lastOrNull { it.isNotEmpty() } ?: return null
        return client.get("$apiUrl/novels/records/$id", ensureSuccess = false).use { response ->
            if (response.isSuccessful) response.parseAs<NovelDto>().toSManga(baseUrl) else null
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val details = async {
            if (fetchDetails) {
                client.get("$apiUrl/novels/records/${manga.url}").parseAs<NovelDto>().toSManga(baseUrl)
            } else {
                manga
            }
        }
        val chapterList = async { if (fetchChapters) fetchChapterList(manga.url) else chapters }
        SMangaUpdate(details.await(), chapterList.await())
    }

    private suspend fun fetchChapterList(novelId: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var page = 1
        do {
            val url = "$apiUrl/chapters/records".toHttpUrl().newBuilder()
                .addQueryParameter("filter", "novel_id=\"$novelId\"")
                .addQueryParameter("fields", "chapter_no,created")
                .addQueryParameter("sort", "-chapter_no")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("perPage", MAX_PER_PAGE.toString())
                .build()
            val data = client.get(url).parseAs<RecordsDto<ChapterDto>>()
            data.items.mapTo(chapters) { it.toSChapter(novelId) }
        } while (page++ < data.totalPages)
        return chapters
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val (novelId, number) = chapter.url.split("/")
        // The reader route ignores the slug segment, only the chapter number and novel id matter.
        return "$baseUrl/novel/read/Chapter-$number/$novelId"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, getChapterUrl(chapter)))

    override suspend fun fetchPageText(page: Page): String {
        val segments = page.url.toHttpUrl().pathSegments
        val novelId = segments.last()
        val number = segments[segments.size - 2].removePrefix("Chapter-")
        val url = "$apiUrl/chapters/records".toHttpUrl().newBuilder()
            .addQueryParameter("filter", "novel_id=\"$novelId\"&&chapter_no=$number")
            .addQueryParameter("fields", "content")
            .addQueryParameter("perPage", "1")
            .build()
        val content = client.get(url).parseAs<RecordsDto<ChapterContentDto>>().items.firstOrNull()?.content
            ?: throw Exception("Chapter not found")
        return content.toParagraphHtml()
    }

    override suspend fun fetchFilterData(): JsonElement {
        val tags = mutableListOf<String>()
        var page = 1
        do {
            val url = "$apiUrl/novels/records".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("perPage", MAX_PER_PAGE.toString())
                .addQueryParameter("fields", "tags")
                .build()
            val data = client.get(url).parseAs<RecordsDto<TagsDto>>()
            data.items.flatMap { it.tags }.mapNotNullTo(tags) { it.trim().ifEmpty { null } }
        } while (page++ < data.totalPages)
        return tags.groupBy { it.lowercase() }.values
            .sortedByDescending { it.size }
            .map { it.first() }
            .toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val tags = data?.parseAs<List<String>>().orEmpty()
        return FilterList(
            buildList<Filter<*>> {
                add(SortFilter())
                add(StatusFilter())
                add(GenreFilter())
                if (tags.isNotEmpty()) add(TagFilter(tags))
            },
        )
    }

    private fun String.escapeFilter() = replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val PER_PAGE = 30
        private const val MAX_PER_PAGE = 500
        private const val NOVEL_FIELDS =
            "id,title,author,synopsis,genre,status,cover,views,readers,rating,upload_date,tags,chapter_count"
    }
}

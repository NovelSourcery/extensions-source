package eu.kanade.tachiyomi.novelextension.en.requiemtranslations

import eu.kanade.tachiyomi.source.NovelSource
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

// The website sits behind an Anubis proof-of-work wall, but its PocketBase backend is open.
@Source
abstract class RequiemTranslations :
    KeiSource(),
    NovelSource {

    override val supportsFilterFetching = true

    private val apiUrl = "https://pb.requiemtls.com/api/collections"

    override suspend fun getPopularManga(page: Int): MangasPage = fetchSeries(page, "-hot,-score", null)

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchSeries(page, "-created", null)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val conditions = buildList {
            if (query.isNotBlank()) {
                val term = query.trim().escapeFilter()
                add("(title~\"$term\"||alt_titles~\"$term\")")
            }
            filters.firstInstanceOrNull<GenreFilter>()?.selected()?.forEach { add("genres~\"$it\"") }
            filters.firstInstanceOrNull<StatusFilter>()?.toValue()?.let { add("status=\"$it\"") }
            filters.firstInstanceOrNull<R18Filter>()?.toValue()?.let { add("mature=$it") }
        }
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toValue() ?: "-created"
        return fetchSeries(page, sort, conditions.joinToString("&&").ifEmpty { null })
    }

    private suspend fun fetchSeries(page: Int, sort: String, filter: String?): MangasPage {
        val url = "$apiUrl/series/records".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("perPage", PER_PAGE.toString())
            .addQueryParameter("sort", sort)
            .addQueryParameter("fields", "id,slug,title,thumbnail")
            .apply { filter?.let { addQueryParameter("filter", it) } }
            .build()
        val data = client.get(url).parseAs<RecordsDto<SeriesDto>>()
        return MangasPage(data.items.map { it.toSManga() }, data.page < data.totalPages)
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url.toSlug()}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val slug = when {
            segments.size == 2 && segments[0] == "series" -> segments[1]
            segments.size == 1 && EPISODE_SUFFIX_REGEX.containsMatchIn(segments[0]) -> {
                val chapterUrl = "$apiUrl/chapters/records".toHttpUrl().newBuilder()
                    .addQueryParameter("filter", "slug=\"${segments[0].escapeFilter()}\"")
                    .addQueryParameter("expand", "series")
                    .addQueryParameter("fields", "expand.series.slug")
                    .build()
                client.get(chapterUrl).parseAs<RecordsDto<ChapterSeriesDto>>().items.firstOrNull()?.slug ?: return null
            }
            else -> return null
        }
        return fetchSeriesDetails(slug)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val slug = manga.url.toSlug()
        val details = async { if (fetchDetails) fetchSeriesDetails(slug) ?: throw Exception("Series not found") else manga }
        val chapterList = async { if (fetchChapters) fetchChapterList(slug) else chapters }
        SMangaUpdate(details.await(), chapterList.await())
    }

    private suspend fun fetchSeriesDetails(slug: String): SManga? {
        val url = "$apiUrl/series/records".toHttpUrl().newBuilder()
            .addQueryParameter("filter", "slug=\"${slug.escapeFilter()}\"")
            .addQueryParameter("expand", "genres,tags,writers,artists,types")
            .addQueryParameter("perPage", "1")
            .build()
        val series = client.get(url).parseAs<RecordsDto<SeriesDto>>().items.firstOrNull() ?: return null
        val chapterCount = "$apiUrl/chapters/records".toHttpUrl().newBuilder()
            .addQueryParameter("filter", "series=\"${series.id}\"")
            .addQueryParameter("fields", "id")
            .addQueryParameter("perPage", "1")
            .build()
            .let { client.get(it).parseAs<RecordsDto<ChapterDto>>().totalItems }
        return series.toSMangaDetails(chapterCount)
    }

    private suspend fun fetchChapterList(slug: String): List<SChapter> {
        val chapters = mutableListOf<ChapterDto>()
        var page = 1
        do {
            val url = "$apiUrl/chapters/records".toHttpUrl().newBuilder()
                .addQueryParameter("filter", "series.slug=\"${slug.escapeFilter()}\"")
                .addQueryParameter("fields", "chapter_number,chapter_title,slug,published_at")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("perPage", MAX_PER_PAGE.toString())
                .build()
            val data = client.get(url).parseAs<RecordsDto<ChapterDto>>()
            chapters += data.items
        } while (page++ < data.totalPages)
        // chapter_number is a text field, so the API can only sort it lexicographically.
        return chapters.sortedByDescending { it.number }.map { it.toSChapter() }
    }

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/${chapter.url.toSlug()}"

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, getChapterUrl(chapter)))

    override suspend fun fetchPageText(page: Page): String {
        val slug = page.url.toHttpUrl().pathSegments.last { it.isNotEmpty() }
        val url = "$apiUrl/chapters/records".toHttpUrl().newBuilder()
            .addQueryParameter("filter", "slug=\"${slug.escapeFilter()}\"")
            .addQueryParameter("fields", "content")
            .addQueryParameter("perPage", "1")
            .build()
        val content = client.get(url).parseAs<RecordsDto<ChapterContentDto>>().items.firstOrNull()?.content
            ?: throw Exception("Chapter not found")
        return content.markdownToHtml()
    }

    override suspend fun fetchFilterData(): JsonElement {
        val url = "$apiUrl/genres/records".toHttpUrl().newBuilder()
            .addQueryParameter("perPage", MAX_PER_PAGE.toString())
            .addQueryParameter("sort", "name")
            .addQueryParameter("fields", "id,name")
            .build()
        return client.get(url).parseAs<RecordsDto<GenreDto>>().items.toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<List<GenreDto>>().orEmpty()
        return FilterList(
            listOfNotNull(
                SortFilter(),
                StatusFilter(),
                R18Filter(),
                genres.takeIf { it.isNotEmpty() }?.let(::GenreFilter),
            ),
        )
    }

    // Older versions (WordPress theme) stored "/series/<slug>/" paths or "<slug>/".
    private fun String.toSlug() = trim('/').substringAfterLast('/')

    private fun String.escapeFilter() = replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val PER_PAGE = 24
        private const val MAX_PER_PAGE = 500
        private val EPISODE_SUFFIX_REGEX = Regex("""-episode-[\d.]+$""")
    }
}

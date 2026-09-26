package eu.kanade.tachiyomi.novelextension.ar.mtlarabic

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
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

private const val SORT_BY_LATEST = "latest_update"
private const val SORT_BY_CHAPTERS = "-num_chapters"

/**
 * مكتبة الخيال (mtlarabic.com) — an Arabic web-novel library.
 *
 * The site is a server-rendered Node app with no browse API: `/novels` and `/novel-details`
 * ship their payload as a `<script type="application/json">` island, which is what the listing
 * and detail parsing here read. The chapter list is the one exception — the site exposes
 * `/api/novels/{id}/chapters` for its own pagination widget, so chapters go through that
 * instead of being scraped.
 */
@Source
abstract class MtlArabic :
    KeiSource(),
    NovelSource {

    override val supportsLatest = true

    private companion object {
        /** The chapter endpoint's own page size; the site hardcodes 100. */
        const val CHAPTERS_PER_PAGE = 100
    }

    // Explicit instance: the islands carry many fields that are irrelevant at a given call site.
    private val json = Json { ignoreUnknownKeys = true }

    // ==================== Catalogue ====================

    // There is no popularity metric on the site, so "popular" is the longest novels: the listing
    // sorted by chapter count, which is a standing signal of a novel people stayed with.
    override suspend fun getPopularManga(page: Int): MangasPage = getNovelsPage(
        listingUrl(page, SORT_BY_CHAPTERS),
    )

    override suspend fun getLatestUpdates(page: Int): MangasPage = getNovelsPage(
        listingUrl(page, SORT_BY_LATEST),
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        // The sort filter owns `sort` outright, so the base url is built without one — a second
        // addQueryParameter("sort", …) on the same builder would be silently dropped.
        var builder = "$baseUrl/novels".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("q", query)

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> builder = builder.addQueryParameter("category", filter.selected())
                is SortFilter -> builder = builder.addQueryParameter("sort", filter.selected())
                else -> {}
            }
        }

        return getNovelsPage(builder.build())
    }

    private fun listingUrl(page: Int, sort: String = SORT_BY_LATEST): HttpUrl = "$baseUrl/novels".toHttpUrl().newBuilder()
        .addQueryParameter("page", page.toString())
        .addQueryParameter("sort", sort)
        .build()

    private suspend fun getNovelsPage(url: HttpUrl): MangasPage {
        val listing = client.get(url).parseAs<ListingResponse>(json)
        val pagination = listing.pagination
        return MangasPage(
            listing.items.map { it.toSManga() },
            pagination.currentPage < pagination.totalPages,
        )
    }

    // ==================== Details ====================

    private fun parseDetails(details: NovelDetails) = SManga.create().apply {
        url = ID_PREFIX + details.id
        title = details.name
        thumbnail_url = coverUrl(details.image)
        description = details.description
        // The site files the Chinese original title here; it is the only name besides the
        // Arabic one, and the library has no author field of its own.
        author = details.originalName
        genre = details.type
        status = mangaStatus(details.status)
    }

    private suspend fun fetchDetails(id: Int): NovelDetails = client.get(detailsUrl(id)).parseAs<NovelDetails>(json)

    private fun detailsUrl(id: Int) = "$baseUrl/novel-details?id=$id"

    private fun detailsUrl(manga: SManga) = detailsUrl(manga.novelId())

    override fun getMangaUrl(manga: SManga): String = detailsUrl(manga)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        // The novel lives at /{slug} as well, and the slug is Arabic, so an id in the query is
        // the only unambiguous marker. /novel-details?id= is what that link resolves to.
        val id = url.queryParameter("id")?.toIntOrNull() ?: return null
        return parseDetails(fetchDetails(id))
    }

    // ==================== Chapters ====================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        // One detail request serves both halves: it carries the metadata and, through
        // totalChapters, how many chapter pages there are to walk.
        val details = fetchDetails(manga.novelId())

        return SMangaUpdate(
            if (fetchDetails) parseDetails(details) else manga,
            if (fetchChapters) fetchChapterList(details) else chapters,
        )
    }

    /**
     * The detail page embeds only the first 100 chapters and ignores `?page=`, so anything past
     * that is walked through the endpoint the site's own pager uses.
     */
    private suspend fun fetchChapterList(details: NovelDetails): List<SChapter> {
        val firstPage = fetchChapterPage(details.id, 1)
        val chapters = mutableListOf<SChapter>()

        firstPage.chapters.mapTo(chapters) { it.toSChapter(details.slug) }
        for (page in 2..firstPage.pagination.totalPages.coerceAtLeast(1)) {
            fetchChapterPage(details.id, page).chapters.mapTo(chapters) { it.toSChapter(details.slug) }
        }

        return chapters.sortedByDescending { it.chapter_number }
    }

    private suspend fun fetchChapterPage(id: Int, page: Int): ChaptersResponse = client.get(
        "$baseUrl/api/novels/$id/chapters?page=$page&limit=$CHAPTERS_PER_PAGE&sort=asc",
    ).parseAs(json)

    // ==================== Reader ====================

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val chapter = client.get(baseUrl + page.url).parseAs<ChapterPage>(json)
        // The reader island stores the body as plain text rather than markup, one paragraph per
        // line, with the lines separated by blank lines. The blanks are the site's own spacing and
        // are dropped here so they don't compound with the separator below.
        return chapter.content
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
    }

    // ==================== Filters ====================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        GenreFilter(),
        SortFilter(),
    )

    private fun SManga.novelId(): Int = url.removePrefix(ID_PREFIX).toInt()

    /** The site's own category select, plus the "all" entry it sends as an empty value. */
    private class GenreFilter :
        Filter.Select<String>(
            "التصنيف",
            arrayOf("الكل", "تاريخي", "حضري", "خيال", "خيال علمي", "رياضي"),
        ) {
        fun selected() = if (state == 0) "" else values[state]
    }

    /**
     * The site renders exactly these two sorts, and nothing else: any other value is accepted in
     * the query string and then quietly ignored, leaving the default listing back.
     */
    private class SortFilter :
        Filter.Select<String>(
            "الترتيب",
            arrayOf("آخر تحديث", "عدد الفصول - من أعلى لأقل"),
        ) {
        fun selected() = if (state == 0) SORT_BY_LATEST else SORT_BY_CHAPTERS
    }
}

package eu.kanade.tachiyomi.extension.zh.qimao

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
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
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * qimao.com (七猫中文网) is a Nuxt.js app whose book-detail page embeds a minified, hard-to-parse
 * `window.__NUXT__` IIFE payload - but its own `/api/book-detail/` routes, `/api/book/chapter-list`, and
 * `/api/search/result` routes are open, unauthenticated, and return plain JSON, so those are used
 * directly instead. Chapter *text*, by contrast, genuinely is server-rendered - it's just absent
 * from the __NUXT__ data object itself; the reading page's real HTML (`div.chapter-detail-article`)
 * has it, confirmed live, no API needed.
 *
 * No working "browse everything" or ranking endpoint could be found (`/api/rank/book-list` and
 * `/api/classify/book-list` exist but their required params couldn't be determined); popular
 * instead surfaces the fixed 10-item recommendation list search always returns alongside results.
 */
@Source
abstract class Qimao :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val supportsLatest = false

    private val preferences by getPreferencesLazy()

    // ======================== Popular / Search ========================

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)
        val data = client.get("$baseUrl/api/search/result?keyword=&page=1", headers).parseAs<SearchResponse>().data
        return MangasPage(data.doYouLikeList.map { it.toSManga() }, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/api/search/result".toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .addQueryParameter("page", page.toString())
            .build()
        val data = client.get(url, headers).parseAs<SearchResponse>().data

        val count = data.pageData.count.toIntOrNull() ?: 0
        val pageSize = data.pageData.pageSize.toIntOrNull() ?: data.searchList.size
        val hasNextPage = pageSize > 0 && page * pageSize < count

        return MangasPage(data.searchList.map { it.toSManga() }, hasNextPage)
    }

    // ======================== Details + Chapters ========================

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/shuku/${manga.url}/"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val bookId = manga.url

        val updatedManga = if (fetchDetails) {
            val detailDeferred = async { client.get("$baseUrl/api/book-detail/main-info?book_id=$bookId", headers).parseAs<MainInfoResponse>().data.bookDetail }
            val introDeferred = async { fetchIntro(bookId) }
            buildSManga(bookId, detailDeferred.await(), introDeferred.await())
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            val showLocked = preferences.getBoolean(PREF_SHOW_LOCKED, false)
            client.get("$baseUrl/api/book/chapter-list?book_id=$bookId", headers).parseAs<ChapterListResponse>().data.chapters
                .mapNotNull { it.toSChapter(bookId, showLocked) }
                .reversed()
        } else {
            chapters
        }

        SMangaUpdate(updatedManga, updatedChapters)
    }

    private suspend fun fetchIntro(bookId: String): String? = runCatching {
        client.get("$baseUrl/api/book-detail/intro?book_id=$bookId", headers).parseAs<IntroResponse>().data.intro
    }.getOrNull()

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val bookId = url.pathSegments.getOrNull(1)?.substringBefore("-") ?: return null
        val response = client.get("$baseUrl/api/book-detail/main-info?book_id=$bookId", headers, ensureSuccess = false)
        if (!response.isSuccessful) return null
        val detail = response.parseAs<MainInfoResponse>().data.bookDetail
        return buildSManga(bookId, detail, fetchIntro(bookId))
    }

    private fun buildSManga(bookId: String, detail: BookDetailDto, intro: String?): SManga = SManga.create().apply {
        url = bookId
        title = detail.title
        author = detail.author
        thumbnail_url = detail.imageLink
        description = intro
        genre = listOfNotNull(detail.category1, detail.category2).distinct().joinToString()
        status = when (detail.isOver) {
            "1" -> SManga.COMPLETED
            "0" -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    // ======================== Pages ========================

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.get(baseUrl + page.url, headers).asJsoup()
        val content = doc.selectFirst("div.chapter-detail-article div.article")
            ?: doc.selectFirst("div.chapter-detail-article")
            ?: throw Exception("This chapter is locked. Unlock it on the website, or disable \"Show locked chapters\".")
        return content.html()
    }

    // ======================== Preferences ========================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Include premium/locked chapters in the chapter list."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    // ======================== DTOs ========================

    @Serializable
    private class SearchResponse(val data: SearchData)

    @Serializable
    private class SearchData(
        @SerialName("page_data") val pageData: PageDataDto,
        @SerialName("search_list") val searchList: List<SearchItemDto> = emptyList(),
        @SerialName("do_you_like_list") val doYouLikeList: List<SearchItemDto> = emptyList(),
    )

    @Serializable
    private class PageDataDto(val count: String, @SerialName("page_size") val pageSize: String)

    @Serializable
    private class SearchItemDto(
        @SerialName("book_id") val bookId: String,
        val title: String,
        val author: String? = null,
        val intro: String? = null,
        @SerialName("image_link") val imageLink: String? = null,
        @SerialName("category2_name") val category2: String? = null,
    ) {
        fun toSManga(): SManga {
            val itemTitle = title
            val itemAuthor = author
            val itemIntro = intro
            val itemGenre = category2

            return SManga.create().apply {
                url = bookId
                title = itemTitle
                author = itemAuthor
                thumbnail_url = imageLink
                description = itemIntro
                genre = itemGenre
            }
        }
    }

    @Serializable
    private class MainInfoResponse(val data: MainInfoData)

    @Serializable
    private class MainInfoData(@SerialName("book_detail") val bookDetail: BookDetailDto)

    @Serializable
    private class BookDetailDto(
        val title: String,
        val author: String? = null,
        @SerialName("image_link") val imageLink: String? = null,
        @SerialName("category_1_name") val category1: String? = null,
        @SerialName("category_2_name") val category2: String? = null,
        @SerialName("is_over") val isOver: String? = null,
    )

    @Serializable
    private class IntroResponse(val data: IntroData)

    @Serializable
    private class IntroData(val intro: String? = null)

    @Serializable
    private class ChapterListResponse(val data: ChapterListData)

    @Serializable
    private class ChapterListData(val chapters: List<ChapterDto> = emptyList())

    @Serializable
    private class ChapterDto(
        val id: String,
        val title: String,
        @SerialName("is_vip") val isVip: String,
        @SerialName("update_time") val updateTime: String? = null,
        val index: String,
    ) {
        fun toSChapter(bookId: String, showLocked: Boolean): SChapter? {
            val locked = isVip == "1"
            if (locked && !showLocked) return null
            val chapterTitle = title

            return SChapter.create().apply {
                url = "/shuku/$bookId-$id/"
                name = if (locked) "🔒 $chapterTitle" else chapterTitle
                chapter_number = index.toFloatOrNull() ?: -1f
                date_upload = updateTime?.toLongOrNull()?.times(1000L) ?: 0L
            }
        }
    }

    companion object {
        private const val PREF_SHOW_LOCKED = "pref_show_locked_chapters"
    }
}

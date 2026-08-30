package eu.kanade.tachiyomi.novelextension.zh.qimao

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

@Source
abstract class Qimao :
    KeiSource(),
    NovelSource,
    ConfigurableSource {

    override val supportsLatest = false

    private val preferences by getPreferencesLazy()

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
            detailDeferred.await().toSManga(bookId, introDeferred.await())
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
        return detail.toSManga(bookId, fetchIntro(bookId))
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = listOf(Page(0, chapter.url))

    override suspend fun fetchPageText(page: Page): String {
        val doc = client.get(baseUrl + page.url, headers).asJsoup()
        val content = doc.selectFirst("div.chapter-detail-article div.article")
            ?: throw Exception("This chapter is locked. Unlock it on the website, or disable \"Show locked chapters\".")
        return content.html()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_LOCKED
            title = "Show locked chapters"
            summary = "Include premium/locked chapters in the chapter list."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

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
        @SerialName("book_id") private val bookId: String,
        private val title: String,
        private val author: String? = null,
        private val intro: String? = null,
        @SerialName("image_link") private val imageLink: String? = null,
        @SerialName("category2_name") private val category2: String? = null,
    ) {
        fun toSManga(): SManga = SManga.create().apply {
            url = bookId
            title = this@SearchItemDto.title
            author = this@SearchItemDto.author
            thumbnail_url = imageLink
            description = intro
            genre = category2
        }
    }

    @Serializable
    private class MainInfoResponse(val data: MainInfoData)

    @Serializable
    private class MainInfoData(@SerialName("book_detail") val bookDetail: BookDetailDto)

    @Serializable
    private class BookDetailDto(
        private val title: String,
        private val author: String? = null,
        @SerialName("image_link") private val imageLink: String? = null,
        @SerialName("category_1_name") private val category1: String? = null,
        @SerialName("category_2_name") private val category2: String? = null,
        @SerialName("is_over") private val isOver: String? = null,
    ) {
        fun toSManga(bookId: String, intro: String?): SManga = SManga.create().apply {
            url = bookId
            title = this@BookDetailDto.title
            author = this@BookDetailDto.author
            thumbnail_url = imageLink
            description = intro
            genre = listOfNotNull(category1, category2).distinct().joinToString()
            status = when (isOver) {
                "1" -> SManga.COMPLETED
                "0" -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

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
        private val id: String,
        private val title: String,
        @SerialName("is_vip") private val isVip: String,
        @SerialName("update_time") private val updateTime: String? = null,
        private val index: String,
    ) {
        fun toSChapter(bookId: String, showLocked: Boolean): SChapter? {
            val locked = isVip == "1"
            if (locked && !showLocked) return null

            return SChapter.create().apply {
                url = "/shuku/$bookId-$id/"
                name = if (locked) "🔒 $title" else title
                chapter_number = index.toFloatOrNull() ?: -1f
                date_upload = updateTime?.toLongOrNull()?.times(1000L) ?: 0L
            }
        }
    }

    companion object {
        private const val PREF_SHOW_LOCKED = "pref_show_locked_chapters"
    }
}

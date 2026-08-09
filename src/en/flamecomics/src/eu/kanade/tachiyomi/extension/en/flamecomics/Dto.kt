package eu.kanade.tachiyomi.novelextension.en.flamecomics

import kotlinx.serialization.Serializable

@Serializable
class BrowsePageData(val pageProps: BrowseProps)

@Serializable
class BrowseProps(val series: List<NovelListItem>)

@Serializable
class NovelListItem(
    val novel_id: Int? = null,
    val title: String,
    val type: String,
    val cover: String? = null,
    val likes: Int? = null,
    val last_edit: Long? = null,
)

@Serializable
class NovelDetailsPageData(val pageProps: NovelDetailsProps)

@Serializable
class NovelDetailsProps(
    val novels: NovelDetails,
    val chapters: List<NovelChapterItem>,
)

@Serializable
class NovelDetails(
    val novel_id: Int,
    val title: String,
    val altTitles: List<String>? = null,
    val description: String? = null,
    val tags: List<String>? = null,
    val author: List<String>? = null,
    val artist: List<String>? = null,
    val status: String? = null,
    val cover: String? = null,
    val last_edit: Long? = null,
)

@Serializable
class NovelChapterItem(
    val chapter_id: Int,
    val novel_id: Int,
    val chapter: String,
    val title: String? = null,
    val release_date: Long,
    val token: String,
)

@Serializable
class ChapterContentPageData(val pageProps: ChapterContentProps)

@Serializable
class ChapterContentProps(val chapter: ChapterContent)

@Serializable
class ChapterContent(
    val chapter_title: String? = null,
    val content: String,
)

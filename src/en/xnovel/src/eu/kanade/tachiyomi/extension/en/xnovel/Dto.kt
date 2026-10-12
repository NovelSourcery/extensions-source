package eu.kanade.tachiyomi.novelextension.en.xnovel

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.setAltTitles
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class SearchData(@SerialName("get_searchNovel") val result: SearchResult)

@Serializable
class SearchResult(val paging: Paging, val items: List<NovelNode>)

@Serializable
class Paging(val next: Int? = null)

@Serializable
class NovelData(@SerialName("get_NovelsNode") val node: NovelNode)

@Serializable
class NovelNode(private val id: String, private val data: Novel) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        url = id
        title = data.title
        thumbnail_url = data.cover?.let { baseUrl + it }
    }

    fun toDetailedSManga(baseUrl: String) = toSManga(baseUrl).apply { data.fillDetails(this) }
}

@Serializable
class Novel(
    @SerialName("N_title") val title: String,
    @SerialName("urlCoverOri") val cover: String? = null,
    @SerialName("info_descText") private val synopsis: String? = null,
    @SerialName("info_meta_AlternativeNames") private val altNames: List<String>? = null,
    @SerialName("info_meta_Author") private val authors: List<String>? = null,
    @SerialName("info_meta_Genre") private val genres: List<String>? = null,
    @SerialName("info_meta_Tag") private val tags: List<String>? = null,
    @SerialName("info_meta_Status") private val status: String? = null,
    private val rate: Float? = null,
    private val follows: Int? = null,
    private val reviews: Int? = null,
    @SerialName("comments_total") private val comments: Int? = null,
    private val views: List<ViewCount>? = null,
) {
    fun fillDetails(manga: SManga) = manga.apply {
        author = authors?.joinToString()?.ifEmpty { null }
        genre = buildList {
            genres?.forEach { add(GENRES[it] ?: it.toTitleCase()) }
            tags?.forEach { add(it.toTitleCase()) }
        }.distinct().joinToString().ifEmpty { null }
        status = when (this@Novel.status?.lowercase()) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled", "dropped" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }

        val alternatives = altNames.orEmpty().filter { it.isNotBlank() }
        setAltTitles(alternatives)
        description = buildString {
            synopsis?.trim()?.removeSuffix("---")?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it).append("\n\n") }
            rate?.takeIf { it > 0f }?.let { append("Rating: ").append(it).append("/10\n") }
            views?.sumOf { it.count }?.takeIf { it > 0 }?.let { append("Views: ").append(it).append("\n") }
            follows?.takeIf { it > 0 }?.let { append("Follows: ").append(it).append("\n") }
            reviews?.takeIf { it > 0 }?.let { append("Reviews: ").append(it).append("\n") }
            comments?.takeIf { it > 0 }?.let { append("Comments: ").append(it).append("\n") }
            if (alternatives.isNotEmpty()) {
                append("\nAlternative Titles:\n")
                alternatives.forEach { append("• ").append(it).append("\n") }
            }
        }.trim()
    }
}

@Serializable
class ViewCount(val count: Int)

@Serializable
class ChapterListData(@SerialName("get_AllnovelChapterList") val chapters: List<ChapterNode>)

@Serializable
class ChapterNode(private val data: Chapter) {
    fun toSChapter(novelId: String) = SChapter.create().apply {
        url = "$novelId/${data.id}"
        name = data.title
        chapter_number = data.index
        date_upload = data.created ?: 0L
    }
}

@Serializable
class Chapter(
    @SerialName("ch_id") val id: String,
    @SerialName("ch_index") val index: Float,
    @SerialName("ch_title") val title: String,
    @SerialName("date_create") val created: Long? = null,
)

@Serializable
class ChapterContentData(@SerialName("get_ChaptersNode") val node: ChapterContentNode? = null)

@Serializable
class ChapterContentNode(val data: ChapterContent)

@Serializable
class ChapterContent(@SerialName("sourcesNode_list") val sources: List<ChapterSourceNode>)

@Serializable
class ChapterSourceNode(val data: ChapterSource)

@Serializable
class ChapterSource(val content: List<String>? = null)

private fun String.toTitleCase(): String = split('_', '-', ' ')
    .filter { it.isNotEmpty() }
    .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

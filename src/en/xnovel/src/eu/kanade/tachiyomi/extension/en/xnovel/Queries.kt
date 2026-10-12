package eu.kanade.tachiyomi.novelextension.en.xnovel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class SearchVariables(val select: SearchSelect)

@Serializable
class SearchSelect(
    val word: String,
    val page: Int,
    val size: Int,
    val sortby: String,
    val incGenres: List<String>,
    val excGenres: List<String>,
)

@Serializable
class IdVariables(val id: String)

@Serializable
class NovelIdVariables(@SerialName("N_id") val novelId: String)

val SEARCH_QUERY = $$"""
    query get_searchNovel($select: SearchNovel_Select) {
        get_searchNovel(select: $select) {
            paging { next }
            items {
                id
                data {
                    N_title
                    urlCoverOri
                }
            }
        }
    }
"""

val NOVEL_QUERY = $$"""
    query get_NovelsNode($id: String!) {
        get_NovelsNode(id: $id) {
            id
            data {
                N_title
                urlCoverOri
                info_descText
                info_meta_AlternativeNames
                info_meta_Author
                info_meta_Genre
                info_meta_Tag
                info_meta_Status
                rate
                follows
                reviews
                comments_total
                views { count }
            }
        }
    }
"""

val CHAPTER_LIST_QUERY = $$"""
    query get_AllnovelChapterList($N_id: String!) {
        get_AllnovelChapterList(N_id: $N_id) {
            data {
                ch_id
                ch_index
                ch_title
                date_create
            }
        }
    }
"""

val CHAPTER_CONTENT_QUERY = $$"""
    query get_ChaptersNode($id: String!) {
        get_ChaptersNode(id: $id) {
            data {
                sourcesNode_list {
                    data {
                        content
                    }
                }
            }
        }
    }
"""

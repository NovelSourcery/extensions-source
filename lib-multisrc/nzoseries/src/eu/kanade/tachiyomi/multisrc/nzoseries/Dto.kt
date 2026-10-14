package eu.kanade.tachiyomi.multisrc.nzoseries

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class BrowseResponseDto(
    val success: Boolean,
    val data: JsonElement,
)

@Serializable
class BrowseDataDto(
    val pages: Int,
    val posts: List<PostDto>,
)

@Serializable
class PostDto(
    val title: String,
    val thumbnail: String? = null,
    val permalink: String,
)

@Serializable
class TocResponseDto(
    val data: List<TocChapterDto>,
)

@Serializable
class TocChapterDto(
    val title: String,
    val url: String,
    @SerialName("is_locked") val isLocked: Boolean = false,
    val date: String? = null,
)

@Serializable
class ContentRequestDto(
    @SerialName("captcha_response") val captchaResponse: String,
)

@Serializable
class ContentErrorDto(
    val message: String? = null,
    val captcha: Boolean = false,
)

@Serializable
class FilterDataDto(
    val orders: List<FilterOptionDto>,
    val statuses: List<FilterOptionDto>,
    val genres: List<FilterOptionDto>,
    val tags: List<FilterOptionDto>,
)

@Serializable
class FilterOptionDto(val value: String, val name: String)

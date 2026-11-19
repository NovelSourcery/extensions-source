package eu.kanade.tachiyomi.novelextension.en.lupussensei

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class FeedDto(
    val feed: FeedContentDto,
)

@Serializable
class FeedContentDto(
    val entry: List<FeedEntryDto>? = null,
)

@Serializable
class FeedEntryDto(
    val link: List<FeedLinkDto> = emptyList(),
    @SerialName("media\$thumbnail") val thumbnail: FeedThumbnailDto? = null,
)

@Serializable
class FeedLinkDto(
    val rel: String? = null,
    val href: String? = null,
)

@Serializable
class FeedThumbnailDto(
    val url: String,
)

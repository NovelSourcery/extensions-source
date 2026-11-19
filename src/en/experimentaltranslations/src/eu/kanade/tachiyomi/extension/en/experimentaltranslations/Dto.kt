package eu.kanade.tachiyomi.novelextension.en.experimentaltranslations

import kotlinx.serialization.Serializable

@Serializable
class WpEntryDto(
    val slug: String,
    val link: String,
    val date: String? = null,
    val title: RenderedDto,
    val content: RenderedDto? = null,
)

@Serializable
class RenderedDto(
    val rendered: String,
)

@Serializable
class WpContentDto(
    val content: RenderedDto? = null,
)

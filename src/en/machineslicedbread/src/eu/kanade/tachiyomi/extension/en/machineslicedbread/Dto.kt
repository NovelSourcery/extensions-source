package eu.kanade.tachiyomi.novelextension.en.machineslicedbread

import kotlinx.serialization.Serializable

@Serializable
class WpPageDto(
    val link: String,
    val date: String? = null,
    val title: RenderedDto,
)

@Serializable
class RenderedDto(
    val rendered: String,
)

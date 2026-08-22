package eu.kanade.tachiyomi.novelextension.en.dragonholic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class ChaptersResponse(
    val success: Boolean = false,
    val chapters: List<ChapterItemDto> = emptyList(),
)

@Serializable
class ChapterItemDto(
    val name: String,
    val slug: String,
    @SerialName("chapter_order") val chapterOrder: String? = null,
    @SerialName("is_premium") val isPremium: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
)

/** A single browse-filter choice, as emitted by the site's Alpine.js filter widgets. */
@Serializable
class FilterOption(
    val label: String,
    val slug: String? = null,
    val value: String? = null,
) {
    /** The value to submit for this option - `slug` for genre/tag checkboxes, `value` for the translator select. */
    val id: String get() = slug ?: value.orEmpty()
}

/**
 * Cached remote filter data (per [keiyoushi.source.KeiSource.fetchFilterData]/[keiyoushi.source.KeiSource.getFilterList]).
 * Tags aren't included here: the site's tag cloud has 12,000+ entries (auto-generated, not a
 * curated taxonomy) - far too many to enumerate as checkboxes, so tag filtering is a free-text
 * slug field instead (see `TagFilter` in Dragonholic.kt).
 */
@Serializable
class FilterData(
    val genres: List<FilterOption> = emptyList(),
    val translators: List<FilterOption> = emptyList(),
)

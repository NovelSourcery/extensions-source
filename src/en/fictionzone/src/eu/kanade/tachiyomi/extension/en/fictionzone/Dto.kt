package eu.kanade.tachiyomi.novelextension.en.fictionzone

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
class ApiEnvelope<T>(
    val success: Boolean? = null,
    val message: String? = null,
    val data: T? = null,
)

@Serializable
class Pagination(
    val page: Int? = null,
    @SerialName("total_pages") val totalPages: Int? = null,
    @SerialName("has_next") val hasNext: Boolean? = null,
)

@Serializable
class BrowseData(
    val novels: List<NovelSummaryDto> = emptyList(),
    // AI search reuses the same envelope shape but under "results" instead of "novels".
    val results: List<AiSearchResultDto>? = null,
    val pagination: Pagination? = null,
)

@Serializable
class NovelSummaryDto(
    val title: String = "",
    val slug: String? = null,
    @SerialName("source_key") val sourceKey: String? = null,
    @SerialName("source_id") val sourceId: String? = null,
    val synopsis: String? = null,
    val image: String? = null,
    @SerialName("cover_image") val coverImage: String? = null,
)

@Serializable
class AiSearchResultDto(
    val title: String? = null,
    val slug: String = "",
    val synopsis: String? = null,
    @SerialName("cover_image_url") val coverImageUrl: String? = null,
)

@Serializable
class AiSearchRequestDto(val query: String, val limit: Int, val offset: Int)

// Envelope the api-party proxy expects for every browse/details/chapters/content call.
// headers is a list of [name, value] pairs (not a JSON object) because that's the shape
// the endpoint requires.
@Serializable
class ApiPartyRequestDto(
    val path: String,
    val query: Map<String, String>? = null,
    val headers: List<List<String>>,
    val method: String,
    val body: JsonElement? = null,
)

// The details endpoint has been observed to return the novel flatly under "data"; some other
// path apparently nests it under "data.novel" (hence the wrapper), but that shape has never been
// directly observed against the live API (it requires omniportal auth this session didn't have),
// so it's kept as a fallback rather than asserted.
@Serializable
class NovelDetailsWrapperDto(val novel: NovelDetailsDto? = null)

@Serializable
class NovelDetailsDto(
    val title: String = "",
    @SerialName("alt_titles") val altTitles: List<String> = emptyList(),
    val synopsis: String? = null,
    // Elements have been observed as {"id":.., "name":..} objects; kept as JsonElement (not a
    // fixed NameDto) because the omniportal source of this same field has, per prior defensive
    // code here, sometimes sent bare strings instead - a shape this session couldn't verify.
    val genres: List<JsonElement> = emptyList(),
    val tags: List<JsonElement> = emptyList(),
    // Observed as a JSON int (e.g. 2) on the platform endpoint; kept as JsonElement since the
    // pre-existing code also handled a string form ("ongoing"/"completed"), presumably for
    // omniportal responses.
    val status: JsonElement? = null,
    val image: String? = null,
    @SerialName("cover_image") val coverImage: String? = null,
    val author: String? = null,
    val contributors: List<ContributorDto> = emptyList(),
)

@Serializable
class ContributorDto(@SerialName("display_name") val displayName: String? = null)

fun JsonElement.nameOrSelf(): String? = runCatching {
    (this as? kotlinx.serialization.json.JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
        ?: jsonPrimitive.contentOrNull
}.getOrNull()

@Serializable
class ChapterListData(
    val chapters: List<ChapterDto> = emptyList(),
    val pagination: Pagination? = null,
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("source_key") val sourceKey: String? = null,
)

@Serializable
class ChapterDto(
    val title: String = "",
    @SerialName("chapter_id") val chapterId: String = "",
    @SerialName("published_date") val publishedDate: String? = null,
    @SerialName("chapter_number") val chapterNumber: Double? = null,
)

@Serializable
class ChapterContentDto(val content: String = "")

// Genres/tags/sources cache entries: id has been observed as a JSON number (genres/tags) - kept
// as JsonElement rather than String since a numeric id would fail to decode into a String field
// under strict (non-lenient) JSON settings.
@Serializable
class NamedIdDto(val id: JsonElement? = null, val name: String = "") {
    fun idString(): String = id?.jsonPrimitive?.contentOrNull ?: ""
}

@Serializable
class SourcesData(val sources: List<NamedIdDto> = emptyList())

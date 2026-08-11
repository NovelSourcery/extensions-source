package eu.kanade.tachiyomi.novelextension.en.bakatsuki

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class CategoryResponse(
    val query: CategoryQuery,
    @SerialName("continue") val continueData: ContinueData? = null,
)

@Serializable
class CategoryQuery(
    val categorymembers: List<CategoryMember>,
)

@Serializable
class CategoryMember(
    val title: String,
)

@Serializable
class ContinueData(
    val cmcontinue: String? = null,
)

@Serializable
class ParseResponse(
    val parse: ParseData? = null,
    val error: ApiError? = null,
)

@Serializable
class ApiError(
    val code: String,
    val info: String,
)

@Serializable
class ParseData(
    val title: String,
    val text: ParseText,
)

@Serializable
class ParseText(
    @SerialName("*") val content: String,
)

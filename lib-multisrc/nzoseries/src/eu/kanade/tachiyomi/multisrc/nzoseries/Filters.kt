package eu.kanade.tachiyomi.multisrc.nzoseries

import eu.kanade.tachiyomi.source.model.Filter

open class SelectFilter(
    name: String,
    val param: String,
    private val options: List<FilterOptionDto>,
) : Filter.Select<String>(name, options.map { it.name }.toTypedArray()) {
    val value: String get() = options[state].value
}

class SortFilter(options: List<FilterOptionDto>) : SelectFilter("Order by", "orderBy", options)

class StatusFilter(options: List<FilterOptionDto>) : SelectFilter("Status", "status", options)

class TriStateOption(name: String, val id: String) : Filter.TriState(name)

open class TriStateGroup(
    name: String,
    private val param: String,
    options: List<FilterOptionDto>,
) : Filter.Group<TriStateOption>(name, options.map { TriStateOption(it.name, it.value) }) {
    fun addParams(params: MutableList<Pair<String, String>>) {
        state.forEach {
            when {
                it.isIncluded() -> params += "include$param[]" to it.id
                it.isExcluded() -> params += "exclude$param[]" to it.id
            }
        }
    }
}

class GenreFilter(options: List<FilterOptionDto>) : TriStateGroup("Genres", "Genres", options)

class TagFilter(options: List<FilterOptionDto>) : TriStateGroup("Tags", "Tags", options)

class TagsModeFilter : Filter.CheckBox("Match any tag (OR instead of AND)")

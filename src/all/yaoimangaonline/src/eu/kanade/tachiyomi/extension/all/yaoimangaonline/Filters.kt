
@file:Suppress("SpellCheckingInspection")

package eu.kanade.tachiyomi.extension.all.yaoimangaonline

import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.serialization.Serializable

@Serializable
class YaoiFilterOption(
    val name: String,
    val value: String,
)

@Serializable
class YaoiFilterData(
    val types: List<YaoiFilterOption>,
    val doujinshi: List<YaoiFilterOption>,
    val tags: List<YaoiFilterOption>,
)

class TypeFilter(
    values: List<YaoiFilterOption> = emptyList(),
) : Filter.Select<String>(
    "Type",
    (listOf("ALL") + values.map { it.name }).toTypedArray(),
) {
    private val vals = listOf("") + values.map { it.value }
    override fun toString() = vals[state].trim()
}

class DoujinshiFilter(
    values: List<YaoiFilterOption> = emptyList(),
) : Filter.Select<String>(
    "Doujinshi",
    (listOf("ALL") + values.map { it.name }).toTypedArray(),
) {
    private val vals = listOf("") + values.map { it.value }
    override fun toString() = vals[state].trim()
}

class TagFilter(
    values: List<YaoiFilterOption> = emptyList(),
) : Filter.Select<String>(
    "Tag",
    (listOf("ALL") + values.map { it.name }).toTypedArray(),
) {
    private val vals = listOf("") + values.map { it.value }
    override fun toString() = vals[state].trim()
}


package eu.kanade.tachiyomi.extension.all.yaoime

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Serializable
class FilterOption(
    val name: String,
    val parameter: String,
    val value: String,
)

@Serializable
class FilterGroupData(
    val name: String,
    val options: List<FilterOption>,
)

@Serializable
class NumberFilterData(
    val name: String,
    val parameter: String,
)

@Serializable
class YaoiFilterData(
    val groups: List<FilterGroupData>,
    val numbers: List<NumberFilterData>,
)

class CheckboxFilterOption(
    name: String,
    val parameter: String,
    val value: String,
) : Filter.CheckBox(name)

class DynamicCheckboxGroupFilter(
    name: String,
    options: List<FilterOption>,
) : Filter.Group<CheckboxFilterOption>(
    name,
    options.map {
        CheckboxFilterOption(
            it.name,
            it.parameter,
            it.value,
        )
    },
) {
    val selected: List<Pair<String, String>>
        get() = state
            .filter { it.state }
            .map { it.parameter to it.value }
}

class DynamicSelectFilter(
    name: String,
    private val options: List<FilterOption>,
) : Filter.Select<String>(
    name,
    (listOf("All") + options.map { it.name }).toTypedArray(),
) {
    val selected: Pair<String, String>?
        get() = options.getOrNull(state - 1)?.let {
            it.parameter to it.value
        }
}

class DynamicNumberFilter(
    name: String,
    val parameter: String,
) : Filter.Text(name)

fun Document.extractYaoiFilters(): YaoiFilterData {
    val form = selectFirst("form[action='/browse']")
        ?: error("Yaoi.me browse filter form not found")

    val container = form.parent()
        ?: error("Yaoi.me filter container not found")

    val groups = mutableListOf<FilterGroupData>()

    container.children()
        .filter { it.tagName() == "div" }
        .forEach { section ->
            val heading = section.children()
                .firstOrNull()
                ?.ownText()
                ?.substringBefore(":")
                ?.trim()
                .orEmpty()

            if (heading.isBlank()) {
                return@forEach
            }

            val options = extractFilterOptions(section)

            if (options.isEmpty()) {
                return@forEach
            }

            if (options.any { it.parameter == "tags" }) {
                section.children()
                    .filter {
                        it.select("a[href*='tags=']").isNotEmpty()
                    }
                    .forEach { category ->
                        val name = category
                            .selectFirst("div[title]")
                            ?.ownText()
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: heading

                        val categoryOptions = extractFilterOptions(category)
                            .filter { it.parameter == "tags" }

                        if (categoryOptions.isNotEmpty()) {
                            groups.add(
                                FilterGroupData(
                                    name = "$heading - $name",
                                    options = categoryOptions,
                                ),
                            )
                        }
                    }
            } else {
                groups.add(
                    FilterGroupData(
                        name = heading,
                        options = options,
                    ),
                )
            }
        }

    val numbers = form
        .select("input[type=number][name]")
        .map { input ->
            val label = input.parent()

            val heading = label
                ?.selectFirst("span")
                ?.text()
                ?.trim()
                .orEmpty()

            val placeholder = input.attr("placeholder")
                .ifBlank { input.attr("name") }

            val name = if (heading.isBlank()) {
                placeholder
            } else {
                "$heading - $placeholder"
            }

            NumberFilterData(
                name = name,
                parameter = input.attr("name"),
            )
        }
        .distinctBy { it.parameter }

    check(groups.isNotEmpty()) {
        "Yaoi.me filter options not found"
    }

    return YaoiFilterData(
        groups = groups,
        numbers = numbers,
    )
}

private fun Document.extractFilterOptions(
    section: Element,
): List<FilterOption> {
    val base = location().toHttpUrlOrNull()
        ?: return emptyList()

    return section
        .select("a[href]")
        .mapNotNull { anchor ->
            val url = base.resolve(anchor.attr("href"))
                ?: return@mapNotNull null

            val parameter = url.queryParameterNames
                .firstOrNull {
                    it !in setOf("sort", "page", "q")
                }
                ?: return@mapNotNull null

            val value = url.queryParameter(parameter)
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val name = anchor.ownText()
                .trim()
                .takeIf { it.isNotBlank() }
                ?: anchor.selectFirst("img[aria-label]")
                    ?.attr("aria-label")
                    ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            FilterOption(
                name = name,
                parameter = parameter,
                value = value,
            )
        }
        .distinctBy { it.parameter to it.value }
}

fun YaoiFilterData.toFilterList(): FilterList {
    val filters = mutableListOf<Filter<*>>()

    groups.forEach { group ->
        if (group.options.isEmpty()) {
            return@forEach
        }

        val parameters = group.options
            .map { it.parameter }
            .distinct()

        when {
            parameters.size > 1 -> {
                filters.add(
                    DynamicCheckboxGroupFilter(
                        group.name,
                        group.options,
                    ),
                )
            }

            parameters.first() in setOf(
                "langs",
                "genre",
                "tags",
            ) -> {
                filters.add(
                    DynamicCheckboxGroupFilter(
                        group.name,
                        group.options,
                    ),
                )
            }

            else -> {
                filters.add(
                    DynamicSelectFilter(
                        group.name,
                        group.options,
                    ),
                )
            }
        }
    }

    if (numbers.isNotEmpty()) {
        filters.add(Filter.Separator())
    }

    numbers.forEach { number ->
        filters.add(
            DynamicNumberFilter(
                name = number.name,
                parameter = number.parameter,
            ),
        )
    }

    return FilterList(filters)
}

fun FilterList.toYaoiQueryParameters(): List<Pair<String, String>> {
    val parameters = linkedMapOf<String, MutableList<String>>()

    fun add(parameter: String, value: String) {
        if (value.isNotBlank()) {
            parameters
                .getOrPut(parameter) { mutableListOf() }
                .add(value)
        }
    }

    forEach { filter ->
        when (filter) {
            is DynamicCheckboxGroupFilter -> {
                filter.selected.forEach { (parameter, value) ->
                    add(parameter, value)
                }
            }

            is DynamicSelectFilter -> {
                filter.selected?.let { (parameter, value) ->
                    add(parameter, value)
                }
            }

            is DynamicNumberFilter -> {
                add(
                    filter.parameter,
                    filter.state.trim(),
                )
            }

            else -> Unit
        }
    }

    return parameters.map { (parameter, values) ->
        parameter to values.distinct().joinToString(",")
    }
}

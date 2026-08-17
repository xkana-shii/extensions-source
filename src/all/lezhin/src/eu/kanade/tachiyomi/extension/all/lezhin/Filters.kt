package eu.kanade.tachiyomi.extension.all.lezhin

import android.util.Log
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.jsoup.Jsoup

private const val LOG_TAG = "LezhinFilters"

@Serializable
class LezhinTag(
    val tagId: Long,
    val tag: String,
    val name: String,
)

@Serializable
class LezhinDivision(
    val divisionName: String,
    val tags: List<LezhinTag>,
)

object LezhinTagsParser {

    fun parseDivisionsFromHtml(html: String): List<LezhinDivision> {
        try {
            val document = Jsoup.parse(html)

            document.select("script").forEach { script ->
                val data = script.data()

                if (!data.contains("/v2/tag/list")) {
                    return@forEach
                }

                val generalIndex = data.indexOf("\"general\":")
                if (generalIndex < 0) {
                    return@forEach
                }

                val arrayStartIndex = data.indexOf('[', generalIndex)
                if (arrayStartIndex < 0) {
                    return@forEach
                }

                val arrayEndIndex = findMatchingBracket(data, arrayStartIndex)
                if (arrayEndIndex < 0) {
                    return@forEach
                }

                val array = JSONArray(
                    data.substring(arrayStartIndex, arrayEndIndex + 1),
                )

                val divisions = buildList {
                    for (i in 0 until array.length()) {
                        val divisionObject = array.getJSONObject(i)
                        val divisionName = divisionObject.optString("divisionName", "Tags")
                        val tagsArray = divisionObject.optJSONArray("tags") ?: JSONArray()

                        val tags = buildList {
                            for (j in 0 until tagsArray.length()) {
                                val tagObject = tagsArray.getJSONObject(j)

                                val tagId = tagObject.optLong("tagId", -1L)
                                val tag = tagObject.optString("tag", "")
                                val name = tagObject.optString("name", tag)

                                if (tag.isBlank() && name.isBlank()) {
                                    continue
                                }

                                add(
                                    LezhinTag(
                                        tagId = tagId,
                                        tag = tag,
                                        name = name,
                                    ),
                                )
                            }
                        }

                        if (tags.isNotEmpty()) {
                            add(
                                LezhinDivision(
                                    divisionName = divisionName,
                                    tags = tags,
                                ),
                            )
                        }
                    }
                }

                if (divisions.isNotEmpty()) {
                    Log.d(
                        LOG_TAG,
                        "Parsed ${divisions.sumOf { it.tags.size }} tags across ${divisions.size} divisions",
                    )

                    return divisions
                }
            }
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "Failed to parse embedded tag JSON", e)
        }

        try {
            val document = Jsoup.parse(html)

            val names = document
                .select(
                    ".panelBody__items__Bzuhu button, " +
                        ".panelBody__items__Bzuhu a, " +
                        ".panelBody__item__TBUYn",
                )
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()

            if (names.isNotEmpty()) {
                val tags = names.mapIndexed { index, name ->
                    LezhinTag(
                        tagId = -1L - index,
                        tag = name,
                        name = name,
                    )
                }

                Log.d(
                    LOG_TAG,
                    "Fallback scraped ${tags.size} tags without IDs",
                )

                return listOf(
                    LezhinDivision(
                        divisionName = "Tags",
                        tags = tags,
                    ),
                )
            }
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "Failed to scrape fallback tags", e)
        }

        Log.w(LOG_TAG, "No tags found")

        return emptyList()
    }

    private fun findMatchingBracket(
        value: String,
        startIndex: Int,
    ): Int {
        var depth = 0
        var inString = false
        var escaped = false

        for (i in startIndex until value.length) {
            val char = value[i]

            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }

                continue
            }

            when (char) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--

                    if (depth == 0) {
                        return i
                    }
                }
            }
        }

        return -1
    }
}

class LezhinTagFilter(
    val tagId: Long,
    val tag: String,
    name: String,
) : Filter.CheckBox(name)

class LezhinTagFilterGroup(
    displayName: String,
    tags: List<LezhinTag>,
) : Filter.Group<LezhinTagFilter>(
    displayName,
    tags.map {
        LezhinTagFilter(
            tagId = it.tagId,
            tag = it.tag,
            name = it.name,
        )
    },
)

fun divisionsToFilterList(
    divisions: List<LezhinDivision>,
): FilterList = FilterList(
    *divisions
        .map { division ->
            LezhinTagFilterGroup(
                displayName = division.divisionName,
                tags = division.tags,
            )
        }
        .toTypedArray(),
)

fun selectedTagIds(
    filters: FilterList,
): List<Long> = filters
    .filterIsInstance<LezhinTagFilterGroup>()
    .flatMap { group ->
        group.state
            .filter { it.state && it.tagId >= 0L }
            .map { it.tagId }
    }
    .distinct()

fun selectedTagNames(
    filters: FilterList,
): List<String> = filters
    .filterIsInstance<LezhinTagFilterGroup>()
    .flatMap { group ->
        group.state
            .filter { it.state && it.tagId < 0L }
            .map { it.tag }
    }
    .distinct()

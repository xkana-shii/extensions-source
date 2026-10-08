
package eu.kanade.tachiyomi.extension.all.yaoimangaonline

import android.text.Html
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

@Source
abstract class YaoiMangaOnline : KeiSource() {

    override val supportsLatest = false

    override val supportsFilterFetching = true

    private val excludedCategoryIds = setOf(
        "2009", // Yaoi Anime
        "3017", // Gay Movies
        "1852", // Gay Novels
        "199", // Yaoi Games Online
        "15275", // Completed (subcategory of Yaoi Webtoons)
        "15442", // Hiatus (subcategory of Yaoi Webtoons)
        "15276", // Ongoing (subcategory of Yaoi Webtoons)
    )

    private val excludedCategoryNames = setOf(
        "Yaoi Anime",
        "Gay Movies",
        "Gay Novels",
        "Yaoi Games Online",
        "Completed",
        "Hiatus",
        "Ongoing",
    )

    private fun cleanCategoryName(raw: String): String = raw
        .replace(Regex("\\s*\\(\\d[\\d,]*\\)$"), "")
        .replace("&amp;", "&")
        .trim()

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get(baseUrl).asJsoup()

        val allCategories = document.select("#cat option[value]")
            .mapNotNull { option ->
                val value = option.attr("value").trim()
                val rawName = option.text().trim()
                val cleanName = cleanCategoryName(rawName)

                if (
                    value == "-1" ||
                    value.isEmpty() ||
                    rawName.isEmpty() ||
                    value in excludedCategoryIds ||
                    cleanName in excludedCategoryNames
                ) {
                    null
                } else {
                    YaoiFilterOption(cleanName, value)
                }
            }

        val djRegex = Regex("(?i)\\bdj\\b|\\bdoujinshi\\b")

        val types = allCategories.filterNot { djRegex.containsMatchIn(it.name) }
        val doujinshi = allCategories.filter { djRegex.containsMatchIn(it.name) }

        val tags = document.select(".tagcloud a.tag-cloud-link")
            .mapNotNull { element ->
                val name = element.text().trim()
                val href = element.attr("href").trim()
                val slug = href.trimEnd('/').substringAfterLast("/tag/").trimEnd('/').trim()

                if (name.isEmpty() || slug.isEmpty()) {
                    null
                } else {
                    YaoiFilterOption(name, slug)
                }
            }

        return YaoiFilterData(types, doujinshi, tags).toJsonElement()
    }

    // =================== Popular ===================

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangasPage(client.get("$baseUrl/page/$page/").asJsoup())

    private fun parseMangasPage(document: Document): MangasPage {
        val mangas = document.select(".post:not(.sticky):not(.category-gay-movies):not(.category-yaoi-anime):not(.category-gay-novels):not(.category-yaoi-games-online) > div > a")
            .map { element ->
                SManga.create().apply {
                    title = element.attr("title")
                    setUrlWithoutDomain(element.absUrl("href"))
                    thumbnail_url = element.selectFirst("img")?.attr("src")
                }
            }
        val hasNextPage = document.selectFirst(".herald-pagination > .next") != null
        return MangasPage(mangas, hasNextPage)
    }

    // =================== Latest ===================

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException()

    // =================== Search ===================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            var categoryId: String? = null

            filters.forEach {
                when (it) {
                    is TypeFilter -> if (it.state != 0) categoryId = it.toString()
                    is DoujinshiFilter -> if (it.state != 0) categoryId = it.toString()
                    else -> {}
                }
            }

            if (categoryId != null) {
                addQueryParameter("cat", categoryId)
            }

            filters.forEach {
                when (it) {
                    is TagFilter -> if (it.state != 0) {
                        addEncodedPathSegments("tag/$it")
                    }
                    else -> {}
                }
            }

            addEncodedPathSegments("page/$page")
            addQueryParameter("s", query)
        }.build()

        return parseMangasPage(client.get(url).asJsoup())
    }

    // =================== Details ===================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)

        val document = client.get(baseUrl + manga.url).asJsoup()
        return SMangaUpdate(
            if (fetchDetails) mangaDetailsParse(document) else manga,
            if (fetchChapters) chapterListParse(document) else chapters,
        )
    }

    private fun mangaDetailsParse(document: Document) = SManga.create().apply {
        title = document.select("h1.entry-title").text()
            .substringBeforeLast("by").trim()

        thumbnail_url = document.selectFirst(".herald-post-thumbnail img")?.attr("src")

        description = document
            .select(".entry-content > p:not(:has(img)):not(:contains(You need to login))")
            .joinToString("\n\n") {
                @Suppress("DEPRECATION")
                Html.fromHtml(it.html()).toString().trim()
            }
            .let { text ->
                val languageIndex = text.indexOf("Language:", ignoreCase = true)
                if (languageIndex != -1) {
                    text.substring(text.indexOf('\n', languageIndex).takeIf { it != -1 } ?: text.length)
                } else {
                    text
                }
            }
            .trim()

        // KNS
        genre = document.select(".meta-tags > a")
            .map { it.text().trim() }
            .filter {
                it.isNotEmpty() &&
                    it !in excludedCategoryNames &&
                    !it.equals("Yaoi Anime", ignoreCase = true) &&
                    !it.equals("Gay Movies", ignoreCase = true) &&
                    !it.equals("Gay Novels", ignoreCase = true) &&
                    !it.equals("Yaoi Games Online", ignoreCase = true)
            }
            .joinToString()
            .ifEmpty { null }
        // KNS

        author = document.select(".entry-content > p:matches((?i)(Author|Mangaka|Artist):)").text()
            .substringAfter(":")
            .substringBefore("Language:")
            .trim()
            .ifEmpty { null }

        // KNS
        artist = author

        status = when {
            document.selectFirst(".meta-category a[href*='/ongoing/']") != null -> SManga.ONGOING
            document.selectFirst(".meta-category a[href*='/completed/']") != null -> SManga.COMPLETED
            document.selectFirst(".meta-category a[href*='/hiatus/']") != null -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        // KNS
    }

    // =================== Chapters ===================

    private fun chapterListParse(document: Document): List<SChapter> {
        val chapters = document.select(".mpp-toc a").map { element ->
            SChapter.create().apply {
                name = element.ownText()
                setUrlWithoutDomain(element.absUrl("href").ifEmpty { element.baseUri() })
            }
        }
        return chapters.ifEmpty {
            listOf(
                SChapter.create().apply {
                    name = "Chapter"
                    url = document.location().toHttpUrl().encodedPath
                },
            )
        }.reversed()
    }

    // =================== Pages ===================

    override suspend fun getPageList(chapter: SChapter): List<Page> = client.get(baseUrl + chapter.url).asJsoup()
        .select(".entry-content img")
        .mapIndexed { idx, img -> Page(idx, imageUrl = img.attr("src")) }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<YaoiFilterData>()
        return FilterList(
            TypeFilter(filterData?.types.orEmpty()),
            DoujinshiFilter(filterData?.doujinshi.orEmpty()),
            TagFilter(filterData?.tags.orEmpty()),
        )
    }
}

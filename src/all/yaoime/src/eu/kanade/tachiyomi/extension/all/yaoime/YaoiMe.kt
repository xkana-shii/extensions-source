
package eu.kanade.tachiyomi.extension.all.yaoime

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Source
abstract class YaoiMe : KeiSource() {

    override val supportsFilterFetching = true

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(2)

    private val dateFormat = DateTimeFormatter.ofPattern(
        "d MMM yyyy",
        Locale.ENGLISH,
    )

    private val dateRegex = Regex(
        """\b\d{1,2}\s+[A-Za-z]{3}\s+\d{4}\b""",
    )

    private val chapterNumberRegex = Regex(
        """(?i)(?:ch(?:apter)?|ep(?:isode)?)\s*\.?\s*(\d+(?:\.\d+)?)""",
    )

    // ============================== Filters ==============================

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get(
            "$baseUrl/browse?sort=update",
        ).asJsoup()

        return document.extractYaoiFilters().toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList = data
        ?.parseAs<YaoiFilterData>()
        ?.toFilterList()
        ?: FilterList()

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage = browseManga(
        page = page,
        sort = "popular",
    )

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = browseManga(
        page = page,
        sort = "update",
    )

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = browseManga(
        page = page,
        query = query,
        sort = "update",
        filters = filters,
    )

    private suspend fun browseManga(
        page: Int,
        query: String = "",
        sort: String? = null,
        filters: FilterList = FilterList(),
    ): MangasPage {
        val selectedFilters = filters.toYaoiQueryParameters()

        val url = "$baseUrl/browse".toHttpUrl().newBuilder().apply {
            if (lang != "all" && selectedFilters.none { it.first == "langs" }) {
                addQueryParameter(
                    "langs",
                    lang.lowercase(Locale.ROOT),
                )
            }

            if (query.isNotBlank()) {
                addQueryParameter("q", query)
            }

            if (sort != null) {
                addQueryParameter("sort", sort)
            }

            selectedFilters.forEach { (parameter, value) ->
                addQueryParameter(parameter, value)
            }

            if (page > 1) {
                addQueryParameter("page", page.toString())
            }
        }.build()

        val document = client.get(url).asJsoup()

        val mangas = document
            .select("a[href^='/series/']")
            .groupBy {
                it.attr("href")
                    .substringBefore("?")
                    .trimEnd('/')
            }
            .mapNotNull { (_, links) ->
                mangaFromElement(links)
            }

        val hasNextPage = document
            .select("a[href*='page=']")
            .any {
                it.attr("href").contains(
                    Regex("""[?&]page=${page + 1}(?:&|$)"""),
                )
            }

        return MangasPage(mangas, hasNextPage)
    }

    private fun mangaFromElement(links: List<Element>): SManga? {
        val url = links.firstOrNull()
            ?.attr("href")
            ?.substringBefore("?")
            ?.trimEnd('/')
            ?: return null

        if (!url.startsWith("/series/")) {
            return null
        }

        val image = links.firstNotNullOfOrNull {
            it.selectFirst("img")
        } ?: links.firstOrNull()
            ?.parent()
            ?.selectFirst("img")

        val titleCandidates = links.flatMap { link ->
            buildList {
                addAll(
                    link.select("[data-testid*=title], [class*=title]")
                        .map { it.text() },
                )

                add(link.attr("title"))

                addAll(
                    link.select("img[alt]")
                        .map { it.attr("alt") },
                )

                addAll(
                    link.select("h1, h2, h3, h4")
                        .map { it.text() },
                )

                add(link.ownText())
            }
        }

        val invalidTitles = setOf(
            "completed",
            "ongoing",
            "releasing",
            "hiatus",
            "cancelled",
            "canceled",
            "upcoming",
            "unknown",
            "safe",
            "suggestive",
            "erotica",
            "pornographic",
            "manga",
            "manhwa",
            "manhua",
            "doujinshi",
            "novel",
            "other",
            "cover",
            "cover image",
            "read",
            "read now",
            "start reading",
        )

        val title = titleCandidates
            .map {
                it.trim().replace(Regex("""\s+"""), " ")
            }
            .firstOrNull {
                it.isNotBlank() &&
                    it.lowercase(Locale.ROOT) !in invalidTitles
            }
            ?: url.substringAfterLast('/')
                .replace('-', ' ')
                .replace('_', ' ')
                .split(' ')
                .joinToString(" ") { word ->
                    word.replaceFirstChar { it.uppercaseChar() }
                }

        if (title.isBlank()) {
            return null
        }

        val thumbnail = image?.let {
            it.absUrl("src").ifBlank {
                it.absUrl("data-src")
            }
        }

        return SManga.create().apply {
            this.url = url
            this.title = title
            thumbnail_url = thumbnail
        }
    }

    // ============================== URL Lookup ===========================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) {
            return null
        }

        if (!url.encodedPath.startsWith("/series/")) {
            return null
        }

        val manga = SManga.create().apply {
            this.url = url.encodedPath
            title = url.pathSegments.lastOrNull().orEmpty()
        }

        return fetchMangaUpdate(
            manga = manga,
            chapters = emptyList(),
            fetchDetails = true,
            fetchChapters = false,
        ).manga
    }

    // ============================== Manga Details ========================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) {
            return SMangaUpdate(manga, chapters)
        }

        val document = client.get(baseUrl + manga.url).asJsoup()

        if (fetchDetails) {
            mangaDetailsParse(document, manga)
        }

        val chapterList = if (fetchChapters) {
            chapterListParse(document)
        } else {
            chapters
        }

        return SMangaUpdate(manga, chapterList)
    }

    private fun mangaDetailsParse(
        document: Document,
        manga: SManga,
    ) {
        val titleElement = document.selectFirst("h1")

        val title = titleElement?.text()
            ?.takeIf { it.isNotBlank() }

        val cover = document
            .select("img[alt]")
            .firstOrNull {
                it.attr("alt").equals(title, ignoreCase = true)
            }

        val synopsisHeading = document
            .select("h2, h3")
            .firstOrNull {
                it.text().equals("Synopsis", ignoreCase = true)
            }

        val synopsis = synopsisHeading
            ?.nextElementSibling()
            ?.text()
            ?.takeIf { it.isNotBlank() }
            ?: synopsisHeading
                ?.parent()
                ?.selectFirst("p")
                ?.text()

        val credits = document
            .select("p, span, div")
            .firstOrNull {
                val text = it.ownText()
                text.startsWith("Story by") ||
                    text.startsWith("Story & art by")
            }
            ?.text()

        val genres = document
            .select(
                "a[href^='/genre/'], " +
                    "a[href^='/genres/'], " +
                    "a[href^='/tag/'], " +
                    "a[href^='/tags/']",
            )
            .eachText()
            .filter { it.isNotBlank() }
            .distinct()

        val headerText = titleElement
            ?.parent()
            ?.parent()
            ?.text()
            .orEmpty()
            .take(300)
            .lowercase()

        manga.apply {
            if (title != null) {
                this.title = title
            }

            cover?.let {
                thumbnail_url = it.absUrl("src").ifBlank {
                    it.absUrl("data-src")
                }
            }

            if (!synopsis.isNullOrBlank()) {
                description = synopsis
            }

            if (genres.isNotEmpty()) {
                genre = genres.joinToString()
            }

            if (!credits.isNullOrBlank()) {
                if ("Story & art by" in credits) {
                    val creator = credits
                        .substringAfter("Story & art by")
                        .substringBefore("·")
                        .trim()

                    author = creator
                    artist = creator
                } else {
                    author = credits
                        .substringAfter("Story by")
                        .substringBefore("·")
                        .trim()

                    if ("Art by" in credits) {
                        artist = credits
                            .substringAfter("Art by")
                            .substringBefore("·")
                            .trim()
                    }
                }
            }

            status = when {
                "completed" in headerText -> SManga.COMPLETED
                "ongoing" in headerText -> SManga.ONGOING
                "hiatus" in headerText -> SManga.ON_HIATUS
                "cancelled" in headerText -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    // ============================== Chapters =============================

    private fun chapterListParse(document: Document): List<SChapter> = document
        .select("a[href^='/chapter/']")
        .filterNot {
            val label = it.text()
            label.startsWith("Start reading", ignoreCase = true) ||
                label.startsWith("Latest", ignoreCase = true)
        }
        .distinctBy {
            it.attr("href").substringBefore("?")
        }
        .map { element ->
            val chapterUrl = element.attr("href")
                .substringBefore("?")

            val chapterName = element.text()
                .replace(Regex("""\bNEW\b"""), "")
                .trim()
                .ifBlank { "Chapter" }

            val surrounding = element.parent()?.parent()
            val surroundingText = surrounding?.text().orEmpty()

            val languages = surrounding
                ?.select("img[alt]")
                ?.map { it.attr("alt").trim() }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                .orEmpty()

            val date = dateRegex
                .find(surroundingText)
                ?.value

            SChapter.create().apply {
                url = chapterUrl
                name = chapterName

                if (languages.isNotEmpty()) {
                    scanlator = languages.joinToString()
                }

                chapter_number = chapterNumberRegex
                    .find(chapterName)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toFloatOrNull()
                    ?: -1f

                date_upload = dateFormat.tryParseDate(
                    date,
                    ZoneId.of("UTC"),
                )
            }
        }

    // ============================== Reader ===============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = baseUrl + chapter.url

        val imageUrls: List<String> = runWebView(
            timeout = 45.seconds,
        ) {
            var previous = emptyList<String>()
            var stableCount = 0

            poll(800.milliseconds) {
                evaluateJs(PAGE_EXTRACTION_SCRIPT) { raw ->
                    val urls = runCatching {
                        raw.parseAs<List<String>>()
                    }.getOrDefault(emptyList())

                    if (urls.isNotEmpty()) {
                        stableCount = if (urls == previous) {
                            stableCount + 1
                        } else {
                            0
                        }

                        previous = urls

                        if (stableCount >= 3) {
                            resolve(urls)
                        }
                    }
                }
            }

            loadUrl(chapterUrl)
        }

        if (imageUrls.isEmpty()) {
            throw Exception("No chapter images found")
        }

        return imageUrls.mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    companion object {
        private val PAGE_EXTRACTION_SCRIPT = """
            (() => {
                const reader = document.querySelector(
                    '[data-testid="reader"], ' +
                    '#chapter-reader, ' +
                    '#reader, ' +
                    '.reader-content, ' +
                    '[class*="reader-pages"]'
                );

                const root = reader ||
                    document.querySelector("main") ||
                    document.body;

                const images = Array.from(
                    root.querySelectorAll("img")
                );

                const results = [];

                for (const img of images) {
                    const source =
                        img.getAttribute("data-src") ||
                        img.getAttribute("data-original") ||
                        img.getAttribute("src");

                    if (!source) continue;

                    if (/^(data:|blob:)/i.test(source)) {
                        continue;
                    }

                    let url;

                    try {
                        url = new URL(
                            source,
                            document.baseURI
                        ).href;
                    } catch (_) {
                        continue;
                    }

                    if (!/^https?:/i.test(url)) {
                        continue;
                    }

                    const info = (
                        url + " " +
                        (img.className || "") + " " +
                        (img.alt || "")
                    ).toLowerCase();

                    if (
                        /avatar|favicon|logo|flag|banner|icon/.test(info)
                    ) {
                        continue;
                    }

                    const isReaderImage =
                        reader !== null ||
                        img.naturalWidth >= 400 ||
                        img.width >= 400 ||
                        img.closest(
                            '[class*="page"], ' +
                            '[class*="chapter"], ' +
                            '[class*="reader"]'
                        ) !== null ||
                        /\/pages?\//i.test(url);

                    if (!isReaderImage) {
                        continue;
                    }

                    if (!results.includes(url)) {
                        results.push(url);
                    }
                }

                return results;
            })()
        """.trimIndent()
    }
}

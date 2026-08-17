package eu.kanade.tachiyomi.extension.all.lezhin

import android.text.InputType
import android.util.Base64
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.extractNextJs
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.toJsonRequestBody
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

private const val LOG_TAG = "Lezhin"

@Suppress("unused")
@Source
abstract class Lezhin :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    private val sessionMutex = Mutex()

    @Volatile
    private var sessionInitialized = false

    @Volatile
    private var cdnBase: String? = null

    private val pathSegment: String
        get() = lang

    private val siteLocale: String
        get() = when (lang) {
            "ko" -> "ko-KR"
            else -> "en-US"
        }

    private val apiBase =
        "https://www.lezhinus.com/lz-api/v2/"

    private val sourceHost: String
        get() = baseUrl.toHttpUrl().host

    private val imageFormat: String
        get() = preferences
            .getString(
                PREF_IMAGE_FORMAT,
                IMAGE_FORMAT_WEBP,
            )
            ?: IMAGE_FORMAT_WEBP

    override fun OkHttpClient.Builder.configureClient() = apply {
        addNetworkInterceptor(
            ::imageDescrambler,
        )
    }

    override fun Headers.Builder.configureHeaders() = apply {
        set(
            "x-lz-locale",
            siteLocale,
        )
    }

    override fun setupPreferenceScreen(
        screen: PreferenceScreen,
    ) {
        val context = screen.context

        EditTextPreference(context).apply {
            key = PREF_EMAIL
            title = "Email"
            summary = "Email used to log in to Lezhin"
            dialogTitle = "Lezhin email"
            setDefaultValue("")

            setOnPreferenceChangeListener { _, _ ->
                clearSession()
                true
            }
        }.also(
            screen::addPreference,
        )

        EditTextPreference(context).apply {
            key = PREF_PASSWORD
            title = "Password"
            summary = "Password used to log in to Lezhin"
            dialogTitle = "Lezhin password"
            setDefaultValue("")

            setOnBindEditTextListener { editText ->
                editText.inputType =
                    InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
            }

            setOnPreferenceChangeListener { _, _ ->
                clearSession()
                true
            }
        }.also(
            screen::addPreference,
        )

        ListPreference(context).apply {
            key = PREF_IMAGE_FORMAT
            title = "Image format"
            summary = "Preferred image format for reader pages"

            entries = arrayOf(
                "WebP",
                "JPEG",
            )

            entryValues = arrayOf(
                IMAGE_FORMAT_WEBP,
                IMAGE_FORMAT_JPEG,
            )

            setDefaultValue(
                IMAGE_FORMAT_WEBP,
            )
        }.also(
            screen::addPreference,
        )
    }

    // ============================= Popular ==============================

    override suspend fun getPopularManga(
        page: Int,
    ): MangasPage {
        val perPage = 500
        val offset = (page - 1) * perPage

        val url = apiBase
            .toHttpUrl()
            .newBuilder()
            .addPathSegment("contents")
            .addQueryParameter(
                "menu",
                "general",
            )
            .addQueryParameter(
                "limit",
                perPage.toString(),
            )
            .addQueryParameter(
                "offset",
                offset.toString(),
            )
            .addQueryParameter(
                "order",
                "popular",
            )
            .build()

        val result = apiGet(
            url = url,
            adult = true,
        ).use {
            it.parseAs<LezhinContentListDto>()
        }

        return MangasPage(
            mangas = result.data.map {
                it.toSManga(
                    pathSegment,
                )
            },
            hasNextPage = result.hasNext,
        )
    }

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(
        page: Int,
    ): MangasPage {
        if (page > 1) {
            return MangasPage(
                emptyList(),
                false,
            )
        }

        updateSession()

        val latestPath = when (lang) {
            "ko" -> "scheduled"
            else -> "daily"
        }

        val document = client
            .get(
                "$baseUrl/$pathSegment/$latestPath",
                authorizedHeaders(
                    adult = true,
                ),
            )
            .use {
                it.asJsoup()
            }

        return MangasPage(
            parseLatestMangaList(
                document,
            ),
            false,
        )
    }

    private fun parseLatestMangaList(
        document: Document,
    ): List<SManga> {
        val entries = document
            .select(
                "a[href*='/$pathSegment/comic/']",
            )
            .mapNotNull { anchor ->
                val mangaUrl =
                    extractMangaPath(
                        anchor.attr("href"),
                    )
                        ?: return@mapNotNull null

                val alias =
                    mangaUrl
                        .substringAfterLast('/')
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?: return@mapNotNull null

                LatestEntry(
                    manga = SManga
                        .create()
                        .apply {
                            url =
                                mangaUrl

                            title =
                                extractCardTitle(
                                    anchor = anchor,
                                    alias = alias,
                                )

                            thumbnail_url =
                                extractCardCover(
                                    anchor,
                                )
                        },
                    updated =
                    isUpdatedCard(
                        anchor,
                    ),
                )
            }
            .distinctBy {
                it.manga.url
            }

        val updated = entries
            .filter {
                it.updated
            }
            .map {
                it.manga
            }

        return updated.ifEmpty {
            entries.map {
                it.manga
            }
        }
    }

    private fun extractMangaPath(
        value: String,
    ): String? {
        if (value.isBlank()) {
            return null
        }

        val path =
            if (
                value.startsWith("http://") ||
                value.startsWith("https://")
            ) {
                value
                    .toHttpUrlOrNull()
                    ?.encodedPath
                    ?: return null
            } else {
                value
                    .substringBefore('?')
                    .substringBefore('#')
                    .let {
                        if (it.startsWith('/')) {
                            it
                        } else {
                            "/$it"
                        }
                    }
            }

        if (
            !path.startsWith(
                "/$pathSegment/comic/",
            )
        ) {
            return null
        }

        return path
    }

    private fun extractCardTitle(
        anchor: Element,
        alias: String,
    ): String {
        val candidates = listOfNotNull(
            anchor
                .attr("aria-label")
                .takeIf {
                    it.isNotBlank()
                },
            anchor
                .attr("title")
                .takeIf {
                    it.isNotBlank()
                },
            anchor
                .selectFirst(
                    "img[alt]",
                )
                ?.attr("alt")
                ?.takeIf {
                    it.isNotBlank()
                },
            anchor
                .selectFirst(
                    "[class*=title], [class*=Title]",
                )
                ?.text()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                },
            anchor
                .selectFirst(
                    "h2, h3, h4",
                )
                ?.text()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                },
        )

        return candidates
            .firstOrNull {
                !isBadgeText(it)
            }
            ?: alias
                .replace(
                    '_',
                    ' ',
                )
                .replace(
                    '-',
                    ' ',
                )
    }

    private fun extractCardCover(
        anchor: Element,
    ): String? {
        val image =
            anchor
                .selectFirst("img")
                ?: return null

        val source =
            image
                .absUrl("src")
                .ifEmpty {
                    image.attr("src")
                }
                .ifEmpty {
                    image.attr("data-src")
                }
                .takeIf {
                    it.isNotBlank() &&
                        !it.startsWith(
                            "data:",
                        )
                }
                ?: return null

        return normalizeCoverUrl(
            source,
        )
    }

    private fun isUpdatedCard(
        anchor: Element,
    ): Boolean = anchor
        .select(
            "span, p, div",
        )
        .map {
            it.ownText()
                .trim()
        }
        .filter {
            it.isNotBlank()
        }
        .any {
            it.equals(
                "UP",
                ignoreCase = true,
            ) ||
                it.equals(
                    "NEW",
                    ignoreCase = true,
                ) ||
                it == "신작"
        }

    private fun isBadgeText(
        value: String,
    ): Boolean = value.equals(
        "UP",
        ignoreCase = true,
    ) ||
        value.equals(
            "NEW",
            ignoreCase = true,
        ) ||
        value.equals(
            "EVENT",
            ignoreCase = true,
        ) ||
        value.equals(
            "Zzz",
            ignoreCase = true,
        ) ||
        value == "신작"

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val perPage = 30
        val offset = (page - 1) * perPage

        val selectedIds =
            selectedTagIds(
                filters,
            )

        val selectedNames =
            selectedTagNames(
                filters,
            )

        if (
            query.isBlank() &&
            selectedIds.isEmpty() &&
            selectedNames.isEmpty()
        ) {
            return getPopularManga(
                page,
            )
        }

        val url =
            if (
                selectedIds.isNotEmpty()
            ) {
                "${apiBase}advanced-search/multitags"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter(
                        "int_id",
                        selectedIds
                            .joinToString(","),
                    )
                    .addQueryParameter(
                        "ext_id",
                        "",
                    )
                    .addQueryParameter(
                        "filter",
                        "exact_match",
                    )
                    .addQueryParameter(
                        "order",
                        "relevant",
                    )
                    .addQueryParameter(
                        "tab",
                        "general",
                    )
                    .addQueryParameter(
                        "limit",
                        perPage.toString(),
                    )
                    .addQueryParameter(
                        "offset",
                        offset.toString(),
                    )
                    .build()
            } else {
                "${apiBase}advanced-search"
                    .toHttpUrl()
                    .newBuilder()
                    .addQueryParameter(
                        "q",
                        query,
                    )
                    .addQueryParameter(
                        "t",
                        "all",
                    )
                    .addQueryParameter(
                        "order",
                        "popular",
                    )
                    .addQueryParameter(
                        "offset",
                        offset.toString(),
                    )
                    .addQueryParameter(
                        "limit",
                        perPage.toString(),
                    )
                    .apply {
                        if (
                            selectedNames
                                .isNotEmpty()
                        ) {
                            addQueryParameter(
                                "tags",
                                selectedNames
                                    .joinToString(","),
                            )
                        }
                    }
                    .build()
            }

        val result = apiGet(
            url = url,
            adult = true,
        ).use {
            it.parseAs<LezhinContentListDto>()
        }

        return MangasPage(
            mangas = result.data.map {
                it.toSManga(
                    pathSegment,
                )
            },
            hasNextPage =
            result.hasNext,
        )
    }

    // ============================== Filters ==============================

    override val supportsFilterFetching =
        true

    override suspend fun fetchFilterData(): JsonElement {
        updateSession()

        val url =
            "$baseUrl/$pathSegment/tags"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter(
                    "filter",
                    "exact_match",
                )
                .addQueryParameter(
                    "order",
                    "relevant",
                )
                .addQueryParameter(
                    "tab",
                    "general",
                )
                .build()

        val html = client
            .get(
                url,
                authorizedHeaders(
                    adult = true,
                ),
            )
            .use {
                it.body.string()
            }

        val divisions =
            LezhinTagsParser
                .parseDivisionsFromHtml(
                    html,
                )

        if (
            divisions.isEmpty()
        ) {
            throw IOException(
                "Unable to load Lezhin tags",
            )
        }

        return divisions
            .toJsonElement()
    }

    override fun getFilterList(
        data: JsonElement?,
    ): FilterList {
        if (
            data == null
        ) {
            return FilterList()
        }

        val divisions =
            runCatching {
                data.parseAs<
                    List<LezhinDivision>,
                    >()
            }.getOrElse {
                Log.e(
                    LOG_TAG,
                    "Failed to parse cached filters",
                    it,
                )

                emptyList()
            }

        return divisionsToFilterList(
            divisions,
        )
    }

    // ============================= Deeplinks =============================

    override suspend fun getMangaByUrl(
        url: HttpUrl,
    ): SManga? {
        if (
            url.host !=
            sourceHost
        ) {
            return null
        }

        val segments =
            url.pathSegments

        if (
            segments.getOrNull(0) !=
            pathSegment ||
            segments.getOrNull(1) !=
            "comic"
        ) {
            return null
        }

        val alias =
            segments
                .getOrNull(2)
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return null

        updateSession()

        val manga =
            SManga
                .create()
                .apply {
                    this.url =
                        "/$pathSegment/comic/$alias"

                    title =
                        alias
                }

        val document = client
            .get(
                resolveSourceUrl(
                    manga.url,
                ),
                authorizedHeaders(
                    adult = true,
                ),
            )
            .use {
                it.asJsoup()
            }

        return parseMangaDetails(
            document = document,
            manga = manga,
        )
    }

    // ======================== Details + Chapters =========================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (
            !fetchDetails &&
            !fetchChapters
        ) {
            return SMangaUpdate(
                manga = manga,
                chapters = chapters,
            )
        }

        updateSession()

        val document = client
            .get(
                resolveSourceUrl(
                    manga.url,
                ),
                authorizedHeaders(
                    adult = true,
                ),
            )
            .use {
                it.asJsoup()
            }

        return SMangaUpdate(
            manga =
            if (
                fetchDetails
            ) {
                parseMangaDetails(
                    document = document,
                    manga = manga,
                )
            } else {
                manga
            },
            chapters =
            if (
                fetchChapters
            ) {
                parseChapterList(
                    document = document,
                    manga = manga,
                )
            } else {
                chapters
            },
        )
    }

    private fun parseMangaDetails(
        document: Document,
        manga: SManga,
    ): SManga {
        val title =
            document
                .selectFirst(
                    "div.lzSection p[class*=-Head3xl]",
                )
                ?.text()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: document
                    .selectFirst(
                        "h1",
                    )
                    ?.text()
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                ?: document
                    .title()
                    .trim()
                    .takeIf {
                        it.isNotBlank()
                    }
                ?: manga.title

        val description =
            document
                .selectFirst(
                    "meta[name=description]",
                )
                ?.attr(
                    "content",
                )
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: document
                    .selectFirst(
                        "p.summary",
                    )
                    ?.text()
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }

        val coverCandidates =
            mutableListOf<String>()

        document
            .select(
                "picture img[src]",
            )
            .forEach { image ->
                val source =
                    image
                        .absUrl("src")
                        .ifEmpty {
                            image.attr(
                                "src",
                            )
                        }

                if (
                    source.isNotBlank()
                ) {
                    coverCandidates +=
                        source
                }
            }

        document
            .select(
                "div.episodeListCover__yMRVY img[src], " +
                    "div.comicEpisodeList__cover__tu49G img[src]",
            )
            .forEach { image ->
                val source =
                    image
                        .absUrl("src")
                        .ifEmpty {
                            image.attr(
                                "src",
                            )
                        }

                if (
                    source.isNotBlank()
                ) {
                    coverCandidates +=
                        source
                }
            }

        document
            .selectFirst(
                "meta[property=og:image]",
            )
            ?.attr(
                "content",
            )
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let(
                coverCandidates::add,
            )

        val selectedCover =
            coverCandidates
                .firstOrNull {
                    it.contains(
                        "/images/tall",
                        ignoreCase = true,
                    )
                }
                ?: coverCandidates
                    .firstOrNull()

        val authors =
            mutableListOf<String>()

        val artists =
            mutableListOf<String>()

        val originals =
            mutableListOf<String>()

        document
            .select(
                ".episodeListDetail__artist__MWexm",
            )
            .forEach { node ->
                val role =
                    node
                        .selectFirst(
                            ".episodeListDetail__artistName__gD_OK",
                        )
                        ?.text()
                        ?.trim()
                        ?.lowercase()
                        ?: node
                            .selectFirst(
                                "span",
                            )
                            ?.text()
                            ?.trim()
                            ?.lowercase()

                val creator =
                    node
                        .selectFirst(
                            "a",
                        )
                        ?.text()
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: node
                            .ownText()
                            .trim()
                            .takeIf {
                                it.isNotBlank()
                            }
                        ?: return@forEach

                when {
                    role?.contains(
                        "writer",
                    ) == true ||
                        role?.contains(
                            "scripter",
                        ) == true ||
                        role?.contains(
                            "script",
                        ) == true ||
                        role?.contains(
                            "story",
                        ) == true ||
                        role?.contains(
                            "스토리",
                        ) == true ||
                        role == "글" -> {
                        authors +=
                            creator
                    }

                    role?.contains(
                        "painter",
                    ) == true ||
                        role?.contains(
                            "artist",
                        ) == true ||
                        role?.contains(
                            "그림",
                        ) == true -> {
                        artists +=
                            creator
                    }

                    role?.contains(
                        "original",
                    ) == true ||
                        role?.contains(
                            "origin",
                        ) == true ||
                        role?.contains(
                            "원작",
                        ) == true -> {
                        originals +=
                            creator
                    }

                    else -> {
                        authors +=
                            creator
                    }
                }
            }

        val tags =
            document
                .select(
                    "a[href*=/tags/]",
                )
                .map {
                    it.text()
                        .trim()
                }
                .filter {
                    it.isNotBlank()
                }
                .distinct()

        val pageText =
            document.text()

        val status =
            when {
                pageText.contains(
                    "COMPLETED",
                    ignoreCase = true,
                ) ||
                    pageText.contains(
                        "COMPLETE",
                        ignoreCase = true,
                    ) ||
                    pageText.contains(
                        "완결",
                    ) -> {
                    SManga.COMPLETED
                }

                pageText.contains(
                    "NEW",
                    ignoreCase = true,
                ) ||
                    pageText.contains(
                        "신작",
                    ) -> {
                    SManga.ONGOING
                }

                else -> {
                    manga.status
                }
            }

        val normalizedCover =
            normalizeCoverUrl(
                url =
                selectedCover
                    ?: manga.thumbnail_url,
                fallbackUrl =
                manga.thumbnail_url,
            )

        return SManga
            .create()
            .apply {
                url =
                    manga.url

                this.title =
                    title

                this.description =
                    description
                        ?: manga.description

                val combinedAuthors =
                    (
                        authors +
                            originals
                        )
                        .distinct()

                author =
                    if (
                        combinedAuthors
                            .isNotEmpty()
                    ) {
                        combinedAuthors
                            .joinToString(", ")
                    } else {
                        manga.author
                    }

                artist =
                    if (
                        artists
                            .isNotEmpty()
                    ) {
                        artists
                            .distinct()
                            .joinToString(", ")
                    } else {
                        manga.artist
                    }

                genre =
                    if (
                        tags
                            .isNotEmpty()
                    ) {
                        tags
                            .joinToString(", ")
                    } else {
                        manga.genre
                    }

                this.status =
                    status

                thumbnail_url =
                    normalizedCover
                        ?: manga.thumbnail_url
            }
    }

    private fun normalizeCoverUrl(
        url: String?,
        fallbackUrl: String? = null,
    ): String? {
        val unwrapped =
            unwrapImageUrl(
                url,
            )
                ?: return fallbackUrl

        val tall =
            unwrapped.replace(
                "/images/wide",
                "/images/tall",
                ignoreCase = true,
            )

        val parsed =
            tall
                .toHttpUrlOrNull()
                ?: return tall

        if (
            parsed.host !=
            "ccdn.lezhin.com" ||
            !parsed
                .encodedPath
                .contains(
                    "/images/tall",
                    ignoreCase = true,
                )
        ) {
            return tall
        }

        val fallbackUpdated =
            unwrapImageUrl(
                fallbackUrl,
            )
                ?.toHttpUrlOrNull()
                ?.queryParameter(
                    "updated",
                )

        val updated =
            parsed
                .queryParameter(
                    "updated",
                )
                ?: fallbackUpdated

        val webpPath =
            parsed
                .encodedPath
                .replace(
                    Regex(
                        """\.(?:jpg|jpeg|png|webp)$""",
                        RegexOption.IGNORE_CASE,
                    ),
                    ".webp",
                )

        val builder =
            parsed
                .newBuilder()
                .encodedPath(
                    webpPath,
                )

        parsed
            .queryParameterNames
            .forEach {
                builder
                    .removeAllQueryParameters(
                        it,
                    )
            }

        if (
            !updated.isNullOrBlank()
        ) {
            builder
                .addQueryParameter(
                    "updated",
                    updated,
                )
        }

        builder
            .addQueryParameter(
                "width",
                "1200",
            )

        return builder
            .build()
            .toString()
    }

    private fun unwrapImageUrl(
        value: String?,
    ): String? {
        val source =
            value
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return null

        val parsed =
            source
                .toHttpUrlOrNull()
                ?: return source

        if (
            parsed
                .encodedPath
                .contains(
                    "/_next/image",
                )
        ) {
            return parsed
                .queryParameter(
                    "url",
                )
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: source
        }

        return source
    }

    private fun parseChapterList(
        document: Document,
        manga: SManga,
    ): List<SChapter> {
        val lockStates =
            parseChapterLockStates(
                document,
            )

        val hydrated =
            document
                .extractNextJs<
                    LezhinHydratedChaptersDto,
                    > { element ->
                    element is JsonObject &&
                        "episodes" in element
                }

        if (
            hydrated != null
        ) {
            return hydrated
                .episodes
                .map { episode ->
                    val chapterUrl =
                        manga.url
                            .trimEnd('/') +
                            "/" +
                            episode.name

                    val isLocked =
                        normalizeChapterPath(
                            chapterUrl,
                        )
                            ?.let {
                                lockStates[it]
                            } ==
                            true

                    val title =
                        episode
                            .display
                            .title

                    SChapter
                        .create()
                        .apply {
                            url =
                                chapterUrl

                            name =
                                if (
                                    isLocked
                                ) {
                                    "🔒 $title"
                                } else {
                                    title
                                }

                            (
                                parseChapterNumber(
                                    title,
                                )
                                    ?: parseChapterNumber(
                                        episode.name,
                                    )
                                )
                                ?.let {
                                    chapter_number =
                                        it
                                }
                        }
                }
        }

        return document
            .select(
                "div[data-id]",
            )
            .mapNotNull { node ->
                val anchor =
                    node
                        .selectFirst(
                            "a[href]",
                        )
                        ?: return@mapNotNull null

                val href =
                    anchor
                        .attr(
                            "href",
                        )
                        .trim()
                        .takeIf {
                            it.isNotBlank()
                        }
                        ?: return@mapNotNull null

                val title =
                    anchor
                        .selectFirst(
                            "h3",
                        )
                        ?.text()
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: anchor
                            .text()
                            .trim()
                            .takeIf {
                                it.isNotBlank()
                            }
                        ?: return@mapNotNull null

                val isLocked =
                    getChapterLockState(
                        anchor,
                    ) ==
                        true

                SChapter
                    .create()
                    .apply {
                        url =
                            href

                        name =
                            if (
                                isLocked
                            ) {
                                "🔒 $title"
                            } else {
                                title
                            }

                        (
                            parseChapterNumber(
                                title,
                            )
                                ?: parseChapterNumber(
                                    href
                                        .substringAfterLast(
                                            '/',
                                        ),
                                )
                            )
                            ?.let {
                                chapter_number =
                                    it
                            }
                    }
            }
    }

    private fun parseChapterLockStates(
        document: Document,
    ): Map<String, Boolean> {
        return buildMap {
            document
                .select(
                    "div[data-id]",
                )
                .forEach { node ->
                    val anchor =
                        node
                            .selectFirst(
                                "a[href]",
                            )
                            ?: return@forEach

                    val path =
                        normalizeChapterPath(
                            anchor.attr(
                                "href",
                            ),
                        )
                            ?: return@forEach

                    val locked =
                        getChapterLockState(
                            anchor,
                        )
                            ?: return@forEach

                    put(
                        path,
                        locked,
                    )
                }
        }
    }

    private fun getChapterLockState(
        anchor: Element,
    ): Boolean? {
        val priceNodes =
            anchor.select(
                "div.flex.items-end.justify-between p",
            )

        val priceText =
            when {
                priceNodes.size >= 2 -> {
                    priceNodes[1]
                        .text()
                        .trim()
                }

                priceNodes.size == 1 -> {
                    priceNodes[0]
                        .text()
                        .trim()
                }

                else -> {
                    anchor
                        .select(
                            "p",
                        )
                        .lastOrNull()
                        ?.text()
                        ?.trim()
                        .orEmpty()
                }
            }

        if (
            priceText.isBlank()
        ) {
            return null
        }

        val isAccessible =
            priceText.equals(
                "purchased",
                ignoreCase = true,
            ) ||
                priceText.equals(
                    "free",
                    ignoreCase = true,
                ) ||
                priceText.equals(
                    "owned",
                    ignoreCase = true,
                ) ||
                priceText.equals(
                    "구매완료",
                    ignoreCase = true,
                ) ||
                priceText.equals(
                    "구매함",
                    ignoreCase = true,
                ) ||
                priceText.equals(
                    "무료",
                    ignoreCase = true,
                )

        return !isAccessible
    }

    private fun normalizeChapterPath(
        value: String,
    ): String? {
        if (
            value.isBlank()
        ) {
            return null
        }

        val path =
            if (
                value.startsWith(
                    "http://",
                ) ||
                value.startsWith(
                    "https://",
                )
            ) {
                value
                    .toHttpUrlOrNull()
                    ?.encodedPath
                    ?: return null
            } else {
                value
                    .substringBefore('?')
                    .substringBefore('#')
                    .let {
                        if (
                            it.startsWith('/')
                        ) {
                            it
                        } else {
                            "/$it"
                        }
                    }
            }

        return path
            .trimEnd('/')
    }

    private fun parseChapterNumber(
        value: String,
    ): Float? = Regex(
        """\d+(?:\.\d+)?""",
    )
        .find(
            value,
        )
        ?.value
        ?.toFloatOrNull()

    // ============================== Reader ===============================

    override suspend fun getPageList(
        chapter: SChapter,
    ): List<Page> {
        updateSession()

        val document = client
            .get(
                resolveSourceUrl(
                    chapter.url,
                ),
                authorizedHeaders(
                    adult = true,
                ),
            )
            .use {
                it.asJsoup()
            }

        val queries =
            document
                .extractNextJs<
                    LezhinQueriesDto,
                    > { element ->
                    element is JsonObject &&
                        "queries" in element
                }
                ?: throw IOException(
                    "Chapter is unavailable",
                )

        val pagesQuery =
            queries
                .queries
                .firstOrNull {
                    getQueryKeyName(
                        it,
                    ) ==
                        "viewer-static-state"
                }
                ?: throw IOException(
                    "Lezhin viewer-static-state not found",
                )

        val userQuery =
            queries
                .queries
                .firstOrNull {
                    getQueryKeyName(
                        it,
                    ) ==
                        "viewer-user-state"
                }

        val viewer =
            pagesQuery
                .state
                .data
                .parseAs<
                    LezhinViewerPagesDto,
                    >()

        val status =
            userQuery
                ?.let {
                    runCatching {
                        it
                            .state
                            .data
                            .parseAs<
                                LezhinViewerStatusDto,
                                >()
                    }
                        .getOrNull()
                }
                ?: LezhinViewerStatusDto()

        Log.d(
            LOG_TAG,
            "Reader state: " +
                "episode=${viewer.episode.id}, " +
                "comic=${viewer.comic.id}, " +
                "purchased=${status.isPurchased}, " +
                "subscribed=${status.isSubscribed}, " +
                "shuffled=${viewer.media.imageShuffle}",
        )

        val media =
            viewer
                .media
                .pageView
                .ifEmpty {
                    viewer
                        .media
                        .scrollView
                }
                .filter {
                    it.cutType ==
                        "contents"
                }

        if (
            media.isEmpty()
        ) {
            throw IOException(
                "No readable pages found. " +
                    "This chapter may be locked or unavailable.",
            )
        }

        if (
            cdnBase == null
        ) {
            updateCdn(
                forced = true,
            )
        }

        val cdn =
            cdnBase
                ?: throw IOException(
                    "Unable to determine Lezhin CDN",
                )

        return media
            .mapIndexed {
                    index,
                    item,
                ->

                val imageUrl =
                    cdn.trimEnd('/') +
                        "/v2" +
                        item.path +
                        imageFormat

                val metadata =
                    buildString {
                        append(
                            "lezhin_comic=",
                        )
                        append(
                            viewer.comic.id,
                        )
                        append(
                            ";episode=",
                        )
                        append(
                            viewer.episode.id,
                        )
                        append(
                            ";purchased=",
                        )
                        append(
                            status.isPurchased,
                        )
                        append(
                            ";updated=",
                        )
                        append(
                            viewer.episode.updatedAt,
                        )
                        append(
                            ";shuffle=",
                        )
                        append(
                            viewer
                                .media
                                .imageShuffle,
                        )
                    }

                val pageUrl =
                    imageUrl
                        .toHttpUrl()
                        .newBuilder()
                        .fragment(
                            metadata,
                        )
                        .build()
                        .toString()

                Page(
                    index = index,
                    url = pageUrl,
                )
            }
    }

    private fun getQueryKeyName(
        query: LezhinQueryDto,
    ): String? {
        val first =
            query
                .queryKey
                .firstOrNull()
                ?: return null

        return (
            first as?
                JsonPrimitive
            )
            ?.takeIf {
                it.isString
            }
            ?.content
    }

    override suspend fun getImageUrl(
        page: Page,
    ): String {
        val pageUrl =
            page.url
                .toHttpUrl()

        val parameters =
            parseFragmentParameters(
                pageUrl.fragment
                    ?: throw IOException(
                        "Missing Lezhin page metadata",
                    ),
            )

        val comicId =
            parameters[
                "lezhin_comic",
            ]
                ?.toIntOrNull()
                ?: throw IOException(
                    "Missing Lezhin comic id",
                )

        val episodeId =
            parameters[
                "episode",
            ]
                ?.toIntOrNull()
                ?: throw IOException(
                    "Missing Lezhin episode id",
                )

        val purchased =
            parameters[
                "purchased",
            ]
                ?.toBooleanStrictOrNull()
                ?: false

        val updatedAt =
            parameters[
                "updated",
            ]
                ?.toLongOrNull()
                ?: 0L

        val shuffled =
            parameters[
                "shuffle",
            ]
                ?.toBooleanStrictOrNull()
                ?: false

        val unsignedImageUrl =
            pageUrl
                .newBuilder()
                .fragment(
                    null,
                )
                .build()

        val signed =
            createSignedImageUrl(
                comicId =
                comicId,
                episodeId =
                episodeId,
                purchased =
                purchased,
                updatedAt =
                updatedAt,
                imageUrl =
                unsignedImageUrl,
            )

        return if (
            shuffled
        ) {
            signed
                .newBuilder()
                .fragment(
                    "lezhin_eid=$episodeId;cols=5",
                )
                .build()
                .toString()
        } else {
            signed
                .toString()
        }
    }

    private suspend fun createSignedImageUrl(
        comicId: Int,
        episodeId: Int,
        purchased: Boolean,
        updatedAt: Long,
        imageUrl: HttpUrl,
    ): HttpUrl {
        val signingUrl =
            "${apiBase}cloudfront/signed-url/generate"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter(
                    "contentId",
                    comicId.toString(),
                )
                .addQueryParameter(
                    "episodeId",
                    episodeId.toString(),
                )
                .addQueryParameter(
                    "purchased",
                    purchased.toString(),
                )
                .addQueryParameter(
                    "q",
                    "40",
                )
                .addQueryParameter(
                    "firstCheckType",
                    "P",
                )
                .build()

        val keyPair =
            apiGet(
                url =
                signingUrl,
            )
                .use {
                    it.parseAs<
                        LezhinSignedUrlResponseDto,
                        >()
                }
                .data

        return imageUrl
            .newBuilder()
            .addQueryParameter(
                "purchased",
                purchased.toString(),
            )
            .addQueryParameter(
                "q",
                "40",
            )
            .addQueryParameter(
                "updated",
                updatedAt.toString(),
            )
            .addQueryParameter(
                "Policy",
                keyPair.policy,
            )
            .addQueryParameter(
                "Signature",
                keyPair.signature,
            )
            .addQueryParameter(
                "Key-Pair-Id",
                keyPair.keyPairId,
            )
            .build()
    }

    private fun parseFragmentParameters(
        fragment: String,
    ): Map<String, String> = fragment
        .split(';')
        .mapNotNull {
            val parts =
                it.split(
                    '=',
                    limit = 2,
                )

            if (
                parts.size != 2
            ) {
                null
            } else {
                parts[0] to
                    parts[1]
            }
        }
        .toMap()

    // ============================== Session ==============================

    private suspend fun updateSession(
        force: Boolean = false,
    ) {
        sessionMutex
            .withLock {
                if (
                    !force &&
                    sessionInitialized &&
                    !tokenExpired()
                ) {
                    return
                }

                if (
                    force
                ) {
                    clearAuth()
                }

                val email =
                    preferences
                        .getString(
                            PREF_EMAIL,
                            "",
                        )
                        .orEmpty()

                val password =
                    preferences
                        .getString(
                            PREF_PASSWORD,
                            "",
                        )
                        .orEmpty()

                val hasCredentials =
                    email.isNotBlank() &&
                        password.isNotBlank()

                if (
                    currentToken()
                        .isNullOrBlank() ||
                    tokenExpired()
                ) {
                    fetchHydratedAuth()
                        ?.takeIf {
                            !it
                                .accessToken
                                .isNullOrBlank()
                        }
                        ?.let(
                            ::saveAuth,
                        )
                }

                if (
                    hasCredentials &&
                    (
                        !isLogged() ||
                            currentToken()
                                .isNullOrBlank() ||
                            tokenExpired()
                        )
                ) {
                    loginWithWebView(
                        email =
                        email,
                        password =
                        password,
                    )
                        ?.takeIf {
                            !it
                                .accessToken
                                .isNullOrBlank()
                        }
                        ?.let(
                            ::saveAuth,
                        )
                }

                if (
                    tokenExpired()
                ) {
                    clearAuth()
                }

                if (
                    isLogged() &&
                    !currentToken()
                        .isNullOrBlank()
                ) {
                    forceLanguage()
                }

                sessionInitialized =
                    true
            }
    }

    private suspend fun fetchHydratedAuth(): LezhinAuthDto? {
        return try {
            val document =
                client
                    .get(
                        baseUrl,
                        headers,
                        ensureSuccess =
                        false,
                    )
                    .use { response ->
                        if (
                            !response
                                .isSuccessful
                        ) {
                            return null
                        }

                        response
                            .asJsoup()
                    }

            document
                .extractNextJs<
                    LezhinAuthDto,
                    > { element ->
                    element is JsonObject &&
                        "accessToken" in element
                }
        } catch (e: Throwable) {
            Log.w(
                LOG_TAG,
                "Failed to extract hydrated auth data",
                e,
            )

            null
        }
    }

    private suspend fun loginWithWebView(
        email: String,
        password: String,
    ): LezhinAuthDto? {
        return try {
            val emailJs =
                email
                    .toJsonString()

            val passwordJs =
                password
                    .toJsonString()

            val result =
                runWebView {
                    blockImages =
                        true

                    javaScriptEnabled =
                        true

                    domStorageEnabled =
                        true

                    var submitted =
                        false

                    jsBridge(
                        LOGIN_BRIDGE,
                    ) { message ->
                        resolve(
                            message,
                        )
                    }

                    onPageFinished {
                        if (
                            submitted
                        ) {
                            return@onPageFinished
                        }

                        submitted =
                            true

                        evaluateJs(
                            """
                            (async () => {
                                try {
                                    const response = await fetch(
                                        new URL(
                                            '/api/authentication/login',
                                            window.location.origin
                                        ),
                                        {
                                            method: 'POST',
                                            headers: {
                                                'Content-Type': 'application/json'
                                            },
                                            body: JSON.stringify({
                                                email: $emailJs,
                                                password: $passwordJs,
                                                remember: 'false',
                                                provider: 'email',
                                                language: JSON.stringify(
                                                    window.location.pathname
                                                        .split('/')
                                                        .at(1)
                                                )
                                            })
                                        }
                                    );

                                    const text = await response.text();

                                    window.$LOGIN_BRIDGE.post(
                                        text
                                    );
                                } catch (error) {
                                    window.$LOGIN_BRIDGE.post(
                                        JSON.stringify({
                                            error: String(error)
                                        })
                                    );
                                }
                            })();
                            """.trimIndent(),
                        )
                    }

                    loadUrl(
                        "$baseUrl/$pathSegment/login",
                    )
                }

            val root =
                JSONObject(
                    result,
                )

            val appConfig =
                root
                    .optJSONObject(
                        "appConfig",
                    )
                    ?: return null

            val accessToken =
                appConfig
                    .optString(
                        "accessToken",
                    )
                    .takeIf {
                        it.isNotBlank()
                    }
                    ?: return null

            val userId =
                if (
                    appConfig.has(
                        "id",
                    )
                ) {
                    appConfig
                        .optLong(
                            "id",
                        )
                } else {
                    null
                }

            LezhinAuthDto(
                id =
                userId,
                accessToken =
                accessToken,
            )
        } catch (e: Throwable) {
            Log.e(
                LOG_TAG,
                "Lezhin login failed",
                e,
            )

            null
        }
    }

    private suspend fun forceLanguage() {
        if (
            currentToken()
                .isNullOrBlank()
        ) {
            return
        }

        val body =
            LezhinLocaleRequestDto(
                localeRegion =
                siteLocale,
            )
                .toJsonRequestBody()

        try {
            client
                .post(
                    "$baseUrl/api/locale",
                    authorizedHeaders(),
                    body,
                    ensureSuccess =
                    false,
                )
                .close()

            Log.d(
                LOG_TAG,
                "Forced Lezhin locale to $siteLocale",
            )
        } catch (e: Throwable) {
            Log.w(
                LOG_TAG,
                "Failed to force Lezhin locale",
                e,
            )
        }
    }

    private suspend fun updateCdn(
        forced: Boolean = false,
    ) {
        if (
            cdnBase != null &&
            !forced
        ) {
            return
        }

        val html =
            try {
                client
                    .get(
                        "$baseUrl/account",
                        authorizedHeaders(),
                        ensureSuccess =
                        false,
                    )
                    .use { response ->
                        if (
                            response
                                .isSuccessful
                        ) {
                            response
                                .body
                                .string()
                        } else {
                            ""
                        }
                    }
            } catch (_: Throwable) {
                ""
            }

        extractCdnFromHtml(
            html,
        )
            ?.let {
                cdnBase =
                    it

                return
            }

        val webViewCdn =
            runCatching {
                runWebView {
                    blockImages =
                        true

                    onPageFinished {
                        evaluateJs(
                            """
                            (() => {
                                try {
                                    return (
                                        window.__LZ_CONFIG__
                                            ?.contentsCdnUrl ??
                                        JSON.parse(
                                            document
                                                .querySelector('#lz-static')
                                                ?.dataset
                                                ?.env ?? '{}'
                                        ).CONTENT_CDN_URL ??
                                        null
                                    );
                                } catch (_) {
                                    return null;
                                }
                            })()
                            """.trimIndent(),
                        ) { value ->
                            resolve(
                                value.parseAs<
                                    String?,
                                    >(),
                            )
                        }
                    }

                    val webViewHeaders =
                        buildMap {
                            put(
                                "x-lz-locale",
                                siteLocale,
                            )

                            currentToken()
                                ?.let { token ->
                                    put(
                                        "Authorization",
                                        "Bearer $token",
                                    )
                                }
                        }

                    loadUrl(
                        "$baseUrl/account",
                        webViewHeaders,
                    )
                }
            }
                .getOrNull()

        cdnBase =
            webViewCdn
                ?.takeIf {
                    it.isNotBlank()
                }
    }

    private fun extractCdnFromHtml(
        html: String,
    ): String? {
        if (
            html.isBlank()
        ) {
            return null
        }

        Regex(
            """["']?contentsCdnUrl["']?\s*[:=]\s*["']([^"']+)["']""",
        )
            .find(
                html,
            )
            ?.groupValues
            ?.getOrNull(
                1,
            )
            ?.replace(
                "\\/",
                "/",
            )
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                return it
            }

        val document =
            Jsoup.parse(
                html,
            )

        val environment =
            document
                .selectFirst(
                    "#lz-static",
                )
                ?.attr(
                    "data-env",
                )
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return null

        return runCatching {
            JSONObject(
                environment,
            )
                .optString(
                    "CONTENT_CDN_URL",
                )
                .takeIf {
                    it.isNotBlank()
                }
        }
            .getOrNull()
    }

    // ============================== Token ================================

    private fun saveAuth(
        auth: LezhinAuthDto,
    ) {
        val token =
            auth
                .accessToken
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: return

        val expiration =
            extractTokenExpiration(
                token,
            )

        preferences
            .edit()
            .putString(
                PREF_TOKEN,
                token,
            )
            .putLong(
                PREF_TOKEN_EXPIRES,
                expiration,
            )
            .apply()

        val userId =
            auth.id

        if (
            userId != null
        ) {
            preferences
                .edit()
                .putString(
                    PREF_USER_ID,
                    userId.toString(),
                )
                .apply()
        } else {
            preferences
                .edit()
                .remove(
                    PREF_USER_ID,
                )
                .apply()
        }
    }

    private fun extractTokenExpiration(
        token: String,
    ): Long {
        return runCatching {
            val segment =
                token
                    .split('.')
                    .getOrNull(
                        1,
                    )
                    ?: return@runCatching 0L

            val padding =
                "=".repeat(
                    (
                        4 -
                            segment.length %
                            4
                        ) %
                        4,
                )

            val bytes =
                Base64.decode(
                    segment +
                        padding,
                    Base64.URL_SAFE or
                        Base64.NO_WRAP,
                )

            val json =
                JSONObject(
                    bytes.toString(
                        Charsets.UTF_8,
                    ),
                )

            json
                .optLong(
                    "exp",
                    0L,
                ) *
                1000L
        }
            .getOrDefault(
                0L,
            )
    }

    private fun tokenExpired(): Boolean {
        val expiration =
            preferences
                .getLong(
                    PREF_TOKEN_EXPIRES,
                    0L,
                )

        if (
            expiration == 0L
        ) {
            return false
        }

        return System
            .currentTimeMillis() +
            TOKEN_EXPIRY_MARGIN_MS >=
            expiration
    }

    private fun currentToken(): String? = preferences
        .getString(
            PREF_TOKEN,
            null,
        )
        ?.takeIf {
            it.isNotBlank()
        }

    private fun isLogged(): Boolean = preferences
        .getString(
            PREF_USER_ID,
            null,
        )
        .isNullOrBlank()
        .not()

    private fun clearAuth() {
        preferences
            .edit()
            .remove(
                PREF_TOKEN,
            )
            .remove(
                PREF_TOKEN_EXPIRES,
            )
            .remove(
                PREF_USER_ID,
            )
            .apply()
    }

    private fun clearSession() {
        clearAuth()

        cdnBase =
            null

        sessionInitialized =
            false
    }

    // =============================== HTTP ================================

    private fun authorizedHeaders(
        adult: Boolean = false,
    ): Headers = headers
        .newBuilder()
        .apply {
            set(
                "Origin",
                baseUrl,
            )

            set(
                "Referer",
                "$baseUrl/",
            )

            currentToken()
                ?.let { token ->
                    set(
                        "Authorization",
                        "Bearer $token",
                    )
                }

            if (
                adult
            ) {
                set(
                    "X-LZ-Adult",
                    "2",
                )

                set(
                    "X-LZ-AllowAdult",
                    "true",
                )
            }
        }
        .build()

    private suspend fun apiGet(
        url: HttpUrl,
        adult: Boolean = false,
    ): Response {
        updateSession()

        var response =
            client
                .get(
                    url,
                    authorizedHeaders(
                        adult =
                        adult,
                    ),
                    ensureSuccess =
                    false,
                )

        if (
            response.code ==
            401
        ) {
            response
                .close()

            updateSession(
                force =
                true,
            )

            response =
                client
                    .get(
                        url,
                        authorizedHeaders(
                            adult =
                            adult,
                        ),
                        ensureSuccess =
                        false,
                    )
        }

        if (
            !response
                .isSuccessful
        ) {
            val code =
                response.code

            response
                .close()

            throw IOException(
                "Lezhin API returned HTTP $code",
            )
        }

        return response
    }

    private fun resolveSourceUrl(
        value: String,
    ): String {
        if (
            value.startsWith(
                "http://",
            ) ||
            value.startsWith(
                "https://",
            )
        ) {
            return value
        }

        return if (
            value.startsWith('/')
        ) {
            "$baseUrl$value"
        } else {
            "$baseUrl/$value"
        }
    }

    // ========================== Image Descramble =========================

    private fun imageDescrambler(
        chain: Interceptor.Chain,
    ): Response {
        val request =
            chain.request()

        val fragment =
            request
                .url
                .fragment
                ?: return chain
                    .proceed(
                        request,
                    )

        if (
            !fragment
                .startsWith(
                    "lezhin_eid=",
                )
        ) {
            return chain
                .proceed(
                    request,
                )
        }

        val parameters =
            parseFragmentParameters(
                fragment,
            )

        val episodeId =
            parameters[
                "lezhin_eid",
            ]
                ?.toIntOrNull()
                ?: return chain
                    .proceed(
                        request,
                    )

        val gridSize =
            parameters[
                "cols",
            ]
                ?.toIntOrNull()
                ?: 5

        val response =
            chain
                .proceed(
                    request,
                )

        if (
            !response
                .isSuccessful
        ) {
            return response
        }

        val originalMediaType =
            response
                .body
                .contentType()

        val bytes =
            try {
                response
                    .body
                    .bytes()
            } catch (e: Throwable) {
                Log.e(
                    LOG_TAG,
                    "Failed to read shuffled image",
                    e,
                )

                return response
            }

        val descrambled =
            try {
                LezhinDescrambler
                    .descramble(
                        input =
                        bytes,
                        episodeId =
                        episodeId,
                        gridSize =
                        gridSize,
                    )
            } catch (e: Throwable) {
                Log.e(
                    LOG_TAG,
                    "Failed to descramble image",
                    e,
                )

                bytes
            }

        val mediaType =
            if (
                descrambled ===
                bytes
            ) {
                originalMediaType
            } else {
                "image/png"
                    .toMediaTypeOrNull()
            }

        return response
            .newBuilder()
            .body(
                descrambled
                    .toResponseBody(
                        mediaType,
                    ),
            )
            .build()
    }

    private data class LatestEntry(
        val manga: SManga,
        val updated: Boolean,
    )

    private companion object {
        const val PREF_EMAIL =
            "lezhin_email"

        const val PREF_PASSWORD =
            "lezhin_password"

        const val PREF_IMAGE_FORMAT =
            "lezhin_image_format"

        const val PREF_TOKEN =
            "lezhin_token"

        const val PREF_TOKEN_EXPIRES =
            "lezhin_token_expires"

        const val PREF_USER_ID =
            "lezhin_user_id"

        const val IMAGE_FORMAT_WEBP =
            ".webp"

        const val IMAGE_FORMAT_JPEG =
            ".jpeg"

        const val LOGIN_BRIDGE =
            "LezhinLoginBridge"

        const val TOKEN_EXPIRY_MARGIN_MS =
            30_000L
    }
}

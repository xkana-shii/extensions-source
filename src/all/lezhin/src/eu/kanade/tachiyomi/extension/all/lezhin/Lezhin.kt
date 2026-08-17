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
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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
            summary =
                "Email used to log in to Lezhin"
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
            summary =
                "Password used to log in to Lezhin"
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

        val offset =
            (page - 1) * perPage

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

        val result =
            apiGet(
                url = url,
                adult = true,
            )
                .parseAs<LezhinContentListDto>()

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
    ): MangasPage = getPopularManga(page)

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val perPage = 30

        val offset =
            (page - 1) * perPage

        val selectedIds =
            selectedTagIds(filters)

        val selectedNames =
            selectedTagNames(filters)

        if (
            query.isBlank() &&
            selectedIds.isEmpty() &&
            selectedNames.isEmpty()
        ) {
            return getPopularManga(page)
        }

        val url = if (
            selectedIds.isNotEmpty()
        ) {
            "${apiBase}advanced-search/multitags"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter(
                    "int_id",
                    selectedIds.joinToString(","),
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
                        selectedNames.isNotEmpty()
                    ) {
                        addQueryParameter(
                            "tags",
                            selectedNames.joinToString(","),
                        )
                    }
                }
                .build()
        }

        val result =
            apiGet(
                url = url,
                adult = true,
            )
                .parseAs<LezhinContentListDto>()

        return MangasPage(
            mangas = result.data.map {
                it.toSManga(
                    pathSegment,
                )
            },
            hasNextPage = result.hasNext,
        )
    }

    // ============================== Filters ==============================

    override val supportsFilterFetching =
        true

    override val filterFetchHint =
        "Tap 'Reset' to load Lezhin tags"

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

        if (divisions.isEmpty()) {
            throw IOException(
                "Unable to load Lezhin tags",
            )
        }

        return divisions.toJsonElement()
    }

    override fun getFilterList(
        data: JsonElement?,
    ): FilterList {
        if (data == null) {
            return FilterList()
        }

        val divisions = runCatching {
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
            url.host != sourceHost
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
                    it.isNotEmpty()
                }
                ?: return null

        updateSession()

        val manga = SManga
            .create()
            .apply {
                this.url =
                    "/$pathSegment/comic/$alias"

                title = alias
            }

        val document = client
            .get(
                "$baseUrl${manga.url}",
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
        updateSession()

        val document = client
            .get(
                "$baseUrl${manga.url}",
                authorizedHeaders(
                    adult = true,
                ),
            )
            .use {
                it.asJsoup()
            }

        return SMangaUpdate(
            manga = parseMangaDetails(
                document = document,
                manga = manga,
            ),
            chapters = parseChapterList(
                document = document,
                manga = manga,
            ),
        )
    }

    private fun parseMangaDetails(
        document: Document,
        manga: SManga,
    ): SManga {
        val title =
            document.selectFirst(
                "div.lzSection p[class*=-Head3xl]",
            )
                ?.text()
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?: document
                    .selectFirst("h1")
                    ?.text()
                    ?.takeIf {
                        it.isNotEmpty()
                    }
                ?: document
                    .title()
                    .takeIf {
                        it.isNotEmpty()
                    }
                ?: manga.title

        val description =
            document.selectFirst(
                "meta[name=description]",
            )
                ?.attr("content")
                ?.takeIf {
                    it.isNotEmpty()
                }
                ?: document
                    .selectFirst("p.summary")
                    ?.text()
                    ?.takeIf {
                        it.isNotEmpty()
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
                        .takeIf {
                            it.isNotEmpty()
                        }
                        ?: image.attr("src")

                if (
                    source.isNotEmpty()
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
                        .takeIf {
                            it.isNotEmpty()
                        }
                        ?: image.attr("src")

                if (
                    source.isNotEmpty()
                ) {
                    coverCandidates +=
                        source
                }
            }

        document
            .selectFirst(
                "meta[property=og:image]",
            )
            ?.attr("content")
            ?.takeIf {
                it.isNotEmpty()
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
                    ) ||
                        it.endsWith(
                            "tall.jpg",
                            ignoreCase = true,
                        ) ||
                        it.endsWith(
                            "tall.webp",
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
                val role = node
                    .selectFirst(
                        ".episodeListDetail__artistName__gD_OK",
                    )
                    ?.text()
                    ?.lowercase()
                    ?: node
                        .selectFirst("span")
                        ?.text()
                        ?.lowercase()

                val creator = node
                    .selectFirst("a")
                    ?.text()
                    ?.takeIf {
                        it.isNotEmpty()
                    }
                    ?: node
                        .ownText()
                        .takeIf {
                            it.isNotEmpty()
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
                        ) == true -> {
                        authors += creator
                    }

                    role?.contains(
                        "painter",
                    ) == true ||
                        role?.contains(
                            "artist",
                        ) == true -> {
                        artists += creator
                    }

                    role?.contains(
                        "original",
                    ) == true ||
                        role?.contains(
                            "origin",
                        ) == true -> {
                        originals += creator
                    }

                    else -> {
                        authors += creator
                    }
                }
            }

        val tags = document
            .select(
                "a[href*=/tags/]",
            )
            .map {
                it.text()
            }
            .filter {
                it.isNotEmpty()
            }
            .distinct()

        val pageText =
            document.text()

        val status = when {
            pageText.contains(
                "COMPLETED",
                ignoreCase = true,
            ) ||
                pageText.contains(
                    "COMPLETE",
                    ignoreCase = true,
                ) -> {
                SManga.COMPLETED
            }

            pageText.contains(
                "NEW",
                ignoreCase = true,
            ) -> {
                SManga.ONGOING
            }

            else -> {
                manga.status
            }
        }

        return SManga
            .create()
            .apply {
                url = manga.url

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
                        combinedAuthors.isNotEmpty()
                    ) {
                        combinedAuthors
                            .joinToString(", ")
                    } else {
                        manga.author
                    }

                artist =
                    if (
                        artists.isNotEmpty()
                    ) {
                        artists
                            .distinct()
                            .joinToString(", ")
                    } else {
                        manga.artist
                    }

                genre =
                    if (
                        tags.isNotEmpty()
                    ) {
                        tags.joinToString(", ")
                    } else {
                        manga.genre
                    }

                this.status =
                    status

                thumbnail_url =
                    selectedCover
                        ?.let { cover ->
                            if (
                                cover.contains(
                                    "/images/wide",
                                    ignoreCase = true,
                                )
                            ) {
                                cover.replace(
                                    "/images/wide",
                                    "/images/tall",
                                    ignoreCase = true,
                                )
                            } else {
                                cover
                            }
                        }
                        ?: manga.thumbnail_url
            }
    }

    private fun parseChapterList(
        document: Document,
        manga: SManga,
    ): List<SChapter> {
        val hydrated = document
            .extractNextJs<
                LezhinHydratedChaptersDto,
                > { element ->
                element is JsonObject &&
                    "episodes" in element
            }

        if (hydrated != null) {
            return hydrated
                .episodes
                .map { episode ->
                    SChapter
                        .create()
                        .apply {
                            url =
                                manga.url
                                    .trimEnd('/') +
                                "/" +
                                episode.name

                            name =
                                episode
                                    .display
                                    .title

                            parseChapterNumber(
                                episode.name,
                            )?.let {
                                chapter_number =
                                    it
                            }
                        }
                }
        }

        return document
            .select(
                "div[data-id] a[href]",
            )
            .mapNotNull { anchor ->
                val href =
                    anchor
                        .attr("href")
                        .takeIf {
                            it.isNotEmpty()
                        }
                        ?: return@mapNotNull null

                val title =
                    anchor
                        .selectFirst("h3")
                        ?.text()
                        ?.takeIf {
                            it.isNotEmpty()
                        }
                        ?: anchor
                            .text()
                            .takeIf {
                                it.isNotEmpty()
                            }
                        ?: return@mapNotNull null

                SChapter
                    .create()
                    .apply {
                        url = href
                        name = title

                        parseChapterNumber(
                            href
                                .substringAfterLast('/'),
                        )?.let {
                            chapter_number =
                                it
                        }
                    }
            }
    }

    private fun parseChapterNumber(
        value: String,
    ): Float? = Regex(
        """\d+(?:\.\d+)?""",
    )
        .find(value)
        ?.value
        ?.toFloatOrNull()

    // ============================== Reader ===============================

    override suspend fun getPageList(
        chapter: SChapter,
    ): List<Page> {
        updateSession()

        val document = client
            .get(
                "$baseUrl${chapter.url}",
                authorizedHeaders(
                    adult = true,
                ),
            )
            .use {
                it.asJsoup()
            }

        val queries = document
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
                    it.queryKey
                        .firstOrNull() ==
                        "viewer-static-state"
                }
                ?: throw IOException(
                    "Lezhin viewer-static-state not found",
                )

        val userQuery =
            queries
                .queries
                .firstOrNull {
                    it.queryKey
                        .firstOrNull() ==
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
                        it.state
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

        if (media.isEmpty()) {
            throw IOException(
                "No readable pages found. " +
                    "This chapter may be locked or unavailable.",
            )
        }

        if (cdnBase == null) {
            updateCdn(
                forced = true,
            )
        }

        val cdn =
            cdnBase
                ?: throw IOException(
                    "Unable to determine Lezhin CDN",
                )

        return media.mapIndexed {
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
                    append(";episode=")
                    append(
                        viewer.episode.id,
                    )
                    append(";purchased=")
                    append(
                        status.isPurchased,
                    )
                    append(";updated=")
                    append(
                        viewer.episode.updatedAt,
                    )
                    append(";shuffle=")
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
                    .fragment(metadata)
                    .build()
                    .toString()

            Page(
                index = index,
                url = pageUrl,
            )
        }
    }

    override suspend fun getImageUrl(
        page: Page,
    ): String {
        val pageUrl =
            page.url.toHttpUrl()

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
                .fragment(null)
                .build()

        val signed =
            createSignedImageUrl(
                comicId = comicId,
                episodeId = episodeId,
                purchased = purchased,
                updatedAt = updatedAt,
                imageUrl =
                unsignedImageUrl,
            )

        return if (shuffled) {
            signed
                .newBuilder()
                .fragment(
                    "lezhin_eid=$episodeId;cols=5",
                )
                .build()
                .toString()
        } else {
            signed.toString()
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
                url = signingUrl,
            )
                .parseAs<
                    LezhinSignedUrlResponseDto,
                    >()
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
        sessionMutex.withLock {
            if (
                !force &&
                sessionInitialized &&
                !tokenExpired()
            ) {
                return
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

            val token =
                currentToken()

            if (
                force ||
                token.isNullOrBlank() ||
                tokenExpired() ||
                (
                    hasCredentials &&
                        !isLogged()
                    )
            ) {
                fetchHydratedAuth()
                    ?.takeIf {
                        !it.accessToken
                            .isNullOrBlank()
                    }
                    ?.let(
                        ::saveAuth,
                    )
            }

            if (
                hasCredentials &&
                (
                    force ||
                        !isLogged() ||
                        tokenExpired()
                    )
            ) {
                loginWithWebView(
                    email = email,
                    password = password,
                )
                    ?.takeIf {
                        !it.accessToken
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

            updateCdn(
                forced = force,
            )

            sessionInitialized =
                true
        }
    }

    private suspend fun fetchHydratedAuth(): LezhinAuthDto? {
        return try {
            val document = client
                .get(
                    baseUrl,
                    headers,
                    ensureSuccess = false,
                )
                .use { response ->
                    if (
                        !response.isSuccessful
                    ) {
                        return null
                    }

                    response.asJsoup()
                }

            document
                .extractNextJs<
                    LezhinAuthDto,
                    > { element ->
                    element is JsonObject &&
                        "accessToken" in
                        element
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
                email.toJsonString()

            val passwordJs =
                password.toJsonString()

            val languageJs =
                pathSegment.toJsonString()

            val result =
                runWebView {
                    blockImages = true
                    javaScriptEnabled = true
                    domStorageEnabled = true

                    var submitted =
                        false

                    jsBridge(
                        LOGIN_BRIDGE,
                    ) { message ->
                        resolve(message)
                    }

                    onPageFinished {
                        if (submitted) {
                            return@onPageFinished
                        }

                        submitted = true

                        evaluateJs(
                            """
                            (async () => {
                                try {
                                    const response = await fetch(
                                        new URL('/api/authentication/login', window.location.origin),
                                        {
                                            method: 'POST',
                                            headers: {
                                                'Content-Type': 'application/json'
                                            },
                                            body: JSON.stringify({
                                                email: $emailJs,
                                                password: $passwordJs,
                                                remember: false,
                                                provider: 'email',
                                                language: $languageJs
                                            })
                                        }
                                    );

                                    const text = await response.text();
                                    window.$LOGIN_BRIDGE.post(text);
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
                JSONObject(result)

            val appConfig =
                root.optJSONObject(
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
                    appConfig.has("id")
                ) {
                    appConfig.optLong(
                        "id",
                    )
                } else {
                    null
                }

            LezhinAuthDto(
                id = userId,
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
                    ensureSuccess = false,
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

        val html = try {
            client
                .get(
                    "$baseUrl/account",
                    authorizedHeaders(),
                    ensureSuccess = false,
                )
                .use { response ->
                    if (
                        response.isSuccessful
                    ) {
                        response.body.string()
                    } else {
                        ""
                    }
                }
        } catch (_: Throwable) {
            ""
        }

        extractCdnFromHtml(
            html,
        )?.let {
            cdnBase = it

            return
        }

        val webViewCdn = runCatching {
            runWebView {
                blockImages = true

                onPageFinished {
                    evaluateJs(
                        """
                        (() => {
                            try {
                                return (
                                    window.__LZ_CONFIG__?.contentsCdnUrl ??
                                    JSON.parse(
                                        document.querySelector('#lz-static')?.dataset?.env ?? '{}'
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

                loadUrl(
                    "$baseUrl/account",
                )
            }
        }.getOrNull()

        cdnBase =
            webViewCdn
                ?.takeIf {
                    it.isNotBlank()
                }
    }

    private fun extractCdnFromHtml(
        html: String,
    ): String? {
        if (html.isBlank()) {
            return null
        }

        Regex(
            """["']?contentsCdnUrl["']?\s*[:=]\s*["']([^"']+)["']""",
        )
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
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
            Jsoup.parse(html)

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
        }.getOrNull()
    }

    // ============================== Token ================================

    private fun saveAuth(
        auth: LezhinAuthDto,
    ) {
        val token =
            auth.accessToken
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

        if (
            auth.id != null
        ) {
            preferences
                .edit()
                .putString(
                    PREF_USER_ID,
                    auth.id.toString(),
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
                    .getOrNull(1)
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
                    segment + padding,
                    Base64.URL_SAFE or
                        Base64.NO_WRAP,
                )

            val json =
                JSONObject(
                    bytes.toString(
                        Charsets.UTF_8,
                    ),
                )

            json.optLong(
                "exp",
                0L,
            ) * 1000L
        }.getOrDefault(
            0L,
        )
    }

    private fun tokenExpired(): Boolean {
        val expiration =
            preferences.getLong(
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

        cdnBase = null
        sessionInitialized =
            false
    }

    // =============================== HTTP ================================

    private fun authorizedHeaders(
        adult: Boolean = false,
    ): Headers = headers
        .newBuilder()
        .apply {
            currentToken()
                ?.let { token ->
                    set(
                        "Authorization",
                        "Bearer $token",
                    )
                }

            if (adult) {
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
            client.get(
                url,
                authorizedHeaders(
                    adult = adult,
                ),
                ensureSuccess = false,
            )

        if (
            response.code == 401
        ) {
            response.close()

            updateSession(
                force = true,
            )

            response =
                client.get(
                    url,
                    authorizedHeaders(
                        adult = adult,
                    ),
                    ensureSuccess = false,
                )
        }

        if (
            !response.isSuccessful
        ) {
            val code =
                response.code

            response.close()

            throw IOException(
                "Lezhin API returned HTTP $code",
            )
        }

        return response
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
                ?: return chain.proceed(
                    request,
                )

        if (
            !fragment.startsWith(
                "lezhin_eid=",
            )
        ) {
            return chain.proceed(
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
                ?: return chain.proceed(
                    request,
                )

        val gridSize =
            parameters[
                "cols",
            ]
                ?.toIntOrNull()
                ?: 5

        val response =
            chain.proceed(
                request,
            )

        if (
            !response.isSuccessful
        ) {
            return response
        }

        val originalMediaType =
            response
                .body
                .contentType()

        val bytes = try {
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

        val descrambled = try {
            LezhinDescrambler
                .descramble(
                    input = bytes,
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
                descrambled === bytes
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

package eu.kanade.tachiyomi.extension.all.patreon

import android.text.InputType
import android.webkit.CookieManager
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
import keiyoushi.utils.toJsonString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Suppress("unused")
@Source
abstract class Patreon :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val postPagesCache: MutableMap<String, List<String>> =
        LruCache(POST_PAGES_CACHE_SIZE)

    private val searchCursorCache: MutableMap<String, MutableMap<Int, String?>> =
        LruCache(SEARCH_CURSOR_CACHE_SIZE)

    private val autoLoginMutex = Mutex()

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("User-Agent", USER_AGENT)
        set("Accept", "application/json, text/plain, */*")
        set("X-Requested-With", "XMLHttpRequest")
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (page > 1) return MangasPage(emptyList(), false)

        tryOrNull {
            fetchExploreSections()
        }?.let { result ->
            if (result.mangas.isNotEmpty()) {
                return result
            }
        }

        return MangasPage(emptyList(), false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        if (filters.membershipsOnly()) {
            if (page > 1) return MangasPage(emptyList(), false)

            maybeAutoLogin()

            val memberships = fetchCurrentUserMemberships().mangas
            val filteredMemberships = if (query.isBlank()) {
                memberships
            } else {
                memberships.filter { manga ->
                    manga.title.contains(query, ignoreCase = true) ||
                        manga.author.orEmpty().contains(query, ignoreCase = true) ||
                        manga.artist.orEmpty().contains(query, ignoreCase = true)
                }
            }

            return MangasPage(filteredMemberships, false)
        }

        if (query.isBlank()) {
            if (page > 1) return MangasPage(emptyList(), false)
            return fetchExploreSections()
        }

        maybeAutoLogin()

        if (hasPatreonSession()) {
            tryOrNull {
                fetchLoggedInSearch(page, query)
            }?.let { result ->
                if (result.mangas.isNotEmpty()) {
                    return result
                }
            }
        }

        tryOrNull {
            fetchAnonymousSearch(page, query)
        }?.let { result ->
            if (result.mangas.isNotEmpty()) {
                return result
            }
        }

        return fetchSearchHtmlFallback(page, query)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val sourceHost = baseUrl.toHttpUrl().host.removePrefix("www.")
        val targetHost = url.host.removePrefix("www.")

        if (!sourceHost.equals(targetHost, ignoreCase = true)) {
            return null
        }

        val campaignId = url.encodedPath.extractCampaignIdFromSourceUrl()
            ?: fetchCampaignIdFromPage(url.toString())

        return fetchCampaignManga(campaignId)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (fetchChapters) {
            maybeAutoLogin()
        }

        return coroutineScope {
            val campaignId = manga.url.extractCampaignIdFromSourceUrl()
                ?: resolveCampaignId(manga.url)

            val mangaDeferred = if (fetchDetails) {
                async {
                    fetchCampaignManga(campaignId).apply {
                        if (manga.title.isNotBlank()) {
                            title = manga.title
                        }

                        manga.thumbnail_url
                            ?.takeIf { thumbnail -> thumbnail.isNotBlank() }
                            ?.let { thumbnail ->
                                thumbnail_url = thumbnail
                            }
                    }
                }
            } else {
                null
            }

            val chaptersDeferred = if (fetchChapters) {
                async {
                    fetchChapters(campaignId)
                }
            } else {
                null
            }

            SMangaUpdate(
                manga = mangaDeferred?.await() ?: manga,
                chapters = chaptersDeferred?.await() ?: chapters,
            )
        }
    }

    private suspend fun fetchChapters(campaignId: String): List<SChapter> {
        val maxPages = preferences
            .getString(POST_PAGES_PREF, POST_PAGES_DEFAULT)!!
            .toInt()

        val requestHeaders = patreonHeaders(requireLogin = false)

        val chapters = mutableListOf<SChapter>()
        var nextUrl: String? = postsApiUrl(campaignId)
        var page = 0

        while (page < maxPages) {
            val requestUrl = nextUrl
                ?.takeIf { url -> url.isNotBlank() }
                ?: break

            page++

            val root = getJson<PatreonApiRoot>(
                requestUrl,
                requestHeaders,
            ) { code ->
                "Patreon HTTP $code. Check your login and that your account can view this creator."
            }

            root.dataPosts(json).forEach { post ->
                if (post.isLocked()) {
                    if (!hideLockedChapters()) {
                        chapters.add(
                            post.toSChapter(
                                campaignId,
                                locked = true,
                            ),
                        )
                    }

                    return@forEach
                }

                val imageUrls = post.imageUrls(root, json)

                if (imageUrls.isNotEmpty()) {
                    postPagesCache[post.id] = imageUrls
                    chapters.add(post.toSChapter(campaignId))
                }
            }

            nextUrl = root.links?.next?.absolutePatreonUrl()
        }

        return chapters.distinctBy { chapter -> chapter.url }
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val postId = chapter.url.extractPostIdFromChapterUrl()
        return "$baseUrl/posts/$postId"
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        maybeAutoLogin()

        val postId = chapter.url.extractPostIdFromChapterUrl()

        postPagesCache[postId]?.let { cachedUrls ->
            return cachedUrls.toPages()
        }

        val root = getJson<PatreonApiRoot>(
            postApiUrl(postId),
            patreonHeaders(requireLogin = false),
        ) { code ->
            "Patreon HTTP $code. This post may be locked, expired, or blocked by Patreon."
        }

        val post = root.dataPosts(json).firstOrNull()
            ?: return emptyList()

        if (post.isLocked()) {
            throw IOException(
                "This Patreon post is locked. You need a higher membership tier to read it.",
            )
        }

        val urls = post.imageUrls(root, json)

        postPagesCache[post.id] = urls

        return urls.toPages()
    }

    override fun imageRequest(page: Page): Request {
        val imageUrl = page.imageUrl!!
        val imageHost = imageUrl.toHttpUrlOrNull()?.host

        val imageHeaders = if (
            imageHost == "patreon.com" ||
            imageHost?.endsWith(".patreon.com") == true
        ) {
            patreonHeaders(requireLogin = false).newBuilder()
        } else {
            headers.newBuilder()
        }
            .set(
                "Accept",
                "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            )
            .build()

        return GET(imageUrl, imageHeaders)
    }

    @Suppress("UNUSED_PARAMETER")
    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        MembershipsOnlyFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = AUTO_LOGIN_EMAIL_PREF
            title = "Patreon email"
            summary = "Email used for automatic login when your Patreon session expires."
            setDefaultValue("")

            setOnBindEditTextListener { editText ->
                editText.inputType =
                    InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS

                editText.isSingleLine = true
            }
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = AUTO_LOGIN_PASSWORD_PREF
            title = "Patreon password"
            summary = "Password used for automatic login. After one failed attempt, automatic login will not try again for 24 hours."
            setDefaultValue("")

            setOnBindEditTextListener { editText ->
                editText.inputType =
                    InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_PASSWORD

                editText.isSingleLine = true
            }
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = POST_PAGES_PREF
            title = "Maximum post pages to load"
            summary = "Loading more pages costs more time and network traffic. Currently: %s"
            entryValues = POST_PAGE_OPTIONS
            entries = POST_PAGE_OPTIONS.map { option -> "$option pages" }.toTypedArray()
            setDefaultValue(POST_PAGES_DEFAULT)
        }.let(screen::addPreference)

        CheckBoxPreference(screen.context).apply {
            key = HIDE_LOCKED_CHAPTERS_PREF
            title = "Hide locked chapters"
            summary = "Hide Patreon posts that your current membership cannot view. When disabled, locked posts appear with a 🔒 prefix."
            setDefaultValue(false)
        }.let(screen::addPreference)
    }

    private suspend fun maybeAutoLogin(): Boolean = autoLoginMutex.withLock {
        if (hasPatreonSession()) {
            clearAutoLoginFailure()
            return@withLock true
        }

        val email = preferences
            .getString(AUTO_LOGIN_EMAIL_PREF, "")
            .orEmpty()
            .trim()

        val password = preferences
            .getString(AUTO_LOGIN_PASSWORD_PREF, "")
            .orEmpty()

        if (email.isBlank() || password.isBlank()) {
            return@withLock false
        }

        if (isAutoLoginCoolingDown()) {
            return@withLock false
        }

        val success = try {
            performAutoLogin(email, password)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

        if (success && hasPatreonSession()) {
            CookieManager.getInstance().flush()
            clearAutoLoginFailure()
            true
        } else {
            markAutoLoginFailure()
            false
        }
    }

    private suspend fun performAutoLogin(
        email: String,
        password: String,
    ): Boolean = runWebView(
        timeout = AUTO_LOGIN_TIMEOUT_SECONDS.seconds,
    ) {
        javaScriptEnabled = true
        domStorageEnabled = true
        blockImages = true
        userAgent = USER_AGENT

        var submitted = false
        var evaluating = false

        poll(AUTO_LOGIN_POLL_INTERVAL_MS.milliseconds) {
            if (hasPatreonSession()) {
                resolve(true)
                return@poll
            }

            if (evaluating) {
                return@poll
            }

            evaluating = true

            val script = if (submitted) {
                AUTO_LOGIN_CHECK_SCRIPT
            } else {
                autoLoginScript(email, password)
            }

            evaluateJs(script) { rawResult ->
                evaluating = false

                when (
                    runCatching {
                        rawResult.parseAs<String?>()
                    }.getOrNull()
                ) {
                    "submitted" -> submitted = true
                    "failed" -> resolve(false)
                }
            }
        }

        loadUrl(loginUrl())
    }

    private fun autoLoginScript(
        email: String,
        password: String,
    ): String {
        val encodedEmail = email.toJsonString()
        val encodedPassword = password.toJsonString()

        return """
            (() => {
                const email = $encodedEmail;
                const password = $encodedPassword;

                const emailInput = document.querySelector(
                    'input[type="email"], input[name="email"], input[autocomplete="email"], input[autocomplete="username"]'
                );

                const passwordInput = document.querySelector(
                    'input[type="password"], input[name="password"], input[autocomplete="current-password"]'
                );

                if (!emailInput || !passwordInput) {
                    return 'waiting';
                }

                const setValue = (input, value) => {
                    const descriptor = Object.getOwnPropertyDescriptor(
                        HTMLInputElement.prototype,
                        'value'
                    );

                    if (descriptor && descriptor.set) {
                        descriptor.set.call(input, value);
                    } else {
                        input.value = value;
                    }

                    input.dispatchEvent(new Event('input', {
                        bubbles: true
                    }));

                    input.dispatchEvent(new Event('change', {
                        bubbles: true
                    }));
                };

                setValue(emailInput, email);
                setValue(passwordInput, password);

                const remember = Array.from(
                    document.querySelectorAll('input[type="checkbox"]')
                ).find((checkbox) => {
                    const identifier = [
                        checkbox.name || '',
                        checkbox.id || '',
                        checkbox.getAttribute('aria-label') || ''
                    ].join(' ').toLowerCase();

                    return identifier.includes('remember');
                });

                if (remember && !remember.checked) {
                    remember.click();
                }

                const form = passwordInput.form || emailInput.form;

                let submit = form
                    ? form.querySelector(
                        'button[type="submit"], input[type="submit"]'
                    )
                    : null;

                if (!submit) {
                    const root = form || document;

                    submit = Array.from(
                        root.querySelectorAll('button')
                    ).find((button) => {
                        const text = (button.textContent || '')
                            .trim()
                            .toLowerCase();

                        return text === 'continue' ||
                            text === 'log in' ||
                            text === 'login' ||
                            text === 'sign in';
                    });
                }

                if (submit) {
                    submit.click();
                    return 'submitted';
                }

                if (form && typeof form.requestSubmit === 'function') {
                    form.requestSubmit();
                    return 'submitted';
                }

                return 'waiting';
            })();
        """.trimIndent()
    }

    private fun loginUrl(): String = "$baseUrl/login?ru=%2Fhome"

    private fun isAutoLoginCoolingDown(): Boolean {
        val lastFailure = preferences.getLong(
            AUTO_LOGIN_LAST_FAILURE_PREF,
            0L,
        )

        if (lastFailure <= 0L) {
            return false
        }

        val elapsed = System.currentTimeMillis() - lastFailure

        if (elapsed >= AUTO_LOGIN_COOLDOWN_MS) {
            clearAutoLoginFailure()
            return false
        }

        return true
    }

    private fun markAutoLoginFailure() {
        preferences.edit()
            .putLong(
                AUTO_LOGIN_LAST_FAILURE_PREF,
                System.currentTimeMillis(),
            )
            .apply()
    }

    private fun clearAutoLoginFailure() {
        if (!preferences.contains(AUTO_LOGIN_LAST_FAILURE_PREF)) {
            return
        }

        preferences.edit()
            .remove(AUTO_LOGIN_LAST_FAILURE_PREF)
            .apply()
    }

    private suspend fun fetchCurrentUserMemberships(): MangasPage {
        val root = getJson<PatreonApiRoot>(
            currentUserMembershipsApiUrl(),
            patreonHeaders(),
        ) { code ->
            "Patreon memberships HTTP $code. Log in to Patreon using the app WebView first."
        }

        return MangasPage(root.currentUserMembershipResults(json), false)
    }

    private suspend fun fetchExploreSections(): MangasPage {
        val root = getJson<PatreonApiRoot>(
            exploreSectionsApiUrl(),
            patreonHeaders(requireLogin = false),
        ) { code ->
            "Patreon explore HTTP $code"
        }

        return MangasPage(root.exploreCampaignResults(json), false)
    }

    private suspend fun fetchLoggedInSearch(
        page: Int,
        query: String,
    ): MangasPage {
        val cursor = if (page <= 1) {
            ""
        } else {
            searchCursorCache[query.searchCursorKey()]
                ?.get(page)
                .orEmpty()
        }

        if (page > 1 && cursor.isBlank()) {
            return MangasPage(emptyList(), false)
        }

        val root = getJson<PatreonApiRoot>(
            loggedInSearchApiUrl(query, cursor),
            patreonHeaders(),
        ) { code ->
            "Patreon logged-in search HTTP $code"
        }

        val mangas = root.searchFeedCampaignResults(json)
        val next = root.links?.next

        saveNextSearchCursor(query, page, next)

        return MangasPage(mangas, !next.isNullOrBlank())
    }

    private suspend fun fetchAnonymousSearch(
        page: Int,
        query: String,
    ): MangasPage {
        val root = getJson<PatreonApiRoot>(
            anonymousSearchApiUrl(query, page),
            headers,
        ) { code ->
            "Patreon anonymous search HTTP $code"
        }

        return MangasPage(
            root.searchResults(baseUrl),
            !root.links?.next.isNullOrBlank(),
        )
    }

    private suspend fun fetchSearchHtmlFallback(
        page: Int,
        query: String,
    ): MangasPage {
        val searchUrl = "$baseUrl/search?q=${query.encode()}&p=$page"

        val response = client.get(
            searchUrl,
            patreonHeaders(requireLogin = false),
            ensureSuccess = false,
        )

        response.use { searchResponse ->
            if (!searchResponse.isSuccessful) {
                throw Exception("Patreon search HTTP ${searchResponse.code}")
            }

            val document = Jsoup.parse(searchResponse.body.string(), baseUrl)

            val results = document
                .select(
                    "[data-tag=campaign-result] a[data-tag=campaign-result-container], " +
                        ".CreatorTile-module__aMsLzq__creatorTileContainer",
                )
                .mapNotNull { element ->
                    val link = if (element.`is`("a")) {
                        element
                    } else {
                        element.selectFirst("a[href*=patreon.com]")
                    } ?: return@mapNotNull null

                    val href = link.attr("abs:href")
                        .ifBlank { link.attr("href") }

                    val title = element.selectFirst("h1")
                        ?.text()
                        ?.trim()
                        ?: element.selectFirst("span")
                            ?.text()
                            ?.trim()
                        ?: return@mapNotNull null

                    val thumbnail = element.selectFirst("img")
                        ?.attr("abs:src")
                        ?.ifBlank {
                            element.selectFirst("img")
                                ?.attr("src")
                                .orEmpty()
                        }
                        .orEmpty()

                    val campaignId = CAMPAIGN_ID_FROM_MEDIA_REGEX
                        .find(thumbnail)
                        ?.groupValues
                        ?.getOrNull(1)

                    val username = href.usernameFromPatreonUrl() ?: title

                    SManga.create().apply {
                        url = campaignId
                            ?.let { id -> "/campaign/$id" }
                            ?: href.toSourcePath(baseUrl)

                        this.title = title
                        author = username
                        artist = username
                        thumbnail_url = thumbnail.takeIf { url -> url.isNotBlank() }
                        description = ""
                        initialized = true
                    }
                }
                .distinctBy { manga -> manga.url }

            val hasNextPage = document.selectFirst(
                "a[href*=/search][href*=p=${page + 1}], a[href*=\"p=${page + 1}\"]",
            ) != null

            return MangasPage(results, hasNextPage)
        }
    }

    private fun patreonCookie(): String = CookieManager.getInstance()
        .getCookie(baseUrl)
        .orEmpty()

    private fun hasPatreonSession(): Boolean = patreonCookie()
        .split(';')
        .any { rawCookie ->
            val cookie = rawCookie.trim()
            val separator = cookie.indexOf('=')

            separator > 0 &&
                cookie.substring(0, separator).trim() == SESSION_COOKIE &&
                cookie.substring(separator + 1).trim().isNotBlank()
        }

    private fun patreonHeaders(
        requireLogin: Boolean = true,
    ): Headers {
        val cookie = patreonCookie().trim()

        if (requireLogin && !hasPatreonSession()) {
            throw Exception(
                "Log in to Patreon using the app WebView first, then try again.",
            )
        }

        return headers.newBuilder()
            .apply {
                if (cookie.isNotBlank()) {
                    set("Cookie", cookie)
                }
            }
            .build()
    }

    private suspend fun resolveCampaignId(query: String): String {
        val trimmed = query.trim()

        if (trimmed.isBlank()) {
            throw Exception(
                "Search with a Patreon creator URL, creator slug, or campaign ID.",
            )
        }

        if (trimmed.matches(NUMERIC_ID_REGEX)) {
            return trimmed
        }

        val url = when {
            trimmed.startsWith("http://") ||
                trimmed.startsWith("https://") -> trimmed

            trimmed.startsWith("/") -> "$baseUrl$trimmed"
            else -> "$baseUrl/$trimmed"
        }

        return fetchCampaignIdFromPage(url)
    }

    private suspend fun fetchCampaignIdFromPage(url: String): String {
        val response = client.get(
            url,
            patreonHeaders(requireLogin = false),
            ensureSuccess = false,
        )

        response.use { pageResponse ->
            if (!pageResponse.isSuccessful) {
                throw Exception(
                    "Could not open Patreon page: HTTP ${pageResponse.code}",
                )
            }

            val html = pageResponse.body.string()

            extractCampaignIdFromHtml(html)?.let { campaignId ->
                return campaignId
            }
        }

        throw Exception(
            "Could not find campaign ID. Try searching with the numeric campaign ID instead.",
        )
    }

    private fun extractCampaignIdFromHtml(html: String): String? {
        val document = Jsoup.parse(html, baseUrl)

        document
            .select("script#__NEXT_DATA__, script[type=application/json]")
            .forEach { script ->
                val payload = script.data()
                    .ifBlank { script.html() }
                    .trim()

                if (payload.isNotBlank()) {
                    val root = runCatching {
                        json.parseToJsonElement(payload)
                    }.getOrNull()

                    if (root != null) {
                        val campaignId = root.findCampaignId()

                        if (campaignId != null) {
                            return campaignId
                        }
                    }
                }
            }

        for (regex in CAMPAIGN_ID_REGEXES) {
            val campaignId = regex.find(html)
                ?.groupValues
                ?.getOrNull(1)

            if (!campaignId.isNullOrBlank()) {
                return campaignId
            }
        }

        return null
    }

    private fun JsonElement.findCampaignId(): String? {
        when (this) {
            is JsonObject -> {
                val directId = directCampaignId()

                if (directId != null) {
                    return directId
                }

                for (value in values) {
                    val nestedId = value.findCampaignId()

                    if (nestedId != null) {
                        return nestedId
                    }
                }
            }

            is JsonArray -> {
                for (value in this) {
                    val nestedId = value.findCampaignId()

                    if (nestedId != null) {
                        return nestedId
                    }
                }
            }

            else -> Unit
        }

        return null
    }

    private fun JsonObject.directCampaignId(): String? {
        val campaign = this["campaign"] as? JsonObject

        if (campaign != null) {
            val campaignData = campaign["data"] as? JsonObject

            val id = campaignData
                ?.primitiveString("id")
                ?: campaign.primitiveString("id")

            if (id != null && id.matches(NUMERIC_ID_REGEX)) {
                return id
            }
        }

        val explicitId = primitiveString("campaign_id")
            ?: primitiveString("campaignId")

        if (explicitId != null && explicitId.matches(NUMERIC_ID_REGEX)) {
            return explicitId
        }

        val type = primitiveString("type")
        val id = primitiveString("id")

        if (
            type?.contains("campaign", ignoreCase = true) == true &&
            id != null &&
            id.matches(NUMERIC_ID_REGEX)
        ) {
            return id
        }

        return null
    }

    private fun JsonObject.primitiveString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private suspend fun fetchCampaignManga(campaignId: String): SManga = try {
        val root = getJson<PatreonApiRoot>(
            campaignApiUrl(campaignId),
            patreonHeaders(requireLogin = false),
        ) { code ->
            "Patreon campaign HTTP $code"
        }

        root.dataResource(json).toSManga(campaignId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        SManga.create().apply {
            url = "/campaign/$campaignId"
            title = "Patreon campaign $campaignId"
            author = "Patreon"
            artist = "Patreon"
            description = ""
            initialized = true
        }
    }

    private suspend inline fun <reified T> getJson(
        url: String,
        requestHeaders: Headers,
        errorMessage: (Int) -> String,
    ): T {
        val response = client.get(
            url,
            requestHeaders,
            ensureSuccess = false,
        )

        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw Exception(errorMessage(code))
        }

        return response.parseAs(json)
    }

    private suspend fun <T> tryOrNull(
        block: suspend () -> T,
    ): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun saveNextSearchCursor(
        query: String,
        page: Int,
        nextUrl: String?,
    ) {
        val cursor = nextUrl
            ?.let { url ->
                PAGE_CURSOR_REGEX
                    .find(url)
                    ?.groupValues
                    ?.getOrNull(1)
            }
            ?.decodeUrl()
            ?: return

        val key = query.searchCursorKey()
        val map = searchCursorCache.getOrPut(key) { mutableMapOf() }

        map[page + 1] = cursor
    }

    private fun currentUserMembershipsApiUrl(): String = "$baseUrl/api/current_user" +
        "?fields%5Bcampaign%5D=avatar_photo_image_urls%2Cname%2Csummary%2Curl%2Curl_for_current_user%2Cvanity" +
        "&include=active_memberships%2Cactive_memberships.campaign" +
        "&json-api-version=1.0" +
        "&json-api-use-default-includes=false"

    private fun exploreSectionsApiUrl(): String = "$baseUrl/api/explore/sections" +
        "?fields%5Bexplore-campaign%5D=campaign_id%2Cname%2Csummary%2Cavatar_photo_url%2Curl%2Cvanity" +
        "&include=items%2Citems.campaign" +
        "&filter%5Banchor_topic%5D=" +
        "&filter%5Bselected_topic%5D=" +
        "&filter%5Binclude_nsfw%5D=true" +
        "&filter%5Bchurned_campaign_id%5D=null" +
        "&json-api-version=1.0" +
        "&json-api-use-default-includes=false"

    private fun loggedInSearchApiUrl(
        query: String,
        cursor: String,
    ): String = "$baseUrl/api/search_feed/v1/campaign" +
        "?filter%5Bquery%5D=${query.encode()}" +
        "&filter%5Bis_for_preview%5D=false" +
        "&filter%5Binclude_nsfw%5D=true" +
        "&fields%5Bcampaign%5D=avatar_photo_url%2Cavatar_photo_image_urls%2Cname%2Csummary%2Curl%2Ccampaign_id%2Cvanity" +
        "&include=card_campaign.campaign" +
        "&page%5Bsize%5D=24" +
        "&page%5Bcursor%5D=${cursor.encode()}" +
        "&json-api-version=1.0" +
        "&json-api-use-default-includes=false"

    private fun anonymousSearchApiUrl(
        query: String,
        page: Int,
    ): String = "$baseUrl/api/search" +
        "?q=${query.encode()}" +
        "&page%5Bnumber%5D=$page" +
        "&json-api-version=1.0" +
        "&json-api-use-default-includes=false" +
        "&include=[]"

    private fun postsApiUrl(campaignId: String): String = "$baseUrl/api/posts" +
        "?$POSTS_QUERY" +
        "&filter%5Bcampaign_id%5D=${campaignId.encode()}"

    private fun postApiUrl(postId: String): String = "$baseUrl/api/posts/${postId.encode()}?$POST_DETAIL_QUERY"

    private fun campaignApiUrl(campaignId: String): String = "$baseUrl/api/campaigns/${campaignId.encode()}" +
        "?fields%5Bcampaign%5D=avatar_photo_url%2Ccover_photo_url%2Cname%2Csummary%2Curl%2Cvanity" +
        "&json-api-version=1.0" +
        "&json-api-use-default-includes=false"

    private fun String.absolutePatreonUrl(): String = when {
        startsWith("http://") || startsWith("https://") -> this
        startsWith("/") -> "$baseUrl$this"
        else -> "$baseUrl/$this"
    }

    private fun String.extractCampaignIdFromSourceUrl(): String? = CAMPAIGN_ID_FROM_SOURCE_URL_REGEX
        .find(this)
        ?.groupValues
        ?.getOrNull(1)

    private fun String.encode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    private fun String.decodeUrl(): String = URLDecoder.decode(this, Charsets.UTF_8.name())

    private fun String.searchCursorKey(): String = trim().lowercase()

    private fun hideLockedChapters(): Boolean = preferences.getBoolean(HIDE_LOCKED_CHAPTERS_PREF, false)

    private fun String.extractPostIdFromChapterUrl(): String = substringAfterLast("/post/")
        .substringBefore('/')
        .substringBefore('?')

    private fun FilterList.membershipsOnly(): Boolean = filterIsInstance<MembershipsOnlyFilter>()
        .firstOrNull()
        ?.state == true

    private class MembershipsOnlyFilter : Filter.CheckBox("Only memberships", false)

    private class LruCache<K, V>(
        private val maxEntries: Int,
    ) : LinkedHashMap<K, V>(16, 0.75f, true) {

        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<K, V>,
        ): Boolean = size > maxEntries
    }

    companion object {
        private const val AUTO_LOGIN_EMAIL_PREF = "PATREON_AUTO_LOGIN_EMAIL"
        private const val AUTO_LOGIN_PASSWORD_PREF = "PATREON_AUTO_LOGIN_PASSWORD"
        private const val AUTO_LOGIN_LAST_FAILURE_PREF = "PATREON_AUTO_LOGIN_LAST_FAILURE"

        private const val POST_PAGES_PREF = "PATREON_POST_PAGES"
        private const val HIDE_LOCKED_CHAPTERS_PREF = "PATREON_HIDE_LOCKED_CHAPTERS"
        private const val POST_PAGES_DEFAULT = "5"

        private const val SESSION_COOKIE = "session_id"

        private const val AUTO_LOGIN_TIMEOUT_SECONDS = 30
        private const val AUTO_LOGIN_POLL_INTERVAL_MS = 500L
        private const val AUTO_LOGIN_COOLDOWN_MS = 24L * 60L * 60L * 1000L

        private val POST_PAGE_OPTIONS =
            (5..75 step 5).map { option -> option.toString() }.toTypedArray()

        private const val POST_PAGES_CACHE_SIZE = 200
        private const val SEARCH_CURSOR_CACHE_SIZE = 50

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36"

        private val AUTO_LOGIN_CHECK_SCRIPT = """
            (() => {
                const invalidInput = document.querySelector(
                    'input[type="email"][aria-invalid="true"], input[type="password"][aria-invalid="true"]'
                );

                if (invalidInput) {
                    return 'failed';
                }

                const alerts = Array.from(
                    document.querySelectorAll('[role="alert"], [aria-live="assertive"]')
                )
                    .map((element) => (element.textContent || '').trim())
                    .filter(Boolean)
                    .join(' ')
                    .toLowerCase();

                if (
                    alerts.includes('incorrect password') ||
                    alerts.includes('wrong password') ||
                    alerts.includes('invalid password') ||
                    alerts.includes('invalid email') ||
                    alerts.includes('try again')
                ) {
                    return 'failed';
                }

                return 'waiting';
            })();
        """.trimIndent()

        private const val POSTS_QUERY =
            "include=attachments%2Cattachments_media%2Cimages%2Cmedia" +
                "&fields%5Bpost%5D=content%2Ccontent_json_string%2Ccurrent_user_can_view%2Cimage%2Cpost_file%2Cpublished_at%2Ctitle" +
                "&fields%5Bmedia%5D=id%2Cimage_urls%2Cdownload_url%2Cmimetype%2Cfile_name" +
                "&sort=-published_at" +
                "&filter%5Bis_draft%5D=false" +
                "&filter%5Bcontains_exclusive_posts%5D=true" +
                "&json-api-use-default-includes=false" +
                "&json-api-version=1.0"

        private const val POST_DETAIL_QUERY =
            "include=attachments%2Cattachments_media%2Cimages%2Cmedia" +
                "&fields%5Bpost%5D=content%2Ccontent_json_string%2Ccurrent_user_can_view%2Cimage%2Cpost_file%2Cpublished_at%2Ctitle" +
                "&fields%5Bmedia%5D=id%2Cimage_urls%2Cdownload_url%2Cmimetype%2Cfile_name" +
                "&json-api-use-default-includes=false" +
                "&json-api-version=1.0"

        private val NUMERIC_ID_REGEX =
            Regex("""\d+""")

        private val CAMPAIGN_ID_FROM_SOURCE_URL_REGEX =
            Regex("""/campaign/(\d+)""")

        private val CAMPAIGN_ID_REGEXES = listOf(
            Regex(
                """"campaign"\s*:\s*\{\s*"data"\s*:\s*\{[^{}]*"id"\s*:\s*"(\d+)"""",
            ),
            Regex(
                """\\"campaign\\"\s*:\s*\{\\"data\\"\s*:\s*\{[^{}]*\\"id\\"\s*:\s*\\"(\d+)\\"""",
            ),
            Regex(
                """https:\\/\\/www\.patreon\.com\\/api\\/campaigns\\/(\d+)""",
            ),
            Regex(
                """https://www\.patreon\.com/api/campaigns/(\d+)""",
            ),
            Regex(
                """/api/campaigns/(\d+)""",
            ),
            Regex(
                """/campaign/(\d+)/""",
            ),
        )

        private val CAMPAIGN_ID_FROM_MEDIA_REGEX =
            Regex("""/campaign/(\d+)/""")

        private val PAGE_CURSOR_REGEX =
            Regex("""page(?:%5B|\[)cursor(?:%5D|])=([^&]+)""")
    }
}

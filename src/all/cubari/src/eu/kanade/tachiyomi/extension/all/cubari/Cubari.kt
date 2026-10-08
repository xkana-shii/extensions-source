
package eu.kanade.tachiyomi.extension.all.cubari

import android.os.Build
import android.text.InputType
import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.source.ConfigurableSource
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
import kotlinx.serialization.json.JsonArray
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Cubari :
    KeiSource(),
    ConfigurableSource {

    // KNS
    private val preferences by getPreferencesLazy()
    // KNS

    override val supportsLatest = true

    override fun Headers.Builder.configureHeaders() = set(
        "User-Agent",
        "(Android ${Build.VERSION.RELEASE}; " +
            "${Build.MANUFACTURER} ${Build.MODEL}) " +
            "Tachiyomi/${AppInfo.getVersionName()} ${Build.ID} " +
            "Keiyoushi",
    )

    // KNS
    override fun OkHttpClient.Builder.configureClient() = addInterceptor { chain ->
        val request = chain.request()
        val token = preferences.getString(PREF_GITHUB_TOKEN, "").orEmpty()

        val builder = request.newBuilder()
            .removeHeader("Accept-Encoding")

        if (token.isNotBlank() && isGithubHost(request.url.host)) {
            builder.header("Authorization", "token $token")
        }

        chain.proceed(builder.build())
    }

    private fun isGithubHost(host: String): Boolean {
        val normalizedHost = host.lowercase()
        return normalizedHost == "github.com" ||
            normalizedHost.endsWith(".github.com") ||
            normalizedHost.endsWith(".githubusercontent.com")
    }

    private fun proxyUrlIfGithubHost(url: String): String {
        if (url.isBlank()) return url
        val host = runCatching { url.toHttpUrl().host }.getOrNull() ?: return url
        if (!isGithubHost(host)) return url

        val encodedUrl = Base64.encodeToString(
            url.toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP,
        )
        return "$CUBARI_PROXY_PREFIX$encodedUrl"
    }
    // KNS

    // History and pins only exist in the site's remoteStorage cache, reachable through its own JS
    private suspend fun fetchHistory(): List<HistoryEntryDto> = runWebView<String>(10.seconds) {
        userAgent = headers["User-Agent"]!!
        jsBridge("android") { resolve(it) }
        onPageFinished {
            evaluateJs(
                "Promise.all([globalHistoryHandler.getAllPinnedSeries(), globalHistoryHandler.getAllUnpinnedSeries()])" +
                    ".then(e => android.post(JSON.stringify(e.flat())))",
            )
        }
        loadUrl("$baseUrl/")
    }.parseAs()

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaList(fetchHistory(), SortType.UNPINNED)

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaList(fetchHistory(), SortType.PINNED)

    private fun seriesApiUrl(url: String): String {
        val urlComponents = url.split("/")
        val source = urlComponents[2]
        val slug = urlComponents[3]

        return "$baseUrl/read/api/$source/series/$slug/"
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)

        val series = client.get(seriesApiUrl(manga.url)).parseAs<SeriesDto>()

        return SMangaUpdate(
            if (fetchDetails) series.toSManga(manga.url) else manga,
            if (fetchChapters) parseChapterList(series, manga) else chapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = when {
        chapter.url.contains("/chapter/") -> {
            client.get("$baseUrl${chapter.url}")
                .parseAs<JsonArray>()
                .mapIndexed { i, jsonEl ->
                    // KNS
                    Page(i, "", proxyUrlIfGithubHost(jsonEl.pageSrc()))
                    // KNS
                }
        }

        else -> {
            val series = client.get(seriesApiUrl(chapter.url)).parseAs<SeriesDto>()
            seriesJsonPageListParse(series, chapter)
        }
    }

    private fun seriesJsonPageListParse(series: SeriesDto, chapter: SChapter): List<Page> {
        val groupMap = series.groups.entries.associateBy({ it.value.ifEmpty { "default" } }, { it.key })
        val chapterScanlator = chapter.scanlator ?: "default" // workaround for "" as group causing NullPointerException (#13772)

        // prevent NullPointerException when chapters.key is 084 and chapter.chapter_number is 84
        val chapters = series.chapters.mapKeys {
            it.key.replace(Regex("^0+(?!$)"), "")
        }

        val chapterDto = chapters[chapter.chapter_number.toString()]
            ?: chapters[chapter.chapter_number.toInt().toString()]!!

        val pages = chapterDto.groups[groupMap[chapterScanlator]]!! as JsonArray

        return pages.mapIndexed { i, jsonEl ->
            // KNS
            Page(i, "", proxyUrlIfGithubHost(jsonEl.pageSrc()))
            // KNS
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val (source, slug) = parseUrl(url) ?: return null
        return fetchSeries(source, slug)
    }

    private suspend fun fetchSeries(source: String, slug: String): SManga {
        val series = client.get("$baseUrl/read/api/$source/series/$slug/").parseAs<SeriesDto>()
        // Only tag for recently read on search; a failed tag shouldn't fail the search
        runCatching { tagHistory(source, slug) }

        return series.toSManga("/read/$source/$slug")
    }

    // The series page adds itself to the site's history. tag() is re-run so that
    // history-ready fires after our listener is attached.
    private suspend fun tagHistory(source: String, slug: String) = runWebView<Unit>(10.seconds) {
        userAgent = headers["User-Agent"]!!
        jsBridge("android") { resolve(Unit) }
        onPageFinished {
            evaluateJs("window.addEventListener('history-ready', () => android.post(''), { once: true }); tag();")
        }
        loadUrl("$baseUrl/read/$source/$slug/")
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val searchQuery = query.trim()

        // KNS
        if (searchQuery.startsWith("github:")) {
            val mangasPage = githubRepoMangaList(searchQuery.removePrefix("github:").trim())
            require(mangasPage.mangas.isNotEmpty()) {
                "No .json files found in the specified GitHub repository."
            }
            return mangasPage
        }
        // KNS

        // legacy cubari:source/slug format
        if (searchQuery.startsWith("cubari:")) {
            val queryFragments = searchQuery.substringAfter("cubari:").split("/", limit = 2)
            require(queryFragments.size == 2 && queryFragments.all { it.isNotBlank() }) {
                SEARCH_FALLBACK_MSG
            }
            return MangasPage(listOf(fetchSeries(queryFragments[0], queryFragments[1])), false)
        }

        // KNS
        if (searchQuery.startsWith("https://") || searchQuery.startsWith("http://")) {
            val (source, slug) = parseUrl(searchQuery.toHttpUrl())
                ?: throw IllegalArgumentException(SEARCH_FALLBACK_MSG)

            return MangasPage(listOf(fetchSeries(source, slug)), false)
        }
        // KNS

        val filtered = fetchHistory().filter { it.matches(searchQuery) }
        val mangasPage = parseMangaList(filtered, SortType.ALL)
        require(mangasPage.mangas.isNotEmpty()) { SEARCH_FALLBACK_MSG }

        return mangasPage
    }

    // KNS
    private fun languageCodeAlias(raw: String): String? = when (raw.lowercase()) {
        "en", "english" -> "en"
        "ja", "jp", "japanese" -> "ja"
        "ko", "kr", "korean" -> "ko"
        "zh", "chinese", "zh-cn", "zh-hans", "zh-hant" -> "zh"
        else -> null
    }

    private fun extractSupportedLang(description: String?): String? {
        if (description.isNullOrBlank()) return null
        val lowerDescription = description.lowercase()

        val linePattern = Regex(
            """^\s*lang(?:uage)?[\s:]+([a-z0-9\-_]+)\s*$""",
            RegexOption.MULTILINE,
        )
        linePattern.find(lowerDescription)
            ?.groupValues?.getOrNull(1)
            ?.let(::languageCodeAlias)
            ?.let { return it }

        Regex("""lang:\s*([a-z0-9\-_]+)""")
            .find(lowerDescription)
            ?.groupValues?.getOrNull(1)
            ?.let(::languageCodeAlias)
            ?.let { return it }

        return when {
            "english" in lowerDescription -> "en"
            "japanese" in lowerDescription -> "ja"
            "korean" in lowerDescription -> "ko"
            "chinese" in lowerDescription ||
                "zh-cn" in lowerDescription ||
                "zh-hans" in lowerDescription ||
                "zh-hant" in lowerDescription -> "zh"
            else -> null
        }
    }

    private fun githubHeaders(): Headers = Headers.Builder()
        .add("Accept", "application/vnd.github+json")
        .build()

    private suspend fun githubRepoMangaList(repo: String): MangasPage {
        val repositoryParts = repo.split("/")
        require(repositoryParts.size == 2 && repositoryParts.all { it.isNotBlank() }) {
            "Please enter a GitHub repository as github:owner/repository."
        }

        val repository = client.get(
            "https://api.github.com/repos/$repo",
            githubHeaders(),
        ).parseAs<GithubRepositoryDto>()

        val branch = repository.defaultBranch

        val tree = client.get(
            "https://api.github.com/repos/$repo/git/trees/$branch?recursive=1",
            githubHeaders(),
        ).parseAs<GithubTreeDto>()

        val jsonPaths = tree.tree
            .filter { it.type == "blob" && it.path.endsWith(".json") }
            .map { it.path }

        val enforceLanguage = preferences.getBoolean(PREF_ENFORCE_LANGUAGE, true)
        val sourceLang = lang.lowercase()
        val shouldFilterByLang = enforceLanguage && sourceLang != "all" && sourceLang != "other"
        val expectedLang = if (shouldFilterByLang) languageCodeAlias(sourceLang) else null

        val mangaList = jsonPaths.mapNotNull { path ->
            val rawUrl = "https://raw.githubusercontent.com/$repo/$branch/$path"

            runCatching {
                val json = client.get(rawUrl).parseAs<GithubMangaDto>()
                val descField = json.description

                if (expectedLang != null && extractSupportedLang(descField) != expectedLang) {
                    return@runCatching null
                }

                val displayTitle = json.title
                    .takeIf { it.isNotBlank() }
                    ?: path.substringAfterLast("/").removeSuffix(".json")

                val gistBase64 = Base64.encodeToString(
                    "raw/$repo/$branch/$path".toByteArray(),
                    Base64.NO_PADDING or Base64.NO_WRAP,
                )

                SManga.create().apply {
                    title = displayTitle
                    url = "/read/gist/$gistBase64"
                    description = buildString {
                        append("GitHub: ").append(repo)
                        append("\nPath: ").append(path)
                        if (descField.isNotBlank()) {
                            append("\n\n").append(descField)
                        }
                    }
                    thumbnail_url = json.cover.orEmpty()
                }
            }.getOrNull()
        }

        return MangasPage(mangaList, false)
    }
    // KNS

    private fun parseUrl(url: HttpUrl): Pair<String, String>? {
        val host = url.host
        val pathSegments = url.pathSegments

        return if (
            host.endsWith("imgur.com") &&
            pathSegments.size >= 2 &&
            pathSegments[0] in listOf("a", "gallery")
        ) {
            "imgur" to pathSegments[1]
        } else if (
            host.endsWith("reddit.com") &&
            pathSegments.size >= 2 &&
            pathSegments[0] == "gallery"
        ) {
            "reddit" to pathSegments[1]
        } else if (
            host == "imgchest.com" &&
            pathSegments.size >= 2 &&
            pathSegments[0] == "p"
        ) {
            "imgchest" to pathSegments[1]
        } else if (
            host.endsWith("catbox.moe") &&
            pathSegments.size >= 2 &&
            pathSegments[0] == "c"
        ) {
            "catbox" to pathSegments[1]
        } else if (
            host.endsWith("cubari.moe") &&
            pathSegments.size >= 3
        ) {
            pathSegments[1] to pathSegments[2]
        } else if (
            host.endsWith(".githubusercontent.com")
        ) {
            val src = host.substringBefore(".")
            val path = url.encodedPath

            "gist" to Base64.encodeToString("$src$path".toByteArray(), Base64.NO_PADDING)
        } else {
            null
        }
    }

    // ------------- Helpers and whatnot ---------------

    private val volumeNotSpecifiedTerms = setOf("Uncategorized", "null", "")

    private fun parseChapterList(series: SeriesDto, manga: SManga): List<SChapter> {
        val chapterList = series.chapters.entries.flatMap { chapterEntry ->
            val chapterNum = chapterEntry.key
            val chapterObj = chapterEntry.value
            val volume = chapterObj.volume.content.let {
                if (volumeNotSpecifiedTerms.contains(it)) null else it
            }
            val title = chapterObj.title.orEmpty()

            chapterObj.groups.entries.map { groupEntry ->
                val groupNum = groupEntry.key
                val releaseDate = chapterObj.releaseDate?.get(groupNum)

                SChapter.create().apply {
                    scanlator = series.groups[groupNum]!!
                    chapter_number = chapterNum.toFloatOrNull() ?: -1f

                    date_upload = if (releaseDate != null) {
                        releaseDate.double.toLong() * 1000
                    } else {
                        0L
                    }

                    name = buildString {
                        if (!volume.isNullOrBlank()) append("Vol.$volume ")
                        append("Ch.$chapterNum")
                        if (title.isNotBlank()) append(" - $title")
                    }

                    url = if (groupEntry.value is JsonArray) {
                        "${manga.url}/$chapterNum/$groupNum"
                    } else {
                        groupEntry.value.pageSrc()
                    }
                }
            }
        }

        return chapterList.sortedByDescending { it.chapter_number }
    }

    private fun parseMangaList(payload: List<HistoryEntryDto>, sortType: SortType): MangasPage {
        val mangaList = payload.mapNotNull { entry ->
            if (sortType == SortType.PINNED && entry.pinned) {
                entry.toSManga()
            } else if (sortType == SortType.UNPINNED && !entry.pinned) {
                entry.toSManga()
            } else if (sortType == SortType.ALL) {
                entry.toSManga()
            } else {
                null
            }
        }

        return MangasPage(mangaList, false)
    }

    // KNS
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_GITHUB_TOKEN
            title = "GitHub Personal Access Token"
            summary = "Use to increase GitHub API rate limit for repository searches and authenticated fetches."
            setDefaultValue("")
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_ENFORCE_LANGUAGE
            title = "Enforce Language"
            summary = "When enabled, only manga matching the selected language will be shown for repository searches. Disable to show all."
            setDefaultValue(true)
        }.let(screen::addPreference)
    }
    // KNS

    companion object {
        const val AUTHOR_FALLBACK = "Unknown"
        const val ARTIST_FALLBACK = "Unknown"
        const val DESCRIPTION_FALLBACK = "No description."
        const val SEARCH_FALLBACK_MSG = "Please enter a valid Cubari URL"

        // KNS
        private const val CUBARI_PROXY_PREFIX = "cubari://proxy/"
        private const val PREF_GITHUB_TOKEN = "cubari_github_token"
        private const val PREF_ENFORCE_LANGUAGE = "cubari_enforce_language"
        // KNS

        enum class SortType {
            PINNED,
            UNPINNED,
            ALL,
        }
    }
}

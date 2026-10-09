
package eu.kanade.tachiyomi.extension.all.myreadingmanga

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.webkit.URLUtil
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
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
import keiyoushi.utils.applicationContext
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US)
private val shortDateFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

@Source
abstract class MyReadingManga :
    KeiSource(),
    ConfigurableSource {

    private val siteLang: String
        get() = when (lang) {
            "ar" -> "Arabic"
            "id" -> "Indonesia"
            "zh" -> "Chinese"
            "zh-hant" -> "Traditional-Chinese"
            "hr" -> "Croatian"
            "en" -> "English"
            "fil" -> "Filipino"
            "fr" -> "French"
            "de" -> "German"
            "hu" -> "Hungarian"
            "it" -> "Italian"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "lt" -> "Lithuanian"
            "fa" -> "Persian"
            "pl" -> "Polish"
            "pt-BR" -> "Portuguese"
            "pt" -> "Portuguese"
            "ru" -> "Russian"
            "sk" -> "Slovak"
            "es" -> "Spanish"
            "sv" -> "Swedish"
            "th" -> "Thai"
            "tr" -> "Turkish"
            "vi" -> "Vietnamese"
            else -> lang
        }

    private val latestLang: String get() = if (lang == "ja") "jp" else siteLang

    // Basic Info
    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("User-Agent", USER_AGENT)
        .add("X-Requested-With", randomString((1..20).random()))

    // KNS
    private val preferences by getPreferencesLazy()

    private val credentials: Credential
        get() = Credential(
            username = preferences.getString(USERNAME_PREF, "") ?: "",
            password = preferences.getString(PASSWORD_PREF, "") ?: "",
        )

    private class Credential(val username: String, val password: String)
    // KNS

    override fun OkHttpClient.Builder.configureClient() = addInterceptor { chain ->
        val request = chain.request()
        val headers = request.headers.newBuilder().apply {
            removeAll("X-Requested-With")
        }.build()

        chain.proceed(request.newBuilder().headers(headers).build())
    }
        // KNS
        .addInterceptor(LoginInterceptor())
    // KNS

    override val supportsLatest = true

    // KNS
    private inner class LoginInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val path = request.url.encodedPath

            if (path == "/wp-login.php" || path == "/login" || path == "/login/" || path.startsWith("/login/")) {
                return chain.proceed(request)
            }

            val username = credentials.username
            val password = credentials.password

            val cookies = client.cookieJar.loadForRequest(request.url)
            val hasLoginCookie = cookies.any { it.name.startsWith("wordpress_logged_in_") }

            if (!hasLoginCookie && username.isNotBlank() && password.isNotBlank()) {
                val loginBody = FormBody.Builder()
                    .add("log", username)
                    .add("pwd", password)
                    .add("wp-submit", "Log In")
                    .add("redirect_to", "$baseUrl/")
                    .add("testcookie", "1")
                    .build()

                val loginRequest = Request.Builder()
                    .url("$baseUrl/wp-login.php")
                    .headers(headers)
                    .post(loginBody)
                    .build()

                chain.proceed(loginRequest).use { loginResponse ->
                    if (!loginResponse.isSuccessful) {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(
                                applicationContext,
                                "MyReadingManga login failed. Please check your credentials.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }

            val response = chain.proceed(request)
            val finalPath = response.request.url.encodedPath
            if (finalPath == "/login" || finalPath == "/login/" || finalPath.startsWith("/login/")) {
                response.close()
                throw IOException("Please log in via extension settings")
            }

            return response
        }
    }

    // Preference Screen
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val usernamePref = EditTextPreference(screen.context).apply {
            key = USERNAME_PREF
            title = "Username"
            summary = "Enter your username"
        }
        val passwordPref = EditTextPreference(screen.context).apply {
            key = PASSWORD_PREF
            title = "Password"
            summary = "Enter your password"
            setOnBindEditTextListener { et ->
                et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }
        screen.addPreference(usernamePref)
        screen.addPreference(passwordPref)
    }
    // KNS

    // Popular - Random
    override suspend fun getPopularManga(page: Int): MangasPage {
        val document = client.get("$baseUrl/page/$page/?s=&ep_sort=rand&ep_filter_lang=$siteLang").asJsoup() // Random Manga as returned by search
        return searchMangaParse(document)
    }

    // Latest
    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val document = client.get("$baseUrl/lang/${latestLang.lowercase()}" + if (page > 1) "/page/$page/" else "").asJsoup() // Home Page - Latest Manga
        val mangas = document.select("article:not(.category-video)").map { element ->
            buildManga(element.selectFirst("a[rel]")!!, element.selectFirst("a.entry-image-link img"))
        }
        val hasNextPage = document.selectFirst("li.pagination-next") != null
        return MangasPage(mangas, hasNextPage)
    }

    // Search
    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val filterList = if (filters.isEmpty()) getFilterList() else filters

        val uri = Uri.parse("$baseUrl/page/$page/").buildUpon()
            .appendQueryParameter("s", query)
        filterList.forEach { filter ->
            if (filter is UriFilter) {
                filter.addToUri(uri)
            }
            if (filter is SearchSortTypeList) {
                uri.appendQueryParameter("ep_sort", listOf("date", "date_asc", "rand", "")[filter.state])
            }
        }

        return searchMangaParse(client.get(uri.toString()).asJsoup())
    }

    private var mangaParsedSoFar = 0

    private fun searchMangaParse(document: Document): MangasPage {
        if (document.location().contains("/page/1")) mangaParsedSoFar = 0
        val articles = document.select("article")
        mangaParsedSoFar += articles.size
        // video posts have no readable pages
        val mangas = articles.filterNot { it.hasClass("category-video") }.map { element ->
            buildManga(element.selectFirst("a[rel]")!!, element.selectFirst("a.entry-image-link img"))
        }
        val totalResults = TOTAL_RESULTS_REGEX.find(document.selectFirst(".ep-search-count")?.text() ?: "")?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() ?: 0
        return MangasPage(mangas, mangaParsedSoFar < totalResults)
    }

    // Build Manga From Element
    private fun buildManga(titleElement: Element, thumbnailElement: Element?): SManga {
        val manga = SManga.create().apply {
            setUrlWithoutDomain(titleElement.absUrl("href"))
            title = cleanTitle(titleElement.text())
        }
        if (thumbnailElement != null) manga.thumbnail_url = getThumbnail(getImage(thumbnailElement))
        return manga
    }

    private fun getImage(element: Element): String? {
        val url = when {
            element.attr("data-src").contains(EXTENSION_REGEX) -> element.attr("abs:data-src")
            element.attr("data-cfsrc").contains(EXTENSION_REGEX) -> element.attr("abs:data-cfsrc")
            element.attr("src").contains(EXTENSION_REGEX) -> element.attr("abs:src")
            else -> element.attr("abs:data-lazy-src")
        }

        return if (URLUtil.isValidUrl(url)) url else null
    }

    // removes resizing
    private fun getThumbnail(thumbnailUrl: String?): String? {
        thumbnailUrl ?: return null
        val url = thumbnailUrl.substringBeforeLast("-") + "." + thumbnailUrl.substringAfterLast(".")
        return if (URLUtil.isValidUrl(url)) url else null
    }

    // cleans up the name removing author and language from the title
    private fun cleanTitle(title: String) = title.replace(TITLE_REGEX, "").replace(Regex("""\s+"""), " ").trim()

    private fun cleanAuthor(author: String) = author.substringAfter("[").substringBefore("]").trim()

    // Manga Details
    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) return SMangaUpdate(manga, chapters)

        val needCover = fetchDetails &&
            (manga.thumbnail_url?.let { url -> client.get(url, ensureSuccess = false).use { !it.isSuccessful } } ?: true)

        val document = client.get(getMangaUrl(manga)).asJsoup()

        val updatedManga = if (fetchDetails) {
            mangaDetailsParse(document, needCover).apply { initialized = true }
        } else {
            manga
        }

        return SMangaUpdate(
            updatedManga,
            if (fetchChapters) chapterListParse(document) else chapters,
        )
    }

    private suspend fun mangaDetailsParse(document: Document, needCover: Boolean): SManga = SManga.create().apply {
        title = cleanTitle(document.selectFirst("h1")?.text() ?: "")
        author = cleanAuthor(document.selectFirst("h1")?.text() ?: "")
        artist = author
        genre = document.select(".entry-header p a[href*=genre], [href*=tag], span.entry-categories a").joinToString { it.text() }
        val basicDescription = document.selectFirst("h1")?.text()
        // too troublesome to achieve 100% accuracy assigning scanlator group during chapterListParse
        val scanlatedBy = document.selectFirst(".entry-terms:has(a[href*=group])")
            ?.select("a[href*=group]")?.joinToString(prefix = "Scanlated by: ") { it.text() }
        val extendedDescription = document.select(".entry-content p:not(p:containsOwn(|)):not(.chapter-class + p)").joinToString("\n") { it.text() }
        description = listOfNotNull(basicDescription, scanlatedBy, extendedDescription).joinToString("\n").trim()
        status = when (document.selectFirst("a[href*=status]")?.text()) {
            "Ongoing" -> SManga.ONGOING
            "Completed" -> SManga.COMPLETED
            "Licensed" -> SManga.LICENSED
            "Dropped" -> SManga.CANCELLED
            "Discontinued" -> SManga.CANCELLED
            "Hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }

        if (needCover) {
            thumbnail_url = client.get("$baseUrl/?s=${document.location()}").asJsoup()
                .selectFirst("div.ep-search-content div.entry-content img")
                ?.let {
                    getThumbnail(getImage(it))
                }
        }
    }

    // Start Chapter Get
    private fun chapterListParse(document: Document): List<SChapter> {
        val chapters = mutableListOf<SChapter>()

        val rawDate = document.selectFirst(".entry-time")?.text()
        val date = dateFormat.tryParseDate(rawDate).takeIf { it != 0L }
            ?: shortDateFormat.tryParseDate(rawDate)

        // create first chapter since its on main manga page
        chapters.add(createChapter("1", document.location(), date, "Ch. 1"))
        // see if there are multiple chapters or not
        val lastChapterNumber = document.select("a[class=page-numbers]").last()?.text()?.toIntOrNull()
        if (lastChapterNumber != null) {
            // There are entries with more chapters but those never show up,
            // so we take the last one and loop it to get all hidden ones.
            // Example: 1 2 3 4 .. 7 8 9 Next
            for (i in 2..lastChapterNumber) {
                chapters.add(createChapter(i.toString(), document.location(), date, "Ch. $i"))
            }
        }
        chapters.reverse()
        return chapters
    }

    private fun createChapter(pageNumber: String, mangaUrl: String, date: Long, chname: String): SChapter {
        val chapter = SChapter.create()
        chapter.setUrlWithoutDomain("$mangaUrl/$pageNumber")
        chapter.name = chname
        chapter.date_upload = date
        return chapter
    }

    // Pages
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        return (document.select("div.entry-content img") + document.select("div.separator img[data-src]"))
            .mapNotNull { getImage(it) }
            .distinct()
            .mapIndexed { i, url -> Page(i, imageUrl = url) }
    }

    // Filter Parsing, grabs pages as document and filters out Genres, Popular Tags, and Categories, Parings, and Scan Groups
    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement {
        suspend fun fetchFilter(url: String, css: String): List<Pair<String, String>> = client.get(url).asJsoup()
            .select(css)
            .map { Pair(it.text(), it.attr("href").split("/").dropLast(1).lastOrNull() ?: "") }

        return FilterData(
            genres = fetchFilter(baseUrl, ".tagcloud a[href*=/genre/]"),
            tags = fetchFilter("$baseUrl/tags/", ".tag-groups-alphabetical-index a"),
            categories = fetchFilter("$baseUrl/cats/", ".tag-groups-alphabetical-index a"),
            pairings = fetchFilter("$baseUrl/pairing/", ".tag-groups-alphabetical-index a"),
            groups = fetchFilter("$baseUrl/group/", ".tag-groups-alphabetical-index a"),
        ).toJsonElement()
    }

    // Generates the filter lists for app
    override fun getFilterList(data: JsonElement?): FilterList {
        val filters = mutableListOf<Filter<*>>(
            EnforceLanguageFilter(siteLang),
            SearchSortTypeList(),
        )

        data?.parseAs<FilterData>()?.also {
            filters += GenreFilter(it.genres.toTypedArray())
            filters += TagFilter(it.tags.toTypedArray())
            filters += CatFilter(it.categories.toTypedArray())
            filters += PairingFilter(it.pairings.toTypedArray())
            filters += ScanGroupFilter(it.groups.toTypedArray())
        }

        return FilterList(filters)
    }

    @Serializable
    class FilterData(
        val genres: List<Pair<String, String>>,
        val tags: List<Pair<String, String>>,
        val categories: List<Pair<String, String>>,
        val pairings: List<Pair<String, String>>,
        val groups: List<Pair<String, String>>,
    )

    companion object {
        // KNS
        private const val USERNAME_PREF = "MYREADINGMANGA_USERNAME"
        private const val PASSWORD_PREF = "MYREADINGMANGA_PASSWORD"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36"
        // KNS

        private val EXTENSION_REGEX = Regex("""\.(jpg|png|jpeg|webp)""")
        private val TITLE_REGEX = Regex("""^\s*\[[^]]*]\s*|\s*\[[^]]*].*$""")
        private val TOTAL_RESULTS_REGEX = Regex("""([\d,]+)""")
    }

    private fun randomString(length: Int): String {
        val charPool = ('a'..'z') + ('A'..'Z')
        return List(length) { charPool.random() }.joinToString("")
    }
}

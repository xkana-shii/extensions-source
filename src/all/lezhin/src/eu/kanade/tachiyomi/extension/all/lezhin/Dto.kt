package eu.kanade.tachiyomi.extension.all.lezhin

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable
class LezhinContentListDto(
    val data: List<LezhinContentDto>,
    val hasNext: Boolean = false,
)

@Serializable
class LezhinContentDto(
    val id: Long,
    val alias: String,
    val title: String,
    val artists: List<LezhinArtistDto> = emptyList(),
    val updated: Long? = null,
    val updatedAt: Long? = null,
) {
    fun toSManga(languagePath: String) = SManga.create().apply {
        url = "/$languagePath/comic/$alias"
        title = this@LezhinContentDto.title.trim()
        thumbnail_url = getCoverUrl()

        val authors = artists
            .filter {
                it.role.equals("scripter", true) ||
                    it.role.equals("original", true) ||
                    it.role.equals("writer", true)
            }
            .map { it.name }
            .filter { it.isNotBlank() }
            .distinct()

        val painters = artists
            .filter {
                it.role.equals("painter", true) ||
                    it.role.equals("artist", true)
            }
            .map { it.name }
            .filter { it.isNotBlank() }
            .distinct()

        if (authors.isNotEmpty()) {
            author = authors.joinToString(", ")
        }

        if (painters.isNotEmpty()) {
            artist = painters.joinToString(", ")
        }
    }

    private fun getCoverUrl(): String = "https://ccdn.lezhin.com/v2/comics/$id/images/tall.webp"
        .toHttpUrl()
        .newBuilder()
        .apply {
            (updated ?: updatedAt)
                ?.takeIf { it > 0L }
                ?.let {
                    addQueryParameter("updated", it.toString())
                }

            addQueryParameter("width", "1200")
        }
        .build()
        .toString()
}

@Serializable
class LezhinArtistDto(
    val role: String,
    val name: String,
)

@Serializable
class LezhinAuthDto(
    val id: Long? = null,
    val accessToken: String? = null,
)

@Serializable
class LezhinLocaleRequestDto(
    @SerialName("locale_region")
    val localeRegion: String,
)

@Serializable
class LezhinHydratedChaptersDto(
    val episodes: List<LezhinEpisodeDto>,
)

@Serializable
class LezhinEpisodeDto(
    val name: String,
    val display: LezhinEpisodeDisplayDto,
)

@Serializable
class LezhinEpisodeDisplayDto(
    val title: String,
)

@Serializable
class LezhinQueriesDto(
    val queries: List<LezhinQueryDto>,
)

@Serializable
class LezhinQueryDto(
    val queryKey: List<String>,
    val state: LezhinQueryStateDto,
)

@Serializable
class LezhinQueryStateDto(
    val data: JsonElement,
)

@Serializable
class LezhinViewerPagesDto(
    val comic: LezhinViewerComicDto,
    val episode: LezhinViewerEpisodeDto,
    val media: LezhinViewerMediaContainerDto,
)

@Serializable
class LezhinViewerComicDto(
    val id: Int,
)

@Serializable
class LezhinViewerEpisodeDto(
    val id: Int,
    val updatedAt: Long,
)

@Serializable
class LezhinViewerMediaContainerDto(
    val imageShuffle: Boolean = false,
    val scrollView: List<LezhinViewerMediaDto> = emptyList(),
    val pageView: List<LezhinViewerMediaDto> = emptyList(),
)

@Serializable
class LezhinViewerMediaDto(
    val path: String,
    val cutType: String,
)

@Serializable
class LezhinViewerStatusDto(
    val isPurchased: Boolean = false,
    val isSubscribed: Boolean = false,
)

@Serializable
class LezhinSignedUrlResponseDto(
    val data: LezhinSignedUrlDto,
)

@Serializable
class LezhinSignedUrlDto(
    @SerialName("Policy")
    val policy: String,
    @SerialName("Signature")
    val signature: String,
    @SerialName("Key-Pair-Id")
    val keyPairId: String,
)

package eu.kanade.tachiyomi.extension.all.patreon

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.util.Locale
import kotlin.time.Instant

@Serializable
class PatreonApiRoot(
    val data: JsonElement = JsonArray(emptyList()),
    val included: List<PatreonResource> = emptyList(),
    val links: PatreonLinks? = null,
)

@Serializable
class PatreonLinks(
    val next: String? = null,
)

@Serializable
class PatreonResource(
    val id: String,
    val type: String? = null,
    val attributes: PatreonAttributes = PatreonAttributes(),
    val relationships: PatreonRelationships = PatreonRelationships(),
)

typealias PatreonPost = PatreonResource

@Serializable
class PatreonResourceRef(
    val id: String,
)

@Serializable
class PatreonRelationships(
    @SerialName("active_memberships") val activeMemberships: PatreonRelationship? = null,
    @SerialName("attachments_media") val attachmentsMedia: PatreonRelationship? = null,
    @SerialName("card_campaign") val cardCampaign: PatreonRelationship? = null,
    val attachments: PatreonRelationship? = null,
    val images: PatreonRelationship? = null,
    val media: PatreonRelationship? = null,
    val campaign: PatreonRelationship? = null,
    val items: PatreonRelationship? = null,
)

@Serializable
class PatreonRelationship(
    val data: JsonElement? = null,
)

@Serializable
class PatreonAttributes(
    val name: String? = null,
    val title: String? = null,
    val url: String? = null,
    @SerialName("url_for_current_user") val urlForCurrentUser: String? = null,
    val vanity: String? = null,
    val summary: String? = null,
    val content: String? = null,
    val image: PatreonImage? = null,
    val mimetype: String? = null,
    @SerialName("content_json_string") val contentJsonString: String? = null,
    @SerialName("current_user_can_view") val currentUserCanView: Boolean? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("post_file") val postFile: PatreonPostFile? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("image_urls") val imageUrls: PatreonImageUrls? = null,
    @SerialName("avatar_photo_url") val avatarPhotoUrl: String? = null,
    @SerialName("avatar_photo_image_urls") val avatarPhotoImageUrls: PatreonImageUrls? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("cover_photo_url") val coverPhotoUrl: String? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("campaign_id") val campaignId: JsonElement? = null,
)

@Serializable
class PatreonImage(
    val url: String? = null,
    @SerialName("large_url") val largeUrl: String? = null,
)

@Serializable
class PatreonPostFile(
    val name: String? = null,
    val url: String? = null,
)

@Serializable
class PatreonImageUrls(
    val original: String? = null,
    val default: String? = null,
    val large: String? = null,
    val thumbnail: String? = null,
    @SerialName("default_large") val defaultLarge: String? = null,
)

private inline fun <reified T> JsonElement.decodeOrNull(json: Json): T? = runCatching { json.decodeFromJsonElement<T>(this) }.getOrNull()

private inline fun <reified T> JsonElement.asList(json: Json): List<T> = when (this) {
    is JsonArray -> mapNotNull { element -> element.decodeOrNull<T>(json) }
    is JsonObject -> listOfNotNull(decodeOrNull<T>(json))
    else -> emptyList()
}

fun PatreonApiRoot.dataPosts(json: Json): List<PatreonPost> = data.asList(json)

fun PatreonApiRoot.dataResource(json: Json): PatreonResource = when (data) {
    is JsonObject -> json.decodeFromJsonElement(data)
    else -> throw Exception("Unexpected Patreon response")
}

private fun JsonElement.asResourceList(json: Json): List<PatreonResource> = asList(json)

private fun relationshipRefs(
    relationship: PatreonRelationship?,
    json: Json,
): List<PatreonResourceRef> = relationship?.data?.asList(json) ?: emptyList()

private fun relationshipRef(
    relationship: PatreonRelationship?,
    json: Json,
): PatreonResourceRef? = (relationship?.data as? JsonObject)?.decodeOrNull(json)

fun PatreonApiRoot.currentUserMembershipResults(json: Json): List<SManga> {
    val includedById = included.associateBy { resource -> resource.id }
    val currentUser = data.asResourceList(json).firstOrNull() ?: return emptyList()

    return relationshipRefs(currentUser.relationships.activeMemberships, json).mapNotNull { membershipRef ->
        val membership = includedById[membershipRef.id] ?: return@mapNotNull null
        val campaignRef = relationshipRef(membership.relationships.campaign, json) ?: return@mapNotNull null
        val campaign = includedById[campaignRef.id] ?: return@mapNotNull null
        val campaignId = campaign.attributes.campaignId.asString()
            ?: campaign.id.takeIf { id -> id.isNotBlank() }
            ?: return@mapNotNull null

        campaign.toSManga(campaignId)
    }.distinctBy { manga -> manga.url }
}

fun PatreonApiRoot.exploreCampaignResults(json: Json): List<SManga> {
    val includedById = included.associateBy { resource -> resource.id }
    val result = mutableListOf<SManga>()

    data.asResourceList(json).forEach { section ->
        relationshipRefs(section.relationships.items, json).forEach { ref ->
            val item = includedById[ref.id] ?: return@forEach
            val campaign = item.resolveExploreCampaign(includedById, json) ?: return@forEach
            val campaignId = campaign.attributes.campaignId.asString()
                ?: campaign.id.takeIf { id -> id.isNotBlank() }
                ?: return@forEach

            result.add(campaign.toSManga(campaignId))
        }
    }

    if (result.isEmpty()) {
        included.forEach { resource ->
            val campaign = resource.resolveExploreCampaign(includedById, json) ?: return@forEach
            val campaignId = campaign.attributes.campaignId.asString()
                ?: campaign.id.takeIf { id -> id.isNotBlank() }
                ?: return@forEach

            result.add(campaign.toSManga(campaignId))
        }
    }

    return result.distinctBy { manga -> manga.url }
}

fun PatreonApiRoot.searchFeedCampaignResults(json: Json): List<SManga> {
    val includedById = included.associateBy { resource -> resource.id }

    return data.asResourceList(json).mapNotNull { resource ->
        val campaign = resource.resolveCampaignFromSearchFeed(includedById, json) ?: return@mapNotNull null
        val campaignId = campaign.attributes.campaignId.asString()
            ?: campaign.id.takeIf { id -> id.isNotBlank() }
            ?: return@mapNotNull null

        campaign.toSManga(campaignId)
    }.distinctBy { manga -> manga.url }
}

fun PatreonApiRoot.searchResults(baseUrl: String): List<SManga> {
    val includedById = included.associateBy { resource -> resource.id }

    val elements = when (data) {
        is JsonArray -> data
        is JsonObject -> JsonArray(listOf(data))
        else -> JsonArray(emptyList())
    }

    return elements.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val type = obj.string("type").orEmpty()
        val id = obj.string("id").orEmpty()
        val attrs = obj.obj("attributes") ?: JsonObject(emptyMap())

        val campaignRefId = obj.obj("relationships")
            ?.obj("campaign")
            ?.get("data")
            ?.let { relationshipData ->
                (relationshipData as? JsonObject)?.string("id")
            }

        val campaignFromIncluded = campaignRefId?.let { campaignId ->
            includedById[campaignId]
        }

        val campaignId = when {
            type.contains("campaign", ignoreCase = true) && id.isNotBlank() -> id
            campaignRefId != null -> campaignRefId
            campaignFromIncluded != null -> campaignFromIncluded.id
            else -> null
        }

        val campaignAttrs = campaignFromIncluded?.attributes

        val title = campaignAttrs?.name
            ?: attrs.string("name")
            ?: attrs.string("title")
            ?: attrs.string("full_name")
            ?: attrs.string("vanity")
            ?: return@mapNotNull null

        val patreonUrl = campaignAttrs?.url
            ?: attrs.string("url")
            ?: attrs.string("patreon_url")
            ?: attrs.string("vanity")?.let { vanity -> "$baseUrl/$vanity" }

        val username = campaignAttrs?.pageUsername()
            ?: attrs.string("vanity")
            ?: patreonUrl.usernameFromPatreonUrl()
            ?: title

        val thumbnail = campaignAttrs?.avatarPhotoUrl
            ?: campaignAttrs?.avatarPhotoImageUrls.best()
            ?: campaignAttrs?.avatarUrl
            ?: campaignAttrs?.coverPhotoUrl
            ?: attrs.string("avatar_photo_url")
            ?: attrs.obj("avatar_photo_image_urls")?.imageUrlsBest()
            ?: attrs.string("avatar_url")
            ?: attrs.string("image_url")
            ?: attrs.string("thumbnail_url")
            ?: attrs.string("cover_photo_url")
            ?: attrs.obj("image")?.string("url")
            ?: attrs.obj("avatar")?.string("url")

        val description = attrs.string("summary").htmlToMarkdown().orEmpty()

        SManga.create().apply {
            url = campaignId?.let { campaign -> "/campaign/$campaign" } ?: patreonUrl.toSourcePath(baseUrl)
            this.title = title
            author = username
            artist = username
            thumbnail_url = thumbnail
            this.description = description
            initialized = true
        }
    }.distinctBy { manga -> manga.url }
}

fun PatreonResource.toSManga(
    campaignId: String,
    fallbackName: String = "",
): SManga = SManga.create().apply {
    val username = attributes.pageUsername()

    url = "/campaign/$campaignId"
    title = attributes.name ?: fallbackName.ifBlank { "Patreon campaign $campaignId" }
    author = username
    artist = username
    thumbnail_url = attributes.avatarPhotoUrl
        ?: attributes.avatarPhotoImageUrls.best()
        ?: attributes.avatarUrl
        ?: attributes.coverPhotoUrl
        ?: attributes.coverUrl
    description = attributes.summary.htmlToMarkdown().orEmpty()
    initialized = true
}

fun PatreonPost.toSChapter(
    campaignId: String,
    locked: Boolean = false,
): SChapter = SChapter.create().apply {
    url = "/campaign/$campaignId/post/$id" + if (locked) "?locked=true" else ""

    val rawName = attributes.title?.takeIf { title -> title.isNotBlank() } ?: "Post $id"
    name = if (locked) "🔒 $rawName" else rawName

    date_upload = attributes.publishedAt.parsePatreonDate()
    chapter_number = -2f
}

fun PatreonPost.isLocked(): Boolean = attributes.currentUserCanView == false

fun PatreonPost.imageUrls(
    root: PatreonApiRoot,
    json: Json,
): List<String> {
    if (attributes.currentUserCanView == false) return emptyList()

    val includedById = root.included.associateBy { resource -> resource.id }
    val urls = mutableListOf<String>()

    val refs = mutableListOf<PatreonResourceRef>().apply {
        addAll(relationshipRefs(relationships.attachmentsMedia, json))
        addAll(relationshipRefs(relationships.attachments, json))
        addAll(relationshipRefs(relationships.images, json))
        addAll(relationshipRefs(relationships.media, json))
    }.distinctBy { ref -> ref.id }

    refs.forEach { ref ->
        val media = includedById[ref.id] ?: return@forEach
        media.attributes.bestImageUrl()?.let { imageUrl ->
            urls.add(imageUrl)
        }
    }

    attributes.postFile?.let { postFile ->
        val url = postFile.url

        if (!url.isNullOrBlank() && (postFile.name.isImageFileName() || url.isImageUrl())) {
            urls.add(url)
        }
    }

    attributes.image?.let { image ->
        listOf(image.largeUrl, image.url).forEach { imageUrl ->
            if (!imageUrl.isNullOrBlank()) {
                urls.add(imageUrl)
            }
        }
    }

    attributes.content?.extractImageUrlsFromHtml()?.let { contentUrls ->
        urls.addAll(contentUrls)
    }

    attributes.contentJsonString?.extractStructuredImageUrls(json, includedById)?.let { contentUrls ->
        urls.addAll(contentUrls)
    }

    return urls.distinct()
}

fun List<String>.toPages(): List<Page> = mapIndexed { index, url -> Page(index, imageUrl = url) }

private fun PatreonResource.resolveExploreCampaign(
    includedById: Map<String, PatreonResource>,
    json: Json,
): PatreonResource? {
    if (
        type?.contains("explore-campaign", ignoreCase = true) == true ||
        type?.contains("campaign", ignoreCase = true) == true
    ) {
        if (attributes.name != null || attributes.campaignId != null) {
            return this
        }
    }

    val campaignId = relationshipRef(relationships.campaign, json)?.id
    if (campaignId != null) {
        includedById[campaignId]?.let { campaign ->
            return campaign
        }
    }

    return null
}

private fun PatreonResource.resolveCampaignFromSearchFeed(
    includedById: Map<String, PatreonResource>,
    json: Json,
): PatreonResource? {
    if (type?.contains("campaign", ignoreCase = true) == true && attributes.name != null) {
        return this
    }

    val directCampaignId = relationshipRef(relationships.campaign, json)?.id
    if (directCampaignId != null) {
        includedById[directCampaignId]?.let { campaign ->
            return campaign
        }
    }

    val cardId = relationshipRef(relationships.cardCampaign, json)?.id
    if (cardId != null) {
        val cardCampaign = includedById[cardId]

        if (cardCampaign != null) {
            val nestedCampaignId = relationshipRef(cardCampaign.relationships.campaign, json)?.id

            if (nestedCampaignId != null) {
                includedById[nestedCampaignId]?.let { campaign ->
                    return campaign
                }
            }

            if (cardCampaign.type?.contains("campaign", ignoreCase = true) == true) {
                return cardCampaign
            }
        }
    }

    if (attributes.campaignId.asString() != null) {
        return this
    }

    return null
}

private fun PatreonAttributes.bestImageUrl(): String? {
    val candidates = listOfNotNull(
        downloadUrl,
        imageUrls?.original,
        imageUrls?.default,
        imageUrls?.large,
        imageUrls?.defaultLarge,
        imageUrls?.thumbnail,
        url,
    )

    val knownImage = mimetype?.startsWith("image/", ignoreCase = true) == true ||
        fileName.isImageFileName() ||
        name.isImageFileName()

    return candidates.firstOrNull { candidate ->
        knownImage || candidate.isImageUrl()
    }
}

private fun PatreonAttributes.pageUsername(): String = vanity?.takeIf { value -> value.isNotBlank() }
    ?: url.usernameFromPatreonUrl()
    ?: urlForCurrentUser.usernameFromPatreonUrl()
    ?: name?.takeIf { value -> value.isNotBlank() }
    ?: "Patreon"

internal fun String?.usernameFromPatreonUrl(): String? {
    if (isNullOrBlank()) return null

    return substringBefore("?")
        .substringBefore("#")
        .trimEnd('/')
        .substringAfterLast('/')
        .takeIf { value ->
            value.isNotBlank() &&
                value != "www.patreon.com" &&
                !value.contains("patreon.com")
        }
}

private fun String?.htmlToMarkdown(): String? {
    if (isNullOrBlank()) return null

    val document = Jsoup.parseBodyFragment(this)
    val markdown = document.body().childNodes()
        .joinToString("") { node -> node.toMarkdown() }
        .replace("\u00A0", " ")

    return markdown
        .lineSequence()
        .joinToString("\n") { line -> line.trim(' ', '\t') }
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
        .takeIf { value -> value.isNotBlank() }
}

private fun Node.toMarkdown(): String = when (this) {
    is TextNode -> text().replace("\u00A0", " ")
    is Element -> toMarkdownElement()
    else -> childNodes().joinToString("") { node -> node.toMarkdown() }
}

private fun Element.toMarkdownElement(): String {
    val tag = tagName().lowercase(Locale.ROOT)

    return when (tag) {
        "br" -> "\n"

        "p" -> {
            val content = childrenToMarkdown().trim()
            if (content.isBlank()) "" else "$content\n\n"
        }

        "strong", "b" -> wrapMarkdown("**", childrenToMarkdown())
        "em", "i" -> wrapMarkdown("*", childrenToMarkdown())
        "s", "strike", "del" -> wrapMarkdown("~~", childrenToMarkdown())

        "a" -> {
            val text = childrenToMarkdown().trim().ifBlank { text().trim() }
            val href = attr("href").ifBlank { attr("abs:href") }.trim()

            when {
                text.isBlank() && href.isBlank() -> ""
                href.isBlank() -> text
                text.isBlank() || text == href -> href
                else -> "[$text]($href)"
            }
        }

        "img" -> {
            val src = attr("src").ifBlank { attr("abs:src") }.trim()
            val alt = attr("alt")
                .ifBlank { attr("title") }
                .ifBlank { "image" }
                .trim()

            if (src.isBlank()) "" else "![$alt]($src)"
        }

        "ul" -> {
            val items = childNodes()
                .joinToString("") { node ->
                    if (node is Element && node.tagName().equals("li", ignoreCase = true)) {
                        "- ${node.childrenToMarkdown().trim()}\n"
                    } else {
                        node.toMarkdown()
                    }
                }
                .trimEnd()

            if (items.isBlank()) "" else "$items\n\n"
        }

        "ol" -> {
            var index = 1

            val items = childNodes()
                .joinToString("") { node ->
                    if (node is Element && node.tagName().equals("li", ignoreCase = true)) {
                        "${index++}. ${node.childrenToMarkdown().trim()}\n"
                    } else {
                        node.toMarkdown()
                    }
                }
                .trimEnd()

            if (items.isBlank()) "" else "$items\n\n"
        }

        "li" -> {
            val content = childrenToMarkdown().trim()
            if (content.isBlank()) "" else "- $content\n"
        }

        "blockquote" -> {
            val content = childrenToMarkdown().trim()

            if (content.isBlank()) {
                ""
            } else {
                content.lines()
                    .joinToString("\n") { line -> "> $line" } + "\n\n"
            }
        }

        "h1" -> markdownHeading(1)
        "h2" -> markdownHeading(2)
        "h3" -> markdownHeading(3)
        "h4" -> markdownHeading(4)
        "h5" -> markdownHeading(5)
        "h6" -> markdownHeading(6)

        else -> childrenToMarkdown()
    }
}

private fun Element.childrenToMarkdown(): String = childNodes().joinToString("") { node -> node.toMarkdown() }

private fun Element.markdownHeading(level: Int): String {
    val content = childrenToMarkdown().trim()

    if (content.isBlank()) return ""

    return "${"#".repeat(level)} $content\n\n"
}

private fun wrapMarkdown(marker: String, value: String): String {
    val content = value.trim()

    if (content.isBlank()) return ""

    return "$marker$content$marker"
}

private fun String?.parsePatreonDate(): Long {
    if (isNullOrBlank()) return 0L

    return Instant.parseOrNull(this)?.toEpochMilliseconds() ?: 0L
}

private fun String?.isImageFileName(): Boolean {
    if (isNullOrBlank()) return false

    val clean = substringBefore('?')
        .substringBefore('#')
        .lowercase(Locale.ROOT)

    return IMAGE_EXTENSIONS.any { extension -> clean.endsWith(extension) }
}

private fun String.isImageUrl(): Boolean = substringBefore('?')
    .substringBefore('#')
    .isImageFileName()

private fun String.extractImageUrlsFromHtml(): List<String> = Jsoup.parse(this)
    .select("img[src], source[srcset]")
    .flatMap { element ->
        val src = element.attr("abs:src")
            .ifBlank { element.attr("src") }

        val srcset = element.attr("srcset")
            .split(',')
            .map { entry -> entry.trim().substringBefore(' ') }

        listOf(src) + srcset
    }
    .filter { url -> url.startsWith("http") }
    .distinct()

private fun String.extractStructuredImageUrls(
    json: Json,
    includedById: Map<String, PatreonResource>,
): List<String> {
    val element = runCatching {
        json.parseToJsonElement(this)
    }.getOrNull() ?: return extractImageUrlsFromText()

    val urls = mutableListOf<String>()
    element.collectStructuredImageUrls(includedById, urls)

    return urls.distinct()
}

private fun JsonElement.collectStructuredImageUrls(
    includedById: Map<String, PatreonResource>,
    urls: MutableList<String>,
) {
    when (this) {
        is JsonArray -> forEach { element ->
            element.collectStructuredImageUrls(includedById, urls)
        }

        is JsonObject -> {
            val nodeType = string("type").orEmpty()
            val isImageNode = nodeType.contains("image", ignoreCase = true)

            for ((key, value) in this) {
                val primitive = (value as? JsonPrimitive)?.contentOrNull

                if (primitive != null) {
                    val normalized = primitive.replace("\\/", "/")

                    if (key in MEDIA_ID_KEYS || (isImageNode && key == "id")) {
                        includedById[normalized]?.attributes?.bestImageUrl()?.let { imageUrl ->
                            urls.add(imageUrl)
                        }
                    }

                    if (normalized.startsWith("http")) {
                        if (
                            normalized.isImageUrl() ||
                            key in IMAGE_URL_KEYS ||
                            (isImageNode && key in IMAGE_NODE_URL_KEYS)
                        ) {
                            urls.add(normalized)
                        }
                    } else {
                        urls.addAll(normalized.extractImageUrlsFromText())
                    }
                } else {
                    value.collectStructuredImageUrls(includedById, urls)
                }
            }
        }

        is JsonPrimitive -> {
            contentOrNull?.let { content ->
                urls.addAll(content.extractImageUrlsFromText())
            }
        }
    }
}

private fun String.extractImageUrlsFromText(): List<String> = IMAGE_URL_REGEX
    .findAll(this)
    .map { match -> match.value.replace("\\/", "/") }
    .filter { url -> url.isImageUrl() }
    .distinct()
    .toList()

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull

internal fun String?.toSourcePath(baseUrl: String): String {
    val clean = this
        ?.substringBefore("?")
        ?.substringBefore("#")
        ?.trim()
        .orEmpty()

    return when {
        clean.isBlank() -> "/"

        clean.startsWith(baseUrl) ->
            clean.removePrefix(baseUrl)
                .ifBlank { "/" }

        clean.startsWith("/") -> clean

        clean.startsWith("http://") ||
            clean.startsWith("https://") -> clean

        else -> "/$clean"
    }
}

private fun PatreonImageUrls?.best(): String? = this?.original
    ?: this?.default
    ?: this?.large
    ?: this?.defaultLarge
    ?: this?.thumbnail

private fun JsonObject.imageUrlsBest(): String? = string("original")
    ?: string("default")
    ?: string("large")
    ?: string("default_large")
    ?: string("thumbnail")

private val IMAGE_EXTENSIONS =
    listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif")

private val MEDIA_ID_KEYS =
    setOf("media_id", "mediaId", "image_id", "imageId")

private val IMAGE_URL_KEYS =
    setOf(
        "src",
        "image_url",
        "imageUrl",
        "large_url",
        "largeUrl",
        "original",
        "default",
        "large",
        "thumbnail",
        "default_large",
    )

private val IMAGE_NODE_URL_KEYS =
    setOf("src", "url", "download_url", "downloadUrl")

private val IMAGE_URL_REGEX =
    Regex(
        """https?:\\?/\\?/[^"'<>\s]+\.(?:jpg|jpeg|png|gif|webp|avif)(?:\?[^"'<>\s]*)?""",
        RegexOption.IGNORE_CASE,
    )

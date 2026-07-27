package network.lapis.cloud.server.federation

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/** Content type this wave's Actor document/inbox/outbox responses and requests use, per ActivityPub's own spec (RFC 6838 media type, not a made-up one). */
internal const val ACTIVITY_JSON_CONTENT_TYPE = "application/activity+json"

/**
 * Lenient JSON parser for inbound Activities -- `ignoreUnknownKeys` so a FUTURE field this project
 * adds (or a vanilla ActivityPub extension a remote server sends) never breaks decoding here; this
 * codebase's own [Activity]/[LapisExtensionDto] shapes are always the authority for what this
 * server itself understands, never a hard rejection of anything extra.
 */
internal val FEDERATION_JSON: Json = Json { ignoreUnknownKeys = true }

/** The project-controlled JSON-LD `@context` every Actor document/Activity this server emits declares -- standard ActivityStreams + security-v1 vocabularies plus the namespaced `lapis:` extension term (V0.8.1 Federation-Grundgerüst). */
internal val ACTIVITY_PUB_CONTEXT: JsonElement =
    buildJsonArray {
        add(JsonPrimitive("https://www.w3.org/ns/activitystreams"))
        add(JsonPrimitive("https://w3id.org/security/v1"))
        add(JsonObject(mapOf("lapis" to JsonPrimitive("https://lapisproject.dev/ns/federation-v1#"))))
    }

/**
 * A minimal, valid ActivityPub `Actor` object -- one per Lapis-Cloud instance (the organization
 * itself), served at `GET /federation/actor`. See `24-federation.kuml.kts` file header "Actor =
 * Organization, not Member".
 *
 * `@EncodeDefault(ALWAYS)` on [context]/[type]: [FEDERATION_JSON] deliberately does NOT set
 * `encodeDefaults = true` globally (that would also force [Activity.lapisExtension]'s `null`
 * default to serialize as an explicit `"lapis:extension":null`, defeating the whole point of
 * `ActivityPubExtensionRoundTripTest`'s "absent, not null-valued, when unused" assertion) -- these
 * two fields opt in individually instead, since `@context`/`type` are ActivityPub-spec-mandated
 * and must always appear on the wire, unlike the vast majority of this codebase's other
 * `@Serializable` DTOs (which correctly rely on the default omission behavior).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ActorDocument(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("@context")
    val context: JsonElement = ACTIVITY_PUB_CONTEXT,
    val id: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "Organization",
    val name: String,
    val inbox: String,
    val outbox: String,
    val publicKey: PublicKeyBlock,
)

@Serializable
data class PublicKeyBlock(
    val id: String,
    val owner: String,
    val publicKeyPem: String,
)

/**
 * The Meritokratie extension vocabulary -- namespaced under the `lapis:` JSON-LD term declared in
 * [ACTIVITY_PUB_CONTEXT]. Nested under a single `lapis:extension` key on [Activity] (not flattened
 * top-level keys) so a vanilla/non-Lapis-Cloud ActivityPub parser trivially ignores the whole block
 * per JSON-LD's own extensibility semantics (one unknown top-level key, not N).
 *
 * **No concrete content type populates this yet** -- see
 * [network.lapis.cloud.shared.rpc.IFederationService] KDoc "Scope boundary". This exists purely to
 * prove the extension mechanism round-trips before any real content type needs it (task
 * requirement: a serialization round-trip test, see `ActivityPubExtensionRoundTripTest`).
 */
@Serializable
data class LapisExtensionDto(
    val ltrAmount: String? = null,
    val voteWeight: String? = null,
    val pseudonymReputationAnchor: String? = null,
)

/** A `Follow`/`Accept`/`Reject`/`Undo` (or, forward-compatibly, any other) ActivityPub Activity -- the wire shape [network.lapis.cloud.server.routes.registerFederationRoutes]' inbox handler decodes/emits. `@context`'s `@EncodeDefault(ALWAYS)` mirrors [ActorDocument]'s own -- see that class's KDoc. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Activity(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("@context")
    val context: JsonElement = ACTIVITY_PUB_CONTEXT,
    val id: String,
    val type: String,
    val actor: String,
    @SerialName("object")
    val activityObject: JsonElement,
    @SerialName("lapis:extension")
    val lapisExtension: LapisExtensionDto? = null,
)

/**
 * A minimal subset of [Activity] a vanilla/non-Lapis-Cloud ActivityPub parser might actually use
 * -- base ActivityStreams fields only, no `lapis:` awareness at all. Used ONLY by
 * `ActivityPubExtensionRoundTripTest` to demonstrate forward-compatibility, never referenced by
 * production code (a real vanilla parser is, definitionally, code this project does not own).
 */
@Serializable
data class VanillaActivity(
    val id: String,
    val type: String,
    val actor: String,
)

/** `object` field helper -- an ActivityPub `Follow`'s `object` is the target actor URI as a bare string. */
internal fun actorUriObject(actorUri: String): JsonElement = JsonPrimitive(actorUri)

/** `object` field helper -- `Accept`/`Reject`/`Undo` embed (or id-reference) the Activity they respond to; this wave always embeds the full original `Follow` id as a bare string reference, the simplest valid ActivityPub shape. */
internal fun activityIdObject(activityId: String): JsonElement = JsonPrimitive(activityId)

/** Extracts a bare string `object` value (an actor URI or an activity id reference) -- `null` if `object` is not a JSON string (e.g. an embedded object this wave never emits but could receive from a remote server). */
internal fun JsonElement.asObjectIdOrNull(): String? =
    when (this) {
        is JsonPrimitive -> if (isString) content else null
        is JsonObject -> (this["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        is JsonArray -> null
    }

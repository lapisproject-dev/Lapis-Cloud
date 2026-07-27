package network.lapis.cloud.server.federation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonPrimitive

/**
 * Proves the `lapis:` JSON-LD extension mechanism round-trips (task requirement 4) -- WITHOUT any
 * real content type behind it yet, per [network.lapis.cloud.shared.rpc.IFederationService] KDoc
 * "Scope boundary". Also demonstrates that a vanilla/non-Lapis-Cloud ActivityPub parser (modelled
 * here by [VanillaActivity], which knows nothing about `lapis:extension`) safely ignores the
 * unknown extension block per JSON-LD/JSON's own extensibility semantics.
 */
class ActivityPubExtensionRoundTripTest :
    FunSpec({
        test("an Activity carrying a populated lapisExtension survives encode -> decode byte-for-byte on its extension fields") {
            val original =
                Activity(
                    id = "https://example.org/federation/activities/1",
                    type = "Follow",
                    actor = "https://example.org/federation/actor",
                    activityObject = actorUriObject("https://remote.example/federation/actor"),
                    lapisExtension =
                        LapisExtensionDto(
                            ltrAmount = "42.50",
                            voteWeight = "0.875",
                            pseudonymReputationAnchor = "sha256:abcdef0123456789",
                        ),
                )

            val json = FEDERATION_JSON.encodeToString(Activity.serializer(), original)
            val decoded = FEDERATION_JSON.decodeFromString(Activity.serializer(), json)

            decoded.lapisExtension shouldBe original.lapisExtension
            decoded.id shouldBe original.id
            decoded.type shouldBe original.type
            decoded.actor shouldBe original.actor
        }

        test(
            "the same JSON decodes cleanly into a minimal VanillaActivity (base ActivityStreams fields only) -- a non-Lapis-Cloud parser is never broken by the unknown lapis:extension block",
        ) {
            val original =
                Activity(
                    id = "https://example.org/federation/activities/2",
                    type = "Follow",
                    actor = "https://example.org/federation/actor",
                    activityObject = actorUriObject("https://remote.example/federation/actor"),
                    lapisExtension = LapisExtensionDto(ltrAmount = "10.00"),
                )
            val json = FEDERATION_JSON.encodeToString(Activity.serializer(), original)

            val vanillaJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val vanilla = vanillaJson.decodeFromString(VanillaActivity.serializer(), json)

            vanilla.id shouldBe original.id
            vanilla.type shouldBe original.type
            vanilla.actor shouldBe original.actor
        }

        test(
            "lapisExtension = null (no extension data) means the lapis:extension key is entirely ABSENT from the serialized JSON, not null-valued -- the mechanism costs nothing when unused",
        ) {
            val activity =
                Activity(
                    id = "https://example.org/federation/activities/3",
                    type = "Follow",
                    actor = "https://example.org/federation/actor",
                    activityObject = actorUriObject("https://remote.example/federation/actor"),
                    lapisExtension = null,
                )
            val json = FEDERATION_JSON.encodeToString(Activity.serializer(), activity)
            json shouldNotContain "lapis:extension"

            val decoded = FEDERATION_JSON.decodeFromString(Activity.serializer(), json)
            decoded.lapisExtension shouldBe null
        }

        test("object as a bare actor-URI string round-trips via asObjectIdOrNull") {
            val objectElement = actorUriObject("https://remote.example/federation/actor")
            objectElement.asObjectIdOrNull() shouldBe "https://remote.example/federation/actor"
        }

        test("object as an activity-id reference string round-trips via asObjectIdOrNull") {
            val objectElement = activityIdObject("https://example.org/federation/activities/1")
            objectElement.asObjectIdOrNull() shouldBe "https://example.org/federation/activities/1"
        }

        test(
            "asObjectIdOrNull extracts the id field from an embedded object shape (forward-compat with a remote server that embeds instead of references)",
        ) {
            val embedded =
                kotlinx.serialization.json.JsonObject(
                    mapOf("id" to JsonPrimitive("https://example.org/federation/activities/9"), "type" to JsonPrimitive("Follow")),
                )
            embedded.asObjectIdOrNull() shouldBe "https://example.org/federation/activities/9"
        }
    })

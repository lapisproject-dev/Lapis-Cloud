package network.lapis.cloud.server.conference

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 1 -- environment configuration for the LiveKit
 * SFU/Twirp connection this server talks to. Mirrors [network.lapis.cloud.server.federation.FederationConfig]'s
 * "plain `LAPIS_`-prefixed env var, sane local default" shape, with one addition FederationConfig
 * does not need: an explicit, fail-fast **validation** step, because a broken LiveKit credential
 * here is not "federation degraded" but "every mint of a participant/admin JWT throws a raw,
 * unexplained `KeyLengthException` from deep inside nimbus-jose-jwt at the first Twirp call" --
 * see [load] KDoc.
 *
 * Read via env-var lookups injected as a `(String) -> String?` function ([load]'s `env` parameter)
 * rather than `System.getenv` calls scattered through this class -- same reasoning
 * [network.lapis.cloud.server.postal.LetterxpressPostalMailProvider]'s KDoc gives for its own
 * constructor-parameter-default idiom: `System.getenv` cannot be mutated per-JVM-test-run, so a
 * hardcoded call here would make the partial-config/short-secret fail-fast paths untestable without
 * reflection hacks. Production code's call site (a future wave's `Application.module`) stays
 * `ConferenceConfig.load()` -- `System::getenv` is the default.
 */
class ConferenceConfig private constructor(
    /** WebSocket URL handed to the browser (`ws://`/`wss://`) -- blank iff the feature is unconfigured. Never sent anywhere but the client's own connect call; not a secret. */
    val livekitUrl: String,
    /** HTTP base for the Twirp Room-Service API ([LiveKitAdminClient]) -- see [load] for the `LAPIS_LIVEKIT_API_URL`-unset derivation from [livekitUrl]. */
    val livekitApiUrl: String,
    /** LiveKit API key -- the JWT `iss` claim on every minted token. Not secret in the cryptographic sense (it is not the signing key), but never logged anyway, symmetric with [apiSecret]. */
    val apiKey: String,
    /** LiveKit API secret -- the HMAC signing key for every minted token (see [LiveKitAccessToken]). **Never logged, never included in [toString], never placed in a DTO or exception message.** */
    val apiSecret: String,
    /** Participant join-token TTL -- see [LiveKitAccessToken] KDoc "why configurable, not LiveKit's own 6h SDK default". */
    val tokenTtlMinutes: Long,
    /** Wave-1 "Kleinsitzung" ceiling -- mirrors `deploy/local/livekit.yaml`'s own `room.max_participants: 25`, enforced a second time at the RPC layer (a future wave's `ConferenceService`). */
    val maxParticipants: Int,
    /**
     * TURN relay URL(s) handed to the browser as `RTCIceServer.urls` alongside a fresh
     * [network.lapis.cloud.server.conference.TurnCredentialMinter]-minted credential on every
     * [network.lapis.cloud.server.rpc.ConferenceService.joinRoom] call -- see [load] KDoc "TURN is
     * independently optional" for why this is never part of the [enabled] all-or-nothing gate. Empty
     * iff TURN is not configured (a deployment with only direct/host ICE candidates reachable, or one
     * that provides TURN some other way outside this codebase's own minting).
     */
    val turnUrls: List<String>,
    /**
     * Shared secret coturn's `use-auth-secret`/`static-auth-secret` mechanism verifies every minted
     * credential against (`deploy/local/turnserver.conf`'s `static-auth-secret` value) -- audit-
     * round-1 fix replacing the OLD forever-valid static TURN username/password pair. **Never logged,
     * never included in [toString], never placed in a DTO or exception message** -- same discipline
     * [apiSecret] documents.
     */
    val turnSharedSecret: String,
) {
    /** `true` iff [livekitUrl], [apiKey] and [apiSecret] are all non-blank -- see [load] KDoc "Startup behaviour" for what happens when only SOME are set (that state never reaches this property; [load] throws first). */
    val enabled: Boolean = livekitUrl.isNotBlank() && apiKey.isNotBlank() && apiSecret.isNotBlank()

    /** `true` iff [turnUrls] is non-empty AND [turnSharedSecret] is non-blank -- see [load] KDoc "TURN is independently optional". */
    val turnEnabled: Boolean = turnUrls.isNotEmpty() && turnSharedSecret.isNotBlank()

    /** Deliberately omits [apiSecret]/[turnSharedSecret] (and, for symmetry, [apiKey]) -- see class KDoc "Never logged". Anything that DOES want to log config state should log this string, not the individual fields. */
    override fun toString(): String {
        val keyState = if (apiKey.isBlank()) "<blank>" else "<redacted>"
        val secretState = if (apiSecret.isBlank()) "<blank>" else "<redacted>"
        val turnSecretState = if (turnSharedSecret.isBlank()) "<blank>" else "<redacted>"
        return "ConferenceConfig(enabled=$enabled, livekitUrl='$livekitUrl', livekitApiUrl='$livekitApiUrl', " +
            "apiKey=$keyState, apiSecret=$secretState, " +
            "tokenTtlMinutes=$tokenTtlMinutes, maxParticipants=$maxParticipants, " +
            "turnEnabled=$turnEnabled, turnUrls=$turnUrls, turnSharedSecret=$turnSecretState)"
    }

    companion object {
        /**
         * nimbus-jose-jwt's `MACSigner` (used by [LiveKitAccessToken]) refuses to construct an
         * HS256 signer for a key shorter than 256 bits -- see `deploy/local/livekit.yaml`'s file
         * header for the full story (`livekit-server --dev`'s hardcoded 48-bit `secret` is exactly
         * the trap this constant's check exists to catch before it reaches nimbus as an opaque
         * `KeyLengthException`).
         */
        const val MIN_API_SECRET_BYTES = 32

        private const val DEFAULT_TOKEN_TTL_MINUTES = 240L
        private const val DEFAULT_MAX_PARTICIPANTS = 25

        /**
         * Reads `LAPIS_LIVEKIT_URL`/`LAPIS_LIVEKIT_API_URL`/`LAPIS_LIVEKIT_API_KEY`/
         * `LAPIS_LIVEKIT_API_SECRET`/`LAPIS_LIVEKIT_TOKEN_TTL_MINUTES`/`LAPIS_CONFERENCE_MAX_PARTICIPANTS`
         * via [env] (defaults to [System.getenv]).
         *
         * **Startup behaviour** (three-way, deliberately NOT a plain "enabled/disabled" boolean
         * gate):
         * 1. If `LAPIS_LIVEKIT_URL`/`_API_KEY`/`_API_SECRET` are ALL blank/unset: the feature is
         *    simply off ([enabled] = `false`), no failure -- every existing test and
         *    `./gradlew clean check` run keeps passing with zero new env, matching every other
         *    optional-integration config in this codebase (e.g.
         *    [network.lapis.cloud.server.postal.LetterxpressPostalMailProvider]'s own
         *    blank-credential short-circuit).
         * 2. If SOME but not all three are set: throws [IllegalStateException] at load time -- a
         *    deployment that half-configured LiveKit almost certainly meant to fully configure it,
         *    and silently running with the feature off would hide a real operator mistake behind a
         *    confusing "Videokonferenz is just missing from the nav" symptom instead of a clear
         *    startup error.
         * 3. If a non-blank `LAPIS_LIVEKIT_API_SECRET` is shorter than [MIN_API_SECRET_BYTES] UTF-8
         *    bytes: throws [IllegalStateException] naming the nimbus 256-bit minimum explicitly --
         *    this is the exact trap `livekit-server --dev`'s hardcoded secret walks into (48 bits),
         *    caught here with an explanatory message instead of surfacing as an opaque
         *    `KeyLengthException` from inside nimbus-jose-jwt on the first token mint.
         *
         * `LAPIS_LIVEKIT_API_URL` is exempt from the all-or-nothing check in step 2: when unset, it
         * is derived from `LAPIS_LIVEKIT_URL` by swapping `ws://`/`wss://` for `http://`/`https://`
         * (the WebSocket scheme the browser needs vs. the plain HTTP scheme the Twirp Room-Service
         * API needs) -- so it never needs to be set explicitly for the common case of one LiveKit
         * deployment serving both.
         *
         * **TURN is independently optional** (audit-round-1 fix): `LAPIS_TURN_URLS` (comma-
         * separated, e.g. `turn:127.0.0.1:3478?transport=udp,turn:127.0.0.1:3478?transport=tcp`) and
         * `LAPIS_TURN_SHARED_SECRET` follow their OWN all-or-nothing pair check, entirely separate
         * from the LiveKit url/key/secret trio above -- a deployment can run LiveKit without TURN (no
         * relay fallback, direct/host ICE candidates only) just as easily as it could before this
         * fix, or run TURN without needing to touch the LiveKit trio at all. See [TurnCredentialMinter]
         * for how [turnSharedSecret] turns into a fresh, short-lived credential on every
         * [network.lapis.cloud.server.rpc.ConferenceService.joinRoom] call, replacing the OLD
         * forever-valid static TURN username/password pair `deploy/local/livekit.yaml`'s `rtc
         * .turn_servers` block used to embed.
         */
        fun load(env: (String) -> String? = System::getenv): ConferenceConfig {
            val url = env("LAPIS_LIVEKIT_URL")?.trim().orEmpty()
            val explicitApiUrl = env("LAPIS_LIVEKIT_API_URL")?.trim().orEmpty()
            val apiUrl = explicitApiUrl.ifBlank { deriveApiUrl(url) }
            val key = env("LAPIS_LIVEKIT_API_KEY")?.trim().orEmpty()
            val secret = env("LAPIS_LIVEKIT_API_SECRET")?.trim().orEmpty()
            val ttlMinutes = env("LAPIS_LIVEKIT_TOKEN_TTL_MINUTES")?.trim()?.toLongOrNull() ?: DEFAULT_TOKEN_TTL_MINUTES
            val maxParticipants = env("LAPIS_CONFERENCE_MAX_PARTICIPANTS")?.trim()?.toIntOrNull() ?: DEFAULT_MAX_PARTICIPANTS
            val turnUrls =
                env("LAPIS_TURN_URLS")
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
            val turnSharedSecret = env("LAPIS_TURN_SHARED_SECRET")?.trim().orEmpty()

            val presentCount = listOf(url, key, secret).count { it.isNotBlank() }
            check(presentCount == 0 || presentCount == 3) {
                "Incomplete LiveKit configuration: LAPIS_LIVEKIT_URL, LAPIS_LIVEKIT_API_KEY and " +
                    "LAPIS_LIVEKIT_API_SECRET must be either ALL set or ALL unset (got $presentCount/3 set) -- " +
                    "Videokonferenzen (Wave 1) cannot start in a half-configured state, see ConferenceConfig.load KDoc"
            }
            if (secret.isNotBlank()) {
                val secretBytes = secret.toByteArray(Charsets.UTF_8).size
                check(secretBytes >= MIN_API_SECRET_BYTES) {
                    "LAPIS_LIVEKIT_API_SECRET is only $secretBytes bytes -- nimbus-jose-jwt's MACSigner " +
                        "requires an HS256 HMAC key of at least $MIN_API_SECRET_BYTES bytes (256 bits). " +
                        "This is exactly the trap `livekit-server --dev`'s hardcoded 6-byte secret walks " +
                        "into -- use an explicit `keys:` block with a long secret instead, see " +
                        "deploy/local/livekit.yaml for a working example."
                }
            }
            val turnPresentCount = listOf(turnUrls.isNotEmpty(), turnSharedSecret.isNotBlank()).count { it }
            check(turnPresentCount == 0 || turnPresentCount == 2) {
                "Incomplete TURN configuration: LAPIS_TURN_URLS and LAPIS_TURN_SHARED_SECRET must be " +
                    "either BOTH set or BOTH unset (got $turnPresentCount/2 set) -- see ConferenceConfig.load " +
                    "KDoc \"TURN is independently optional\""
            }

            return ConferenceConfig(
                livekitUrl = url,
                livekitApiUrl = apiUrl,
                apiKey = key,
                apiSecret = secret,
                tokenTtlMinutes = ttlMinutes,
                maxParticipants = maxParticipants,
                turnUrls = turnUrls,
                turnSharedSecret = turnSharedSecret,
            )
        }

        private fun deriveApiUrl(wsUrl: String): String =
            when {
                wsUrl.startsWith("wss://") -> "https://" + wsUrl.removePrefix("wss://")
                wsUrl.startsWith("ws://") -> "http://" + wsUrl.removePrefix("ws://")
                else -> wsUrl
            }
    }
}

package network.lapis.cloud.server.crypto

import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private val logger = KotlinLogging.logger {}

/**
 * V1.0 Videokonferenzen (Kleinsitzung), Wave 3 "Externes Streaming" -- **this codebase's FIRST
 * at-rest encryption primitive.** A full grep of `lapis-server`/`lapis-shared` for
 * `encrypt|Cipher|AES|GCM|SecretKeySpec|KeyGenerator|javax.crypto` before this wave turned up
 * exactly two hits, neither of them at-rest encryption:
 * [network.lapis.cloud.server.conference.TurnCredentialMinter] (`javax.crypto.Mac` HMAC-SHA1
 * SIGNING) and [network.lapis.cloud.server.conference.LiveKitAccessToken] (nimbus `MACSigner` JWS
 * SIGNING). `network.lapis.cloud.server.backup.OrganizationExportService`/`BackupRoutes` even
 * explicitly document the *absence* of at-rest encryption as a deliberate posture for THEIR own
 * data. [SecretBox] is deliberately named generically (not `StreamSecretBox`/`RtmpKeyBox`) and
 * kept to the tiniest possible API surface so later waves needing their own encrypted-at-rest
 * credential (SMTP passwords, PSP credentials) reuse this class and its key-loading convention
 * rather than inventing a second scheme.
 *
 * **Algorithm: AES-256-GCM** (`AES/GCM/NoPadding`), a 96-bit IV freshly drawn from [SecureRandom]
 * on every [seal] call, a 128-bit authentication tag. AEAD, so tampering with stored ciphertext is
 * *detected* (an [SecretBoxException] on [open]) rather than silently decrypting into garbage that
 * then gets published as an RTMP URL to a stranger's channel.
 *
 * **A fresh [Cipher] instance is created on every [seal]/[open] call, never cached as a field** --
 * [Cipher] is NOT thread-safe, exactly the bug class this repo's own security checklist already
 * names for [java.security.MessageDigest] (see [network.lapis.cloud.server.audit.AuditHashChain]
 * for that precedent). [SecureRandom], by contrast, IS thread-safe per its own JDK contract, so a
 * single instance is reused across calls (same pattern
 * [network.lapis.cloud.server.security.SessionTokens] already establishes).
 *
 * **AAD binding to the owning row.** [seal]/[open] both take an [aad] (Additional Authenticated
 * Data) string -- this codebase's one call site
 * (`network.lapis.cloud.server.rpc.ConferenceStreamingService`, a later wave step) always passes
 * the owning `conference_stream_destination.id` (a UUID string). GCM authenticates but does not
 * encrypt the AAD, so a ciphertext copied from one destination row to another (e.g. via a crafted
 * `UPDATE`, or a bug that swaps two rows) fails to decrypt rather than silently authorizing a
 * stream to someone else's channel. Costs one extra parameter at every call site, closes a real
 * (if unlikely) DB-tampering path.
 *
 * **Versioned wire format, one column, human-inspectable structure**: [seal] returns
 * `"v1:<base64url(iv)>:<base64url(ciphertext||tag)>"` (Java's own GCM `Cipher.doFinal` already
 * appends the tag to the ciphertext, so no separate tag field is needed). The literal `v1:` prefix
 * makes a future key-rotation scheme or algorithm change expressible without a schema change --
 * [open] rejects any other prefix (or a value that does not split into exactly three `:`-separated
 * parts) outright, rather than guessing at an unknown format.
 *
 * **Never logged, never in `toString()`, never in an exception message.** [SecretBoxException]
 * always carries the fixed, generic German message below -- no plaintext, no ciphertext, no key
 * material, not even the underlying [javax.crypto] exception's own message/class hierarchy. The
 * one place any detail surfaces is a single WARN-level [logger] line naming only the failing
 * exception's simple class name (mirrors
 * [network.lapis.cloud.server.conference.ConferenceRecordingConfig.probeFfmpegAvailable]'s own
 * "log the failure kind, never the payload" discipline) -- that log line never leaves this file.
 *
 * **Constructed from an already-loaded key, not from `System.getenv` itself** -- so tests can
 * inject an arbitrary key without touching real environment variables (same testability reasoning
 * [network.lapis.cloud.server.conference.ConferenceConfig]'s own KDoc gives for its injected `env`
 * function). See `network.lapis.cloud.server.conference.ConferenceStreamingConfig.secretEncryptionKey`
 * for where a real deployment's key comes from and the fail-fast validation `LAPIS_SECRET_ENCRYPTION_KEY`
 * goes through before ever reaching this constructor.
 *
 * @param key exactly [KEY_SIZE_BYTES] raw bytes (AES-256) -- see [KEY_SIZE_BYTES] KDoc.
 */
class SecretBox(
    private val key: ByteArray,
) {
    init {
        require(key.size == KEY_SIZE_BYTES) {
            "SecretBox key must be exactly $KEY_SIZE_BYTES bytes (AES-256), got ${key.size}"
        }
    }

    /**
     * Encrypts [plaintext] under this box's key, authenticating [aad] alongside it (see class KDoc
     * "AAD binding to the owning row") -- returns the versioned wire format `"v1:<iv>:<ct||tag>"`.
     * A fresh IV and a fresh [Cipher] instance every call (see class KDoc) -- two [seal] calls with
     * an identical [plaintext]/[aad] pair therefore never produce the same output.
     */
    fun seal(
        plaintext: String,
        aad: String,
    ): String {
        val iv = ByteArray(IV_SIZE_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE_BITS, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val ciphertextAndTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return "$VERSION_PREFIX:${urlEncoder.encodeToString(iv)}:${urlEncoder.encodeToString(ciphertextAndTag)}"
    }

    /**
     * Reverses [seal] -- [aad] MUST be the exact same string passed at seal time (see class KDoc
     * "AAD binding to the owning row") or decryption fails. Throws [SecretBoxException] -- never
     * any raw [javax.crypto]/[IllegalArgumentException] -- on: a malformed [sealed] value (wrong
     * part count, a version prefix other than `v1`, non-base64url IV/ciphertext segments), a wrong
     * key, a mismatched [aad], or a tampered ciphertext/tag (GCM authentication failure). See class
     * KDoc "Never logged" for exactly what does and does not surface on failure.
     */
    fun open(
        sealed: String,
        aad: String,
    ): String {
        val parts = sealed.split(":")
        if (parts.size != 3 || parts[0] != VERSION_PREFIX) {
            logger.warn { "SecretBox.open: unsupported wire format (expected '$VERSION_PREFIX:<iv>:<ct>', got ${parts.size} part(s))" }
            throw SecretBoxException(DECRYPTION_FAILURE_MESSAGE)
        }
        return try {
            val iv = urlDecoder.decode(parts[1])
            val ciphertextAndTag = urlDecoder.decode(parts[2])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE_BITS, iv))
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(ciphertextAndTag), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            logger.warn { "SecretBox.open failed (${e::class.simpleName ?: "unknown GeneralSecurityException"})" }
            throw SecretBoxException(DECRYPTION_FAILURE_MESSAGE)
        } catch (e: IllegalArgumentException) {
            // Base64.Decoder.decode's own failure mode for a non-base64url segment.
            logger.warn { "SecretBox.open failed (${e::class.simpleName ?: "unknown IllegalArgumentException"})" }
            throw SecretBoxException(DECRYPTION_FAILURE_MESSAGE)
        }
    }

    companion object {
        /** AES-256 -- see class KDoc. Also the width [network.lapis.cloud.server.conference.ConferenceStreamingConfig.load] validates `LAPIS_SECRET_ENCRYPTION_KEY` against after base64-decoding. */
        const val KEY_SIZE_BYTES = 32

        private const val IV_SIZE_BYTES = 12
        private const val TAG_SIZE_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION_PREFIX = "v1"

        /** Fixed, generic, German -- see class KDoc "Never logged". */
        private const val DECRYPTION_FAILURE_MESSAGE = "Stream-Zugangsdaten konnten nicht entschluesselt werden"

        private val secureRandom = SecureRandom()
        private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
        private val urlDecoder = Base64.getUrlDecoder()
    }
}

/**
 * See [SecretBox.open]/[SecretBox.seal] KDoc "Never logged" -- [message] is always exactly
 * [SecretBox]'s own fixed, generic German string, and this class never carries a `cause` (a
 * `Throwable.cause` chain is exactly the kind of place a stray key/ciphertext fragment could leak
 * through a future refactor without anyone noticing) -- deliberately narrower than this codebase's
 * usual `message + cause: Throwable? = null` exception shape (e.g.
 * [network.lapis.cloud.server.conference.RecordingComposeException]) for that reason.
 */
class SecretBoxException(
    message: String,
) : Exception(message)

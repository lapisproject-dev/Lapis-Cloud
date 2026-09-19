package network.lapis.cloud.server.clientversion

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

/**
 * Welle V1.4.20 "Client-Hinweis: Neue Version verfuegbar" -- the build identifier of the KVision
 * client bundle THIS server process serves: a truncated SHA-256 over the actual `main.bundle.js`
 * file on disk.
 *
 * **Why a content hash and not a Gradle build-time constant.** The hash is taken over the file that
 * is really delivered, so it cannot drift from the served artifact; it stays identical across a
 * pure server restart (same bytes, same id) and changes exactly when the bundle changes. A
 * build-time constant would need cross-module Gradle plumbing (client constant + server resource
 * from one run) and would change even for an unchanged bundle.
 *
 * **Not a security token, and leaks nothing.** The id is a change detector. It is a hash over a file
 * that is publicly downloadable anyway (`GET /main.bundle.js` is open to everyone), so exposing it
 * via `GET /api/client-version` reveals nothing that was not already computable by any visitor: no
 * dependency versions, no host names, no paths. That is also why truncating to [ID_LENGTH] hex
 * characters (64 bit) is fine.
 */
object ClientBuildId {
    /** File name of the client bundle inside the client dist root. */
    const val BUNDLE_FILE_NAME = "main.bundle.js"

    /** Route the client polls -- single source of truth for server registration and tests. */
    const val ROUTE_PATH = "/api/client-version"

    /** Number of hex characters kept from the SHA-256 digest (64 bit). */
    const val ID_LENGTH = 16

    private const val BUFFER_BYTES = 8 * 1024
    private val WELL_FORMED = Regex("^[0-9a-f]{$ID_LENGTH}$")

    @Volatile
    private var failureLogged = false

    /**
     * SHA-256 over [BUNDLE_FILE_NAME] under [clientDistRoot], lower-case hex, truncated to
     * [ID_LENGTH] characters -- or `null` when no bundle is present / readable. NEVER throws: a
     * missing or broken bundle must never stop the server from starting (same "cosmetic, never
     * fail-fast" posture as `BrandingStartupCheck`). A fresh [MessageDigest] per call
     * (thread-safety); the file is streamed, never fully buffered.
     */
    fun compute(clientDistRoot: File): String? =
        try {
            val bundle = File(clientDistRoot, BUNDLE_FILE_NAME)
            if (!bundle.isFile) {
                null
            } else {
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(bundle).use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }.take(ID_LENGTH)
            }
        } catch (e: Exception) {
            if (!failureLogged) {
                failureLogged = true
                logger.warn { "Client-Bundle konnte nicht gehasht werden (${e::class.simpleName}) -- Versionshinweis deaktiviert." }
            }
            null
        }

    /** `true` iff [value] is exactly [ID_LENGTH] lower-case hex characters -- guard for injection and the route. */
    fun isWellFormed(value: String?): Boolean = value != null && WELL_FORMED.matches(value)
}

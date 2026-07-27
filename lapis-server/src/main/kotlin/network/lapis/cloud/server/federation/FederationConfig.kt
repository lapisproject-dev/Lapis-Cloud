package network.lapis.cloud.server.federation

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.security.DeploymentMode

private val logger = KotlinLogging.logger {}

/**
 * V0.8.1 Federation-Grundgerüst -- the stable, absolute, dereferenceable HTTPS base URL this
 * server's own ActivityPub Actor/inbox/outbox URIs are built from. Unlike every other env-var
 * read in this codebase (`LAPIS_DB_URL`, `LAPIS_DOCUMENT_STORAGE_ROOT`, ...), this one is
 * genuinely required for federation to function at all: a remote server must be able to
 * dereference [actorUri] to fetch our public key, and [inboxUri]/[outboxUri] must be reachable
 * from the public internet. No prior wave needed a "what is my own public URL" concept.
 *
 * Defaults to `http://localhost:8080` (matching every other localhost-default in this codebase,
 * e.g. `Application.main`'s own `embeddedServer(... port = 8080 ...)`) so a fresh local/dev/test
 * boot never crashes on a missing env var -- see [Application.module]'s startup WARN for the
 * loud, non-fatal signal that a real deployment must override this.
 */
object FederationConfig {
    val publicBaseUrl: String = System.getenv("LAPIS_PUBLIC_BASE_URL") ?: "http://localhost:8080"

    val actorUri: String get() = "$publicBaseUrl/federation/actor"
    val inboxUri: String get() = "$publicBaseUrl/federation/inbox"
    val outboxUri: String get() = "$publicBaseUrl/federation/outbox"

    /**
     * Logs a one-time `WARN` if [publicBaseUrl] is not `https://` and this is not the H2-in-memory
     * default deployment -- a real deployment without a real public HTTPS origin can never
     * actually federate (remote signature verification and actor-document fetches by other
     * servers would fail against a `localhost`/plain-HTTP URI). Never throws -- a
     * misconfiguration here must not prevent the rest of the server from starting, exactly the
     * same non-fatal posture `registerAuthRoutes`' own `cookieSecure` WARN idiom uses.
     */
    fun warnIfNotPubliclyReachable() {
        if (!publicBaseUrl.startsWith("https://") && !DeploymentMode.isH2InMemory()) {
            logger.warn {
                "LAPIS_PUBLIC_BASE_URL is '$publicBaseUrl' (not https://) in a non-H2-in-memory deployment -- " +
                    "federation with real remote servers will not work until this points at a real, public HTTPS origin"
            }
        }
    }
}

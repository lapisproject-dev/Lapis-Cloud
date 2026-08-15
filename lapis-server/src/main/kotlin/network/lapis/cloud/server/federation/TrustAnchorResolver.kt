package network.lapis.cloud.server.federation

import io.ktor.client.request.get
import io.ktor.http.isSuccess
import java.net.URLEncoder

/** Outcome of [TrustAnchorResolver.resolveOneHop] against exactly one candidate anchor. */
internal sealed interface OneHopResolution {
    data class Trusted(
        val anchorEntityUri: String,
    ) : OneHopResolution

    data class NotTrusted(
        val reason: String,
    ) : OneHopResolution
}

/**
 * The CONSUMING side of V0.8.3 Trust-Anchor-Governance: given a claimed home-server URI and ONE
 * candidate Trust Anchor entity URI, fetches and verifies a one-hop trust chain -- is the home
 * server currently included, with a validly-signed, non-expired Subordinate Statement, in that
 * anchor's pool. See `network.lapis.cloud.shared.domain.TrustAnchorEventType` KDoc "CRITICAL
 * FRAMING" -- this is a purely informational signal, [network.lapis.cloud.server.rpc.TrustAnchorService.resolveTrustChain]
 * (the only call site) never uses it to gate anything.
 *
 * **Two fetches, both SSRF-guarded, network I/O only** -- the actual cryptographic verification
 * (self-signed-bootstrap reasoning, temporal checks, signature checks) lives entirely in
 * [TrustAnchorChainVerification], deliberately split out so it can be tested directly with
 * hand-crafted JWTs and no network egress (this sandbox has no general internet egress, same
 * documented limitation V0.8.1/V0.8.2's own test suites already state for their outbound-fetch
 * happy paths).
 *
 * **Fresh-fetch, never cached**: both the Entity Configuration and the Subordinate Statement are
 * fetched anew on every call -- no in-memory/DB cache. This is what makes signing-key REVOCATION
 * (as opposed to mere pool-member removal) actually effective for this server acting as a Relying
 * Party toward some OTHER anchor too, symmetric with [TrustAnchorStatements]' own publishing-side
 * freshness guarantee.
 *
 * **SSRF-hardening reuse**: every outbound fetch here goes through [requireSafeFederationUrl]/
 * [federationHttpClient]/[readCappedFederationBodyOrNull] UNCHANGED -- both [homeServerUri]-derived
 * and [anchorEntityUri]-derived URLs (and the fetch-endpoint URL the anchor's OWN Entity
 * Configuration names) are attacker/operator-influenced input, exactly the class of input this
 * guard exists for. No new SSRF-guard code is written this wave, per this wave's task requirement.
 */
internal object TrustAnchorResolver {
    suspend fun resolveOneHop(
        homeServerUri: String,
        anchorEntityUri: String,
    ): OneHopResolution {
        val entityConfigUrl = "$anchorEntityUri/.well-known/openid-federation"
        if (runCatching { requireSafeFederationUrl(entityConfigUrl) }.isFailure) {
            return OneHopResolution.NotTrusted("Anchor entity URI is not a safe fetch target")
        }

        val entityConfigCompact =
            fetchCompactJwt(entityConfigUrl)
                ?: return OneHopResolution.NotTrusted("Could not fetch the anchor's Entity Configuration")
        val verifiedEntityConfig =
            TrustAnchorChainVerification.verifyEntityConfiguration(compact = entityConfigCompact, expectedAnchorEntityUri = anchorEntityUri)
                ?: return OneHopResolution.NotTrusted("Anchor's Entity Configuration failed verification")

        val fetchUrl = "${verifiedEntityConfig.fetchEndpoint}?sub=${URLEncoder.encode(homeServerUri, "UTF-8")}"
        if (runCatching { requireSafeFederationUrl(fetchUrl) }.isFailure) {
            return OneHopResolution.NotTrusted("Anchor's federation_fetch_endpoint is not a safe fetch target")
        }

        val statementCompact =
            fetchCompactJwt(fetchUrl)
                ?: return OneHopResolution.NotTrusted("Anchor does not vouch for this home server (fetch returned nothing usable)")
        val statementValid =
            TrustAnchorChainVerification.verifySubordinateStatement(
                compact = statementCompact,
                expectedAnchorEntityUri = anchorEntityUri,
                expectedHomeServerUri = homeServerUri,
                jwksJson = verifiedEntityConfig.jwksJson,
            )
        if (!statementValid) {
            return OneHopResolution.NotTrusted("Anchor's Subordinate Statement for this home server failed verification")
        }

        return OneHopResolution.Trusted(anchorEntityUri)
    }

    private suspend fun fetchCompactJwt(url: String): String? =
        runCatching {
            val target = requireSafeFederationUrl(url)
            federationHttpClient(target).use { client ->
                val response = client.get(url)
                if (!response.status.isSuccess()) return@use null
                val bytes = response.readCappedFederationBodyOrNull() ?: return@use null
                bytes.toString(Charsets.UTF_8).trim().takeIf { it.isNotBlank() }
            }
        }.getOrNull()
}

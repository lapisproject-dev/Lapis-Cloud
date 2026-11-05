package network.lapis.cloud.server.keycloak

import com.nimbusds.jwt.JWTClaimsSet
import network.lapis.cloud.server.keycloak.KeycloakMemberProvisioner.RejectReason
import java.util.Date

/**
 * Welle V1.9.285 -- diagnostic "shape" of a VERIFIED ID token for the log line of a refused just-in-time provisioning.
 *
 * Only claim NAMES (allowlisted) and coarse JSON type words are produced -- never a value, never a value length, never an
 * element of an array or a key of a nested object. The input MUST be `OidcJwt.VerificationResult.Valid.claims`; this object
 * reads nothing else and never throws.
 */
internal object KeycloakClaimsShape {
    const val MAX_CLAIMS = 40
    const val MAX_CLAIMS_SECTION_LENGTH = 2048
    private const val UNAVAILABLE = "idTokenShape=unavailable"
    private val CLAIM_NAME = Regex("[A-Za-z0-9_:./-]{1,64}")

    /** The claims that drive the display name, in this fixed order (deliberately not sorted). */
    val NAME_CLAIMS: List<String> = listOf("name", "given_name", "family_name", "preferred_username")

    /** Refusal reasons whose log line carries the shape: the ones a claim-name or claim-type problem can explain. */
    val LOGGED_REASONS: Set<RejectReason> =
        setOf(
            RejectReason.NAME_MISSING,
            RejectReason.EMAIL_NOT_VERIFIED,
            RejectReason.GROUP_MISSING,
            RejectReason.GROUP_WRONG_TYPE,
        )

    /** Never throws; on any internal failure returns a fixed text (no exception message). */
    fun describe(
        verifiedClaims: JWTClaimsSet,
        groupClaim: String?,
    ): String = runCatching { describe(claimMap = verifiedClaims.claims, groupClaim = groupClaim) }.getOrElse { UNAVAILABLE }

    /** Pure core, unit-testable with null values and arbitrary maps. */
    internal fun describe(
        claimMap: Map<String, Any?>,
        groupClaim: String?,
    ): String {
        val valid = claimMap.keys.filter { CLAIM_NAME.matches(it) }.sorted()
        val invalidCount = claimMap.size - valid.size
        val shown = ArrayList<String>()
        var sectionLength = 0
        var omitted = 0
        for (name in valid) {
            val entry = name + ":" + typeWord(claimMap[name])
            val extra = if (shown.isEmpty()) entry.length else entry.length + 2
            if (shown.size >= MAX_CLAIMS || sectionLength + extra > MAX_CLAIMS_SECTION_LENGTH) {
                omitted++
            } else {
                shown += entry
                sectionLength += extra
            }
        }
        val nameSection = NAME_CLAIMS.joinToString(", ") { it + ":" + nameState(present = claimMap.containsKey(it), value = claimMap[it]) }
        val groupState =
            when {
                groupClaim == null -> "not-configured"
                !claimMap.containsKey(groupClaim) -> "absent"
                else -> typeWord(claimMap[groupClaim])
            }
        return "idTokenClaims=[" + shown.joinToString(", ") + "] nameClaims=[" + nameSection + "] groupClaim=" + groupState +
            " invalidClaimNames=" + invalidCount + " omittedClaims=" + omitted
    }

    /** "string" | "boolean" | "number" (Number, Date) | "array" (List, Array) | "object" (Map) | "null" | "other" */
    internal fun typeWord(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> "string"
            is Boolean -> "boolean"
            is Number, is Date -> "number"
            is List<*>, is Array<*> -> "array"
            is Map<*, *> -> "object"
            else -> "other"
        }

    /** "absent" | "string-nonempty" | "string-empty" | "string-sanitizes-to-empty" | "wrong-type:<typeWord>" */
    internal fun nameState(
        present: Boolean,
        value: Any?,
    ): String =
        when {
            !present -> "absent"
            value is String && value.isEmpty() -> "string-empty"
            value is String && KeycloakProvisioningClaims.sanitizeName(value) == null -> "string-sanitizes-to-empty"
            value is String -> "string-nonempty"
            else -> "wrong-type:" + typeWord(value)
        }
}

package network.lapis.cloud.server.keycloak

import com.nimbusds.jwt.JWTClaimsSet
import network.lapis.cloud.server.rpc.MEMBER_DISPLAY_NAME_MAX_LENGTH

/**
 * Welle V1.9.73 -- the pure evaluation of the ID-token claims that drive just-in-time provisioning and the profile sync.
 *
 * **Source of the claims.** [JWTClaimsSet] instances handed to these functions MUST come from
 * `OidcJwt.VerificationResult.Valid` -- the ID token AFTER signature, issuer, audience, nonce and expiry were verified. The
 * UserInfo endpoint is never called and the access token is never decoded: a group or name claim from any other source
 * would be attacker-influenced. Nothing here reads a role: the role of a provisioned account is a literal constant in
 * [KeycloakMemberProvisioner], never derived from a claim.
 */
internal object KeycloakProvisioningClaims {
    enum class GroupCheck {
        /** The required group is present. */
        MATCH,

        /** The claim is absent. */
        MISSING,

        /** The claim exists but is neither a string nor an array (a number, an object, ...). */
        WRONG_TYPE,

        /** The claim is a string or array but does not contain the required group (also: empty). */
        NO_MATCH,
    }

    sealed interface NameResult {
        data class Ok(
            val displayName: String,
        ) : NameResult

        data object Missing : NameResult
    }

    /**
     * Looks for [requiredGroup] in the top-level claim [claimName], which Keycloak's group mapper emits as a single string
     * or as an array of strings. Only string elements count; any other element is ignored.
     */
    fun checkGroup(
        claims: JWTClaimsSet,
        claimName: String,
        requiredGroup: String,
    ): GroupCheck {
        val value = runCatching { claims.getClaim(claimName) }.getOrNull() ?: return GroupCheck.MISSING
        return when (value) {
            is String -> if (groupMatches(value = value, requiredGroup = requiredGroup)) GroupCheck.MATCH else GroupCheck.NO_MATCH
            is List<*> ->
                if (value.any {
                        it is String &&
                            groupMatches(
                                value = it,
                                requiredGroup = requiredGroup,
                            )
                    }
                ) {
                    GroupCheck.MATCH
                } else {
                    GroupCheck.NO_MATCH
                }
            else -> GroupCheck.WRONG_TYPE
        }
    }

    /**
     * Exact, case-sensitive comparison. With `g` = [requiredGroup] without a leading `/`: `g` and `/g` match; a nested path
     * such as `/parent/g` does NOT (a sub-group is not the group).
     */
    fun groupMatches(
        value: String,
        requiredGroup: String,
    ): Boolean {
        val bare = requiredGroup.removePrefix("/")
        if (bare.isEmpty()) return false
        return value == bare || value == "/$bare"
    }

    /**
     * Three stages, first one that produces something wins:
     * 1. `name`;
     * 2. `given_name` + `family_name`;
     * 3. `first_name` + `last_name` (the claim names of identity providers such as Wabeo).
     *
     * Within a stage the non-empty parts (after [sanitizeName]) are joined by one space -- one part alone is accepted. Stages
     * are never mixed (no `given_name` + `last_name`): that would combine names of different semantics. Only string claims
     * count; a claim of another type is treated as absent. `preferred_username` is deliberately never used.
     */
    fun displayName(claims: JWTClaimsSet): NameResult {
        val resolved =
            sanitizeName(stringClaim(claims = claims, name = "name"))
                ?: joinedParts(claims = claims, first = "given_name", second = "family_name")
                ?: joinedParts(claims = claims, first = "first_name", second = "last_name")
        return resolved?.let { NameResult.Ok(it) } ?: NameResult.Missing
    }

    private fun stringClaim(
        claims: JWTClaimsSet,
        name: String,
    ): String? = runCatching { claims.getClaim(name) }.getOrNull() as? String

    private fun joinedParts(
        claims: JWTClaimsSet,
        first: String,
        second: String,
    ): String? =
        sanitizeName(
            listOfNotNull(
                sanitizeName(stringClaim(claims = claims, name = first)),
                sanitizeName(stringClaim(claims = claims, name = second)),
            ).joinToString(" "),
        )

    /**
     * Whether the identity provider confirmed the name: the top-level claim [claimName] must be exactly `true`, an integer `1`
     * (Nimbus parses a JSON `1` as Long), the string `"1"` or the string `"true"`. No trimming, no case folding. Everything
     * else is "not confirmed": absent, null, 0, false, a floating-point `1.0`, `"True"`, `" 1"`, arrays, objects.
     */
    fun nameConfirmed(
        claims: JWTClaimsSet,
        claimName: String,
    ): Boolean =
        when (val value = runCatching { claims.getClaim(claimName) }.getOrNull()) {
            true -> true
            is Long -> value == 1L
            is Int -> value == 1
            is Short -> value == 1.toShort()
            is Byte -> value == 1.toByte()
            is String -> value == "1" || value == "true"
            else -> false
        }

    /**
     * Makes an IdP-supplied name safe to store and to put into a mail: whitespace-like controls (tab, CR, LF, NEL, U+2028,
     * U+2029) become a space, every other control (Cc) and format character (Cf, which includes the bidirectional overrides
     * U+202A..U+202E and U+2066..U+2069) is removed, whitespace is collapsed and trimmed, and the result is cut to
     * [MEMBER_DISPLAY_NAME_MAX_LENGTH] UTF-16 units without splitting a surrogate pair. Returns `null` when nothing is left.
     */
    fun sanitizeName(raw: String?): String? {
        if (raw == null) return null
        val cleaned = StringBuilder(raw.length)
        for (c in raw) {
            when {
                c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C' || c == '\u0085' || c == ' ' || c == ' ' ->
                    cleaned.append(' ')
                Character.getType(c) == Character.CONTROL.toInt() || Character.getType(c) == Character.FORMAT.toInt() -> Unit
                else -> cleaned.append(c)
            }
        }
        var result = cleaned.toString().replace(Regex("\\s+"), " ").trim()
        if (result.length > MEMBER_DISPLAY_NAME_MAX_LENGTH) {
            var end = MEMBER_DISPLAY_NAME_MAX_LENGTH
            if (Character.isHighSurrogate(result[end - 1]) && Character.isLowSurrogate(result[end])) end -= 1
            result = result.substring(0, end).trim()
        }
        return result.ifEmpty { null }
    }
}

package network.lapis.cloud.server.federation

import kotlinx.serialization.Serializable

/** `POST /federation/oidc/token` success response (RFC 6749 §5.1) -- the same shape both when THIS server is the Issuer responding to an RP, and when parsing a home server's own token-endpoint response (RP side). */
@Serializable
data class OidcTokenResponseDto(
    val access_token: String,
    val token_type: String = "Bearer",
    val expires_in: Long,
    val refresh_token: String? = null,
    val id_token: String,
    val scope: String,
)

/** `POST /federation/oidc/token` error response (RFC 6749 §5.2). */
@Serializable
data class OidcTokenErrorDto(
    val error: String,
    val error_description: String? = null,
)

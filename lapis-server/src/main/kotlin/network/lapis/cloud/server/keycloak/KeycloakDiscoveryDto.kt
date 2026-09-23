package network.lapis.cloud.server.keycloak

import kotlinx.serialization.Serializable

/**
 * `GET {issuerUrl}/.well-known/openid-configuration` response shape, Relying-Party side -- a
 * deliberately narrow subset of Keycloak's real discovery document (which has on the order of 60
 * fields). Only the fields sub-wave 1b's login/callback/logout flow actually needs are modelled;
 * everything else is dropped by `ignoreUnknownKeys = true` at the deserialization call site (see
 * `KeycloakOidcMetadata`). Modelled on `network.lapis.cloud.server.federation.OidcDiscoveryDto`,
 * which is this server's OWN (Issuer-side) discovery document -- this DTO is its Relying-Party
 * mirror image, for a REMOTE Keycloak's document.
 */
@Serializable
data class KeycloakDiscoveryDto(
    val issuer: String,
    val authorization_endpoint: String,
    val token_endpoint: String,
    val jwks_uri: String,
    val end_session_endpoint: String? = null,
)

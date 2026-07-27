package network.lapis.cloud.server.federation

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** RFC 7591 Dynamic Client Registration request body -- sent by this server (acting as RP) to a guest's claimed home server's `registration_endpoint`. */
@Serializable
data class OidcDynamicClientRegistrationRequest(
    val client_name: String,
    val redirect_uris: List<String>,
    val grant_types: List<String> = listOf("authorization_code", "refresh_token"),
    val response_types: List<String> = listOf("code"),
    val token_endpoint_auth_method: String = "client_secret_post",
    val backchannel_logout_uri: String? = null,
)

/** RFC 7591 Dynamic Client Registration response body -- as returned by either this server's own `/federation/oidc/register` (Issuer side) or a remote home server's `registration_endpoint` (RP side). */
@Serializable
data class OidcDynamicClientRegistrationResponse(
    val client_id: String,
    val client_secret: String,
    val client_id_issued_at: Long,
    val client_secret_expires_at: Long = 0,
    val redirect_uris: List<String> = emptyList(),
    val grant_types: List<String> = emptyList(),
    val response_types: List<String> = emptyList(),
    val token_endpoint_auth_method: String = "client_secret_post",
)

private val REGISTRAR_JSON = Json { ignoreUnknownKeys = true }

sealed interface OidcClientRegistrationOutcome {
    data class Success(
        val response: OidcDynamicClientRegistrationResponse,
    ) : OidcClientRegistrationOutcome

    data class Failure(
        val reason: String,
    ) : OidcClientRegistrationOutcome
}

/**
 * RP-side Dynamic Client Registration (RFC 7591) CLIENT logic -- this server registering ITSELF as
 * an OAuth client against a guest's claimed home server's `registration_endpoint`, the first time
 * it ever sees a guest claiming that home server. The Issuer-side counterpart (this server ACTING
 * AS the registration endpoint for other visited servers) is `POST /federation/oidc/register` in
 * `network.lapis.cloud.server.routes.OidcRoutes`, not this file.
 *
 * SSRF-guarded exactly like every other outbound federation fetch this wave adds -- reuses
 * [requireSafeFederationUrl]/[federationHttpClient]/[readCappedFederationBodyOrNull] verbatim, no
 * new SSRF-guard code (see `network.lapis.cloud.server.routes.OidcRoutes` KDoc "SSRF-defense reuse").
 */
object OidcClientRegistrar {
    /**
     * Registers this server (as OAuth client) against [registrationEndpoint]. [registrationEndpoint]
     * MUST already have passed [requireSafeFederationUrl] via the caller's own discovery-fetch step
     * -- this function re-guards it anyway (defense in depth, cheap) immediately before the POST.
     */
    suspend fun register(
        registrationEndpoint: String,
        clientName: String,
        redirectUri: String,
        backchannelLogoutUri: String,
    ): OidcClientRegistrationOutcome =
        runCatching {
            requireSafeFederationUrl(registrationEndpoint)
            federationHttpClient().use { client ->
                val response: HttpResponse =
                    client.post(registrationEndpoint) {
                        contentType(ContentType.Application.Json)
                        setBody(
                            REGISTRAR_JSON.encodeToString(
                                OidcDynamicClientRegistrationRequest.serializer(),
                                OidcDynamicClientRegistrationRequest(
                                    client_name = clientName,
                                    redirect_uris = listOf(redirectUri),
                                    backchannel_logout_uri = backchannelLogoutUri,
                                ),
                            ),
                        )
                    }
                if (!response.status.isSuccess()) {
                    return@use OidcClientRegistrationOutcome.Failure("Registration endpoint returned ${response.status}")
                }
                val bytes =
                    response.readCappedFederationBodyOrNull()
                        ?: return@use OidcClientRegistrationOutcome.Failure("Registration response too large")
                val parsed =
                    runCatching {
                        REGISTRAR_JSON.decodeFromString(
                            OidcDynamicClientRegistrationResponse.serializer(),
                            bytes.toString(Charsets.UTF_8),
                        )
                    }.getOrNull() ?: return@use OidcClientRegistrationOutcome.Failure("Malformed registration response")
                OidcClientRegistrationOutcome.Success(parsed)
            }
        }.getOrElse { OidcClientRegistrationOutcome.Failure(it.message ?: "Registration failed") }
}

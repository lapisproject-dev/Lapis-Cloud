package network.lapis.cloud.server.federation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * [OidcClientRegistrar] -- RP-side Dynamic Client Registration (RFC 7591) client logic. Reuses
 * [requireSafeFederationUrl] verbatim (see that function's own exhaustive SSRF test coverage in
 * [FederationHttpClientSsrfTest]) -- this file only pins that [OidcClientRegistrar.register] itself
 * fails closed (a typed [OidcClientRegistrationOutcome.Failure], never throws) for a
 * registration_endpoint the SSRF guard rejects, without attempting any network call.
 */
class OidcClientRegistrarTest :
    FunSpec({
        test("register() against a non-HTTPS registration_endpoint fails closed (Failure, never throws), no network attempted") {
            val outcome =
                OidcClientRegistrar.register(
                    registrationEndpoint = "http://insecure-registration.example/register",
                    clientName = "Lapis Cloud",
                    redirectUri = "https://us.example/federation/oidc/rp/callback",
                    backchannelLogoutUri = "https://us.example/federation/oidc/backchannel-logout",
                )
            outcome.shouldBeInstanceOf<OidcClientRegistrationOutcome.Failure>()
        }

        test("register() against a loopback-resolving registration_endpoint fails closed (Failure, never throws)") {
            val outcome =
                OidcClientRegistrar.register(
                    registrationEndpoint = "https://127.0.0.1/register",
                    clientName = "Lapis Cloud",
                    redirectUri = "https://us.example/federation/oidc/rp/callback",
                    backchannelLogoutUri = "https://us.example/federation/oidc/backchannel-logout",
                )
            outcome.shouldBeInstanceOf<OidcClientRegistrationOutcome.Failure>()
        }
    })

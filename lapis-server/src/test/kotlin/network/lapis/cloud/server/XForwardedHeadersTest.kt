package network.lapis.cloud.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

/**
 * Fix (2026-08-14): [network.lapis.cloud.server.module] installs [XForwardedHeaders] specifically
 * so `call.request.origin.remoteHost` reflects the real client IP behind a reverse proxy (Apache on
 * the VPS 4000 test deployment) instead of the proxy's own loopback address -- see that `install()`
 * call site's own KDoc for the full "why", the live-verified (tcpdump) proof that the proxy actually
 * sends `X-Forwarded-For`, AND the second live-verified finding that made `useLastProxy()` mandatory,
 * not optional: Apache's mod_proxy_http *appends* to an attacker-supplied `X-Forwarded-For` header
 * rather than replacing it, and Ktor's zero-config default (`useFirstProxy()`) would have taken that
 * attacker-supplied, spoofable FIRST value -- handing every IP-keyed rate limiter in this codebase a
 * trivial bypass instead of fixing it.
 *
 * These tests exercise the plugin mechanism directly, isolated from any specific route's business
 * logic (login/registration/federation-inbox rate limiting, which already has its own tests and was
 * never in question) -- a minimal `testApplication` with just the plugin and a throwaway echo route
 * is the most direct way to prove the mechanism itself, and does not require touching production
 * routing. Every `install(XForwardedHeaders)` call below intentionally mirrors the production
 * configuration (`useLastProxy()`), not the zero-config default -- testing the default here would
 * validate the wrong (vulnerable) configuration.
 */
class XForwardedHeadersTest :
    FunSpec({
        test("origin.remoteHost reflects X-Forwarded-For once XForwardedHeaders is installed") {
            testApplication {
                application {
                    install(XForwardedHeaders) { useLastProxy() }
                    routing {
                        get("/echo-remote-host") {
                            call.respondText(text = call.request.origin.remoteHost)
                        }
                    }
                }

                val response =
                    client.get("/echo-remote-host") {
                        header("X-Forwarded-For", "203.0.113.42")
                    }

                response.bodyAsText() shouldBe "203.0.113.42"
            }
        }

        test("origin.remoteHost falls back to the raw connection when no X-Forwarded-For header is present") {
            // Same-machine local dev / a direct (non-proxied) request must keep working exactly as
            // before -- the plugin must be a pure no-op absent the header, never a hard requirement.
            testApplication {
                application {
                    install(XForwardedHeaders) { useLastProxy() }
                    routing {
                        get("/echo-remote-host") {
                            call.respondText(text = call.request.origin.remoteHost)
                        }
                    }
                }

                val response = client.get("/echo-remote-host")

                // "localhost" is what Ktor's own in-process test engine reports as the virtual
                // client when no forwarding header is present -- a test-engine implementation
                // detail, but a stable one (empirically verified against the pinned Ktor version
                // this module actually resolves), and asserting the concrete value catches a
                // regression (e.g. an empty string, or the plugin crashing) more precisely than a
                // bare "is not blank" check would.
                response.bodyAsText() shouldBe "localhost"
            }
        }

        test(
            "SECURITY: an attacker-supplied X-Forwarded-For value is NOT trusted -- only the last (proxy-appended) entry is",
        ) {
            // Regression test for the exact vulnerability found during review of this fix (2026-08-14):
            // Apache's mod_proxy_http appends to, rather than replaces, an existing X-Forwarded-For
            // request header (verified live via tcpdump against the real VPS 4000 deployment -- a
            // curl with a self-supplied "X-Forwarded-For: 6.6.6.6-SPOOFED" arrived at the backend as
            // "X-Forwarded-For: 6.6.6.6-SPOOFED, <real client IP>"). Ktor's zero-config default
            // (useFirstProxy()) would take the FIRST value -- the attacker's own spoofed one -- which
            // would let any external client defeat every IP-keyed rate limiter in this codebase by
            // sending a fresh fake value on every request. useLastProxy() must take the LAST value
            // instead, simulating exactly that append pattern here.
            testApplication {
                application {
                    install(XForwardedHeaders) { useLastProxy() }
                    routing {
                        get("/echo-remote-host") {
                            call.respondText(text = call.request.origin.remoteHost)
                        }
                    }
                }

                val response =
                    client.get("/echo-remote-host") {
                        header("X-Forwarded-For", "6.6.6.6-SPOOFED-ATTACKER-VALUE, 91.10.157.41")
                    }

                response.bodyAsText() shouldBe "91.10.157.41"
            }
        }
    })

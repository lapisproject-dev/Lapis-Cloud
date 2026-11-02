package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.net.URI

class StrictSameOriginFormPostTest :
    FunSpec({
        val origin = URI("https://example.org")

        fun check(headers: Headers) = isStrictSameOriginFormPost(headers = headers, canonicalOrigin = origin)

        test("Origin matching the canonical origin passes (default port normalised)") {
            check(headersOf(HttpHeaders.Origin, "https://example.org")) shouldBe true
            check(headersOf(HttpHeaders.Origin, "https://example.org:443")) shouldBe true
        }

        test("Origin of another host, scheme or port, malformed, or the literal null is refused") {
            check(headersOf(HttpHeaders.Origin, "https://evil.example")) shouldBe false
            check(headersOf(HttpHeaders.Origin, "http://example.org")) shouldBe false
            check(headersOf(HttpHeaders.Origin, "https://example.org:8443")) shouldBe false
            check(headersOf(HttpHeaders.Origin, "ht!tp://%%")) shouldBe false
            check(headersOf(HttpHeaders.Origin, "null")) shouldBe false
        }

        test("without Origin the Referer decides; a foreign Referer is refused") {
            check(headersOf(HttpHeaders.Referrer, "https://example.org/veranstaltung/x")) shouldBe true
            check(headersOf(HttpHeaders.Referrer, "https://evil.example/veranstaltung/x")) shouldBe false
            check(headersOf(HttpHeaders.Referrer, "null")) shouldBe false
        }

        test("an Origin wins over a Referer (a matching Referer cannot rescue a foreign Origin)") {
            check(
                Headers.build {
                    append(HttpHeaders.Origin, "https://evil.example")
                    append(HttpHeaders.Referrer, "https://example.org/")
                },
            ) shouldBe false
        }

        test("neither header: refused (the lenient isSameOriginRequest would accept it)") {
            check(Headers.Empty) shouldBe false
            isSameOriginRequest(headers = Headers.Empty, canonicalOrigin = origin) shouldBe true
        }

        test("Sec-Fetch-Site, when present, must be same-origin") {
            fun with(site: String) =
                Headers.build {
                    append(HttpHeaders.Origin, "https://example.org")
                    append("Sec-Fetch-Site", site)
                }
            check(with("same-origin")) shouldBe true
            check(with("same-site")) shouldBe false
            check(with("cross-site")) shouldBe false
            check(with("none")) shouldBe false
        }

        test("no canonical origin configured: refused") {
            isStrictSameOriginFormPost(headers = headersOf(HttpHeaders.Origin, "https://example.org"), canonicalOrigin = null) shouldBe
                false
        }
    })

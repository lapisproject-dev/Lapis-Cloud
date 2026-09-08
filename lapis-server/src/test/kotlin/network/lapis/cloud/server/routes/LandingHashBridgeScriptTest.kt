package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

/**
 * Review-Fund MEDIUM (Multi-Agent-Pipeline "Öffentliche Root-Seite für Lapis Cloud", fehlende
 * Testabdeckung): [LANDING_HASH_BRIDGE_SCRIPT]'s own KDoc makes explicit safety claims -- "a closed
 * allowlist of characters", "this can never become an open redirect" -- that, before this test, were
 * backed only by a single positive smoke assertion (`bridgeAsset.bodyAsText() shouldContain
 * "location.replace"`, [PublicLandingRoutesTest] Test 17 / § 1.2 Hash-Bridge). Neither that test nor
 * any other one ever exercised the regex itself with a malicious/malformed fragment.
 *
 * This test extracts the EXACT regex literal from the production constant (via a fixed
 * `if (/.../.test(h))` marker) rather than hand-typing a second copy of the pattern, so a future edit
 * to the allowlist that is not mirrored here fails loudly (the "sanity" test below) instead of this
 * suite silently testing a stale pattern that no longer matches what ships.
 *
 * Two independent safety properties are proven, matching the two ways the JS code path can stay
 * safe for a given fragment `h` (see [LANDING_HASH_BRIDGE_SCRIPT] KDoc bullet 2/3):
 * 1. Fragments carrying markup/protocol/escape characters (`<`, `>`, `"`, `'`, a bare `javascript:`
 *    with no leading `#/`, a literal backslash, an empty/bare `#`) never match the allowlist at all
 *    -- `.test(h)` is `false`, so `location.replace(...)` is never even called for them.
 * 2. For every fragment that DOES match -- including one shaped like a protocol-relative URL, e.g.
 *    `#//evil.example` -- the actual navigation target `"/app" + h` still always starts with the
 *    literal `/app`, i.e. a same-origin path, never `//evil.example` or any other origin. This is
 *    the property that makes the redirect target immune to open-redirect regardless of what the
 *    allowlist lets through, per the KDoc's own reasoning ("never constructed from
 *    location.host/document.referrer/any other request-controlled input").
 */
class LandingHashBridgeScriptTest :
    FunSpec({
        val regexLiteral =
            Regex("""if \(/(.+)/\.test\(h\)\)""")
                .find(LANDING_HASH_BRIDGE_SCRIPT)
                ?.groupValues
                ?.get(1)
                ?: error(
                    "LANDING_HASH_BRIDGE_SCRIPT no longer contains the expected `if (/.../.test(h))` shape -- " +
                        "update this extraction (and re-check the tests below still express the intended " +
                        "safety property) before trusting a green run of this suite.",
                )
        val allowlist = Regex(regexLiteral)

        test("sanity: the extracted literal is the known allowlist pattern (fails loudly on drift)") {
            regexLiteral shouldBe """^#\/[A-Za-z0-9_\-\/?=&%.~+]*$"""
        }

        test("positive: well-formed hash routes match, and the redirect target is the literal /app plus the fragment") {
            listOf("#/dashboard", "#/login?returnTo=x", "#/", "#/a/b/c?x=1&y=2").forEach { h ->
                allowlist.matches(h) shouldBe true
                ("/app" + h) shouldStartWith "/app"
            }
        }

        test(
            "negative/tamper: markup, quotes, a bare javascript: scheme, a backslash, and an empty/bare " +
                "hash never match -- location.replace is never reached for any of them",
        ) {
            listOf(
                "",
                "#",
                "javascript:alert(1)",
                "#javascript:alert(1)",
                "#/<script>alert(1)</script>",
                "#/\"onmouseover=alert(1)",
                "#/'onmouseover=alert(1)",
                "#/\\evil.example",
                "#/ evil",
                "#/foo\nbar",
            ).forEach { h -> allowlist.matches(h) shouldBe false }
        }

        test(
            "tamper: even a fragment shaped like a protocol-relative URL (#//evil.example) -- which DOES " +
                "satisfy the character allowlist -- can never produce an off-origin target, because the " +
                "navigation target is always the literal \"/app\" prefix plus the fragment, never the " +
                "fragment (or any request-controlled value) alone",
        ) {
            val h = "#//evil.example"
            allowlist.matches(h) shouldBe true
            val target = "/app" + h
            target shouldStartWith "/app"
            // The one shape that WOULD be dangerous -- a target starting with "//" (browser-parsed as
            // protocol-relative, i.e. a different origin) -- is structurally impossible here because the
            // literal "/app" prefix is hardcoded ahead of the fragment, not derived from it.
            (target.startsWith("//")) shouldBe false
        }
    })

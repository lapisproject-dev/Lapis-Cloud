package network.lapis.cloud.server.clientversion

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * "Redact real infra topology from public repo" (rounds 1-5, `CHANGELOG.md` "[Unreleased]"): this repo is
 * public, so real deployment hostnames/IPs/provider names were replaced throughout `deploy/` and
 * `CHANGELOG.md` with the prose placeholders `PROD_HOST` / `ELB_HOST` / `STAGING_HOST` and the already-real
 * `${LAPIS_PUBLIC_IP}` env var (see the NOTE block near the top of `deploy/production/README.adoc` for the
 * full legend). Four of the first five redaction rounds this wave took each reintroduced exactly one leak of
 * the same class the round before it had just closed -- a heuristic pattern that keeps resurfacing in ONE
 * line at a time, exactly what a plain code review reading full files top-to-bottom is prone to miss and a
 * `grep`-shaped test is not.
 *
 * Round 4 introduced a leak *inside this very test file*: that version spelled the real IP/hostname/domain
 * out in plain text (in the KDoc and in the forbidden-pattern regexes), which defeated the whole point of
 * the redaction the moment the file was committed to the public repo. It was fixed by representing each
 * forbidden value only as a `(length, SHA-256 hex digest)` pair, matched by sliding a same-length window
 * across each line and comparing digests -- so the file no longer contained any real value in cleartext.
 *
 * Round 5 fixes what round 4's fix itself got wrong: all nine forbidden values had low entropy (4-20
 * characters of lowercase letters/digits/dots -- short hostnames, an IPv4 address, a provider name), so an
 * *unsalted* SHA-256 digest plus the exact cleartext length is not a one-way commitment for a value that
 * small -- it is a complete offline dictionary/brute-force oracle, publicly committed right next to the
 * files it was meant to protect. A production IPv4 (length 13) is brute-forceable over the full
 * `\d+\.\d+\.\d+\.\d+` search space in minutes on a single core (seconds with GPU tooling), a 4-character
 * hostname over `[a-z0-9-]^4` in under a second, and several of the `DEPLOY_ONLY_FORBIDDEN` values fell to a
 * small, plausible wordlist (provider name, org-domain-adjacent strings) instantly. Anyone who clones this
 * public repo could reconstruct every redacted value from this test alone, without needing git history --
 * the tripwire was undoing the exact redaction it exists to guard.
 *
 * Round 5's fix has two parts, matched to what actually needs hiding:
 *
 * 1. **The production IPv4 address is a *structural* pattern within `deploy/`, not a secret string** -- any
 *    literal IPv4 address appearing in `deploy/` (outside the tiny, exhaustively-enumerated allowlist of
 *    legitimate non-routable/documentation addresses this repo actually uses there: `127.0.0.1` loopback,
 *    `0.0.0.0` all-interfaces bind, `1.1.1.1` as a connectivity-check example target in prose) is a leak by
 *    definition -- the real deployment IP is supposed to be represented exclusively by the `${LAPIS_PUBLIC_IP}`
 *    env var, never a literal, anywhere in `deploy/`. [IPV4_PATTERN] catches *any* such address, which is
 *    strictly stronger than matching one specific known value and carries no brute-forceable secret at all:
 *    knowing the regex and the three allowed literals tells an attacker nothing about the real IP. This
 *    structural check is deliberately scoped to `deploy/` only -- `CHANGELOG.md` (see below) also contains
 *    version numbers (`V1.4.5.1`), German thousands-grouped amounts (`1.000.000.000,00`), and unrelated
 *    historical/example IPs (RFC 1918/link-local/CGNAT examples in security write-ups, a leftover pre-Docker
 *    PZB firewall rule for a different address entirely) that a blanket `\d+\.\d+\.\d+\.\d+` scan there would
 *    misfire on constantly; verified empirically against this file's current content.
 * 2. **The low-entropy human-chosen strings (internal short hostnames, the hosting/mail provider name) and
 *    the production IP's exact-match check *within* `CHANGELOG.md`** are genuinely secret and, in
 *    `CHANGELOG.md`'s case, cannot be told apart from the noise described above by any structural pattern --
 *    so none of them are committed to this file in any form, hashed or otherwise. Instead they are read at
 *    test runtime from the `LAPIS_REDACTION_TRIPWIRE_SECRETS` environment variable (see
 *    [readSecretForbiddenValues] below for the exact format), which is never checked into the repo.
 *
 *    IMPORTANT, corrected in round 6 (this part of round 4/5's own KDoc was wrong): as of today, `.env` is
 *    the only mechanism -- **`LAPIS_REDACTION_TRIPWIRE_SECRETS` is not wired into any CI job**.
 *    `.github/workflows/ci.yml` runs `./gradlew clean check` with no `env:` block and no
 *    `secrets.*` reference, so a GitHub Actions repository secret with this name would never reach the
 *    test process even if one existed -- and no such secret has been created (this repo's org is
 *    `lapisproject-dev`, not `kuml-dev`, which an earlier draft of this KDoc named by mistake; there is
 *    no `lapisproject-dev`-org secret of this name either). Concretely: the variable is unset on *every*
 *    run today -- local, fork/PR, and the org's own CI push/PR runs alike -- so this part of the test
 *    (and the whole "CHANGELOG.md contains no un-redacted real infra IP or internal hostname" test below)
 *    is currently a no-op everywhere; it only does real work when a human sets the env var by hand before
 *    running `./gradlew :lapis-server:test --tests '*InfraTopologyRedactionTripwireTest'` locally. Wiring it
 *    into CI (add an `env:` entry to the `check` step in `ci.yml` pulling from a real repository secret,
 *    then create that secret under `lapisproject-dev/Lapis-Cloud`) is tracked as follow-up work, not done
 *    yet. A skip here is therefore not evidence that CHANGELOG.md/`deploy/` are actually being checked for
 *    the secret values -- only the always-on [IPV4_PATTERN]-in-`deploy/` and org-domain-in-`deploy/` checks
 *    below run unconditionally in CI today and catch the highest-value structural leak without any secret
 *    configured.
 *
 * The org domain (`parteidervernunft.de`) is neither hashed nor secret-sourced: it already appears in
 * cleartext elsewhere in this very file, in [ALLOWED_DEPLOY_MENTIONS] (PdV is already publicly named as the
 * deployment's operator in `deploy/production/README.adoc`), so writing it into a pattern here adds no new
 * exposure. What the check still needs to catch is the domain showing up *outside* those specifically
 * accepted lines, which would indicate a still-live FQDN leak the redaction was supposed to remove. Checked
 * only within `deploy/`, same scoping reason as the IPv4 check above.
 *
 * Scope note: unlike `deploy/`, `CHANGELOG.md`'s **historical** entries (describing past waves, at the time
 * they actually shipped) were deliberately left out of this redaction's scope -- only the real IP and the
 * real internal hostname were required to be scrubbed from `CHANGELOG.md`; its many pre-redaction FQDN and
 * provider-name mentions are historical record, not live topology, and stay. This is a LEDGER test, not a
 * zero-tolerance gate for every pattern everywhere.
 */
private val SCAN_ROOT =
    File("..").let { if (File(it, "deploy").exists()) it else File(".") }

/** Any literal IPv4 address outside this allowlist is a topology leak -- see the class KDoc, part 1. */
private val IPV4_PATTERN = Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""")
private val ALLOWED_IPV4_LITERALS =
    setOf(
        "127.0.0.1", // loopback -- every port-forwarding/bind example in deploy/ uses this deliberately
        "0.0.0.0", // all-interfaces bind, and the log line format `Responding at http://0.0.0.0:8080`
        "1.1.1.1", // Cloudflare's public resolver, used only as a `docker run alpine ping` connectivity example
    )

/**
 * The org domain is public already (see class KDoc) -- matched as a plain literal, never hashed. A match
 * outside [ALLOWED_DEPLOY_MENTIONS] means the domain leaked into a *new* place the redaction didn't account
 * for (e.g. a still-live vhost/realm/log-filename), which is exactly what this ledger exists to catch.
 */
private const val ORG_DOMAIN = "parteidervernunft.de"

/**
 * Lines in `deploy/` accepted as-is by the "Redact real infra topology" changelog entry: the bare org
 * domain in `.env.example` comments (PdV is already named as the deployment's operator/auftraggeber in
 * `deploy/production/README.adoc`, so the domain alone adds no topology information beyond what the README
 * already states).
 */
private val ALLOWED_DEPLOY_MENTIONS =
    setOf(
        "# LAPIS_SMTP_FROM_ADDRESS=no_reply@parteidervernunft.de",
        "# LAPIS_SMTP_REPLY_TO=kontakt@parteidervernunft.de",
        "# LAPIS_BRAND_WEBSITE_URL=https://parteidervernunft.de",
        "# LAPIS_LEGAL_CONTACT_EMAIL=kontakt@parteidervernunft.de",
        "# LAPIS_LEGAL_DPO_CONTACT=Erika Muster, dsb@parteidervernunft.de",
        "# LAPIS_EMBED_ALLOWED_ORIGINS=https://parteidervernunft.de,https://www.parteidervernunft.de",
    )

/**
 * `LAPIS_REDACTION_TRIPWIRE_SECRETS` format: newline- or comma-separated entries of the shape
 * `SCOPE:value`, where `SCOPE` is `ALWAYS` (checked everywhere, including `CHANGELOG.md`'s historical
 * entries) or `DEPLOY` (checked only within `deploy/`, subject to [ALLOWED_DEPLOY_MENTIONS]). Values are
 * matched case-insensitively as plain substrings -- never hashed, never derivable from this file. Example
 * (illustrative values only, not the real ones): `ALWAYS:pdv-internal-host,DEPLOY:example-mail-provider`.
 * Unset -> this part of the test is skipped, not failed. As of round 6 this is unset EVERYWHERE, including
 * the org's own CI (`ci.yml` does not set it -- see the class KDoc part 2 for the full correction of an
 * earlier, wrong claim that CI provisions it); set it by hand for a local run to exercise this check.
 */
private const val SECRETS_ENV_VAR = "LAPIS_REDACTION_TRIPWIRE_SECRETS"

private data class SecretForbiddenValues(
    val always: List<String>,
    val deployOnly: List<String>,
)

private fun readSecretForbiddenValues(): SecretForbiddenValues? {
    val raw = System.getenv(SECRETS_ENV_VAR)?.takeIf { it.isNotBlank() } ?: return null
    val always = mutableListOf<String>()
    val deployOnly = mutableListOf<String>()
    raw
        .split(',', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .forEach { entry ->
            val (scope, value) =
                entry.split(":", limit = 2).takeIf { it.size == 2 }
                    ?: error("$SECRETS_ENV_VAR entry '$entry' is not in 'SCOPE:value' form")
            when (scope.uppercase()) {
                "ALWAYS" -> always += value
                "DEPLOY" -> deployOnly += value
                else -> error("$SECRETS_ENV_VAR entry '$entry' has unknown scope '$scope' (expected ALWAYS or DEPLOY)")
            }
        }
    return SecretForbiddenValues(always = always, deployOnly = deployOnly)
}

private fun ipv4Leaks(line: String): List<String> =
    IPV4_PATTERN
        .findAll(line)
        .map { it.value }
        .filterNot { it in ALLOWED_IPV4_LITERALS }
        .toList()

/** Structural checks (IPv4 literal + org domain) -- `deploy/`-only, see class KDoc for why. */
private fun structuralLeaks(
    file: File,
    allowedLines: Set<String>,
): List<String> =
    file
        .readLines()
        .filterNot { it.trim() in allowedLines }
        .flatMap { rawLine ->
            val line = rawLine.trim()
            val hits = mutableListOf<String>()
            hits += ipv4Leaks(line)
            if (line.lowercase().contains(ORG_DOMAIN)) hits += line
            hits
        }.distinct()

private fun secretLeaks(
    file: File,
    forbidden: List<String>,
    allowedLines: Set<String>,
): List<String> {
    if (forbidden.isEmpty()) return emptyList()
    return file
        .readLines()
        .filterNot { it.trim() in allowedLines }
        .map { it.trim() }
        .filter { line -> forbidden.any { line.contains(it, ignoreCase = true) } }
}

class InfraTopologyRedactionTripwireTest :
    FunSpec({
        val secrets = readSecretForbiddenValues()
        if (secrets == null) {
            println(
                "InfraTopologyRedactionTripwireTest: '$SECRETS_ENV_VAR' is not set -- skipping the " +
                    "exact-value secret checks (internal short hostname, provider name, and the " +
                    "CHANGELOG.md IP/hostname check). This variable is currently unset EVERYWHERE, " +
                    "including the org's own CI -- it is not wired into ci.yml yet, so this skip " +
                    "happens on every run today, not just local/fork/PR ones. Set it by hand to " +
                    "exercise this check locally. The always-on IPv4-literal and org-domain checks " +
                    "below still run.",
            )
        }

        test("deploy/ contains no un-redacted IPv4 literals, org-domain leaks, or (when configured) secret values") {
            val deployDir = File(SCAN_ROOT, "deploy")
            deployDir.exists() shouldBe true
            val scannedExtensions = setOf("yml", "yaml", "adoc", "sh", "template", "example", "conf")
            val scannedFiles =
                deployDir
                    .walkTopDown()
                    .filter { it.isFile && it.extension in scannedExtensions }
                    .toList()
            // Guards against a silent vacuous pass if SCAN_ROOT/deployDir resolution ever regresses
            // (wrong working directory, moved module, etc.) -- a scan of zero/few files would trivially
            // report zero leaks without actually having scanned anything.
            scannedFiles.size shouldBeGreaterThan 10
            val leaks =
                scannedFiles
                    .flatMap { file ->
                        val structural = structuralLeaks(file = file, allowedLines = ALLOWED_DEPLOY_MENTIONS)
                        val secret =
                            secrets?.let {
                                secretLeaks(file = file, forbidden = it.deployOnly, allowedLines = ALLOWED_DEPLOY_MENTIONS)
                            } ?: emptyList()
                        (structural + secret).distinct().map { "${file.path}: $it" }
                    }
            leaks shouldBe emptyList()
        }

        test("CHANGELOG.md contains no un-redacted real infra IP or internal hostname (when configured)") {
            // No structural (IPv4/domain) scan here -- see class KDoc part 1 for why CHANGELOG.md's version
            // numbers, formatted amounts, and unrelated historical IP examples make that unsafe. Only the
            // secret-sourced exact-value check (ALWAYS scope) applies, and it no-ops when unconfigured.
            val changelog = File(SCAN_ROOT, "CHANGELOG.md")
            changelog.exists() shouldBe true
            val secret = secrets?.let { secretLeaks(file = changelog, forbidden = it.always, allowedLines = emptySet()) } ?: emptyList()
            secret shouldBe emptyList()
        }
    })

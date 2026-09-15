package network.lapis.cloud.server.branding

import java.io.File

/**
 * V1.2.5 White-Label-Branding -- operator-supplied web-UI branding (page `<title>` + optional
 * navbar logo). Pure string validation ONLY, no I/O of any kind (same
 * "`./gradlew clean check` never needs a running SMTP relay" posture `SmtpConfig.load()`
 * establishes -- see that class' own KDoc) -- file existence/readability/size of [logoPath] is
 * checked separately, later, by [BrandingStartupCheck].
 *
 * **Never fail-fast, unlike [network.lapis.cloud.server.mail.SmtpConfig].** There is deliberately
 * NO "Incomplete" state and [load] never throws: broken cosmetic configuration (a title with a
 * stray control character, a logo path with the wrong extension) degrades to [DEFAULT_TITLE]/no
 * logo, it never refuses to start this server -- see [BrandingStartupCheck] KDoc "why branding is
 * never fail-fast" for the full argument against reusing SMTP's fail-fast posture here.
 *
 * **`LAPIS_BRAND_LOGO_PATH` is an absolute, deployment-local filesystem path, never a URL.** No
 * `LAPIS_BRAND_LOGO_URL` exists and none is planned -- an outbound fetch to an operator-supplied
 * URL at request time (or even once at startup) would reopen exactly the SSRF surface this
 * codebase's other `*Config` classes (`OracleSourceConfig`, `LetterxpressPostalMailProvider`) work
 * hard to avoid, for a purely cosmetic feature that does not need it. The operator places the file
 * on disk (bind-mounted into the container, see `deploy/production/docker-compose.yml`) and points
 * this variable at it.
 */
class BrandConfig private constructor(
    /** Getrimmter Titel, oder [DEFAULT_TITLE] falls `LAPIS_BRAND_TITLE` unset/leer/ungültig war. */
    val title: String,
    /**
     * Absoluter Dateipfad, oder `null` falls `LAPIS_BRAND_LOGO_PATH` unset/leer/ungültig war.
     * Existenz/Lesbarkeit/Dateigröße werden hier NICHT geprüft -- reine String-Validierung, siehe
     * Klassen-KDoc. Siehe [BrandingStartupCheck] für die tatsächliche Datei-Probe.
     */
    val logoPath: String?,
    /**
     * V1.4.18 -- Nutzer-Beschwerde 2026-09-15: die öffentliche Fußzeile nennt den Betreiber-Namen
     * (dieses [title]) nur als Text, ohne Link zur eigentlichen Betreiber-Webseite (PdV/ELB o.ä.).
     * Optional, `null` falls `LAPIS_BRAND_WEBSITE_URL` unset/leer/ungültig war -- die Fußzeile
     * rendert den Betreiber-Namen dann weiterhin als reinen Text (unverändertes Verhalten), statt
     * einen kaputten Link zu zeigen. Anders als [logoPath] eine ECHTE URL, kein Dateipfad -- aber
     * anders als eine hypothetische `LAPIS_BRAND_LOGO_URL` (siehe Klassen-KDoc, bewusst nie
     * eingeführt) löst dieser Wert NIEMALS einen serverseitigen Fetch aus, er landet nur als
     * `href`-Attribut in selbst erzeugtem HTML (kotlinx.html escaped das Attribut automatisch) --
     * das SSRF-Argument gegen eine Logo-URL trifft hier also nicht zu. Schema-Allowlist
     * (http/https) bleibt trotzdem Pflicht, sonst könnte ein `javascript:`-Schema beim Klick
     * beliebigen Code im Kontext dieser Seite ausführen.
     */
    val websiteUrl: String?,
    /**
     * Namen der `LAPIS_BRAND_*`-Variablen, deren Wert verworfen wurde (auf den jeweiligen Default
     * zurückgefallen) -- NUR für Startup-Logging bestimmt (siehe [BrandingStartupCheck]), niemals
     * ein Grund zu werfen. Leer, wenn beide Werte entweder unset oder gültig waren.
     */
    val invalid: List<String>,
) {
    companion object {
        const val ENV_TITLE = "LAPIS_BRAND_TITLE"
        const val ENV_LOGO_PATH = "LAPIS_BRAND_LOGO_PATH"
        const val ENV_WEBSITE_URL = "LAPIS_BRAND_WEBSITE_URL"
        const val DEFAULT_TITLE = "Lapis Cloud"
        private const val MAX_TITLE_LENGTH = 80
        private const val MAX_WEBSITE_URL_LENGTH = 200
        private val ALLOWED_LOGO_EXTENSIONS = setOf("svg", "png", "webp")
        private val ALLOWED_WEBSITE_URL_SCHEMES = setOf("http", "https")

        /**
         * Pure string validation ONLY -- no DNS, no socket, no file I/O of any kind. Never throws
         * -- an invalid value is recorded in [BrandConfig.invalid] and the corresponding field
         * falls back to its default, see class KDoc.
         */
        fun load(env: (String) -> String? = System::getenv): BrandConfig {
            val invalid = mutableListOf<String>()

            val rawTitle = env(ENV_TITLE)?.trim()?.takeUnless { it.isBlank() }
            val title =
                when {
                    rawTitle == null -> DEFAULT_TITLE
                    // Header-/log-injection guard, symmetric to SmtpConfig's \r/\n check for
                    // fromDisplayName -- generalized to ANY C0 control character (not just \r/\n),
                    // since this value can end up inside an HTML <title> AND a JSON payload (see
                    // BrandingHtml.inject), two different injection surfaces at once.
                    rawTitle.any { it.code < 0x20 } -> {
                        invalid += ENV_TITLE
                        DEFAULT_TITLE
                    }
                    rawTitle.length > MAX_TITLE_LENGTH -> {
                        invalid += ENV_TITLE
                        DEFAULT_TITLE
                    }
                    else -> rawTitle
                }

            val rawLogoPath = env(ENV_LOGO_PATH)?.trim()?.takeUnless { it.isBlank() }
            val logoPath =
                when {
                    rawLogoPath == null -> null
                    !isValidLogoPath(rawLogoPath) -> {
                        invalid += ENV_LOGO_PATH
                        null
                    }
                    else -> rawLogoPath
                }

            val rawWebsiteUrl = env(ENV_WEBSITE_URL)?.trim()?.takeUnless { it.isBlank() }
            val websiteUrl =
                when {
                    rawWebsiteUrl == null -> null
                    !isValidWebsiteUrl(rawWebsiteUrl) -> {
                        invalid += ENV_WEBSITE_URL
                        null
                    }
                    else -> rawWebsiteUrl
                }

            return BrandConfig(title = title, logoPath = logoPath, websiteUrl = websiteUrl, invalid = invalid)
        }

        /**
         * Must be an absolute path (never resolved relative to some ambiguous working directory),
         * contain no embedded NUL byte, and carry one of [ALLOWED_LOGO_EXTENSIONS] (checked
         * case-insensitively -- an operator typing `.SVG` is far more likely than a deliberate,
         * meaningfully different extension). [LAPIS_BRAND_LOGO_PATH] is an operator-only
         * environment variable, never user/request input -- this check is defense-in-depth, not a
         * defense against an untrusted caller (see class KDoc).
         */
        private fun isValidLogoPath(path: String): Boolean {
            if (path.contains('\u0000')) return false
            if (!File(path).isAbsolute) return false
            val extension = path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            return extension in ALLOWED_LOGO_EXTENSIONS
        }

        /**
         * `http(s)://` scheme allowlist only -- no reachability check, no DNS resolution, this
         * value is never fetched (see [websiteUrl] KDoc). Control-character guard mirrors [title]'s
         * own (this value can end up in a startup-log WARN line if rejected, same log-injection
         * surface).
         */
        private fun isValidWebsiteUrl(url: String): Boolean {
            if (url.any { it.code < 0x20 }) return false
            if (url.length > MAX_WEBSITE_URL_LENGTH) return false
            val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
            return scheme in ALLOWED_WEBSITE_URL_SCHEMES
        }
    }
}

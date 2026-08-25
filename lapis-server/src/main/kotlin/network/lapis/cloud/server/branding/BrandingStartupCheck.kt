package network.lapis.cloud.server.branding

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger {}

/** Result of probing [ResolvedBranding.logoPath] on disk -- see [BrandingStartupCheck.resolve]. */
data class LogoProbe(
    val readable: Boolean,
    val isRegularFile: Boolean,
    val sizeBytes: Long,
)

/**
 * The branding this process will actually serve, AFTER [BrandingStartupCheck.resolve] has probed
 * [BrandConfig.logoPath] on disk. [logoPath] is non-null if and only if [logoAvailable] is `true`
 * -- callers never need to re-check availability before using the path.
 */
data class ResolvedBranding(
    val title: String,
    val logoAvailable: Boolean,
    val logoPath: String?,
)

/**
 * V1.2.5 White-Label-Branding -- startup-time resolution of [BrandConfig] into [ResolvedBranding],
 * mirroring `network.lapis.cloud.server.mail.SmtpStartupCheck`'s "log inventory, warn on a
 * problem" shape, except for the one deliberate difference this class' KDoc below explains:
 * [resolve] NEVER throws.
 *
 * **Why branding is never fail-fast, unlike SMTP.** `SmtpStartupCheck` fail-fasts on
 * `SmtpConfigState.Incomplete` because a broken password-reset/verification mailer is a genuine
 * functional (and security-sensitive) failure -- a member simply cannot reset a forgotten password.
 * Branding is purely cosmetic: a malformed `LAPIS_BRAND_TITLE` or an unreadable
 * `LAPIS_BRAND_LOGO_PATH` degrades to the "Lapis Cloud" default title and no logo -- the
 * application is 100% functional either way, so refusing to start over it would trade a real
 * outage for a cosmetic default. [BrandConfig.load] already never throws; this class' own probe
 * of the logo file on disk (the one piece of I/O branding legitimately needs) follows the same
 * posture -- any failure here (missing file, not a regular file, too large, not readable) is
 * logged at WARN and degrades [ResolvedBranding.logoAvailable] to `false`, never an exception.
 */
object BrandingStartupCheck {
    /** 512 KiB -- generous for a wordmark/logo SVG or PNG, small enough that a misconfigured multi-megabyte file cannot bloat every "/" response. */
    private const val MAX_LOGO_BYTES = 512 * 1024L

    /**
     * Logs [BrandConfig.invalid] (if any) at WARN, probes [BrandConfig.logoPath] on disk via
     * [probe] (real file I/O -- injectable for tests, defaults to [probeFile]), and returns the
     * [ResolvedBranding] this process will actually serve. Never throws -- see class KDoc.
     */
    fun resolve(
        config: BrandConfig,
        probe: (String) -> LogoProbe? = ::probeFile,
    ): ResolvedBranding {
        if (config.invalid.isNotEmpty()) {
            // Names only, never the rejected raw value -- same "never log the actual value"
            // discipline `SmtpStartupCheck`'s own fail-fast message follows, doubly warranted here
            // since a rejected LAPIS_BRAND_TITLE could itself carry a log-injection payload (see
            // BrandConfig's own control-character guard, which is exactly why it WAS rejected).
            logger.warn {
                "Branding-Konfiguration teilweise ungültig, Default greift für: " +
                    "${config.invalid.joinToString(", ")} (siehe BrandConfig KDoc)."
            }
        }

        val logoPath = config.logoPath
        if (logoPath == null) {
            logger.info { "Branding aktiv: title='${config.title}', kein Logo konfiguriert (LAPIS_BRAND_LOGO_PATH unset)." }
            return ResolvedBranding(title = config.title, logoAvailable = false, logoPath = null)
        }

        val logoProbe = probe(logoPath)
        val available = logoProbe != null && logoProbe.readable && logoProbe.isRegularFile && logoProbe.sizeBytes <= MAX_LOGO_BYTES
        if (!available) {
            logger.warn {
                "LAPIS_BRAND_LOGO_PATH ('$logoPath') ist gesetzt, aber die Datei ist nicht nutzbar " +
                    "(fehlt, kein regulärer Pfad, nicht lesbar, oder größer als $MAX_LOGO_BYTES Bytes) " +
                    "-- Logo bleibt deaktiviert, der Server startet trotzdem (siehe Klassen-KDoc)."
            }
        } else {
            logger.info { "Branding aktiv: title='${config.title}', logo='$logoPath'." }
        }
        return ResolvedBranding(title = config.title, logoAvailable = available, logoPath = if (available) logoPath else null)
    }

    private fun probeFile(path: String): LogoProbe? {
        val file = File(path)
        if (!file.exists()) return null
        return LogoProbe(readable = file.canRead(), isRegularFile = file.isFile, sizeBytes = file.length())
    }
}

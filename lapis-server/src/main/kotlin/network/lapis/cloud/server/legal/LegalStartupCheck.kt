package network.lapis.cloud.server.legal

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * V1.4.7 "Rechtstexte" -- startup-time logging companion to [LegalConfig], mirroring
 * `network.lapis.cloud.server.branding.BrandingStartupCheck`'s "log inventory, warn on a problem,
 * never throw" shape.
 *
 * **Never logs a value, only variable NAMES** -- a rejected value can be exactly why it was
 * rejected (a header-/log-injection payload, see [LegalConfig]'s own control-character guard),
 * same discipline `BrandingStartupCheck`'s own warning follows.
 */
object LegalStartupCheck {
    fun check(config: LegalConfig) {
        if (config.invalid.isNotEmpty()) {
            logger.warn {
                "Rechtsangaben teilweise ungültig, Wert verworfen für: " +
                    "${config.invalid.joinToString(", ")} (siehe LegalConfig KDoc)."
            }
        }
        if (config.missingMandatory.isNotEmpty()) {
            logger.warn {
                "Impressum/Datenschutz unvollständig -- folgende Pflichtangaben fehlen: " +
                    "${config.missingMandatory.joinToString(", ")}. /impressum und /datenschutz zeigen an " +
                    "deren Stelle einen Betreiber-Hinweis, statt ein leeres, aber gültig aussehendes " +
                    "Impressum. Der Server startet trotzdem."
            }
        }
        if (config.isComplete) {
            logger.info { "Rechtsangaben vollständig (LAPIS_LEGAL_*)." }
        }
    }
}

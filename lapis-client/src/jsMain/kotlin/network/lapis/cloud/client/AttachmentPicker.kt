package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.AttachmentRules

/*
 * V1.9.233: the parts of the attachment pickers that mailing messages (V1.9.232, a table) and direct messages (a compact list) share --
 * the pre-check and the German refusal texts per server code. Extracted from MailingAttachments.kt; the wording (and so the message ids
 * of the catalogs) is unchanged.
 */

/** The two looks of the picker: the mailing section's table, or the direct-message composer's compact list. */
internal enum class AttachmentPickerVariant { TABLE, COMPACT }

/**
 * Comfort pre-check of a chosen file (the server decides for real): `null` when it looks acceptable, otherwise a fixed German text.
 * Order: extension whitelist, 10 MB per file, total, count, duplicate name (case-insensitive). [existing] are the (name, size) of the
 * files that are already attached PLUS those of the same selection that were uploaded before this one.
 */
internal fun attachmentPrecheck(
    name: String,
    sizeBytes: Long,
    existing: List<Pair<String, Long>>,
): String? {
    val shown = sanitizeUntrustedI18nText(name)
    if (AttachmentRules.extensionOf(name) !in AttachmentRules.ALLOWED) {
        return gettext(
            "„%1“ hat ein nicht unterstütztes Format. Erlaubt sind PDF, Bilder (PNG, JPEG, GIF, WebP), Word, Excel, PowerPoint, " +
                "OpenDocument, TXT und CSV.",
            shown,
        )
    }
    if (sizeBytes > AttachmentRules.MAX_FILE_BYTES) {
        return gettext("„%1“ ist zu groß (höchstens 10 MB pro Datei).", shown)
    }
    if (existing.sumOf { it.second } + sizeBytes > AttachmentRules.MAX_TOTAL_BYTES) {
        return gettext("Mit „%1“ wären die Anhänge zusammen größer als 20 MB.", shown)
    }
    if (existing.size >= AttachmentRules.MAX_FILES) {
        return gettext("Höchstens %1 Anhänge pro Nachricht.", AttachmentRules.MAX_FILES)
    }
    val key = name.lowercase()
    if (existing.any { it.first.lowercase() == key }) {
        return gettext("Ein Anhang mit dem Namen „%1“ ist schon vorhanden.", shown)
    }
    return null
}

/** Fixed German text for a refusal CODE of the server -- the server's body text is never shown. */
internal fun attachmentRefusalText(
    code: String,
    fileName: String,
): String {
    val name = sanitizeUntrustedI18nText(fileName)
    return when (code) {
        "FILE_TOO_LARGE" -> gettext("„%1“ ist zu groß (höchstens 10 MB pro Datei).", name)
        "TOTAL_TOO_LARGE" -> gettext("Mit „%1“ wären die Anhänge zusammen größer als 20 MB.", name)
        "TOO_MANY" -> gettext("Höchstens %1 Anhänge pro Nachricht.", AttachmentRules.MAX_FILES)
        "TYPE_NOT_ALLOWED" ->
            gettext(
                "„%1“ hat ein nicht unterstütztes Format. Erlaubt sind PDF, Bilder (PNG, JPEG, GIF, WebP), Word, Excel, PowerPoint, " +
                    "OpenDocument, TXT und CSV.",
                name,
            )
        "CONTENT_MISMATCH" -> gettext("„%1“ passt nicht zu ihrer Dateiendung oder ist beschädigt oder verschlüsselt.", name)
        "MACRO_OR_EMBEDDED" -> gettext("„%1“ enthält Makros oder eingebettete Objekte und wird nicht angenommen.", name)
        "DUPLICATE_NAME" -> gettext("Ein Anhang mit dem Namen „%1“ ist schon vorhanden.", name)
        "INVALID_NAME" -> gettext("„%1“ hat keinen gültigen Dateinamen.", name)
        "NOT_DRAFT" -> gettext("Die Nachricht ist kein Entwurf mehr. Anhänge können nicht mehr geändert werden.")
        "RATE_LIMITED" -> gettext("Zu viele Anfragen – bitte kurz warten und erneut versuchen.")
        "FORBIDDEN", "INVALID_ORIGIN" -> gettext("Dafür fehlt Ihnen die Berechtigung.")
        "RECIPIENT_UNAVAILABLE" -> gettext("Diesem Mitglied können Sie keine Dateien schicken.")
        "PENDING_LIMIT" -> gettext("Sie haben zu viele noch nicht gesendete Dateien. Bitte senden oder entfernen Sie zuerst welche.")
        else -> gettext("Hochladen fehlgeschlagen.")
    }
}

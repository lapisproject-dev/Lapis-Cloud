package network.lapis.cloud.server.translation

import network.lapis.cloud.server.events.EventText
import java.security.MessageDigest

/**
 * The translatable text of an event and the SHA-256 identity of each field. Hash and prompt share the same normalized text. A null or empty
 * field is not part of the map (it is never translated and has no hash).
 */
internal object EventTranslationSource {
    fun fields(
        title: String,
        summary: String?,
        description: String,
        coverImageAlt: String?,
    ): Map<TranslatableField, String> {
        val result = LinkedHashMap<TranslatableField, String>()
        EventText.normalizeSingleLine(title).takeIf { it.isNotEmpty() }?.let { result[TranslatableField.TITLE] = it }
        summary?.let(EventText::normalizeSingleLine)?.takeIf { it.isNotEmpty() }?.let { result[TranslatableField.SUMMARY] = it }
        EventText.normalizeMultiline(description).takeIf { it.isNotEmpty() }?.let { result[TranslatableField.DESCRIPTION] = it }
        coverImageAlt
            ?.let(
                EventText::normalizeSingleLine,
            )?.takeIf { it.isNotEmpty() }
            ?.let { result[TranslatableField.COVER_IMAGE_ALT] = it }
        return result
    }

    /** `sha256Hex(fieldName + NUL + text)`, UTF-8, a NEW [MessageDigest] per call (not thread-safe). */
    fun hashOf(
        field: TranslatableField,
        normalizedText: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((field.name + "\u0000" + normalizedText).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hashes(fields: Map<TranslatableField, String>): Map<TranslatableField, String> =
        fields.mapValues { (field, text) -> hashOf(field = field, normalizedText = text) }

    fun hashes(
        title: String,
        summary: String?,
        description: String,
        coverImageAlt: String?,
    ): Map<TranslatableField, String> =
        hashes(fields(title = title, summary = summary, description = description, coverImageAlt = coverImageAlt))
}

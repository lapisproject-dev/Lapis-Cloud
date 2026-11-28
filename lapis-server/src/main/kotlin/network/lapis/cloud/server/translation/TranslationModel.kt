package network.lapis.cloud.server.translation

import kotlin.uuid.Uuid

/**
 * The kinds of public content that can be translated just in time. Literals are only ever appended (the value is persisted as text and guarded
 * by a database CHECK that a later wave extends with a new migration). **No `ARTICLE`**: articles are deliberately out of scope
 * (`TranslationStructureTest` pins that).
 */
internal enum class TranslatableContentType { EVENT, }

internal enum class TranslatableField { TITLE, SUMMARY, DESCRIPTION, COVER_IMAGE_ALT }

internal enum class TranslationState {
    TRANSLATED,
    SAME_LANGUAGE,
    DISABLED,
    NO_CONSENT,
    NOT_PUBLIC,
    NOT_FOUND,
    PENDING,
}

/**
 * The answer of one translation request. [fields] is always complete for the fields the content has: the translation OR the original, never a
 * mixture (all-or-nothing per content and language pair).
 */
internal data class TranslationOutcome(
    val deliveredLang: String,
    val sourceLang: String,
    val requestedLang: String,
    val state: TranslationState,
    val fields: Map<TranslatableField, String>,
) {
    val translated: Boolean get() = state == TranslationState.TRANSLATED
    val machineTranslated: Boolean get() = translated
    val translationPending: Boolean get() = state == TranslationState.PENDING
}

internal data class ContentTranslationRow(
    val contentType: TranslatableContentType,
    val contentId: Uuid,
    val field: TranslatableField,
    val sourceSha256: String,
    val sourceLang: String,
    val targetLang: String,
    val translatedText: String,
    val modelProfile: String,
)

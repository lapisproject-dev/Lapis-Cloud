package network.lapis.cloud.server.agreementtexts

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.shared.domain.AgreementTextFormat
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.rpc.ServiceBusyException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

// Privacy rule of this file: the logs name the kind and the sequence number only -- never the text, a hash or a label.
private val logger = KotlinLogging.logger {}

/** The text a registrant is shown right now. [placeholder] is `true` while no version of the kind was published. */
internal data class CurrentAgreementText(
    val versionLabel: String,
    val body: String,
    val sha256: String,
    val placeholder: Boolean,
    /** V1.9.196 -- how [body] is displayed. The built-in placeholders are always [AgreementTextFormat.PLAIN]. The format is not part of [sha256]. */
    val format: AgreementTextFormat = AgreementTextFormat.PLAIN,
)

/**
 * V1.9.134 -- which text is the live one: the published version with the highest `sequence_no`, else the built-in placeholder. NEVER the
 * draft. A database version is re-hashed on every read; a stored hash that no longer matches means the proof would be wrong, so the call
 * fails with [ServiceBusyException] and no registration is accepted.
 */
internal object AgreementTextResolver {
    fun current(
        kind: AgreementTextKind,
        profile: EncounterProfile = EncounterProfile.CHURCH_SERVICE,
    ): CurrentAgreementText =
        transaction {
            val row = AgreementTextStore.currentVersion(kind)
            if (row == null) {
                val builtin = BuiltinAgreementTexts.of(kind = kind, profile = profile)
                CurrentAgreementText(versionLabel = builtin.versionLabel, body = builtin.body, sha256 = builtin.sha256, placeholder = true)
            } else {
                val label = row.versionLabel
                val body = row.body
                val stored = row.sha256
                if (!AgreementTextHash.matches(
                        expectedSha256 = AgreementTextHash.of(versionLabel = label, body = body),
                        providedSha256 = stored,
                    )
                ) {
                    logger.warn { "agreement integrity mismatch (kind=${kind.name}, sequenceNo=${row.sequenceNo})" }
                    throw ServiceBusyException()
                }
                CurrentAgreementText(versionLabel = label, body = body, sha256 = stored, placeholder = false, format = row.format)
            }
        }

    /** `true` iff [version] equals the current label exactly and [sha256] equals the current hash (constant time). */
    fun matchesCurrent(
        kind: AgreementTextKind,
        version: String,
        sha256: String,
        profile: EncounterProfile = EncounterProfile.CHURCH_SERVICE,
    ): Boolean {
        val current = current(kind = kind, profile = profile)
        if (version != current.versionLabel) return false
        return AgreementTextHash.matches(expectedSha256 = current.sha256, providedSha256 = sha256)
    }
}

package network.lapis.cloud.server.agreementtexts

import network.lapis.cloud.server.encounter.EncounterAssemblyConsentDisclaimer
import network.lapis.cloud.server.encounter.EncounterAssemblyRecordingConsentDisclaimer
import network.lapis.cloud.server.encounter.EncounterConsentDisclaimer
import network.lapis.cloud.server.encounter.EncounterRecordingConsentDisclaimer
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.EncounterConsentLayout
import network.lapis.cloud.shared.domain.EncounterProfile

/**
 * V1.9.148 -- the label namespace of the encounter entry notices. `encounter_consent_acknowledgment` has the primary key
 * `(member_id, consent_version)` and NO column for the kind, so a label must identify one text across BOTH ENC kinds and every label that
 * ever went into the table from code. The suffix `+rec` belongs to the system (the label regex forbids `+`).
 */
@Suppress("DEPRECATION")
internal object EncounterConsentLabels {
    /** Every label code ever wrote (the four earlier objects, the two new placeholders), each also with the system suffixes ("+rec", "+map", "+rec+map"). */
    val reserved: Set<String> by lazy {
        val base =
            listOf(
                EncounterConsentDisclaimer.VERSION,
                EncounterAssemblyConsentDisclaimer.VERSION,
                EncounterRecordingConsentDisclaimer.VERSION,
                EncounterAssemblyRecordingConsentDisclaimer.VERSION,
                EncounterChurchConsentDefault.VERSION,
                EncounterAssemblyConsentDefault.VERSION,
            )
        base.flatMap { withSystemSuffixes(it) }.toSet()
    }

    /** [label] plus every variant the code can append the system suffixes to: "", "+rec", "+map", "+rec+map" (V1.9.166). */
    fun withSystemSuffixes(label: String): List<String> =
        listOf(
            label,
            label + EncounterConsentLayout.RECORDING_SUFFIX,
            label + EncounterConsentLayout.MAP_SUFFIX,
            label + EncounterConsentLayout.RECORDING_SUFFIX + EncounterConsentLayout.MAP_SUFFIX,
        )

    fun isReserved(label: String): Boolean = label in reserved

    fun kindOf(profile: EncounterProfile): AgreementTextKind =
        when (profile) {
            EncounterProfile.CHURCH_SERVICE -> AgreementTextKind.ENC_CHURCH
            EncounterProfile.ASSEMBLY -> AgreementTextKind.ENC_ASSEM
        }

    /** The other ENC kind. */
    fun siblingOf(kind: AgreementTextKind): AgreementTextKind =
        when (kind) {
            AgreementTextKind.ENC_CHURCH -> AgreementTextKind.ENC_ASSEM
            AgreementTextKind.ENC_ASSEM -> AgreementTextKind.ENC_CHURCH
            else -> throw IllegalArgumentException("not an encounter consent kind")
        }

    /** Every label (with and without the system suffixes) a published or built-in version of [kind] can produce. */
    fun linesOf(kind: AgreementTextKind): Set<String> {
        val labels =
            buildSet {
                add(BuiltinAgreementTexts.of(kind = kind).versionLabel)
                AgreementTextStore.listVersions(kind).forEach { add(it.versionLabel) }
                when (kind) {
                    AgreementTextKind.ENC_CHURCH -> {
                        add(EncounterConsentDisclaimer.VERSION)
                        add(EncounterRecordingConsentDisclaimer.VERSION)
                    }
                    AgreementTextKind.ENC_ASSEM -> {
                        add(EncounterAssemblyConsentDisclaimer.VERSION)
                        add(EncounterAssemblyRecordingConsentDisclaimer.VERSION)
                    }
                    else -> Unit
                }
            }
        return labels.flatMap { withSystemSuffixes(it) }.toSet()
    }
}

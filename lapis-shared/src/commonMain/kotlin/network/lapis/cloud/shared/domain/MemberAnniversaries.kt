package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
enum class AnniversaryEntryKind { BIRTHDAY, MEMBERSHIP_ANNIVERSARY }

/**
 * Eine Zeile der Geburtstags-/Jubiläums-Übersicht (Welle V1.4.4.2). [memberStatus] ist bewusst Teil
 * des DTOs -- **die Beschriftung im Client folgt IMMER [memberStatus], nie dem bloßen Feldnamen**:
 * ein [AnniversaryEntryKind.MEMBERSHIP_ANNIVERSARY]-Eintrag mit `memberStatus == DONOR` heisst
 * "N Jahre Förderer", NIEMALS "N Jahre Mitgliedschaft" -- ein Förderer war nie Vollmitglied, und ein
 * Brief mit dieser falschen Behauptung wäre eine vom System erfundene Aussage über den Rechtsstatus
 * einer Person (Tesler-Fund im Design-Review). Kein Geburtsjahr im DTO -- [years] ist bereits die
 * abgeleitete Zahl (erreichtes Alter bzw. Zugehörigkeitsjahre); das exakte Geburtsdatum bleibt im
 * Mitgliedersatz, wo es hingehört (Ive/Atkinson: Zurückhaltung, keine Anonymisierungs-Behauptung).
 */
@Serializable
data class AnniversaryEntryDto(
    val kind: AnniversaryEntryKind,
    val memberId: String,
    val memberDisplayName: String,
    val memberStatus: MemberStatus,
    /** Nächstes tatsächliches Vorkommen, immer >= dem Abfragetag. */
    val occursOn: LocalDate,
    /** Ursprüngliches Datum (Geburtsdatum bzw. joinedAt) -- bleibt sichtbar, auch am 29.02. */
    val originalDate: LocalDate,
    val shiftedFromLeapDay: Boolean,
    /** BIRTHDAY: erreichtes Alter. MEMBERSHIP_ANNIVERSARY: Zugehörigkeitsjahre. */
    val years: Int,
    val emphasis: AnniversaryEmphasis,
)

/**
 * Welle V1.4.4.2 "Geburtstage & Jubiläen" -- BOARD/ADMIN-Übersicht über [entries] innerhalb
 * [from]..[through] (inklusive beider Enden, [from] ist immer der Abfragetag). Bewusst KEIN `total`
 * -- Vorbild [MemberFinancialHistoryDto] "vier getrennte Größen"; hier ist die Begründung analog:
 * [entries] ist bereits die einzige benötigte Zahl (`entries.size`), eine weitere Summe wäre
 * redundant Zustand.
 *
 * [membersWithoutDateOfBirth] ist die Norman-Abdeckungszeile: `dateOfBirth` ist ein NULLABLE
 * Transparenzregister-Feld (siehe `MemberTable` KDoc), keine für jedes Mitglied gepflegte
 * Stammdatenpflicht -- eine Liste, die diese Lücke verschweigt, würde "keine Geburtstage" mit
 * "kein Geburtsdatum hinterlegt" verwechseln lassen. [eligibleMemberCount] ist die Grundgesamtheit
 * (`MemberStatusSets.ANNIVERSARY_ELIGIBLE`, `anonymizedAt IS NULL`), gegen die
 * [membersWithoutDateOfBirth] die Coverage-Aussage einordnet ("36 von 285").
 */
@Serializable
data class MemberAnniversaryOverviewDto(
    val windowDays: Int,
    val from: LocalDate,
    val through: LocalDate,
    val entries: List<AnniversaryEntryDto>,
    val eligibleMemberCount: Int,
    val membersWithoutDateOfBirth: Int,
)

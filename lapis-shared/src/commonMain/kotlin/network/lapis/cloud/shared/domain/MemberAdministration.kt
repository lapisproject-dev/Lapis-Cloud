package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Welle V1.2.12 "Mitgliederverwaltung: vollständige Bearbeitung + privilegiertes Roster" --
 * PRIVILEGIERTE Projektion einer `member`-Zeile, erreichbar ausschließlich über
 * [network.lapis.cloud.shared.rpc.IMemberService.listMembersForAdministration] (erzwingt
 * `isPrivileged`). Dieselbe DSGVO-Sorgfalt wie die bewusst schlanke [MemberSummaryDto] (siehe
 * dessen KDoc): [MemberSummaryDto] ist die Picker-Projektion (id+displayName, von elf
 * Client-Screens genutzt), DIESES DTO ist die Verwaltungs-Projektion und darf NIE aus einem
 * Picker-Pfad heraus geliefert werden.
 *
 * [role] ist NULLABLE, nicht aus Bequemlichkeit: die 407 per `MemberCsvImport` (V1.2.11)
 * importierten Mitglieder haben bewusst KEINE `account`-Zeile (siehe `MemberCsvImport` KDoc
 * "Known gaps"). `null` heißt exakt "kein Login-Konto", nicht "Rolle unbekannt" -- eine
 * Rollenänderung ist für eine solche Zeile strukturell unmöglich und wird serverseitig mit
 * [network.lapis.cloud.shared.rpc.MemberHasNoAccountException] abgelehnt, siehe
 * [network.lapis.cloud.shared.rpc.IMemberService.updateMemberRole] KDoc.
 */
@Serializable
data class MemberAdminRowDto(
    val id: String,
    val displayName: String,
    val email: String,
    val status: MemberStatus,
    val role: AccountRole?,
    val joinedAt: LocalDate,
    val externalReference: String? = null,
    val anonymized: Boolean = false,
)

/** Sortierschlüssel für [network.lapis.cloud.shared.rpc.IMemberService.listMembersForAdministration] -- niemals ein roher Client-Spaltenname (keine SQL-Injection-Fläche über die Sortierung). */
@Serializable
enum class MemberAdminSort { NAME_ASC, NAME_DESC, JOINED_DESC, JOINED_ASC }

/**
 * Bündelt [network.lapis.cloud.shared.rpc.IMemberService.listMembersForAdministration]s Filter/
 * Paginierung in ein Objekt -- selbes Muster wie [AuditLogListQuery]. [limit]/[offset]/[search]
 * werden serverseitig NOCHMALS begrenzt/getrimmt (siehe [DEFAULT_LIMIT]/[MAX_LIMIT]/
 * [MAX_SEARCH_LENGTH]) -- diese Konstanten sind eine Client-Bequemlichkeit, kein Vertrauensanker.
 */
@Serializable
data class MemberAdminQuery(
    val search: String? = null,
    val statuses: Set<MemberStatus> = emptySet(),
    val sort: MemberAdminSort = MemberAdminSort.NAME_ASC,
    val limit: Int = DEFAULT_LIMIT,
    val offset: Int = 0,
) {
    companion object {
        const val DEFAULT_LIMIT = 25
        const val MAX_LIMIT = 100
        const val MAX_SEARCH_LENGTH = 200
    }
}

/**
 * [statusCounts] trägt die Zahlen der Filter-Chips. Bewusst SERVERSEITIG mitgeliefert statt vom
 * Client durch fünf Einzelabfragen (eine je Chip) erzeugt -- das wären fünf Roundtrips für eine
 * rein dekorative Zahl. Gezählt wird über die durch [MemberAdminQuery.search] gefilterte, aber
 * NICHT durch [MemberAdminQuery.statuses] gefilterte Menge -- sonst zeigte jeder Chip beim
 * Umschalten seine eigene Auswahl als Gesamtzahl. Enthält nur Status mit Treffer > 0.
 */
@Serializable
data class MemberAdminPageDto(
    val rows: List<MemberAdminRowDto>,
    val totalCount: Int,
    val statusCounts: Map<MemberStatus, Int>,
    val limit: Int,
    val offset: Int,
)

/**
 * Der administrativ verwaltbare Statusquadrant (Welle V1.2.12) -- exakt die vier Status, die im
 * PdV-CSV-Import (V1.2.11) tatsächlich vorkommen. [MemberStatus.APPLICATION]/[MemberStatus
 * .REJECTED] gehören dem Aufnahme-Workflow ([network.lapis.cloud.shared.rpc.IRegistrationService],
 * mit seinen reviewedBy/reviewedAt-Metadaten), [MemberStatus.FRIEND] entsteht ausschließlich durch
 * Selbstregistrierung, [MemberStatus.GUEST] ist eine föderierte OIDC-Identität. Keiner der vier
 * gehört dieser generischen Bearbeitung -- das System bietet gar nicht erst an, was es nicht
 * erlaubt.
 *
 * BEWUSST EIGENSTÄNDIG, kein Ableitungsergebnis aus [MemberStatusSets]: eine spätere Erweiterung
 * von [MemberStatusSets.LOGIN_BLOCKED]/[MemberStatusSets.MEMBERSHIP_ENDED] darf die Admin-UI nicht
 * stillschweigend mit erweitern. [MemberStatusTransitionsTest] pinnt das Verhältnis der drei
 * Mengen zueinander.
 */
object MemberStatusTransitions {
    val ADMINISTRATIVELY_MANAGED: Set<MemberStatus> =
        setOf(MemberStatus.ACTIVE, MemberStatus.WITHDRAWN, MemberStatus.DONOR, MemberStatus.DECEASED)

    /** Leere Menge für jeden Status außerhalb [ADMINISTRATIVELY_MANAGED] -- kein Übergang wird angeboten, was das System nicht erlaubt. */
    fun allowedTargets(from: MemberStatus): Set<MemberStatus> =
        if (from in ADMINISTRATIVELY_MANAGED) ADMINISTRATIVELY_MANAGED - from else emptySet()

    /** Weg von DECEASED ist eine Datenkorrektur, kein Lebenszyklus-Ereignis -> ADMIN-exklusiv. */
    fun requiresAdmin(from: MemberStatus): Boolean = from == MemberStatus.DECEASED
}

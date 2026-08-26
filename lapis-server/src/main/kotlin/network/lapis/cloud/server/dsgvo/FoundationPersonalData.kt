package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Owns the Foundation-stub tables (see [MemberTable] KDoc): [MemberTable] itself — the
 * PK-only "root" record, not reachable via the generic FK-to-`member` walk that drives
 * `PersonalDataCoverageTest` and therefore requiring its own explicit coverage — and
 * [AccountTable] (login credentials).
 *
 * Erasure: [MemberTable] is anonymized, never hard-deleted, because retentionspflichtige
 * Datensaetze (e.g. [network.lapis.cloud.server.db.generated.ContributionTable], see
 * [ContributionPersonalData]) keep a foreign key to it — the row must survive as an FK anchor.
 * [AccountTable] has no retention duty of its own and is always hard-deleted regardless of
 * [ErasureMode]: that is the step that actually severs the member's ability to log in.
 *
 * **V0.7.2**: `reviewedBy`/`reviewedAt`/`rejectionReason` (the board's own admission-decision
 * metadata) are exported/nulled-out alongside the other V0.4.1/V0.5.2 PII fields when THIS member
 * is erased -- same treatment as street/postalCode/dateOfBirth. Deliberately NOT touched here when
 * this member appears as `reviewedBy` on a DIFFERENT member's row (i.e. erasing board member A
 * does not scrub A's id off every application A ever decided) -- same "don't scrub a third
 * party's accountability trail out of an unrelated erasure" reasoning
 * [AuditLogPersonalData] already establishes for its own domain.
 */
object FoundationPersonalData : PersonalDataContributor {
    override val sectionKey = "foundation"
    override val displayName = "Stammdaten"
    override val coveredTables = setOf(MemberTable, AccountTable)

    override fun export(memberId: Uuid) =
        buildJsonObject {
            val memberRow = MemberTable.selectAll().where { MemberTable.id eq memberId }.singleOrNull() ?: return@buildJsonObject
            put("displayName", memberRow[MemberTable.displayName])
            put("email", memberRow[MemberTable.email])
            put("status", memberRow[MemberTable.status].name)
            put("joinedAt", memberRow[MemberTable.joinedAt].toString())
            put("anonymizedAt", memberRow[MemberTable.anonymizedAt]?.toString())
            // V0.4.1 postal address -- PII, exported alongside displayName/email.
            put("street", memberRow[MemberTable.street])
            put("postalCode", memberRow[MemberTable.postalCode])
            put("city", memberRow[MemberTable.city])
            put("country", memberRow[MemberTable.country])
            // V0.5.2 Transparenzregister beneficial-owner fields -- PII, exported alongside the
            // V0.4.1 postal address.
            put("dateOfBirth", memberRow[MemberTable.dateOfBirth]?.toString())
            put("nationality", memberRow[MemberTable.nationality])
            // V0.7.2 Beitritts-Workflow board-decision metadata -- PII (who decided, when, why),
            // exported alongside the other member fields above.
            put("reviewedBy", memberRow[MemberTable.reviewedBy]?.toString())
            put("reviewedAt", memberRow[MemberTable.reviewedAt]?.toString())
            put("rejectionReason", memberRow[MemberTable.rejectionReason])
            // V0.11.0 FRIEND self-registration -- PII (origin/verification metadata), exported
            // alongside the other member fields above.
            put("friendSince", memberRow[MemberTable.friendSince]?.toString())
            put("emailVerifiedAt", memberRow[MemberTable.emailVerifiedAt]?.toString())
            // V1.2.11 PdV-CSV-Import: the source-CRM person number is a personal reference and
            // belongs in the Art. 15 DSGVO Auskunft alongside the other member fields above.
            put("externalReference", memberRow[MemberTable.externalReference])

            val accountRow = AccountTable.selectAll().where { AccountTable.memberId eq memberId }.singleOrNull()
            if (accountRow != null) {
                put("role", accountRow[AccountTable.role].name)
                // Never export the credential values themselves — only whether one is set.
                put("hasPasswordLogin", accountRow[AccountTable.passwordHash] != null)
                put("hasOidcLogin", accountRow[AccountTable.oidcSubject] != null)
                // V0.8.2 OIDC-Gastzugang-Federation: exported alongside hasOidcLogin above.
                put("oidcHomeServerIssuer", accountRow[AccountTable.oidcIssuer])
            }
        }

    override fun erase(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val placeholderEmail = "anonymized-$memberId@deleted.invalid"
        val anonymized =
            MemberTable.update({ MemberTable.id eq memberId }) {
                it[displayName] = "Geloeschtes Mitglied"
                it[email] = placeholderEmail
                it[anonymizedAt] = nowUtc()
                // V0.4.1 postal address -- PII, nulled out alongside displayName/email on anonymization.
                it[street] = null
                it[postalCode] = null
                it[city] = null
                it[country] = null
                // V0.5.2 Transparenzregister beneficial-owner fields -- PII, nulled out the same way.
                it[dateOfBirth] = null
                it[nationality] = null
                // V0.7.2 Beitritts-Workflow board-decision metadata -- PII, nulled out the same
                // way. Only THIS member's own reviewedBy/reviewedAt/rejectionReason -- see class
                // KDoc for why a different member's reviewedBy pointing AT this member is
                // deliberately left untouched.
                it[reviewedBy] = null
                it[reviewedAt] = null
                it[rejectionReason] = null
                // V0.11.0 FRIEND self-registration -- PII, nulled out the same way.
                it[friendSince] = null
                it[emailVerifiedAt] = null
                // V1.2.11 PdV-CSV-Import: the source-CRM person number is a direct re-identification
                // key back into the source CRM -- nulled out on erasure like every other PII field
                // above, so an erased member cannot be re-linked to the CRM export after the fact.
                it[externalReference] = null
            }
        val accountsDeleted = AccountTable.deleteWhere { AccountTable.memberId eq memberId }
        return listOf(
            TableErasureOutcome(
                table = "member",
                rowsAnonymized = anonymized,
                retentionReason = "FK-Anker fuer retentionspflichtige Datensaetze (z.B. Beitraege, GoBD/HGB/AO)",
            ),
            TableErasureOutcome(table = "account", rowsDeleted = accountsDeleted),
        )
    }
}

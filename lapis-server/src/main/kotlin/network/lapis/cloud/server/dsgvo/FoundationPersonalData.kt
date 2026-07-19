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

            val accountRow = AccountTable.selectAll().where { AccountTable.memberId eq memberId }.singleOrNull()
            if (accountRow != null) {
                put("role", accountRow[AccountTable.role].name)
                // Never export the credential values themselves — only whether one is set.
                put("hasPasswordLogin", accountRow[AccountTable.passwordHash] != null)
                put("hasOidcLogin", accountRow[AccountTable.oidcSubject] != null)
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

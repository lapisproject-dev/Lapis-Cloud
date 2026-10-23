package network.lapis.cloud.server.agreementtexts

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AgreementTextDraftTable
import network.lapis.cloud.server.db.generated.AgreementTextVersionTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.shared.domain.AgreementTextFormat
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AuditEntityType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** DevSeedData's ADMIN -- the author of every version a test publishes directly. */
internal val AGREEMENT_TEST_ADMIN_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000001")

/**
 * The H2 database is shared by every spec of one test JVM. A spec that publishes a version changes what EVERY registration test sees as the
 * current text, so it MUST call [reset] in `afterEach`/`finally` (test-side SQL: the immutability of a published version is a rule of the
 * application code, which `AgreementTextStoreTripwireTest` guards).
 */
internal object AgreementTextTestState {
    fun reset() {
        transaction {
            AgreementTextVersionTable.deleteAll()
            AgreementTextDraftTable.deleteAll()
            AuditLogEntryTable.deleteWhere { AuditLogEntryTable.entityType eq AuditEntityType.AGREEMENT_TEXT }
        }
    }

    /** Appends a version straight through the store (no service, no audit). Returns its hash. */
    fun publishDirect(
        kind: AgreementTextKind,
        label: String,
        body: String,
        validFrom: LocalDate = LocalDate(2026, 1, 1),
        format: AgreementTextFormat = AgreementTextFormat.PLAIN,
        authorMemberId: Uuid = AGREEMENT_TEST_ADMIN_ID,
    ): String {
        val sha = AgreementTextHash.of(versionLabel = label, body = body)
        transaction {
            AgreementTextStore.insertVersion(
                id = Uuid.random(),
                kind = kind,
                versionLabel = label,
                validFrom = validFrom,
                body = body,
                sha256 = sha,
                authorMemberId = authorMemberId,
                now = DbClock.nowLocalDateTime(),
                format = format,
            )
        }
        return sha
    }

    fun versionRows() = transaction { AgreementTextVersionTable.selectAll().toList() }

    fun draftRows() = transaction { AgreementTextDraftTable.selectAll().toList() }

    fun auditRows() =
        transaction {
            AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityType eq AuditEntityType.AGREEMENT_TEXT }.toList()
        }
}

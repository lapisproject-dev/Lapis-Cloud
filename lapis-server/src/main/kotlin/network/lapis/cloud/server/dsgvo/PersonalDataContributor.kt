package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.JsonElement
import network.lapis.cloud.shared.domain.DsgvoSubjectKind
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.Table
import kotlin.uuid.Uuid

/**
 * Per-table erasure outcome, reported by [PersonalDataContributor.erase] and aggregated into
 * `ErasureRequestTable.outcomeSummary` / `DsgvoAuditLogTable.outcomeSummary` (both in
 * `network.lapis.cloud.server.db.generated.DsgvoTables`) by
 * `network.lapis.cloud.server.rpc.DsgvoService`. Counts only — never payload, see
 * `docs/architecture/dsgvo.adoc` "Audit-Log-Datenschutz".
 */
data class TableErasureOutcome(
    val table: String,
    val rowsAnonymized: Int = 0,
    val rowsDeleted: Int = 0,
    val rowsRetained: Int = 0,
    val retentionReason: String? = null,
)

/**
 * Extension point every domain area implements once, next to the tables it owns, for the tables
 * that carry data about a DSGVO [DataSubject]. See [PersonalDataRegistry] KDoc for how contributors
 * are wired up — and, more importantly, for why registering one here alone does **not** prevent a
 * future table from going uncovered; `PersonalDataCoverageTest`'s `information_schema` walk (now
 * over every [PersonalDataRegistry.subjectRootTables], not just `member`) is the actual enforcement
 * mechanism.
 *
 * [export] and [erase] both run inside the caller's `transaction {}` (see
 * `network.lapis.cloud.server.rpc.DsgvoService` / `network.lapis.cloud.server.routes.DsgvoRoutes` /
 * `network.lapis.cloud.server.rpc.CrmService` / `network.lapis.cloud.server.routes.CrmRoutes`) —
 * implementations must not open their own `transaction {}`. Both use typed Exposed query builders
 * exclusively, never dynamic SQL string-building over table names (SQL-injection hygiene, house
 * rule).
 *
 * **Welle V1.4.2 "Interessenten-/Sympathisanten-CRM"**: this interface is now subject-agnostic
 * ([DataSubject], not a bare member `Uuid`) so a `crm_contact` can be a DSGVO subject too. Every
 * one of the 29 contributors that existed before this wave only ever handles
 * [network.lapis.cloud.shared.domain.DsgvoSubjectKind.MEMBER] subjects and does not need a
 * `when (subject)` of its own — see [MemberPersonalDataContributor] for the thin adapter that keeps
 * their 29 call sites untouched in shape. A caller (`DsgvoService`/`DsgvoRoutes`/`CrmService`/
 * `CrmRoutes`) MUST filter [PersonalDataRegistry.contributors] by [handledSubjects] before calling
 * [export]/[erase] — a contributor that does not handle the given subject's kind is simply skipped,
 * never called with a subject it cannot service.
 */
interface PersonalDataContributor {
    /** Stable machine key — becomes the export JSON section key and the audit-trail label. */
    val sectionKey: String
    val displayName: String

    /**
     * The Exposed [Table] objects this contributor owns and that carry data about a subject.
     * Checked for double-registration by [PersonalDataRegistry]'s init block, and cross-checked
     * against `information_schema` by `PersonalDataCoverageTest`.
     */
    val coveredTables: Set<Table>

    /** Which [DsgvoSubjectKind]s this contributor can be called with — see class KDoc "filter before dispatch". */
    val handledSubjects: Set<DsgvoSubjectKind>

    /** Reads all of [subject]'s data this contributor owns. Runs inside the caller's transaction. */
    fun export(subject: DataSubject): JsonElement

    /**
     * De-identifies/deletes this contributor's data for [subject] per [mode]; returns one
     * [TableErasureOutcome] per table in [coveredTables] the contributor actually touched.
     */
    fun erase(
        subject: DataSubject,
        mode: ErasureMode,
    ): List<TableErasureOutcome>
}

/**
 * Adapter every pre-V1.4.2 [PersonalDataContributor] implements instead of the raw interface —
 * keeps all 29 existing implementations' shape essentially unchanged (`exportMember`/`eraseMember`
 * replace `export`/`erase`, both still keyed on a plain member [Uuid]) while the compiler still
 * forces every one of them to be touched (they are `abstract`, not defaulted) so this migration
 * cannot silently skip a contributor. The only unsafe cast in this whole framework lives in
 * [asMember], called from here and nowhere else in a hot path — see that function's own KDoc for
 * why reaching its `error` branch is a caller bug (a broken [handledSubjects] filter), never a data
 * condition a legitimate call can trigger.
 */
interface MemberPersonalDataContributor : PersonalDataContributor {
    /** Reads all of [memberId]'s data this contributor owns. Runs inside the caller's transaction. */
    fun exportMember(memberId: Uuid): JsonElement

    /**
     * De-identifies/deletes this contributor's data for [memberId] per [mode]; returns one
     * [TableErasureOutcome] per table in [coveredTables] the contributor actually touched.
     */
    fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome>

    override val handledSubjects: Set<DsgvoSubjectKind> get() = setOf(DsgvoSubjectKind.MEMBER)

    override fun export(subject: DataSubject): JsonElement = exportMember(memberId = subject.asMember())

    override fun erase(
        subject: DataSubject,
        mode: ErasureMode,
    ): List<TableErasureOutcome> = eraseMember(memberId = subject.asMember(), mode = mode)
}

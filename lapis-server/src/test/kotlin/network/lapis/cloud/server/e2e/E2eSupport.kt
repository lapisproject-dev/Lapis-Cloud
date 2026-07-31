package network.lapis.cloud.server.e2e

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipAgreementAcknowledgmentTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.db.generated.VoteBallotTable
import network.lapis.cloud.server.db.generated.VoteOptionTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.0 "Pilot-Produktivbetrieb" end-to-end integration test wave -- shared infrastructure for the
 * scenarios under this package. Every scenario mounts the REAL, fully-wired
 * [network.lapis.cloud.server.module] (every route, every one of `initRpc`'s `registerService`
 * calls) in a single `testApplication` -- not the hand-picked-subset-of-routes idiom every existing
 * `*ServiceTest`/`ServiceIntegrationTest` file uses -- and drives it through the real HTTP surface:
 * real `/api/auth/login` + session cookies for anything a scenario logs in as, real plain-HTTP
 * routes (mailmerge PDFs, backup export/restore) via `client.get/post`, and small per-scenario
 * throwaway routes that construct an RPC service class directly (`GovernanceService(call)`,
 * `AccountingService(call)`, ...) -- the same construction `initRpc`'s own factories use --
 * layered onto the SAME `module()`-wired application, so the surrounding middleware (StatusPages,
 * session-cookie resolution, CallLogging) is 100% real; only the literal Kilua JSON-RPC envelope is
 * elided. See the V1.0 wave's CHANGELOG entry for why a genuinely wire-level RPC call is not
 * achievable from a JVM test in this codebase at all (Kilua RPC's JVM client stub is a no-op) --
 * this is the same, already-house-endorsed definition of "real RPC call" `ServiceIntegrationTest`
 * and every `*ServiceTest` file already uses, just layered onto a fully (not partially) wired
 * application and combined with real login/session flows where a scenario's story calls for it.
 *
 * `X-Member-Id` is used ONLY where a scenario needs a pre-existing seeded demo member (ADMIN_ID/
 * BOARD_ID/TREASURER_ID/MEMBER_ID) as a fixture *scene partner* performing a privileged action --
 * never for the specific member identity whose cross-domain journey the scenario is proving.
 */
const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/** Same regex idiom `AuthRoutesTest`/`OidcRoutesTest` already established, centralized here. */
fun rawTokenFromSetCookie(setCookieHeader: String): String {
    val match = Regex("lapis_session=([^;]+)").find(setCookieHeader)
    return requireNotNull(match) { "no lapis_session cookie in: $setCookieHeader" }.groupValues[1]
}

/**
 * Logs in via the REAL `/api/auth/login` route (mounted by [network.lapis.cloud.server.module])
 * and returns the raw session token extracted from the `Set-Cookie` response header -- the same
 * two-step idiom `AuthRoutesTest` hand-rolls per-test, centralized here so every E2E scenario
 * shares one implementation.
 */
suspend fun HttpClient.realLogin(
    email: String,
    password: String,
): String {
    val response =
        post("/api/auth/login") {
            setBody("""{"email":"$email","password":"$password"}""")
        }
    val setCookie =
        requireNotNull(response.headers[HttpHeaders.SetCookie]) {
            "login for $email did not set a session cookie (status ${response.status})"
        }
    return rawTokenFromSetCookie(setCookie)
}

/** Attaches a previously-obtained raw session token as a real `Cookie` header -- the production transport, not `X-Member-Id`. */
fun HttpRequestBuilder.withSession(rawToken: String) {
    header(HttpHeaders.Cookie, "lapis_session=$rawToken")
}

/**
 * Direct-DB `Member` + `Account` insert for scenario setup that must start from a state
 * self-registration/OIDC cannot itself produce (e.g. a pre-existing BOARD-role voter needed purely
 * as a scene partner) -- mirrors the identical idiom every existing `*ServiceTest` file already
 * hand-rolls per-file (`createTestMember`), centralized here as the one genuinely new,
 * from-scratch piece of shared E2E infrastructure. When [password] is non-null, a real
 * [PasswordHasher] hash is stored so the member can also [realLogin] if a scenario needs that.
 */
fun createRealMember(
    displayName: String,
    email: String,
    status: MemberStatus = MemberStatus.AKTIV,
    role: AccountRole = AccountRole.MEMBER,
    password: String? = null,
): Uuid {
    val id = Uuid.random()
    transaction {
        MemberTable.insert {
            it[MemberTable.id] = id
            it[MemberTable.displayName] = displayName
            it[MemberTable.email] = email
            it[MemberTable.status] = status
            it[joinedAt] = LocalDate(2026, 1, 1)
            it[membershipTierId] = null
        }
        AccountTable.insert {
            it[AccountTable.id] = Uuid.random()
            it[memberId] = id
            it[AccountTable.role] = role
            it[passwordHash] = password?.let { pw -> PasswordHasher.hash(pw) }
        }
    }
    return id
}

/** A password every E2E scenario's freshly-registered/created members can share -- satisfies `PasswordPolicy`, same shape `RegistrationServiceTest.STRONG_PASSWORD` uses. */
const val E2E_STRONG_PASSWORD = "a-genuinely-strong-e2e-password-1"

/**
 * Tears down every Governance/Membership/Accounting fixture row an E2E scenario created, in
 * FK-safe order. Must be called from inside an already-open `transaction {}`.
 *
 * **Why the member rows are RETIRED (status -> [MemberStatus.AUSGETRETEN]) rather than deleted.**
 * `audit_log_entry.actor_member_id` has a real FK to `member.id`, AND it is one of the fields
 * [network.lapis.cloud.server.audit.AuditHashChain.canonicalPayload] folds into every row's
 * `entry_hash`. That leaves exactly three options for a member who has produced audit entries, and
 * two of them silently corrupt state that OTHER Specs in this same JVM (all ~1100 tests share one
 * H2 in-memory database, see [network.lapis.cloud.server.db.DatabaseConfig]) still depend on:
 *
 * 1. `UPDATE audit_log_entry SET actor_member_id = NULL` -- changes a hashed field, so every
 *    touched row's stored `entry_hash` no longer matches a recomputation. Any later
 *    `verifyChainIntegrity` covering those sequence numbers reports the chain as BROKEN. This is
 *    silent, permanent corruption of a global, append-only structure, and is why this helper does
 *    NOT do it.
 * 2. `DELETE FROM audit_log_entry` -- leaves a `sequence_number` gap. `verifyRows` reports both
 *    the gap itself and, for a *windowed* verification whose immediate predecessor row was the
 *    deleted one, a missing-anchor break. Also order-dependent corruption of a shared structure.
 * 3. Leave the audit rows (and therefore the member row they reference) alone, and instead move
 *    the member out of every "live member" set. That is what this does: `AUSGETRETEN` removes the
 *    member from [network.lapis.cloud.server.rpc.eligibleMemberIds]' General-Assembly set (which
 *    is literally "all members with status AKTIV"), from headcount quorum, and from every
 *    `requireActiveMembership` gate -- i.e. it has the same isolating effect deletion was after,
 *    without touching the audit chain at all.
 *
 * The `membership_tier_id` is cleared at the same time so a retired fixture member can never be
 * picked up by a later Spec's `generateContributionsForPeriod` run.
 */
fun hardDeleteGovernanceAndMembershipFixtures(
    committeeIds: List<Uuid>,
    memberIds: List<Uuid>,
) {
    if (memberIds.isNotEmpty()) {
        // Contributions/Accounting first -- Posting -> JournalEntry, both reference the member.
        val journalEntryIds =
            JournalEntryTable
                .selectAll()
                .where { JournalEntryTable.donorMemberId inList memberIds }
                .map { it[JournalEntryTable.id] }
        if (journalEntryIds.isNotEmpty()) {
            PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
            JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
        }
        ContributionTable.deleteWhere { ContributionTable.memberId inList memberIds }
        LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList memberIds }
        MembershipAgreementAcknowledgmentTable.deleteWhere {
            MembershipAgreementAcknowledgmentTable.memberId inList memberIds
        }
        SessionTable.deleteWhere { SessionTable.memberId inList memberIds }
    }

    if (committeeIds.isNotEmpty()) {
        val meetingIds =
            MeetingTable
                .selectAll()
                .where { MeetingTable.committeeId inList committeeIds }
                .map { it[MeetingTable.id] }
        val motionIds =
            MotionTable
                .selectAll()
                .where { MotionTable.targetCommitteeId inList committeeIds }
                .map { it[MotionTable.id] }
        val voteIds =
            if (motionIds.isEmpty()) {
                emptyList()
            } else {
                VoteTable.selectAll().where { VoteTable.motionId inList motionIds }.map { it[VoteTable.id] }
            }

        if (voteIds.isNotEmpty()) {
            VoteBallotTable.deleteWhere { VoteBallotTable.voteId inList voteIds }
            // Break the Vote -> Resolution FK before the Resolution rows go, so the delete order
            // below does not depend on which of the two a given scenario happened to create first.
            VoteTable.update({ VoteTable.id inList voteIds }) { it[resolutionId] = null }
            VoteOptionTable.deleteWhere { VoteOptionTable.voteId inList voteIds }
            VoteTable.deleteWhere { VoteTable.id inList voteIds }
        }
        if (motionIds.isNotEmpty()) {
            MotionTable.update({ MotionTable.id inList motionIds }) {
                it[resolutionId] = null
                it[agendaItemId] = null
            }
        }
        if (meetingIds.isNotEmpty()) {
            ResolutionTable.deleteWhere { ResolutionTable.meetingId inList meetingIds }
        }
        if (motionIds.isNotEmpty()) {
            MotionTable.deleteWhere { MotionTable.id inList motionIds }
        }
        if (meetingIds.isNotEmpty()) {
            AttendanceTable.deleteWhere { AttendanceTable.meetingId inList meetingIds }
            AgendaItemTable.deleteWhere { AgendaItemTable.meetingId inList meetingIds }
            MeetingTable.deleteWhere { MeetingTable.id inList meetingIds }
        }
        CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId inList committeeIds }
        CommitteeTable.deleteWhere { CommitteeTable.id inList committeeIds }
    }

    if (memberIds.isNotEmpty()) {
        // See KDoc: retire, never delete -- the audit hash chain covers actor_member_id.
        MemberTable.update({ MemberTable.id inList memberIds }) {
            it[status] = MemberStatus.AUSGETRETEN
            it[membershipTierId] = null
        }
    }
}

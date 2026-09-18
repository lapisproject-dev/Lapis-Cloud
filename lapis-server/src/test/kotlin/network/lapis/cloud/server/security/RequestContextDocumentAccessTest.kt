package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import kotlin.uuid.Uuid

/**
 * Pure, DB-free unit coverage of [canAccessDocumentAtLevel]/[isPrivileged]/[ESCALATED_ROLES] directly on
 * [CurrentMember] -- the exact function both [network.lapis.cloud.server.rpc.DocumentService]
 * (listDocuments/listVersions) and [network.lapis.cloud.server.routes.registerDocumentRoutes]'s
 * download route call, so this single table-driven test is the authoritative source of truth for
 * every [DocumentAccessLevel] x role x [CurrentMember.status] combination. Higher-level
 * integration coverage for the two real call sites lives in
 * `network.lapis.cloud.server.rpc.ServiceIntegrationTest` and
 * `network.lapis.cloud.server.routes.DocumentRoutesGuestAccessTest`.
 *
 * **V0.11.0**: extended from a bare `isGuest: Boolean` axis to the full [MemberStatus] axis --
 * this is exactly the test the Security-Loop for the FRIEND wave asks to re-verify: PUBLIC_MEMBERS
 * must be `false` for [MemberStatus.FRIEND], [MemberStatus.GUEST], AND [MemberStatus.APPLICATION]
 * (the last one a pre-existing gap this wave's [CurrentMember.canAccessDocumentAtLevel] rewrite
 * closes, see that function's KDoc).
 */
class RequestContextDocumentAccessTest :
    FunSpec({
        fun member(
            role: AccountRole,
            status: MemberStatus,
        ) = CurrentMember(memberId = Uuid.random(), role = role, status = status)

        test("PUBLIC_MEMBERS: only ACTIVE is allowed, every other status is rejected") {
            AccountRole.entries.forEach { role ->
                MemberStatus.entries.forEach { status ->
                    val expected = status == MemberStatus.ACTIVE
                    member(role, status).canAccessDocumentAtLevel(DocumentAccessLevel.PUBLIC_MEMBERS) shouldBe expected
                }
            }
        }

        test(
            "BOARD_ONLY: uses ESCALATED_ROLES (Welle Treasurer Document Upload) -- BOARD/TREASURER/ADMIN allowed, MEMBER rejected, status irrelevant since a non-member's role is never BOARD/TREASURER/ADMIN",
        ) {
            member(AccountRole.BOARD, MemberStatus.ACTIVE).canAccessDocumentAtLevel(DocumentAccessLevel.BOARD_ONLY) shouldBe true
            member(AccountRole.TREASURER, MemberStatus.ACTIVE).canAccessDocumentAtLevel(DocumentAccessLevel.BOARD_ONLY) shouldBe true
            member(AccountRole.ADMIN, MemberStatus.ACTIVE).canAccessDocumentAtLevel(DocumentAccessLevel.BOARD_ONLY) shouldBe true
            member(AccountRole.MEMBER, MemberStatus.ACTIVE).canAccessDocumentAtLevel(DocumentAccessLevel.BOARD_ONLY) shouldBe false
            // A real guest/friend always has role = MEMBER (see OidcGuestMemberStore /
            // RegistrationService.registerFriend), but even a hypothetical non-member + an
            // ESCALATED_ROLES role (never produced by any actual write path) is still correctly
            // gated by role alone here -- BOARD_ONLY was never the gap, no status check was ever
            // needed on this branch.
            member(AccountRole.MEMBER, MemberStatus.GUEST).canAccessDocumentAtLevel(DocumentAccessLevel.BOARD_ONLY) shouldBe false
            member(AccountRole.MEMBER, MemberStatus.FRIEND).canAccessDocumentAtLevel(DocumentAccessLevel.BOARD_ONLY) shouldBe false
        }

        test("ADMIN_ONLY: unaffected by this fix -- only ADMIN is allowed, BOARD is not, status irrelevant") {
            member(AccountRole.ADMIN, MemberStatus.ACTIVE).canAccessDocumentAtLevel(DocumentAccessLevel.ADMIN_ONLY) shouldBe true
            member(AccountRole.BOARD, MemberStatus.ACTIVE).canAccessDocumentAtLevel(DocumentAccessLevel.ADMIN_ONLY) shouldBe false
            member(AccountRole.MEMBER, MemberStatus.GUEST).canAccessDocumentAtLevel(DocumentAccessLevel.ADMIN_ONLY) shouldBe false
            member(AccountRole.MEMBER, MemberStatus.FRIEND).canAccessDocumentAtLevel(DocumentAccessLevel.ADMIN_ONLY) shouldBe false
        }

        test(
            "isPrivileged is unaffected by status -- a non-member's role is always MEMBER so isPrivileged was never reachable by one to begin with",
        ) {
            member(AccountRole.BOARD, MemberStatus.ACTIVE).isPrivileged shouldBe true
            member(AccountRole.ADMIN, MemberStatus.ACTIVE).isPrivileged shouldBe true
            member(AccountRole.MEMBER, MemberStatus.GUEST).isPrivileged shouldBe false
            member(AccountRole.MEMBER, MemberStatus.FRIEND).isPrivileged shouldBe false
            member(AccountRole.MEMBER, MemberStatus.ACTIVE).isPrivileged shouldBe false
        }

        test(
            "canAccessRecordingAtLevel BOARD_ONLY: deliberately NOT widened to ESCALATED_ROLES -- " +
                "only isPrivileged (BOARD/ADMIN) may read a BOARD_ONLY conference recording, TREASURER " +
                "is rejected even though canAccessDocumentAtLevel(BOARD_ONLY) allows TREASURER for " +
                "documents (Welle Treasurer Document Upload, review finding: recordings must not " +
                "silently ride the document-access widening)",
        ) {
            member(AccountRole.BOARD, MemberStatus.ACTIVE).canAccessRecordingAtLevel(DocumentAccessLevel.BOARD_ONLY) shouldBe true
            member(AccountRole.ADMIN, MemberStatus.ACTIVE).canAccessRecordingAtLevel(DocumentAccessLevel.BOARD_ONLY) shouldBe true
            member(AccountRole.TREASURER, MemberStatus.ACTIVE).canAccessRecordingAtLevel(DocumentAccessLevel.BOARD_ONLY) shouldBe false
            member(AccountRole.MEMBER, MemberStatus.ACTIVE).canAccessRecordingAtLevel(DocumentAccessLevel.BOARD_ONLY) shouldBe false
        }

        test(
            "canAccessRecordingAtLevel PUBLIC_MEMBERS/ADMIN_ONLY: identical to canAccessDocumentAtLevel -- " +
                "neither tier was touched by the TREASURER wave",
        ) {
            AccountRole.entries.forEach { role ->
                MemberStatus.entries.forEach { status ->
                    val m = member(role, status)
                    m.canAccessRecordingAtLevel(DocumentAccessLevel.PUBLIC_MEMBERS) shouldBe
                        m.canAccessDocumentAtLevel(DocumentAccessLevel.PUBLIC_MEMBERS)
                    m.canAccessRecordingAtLevel(DocumentAccessLevel.ADMIN_ONLY) shouldBe
                        m.canAccessDocumentAtLevel(DocumentAccessLevel.ADMIN_ONLY)
                }
            }
        }

        test("isGuest/isNonMember derivation -- isGuest is GUEST-only, isNonMember covers GUEST and FRIEND") {
            member(AccountRole.MEMBER, MemberStatus.GUEST).isGuest shouldBe true
            member(AccountRole.MEMBER, MemberStatus.FRIEND).isGuest shouldBe false
            member(AccountRole.MEMBER, MemberStatus.GUEST).isNonMember shouldBe true
            member(AccountRole.MEMBER, MemberStatus.FRIEND).isNonMember shouldBe true
            member(AccountRole.MEMBER, MemberStatus.ACTIVE).isNonMember shouldBe false
            member(AccountRole.MEMBER, MemberStatus.APPLICATION).isNonMember shouldBe false
        }
    })

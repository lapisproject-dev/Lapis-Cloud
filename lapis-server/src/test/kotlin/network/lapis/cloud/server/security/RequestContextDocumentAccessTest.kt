package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import kotlin.uuid.Uuid

/**
 * Pure, DB-free unit coverage of [canAccessDocumentAtLevel]/[isPrivileged] directly on
 * [CurrentMember] -- the exact function both [network.lapis.cloud.server.rpc.DocumentService]
 * (listDocuments/listVersions) and [network.lapis.cloud.server.routes.registerDocumentRoutes]'s
 * download route call, so this single table-driven test is the authoritative source of truth for
 * every [DocumentAccessLevel] x role x [CurrentMember.isGuest] combination. Higher-level
 * integration coverage for the two real call sites lives in
 * `network.lapis.cloud.server.rpc.ServiceIntegrationTest` and
 * `network.lapis.cloud.server.routes.DocumentRoutesGuestAccessTest`.
 */
class RequestContextDocumentAccessTest :
    FunSpec({
        fun member(
            role: AccountRole,
            isGuest: Boolean,
        ) = CurrentMember(memberId = Uuid.random(), role = role, isGuest = isGuest)

        test("PUBLIC_MEMBERS: any non-guest role is allowed, any guest role is rejected") {
            AccountRole.entries.forEach { role ->
                member(role, isGuest = false).canAccessDocumentAtLevel(DocumentAccessLevel.PUBLIC_MEMBERS) shouldBe true
                member(role, isGuest = true).canAccessDocumentAtLevel(DocumentAccessLevel.PUBLIC_MEMBERS) shouldBe false
            }
        }

        test(
            "BOARD_ONLY: unaffected by this fix -- BOARD/ADMIN allowed, MEMBER rejected, guest-flag irrelevant since a guest's role is never BOARD/ADMIN",
        ) {
            member(AccountRole.BOARD, isGuest = false).canAccessDocumentAtLevel(DocumentAccessLevel.BOARD_ONLY) shouldBe true
            member(AccountRole.ADMIN, isGuest = false).canAccessDocumentAtLevel(DocumentAccessLevel.BOARD_ONLY) shouldBe true
            member(AccountRole.MEMBER, isGuest = false).canAccessDocumentAtLevel(DocumentAccessLevel.BOARD_ONLY) shouldBe false
            // A real guest always has role = MEMBER (see OidcGuestMemberStore), but even a
            // hypothetical isGuest=true + BOARD/ADMIN combination (never produced by any actual
            // write path) is still correctly gated by role alone here -- BOARD_ONLY was never the
            // gap, no isGuest check was ever needed on this branch.
            member(AccountRole.MEMBER, isGuest = true).canAccessDocumentAtLevel(DocumentAccessLevel.BOARD_ONLY) shouldBe false
        }

        test("ADMIN_ONLY: unaffected by this fix -- only ADMIN is allowed, BOARD is not, guest-flag irrelevant") {
            member(AccountRole.ADMIN, isGuest = false).canAccessDocumentAtLevel(DocumentAccessLevel.ADMIN_ONLY) shouldBe true
            member(AccountRole.BOARD, isGuest = false).canAccessDocumentAtLevel(DocumentAccessLevel.ADMIN_ONLY) shouldBe false
            member(AccountRole.MEMBER, isGuest = true).canAccessDocumentAtLevel(DocumentAccessLevel.ADMIN_ONLY) shouldBe false
        }

        test(
            "isPrivileged is unaffected by isGuest -- a guest's role is always MEMBER so isPrivileged was never reachable by a guest to begin with",
        ) {
            member(AccountRole.BOARD, isGuest = false).isPrivileged shouldBe true
            member(AccountRole.ADMIN, isGuest = false).isPrivileged shouldBe true
            member(AccountRole.MEMBER, isGuest = true).isPrivileged shouldBe false
            member(AccountRole.MEMBER, isGuest = false).isPrivileged shouldBe false
        }
    })

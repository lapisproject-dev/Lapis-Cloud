package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel

/**
 * Welle "Treasurer Document Upload" -- review finding fix. Extracted from `DocumentsScreen`'s
 * inline `val canManage = AppState.hasRole(BOARD, TREASURER, ADMIN)`, which was ungated by any
 * test: `DocumentsScreenTest` never touched it, so the next change to `ESCALATED_ROLES`
 * (`network.lapis.cloud.server.security.RequestContext`) could silently drift this client check
 * away from the server's real gate with nothing to catch it. Same "UX nicety on top of the
 * server's real authority" posture every other `*AuthzUi` object in this client carries -- see
 * [DatevAuthzUi] KDoc -- and same "drift guard" test shape as [DatevAuthzUiTest].
 *
 * [MANAGE_ROLES] must mirror `ESCALATED_ROLES` exactly (BOARD/TREASURER/ADMIN): the server's real
 * gates are `DocumentService.createFolder`/`createDocument`/`deleteDocument` and
 * `network.lapis.cloud.server.routes.registerDocumentRoutes`'s upload route, all three of which
 * check `current.role !in ESCALATED_ROLES`. A narrower client set would just hide buttons a
 * TREASURER is actually allowed to use; a wider one would show buttons that 403 server-side.
 */
object DocumentsAuthzUi {
    val MANAGE_ROLES: Set<AccountRole> = setOf(AccountRole.BOARD, AccountRole.TREASURER, AccountRole.ADMIN)

    fun canManage(role: AccountRole?): Boolean = role in MANAGE_ROLES

    /**
     * Review finding fix (Welle "Treasurer Document Upload", Runde 4): mirrors the server's
     * `CurrentMember.canAccessDocumentAtLevel` role-branch exactly (the DocumentAccessLevel status
     * check for PUBLIC_MEMBERS is irrelevant here -- only [MANAGE_ROLES] members reach this
     * dropdown at all, and every one of them already has organization-member status). Before this
     * fix, `DocumentsScreen`'s access-level dropdown offered `DocumentAccessLevel.entries`
     * unconditionally, so a TREASURER or BOARD member could pick `ADMIN_ONLY` and get a 200 from
     * `createDocument` -- but the resulting document was invisible in `listDocuments` (filtered by
     * level), un-uploadable (upload route 403s below its own access-level check) and
     * un-deletable-by-them (`deleteDocument` 403s the same way): a permanently orphaned row only
     * an ADMIN could ever clean up, with the client showing a success toast the whole time. This
     * function is the single source of truth for which levels a role may pick when creating a
     * document; `createDocument`'s own `canAccessDocumentAtLevel` check is the real server-side
     * authority, this is purely a UX nicety on top of it, same posture as [canManage].
     */
    fun allowedCreateLevels(role: AccountRole?): List<DocumentAccessLevel> =
        DocumentAccessLevel.entries.filter { level ->
            when (level) {
                DocumentAccessLevel.PUBLIC_MEMBERS -> true
                DocumentAccessLevel.BOARD_ONLY -> role in MANAGE_ROLES
                DocumentAccessLevel.ADMIN_ONLY -> role == AccountRole.ADMIN
            }
        }
}

package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.UnlinkedMemberDto

/**
 * V1.7.2 sub-wave 2a "Keycloak als externe Benutzerverwaltung -- UI (Server-Seite)" -- the
 * ADMIN-only manual counterpart to the automatic email-match linking
 * [network.lapis.cloud.server.keycloak.KeycloakAccountLinker] already performs on every successful
 * Keycloak login (see that class's KDoc). This interface exists for exactly the case the vault
 * spec's decision 5 calls out: "Kein Treffer -> Login wird abgelehnt, ein Admin muss manuell
 * verknüpfen" (no automatic email match -> login is rejected, an admin must link manually) -- e.g.
 * a mistyped/collided email, or a member whose Keycloak account uses a different address than their
 * `member.email`.
 *
 * **Deliberately typed exceptions, not a `Result<T>` wrapper** -- same wire-transparency-driven
 * convention every other RPC interface in this codebase uses (see
 * `network.lapis.cloud.shared.rpc.ServiceExceptions.kt` KDoc "why this is a distinct type"): Kilua
 * RPC's polymorphic exception protocol transmits only the exception's TYPE across the wire, never
 * an arbitrary `Result` payload's shape, so every rejection here is one of the existing shared
 * [ForbiddenException]/[ConflictException]/[NotFoundException] types instead of a bespoke
 * `Result`/sealed-outcome return value.
 *
 * **ADMIN-only, not the broader `ESCALATED_ROLES` (BOARD/TREASURER/ADMIN).** Linking touches
 * another member's login capability directly -- a strictly higher-stakes action than the
 * BOARD_ONLY document tier or committee administration that `ESCALATED_ROLES` normally gates (see
 * `network.lapis.cloud.server.security.RequestContext.ESCALATED_ROLES` KDoc). This mirrors
 * [ITrustAnchorService]'s own ADMIN-only tier for an org-wide trust decision, and
 * [network.lapis.cloud.server.keycloak.KeycloakAccountLinker]'s own reservation of `linkedBy =
 * null` for an auto-link vs. a real member id for a manual one -- only an ADMIN may be that member
 * id.
 */
@RpcService
interface IKeycloakLinkService {
    /**
     * Role: ADMIN. Every local member this Keycloak-mode deployment's automatic linking cannot
     * (yet) resolve on its own -- i.e. no `keycloak_account_link` row exists for them -- filtered to
     * members an admin could plausibly still want to log in at all (excludes
     * [network.lapis.cloud.shared.domain.MemberStatusSets.LOGIN_BLOCKED], the same status set
     * [network.lapis.cloud.server.keycloak.KeycloakAccountLinker] itself already treats as blocking
     * login). Ordered by [network.lapis.cloud.shared.domain.UnlinkedMemberDto.displayName] so an
     * admin can find a specific member in a picker UI.
     */
    suspend fun listUnlinkedMembers(): List<UnlinkedMemberDto>

    /**
     * Role: ADMIN. Manually links [memberId] to the Keycloak identity `(issuer, [keycloakSubject])`
     * -- `issuer` is always this deployment's single configured `keycloakConfig.issuerUrl` (Wave 1
     * does not support multiple simultaneous Keycloak issuers, see
     * [network.lapis.cloud.server.keycloak.KeycloakConfig] KDoc), so only the subject is a caller
     * argument. Unlike an auto-link, the resulting row's `linked_by` is the CALLING admin's own
     * member id, never `null` (see [network.lapis.cloud.server.keycloak.KeycloakAccountLinkTable]
     * KDoc "linkedBy"). Throws [NotFoundException] if [memberId] does not resolve to an existing
     * member, [ConflictException] if [memberId] already has a link, or if `(issuer,
     * [keycloakSubject])` is already linked to a DIFFERENT member. Throws
     * [network.lapis.cloud.shared.rpc.BadRequestException] if this deployment is not in Keycloak
     * mode at all (`keycloakConfig.enabled == false`) -- there is nothing to link against.
     *
     * **Security-audit fix (V1.7.2)**: also [ConflictException] if the target is DSGVO-anonymized
     * or its status is in [network.lapis.cloud.shared.domain.MemberStatusSets.LOGIN_BLOCKED]. The
     * audit event (acting admin + SHA-256 fingerprint of the subject) is written in the SAME
     * transaction as the link. After commit the member receives a security notice mail (never
     * containing the subject) -- a manual link decides which external identity may act as this
     * member, the same class of action as `IMemberService.setTemporaryPasswordForMember`, and gets
     * the same transparency control.
     */
    suspend fun linkMember(
        memberId: String,
        keycloakSubject: String,
    )

    /**
     * Role: ADMIN. Removes [memberId]'s `keycloak_account_link` row, if one exists -- idempotent:
     * calling it again on an already-unlinked member is a clean no-op, same idempotency convention
     * [network.lapis.cloud.shared.rpc.ITrustAnchorService.removePoolMember]'s sibling methods
     * establish for THEIR own "remove" actions (there, a missing row is a [NotFoundException]
     * instead -- this method deliberately diverges: unlinking is a defensive/corrective admin
     * action an admin may plausibly retry without first checking current state, and a member with
     * no link is already in the desired end state, not an error). Throws [NotFoundException] only
     * if [memberId] itself does not resolve to an existing member.
     *
     * **Security-audit fix (V1.7.2)**: a genuine removal (a) is audited in the same transaction,
     * including a fingerprint of the removed subject, (b) revokes every live session of the target
     * (an unlink is typically the correction of a WRONG link, whose holder may be logged in as this
     * member right now; an admin unlinking their own record keeps the current session), and (c)
     * sends the member a security notice unless they are anonymized/DECEASED. The no-op case has
     * none of these side effects.
     *
     * Returns `true` if a row actually existed and was removed, `false` for the no-op case above --
     * review fix (MINOR 6): lets the caller show an honest "nothing was linked" message instead of
     * always claiming a link was removed.
     */
    suspend fun unlinkMember(memberId: String): Boolean
}

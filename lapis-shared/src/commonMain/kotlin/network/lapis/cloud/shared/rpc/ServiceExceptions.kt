package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.AbstractServiceException
import dev.kilua.rpc.annotations.RpcServiceException

/**
 * The project's typed RPC service exceptions, defined here in `lapis-shared` (not in
 * `lapis-server`, where each used to live next to its throw site) specifically so Kilua RPC's KSP
 * processor -- which only ever runs against this module's `commonMain`/`jvm`/`js` source sets, see
 * `lapis-shared/build.gradle.kts` -- can see them on the JS target too and generate a correct
 * polymorphic serializer for them. When these classes lived in the JVM-only `lapis-server` module,
 * a JS client deserializing any RPC error response failed with a
 * `SerializationException: Serializer for subclass '<Name>' is not found in the polymorphic scope
 * of 'AbstractServiceException'` instead of receiving the typed exception -- the authorization
 * boundary itself was never affected (calls were still correctly rejected), only the error's wire
 * shape was broken. See each throw site's own KDoc (`resolveCurrentMember`/`requireRole` in
 * `RequestContext.kt`, `PasswordPolicy.validate`, `AuthService.changePassword`, etc.) for why each
 * exception is thrown; this file only holds the type declarations themselves.
 */
@RpcServiceException
class UnauthenticatedException(
    override val message: String = "Missing, invalid, or expired session",
) : AbstractServiceException()

@RpcServiceException
class ForbiddenException(
    override val message: String = "Not authorized for this operation",
) : AbstractServiceException()

@RpcServiceException
class WeakPasswordException(
    override val message: String,
) : AbstractServiceException()

@RpcServiceException
class InvalidPasswordException(
    override val message: String = "Current password is incorrect",
) : AbstractServiceException()

@RpcServiceException
class NotFoundException(
    override val message: String,
) : AbstractServiceException()

@RpcServiceException
class ConflictException(
    override val message: String,
) : AbstractServiceException()

@RpcServiceException
class BadRequestException(
    override val message: String,
) : AbstractServiceException()

/**
 * Welle V1.2.12 -- **why this is a distinct type and not just `ConflictException("A member with
 * this email already exists")`**: `AppState.guarded`'s own KDoc documents, empirically verified,
 * that Kilua RPC's polymorphic exception protocol never transmits an `AbstractServiceException`
 * subclass's own `message` across the wire -- only the subclass discriminator itself. A client
 * catching a plain `ConflictException` therefore cannot distinguish "email already taken" from
 * "reason too short" from "illegal status transition" by inspecting `e.message` (it is always
 * empty on the JS side); the only wire-visible signal is the exception's TYPE. This is the exact
 * problem [WeakPasswordException]/[InvalidPasswordException] already solved for password
 * validation -- this class follows the same established pattern: a distinct type, a
 * server-authored default message (visible to JVM-side test code calling the service directly,
 * never to the browser), and a dedicated client-side catch clause
 * (`network.lapis.cloud.client.MemberAdminGuard.memberAdminGuarded`) that shows a fixed,
 * type-appropriate German toast instead of trying to parse a message that never arrives. Thrown by
 * `network.lapis.cloud.server.rpc.MemberService.updateMemberCoreData` for both the pre-check and
 * the concurrent-write race backstop (same two-layer uniqueness guard
 * `RegistrationService.createMemberDirect` already establishes for the identical email-uniqueness
 * question).
 */
@RpcServiceException
class MemberEmailInUseException(
    override val message: String = "A member with this email already exists",
) : AbstractServiceException()

/**
 * Welle V1.2.12 Review Runde 3 -- see [MemberEmailInUseException] KDoc for why this is a distinct
 * type rather than a `ConflictException` message (the identical wire-transparency reasoning
 * applies here). Before this type existed, an overlong address hit a plain `ConflictException`
 * server-side, which `network.lapis.cloud.client.MemberAdminGuard.memberAdminGuarded`'s generic
 * fallback then showed as "Die Aktion steht im Konflikt mit dem aktuellen Zustand -- bitte Ansicht
 * aktualisieren" -- actively misleading for a length problem (refreshing the view fixes nothing).
 * Thrown by `network.lapis.cloud.server.rpc.MemberService.updateMemberCoreData` when the
 * normalized address exceeds `MemberTable.email`'s `VARCHAR(320)` column length (see that method's
 * own `MEMBER_EMAIL_MAX_LENGTH`-check KDoc).
 */
@RpcServiceException
class MemberEmailTooLongException(
    override val message: String = "email exceeds the maximum length",
) : AbstractServiceException()

/**
 * Welle V1.2.12 -- see [MemberEmailInUseException] KDoc for why this is a distinct type rather
 * than a `ConflictException` message. Thrown by
 * `network.lapis.cloud.server.rpc.MemberService.updateMemberRole` when the target member has no
 * `account` row at all -- the structural reality for every one of the 407 members
 * `network.lapis.cloud.server.bootstrap.MemberCsvImport` created (see `MemberAdminRowDto.role`
 * KDoc): there is no role to change, by construction, not a transient/racy condition.
 */
@RpcServiceException
class MemberHasNoAccountException(
    override val message: String = "Member has no login account -- no role to change",
) : AbstractServiceException()

/**
 * Welle V1.2.12 -- see [MemberEmailInUseException] KDoc for why this is a distinct type rather
 * than a `ConflictException` message. Thrown by
 * `network.lapis.cloud.server.rpc.MemberService.updateMemberRole`'s race-safe last-admin guard
 * (`.forUpdate()`-locked `ADMIN`-row count, see that method's own KDoc "Letzter-Admin-Schutz") --
 * genuinely reachable only through the concurrent-degradation race the guard exists to close (two
 * different ADMIN callers simultaneously demoting each other), never through a lone caller
 * demoting themselves (that path is rejected earlier, and independently, as a self-service
 * action).
 */
@RpcServiceException
class LastAdminException(
    override val message: String = "Cannot remove the last remaining ADMIN account",
) : AbstractServiceException()

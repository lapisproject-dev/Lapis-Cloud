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

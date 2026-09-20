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

/**
 * Welle V1.2.13 -- see [MemberEmailInUseException] KDoc for why this is a distinct type rather
 * than a `ConflictException` message (identical wire-transparency reasoning: Kilua RPC transmits
 * only the subclass discriminator, never the message). The structural counterpart of
 * [MemberHasNoAccountException]: thrown by
 * `network.lapis.cloud.server.rpc.MemberService.grantMemberAccount` when the target member ALREADY
 * has an `account` row -- both by the explicit pre-check and by the `uq_account_member_id`
 * unique-index race backstop (same two-layer uniqueness idiom
 * `MemberService.updateMemberCoreData` already establishes for the e-mail column).
 *
 * Also the type an ADMIN targeting their OWN member id receives: the caller necessarily has an
 * account (they authenticated with it), so `grantMemberAccount` needs no separate self-target
 * check -- see that method's own KDoc.
 */
@RpcServiceException
class MemberAlreadyHasAccountException(
    override val message: String = "Member already has a login account",
) : AbstractServiceException()

/**
 * Welle V1.3.2 "Webhooks" (ausgehend) -- see [MemberEmailInUseException] KDoc for why this is FOUR
 * distinct types rather than one `WebhookUrlRejectedException(reason: WebhookUrlRejectionReason)`:
 * the identical wire-transparency constraint applies to ANY non-discriminator field, not just
 * `message` -- Kilua RPC's polymorphic exception protocol transmits only the subclass type itself,
 * so a `reason` property would never arrive at the client either. Thrown by
 * `network.lapis.cloud.server.rpc.WebhookService.setWebhookUrl`/`.rotateWebhookSecret`... no,
 * `rotateWebhookSecret` never validates a URL -- only `setWebhookUrl`, via
 * `network.lapis.cloud.server.webhook.OutboundUrlGuard.checkWebhookUrl`'s four
 * [WebhookUrlRejectionReason] outcomes (Design-Team decision D6). The client
 * (`network.lapis.cloud.client.ApiKeysScreen`) catches each type separately and shows the
 * corresponding one of D6's four fixed German sentences -- never an IP address, hostname, or DNS
 * detail (same non-leaking discipline the guard itself enforces server-side).
 */
@RpcServiceException
class WebhookUrlNotHttpsException(
    override val message: String = "Webhook URL must use https://",
) : AbstractServiceException()

@RpcServiceException
class WebhookUrlMalformedException(
    override val message: String = "Webhook URL is not a valid URL",
) : AbstractServiceException()

@RpcServiceException
class WebhookUrlNotPubliclyRoutableException(
    override val message: String = "Webhook URL does not resolve to a publicly routable address",
) : AbstractServiceException()

@RpcServiceException
class WebhookUrlTooLongException(
    override val message: String = "Webhook URL exceeds the maximum length",
) : AbstractServiceException()

/**
 * Welle V1.4.22 "Zahlungskonto im Offene-Posten-Pfad" -- see [MemberEmailInUseException] KDoc for why
 * these are THREE distinct types rather than three `ConflictException` messages, or one exception
 * carrying a reason code: Kilua RPC's polymorphic exception protocol transmits only the subclass
 * discriminator, so neither `message` nor any other property ever reaches the browser. The exception
 * TYPE is the only stable, wire-visible marker available -- exactly the constraint
 * [WebhookUrlNotHttpsException] and friends already answer the same way.
 *
 * Found live on staging (V1.4.21): `settleOpenItem` rejected a settlement with
 * `ConflictException("no bankAccountId given and organization_settings.payment_bank_account_id is
 * not configured")`, and the treasurer saw `AppState.guarded`'s generic "Die Aktion steht im
 * Konflikt mit dem aktuellen Zustand -- bitte Ansicht aktualisieren" -- advice that fixes nothing,
 * with the actual cause (an unconfigured organization setting) nowhere on screen.
 *
 * Thrown by `network.lapis.cloud.server.rpc.OpenItemService.settleOpenItem` (no explicit
 * `bankAccountId` and no `organization_settings.payment_bank_account_id`) and
 * `.retrySettlementPosting` (which has no explicit account at all and therefore ALWAYS needs the
 * default mapping). Caught by `network.lapis.cloud.client.openItemGuarded`.
 */
@RpcServiceException
class PaymentBankAccountNotConfiguredException(
    override val message: String = "organization_settings.payment_bank_account_id is not configured",
) : AbstractServiceException()

/**
 * Welle V1.4.22 -- distinct type, see [PaymentBankAccountNotConfiguredException]. An open item whose
 * creation posting failed (`creation_journal_entry_id IS NULL`) cannot be settled: the remedy is
 * `retryOpenItemPosting`, which the generic conflict toast never mentioned. Thrown by
 * `network.lapis.cloud.server.rpc.OpenItemService.settleOpenItem`.
 */
@RpcServiceException
class OpenItemNotBookedException(
    override val message: String = "OpenItem is not booked yet -- call retryOpenItemPosting first",
) : AbstractServiceException()

/**
 * Welle V1.4.22 -- distinct type, see [PaymentBankAccountNotConfiguredException]. The settled amount
 * exceeds the item's live `openAmount`. The client pre-checks this against the row it displays, so
 * reaching the server means the displayed row is stale (a concurrent settlement) -- which is the one
 * case where "refresh the view" IS the right advice, but it has to say WHICH check failed. Thrown by
 * `network.lapis.cloud.server.rpc.OpenItemService.settleOpenItem`.
 */
@RpcServiceException
class OpenItemAmountExceedsOpenAmountException(
    override val message: String = "amount exceeds the open amount of this OpenItem",
) : AbstractServiceException()

/**
 * Welle V1.4.22, Audit-Nachtrag -- the caller-supplied (or organization-default) ledger account cannot
 * be the money side of a payment: it is inactive, not an `ASSET` account, the receivables/payables
 * collective account, or does not exist at all. Distinct type for the same wire-transparency reason as
 * [PaymentBankAccountNotConfiguredException]; without it this arrived as a plain
 * [BadRequestException], which the client can only render as "Ungültige Anfrage." -- true but useless
 * for a treasurer whose account list went stale while the dialog was open.
 *
 * **A non-existent account id is folded in deliberately.** The obvious alternative,
 * [NotFoundException], is indistinguishable on the client from "the open item was not found" (Kilua
 * RPC transmits only the type), which would be actively misleading. A malformed id string still
 * becomes a [NotFoundException] before this point (`toOpenItemUuid`, this repo's established
 * "well-formed-ness vs. semantic validity" split).
 *
 * Thrown by `network.lapis.cloud.server.rpc.OpenItemService.settleOpenItem`/`.retrySettlementPosting`
 * via their shared `requirePaymentCapableAccount`.
 */
@RpcServiceException
class PaymentAccountNotPaymentCapableException(
    override val message: String = "The chosen ledger account cannot be used as a payment account",
) : AbstractServiceException()

/**
 * Welle V1.6.1 -- the AI assistance layer is switched off (default) or not fully configured on
 * this server. A distinct type for the same wire-transparency reason as [MemberEmailInUseException]:
 * Kilua RPC transmits only the subclass discriminator, never the message.
 */
@RpcServiceException
class AiFeatureDisabledException(
    override val message: String = "AI assistance is not available on this server",
) : AbstractServiceException()

/** Welle V1.6.1 -- the caller has not opted in to the requested AI feature. Distinct type, see [AiFeatureDisabledException]. */
@RpcServiceException
class AiOptInMissingException(
    override val message: String = "Member has not opted in to AI assistance",
) : AbstractServiceException()

/**
 * Welle V1.6.1 -- only `PUBLIC_MEMBERS` documents may be released to the AI knowledge base (a
 * `BOARD_ONLY`/`ADMIN_ONLY` text would otherwise leave the server towards an external provider).
 * Distinct type, see [AiFeatureDisabledException].
 */
@RpcServiceException
class AiDocumentNotReleasableException(
    override val message: String = "Only PUBLIC_MEMBERS documents may be released to the knowledge base",
) : AbstractServiceException()

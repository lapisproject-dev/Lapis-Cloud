package network.lapis.cloud.server.routes

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.events.QrCodeEncoder
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.member.MemberCardEligibility
import network.lapis.cloud.server.member.MemberCardIssuance
import network.lapis.cloud.server.member.MemberCardPolicy
import network.lapis.cloud.server.pdf.MemberCardPdfGenerator
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle "Digitaler Mitgliedsausweis (PDF)" -- the authenticated card download. PDF bytes travel
 * over a plain Ktor route rather than Kilua RPC, same reasoning [registerMailmergeRoutes]/
 * [registerDocumentRoutes] already document for every other binary payload in this codebase.
 *
 * **Deliberately a `POST`, not a `GET` -- the single most load-bearing decision in this file.**
 * Every other PDF route here is a `GET`, because every other one is a pure read. This one is not:
 * a card code exists only as a hash at rest ([network.lapis.cloud.server.member.MemberCardStore]),
 * so producing a card ALWAYS mints a fresh code and invalidates the previous one (see
 * [MemberCardIssuance] KDoc for why there is no third option). A `GET` that rotates a credential is
 * a trap: browser prefetch, a link preview in a chat client, a double-clicked download, or a
 * retried request would each silently invalidate a member's card. `POST` also means no card code
 * can ever land in a `Referer` header, a browser history entry, or a proxy access log via a URL.
 * The client triggers it with the hidden-form-POST idiom this codebase already uses for
 * `POST /api/mailmerge/invitations` (see `MailmergeHttp.submitEinladungPdfDownload`).
 *
 * **Authorization**: the subject themselves OR BOARD/ADMIN -- the same shape
 * [registerTravelExpenseReceiptRoutes]' download gate uses, and deliberately NOT TREASURER: a
 * membership card is an identity document, not a financial one. Enforced here, server-side, never
 * only in the UI.
 *
 * **Not eligible, not issued**: only a member in
 * [network.lapis.cloud.shared.domain.MemberStatusSets.ORGANIZATION_MEMBER] gets a card (409
 * otherwise) -- see [MemberCardEligibility] KDoc.
 *
 * **Never stored.** The PDF is generated per request and streamed; unlike
 * [registerMailmergeRoutes]' Beitragsrechnung/Spendenbescheinigung it is NOT archived into the
 * Document store. There is no retention obligation for a membership card, and an archived copy
 * would be a second, longer-lived place holding a bearer credential's QR code.
 *
 * **Brand, not organization record**: [brandTitle] is the operator's resolved
 * [network.lapis.cloud.server.branding.ResolvedBranding] title (the same value the public pages
 * use), not `organization_settings.name` -- the card carries no address, no bank details and no
 * legal-entity data, so what it needs is the name the member recognises from every other surface
 * of this deployment, resolved once at startup rather than per request.
 *
 * **Rate limiting**: keyed by the SUBJECT member, not the caller -- the resource being protected is
 * one member's card-code rotation rate, and a BOARD/ADMIN caller cycling one member's card is
 * exactly as harmful as that member doing it themselves.
 */
fun Route.registerMemberCardRoutes(
    baseUrl: String,
    brandTitle: String,
    rateLimiter: FederationInboxRateLimiter,
) {
    post("/api/members/{memberId}/card.pdf") {
        val memberId = call.parameters["memberId"]?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        if (memberId == null) {
            call.respond(HttpStatusCode.BadRequest, "Invalid memberId")
            return@post
        }
        val current = resolveCurrentMember(call)
        if (current.memberId != memberId && !current.isPrivileged) throw ForbiddenException()

        if (!rateLimiter.checkAndRecord("member-card:$memberId")) {
            call.response.header(
                HttpHeaders.RetryAfter,
                rateLimiter.retryAfterSeconds("member-card:$memberId").toString(),
            )
            call.respond(HttpStatusCode.TooManyRequests, "Zu viele Ausweis-Anfragen -- bitte spaeter erneut versuchen.")
            return@post
        }

        val issued =
            try {
                transaction {
                    val now = DbClock.nowLocalDateTime()
                    val result = MemberCardIssuance.rotate(memberId = memberId, now = now)
                    // Audit trail on the EXISTING MEMBER entity type (no new AuditEntityType, no
                    // schema drift): issuing a card is a governance-relevant act on a member --
                    // it is how "who could prove membership, since when" is reconstructed later,
                    // and for a BOARD/ADMIN-triggered issuance it is also the record of whose
                    // card was invalidated in the process. The raw code is NEVER written here --
                    // an audit log is readable by ADMIN and would otherwise become a second
                    // plaintext store of every bearer credential ever issued.
                    AuditLogRecorder.record(
                        actorMemberId = current.memberId,
                        actorRole = current.role,
                        entityType = AuditEntityType.MEMBER,
                        entityId = memberId,
                        action = AuditAction.UPDATE,
                        after = if (result.revoked) MEMBER_CARD_AUDIT_REISSUED else MEMBER_CARD_AUDIT_ISSUED,
                        occurredAt = now,
                    )
                    result
                }
            } catch (e: NotFoundException) {
                call.respond(HttpStatusCode.NotFound, e.message)
                return@post
            } catch (e: ConflictException) {
                call.respond(HttpStatusCode.Conflict, e.message)
                return@post
            }

        val pdfBytes =
            MemberCardPdfGenerator.generate(
                MemberCardPdfGenerator.Card(
                    brandTitle = brandTitle,
                    displayName = issued.card.displayName,
                    memberNumber = issued.card.memberNumber,
                    joinedAt = issued.card.joinedAt,
                    statusLabel = MemberCardEligibility.statusLabel(issued.card.status),
                    membershipTierName = issued.card.membershipTierName,
                    qr = QrCodeEncoder.encode(MemberCardPolicy.verifyUrl(baseUrl = baseUrl, rawCode = issued.rawCode)),
                ),
            )

        call.response.header(HttpHeaders.CacheControl, "private, no-store")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header(
            HttpHeaders.ContentDisposition,
            // RFC 5987 `filename*` plus an ASCII fallback -- the same helper
            // `registerTravelExpenseReceiptRoutes` uses, rather than Ktor's `ContentDisposition`
            // builder that the older Mailmerge routes use: a member number is pure ASCII today, but
            // the filename is assembled from it here and nothing guarantees that forever.
            contentDispositionHeader("Mitgliedsausweis-${issued.card.memberNumber}.pdf"),
        )
        call.respondBytes(pdfBytes, contentType = ContentType.Application.Pdf)
    }
}

/** Audit `after` markers -- short, stable literals, never the raw code (see the route's own comment). */
internal const val MEMBER_CARD_AUDIT_ISSUED = "member_card_issued"
internal const val MEMBER_CARD_AUDIT_REISSUED = "member_card_reissued"

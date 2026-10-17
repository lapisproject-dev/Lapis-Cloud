package network.lapis.cloud.server.events

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.EventCaptureAcknowledgmentTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.uuid.Uuid

/**
 * V1.9.156 -- THE ONLY WRITER of `event_capture_acknowledgment` (the GDPR contributor reads and, on erasure, leaves it; see `EventCapturePersonalData`).
 * All functions run inside the caller's `transaction {}`, which also holds the row lock of the event (`EventStore.lockEventForUpdate`), so the
 * check-then-insert idempotence below cannot race with itself for one event.
 *
 * **What is stored, and what is not.** The version label and hash of the consent text, the event, ONE of {member id, guest key} and a DATE -- no name,
 * no room, no clock time, no IP. The date is the organization-zone day ([network.lapis.cloud.server.time.OrganizationTimeZone.today]). The guest key is
 * the HMAC-SHA256 hex of the LiveKit identity of a person without an account: it names nobody and, because it is an HMAC under a server secret, cannot be
 * linked to a registration or ticket by anybody who only holds the database; the server can match it with the track files of a recording (whose
 * participant identity it is), which is exactly what the proof is for.
 */
internal object EventCaptureAcknowledgments {
    /**
     * The key under which a person WITHOUT an account is proven: HMAC-SHA256 hex of the LiveKit identity (`evg-...`) under a sub-key of the server secret
     * ([serverSecret], the LiveKit API secret, domain-separated), 64 characters.
     *
     * The secret is the point: the identity of a ticket holder is derived from data that sits in `event_registration` (`ticket_code_sha256`), so a plain
     * hash of it could be recomputed for every registration and matched against this table -- which would turn the proof into an online attendance list
     * by name. Without the secret that link cannot be made; the server itself can still compute the key from the identity in a recording's track files.
     */
    fun guestKeyOf(
        identity: String,
        serverSecret: String,
    ): String {
        val subKey = hmac(key = serverSecret.toByteArray(Charsets.UTF_8), data = GUEST_KEY_DOMAIN.toByteArray(Charsets.UTF_8))
        return hmac(key = subKey, data = identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private const val GUEST_KEY_DOMAIN = "lapis-event-capture-guest-key-v1"

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** Idempotent: a second acknowledgment of the same version for the same event and member writes nothing. */
    fun recordMember(
        eventId: Uuid,
        memberId: Uuid,
        consentVersion: String,
        consentSha256: String,
        today: LocalDate,
    ) {
        val exists =
            EventCaptureAcknowledgmentTable
                .selectAll()
                .where {
                    (EventCaptureAcknowledgmentTable.eventId eq eventId) and
                        (EventCaptureAcknowledgmentTable.memberId eq memberId) and
                        (EventCaptureAcknowledgmentTable.consentVersion eq consentVersion)
                }.limit(1)
                .any()
        if (exists) return
        EventCaptureAcknowledgmentTable.insert {
            it[id] = Uuid.random()
            it[EventCaptureAcknowledgmentTable.eventId] = eventId
            it[EventCaptureAcknowledgmentTable.memberId] = memberId
            it[guestKey] = null
            it[EventCaptureAcknowledgmentTable.consentVersion] = consentVersion
            it[EventCaptureAcknowledgmentTable.consentSha256] = consentSha256
            it[acknowledgedOn] = today
        }
    }

    /** Idempotent, like [recordMember]; [guestKey] comes from [guestKeyOf]. */
    fun recordGuest(
        eventId: Uuid,
        guestKey: String,
        consentVersion: String,
        consentSha256: String,
        today: LocalDate,
    ) {
        val exists =
            EventCaptureAcknowledgmentTable
                .selectAll()
                .where {
                    (EventCaptureAcknowledgmentTable.eventId eq eventId) and
                        (EventCaptureAcknowledgmentTable.guestKey eq guestKey) and
                        (EventCaptureAcknowledgmentTable.consentVersion eq consentVersion)
                }.limit(1)
                .any()
        if (exists) return
        EventCaptureAcknowledgmentTable.insert {
            it[id] = Uuid.random()
            it[EventCaptureAcknowledgmentTable.eventId] = eventId
            it[memberId] = null
            it[EventCaptureAcknowledgmentTable.guestKey] = guestKey
            it[EventCaptureAcknowledgmentTable.consentVersion] = consentVersion
            it[EventCaptureAcknowledgmentTable.consentSha256] = consentSha256
            it[acknowledgedOn] = today
        }
    }

    /** Deleting an event deletes its proofs first (no ON DELETE CASCADE in V110). */
    fun deleteForEvent(eventId: Uuid) {
        EventCaptureAcknowledgmentTable.deleteWhere { EventCaptureAcknowledgmentTable.eventId eq eventId }
    }
}

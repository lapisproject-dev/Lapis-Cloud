package network.lapis.cloud.server.events

import network.lapis.cloud.server.agreementtexts.AgreementTextTestState
import network.lapis.cloud.server.db.generated.EventCaptureAcknowledgmentTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.EVENT_CAPTURE_NOTICE_VERSION
import network.lapis.cloud.shared.domain.EventCaptureAcknowledgmentInput
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** A valid `EVENT_REC` body: headline, three key points, one detail paragraph (and not the built-in placeholder). */
internal const val EVENT_CAPTURE_TEST_BODY: String = "Aufzeichnung im Saal\n\nPunkt eins\n\nPunkt zwei\n\nPunkt drei\n\nDetailabsatz."

internal const val EVENT_CAPTURE_TEST_LABEL: String = "2026-10-18.test.v1"

/**
 * V1.9.156 -- the state of the event capture feature in a test. The H2 database is shared by every spec of one test JVM: a spec that switches the
 * feature on or publishes an `EVENT_REC` text MUST call [reset] in `afterEach`/`afterSpec`, or every other event test would suddenly meet a consent.
 */
internal object EventCaptureTestState {
    /** Sets the instance switch directly (test-side SQL; the rules of the switch itself are tested through the service). */
    fun instance(
        enabled: Boolean,
        noticeVersion: String? = if (enabled) EVENT_CAPTURE_NOTICE_VERSION else null,
    ) {
        transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[eventCaptureEnabled] = enabled
                it[eventCaptureNoticeVersion] = noticeVersion
            }
        }
    }

    /** Publishes a consent text straight through the store and returns the input a participant would send for it. */
    fun publishConsent(
        label: String = EVENT_CAPTURE_TEST_LABEL,
        body: String = EVENT_CAPTURE_TEST_BODY,
    ): EventCaptureAcknowledgmentInput {
        val sha = AgreementTextTestState.publishDirect(kind = AgreementTextKind.EVENT_REC, label = label, body = body)
        return EventCaptureAcknowledgmentInput(consentVersion = label, consentSha256 = sha)
    }

    /** Switch ON with a published text: the state in which a released event requires the consent. */
    fun enabledWithText(): EventCaptureAcknowledgmentInput {
        instance(enabled = true)
        return publishConsent()
    }

    fun release(
        eventId: Uuid,
        allowed: Boolean = true,
    ) {
        transaction { EventTable.update({ EventTable.id eq eventId }) { it[captureAllowed] = allowed } }
    }

    fun acknowledgments(eventId: Uuid): List<ResultRow> =
        transaction { EventCaptureAcknowledgmentTable.selectAll().where { EventCaptureAcknowledgmentTable.eventId eq eventId }.toList() }

    fun reset() {
        instance(enabled = false, noticeVersion = null)
        AgreementTextTestState.reset()
    }
}

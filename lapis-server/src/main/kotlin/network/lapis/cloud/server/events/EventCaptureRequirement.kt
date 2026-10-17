package network.lapis.cloud.server.events

import network.lapis.cloud.server.agreementtexts.AgreementTextResolver
import network.lapis.cloud.server.agreementtexts.CurrentAgreementText
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.rpc.EventCaptureSettingsStore
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.EncounterConsentLayout
import network.lapis.cloud.shared.domain.EventCaptureAcknowledgmentInput
import network.lapis.cloud.shared.domain.EventCaptureNoticeDto
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.rpc.EventCaptureNoticeOutdatedException
import network.lapis.cloud.shared.rpc.EventCaptureNoticeRequiredException
import network.lapis.cloud.shared.rpc.EventCaptureTextMissingException
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * V1.9.156 -- THE answer to "does entering this event room require the capture consent", and the check of that consent. Shared by the member
 * join, the join of a person without an account and the state the client reads ([EventCaptureNoticeDto]). Must run inside the caller's
 * `transaction {}` where it reads the event row or the settings (the text resolver opens its own).
 *
 * The rule is deliberately the same one `EventConferenceGuard.requireEventCaptureAllowed` applies at the START of a recording / stream: the
 * instance switch is on with the CURRENT operator notice, the event is released and it is a Lapis conference. Whoever is let into such a room has
 * acknowledged the CURRENT consent text; nobody can be let in "without notice" -- if no consent text was published, nobody is let in at all.
 */
internal object EventCaptureRequirement {
    /** `true` iff entering [eventRow] requires the capture consent. */
    fun applies(eventRow: ResultRow?): Boolean =
        eventRow != null &&
            eventRow[EventTable.captureAllowed] &&
            eventRow[EventTable.onlineKind] == EventOnlineKind.LAPIS_CONFERENCE &&
            EventCaptureSettingsStore.load().startAllowed()

    /**
     * Checks the consent a participant submitted against the LIVE text and returns that text (its label and hash are what the proof stores).
     * No published text ([EventCaptureTextMissingException]) is refused before anything else: the built-in placeholder is never a basis for entry.
     */
    fun requireConsent(consent: EventCaptureAcknowledgmentInput?): CurrentAgreementText {
        val current = AgreementTextResolver.current(kind = AgreementTextKind.EVENT_REC)
        if (current.placeholder) throw EventCaptureTextMissingException()
        val given = consent ?: throw EventCaptureNoticeRequiredException()
        if (!AgreementTextResolver.matchesCurrent(
                kind = AgreementTextKind.EVENT_REC,
                version = given.consentVersion,
                sha256 = given.consentSha256,
            )
        ) {
            throw EventCaptureNoticeOutdatedException()
        }
        return current
    }

    /** The consent text as the participant is shown it: headline, key points, full text (hashed verbatim). */
    fun noticeDto(): EventCaptureNoticeDto = noticeDto(AgreementTextResolver.current(kind = AgreementTextKind.EVENT_REC))

    fun noticeDto(current: CurrentAgreementText): EventCaptureNoticeDto {
        val split = EncounterConsentLayout.splitOrNull(body = current.body)
        return EventCaptureNoticeDto(
            versionLabel = current.versionLabel,
            sha256 = current.sha256,
            headline = split?.headline ?: "",
            keyPoints = split?.keyPoints ?: emptyList(),
            fullText = current.body,
            placeholder = current.placeholder,
        )
    }
}

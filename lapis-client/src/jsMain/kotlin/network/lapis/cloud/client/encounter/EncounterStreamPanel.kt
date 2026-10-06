package network.lapis.cloud.client.encounter

import io.kvision.core.Container
import io.kvision.html.ButtonStyle
import io.kvision.html.Div
import io.kvision.html.Span
import io.kvision.html.div
import io.kvision.html.h2
import io.kvision.html.link
import io.kvision.html.span
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import network.lapis.cloud.client.ActionIcon
import network.lapis.cloud.client.AppScope
import network.lapis.cloud.client.LapisField
import network.lapis.cloud.client.Routes
import network.lapis.cloud.client.dataSection
import network.lapis.cloud.client.guarded
import network.lapis.cloud.client.lapisForm
import network.lapis.cloud.client.newActionButton
import network.lapis.cloud.client.notifySuccess
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.client.sanitizeUntrustedI18nText
import network.lapis.cloud.client.untrustedOptions
import network.lapis.cloud.shared.domain.ConferenceStreamAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceStreamDto
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetDto
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IConferenceStreamingService

private class StreamPanelData(
    val availability: ConferenceStreamAvailabilityDto,
    val targets: List<ConferenceStreamTargetDto>,
    val active: ConferenceStreamDto?,
)

/** A stream that still exists as a live thing (not ended or failed): it is what the badge and the stop button are about. */
private fun ConferenceStreamDto.isRunning(): Boolean = status != ConferenceStreamStatus.ENDED && status != ConferenceStreamStatus.FAILED

/**
 * V1.9.62 Begegnungsraum (B2) -- the "Übertragung" tab, for people who moderate: start and stop the transmission of the PULPIT to the
 * destinations the administrators configured. Hard rules, mirrored from the server (`ConferenceStreamingService`):
 *
 * - The layout is fixed to the pulpit ([ConferenceStreamLayout.SINGLE_PARTICIPANT]) and the picture is the pulpit person's: the page
 *   shows the fixed text "Nur Kanzel", there is NO layout choice. The congregation is never transmitted.
 * - With more than one pulpit person present the moderator picks whose picture goes out; with exactly one that person is chosen; with
 *   none the start button stays disabled ("Niemand ist auf der Kanzel.").
 * - This panel creates and activates NO destination, in particular no YouTube destination: destinations are an administrator's
 *   decision on their own screen (the "Übertragungsziele verwalten" link is shown to administrators only).
 * - Pause and resume are not part of B2 (CHANGELOG); only start and stop.
 *
 * The room itself shows the badge [EncounterLiveBadge] to EVERYBODY present (the server's transparency read allows it): whoever is in
 * the room may know that the pulpit is being transmitted.
 */
internal class EncounterStreamPanel(
    parent: Container,
    private val roomId: String,
    private val isAdmin: Boolean,
    private val pulpitPeople: () -> List<EncounterPresentDto>,
) {
    val root: Div = parent.div(className = "lapis-encounter-stream")
    private val section =
        root.dataSection<StreamPanelData>(
            isEmpty = { false },
            load = {
                guarded {
                    val service = rpcService<IConferenceStreamingService>()
                    val availability = service.getStreamingAvailability()
                    if (!availability.enabled) {
                        StreamPanelData(availability = availability, targets = emptyList(), active = null)
                    } else {
                        StreamPanelData(
                            availability = availability,
                            targets = service.listStreamTargets(),
                            active = service.getActiveStream(roomId).firstOrNull { it.isRunning() },
                        )
                    }
                }
            },
        ) { panel, data -> render(panel, data) }

    fun load() = section.reload()

    private fun render(
        panel: SimplePanel,
        data: StreamPanelData,
    ) {
        panel.h2(tr("Übertragung")) { addCssClass("h6") }
        when {
            !data.availability.enabled ->
                panel.div(tr("Übertragung ist auf dieser Instanz nicht eingerichtet."), className = "text-muted")
            data.active != null -> renderRunning(panel, data.active)
            data.targets.isEmpty() -> {
                panel.div(tr("Kein Übertragungsziel eingerichtet."), className = "text-muted")
                if (isAdmin) panel.link(tr("Übertragungsziele verwalten"), url = "#${Routes.CONFERENCE_STREAM_DESTINATIONS}")
            }
            else -> renderStartForm(panel, data)
        }
    }

    private fun renderRunning(
        panel: SimplePanel,
        stream: ConferenceStreamDto,
    ) {
        panel.div(tr("Live (nur Kanzel)"), className = "fw-bold")
        val stop = newActionButton(ActionIcon.BROADCAST, tr("Übertragung beenden"), ButtonStyle.OUTLINEDANGER)
        panel.add(stop)
        stop.onClick {
            stop.disabled = true
            AppScope.launch {
                val result = guarded { rpcService<IConferenceStreamingService>().stopStream(stream.id) }
                if (result != null) notifySuccess(tr("Die Übertragung wird beendet."))
                load()
            }
        }
    }

    private fun renderStartForm(
        panel: SimplePanel,
        data: StreamPanelData,
    ) {
        val form = panel.vPanel(spacing = 6).lapisForm()
        panel.div(tr("Nur Kanzel"), className = "text-muted small")
        val targetFields = mutableListOf<Pair<String, LapisField>>()
        data.targets.forEach { target ->
            targetFields +=
                target.id to
                form.checkField(label = sanitizeUntrustedI18nText(target.label))
        }
        val pulpit = pulpitPeople().filter { it.role == EncounterPresenceRole.PULPIT }
        val pulpitField =
            if (pulpit.size > 1) {
                form.searchableSelectField(
                    label = tr("Wessen Bild wird übertragen?"),
                    options = untrustedOptions(pulpit.map { it.memberId to it.displayName }),
                    value = pulpit.first().memberId,
                    required = true,
                )
            } else {
                null
            }
        if (pulpit.isEmpty()) form.panel.div(tr("Niemand ist auf der Kanzel."), className = "text-muted small")
        val start = newActionButton(ActionIcon.BROADCAST, tr("Übertragung starten"), ButtonStyle.PRIMARY)
        start.disabled = pulpit.isEmpty()
        form.buttons(primary = start)
        start.onClick {
            val chosen = targetFields.filter { it.second.value == "true" }.map { it.first }
            val speaker = pulpitField?.value ?: pulpit.singleOrNull()?.memberId
            if (chosen.isEmpty() || chosen.size > data.availability.maxDestinations || speaker == null) {
                form.showFormError(gettext("Bitte wählen Sie mindestens ein Ziel (höchstens %1).", data.availability.maxDestinations))
                return@onClick
            }
            form.submit(start) {
                val result =
                    guarded {
                        rpcService<IConferenceStreamingService>().startStream(
                            roomId = roomId,
                            destinationIds = chosen,
                            layout = ConferenceStreamLayout.SINGLE_PARTICIPANT,
                            latencyMode = ConferenceStreamLatencyMode.STANDARD,
                            participantIdentity = speaker,
                        )
                    }
                if (result != null) notifySuccess(tr("Die Übertragung wird gestartet."))
                load()
            }
        }
    }
}

/**
 * The "Live (nur Kanzel)" badge every person in the room sees while the pulpit is transmitted. Polled every 30 s by the room
 * (`getActiveStream` is the server's transparency read, allowed for all present). When streaming is not enabled on the instance the
 * server answers with a conflict: the polling then stops for good and the badge stays hidden.
 */
internal class EncounterLiveBadge(
    parent: Container,
    private val roomId: String,
) {
    val badge: Span = parent.span(tr("Live (nur Kanzel)"), className = "badge text-bg-danger lapis-encounter-live")

    /** Stays `false` after the first conflict answer: streaming is not available here, so there is nothing to poll. */
    var available: Boolean = true
        private set

    init {
        badge.hide()
    }

    suspend fun poll() {
        if (!available) return
        try {
            val running = rpcService<IConferenceStreamingService>().getActiveStream(roomId).any { it.isRunning() }
            if (running) badge.show() else badge.hide()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConflictException) {
            available = false
            badge.hide()
        } catch (e: Throwable) {
            // A failed poll keeps what the badge showed; the next tick tries again.
        }
    }

    val isShown: Boolean get() = badge.visible
}

package network.lapis.cloud.client

import io.kvision.form.check.CheckBox
import io.kvision.form.check.checkBox
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.EVENT_CAPTURE_NOTICE_VERSION
import network.lapis.cloud.shared.domain.EventCaptureChangeLock
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.rpc.EventCaptureChangeLockedException
import network.lapis.cloud.shared.rpc.IEventService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService

/** What the section needs from the server. [lock] explains in words why the release cannot be changed right now. */
internal data class EventCaptureFormData(
    val instanceEnabled: Boolean,
    val lock: EventCaptureChangeLock,
)

/** Injectable so a DOM test needs no server. [eventId] is `null` in the create form (a new event is never active). */
internal fun interface EventCaptureFormLoader {
    suspend fun load(eventId: String?): EventCaptureFormData?
}

internal val liveEventCaptureFormLoader =
    EventCaptureFormLoader { eventId ->
        if (eventId != null) {
            guarded { rpcService<IEventService>().getEventCaptureState(eventId) }
                ?.let { EventCaptureFormData(instanceEnabled = it.instanceEnabled, lock = it.changeLock) }
        } else {
            guarded { rpcService<IOrganizationSettingsService>().getOrganizationSettings() }
                ?.let {
                    EventCaptureFormData(
                        instanceEnabled =
                            it.eventCaptureEnabled &&
                                it.eventCaptureNoticeVersion == EVENT_CAPTURE_NOTICE_VERSION &&
                                !it.eventCaptureTextIsPlaceholder,
                        lock = EventCaptureChangeLock.NONE,
                    )
                }
        }
    }

/** The control of the "Aufzeichnung und Übertragung" release in the event form. */
internal class EventCaptureRefs(
    val check: CheckBox,
    val box: SimplePanel,
    /** The value the event had when the form was built (`false` for a new event). */
    val initial: Boolean,
    private val isShown: () -> Boolean,
) {
    /** What the person wants: only a SHOWN, enabled box counts; a hidden or locked box wants what the event already has. */
    fun wanted(): Boolean = if (isShown() && !check.disabled) check.value else initial

    /** `true` iff saving has to call `setEventCaptureAllowed`. */
    fun changed(): Boolean = wanted() != initial
}

/**
 * V1.9.156 -- the release of recording and live streaming in the event form: ONE box, only for an event with a Lapis video conference and only while the
 * instance switch is on (an event that is still released while the switch is off shows the box locked, with the reason in words). The release is saved with
 * its own call AFTER the event itself (`setEventCaptureAllowed`) -- see [applyEventCaptureRelease].
 *
 * The reason a box is locked is always text ([aria-describedby] points at it), never only a disabled control: the board must be able to see why.
 */
internal fun buildEventCaptureSection(
    panel: SimplePanel,
    prefill: EventDto?,
    online: EventOnlineKindRefs,
    loader: EventCaptureFormLoader = liveEventCaptureFormLoader,
): EventCaptureRefs {
    val box = panel.vPanel(spacing = 4)
    val initial = prefill?.captureAllowed == true
    val serial = ++captureSectionSerial
    val helpId = "lapis-event-capture-help-$serial"
    val reasonId = "lapis-event-capture-reason-$serial"
    val check = box.checkBox(value = initial, label = tr("Aufzeichnung und Übertragung erlauben"))
    check.input.setAttribute("aria-describedby", helpId)
    val help: Div =
        box.div(
            tr(
                "Dann können Bild, Ton und Name aller Personen im Online-Raum aufgezeichnet und live übertragen werden, nicht nur die der Vortragenden. Jede Person muss vor dem Betreten ausdrücklich zustimmen, und im Raum steht, solange aufgezeichnet oder übertragen wird. Gestartet wird nur durch die Moderation im Raum.",
            ),
        ) {
            addCssClasses("text-muted small")
            setAttribute("id", helpId)
        }
    val reason: Div =
        box.div("") {
            addCssClasses("text-warning small")
            setAttribute("id", reasonId)
        }
    var data: EventCaptureFormData? = null
    val refs = EventCaptureRefs(check = check, box = box, initial = initial) { box.visible }

    fun sync() {
        val isConference = online.kind() == EventOnlineKind.LAPIS_CONFERENCE
        val loaded = data
        // Before the answer is known the box stays hidden (nothing flickers in); an event that is released stays visible so it can be seen.
        val shown = isConference && loaded != null && (loaded.instanceEnabled || initial)
        box.visible = shown
        val lockText =
            when {
                loaded == null -> null
                !loaded.instanceEnabled -> tr("Die Aufzeichnung in Veranstaltungsräumen ist auf dieser Instanz nicht eingeschaltet.")
                loaded.lock == EventCaptureChangeLock.ROOM_ACTIVE ->
                    tr(
                        "Die Freigabe kann nicht mehr geändert werden: Der Online-Raum ist geöffnet oder es läuft eine Aufzeichnung oder Übertragung.",
                    )
                else -> null
            }
        check.disabled = lockText != null
        // The reason is what describes the box while it is locked; otherwise the explanation does.
        check.input.setAttribute("aria-describedby", if (lockText != null) reasonId else helpId)
        val reasonText = lockText ?: ""
        reason.content = reasonText
        help.visible = lockText == null
        reason.visible = lockText != null
        if (check.disabled && check.value != initial) check.value = initial
    }
    sync()
    online.kindRadio.subscribe { sync() }
    AppScope.launch {
        data = loader.load(prefill?.id)
        sync()
    }
    return refs
}

private var captureSectionSerial = 0

/**
 * Saves the release AFTER the event was saved. Returns `true` iff nothing is left to do (unchanged, or saved). A refusal (the room became active in the
 * meantime) gets its own toast; the event itself stays saved.
 */
internal suspend fun applyEventCaptureRelease(
    eventId: String,
    refs: EventCaptureRefs,
): Boolean {
    if (!refs.changed()) return true
    val wanted = refs.wanted()
    val saved =
        guarded {
            try {
                rpcService<IEventService>().setEventCaptureAllowed(eventId, wanted)
            } catch (e: EventCaptureChangeLockedException) {
                notifyError(
                    tr("Die Veranstaltung wurde gespeichert, die Freigabe der Aufzeichnung konnte aber nicht mehr geändert werden."),
                )
                null
            }
        }
    return saved != null
}

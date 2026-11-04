package network.lapis.cloud.client

import io.kvision.core.onEvent
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
import network.lapis.cloud.shared.rpc.EventCaptureStillCapturingException
import network.lapis.cloud.shared.rpc.IEventService
import network.lapis.cloud.shared.rpc.IOrganizationSettingsService

/**
 * What the section needs from the server. [lock] explains in words why the release cannot be changed right now. [roomOpen] (V1.9.280): the online window is
 * open or the room lives, so a release switched on now is asked for INSIDE the room.
 */
internal data class EventCaptureFormData(
    val instanceEnabled: Boolean,
    val lock: EventCaptureChangeLock,
    val roomOpen: Boolean = false,
)

/** Injectable so a DOM test needs no server. [eventId] is `null` in the create form (a new event is never active). */
internal fun interface EventCaptureFormLoader {
    suspend fun load(eventId: String?): EventCaptureFormData?
}

internal val liveEventCaptureFormLoader =
    EventCaptureFormLoader { eventId ->
        if (eventId != null) {
            guarded { rpcService<IEventService>().getEventCaptureState(eventId) }
                ?.let { EventCaptureFormData(instanceEnabled = it.instanceEnabled, lock = it.changeLock, roomOpen = it.roomOpen) }
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
    /** `true` in the create form: the release then travels WITH the event (`EventInput.captureAllowed`), not in a second call. */
    private val isNew: Boolean,
    private val isShown: () -> Boolean,
    /** `true` once the person changed the box themselves (the default tick of a new event does not count). */
    private val wasTouched: () -> Boolean = { true },
) {
    /** What the person wants: only a SHOWN, enabled box counts; a hidden or locked box wants what the event already has. */
    fun wanted(): Boolean = if (isShown() && !check.disabled) check.value else initial

    /** `true` iff saving an EXISTING event has to call `setEventCaptureAllowed` (a new event carries the release in its input, see [createValue]). */
    fun changed(): Boolean = !isNew && wanted() != initial

    /**
     * V1.9.280 -- for `EventInput.captureAllowed` when an event (or a series) is CREATED: the person's choice if the box was shown and active (`true` is
     * the default tick, `false` an explicit "no"), otherwise `null` = no choice (the server decides: never released for anything but a Lapis conference).
     * A box the person never touched is no choice either: the pre-ticked default travels as `null`, so the audit records `source = default`; an
     * untouched, unticked box (conference kind off / instance switch off) travels as an explicit `false`.
     */
    fun createValue(): Boolean? =
        when {
            !(isNew && isShown() && !check.disabled) -> null
            wasTouched() -> check.value
            check.value -> null
            else -> false
        }
}

/**
 * V1.9.156 -- the release of recording and live streaming in the event form: ONE box, only for an event with a Lapis video conference and only while the
 * instance switch is on (an event that is still released while the switch is off shows the box locked, with the reason in words). The release is saved with
 * its own call AFTER the event itself (`setEventCaptureAllowed`) -- see [applyEventCaptureRelease].
 *
 * The reason a box is locked is always text ([aria-describedby] points at it), never only a disabled control: the board must be able to see why.
 *
 * V1.9.280: a NEW event starts with the box ticked when the instance switch is on and the kind is a Lapis conference (until the person touches the box
 * themselves); an existing event shows its stored value. The release can be switched on at any time; only switching OFF while a recording / stream runs is
 * locked. The explanation is always visible; after the start an additional hint says that people already inside are asked.
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
    val lateId = "lapis-event-capture-late-$serial"
    val isNew = prefill == null
    var touched = false
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
    val late: Div =
        box.div(tr("Die Veranstaltung hat begonnen. Wer schon im Raum ist und noch nicht zugestimmt hat, wird sofort gefragt.")) {
            addCssClasses("text-muted small")
            setAttribute("id", lateId)
            visible = false
        }
    val reason: Div =
        box.div("") {
            addCssClasses("text-warning small")
            setAttribute("id", reasonId)
        }
    var data: EventCaptureFormData? = null
    val refs =
        EventCaptureRefs(check = check, box = box, initial = initial, isNew = isNew, isShown = { box.visible }, wasTouched = { touched })

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
                loaded.lock == EventCaptureChangeLock.CAPTURE_RUNNING ->
                    tr("Ausschalten ist erst möglich, wenn Aufzeichnung und Übertragung beendet sind.")
                else -> null
            }
        check.disabled = lockText != null
        // The reason is what describes the box while it is locked; otherwise the explanation (and, after the start, the hint) does.
        // A new event: ticked by default when the switch is on and the kind is a conference, as long as the person has not touched the box.
        if (isNew && !touched && loaded != null) check.value = isConference && loaded.instanceEnabled
        if (check.disabled && !isNew && check.value != initial) check.value = initial
        val showLate = loaded != null && loaded.roomOpen && !check.value && lockText == null
        late.visible = showLate
        check.input.setAttribute(
            "aria-describedby",
            when {
                lockText != null -> reasonId
                showLate -> "$helpId $lateId"
                else -> helpId
            },
        )
        reason.content = lockText ?: ""
        // The explanation stays visible always (V1.9.280); the reason is shown in addition when the box is locked.
        help.visible = true
        reason.visible = lockText != null
    }
    sync()
    online.kindRadio.subscribe { sync() }
    check.onEvent {
        change = {
            touched = true
            sync()
        }
    }
    AppScope.launch {
        data = loader.load(prefill?.id)
        sync()
    }
    return refs
}

private var captureSectionSerial = 0

/**
 * Saves the release of an EXISTING event AFTER the event was saved (a new event carries it in its input, [EventCaptureRefs.createValue]). Returns `true`
 * iff nothing is left to do (unchanged, or saved). A refusal (a recording / stream runs, so it cannot be switched off) gets its own toast; the event
 * itself stays saved.
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
            } catch (e: EventCaptureStillCapturingException) {
                notifyError(
                    tr(
                        "Die Veranstaltung wurde gespeichert. Ausschalten ist erst möglich, wenn Aufzeichnung und Übertragung beendet sind.",
                    ),
                )
                null
            } catch (e: EventCaptureChangeLockedException) {
                notifyError(
                    tr("Die Veranstaltung wurde gespeichert, die Freigabe der Aufzeichnung konnte aber nicht mehr geändert werden."),
                )
                null
            }
        }
    return saved != null
}

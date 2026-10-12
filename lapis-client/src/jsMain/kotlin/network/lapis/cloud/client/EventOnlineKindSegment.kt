package network.lapis.cloud.client

import io.kvision.form.check.CheckBox
import io.kvision.form.check.RadioGroup
import io.kvision.form.check.checkBox
import io.kvision.form.check.radioGroup
import io.kvision.form.select.Select
import io.kvision.form.select.select
import io.kvision.form.text.Text
import io.kvision.form.text.text
import io.kvision.html.Div
import io.kvision.html.div
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.panel.SimplePanel
import io.kvision.panel.vPanel
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.EventDto
import network.lapis.cloud.shared.domain.EventEncounterSpaceOptionDto
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.rpc.IConferenceService
import network.lapis.cloud.shared.rpc.IEventService

/** What the section needs from the server: the selectable encounter spaces and whether (and how big) conferencing is on. */
internal data class EventOnlineFormData(
    val spaces: List<EventEncounterSpaceOptionDto>,
    val conferencingEnabled: Boolean,
    val eventMaxParticipants: Int,
)

/** Injectable so a DOM test needs no server. */
internal fun interface EventOnlineFormLoader {
    suspend fun load(): EventOnlineFormData
}

internal val liveEventOnlineFormLoader =
    EventOnlineFormLoader {
        val spaces = guarded { rpcService<IEventService>().listEncounterSpacesForEvents() }.orEmpty()
        val availability = guarded { rpcService<IConferenceService>().getAvailability() }
        EventOnlineFormData(
            spaces = spaces,
            conferencingEnabled = availability?.enabled == true,
            eventMaxParticipants = availability?.eventMaxParticipants ?: 0,
        )
    }

/** The parts of the surrounding form that the warnings depend on -- read on every [EventOnlineKindRefs.refresh]. */
internal class EventOnlineFormContext(
    val visibility: () -> EventVisibility,
    val hasRegistrationDeadline: () -> Boolean,
    val capacity: () -> Int?,
)

/** The controls of the "Online-Teilnahme" section, as the event form reads them. */
internal class EventOnlineKindRefs(
    val kindRadio: RadioGroup,
    val onlineUrlInput: Text,
    val onlineUrlPublicCheck: CheckBox,
    val spaceSelect: Select,
    val refresh: () -> Unit,
) {
    fun kind(): EventOnlineKind =
        kindRadio.value?.let { chosen -> EventOnlineKind.entries.firstOrNull { it.name == chosen } } ?: EventOnlineKind.NONE

    fun encounterSpaceId(): String? = spaceSelect.value?.takeIf { it.isNotBlank() }
}

/**
 * Welle V1.9.113 -- the "Online-Teilnahme" section of the event form, replacing the former single "Online-Link" field: a group of four
 * radio options (native `radiogroup` semantics -- arrow keys move the choice, only the chosen option is a tab stop), exactly ONE
 * explanatory sentence for the chosen kind, and below it only what that kind needs:
 *
 * - **Vor Ort** -- nothing else.
 * - **Externer Link** -- the link field and the "show publicly" box (the pre-V1.9.113 behaviour, only here).
 * - **Videokonferenz** -- the warnings (seat ceiling, open-to-everybody-with-the-link) and nothing to fill in.
 * - **Begegnungsraum** -- the picker of the encounter spaces (with "nur Mitglieder" next to a members-only space).
 *
 * Every state is stated in words, never by colour or symbol alone. The warnings sit in a permanently mounted `role="status"` region so a
 * change is announced. The place stays untouched: an event with a place AND a Lapis conference is a hybrid event without any switch.
 */
internal fun buildEventOnlineKindSection(
    panel: SimplePanel,
    prefill: EventDto?,
    context: EventOnlineFormContext,
    loader: EventOnlineFormLoader = liveEventOnlineFormLoader,
): EventOnlineKindRefs {
    val kindRadio =
        panel.radioGroup(
            options = EventOnlineKind.entries.map { it.name to eventOnlineKindLabel(it) },
            value = (prefill?.onlineKind ?: EventOnlineKind.NONE).name,
            label = tr("Online-Teilnahme"),
        )
    kindRadio.setAttribute("role", "radiogroup")
    val sentence = panel.div("") { addCssClasses("text-muted small") }
    val unavailableNote =
        panel.div(eventOnlineUnavailableNote()) {
            addCssClasses("text-warning small")
            visible = false
        }

    val externalBox = panel.vPanel(spacing = 4)
    val onlineUrlInput = externalBox.text(label = tr("Online-Link")).apply { value = prefill?.onlineUrl }
    val onlineUrlPublicCheck =
        externalBox.checkBox(
            value = prefill?.onlineUrlPublic == true,
            label = tr("Online-Link öffentlich anzeigen (Webseite und Veranstaltungs-Feed)"),
        )
    externalBox.div(tr("Nur aktivieren, wenn der Link ohne Anmeldung geteilt werden darf.")) { addCssClasses("text-muted small") }

    fun syncOnlineUrlPublicCheck() {
        val usable = isHttpsLink(onlineUrlInput.value.orEmpty())
        onlineUrlPublicCheck.disabled = !usable
        if (!usable && onlineUrlPublicCheck.value) onlineUrlPublicCheck.value = false
    }
    syncOnlineUrlPublicCheck()
    onlineUrlInput.subscribe { syncOnlineUrlPublicCheck() }

    val spaceBox = panel.vPanel(spacing = 4)
    var spaces: List<EventEncounterSpaceOptionDto> = emptyList()
    val spaceSelect =
        spaceBox.select(
            options = spaceOptions(spaces, prefill),
            value = prefill?.encounterSpaceId ?: "",
            label = tr("Begegnungsraum"),
        )
    val warnings: Div = panel.div { setAttribute("role", "status") }
    var eventMaxParticipants = 0
    var conferencingKnownDisabled = false

    lateinit var refreshAll: () -> Unit

    refreshAll = {
        val kind = EventOnlineKind.entries.firstOrNull { it.name == kindRadio.value } ?: EventOnlineKind.NONE
        sentence.content = eventOnlineKindSentence(kind)
        externalBox.visible = kind == EventOnlineKind.EXTERNAL_LINK
        spaceBox.visible = kind == EventOnlineKind.LAPIS_ENCOUNTER
        unavailableNote.visible = conferencingKnownDisabled
        val chosenSpace = spaces.firstOrNull { it.id == spaceSelect.value }
        warnings.removeAll()
        eventOnlineWarnings(
            kind = kind,
            visibility = context.visibility(),
            hasRegistrationDeadline = context.hasRegistrationDeadline(),
            capacity = context.capacity(),
            eventMaxParticipants = eventMaxParticipants,
            spaceMembersOnly =
                chosenSpace?.membersOnly ?: prefill
                    ?.takeIf {
                        it.encounterSpaceId == spaceSelect.value
                    }?.encounterSpaceMembersOnly ?: false,
        ).forEach { text -> warnings.div(text) { addCssClasses("text-warning small") } }
    }
    kindRadio.subscribe { refreshAll() }
    spaceSelect.subscribe { refreshAll() }
    refreshAll()

    AppScope.launch {
        val data = loader.load()
        spaces = data.spaces
        eventMaxParticipants = data.eventMaxParticipants
        conferencingKnownDisabled = !data.conferencingEnabled
        val chosen = spaceSelect.value
        spaceSelect.options = spaceOptions(spaces, prefill)
        spaceSelect.value = chosen
        refreshAll()
    }
    return EventOnlineKindRefs(
        kindRadio = kindRadio,
        onlineUrlInput = onlineUrlInput,
        onlineUrlPublicCheck = onlineUrlPublicCheck,
        spaceSelect = spaceSelect,
        refresh = { refreshAll() },
    )
}

/**
 * The picker's options: "bitte wählen", every selectable space (members-only ones marked with the words), and the space an existing event
 * already points to even when it is no longer in the list (archived since), so the select shows the true current choice.
 */
private fun spaceOptions(
    spaces: List<EventEncounterSpaceOptionDto>,
    prefill: EventDto?,
): List<Pair<String, String>> {
    val current = prefill?.encounterSpaceId
    val listed =
        spaces.map { space ->
            space.id to if (space.membersOnly) gettext("%1 (nur Mitglieder)", space.title) else space.title
        }
    val missing =
        if (current != null && spaces.none { it.id == current }) {
            listOf(current to gettext("%1 (nicht mehr verfügbar)", prefill.encounterSpaceTitle ?: gettext("Begegnungsraum")))
        } else {
            emptyList()
        }
    return listOf("" to tr("Bitte wählen")) + untrustedOptions(listed) + untrustedOptions(missing)
}

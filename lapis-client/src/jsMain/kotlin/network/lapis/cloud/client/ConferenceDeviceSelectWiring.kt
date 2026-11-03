package network.lapis.cloud.client

import io.kvision.form.select.Select
import kotlinx.coroutines.launch
import network.lapis.cloud.client.livekit.ConferenceDeviceFailure
import network.lapis.cloud.client.livekit.ConferenceDeviceKind

/*
 * V1.9.272 -- the device-select wiring shared by the member call screen and the event guest call view.
 *
 * SECURITY / PRIVACY: this file persists nothing and tells nobody anything by itself. It has no storage access, no toast, no log and no
 * remote call; what a caller wants to do after a successful switch (the member screen remembers the id, the guest view forgets it) or after
 * a failure (the member screen shows a toast, the guest view a note) is handed in as a lambda.
 */

/**
 * A user-driven dropdown change calls [switch]. [isProgrammatic] filters PROGRAMMATIC `select.value =` assignments (pre-selection after an
 * enumeration, the revert after a failure here, the sync from `onActiveDeviceChanged`) -- without that guard every such assignment would
 * fire this very `subscribe {}` callback again (verified against the KVision 9.6.0 source) and cause a second, superfluous switch. No
 * success toast (Jobs' condition in the design review) -- only a visible revert on failure.
 *
 * [guarded] runs the switch and returns its failure (`null` = success); it is a parameter because the member screen runs it through
 * `AppState.guarded`, which is generic and therefore cannot be passed as a lambda itself.
 */
internal fun wireConferenceDeviceSelect(
    select: Select,
    kind: ConferenceDeviceKind,
    switch: suspend (ConferenceDeviceKind, String) -> ConferenceDeviceFailure?,
    activeId: (ConferenceDeviceKind) -> String?,
    isProgrammatic: () -> Boolean,
    setProgrammatic: (Boolean) -> Unit,
    guarded: suspend (suspend () -> ConferenceDeviceFailure?) -> ConferenceDeviceFailure?,
    onFailure: (String) -> Unit,
    onApplied: (deviceId: String) -> Unit,
) {
    select.subscribe { selected ->
        // V1.9.169 -- the "Erst nach Freigabe verfügbar" placeholder is not a choice
        if (isDevicePlaceholder(select)) return@subscribe
        // V1.4.19 -- besides the synchronous programmatic guard: empty/blank values (the loading placeholder `"" to …`, or
        // `enumerateDevices()` without permission) are never a user choice, even if KVision fires the subscriber asynchronously.
        val deviceId =
            conferenceDeviceSelectionToApply(applyingProgrammatic = isProgrammatic(), deviceId = selected)
                ?: return@subscribe
        AppScope.launch {
            val failure = guarded { switch(kind, deviceId) }
            if (failure != null) {
                onFailure(conferenceDeviceErrorMessage(kind, failure))
                setProgrammatic(true)
                select.value = activeId(kind)
                setProgrammatic(false)
            } else {
                onApplied(deviceId)
            }
        }
    }
}

package network.lapis.cloud.client.encounter

import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.lapis.cloud.client.AppScope
import network.lapis.cloud.client.livekit.ConferenceDeviceKind
import network.lapis.cloud.client.livekit.ConferenceDeviceOption
import network.lapis.cloud.client.sinkIdApiAvailable
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import kotlin.js.Promise

/**
 * V1.9.91 -- the speaker choice of the encounter room: the ONE place that lists the audio OUTPUT devices, sets the sink of an `<audio>`
 * element and listens to `devicechange`.
 *
 * Why not LiveKit: a congregation member's session has `publishEnabled = false`, so `LiveKitRoomSession.listDevices`/`switchDevice`
 * answer "nothing" for them. The room's sound comes out of the `<audio>` elements of the two media hosts (the pulpit's and the table's);
 * the output device is therefore set on those elements, the same way for an office holder and for a congregation member. Nothing here
 * asks for a permission: there is no `getUserMedia` and no `selectAudioOutput`, only the list the browser already gives.
 *
 * Privacy: a device id or a device name never leaves this class except as a return value; nothing is stored here (the picker stores
 * the id after a user's own choice), nothing is logged and no failure text is read.
 */
internal interface EncounterDeviceEnvironment {
    /** `true` only when `HTMLMediaElement.setSinkId` really exists (Safari has none). */
    fun sinkApiAvailable(): Boolean

    /** The browser's audio output devices; empty when the list is not available. Never throws. */
    suspend fun listOutputs(): List<ConferenceDeviceOption>

    /** Sets the output of [element]; `true` = done. Never throws: every failure is `false`. */
    suspend fun setSink(
        element: HTMLElement,
        sinkId: String,
    ): Boolean

    /** Calls [listener] whenever a device is plugged in or out; the returned function removes the listener again. */
    fun onDeviceChange(listener: () -> Unit): () -> Unit
}

/** The real environment: the browser. A `jsTest` hands over a fake instead. */
internal fun browserDeviceEnvironment(): EncounterDeviceEnvironment =
    object : EncounterDeviceEnvironment {
        override fun sinkApiAvailable(): Boolean = sinkIdApiAvailable()

        override suspend fun listOutputs(): List<ConferenceDeviceOption> {
            val devices = window.navigator.asDynamic().mediaDevices ?: return emptyList()
            if (devices.enumerateDevices == undefined) return emptyList()
            return try {
                val raw = (devices.enumerateDevices() as Promise<Array<dynamic>>).await()
                raw
                    .filter { it.kind.unsafeCast<String>() == ConferenceDeviceKind.SPEAKER.jsKind }
                    .map {
                        ConferenceDeviceOption(
                            deviceId = (it.deviceId as? String).orEmpty(),
                            rawLabel = (it.label as? String).orEmpty(),
                        )
                    }
            } catch (e: Throwable) {
                emptyList()
            }
        }

        override suspend fun setSink(
            element: HTMLElement,
            sinkId: String,
        ): Boolean {
            val target = element.asDynamic()
            if (target.setSinkId == undefined) return false
            return try {
                (target.setSinkId(sinkId) as Promise<dynamic>).await()
                true
            } catch (e: Throwable) {
                false
            }
        }

        override fun onDeviceChange(listener: () -> Unit): () -> Unit {
            val devices = window.navigator.asDynamic().mediaDevices ?: return {}
            if (devices.addEventListener == undefined) return {}
            val handler: (Event) -> Unit = { listener() }
            devices.addEventListener("devicechange", handler)
            return { devices.removeEventListener("devicechange", handler) }
        }
    }

/** The value of the "system default" entry of the speaker field; it becomes `setSinkId("")`. */
internal const val ENCOUNTER_SYSTEM_DEFAULT_OUTPUT = "lapis-system-default"

/** Chrome lists the system default twice: as an entry of its own and as the alias `default`. The alias is never offered as a device. */
private const val CHROME_DEFAULT_ALIAS = "default"

/** The usable output devices: no blank id (no permission yet) and no `default` alias. The order is kept. */
internal fun encounterUsableOutputs(raw: List<ConferenceDeviceOption>): List<ConferenceDeviceOption> =
    raw.filter { it.deviceId.isNotBlank() && it.deviceId != CHROME_DEFAULT_ALIAS }

internal enum class EncounterOutputResult { APPLIED, REVERTED_TO_PREVIOUS, FELL_BACK_TO_DEFAULT, UNSUPPORTED }

internal enum class EncounterOutputFallback { DEVICE_GONE }

/**
 * The speaker of the whole room: ONE sink id for every `<audio>` element of both hosts (pulpit and table), so the pulpit and the table
 * always come out of the same device. [elements] is evaluated late (the hosts are created after this object).
 */
internal class EncounterAudioOutput(
    private val env: EncounterDeviceEnvironment,
    private val elements: () -> List<HTMLElement>,
    private val onFellBack: (EncounterOutputFallback) -> Unit,
) {
    /** The chosen output device; `null` = the system's default. */
    var sinkId: String? = null
        private set

    private val mutex = Mutex()

    val available: Boolean get() = env.sinkApiAvailable()

    /** The usable output devices; empty without the sink API. */
    suspend fun listOutputs(): List<ConferenceDeviceOption> = if (!available) emptyList() else encounterUsableOutputs(env.listOutputs())

    /**
     * A new `<audio>` element (called by [EncounterMediaHost.add]) gets the chosen output. Without a choice, or without the API, nothing
     * happens. If the chosen device has gone away, the whole room falls back to the system default once.
     */
    fun apply(element: HTMLElement) {
        if (!available) return
        AppScope.launch {
            // Re-read under the lock, so a running select() has finished and this element ends on the same device as the room.
            val gone =
                mutex.withLock {
                    val wanted = sinkId ?: return@withLock false
                    !env.setSink(element, wanted)
                }
            if (gone) {
                select(null)
                onFellBack(EncounterOutputFallback.DEVICE_GONE)
            }
        }
    }

    /**
     * Sets the output of every element of both hosts. All or nothing: if one element refuses, every element goes back to the previous
     * device; if even that fails, every element goes to the system default. Two elements never end up on different devices.
     */
    suspend fun select(id: String?): EncounterOutputResult {
        if (!available) return EncounterOutputResult.UNSUPPORTED
        return mutex.withLock {
            val previous = sinkId
            if (applyToAll(id.orEmpty())) {
                sinkId = id
                EncounterOutputResult.APPLIED
            } else if (applyToAll(previous.orEmpty())) {
                EncounterOutputResult.REVERTED_TO_PREVIOUS
            } else {
                applyToAll("")
                sinkId = null
                EncounterOutputResult.FELL_BACK_TO_DEFAULT
            }
        }
    }

    /** Applies [target] to every element, also to elements that arrive while this runs. `true` = every element accepted it. */
    private suspend fun applyToAll(target: String): Boolean {
        val done = mutableListOf<HTMLElement>()
        var allOk = true
        while (true) {
            val next = elements().filter { candidate -> done.none { it === candidate } }
            if (next.isEmpty()) break
            for (element in next) {
                done += element
                if (!env.setSink(element, target)) allOk = false
            }
        }
        return allOk
    }
}

package network.lapis.cloud.client

import io.kvision.form.select.Select
import network.lapis.cloud.client.livekit.ConferenceDeviceKind
import network.lapis.cloud.client.livekit.ConferenceDeviceOption
import network.lapis.cloud.client.livekit.conferenceDeviceEnumeration
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.169 review fixes -- the request decision, the browser provider with a stand-in for `getUserMedia`, the enumeration count, the placeholder and the refresh gate. */
class ConferenceDevicePermissionProviderTest {
    /** A fake `getUserMedia`: [script] maps (audio, video) to null (success) or an error name; counts stopped tracks. */
    private class FakeMedia(
        private val script: (Boolean, Boolean) -> String?,
    ) {
        val calls = mutableListOf<Pair<Boolean, Boolean>>()
        var stopped = 0

        fun acquire(
            audio: Boolean,
            video: Boolean,
        ): Promise<dynamic> {
            calls += audio to video
            val failure = script(audio, video)
            return if (failure == null) {
                val track = js("({})")
                track.stop = { stopped++ }
                val stream = js("({})")
                stream.getTracks = { arrayOf(track) }
                Promise.resolve(stream)
            } else {
                Promise.reject(Throwable(failure).also { it.asDynamic().name = failure })
            }
        }
    }

    private suspend fun requestWith(
        media: FakeMedia,
        audio: Boolean = true,
        video: Boolean = true,
    ) = BrowserDevicePermissionProvider(acquire = media::acquire).request(audio, video)

    @Test
    fun aSuccess_stopsEveryTrack_andOpensTheDevicesOnce() =
        formTest {
            val media = FakeMedia { _, _ -> null }
            assertEquals(DevicePermissionRequestResult.Allowed(true, true), requestWith(media))
            assertEquals(listOf(true to true), media.calls)
            assertEquals(1, media.stopped, "the track is stopped at once")
        }

    @Test
    fun aBlockedCamera_doesNotKeepTheMicrophoneFromBeingAllowed() =
        formTest {
            // NotAllowedError for every attempt that includes the camera, as after the person blocked the camera earlier
            val media = FakeMedia { _, video -> if (video) "NotAllowedError" else null }
            assertEquals(DevicePermissionRequestResult.Allowed(audio = true, video = false), requestWith(media))
            assertEquals(listOf(true to true, true to false, false to true), media.calls)
            assertEquals(1, media.stopped)
        }

    @Test
    fun aCameraHeldByAnotherApp_leavesTheMicrophoneAllowed_andAloneItIsFailedNotMissing() =
        formTest {
            val busyCamera = FakeMedia { _, video -> if (video) "NotReadableError" else null }
            assertEquals(DevicePermissionRequestResult.Allowed(true, false), requestWith(busyCamera))
            val busyBoth = FakeMedia { _, _ -> "NotReadableError" }
            assertEquals(DevicePermissionRequestResult.Failed("NotReadableError"), requestWith(busyBoth))
            val aborted = FakeMedia { _, _ -> "AbortError" }
            assertEquals(DevicePermissionRequestResult.Failed("AbortError"), requestWith(aborted, audio = true, video = false))
        }

    @Test
    fun missingBlockedAndUnknown_areToldApart() =
        formTest {
            assertEquals(DevicePermissionRequestResult.NotFound, requestWith(FakeMedia { _, _ -> "NotFoundError" }))
            assertEquals(DevicePermissionRequestResult.Blocked, requestWith(FakeMedia { _, _ -> "NotAllowedError" }))
            assertEquals(
                DevicePermissionRequestResult.Blocked,
                requestWith(FakeMedia { audio, _ -> if (audio) "NotAllowedError" else "NotFoundError" }),
                "a block wins over a missing device",
            )
            assertEquals(
                DevicePermissionRequestResult.Allowed(false, true),
                requestWith(
                    FakeMedia {
                        _,
                        _,
                        ->
                        null
                    },
                    audio = false,
                    video = true,
                ),
            )
        }

    @Test
    fun theQuery_mapsTheBrowserState_andFallsBackToUnknown() =
        formTest {
            fun api(state: String): dynamic {
                val permissions = js("({})")
                permissions.query = { _: dynamic ->
                    val status: dynamic = js("({})")
                    status.state = state
                    Promise.resolve<dynamic>(status)
                }
                return permissions
            }

            suspend fun statusOf(permissions: dynamic) =
                BrowserDevicePermissionProvider(permissionsApi = { permissions }).query(ConferenceDeviceKind.CAMERA)
            assertEquals(DevicePermissionStatus.GRANTED, statusOf(api("granted")))
            assertEquals(DevicePermissionStatus.PROMPT, statusOf(api("prompt")))
            assertEquals(DevicePermissionStatus.DENIED, statusOf(api("denied")))
            assertEquals(DevicePermissionStatus.UNKNOWN, statusOf(api("other")))
            assertEquals(DevicePermissionStatus.UNKNOWN, statusOf(null))
            val throwing = js("({})")
            throwing.query = { _: dynamic -> throw IllegalStateException("no") }
            assertEquals(DevicePermissionStatus.UNKNOWN, statusOf(throwing))
        }

    @Test
    fun theEnumeration_countsEveryEntry_butOffersOnlyUsableOnes() {
        val blank = conferenceDeviceEnumeration(listOf(ConferenceDeviceOption("", ""), ConferenceDeviceOption(" ", "")))
        assertEquals(2, blank.rawCount)
        assertTrue(blank.usable.isEmpty())
        val mixed = conferenceDeviceEnumeration(listOf(ConferenceDeviceOption("a", "A"), ConferenceDeviceOption("", "")))
        assertEquals(2, mixed.rawCount)
        assertEquals(listOf("a"), mixed.usable.map { it.deviceId })
        val none = conferenceDeviceEnumeration(emptyList())
        assertEquals(0, none.rawCount)
    }

    @Test
    fun theRefreshGate_queuesOnceForARequest_andDropsAHotplugEvent() {
        val gate = DeviceRefreshGate()
        assertFalse(gate.shouldDefer(false), "idle: run")
        gate.start()
        assertTrue(gate.shouldDefer(true), "hotplug while running is deferred ...")
        assertFalse(gate.queued, "... and dropped")
        assertTrue(gate.shouldDefer(false))
        assertTrue(gate.shouldDefer(false))
        assertTrue(gate.queued)
        assertTrue(gate.finish(), "one more run is due")
        assertFalse(gate.running)
        assertFalse(gate.finish(), "only once")
    }

    @Test
    fun thePlaceholder_isMarkedOnTheNativeSelect_guardsTheValueWrite_andLeavesThePreference() =
        formTest {
            val key = "lapis-test-device-pref"
            try {
                kotlinx.browser.localStorage.setItem(key, "stored-id")
            } catch (e: Throwable) {
                // storage blocked: the rest still holds
            }
            mountedForm("device-placeholder") { root, element ->
                val select = Select(options = listOf("a" to "A"), value = "a")
                root.add(select)
                val guard = mutableListOf<Boolean>()
                applyDevicePlaceholder(select, "Erst nach Freigabe verfügbar") { guard += it }
                assertEquals(listOf(true, false), guard, "raised around the value write, then lowered")
                val native = select.input.getElement() as org.w3c.dom.HTMLElement
                assertEquals("true", native.getAttribute("aria-disabled"), "on the native <select>, not on the wrapper")
                assertTrue(isDevicePlaceholder(select))
                assertFalse(
                    (select.getElement() as org.w3c.dom.HTMLElement).getAttribute("aria-disabled") == "true",
                    "the wrapper is not marked",
                )
                assertEquals("", select.value.orEmpty())
                assertEquals(
                    "stored-id",
                    try {
                        kotlinx.browser.localStorage.getItem(key)
                    } catch (e: Throwable) {
                        "stored-id"
                    },
                    "the stored preference stays",
                )
                clearDevicePlaceholder(select)
                assertNull(native.getAttribute("aria-disabled"))
                assertFalse(isDevicePlaceholder(select))
                assertTrue(element().isConnected)
            }
            try {
                kotlinx.browser.localStorage.removeItem(key)
            } catch (e: Throwable) {
                // nothing to clean
            }
        }
}

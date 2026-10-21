package network.lapis.cloud.client

import network.lapis.cloud.client.livekit.ConferenceDeviceKind
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.169 -- the access block in a REAL mounted root, with a fake provider (no browser dialog is ever opened). */
class ConferenceDevicePermissionRowDomTest {
    private companion object {
        val stylesLoaded: Boolean =
            run {
                js("require('bootstrap/dist/css/bootstrap.min.css')")
                js("require('zzz-kvision-assets/css/kv-style.css')")
                js("require('./theme.css')")
                true
            }
    }

    private class FakeProvider(
        var result: DevicePermissionRequestResult = DevicePermissionRequestResult.Allowed(true, true),
        var status: DevicePermissionStatus = DevicePermissionStatus.PROMPT,
    ) : DevicePermissionProvider {
        var requests = 0
        var unregistered = 0
        val listeners = mutableListOf<() -> Unit>()

        override suspend fun query(kind: ConferenceDeviceKind) = status

        override suspend fun request(
            audio: Boolean,
            video: Boolean,
        ): DevicePermissionRequestResult {
            requests++
            return result
        }

        override fun onChange(
            kind: ConferenceDeviceKind,
            listener: () -> Unit,
        ): () -> Unit {
            listeners += listener
            return { unregistered++ }
        }
    }

    private fun HTMLElement.permissionButton() = assertNotNull(querySelector(".lapis-conference-device-permission button") as? HTMLElement)

    private fun HTMLElement.live() = assertNotNull(querySelector("[role=status].visually-hidden") as? HTMLElement)

    @Test
    fun theBlock_showsTheExplanationAndAPrimaryButton_andNeverOpensADeviceOnItsOwn(): Promise<Unit> =
        formTest {
            val provider = FakeProvider()
            mountedForm("device-permission-show") { root, element ->
                assertTrue(stylesLoaded)
                val row = ConferenceDevicePermissionRow(root, provider, {}, {})
                assertFalse(row.visible, "hidden until a state says access is missing")
                row.render(DevicePermissionState.NEEDS_ACCESS, DevicePermissionState.NEEDS_ACCESS)
                assertTrue(row.visible)
                val button = element().permissionButton()
                assertEquals("Zugriff auf Mikrofon und Kamera erlauben", button.textContent.orEmpty().trim())
                assertEquals("button", button.getAttribute("type"))
                assertTrue(button.classList.contains("btn-primary") && button.classList.contains("w-100"))
                assertEquals(0, provider.requests)
                row.render(DevicePermissionState.GRANTED, DevicePermissionState.GRANTED)
                assertFalse(row.visible)
                assertEquals(0, provider.requests, "no render ever requests access")
            }
        }

    @Test
    fun aFullSuccess_refreshes_announces_andMovesFocusToTheMicrophone(): Promise<Unit> =
        formTest {
            val provider = FakeProvider()
            var refreshed = 0
            var focused = 0
            mountedForm("device-permission-ok") { root, element ->
                lateinit var row: ConferenceDevicePermissionRow
                row =
                    ConferenceDevicePermissionRow(root, provider, {
                        refreshed++
                        row.render(DevicePermissionState.GRANTED, DevicePermissionState.GRANTED)
                    }, { focused++ })
                row.render(DevicePermissionState.NEEDS_ACCESS, DevicePermissionState.NEEDS_ACCESS)
                element().permissionButton().click()
                awaitUntil("announced") {
                    element()
                        .live()
                        .textContent
                        .orEmpty()
                        .isNotBlank()
                }
                assertEquals(
                    "Zugriff erlaubt. Sie können Ihre Geräte jetzt auswählen.",
                    element()
                        .live()
                        .textContent
                        .orEmpty()
                        .trim(),
                )
                assertEquals(1, provider.requests)
                assertEquals(1, refreshed)
                assertEquals(1, focused)
                assertFalse(row.visible)
            }
        }

    @Test
    fun aRefusal_showsTheBlockedSentence_keepsTheButton_andFocusStaysThere(): Promise<Unit> =
        formTest {
            val provider = FakeProvider(result = DevicePermissionRequestResult.Blocked)
            mountedForm("device-permission-blocked") { root, element ->
                val row = ConferenceDevicePermissionRow(root, provider, {}, {})
                row.render(DevicePermissionState.NEEDS_ACCESS, DevicePermissionState.NEEDS_ACCESS)
                val button = element().permissionButton()
                button.click()
                awaitUntil("blocked line") { row.blockedLine.visible }
                assertTrue(
                    row.blockedLine
                        .getElement()!!
                        .textContent
                        .orEmpty()
                        .startsWith("Der Browser hat den Zugriff blockiert."),
                )
                assertTrue(row.visible)
                assertEquals(1, provider.requests, "exactly one request")
                assertEquals(button, element().ownerDocument!!.activeElement)
                assertFalse(button.hasAttribute("disabled"))
            }
        }

    @Test
    fun aPartialGrant_keepsTheBlock_andSaysWhichKindWorks(): Promise<Unit> =
        formTest {
            val provider = FakeProvider(result = DevicePermissionRequestResult.Allowed(audio = true, video = false))
            mountedForm("device-permission-partial") { root, element ->
                val row = ConferenceDevicePermissionRow(root, provider, {}, {})
                row.render(DevicePermissionState.NEEDS_ACCESS, DevicePermissionState.NEEDS_ACCESS)
                element().permissionButton().click()
                awaitUntil("partial text") { row.blockedLine.visible }
                assertEquals(
                    "Nur das Mikrofon ist freigegeben.",
                    row.blockedLine
                        .getElement()!!
                        .textContent
                        .orEmpty()
                        .trim(),
                )
                assertTrue(row.visible)
            }
        }

    @Test
    fun nothingFound_isSaid(): Promise<Unit> =
        formTest {
            val provider = FakeProvider(result = DevicePermissionRequestResult.NotFound)
            mountedForm("device-permission-none") { root, element ->
                val row = ConferenceDevicePermissionRow(root, provider, {}, {})
                row.render(DevicePermissionState.NEEDS_ACCESS, DevicePermissionState.NEEDS_ACCESS)
                element().permissionButton().click()
                awaitUntil("none text") { row.blockedLine.visible }
                assertEquals(
                    "Es wurde weder ein Mikrofon noch eine Kamera gefunden.",
                    row.blockedLine
                        .getElement()!!
                        .textContent
                        .orEmpty()
                        .trim(),
                )
            }
        }

    @Test
    fun aDoubleClickWhileTheRequestRuns_isIgnored_andAriaBusyIsSet(): Promise<Unit> =
        formTest {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val provider =
                object : DevicePermissionProvider {
                    var requests = 0

                    override suspend fun query(kind: ConferenceDeviceKind) = DevicePermissionStatus.PROMPT

                    override suspend fun request(
                        audio: Boolean,
                        video: Boolean,
                    ): DevicePermissionRequestResult {
                        requests++
                        gate.await()
                        return DevicePermissionRequestResult.Blocked
                    }

                    override fun onChange(
                        kind: ConferenceDeviceKind,
                        listener: () -> Unit,
                    ): () -> Unit = {}
                }
            mountedForm("device-permission-double") { root, element ->
                val row = ConferenceDevicePermissionRow(root, provider, {}, {})
                row.render(DevicePermissionState.NEEDS_ACCESS, DevicePermissionState.NEEDS_ACCESS)
                val button = element().permissionButton()
                button.click()
                awaitUntil("busy") { button.getAttribute("aria-busy") == "true" }
                button.click()
                assertEquals("Zugriff auf Mikrofon und Kamera erlauben", button.textContent.orEmpty().trim())
                gate.complete(Unit)
                awaitUntil("idle") { button.getAttribute("aria-busy") == null }
                assertEquals(1, provider.requests)
            }
        }

    @Test
    fun bothKindsDenied_showTheBlockedSentenceWithoutAnyRequest_andDisposeUnregisters(): Promise<Unit> =
        formTest {
            val provider = FakeProvider(status = DevicePermissionStatus.DENIED)
            mountedForm("device-permission-denied") { root, _ ->
                var refreshes = 0
                val row = ConferenceDevicePermissionRow(root, provider, { refreshes++ }, {})
                row.render(DevicePermissionState.BLOCKED, DevicePermissionState.BLOCKED)
                assertTrue(row.blockedLine.visible)
                assertEquals(0, provider.requests)
                assertEquals(2, provider.listeners.size, "one listener per kind")
                provider.listeners.forEach { it() }
                awaitUntil("listener refreshed") { refreshes == 2 }
                assertEquals(0, provider.requests, "a permission change only refreshes, it never requests")
                row.dispose()
                assertEquals(2, provider.unregistered)
            }
        }

    @Test
    fun aFailedOpen_saysTheDeviceCouldNotBeOpened_notThatNoneExists(): Promise<Unit> =
        formTest {
            val provider = FakeProvider(result = DevicePermissionRequestResult.Failed("NotReadableError"))
            mountedForm("device-permission-failed") { root, element ->
                val row = ConferenceDevicePermissionRow(root, provider, {}, {})
                row.render(DevicePermissionState.NEEDS_ACCESS, DevicePermissionState.NEEDS_ACCESS)
                element().permissionButton().click()
                awaitUntil("failed text") { row.blockedLine.visible }
                val text =
                    row.blockedLine
                        .getElement()!!
                        .textContent
                        .orEmpty()
                assertTrue(text.startsWith("Ein Gerät konnte nicht geöffnet werden."), text)
            }
        }

    @Test
    fun theBlockedSentenceOfTheState_goesAwayWhenTheStateImproves_butAnAnswerToAClickStays(): Promise<Unit> =
        formTest {
            val provider = FakeProvider(result = DevicePermissionRequestResult.Allowed(audio = true, video = false))
            mountedForm("device-permission-stale") { root, element ->
                val row = ConferenceDevicePermissionRow(root, provider, {}, {})
                row.render(DevicePermissionState.BLOCKED, DevicePermissionState.BLOCKED)
                assertTrue(row.blockedLine.visible)
                row.render(DevicePermissionState.NEEDS_ACCESS, DevicePermissionState.BLOCKED)
                assertTrue(row.visible, "the block stays")
                assertFalse(row.blockedLine.visible, "the old 'blocked' text does not")
                element().permissionButton().click()
                awaitUntil("partial text") { row.blockedLine.visible }
                row.render(DevicePermissionState.GRANTED, DevicePermissionState.NEEDS_ACCESS)
                assertTrue(row.blockedLine.visible, "the answer to the click stays while the block is shown")
            }
        }
}

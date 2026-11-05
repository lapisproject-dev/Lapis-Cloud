package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import network.lapis.cloud.shared.domain.GuestScreenShareOutcome
import network.lapis.cloud.shared.domain.GuestScreenShareStateDto
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ANNA = "evg-bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
private const val BEN = "evg-cccccccc-cccc-4ccc-8ccc-cccccccccccc"

/**
 * V1.9.282 -- the "Gäste" section of the moderation panel in a real DOM: the three states in words, the button whose accessible name carries the
 * guest's name, the standing hint for recording and stream, the outcome sentences, and a hostile name staying text. Plus the controller against fakes.
 */
class ConferenceModerationGuestShareDomTest {
    private fun HTMLElement.rows(): List<HTMLElement> = allOf(".lapis-conference-guest-share-row")

    private suspend fun withSection(
        onToggle: (String, Boolean) -> Unit = { _, _ -> },
        block: suspend (ConferenceGuestShareSection, HTMLElement) -> Unit,
    ) {
        mountedForm("guest-share-section") { root, element ->
            val section = ConferenceGuestShareSection(parent = root, onToggle = onToggle)
            block(section, element())
        }
    }

    @Test
    fun noGuests_noSection(): Promise<Unit> =
        formTest {
            withSection { section, page ->
                section.render(emptyList(), null, emptySet(), recordingActive = true, streamActive = true)
                assertEquals(0, section.rows)
                assertTrue(page.rows().isEmpty())
                assertFalse(page.textContent.orEmpty().contains("Gäste"))
            }
        }

    @Test
    fun theThreeStates_areSaidInWords_andTheButtonCarriesTheName(): Promise<Unit> =
        formTest {
            val calls = mutableListOf<Pair<String, Boolean>>()
            withSection(onToggle = { id, allow -> calls += id to allow }) { section, page ->
                section.render(
                    guests = listOf(GuestShareGuest(ANNA, "Anna (Gast)"), GuestShareGuest(BEN, "Ben (Gast)")),
                    grantedIdentity = null,
                    sharingIdentities = emptySet(),
                    recordingActive = false,
                    streamActive = false,
                )
                awaitUntil("two rows") { page.rows().size == 2 }
                assertTrue(page.rows().all { it.textContent.orEmpty().contains("Darf den Bildschirm nicht teilen") })
                val allow = page.rows()[0].allOf("button").single()
                assertEquals("Bildschirm teilen erlauben", allow.textContent?.trim())
                assertEquals("Bildschirm teilen erlauben für Anna (Gast)", allow.getAttribute("aria-label"))
                allow.click()
                assertEquals(listOf(ANNA to true), calls)

                section.render(
                    listOf(GuestShareGuest(ANNA, "Anna (Gast)")),
                    ANNA,
                    emptySet(),
                    recordingActive = false,
                    streamActive = false,
                )
                awaitUntil("allowed") {
                    page
                        .rows()
                        .singleOrNull()
                        ?.textContent
                        .orEmpty()
                        .contains("Darf den Bildschirm teilen")
                }
                val revoke =
                    page
                        .rows()
                        .single()
                        .allOf("button")
                        .single()
                assertEquals("Freigabe entziehen", revoke.textContent?.trim())
                assertEquals("Freigabe entziehen für Anna (Gast)", revoke.getAttribute("aria-label"))
                revoke.click()
                assertEquals(listOf(ANNA to true, ANNA to false), calls)

                section.render(
                    listOf(GuestShareGuest(ANNA, "Anna (Gast)")),
                    ANNA,
                    setOf(ANNA),
                    recordingActive = false,
                    streamActive = false,
                )
                awaitUntil("sharing") {
                    page
                        .rows()
                        .singleOrNull()
                        ?.textContent
                        .orEmpty()
                        .contains("Teilt gerade den Bildschirm")
                }
            }
        }

    @Test
    fun theStandingHint_namesRecordingStreamOrBoth(): Promise<Unit> =
        formTest {
            withSection { section, page ->
                val guests = listOf(GuestShareGuest(ANNA, "Anna (Gast)"))
                section.render(guests, null, emptySet(), recordingActive = false, streamActive = false)
                awaitUntil("row") { page.rows().size == 1 }
                assertFalse(page.textContent.orEmpty().contains("läuft"))
                section.render(guests, null, emptySet(), recordingActive = true, streamActive = false)
                awaitUntil("recording hint") { page.textContent.orEmpty().contains("Die Aufzeichnung läuft") }
                section.render(guests, null, emptySet(), recordingActive = false, streamActive = true)
                awaitUntil("stream hint") { page.textContent.orEmpty().contains("Der Livestream läuft") }
                assertFalse(page.textContent.orEmpty().contains("Die Aufzeichnung läuft"))
                section.render(guests, null, emptySet(), recordingActive = true, streamActive = true)
                awaitUntil("both") { page.textContent.orEmpty().contains("Aufzeichnung und Livestream laufen") }
            }
        }

    @Test
    fun aHostileName_staysText(): Promise<Unit> =
        formTest {
            withSection { section, page ->
                section.render(listOf(GuestShareGuest(ANNA, "<script>alert(1)</script><b>x</b>")), null, emptySet(), false, false)
                awaitUntil("row") { page.rows().size == 1 }
                assertNull(page.querySelector("script"))
                assertNull(page.querySelector("b"))
                assertTrue(
                    page
                        .rows()
                        .single()
                        .textContent
                        .orEmpty()
                        .contains("<script>alert(1)</script><b>x</b>"),
                )
            }
        }

    @Test
    fun theController_readsTheServer_actsOnce_andTellsTheOutcome(): Promise<Unit> =
        formTest {
            withSection { section, page ->
                val scope = CoroutineScope(SupervisorJob())
                var state = GuestScreenShareStateDto(grantedIdentity = null, sharing = false)
                val sets = mutableListOf<Pair<String, Boolean>>()
                val notices = mutableListOf<GuestShareNoticeLine>()
                var outcome: GuestScreenShareOutcome? = GuestScreenShareOutcome.GRANTED
                val controller =
                    GuestShareController(
                        scope = scope,
                        section = section,
                        loadState = { state },
                        setShare = { id, allow ->
                            sets += id to allow
                            if (outcome == GuestScreenShareOutcome.GRANTED) state = GuestScreenShareStateDto(id, false)
                            outcome
                        },
                        guests = { listOf(GuestShareGuest(ANNA, "Anna (Gast)"), GuestShareGuest(BEN, "Ben (Gast)")) },
                        sharingIdentities = { emptySet() },
                        recordingActive = { false },
                        streamActive = { false },
                        notify = { notices += it },
                        failed = {},
                        debounceMs = 10,
                    )
                try {
                    controller.refreshNow()
                    awaitUntil("rows") { page.rows().size == 2 }
                    controller.act(ANNA, true)
                    awaitUntil("granted") {
                        page
                            .rows()
                            .firstOrNull()
                            ?.textContent
                            .orEmpty()
                            .contains("Darf den Bildschirm teilen")
                    }
                    assertEquals(listOf(ANNA to true), sets)
                    assertEquals("Der Gast darf jetzt den Bildschirm teilen.", notices.single().text)
                    assertFalse(notices.single().error)
                    // the server answers TAKEN for the second guest: an error sentence, the state is read again
                    outcome = GuestScreenShareOutcome.TAKEN
                    controller.act(BEN, true)
                    awaitUntil("taken") { notices.size == 2 }
                    assertTrue(notices[1].error)
                    assertTrue(notices[1].text.contains("nur ein Gast"))
                    // a failed call (null) says nothing itself (the guarded wrapper already showed the error)
                    outcome = null
                    controller.act(BEN, true)
                    awaitUntil("failed call done") { sets.size == 3 }
                    assertEquals(2, notices.size)
                    // a roster change redraws at once and reads the server once the burst is over
                    state = GuestScreenShareStateDto(null, false)
                    controller.onRoomChanged()
                    awaitUntil("the server state was re-read") { page.rows().all { it.textContent.orEmpty().contains("nicht teilen") } }
                    assertNotNull(page)
                } finally {
                    controller.dispose()
                }
            }
        }
}

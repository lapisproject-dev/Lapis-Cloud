package network.lapis.cloud.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.MemberPhotoRules
import network.lapis.cloud.shared.domain.MemberPhotoUploadError
import network.lapis.cloud.shared.domain.MemberPhotoVisibility
import network.lapis.cloud.shared.domain.OwnMemberPhotoDto
import network.lapis.cloud.shared.rpc.MemberPhotoConsentOutdatedException
import org.khronos.webgl.Uint8Array
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FilePropertyBag
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CURRENT = MemberPhotoRules.CONSENT_TEXT_VERSION

private fun dto(
    hasPhoto: Boolean,
    visibility: MemberPhotoVisibility = MemberPhotoVisibility.PRIVATE,
): OwnMemberPhotoDto =
    OwnMemberPhotoDto(
        hasPhoto = hasPhoto,
        visibility = visibility,
        widthPx = if (hasPhoto) 800 else null,
        heightPx = if (hasPhoto) 800 else null,
        uploadedAt = if (hasPhoto) LocalDateTime(2026, 9, 30, 12, 0) else null,
        previewVersion = if (hasPhoto) "abc12345" else null,
        publicUrl = if (visibility == MemberPhotoVisibility.PUBLIC) "https://lapis.example.org/public/member-photos/TOKEN" else null,
        requiredConsentTextVersion = CURRENT,
    )

private class FakeRpc(
    var state: OwnMemberPhotoDto,
) : MemberPhotoRpc {
    val calls = mutableListOf<String>()
    var failNextVisibilityWith: Throwable? = null

    override suspend fun getOwnPhoto(): OwnMemberPhotoDto {
        calls += "get"
        return state
    }

    override suspend fun setOwnPhotoVisibility(
        visibility: MemberPhotoVisibility,
        consentTextVersion: String?,
    ): OwnMemberPhotoDto {
        calls += "set:$visibility:$consentTextVersion"
        failNextVisibilityWith?.let {
            failNextVisibilityWith = null
            throw it
        }
        state = dto(hasPhoto = state.hasPhoto, visibility = visibility)
        return state
    }

    override suspend fun deleteOwnPhoto(): OwnMemberPhotoDto {
        calls += "delete"
        state = dto(hasPhoto = false)
        return state
    }
}

private class ConfirmProbe {
    var title: String? = null
    var message: String? = null
    var label: String? = null
    var onConfirm: (() -> Unit)? = null
    var opened = 0

    val hook: (String, String, String, () -> Unit) -> Unit = { t, m, l, c ->
        opened++
        title = t
        message = m
        label = l
        onConfirm = c
    }
}

private fun file(
    type: String,
    sizeBytes: Int = 16,
): File = File(arrayOf<dynamic>(Uint8Array(sizeBytes)), "photo", FilePropertyBag(type = type))

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- [MemberPhotoCard] in a REAL mounted KVision root: the consent-gated
 * switch (never optimistic, state only from the server answer), upload error mapping to fixed sentences,
 * client-side pre-checks, the locked UI during a request, the public link and the removal dialog.
 */
class MemberPhotoCardDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = formTest(block)

    private fun HTMLElement.checkbox(): HTMLInputElement = assertNotNull(querySelector("input[type=checkbox]") as? HTMLInputElement)

    private fun HTMLElement.isShown(): Boolean = getClientRects().length > 0

    private fun HTMLElement.previewBox(): HTMLElement = assertNotNull(querySelector(".member-photo-preview") as? HTMLElement)

    private fun HTMLElement.errorBox(): HTMLElement? = querySelector(".alert.alert-danger") as? HTMLElement

    private fun HTMLElement.statusLine(): HTMLElement? = querySelector("[role=status]") as? HTMLElement

    private suspend fun settle() = delay(60)

    @Test
    fun emptyState_showsIconHintAndADisabledSwitch(): Promise<Unit> =
        test {
            mountedForm("member-photo-empty") { root, element ->
                val card = MemberPhotoCard(parent = root, eligible = true, rpc = FakeRpc(dto(hasPhoto = false)))
                card.load()
                settle()
                val preview = element().previewBox()
                assertNotNull(preview.querySelector(".fa-user"), "an icon placeholder, not initials")
                assertEquals("true", preview.querySelector(".fa-user")?.getAttribute("aria-hidden"))
                assertNull(preview.querySelector("img"))
                assertTrue(element().textContent.orEmpty().contains("Noch kein Foto hinterlegt."))
                assertTrue(card.publicSwitch.disabled, "no photo, nothing to publish")
                assertFalse(card.removeButton.getElement()?.isShown() ?: false, "nothing to remove")
            }
        }

    @Test
    fun storedPhoto_showsThePreviewWithAltAndCacheBusterVersion(): Promise<Unit> =
        test {
            mountedForm("member-photo-preview") { root, element ->
                val card = MemberPhotoCard(parent = root, eligible = true, rpc = FakeRpc(dto(hasPhoto = true)))
                card.load()
                settle()
                val img = assertNotNull(element().previewBox().querySelector("img"))
                assertEquals("/api/member-photo/own?v=abc12345", img.getAttribute("src"))
                assertEquals("Ihr Mitgliedsfoto", img.getAttribute("alt"))
                assertTrue(
                    element().textContent.orEmpty().contains("Ihr Foto ist privat. Andere Mitglieder und der Vorstand sehen es nicht."),
                )
                assertTrue(element().textContent.orEmpty().contains("Ein neues Foto ist zunächst privat."))
                assertFalse(card.publicSwitch.disabled)
            }
        }

    @Test
    fun switchingOn_opensTheConsentDialog_callsNothing_andStaysOffUntilConfirmed(): Promise<Unit> =
        test {
            mountedForm("member-photo-switch-on") { root, element ->
                val rpc = FakeRpc(dto(hasPhoto = true))
                val confirm = ConfirmProbe()
                val card = MemberPhotoCard(parent = root, eligible = true, rpc = rpc, confirm = confirm.hook)
                card.load()
                settle()

                element().checkbox().click()
                settle()
                assertEquals(1, confirm.opened)
                assertEquals("Foto veröffentlichen", resolvedAttributeText(assertNotNull(confirm.title)))
                assertEquals("Veröffentlichen", resolvedAttributeText(assertNotNull(confirm.label)))
                assertEquals(
                    MEMBER_PHOTO_CONSENT_TEXT,
                    resolvedAttributeText(assertNotNull(confirm.message)),
                    "the exact, versioned consent wording",
                )
                assertFalse(rpc.calls.any { it.startsWith("set:") }, "no server call before the confirmation")
                assertFalse(element().checkbox().checked, "the switch shows the SERVER state, not the click")

                // cancelling = never invoking onConfirm: still nothing
                settle()
                assertFalse(rpc.calls.any { it.startsWith("set:") })
            }
        }

    @Test
    fun confirming_publishesWithTheServerVersion_andTheStateComesFromTheAnswer(): Promise<Unit> =
        test {
            mountedForm("member-photo-confirm") { root, element ->
                val rpc = FakeRpc(dto(hasPhoto = true))
                val confirm = ConfirmProbe()
                val card = MemberPhotoCard(parent = root, eligible = true, rpc = rpc, confirm = confirm.hook)
                card.load()
                settle()
                element().checkbox().click()
                settle()

                assertNotNull(confirm.onConfirm).invoke()
                settle()
                assertEquals(listOf("get", "set:PUBLIC:$CURRENT"), rpc.calls)
                assertTrue(element().checkbox().checked)

                // the public link: opens in a new tab, no opener, the raw URL is not shown as text
                val link = assertNotNull(element().querySelector("a[target=_blank]") as? HTMLElement)
                assertEquals("https://lapis.example.org/public/member-photos/TOKEN", link.getAttribute("href"))
                assertEquals("noopener noreferrer", link.getAttribute("rel"))
                assertEquals("Öffentliche Ansicht öffnen", link.textContent.orEmpty().trim())
                assertFalse(element().textContent.orEmpty().contains("member-photos/TOKEN"), "the URL is not displayed as text")
            }
        }

    @Test
    fun switchingOff_needsNoDialog_callsThePrivateVisibility_andRemovesTheLink(): Promise<Unit> =
        test {
            mountedForm("member-photo-switch-off") { root, element ->
                val rpc = FakeRpc(dto(hasPhoto = true, visibility = MemberPhotoVisibility.PUBLIC))
                val confirm = ConfirmProbe()
                val card = MemberPhotoCard(parent = root, eligible = true, rpc = rpc, confirm = confirm.hook)
                card.load()
                settle()
                assertTrue(element().checkbox().checked)
                assertNotNull(element().querySelector("a[target=_blank]"))

                element().checkbox().click()
                settle()
                assertEquals(0, confirm.opened, "withdrawal is one click, no dialog")
                assertEquals(listOf("get", "set:PRIVATE:null"), rpc.calls)
                assertFalse(element().checkbox().checked)
                assertNull(element().querySelector("a[target=_blank]"))
            }
        }

    @Test
    fun aFailedPublish_leavesTheSwitchOff_andAnOutdatedConsentReloadsTheServerState(): Promise<Unit> =
        test {
            mountedForm("member-photo-failure") { root, element ->
                val rpc = FakeRpc(dto(hasPhoto = true))
                val confirm = ConfirmProbe()
                val card = MemberPhotoCard(parent = root, eligible = true, rpc = rpc, confirm = confirm.hook)
                card.load()
                settle()

                // a transport/other failure: state unchanged
                rpc.failNextVisibilityWith = IllegalStateException("boom")
                element().checkbox().click()
                settle()
                assertNotNull(confirm.onConfirm).invoke()
                settle()
                assertFalse(element().checkbox().checked)
                assertEquals(MemberPhotoVisibility.PRIVATE, rpc.state.visibility)

                // an outdated consent text: the card re-reads the server state (one more "get")
                val getsBefore = rpc.calls.count { it == "get" }
                rpc.failNextVisibilityWith = MemberPhotoConsentOutdatedException()
                element().checkbox().click()
                settle()
                assertNotNull(confirm.onConfirm).invoke()
                settle()
                assertEquals(getsBefore + 1, rpc.calls.count { it == "get" })
                assertFalse(element().checkbox().checked)
            }
        }

    @Test
    fun everyUploadErrorCode_becomesAFixedSentenceInAnAlert_neverServerText(): Promise<Unit> =
        test {
            val expectations =
                mapOf(
                    MemberPhotoUploadError.UNSUPPORTED_FORMAT to "Nur JPEG- oder PNG-Bilder sind erlaubt",
                    MemberPhotoUploadError.UNDECODABLE to "Nur JPEG- oder PNG-Bilder sind erlaubt",
                    MemberPhotoUploadError.FILE_TOO_LARGE to "Die Datei ist größer als 10 MB.",
                    MemberPhotoUploadError.TOO_SMALL to "Das Bild ist zu klein (mindestens 400 × 400 Pixel).",
                    MemberPhotoUploadError.RATE_LIMITED to "Zu viele Versuche. Bitte später erneut versuchen.",
                    MemberPhotoUploadError.DIMENSIONS_TOO_LARGE to "Das Bild ist zu groß (maximal 8000 Pixel Kantenlänge).",
                    MemberPhotoUploadError.NOT_ELIGIBLE to "Das Foto konnte nicht hochgeladen werden.",
                    MemberPhotoUploadError.INVALID_REQUEST to "Das Foto konnte nicht hochgeladen werden.",
                    MemberPhotoUploadError.BUSY to "Das Foto konnte nicht hochgeladen werden.",
                )
            expectations.forEach { (code, expected) ->
                mountedForm("member-photo-error-${code.name.lowercase()}") { root, element ->
                    val card =
                        MemberPhotoCard(
                            parent = root,
                            eligible = true,
                            rpc = FakeRpc(dto(hasPhoto = false)),
                            uploader = { _, _ -> MemberPhotoHttp.Result.Error(code) },
                        )
                    card.load()
                    card.handleUpload(file(type = "image/png"))
                    settle()
                    val box = assertNotNull(element().errorBox(), "an alert for $code")
                    assertTrue(box.isShown(), "visible for $code")
                    assertEquals("alert", box.getAttribute("role"))
                    assertTrue(box.textContent.orEmpty().contains(expected), "text for $code was '${box.textContent}'")
                }
            }
        }

    @Test
    fun clientPreChecks_rejectAGif_anEmptyType_andAnOversizedFile_withoutAnyUpload(): Promise<Unit> =
        test {
            mountedForm("member-photo-prechecks") { root, element ->
                var uploads = 0
                val card =
                    MemberPhotoCard(
                        parent = root,
                        eligible = true,
                        rpc = FakeRpc(dto(hasPhoto = false)),
                        uploader = { _, _ ->
                            uploads++
                            MemberPhotoHttp.Result.Ok
                        },
                    )
                card.load()

                card.handleUpload(file(type = "image/gif"))
                assertTrue(
                    element()
                        .errorBox()
                        ?.textContent
                        .orEmpty()
                        .contains("Nur JPEG- oder PNG-Bilder sind erlaubt"),
                )

                card.handleUpload(file(type = ""))
                assertTrue(
                    element()
                        .errorBox()
                        ?.textContent
                        .orEmpty()
                        .contains("Nur JPEG- oder PNG-Bilder sind erlaubt"),
                )

                card.handleUpload(file(type = "image/jpeg", sizeBytes = MemberPhotoRules.MAX_UPLOAD_BYTES.toInt() + 1))
                assertTrue(
                    element()
                        .errorBox()
                        ?.textContent
                        .orEmpty()
                        .contains("Die Datei ist größer als 10 MB."),
                )

                assertEquals(0, uploads, "no request for a file the server would refuse anyway")
            }
        }

    @Test
    fun duringAnUpload_everyControlIsLocked_andTheStatusLineIsAnnounced(): Promise<Unit> =
        test {
            mountedForm("member-photo-busy") { root, element ->
                val gate = CompletableDeferred<MemberPhotoHttp.Result>()
                val card =
                    MemberPhotoCard(
                        parent = root,
                        eligible = true,
                        rpc = FakeRpc(dto(hasPhoto = true)),
                        uploader = { _, _ -> gate.await() },
                    )
                card.load()
                settle()
                assertFalse(card.uploadButton.disabled)

                val job = CoroutineScope(SupervisorJob()).launch { card.handleUpload(file(type = "image/jpeg")) }
                settle()
                assertTrue(card.uploadButton.disabled)
                assertTrue(card.removeButton.disabled)
                assertTrue(card.publicSwitch.disabled)
                val status = assertNotNull(element().statusLine())
                assertTrue(status.isShown())
                assertTrue(status.textContent.orEmpty().contains("Foto wird hochgeladen"))

                gate.complete(MemberPhotoHttp.Result.Ok)
                job.join()
                settle()
                assertFalse(card.uploadButton.disabled)
                assertFalse(card.removeButton.disabled)
                assertFalse(status.isShown(), "the status line is gone again")
            }
        }

    @Test
    fun removing_goesThroughTheConfirmationDialog(): Promise<Unit> =
        test {
            mountedForm("member-photo-remove") { root, element ->
                val rpc = FakeRpc(dto(hasPhoto = true))
                val confirm = ConfirmProbe()
                val card = MemberPhotoCard(parent = root, eligible = true, rpc = rpc, confirm = confirm.hook)
                card.load()
                settle()

                card.removeButton.getElement()?.click()
                settle()
                assertEquals("Foto entfernen", resolvedAttributeText(assertNotNull(confirm.title)))
                assertEquals("Ihr Foto wird endgültig gelöscht.", resolvedAttributeText(assertNotNull(confirm.message)))
                assertFalse(rpc.calls.contains("delete"), "nothing deleted before the confirmation")

                assertNotNull(confirm.onConfirm).invoke()
                settle()
                assertTrue(rpc.calls.contains("delete"))
                assertNull(element().previewBox().querySelector("img"))
                assertTrue(card.publicSwitch.disabled)
            }
        }

    @Test
    fun aMemberWhoIsNotEligible_seesOnlyAHint_noUploadNoSwitch(): Promise<Unit> =
        test {
            mountedForm("member-photo-not-eligible") { root, element ->
                val card = MemberPhotoCard(parent = root, eligible = false, rpc = FakeRpc(dto(hasPhoto = false)))
                card.load()
                settle()
                assertTrue(element().textContent.orEmpty().contains("Nur Mitglieder können ein Foto hinterlegen."))
                val file = element().querySelector("input[type=file]") as? HTMLElement
                assertFalse(file?.isShown() ?: false, "no upload control")
                assertFalse((element().querySelector("input[type=checkbox]") as? HTMLElement)?.isShown() ?: false, "no switch")
                assertTrue(card.uploadButton.disabled)
            }
        }

    @Test
    fun uploadAndRemove_carryTheIconsOfTheirVerbs_andKeepTheirNames(): Promise<Unit> =
        test {
            mountedForm("member-photo-icons") { root, element ->
                val card = MemberPhotoCard(parent = root, eligible = true, rpc = FakeRpc(dto(hasPhoto = true)))
                card.load()
                settle()
                assertActionIcon(assertNotNull(card.uploadButton.getElement()), "Foto ersetzen", "fa-upload")
                assertActionIcon(assertNotNull(card.removeButton.getElement()), "Foto entfernen", "fa-trash")
                assertNotNull(element())
            }
        }
}

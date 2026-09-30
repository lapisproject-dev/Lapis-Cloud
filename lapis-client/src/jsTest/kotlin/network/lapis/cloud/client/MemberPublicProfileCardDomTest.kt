package network.lapis.cloud.client

import kotlinx.coroutines.delay
import network.lapis.cloud.shared.domain.MemberPublicBioRules
import network.lapis.cloud.shared.domain.OwnPublicProfileDto
import network.lapis.cloud.shared.domain.PublicListingPlace
import network.lapis.cloud.shared.rpc.MemberPublicBioConsentOutdatedException
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CURRENT = MemberPublicBioRules.CONSENT_TEXT_VERSION

private fun profile(
    eligible: Boolean = true,
    isBoardMember: Boolean = true,
    isPolitician: Boolean = false,
    displayName: String = "Vera Vorsitz",
    bio: String? = null,
    bioPublic: Boolean = false,
    outdated: Boolean = false,
    photoPublic: Boolean = false,
    roleLabel: String? = "Vorsitz",
): OwnPublicProfileDto =
    OwnPublicProfileDto(
        eligible = eligible,
        isBoardMember = isBoardMember,
        isPolitician = isPolitician,
        displayName = displayName,
        roleLabel = if (isBoardMember) roleLabel else null,
        office = null,
        bioText = bio,
        bioPublic = bioPublic,
        bioConsentOutdated = outdated,
        photoPublic = photoPublic,
        politicianListingEffective = false,
        publicOn = if (isBoardMember) listOf(PublicListingPlace.BOARD) else emptyList(),
        requiredConsentTextVersion = CURRENT,
    )

private class FakeProfileRpc(
    var state: OwnPublicProfileDto,
) : MemberPublicProfileRpc {
    val calls = mutableListOf<String>()
    var failNextVisibilityWith: Throwable? = null

    override suspend fun getOwnPublicProfile(): OwnPublicProfileDto {
        calls += "get"
        return state
    }

    override suspend fun saveOwnBio(text: String): OwnPublicProfileDto {
        calls += "save:$text"
        val trimmed = text.trim()
        state =
            if (trimmed.isEmpty()) {
                state.copy(bioText = null, bioPublic = false, bioConsentOutdated = false)
            } else {
                state.copy(bioText = trimmed)
            }
        return state
    }

    override suspend fun setOwnBioPublic(
        visible: Boolean,
        consentTextVersion: String?,
    ): OwnPublicProfileDto {
        calls += "public:$visible:$consentTextVersion"
        failNextVisibilityWith?.let {
            failNextVisibilityWith = null
            throw it
        }
        state = state.copy(bioPublic = visible, bioConsentOutdated = false)
        return state
    }
}

private class ProfileConfirmProbe {
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

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- [MemberPublicProfileCard] in a REAL mounted KVision root: hidden for
 * a member with nothing to show, the live code-point counter, save/delete, the consent-gated switch (never
 * optimistic, state only from the server answer), the outdated-wording reload, and a preview that shows
 * user text as text.
 */
class MemberPublicProfileCardDomTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = formTest(block)

    private fun HTMLElement.checkbox(): HTMLInputElement = assertNotNull(querySelector("input[type=checkbox]") as? HTMLInputElement)

    private fun HTMLElement.isShown(): Boolean = getClientRects().length > 0

    private fun HTMLElement.counterText(): String =
        assertNotNull(allOf(".small.text-muted, .small.text-danger").firstOrNull { it.textContent.orEmpty().contains(" / ") })
            .textContent
            .orEmpty()
            .trim()

    private suspend fun settle() = delay(60)

    private fun HTMLElement.previewCard(): HTMLElement = assertNotNull(querySelector(".lapis-public-card") as? HTMLElement)

    @Test
    fun aMemberWhoIsNeitherEligibleNorHoldsAText_seesNoCardAtAll(): Promise<Unit> =
        test {
            mountedForm("public-profile-hidden") { root, _ ->
                val card =
                    MemberPublicProfileCard(
                        parent = root,
                        rpc = FakeProfileRpc(profile(eligible = false, isBoardMember = false)),
                        loadListingState = { null },
                    )
                card.load()
                settle()
                assertFalse(card.root.getElement()?.isShown() ?: false, "no empty card for a member who cannot use the feature")
            }
        }

    @Test
    fun anEligibleBoardMember_seesAnEmptyEditor_aStatusLine_andASaveButtonThatWaitsForText(): Promise<Unit> =
        test {
            mountedForm("public-profile-empty") { root, element ->
                val card = MemberPublicProfileCard(parent = root, rpc = FakeProfileRpc(profile()), loadListingState = { null })
                card.load()
                settle()
                assertTrue(card.root.getElement()?.isShown() ?: false)
                val text = element().textContent.orEmpty()
                assertTrue(text.contains("Derzeit öffentlich auf: Vorstand"), "the name and role are already public: $text")
                assertEquals("0 / 500", element().counterText())
                assertTrue(card.saveButton.disabled, "nothing to save yet")
                assertTrue(card.publicSwitch.disabled, "nothing to publish without a stored text")
                assertFalse(card.deleteButton.getElement()?.isShown() ?: false, "nothing to delete")
            }
        }

    @Test
    fun theCounterCountsCodePoints_notUtf16Units_andTurnsRedOverTheLimit(): Promise<Unit> =
        test {
            mountedForm("public-profile-counter") { root, element ->
                val card = MemberPublicProfileCard(parent = root, rpc = FakeProfileRpc(profile()), loadListingState = { null })
                card.load()
                settle()
                element().typeInto("Kurzvorstellung", "😀😀😀") // three emojis = 6 UTF-16 units
                settle()
                assertEquals("3 / 500", element().counterText())
                assertFalse(card.saveButton.disabled, "an edit makes the save button available")

                element().typeInto("Kurzvorstellung", "😀".repeat(501))
                settle()
                assertEquals("501 / 500", element().counterText())
                val counter = assertNotNull(element().allOf(".text-danger").firstOrNull { it.textContent.orEmpty().contains("501 / 500") })
                assertTrue(counter.isShown())
            }
        }

    @Test
    fun saving_sendsTheText_rendersTheServerAnswer_andEnablesThePublishSwitch(): Promise<Unit> =
        test {
            mountedForm("public-profile-save") { root, element ->
                val rpc = FakeProfileRpc(profile())
                val card = MemberPublicProfileCard(parent = root, rpc = rpc, loadListingState = { null })
                card.load()
                settle()
                element().typeInto("Kurzvorstellung", "  Ich bin seit 2010 dabei.  ")
                settle()
                card.saveButton.getElement()?.click()
                settle()
                assertEquals("get", rpc.calls[0])
                assertEquals("save:  Ich bin seit 2010 dabei.  ", rpc.calls[1], "the raw text goes over the wire, the server trims")
                assertEquals("Ich bin seit 2010 dabei.", assertNotNull(rpc.state.bioText))
                assertFalse(card.publicSwitch.disabled, "a stored, saved text can be published")
                assertTrue(card.deleteButton.getElement()?.isShown() ?: false)
            }
        }

    @Test
    fun anUnsavedEdit_blocksTheSwitchWithAHint_becausePublishingRefersToTheSavedText(): Promise<Unit> =
        test {
            mountedForm("public-profile-dirty") { root, element ->
                val card =
                    MemberPublicProfileCard(parent = root, rpc = FakeProfileRpc(profile(bio = "Gespeichert")), loadListingState = { null })
                card.load()
                settle()
                assertFalse(card.publicSwitch.disabled)
                element().typeInto("Kurzvorstellung", "Gespeichert und ergaenzt")
                settle()
                assertTrue(card.publicSwitch.disabled)
                assertTrue(element().textContent.orEmpty().contains("Bitte zuerst speichern."))
            }
        }

    @Test
    fun aSilentRefresh_neverOverwritesAnUnsavedEdit(): Promise<Unit> =
        test {
            mountedForm("public-profile-keep-draft") { root, element ->
                val card =
                    MemberPublicProfileCard(parent = root, rpc = FakeProfileRpc(profile(bio = "Gespeichert")), loadListingState = { null })
                card.load()
                settle()
                element().typeInto("Kurzvorstellung", "Gespeichert und ergaenzt")
                settle()
                card.load()
                settle()
                assertTrue(card.bioField.value.contains("ergaenzt"))
                assertTrue(card.publicSwitch.disabled)
            }
        }

    @Test
    fun switchingOn_opensTheConsentDialog_callsNothing_andStaysOffUntilConfirmed(): Promise<Unit> =
        test {
            mountedForm("public-profile-switch-on") { root, element ->
                val rpc = FakeProfileRpc(profile(bio = "Mein Text"))
                val confirm = ProfileConfirmProbe()
                val card = MemberPublicProfileCard(parent = root, rpc = rpc, confirm = confirm.hook, loadListingState = { null })
                card.load()
                settle()

                element().checkbox().click()
                settle()
                assertEquals(1, confirm.opened)
                assertEquals("Kurzvorstellung veröffentlichen", resolvedAttributeText(assertNotNull(confirm.title)))
                assertEquals("Veröffentlichen", resolvedAttributeText(assertNotNull(confirm.label)))
                assertEquals(
                    MEMBER_PUBLIC_BIO_CONSENT_TEXT,
                    resolvedAttributeText(assertNotNull(confirm.message)),
                    "the exact, versioned consent wording",
                )
                assertFalse(rpc.calls.any { it.startsWith("public:") }, "no server call before the confirmation")
                assertFalse(element().checkbox().checked, "the switch shows the SERVER state, not the click")
            }
        }

    @Test
    fun confirming_publishesWithTheServerVersion_andTheStateComesFromTheAnswer(): Promise<Unit> =
        test {
            mountedForm("public-profile-confirm") { root, element ->
                val rpc = FakeProfileRpc(profile(bio = "Mein Text"))
                val confirm = ProfileConfirmProbe()
                val card = MemberPublicProfileCard(parent = root, rpc = rpc, confirm = confirm.hook, loadListingState = { null })
                card.load()
                settle()
                element().checkbox().click()
                settle()
                assertNotNull(confirm.onConfirm).invoke()
                settle()
                assertEquals(listOf("get", "public:true:$CURRENT"), rpc.calls)
                assertTrue(element().checkbox().checked)
            }
        }

    @Test
    fun switchingOff_needsNoDialog_andCallsTheWithdrawal(): Promise<Unit> =
        test {
            mountedForm("public-profile-switch-off") { root, element ->
                val rpc = FakeProfileRpc(profile(bio = "Mein Text", bioPublic = true))
                val confirm = ProfileConfirmProbe()
                val card = MemberPublicProfileCard(parent = root, rpc = rpc, confirm = confirm.hook, loadListingState = { null })
                card.load()
                settle()
                assertTrue(element().checkbox().checked)
                element().checkbox().click()
                settle()
                assertEquals(0, confirm.opened, "withdrawal is one click, no dialog")
                assertEquals(listOf("get", "public:false:null"), rpc.calls)
                assertFalse(element().checkbox().checked)
            }
        }

    @Test
    fun anOutdatedConsentOnTheServer_reloadsTheStateAndKeepsTheSwitchOff(): Promise<Unit> =
        test {
            mountedForm("public-profile-outdated") { root, element ->
                val rpc = FakeProfileRpc(profile(bio = "Mein Text"))
                val confirm = ProfileConfirmProbe()
                val card = MemberPublicProfileCard(parent = root, rpc = rpc, confirm = confirm.hook, loadListingState = { null })
                card.load()
                settle()
                val getsBefore = rpc.calls.count { it == "get" }
                rpc.failNextVisibilityWith = MemberPublicBioConsentOutdatedException()
                element().checkbox().click()
                settle()
                assertNotNull(confirm.onConfirm).invoke()
                settle()
                assertEquals(getsBefore + 1, rpc.calls.count { it == "get" })
                assertFalse(element().checkbox().checked)
            }
        }

    @Test
    fun aConsentUnderAnOldWording_showsTheHint_andTheSwitchIsOff(): Promise<Unit> =
        test {
            mountedForm("public-profile-old-wording") { root, element ->
                val card =
                    MemberPublicProfileCard(
                        parent = root,
                        rpc = FakeProfileRpc(profile(bio = "Text", bioPublic = false, outdated = true)),
                        loadListingState = { null },
                    )
                card.load()
                settle()
                assertTrue(element().textContent.orEmpty().contains("Der Hinweistext wurde aktualisiert. Bitte erneut bestätigen."))
                assertFalse(element().checkbox().checked)
            }
        }

    @Test
    fun deleting_goesThroughTheConfirmationDialog_andSendsAnEmptyText(): Promise<Unit> =
        test {
            mountedForm("public-profile-delete") { root, _ ->
                val rpc = FakeProfileRpc(profile(bio = "Weg damit", bioPublic = true))
                val confirm = ProfileConfirmProbe()
                val card = MemberPublicProfileCard(parent = root, rpc = rpc, confirm = confirm.hook, loadListingState = { null })
                card.load()
                settle()
                card.deleteButton.getElement()?.click()
                settle()
                assertEquals("Kurzvorstellung löschen", resolvedAttributeText(assertNotNull(confirm.title)))
                assertEquals("Ihre Kurzvorstellung wird endgültig gelöscht.", resolvedAttributeText(assertNotNull(confirm.message)))
                assertFalse(rpc.calls.any { it.startsWith("save:") }, "nothing deleted before the confirmation")
                assertNotNull(confirm.onConfirm).invoke()
                settle()
                assertEquals("save:", rpc.calls.last())
                assertNull(rpc.state.bioText)
                assertFalse(card.deleteButton.getElement()?.isShown() ?: false)
            }
        }

    @Test
    fun thePreview_showsUserTextAsText_neverAsMarkup_andReflectsTheEditor(): Promise<Unit> =
        test {
            mountedForm("public-profile-preview") { root, element ->
                val card =
                    MemberPublicProfileCard(
                        parent = root,
                        rpc = FakeProfileRpc(profile(displayName = "<i>Vera</i>")),
                        loadListingState = { null },
                    )
                card.load()
                settle()
                element().typeInto("Kurzvorstellung", "<b>fett</b> & \"zitat\"")
                settle()
                val preview = element().previewCard()
                val bio = assertNotNull(preview.querySelector(".lapis-public-card-bio") as? HTMLElement)
                assertEquals("<b>fett</b> & \"zitat\"", bio.textContent)
                assertNull(preview.querySelector("b"), "no element was created from the text")
                assertEquals("<i>Vera</i>", assertNotNull(preview.querySelector(".lapis-public-card-name")).textContent)
                assertNull(preview.querySelector("i"))
                assertEquals("Vorsitz", assertNotNull(preview.querySelector(".lapis-public-card-role")).textContent)
            }
        }

    @Test
    fun thePreviewShowsInitialsWithoutAPublicPhoto_andThePhotoWithOne(): Promise<Unit> =
        test {
            mountedForm("public-profile-avatar") { root, element ->
                val rpc = FakeProfileRpc(profile(displayName = "Anna Müller", photoPublic = false))
                val card = MemberPublicProfileCard(parent = root, rpc = rpc, loadListingState = { null })
                card.load()
                settle()
                assertEquals("AM", assertNotNull(element().previewCard().querySelector(".lapis-public-card-initials")).textContent)
                assertNull(element().previewCard().querySelector("img"))

                rpc.state = rpc.state.copy(photoPublic = true)
                card.load()
                settle()
                assertEquals("/api/member-photo/own", assertNotNull(element().previewCard().querySelector("img")).getAttribute("src"))
            }
        }

    @Test
    fun aNonPolitician_hasNoListingSection(): Promise<Unit> =
        test {
            mountedForm("public-profile-no-listing") { root, element ->
                val card = MemberPublicProfileCard(parent = root, rpc = FakeProfileRpc(profile()), loadListingState = { null })
                card.load()
                settle()
                assertFalse(element().textContent.orEmpty().contains("Öffentliche Politiker-Seite"))
            }
        }

    @Test
    fun initialsOf_isTheFirstLetterOfTheFirstAndTheLastWord(): Promise<Unit> =
        test {
            assertEquals("AM", initialsOf("Anna-Lena Müller"))
            assertEquals("HS", initialsOf("Hans Peter Schmidt"))
            assertEquals("Ö", initialsOf("Ölaf"))
            assertEquals("", initialsOf("12345"))
            assertEquals("", initialsOf("   "))
        }
}

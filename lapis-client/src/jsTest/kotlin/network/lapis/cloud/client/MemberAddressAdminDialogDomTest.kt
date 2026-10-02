package network.lapis.cloud.client

import io.kvision.panel.SimplePanel
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberAddressDataDto
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ConflictException
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val ADMIN_TODAY = LocalDate(2026, 10, 2)
private const val SECRET_STREET = "Q7ZX-Hauptstrasse 1"
private const val SECRET_NATIONALITY = "Q7ZX-deutsch"

private fun adminRow(
    name: String = "Dana Keller",
    anonymized: Boolean = false,
) = MemberAdminRowDto(
    id = "member-9",
    displayName = name,
    email = "dana@example.org",
    status = MemberStatus.ACTIVE,
    role = AccountRole.MEMBER,
    joinedAt = LocalDate(2026, 1, 1),
    anonymized = anonymized,
)

private fun addressData() =
    MemberAddressDataDto(
        memberId = "member-9",
        displayName = "Dana Keller",
        street = SECRET_STREET,
        postalCode = "38100",
        city = "Braunschweig",
        country = "DE",
        dateOfBirth = LocalDate(1980, 2, 3),
        nationality = SECRET_NATIONALITY,
        dateOfDeath = null,
    )

private class FakeAdminRpc : MemberAddressAdminRpc {
    var data = addressData()
    var loadCalls = 0
    val addressCalls = mutableListOf<List<String?>>()
    var updateFailure: Throwable? = null

    override suspend fun loadForAdministration(memberId: String): MemberAddressDataDto {
        loadCalls++
        return data
    }

    override suspend fun updateAddress(
        memberId: String,
        street: String?,
        postalCode: String?,
        city: String?,
        country: String?,
    ): MemberDto {
        addressCalls += listOf(memberId, street, postalCode, city, country)
        updateFailure?.let { throw it }
        data = data.copy(street = street, postalCode = postalCode, city = city, country = country)
        return memberDto()
    }

    override suspend fun updateBeneficialOwnerData(
        memberId: String,
        dateOfBirth: LocalDate?,
        nationality: String?,
    ): MemberDto {
        data = data.copy(dateOfBirth = dateOfBirth, nationality = nationality)
        return memberDto()
    }

    private fun memberDto() =
        MemberDto(
            id = data.memberId,
            displayName = data.displayName,
            email = "dana@example.org",
            status = MemberStatus.ACTIVE,
            joinedAt = LocalDate(2026, 1, 1),
            role = AccountRole.MEMBER,
            street = data.street,
            postalCode = data.postalCode,
            city = data.city,
            country = data.country,
            dateOfBirth = data.dateOfBirth,
            nationality = data.nationality,
            dateOfDeath = data.dateOfDeath,
        )
}

private class SaveConfirms : AdminActionConfirm {
    class Shown(
        val title: String,
        val message: String,
        val onConfirm: () -> Unit,
    )

    val shown = mutableListOf<Shown>()

    override fun show(
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
    ) {
        shown += Shown(title, message, onConfirm)
    }
}

/** V1.9.35 -- the board's two-step, audited address / GwG dialog in a real mounted KVision root. */
class MemberAddressAdminDialogDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "board-1",
            displayName = "Vorstand",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun HTMLElement.rosterButton(): HTMLElement? =
        allOf("button").firstOrNull { it.getAttribute("aria-label") == "Anschrift und GwG-Angaben bearbeiten" }

    private inline fun withDialog(
        id: String,
        rpc: FakeAdminRpc,
        row: MemberAdminRowDto = adminRow(),
        errors: MutableList<String> = mutableListOf(),
        confirms: SaveConfirms = SaveConfirms(),
        crossinline block: suspend () -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.BOARD))
            mountedForm(id) { root: SimplePanel, element ->
                renderMemberAddressAdminAction(root, row, rpc)
                // a dialog needs the real action button: open it exactly as a person does
                element().rosterButton()!!.click()
                block()
            }
        }

    @Test
    fun openingTheDialog_makesNoServerCall_untilAngabenAnzeigenIsClicked(): Promise<Unit> {
        val rpc = FakeAdminRpc()
        return withDialog("addr-admin-step1", rpc) {
            val modal = lastOpenModal()
            assertTrue(modal.textContent.orEmpty().contains("Ihr Abruf wird mit Ihrem Namen im Prüfprotokoll vermerkt."))
            assertFalse(modal.textContent.orEmpty().contains(SECRET_STREET))
            delay(120)
            assertEquals(0, rpc.loadCalls)
            // closing again leaves no trace either
            modal.buttonNamed("Schließen").click()
            delay(80)
            assertEquals(0, rpc.loadCalls)
        }
    }

    @Test
    fun angabenAnzeigen_readsExactlyOnce_andPrefillsTheForms_evenOnADoubleClick(): Promise<Unit> {
        val rpc = FakeAdminRpc()
        return withDialog("addr-admin-step2", rpc) {
            val modal = lastOpenModal()
            val show = modal.buttonNamed("Angaben anzeigen")
            show.click()
            show.click()
            awaitUntil("loaded") { lastOpenModal().textContent.orEmpty().contains("Anschrift speichern") }
            assertEquals(1, rpc.loadCalls)
            assertEquals(SECRET_STREET, (lastOpenModal().controlOf("Straße und Hausnummer") as HTMLInputElement).value)
            assertFalse(lastOpenModal().textContent.orEmpty().contains("Ihr Abruf wird mit Ihrem Namen"), "step 1 is gone")
        }
    }

    @Test
    fun saving_asksFirst_cancelWritesNothing_confirmWritesOnce_andRebuildsWithoutASecondRead(): Promise<Unit> {
        val rpc = FakeAdminRpc()
        val confirms = SaveConfirms()
        return formTest {
            AppState.setSession(session(AccountRole.BOARD))
            mountedForm("addr-admin-save") { root: SimplePanel, element ->
                val successes = mutableListOf<String>()
                openMemberAddressAdminDialog(adminRow(), rpc, { ADMIN_TODAY }, confirms, {}, { successes += it })
                lastOpenModal().buttonNamed("Angaben anzeigen").click()
                awaitUntil("loaded") { lastOpenModal().textContent.orEmpty().contains("Anschrift speichern") }
                lastOpenModal().typeInto("Ort", "  Wolfsburg  ")
                delay(60)
                lastOpenModal().buttonNamed("Anschrift speichern").click()
                val ask = confirms.shown.single()
                assertTrue(ask.message.contains("Dana Keller"))
                delay(60)
                assertTrue(rpc.addressCalls.isEmpty(), "cancel path: nothing written before confirming")
                ask.onConfirm()
                awaitUntil("written") { rpc.addressCalls.size == 1 }
                assertEquals(listOf<String?>("member-9", SECRET_STREET, "38100", "Wolfsburg", "DE"), rpc.addressCalls.single())
                awaitUntil("toast") { successes.isNotEmpty() }
                assertEquals(listOf("Gespeichert."), successes)
                delay(120)
                assertEquals(1, rpc.loadCalls, "rebuilt from the returned DTO, no second audited read")
                assertEquals("Wolfsburg", (lastOpenModal().controlOf("Ort") as HTMLInputElement).value)
                assertTrue(element().textContent != null)
            }
        }
    }

    @Test
    fun aRefusedWrite_reloadsExactlyOnce(): Promise<Unit> {
        val rpc = FakeAdminRpc().apply { updateFailure = ConflictException("x") }
        val confirms = SaveConfirms()
        return formTest {
            AppState.setSession(session(AccountRole.BOARD))
            mountedForm("addr-admin-conflict") { _, _ ->
                val errors = mutableListOf<String>()
                openMemberAddressAdminDialog(adminRow(), rpc, { ADMIN_TODAY }, confirms, { errors += it }, {})
                lastOpenModal().buttonNamed("Angaben anzeigen").click()
                awaitUntil("loaded") { lastOpenModal().textContent.orEmpty().contains("Anschrift speichern") }
                lastOpenModal().typeInto("Ort", "Wolfsburg")
                delay(60)
                lastOpenModal().buttonNamed("Anschrift speichern").click()
                confirms.shown.single().onConfirm()
                awaitUntil("reloaded") { rpc.loadCalls == 2 }
                assertEquals(1, errors.size)
                assertFalse(errors.single().contains("Wolfsburg"))
            }
        }
    }

    @Test
    fun aForgedMarkerInTheName_isSanitized_inTheTitleAndInTheConfirmation(): Promise<Unit> {
        val forged = "###KvI18nS###\u0001evil"
        val rpc = FakeAdminRpc()
        val confirms = SaveConfirms()
        return formTest {
            AppState.setSession(session(AccountRole.BOARD))
            mountedForm("addr-admin-forged") { _, _ ->
                openMemberAddressAdminDialog(adminRow(name = "Dana $forged"), rpc, { ADMIN_TODAY }, confirms, {}, {})
                assertFalse(lastOpenModal().textContent.orEmpty().contains("###KvI18nS###"))
                lastOpenModal().buttonNamed("Angaben anzeigen").click()
                awaitUntil("loaded") { lastOpenModal().textContent.orEmpty().contains("Anschrift speichern") }
                lastOpenModal().typeInto("Ort", "Wolfsburg")
                delay(60)
                lastOpenModal().buttonNamed("Anschrift speichern").click()
                assertFalse(
                    confirms.shown
                        .single()
                        .message
                        .contains("###KvI18nS###"),
                )
            }
        }
    }

    @Test
    fun closingTheDialog_leavesNoPersonalDataInTheDocument(): Promise<Unit> {
        val rpc = FakeAdminRpc()
        return withDialog("addr-admin-close", rpc) {
            lastOpenModal().buttonNamed("Angaben anzeigen").click()
            awaitUntil("loaded") { lastOpenModal().textContent.orEmpty().contains("Anschrift speichern") }
            lastOpenModal().buttonNamed("Schließen").click()
            delay(150)
            closeOpenModals(timeoutMs = 500)
            val inputs = document.querySelectorAll("input")
            val values = (0 until inputs.length).map { (inputs.item(it) as HTMLInputElement).value }
            assertFalse(values.any { it.contains("Q7ZX") }, "no input value of the dialog stays")
            assertFalse(
                document.body
                    ?.textContent
                    .orEmpty()
                    .contains("Q7ZX"),
            )
        }
    }

    @Test
    fun theRosterButton_isOfferedOnlyToBoardAndAdmin_andNotForAnAnonymizedMember(): Promise<Unit> =
        formTest {
            val rpc = FakeAdminRpc()
            listOf(AccountRole.TREASURER, AccountRole.MEMBER).forEachIndexed { i, role ->
                AppState.setSession(session(role))
                mountedForm("addr-admin-role-$i") { root, element ->
                    renderMemberAddressAdminAction(root, adminRow(), rpc)
                    assertEquals(null, element().rosterButton(), role.name)
                }
            }
            AppState.setSession(session(AccountRole.ADMIN))
            mountedForm("addr-admin-anon") { root, element ->
                renderMemberAddressAdminAction(root, adminRow(anonymized = true), rpc)
                assertEquals(null, element().rosterButton())
            }
            AppState.setSession(session(AccountRole.ADMIN))
            mountedForm("addr-admin-admin") { root, element ->
                renderMemberAddressAdminAction(root, adminRow(), rpc)
                assertTrue(element().rosterButton() != null)
            }
            assertEquals(0, rpc.loadCalls)
        }
}

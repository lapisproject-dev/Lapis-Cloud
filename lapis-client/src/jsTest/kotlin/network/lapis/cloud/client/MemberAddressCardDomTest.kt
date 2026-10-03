package network.lapis.cloud.client

import io.kvision.panel.SimplePanel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ConflictException
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val TODAY = LocalDate(2026, 10, 2)

private fun addressMember(
    street: String? = "Hauptstrasse 1",
    dateOfDeath: LocalDate? = null,
) = MemberDto(
    id = "member-1",
    displayName = "Dana Keller",
    email = "dana@example.org",
    status = MemberStatus.ACTIVE,
    joinedAt = LocalDate(2026, 1, 1),
    role = AccountRole.MEMBER,
    street = street,
    postalCode = "38100",
    city = "Braunschweig",
    country = "DE",
    dateOfBirth = LocalDate(1980, 2, 3),
    nationality = "deutsch",
    dateOfDeath = dateOfDeath,
)

private class FakeAddressRpc(
    var member: MemberDto,
) : MemberAddressRpc {
    var getCalls = 0
    val addressCalls = mutableListOf<List<String?>>()
    val gwgCalls = mutableListOf<List<String?>>()
    var failWith: Throwable? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun getCurrentMember(): MemberDto {
        getCalls++
        return member
    }

    override suspend fun updateAddress(
        memberId: String,
        street: String?,
        postalCode: String?,
        city: String?,
        country: String?,
    ): MemberDto {
        addressCalls += listOf(memberId, street, postalCode, city, country)
        gate?.await()
        failWith?.let { throw it }
        member = member.copy(street = street, postalCode = postalCode, city = city, country = country)
        return member
    }

    override suspend fun updateBeneficialOwnerData(
        memberId: String,
        dateOfBirth: LocalDate?,
        nationality: String?,
    ): MemberDto {
        gwgCalls += listOf(memberId, dateOfBirth?.toString(), nationality)
        gate?.await()
        failWith?.let { throw it }
        member = member.copy(dateOfBirth = dateOfBirth, nationality = nationality)
        return member
    }
}

/** Welle V1.9.33 -- [MemberAddressCard] in a real mounted KVision root. */
class MemberAddressCardDomTest {
    private val session =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.MEMBER,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun HTMLElement.disabledButton(text: String): Boolean = (buttonNamed(text) as HTMLButtonElement).disabled

    private fun HTMLElement.input(label: String): HTMLInputElement = controlOf(label) as HTMLInputElement

    private inline fun withCard(
        rpc: FakeAddressRpc,
        id: String,
        errors: MutableList<String> = mutableListOf(),
        successes: MutableList<String> = mutableListOf(),
        crossinline block: suspend (() -> HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session)
            mountedForm(id) { root, element ->
                renderMemberAddressSection(root, rpc, { TODAY }, { errors += it }, { successes += it })
                awaitUntil("card rendered") { element().textContent.orEmpty().contains("Anschrift speichern") }
                block(element)
            }
        }

    @Test
    fun fieldsArePrefilled_andBothButtonsStartDisabled(): Promise<Unit> =
        withCard(FakeAddressRpc(addressMember()), "addr-prefill") { el ->
            assertEquals("Hauptstrasse 1", el().input("Straße und Hausnummer").value)
            assertEquals("1980-02-03", el().input("Geburtsdatum").value)
            assertTrue(el().disabledButton("Anschrift speichern"))
            assertTrue(el().disabledButton("Angaben speichern"))
        }

    @Test
    fun savingTheAddress_callsOnlyUpdateAddress_withTrimmedValues_andReloads(): Promise<Unit> {
        val rpc = FakeAddressRpc(addressMember())
        return withCard(rpc, "addr-save") { el ->
            el().typeInto("Ort", "  Wolfsburg  ")
            delay(60)
            assertFalse(el().disabledButton("Anschrift speichern"))
            el().buttonNamed("Anschrift speichern").click()
            awaitUntil("reloaded") { rpc.getCalls == 2 }
            assertEquals(listOf(listOf<String?>("member-1", "Hauptstrasse 1", "38100", "Wolfsburg", "DE")), rpc.addressCalls)
            assertTrue(rpc.gwgCalls.isEmpty())
        }
    }

    @Test
    fun savingTheGwgData_callsOnlyUpdateBeneficialOwnerData(): Promise<Unit> {
        val rpc = FakeAddressRpc(addressMember())
        return withCard(rpc, "addr-gwg") { el ->
            el().typeInto("Staatsangehörigkeit", "französisch")
            delay(60)
            el().buttonNamed("Angaben speichern").click()
            awaitUntil("reloaded") { rpc.getCalls == 2 }
            assertEquals(listOf(listOf<String?>("member-1", "1980-02-03", "französisch")), rpc.gwgCalls)
            assertTrue(rpc.addressCalls.isEmpty())
        }
    }

    @Test
    fun anEmptyField_isSentAsNull(): Promise<Unit> {
        val rpc = FakeAddressRpc(addressMember())
        return withCard(rpc, "addr-null") { el ->
            el().typeInto("Straße und Hausnummer", "   ")
            delay(60)
            el().buttonNamed("Anschrift speichern").click()
            awaitUntil("reloaded") { rpc.getCalls == 2 }
            assertEquals(null, rpc.addressCalls.single()[1])
        }
    }

    @Test
    fun anOverlongValue_disablesTheButton_andShowsTheInlineMessage(): Promise<Unit> =
        withCard(FakeAddressRpc(addressMember()), "addr-long") { el ->
            el().typeInto("Straße und Hausnummer", "a".repeat(201))
            delay(60)
            assertTrue(el().disabledButton("Anschrift speichern"))
            assertTrue(el().shownErrors().any { it.contains("Höchstens 200 Zeichen.") }, el().shownErrors().toString())
        }

    @Test
    fun badBirthDates_areRejected_goodOnesPass(): Promise<Unit> =
        withCard(FakeAddressRpc(addressMember(dateOfDeath = LocalDate(2020, 1, 1))), "addr-dob") { el ->
            listOf(
                "2026-10-03" to "nicht in der Zukunft",
                "1899-12-31" to "01.01.1900",
                "2020-01-02" to "nicht nach dem Sterbedatum",
            ).forEach { (value, fragment) ->
                el().typeInto("Geburtsdatum", value)
                delay(60)
                assertTrue(el().disabledButton("Angaben speichern"), value)
                assertTrue(el().shownErrors().any { it.contains(fragment) }, "$value: ${el().shownErrors()}")
            }
            el().typeInto("Geburtsdatum", "1900-01-01")
            delay(60)
            assertFalse(el().disabledButton("Angaben speichern"))
        }

    @Test
    fun aConflict_reloads_showsAFixedMessage_andNeverTheValues(): Promise<Unit> {
        val rpc = FakeAddressRpc(addressMember()).apply { failWith = ConflictException("Invalid address data Geheimstrasse") }
        val errors = mutableListOf<String>()
        return withCard(rpc, "addr-conflict", errors) { el ->
            el().typeInto("Ort", "Geheimstadt")
            delay(60)
            el().buttonNamed("Anschrift speichern").click()
            awaitUntil("reloaded") { rpc.getCalls == 2 }
            assertEquals(1, errors.size)
            assertTrue(errors.single().contains("nicht gespeichert"))
            assertFalse(errors.single().contains("Geheim"))
        }
    }

    @Test
    fun aDoubleClick_causesOneCall(): Promise<Unit> {
        val gate = CompletableDeferred<Unit>()
        val rpc = FakeAddressRpc(addressMember()).apply { this.gate = gate }
        return withCard(rpc, "addr-double") { el ->
            el().typeInto("Ort", "Wolfsburg")
            delay(60)
            val button = el().buttonNamed("Anschrift speichern")
            button.click()
            button.click()
            delay(60)
            gate.complete(Unit)
            awaitUntil("reloaded") { rpc.getCalls == 2 }
            assertEquals(1, rpc.addressCalls.size)
        }
    }

    @Test
    fun aForgedI18nMarkerInAValue_isNotRenderedAsATranslation(): Promise<Unit> {
        val forged = "###KvI18nS###\u0001forged"
        return withCard(FakeAddressRpc(addressMember(street = forged)), "addr-forged") { el ->
            assertFalse(el().textContent.orEmpty().contains("forged"))
            assertEquals(forged, el().input("Straße und Hausnummer").value)
        }
    }

    @Test
    fun aGuestSession_getsNoCard(): Promise<Unit> =
        formTest {
            AppState.setSession(session.copy(isGuest = true))
            val rpc = FakeAddressRpc(addressMember())
            mountedForm("addr-guest") { root: SimplePanel, element ->
                renderMemberAddressSection(root, rpc, { TODAY })
                delay(80)
                assertEquals(0, rpc.getCalls)
                assertFalse(element().textContent.orEmpty().contains("Anschrift"))
            }
        }

    @Test
    fun bothSaveButtons_carryTheSaveIcon_andKeepTheirNames(): Promise<Unit> =
        withCard(FakeAddressRpc(addressMember()), "addr-icons") { el ->
            assertButtonIcon(el(), "Anschrift speichern", "fa-floppy-disk")
            assertButtonIcon(el(), "Angaben speichern", "fa-floppy-disk")
        }
}

package network.lapis.cloud.client

import io.kvision.panel.vPanel
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberSelectionDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.VolunteerAllowanceCategory
import network.lapis.cloud.shared.domain.VolunteerAllowanceDeclarationSource
import network.lapis.cloud.shared.domain.VolunteerAllowanceSelfDeclarationDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeDeclarationsRpc(
    var own: List<VolunteerAllowanceSelfDeclarationDto> = emptyList(),
    var others: List<VolunteerAllowanceSelfDeclarationDto> = emptyList(),
    var members: List<MemberSelectionDto> = emptyList(),
) : VolunteerAllowanceDeclarationsRpc {
    val declarationCalls = mutableListOf<Pair<String?, Int?>>()
    var memberCalls = 0
    var failOwn = false

    override suspend fun listDeclarations(
        memberId: String?,
        calendarYear: Int?,
    ): List<VolunteerAllowanceSelfDeclarationDto> {
        declarationCalls += memberId to calendarYear
        if (memberId == null && failOwn) throw ForbiddenException("secret-server-text")
        return if (memberId == null) own else others
    }

    override suspend fun listMembersForSelection(): List<MemberSelectionDto> {
        memberCalls++
        return members
    }
}

private fun declaration(
    year: Int,
    source: VolunteerAllowanceDeclarationSource,
    recordedBy: String = "Dana Keller",
    category: VolunteerAllowanceCategory = VolunteerAllowanceCategory.HONORARY,
    signedOn: LocalDate? = null,
) = VolunteerAllowanceSelfDeclarationDto(
    id = "d-$year-$source",
    memberId = "member-1",
    category = category,
    calendarYear = year,
    source = source,
    declaredAt = LocalDateTime(year, 3, 4, 10, 0),
    signedOn = signedOn,
    recordedByDisplayName = recordedBy,
)

/** Welle V1.9.34 -- the declarations overview: own list for everyone, other members only for BOARD/ADMIN, never an amount. */
class VolunteerAllowanceDeclarationsCardDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private inline fun withCard(
        id: String,
        rpc: FakeDeclarationsRpc,
        role: AccountRole = AccountRole.MEMBER,
        crossinline block: suspend (() -> HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session(role))
            mountedForm(id) { root, element ->
                root.vPanel { renderVolunteerAllowanceDeclarationsCard(this, rpc, thisYear = 2026) }
                block(element)
            }
        }

    @Test
    fun ownList_showsRowsWithColumns_andAGreyBadge(): Promise<Unit> {
        val rpc =
            FakeDeclarationsRpc(
                own =
                    listOf(
                        declaration(2026, VolunteerAllowanceDeclarationSource.IN_APP),
                        declaration(
                            2025,
                            VolunteerAllowanceDeclarationSource.ON_PAPER,
                            recordedBy = "Ole Voss",
                            category = VolunteerAllowanceCategory.INSTRUCTOR,
                            signedOn = LocalDate(2025, 2, 1),
                        ),
                    ),
            )
        return withCard("decl-own", rpc) { el ->
            awaitUntil("table") { el().allOf("tbody tr").size == 2 }
            val headers = el().allOf("th").map { it.textContent.orEmpty() }
            assertEquals(listOf("Jahr", "Art", "Abgegeben", "Unterschrieben am", "Erfasst von"), headers)
            val text = el().textContent.orEmpty()
            assertTrue(text.contains("In der App") && text.contains("Auf Papier"))
            assertTrue(text.contains("Übungsleiterpauschale") && text.contains("Ehrenamtspauschale"))
            assertTrue(text.contains("Ole Voss"))
            assertTrue(el().allOf(".badge").all { it.className.contains("text-secondary") }, "grey type badges")
            assertEquals(listOf<Pair<String?, Int?>>(null to null), rpc.declarationCalls)
        }
    }

    @Test
    fun noDeclaration_showsTheEmptyText(): Promise<Unit> =
        withCard("decl-empty", FakeDeclarationsRpc()) { el ->
            awaitUntil("empty text") { el().textContent.orEmpty().contains("Keine Erklärung erfasst.") }
        }

    @Test
    fun atTwoHundredEntries_theCapHintIsShown(): Promise<Unit> {
        val rpc =
            FakeDeclarationsRpc(
                own =
                    (1..VOLUNTEER_ALLOWANCE_DECLARATIONS_LIST_CAP).map {
                        declaration(2000 + it, VolunteerAllowanceDeclarationSource.IN_APP)
                    },
            )
        return withCard("decl-cap", rpc) { el ->
            awaitUntil("hint") { el().textContent.orEmpty().contains("höchstens 200 Einträge") }
        }
    }

    @Test
    fun aMember_getsNoBoardSection_andTheSelectionIsNeverLoaded(): Promise<Unit> {
        val rpc = FakeDeclarationsRpc(own = listOf(declaration(2026, VolunteerAllowanceDeclarationSource.IN_APP)))
        return withCard("decl-member", rpc) { el ->
            awaitUntil("rows") { el().allOf("tbody tr").size == 1 }
            assertFalse(el().textContent.orEmpty().contains("Erklärungen anderer Mitglieder"))
            delay(80)
            assertEquals(0, rpc.memberCalls)
        }
    }

    @Test
    fun aBoardMember_expandsToLoadMembers_andPickingOneQueriesThatMember_withTheYearFilter(): Promise<Unit> {
        val rpc =
            FakeDeclarationsRpc(
                others = listOf(declaration(2024, VolunteerAllowanceDeclarationSource.ON_PAPER, recordedBy = "###KvI18nS###Fake Name")),
                members =
                    listOf(
                        MemberSelectionDto("member-9", "###KvI18nS###Evil Member", MemberStatus.ACTIVE),
                        MemberSelectionDto("member-8", "Ole Voss", MemberStatus.ACTIVE),
                    ),
            )
        return withCard("decl-board", rpc, AccountRole.BOARD) { el ->
            awaitUntil("own load") { rpc.declarationCalls.isNotEmpty() }
            assertEquals(0, rpc.memberCalls, "members are loaded only on expand")
            el().buttonNamed("Erklärungen anderer Mitglieder ansehen").click()
            awaitUntil("members loaded") { rpc.memberCalls == 1 && el().textContent.orEmpty().contains("Bitte ein Mitglied wählen.") }
            assertEquals("true", el().buttonNamed("Erklärungen anderer Mitglieder ansehen").getAttribute("aria-expanded"))
            assertFalse(el().textContent.orEmpty().contains("###KvI18nS###"))
            el().chooseIn("Mitglied", "member-8")
            awaitUntil("member queried") { rpc.declarationCalls.contains("member-8" to null) }
            el().chooseIn("Jahr", "2025")
            awaitUntil("year filter sent") { rpc.declarationCalls.contains("member-8" to 2025) }
            awaitUntil("foreign row sanitized") { el().textContent.orEmpty().contains("Fake Name") }
            assertFalse(el().textContent.orEmpty().contains("###KvI18nS###"))
        }
    }

    @Test
    fun neverShowsAnAmount(): Promise<Unit> {
        val rpc = FakeDeclarationsRpc(own = listOf(declaration(2026, VolunteerAllowanceDeclarationSource.IN_APP)))
        return withCard("decl-noamount", rpc) { el ->
            awaitUntil("rows") { el().allOf("tbody tr").size == 1 }
            assertFalse(el().textContent.orEmpty().contains("€"))
            assertFalse(el().textContent.orEmpty().contains("EUR"))
            assertFalse(
                Regex("""\d,\d{2}\b""").containsMatchIn(el().textContent.orEmpty()),
                "no amount-shaped number (German decimal comma)",
            )
        }
    }

    @Test
    fun aFailingLoad_showsTheErrorState_withoutTheExceptionText(): Promise<Unit> {
        val rpc = FakeDeclarationsRpc().apply { failOwn = true }
        return withCard("decl-fail", rpc) { el ->
            awaitUntil("error state") { el().textContent.orEmpty().contains("Die Daten konnten nicht geladen werden.") }
            assertFalse(el().textContent.orEmpty().contains("secret-server-text"))
        }
    }

    private fun HTMLElement.optionLabels(label: String): List<String> {
        val input = controlOf(label) as HTMLInputElement
        input.click()
        val labels = (input.closest(".lapis-ssel") as HTMLElement).allOf("[role=option]").map { it.textContent.orEmpty().trim() }
        input.dispatchEvent(
            KeyboardEvent("keydown", KeyboardEventInit(key = "Escape", bubbles = true, cancelable = true)),
        )
        return labels
    }

    @Test
    fun theLabelCarriesTheStatusOnlyForNonActiveMembers(): Promise<Unit> {
        val rpc =
            FakeDeclarationsRpc(
                members =
                    listOf(
                        MemberSelectionDto("m-1", "Anna Aktiv", MemberStatus.ACTIVE),
                        MemberSelectionDto("m-2", "Willi Weg", MemberStatus.WITHDRAWN),
                        MemberSelectionDto("m-3", "###KvI18nS###Mallory", MemberStatus.DECEASED),
                    ),
            )
        return withCard("decl-status", rpc, AccountRole.ADMIN) { el ->
            el().buttonNamed("Erklärungen anderer Mitglieder ansehen").click()
            awaitUntil("members loaded") { rpc.memberCalls == 1 && el().textContent.orEmpty().contains("Bitte ein Mitglied wählen.") }
            val labels = el().optionLabels("Mitglied")
            assertTrue("Anna Aktiv" in labels, labels.toString())
            assertTrue("Willi Weg (${memberStatusLabel(MemberStatus.WITHDRAWN)})" in labels, labels.toString())
            assertTrue(labels.any { it.startsWith("Mallory (") }, labels.toString())
            assertFalse(labels.any { it.contains("###KvI18nS###") })
            assertEquals(listOf("Anna Aktiv"), labels.filter { !it.contains("(") && it.startsWith("Anna") })
        }
    }

    @Test
    fun atTheSelectionCap_theSearchHintIsShown(): Promise<Unit> {
        val rpc =
            FakeDeclarationsRpc(
                members = (1..MEMBER_SELECTION_CAP).map { MemberSelectionDto("m-$it", "Person $it", MemberStatus.ACTIVE) },
            )
        return withCard("decl-selcap", rpc, AccountRole.BOARD) { el ->
            el().buttonNamed("Erklärungen anderer Mitglieder ansehen").click()
            awaitUntil("hint") { el().textContent.orEmpty().contains("Nicht alle Mitglieder werden angezeigt") }
        }
    }
}

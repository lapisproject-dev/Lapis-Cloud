package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ExternalMemberIdSettingsDto
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberStatus
import org.w3c.dom.HTMLInputElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V156 -- the external member id in the client: who may edit it, the card (invisible without a configured source, the fixed source label, the inline
 * rule, save and remove with the confirmation wording per switch). Pure client; the server decides everything again.
 */
class ExternalMemberIdDomTest {
    private fun row(
        status: MemberStatus = MemberStatus.ACTIVE,
        role: AccountRole? = AccountRole.MEMBER,
        id: String = "member-7",
        anonymized: Boolean = false,
        externalId: String? = null,
    ) = MemberAdminRowDto(
        id = id,
        displayName = "Amara Okafor",
        email = "amara@example.org",
        status = status,
        role = role,
        joinedAt = LocalDate(2026, 1, 1),
        anonymized = anonymized,
        externalMemberId = externalId,
        externalMemberIdSource = externalId?.let { "Wabeo" },
    )

    private class FakeRpc : ExternalMemberIdRpc {
        val sets = mutableListOf<Pair<String, String>>()
        val clears = mutableListOf<String>()

        override suspend fun settings() = ExternalMemberIdSettingsDto(source = "Wabeo", requiredForVoting = true)

        override suspend fun set(
            memberId: String,
            externalId: String,
        ): MemberAdminRowDto {
            sets += memberId to externalId
            return MemberAdminRowDto(
                id = memberId,
                displayName = "x",
                email = "x@example.org",
                status = MemberStatus.ACTIVE,
                role = AccountRole.MEMBER,
                joinedAt = LocalDate(2026, 1, 1),
            )
        }

        override suspend fun clear(memberId: String): MemberAdminRowDto {
            clears += memberId
            return set(memberId, "")
        }
    }

    @Test
    fun editRight_isBoardOrAdminOnly_neverOwnRow_neverAnonymised() {
        assertTrue(canEditExternalMemberIdOf(AccountRole.BOARD, "board-1", row()))
        assertTrue(canEditExternalMemberIdOf(AccountRole.ADMIN, "admin-1", row()))
        assertFalse(canEditExternalMemberIdOf(AccountRole.TREASURER, "t-1", row()))
        assertFalse(canEditExternalMemberIdOf(AccountRole.MEMBER, "m-1", row()))
        assertFalse(canEditExternalMemberIdOf(null, null, row()))
        assertFalse(canEditExternalMemberIdOf(AccountRole.BOARD, "member-7", row(id = "member-7")))
        assertFalse(canEditExternalMemberIdOf(AccountRole.ADMIN, "admin-1", row(anonymized = true)))
    }

    @Test
    fun editRight_escalatedRowsOnlyForAdmin_andAFormerMemberOnlyToClearAStaleId() {
        for (role in listOf(AccountRole.BOARD, AccountRole.TREASURER, AccountRole.ADMIN)) {
            assertFalse(canEditExternalMemberIdOf(AccountRole.BOARD, "board-1", row(role = role)), "BOARD must not edit a $role row")
            assertTrue(canEditExternalMemberIdOf(AccountRole.ADMIN, "admin-1", row(role = role)), "ADMIN may edit a $role row")
        }
        assertFalse(canEditExternalMemberIdOf(AccountRole.BOARD, "board-1", row(status = MemberStatus.WITHDRAWN)))
        assertTrue(canEditExternalMemberIdOf(AccountRole.BOARD, "board-1", row(status = MemberStatus.WITHDRAWN, externalId = "A1")))
    }

    @Test
    fun fieldRule_matchesTheServerRules() {
        assertNull(externalMemberIdFieldProblem("WB-4711"))
        assertNull(externalMemberIdFieldProblem("  a.b_c:d/e-1 "))
        assertNotNull(externalMemberIdFieldProblem(""))
        assertNotNull(externalMemberIdFieldProblem("has space"))
        assertNotNull(externalMemberIdFieldProblem("x".repeat(65)))
        assertNotNull(externalMemberIdFieldProblem("-leading"))
    }

    @Test
    fun removalQuestion_namesTheLossOfTheVotingRightOnlyWhileTheSwitchIsOn() {
        assertFalse(externalMemberIdRemovalQuestion(requiredForVoting = false).contains("Stimmrecht"))
        assertTrue(externalMemberIdRemovalQuestion(requiredForVoting = true).contains("Stimmrecht"))
    }

    @Test
    fun card_isInvisibleWithoutAConfiguredSource(): Promise<Unit> =
        formTest {
            mountedForm("ext-card-off") { root, element ->
                val settings = ExternalMemberIdSettingsDto(source = null)
                MemberExternalIdCard(root, row(), settings, FakeRpc(), LegendGroup(), onChanged = {}).render()
                assertEquals(0, element().allOf("input").size)
            }
        }

    @Test
    fun card_showsTheFixedSource_theStoredId_andRefusesAnInvalidEntryWithoutACall(): Promise<Unit> =
        formTest {
            mountedForm("ext-card-invalid") { root, element ->
                val rpc = FakeRpc()
                val card =
                    MemberExternalIdCard(
                        root,
                        row(externalId = "WB-1"),
                        ExternalMemberIdSettingsDto(source = "Wabeo"),
                        rpc,
                        LegendGroup(),
                        onChanged = {},
                    )
                card.render()
                assertTrue(element().textContent.orEmpty().contains("Wabeo"), "the source label is shown")
                val input = element().querySelector("input[type=text]") as HTMLInputElement
                assertEquals("WB-1", input.value)
                card.field!!.setValue("bad value")
                assertFalse(card.form.validateAndReport(), "the rule refuses the entry")
                assertTrue(rpc.sets.isEmpty())
            }
        }

    @Test
    fun card_removeAsksTheQuestionOfTheSwitch_andOnlyThenClears(): Promise<Unit> =
        formTest {
            mountedForm("ext-card-remove") { root, element ->
                val rpc = FakeRpc()
                val questions = mutableListOf<String>()
                var pending: (() -> Unit)? = null
                var changed = 0
                val card =
                    MemberExternalIdCard(
                        parent = root,
                        row = row(externalId = "WB-1"),
                        settings = ExternalMemberIdSettingsDto(source = "Wabeo", requiredForVoting = true),
                        rpc = rpc,
                        legendGroup = LegendGroup(),
                        onChanged = { changed++ },
                        confirm = { message, onConfirm ->
                            questions += message
                            pending = onConfirm
                        },
                        toastSuccess = {},
                    )
                card.render()
                val removeButton = element().allOf("button").first { it.textContent.orEmpty().contains("Entfernen") }
                removeButton.click()
                assertEquals(1, questions.size)
                assertTrue(questions.single().contains("Stimmrecht"))
                assertTrue(rpc.clears.isEmpty(), "nothing is removed before the confirmation")
                pending!!.invoke()
                awaitUntil("the id was cleared") { rpc.clears.isNotEmpty() }
                awaitUntil("the host was told to reload") { changed == 1 }
            }
        }

    @Test
    fun card_withoutAnId_hasNoRemoveButton(): Promise<Unit> =
        formTest {
            mountedForm("ext-card-noid") { root, element ->
                val card =
                    MemberExternalIdCard(
                        parent = root,
                        row = row(externalId = null),
                        settings = ExternalMemberIdSettingsDto(source = "Wabeo"),
                        rpc = FakeRpc(),
                        legendGroup = LegendGroup(),
                        onChanged = {},
                    )
                card.render()
                assertTrue(element().allOf("button").none { it.textContent.orEmpty().contains("Entfernen") })
            }
        }
}

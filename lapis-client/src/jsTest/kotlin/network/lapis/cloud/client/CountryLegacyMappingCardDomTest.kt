package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CountryLegacyCountsDto
import network.lapis.cloud.shared.domain.CountryLegacyMappingReportDto
import network.lapis.cloud.shared.domain.CountryLegacyMappingResultDto
import network.lapis.cloud.shared.domain.LegacyLineDto
import network.lapis.cloud.shared.domain.LegacyOutcome
import network.lapis.cloud.shared.domain.LegacySectionDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RareSummaryDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.ConflictException
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun section(
    recognised: Int = 5,
    lines: List<LegacyLineDto> = emptyList(),
) = LegacySectionDto(
    totalMembersWithLegacy = recognised + 4,
    recognisedMembers = recognised,
    ambiguousMembers = 1,
    unrecognisedMembers = 3,
    protectedSkippedMembers = 2,
    lines = lines,
    rare = RareSummaryDto(members = 4, recognised = 1),
)

private class FakeMappingRpc(
    var report: CountryLegacyMappingReportDto,
) : CountryLegacyMappingRpc {
    var previews = 0
    val applied = mutableListOf<String>()
    var applyFailure: Throwable? = null

    override suspend fun preview(): CountryLegacyMappingReportDto {
        previews++
        return report
    }

    override suspend fun apply(fingerprint: String): CountryLegacyMappingResultDto {
        applied += fingerprint
        applyFailure?.let { throw it }
        return CountryLegacyMappingResultDto(
            country = CountryLegacyCountsDto(recognised = 5, applied = 5, open = 4, ambiguous = 1),
            nationality = CountryLegacyCountsDto(recognised = 2, applied = 2, open = 7, ambiguous = 1),
        )
    }
}

private class RecordingConfirm : AdminActionConfirm {
    val messages = mutableListOf<String>()
    var proceed: (() -> Unit)? = null

    override fun show(
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
    ) {
        messages += message
        proceed = onConfirm
    }
}

/** V1.9.121 -- the board card "Land und Staatsangehörigkeit abgleichen" in a real mounted root. */
class CountryLegacyMappingCardDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "board-1",
            displayName = "Vorstand",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private fun report(forged: String = "###KvI18nS###\u0001evil <i>x</i>") =
        CountryLegacyMappingReportDto(
            country =
                section(
                    lines =
                        listOf(
                            LegacyLineDto("Polska", LegacyOutcome.RECOGNISED, listOf("PL"), 12),
                            LegacyLineDto("Kongo", LegacyOutcome.AMBIGUOUS, emptyList(), 4),
                            LegacyLineDto(forged, LegacyOutcome.UNRECOGNISED, emptyList(), 3),
                        ),
                ),
            nationality = section(recognised = 2),
            fingerprint = "f".repeat(64),
        )

    private inline fun withCard(
        id: String,
        role: AccountRole,
        rpc: FakeMappingRpc,
        confirm: RecordingConfirm = RecordingConfirm(),
        errors: MutableList<String> = mutableListOf(),
        successes: MutableList<String> = mutableListOf(),
        crossinline block: suspend (() -> HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            withFakeCountryNames {
                AppState.setSession(session(role))
                mountedForm(id) { root, element ->
                    renderCountryLegacyMappingCard(root, rpc, { errors += it }, { successes += it }, confirm)
                    block(element)
                }
            }
        }

    @Test
    fun onlyBoardAndAdminSeeTheCard(): Promise<Unit> =
        formTest {
            val rpc = FakeMappingRpc(report())
            listOf(AccountRole.MEMBER, AccountRole.TREASURER).forEachIndexed { i, role ->
                AppState.setSession(session(role))
                mountedForm("mapping-role-$i") { root, element ->
                    renderCountryLegacyMappingCard(root, rpc)
                    assertFalse(element().textContent.orEmpty().contains("abgleichen"), role.name)
                }
            }
            assertEquals(0, rpc.previews)
        }

    @Test
    fun nothingIsLoadedUntilTheDryRunIsStarted_andApplyDoesNotExistYet(): Promise<Unit> {
        val rpc = FakeMappingRpc(report())
        return withCard("mapping-start", AccountRole.BOARD, rpc) { el ->
            assertEquals(0, rpc.previews)
            assertTrue(el().textContent.orEmpty().contains("Land und Staatsangehörigkeit abgleichen"))
            assertTrue(el().allOf("button").none { it.textContent?.trim() == "Anwenden" })
        }
    }

    @Test
    fun theReport_showsLinesAndCounts_asPlainText_neverMarkup(): Promise<Unit> {
        val rpc = FakeMappingRpc(report())
        return withCard("mapping-report", AccountRole.BOARD, rpc) { el ->
            el().buttonNamed("Trockenlauf starten").click()
            awaitUntil("report shown") { el().textContent.orEmpty().contains("Polska") }
            val text = el().textContent.orEmpty()
            assertTrue(text.contains("Polska → Polen (12)"), text)
            assertTrue(text.contains("Kongo → mehrdeutig (4)"), text)
            assertTrue(text.contains("Geschützt übersprungen: 2"), text)
            assertTrue(text.contains("Seltene Werte"), text)
            assertFalse(text.contains("###KvI18nS###"), "a forged i18n marker in a value never reaches the screen as a marker")
            assertEquals(0, el().allOf(".lapis-country-mapping li i").size, "the value is text, not HTML")
            assertEquals(1, rpc.previews)
            assertTrue(rpc.applied.isEmpty(), "the dry run writes nothing")
        }
    }

    @Test
    fun apply_asksFirstWithTheTotals_andSendsExactlyTheFingerprintOfTheReport(): Promise<Unit> {
        val rpc = FakeMappingRpc(report())
        val confirm = RecordingConfirm()
        val successes = mutableListOf<String>()
        return withCard("mapping-apply", AccountRole.ADMIN, rpc, confirm, successes = successes) { el ->
            el().buttonNamed("Trockenlauf starten").click()
            awaitUntil("report shown") { el().allOf("button").any { it.textContent?.trim() == "Anwenden" } }
            (el().buttonNamed("Anwenden") as HTMLButtonElement).click()
            assertEquals(1, confirm.messages.size)
            assertTrue(
                confirm.messages.single().contains("5 Einträge für das Land und 2 Einträge für die Staatsangehörigkeit"),
                confirm.messages.single(),
            )
            assertTrue(rpc.applied.isEmpty(), "nothing is written before the confirmation")
            confirm.proceed!!.invoke()
            awaitUntil("applied") { rpc.applied.isNotEmpty() }
            assertEquals(listOf("f".repeat(64)), rpc.applied)
            awaitUntil("outcome") { el().textContent.orEmpty().contains("Angewendet: Land 5 von 5") }
            assertEquals(listOf("Der Abgleich wurde angewendet."), successes)
        }
    }

    @Test
    fun nothingRecognised_meansApplyStaysDisabled(): Promise<Unit> {
        val empty = report().copy(country = section(recognised = 0), nationality = section(recognised = 0))
        val rpc = FakeMappingRpc(empty)
        return withCard("mapping-none", AccountRole.BOARD, rpc) { el ->
            el().buttonNamed("Trockenlauf starten").click()
            awaitUntil("report shown") { el().allOf("button").any { it.textContent?.trim() == "Anwenden" } }
            assertTrue((el().buttonNamed("Anwenden") as HTMLButtonElement).disabled)
        }
    }

    @Test
    fun aStaleReport_isReportedWithAFixedMessage_andTheResultIsCleared(): Promise<Unit> {
        val rpc = FakeMappingRpc(report()).apply { applyFailure = ConflictException("report outdated") }
        val confirm = RecordingConfirm()
        val errors = mutableListOf<String>()
        return withCard("mapping-stale", AccountRole.BOARD, rpc, confirm, errors) { el ->
            el().buttonNamed("Trockenlauf starten").click()
            awaitUntil("report shown") { el().allOf("button").any { it.textContent?.trim() == "Anwenden" } }
            el().buttonNamed("Anwenden").click()
            confirm.proceed!!.invoke()
            awaitUntil("error shown") { errors.isNotEmpty() }
            assertTrue(errors.single().contains("veraltet"))
            assertNull(el().allOf("button").firstOrNull { it.textContent?.trim() == "Anwenden" }, "the stale report is gone")
        }
    }
}

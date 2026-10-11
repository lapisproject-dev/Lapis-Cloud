package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.CommitteeAutoEndEntryDto
import network.lapis.cloud.shared.domain.CommitteeAutoEndNoticeDto
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeElectionSeatChangeDto
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.CommitteeOpenSeatDto
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeSeatChangeKind
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.VacantMandatoryRoleDto
import network.lapis.cloud.shared.rpc.IGovernanceService
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.102 -- the notice band of the committee screen (seats ended automatically, vacant chair, legacy open seats) and the neutral note on a
 * roster row. Free text (names) is rendered as text, never as markup.
 */
class CommitteeAutoEndNoticeDomTest {
    private fun noticeJson(notice: CommitteeAutoEndNoticeDto) = jsonOf(CommitteeAutoEndNoticeDto.serializer(), notice)

    private fun entry(
        name: String = "Erika Beispiel",
        committee: String = "Vorstand",
        reason: MemberStatus = MemberStatus.WITHDRAWN,
    ) = CommitteeAutoEndEntryDto(
        committeeId = "c1",
        committeeName = committee,
        membershipId = "m1",
        memberId = "p1",
        memberDisplayName = name,
        role = CommitteeRole.CHAIR,
        since = LocalDate(2025, 1, 1),
        until = LocalDate(2026, 10, 11),
        reason = reason,
    )

    private fun committee(type: CommitteeType = CommitteeType.WORKING_GROUP) =
        CommitteeDto(
            id = "committee-1",
            name = "AG",
            type = type,
            description = "",
            active = true,
            quorumPercent = 50,
            createdAt = LocalDateTime(2026, 1, 1, 0, 0),
        )

    @Test
    fun band_listsAutoEnds_vacantChair_andLegacySeats_asANonDismissibleStatusRegion(): Promise<Unit> =
        formTest {
            val route = routeOf { rpcService<IGovernanceService>().getCommitteeAutoEndNotice() }
            val notice =
                CommitteeAutoEndNoticeDto(
                    recentAutoEnds = listOf(entry()),
                    vacantMandatoryRoles = listOf(VacantMandatoryRoleDto("c1", "Vorstand", CommitteeRole.CHAIR)),
                    openSeatsWithoutMembership =
                        listOf(
                            CommitteeOpenSeatDto(
                                committeeId = "c2",
                                committeeName = "AG Alt",
                                membershipId = "m2",
                                memberId = "p2",
                                memberDisplayName = "Otto Alt",
                                role = CommitteeRole.MEMBER,
                                since = LocalDate(2024, 3, 1),
                                until = null,
                                memberStatus = MemberStatus.DONOR,
                            ),
                        ),
                )
            withFetchStub(respond = { r ->
                if (r.isRpc &&
                    r.rpcRoute == route
                ) {
                    r.answerWith(noticeJson(notice))
                } else {
                    StubResponse()
                }
            }) { _ ->
                mountedForm("committee-notice") { root, element ->
                    renderCommitteeAutoEndNotice(root)
                    awaitUntil("the band appears") { element().textContent.orEmpty().contains("Vorsitz ist unbesetzt") }
                    val text = element().textContent.orEmpty()
                    assertTrue(text.contains("Vorstand: Vorsitz ist unbesetzt."), text)
                    assertTrue(text.contains("In den letzten 30 Tagen automatisch beendet: 1 Gremienzeiten"), text)
                    assertTrue(text.contains("Erika Beispiel: Vorstand, "), text)
                    assertTrue(text.contains("Offene Gremienzeiten von Personen ohne Mitgliedschaft: 1 – bitte prüfen."), text)
                    assertTrue(text.contains("Otto Alt: AG Alt, "), text)
                    assertEquals("status", element().querySelector("[role=status]")?.getAttribute("role"))
                    assertEquals(0, element().allOf("button").size, "no dismiss button")
                }
            }
        }

    @Test
    fun band_isInvisible_whenAllThreeListsAreEmpty(): Promise<Unit> =
        formTest {
            val route = routeOf { rpcService<IGovernanceService>().getCommitteeAutoEndNotice() }
            val empty = CommitteeAutoEndNoticeDto(emptyList(), emptyList(), emptyList())
            withFetchStub(respond = { r ->
                if (r.isRpc &&
                    r.rpcRoute == route
                ) {
                    r.answerWith(noticeJson(empty))
                } else {
                    StubResponse()
                }
            }) { calls ->
                mountedForm("committee-notice-empty") { root, element ->
                    renderCommitteeAutoEndNotice(root)
                    awaitUntil("the notice was asked") { calls.toRoute(route).isNotEmpty() }
                    delay(120)
                    assertNull(element().querySelector("[role=status]"))
                    assertEquals("", element().textContent.orEmpty().trim())
                }
            }
        }

    @Test
    fun band_rendersNamesAsText_neverAsMarkup(): Promise<Unit> =
        formTest {
            val route = routeOf { rpcService<IGovernanceService>().getCommitteeAutoEndNotice() }
            val notice =
                CommitteeAutoEndNoticeDto(
                    recentAutoEnds = listOf(entry(name = "<img src=x onerror=window.__pwned=1>", committee = "<b>Fett</b>")),
                    vacantMandatoryRoles = emptyList(),
                    openSeatsWithoutMembership = emptyList(),
                )
            withFetchStub(respond = { r ->
                if (r.isRpc &&
                    r.rpcRoute == route
                ) {
                    r.answerWith(noticeJson(notice))
                } else {
                    StubResponse()
                }
            }) { _ ->
                mountedForm("committee-notice-xss") { root, element ->
                    renderCommitteeAutoEndNotice(root)
                    awaitUntil("the band appears") { element().textContent.orEmpty().contains("automatisch beendet") }
                    assertNull(element().querySelector("img"), "no element created from the name")
                    assertNull(element().querySelector("b"), "no element created from the committee name")
                    assertTrue(element().textContent.orEmpty().contains("<img src=x onerror=window.__pwned=1>"))
                }
            }
        }

    private fun seatChange(
        kind: CommitteeSeatChangeKind,
        name: String = "Erika Beispiel",
    ) = CommitteeElectionSeatChangeDto(
        kind = kind,
        electionId = "e1",
        committeeId = "c1",
        committeeName = "Vorstand",
        membershipId = "m1",
        memberId = "p1",
        memberDisplayName = name,
        role = CommitteeRole.CHAIR,
        effectiveOn = LocalDate(2026, 10, 11),
    )

    @Test
    fun band_listsSeatsAnElectionStartedAndEnded_withALinkToTheElection(): Promise<Unit> =
        formTest {
            val route = routeOf { rpcService<IGovernanceService>().getCommitteeAutoEndNotice() }
            val notice =
                CommitteeAutoEndNoticeDto(
                    recentAutoEnds = emptyList(),
                    vacantMandatoryRoles = emptyList(),
                    openSeatsWithoutMembership = emptyList(),
                    electionSeatChanges =
                        listOf(
                            seatChange(CommitteeSeatChangeKind.ELECTION_SEATED, "Neu Gewählt"),
                            seatChange(CommitteeSeatChangeKind.ELECTION_ENDED, "Bisherige Person"),
                        ),
                )
            withFetchStub(respond = { r ->
                if (r.isRpc &&
                    r.rpcRoute == route
                ) {
                    r.answerWith(noticeJson(notice))
                } else {
                    StubResponse()
                }
            }) { _ ->
                mountedForm("committee-notice-election") { root, element ->
                    renderCommitteeAutoEndNotice(root)
                    awaitUntil("the band appears") { element().textContent.orEmpty().contains("durch Wahl geändert") }
                    val text = element().flatText()
                    assertTrue(text.contains("In den letzten 30 Tagen durch Wahl geändert: 2 Gremienzeiten"), text)
                    assertTrue(text.contains("Übernommen durch Wahl am 11.10.2026: Neu Gewählt, Vorstand, Vorsitz"), text)
                    assertTrue(text.contains("Beendet durch Nachfolgewahl am 11.10.2026: Bisherige Person, Vorstand, Vorsitz"), text)
                    assertEquals(2, element().allOf("button").count { it.textContent?.trim() == "Zur Wahl" })
                    assertEquals("status", element().querySelector("[role=status]")?.getAttribute("role"))
                    element().allOf("span[aria-hidden=true]").also { assertEquals(2, it.size, "one decorative sign per line") }
                }
            }
        }

    @Test
    fun band_rendersElectionSeatChangeNamesAsText_neverAsMarkup(): Promise<Unit> =
        formTest {
            val route = routeOf { rpcService<IGovernanceService>().getCommitteeAutoEndNotice() }
            val notice =
                CommitteeAutoEndNoticeDto(
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    listOf(seatChange(CommitteeSeatChangeKind.ELECTION_SEATED, "<img src=x onerror=window.__pwned=1>")),
                )
            withFetchStub(respond = { r ->
                if (r.isRpc &&
                    r.rpcRoute == route
                ) {
                    r.answerWith(noticeJson(notice))
                } else {
                    StubResponse()
                }
            }) { _ ->
                mountedForm("committee-notice-election-xss") { root, element ->
                    renderCommitteeAutoEndNotice(root)
                    awaitUntil("the band appears") { element().textContent.orEmpty().contains("durch Wahl geändert") }
                    assertNull(element().querySelector("img"))
                    assertTrue(element().textContent.orEmpty().contains("<img src=x onerror=window.__pwned=1>"))
                }
            }
        }

    @Test
    fun band_staysHidden_whenTheNoticeCannotBeLoaded(): Promise<Unit> =
        formTest {
            val route = routeOf { rpcService<IGovernanceService>().getCommitteeAutoEndNotice() }
            withFetchStub(respond = { r ->
                if (r.isRpc &&
                    r.rpcRoute == route
                ) {
                    StubResponse(networkError = true)
                } else {
                    StubResponse()
                }
            }) { calls ->
                mountedForm("committee-notice-error") { root, element ->
                    renderCommitteeAutoEndNotice(root)
                    awaitUntil("the notice was asked") { calls.toRoute(route).isNotEmpty() }
                    delay(120)
                    assertNull(element().querySelector("[role=status]"))
                }
            }
        }

    // ── the note on a roster row ──

    private fun membership(
        until: LocalDate?,
        reason: MemberStatus?,
        since: LocalDate = LocalDate(2024, 1, 1),
    ) = CommitteeMembershipDto(
        id = "m",
        committeeId = "committee-1",
        memberId = "p",
        memberDisplayName = "Erika Beispiel",
        role = CommitteeRole.MEMBER,
        since = since,
        until = until,
        autoEndReason = reason,
    )

    @Test
    fun rowNote_forAnAutomaticEnd_namesTheReason() {
        val note = committeeSeatAutoEndNote(membership(until = LocalDate(2026, 10, 11), reason = MemberStatus.WITHDRAWN))
        assertEquals("automatisch beendet, Grund: Ausgetreten", note)
        val donor = committeeSeatAutoEndNote(membership(until = LocalDate(2026, 10, 11), reason = MemberStatus.DONOR))
        assertTrue(donor.orEmpty().contains("Spender"), donor)
    }

    @Test
    fun rowNote_forASeatThatNeverStarted_saysNotTakenUp() {
        val note =
            committeeSeatAutoEndNote(
                membership(until = LocalDate(2026, 10, 11), reason = MemberStatus.DECEASED, since = LocalDate(2026, 12, 1)),
            )
        assertTrue(note.orEmpty().startsWith("nicht angetreten, automatisch beendet am "), note)
    }

    @Test
    fun rowNote_isNull_withoutAReason_orWithoutAnEnd() {
        assertNull(committeeSeatAutoEndNote(membership(until = LocalDate(2026, 10, 11), reason = null)))
        assertNull(committeeSeatAutoEndNote(membership(until = null, reason = MemberStatus.WITHDRAWN)))
    }

    @Test
    fun roster_showsTheNoteOnlyForRowsWithAReason(): Promise<Unit> =
        formTest {
            val roster = routeOf { rpcService<IGovernanceService>().listCommitteeMembers("c", false) }
            val body =
                jsonOf(
                    ListSerializer(CommitteeMembershipDto.serializer()),
                    listOf(
                        membership(until = LocalDate(2026, 10, 11), reason = MemberStatus.WITHDRAWN),
                        membership(
                            until = LocalDate(2026, 9, 1),
                            reason = null,
                        ).copy(id = "m-manual", memberDisplayName = "Manuel Beendet"),
                    ),
                )
            withFetchStub(respond = { r -> if (r.isRpc && r.rpcRoute == roster) r.answerWith(body) else StubResponse() }) { _ ->
                mountedForm("committee-row-note") { root, element ->
                    renderCommitteeRoster(root, committee(), canManage = false)
                    awaitUntil("both rows are listed") { element().textContent.orEmpty().contains("Manuel Beendet") }
                    val text = element().textContent.orEmpty()
                    assertEquals(1, Regex("automatisch beendet, Grund").findAll(text).count(), text)
                    assertFalse(text.contains("nicht angetreten"))
                }
            }
        }
}

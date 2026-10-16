package network.lapis.cloud.server.memberfinder

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.agreementtexts.AgreementTextTestState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.time.MutableTestClock
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.MemberFinderBand
import network.lapis.cloud.shared.domain.MemberFinderInterest
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.MemberFinderRegrantCooldownException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.hours

/**
 * V1.9.144 -- the visibility threshold and the daily bands. Below five consenting participants a region is invisible; then three coarse bands; only
 * eligible participants count; a join or a leave never moves a published band before the next daily evaluation (so two readings cannot be subtracted to
 * find out who joined), and a revoke is always immediate while a renewed consent waits 24 hours.
 */
class MemberFinderBandsTest :
    FunSpec({
        val data = FinderTestData()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterTest {
            data.reset()
            AgreementTextTestState.reset()
        }

        test("four consenting participants: no band row, the member sees 'not visible yet', the board overview is empty") {
            TimeTestSupport.withServerClock(instant = "2026-10-16T10:00:00Z") {
                data.enableFinder()
                val members = data.region(count = 4)
                data.computeBands()
                data.bandRows() shouldBe 0
                finderTestApplication {
                    val state = call(member = members[0]) { getState() }
                    state.optedIn shouldBe true
                    state.band.shouldBeNull()
                    state.bandComputedOn.shouldBeNull()
                    val board = data.member(label = "board", role = AccountRole.BOARD)
                    call(member = board) { getBoardOverview() }.regions.shouldBeEmpty()
                }
            }
        }

        test("5, 9, 10, 19 and 20 consenting participants give the bands B5_9, B10_19 and B20_PLUS") {
            TimeTestSupport.withServerClock(instant = "2026-10-16T10:00:00Z") {
                data.enableFinder()
                finderTestApplication {
                    val expected = listOf(5 to MemberFinderBand.B5_9, 9 to MemberFinderBand.B5_9, 10 to MemberFinderBand.B10_19)
                    for ((count, band) in expected + listOf(19 to MemberFinderBand.B10_19, 20 to MemberFinderBand.B20_PLUS)) {
                        data.emptyFinderTables()
                        data.region(count = count, label = "n$count")
                        data.computeBands()
                        val observer = data.memberIds.last()
                        val state = call(member = observer) { getState() }
                        state.band shouldBe band
                        state.regionLabel shouldBe "38"
                        state.bandComputedOn.shouldNotBeNull()
                    }
                }
            }
        }

        test("minors, non-ACTIVE members, anonymized members, a foreign region and a stale consent text do not count") {
            TimeTestSupport.withServerClock(instant = "2026-10-16T10:00:00Z") {
                data.enableFinder()
                data.region(count = 4)
                val minor = data.member(label = "minor", dateOfBirth = LocalDate(2012, 1, 1))
                val guest = data.member(label = "guest", status = MemberStatus.GUEST)
                val friend = data.member(label = "friend", status = MemberStatus.FRIEND)
                val anonymized = data.member(label = "anonymized", anonymized = true)
                val foreign = data.member(label = "foreign", postalCode = "75001", countryCode = "FR")
                val blankCountry = data.member(label = "blank-country", countryCode = null, legacyCountry = "")
                val stale = data.member(label = "stale")
                for (m in listOf(minor, guest, friend, anonymized, foreign, blankCountry)) data.consent(memberId = m)
                transaction {
                    MemberFinderConsentStore.grant(
                        memberId = stale,
                        version = "old.v0",
                        sha256 = "0".repeat(64),
                        interest = MemberFinderInterest.BOTH,
                        now = ServerClock.now(),
                    )
                }
                data.computeBands()
                data.bandRows() shouldBe 0
                // a fifth VALID participant makes the region visible
                data.consent(memberId = data.member(label = "fifth"))
                data.computeBands()
                data.bandRows() shouldBe 1
            }
        }

        test("a member who has not consented sees neither the band nor the region label of their own region") {
            TimeTestSupport.withServerClock(instant = "2026-10-16T10:00:00Z") {
                data.enableFinder()
                data.region(count = 6)
                data.computeBands()
                finderTestApplication {
                    val outsider = data.member(label = "outsider")
                    val state = call(member = outsider) { getState() }
                    state.optedIn shouldBe false
                    state.eligible shouldBe true
                    state.band.shouldBeNull()
                    state.regionLabel.shouldBeNull()
                    state.bandComputedOn.shouldBeNull()
                }
            }
        }

        test(
            "a join does not move a published band until the next daily evaluation; a leave neither; a renewed consent waits 24 hours, a revoke never",
        ) {
            val clock = MutableTestClock("2026-10-16T10:00:00Z")
            TimeTestSupport.withMutableServerClock(clock = clock) {
                data.enableFinder()
                val nine = data.region(count = 9)
                data.computeBands()
                finderTestApplication {
                    val observer = nine[0]
                    val before = call(member = observer) { getState() }
                    before.band shouldBe MemberFinderBand.B5_9
                    val board = data.member(label = "board", role = AccountRole.BOARD)
                    val boardBefore = call(member = board) { getBoardOverview() }

                    // A joins through the service: the tenth participant exists now, the published reading does not change
                    val a = data.member(label = "joiner", postalCode = "38999")
                    val text = call(member = a) { getConsentText() }
                    call(member = a) {
                        optIn(
                            consentVersion = text.versionLabel,
                            consentSha256 = text.sha256,
                            interest = MemberFinderInterest.BOTH,
                            adultConfirmed = true,
                        )
                    }.optedIn shouldBe
                        true
                    MemberFinderPoller().tick()
                    call(member = observer) { getState() } shouldBe before
                    call(member = board) { getBoardOverview() } shouldBe boardBefore

                    // the revoke is immediate and does not move the reading either
                    call(member = a) { optOut() }.optedIn shouldBe false
                    call(member = observer) { getState() } shouldBe before

                    // renewing within 24 hours is refused, with a visible moment; after the 24 hours it works
                    shouldThrow<MemberFinderRegrantCooldownException> {
                        attempt(member = a) {
                            optIn(
                                consentVersion = text.versionLabel,
                                consentSha256 = text.sha256,
                                interest = MemberFinderInterest.BOTH,
                                adultConfirmed = true,
                            )
                        }.getOrThrow()
                    }
                    val waiting = call(member = a) { getState() }
                    waiting.optedIn shouldBe false
                    waiting.nextOptInAllowedAt.shouldNotBeNull()
                    clock.advance(25.hours)
                    call(member = a) {
                        optIn(
                            consentVersion = text.versionLabel,
                            consentSha256 = text.sha256,
                            interest = MemberFinderInterest.BOTH,
                            adultConfirmed = true,
                        )
                    }.optedIn shouldBe
                        true
                    call(member = a) { getState() }.nextOptInAllowedAt.shouldBeNull()

                    // the next organization day: one evaluation, the tenth participant shows
                    MemberFinderPoller().tick()
                    call(member = observer) { getState() }.band shouldBe MemberFinderBand.B10_19
                    call(member = board) { getBoardOverview() }.regions.single().band shouldBe MemberFinderBand.B10_19
                }
            }
        }

        test("the evaluation runs once per organization day, not on every tick") {
            val clock = MutableTestClock("2026-10-16T10:00:00Z")
            TimeTestSupport.withMutableServerClock(clock = clock) {
                data.enableFinder()
                data.region(count = 5)
                MemberFinderPoller().tick()
                data.bandRows() shouldBe 1
                data.consent(memberId = data.member(label = "sixth", postalCode = "38998"))
                data.region(count = 5, postalPrefix = "10", label = "other")
                clock.advance(2.hours)
                MemberFinderPoller().tick()
                // same day: the second region (five participants) is NOT evaluated yet
                data.bandRows() shouldBe 1
                clock.advance(24.hours)
                MemberFinderPoller().tick()
                data.bandRows() shouldBe 2
            }
        }

        test("a published FINDER text of the operator ends every effective consent until the members consent again") {
            TimeTestSupport.withServerClock(instant = "2026-10-16T10:00:00Z") {
                data.enableFinder()
                val members = data.region(count = 5)
                data.computeBands()
                AgreementTextTestState.publishDirect(
                    kind = AgreementTextKind.FINDER,
                    label = "2026-10-17.v1",
                    body = "Neuer, rechtlich geprüfter Text.",
                )
                data.computeBands()
                data.bandRows() shouldBe 0
                finderTestApplication {
                    val state = call(member = members[0]) { getState() }
                    state.consentOutdated shouldBe true
                    state.optedIn shouldBe false
                    // an outdated consent is renewed WITHOUT the 24-hour wait: it is an open GRANT of an older text
                    val text = call(member = members[0]) { getConsentText() }
                    text.versionLabel shouldBe "2026-10-17.v1"
                    call(member = members[0]) {
                        optIn(
                            consentVersion = text.versionLabel,
                            consentSha256 = text.sha256,
                            interest = MemberFinderInterest.BOTH,
                            adultConfirmed = true,
                        )
                    }.optedIn shouldBe
                        true
                }
            }
        }
    })

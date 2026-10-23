package network.lapis.cloud.server.memberfinder

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.agreementtexts.AgreementTextTestState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.MemberFinderService
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgreementTextFormat
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.MemberFinderInterest
import network.lapis.cloud.shared.domain.MemberFinderPurpose
import network.lapis.cloud.shared.domain.MemberFinderStateDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.MemberFinderAdultConfirmationRequiredException
import network.lapis.cloud.shared.rpc.MemberFinderConsentOutdatedException
import network.lapis.cloud.shared.rpc.MemberFinderNotEligibleException
import network.lapis.cloud.shared.rpc.MemberFinderUnavailableException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val PINNED = "2026-10-16T10:00:00Z"

/**
 * V1.9.144 -- who may call what. Every status against ALL fifteen methods (only ACTIVE passes the gate; everything else is the bare Forbidden), the roles
 * of the board overview, the switch gate (every method refuses with the same type while the instance switch is off, except `getState` and `optOut`), and
 * the eligibility of the caller's own data (minor, no German region, adult confirmation, stale consent text).
 */
class MemberFinderAccessTest :
    FunSpec({
        val data = FinderTestData()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterTest { data.reset() }

        fun calls(): Map<String, suspend MemberFinderService.() -> Any?> {
            val bogus = Uuid.random().toString()
            return linkedMapOf(
                "getState" to { getState() },
                "getConsentText" to { getConsentText() },
                "optIn" to {
                    optIn(
                        consentVersion = "v",
                        consentSha256 = "s",
                        interest = MemberFinderInterest.BOTH,
                        adultConfirmed = true,
                    )
                },
                "optOut" to { optOut() },
                "sendRequest" to { sendRequest(purpose = MemberFinderPurpose.MEETUP, note = null) },
                "listOutgoingRequests" to { listOutgoingRequests() },
                "withdrawRequest" to { withdrawRequest(bogus) },
                "listIncomingRequests" to { listIncomingRequests() },
                "respondToRequest" to { respondToRequest(requestId = bogus, accept = true, block = false) },
                "listConnections" to { listConnections() },
                "contactConnection" to { contactConnection(connectionId = bogus, message = "Hallo") },
                "blockConnection" to { blockConnection(bogus) },
                "listBlocks" to { listBlocks() },
                "unblock" to { unblock(bogus) },
                "getBoardOverview" to { getBoardOverview() },
            )
        }

        test("the service has exactly the fifteen methods of the matrix") {
            calls().keys.size shouldBe 15
        }

        test("every status against all fifteen methods: only ACTIVE passes the gate, everything else is the bare Forbidden") {
            data.enableFinder()
            finderTestApplication {
                MemberStatus.entries.forEach { status ->
                    val caller = data.member(label = "matrix-$status", status = status)
                    calls().forEach { (name, op) ->
                        val result = attempt(member = caller, op = op)
                        val forbidden = result.exceptionOrNull() is ForbiddenException
                        val expected = status != MemberStatus.ACTIVE || name == "getBoardOverview"
                        withClue("$status / $name") { forbidden shouldBe expected }
                    }
                }
            }
        }

        test("the board overview: MEMBER and TREASURER are refused, BOARD and ADMIN pass") {
            data.enableFinder()
            finderTestApplication {
                for (role in AccountRole.entries) {
                    val caller = data.member(label = "role-$role", role = role)
                    val refused = attempt(member = caller) { getBoardOverview() }.exceptionOrNull() is ForbiddenException
                    refused shouldBe (role == AccountRole.MEMBER || role == AccountRole.TREASURER)
                }
            }
        }

        test("switch off: every method refuses with the same type, except getState (offered = false) and optOut (always possible)") {
            data.enableFinder(false)
            finderTestApplication {
                val caller = data.member(label = "off", role = AccountRole.ADMIN)
                calls().forEach { (name, op) ->
                    val result = attempt(member = caller, op = op)
                    when (name) {
                        "getState" -> (result.getOrThrow() as MemberFinderStateDto).offered shouldBe false
                        "optOut" -> (result.getOrThrow() as MemberFinderStateDto).offered shouldBe false
                        else -> withClue(name) { result.exceptionOrNull().shouldBeInstanceOf<MemberFinderUnavailableException>() }
                    }
                }
            }
        }

        test("V1.9.196: the consent text carries its format and server-rendered HTML for a MARKDOWN version, and none for plain text") {
            data.enableFinder()
            try {
                val plainSha =
                    AgreementTextTestState.publishDirect(kind = AgreementTextKind.FINDER, label = "f.v1", body = "- Einwilligung *roh*")
                finderTestApplication {
                    val plain = call(member = data.member(label = "plain-text")) { getConsentText() }
                    plain.format shouldBe AgreementTextFormat.PLAIN
                    plain.renderedHtml shouldBe null
                    plain.body shouldBe "- Einwilligung *roh*"
                    plain.sha256 shouldBe plainSha
                }
                val markdownSha =
                    AgreementTextTestState.publishDirect(
                        kind = AgreementTextKind.FINDER,
                        label = "f.v2",
                        body = "## Einwilligung\n\nIch stimme **ausdrücklich** zu.\n\n<script>alert(1)</script>",
                        format = AgreementTextFormat.MARKDOWN,
                    )
                finderTestApplication {
                    val text = call(member = data.member(label = "markdown-text")) { getConsentText() }
                    text.format shouldBe AgreementTextFormat.MARKDOWN
                    text.sha256 shouldBe markdownSha
                    text.versionLabel shouldBe "f.v2"
                    text.body shouldBe "## Einwilligung\n\nIch stimme **ausdrücklich** zu.\n\n<script>alert(1)</script>"
                    text.renderedHtml!! shouldContain "<h3>Einwilligung</h3>"
                    text.renderedHtml!! shouldContain "<strong>ausdrücklich</strong>"
                    text.renderedHtml!! shouldNotContain "<script"
                }
            } finally {
                AgreementTextTestState.reset()
            }
        }

        test("a notice version that is not the current one keeps the finder inactive even with the switch on") {
            data.enableFinder()
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[memberFinderNoticeVersion] =
                        "0"
                }
            }
            finderTestApplication {
                val caller = data.member(label = "stale-notice")
                attempt(member = caller) { getConsentText() }.exceptionOrNull().shouldBeInstanceOf<MemberFinderUnavailableException>()
                call(member = caller) { getState() }.offered shouldBe false
            }
        }

        test("opt-in is refused without the adult confirmation, for a known minor, without a German region and for a stale text") {
            TimeTestSupport.withServerClock(instant = PINNED) {
                data.enableFinder()
                finderTestApplication {
                    val text = call(member = data.member(label = "txt")) { getConsentText() }
                    val noConfirm = data.member(label = "no-confirm")
                    attempt(member = noConfirm) {
                        optIn(
                            consentVersion = text.versionLabel,
                            consentSha256 = text.sha256,
                            interest = MemberFinderInterest.BOTH,
                            adultConfirmed = false,
                        )
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<MemberFinderAdultConfirmationRequiredException>()

                    val minor = data.member(label = "minor", dateOfBirth = LocalDate(2009, 10, 17))
                    attempt(member = minor) {
                        optIn(
                            consentVersion = text.versionLabel,
                            consentSha256 = text.sha256,
                            interest = MemberFinderInterest.BOTH,
                            adultConfirmed = true,
                        )
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<MemberFinderNotEligibleException>()
                    // exactly 18 today: of age
                    val eighteen = data.member(label = "eighteen", dateOfBirth = LocalDate(2008, 10, 16))
                    call(member = eighteen) {
                        optIn(
                            consentVersion = text.versionLabel,
                            consentSha256 = text.sha256,
                            interest = MemberFinderInterest.BOTH,
                            adultConfirmed = true,
                        )
                    }.optedIn shouldBe
                        true

                    val foreign = data.member(label = "foreign", postalCode = "75001", countryCode = "FR")
                    attempt(member = foreign) {
                        optIn(
                            consentVersion = text.versionLabel,
                            consentSha256 = text.sha256,
                            interest = MemberFinderInterest.BOTH,
                            adultConfirmed = true,
                        )
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<MemberFinderNotEligibleException>()
                    val noPostal = data.member(label = "no-postal", postalCode = null)
                    attempt(member = noPostal) {
                        optIn(
                            consentVersion = text.versionLabel,
                            consentSha256 = text.sha256,
                            interest = MemberFinderInterest.BOTH,
                            adultConfirmed = true,
                        )
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<MemberFinderNotEligibleException>()

                    // an unknown birth date plus the confirmation is accepted
                    val unknownBirth = data.member(label = "unknown-birth", dateOfBirth = null)
                    call(member = unknownBirth) {
                        optIn(
                            consentVersion = text.versionLabel,
                            consentSha256 = text.sha256,
                            interest = MemberFinderInterest.MEETUP,
                            adultConfirmed = true,
                        )
                    }.optedIn shouldBe
                        true

                    val stale = data.member(label = "stale")
                    attempt(member = stale) {
                        optIn(
                            consentVersion = "old.v0",
                            consentSha256 = text.sha256,
                            interest = MemberFinderInterest.BOTH,
                            adultConfirmed = true,
                        )
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<MemberFinderConsentOutdatedException>()
                    attempt(member = stale) {
                        optIn(
                            consentVersion = text.versionLabel,
                            consentSha256 = "0".repeat(64),
                            interest = MemberFinderInterest.BOTH,
                            adultConfirmed = true,
                        )
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<MemberFinderConsentOutdatedException>()
                }
            }
        }
    })

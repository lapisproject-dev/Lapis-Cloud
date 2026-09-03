package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.crm.CrmContactStore
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.CrmContactTable
import network.lapis.cloud.server.db.generated.CrmInteractionTable
import network.lapis.cloud.server.db.generated.ExternalDonorTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.CrmContactInput
import network.lapis.cloud.shared.domain.CrmContactType
import network.lapis.cloud.shared.domain.CrmInteractionInput
import network.lapis.cloud.shared.domain.CrmInteractionKind
import network.lapis.cloud.shared.domain.CrmLawfulBasis
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/** Inserts a minimal, valid [ExternalDonorTable] row and returns its id -- used by the memberId/externalDonorId link tests below. */
private fun insertExternalDonor(): Uuid {
    val id = Uuid.random()
    ExternalDonorTable.insert {
        it[ExternalDonorTable.id] = id
        it[displayName] = "Test-Spender"
        it[donorCategory] = DonorCategory.GERMAN_NATURAL_PERSON
        it[street] = null
        it[postalCode] = null
        it[city] = null
        it[country] = null
        it[active] = true
    }
    return id
}

/**
 * Creates [count] minimal, valid contacts directly via [CrmContactStore.create] (bypassing the
 * HTTP/rate-limiter/[CrmContactPolicy] layers entirely -- this is only ever used to seed the
 * volume [ICrmService.listContacts]'/[ICrmService.listInteractions]'s `MAX_PAGE_SIZE` cap tests
 * need, where going through 200+ real HTTP round-trips would make the test suite slow for no
 * additional coverage). Every contact gets a distinct `displayName` ("Bulk 000", "Bulk 001", ...)
 * so no [requireUniqueEmail]/-Member/-ExternalDonor collision is possible (`email` stays `null`
 * throughout).
 */
private fun insertBulkContacts(count: Int) {
    transaction {
        repeat(count) { i ->
            CrmContactStore.create(
                input =
                    CrmContactInput(
                        displayName = "Bulk %03d".format(i),
                        email = null,
                        phone = null,
                        street = null,
                        postalCode = null,
                        city = null,
                        country = null,
                        contactType = CrmContactType.INTERESSENT,
                        lawfulBasis = CrmLawfulBasis.LEGITIMATE_INTEREST,
                        consentSource = null,
                        consentGivenAt = null,
                        externalDonorId = null,
                        memberId = null,
                    ),
                createdBy = Uuid.parse(ADMIN_ID),
            )
        }
    }
}

/**
 * Exercises [CrmService]'s role matrix, [network.lapis.cloud.server.crm.CrmContactPolicy]
 * validation wiring, email-uniqueness, rate limiting, and [network.lapis.cloud.server.crm
 * .CrmContactStore.recordInteraction]'s denormalization update -- house style (throwaway routes
 * calling the service class directly), mirrors [WebhookServiceTest]/[DsgvoServiceTest].
 */
class CrmServiceTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterTest {
            transaction {
                CrmInteractionTable.deleteAll()
                CrmContactTable.deleteAll()
            }
        }

        fun generousLimiter() = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes)

        suspend fun testApp(
            contactWriteRateLimiter: FederationInboxRateLimiter = generousLimiter(),
            interactionWriteRateLimiter: FederationInboxRateLimiter = generousLimiter(),
            block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
        ) {
            testApplication {
                application {
                    install(StatusPages) { installCrmExceptionHandlers() }
                    routing {
                        registerCrmTestRoutes(
                            contactWriteRateLimiter = contactWriteRateLimiter,
                            interactionWriteRateLimiter = interactionWriteRateLimiter,
                        )
                    }
                }
                block()
            }
        }

        // ── Role matrix ──────────────────────────────────────────────────────────────

        test("a plain MEMBER is forbidden from listing, creating, and erasing contacts") {
            testApp {
                client.get("/test/list") { header("X-Member-Id", MEMBER_ID) }.status shouldBe HttpStatusCode.Forbidden
                client.post("/test/create") { header("X-Member-Id", MEMBER_ID) }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("no session at all is Unauthenticated, not Forbidden") {
            testApp {
                client.get("/test/list").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("BOARD can create/read/update/archive a contact but is Forbidden from eraseContact") {
            testApp {
                val createResp = client.post("/test/create") { header("X-Member-Id", BOARD_ID) }
                createResp.status shouldBe HttpStatusCode.OK
                val id = createResp.bodyAsText()

                client.get("/test/get/$id") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.OK
                client.post("/test/erase/$id") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("ADMIN can erase a contact -- CrmContactTable/CrmInteractionTable rows are gone afterwards") {
            testApp {
                val id = client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val contactUuid = Uuid.parse(id)
                transaction {
                    CrmContactTable.selectAll().where { CrmContactTable.id eq contactUuid }.count()
                } shouldBe 1L

                client.post("/test/erase/$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK

                transaction {
                    CrmContactTable.selectAll().where { CrmContactTable.id eq contactUuid }.count()
                } shouldBe 0L
            }
        }

        // ── Validation ───────────────────────────────────────────────────────────────

        test("blank displayName is rejected with BadRequestException (422)") {
            testApp {
                client.post("/test/create-blank-name") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.fromValue(422)
            }
        }

        test("lawfulBasis CONSENT without consentSource is rejected with BadRequestException") {
            testApp {
                client
                    .post("/test/create-consent-no-source") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.fromValue(422)
            }
        }

        // ── Email uniqueness ─────────────────────────────────────────────────────────

        test("a duplicate email (case-insensitive) is rejected with ConflictException") {
            testApp {
                client.post("/test/create-with-email/dup@example.org") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                client
                    .post("/test/create-with-email/DUP@example.org") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("two contacts with no email at all are both allowed (multiple NULLs under the unique index)") {
            testApp {
                client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
            }
        }

        // ── recordInteraction denormalization ───────────────────────────────────────

        test("recordInteraction updates the owning contact's last_interaction_at and retention_review_due_at") {
            testApp {
                val id = client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val before =
                    transaction {
                        CrmContactTable
                            .selectAll()
                            .where {
                                CrmContactTable.id eq
                                    Uuid.parse(
                                        id,
                                    )
                            }.single()[CrmContactTable.retentionReviewDueAt]
                    }
                client.post("/test/record-interaction/$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                val (lastInteraction, dueAfter) =
                    transaction {
                        val row = CrmContactTable.selectAll().where { CrmContactTable.id eq Uuid.parse(id) }.single()
                        row[CrmContactTable.lastInteractionAt] to row[CrmContactTable.retentionReviewDueAt]
                    }
                (lastInteraction != null) shouldBe true
                (dueAfter != before) shouldBe true
            }
        }

        // ── recordInteraction monotonic last_interaction_at (review finding "Datenregression") ─

        test("a backdated (nachgetragene) occurredAt never REGRESSES last_interaction_at") {
            testApp {
                val id = client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                // First interaction: recent (server default "now").
                client.post("/test/record-interaction/$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                val afterFirst =
                    transaction {
                        CrmContactTable
                            .selectAll()
                            .where {
                                CrmContactTable.id eq
                                    Uuid.parse(
                                        id,
                                    )
                            }.single()[CrmContactTable.lastInteractionAt]
                    }

                // Second interaction: a long-past date nachgetragen.
                client
                    .post("/test/record-interaction-with/$id?occurredAt=2020-01-01T10:00:00") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.OK

                val afterBackdate =
                    transaction {
                        CrmContactTable
                            .selectAll()
                            .where {
                                CrmContactTable.id eq
                                    Uuid.parse(
                                        id,
                                    )
                            }.single()[CrmContactTable.lastInteractionAt]
                    }
                afterBackdate shouldBe afterFirst
            }
        }

        test("a newer occurredAt still advances last_interaction_at") {
            testApp {
                val id = client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client
                    .post("/test/record-interaction-with/$id?occurredAt=2020-01-01T10:00:00") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.OK
                client
                    .post("/test/record-interaction-with/$id?occurredAt=2024-06-01T10:00:00") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.OK

                val lastInteraction =
                    transaction {
                        CrmContactTable
                            .selectAll()
                            .where {
                                CrmContactTable.id eq
                                    Uuid.parse(
                                        id,
                                    )
                            }.single()[CrmContactTable.lastInteractionAt]
                    }
                lastInteraction shouldBe LocalDateTime.parse("2024-06-01T10:00:00")
            }
        }

        // ── recordInteraction server-side validation (review finding "keine Eingabevalidierung") ─

        test("recordInteraction rejects a blank summary with BadRequestException (422)") {
            testApp {
                val id = client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client
                    .post("/test/record-interaction-with/$id?summary=%20%20") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.fromValue(422)
            }
        }

        test("recordInteraction rejects a summary longer than 4000 characters with BadRequestException (422)") {
            testApp {
                val id = client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client
                    .post("/test/record-interaction-with/$id?summary=${"x".repeat(4001)}") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.fromValue(422)
            }
        }

        test("recordInteraction rejects a future occurredAt with BadRequestException (422)") {
            testApp {
                val id = client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client
                    .post("/test/record-interaction-with/$id?occurredAt=2099-01-01T10:00:00") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.fromValue(422)
            }
        }

        // ── memberId/externalDonorId linkage (review finding "Roher 500 statt Conflict") ────────

        test("a duplicate memberId is rejected with ConflictException, not a raw 500") {
            testApp {
                client.post("/test/create-with-member/$MEMBER_ID") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                client
                    .post("/test/create-with-member/$MEMBER_ID") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("a well-formed but nonexistent memberId is rejected with NotFoundException, not a raw 500") {
            testApp {
                client
                    .post("/test/create-with-member/00000000-0000-0000-0000-0000000000ff") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("a duplicate externalDonorId is rejected with ConflictException, not a raw 500") {
            testApp {
                val donorId =
                    transaction { insertExternalDonor() }
                client
                    .post("/test/create-with-external-donor/$donorId") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.OK
                client
                    .post("/test/create-with-external-donor/$donorId") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("a well-formed but nonexistent externalDonorId is rejected with NotFoundException, not a raw 500") {
            testApp {
                client
                    .post("/test/create-with-external-donor/00000000-0000-0000-0000-0000000000ff") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── updateContact (review finding "unerreichbar und ungetestet") ───────────────

        test("updateContact changes displayName and does not reject the contact's own unchanged email") {
            testApp {
                val id = client.post("/test/create-with-email/self@example.org") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client
                    .post("/test/update/$id?displayName=Neuer%20Name&email=self@example.org") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.OK
                val displayName =
                    transaction {
                        CrmContactTable
                            .selectAll()
                            .where {
                                CrmContactTable.id eq
                                    Uuid.parse(
                                        id,
                                    )
                            }.single()[CrmContactTable.displayName]
                    }
                displayName shouldBe "Neuer Name"
            }
        }

        test("updateContact still rejects an email already used by ANOTHER contact") {
            testApp {
                client.post("/test/create-with-email/taken@example.org") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.OK
                val id = client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client
                    .post("/test/update/$id?displayName=Test&email=taken@example.org") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        // ── archive/unarchive roundtrip ──────────────────────────────────────────────

        test("archive then unarchive roundtrips archivedAt from set back to null") {
            testApp {
                val id = client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client.post("/test/archive/$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                transaction {
                    CrmContactTable.selectAll().where { CrmContactTable.id eq Uuid.parse(id) }.single()[CrmContactTable.archivedAt]
                } shouldNotBe null

                client.post("/test/unarchive/$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                transaction {
                    CrmContactTable.selectAll().where { CrmContactTable.id eq Uuid.parse(id) }.single()[CrmContactTable.archivedAt]
                } shouldBe null
            }
        }

        // ── withdrawConsent (review finding "technisch unmoeglich") ─────────────────────

        test("withdrawConsent sets consentWithdrawnAt AND flips mayReceiveEmail to false") {
            testApp {
                val id = client.post("/test/create-with-consent/consent@example.org") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                // Asserted BEFORE the withdrawal too -- otherwise a `mayReceiveEmail` that ignored
                // the withdrawal entirely (always `false`, say) would pass the post-withdrawal
                // assertion for the wrong reason.
                client.get("/test/may-receive-email/$id") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "true"

                client.post("/test/withdraw-consent/$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                val row = transaction { CrmContactTable.selectAll().where { CrmContactTable.id eq Uuid.parse(id) }.single() }
                (row[CrmContactTable.consentWithdrawnAt] != null) shouldBe true
                client.get("/test/may-receive-email/$id") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "false"
            }
        }

        test("withdrawConsent on a contact with no consentGivenAt is rejected with BadRequestException (422)") {
            testApp {
                val id = client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client.post("/test/withdraw-consent/$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.fromValue(422)
            }
        }

        // ── updateContact preserves consent evidence across a lawful-basis change (review finding
        //    "Rechtsgrundlagen-Wechsel nach einem Widerruf endet in einer Sackgasse") ────────────

        test(
            "updateContact after withdrawConsent still succeeds when switching lawfulBasis away from " +
                "CONSENT, and consentGivenAt/consentWithdrawnAt both remain set",
        ) {
            testApp {
                val id = client.post("/test/create-with-consent/consent@example.org") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client.post("/test/withdraw-consent/$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK

                // Mirrors the client's edit form: switching the basis away from CONSENT submits
                // consentSource=null/consentGivenAt=null. Before the fix this hit the
                // chk_crm_contact_withdrawal_requires_consent CHECK (consent_given_at nulled while
                // consent_withdrawn_at stayed set) and surfaced as a misleading Conflict.
                client
                    .post("/test/update-lawful-basis/$id?lawfulBasis=LEGITIMATE_INTEREST") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.OK

                val row = transaction { CrmContactTable.selectAll().where { CrmContactTable.id eq Uuid.parse(id) }.single() }
                row[CrmContactTable.lawfulBasis] shouldBe CrmLawfulBasis.LEGITIMATE_INTEREST
                (row[CrmContactTable.consentGivenAt] != null) shouldBe true
                (row[CrmContactTable.consentWithdrawnAt] != null) shouldBe true
            }
        }

        test(
            "updateContact switching lawfulBasis away from CONSENT (no prior withdrawal) preserves the " +
                "Art. 7(1) consentGivenAt/consentSource evidence instead of silently erasing it",
        ) {
            testApp {
                val id = client.post("/test/create-with-consent/consent@example.org") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client
                    .post("/test/update-lawful-basis/$id?lawfulBasis=CONTRACT") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.OK

                val row = transaction { CrmContactTable.selectAll().where { CrmContactTable.id eq Uuid.parse(id) }.single() }
                row[CrmContactTable.lawfulBasis] shouldBe CrmLawfulBasis.CONTRACT
                row[CrmContactTable.consentGivenAt] shouldBe LocalDateTime(2026, 1, 1, 0, 0)
                row[CrmContactTable.consentSource] shouldBe "Infostand"
            }
        }

        // ── updateContact re-consent reverses a prior withdrawal (review finding "ein Widerruf ist
        //    unumkehrbar") ──────────────────────────────────────────────────────────────────────

        test(
            "updateContact with a NEW consentGivenAt after withdrawConsent clears consentWithdrawnAt " +
                "and restores mayReceiveEmail",
        ) {
            testApp {
                val id = client.post("/test/create-with-consent/consent@example.org") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client.post("/test/withdraw-consent/$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                client.get("/test/may-receive-email/$id") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "false"
                val withdrawnAt =
                    transaction { CrmContactTable.selectAll().where { CrmContactTable.id eq Uuid.parse(id) }.single() }[
                        CrmContactTable.consentWithdrawnAt,
                    ]

                // A later, fresh consent (Infostand, "now"), mirroring the edit form re-submitting a
                // changed consentGivenAt -- Art. 7(3) Satz 2 DSGVO: a withdrawal does not bar a later
                // new consent. `consentGivenAt=NOW` resolves server-side to DbClock.nowLocalDateTime()
                // at request time -- see /test/update-with-consent's own KDoc for why this can no
                // longer be a hardcoded future ISO literal.
                client
                    .post(
                        "/test/update-with-consent/$id?consentSource=Infostand%202026-09&consentGivenAt=NOW",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.OK

                val row = transaction { CrmContactTable.selectAll().where { CrmContactTable.id eq Uuid.parse(id) }.single() }
                (row[CrmContactTable.consentGivenAt]!! > withdrawnAt!!) shouldBe true
                row[CrmContactTable.consentSource] shouldBe "Infostand 2026-09"
                row[CrmContactTable.consentWithdrawnAt] shouldBe null
                client.get("/test/may-receive-email/$id") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "true"
            }
        }

        test(
            "updateContact re-submitting the SAME unchanged consentGivenAt after withdrawConsent leaves " +
                "the withdrawal standing",
        ) {
            testApp {
                val id = client.post("/test/create-with-consent/consent@example.org") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client.post("/test/withdraw-consent/$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK

                // Same consentGivenAt as create-with-consent (2026-01-01T00:00) -- not a new consent
                // event, so the withdrawal must NOT be silently undone by an unrelated edit.
                client
                    .post(
                        "/test/update-with-consent/$id?consentSource=Infostand&consentGivenAt=2026-01-01T00:00:00",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.OK

                val row = transaction { CrmContactTable.selectAll().where { CrmContactTable.id eq Uuid.parse(id) }.single() }
                (row[CrmContactTable.consentWithdrawnAt] != null) shouldBe true
                client.get("/test/may-receive-email/$id") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "false"
            }
        }

        // ── updateContact must NOT reverse a withdrawal via a corrected/backdated consentGivenAt
        //    that still precedes it (review finding "jede Aenderung des Einwilligungs-Zeitstempels
        //    hebt einen Widerruf auf") ────────────────────────────────────────────────────────────

        test(
            "updateContact correcting a typo in the EXISTING consentGivenAt (still before the " +
                "withdrawal) does NOT reverse the withdrawal",
        ) {
            testApp {
                val id = client.post("/test/create-with-consent/consent@example.org") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client.post("/test/withdraw-consent/$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK

                // The original create-with-consent value is 2026-01-01T00:00 -- an operator later
                // discovers the CORRECT time-of-day was 05:00, still on the same day, still well
                // before the (real-time, "now") withdrawal. This is an Art. 16 correction of the
                // ORIGINAL consent record, not a new consent event.
                client
                    .post(
                        "/test/update-with-consent/$id?consentSource=Infostand&consentGivenAt=2026-01-01T05:00:00",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.OK

                val row = transaction { CrmContactTable.selectAll().where { CrmContactTable.id eq Uuid.parse(id) }.single() }
                row[CrmContactTable.consentGivenAt] shouldBe LocalDateTime(2026, 1, 1, 5, 0)
                (row[CrmContactTable.consentWithdrawnAt] != null) shouldBe true
                client.get("/test/may-receive-email/$id") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "false"
            }
        }

        test(
            "updateContact backdating consentGivenAt to an OLDER, previously-unrecorded consent form " +
                "(still before the withdrawal) does NOT reverse the withdrawal",
        ) {
            testApp {
                val id = client.post("/test/create-with-consent/consent@example.org") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client.post("/test/withdraw-consent/$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK

                // An OLDER paper consent form (2025-06-01) surfaces after the withdrawal -- it
                // chronologically PRECEDES the withdrawal, so Art. 7(3) Satz 2 DSGVO does not let it
                // reactivate anything (only a genuinely LATER new consent does).
                client
                    .post(
                        "/test/update-with-consent/$id?consentSource=Altformular&consentGivenAt=2025-06-01T00:00:00",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.OK

                val row = transaction { CrmContactTable.selectAll().where { CrmContactTable.id eq Uuid.parse(id) }.single() }
                row[CrmContactTable.consentGivenAt] shouldBe LocalDateTime(2025, 6, 1, 0, 0)
                (row[CrmContactTable.consentWithdrawnAt] != null) shouldBe true
                client.get("/test/may-receive-email/$id") { header("X-Member-Id", ADMIN_ID) }.bodyAsText() shouldBe "false"
            }
        }

        // ── updateContact clearConsentEvidence explicitly erases a wrongly-recorded consent (review
        //    finding "eine irrtümlich erfasste Einwilligung lässt sich über keinen Codepfad wieder
        //    entfernen") ─────────────────────────────────────────────────────────────────────────

        test(
            "updateContact with clearConsentEvidence=true erases consentSource/consentGivenAt/" +
                "consentWithdrawnAt once lawfulBasis is no longer CONSENT",
        ) {
            testApp {
                val id = client.post("/test/create-with-consent/consent@example.org") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client
                    .post("/test/update-clear-consent/$id?lawfulBasis=LEGITIMATE_INTEREST") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.OK

                val row = transaction { CrmContactTable.selectAll().where { CrmContactTable.id eq Uuid.parse(id) }.single() }
                row[CrmContactTable.consentSource] shouldBe null
                row[CrmContactTable.consentGivenAt] shouldBe null
                row[CrmContactTable.consentWithdrawnAt] shouldBe null
                row[CrmContactTable.lawfulBasis] shouldBe CrmLawfulBasis.LEGITIMATE_INTEREST
            }
        }

        test("updateContact rejects clearConsentEvidence=true while lawfulBasis stays CONSENT with BadRequestException (422)") {
            testApp {
                val id = client.post("/test/create-with-consent/consent@example.org") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client
                    .post("/test/update-clear-consent/$id?lawfulBasis=CONSENT") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.fromValue(422)

                // Rejected -- the original evidence must still be intact, not half-erased.
                val row = transaction { CrmContactTable.selectAll().where { CrmContactTable.id eq Uuid.parse(id) }.single() }
                row[CrmContactTable.consentSource] shouldBe "Infostand"
                row[CrmContactTable.consentGivenAt] shouldBe LocalDateTime(2026, 1, 1, 0, 0)
            }
        }

        // ── createContact/updateContact reject a consentGivenAt in the future (review finding
        //    "consentGivenAt darf beliebig weit in der Zukunft liegen") ─────────────────────────

        test("createContact rejects a consentGivenAt in the future with BadRequestException (422)") {
            testApp {
                client
                    .post("/test/create-with-consent-at/future@example.org?consentGivenAt=2099-01-01T00:00:00") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.fromValue(422)
            }
        }

        test("updateContact rejects a consentGivenAt in the future with BadRequestException (422)") {
            testApp {
                val id = client.post("/test/create-with-consent/consent@example.org") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client
                    .post(
                        "/test/update-with-consent/$id?consentSource=Infostand&consentGivenAt=2099-01-01T00:00:00",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.fromValue(422)
            }
        }

        // ── updateContact's evidence-preservation fallback: overriding (non-null) branch (review
        //    finding "die Rueckfall-Logik ist nur in ihrem null-Zweig getestet") ──────────────────

        test(
            "updateContact with non-null consentSource/consentGivenAt writes the NEW values, not the " +
                "existing ones -- the overriding branch of the evidence-preservation fallback",
        ) {
            testApp {
                val id = client.post("/test/create-with-consent/consent@example.org") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()

                // Existing evidence is consentSource="Infostand"/consentGivenAt=2026-01-01T00:00 (see
                // /test/create-with-consent). Submitting DIFFERENT non-null values must overwrite them
                // -- if the `?:` fallback were ever inverted to `existing.x ?: input.x`, this would
                // stay silently on the old values and this assertion would catch it.
                client
                    .post(
                        "/test/update-with-consent/$id?consentSource=Website-Formular&consentGivenAt=2026-03-05T08:30:00",
                    ) { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.OK

                val row = transaction { CrmContactTable.selectAll().where { CrmContactTable.id eq Uuid.parse(id) }.single() }
                row[CrmContactTable.consentSource] shouldBe "Website-Formular"
                row[CrmContactTable.consentGivenAt] shouldBe LocalDateTime(2026, 3, 5, 8, 30)
            }
        }

        // ── listContacts pagination (review finding "Offset-Paging ist ungetestet") ─────────────

        test("listContacts offset-paging is deterministic and complete when every displayName ties") {
            testApp {
                // Same displayName on all five -- without the `id` tie-breaker
                // (`CrmContactStore.list`'s own KDoc), two separate offset-paged requests are not
                // guaranteed to agree on ordering.
                (1..5).forEach {
                    client.post("/test/create-with-email/tie$it@example.org") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                        HttpStatusCode.OK
                }
                // Ground truth: one single unpaged request, using the SAME `ORDER BY` the paged
                // requests below use. Deliberately NOT re-derived via a Kotlin-side `.sorted()` of
                // the raw ids -- the DB's own UUID comparison semantics are what actually decides
                // page boundaries, and are not guaranteed to agree with `String.sorted()`.
                val fullOrder =
                    client
                        .get("/test/list-page?limit=5&offset=0") { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                        .split("|")[1]
                        .split(",")
                fullOrder.size shouldBe 5

                val page1 =
                    client
                        .get("/test/list-page?limit=2&offset=0") { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                        .split("|")
                val page2 =
                    client
                        .get("/test/list-page?limit=2&offset=2") { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                        .split("|")
                val page3 =
                    client
                        .get("/test/list-page?limit=2&offset=4") { header("X-Member-Id", ADMIN_ID) }
                        .bodyAsText()
                        .split("|")

                page1[0] shouldBe "5"
                page2[0] shouldBe "5"
                page3[0] shouldBe "5"
                val allPagedIds = (page1[1].split(",") + page2[1].split(",") + page3[1].split(",")).filter { it.isNotBlank() }
                allPagedIds shouldBe fullOrder
            }
        }

        test("listContacts with offset >= total returns an empty page, not an error") {
            testApp {
                client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                val page = client.get("/test/list-page?offset=100") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                page[0] shouldBe "1"
                page[1] shouldBe ""
            }
        }

        test("listContacts coerces a limit of 0 up to at least 1, never an empty page") {
            testApp {
                client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                val page = client.get("/test/list-page?limit=0") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                page[1].split(",").size shouldBe 1
            }
        }

        test("listContacts caps an oversized limit at MAX_PAGE_SIZE (200) regardless of the requested value") {
            testApp {
                insertBulkContacts(205)
                val page = client.get("/test/list-page?limit=100000") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split("|")
                page[0] shouldBe "205"
                page[1].split(",").size shouldBe 200
            }
        }

        // ── listContacts filter accumulation (review finding "Filter-Akkumulation ... nie mit
        //    einem anderen Wert als dem Default aufgerufen") -- filterType AND onlyRetentionOverdue
        //    AND (NOT includeArchived) must all apply together, not any single one alone ─────────

        test("listContacts ANDs filterType + onlyRetentionOverdue + includeArchived=false together") {
            testApp {
                val overdueMatch =
                    Uuid.parse(client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.bodyAsText())
                val wrongTypeOverdue =
                    Uuid.parse(
                        client
                            .post("/test/create-with-email/wrongtype@example.org") { header("X-Member-Id", ADMIN_ID) }
                            .bodyAsText(),
                    )
                val notOverdue = Uuid.parse(client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.bodyAsText())
                val archivedOverdue = Uuid.parse(client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.bodyAsText())

                val past = LocalDateTime(2000, 1, 1, 0, 0)
                transaction {
                    CrmContactTable.update({ CrmContactTable.id eq overdueMatch }) { it[retentionReviewDueAt] = past }
                    CrmContactTable.update({ CrmContactTable.id eq wrongTypeOverdue }) {
                        it[retentionReviewDueAt] = past
                        it[contactType] = CrmContactType.SYMPATHISANT
                    }
                    CrmContactTable.update({ CrmContactTable.id eq archivedOverdue }) {
                        it[retentionReviewDueAt] = past
                        it[archivedAt] = past
                    }
                    // notOverdue: retentionReviewDueAt left at its default (24 months out) on purpose.
                }

                val page =
                    client
                        .get("/test/list-page?filterType=INTERESSENT&onlyRetentionOverdue=true&includeArchived=false") {
                            header("X-Member-Id", ADMIN_ID)
                        }.bodyAsText()
                        .split("|")
                page[0] shouldBe "1"
                page[1] shouldBe overdueMatch.toString()
            }
        }

        // ── listInteractions pagination (review finding "Offset-Paging ist ungetestet") ─────────

        test("listInteractions offset-paging is deterministic and complete when every occurredAt ties") {
            testApp {
                val id = client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                repeat(5) {
                    client
                        .post("/test/record-interaction-with/$id?occurredAt=2026-03-01T10:00:00") {
                            header("X-Member-Id", ADMIN_ID)
                        }.status shouldBe HttpStatusCode.OK
                }
                // Ground truth via the SAME `ORDER BY occurredAt DESC, id ASC` the paged requests
                // below use -- see the analogous comment on the listContacts pagination test above
                // for why this is not re-derived via a Kotlin-side `.sorted()`.
                val fullOrder =
                    client.get("/test/list-interactions-page/$id?limit=5&offset=0") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                fullOrder.split(",").size shouldBe 5

                val page1 =
                    client.get("/test/list-interactions-page/$id?limit=2&offset=0") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val page2 =
                    client.get("/test/list-interactions-page/$id?limit=2&offset=2") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                val page3 =
                    client.get("/test/list-interactions-page/$id?limit=2&offset=4") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()

                val allPagedIds = (page1.split(",") + page2.split(",") + page3.split(",")).filter { it.isNotBlank() }
                allPagedIds shouldBe fullOrder.split(",")
            }
        }

        test("listInteractions caps an oversized limit at MAX_PAGE_SIZE (200)") {
            testApp {
                val id = client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                repeat(205) {
                    client.post("/test/record-interaction/$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                }
                val page = client.get("/test/list-interactions-page/$id?limit=100000") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                page.split(",").size shouldBe 200
            }
        }

        // ── Rate limiting ────────────────────────────────────────────────────────────

        test("createContact honours its rate limiter -- exhausting it yields Conflict") {
            testApp(contactWriteRateLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) {
                client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("recordInteraction honours its OWN rate limiter -- exhausting it yields Conflict") {
            testApp(interactionWriteRateLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) {
                val id = client.post("/test/create") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                client.post("/test/record-interaction/$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
                client.post("/test/record-interaction/$id") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.Conflict
            }
        }
    })

private fun StatusPagesConfig.installCrmExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.fromValue(422)) }
}

private fun Route.registerCrmTestRoutes(
    contactWriteRateLimiter: FederationInboxRateLimiter,
    interactionWriteRateLimiter: FederationInboxRateLimiter,
) {
    fun service(call: io.ktor.server.application.ApplicationCall) =
        CrmService(
            call = call,
            contactWriteRateLimiter = contactWriteRateLimiter,
            interactionWriteRateLimiter = interactionWriteRateLimiter,
        )

    get("/test/list") {
        val page = service(call).listContacts()
        call.respondText(page.items.size.toString())
    }
    // ── Pagination/filter test surface (review finding "Offset-Paging ist ungetestet") ─────
    // Exposes [ICrmService.listContacts]'s full parameter set plus [CrmContactPageDto.total] and
    // the returned ORDER (not just a count, unlike "/test/list" above) so tests can assert
    // determinism across separate offset-paged requests.
    get("/test/list-page") {
        val q = call.request.queryParameters
        val filterType = q["filterType"]?.let { CrmContactType.valueOf(it) }
        val page =
            service(call).listContacts(
                filterType = filterType,
                onlyRetentionOverdue = q["onlyRetentionOverdue"]?.toBoolean() ?: false,
                includeArchived = q["includeArchived"]?.toBoolean() ?: false,
                limit = q["limit"]?.toInt() ?: 50,
                offset = q["offset"]?.toInt() ?: 0,
            )
        call.respondText("${page.total}|${page.items.joinToString(",") { it.id }}")
    }
    get("/test/list-interactions-page/{contactId}") {
        val q = call.request.queryParameters
        val interactions =
            service(call).listInteractions(
                contactId = call.parameters["contactId"]!!,
                limit = q["limit"]?.toInt() ?: 50,
                offset = q["offset"]?.toInt() ?: 0,
            )
        call.respondText(interactions.joinToString(",") { it.id })
    }
    get("/test/get/{id}") {
        val dto = service(call).getContact(call.parameters["id"]!!)
        call.respondText(dto.id)
    }
    get("/test/may-receive-email/{id}") {
        val dto = service(call).getContact(call.parameters["id"]!!)
        call.respondText(dto.mayReceiveEmail.toString())
    }
    post("/test/create") {
        val dto =
            service(call).createContact(
                CrmContactInput(
                    displayName = "Test Kontakt",
                    email = null,
                    phone = null,
                    street = null,
                    postalCode = null,
                    city = null,
                    country = null,
                    contactType = CrmContactType.INTERESSENT,
                    lawfulBasis = CrmLawfulBasis.LEGITIMATE_INTEREST,
                    consentSource = null,
                    consentGivenAt = null,
                    externalDonorId = null,
                    memberId = null,
                ),
            )
        call.respondText(dto.id)
    }
    post("/test/create-blank-name") {
        val dto =
            service(call).createContact(
                CrmContactInput(
                    displayName = "   ",
                    email = null,
                    phone = null,
                    street = null,
                    postalCode = null,
                    city = null,
                    country = null,
                    contactType = CrmContactType.INTERESSENT,
                    lawfulBasis = CrmLawfulBasis.LEGITIMATE_INTEREST,
                    consentSource = null,
                    consentGivenAt = null,
                    externalDonorId = null,
                    memberId = null,
                ),
            )
        call.respondText(dto.id)
    }
    post("/test/create-consent-no-source") {
        val dto =
            service(call).createContact(
                CrmContactInput(
                    displayName = "Test Kontakt",
                    email = null,
                    phone = null,
                    street = null,
                    postalCode = null,
                    city = null,
                    country = null,
                    contactType = CrmContactType.INTERESSENT,
                    lawfulBasis = CrmLawfulBasis.CONSENT,
                    consentSource = null,
                    consentGivenAt = null,
                    externalDonorId = null,
                    memberId = null,
                ),
            )
        call.respondText(dto.id)
    }
    post("/test/create-with-email/{email}") {
        val email = call.parameters["email"]
        val dto =
            service(call).createContact(
                CrmContactInput(
                    displayName = "Test Kontakt",
                    email = email,
                    phone = null,
                    street = null,
                    postalCode = null,
                    city = null,
                    country = null,
                    contactType = CrmContactType.INTERESSENT,
                    lawfulBasis = CrmLawfulBasis.LEGITIMATE_INTEREST,
                    consentSource = null,
                    consentGivenAt = null,
                    externalDonorId = null,
                    memberId = null,
                ),
            )
        call.respondText(dto.id)
    }
    post("/test/create-with-member/{memberId}") {
        val dto =
            service(call).createContact(
                CrmContactInput(
                    displayName = "Test Kontakt",
                    email = null,
                    phone = null,
                    street = null,
                    postalCode = null,
                    city = null,
                    country = null,
                    contactType = CrmContactType.INTERESSENT,
                    lawfulBasis = CrmLawfulBasis.LEGITIMATE_INTEREST,
                    consentSource = null,
                    consentGivenAt = null,
                    externalDonorId = null,
                    memberId = call.parameters["memberId"],
                ),
            )
        call.respondText(dto.id)
    }
    post("/test/create-with-external-donor/{externalDonorId}") {
        val dto =
            service(call).createContact(
                CrmContactInput(
                    displayName = "Test Kontakt",
                    email = null,
                    phone = null,
                    street = null,
                    postalCode = null,
                    city = null,
                    country = null,
                    contactType = CrmContactType.INTERESSENT,
                    lawfulBasis = CrmLawfulBasis.LEGITIMATE_INTEREST,
                    consentSource = null,
                    consentGivenAt = null,
                    externalDonorId = call.parameters["externalDonorId"],
                    memberId = null,
                ),
            )
        call.respondText(dto.id)
    }
    post("/test/create-with-consent/{email}") {
        val dto =
            service(call).createContact(
                CrmContactInput(
                    displayName = "Test Kontakt",
                    email = call.parameters["email"],
                    phone = null,
                    street = null,
                    postalCode = null,
                    city = null,
                    country = null,
                    contactType = CrmContactType.INTERESSENT,
                    lawfulBasis = CrmLawfulBasis.CONSENT,
                    consentSource = "Infostand",
                    consentGivenAt = LocalDateTime(2026, 1, 1, 0, 0),
                    externalDonorId = null,
                    memberId = null,
                ),
            )
        call.respondText(dto.id)
    }
    // Like /test/create-with-consent/{email}, but with a caller-supplied consentGivenAt -- used by
    // the "consentGivenAt darf nicht in der Zukunft liegen" review finding's create-side test.
    post("/test/create-with-consent-at/{email}") {
        val dto =
            service(call).createContact(
                CrmContactInput(
                    displayName = "Test Kontakt",
                    email = call.parameters["email"],
                    phone = null,
                    street = null,
                    postalCode = null,
                    city = null,
                    country = null,
                    contactType = CrmContactType.INTERESSENT,
                    lawfulBasis = CrmLawfulBasis.CONSENT,
                    consentSource = "Infostand",
                    consentGivenAt = LocalDateTime.parse(call.request.queryParameters["consentGivenAt"]!!),
                    externalDonorId = null,
                    memberId = null,
                ),
            )
        call.respondText(dto.id)
    }
    post("/test/update/{id}") {
        val dto =
            service(call).updateContact(
                id = call.parameters["id"]!!,
                input =
                    CrmContactInput(
                        displayName = call.request.queryParameters["displayName"] ?: "Test Kontakt",
                        email = call.request.queryParameters["email"],
                        phone = null,
                        street = null,
                        postalCode = null,
                        city = null,
                        country = null,
                        contactType = CrmContactType.INTERESSENT,
                        lawfulBasis = CrmLawfulBasis.LEGITIMATE_INTEREST,
                        consentSource = null,
                        consentGivenAt = null,
                        externalDonorId = null,
                        memberId = null,
                    ),
            )
        call.respondText(dto.id)
    }
    // Mirrors `CrmContactsScreen.kt`'s edit form: `consentSource`/`consentGivenAt` are submitted as
    // `null` whenever the chosen `lawfulBasis` is not CONSENT (see review finding "Rechtsgrundlagen-
    // Wechsel ... endet in einer Sackgasse") -- used to test that [CrmContactStore.update] preserves
    // any EXISTING consent evidence instead of erasing it.
    post("/test/update-lawful-basis/{id}") {
        val lawfulBasis = CrmLawfulBasis.valueOf(call.request.queryParameters["lawfulBasis"]!!)
        service(call).updateContact(
            id = call.parameters["id"]!!,
            input =
                CrmContactInput(
                    displayName = "Test Kontakt",
                    email = null,
                    phone = null,
                    street = null,
                    postalCode = null,
                    city = null,
                    country = null,
                    contactType = CrmContactType.INTERESSENT,
                    lawfulBasis = lawfulBasis,
                    consentSource = null,
                    consentGivenAt = null,
                    externalDonorId = null,
                    memberId = null,
                ),
        )
        call.respondText("ok")
    }
    // Mirrors `CrmContactsScreen.kt`'s edit form submitting a NON-null, possibly-changed
    // `consentSource`/`consentGivenAt` while staying on lawfulBasis=CONSENT -- used to test both
    // the overriding (non-null) branch of `CrmContactStore.update`'s evidence-preservation fallback
    // and the withdrawal-reversibility behaviour (review findings "die Rueckfall-Logik ist nur in
    // ihrem null-Zweig getestet" and "ein Widerruf ist unumkehrbar").
    //
    // `consentGivenAt=NOW` is a sentinel (instead of a hardcoded ISO literal) that resolves to
    // `DbClock.nowLocalDateTime()` at REQUEST time -- i.e. strictly after any withdrawal the same
    // test already triggered via /test/withdraw-consent, and never later than the "now" this same
    // request's own `CrmContactPolicy.validate` future-check compares against. A hardcoded future
    // literal (the pre-fix "2026-09-14T10:00:00") would fail that future-check now that it exists
    // (review finding "consentGivenAt darf beliebig weit in der Zukunft liegen").
    post("/test/update-with-consent/{id}") {
        val rawConsentGivenAt = call.request.queryParameters["consentGivenAt"]
        val dto =
            service(call).updateContact(
                id = call.parameters["id"]!!,
                input =
                    CrmContactInput(
                        displayName = "Test Kontakt",
                        // Mirrors the edit form re-submitting the EXISTING email, not blanking it --
                        // matches the email every /test/create-with-consent/{email} caller below uses.
                        // update() replaces `email` wholesale (unlike consentSource/consentGivenAt, it
                        // has no existing-value fallback), so a hardcoded `null` here would silently
                        // null out the contact's email and break `mayReceiveEmail` for an unrelated
                        // reason.
                        email = "consent@example.org",
                        phone = null,
                        street = null,
                        postalCode = null,
                        city = null,
                        country = null,
                        contactType = CrmContactType.INTERESSENT,
                        lawfulBasis = CrmLawfulBasis.CONSENT,
                        consentSource = call.request.queryParameters["consentSource"],
                        consentGivenAt =
                            when (rawConsentGivenAt) {
                                null -> null
                                "NOW" -> DbClock.nowLocalDateTime()
                                else -> LocalDateTime.parse(rawConsentGivenAt)
                            },
                        externalDonorId = null,
                        memberId = null,
                    ),
            )
        call.respondText(dto.id)
    }
    // Mirrors the edit form switching lawfulBasis away from CONSENT AND explicitly requesting
    // erasure of a wrongly-recorded consent (review finding "eine irrtümlich erfasste Einwilligung
    // lässt sich über keinen Codepfad wieder entfernen").
    post("/test/update-clear-consent/{id}") {
        val lawfulBasis = CrmLawfulBasis.valueOf(call.request.queryParameters["lawfulBasis"]!!)
        val dto =
            service(call).updateContact(
                id = call.parameters["id"]!!,
                input =
                    CrmContactInput(
                        displayName = "Test Kontakt",
                        email = "consent@example.org",
                        phone = null,
                        street = null,
                        postalCode = null,
                        city = null,
                        country = null,
                        contactType = CrmContactType.INTERESSENT,
                        lawfulBasis = lawfulBasis,
                        consentSource = null,
                        consentGivenAt = null,
                        externalDonorId = null,
                        memberId = null,
                        clearConsentEvidence = true,
                    ),
            )
        call.respondText(dto.id)
    }
    post("/test/archive/{id}") {
        service(call).archiveContact(call.parameters["id"]!!)
        call.respondText("ok")
    }
    post("/test/unarchive/{id}") {
        service(call).unarchiveContact(call.parameters["id"]!!)
        call.respondText("ok")
    }
    post("/test/withdraw-consent/{id}") {
        service(call).withdrawConsent(call.parameters["id"]!!)
        call.respondText("ok")
    }
    post("/test/erase/{id}") {
        service(call).eraseContact(call.parameters["id"]!!)
        call.respondText("ok")
    }
    post("/test/record-interaction/{id}") {
        service(call).recordInteraction(
            CrmInteractionInput(
                contactId = call.parameters["id"]!!,
                occurredAt = null,
                kind = CrmInteractionKind.NOTE,
                summary = "Test-Notiz",
            ),
        )
        call.respondText("ok")
    }
    post("/test/record-interaction-with/{id}") {
        val occurredAt = call.request.queryParameters["occurredAt"]?.let { LocalDateTime.parse(it) }
        val summary = call.request.queryParameters["summary"] ?: "Test-Notiz"
        service(call).recordInteraction(
            CrmInteractionInput(
                contactId = call.parameters["id"]!!,
                occurredAt = occurredAt,
                kind = CrmInteractionKind.NOTE,
                summary = summary,
            ),
        )
        call.respondText("ok")
    }
}

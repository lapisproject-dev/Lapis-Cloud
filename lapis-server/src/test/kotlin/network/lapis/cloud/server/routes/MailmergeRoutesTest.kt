package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.module
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PostingSide
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDateTime as KotlinLocalDateTime

/**
 * Exercises [registerMailmergeRoutes] end to end via the real `application { module() }` (not a
 * throwaway routing setup) -- these are plain Ktor HTTP routes, so the real
 * `Application.module()` StatusPages/routing wiring is exactly what a real request goes through,
 * unlike the "call the service class directly" idiom used for RPC service tests.
 */
class MailmergeRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()
        val createdContributionIds = mutableListOf<Uuid>()
        val createdJournalEntryIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        beforeTest {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[taxExemptionAuthority] = null
                    it[taxExemptionDate] = null
                    it[bankIban] = "DE02120300000000202051"
                    it[bankBic] = "BYLADEM1001"
                }
            }
        }

        afterSpec {
            transaction {
                if (createdJournalEntryIds.isNotEmpty()) {
                    PostingTable.deleteWhere { PostingTable.journalEntryId inList createdJournalEntryIds }
                    JournalEntryTable.deleteWhere { JournalEntryTable.id inList createdJournalEntryIds }
                }
                if (createdContributionIds.isNotEmpty()) {
                    ContributionTable.deleteWhere { ContributionTable.id inList createdContributionIds }
                }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    PostingTable.deleteWhere { PostingTable.ledgerAccountId inList createdLedgerAccountIds }
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                }
                if (createdTierIds.isNotEmpty()) {
                    MembershipTierTable.deleteWhere { MembershipTierTable.id inList createdTierIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    DocumentVersionTable.deleteWhere { DocumentVersionTable.uploadedBy inList createdMemberIds }
                    DocumentTable.deleteWhere { DocumentTable.createdBy inList createdMemberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[taxExemptionAuthority] = null
                    it[taxExemptionDate] = null
                }
            }
        }

        fun createMember(
            email: String,
            role: AccountRole,
            withAddress: Boolean,
            displayName: String = "Mailmerge Testmitglied",
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[MemberTable.displayName] = displayName
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                    if (withAddress) {
                        it[street] = "Musterstrasse 5"
                        it[postalCode] = "38102"
                        it[city] = "Braunschweig"
                        it[country] = "Deutschland"
                    }
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun createTier(): Uuid {
            val id = Uuid.random()
            transaction {
                MembershipTierTable.insert {
                    it[MembershipTierTable.id] = id
                    it[name] = "Mailmerge-Tarif"
                    it[description] = "Testtarif"
                    it[contributionAmount] = BigDecimal("30.00")
                    it[billingInterval] = BillingInterval.YEARLY
                    it[active] = true
                }
            }
            createdTierIds += id
            return id
        }

        fun createContribution(
            memberId: Uuid,
            tierId: Uuid,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ContributionTable.insert {
                    it[ContributionTable.id] = id
                    it[ContributionTable.memberId] = memberId
                    it[ContributionTable.membershipTierId] = tierId
                    it[periodStart] = LocalDate(2026, 1, 1)
                    it[periodEnd] = LocalDate(2026, 12, 31)
                    it[amountDue] = BigDecimal("30.00")
                    it[status] = ContributionStatus.OPEN
                    it[paidAt] = null
                    it[paidAmount] = null
                    it[note] = null
                    it[createdAt] = KotlinLocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            createdContributionIds += id
            return id
        }

        fun createLedgerAccount(
            number: String,
            type: LedgerAccountType,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                LedgerAccountTable.insert {
                    it[LedgerAccountTable.id] = id
                    it[accountNumber] = number
                    it[name] = "Mailmerge-Konto $number"
                    it[accountClass] = 0
                    it[LedgerAccountTable.type] = type
                    it[active] = true
                    it[reserveType] = null
                    it[isCashRegister] = false
                }
            }
            createdLedgerAccountIds += id
            return id
        }

        fun postDonationEntry(
            donorMemberId: Uuid,
            createdBy: Uuid,
            status: JournalEntryStatus,
            kasse: Uuid,
            spenden: Uuid,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                JournalEntryTable.insert {
                    it[JournalEntryTable.id] = id
                    it[entryDate] = LocalDate(2026, 6, 15)
                    it[description] = "Spende"
                    it[voucherReference] = "BELEG-1"
                    it[JournalEntryTable.createdBy] = createdBy
                    it[JournalEntryTable.status] = status
                    it[postedAt] = if (status == JournalEntryStatus.POSTED) KotlinLocalDateTime(2026, 6, 15, 12, 0) else null
                    it[createdAt] = KotlinLocalDateTime(2026, 6, 15, 11, 0)
                    it[JournalEntryTable.donorMemberId] = donorMemberId
                }
                PostingTable.insert {
                    it[PostingTable.id] = Uuid.random()
                    it[journalEntryId] = id
                    it[ledgerAccountId] = kasse
                    it[side] = PostingSide.DEBIT
                    it[amount] = BigDecimal("100.00")
                    it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                    it[costCenterId] = null
                }
                PostingTable.insert {
                    it[PostingTable.id] = Uuid.random()
                    it[journalEntryId] = id
                    it[ledgerAccountId] = spenden
                    it[side] = PostingSide.CREDIT
                    it[amount] = BigDecimal("100.00")
                    it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                    it[costCenterId] = null
                }
            }
            createdJournalEntryIds += id
            return id
        }

        // ── Beitragsrechnung ─────────────────────────────────────────────

        test("invoice.pdf: 401 without X-Member-Id, 403 for MEMBER, 404 for unknown contribution") {
            testApplication {
                application { module() }

                val unauthenticated = client.get("/api/mailmerge/contributions/${Uuid.random()}/invoice.pdf")
                unauthenticated.status shouldBe HttpStatusCode.Unauthorized

                val member = createMember("mailmerge-member-1@example.org", AccountRole.MEMBER, withAddress = true)
                val forbidden =
                    client.get("/api/mailmerge/contributions/${Uuid.random()}/invoice.pdf") { header("X-Member-Id", member.toString()) }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val treasurer = createMember("mailmerge-treasurer-1@example.org", AccountRole.TREASURER, withAddress = true)
                val notFound =
                    client.get("/api/mailmerge/contributions/${Uuid.random()}/invoice.pdf") { header("X-Member-Id", treasurer.toString()) }
                notFound.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("invoice.pdf: 409 when the billed member has no complete postal address") {
            testApplication {
                application { module() }

                val treasurer = createMember("mailmerge-treasurer-2@example.org", AccountRole.TREASURER, withAddress = true)
                val memberNoAddress = createMember("mailmerge-member-noaddress@example.org", AccountRole.MEMBER, withAddress = false)
                val tier = createTier()
                val contribution = createContribution(memberNoAddress, tier)

                val response =
                    client.get("/api/mailmerge/contributions/$contribution/invoice.pdf") { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("invoice.pdf: 200 with application/pdf + %PDF magic bytes, and archives a Document/DocumentVersion row") {
            testApplication {
                application { module() }

                val treasurer = createMember("mailmerge-treasurer-3@example.org", AccountRole.BOARD, withAddress = true)
                val member = createMember("mailmerge-member-withaddress@example.org", AccountRole.MEMBER, withAddress = true)
                val tier = createTier()
                val contribution = createContribution(member, tier)

                val documentCountBefore = transaction { DocumentTable.selectAll().count() }

                val response =
                    client.get("/api/mailmerge/contributions/$contribution/invoice.pdf") { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.contentType()?.withoutParameters() shouldBe ContentType.Application.Pdf
                val bytes = response.bodyAsBytes()
                String(bytes, 0, 4, Charsets.US_ASCII) shouldBe "%PDF"
                (response.headers[HttpHeaders.ContentDisposition] != null) shouldBe true

                val documentCountAfter = transaction { DocumentTable.selectAll().count() }
                (documentCountAfter > documentCountBefore) shouldBe true
            }
        }

        // ── Spendenbescheinigung ─────────────────────────────────────────

        test("receipt.pdf: 404 unknown journalEntryId, 409 for a DRAFT entry, 409 for an entry with no donorMemberId") {
            testApplication {
                application { module() }

                val treasurer = createMember("mailmerge-treasurer-4@example.org", AccountRole.TREASURER, withAddress = true)
                val donor = createMember("mailmerge-donor-1@example.org", AccountRole.MEMBER, withAddress = true)
                val kasse = createLedgerAccount("9501", LedgerAccountType.ASSET)
                val spenden = createLedgerAccount("9601", LedgerAccountType.INCOME)

                val notFound =
                    client.get("/api/mailmerge/donations/${Uuid.random()}/receipt.pdf") { header("X-Member-Id", treasurer.toString()) }
                notFound.status shouldBe HttpStatusCode.NotFound

                val draftEntry = postDonationEntry(donor, treasurer, JournalEntryStatus.DRAFT, kasse, spenden)
                val draftResponse =
                    client.get("/api/mailmerge/donations/$draftEntry/receipt.pdf") { header("X-Member-Id", treasurer.toString()) }
                draftResponse.status shouldBe HttpStatusCode.Conflict

                val noDonorEntryId = Uuid.random()
                transaction {
                    JournalEntryTable.insert {
                        it[id] = noDonorEntryId
                        it[entryDate] = LocalDate(2026, 6, 1)
                        it[description] = "Keine Spende"
                        it[voucherReference] = null
                        it[createdBy] = treasurer
                        it[status] = JournalEntryStatus.POSTED
                        it[postedAt] = KotlinLocalDateTime(2026, 6, 1, 12, 0)
                        it[createdAt] = KotlinLocalDateTime(2026, 6, 1, 11, 0)
                        it[donorMemberId] = null
                    }
                }
                createdJournalEntryIds += noDonorEntryId
                val noDonorResponse =
                    client.get("/api/mailmerge/donations/$noDonorEntryId/receipt.pdf") { header("X-Member-Id", treasurer.toString()) }
                noDonorResponse.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("receipt.pdf: 409 while OrganizationSettings tax-exemption fields are unset") {
            testApplication {
                application { module() }

                val treasurer = createMember("mailmerge-treasurer-5@example.org", AccountRole.TREASURER, withAddress = true)
                val donor = createMember("mailmerge-donor-2@example.org", AccountRole.MEMBER, withAddress = true)
                val kasse = createLedgerAccount("9502", LedgerAccountType.ASSET)
                val spenden = createLedgerAccount("9602", LedgerAccountType.INCOME)
                val entry = postDonationEntry(donor, treasurer, JournalEntryStatus.POSTED, kasse, spenden)

                val response =
                    client.get("/api/mailmerge/donations/$entry/receipt.pdf") { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "receipt.pdf: 200 with application/pdf + %PDF magic bytes once tax-exemption fields are configured, and archives a Document row",
        ) {
            testApplication {
                application { module() }

                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[taxExemptionAuthority] = "Finanzamt Braunschweig"
                        it[taxExemptionDate] = LocalDate(2025, 1, 15)
                    }
                }

                val treasurer = createMember("mailmerge-treasurer-6@example.org", AccountRole.ADMIN, withAddress = true)
                val donor = createMember("mailmerge-donor-3@example.org", AccountRole.MEMBER, withAddress = true)
                val kasse = createLedgerAccount("9503", LedgerAccountType.ASSET)
                val spenden = createLedgerAccount("9603", LedgerAccountType.INCOME)
                val entry = postDonationEntry(donor, treasurer, JournalEntryStatus.POSTED, kasse, spenden)

                val documentCountBefore = transaction { DocumentTable.selectAll().count() }

                val response =
                    client.get("/api/mailmerge/donations/$entry/receipt.pdf") { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.contentType()?.withoutParameters() shouldBe ContentType.Application.Pdf
                val bytes = response.bodyAsBytes()
                String(bytes, 0, 4, Charsets.US_ASCII) shouldBe "%PDF"

                val documentCountAfter = transaction { DocumentTable.selectAll().count() }
                (documentCountAfter > documentCountBefore) shouldBe true
            }
        }

        // ── Einladung ────────────────────────────────────────────────────

        test("invitations: 403 for a plain MEMBER, 400 for no recipients, 200 for BOARD with valid recipients (no Document archived)") {
            testApplication {
                application { module() }

                val member = createMember("mailmerge-member-2@example.org", AccountRole.MEMBER, withAddress = true)
                val board = createMember("mailmerge-board-1@example.org", AccountRole.BOARD, withAddress = true)
                val recipient = createMember("mailmerge-recipient-1@example.org", AccountRole.MEMBER, withAddress = true)

                fun multipartBody(recipientIds: List<Uuid>): MultiPartFormDataContent =
                    MultiPartFormDataContent(
                        formData {
                            append("title", "Einladung zur Mitgliederversammlung")
                            append("eventDateTime", "2026-09-12T18:30:00")
                            append("location", "Vereinsheim")
                            append("bodyText", "Wir laden herzlich ein.")
                            recipientIds.forEach { append("recipientMemberId", it.toString()) }
                        },
                    )

                val forbidden =
                    client.post("/api/mailmerge/invitations") {
                        header("X-Member-Id", member.toString())
                        setBody(multipartBody(listOf(recipient)))
                    }
                forbidden.status shouldBe HttpStatusCode.Forbidden

                val noRecipients =
                    client.post("/api/mailmerge/invitations") {
                        header("X-Member-Id", board.toString())
                        setBody(multipartBody(emptyList()))
                    }
                noRecipients.status shouldBe HttpStatusCode.BadRequest

                val documentCountBefore = transaction { DocumentTable.selectAll().count() }
                val success =
                    client.post("/api/mailmerge/invitations") {
                        header("X-Member-Id", board.toString())
                        setBody(multipartBody(listOf(recipient)))
                    }
                success.status shouldBe HttpStatusCode.OK
                success.contentType()?.withoutParameters() shouldBe ContentType.Application.Pdf
                val bytes = success.bodyAsBytes()
                String(bytes, 0, 4, Charsets.US_ASCII) shouldBe "%PDF"

                val documentCountAfter = transaction { DocumentTable.selectAll().count() }
                documentCountAfter shouldBe documentCountBefore
            }
        }

        test("invitations: 413 when recipientMemberId parts exceed the DoS cap") {
            testApplication {
                application { module() }

                val board = createMember("mailmerge-board-2@example.org", AccountRole.BOARD, withAddress = true)
                // One over the documented MAX_INVITATION_RECIPIENTS cap (1000) -- ids need not
                // resolve to real members, the cap is enforced while parsing the multipart
                // request, before any recipient lookup happens.
                val tooManyRecipients = (1..1001).map { Uuid.random() }

                val response =
                    client.post("/api/mailmerge/invitations") {
                        header("X-Member-Id", board.toString())
                        setBody(
                            MultiPartFormDataContent(
                                formData {
                                    append("title", "Einladung")
                                    append("eventDateTime", "2026-09-12T18:30:00")
                                    append("location", "Vereinsheim")
                                    append("bodyText", "Wir laden herzlich ein.")
                                    tooManyRecipients.forEach { append("recipientMemberId", it.toString()) }
                                },
                            ),
                        )
                    }
                response.status shouldBe HttpStatusCode.PayloadTooLarge
            }
        }

        test("invitations: 400 when bodyText exceeds the max length") {
            testApplication {
                application { module() }

                val board = createMember("mailmerge-board-3@example.org", AccountRole.BOARD, withAddress = true)
                val recipient = createMember("mailmerge-recipient-2@example.org", AccountRole.MEMBER, withAddress = true)
                val hugeBodyText = "x".repeat(20_001)

                val response =
                    client.post("/api/mailmerge/invitations") {
                        header("X-Member-Id", board.toString())
                        setBody(
                            MultiPartFormDataContent(
                                formData {
                                    append("title", "Einladung")
                                    append("eventDateTime", "2026-09-12T18:30:00")
                                    append("location", "Vereinsheim")
                                    append("bodyText", hugeBodyText)
                                    append("recipientMemberId", recipient.toString())
                                },
                            ),
                        )
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        // ── Security-fix regression coverage ────────────────────────────

        test(
            "receipt.pdf: donor displayName with quotes/backslashes/CRLF-adjacent characters does not break Content-Disposition or crash",
        ) {
            testApplication {
                application { module() }

                transaction {
                    OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                        it[taxExemptionAuthority] = "Finanzamt Braunschweig"
                        it[taxExemptionDate] = LocalDate(2025, 1, 15)
                    }
                }

                val treasurer = createMember("mailmerge-treasurer-7@example.org", AccountRole.TREASURER, withAddress = true)
                val donor =
                    createMember(
                        "mailmerge-donor-4@example.org",
                        AccountRole.MEMBER,
                        withAddress = true,
                        displayName = """Spender "Anfuehrung" \Backslash\ /Slash/""",
                    )
                val kasse = createLedgerAccount("9504", LedgerAccountType.ASSET)
                val spenden = createLedgerAccount("9604", LedgerAccountType.INCOME)
                val entry = postDonationEntry(donor, treasurer, JournalEntryStatus.POSTED, kasse, spenden)

                val response =
                    client.get("/api/mailmerge/donations/$entry/receipt.pdf") { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.OK
                val disposition = response.headers[HttpHeaders.ContentDisposition]
                (disposition != null) shouldBe true
                // Quotes/backslashes/slashes from the donor's raw displayName must be stripped
                // before reaching the header -- the sanitized file name is fully deterministic.
                disposition!!.contains("Spendenbescheinigung-Spender Anfuehrung Backslash Slash-2026-06-15.pdf") shouldBe true
                val bytes = response.bodyAsBytes()
                String(bytes, 0, 4, Charsets.US_ASCII) shouldBe "%PDF"
            }
        }

        test("invoice.pdf: non-Latin-1 (Georgian/Cyrillic) member displayName still renders a PDF instead of a 500") {
            testApplication {
                application { module() }

                val treasurer = createMember("mailmerge-treasurer-8@example.org", AccountRole.TREASURER, withAddress = true)
                val member =
                    createMember(
                        "mailmerge-member-nonlatin@example.org",
                        AccountRole.MEMBER,
                        withAddress = true,
                        displayName = "ირაკლი Ирина",
                    )
                val tier = createTier()
                val contribution = createContribution(member, tier)

                val response =
                    client.get("/api/mailmerge/contributions/$contribution/invoice.pdf") { header("X-Member-Id", treasurer.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.contentType()?.withoutParameters() shouldBe ContentType.Application.Pdf
                val bytes = response.bodyAsBytes()
                String(bytes, 0, 4, Charsets.US_ASCII) shouldBe "%PDF"
            }
        }
    })

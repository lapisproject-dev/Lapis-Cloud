package network.lapis.cloud.server.crm

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.CrmContactTable
import network.lapis.cloud.server.db.generated.CrmInteractionTable
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.CrmContactInput
import network.lapis.cloud.shared.domain.CrmContactType
import network.lapis.cloud.shared.domain.CrmInteractionInput
import network.lapis.cloud.shared.domain.CrmInteractionKind
import network.lapis.cloud.shared.domain.CrmLawfulBasis
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val ADMIN = Uuid.parse("00000000-0000-0000-0000-000000000001")

/**
 * V1.9.38 -- `crm_interaction.occurred_at` / `crm_contact.last_interaction_at` / `retention_review_due_at` are class-B wall-clocks:
 * a typed-in time is stored as typed, an omitted one becomes the ORGANIZATION wall-clock of "now" (never a UTC stamp mixed into the
 * same column), and the retention reminder is compared with the organization wall-clock.
 */
class CrmTimeZoneTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { TimeTestSupport.resetOrganizationZone() }
        afterTest {
            transaction {
                CrmInteractionTable.deleteAll()
                CrmContactTable.deleteAll()
            }
            TimeTestSupport.resetOrganizationZone()
        }

        fun newContact(): Uuid {
            val dto =
                transaction {
                    CrmContactStore.create(
                        input =
                            CrmContactInput(
                                displayName = "Zeitzonen-Test",
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
                        createdBy = ADMIN,
                    )
                }
            return Uuid.parse(dto.id)
        }

        fun interaction(
            contactId: Uuid,
            occurredAt: LocalDateTime?,
        ) = transaction {
            CrmContactStore.recordInteraction(
                input =
                    CrmInteractionInput(
                        contactId = contactId.toString(),
                        occurredAt = occurredAt,
                        kind = CrmInteractionKind.NOTE,
                        summary = "Zeitzonen-Test",
                    ),
                recordedBy = ADMIN,
            )
        }

        fun contactRow(id: Uuid) = transaction { CrmContactTable.selectAll().where { CrmContactTable.id eq id }.single() }

        test("an interaction without a typed-in time is stored as the Berlin wall-clock of now (10:00Z is 12:00)") {
            TimeTestSupport.withServerClock(instant = "2026-07-01T10:00:00Z") {
                val id = newContact()
                interaction(id, occurredAt = null)
                val row = contactRow(id)
                row[CrmContactTable.lastInteractionAt] shouldBe LocalDateTime(2026, 7, 1, 12, 0)
                row[CrmContactTable.retentionReviewDueAt] shouldBe LocalDateTime(2028, 7, 1, 12, 0)
            }
        }

        test("a typed-in time is stored exactly as typed, whatever the organization zone is") {
            TimeTestSupport.setOrganizationZone("Asia/Tbilisi")
            TimeTestSupport.withServerClock(instant = "2026-07-01T10:00:00Z") {
                val id = newContact()
                interaction(id, occurredAt = LocalDateTime(2026, 6, 30, 9, 15))
                contactRow(id)[CrmContactTable.lastInteractionAt] shouldBe LocalDateTime(2026, 6, 30, 9, 15)
            }
        }

        test("a brand-new contact's retention reminder counts from the organization wall-clock of its creation") {
            TimeTestSupport.withServerClock(instant = "2026-07-01T10:00:00Z") {
                val id = newContact()
                contactRow(id)[CrmContactTable.retentionReviewDueAt] shouldBe LocalDateTime(2028, 7, 1, 12, 0)
            }
        }
    })

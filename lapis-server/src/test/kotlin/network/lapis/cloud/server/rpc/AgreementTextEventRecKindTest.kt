package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.agreementtexts.AgreementTextStore
import network.lapis.cloud.server.agreementtexts.AgreementTextTestState
import network.lapis.cloud.server.agreementtexts.EventCaptureConsentDefault
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.encounter.encounterApp
import network.lapis.cloud.server.encounter.failure
import network.lapis.cloud.server.encounter.runAs
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AgreementTextDraftInput
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.rpc.AgreementTextInvalidException
import network.lapis.cloud.shared.rpc.AgreementTextLayoutInvalidException
import network.lapis.cloud.shared.rpc.AgreementTextVersionLabelTakenException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val ADMIN_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")
private val BOARD_ID = Uuid.parse("00000000-0000-0000-0000-000000000002")
private val MEMBER_ID = Uuid.parse("00000000-0000-0000-0000-000000000004")

private const val VALID_BODY = "Eigene Überschrift\n\nPunkt eins\n\nPunkt zwei\n\nPunkt drei\n\nDetailabsatz."

/**
 * V1.9.156 -- the kind `EVENT_REC` through the agreement-text administration: ADMIN only; the SAME paragraph rule as the encounter notices (headline of at
 * most 160 characters, three key points of at most 600) but NOT their label reserve (no "+rec": a 50-character label passes); the placeholder can be
 * neither the text nor the label of a published version; the overview never counts anybody; the audit entries never hold the text.
 */
class AgreementTextEventRecKindTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterEach { AgreementTextTestState.reset() }
        afterSpec { AgreementTextTestState.reset() }

        val today by lazy { OrganizationTimeZone.today() }

        fun save(
            label: String,
            body: String,
        ) = AgreementTextDraftInput(kind = AgreementTextKind.EVENT_REC, versionLabel = label, validFrom = today, body = body)

        test(
            "the overview lists EVENT_REC as an unpublished placeholder with its own label, and counts nobody -- before and after a publication",
        ) {
            encounterApp {
                fun kind(overview: network.lapis.cloud.shared.domain.AgreementTextOverviewDto) =
                    overview.kinds.single { it.kind == AgreementTextKind.EVENT_REC }
                val before = client.runAs(member = ADMIN_ID) { call -> AgreementTextAdminService(call = call).getOverview() }.getOrThrow()
                kind(before).placeholderActive shouldBe true
                kind(before).builtinVersionLabel shouldBe EventCaptureConsentDefault.VERSION
                kind(before).olderAcknowledgmentCount shouldBe null
                val draft =
                    client
                        .runAs(
                            member = ADMIN_ID,
                        ) { call -> AgreementTextAdminService(call = call).saveDraft(save("2026-10-18.v1", VALID_BODY)) }
                        .getOrThrow()
                client
                    .runAs(member = ADMIN_ID) { call ->
                        AgreementTextAdminService(
                            call = call,
                        ).publishDraft(kind = AgreementTextKind.EVENT_REC, expectedDraftSha256 = draft.sha256)
                    }.getOrThrow()
                val after = client.runAs(member = ADMIN_ID) { call -> AgreementTextAdminService(call = call).getOverview() }.getOrThrow()
                kind(after).placeholderActive shouldBe false
                // Art. 9 aggregate: ALWAYS zero, never an attendance number
                kind(after).olderAcknowledgmentCount shouldBe 0
            }
        }

        test("only an ADMIN may save or publish") {
            encounterApp {
                listOf(BOARD_ID, MEMBER_ID).forEach { who ->
                    client
                        .runAs(member = who) { call -> AgreementTextAdminService(call = call).saveDraft(save("2026-10-18.v1", VALID_BODY)) }
                        .failure<ForbiddenException>()
                }
            }
        }

        test(
            "the paragraph rule is enforced: three paragraphs, a long headline and a long key point are refused with the layout exception",
        ) {
            encounterApp {
                listOf(
                    "Überschrift\n\nPunkt eins\n\nPunkt zwei",
                    "${"h".repeat(161)}\n\nPunkt eins\n\nPunkt zwei\n\nPunkt drei",
                    "Überschrift\n\nPunkt eins\n\n${"x".repeat(601)}\n\nPunkt drei",
                ).forEach { body ->
                    client
                        .runAs(member = ADMIN_ID) { call -> AgreementTextAdminService(call = call).saveDraft(save("2026-10-18.v1", body)) }
                        .failure<AgreementTextLayoutInvalidException>()
                }
                val atLimits = "${"h".repeat(160)}\n\nPunkt eins\n\n${"k".repeat(600)}\n\nPunkt drei"
                client
                    .runAs(
                        member = ADMIN_ID,
                    ) { call -> AgreementTextAdminService(call = call).saveDraft(save("2026-10-18.v1", atLimits)) }
                    .getOrThrow()
            }
        }

        test(
            "no label reserve: a 50-character label passes (the ENC kinds stop at 46 because the system adds '+rec'), a 51-character one does not",
        ) {
            encounterApp {
                client
                    .runAs(
                        member = ADMIN_ID,
                    ) { call -> AgreementTextAdminService(call = call).saveDraft(save("a".repeat(50), VALID_BODY)) }
                    .getOrThrow()
                client
                    .runAs(member = ADMIN_ID) { call -> AgreementTextAdminService(call = call).saveDraft(save("a".repeat(51), VALID_BODY)) }
                    .failure<AgreementTextInvalidException>()
            }
        }

        test("the placeholder is neither the text nor the label of a published version") {
            encounterApp {
                client
                    .runAs(member = ADMIN_ID) { call ->
                        AgreementTextAdminService(call = call).saveDraft(save("2026-10-18.v1", EventCaptureConsentDefault.TEXT))
                    }.failure<AgreementTextInvalidException>()
                client
                    .runAs(member = ADMIN_ID) { call ->
                        AgreementTextAdminService(call = call).saveDraft(save(EventCaptureConsentDefault.VERSION, VALID_BODY))
                    }.failure<AgreementTextVersionLabelTakenException>()
            }
        }

        test("a published EVENT_REC text is an ordinary version of its kind; the audit entries never hold the text") {
            encounterApp {
                val draft =
                    client
                        .runAs(
                            member = ADMIN_ID,
                        ) { call -> AgreementTextAdminService(call = call).saveDraft(save("2026-10-18.v1", VALID_BODY)) }
                        .getOrThrow()
                val summary =
                    client
                        .runAs(member = ADMIN_ID) { call ->
                            AgreementTextAdminService(
                                call = call,
                            ).publishDraft(kind = AgreementTextKind.EVENT_REC, expectedDraftSha256 = draft.sha256)
                        }.getOrThrow()
                summary.kind shouldBe AgreementTextKind.EVENT_REC
                transaction { AgreementTextStore.currentVersion(AgreementTextKind.EVENT_REC)!!.versionLabel } shouldBe "2026-10-18.v1"
                transaction { AgreementTextStore.currentVersion(AgreementTextKind.ENC_CHURCH) } shouldBe null
                val entries =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityType eq AuditEntityType.AGREEMENT_TEXT }
                            .map { it[AuditLogEntryTable.action] to (it[AuditLogEntryTable.afterSnapshot] ?: "") }
                    }
                entries.count { it.first == AuditAction.POST } shouldBe 1
                entries.forEach { (_, snapshot) ->
                    snapshot shouldNotContain "Überschrift"
                    snapshot shouldNotContain "Punkt eins"
                }
            }
        }

        test("getVersion returns the built-in placeholder with its own date and hash") {
            encounterApp {
                val builtin =
                    client
                        .runAs(member = ADMIN_ID) { call ->
                            AgreementTextAdminService(
                                call = call,
                            ).getVersion(kind = AgreementTextKind.EVENT_REC, versionLabel = EventCaptureConsentDefault.VERSION)
                        }.getOrThrow()
                builtin.body shouldBe EventCaptureConsentDefault.TEXT
                builtin.summary.sha256 shouldBe EventCaptureConsentDefault.SHA256
                builtin.summary.validFrom shouldBe LocalDate(2026, 10, 18)
                builtin.summary.sequenceNo shouldBe 0
            }
        }
    })

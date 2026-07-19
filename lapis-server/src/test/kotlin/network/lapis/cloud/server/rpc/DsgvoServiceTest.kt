package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.DsgvoAuditLogTable
import network.lapis.cloud.server.db.generated.ErasureRequestTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.MailingDeliveryLogTable
import network.lapis.cloud.server.db.generated.MailingListSubscriptionTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.db.generated.MailingMessageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.routes.registerDsgvoRoutes
import network.lapis.cloud.server.security.ForbiddenException
import network.lapis.cloud.server.security.UnauthenticatedException
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.DsgvoAuditAction
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.ErasureStatus
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/**
 * Proves the registry-driven design documented across the `dsgvo` package actually works end to
 * end: the HTTP export bundle and the RPC manifest both surface data from every registered
 * [network.lapis.cloud.server.dsgvo.PersonalDataContributor] (not just the ones easiest to
 * exercise), access control is self-or-ADMIN (export/request) vs. ADMIN-only (decide/execute/
 * audit), and erasure anonymizes/retains-with-reason where accounting or third-party retention
 * applies rather than hard-deleting indiscriminately — mirroring
 * [network.lapis.cloud.server.rpc.ServiceIntegrationTest]'s house style (throwaway routes calling
 * the service classes directly) rather than reverse-engineering Kilua RPC's wire format.
 *
 * Erasure targets are always a freshly created member on a dedicated, test-only membership tier
 * (see [createDsgvoTestMember]), never one of [DevSeedData]'s four fixed demo members or their
 * shared [DevSeedData.standardTierId]: those are reused by other Spec classes running in the same
 * test JVM (e.g. [ServiceIntegrationTest] asserts an exact `entries.size shouldBe 4` and an exact
 * `generated shouldBe 4` against the shared tier), and this file's whole point is to anonymize/
 * hard-delete data -- reusing a shared fixture as an erasure *subject*, or a shared tier for
 * `generateContributionsForPeriod`, would corrupt those exact-count assertions. [afterSpec] removes
 * every row this file creates so no state leaks into Spec classes that run afterwards either.
 */
class DsgvoServiceTest :
    FunSpec({
        val dsgvoTestTierId = Uuid.random()
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            transaction {
                MembershipTierTable.insert {
                    it[id] = dsgvoTestTierId
                    it[name] = "DSGVO-Testbeitrag"
                    it[description] = "Nur fuer DsgvoServiceTest -- nie in ServiceIntegrationTest sichtbar."
                    it[contributionAmount] = BigDecimal("10.00")
                    it[billingInterval] = BillingInterval.MONTHLY
                    it[active] = true
                }
            }
        }

        afterSpec { cleanUpDsgvoTestData(dsgvoTestTierId, createdMemberIds) }

        fun createDsgvoTestMember(
            email: String,
            role: AccountRole = AccountRole.BOARD,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "DSGVO Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.AKTIV
                    it[joinedAt] = LocalDate(2027, 1, 1)
                    it[membershipTierId] = dsgvoTestTierId
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

        test("export (manifest + full HTTP bundle) surfaces real data from every registered contributor") {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                    }
                    routing {
                        registerDsgvoRoutes()
                        post("/test/generate-contributions") {
                            val service = ContributionService(call)
                            val count = service.generateContributionsForPeriod(dsgvoTestTierId.toString(), PERIOD_START, PERIOD_END)
                            call.respondText(count.toString())
                        }
                        post("/test/mark-paid/{contributionId}") {
                            val service = ContributionService(call)
                            service.markContributionPaid(
                                call.parameters["contributionId"]!!,
                                LocalDateTime(2027, 3, 15, 12, 0),
                                BigDecimal("10.00"),
                                "Vertrauliche Buchhalter-Notiz",
                            )
                            call.respondText("ok")
                        }
                        get("/test/contributions/{memberId}") {
                            val service = ContributionService(call)
                            val list = service.listContributions(memberId = call.parameters["memberId"])
                            call.respondText(list.joinToString(",") { it.id })
                        }
                        post("/test/create-folder") {
                            val service = DocumentService(call)
                            val folder = service.createFolder("DSGVO-Test-Ordner")
                            call.respondText(folder.id)
                        }
                        post("/test/create-document/{folderId}") {
                            val service = DocumentService(call)
                            val doc =
                                service.createDocument(
                                    call.parameters["folderId"]!!,
                                    "DSGVO-Testdokument",
                                    DocumentAccessLevel.PUBLIC_MEMBERS,
                                )
                            call.respondText(doc.id)
                        }
                        post("/test/create-list") {
                            val service = MailingService(call)
                            val list = service.createMailingList("DSGVO-Testliste", null)
                            call.respondText(list.id)
                        }
                        post("/test/subscribe/{listId}") {
                            val service = MailingService(call)
                            service.subscribe(call.parameters["listId"]!!)
                            call.respondText("ok")
                        }
                        post("/test/draft/{listId}") {
                            val service = MailingService(call)
                            val message = service.createDraftMessage(call.parameters["listId"]!!, "DSGVO-Betreff", "DSGVO-Nachrichtentext")
                            call.respondText(message.id)
                        }
                        post("/test/send-mail/{messageId}") {
                            val service = MailingService(call)
                            service.sendMailingMessage(call.parameters["messageId"]!!)
                            call.respondText("ok")
                        }
                        post("/test/send-dm/{recipientId}") {
                            val service = DirectMessageService(call)
                            service.sendDirectMessage(call.parameters["recipientId"]!!, "Von Testmitglied gesendet")
                            call.respondText("ok")
                        }
                        get("/test/export-manifest/{memberId}") {
                            val service = DsgvoService(call)
                            val manifest = service.exportManifest(call.parameters["memberId"]!!)
                            call.respondText(manifest.sectionCounts.entries.joinToString(",") { "${it.key}=${it.value}" })
                        }
                        // V0.4.1: exercises the only production write path for the postal address
                        // (MemberService.updateMemberAddress) -- see IMemberService KDoc.
                        post("/test/update-address/{memberId}") {
                            val service = MemberService(call)
                            val query = call.request.queryParameters
                            val dto =
                                service.updateMemberAddress(
                                    call.parameters["memberId"]!!,
                                    query["street"],
                                    query["postalCode"],
                                    query["city"],
                                    query["country"],
                                )
                            call.respondText(dto.id)
                        }
                    }
                }

                val subject = createDsgvoTestMember("dsgvo-export-subject@example.org")
                val subjectHeader = subject.toString()

                // Contribution: TREASURER generates + marks paid with a note (later checked to
                // survive export verbatim, then to be scrubbed by erasure -- see the erasure test).
                // Uses the dedicated dsgvoTestTierId, not DevSeedData.standardTierId -- see class KDoc.
                client.post("/test/generate-contributions") { header("X-Member-Id", TREASURER_ID) }
                val contributionIds = client.get("/test/contributions/$subjectHeader") { header("X-Member-Id", TREASURER_ID) }.bodyAsText()
                val contributionId = contributionIds.split(",").first { it.isNotBlank() }
                client.post("/test/mark-paid/$contributionId") { header("X-Member-Id", TREASURER_ID) }

                // Document: the subject itself is BOARD-elevated (see createDsgvoTestMember) so it
                // can author its own document, exactly like DocumentPersonalData's "created_by"
                // walk expects.
                val folderId = client.post("/test/create-folder") { header("X-Member-Id", subjectHeader) }.bodyAsText()
                val documentId = client.post("/test/create-document/$folderId") { header("X-Member-Id", subjectHeader) }.bodyAsText()
                insertDocumentVersion(documentId, subject)

                // Communication: mailing list authored + subscribed + sent by the subject, plus a
                // direct message sent by the subject to TREASURER.
                val listId = client.post("/test/create-list") { header("X-Member-Id", subjectHeader) }.bodyAsText()
                client.post("/test/subscribe/$listId") { header("X-Member-Id", subjectHeader) }
                val messageId = client.post("/test/draft/$listId") { header("X-Member-Id", subjectHeader) }.bodyAsText()
                client.post("/test/send-mail/$messageId") { header("X-Member-Id", subjectHeader) }
                client.post("/test/send-dm/$TREASURER_ID") { header("X-Member-Id", subjectHeader) }

                // V0.4.1 postal address: written through the only production write path
                // (MemberService.updateMemberAddress, self-service here), then checked to survive
                // export verbatim, then to be nulled by erasure -- see the erasure test.
                client.post(
                    "/test/update-address/$subjectHeader" +
                        "?street=Musterstrasse-1&postalCode=38100&city=Braunschweig&country=DE",
                ) { header("X-Member-Id", subjectHeader) }

                // V0.4.1 donor attribution: a JournalEntry booked/created by TREASURER (not the
                // subject) but attributed to the subject as donorMemberId must still surface in the
                // subject's own export -- AccountingPersonalData's export walk matches donorMemberId
                // alongside createdBy (see that object's KDoc). Inserted directly, like
                // insertDocumentVersion below, since exercising the full postJournalEntry
                // double-entry/ledger-account machinery is out of scope for this DSGVO-focused test.
                insertDonationJournalEntry(subject, createdBy = Uuid.parse(TREASURER_ID))

                // Manifest (RPC surface): every registered contributor participates, and the
                // contributions count reflects the actual row Exposed query pulled (a JsonArray),
                // not a hardcoded placeholder.
                val manifestSelf = client.get("/test/export-manifest/$subjectHeader") { header("X-Member-Id", subjectHeader) }.bodyAsText()
                manifestSelf shouldContain "foundation=1"
                manifestSelf shouldContain "contributions=1"
                manifestSelf shouldContain "documents=1"
                manifestSelf shouldContain "communication=1"
                manifestSelf shouldContain "accounting=1"

                // ADMIN can export someone else's data too (self-or-ADMIN).
                val manifestByAdmin = client.get("/test/export-manifest/$subjectHeader") { header("X-Member-Id", ADMIN_ID) }
                manifestByAdmin.status shouldBe HttpStatusCode.OK

                // A third, unrelated member is neither the subject nor ADMIN -- forbidden both on
                // the RPC-style manifest call and on the dedicated HTTP export route.
                val manifestByOther = client.get("/test/export-manifest/$subjectHeader") { header("X-Member-Id", MEMBER_ID) }
                manifestByOther.status shouldBe HttpStatusCode.Forbidden

                val bundleByOther = client.get("/api/dsgvo/members/$subjectHeader/export") { header("X-Member-Id", MEMBER_ID) }
                bundleByOther.status shouldBe HttpStatusCode.Forbidden

                // The full HTTP bundle actually contains real per-entity field values pulled by
                // every contributor -- not just section row counts.
                val bundleText =
                    client
                        .get(
                            "/api/dsgvo/members/$subjectHeader/export",
                        ) { header("X-Member-Id", subjectHeader) }
                        .bodyAsText()
                bundleText shouldContain "\"foundation\""
                bundleText shouldContain "\"contributions\""
                bundleText shouldContain "\"documents\""
                bundleText shouldContain "\"communication\""
                bundleText shouldContain "\"accounting\""
                bundleText shouldContain "Vertrauliche Buchhalter-Notiz"
                bundleText shouldContain "DSGVO-Testdokument"
                bundleText shouldContain "DSGVO-Testliste"
                bundleText shouldContain "DSGVO-Betreff"
                bundleText shouldContain "Von Testmitglied gesendet"
                // V0.4.1 postal address fields, exported alongside displayName/email (see
                // FoundationPersonalData.export).
                bundleText shouldContain "Musterstrasse-1"
                bundleText shouldContain "38100"
                bundleText shouldContain "Braunschweig"
                // V0.4.1 donor attribution: the entry is present in the subject's own export and
                // is correctly attributed via the "donorMemberId" role (not "createdBy", which
                // belongs to TREASURER here) -- see AccountingPersonalData.export.
                bundleText shouldContain "\"role\":\"donorMemberId\""
                bundleText shouldContain "\"direction\":\"SENT\""

                // Every export call (manifest self, manifest by ADMIN, HTTP bundle by subject) is
                // audited -- accountability (Art. 5 Abs. 2 DSGVO), see DsgvoAuditLogTable KDoc.
                val auditCount =
                    transaction {
                        DsgvoAuditLogTable
                            .selectAll()
                            .where {
                                (DsgvoAuditLogTable.subjectMemberId eq subject) and
                                    (DsgvoAuditLogTable.action eq DsgvoAuditAction.EXPORT)
                            }.count()
                    }
                (auditCount >= 3) shouldBe true
            }
        }

        test(
            "erasure (ANONYMIZE, default): member anonymized, account hard-deleted, contribution/document " +
                "retained with reason, subscription hard-deleted, direct messages retained",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                        exception<ConflictException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Conflict)
                        }
                        exception<NotFoundException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.NotFound)
                        }
                    }
                    routing {
                        post("/test/generate-contributions") {
                            val service = ContributionService(call)
                            val count = service.generateContributionsForPeriod(dsgvoTestTierId.toString(), PERIOD_START, PERIOD_END)
                            call.respondText(count.toString())
                        }
                        post("/test/create-list") {
                            val service = MailingService(call)
                            val list = service.createMailingList("DSGVO-Loesch-Testliste", null)
                            call.respondText(list.id)
                        }
                        post("/test/subscribe/{listId}") {
                            val service = MailingService(call)
                            service.subscribe(call.parameters["listId"]!!)
                            call.respondText("ok")
                        }
                        post("/test/send-dm/{recipientId}") {
                            val service = DirectMessageService(call)
                            service.sendDirectMessage(call.parameters["recipientId"]!!, "Nachrichtentext, der erhalten bleiben muss")
                            call.respondText("ok")
                        }
                        post("/test/request-erasure/{subjectId}") {
                            val service = DsgvoService(call)
                            val request = service.requestErasure(call.parameters["subjectId"]!!, "Testmotion Art. 17 DSGVO")
                            call.respondText(request.id)
                        }
                        post("/test/decide/{requestId}/{approve}") {
                            val service = DsgvoService(call)
                            val request = service.decideErasure(call.parameters["requestId"]!!, call.parameters["approve"]!!.toBoolean())
                            call.respondText(request.status.name)
                        }
                        post("/test/execute/{requestId}") {
                            val service = DsgvoService(call)
                            val request = service.executeErasure(call.parameters["requestId"]!!)
                            call.respondText(request.status.name)
                        }
                        get("/test/list-erasure-requests") {
                            val service = DsgvoService(call)
                            call.respondText(service.listErasureRequests().joinToString(",") { it.id })
                        }
                        get("/test/list-audit") {
                            val service = DsgvoService(call)
                            call.respondText(service.listAuditLog().joinToString(",") { it.action.name })
                        }
                        post("/test/update-address/{memberId}") {
                            val service = MemberService(call)
                            val query = call.request.queryParameters
                            val dto =
                                service.updateMemberAddress(
                                    call.parameters["memberId"]!!,
                                    query["street"],
                                    query["postalCode"],
                                    query["city"],
                                    query["country"],
                                )
                            call.respondText(dto.id)
                        }
                    }
                }

                val subject = createDsgvoTestMember("dsgvo-erasure-subject@example.org")
                val subjectHeader = subject.toString()

                client.post("/test/generate-contributions") { header("X-Member-Id", TREASURER_ID) }
                val listId = client.post("/test/create-list") { header("X-Member-Id", subjectHeader) }.bodyAsText()
                client.post("/test/subscribe/$listId") { header("X-Member-Id", subjectHeader) }
                client.post("/test/send-dm/$TREASURER_ID") { header("X-Member-Id", subjectHeader) }
                client.post("/test/send-dm/$subjectHeader") { header("X-Member-Id", TREASURER_ID) }

                // V0.4.1 postal address: set before erasure so we can prove ANONYMIZE nulls all
                // four fields (see FoundationPersonalData.erase).
                client.post(
                    "/test/update-address/$subjectHeader" +
                        "?street=Alte-Strasse-5&postalCode=99999&city=Loeschstadt&country=DE",
                ) { header("X-Member-Id", subjectHeader) }

                // V0.4.1 donor attribution: a JournalEntry attributed to the subject as donor must
                // be reported as retained (never anonymized/deleted) by erasure -- GoBD/§257 HGB/
                // §147 AO, see AccountingPersonalData.erase KDoc.
                insertDonationJournalEntry(subject, createdBy = Uuid.parse(TREASURER_ID))

                // Non-ADMIN cannot list/decide/execute -- ADMIN-only, unlike export/request which
                // are self-or-ADMIN.
                val listByNonAdmin = client.get("/test/list-erasure-requests") { header("X-Member-Id", subjectHeader) }
                listByNonAdmin.status shouldBe HttpStatusCode.Forbidden

                val requestId = client.post("/test/request-erasure/$subjectHeader") { header("X-Member-Id", subjectHeader) }.bodyAsText()

                // executeErasure before approval is rejected -- irreversible step gated behind
                // the review workflow, not a direct call.
                val executeTooEarly = client.post("/test/execute/$requestId") { header("X-Member-Id", ADMIN_ID) }
                executeTooEarly.status shouldBe HttpStatusCode.Conflict

                val decided = client.post("/test/decide/$requestId/true") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                decided shouldBe ErasureStatus.APPROVED.name

                // Deciding twice on an already-decided request is rejected.
                val decidedAgain = client.post("/test/decide/$requestId/true") { header("X-Member-Id", ADMIN_ID) }
                decidedAgain.status shouldBe HttpStatusCode.Conflict

                val executed = client.post("/test/execute/$requestId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                executed shouldBe ErasureStatus.COMPLETED.name

                transaction {
                    val memberRow = MemberTable.selectAll().where { MemberTable.id eq subject }.single()
                    memberRow[MemberTable.displayName] shouldBe "Geloeschtes Mitglied"
                    memberRow[MemberTable.email] shouldContain "@deleted.invalid"
                    (memberRow[MemberTable.anonymizedAt] != null) shouldBe true
                    // V0.4.1 postal address: nulled alongside displayName/email, see
                    // FoundationPersonalData.erase.
                    memberRow[MemberTable.street] shouldBe null
                    memberRow[MemberTable.postalCode] shouldBe null
                    memberRow[MemberTable.city] shouldBe null
                    memberRow[MemberTable.country] shouldBe null

                    // V0.4.1 donor attribution: the JournalEntry row survives verbatim -- neither
                    // the row nor its donorMemberId FK is touched by erasure, see
                    // AccountingPersonalData.erase KDoc.
                    val donationEntry =
                        JournalEntryTable
                            .selectAll()
                            .where { JournalEntryTable.donorMemberId eq subject }
                            .single()
                    donationEntry[JournalEntryTable.description] shouldBe "DSGVO-Testspende"

                    val accountRemaining = AccountTable.selectAll().where { AccountTable.memberId eq subject }.count()
                    accountRemaining shouldBe 0L

                    val contributionNotes =
                        ContributionTable
                            .selectAll()
                            .where { ContributionTable.memberId eq subject }
                            .map { it[ContributionTable.note] }
                    (contributionNotes.isNotEmpty()) shouldBe true
                    contributionNotes.all { it == null } shouldBe true

                    val subscriptionsRemaining =
                        MailingListSubscriptionTable
                            .selectAll()
                            .where { MailingListSubscriptionTable.memberId eq subject }
                            .count()
                    subscriptionsRemaining shouldBe 0L

                    // ANONYMIZE (the default mode) never touches direct-message bodies -- only
                    // HARD_DELETE_WHERE_UNCONSTRAINED redacts the subject's own sent bodies (see
                    // the dedicated test below).
                    val sentBody =
                        DirectMessageTable
                            .selectAll()
                            .where { DirectMessageTable.senderId eq subject }
                            .single()[DirectMessageTable.body]
                    sentBody shouldBe "Nachrichtentext, der erhalten bleiben muss"

                    val receivedBody =
                        DirectMessageTable
                            .selectAll()
                            .where { DirectMessageTable.recipientId eq subject }
                            .single()[DirectMessageTable.body]
                    receivedBody shouldBe "Nachrichtentext, der erhalten bleiben muss"
                }

                // Every step of the workflow left one audit-trail entry -- ADMIN can read them all.
                val auditActions = client.get("/test/list-audit") { header("X-Member-Id", ADMIN_ID) }.bodyAsText().split(",")
                (auditActions.contains("ERASURE_REQUESTED")) shouldBe true
                (auditActions.contains("ERASURE_APPROVED")) shouldBe true
                (auditActions.contains("ERASURE_EXECUTED")) shouldBe true
            }
        }

        test(
            "erasure (HARD_DELETE_WHERE_UNCONSTRAINED): subject's own sent direct-message bodies are " +
                "redacted, received bodies are never touched",
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                        }
                        exception<ConflictException> { call, cause ->
                            call.respondText(cause.message, status = HttpStatusCode.Conflict)
                        }
                    }
                    routing {
                        post("/test/send-dm/{recipientId}") {
                            val service = DirectMessageService(call)
                            service.sendDirectMessage(call.parameters["recipientId"]!!, "Selbst gesendeter Text")
                            call.respondText("ok")
                        }
                        post("/test/request-erasure/{subjectId}") {
                            val service = DsgvoService(call)
                            val request =
                                service.requestErasure(
                                    call.parameters["subjectId"]!!,
                                    "Testmotion hart",
                                    ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED,
                                )
                            call.respondText(request.id)
                        }
                        post("/test/decide/{requestId}") {
                            val service = DsgvoService(call)
                            service.decideErasure(call.parameters["requestId"]!!, approve = true)
                            call.respondText("ok")
                        }
                        post("/test/execute/{requestId}") {
                            val service = DsgvoService(call)
                            val request = service.executeErasure(call.parameters["requestId"]!!)
                            call.respondText(request.status.name)
                        }
                    }
                }

                val subject = createDsgvoTestMember("dsgvo-hard-delete-subject@example.org")
                val subjectHeader = subject.toString()

                client.post("/test/send-dm/$TREASURER_ID") { header("X-Member-Id", subjectHeader) }
                client.post("/test/send-dm/$subjectHeader") { header("X-Member-Id", TREASURER_ID) }

                val requestId = client.post("/test/request-erasure/$subjectHeader") { header("X-Member-Id", subjectHeader) }.bodyAsText()
                client.post("/test/decide/$requestId") { header("X-Member-Id", ADMIN_ID) }
                val executed = client.post("/test/execute/$requestId") { header("X-Member-Id", ADMIN_ID) }.bodyAsText()
                executed shouldBe ErasureStatus.COMPLETED.name

                transaction {
                    val sentBody =
                        DirectMessageTable
                            .selectAll()
                            .where { DirectMessageTable.senderId eq subject }
                            .single()[DirectMessageTable.body]
                    sentBody shouldBe "[Nachricht vom Absender geloescht]"

                    val receivedBody =
                        DirectMessageTable
                            .selectAll()
                            .where { DirectMessageTable.recipientId eq subject }
                            .single()[DirectMessageTable.body]
                    receivedBody shouldBe "Selbst gesendeter Text"
                }
            }
        }
    })

private val PERIOD_START = LocalDate(2027, 3, 1)
private val PERIOD_END = LocalDate(2027, 3, 31)

/**
 * [DocumentService.createDocument] does not itself create a [DocumentVersionTable] row (file
 * bytes travel over [network.lapis.cloud.server.routes.registerDocumentRoutes], out of scope
 * here, see that route's KDoc) -- inserted directly so [DocumentPersonalData]'s "uploadedVersions"
 * walk has something to find.
 */
private fun insertDocumentVersion(
    documentId: String,
    uploadedBy: Uuid,
) {
    transaction {
        DocumentVersionTable.insert {
            it[id] = Uuid.random()
            it[DocumentVersionTable.documentId] = Uuid.parse(documentId)
            it[versionNumber] = 1
            it[fileName] = "dsgvo-test.pdf"
            it[mimeType] = "application/pdf"
            it[fileSizeBytes] = 1024L
            it[storageKey] = "dsgvo-test-storage-key"
            it[checksumSha256] = "0".repeat(64)
            it[DocumentVersionTable.uploadedBy] = uploadedBy
            it[uploadedAt] = LocalDateTime(2027, 1, 2, 12, 0)
        }
    }
}

/**
 * Inserted directly against [JournalEntryTable] rather than via [AccountingService.postJournalEntry]
 * -- setting up a balanced double-entry posting against an active [network.lapis.cloud.server.db.generated.LedgerAccountTable]
 * row is unrelated machinery this DSGVO-focused test has no need to exercise (see call site KDoc).
 * [donorMemberId] is deliberately distinct from [createdBy] so [AccountingPersonalData]'s export/
 * erasure walk is proven to match on *either* column, not just `created_by`.
 */
private fun insertDonationJournalEntry(
    donorMemberId: Uuid,
    createdBy: Uuid,
) {
    transaction {
        JournalEntryTable.insert {
            it[id] = Uuid.random()
            it[entryDate] = LocalDate(2027, 3, 10)
            it[description] = "DSGVO-Testspende"
            it[voucherReference] = null
            it[JournalEntryTable.createdBy] = createdBy
            it[status] = JournalEntryStatus.POSTED
            it[postedAt] = LocalDateTime(2027, 3, 10, 9, 0)
            it[createdAt] = LocalDateTime(2027, 3, 10, 9, 0)
            it[JournalEntryTable.donorMemberId] = donorMemberId
        }
    }
}

/**
 * Deletes every row this Spec created, in FK-safe child-before-parent order, so no state leaks
 * into other Spec classes sharing the same H2 in-memory database (see class KDoc). Deliberately
 * hard-deletes the member rows themselves (unlike [network.lapis.cloud.server.dsgvo.FoundationPersonalData],
 * which anonymizes) -- this is test-fixture teardown, not the application's Art. 17 erasure
 * semantics, and there is no accounting-retention duty on a tier/members that only ever existed
 * inside this test run.
 */
private fun cleanUpDsgvoTestData(
    dsgvoTestTierId: Uuid,
    memberIds: List<Uuid>,
) {
    if (memberIds.isEmpty()) return
    transaction {
        DsgvoAuditLogTable.deleteWhere { subjectMemberId inList memberIds }
        ErasureRequestTable.deleteWhere { subjectMemberId inList memberIds }
        MailingDeliveryLogTable.deleteWhere { memberId inList memberIds }
        MailingListSubscriptionTable.deleteWhere { memberId inList memberIds }
        MailingMessageTable.deleteWhere { sentBy inList memberIds }
        MailingListTable.deleteWhere { createdBy inList memberIds }
        DirectMessageTable.deleteWhere { (senderId inList memberIds) or (recipientId inList memberIds) }
        DocumentVersionTable.deleteWhere { uploadedBy inList memberIds }
        DocumentTable.deleteWhere { createdBy inList memberIds }
        // Only donorMemberId can reference a test subject here -- createdBy is always the fixed
        // dev-seeded TREASURER_ID, which is never in memberIds and must not be touched.
        JournalEntryTable.deleteWhere { donorMemberId inList memberIds }
        ContributionTable.deleteWhere { memberId inList memberIds }
        AccountTable.deleteWhere { memberId inList memberIds }
        MemberTable.deleteWhere { id inList memberIds }
        MembershipTierTable.deleteWhere { id eq dsgvoTestTierId }
    }
}

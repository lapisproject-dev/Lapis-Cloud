package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.module
import network.lapis.cloud.server.pdf.EinladungPdfGenerator
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.101 -- `POST /api/mailmerge/invitations` with `audience=ALL_ACTIVE`: the server resolves the recipients (one query, sorted by id,
 * 1,000 per part), validates every field, and the PDF carries a cover sheet. The explicit-selection route stays byte-for-byte as it was
 * ([MailmergeRoutesTest] covers that; two regression checks live here as well).
 *
 * The part-boundary tests park every other ACTIVE member as WITHDRAWN (and restore them) so N is exactly what the test sets.
 */
class MailmergeAllActiveInvitationTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdCommitteeIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdCommitteeIds.isNotEmpty()) {
                    CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId inList createdCommitteeIds }
                    CommitteeTable.deleteWhere { CommitteeTable.id inList createdCommitteeIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createCommittee(type: CommitteeType): Uuid {
            val id = Uuid.random()
            transaction {
                CommitteeTable.insert {
                    it[CommitteeTable.id] = id
                    it[name] = "Einladung Testgremium"
                    it[CommitteeTable.type] = type
                    it[description] = "V1.9.101"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            createdCommitteeIds += id
            return id
        }

        fun createMember(
            role: AccountRole,
            displayName: String = "Einladung Testmitglied",
            anonymized: Boolean = false,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[MemberTable.displayName] = displayName
                    it[email] = "inv-all-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                    it[street] = "Musterstrasse 5"
                    it[postalCode] = "38102"
                    it[city] = "Braunschweig"
                    it[country] = "Deutschland"
                    if (anonymized) it[anonymizedAt] = LocalDateTime(2026, 2, 1, 0, 0)
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

        /** Inserts [count] ACTIVE members in one batch (no accounts needed by the recipient query). */
        fun bulkMembers(count: Int): List<Uuid> {
            val ids = List(count) { Uuid.random() }
            transaction {
                MemberTable.batchInsert(ids, shouldReturnGeneratedValues = false) { id ->
                    this[MemberTable.id] = id
                    this[MemberTable.displayName] = "Bulk $id"
                    this[MemberTable.email] = "inv-bulk-$id@example.org"
                    this[MemberTable.status] = MemberStatus.ACTIVE
                    this[MemberTable.joinedAt] = LocalDate(2026, 1, 1)
                }
            }
            createdMemberIds += ids
            return ids
        }

        /** Runs [block] with exactly [keep] (plus whatever [block] creates) as ACTIVE members: all others are parked as WITHDRAWN. */
        suspend fun withOnlyActive(
            keep: List<Uuid>,
            block: suspend () -> Unit,
        ) {
            val parked =
                transaction {
                    MemberTable
                        .selectAll()
                        .where { (MemberTable.status eq MemberStatus.ACTIVE) and (MemberTable.id notInList keep) }
                        .map { it[MemberTable.id] }
                }
            parked.chunked(500).forEach { chunk ->
                transaction { MemberTable.update({ MemberTable.id inList chunk }) { it[status] = MemberStatus.WITHDRAWN } }
            }
            try {
                block()
            } finally {
                transaction {
                    MemberTable.update({ MemberTable.id inList createdMemberIds.toList() }) { it[status] = MemberStatus.WITHDRAWN }
                }
                parked.chunked(500).forEach { chunk ->
                    transaction { MemberTable.update({ MemberTable.id inList chunk }) { it[status] = MemberStatus.ACTIVE } }
                }
            }
        }

        fun form(
            audience: String? = "ALL_ACTIVE",
            committeeId: String? = null,
            part: String? = null,
            recipientIds: List<String> = emptyList(),
        ) = MultiPartFormDataContent(
            formData {
                append("title", "Einladung zur Mitgliederversammlung")
                append("eventDateTime", "2026-09-12T18:30:00")
                append("location", "Vereinsheim")
                append("bodyText", "Wir laden herzlich ein.")
                audience?.let { append("audience", it) }
                committeeId?.let { append("committeeId", it) }
                part?.let { append("part", it) }
                recipientIds.forEach { append("recipientMemberId", it) }
            },
        )

        // ── validation and rights ───────────────────────────────────────────────────────────────────────────

        test("ALL_ACTIVE: every malformed combination is a 400/404, the right comes first (403)") {
            testApplication {
                application { module() }
                val assembly = createCommittee(CommitteeType.GENERAL_ASSEMBLY)
                val workingGroup = createCommittee(CommitteeType.WORKING_GROUP)
                val board = createMember(AccountRole.BOARD)
                val plain = createMember(AccountRole.MEMBER)
                val chairOfAssembly = createMember(AccountRole.MEMBER)
                transaction {
                    CommitteeMembershipTable.insert {
                        it[id] = Uuid.random()
                        it[committeeId] = assembly
                        it[memberId] = chairOfAssembly
                        it[role] = CommitteeRole.CHAIR
                        it[since] = LocalDate(2020, 1, 1)
                        it[until] = null
                    }
                }

                suspend fun post(
                    actor: Uuid,
                    body: MultiPartFormDataContent,
                ) = client.post("/api/mailmerge/invitations") {
                    header("X-Member-Id", actor.toString())
                    setBody(body)
                }

                post(plain, form(committeeId = "$assembly", part = "0")).status shouldBe HttpStatusCode.Forbidden
                post(chairOfAssembly, form(committeeId = "$assembly", part = "0")).status shouldBe HttpStatusCode.Forbidden

                post(board, form(audience = "EVERYBODY", committeeId = "$assembly", part = "0")).status shouldBe HttpStatusCode.BadRequest
                post(board, form(committeeId = "$assembly", part = "0", recipientIds = listOf("${Uuid.random()}"))).status shouldBe
                    HttpStatusCode.BadRequest
                post(board, form(part = "0")).status shouldBe HttpStatusCode.BadRequest
                post(board, form(committeeId = "not-a-uuid", part = "0")).status shouldBe HttpStatusCode.BadRequest
                post(board, form(committeeId = "$assembly")).status shouldBe HttpStatusCode.BadRequest
                post(board, form(committeeId = "$assembly", part = "x")).status shouldBe HttpStatusCode.BadRequest
                post(board, form(committeeId = "$assembly", part = "-1")).status shouldBe HttpStatusCode.BadRequest
                post(board, form(committeeId = "$workingGroup", part = "0")).status shouldBe HttpStatusCode.BadRequest
                post(board, form(committeeId = "${Uuid.random()}", part = "0")).status shouldBe HttpStatusCode.NotFound
                // committeeId/part without audience=ALL_ACTIVE are not silently ignored
                post(board, form(audience = null, committeeId = "$assembly", recipientIds = listOf("${Uuid.random()}"))).status shouldBe
                    HttpStatusCode.BadRequest
                post(board, form(audience = "SELECTED", part = "0", recipientIds = listOf("${Uuid.random()}"))).status shouldBe
                    HttpStatusCode.BadRequest
            }
        }

        test("ALL_ACTIVE: the PDF has a cover sheet with part, as-of time and recipient count, then one letter per recipient") {
            testApplication {
                application { module() }
                val assembly = createCommittee(CommitteeType.GENERAL_ASSEMBLY)
                val board = createMember(AccountRole.BOARD, displayName = "Cover Board")
                val a = createMember(AccountRole.MEMBER, displayName = "Cover Anna")
                val b = createMember(AccountRole.MEMBER, displayName = "Cover Berta")
                val ghost = createMember(AccountRole.MEMBER, displayName = "Cover Anonym", anonymized = true)
                withOnlyActive(listOf(board, a, b, ghost)) {
                    // N counts the anonymized record, the letters do not: three letters + one cover sheet = four pages
                    val response =
                        client.post("/api/mailmerge/invitations") {
                            header("X-Member-Id", board.toString())
                            setBody(form(committeeId = "$assembly", part = "0"))
                        }
                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.ContentDisposition]!! shouldContain "Einladung-Teil-1-von-1.pdf"
                    Loader.loadPDF(response.bodyAsBytes()).use { pdf ->
                        val recipients = 3
                        pdf.numberOfPages shouldBe recipients + 1
                        val text = PDFTextStripper().getText(pdf)
                        text shouldContain "Teil 1 von 1"
                        text shouldContain "Stand "
                        text shouldContain "insgesamt $recipients Empf"
                        text shouldContain "Versand"
                        text shouldContain "Cover Berta"
                        (text.contains("Cover Anonym")) shouldBe false
                    }
                }
            }
        }

        test("the explicit selection stays as it was: no cover sheet, committeeId/part are refused") {
            testApplication {
                application { module() }
                val assembly = createCommittee(CommitteeType.GENERAL_ASSEMBLY)
                val board = createMember(AccountRole.BOARD)
                val recipient = createMember(AccountRole.MEMBER, displayName = "Selected Sven")
                val ok =
                    client.post("/api/mailmerge/invitations") {
                        header("X-Member-Id", board.toString())
                        setBody(form(audience = null, recipientIds = listOf("$recipient")))
                    }
                ok.status shouldBe HttpStatusCode.OK
                ok.headers[HttpHeaders.ContentDisposition]!! shouldContain "Einladung.pdf"
                Loader.loadPDF(ok.bodyAsBytes()).use { it.numberOfPages shouldBe 1 }

                client
                    .post("/api/mailmerge/invitations") {
                        header("X-Member-Id", board.toString())
                        setBody(form(audience = "SELECTED", committeeId = "$assembly", recipientIds = listOf("$recipient")))
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        // ── part boundaries (the resolver function directly: 2,500 letters would be 2,500 PDF pages) ────────

        test("parts: 1,000 members are one part; 1,001 are two with the second holding exactly one; 2,500 are three (1000/1000/500)") {
            val assembly = createCommittee(CommitteeType.GENERAL_ASSEMBLY)
            withOnlyActive(emptyList()) {
                val first = bulkMembers(1_000)
                val one = resolveAllActiveInvitationPart(committeeId = assembly, part = 0)
                one.partCount shouldBe 1
                one.recipients.size shouldBe 1_000
                one.totalRecipients shouldBe 1_000
                shouldThrowBadRequest { resolveAllActiveInvitationPart(committeeId = assembly, part = 1) }

                bulkMembers(1)
                val two0 = resolveAllActiveInvitationPart(committeeId = assembly, part = 0)
                val two1 = resolveAllActiveInvitationPart(committeeId = assembly, part = 1)
                two0.partCount shouldBe 2
                two0.recipients.size shouldBe 1_000
                two1.recipients.size shouldBe 1
                two1.totalRecipients shouldBe 1_001

                bulkMembers(1_499)
                val parts = (0..2).map { resolveAllActiveInvitationPart(committeeId = assembly, part = it) }
                parts.map { it.partCount }.toSet() shouldBe setOf(3)
                parts.map { it.recipients.size } shouldBe listOf(1_000, 1_000, 500)
                val ids = parts.flatMap { part -> part.recipients.map { it.id } }
                ids.toSet().size shouldBe 2_500
                ids shouldBe ids.sorted()
                first.size shouldBe 1_000
                shouldThrowBadRequest { resolveAllActiveInvitationPart(committeeId = assembly, part = 3) }
                shouldThrowBadRequest { resolveAllActiveInvitationPart(committeeId = assembly, part = -1) }
            }
        }

        test("parts: when the only active member is an anonymized record, the part is a 409 (no letters), N still counts it") {
            val assembly = createCommittee(CommitteeType.GENERAL_ASSEMBLY)
            val ghost = createMember(AccountRole.MEMBER, anonymized = true)
            withOnlyActive(listOf(ghost)) {
                var conflict = false
                try {
                    resolveAllActiveInvitationPart(committeeId = assembly, part = 0)
                } catch (_: ConflictException) {
                    conflict = true
                }
                conflict shouldBe true
            }
        }

        test("cover sheet: the generator adds one page only when a part note is given") {
            val organization =
                OrganizationSettingsDto(
                    id = "org",
                    name = "Verein",
                    street = null,
                    postalCode = null,
                    city = null,
                    country = null,
                    bankIban = null,
                    bankBic = null,
                    taxExemptionAuthority = null,
                    taxExemptionDate = null,
                )
            val recipients =
                List(2) {
                    network.lapis.cloud.shared.domain.MemberDto(
                        id = "$it",
                        displayName = "Empf $it",
                        email = "e$it@example.org",
                        status = MemberStatus.ACTIVE,
                        joinedAt = LocalDate(2026, 1, 1),
                        role = AccountRole.MEMBER,
                    )
                }

            fun pages(note: String?) =
                Loader
                    .loadPDF(
                        EinladungPdfGenerator.generate(
                            title = "T",
                            eventDateTime = LocalDateTime(2026, 9, 12, 18, 30),
                            location = "Ort",
                            bodyText = "Text",
                            recipients = recipients,
                            organization = organization,
                            partNote = note,
                        ),
                    ).use { it.numberOfPages }
            pages(null) shouldBe 2
            pages("Teil 1 von 1 · Stand 2026-09-12 18:30 · insgesamt 2 Empfänger") shouldBe 3
        }
    })

private inline fun shouldThrowBadRequest(block: () -> Unit) {
    var thrown = false
    try {
        block()
    } catch (_: BadRequestException) {
        thrown = true
    }
    thrown shouldBe true
}

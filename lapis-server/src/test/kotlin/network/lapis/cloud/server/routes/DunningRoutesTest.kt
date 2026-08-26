package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.DocumentFolderTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.DunningLevelTable
import network.lapis.cloud.server.db.generated.DunningNoticeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.DunningNoticeStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * Security review LOW finding (Round 3/4/5 restluecke). Before this file, [registerDunningRoutes]
 * had ZERO test coverage -- unlike every other raw-Ktor route surface in this repo (see
 * [SepaRoutesTest], [BackupRoutesAuthorizationTest]). Covers the THREE round-4-fixed guards this
 * route relies on -- [requireDunningUsable] (409 when `dunning_enabled = false`), the
 * `previewRateLimiter` (429 on the 11th call/minute), and the `hasDeliveredNoticeInCycle` fee
 * mirror (a SKIPPED-only cycle must never show a fee in the preview, exactly like
 * [network.lapis.cloud.server.payment.dunning.issueDunningNotice]'s own `effectiveFeeAmount`
 * guard) -- plus the usual malformed-id/role-gate/not-found shape every other route file already
 * establishes.
 */
class DunningRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdTierIds = mutableListOf<Uuid>()
        val createdContributionIds = mutableListOf<Uuid>()
        val createdLevelIds = mutableListOf<Uuid>()
        val createdNoticeIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterEach {
            transaction {
                val documentIds =
                    DunningNoticeTable
                        .selectAll()
                        .where { DunningNoticeTable.id inList createdNoticeIds }
                        .mapNotNull { it[DunningNoticeTable.documentId] }
                if (createdNoticeIds.isNotEmpty()) DunningNoticeTable.deleteWhere { DunningNoticeTable.id inList createdNoticeIds }
                if (documentIds.isNotEmpty()) {
                    DocumentVersionTable.deleteWhere { DocumentVersionTable.documentId inList documentIds }
                    DocumentTable.deleteWhere { DocumentTable.id inList documentIds }
                }
                if (createdContributionIds.isNotEmpty()) {
                    ContributionTable.deleteWhere { ContributionTable.id inList createdContributionIds }
                }
                if (createdLevelIds.isNotEmpty()) DunningLevelTable.deleteWhere { DunningLevelTable.id inList createdLevelIds }
                DocumentFolderTable.deleteWhere { DocumentFolderTable.name eq "Mahnungen" }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
                if (createdTierIds.isNotEmpty()) MembershipTierTable.deleteWhere { MembershipTierTable.id inList createdTierIds }
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[dunningEnabled] = false
                }
            }
            createdMemberIds.clear()
            createdTierIds.clear()
            createdContributionIds.clear()
            createdLevelIds.clear()
            createdNoticeIds.clear()
        }

        fun createMember(
            email: String,
            role: AccountRole,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Dunning-Route-Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[street] = "Teststr. 1"
                    it[postalCode] = "38100"
                    it[city] = "Braunschweig"
                    it[country] = "DE"
                    it[membershipTierId] = null
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
                    it[name] = "Dunning-Route Fixture Tarif ${id.toString().take(6)}"
                    it[description] = "Test-Tarif"
                    it[contributionAmount] = BigDecimal("50.00")
                    it[billingInterval] = BillingInterval.YEARLY
                    it[active] = true
                    it[paymentTermDays] = 14
                }
            }
            createdTierIds += id
            return id
        }

        val periodCounter = AtomicInteger(0)

        fun createContribution(
            memberId: Uuid,
            tierId: Uuid,
            status: ContributionStatus = ContributionStatus.OVERDUE,
        ): Uuid {
            val id = Uuid.random()
            val periodIndex = periodCounter.getAndIncrement()
            transaction {
                ContributionTable.insert {
                    it[ContributionTable.id] = id
                    it[periodStart] = LocalDate(2020, 1, 1).plus(periodIndex, kotlinx.datetime.DateTimeUnit.MONTH)
                    it[periodEnd] = LocalDate(2020, 1, 1).plus(periodIndex + 1, kotlinx.datetime.DateTimeUnit.MONTH)
                    it[amountDue] = BigDecimal("50.00")
                    it[ContributionTable.status] = status
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[ContributionTable.memberId] = memberId
                    it[membershipTierId] = tierId
                    it[dueDate] = LocalDate(2026, 1, 1)
                    it[paymentMethod] = ContributionPaymentMethod.MANUAL
                    it[sepaMandateId] = null
                    it[paidAt] = null
                    it[paidAmount] = null
                    it[note] = null
                }
            }
            createdContributionIds += id
            return id
        }

        fun createLevel(
            levelNumber: Int,
            feeAmount: BigDecimal? = if (levelNumber == 1) null else BigDecimal("5.00"),
        ): Uuid {
            val id = Uuid.random()
            transaction {
                DunningLevelTable.insert {
                    it[DunningLevelTable.id] = id
                    it[DunningLevelTable.levelNumber] = levelNumber
                    it[name] = "Stufe $levelNumber"
                    it[graceDays] = 1
                    it[responseDays] = 14
                    it[DunningLevelTable.feeAmount] = feeAmount
                    it[active] = true
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            createdLevelIds += id
            return id
        }

        /** Inserts a raw notice row -- used to seed a SKIPPED-only cycle without going through the RPC layer. */
        fun insertNotice(
            contributionId: Uuid,
            levelId: Uuid,
            levelNumber: Int,
            status: DunningNoticeStatus,
            feeAmount: BigDecimal?,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                DunningNoticeTable.insert {
                    it[DunningNoticeTable.id] = id
                    it[DunningNoticeTable.contributionId] = contributionId
                    it[dunningLevelId] = levelId
                    it[cycleNumber] = 1
                    it[DunningNoticeTable.levelNumber] = levelNumber
                    it[levelName] = "Stufe $levelNumber"
                    it[DunningNoticeTable.feeAmount] = feeAmount
                    it[amountDue] = BigDecimal("50.00")
                    it[DunningNoticeTable.status] = status
                    it[issuedAt] = LocalDateTime(2026, 1, 2, 9, 0)
                    it[respondBy] = LocalDate(2026, 1, 16)
                    it[documentId] = null
                    it[postalDeliveryLogId] = null
                    it[createdBy] = null
                    it[cancelledAt] = null
                    it[cancellationReason] = null
                }
            }
            createdNoticeIds += id
            return id
        }

        fun enableDunning() {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[dunningEnabled] = true
                }
            }
        }

        fun extractText(bytes: ByteArray): String {
            val document = Loader.loadPDF(bytes)
            return try {
                PDFTextStripper().getText(document)
            } finally {
                document.close()
            }
        }

        test("notice.pdf: malformed noticeId -> 400") {
            val storageRoot = Files.createTempDirectory("dunning-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(StatusPages) {
                            exception<ForbiddenException> {
                                call,
                                cause,
                                ->
                                call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                            }
                        }
                        routing {
                            registerDunningRoutes(storageRoot = storageRoot, previewRateLimiter = FederationInboxRateLimiter())
                        }
                    }
                    val admin = createMember("dun-route-notice-400-admin@example.org", AccountRole.ADMIN)
                    val response = client.get("/api/dunning/notices/not-a-uuid/notice.pdf") { header("X-Member-Id", admin.toString()) }
                    response.status shouldBe HttpStatusCode.BadRequest
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test("notice.pdf: unknown noticeId -> 404") {
            val storageRoot = Files.createTempDirectory("dunning-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(StatusPages) {
                            exception<ForbiddenException> {
                                call,
                                cause,
                                ->
                                call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                            }
                        }
                        routing {
                            registerDunningRoutes(storageRoot = storageRoot, previewRateLimiter = FederationInboxRateLimiter())
                        }
                    }
                    val admin = createMember("dun-route-notice-404-admin@example.org", AccountRole.ADMIN)
                    val response =
                        client.get("/api/dunning/notices/${Uuid.random()}/notice.pdf") { header("X-Member-Id", admin.toString()) }
                    response.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test("notice.pdf: role gate -- MEMBER and BOARD get 403, TREASURER and ADMIN get 404 (no document yet), never 403") {
            val storageRoot = Files.createTempDirectory("dunning-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(StatusPages) {
                            exception<ForbiddenException> {
                                call,
                                cause,
                                ->
                                call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                            }
                        }
                        routing {
                            registerDunningRoutes(storageRoot = storageRoot, previewRateLimiter = FederationInboxRateLimiter())
                        }
                    }
                    val admin = createMember("dun-route-role-admin@example.org", AccountRole.ADMIN)
                    val treasurer = createMember("dun-route-role-treasurer@example.org", AccountRole.TREASURER)
                    val board = createMember("dun-route-role-board@example.org", AccountRole.BOARD)
                    val member = createMember("dun-route-role-member@example.org", AccountRole.MEMBER)
                    val noticeId = Uuid.random()

                    client
                        .get("/api/dunning/notices/$noticeId/notice.pdf") {
                            header("X-Member-Id", member.toString())
                        }.status shouldBe HttpStatusCode.Forbidden
                    client
                        .get("/api/dunning/notices/$noticeId/notice.pdf") {
                            header("X-Member-Id", board.toString())
                        }.status shouldBe HttpStatusCode.Forbidden
                    client
                        .get("/api/dunning/notices/$noticeId/notice.pdf") {
                            header("X-Member-Id", treasurer.toString())
                        }.status shouldBe HttpStatusCode.NotFound
                    client
                        .get("/api/dunning/notices/$noticeId/notice.pdf") {
                            header("X-Member-Id", admin.toString())
                        }.status shouldBe HttpStatusCode.NotFound
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test("preview.pdf: malformed contributionId -> 400") {
            val storageRoot = Files.createTempDirectory("dunning-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(StatusPages) {
                            exception<ForbiddenException> {
                                call,
                                cause,
                                ->
                                call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                            }
                        }
                        routing {
                            registerDunningRoutes(storageRoot = storageRoot, previewRateLimiter = FederationInboxRateLimiter())
                        }
                    }
                    val admin = createMember("dun-route-preview-400-admin@example.org", AccountRole.ADMIN)
                    val response =
                        client.post("/api/dunning/contributions/not-a-uuid/preview.pdf") { header("X-Member-Id", admin.toString()) }
                    response.status shouldBe HttpStatusCode.BadRequest
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test(
            "preview.pdf: feature gate (Security finding) -- dunning_enabled=false -> 409 Conflict, PDF never generated",
        ) {
            val storageRoot = Files.createTempDirectory("dunning-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(StatusPages) {
                            exception<ForbiddenException> {
                                call,
                                cause,
                                ->
                                call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                            }
                        }
                        routing {
                            registerDunningRoutes(storageRoot = storageRoot, previewRateLimiter = FederationInboxRateLimiter())
                        }
                    }
                    // dunning_enabled is FALSE by default / reset in afterEach -- no enableDunning() call here.
                    val treasurer = createMember("dun-route-gate-treasurer@example.org", AccountRole.TREASURER)
                    val tierId = createTier()
                    createLevel(levelNumber = 1)
                    val contributionId = createContribution(treasurer, tierId)

                    val response =
                        client.post("/api/dunning/contributions/$contributionId/preview.pdf") {
                            header("X-Member-Id", treasurer.toString())
                        }
                    response.status shouldBe HttpStatusCode.Conflict
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test(
            "preview.pdf: rate limit (Security finding) -- 11th call within the window -> 429, first 10 succeed",
        ) {
            val storageRoot = Files.createTempDirectory("dunning-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(StatusPages) {
                            exception<ForbiddenException> {
                                call,
                                cause,
                                ->
                                call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                            }
                        }
                        routing {
                            registerDunningRoutes(
                                storageRoot = storageRoot,
                                previewRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = Duration.INFINITE),
                            )
                        }
                    }
                    enableDunning()
                    val treasurer = createMember("dun-route-ratelimit-treasurer@example.org", AccountRole.TREASURER)
                    val tierId = createTier()
                    createLevel(levelNumber = 1)
                    val contributionId = createContribution(treasurer, tierId)

                    repeat(10) {
                        val response =
                            client.post("/api/dunning/contributions/$contributionId/preview.pdf") {
                                header("X-Member-Id", treasurer.toString())
                            }
                        response.status shouldBe HttpStatusCode.OK
                    }
                    val eleventh =
                        client.post("/api/dunning/contributions/$contributionId/preview.pdf") {
                            header("X-Member-Id", treasurer.toString())
                        }
                    eleventh.status shouldBe HttpStatusCode.TooManyRequests
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test(
            "preview.pdf: SKIPPED-only cycle (Security finding, hasDeliveredNoticeInCycle mirror) -- next preview shows NO fee, even though level 2 carries one",
        ) {
            val storageRoot = Files.createTempDirectory("dunning-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(StatusPages) {
                            exception<ForbiddenException> {
                                call,
                                cause,
                                ->
                                call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                            }
                        }
                        routing {
                            registerDunningRoutes(storageRoot = storageRoot, previewRateLimiter = FederationInboxRateLimiter())
                        }
                    }
                    enableDunning()
                    val treasurer = createMember("dun-route-skipped-treasurer@example.org", AccountRole.TREASURER)
                    val tierId = createTier()
                    val level1Id = createLevel(levelNumber = 1, feeAmount = null)
                    createLevel(levelNumber = 2, feeAmount = BigDecimal("5.00"))
                    val contributionId = createContribution(treasurer, tierId, status = ContributionStatus.IN_DUNNING)
                    // Level 1 was SKIPPED, not actually delivered -- hasDeliveredNoticeInCycle must
                    // report false, so the level-2 preview must show NO fee (mirrors
                    // issueDunningNotice's own effectiveFeeAmount guard).
                    insertNotice(
                        contributionId = contributionId,
                        levelId = level1Id,
                        levelNumber = 1,
                        status = DunningNoticeStatus.SKIPPED,
                        feeAmount = null,
                    )

                    val response =
                        client.post("/api/dunning/contributions/$contributionId/preview.pdf") {
                            header("X-Member-Id", treasurer.toString())
                        }
                    response.status shouldBe HttpStatusCode.OK
                    val text = extractText(response.bodyAsBytes())
                    text shouldContain "Stufe 2"
                    text.contains("Mahngebuehr") shouldBe false
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }

        test("preview.pdf: no further level configured -> 409 Conflict") {
            val storageRoot = Files.createTempDirectory("dunning-routes-storage").toFile()
            try {
                testApplication {
                    application {
                        install(StatusPages) {
                            exception<ForbiddenException> {
                                call,
                                cause,
                                ->
                                call.respondText(cause.message, status = HttpStatusCode.Forbidden)
                            }
                        }
                        routing {
                            registerDunningRoutes(storageRoot = storageRoot, previewRateLimiter = FederationInboxRateLimiter())
                        }
                    }
                    enableDunning()
                    val treasurer = createMember("dun-route-nolevel-treasurer@example.org", AccountRole.TREASURER)
                    val tierId = createTier()
                    val levelId = createLevel(levelNumber = 1)
                    val contributionId = createContribution(treasurer, tierId, status = ContributionStatus.IN_DUNNING)
                    insertNotice(
                        contributionId = contributionId,
                        levelId = levelId,
                        levelNumber = 1,
                        status = DunningNoticeStatus.ISSUED,
                        feeAmount = null,
                    )

                    val response =
                        client.post("/api/dunning/contributions/$contributionId/preview.pdf") {
                            header("X-Member-Id", treasurer.toString())
                        }
                    response.status shouldBe HttpStatusCode.Conflict
                }
            } finally {
                storageRoot.deleteRecursively()
            }
        }
    })

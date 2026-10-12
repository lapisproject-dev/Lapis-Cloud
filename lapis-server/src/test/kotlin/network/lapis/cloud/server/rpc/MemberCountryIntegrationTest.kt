package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.BoardMembershipTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.member.MemberCountryStore
import network.lapis.cloud.server.member.PeerFixture
import network.lapis.cloud.server.module
import network.lapis.cloud.server.pdf.addressLines
import network.lapis.cloud.server.routes.loadMailmergeMember
import network.lapis.cloud.server.routes.requireCompleteAddress
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val MAP_ROUTE = "/rpc/routeBoardMemberMapServiceManager0"

/**
 * Welle V1.9.121 -- the places that consume the EFFECTIVE country / nationality: letters and the postal-address completeness check
 * (mail merge, dunning, postal mail all go through `loadMailmergeMember`), the board map (code beats legacy text, an emptied legacy
 * column no longer turns a Polish member into a German one), and the Transparenzregister data-gap list.
 */
class MemberCountryIntegrationTest :
    FunSpec({
        val fx = PeerFixture()
        val boardSeats = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec {
            transaction { if (boardSeats.isNotEmpty()) BoardMembershipTable.deleteWhere { BoardMembershipTable.id inList boardSeats } }
            fx.cleanUp()
        }

        fun addressed(
            country: String? = null,
            countryCode: String? = null,
        ): Uuid {
            val id = fx.member()
            transaction {
                MemberTable.update({ MemberTable.id eq id }) {
                    it[street] = "Musterweg 1"
                    it[postalCode] = "38998"
                    it[city] = "Teststadt"
                    it[MemberTable.country] = country
                    it[MemberTable.countryCode] = countryCode
                }
            }
            return id
        }

        test("letters: a code-only member has a complete address, the letter shows the German country name") {
            val byCode = addressed(countryCode = "PL")
            val dto = transaction { loadMailmergeMember(byCode) }!!
            dto.country shouldBe "Polen"
            requireCompleteAddress(dto)
            dto.addressLines().last() shouldBe "Polen"
        }

        test("letters: the legacy text is used while there is no code; neither means an incomplete address") {
            val legacy = addressed(country = "Altland")
            transaction { loadMailmergeMember(legacy) }!!.country shouldBe "Altland"
            val neither = addressed()
            val dto = transaction { loadMailmergeMember(neither) }!!
            dto.country shouldBe null
            shouldThrow<ConflictException> { requireCompleteAddress(dto) }
            // a code wins over a stale legacy text that was kept next to it (reconciliation keeps the text)
            val both = addressed(country = "Altland", countryCode = "AT")
            transaction { loadMailmergeMember(both) }!!.country shouldBe "Österreich"
        }

        test("board map: a member who chose Poland counts as foreign even when the legacy column is empty; DE counts as German") {
            testApplication {
                application { module() }
                val board = fx.member(role = AccountRole.BOARD)

                suspend fun foreign(): Int {
                    val body =
                        client
                            .post(MAP_ROUTE) {
                                header("X-Member-Id", board.toString())
                                contentType(ContentType.Application.Json)
                                setBody("""{"id":1,"jsonrpc":"2.0","method":"getMemberMap","params":[]}""")
                            }.bodyAsText()
                    return Regex("""foreign\\*":(\d+)""").find(body)!!.groupValues[1].toInt()
                }
                val before = foreign()
                addressed(countryCode = "PL")
                foreign() shouldBe before + 1
                addressed(countryCode = "DE")
                foreign() shouldBe before + 1
                addressed() // nothing at all -> German (unchanged rule)
                foreign() shouldBe before + 1
            }
        }

        test("Transparenzregister data gaps: codes count as given, ZZ is given but flagged as unclear, XS is a settled answer") {
            testApplication {
                application {
                    module()
                    routing {
                        get("/t/gap/{id}") {
                            val report = BoardMembershipService(call).getTransparenzregisterReport()
                            val gap = report.beneficialOwnerDataGaps.firstOrNull { it.memberId == call.parameters["id"] }
                            call.respondText(
                                gap?.let { "gap:${it.missingDateOfBirth}:${it.missingNationality}:${it.nationalityUnclear}" } ?: "none",
                            )
                        }
                    }
                }
                val admin = fx.admin()

                fun seat(
                    codes: List<String>?,
                    legacy: String? = null,
                ): Uuid {
                    val m = fx.member()
                    transaction {
                        MemberTable.update({ MemberTable.id eq m }) {
                            it[dateOfBirth] = LocalDate(1980, 1, 1)
                            it[nationality] = legacy
                        }
                        if (codes != null) MemberCountryStore.replaceNationalities(memberId = m, codes = codes, clearLegacy = false)
                        val seatId = Uuid.random()
                        BoardMembershipTable.insert {
                            it[id] = seatId
                            it[committeeRole] = CommitteeRole.CHAIR
                            it[startedAt] = LocalDate(2026, 1, 1)
                            it[memberId] = m
                        }
                        boardSeats += seatId
                    }
                    return m
                }

                suspend fun gapOf(m: Uuid): String = client.get("/t/gap/$m") { header("X-Member-Id", admin.toString()) }.bodyAsText()
                gapOf(seat(codes = null)) shouldBe "gap:false:true:false"
                gapOf(seat(codes = listOf("DE"))) shouldBe "none"
                gapOf(seat(codes = null, legacy = "deutsch")) shouldBe "none"
                gapOf(seat(codes = listOf("ZZ"))) shouldBe "gap:false:false:true"
                gapOf(seat(codes = listOf("XS"))) shouldBe "none"
            }
        }
    })

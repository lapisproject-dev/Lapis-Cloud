package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MembershipTierSnapshot
import network.lapis.cloud.shared.rpc.MembershipTierClosedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

private const val TREASURER = "00000000-0000-0000-0000-000000000003"
private const val ADMIN = "00000000-0000-0000-0000-000000000001"
private const val PREFIX = "ValTier-"

/**
 * Welle V1.9.18 -- server-authoritative validation, the uniqueness guard, the billing-interval lock,
 * the overview counts, the audit trail, the contribution-generation rules and the closed-tier
 * assignment guard of the tier administration.
 */
class MembershipTierValidationTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                val tierIds =
                    MembershipTierTable
                        .selectAll()
                        .where {
                            MembershipTierTable.name like "$PREFIX%"
                        }.map { it[MembershipTierTable.id] }
                ContributionTable.deleteWhere { memberId inList createdMemberIds }
                MemberTable.deleteWhere { id inList createdMemberIds }
                MembershipTierTable.deleteWhere { id inList tierIds }
            }
        }

        fun uniqueName(label: String = "T") = "$PREFIX$label-${Uuid.random().toString().take(8)}"

        fun enc(value: String) = value.encodeURLParameter()

        suspend fun HttpClient.post(
            path: String,
            caller: String = TREASURER,
        ): HttpResponse = post(path) { header("X-Member-Id", caller) }

        suspend fun HttpClient.get(
            path: String,
            caller: String = TREASURER,
        ): HttpResponse = get(path) { header("X-Member-Id", caller) }

        suspend fun HttpClient.createTier(
            name: String = uniqueName(),
            amount: String = "10.00",
            interval: String = "MONTHLY",
            active: Boolean = true,
            term: Int = 14,
            description: String = "",
        ): HttpResponse =
            post(
                "/test/tier/create?name=${enc(
                    name,
                )}&amount=$amount&interval=$interval&active=$active&term=$term&description=${enc(description)}",
            )

        suspend fun HttpClient.createdId(response: HttpResponse): String = response.bodyAsText().split("|")[0]

        fun newMember(
            status: MemberStatus,
            tierId: Uuid?,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Tier-Val Mitglied"
                    it[email] = "tier-val-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = tierId
                }
            }
            createdMemberIds += id
            return id
        }

        fun withApp(block: suspend HttpClient.() -> Unit) =
            testApplication {
                application {
                    install(StatusPages) { installMembershipTierExceptionHandlers() }
                    routing { registerMembershipTierTestRoutes() }
                }
                client.block()
            }

        fun tierCountByName(name: String): Long =
            transaction { MembershipTierTable.selectAll().where { MembershipTierTable.name eq name }.count() }

        // ── Validation ─────────────────────────────────────────────────────────────────

        test(
            "name: empty, whitespace-only and 101 characters are rejected (400), 100 characters and an inner-whitespace collapse are accepted",
        ) {
            withApp {
                createTier(name = "").status shouldBe HttpStatusCode.BadRequest
                createTier(name = "     ").status shouldBe HttpStatusCode.BadRequest
                createTier(name = PREFIX + "x".repeat(101 - PREFIX.length)).status shouldBe HttpStatusCode.BadRequest
                val okName = PREFIX + "y".repeat(100 - PREFIX.length)
                createTier(name = okName).status shouldBe HttpStatusCode.OK
                tierCountByName(okName) shouldBe 1L

                val collapsed = createTier(name = "  ${PREFIX}A   B  ${Uuid.random().toString().take(6)}  ")
                collapsed.status shouldBe HttpStatusCode.OK
                val stored = collapsed.bodyAsText().split("|")[1]
                (stored.contains("  ")) shouldBe false
                stored shouldBe stored.trim()
            }
        }

        test("description of 1001 characters is rejected, 1000 is accepted") {
            withApp {
                val name = uniqueName("desc")
                createTier(name = name, description = "d".repeat(1001)).status shouldBe HttpStatusCode.BadRequest
                tierCountByName(name) shouldBe 0L
                createTier(name = name, description = "d".repeat(1000)).status shouldBe HttpStatusCode.OK
            }
        }

        test("amount: negative, above 100000.00 and more than two decimals are rejected; 0, 100000.00 and 12.50 are accepted") {
            withApp {
                listOf("-0.01", "100000.01", "10.005", "1.234").forEach { bad ->
                    val name = uniqueName("amt")
                    createTier(name = name, amount = bad).status shouldBe HttpStatusCode.BadRequest
                    tierCountByName(name) shouldBe 0L
                }
                listOf("0", "0.00", "100000.00", "12.50", "12.500").forEach { good ->
                    createTier(name = uniqueName("amt"), amount = good).status shouldBe HttpStatusCode.OK
                }
            }
        }

        test("payment term: -1 and 366 are rejected, 0 and 365 are accepted") {
            withApp {
                createTier(name = uniqueName("term"), term = -1).status shouldBe HttpStatusCode.BadRequest
                createTier(name = uniqueName("term"), term = 366).status shouldBe HttpStatusCode.BadRequest
                createTier(name = uniqueName("term"), term = 0).status shouldBe HttpStatusCode.OK
                createTier(name = uniqueName("term"), term = 365).status shouldBe HttpStatusCode.OK
            }
        }

        test("the stored amount carries exactly two decimals, whatever scale the caller sent") {
            withApp {
                val body = createTier(name = uniqueName("scale"), amount = "12.5").bodyAsText()
                body.split("|")[2] shouldBe "12.50"
            }
        }

        test("update: the same validation applies, and an unknown id is 404") {
            withApp {
                val id = createdId(createTier())
                post("/test/tier/update?id=$id&name=&amount=10.00").status shouldBe HttpStatusCode.BadRequest
                post("/test/tier/update?id=$id&name=${enc(uniqueName())}&amount=-1").status shouldBe HttpStatusCode.BadRequest
                post("/test/tier/update?id=$id&name=${enc(uniqueName())}&term=400").status shouldBe HttpStatusCode.BadRequest
                post("/test/tier/update?id=${Uuid.random()}&name=${enc(uniqueName())}").status shouldBe HttpStatusCode.NotFound
                post("/test/tier/update?id=not-a-uuid&name=${enc(uniqueName())}").status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── Uniqueness ─────────────────────────────────────────────────────────────────

        test("a name that differs only in case or surrounding whitespace is a duplicate on create (409 NAME_TAKEN)") {
            withApp {
                val name = uniqueName("dup")
                createTier(name = name).status shouldBe HttpStatusCode.OK
                val clash = createTier(name = "  ${name.uppercase()} ")
                clash.status shouldBe HttpStatusCode.Conflict
                clash.bodyAsText() shouldBe "NAME_TAKEN"
                tierCountByName(name.uppercase()) shouldBe 0L
            }
        }

        test("concurrent creates of the same name: exactly one wins, every loser is a clean 409 NAME_TAKEN (never a leaked SQL error)") {
            withApp {
                val name = uniqueName("race")
                val responses =
                    coroutineScope {
                        (1..8).map { async { createTier(name = name) } }.awaitAll()
                    }
                val statuses = responses.map { it.status }
                statuses.count { it == HttpStatusCode.OK } shouldBe 1
                statuses.count { it == HttpStatusCode.Conflict } shouldBe 7
                responses.filter { it.status == HttpStatusCode.Conflict }.forEach { it.bodyAsText() shouldBe "NAME_TAKEN" }
                tierCountByName(name) shouldBe 1L
            }
        }

        test("update: taking another tier's name is 409 NAME_TAKEN, re-saving or re-casing the OWN name is allowed") {
            withApp {
                val nameA = uniqueName("a")
                val nameB = uniqueName("b")
                val idA = createdId(createTier(name = nameA))
                createTier(name = nameB).status shouldBe HttpStatusCode.OK

                val clash = post("/test/tier/update?id=$idA&name=${enc(nameB.uppercase())}")
                clash.status shouldBe HttpStatusCode.Conflict
                clash.bodyAsText() shouldBe "NAME_TAKEN"

                post("/test/tier/update?id=$idA&name=${enc(nameA)}&amount=11.00").status shouldBe HttpStatusCode.OK
                post("/test/tier/update?id=$idA&name=${enc(nameA.uppercase())}&amount=11.00").status shouldBe HttpStatusCode.OK
            }
        }

        test("the UNIQUE index itself rejects a second row with the same name_key, bypassing the service") {
            withApp {
                val name = uniqueName("idx")
                createTier(name = name).status shouldBe HttpStatusCode.OK
                shouldThrow<ExposedSQLException> {
                    transaction {
                        MembershipTierTable.insert {
                            val newId = Uuid.random()
                            it[id] = newId
                            it[MembershipTierTable.name] = "$PREFIX-bypass-$newId"
                            it[nameKey] = name.lowercase()
                            it[description] = ""
                            it[contributionAmount] = BigDecimal("1.00")
                            it[billingInterval] = BillingInterval.MONTHLY
                            it[active] = true
                            it[paymentTermDays] = 14
                        }
                    }
                }
            }
        }

        // ── Interval lock ──────────────────────────────────────────────────────────────

        test("the billing interval cannot change while an ACTIVE member is assigned (409 INTERVAL_LOCKED), nothing is written") {
            withApp {
                val name = uniqueName("lock")
                val id = createdId(createTier(name = name, interval = "MONTHLY"))
                newMember(MemberStatus.ACTIVE, Uuid.parse(id))

                val locked = post("/test/tier/update?id=$id&name=${enc(name)}&interval=YEARLY")
                locked.status shouldBe HttpStatusCode.Conflict
                locked.bodyAsText() shouldBe "INTERVAL_LOCKED"
                transaction {
                    MembershipTierTable
                        .selectAll()
                        .where {
                            MembershipTierTable.id eq
                                Uuid.parse(
                                    id,
                                )
                        }.single()[MembershipTierTable.billingInterval]
                } shouldBe
                    BillingInterval.MONTHLY
                // same interval with members assigned is fine (other fields may change)
                post("/test/tier/update?id=$id&name=${enc(name)}&interval=MONTHLY&amount=20.00").status shouldBe HttpStatusCode.OK
            }
        }

        test("the billing interval may change while nobody ACTIVE is assigned (no members, or only ended ones)") {
            withApp {
                val name = uniqueName("free")
                val id = createdId(createTier(name = name, interval = "MONTHLY"))
                newMember(MemberStatus.WITHDRAWN, Uuid.parse(id))
                post("/test/tier/update?id=$id&name=${enc(name)}&interval=QUARTERLY").status shouldBe HttpStatusCode.OK
            }
        }

        // ── Overview ───────────────────────────────────────────────────────────────────

        test("overview counts ACTIVE members per tier only, and ACTIVE members without a tier separately") {
            withApp {
                val idA = Uuid.parse(createdId(createTier(name = uniqueName("ova"))))
                val idB = Uuid.parse(createdId(createTier(name = uniqueName("ovb"))))
                val idEmpty = Uuid.parse(createdId(createTier(name = uniqueName("ove"))))

                fun parse(body: String): Triple<Int, Map<String, Int>, Int> {
                    val (n, counts, none) = body.split("#")
                    val map =
                        counts.split(",").filter { it.isNotBlank() }.associate {
                            it.substringBefore("=") to
                                it.substringAfter("=").toInt()
                        }
                    return Triple(n.toInt(), map, none.toInt())
                }
                val before = parse(get("/test/tier/overview").bodyAsText())

                newMember(MemberStatus.ACTIVE, idA)
                newMember(MemberStatus.ACTIVE, idA)
                newMember(MemberStatus.ACTIVE, idB)
                newMember(MemberStatus.WITHDRAWN, idB)
                newMember(MemberStatus.APPLICATION, idB)
                newMember(MemberStatus.ACTIVE, null)
                newMember(MemberStatus.ACTIVE, null)
                newMember(MemberStatus.WITHDRAWN, null)

                val after = parse(get("/test/tier/overview").bodyAsText())
                after.second[idA.toString()] shouldBe 2
                after.second[idB.toString()] shouldBe 1
                after.second[idEmpty.toString()] shouldBe null
                (after.third - before.third) shouldBe 2
                (after.first >= before.first) shouldBe true
            }
        }

        // ── Audit ──────────────────────────────────────────────────────────────────────

        fun auditRows(tierId: Uuid) =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.MEMBERSHIP_TIER) and (AuditLogEntryTable.entityId eq tierId)
                    }.orderBy(AuditLogEntryTable.sequenceNumber)
                    .toList()
            }

        test(
            "create writes one CREATE entry, a changing update one UPDATE entry with before/after, a no-op update none, and the hash chain stays valid",
        ) {
            withApp {
                val name = uniqueName("aud")
                val id = Uuid.parse(createdId(createTier(name = name, amount = "10.00", description = "alt")))
                auditRows(id).size shouldBe 1
                auditRows(id).single()[AuditLogEntryTable.action] shouldBe AuditAction.CREATE
                auditRows(id).single()[AuditLogEntryTable.beforeSnapshot] shouldBe null
                val createdSnapshot =
                    Json.decodeFromString(MembershipTierSnapshot.serializer(), auditRows(id).single()[AuditLogEntryTable.afterSnapshot]!!)
                createdSnapshot.name shouldBe name
                createdSnapshot.contributionAmount shouldBe "10.00"

                post("/test/tier/update?id=$id&name=${enc(name)}&amount=15.50&description=${enc("alt")}").status shouldBe HttpStatusCode.OK
                val rows = auditRows(id)
                rows.size shouldBe 2
                rows[1][AuditLogEntryTable.action] shouldBe AuditAction.UPDATE
                val before = Json.decodeFromString(MembershipTierSnapshot.serializer(), rows[1][AuditLogEntryTable.beforeSnapshot]!!)
                val after = Json.decodeFromString(MembershipTierSnapshot.serializer(), rows[1][AuditLogEntryTable.afterSnapshot]!!)
                before.contributionAmount shouldBe "10.00"
                after.contributionAmount shouldBe "15.50"
                rows[1][AuditLogEntryTable.actorRole] shouldBe AccountRole.TREASURER

                // identical save: nothing changed, nothing logged
                post("/test/tier/update?id=$id&name=${enc(name)}&amount=15.50&description=${enc("alt")}").status shouldBe HttpStatusCode.OK
                auditRows(id).size shouldBe 2

                val seqs = auditRows(id).map { it[AuditLogEntryTable.sequenceNumber] }
                get("/test/tier/verify?from=${seqs.first()}&to=${seqs.last()}").bodyAsText().startsWith("true:") shouldBe true
            }
        }

        test("a rejected write (validation, duplicate) leaves no audit entry behind") {
            withApp {
                val name = uniqueName("noaud")
                val id = Uuid.parse(createdId(createTier(name = name)))
                post("/test/tier/update?id=$id&name=${enc(name)}&amount=-5").status shouldBe HttpStatusCode.BadRequest
                auditRows(id).size shouldBe 1
            }
        }

        // ── Contribution generation ────────────────────────────────────────────────────

        fun contributionsOf(memberId: Uuid) =
            transaction {
                ContributionTable.selectAll().where { ContributionTable.memberId eq memberId }.toList()
            }

        test("a free tier (0.00) generates nothing; a closed tier keeps generating; start after end is 400") {
            withApp {
                val freeId = Uuid.parse(createdId(createTier(name = uniqueName("free0"), amount = "0.00")))
                val freeMember = newMember(MemberStatus.ACTIVE, freeId)
                post("/test/tier/generate?tierId=$freeId&from=2027-01-01&to=2027-01-31").bodyAsText() shouldBe "0"
                contributionsOf(freeMember).size shouldBe 0

                val closedId = Uuid.parse(createdId(createTier(name = uniqueName("closed"), amount = "10.00", active = false)))
                val closedMember = newMember(MemberStatus.ACTIVE, closedId)
                post("/test/tier/generate?tierId=$closedId&from=2027-02-01&to=2027-02-28").bodyAsText() shouldBe "1"
                contributionsOf(closedMember).size shouldBe 1

                post("/test/tier/generate?tierId=$closedId&from=2027-03-31&to=2027-03-01").status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("changing the amount never rewrites an already generated contribution") {
            withApp {
                val name = uniqueName("keep")
                val id = Uuid.parse(createdId(createTier(name = name, amount = "10.00")))
                val member = newMember(MemberStatus.ACTIVE, id)
                post("/test/tier/generate?tierId=$id&from=2027-04-01&to=2027-04-30").bodyAsText() shouldBe "1"
                post("/test/tier/update?id=$id&name=${enc(name)}&amount=99.00").status shouldBe HttpStatusCode.OK
                contributionsOf(member).single()[ContributionTable.amountDue].compareTo(BigDecimal("10.00")) shouldBe 0
            }
        }

        // ── Closed tier: assignment ────────────────────────────────────────────────────

        test("MembershipTierAssignment refuses to MOVE a member onto a closed tier, but keeping or removing a tier stays allowed") {
            withApp {
                val openId = Uuid.parse(createdId(createTier(name = uniqueName("open"))))
                val closedId = Uuid.parse(createdId(createTier(name = uniqueName("clo2"), active = false)))
                val actor = CurrentMember(memberId = Uuid.parse(ADMIN), role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)
                val now = DbClock.nowLocalDateTime()

                val onOpen = newMember(MemberStatus.ACTIVE, openId)
                shouldThrow<MembershipTierClosedException> {
                    transaction {
                        MembershipTierAssignment.apply(
                            targetMemberId = onOpen,
                            newTierId = closedId,
                            actor = actor,
                            reason = null,
                            familyId = null,
                            now = now,
                        )
                    }
                }
                transaction { MemberTable.selectAll().where { MemberTable.id eq onOpen }.single()[MemberTable.membershipTierId] } shouldBe
                    openId

                // a member ALREADY on the (since closed) tier: keeping it is a silent no-op, not an error
                val onClosed = newMember(MemberStatus.ACTIVE, closedId)
                transaction {
                    MembershipTierAssignment.apply(
                        targetMemberId = onClosed,
                        newTierId = closedId,
                        actor = actor,
                        reason = null,
                        familyId = null,
                        now = now,
                    )
                } shouldBe
                    false
                // removing the tier is always allowed
                transaction {
                    MembershipTierAssignment.apply(
                        targetMemberId = onClosed,
                        newTierId = null,
                        actor = actor,
                        reason = null,
                        familyId = null,
                        now = now,
                    )
                } shouldBe
                    true
                // and an open tier is still assignable
                transaction {
                    MembershipTierAssignment.apply(
                        targetMemberId = onClosed,
                        newTierId = openId,
                        actor = actor,
                        reason = null,
                        familyId = null,
                        now = now,
                    )
                } shouldBe
                    true
            }
        }

        test("listMembershipTiers returns a tier that was just closed, flagged active = false (closing never hides it)") {
            withApp {
                val name = uniqueName("vis")
                val id = createdId(createTier(name = name))
                post("/test/tier/update?id=$id&name=${enc(name)}&active=false").status shouldBe HttpStatusCode.OK
                val listed = get("/test/tier/list").bodyAsText().split(";").single { it.startsWith(id) }
                listed.split("|")[4] shouldBe "false"
            }
        }

        test("fixture sanity: every tier row carries a non-blank name_key") {
            transaction {
                MembershipTierTable.selectAll().all { it[MembershipTierTable.nameKey].isNotBlank() } shouldBe true
            }
        }
    })

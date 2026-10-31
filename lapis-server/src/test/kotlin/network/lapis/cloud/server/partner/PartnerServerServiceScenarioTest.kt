package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.respond
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.CommitteeSeatFixture
import network.lapis.cloud.server.rpc.PartnerServerService
import network.lapis.cloud.server.rpc.ServiceCaller
import network.lapis.cloud.server.rpc.withServiceCaller
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartnerAddressRejectionCode
import network.lapis.cloud.shared.domain.PartnerFetchErrorCode
import network.lapis.cloud.shared.domain.PartnerFetchStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.RateLimitedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val NOW = LocalDateTime(2026, 11, 5, 10, 0, 0)

private fun events(count: Int) =
    (1..count).map {
        feedEventJson(id = "e$it", startsAt = "2026-11-1${it % 9}T19:00:00+01:00", endsAt = "2026-11-1${it % 9}T20:00:00+01:00")
    }

/** V1.9.244 -- the admin service and the poller on H2, through a real RPC call context and a fake network. */
class PartnerServerServiceScenarioTest :
    FunSpec({
        val fx = CommitteeSeatFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { wipe() }
        afterSpec {
            wipe()
            fx.cleanUp()
        }

        fun service(
            call: io.ktor.server.application.ApplicationCall,
            fetcher: PartnerFeedFetcher,
            limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
        ) = PartnerServerService(
            call = call,
            probeRateLimiter = limiter,
            fetcher = fetcher,
            publicBaseUrl = "https://own.example.com",
            clock = { NOW },
        )

        suspend fun <T> ServiceCaller.asAdmin(
            admin: Uuid,
            fetcher: PartnerFeedFetcher,
            limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes),
            action: suspend (PartnerServerService) -> T,
        ): Result<T> = call(caller = admin) { action(service(it, fetcher, limiter)) }

        fun partnerRows() = transaction { PartnerServerTable.selectAll().toList() }

        fun eventRows() = transaction { PartnerEventTable.selectAll().toList() }

        test("an ADMIN probes: saved, 12 events found, the list shows OK and 12, 12 cached rows") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val net = FakeNetwork()
            val fetcher = fakeFetcher(net = net) { json(body = feedJson(events = events(12)), etag = "\"v1\"") }
            withServiceCaller {
                val result =
                    asAdmin(
                        admin,
                        fetcher,
                    ) { it.probeAndSave(address = "https://Partner.Beispiel.de/veranstaltungen", displayName = " Partner ") }.getOrThrow()
                result.saved shouldBe true
                result.host shouldBe PARSER_HOST
                result.eventCount shouldBe 12
                val overview = asAdmin(admin, fetcher) { it.list() }.getOrThrow()
                val partner = overview.partners.single()
                partner.status shouldBe PartnerFetchStatus.OK
                partner.eventCount shouldBe 12
                partner.displayName shouldBe "Partner"
                partner.remoteName shouldBe "Partner e.V."
                partner.blocked shouldBe false
            }
            eventRows() shouldHaveSize 12
            net.resolutions.get() shouldBe 1
        }

        test("a failing probe stores nothing and returns the code; a refused address returns its reason without any network access") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val net = FakeNetwork()
            val fetcher = fakeFetcher(net = net, unresolvable = setOf("intern.beispiel.de")) { json(body = "garbage".toByteArray()) }
            withServiceCaller {
                asAdmin(admin, fetcher) { it.probeAndSave(address = "partner.beispiel.de", displayName = null) }.getOrThrow().error shouldBe
                    PartnerFetchErrorCode.INVALID_FEED
                asAdmin(admin, fetcher) { it.probeAndSave(address = "intern.beispiel.de", displayName = null) }.getOrThrow().error shouldBe
                    PartnerFetchErrorCode.NOT_PUBLIC_ADDRESS
                val before = net.resolutions.get()
                val refused = asAdmin(admin, fetcher) { it.probeAndSave(address = "http://127.0.0.1", displayName = null) }.getOrThrow()
                refused.addressRejection shouldBe PartnerAddressRejectionCode.NOT_HTTPS
                asAdmin(
                    admin,
                    fetcher,
                ) { it.probeAndSave(address = "127.0.0.1", displayName = null) }.getOrThrow().addressRejection shouldBe
                    PartnerAddressRejectionCode.IP_LITERAL
                asAdmin(
                    admin,
                    fetcher,
                ) { it.probeAndSave(address = "own.example.com", displayName = null) }.getOrThrow().addressRejection shouldBe
                    PartnerAddressRejectionCode.OWN_HOST
                net.resolutions.get() shouldBe before
            }
            partnerRows().shouldBeEmpty()
        }

        test("every one of the eleven methods refuses a MEMBER and a BOARD member") {
            val member = fx.member(role = AccountRole.MEMBER)
            val board = fx.member(role = AccountRole.BOARD)
            val fetcher = fakeFetcher { json(body = feedJson()) }
            val id = Uuid.random().toString()
            withServiceCaller {
                for (who in listOf(member, board)) {
                    listOf<suspend (PartnerServerService) -> Any?>(
                        { it.list() },
                        { it.probeAndSave(address = "partner.beispiel.de", displayName = null) },
                        { it.setActive(id = id, active = true) },
                        { it.remove(id) },
                        { it.listBlocklist() },
                        { it.addBlock("x.de") },
                        { it.removeBlock(id) },
                        { it.setPartnerListHidden(true) },
                        { it.getPartnerRecursionSettings() },
                        { it.setPartnerRecursionSettings(enabled = false, depth = 2) },
                        { it.listDiscoveredPartnerServers() },
                    ).forEach { action ->
                        asAdmin(who, fetcher) { action(it) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    }
                }
            }
            partnerRows().shouldBeEmpty()
        }

        test("a second probe of the same host is DUPLICATE; another host announcing the same server id is SERVER_ID_TAKEN") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val fetcher = fakeFetcher { json(body = feedJson(events = events(1))) }
            withServiceCaller {
                asAdmin(admin, fetcher) { it.probeAndSave(address = "partner.beispiel.de", displayName = null) }.getOrThrow().saved shouldBe
                    true
                asAdmin(admin, fetcher) { it.probeAndSave(address = "partner.beispiel.de", displayName = null) }.getOrThrow().error shouldBe
                    PartnerFetchErrorCode.DUPLICATE
                // the feed of the other host announces the SAME server id (but its events point at partner.beispiel.de -> dropped, feed still valid)
                asAdmin(admin, fetcher) { it.probeAndSave(address = "zweiter.beispiel.de", displayName = null) }.getOrThrow().error shouldBe
                    PartnerFetchErrorCode.SERVER_ID_TAKEN
            }
            partnerRows() shouldHaveSize 1
        }

        test("the 11th probe within a minute is rate limited") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val fetcher = fakeFetcher(unresolvable = (1..12).map { "h$it.beispiel.de" }.toSet()) { json(body = feedJson()) }
            val limiter = FederationInboxRateLimiter(maxRequests = 10, window = 1.minutes)
            withServiceCaller {
                (1..10).forEach { n ->
                    asAdmin(
                        admin,
                        fetcher,
                        limiter,
                    ) { it.probeAndSave(address = "h$n.beispiel.de", displayName = null) }.getOrThrow().saved shouldBe
                        false
                }
                asAdmin(
                    admin,
                    fetcher,
                    limiter,
                ) {
                    it.probeAndSave(
                        address = "h11.beispiel.de",
                        displayName = null,
                    )
                }.exceptionOrNull().shouldBeInstanceOf<RateLimitedException>()
            }
        }

        test("remove deletes the partner and its cache; a malformed or unknown id is refused") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val fetcher = fakeFetcher { json(body = feedJson(events = events(3))) }
            withServiceCaller {
                asAdmin(admin, fetcher) { it.probeAndSave(address = "partner.beispiel.de", displayName = null) }.getOrThrow().saved shouldBe
                    true
                val id = partnerRows().single()[PartnerServerTable.id].toString()
                asAdmin(admin, fetcher) { it.remove("not-a-uuid") }.exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                asAdmin(admin, fetcher) { it.remove(Uuid.random().toString()) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                asAdmin(admin, fetcher) { it.remove(id) }.getOrThrow()
            }
            partnerRows().shouldBeEmpty()
            eventRows().shouldBeEmpty()
        }

        test(
            "a blocked host is never contacted by a probe or by the poller, and a new block empties the cache of matching partners at once",
        ) {
            val admin = fx.member(role = AccountRole.ADMIN)
            val net = FakeNetwork()
            val fetcher = fakeFetcher(net = net) { json(body = feedJson(events = events(4))) }
            withServiceCaller {
                asAdmin(admin, fetcher) { it.probeAndSave(address = "partner.beispiel.de", displayName = null) }.getOrThrow().saved shouldBe
                    true
                eventRows() shouldHaveSize 4
                asAdmin(admin, fetcher) { it.addBlock("*.beispiel.de") }.getOrThrow().invalid shouldBe false
                asAdmin(admin, fetcher) { it.addBlock("a*.de") }.getOrThrow().invalid shouldBe true
                eventRows().shouldBeEmpty()
                val overview = asAdmin(admin, fetcher) { it.list() }.getOrThrow()
                overview.partners.single().blocked shouldBe true
                overview.partners.single().eventCount shouldBe 0
                overview.blocklist.map { it.pattern } shouldBe listOf("*.beispiel.de")
                val before = net.resolutions.get()
                val requestsBefore = net.requests.get()
                asAdmin(admin, fetcher) { it.probeAndSave(address = "neu.beispiel.de", displayName = null) }.getOrThrow().error shouldBe
                    PartnerFetchErrorCode.BLOCKED
                // the poller: make the partner due, tick -> still no contact
                transaction { PartnerServerTable.update({ PartnerServerTable.host eq PARSER_HOST }) { it[nextFetchAt] = NOW } }
                PartnerFeedPoller(fetcher = fetcher, clock = { NOW }).tick()
                net.resolutions.get() shouldBe before
                net.requests.get() shouldBe requestsBefore
                // unblock: the partner is fetched again by the next tick
                val blockId = overview.blocklist.single().id
                asAdmin(admin, fetcher) { it.removeBlock(blockId) }.getOrThrow()
                transaction { PartnerServerTable.update({ PartnerServerTable.host eq PARSER_HOST }) { it[nextFetchAt] = NOW } }
                PartnerFeedPoller(fetcher = fetcher, clock = { NOW }).tick()
            }
            eventRows() shouldHaveSize 4
        }

        test("the recursion settings: defaults, a change is stored, depth 0 and 4 are bad requests, the audit names the right kinds") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val fetcher = fakeFetcher { json(body = feedJson()) }
            val mark = fx.auditMark()
            withServiceCaller {
                val initial = asAdmin(admin, fetcher) { it.getPartnerRecursionSettings() }.getOrThrow()
                initial.enabled shouldBe true
                initial.depth shouldBe 2
                initial.lastRunAt.shouldBeNull()
                initial.summary.shouldBeNull()
                asAdmin(admin, fetcher) { it.setPartnerRecursionSettings(enabled = true, depth = 0) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<BadRequestException>()
                asAdmin(admin, fetcher) { it.setPartnerRecursionSettings(enabled = true, depth = 4) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<BadRequestException>()
                asAdmin(admin, fetcher) { it.setPartnerRecursionSettings(enabled = true, depth = 2) }.getOrThrow() // no change -> no audit
                asAdmin(admin, fetcher) { it.setPartnerRecursionSettings(enabled = true, depth = 3) }.getOrThrow()
                asAdmin(admin, fetcher) { it.setPartnerRecursionSettings(enabled = false, depth = 3) }.getOrThrow()
                asAdmin(admin, fetcher) {
                    it.setPartnerRecursionSettings(enabled = true, depth = 1)
                }.getOrThrow() // switched on AND depth changed -> two entries
                val now = asAdmin(admin, fetcher) { it.getPartnerRecursionSettings() }.getOrThrow()
                now.enabled shouldBe true
                now.depth shouldBe 1
            }
            val entries = fx.auditSince(mark = mark, type = AuditEntityType.PARTNER_SERVER)
            val kinds = entries.map { it[AuditLogEntryTable.afterSnapshot].orEmpty() }
            kinds shouldHaveSize 4
            kinds.count { it.contains("RECURSION_DEPTH_CHANGED") } shouldBe 2
            kinds.count { it.contains("RECURSION_DISABLED") } shouldBe 1
            kinds.count { it.contains("RECURSION_ENABLED") } shouldBe 1
            fx.chainProblemSince(mark).shouldBeNull()
            transaction { PartnerDiscoveryStore.recursionSettings().depth } shouldBe 1
        }

        test("the discovered servers are listed with their chain and counted per direct partner") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val world = FakeWorld()
            addDirectPartner(host = "a.beispiel.de", partners = listOf("b.beispiel.de"), displayName = "Alpha", now = NOW)
            world.serve(host = "b.beispiel.de", name = "Bravo", partners = listOf("c.beispiel.de"))
            world.serve(host = "c.beispiel.de")
            runnerFor(world = world, now = { NOW }).run()
            withServiceCaller {
                val fetcher = fakeFetcher { json(body = feedJson()) }
                val list = asAdmin(admin, fetcher) { it.listDiscoveredPartnerServers() }.getOrThrow()
                list.map { it.host to it.hops } shouldBe listOf("b.beispiel.de" to 2, "c.beispiel.de" to 3)
                list[1].viaChain shouldBe listOf("a.beispiel.de", "b.beispiel.de")
                list[0].rootName shouldBe "Alpha"
                val overview = asAdmin(admin, fetcher) { it.list() }.getOrThrow()
                overview.partners.single().discoveredServerCount shouldBe 2
                overview.partners.single().discoveredEventCount shouldBe 2
                val settings = asAdmin(admin, fetcher) { it.getPartnerRecursionSettings() }.getOrThrow()
                settings.summary.shouldNotBeNull().reachedByHop shouldBe listOf(1, 1)
            }
        }

        test(
            "the audit log records adds, switches, removals, blocks and the list switch -- hosts and patterns only, and the chain is intact",
        ) {
            val admin = fx.member(role = AccountRole.ADMIN)
            val fetcher = fakeFetcher { json(body = feedJson(name = "Geheimer Fremdname XYZ")) }
            val mark = fx.auditMark()
            withServiceCaller {
                asAdmin(admin, fetcher) { it.probeAndSave(address = "partner.beispiel.de", displayName = null) }.getOrThrow().saved shouldBe
                    true
                val id = partnerRows().single()[PartnerServerTable.id].toString()
                asAdmin(admin, fetcher) { it.setActive(id = id, active = false) }.getOrThrow()
                asAdmin(admin, fetcher) { it.setActive(id = id, active = false) }.getOrThrow() // no change -> no second entry
                asAdmin(admin, fetcher) { it.setPartnerListHidden(true) }.getOrThrow()
                val block = asAdmin(admin, fetcher) { it.addBlock("spam.beispiel.de") }.getOrThrow().block!!
                asAdmin(admin, fetcher) { it.removeBlock(block.id) }.getOrThrow()
                asAdmin(admin, fetcher) { it.remove(id) }.getOrThrow()
            }
            val partnerEntries = fx.auditSince(mark = mark, type = AuditEntityType.PARTNER_SERVER)
            val blockEntries = fx.auditSince(mark = mark, type = AuditEntityType.PARTNER_BLOCKLIST)
            partnerEntries shouldHaveSize 4
            blockEntries shouldHaveSize 2
            val all = (partnerEntries + blockEntries).joinToString { it[AuditLogEntryTable.afterSnapshot].orEmpty() }
            all shouldContain "partner.beispiel.de"
            all shouldNotContain "Geheimer Fremdname"
            fx.chainProblemSince(mark).shouldBeNull()
        }

        test(
            "the poller: a success schedules the next slot, failures back off, expired rows are purged, only partner hosts are contacted",
        ) {
            val admin = fx.member(role = AccountRole.ADMIN)
            val net = FakeNetwork()
            var answer: () -> ByteArray = {
                feedJson(events = events(2), partners = """[{"kind":"partner","host":"dritter.beispiel.de","name":"Dritter"}]""")
            }
            val fetcher = fakeFetcher(net = net) { json(body = answer()) }
            withServiceCaller {
                asAdmin(admin, fetcher) { it.probeAndSave(address = "partner.beispiel.de", displayName = null) }.getOrThrow().saved shouldBe
                    true
            }
            // The recursion has its own scenarios; this test pins the P1 behaviour of the direct fetch, so the partner list is not followed here.
            transaction { PartnerDiscoveryStore.setRecursionSettings(enabled = false, depth = 2) }
            var clockNow = NOW
            val poller = PartnerFeedPoller(fetcher = fetcher, clock = { clockNow })

            fun row() = partnerRows().single()

            // not due yet -> nothing happens
            val requestsAfterProbe = net.requests.get()
            poller.tick()
            net.requests.get() shouldBe requestsAfterProbe

            // due -> fetched, next slot after the next full hour, the third party of the partner list is NOT contacted
            transaction { PartnerServerTable.update({ PartnerServerTable.host eq PARSER_HOST }) { it[nextFetchAt] = NOW } }
            poller.tick()
            net.requests.get() shouldBe requestsAfterProbe + 1
            net.hosts.toSet() shouldBe setOf("93.184.216.34")
            (row()[PartnerServerTable.nextFetchAt] > NOW) shouldBe true
            row()[PartnerServerTable.remotePartnersJson].shouldNotBeNull() shouldContain "dritter.beispiel.de"

            // failures: 1 h, 2 h, 4 h -- the cache stays, the code is stored
            answer = { "broken".toByteArray() }
            val expected = listOf(1L, 2L, 4L)
            expected.forEachIndexed { index, hours ->
                transaction { PartnerServerTable.update({ PartnerServerTable.host eq PARSER_HOST }) { it[nextFetchAt] = clockNow } }
                poller.tick()
                row()[PartnerServerTable.consecutiveFailures] shouldBe index + 1
                row()[PartnerServerTable.lastStatus] shouldBe PartnerFetchStatus.ERROR
                row()[PartnerServerTable.lastError] shouldBe "INVALID_FEED"
                row()[PartnerServerTable.nextFetchAt] shouldBe LocalDateTime(2026, 11, 5, 10 + hours.toInt(), 0, 0)
            }
            eventRows() shouldHaveSize 2

            // a success resets the counter
            answer = { feedJson(events = events(2)) }
            transaction { PartnerServerTable.update({ PartnerServerTable.host eq PARSER_HOST }) { it[nextFetchAt] = clockNow } }
            poller.tick()
            row()[PartnerServerTable.consecutiveFailures] shouldBe 0
            row()[PartnerServerTable.lastStatus] shouldBe PartnerFetchStatus.OK

            // a day later without a refresh the cache is purged
            clockNow = LocalDateTime(2026, 11, 6, 10, 0, 0)
            transaction { PartnerServerTable.update({ PartnerServerTable.host eq PARSER_HOST }) { it[active] = false } }
            poller.tick()
            eventRows().shouldBeEmpty()
        }

        test("a 304 keeps the cache alive and a stopped poller start/stop is idempotent") {
            val admin = fx.member(role = AccountRole.ADMIN)
            var notModified = false
            val fetcher =
                fakeFetcher {
                    if (notModified) {
                        respond(
                            "",
                            io.ktor.http.HttpStatusCode.NotModified,
                        )
                    } else {
                        json(body = feedJson(events = events(2)), etag = "\"v1\"")
                    }
                }
            withServiceCaller {
                asAdmin(admin, fetcher) { it.probeAndSave(address = "partner.beispiel.de", displayName = null) }.getOrThrow().saved shouldBe
                    true
            }
            notModified = true
            val later = LocalDateTime(2026, 11, 5, 14, 0, 0)
            transaction { PartnerServerTable.update({ PartnerServerTable.host eq PARSER_HOST }) { it[nextFetchAt] = NOW } }
            PartnerFeedPoller(fetcher = fetcher, clock = { later }).tick()
            eventRows() shouldHaveSize 2
            eventRows().first()[PartnerEventTable.expiresAt] shouldBe LocalDateTime(2026, 11, 5, 20, 0, 0)
            val poller = PartnerFeedPoller(fetcher = fetcher, tickInterval = 1.minutes, clock = { later })
            poller.start()
            poller.start()
            poller.stop()
            poller.stop()
        }

        test("after an unblock a conditional-GET partner (304 on a matching ETag) is refilled, not confirmed empty") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val fetcher =
                fakeFetcher { req ->
                    if (req.headers[io.ktor.http.HttpHeaders.IfNoneMatch] == "\"v1\"") {
                        respond("", io.ktor.http.HttpStatusCode.NotModified)
                    } else {
                        json(body = feedJson(events = events(3)), etag = "\"v1\"")
                    }
                }
            withServiceCaller {
                asAdmin(admin, fetcher) { it.probeAndSave(address = "partner.beispiel.de", displayName = null) }.getOrThrow().saved shouldBe
                    true
                asAdmin(admin, fetcher) { it.addBlock("*.beispiel.de") }.getOrThrow().invalid shouldBe false
                eventRows().shouldBeEmpty()
                val blockId =
                    asAdmin(admin, fetcher) { it.list() }
                        .getOrThrow()
                        .blocklist
                        .single()
                        .id
                asAdmin(admin, fetcher) { it.removeBlock(blockId) }.getOrThrow()
                transaction { PartnerServerTable.update({ PartnerServerTable.host eq PARSER_HOST }) { it[nextFetchAt] = NOW } }
                PartnerFeedPoller(fetcher = fetcher, clock = { NOW }).tick()
            }
            eventRows() shouldHaveSize 3
        }

        test("a purged cache drops the ETag and the counter, so the next fetch is unconditional") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val fetcher = fakeFetcher { json(body = feedJson(events = events(2)), etag = "\"v1\"") }
            withServiceCaller {
                asAdmin(admin, fetcher) { it.probeAndSave(address = "partner.beispiel.de", displayName = null) }.getOrThrow().saved shouldBe
                    true
            }
            transaction { PartnerServerStore.purgeExpired(now = LocalDateTime(2030, 1, 1, 0, 0, 0)) }
            eventRows().shouldBeEmpty()
            transaction {
                val row = PartnerServerTable.selectAll().where { PartnerServerTable.host eq PARSER_HOST }.single()
                row[PartnerServerTable.etag] shouldBe null
                row[PartnerServerTable.eventCount] shouldBe 0
            }
        }

        test("the own partner list switch is stored and the server id is created once and stays") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val fetcher = fakeFetcher { json(body = feedJson()) }
            withServiceCaller {
                asAdmin(admin, fetcher) { it.setPartnerListHidden(true) }.getOrThrow()
                asAdmin(admin, fetcher) { it.list() }.getOrThrow().partnerListHidden shouldBe true
                asAdmin(admin, fetcher) { it.setPartnerListHidden(false) }.getOrThrow()
            }
            val first = transaction { PartnerServerStore.ensureServerId() }
            transaction { PartnerServerStore.ensureServerId() } shouldBe first
            transaction {
                OrganizationSettingsTable.selectAll().single()[OrganizationSettingsTable.partnerServerId]
            } shouldBe first
        }
    })

private fun wipe() {
    wipePartnerTables()
    transaction { OrganizationSettingsTable.update { it[partnerListHidden] = false } }
}

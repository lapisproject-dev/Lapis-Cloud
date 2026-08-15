package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceStreamingConfig
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.LiveKitEgressClient
import network.lapis.cloud.server.conference.LiveKitEgressInfo
import network.lapis.cloud.server.conference.StreamPoller
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamDestinationTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTargetTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ConferenceStreamDestinationDto
import network.lapis.cloud.shared.domain.ConferenceStreamDto
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"

/** [ConferenceConfig] with `enabled=true` -- built via the injectable `env` seam, no real env vars touched. */
private val ENABLED_CONFERENCE_CONFIG =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            else -> null
        }
    }

private val DISABLED_CONFERENCE_CONFIG = ConferenceConfig.load { null }

/** Deterministic 32-byte test key -- never a real secret, this file's own `SecretBox` usage never touches real env vars either. */
private val TEST_ENCRYPTION_KEY_B64 = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

private val ENABLED_STREAMING_CONFIG =
    ConferenceStreamingConfig.load { key ->
        when (key) {
            "LAPIS_STREAMING_ENABLED" -> "true"
            "LAPIS_SECRET_ENCRYPTION_KEY" -> TEST_ENCRYPTION_KEY_B64
            "LAPIS_STREAM_MAX_DESTINATIONS" -> "2"
            else -> null
        }
    }

private val DISABLED_STREAMING_CONFIG = ConferenceStreamingConfig.load { null }

/**
 * Exercises [ConferenceStreamingService] end to end, mirroring
 * [ConferenceRecordingServiceTest]'s house style (throwaway routes calling the service class
 * directly, fields pipe-separated in the response body). Unlike [ConferenceRecordingService],
 * THIS service DOES call [LiveKitEgressClient] directly (`startStream`/`pauseStream`/
 * `resumeStream`/`stopStream`) -- every test uses [FakeLiveKitEgressClient], zero real network
 * involvement. [afterSpec] hard-deletes every stream/destination/room/member row this file created.
 */
class ConferenceStreamingServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdRoomIds = mutableListOf<Uuid>()
        val createdDestinationIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpConferenceStreamingTestData(createdMemberIds, createdRoomIds, createdDestinationIds) }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.AKTIV,
            role: AccountRole = AccountRole.MEMBER,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Streaming Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
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

        fun createTestRoom(
            creatorId: Uuid,
            title: String,
            ended: Boolean = false,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[ConferenceRoomTable.title] = title
                    it[description] = ""
                    it[livekitRoomName] = "lc-stream-test-$id"
                    it[createdByMemberId] = creatorId
                    it[createdAt] = now
                    it[endedAt] = if (ended) now else null
                    it[maxParticipants] = 25
                }
            }
            createdRoomIds += id
            return id
        }

        /** Inserts a `conference_stream_destination` row directly, bypassing the ADMIN-only RPC path -- used by tests that only need a destination to already EXIST, not to exercise createDestination itself. */
        fun createTestDestination(
            label: String,
            creatorId: Uuid,
            streamKey: String = "test-stream-key-123456",
            enabled: Boolean = true,
        ): Uuid {
            val id = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            val secretBox = SecretBox(Base64.getDecoder().decode(TEST_ENCRYPTION_KEY_B64))
            transaction {
                ConferenceStreamDestinationTable.insert {
                    it[ConferenceStreamDestinationTable.id] = id
                    it[ConferenceStreamDestinationTable.label] = label
                    it[platform] = ConferenceStreamPlatform.GENERIC_RTMP
                    it[rtmpUrl] = "rtmp://sink.example.org:1935/live"
                    it[streamKeyCiphertext] = secretBox.seal(streamKey, aad = id.toString())
                    it[streamKeySetAt] = now
                    it[createdByMemberId] = creatorId
                    it[createdAt] = now
                    it[ConferenceStreamDestinationTable.enabled] = enabled
                }
            }
            createdDestinationIds += id
            return id
        }

        // ── getStreamingAvailability ──────────────────────────────────────

        test("getStreamingAvailability: all gates true -> enabled=true; any one false -> enabled=false, never throws") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing {
                        get("/test/availability-all-true") {
                            val dto =
                                ConferenceStreamingService(
                                    call,
                                    FakeLiveKitEgressClient(),
                                    ENABLED_CONFERENCE_CONFIG,
                                    ENABLED_STREAMING_CONFIG,
                                ).getStreamingAvailability()
                            call.respondText(dto.toPipeString())
                        }
                        get("/test/availability-streaming-disabled") {
                            val dto =
                                ConferenceStreamingService(
                                    call,
                                    FakeLiveKitEgressClient(),
                                    ENABLED_CONFERENCE_CONFIG,
                                    DISABLED_STREAMING_CONFIG,
                                ).getStreamingAvailability()
                            call.respondText(dto.toPipeString())
                        }
                        get("/test/availability-conference-disabled") {
                            val dto =
                                ConferenceStreamingService(
                                    call,
                                    FakeLiveKitEgressClient(),
                                    DISABLED_CONFERENCE_CONFIG,
                                    ENABLED_STREAMING_CONFIG,
                                ).getStreamingAvailability()
                            call.respondText(dto.toPipeString())
                        }
                    }
                }
                val member = createTestMember("stream-availability@example.org")

                // configuredDestinationCount is DB-global (every destination this whole spec ever
                // creates persists until afterSpec) -- only the config-derived fields are asserted
                // exactly, never that count.
                client.get("/test/availability-all-true") { header("X-Member-Id", member.toString()) }.bodyAsText().split("|").let { p ->
                    p[0] shouldBe "true"
                    p[1] shouldBe "true"
                    p[2] shouldBe "2"
                }
                client
                    .get(
                        "/test/availability-streaming-disabled",
                    ) { header("X-Member-Id", member.toString()) }
                    .bodyAsText()
                    .split("|")
                    .let { p ->
                        p[0] shouldBe "false"
                        p[1] shouldBe "false"
                        // LAPIS_STREAM_MAX_DESTINATIONS falls back to ConferenceStreamingConfig's
                        // own DEFAULT_MAX_DESTINATIONS (3) when unset -- unrelated to `enabled`.
                        p[2] shouldBe "3"
                    }
                client
                    .get(
                        "/test/availability-conference-disabled",
                    ) { header("X-Member-Id", member.toString()) }
                    .bodyAsText()
                    .split("|")
                    .let { p ->
                        p[0] shouldBe "false"
                        p[1] shouldBe "true"
                        p[2] shouldBe "2"
                    }
            }
        }

        test("getStreamingAvailability: unauthenticated caller is rejected") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing {
                        get("/test/availability") {
                            val dto =
                                ConferenceStreamingService(
                                    call,
                                    FakeLiveKitEgressClient(),
                                    ENABLED_CONFERENCE_CONFIG,
                                    ENABLED_STREAMING_CONFIG,
                                ).getStreamingAvailability()
                            call.respondText(dto.toPipeString())
                        }
                    }
                }
                client.get("/test/availability").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        // ── Destination CRUD -- ADMIN-only role matrix ───────────────────

        test("createDestination: ADMIN succeeds, streamKeyMask is ALWAYS the constant mask, never the real key or its length") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val response =
                    client.post(
                        "/test/create-destination?label=YouTube-Kanal&platform=YOUTUBE&rtmpUrl=rtmp://a.rtmp.youtube.com/live2&streamKey=super-secret-key-value",
                    ) {
                        header("X-Member-Id", ADMIN_ID)
                    }
                response.status shouldBe HttpStatusCode.OK
                val dto = response.bodyAsText().toDestinationDto()
                dto.streamKeyMask shouldBe "********"
                dto.rtmpUrl shouldBe "rtmp://a.rtmp.youtube.com/live2"
                dto.label shouldBe "YouTube-Kanal"
                createdDestinationIds += Uuid.parse(dto.id)

                // The raw response body itself must never contain the plaintext key, under any name.
                response.bodyAsText() shouldNotContainPlaintext "super-secret-key-value"
            }
        }

        test("createDestination: rejected with Forbidden for a non-ADMIN (BOARD, plain member)") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val member = createTestMember("stream-create-forbidden@example.org")

                client
                    .post("/test/create-destination?label=X&platform=GENERIC_RTMP&rtmpUrl=rtmp://x.example.org/live&streamKey=k") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/create-destination?label=X&platform=GENERIC_RTMP&rtmpUrl=rtmp://x.example.org/live&streamKey=k") {
                        header("X-Member-Id", member.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "createDestination: duplicate label is rejected with Conflict; blank streamKey/label and non-rtmp scheme rejected with BadRequest",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                client
                    .post("/test/create-destination?label=Duplikat&platform=GENERIC_RTMP&rtmpUrl=rtmp://a.example.org/live&streamKey=k1") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.OK
                client
                    .post("/test/create-destination?label=Duplikat&platform=GENERIC_RTMP&rtmpUrl=rtmp://b.example.org/live&streamKey=k2") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.Conflict
                client
                    .post("/test/create-destination?label=&platform=GENERIC_RTMP&rtmpUrl=rtmp://c.example.org/live&streamKey=k3") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.BadRequest
                client
                    .post(
                        "/test/create-destination?label=Leerschluessel&platform=GENERIC_RTMP&rtmpUrl=rtmp://d.example.org/live&streamKey=",
                    ) {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.BadRequest
                client
                    .post(
                        "/test/create-destination?label=HttpScheme&platform=GENERIC_RTMP&rtmpUrl=https://e.example.org/live&streamKey=k4",
                    ) {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test(
            "createDestination: loopback/link-local(metadata)/multicast/IPv6-unique-local literal IPs and 'localhost' are rejected with BadRequest -- RFC1918 literals and ordinary hostnames remain allowed",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    // This test issues far more than 5 createDestination calls for the SAME ADMIN
                    // account -- the default destinationRateLimiter (LoginRateLimiter, maxFailures=5,
                    // see requireWithinLoginRate) would otherwise itself start returning Conflict
                    // partway through and mask the BadRequest assertions this test is actually
                    // checking.
                    routing { registerConferenceStreamingTestRoutes(destinationLimiter = LoginRateLimiter(maxFailures = 50)) }
                }

                suspend fun createWith(
                    label: String,
                    rtmpUrl: String,
                ) = client.post("/test/create-destination?label=$label&platform=GENERIC_RTMP&rtmpUrl=$rtmpUrl&streamKey=k") {
                    header("X-Member-Id", ADMIN_ID)
                }

                // Brackets are percent-encoded (%5B/%5D) in the IPv6-literal URLs below -- unlike
                // ":" (already used unencoded in every other rtmp:// test URL in this file), "[" and
                // "]" are not valid raw query-string characters per RFC 3986, so they are encoded
                // here for a robust request rather than relying on lenient client-side parsing. The
                // server decodes them back via call.request.queryParameters before validateRtmpUrl
                // ever sees the string, so the effective rtmpUrl is the bracketed literal either way.

                // -- rejected: loopback --
                createWith("Loop-V4", "rtmp://127.0.0.1/live").status shouldBe HttpStatusCode.BadRequest
                createWith("Loop-V6", "rtmp://%5B::1%5D/live").status shouldBe HttpStatusCode.BadRequest
                createWith("Loop-Hostname", "rtmp://localhost/live").status shouldBe HttpStatusCode.BadRequest

                // -- rejected: link-local, INCLUDING the 169.254.169.254 cloud-metadata address --
                createWith("Metadata", "rtmp://169.254.169.254/live").status shouldBe HttpStatusCode.BadRequest
                createWith("LinkLocalV4", "rtmp://169.254.1.1/live").status shouldBe HttpStatusCode.BadRequest
                createWith("LinkLocalV6", "rtmp://%5Bfe80::1%5D/live").status shouldBe HttpStatusCode.BadRequest

                // -- rejected: IPv6 unique-local (fd00::/8 / fc00::/7, the ULA equivalent of RFC1918) --
                createWith("Ula", "rtmp://%5Bfd00::1%5D/live").status shouldBe HttpStatusCode.BadRequest

                // -- rejected: multicast, any-local --
                createWith("Multicast", "rtmp://224.0.0.1/live").status shouldBe HttpStatusCode.BadRequest
                createWith("AnyLocal", "rtmp://0.0.0.0/live").status shouldBe HttpStatusCode.BadRequest

                // -- STILL allowed: RFC1918 private-range literals (the documented on-prem-Owncast case) --
                val rfc1918 = createWith("Onprem-Ipv4-Literal", "rtmp://192.168.1.50/live")
                rfc1918.status shouldBe HttpStatusCode.OK
                createdDestinationIds += Uuid.parse(rfc1918.bodyAsText().toDestinationDto().id)

                // -- STILL allowed: any hostname, unresolved (deliberately, see validateRtmpUrl KDoc) --
                val hostname = createWith("Onprem-Hostname", "rtmp://owncast.internal/live")
                hostname.status shouldBe HttpStatusCode.OK
                createdDestinationIds += Uuid.parse(hostname.bodyAsText().toDestinationDto().id)
            }
        }

        test("updateDestination: rejects a loopback/link-local literal rtmpUrl the same way createDestination does") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val admin = Uuid.parse(ADMIN_ID)
                val destId = createTestDestination("Update-Ssrf-Ziel", admin)

                client
                    .post("/test/update-destination?destinationId=$destId&label=Update-Ssrf-Ziel&rtmpUrl=rtmp://169.254.169.254/live") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("updateDestination: newStreamKey=null leaves the stored key unchanged; a blank newStreamKey is rejected, never stored") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val admin = Uuid.parse(ADMIN_ID)
                val destId = createTestDestination("Update-Ziel", admin)

                // null newStreamKey -> "unchanged": omit the query param entirely.
                val unchanged =
                    client.post(
                        "/test/update-destination?destinationId=$destId&label=Update-Ziel-Neu&rtmpUrl=rtmp://new.example.org/live",
                    ) {
                        header("X-Member-Id", ADMIN_ID)
                    }
                unchanged.status shouldBe HttpStatusCode.OK
                unchanged.bodyAsText().toDestinationDto().label shouldBe "Update-Ziel-Neu"

                val blank =
                    client.post(
                        "/test/update-destination?destinationId=$destId&label=Update-Ziel-Neu&rtmpUrl=rtmp://new.example.org/live&newStreamKey=",
                    ) {
                        header("X-Member-Id", ADMIN_ID)
                    }
                blank.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test(
            "deleteDestination: refused with Conflict while a LIVE stream references it; STILL refused once ENDED (no-cascade FK backstop, see class KDoc); a destination with NO stream history at all deletes cleanly",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val creator = createTestMember("stream-delete-live@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val destId = createTestDestination("Delete-Live-Ziel", creator)

                val startResponse =
                    client.post("/test/start-stream?roomId=$roomId&destinationIds=$destId&layout=GRID&latencyMode=STANDARD") {
                        header("X-Member-Id", creator.toString())
                    }
                startResponse.status shouldBe HttpStatusCode.OK
                val streamDto = startResponse.bodyAsText().toStreamDto()
                streamDto.status shouldBe ConferenceStreamStatus.LIVE

                // Blocked by the explicit "active reference" guard.
                client.post("/test/delete-destination?destinationId=$destId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.Conflict

                client.post("/test/stop-stream?streamId=${streamDto.id}") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK

                // Still blocked -- NOT by the active-reference guard anymore (the stream is ENDED),
                // but by the FK backstop: conference_stream_target has NO ON DELETE CASCADE, and the
                // now-historical target row still references this destination. See
                // ConferenceStreamingService.deleteDestination KDoc "Backstop".
                client.post("/test/delete-destination?destinationId=$destId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.Conflict

                // A destination that was NEVER referenced by any stream at all has no historical
                // target rows to collide with -- deletes cleanly.
                val neverUsedDestId = createTestDestination("Never-Used-Ziel", creator)
                client.post("/test/delete-destination?destinationId=$neverUsedDestId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.OK
            }
        }

        // ── startStream ────────────────────────────────────────────────────

        test("startStream: happy path as the room's own creator -> LIVE, correct target, egress called once") {
            testApplication {
                val fakeClient = FakeLiveKitEgressClient()
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes(egressClient = fakeClient) }
                }
                val creator = createTestMember("stream-start-happy@example.org")
                val roomId = createTestRoom(creator, "Mitgliederversammlung")
                val destId = createTestDestination("Happy-Ziel", creator)

                val response =
                    client.post("/test/start-stream?roomId=$roomId&destinationIds=$destId&layout=GRID&latencyMode=STANDARD") {
                        header("X-Member-Id", creator.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                val dto = response.bodyAsText().toStreamDto()
                dto.status shouldBe ConferenceStreamStatus.LIVE
                dto.roomId shouldBe roomId.toString()
                dto.startedByMemberId shouldBe creator.toString()
                fakeClient.startCalls.size shouldBe 1
            }
        }

        test("startStream: allowed for a global BOARD account even though it did not create the room") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val creator = createTestMember("stream-start-board@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val destId = createTestDestination("Board-Ziel", creator)

                client
                    .post("/test/start-stream?roomId=$roomId&destinationIds=$destId&layout=GRID&latencyMode=STANDARD") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        test("startStream: rejected with Forbidden for an ordinary participant who is neither creator nor privileged") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val creator = createTestMember("stream-start-forbidden-creator@example.org")
                val other = createTestMember("stream-start-forbidden-other@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val destId = createTestDestination("Forbidden-Ziel", creator)

                client
                    .post("/test/start-stream?roomId=$roomId&destinationIds=$destId&layout=GRID&latencyMode=STANDARD") {
                        header("X-Member-Id", other.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("startStream: rejected with Conflict for too many destinations, a disabled destination, or an unknown destination") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val creator = createTestMember("stream-start-validation@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val d1 = createTestDestination("Val-1", creator)
                val d2 = createTestDestination("Val-2", creator)
                val d3 = createTestDestination("Val-3", creator)
                val disabled = createTestDestination("Val-Disabled", creator, enabled = false)

                // maxDestinations=2 in ENABLED_STREAMING_CONFIG -- three exceeds it.
                client
                    .post("/test/start-stream?roomId=$roomId&destinationIds=$d1,$d2,$d3&layout=GRID&latencyMode=STANDARD") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict

                client
                    .post("/test/start-stream?roomId=$roomId&destinationIds=$disabled&layout=GRID&latencyMode=STANDARD") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict

                client
                    .post("/test/start-stream?roomId=$roomId&destinationIds=${Uuid.random()}&layout=GRID&latencyMode=STANDARD") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("startStream: SINGLE_PARTICIPANT without a participantIdentity is rejected with Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val creator = createTestMember("stream-start-single@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val destId = createTestDestination("Single-Ziel", creator)

                client
                    .post("/test/start-stream?roomId=$roomId&destinationIds=$destId&layout=SINGLE_PARTICIPANT&latencyMode=STANDARD") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("startStream: one-active-stream-per-room invariant -- a second start while LIVE is rejected with Conflict") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val creator = createTestMember("stream-start-invariant@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val destId = createTestDestination("Invariant-Ziel", creator)

                client
                    .post("/test/start-stream?roomId=$roomId&destinationIds=$destId&layout=GRID&latencyMode=STANDARD") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.OK
                client
                    .post("/test/start-stream?roomId=$roomId&destinationIds=$destId&layout=GRID&latencyMode=STANDARD") {
                        header("X-Member-Id", creator.toString())
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("startStream: two genuinely concurrent starts for the same room -- exactly one succeeds, the other Conflicts") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val creator = createTestMember("stream-start-race-creator@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val destId = createTestDestination("Race-Ziel", creator)

                val outcomes = runConcurrentStartStream(client, roomId.toString(), destId.toString(), creator, Uuid.parse(BOARD_ID))
                outcomes.count { it == HttpStatusCode.OK } shouldBe 1
                outcomes.count { it == HttpStatusCode.Conflict } shouldBe 1

                val activeCount =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                (ConferenceStreamTable.roomId eq roomId) and
                                    (
                                        ConferenceStreamTable.status inList
                                            listOf(ConferenceStreamStatus.STARTING, ConferenceStreamStatus.LIVE)
                                    )
                            }.count()
                    }
                activeCount shouldBe 1
            }
        }

        test(
            "startStream: a destination already targeted by an active stream in a DIFFERENT room is rejected with Conflict, even though the room-level invariant is per-room",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val creatorA = createTestMember("stream-cross-room-a@example.org")
                val creatorB = createTestMember("stream-cross-room-b@example.org")
                val roomA = createTestRoom(creatorA, "Sitzung A")
                val roomB = createTestRoom(creatorB, "Sitzung B")
                val sharedDest = createTestDestination("Shared-Ziel", creatorA)

                // Room A starts first -- succeeds, destination is now LIVE-referenced.
                client
                    .post("/test/start-stream?roomId=$roomA&destinationIds=$sharedDest&layout=GRID&latencyMode=STANDARD") {
                        header("X-Member-Id", creatorA.toString())
                    }.status shouldBe HttpStatusCode.OK

                // Room B is a COMPLETELY independent room -- the one-active-stream-per-room check
                // alone would let this through. It must still be rejected because the destination
                // itself is already targeted by Room A's active stream.
                client
                    .post("/test/start-stream?roomId=$roomB&destinationIds=$sharedDest&layout=GRID&latencyMode=STANDARD") {
                        header("X-Member-Id", creatorB.toString())
                    }.status shouldBe HttpStatusCode.Conflict

                // Once Room A's stream is stopped, the destination is free again for Room B.
                val roomAStream =
                    transaction {
                        ConferenceStreamTable.selectAll().where { ConferenceStreamTable.roomId eq roomA }.single()
                    }
                client
                    .post("/test/stop-stream?streamId=${roomAStream[ConferenceStreamTable.id]}") {
                        header("X-Member-Id", creatorA.toString())
                    }.status shouldBe HttpStatusCode.OK

                client
                    .post("/test/start-stream?roomId=$roomB&destinationIds=$sharedDest&layout=GRID&latencyMode=STANDARD") {
                        header("X-Member-Id", creatorB.toString())
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "startStream: two genuinely concurrent starts in DIFFERENT rooms targeting the SAME destination -- exactly one succeeds, the other Conflicts",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val creatorA = createTestMember("stream-cross-room-race-a@example.org")
                val creatorB = createTestMember("stream-cross-room-race-b@example.org")
                val roomA = createTestRoom(creatorA, "Sitzung A")
                val roomB = createTestRoom(creatorB, "Sitzung B")
                val sharedDest = createTestDestination("Race-Cross-Room-Ziel", creatorA)

                val outcomes =
                    runConcurrentStartStreamAcrossRooms(
                        client,
                        roomA.toString(),
                        roomB.toString(),
                        sharedDest.toString(),
                        creatorA,
                        creatorB,
                    )
                outcomes.count { it == HttpStatusCode.OK } shouldBe 1
                outcomes.count { it == HttpStatusCode.Conflict } shouldBe 1

                val activeTargetCount =
                    transaction {
                        (ConferenceStreamTargetTable innerJoin ConferenceStreamTable)
                            .selectAll()
                            .where {
                                (ConferenceStreamTargetTable.destinationId eq sharedDest) and
                                    (
                                        ConferenceStreamTable.status inList
                                            listOf(ConferenceStreamStatus.STARTING, ConferenceStreamStatus.LIVE)
                                    )
                            }.count()
                    }
                activeTargetCount shouldBe 1
            }
        }

        test("startStream: a synchronous LiveKit failure marks the stream and every target FAILED, never LIVE") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes(egressClient = FakeLiveKitEgressClient(failStart = true)) }
                }
                val creator = createTestMember("stream-start-failure@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val destId = createTestDestination("Failure-Ziel", creator)

                val response =
                    client.post("/test/start-stream?roomId=$roomId&destinationIds=$destId&layout=GRID&latencyMode=STANDARD") {
                        header("X-Member-Id", creator.toString())
                    }
                response.status shouldBe HttpStatusCode.OK
                val dto = response.bodyAsText().toStreamDto()
                dto.status shouldBe ConferenceStreamStatus.FAILED
                dto.failureReason shouldBe "Der Stream konnte nicht gestartet werden."
            }
        }

        // ── pauseStream / resumeStream ───────────────────────────────────

        test("pauseStream then resumeStream: resume mints a NEW egress id and bumps restartCount; pause is idempotent") {
            testApplication {
                val fakeClient = FakeLiveKitEgressClient()
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes(egressClient = fakeClient) }
                }
                val creator = createTestMember("stream-pause-resume@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val destId = createTestDestination("PauseResume-Ziel", creator)

                val startDto =
                    client
                        .post("/test/start-stream?roomId=$roomId&destinationIds=$destId&layout=GRID&latencyMode=STANDARD") {
                            header("X-Member-Id", creator.toString())
                        }.bodyAsText()
                        .toStreamDto()
                val firstEgressId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.id eq Uuid.parse(startDto.id)
                            }.single()[ConferenceStreamTable.livekitEgressId]
                    }

                val paused =
                    client.post("/test/pause-stream?streamId=${startDto.id}") { header("X-Member-Id", creator.toString()) }
                paused.status shouldBe HttpStatusCode.OK
                paused.bodyAsText().toStreamDto().status shouldBe ConferenceStreamStatus.PAUSED
                fakeClient.stopCalls.size shouldBe 1

                // Idempotent -- pausing an already-PAUSED stream is a no-op, no second StopEgress call.
                client.post("/test/pause-stream?streamId=${startDto.id}") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK
                fakeClient.stopCalls.size shouldBe 1

                val resumed =
                    client.post("/test/resume-stream?streamId=${startDto.id}") { header("X-Member-Id", creator.toString()) }
                resumed.status shouldBe HttpStatusCode.OK
                val resumedDto = resumed.bodyAsText().toStreamDto()
                resumedDto.status shouldBe ConferenceStreamStatus.LIVE
                resumedDto.restartCount shouldBe 1

                val newEgressId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where {
                                ConferenceStreamTable.id eq Uuid.parse(startDto.id)
                            }.single()[ConferenceStreamTable.livekitEgressId]
                    }
                newEgressId shouldNotBe firstEgressId
            }
        }

        test("resumeStream: rejected with Conflict when the stream is not PAUSED") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val creator = createTestMember("stream-resume-not-paused@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val destId = createTestDestination("NotPaused-Ziel", creator)
                val startDto =
                    client
                        .post("/test/start-stream?roomId=$roomId&destinationIds=$destId&layout=GRID&latencyMode=STANDARD") {
                            header("X-Member-Id", creator.toString())
                        }.bodyAsText()
                        .toStreamDto()

                client.post("/test/resume-stream?streamId=${startDto.id}") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        test(
            "resumeStream: a MANUAL resumeStream click succeeds even when the destination was disabled " +
                "while paused -- the MINOR-9 gate is AUTO-resume-only, restartEgressForStream's own KDoc " +
                "asymmetry (security-audit-round-2 regression coverage, the round-2 audit noted this test " +
                "was missing)",
        ) {
            testApplication {
                val fakeClient = FakeLiveKitEgressClient()
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes(egressClient = fakeClient) }
                }
                val creator = createTestMember("stream-resume-manual-disabled-dest@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val destId = createTestDestination("Manual-Resume-Disabled-Ziel", creator)
                val startDto =
                    client
                        .post("/test/start-stream?roomId=$roomId&destinationIds=$destId&layout=GRID&latencyMode=STANDARD") {
                            header("X-Member-Id", creator.toString())
                        }.bodyAsText()
                        .toStreamDto()

                client.post("/test/pause-stream?streamId=${startDto.id}") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK

                // Operator disables the destination WHILE the stream sits paused -- exactly the MINOR-9
                // setup, but this time the resume is a HUMAN, in-the-moment click (actorMemberId != null
                // via resumeStream's own caller), not the SECRET_BALLOT auto-resume machinery.
                transaction {
                    ConferenceStreamDestinationTable.update({ ConferenceStreamDestinationTable.id eq destId }) { it[enabled] = false }
                }

                val resumed = client.post("/test/resume-stream?streamId=${startDto.id}") { header("X-Member-Id", creator.toString()) }
                resumed.status shouldBe HttpStatusCode.OK
                resumed.bodyAsText().toStreamDto().status shouldBe ConferenceStreamStatus.LIVE
                fakeClient.startCalls.size shouldBe 2 // original startStream + this manual resume
            }
        }

        // ── stopStream ────────────────────────────────────────────────────

        test("stopStream: happy path transitions LIVE -> ENDED, idempotent on a second call") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val creator = createTestMember("stream-stop-happy@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val destId = createTestDestination("Stop-Ziel", creator)
                val startDto =
                    client
                        .post("/test/start-stream?roomId=$roomId&destinationIds=$destId&layout=GRID&latencyMode=STANDARD") {
                            header("X-Member-Id", creator.toString())
                        }.bodyAsText()
                        .toStreamDto()

                val firstStop = client.post("/test/stop-stream?streamId=${startDto.id}") { header("X-Member-Id", creator.toString()) }
                firstStop.status shouldBe HttpStatusCode.OK
                firstStop.bodyAsText().toStreamDto().status shouldBe ConferenceStreamStatus.ENDED

                val secondStop = client.post("/test/stop-stream?streamId=${startDto.id}") { header("X-Member-Id", creator.toString()) }
                secondStop.status shouldBe HttpStatusCode.OK
                secondStop.bodyAsText().toStreamDto().status shouldBe ConferenceStreamStatus.ENDED
            }
        }

        // ── stopStream -- security-audit-round-2 F1 regression: a race with startStream's still-
        // in-flight LiveKit call must never leave a real, unstopped egress behind a terminal row ──

        test(
            "stopStream: races startStream's still-in-flight LiveKit call (row STARTING, no egress id " +
                "recorded yet at stopStream's Tx1) -- the row is resurrected from ENDED back to STOPPING " +
                "once the fresh egress id is known, and StreamPoller's next tick actually stops it " +
                "(security-audit-round-2 F1)",
        ) {
            testApplication {
                val readyLatch = CountDownLatch(1)
                val releaseLatch = CountDownLatch(1)
                val fakeClient =
                    FakeLiveKitEgressClient(
                        onStartRoomCompositeEgress = {
                            readyLatch.countDown()
                            releaseLatch.await(20, TimeUnit.SECONDS)
                        },
                    )
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes(egressClient = fakeClient) }
                }
                val creator = createTestMember("stream-f1-starting-race@example.org")
                val roomId = createTestRoom(creator, "F1-Starting-Sitzung")
                val destId = createTestDestination("F1-Starting-Ziel", creator)

                val failures = Collections.synchronizedList(mutableListOf<Throwable>())
                val startThread =
                    Thread {
                        try {
                            runBlocking {
                                client.post(
                                    "/test/start-stream?roomId=$roomId&destinationIds=$destId&layout=GRID&latencyMode=STANDARD",
                                ) { header("X-Member-Id", creator.toString()) }
                            }
                        } catch (t: Throwable) {
                            failures += t
                        }
                    }
                startThread.start()
                check(readyLatch.await(20, TimeUnit.SECONDS)) { "startStream's own LiveKit call never reached its hook" }

                val streamId =
                    transaction {
                        ConferenceStreamTable
                            .selectAll()
                            .where { ConferenceStreamTable.roomId eq roomId }
                            .single()[ConferenceStreamTable.id]
                    }
                transaction {
                    ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamId }.single()
                }[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.STARTING

                // stopStream races the still-in-flight startStream call -- its own Tx1 sees STARTING
                // with NO egress id recorded yet, so it cannot wait on anything and finalizes ENDED
                // immediately, unaware the LiveKit call is about to succeed.
                val stopResponse = client.post("/test/stop-stream?streamId=$streamId") { header("X-Member-Id", creator.toString()) }
                stopResponse.status shouldBe HttpStatusCode.OK
                stopResponse.bodyAsText().toStreamDto().status shouldBe ConferenceStreamStatus.ENDED

                // Release the blocked LiveKit call -- it succeeds and returns a REAL egress id.
                releaseLatch.countDown()
                startThread.join(20_000)
                if (failures.isNotEmpty()) throw failures.first()

                // The F1 fix: startStream's own Tx2 "abandoned" branch discovers the row is now ENDED,
                // records the freshly-minted egress id, and resurrects the row to STOPPING (clearing
                // endedAt) instead of silently leaking a publishing egress behind a terminal row.
                val resurrectedRow =
                    transaction { ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamId }.single() }
                resurrectedRow[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.STOPPING
                resurrectedRow[ConferenceStreamTable.endedAt] shouldBe null
                val freshEgressId = resurrectedRow[ConferenceStreamTable.livekitEgressId]
                freshEgressId shouldNotBe null
                fakeClient.startCalls.size shouldBe 1

                // StreamPoller.handleStopping picks the resurrected row up on its very next tick, calls
                // StopEgress for the FRESH id, and only THEN finalizes to ENDED for real -- proving the
                // egress is never left publishing behind a terminal row.
                StreamPoller(fakeClient, ENABLED_STREAMING_CONFIG).tick()
                (fakeClient.stopCalls.any { it.second == freshEgressId }) shouldBe true
                val finalRow = transaction { ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamId }.single() }
                finalRow[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
            }
        }

        // ── stopStream -- security-audit round-3 NEU-1: a resumeStream resurrection landing between
        // stopStream's OWN StopEgress request and its confirmation must not be silently overwritten ──

        test(
            "stopStream: security-audit round-3 NEU-1 -- a resumeStream/restartEgressForStream " +
                "resurrection that attaches a FRESH egress id to the row WHILE stopStream is still " +
                "confirming the OLD egress id's stop must not be finalized to ENDED, and StreamPoller's " +
                "next tick actually stops the fresh egress instead of leaking it behind a terminal row",
        ) {
            testApplication {
                val readyLatch = CountDownLatch(1)
                val releaseLatch = CountDownLatch(1)
                val resurrectionDone = CountDownLatch(1)
                // FakeLiveKitEgressClient mints "EG_fake_<n>" ids from a simple incrementing counter --
                // this test's own initial start-stream call below is the first ever egress start, so
                // its id is deterministically "EG_fake_1".
                val oldEgressId = "EG_fake_1"
                // Test-infrastructure discipline established by the F1 regression test above (R3's own
                // comment there explains it in full): the block/release hook must NOT fire on the very
                // first startRoomCompositeEgress call -- that is this test's own initial start-stream
                // setup call, needed just to get the stream LIVE before the actual race begins. Only
                // the SECOND call (resumeStream's own restart, below) is the one this test controls.
                val startRoomCompositeEgressCallCount = AtomicInteger(0)
                // pauseStream's OWN best-effort StopEgress (below) also targets oldEgressId -- the
                // synchronization must only engage on the SECOND such call (stopStream's own
                // confirmation-triggering one), not pauseStream's first, unrelated one.
                val stopOldEgressCallCount = AtomicInteger(0)
                val fakeClient =
                    FakeLiveKitEgressClient(
                        onStartRoomCompositeEgress = {
                            if (startRoomCompositeEgressCallCount.incrementAndGet() > 1) {
                                readyLatch.countDown()
                                releaseLatch.await(20, TimeUnit.SECONDS)
                            }
                        },
                        // Fires when stopStream requests the stop of the OLD (stale) egress id -- this
                        // is the exact "confirmation in progress" window the security-audit round-3
                        // finding exploits. Releases the blocked resumeStream call (letting its OWN
                        // LiveKit call return and its Tx2 resurrect the row with a FRESH egress id) and
                        // waits for that resurrection to actually land in the DB before letting
                        // stopStream's own confirmation loop proceed.
                        onStopEgress = { id ->
                            if (id == oldEgressId && stopOldEgressCallCount.incrementAndGet() == 2) {
                                releaseLatch.countDown()
                                check(resurrectionDone.await(20, TimeUnit.SECONDS)) { "resumeStream's resurrection never landed" }
                            }
                        },
                    )
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes(egressClient = fakeClient) }
                }
                val creator = createTestMember("stream-neu1-race@example.org")
                val roomId = createTestRoom(creator, "NEU-1-Sitzung")
                val destId = createTestDestination("NEU-1-Ziel", creator)

                val startDto =
                    client
                        .post("/test/start-stream?roomId=$roomId&destinationIds=$destId&layout=GRID&latencyMode=STANDARD") {
                            header("X-Member-Id", creator.toString())
                        }.bodyAsText()
                        .toStreamDto()
                val streamId = startDto.id

                // pauseStream's own best-effort StopEgress does NOT clear livekit_egress_id, so
                // oldEgressId stays on the row straight through PAUSED and into the STARTING claim
                // resumeStream makes below -- the pre-existing "stale id survives the claim" property
                // restartEgressForStream's own KDoc documents.
                client.post("/test/pause-stream?streamId=$streamId") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK
                transaction {
                    ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq Uuid.parse(streamId) }.single()
                }[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED

                val failures = Collections.synchronizedList(mutableListOf<Throwable>())
                val resumeThread =
                    Thread {
                        try {
                            runBlocking {
                                client.post("/test/resume-stream?streamId=$streamId") { header("X-Member-Id", creator.toString()) }
                            }
                        } catch (t: Throwable) {
                            failures += t
                        } finally {
                            // Signals the onStopEgress hook above that resumeStream's Tx2 has
                            // committed -- the resurrection this test is racing against has now
                            // genuinely landed in the DB.
                            resurrectionDone.countDown()
                        }
                    }
                resumeThread.start()
                check(readyLatch.await(20, TimeUnit.SECONDS)) { "resumeStream's own LiveKit call never reached its hook" }
                transaction {
                    ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq Uuid.parse(streamId) }.single()
                }[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.STARTING

                // stopStream races the still-in-flight resumeStream call -- its Tx1 sees STARTING with
                // the STALE oldEgressId still on the row (never cleared by the PAUSED->STARTING claim),
                // requests its stop, and -- via the onStopEgress hook above -- waits for the
                // resurrection to land BEFORE its own confirmation loop even starts polling.
                val stopResponse = client.post("/test/stop-stream?streamId=$streamId") { header("X-Member-Id", creator.toString()) }
                stopResponse.status shouldBe HttpStatusCode.OK
                resumeThread.join(20_000)
                if (failures.isNotEmpty()) throw failures.first()

                // The NEU-1 fix: stopStream's finalizing write is guarded on the egress id it actually
                // confirmed (oldEgressId) still matching the row's CURRENT livekit_egress_id -- since
                // the row now carries the FRESH id resumeStream's Tx2 just attached, the write is
                // skipped entirely. Without the fix, this assertion fails: the row would read ENDED,
                // and the fresh, still-running egress would be permanently unreachable.
                stopResponse.bodyAsText().toStreamDto().status shouldBe ConferenceStreamStatus.STOPPING
                val resurrectedRow =
                    transaction { ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq Uuid.parse(streamId) }.single() }
                resurrectedRow[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.STOPPING
                resurrectedRow[ConferenceStreamTable.endedAt] shouldBe null
                val freshEgressId = resurrectedRow[ConferenceStreamTable.livekitEgressId]
                freshEgressId shouldNotBe null
                freshEgressId shouldNotBe oldEgressId
                fakeClient.startCalls.size shouldBe 2

                // StreamPoller.handleStopping picks the row up on its very next tick, calls StopEgress
                // for the FRESH id, and only THEN finalizes to ENDED for real -- proving the fresh
                // egress resumeStream started is never left running behind a terminal row.
                StreamPoller(fakeClient, ENABLED_STREAMING_CONFIG).tick()
                (fakeClient.stopCalls.any { it.second == freshEgressId }) shouldBe true
                val finalRow =
                    transaction { ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq Uuid.parse(streamId) }.single() }
                finalRow[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
            }
        }

        // ── restartEgressForStream -- security-audit round-4 R4-2: the abandoned branch used to drop
        // the freshly-started egress id entirely for PAUSING/FAILED, unlike every other statusAtAbandon
        // case ───────────────────────────────────────────────────────────

        test(
            "restartEgressForStream: security-audit round-4 R4-2 -- a row concurrently flipped to PAUSING " +
                "WHILE this restart's own LiveKit call is still in flight still gets the freshly-started " +
                "egress id recorded (not silently dropped), so SecretBallotStreamGuard/StreamPoller's own " +
                "PAUSING handling can find and stop it",
        ) {
            testApplication {
                lateinit var streamUuid: Uuid
                // Test-infrastructure discipline established by the NEU-1 regression test above (see
                // that test's own comment) -- the hook must NOT fire on the very first
                // startRoomCompositeEgress call, since that is this test's own initial start-stream
                // setup call, needed just to get the stream LIVE before the actual race begins. Only
                // the SECOND call (resumeStream's own restart, below) is the one this test controls --
                // by then, streamUuid is already assigned.
                val startRoomCompositeEgressCallCount = AtomicInteger(0)
                val fakeClient =
                    FakeLiveKitEgressClient(
                        // Fires synchronously inside startRoomCompositeEgress, BEFORE it returns --
                        // simulates a concurrent ConferenceStreamPauseCoordinator.markPausingForSecretBallot
                        // (or reconcileMissedSecretBallotPause) landing WHILE restartEgressForStream's own
                        // Tx1-committed STARTING row is waiting on this very LiveKit call, mirroring
                        // SecretBallotStreamPauseTest's own scenario-31 hook shape (that one targets
                        // startStream's Tx2, this one targets restartEgressForStream's Tx2 -- the abandoned
                        // branch this round's finding is actually about).
                        onStartRoomCompositeEgress = {
                            if (startRoomCompositeEgressCallCount.incrementAndGet() == 2) {
                                transaction {
                                    ConferenceStreamTable.update({
                                        (ConferenceStreamTable.id eq streamUuid) and
                                            (ConferenceStreamTable.status eq ConferenceStreamStatus.STARTING)
                                    }) {
                                        it[status] = ConferenceStreamStatus.PAUSING
                                    }
                                }
                            }
                        },
                    )
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes(egressClient = fakeClient) }
                }
                val creator = createTestMember("stream-r4-2a-pausing-abandon@example.org")
                val roomId = createTestRoom(creator, "R4-2a-Sitzung")
                val destId = createTestDestination("R4-2a-Ziel", creator)

                val startDto =
                    client
                        .post("/test/start-stream?roomId=$roomId&destinationIds=$destId&layout=GRID&latencyMode=STANDARD") {
                            header("X-Member-Id", creator.toString())
                        }.bodyAsText()
                        .toStreamDto()
                streamUuid = Uuid.parse(startDto.id)
                val firstEgressId =
                    transaction { ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single() }[
                        ConferenceStreamTable.livekitEgressId,
                    ]

                client.post("/test/pause-stream?streamId=${startDto.id}") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK
                transaction {
                    ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single()
                }[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED

                // restartEgressForStream's Tx1 atomically claims PAUSED -> STARTING, then calls LiveKit
                // (the hook above fires here, flipping the row to PAUSING mid-flight) -- Tx2's own
                // re-read finds the row no longer STARTING and takes the "abandoned" branch this fix
                // targets.
                client.post("/test/resume-stream?streamId=${startDto.id}") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK

                // The R4-2 fix: status is left exactly as the concurrent actor wrote it (PAUSING), but
                // the freshly-started egress id is now recorded on the row -- before the fix, this
                // branch fell through to a bare `return@transaction statusAtAbandon` with NO write at
                // all, leaving the row PAUSING with a STALE (or no) egress id while a real, running
                // egress from THIS call sat completely untracked.
                val row = transaction { ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single() }
                row[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSING
                val freshEgressId = row[ConferenceStreamTable.livekitEgressId]
                freshEgressId shouldNotBe null
                freshEgressId shouldNotBe firstEgressId
                fakeClient.startCalls.size shouldBe 2

                // StreamPoller's own PAUSING handling picks the fresh id up on its very next tick and
                // stops it for real -- proving it was never actually leaked.
                StreamPoller(fakeClient, ENABLED_STREAMING_CONFIG).tick()
                (fakeClient.stopCalls.any { it.second == freshEgressId }) shouldBe true
            }
        }

        test(
            "restartEgressForStream: security-audit round-5 -- a row concurrently marked FAILED WHILE " +
                "this restart's own LiveKit call is still in flight is now RESURRECTED to STOPPING (not " +
                "left FAILED) with the freshly-started egress id, so StreamPoller.handleStopping picks it " +
                "up and actually confirms/finalizes it -- superseding round-4 R4-2's weaker " +
                "id-recorded-but-left-FAILED behaviour, which stranded the fresh egress outside both " +
                "NON_TERMINAL_STREAM_STATUSES and the ballot-gate blocklist",
        ) {
            testApplication {
                lateinit var streamUuid: Uuid
                // See the R4-2a test above -- the hook must not fire on the initial start-stream setup
                // call, only on the SECOND startRoomCompositeEgress call (resumeStream's own restart).
                val startRoomCompositeEgressCallCount = AtomicInteger(0)
                val fakeClient =
                    FakeLiveKitEgressClient(
                        onStartRoomCompositeEgress = {
                            if (startRoomCompositeEgressCallCount.incrementAndGet() == 2) {
                                transaction {
                                    ConferenceStreamTable.update({
                                        (ConferenceStreamTable.id eq streamUuid) and
                                            (ConferenceStreamTable.status eq ConferenceStreamStatus.STARTING)
                                    }) {
                                        it[status] = ConferenceStreamStatus.FAILED
                                        it[failureReason] = "simulated concurrent failure, unrelated to this call"
                                    }
                                }
                            }
                        },
                    )
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes(egressClient = fakeClient) }
                }
                val creator = createTestMember("stream-r5-failed-abandon@example.org")
                val roomId = createTestRoom(creator, "R5-FAILED-Sitzung")
                val destId = createTestDestination("R5-FAILED-Ziel", creator)

                val startDto =
                    client
                        .post("/test/start-stream?roomId=$roomId&destinationIds=$destId&layout=GRID&latencyMode=STANDARD") {
                            header("X-Member-Id", creator.toString())
                        }.bodyAsText()
                        .toStreamDto()
                streamUuid = Uuid.parse(startDto.id)
                val firstEgressId =
                    transaction { ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single() }[
                        ConferenceStreamTable.livekitEgressId,
                    ]

                client.post("/test/pause-stream?streamId=${startDto.id}") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK

                client.post("/test/resume-stream?streamId=${startDto.id}") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK

                // The round-5 fix: FAILED is no longer left standing (round-4 R4-2's behaviour) -- the
                // row is resurrected to STOPPING (mirroring the STOPPING/ENDED branch), `endedAt` stays
                // null, and the freshly-started egress id is recorded. Without the fix, this assertion
                // fails: the row would read FAILED, outside StreamPoller's own
                // NON_TERMINAL_STREAM_STATUSES sweep and SecretBallotStreamLock's ballot-gate blocklist
                // -- a real, running egress with no path back to being observed or stopped.
                val row = transaction { ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single() }
                row[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.STOPPING
                row[ConferenceStreamTable.endedAt] shouldBe null
                val freshEgressId = row[ConferenceStreamTable.livekitEgressId]
                freshEgressId shouldNotBe null
                freshEgressId shouldNotBe firstEgressId
                fakeClient.startCalls.size shouldBe 2

                // StreamPoller.handleStopping picks the row up on its very next tick, calls StopEgress
                // for the FRESH id, and finalizes ENDED for real -- proving the fresh egress
                // restartEgressForStream started is never left running behind an un-revisited FAILED row.
                StreamPoller(fakeClient, ENABLED_STREAMING_CONFIG).tick()
                (fakeClient.stopCalls.any { it.second == freshEgressId }) shouldBe true
                val finalRow =
                    transaction { ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single() }
                finalRow[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.ENDED
            }
        }

        test(
            "restartEgressForStream: security-audit round-5 -- a row concurrently flipped (back) to LIVE " +
                "WHILE this restart's own LiveKit call is still in flight keeps status LIVE (still " +
                "correctly blocks a secret ballot) but the freshly-started egress id is STILL recorded, " +
                "never silently dropped -- before this fix, LIVE fell through the abandoned branch with " +
                "NO write at all, unlike every other statusAtAbandon case",
        ) {
            testApplication {
                lateinit var streamUuid: Uuid
                var concurrentLiveEgressId: String? = null
                // See the R4-2a test above -- the hook must not fire on the initial start-stream setup
                // call, only on the SECOND startRoomCompositeEgress call (resumeStream's own restart).
                val startRoomCompositeEgressCallCount = AtomicInteger(0)
                val fakeClient =
                    FakeLiveKitEgressClient(
                        onStartRoomCompositeEgress = {
                            if (startRoomCompositeEgressCallCount.incrementAndGet() == 2) {
                                // Simulates a second, independent egress attempt for the SAME stream
                                // committing LIVE with its OWN egress id WHILE this restart's own
                                // LiveKit call is still outstanding -- an exceptional double-egress
                                // window, not a normal single-writer path, but exactly the state
                                // restartEgressForStream's Tx2 must not silently mishandle.
                                concurrentLiveEgressId = "EG_fake_concurrent_live"
                                transaction {
                                    ConferenceStreamTable.update({
                                        (ConferenceStreamTable.id eq streamUuid) and
                                            (ConferenceStreamTable.status eq ConferenceStreamStatus.STARTING)
                                    }) {
                                        it[status] = ConferenceStreamStatus.LIVE
                                        it[livekitEgressId] = concurrentLiveEgressId
                                    }
                                }
                            }
                        },
                    )
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes(egressClient = fakeClient) }
                }
                val creator = createTestMember("stream-r5-live-abandon@example.org")
                val roomId = createTestRoom(creator, "R5-LIVE-Sitzung")
                val destId = createTestDestination("R5-LIVE-Ziel", creator)

                val startDto =
                    client
                        .post("/test/start-stream?roomId=$roomId&destinationIds=$destId&layout=GRID&latencyMode=STANDARD") {
                            header("X-Member-Id", creator.toString())
                        }.bodyAsText()
                        .toStreamDto()
                streamUuid = Uuid.parse(startDto.id)

                client.post("/test/pause-stream?streamId=${startDto.id}") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK
                transaction {
                    ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single()
                }[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.PAUSED

                // restartEgressForStream's Tx1 atomically claims PAUSED -> STARTING, then calls LiveKit
                // (the hook above fires here, flipping the row to LIVE with a DIFFERENT egress id
                // mid-flight) -- Tx2's own re-read finds the row no longer STARTING and takes the
                // "abandoned" branch's LIVE case this round-5 fix adds.
                client.post("/test/resume-stream?streamId=${startDto.id}") { header("X-Member-Id", creator.toString()) }.status shouldBe
                    HttpStatusCode.OK

                // The round-5 fix: status is left exactly as the concurrent actor wrote it (LIVE, still
                // correctly blocking a secret ballot -- SecretBallotStreamLock.requireStreamQuiescedForBallot
                // treats LIVE as "publishing"), but the id THIS call's own successful LiveKit call
                // returned is recorded -- before the fix, this branch fell through to a bare
                // `return@transaction statusAtAbandon` with NO write at all, silently discarding the
                // fresh, genuinely-running egress this call started.
                val row = transaction { ConferenceStreamTable.selectAll().where { ConferenceStreamTable.id eq streamUuid }.single() }
                row[ConferenceStreamTable.status] shouldBe ConferenceStreamStatus.LIVE
                val recordedEgressId = row[ConferenceStreamTable.livekitEgressId]
                recordedEgressId shouldNotBe null
                recordedEgressId shouldNotBe concurrentLiveEgressId
                fakeClient.startCalls.size shouldBe 2
            }
        }

        // ── getActiveStream -- never privilege-gated ─────────────────────

        test("getActiveStream: visible to ANY AKTIV member, not just creator/privileged; labels/platform only") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val creator = createTestMember("stream-active-creator@example.org")
                val bystander = createTestMember("stream-active-bystander@example.org")
                val roomId = createTestRoom(creator, "Sitzung")
                val destId = createTestDestination("Active-Ziel", creator)
                client.post("/test/start-stream?roomId=$roomId&destinationIds=$destId&layout=GRID&latencyMode=STANDARD") {
                    header("X-Member-Id", creator.toString())
                }

                val response = client.get("/test/active-stream?roomId=$roomId") { header("X-Member-Id", bystander.toString()) }
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body.isNotBlank() shouldBe true
                // Never url, never key -- see getActiveStream KDoc.
                body shouldNotContainPlaintext "rtmp://"
            }
        }

        test("getActiveStream: empty (not an error) when no stream is active") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val member = createTestMember("stream-active-empty@example.org")
                val roomId = createTestRoom(member, "Sitzung")

                val response = client.get("/test/active-stream?roomId=$roomId") { header("X-Member-Id", member.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe ""
            }
        }

        // ── getActiveStream -- Wave 5 "Föderations-Gastbeitritt", design review D13 ──────────

        test(
            "D13: a GAST who has joined an opted-in room sees the active stream -- 'everyone in the room has a legal right to know' applies to a guest too",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val creator = createTestMember("stream-d13-happy@example.org")
                val roomId = createTestRoom(creator, "D13-Sitzung")
                transaction { ConferenceRoomTable.update({ ConferenceRoomTable.id eq roomId }) { it[allowFederationGuests] = true } }
                val guest = createTestMember("stream-d13-guest@example.org", status = MemberStatus.GAST)
                transaction {
                    ConferenceParticipationTable.insert {
                        it[id] = Uuid.random()
                        it[ConferenceParticipationTable.roomId] = roomId
                        it[memberId] = guest
                        it[role] = ConferenceRole.PARTICIPANT
                        it[joinedAt] = DbClock.nowLocalDateTime()
                        it[leftAt] = null
                    }
                }
                val destId = createTestDestination("D13-Ziel", creator)
                client.post("/test/start-stream?roomId=$roomId&destinationIds=$destId&layout=GRID&latencyMode=STANDARD") {
                    header("X-Member-Id", creator.toString())
                }

                val response = client.get("/test/active-stream?roomId=$roomId") { header("X-Member-Id", guest.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().isNotBlank() shouldBe true
            }
        }

        test("D13: a GAST who never joined the room is rejected; a GAST in a non-opted-in room is rejected") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val creator = createTestMember("stream-d13-neg@example.org")
                val openRoomId = createTestRoom(creator, "D13-Offen")
                transaction { ConferenceRoomTable.update({ ConferenceRoomTable.id eq openRoomId }) { it[allowFederationGuests] = true } }
                val closedRoomId = createTestRoom(creator, "D13-Geschlossen")

                val neverJoinedGuest = createTestMember("stream-d13-never@example.org", status = MemberStatus.GAST)
                client.get("/test/active-stream?roomId=$openRoomId") { header("X-Member-Id", neverJoinedGuest.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden

                val closedRoomGuest = createTestMember("stream-d13-closed@example.org", status = MemberStatus.GAST)
                transaction {
                    ConferenceParticipationTable.insert {
                        it[id] = Uuid.random()
                        it[ConferenceParticipationTable.roomId] = closedRoomId
                        it[memberId] = closedRoomGuest
                        it[role] = ConferenceRole.PARTICIPANT
                        it[joinedAt] = DbClock.nowLocalDateTime()
                        it[leftAt] = null
                    }
                }
                client.get("/test/active-stream?roomId=$closedRoomId") { header("X-Member-Id", closedRoomGuest.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        // ── No RPC response ever carries the plaintext key (reflective-ish assertion) ────────

        test("no RPC response body anywhere in this file ever contains a plaintext stream key") {
            testApplication {
                application {
                    install(StatusPages) { installConferenceStreamingExceptionHandlers() }
                    routing { registerConferenceStreamingTestRoutes() }
                }
                val plaintextKey = "totally-secret-plaintext-key-999"
                val response =
                    client.post(
                        "/test/create-destination?label=Plaintext-Check&platform=GENERIC_RTMP&rtmpUrl=rtmp://f.example.org/live&streamKey=$plaintextKey",
                    ) {
                        header("X-Member-Id", ADMIN_ID)
                    }
                val dto = response.bodyAsText().toDestinationDto()
                createdDestinationIds += Uuid.parse(dto.id)

                val listResponse = client.get("/test/list-destinations") { header("X-Member-Id", ADMIN_ID) }
                listResponse.bodyAsText() shouldNotContainPlaintext plaintextKey

                val updateResponse =
                    client.post(
                        "/test/update-destination?destinationId=${dto.id}&label=Plaintext-Check&rtmpUrl=rtmp://f.example.org/live",
                    ) {
                        header("X-Member-Id", ADMIN_ID)
                    }
                updateResponse.bodyAsText() shouldNotContainPlaintext plaintextKey
            }
        }
    })

// ── Fakes ────────────────────────────────────────────────────────────────

/**
 * Records every call for assertions -- [startRoomCompositeEgress]/[startParticipantEgress] return a
 * fresh, incrementing fake `egress_id` each call (so `resumeStream`'s "mints a NEW egress id" test
 * has something real to assert against), or throw [LiveKitAdminException] when [failStart] is set.
 * [stopEgress] always records the call and returns a plausible `EGRESS_ENDING` response.
 */
private class FakeLiveKitEgressClient(
    private val failStart: Boolean = false,
    /**
     * Security-audit-round-2 F1 regression tests -- fires synchronously inside
     * [startRoomCompositeEgress], BEFORE it returns, mirroring
     * [SecretBallotStreamPauseTest]'s own `ControllableFakeLiveKitEgressClient.onStartRoomCompositeEgress`
     * hook (duplicated here rather than shared -- that class is `private` to its own test file). Lets a
     * test deterministically land a concurrent `stopStream` call in the window between
     * [ConferenceStreamingService.startStream]'s Tx1 (row is `STARTING`, no `egress_id` recorded yet)
     * and its Tx2 (LiveKit call returns, egress id gets recorded) without a real, timing-dependent race.
     */
    private val onStartRoomCompositeEgress: (() -> Unit)? = null,
    /**
     * Security-audit round-3 NEU-1 regression test -- fires synchronously inside [stopEgress], BEFORE
     * it returns, mirroring [onStartRoomCompositeEgress]'s own shape one-for-one. Lets a test
     * deterministically land a concurrent `resumeStream`/`restartEgressForStream` "abandoned" branch
     * resurrection (a FRESH egress id attached to the same row) in the window between
     * [ConferenceStreamingService.stopStream] requesting the stop of the OLD egress id and it actually
     * confirming/finalizing -- without a real, timing-dependent race.
     */
    private val onStopEgress: ((String) -> Unit)? = null,
) : LiveKitEgressClient {
    private val counter = AtomicInteger(0)
    val startCalls = Collections.synchronizedList(mutableListOf<Triple<String, ConferenceStreamLayout?, List<String>>>())
    val stopCalls = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    override suspend fun startTrackEgress(
        roomName: String,
        trackId: String,
        outputFilepathWithoutExtension: String,
    ): LiveKitEgressInfo = error("not used by ConferenceStreamingServiceTest")

    override suspend fun stopEgress(
        roomName: String,
        egressId: String,
    ): LiveKitEgressInfo {
        stopCalls += roomName to egressId
        onStopEgress?.invoke(egressId)
        return LiveKitEgressInfo(egressId = egressId, status = "EGRESS_ENDING")
    }

    override suspend fun listEgress(roomName: String): List<LiveKitEgressInfo> = emptyList()

    override suspend fun startRoomCompositeEgress(
        roomName: String,
        layout: ConferenceStreamLayout,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo {
        onStartRoomCompositeEgress?.invoke()
        startCalls += Triple(roomName, layout, rtmpUrls)
        if (failStart) throw LiveKitAdminException("simulated egress start failure")
        return LiveKitEgressInfo(egressId = "EG_fake_${counter.incrementAndGet()}", status = "EGRESS_STARTING")
    }

    override suspend fun startParticipantEgress(
        roomName: String,
        identity: String,
        latencyMode: ConferenceStreamLatencyMode,
        rtmpUrls: List<String>,
    ): LiveKitEgressInfo {
        startCalls += Triple(roomName, ConferenceStreamLayout.SINGLE_PARTICIPANT, rtmpUrls)
        if (failStart) throw LiveKitAdminException("simulated egress start failure")
        return LiveKitEgressInfo(egressId = "EG_fake_${counter.incrementAndGet()}", status = "EGRESS_STARTING")
    }

    override suspend fun updateStream(
        roomName: String,
        egressId: String,
        addUrls: List<String>,
        removeUrls: List<String>,
    ): LiveKitEgressInfo = error("not used by ConferenceStreamingServiceTest")
}

// ── Helpers ──────────────────────────────────────────────────────────────

private infix fun String.shouldNotContainPlaintext(plaintext: String) {
    (this.contains(plaintext)) shouldBe false
}

/**
 * Fires [ConferenceStreamingService.startStream] for the same [roomId] from two different callers
 * on two real JVM threads, released together via [startLatch] -- mirrors
 * [runConcurrentStartRecording]'s own shape for the identical race on the analogous invariant.
 */
private fun runConcurrentStartStream(
    client: HttpClient,
    roomId: String,
    destinationId: String,
    callerA: Uuid,
    callerB: Uuid,
    timeoutSeconds: Long = 20,
): List<HttpStatusCode> {
    val startLatch = CountDownLatch(2)
    val doneLatch = CountDownLatch(2)
    val results = Collections.synchronizedList(mutableListOf<HttpStatusCode>())
    val failures = mutableListOf<Throwable>()

    fun startThread(callerId: Uuid): Thread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                runBlocking {
                    val response =
                        client.post("/test/start-stream?roomId=$roomId&destinationIds=$destinationId&layout=GRID&latencyMode=STANDARD") {
                            header("X-Member-Id", callerId.toString())
                        }
                    results += response.status
                }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            } finally {
                doneLatch.countDown()
            }
        }

    val t1 = startThread(callerA)
    val t2 = startThread(callerB)
    t1.start()
    t2.start()
    val completed = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    check(completed) { "Concurrent startStream attempts did not complete within ${timeoutSeconds}s -- likely deadlock" }
    if (failures.isNotEmpty()) throw failures.first()
    return results.toList()
}

/**
 * Same shape as [runConcurrentStartStream], but for the cross-room destination-exclusivity race:
 * two DIFFERENT rooms ([roomIdA]/[roomIdB]), two different callers, the SAME [destinationId] --
 * exercises the `destinationRows.forUpdate()` lock in [ConferenceStreamingService.startStream]
 * rather than the room-row lock.
 */
private fun runConcurrentStartStreamAcrossRooms(
    client: HttpClient,
    roomIdA: String,
    roomIdB: String,
    destinationId: String,
    callerA: Uuid,
    callerB: Uuid,
    timeoutSeconds: Long = 20,
): List<HttpStatusCode> {
    val startLatch = CountDownLatch(2)
    val doneLatch = CountDownLatch(2)
    val results = Collections.synchronizedList(mutableListOf<HttpStatusCode>())
    val failures = mutableListOf<Throwable>()

    fun startThread(
        roomId: String,
        callerId: Uuid,
    ): Thread =
        Thread {
            try {
                startLatch.countDown()
                startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
                runBlocking {
                    val response =
                        client.post("/test/start-stream?roomId=$roomId&destinationIds=$destinationId&layout=GRID&latencyMode=STANDARD") {
                            header("X-Member-Id", callerId.toString())
                        }
                    results += response.status
                }
            } catch (t: Throwable) {
                synchronized(failures) { failures += t }
            } finally {
                doneLatch.countDown()
            }
        }

    val t1 = startThread(roomIdA, callerA)
    val t2 = startThread(roomIdB, callerB)
    t1.start()
    t2.start()
    val completed = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    check(completed) { "Concurrent cross-room startStream attempts did not complete within ${timeoutSeconds}s -- likely deadlock" }
    if (failures.isNotEmpty()) throw failures.first()
    return results.toList()
}

private fun cleanUpConferenceStreamingTestData(
    memberIds: List<Uuid>,
    roomIds: List<Uuid>,
    destinationIds: List<Uuid>,
) {
    transaction {
        if (memberIds.isNotEmpty()) {
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) {
                it[actorMemberId] = null
            }
        }
        val streamIds =
            ConferenceStreamTable
                .selectAll()
                .filter { row ->
                    row[ConferenceStreamTable.roomId] in roomIds || row[ConferenceStreamTable.startedByMemberId] in memberIds
                }.map { it[ConferenceStreamTable.id] }
        streamIds.forEach { streamId -> ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.streamId eq streamId } }
        streamIds.forEach { streamId -> ConferenceStreamTable.deleteWhere { ConferenceStreamTable.id eq streamId } }
        if (destinationIds.isNotEmpty()) {
            ConferenceStreamTargetTable.deleteWhere { ConferenceStreamTargetTable.destinationId inList destinationIds }
            ConferenceStreamDestinationTable.deleteWhere { ConferenceStreamDestinationTable.id inList destinationIds }
        }
        // Wave 5 "Föderations-Gastbeitritt" D13 tests insert conference_participation rows
        // directly (a GAST "joining" a room, no LiveKit involved) -- delete before the room/member
        // rows they FK-reference.
        if (roomIds.isNotEmpty() || memberIds.isNotEmpty()) {
            ConferenceParticipationTable.deleteWhere {
                (ConferenceParticipationTable.roomId inList roomIds) or (ConferenceParticipationTable.memberId inList memberIds)
            }
        }
        roomIds.forEach { roomId -> ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id eq roomId } }
        memberIds.forEach { memberId -> AccountTable.deleteWhere { AccountTable.memberId eq memberId } }
        memberIds.forEach { memberId -> MemberTable.deleteWhere { MemberTable.id eq memberId } }
    }
}

private fun StatusPagesConfig.installConferenceStreamingExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
    }
    exception<ForbiddenException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Forbidden)
    }
    exception<NotFoundException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.NotFound)
    }
    exception<ConflictException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Conflict)
    }
    exception<BadRequestException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.BadRequest)
    }
}

/**
 * Shared throwaway routes for [ConferenceStreamingService] using the ENABLED conference+streaming
 * config -- mirrors [ConferenceRecordingServiceTest]'s own `registerConferenceRecordingTestRoutes`
 * style. [egressClient] defaults to a fresh, always-succeeding [FakeLiveKitEgressClient].
 */
private fun Route.registerConferenceStreamingTestRoutes(
    egressClient: LiveKitEgressClient = FakeLiveKitEgressClient(),
    destinationLimiter: LoginRateLimiter = LoginRateLimiter(),
    startLimiter: LoginRateLimiter = LoginRateLimiter(),
    mutateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
    readLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 60, window = 1.minutes),
) {
    fun service(call: ApplicationCall) =
        ConferenceStreamingService(
            call,
            egressClient,
            ENABLED_CONFERENCE_CONFIG,
            ENABLED_STREAMING_CONFIG,
            destinationLimiter,
            startLimiter,
            mutateLimiter,
            readLimiter,
        )

    post("/test/create-destination") {
        val q = call.request.queryParameters
        val dto =
            service(call).createDestination(
                q["label"]!!,
                ConferenceStreamPlatform.valueOf(q["platform"]!!),
                q["rtmpUrl"]!!,
                q["streamKey"]!!,
            )
        call.respondText(dto.toPipeString())
    }
    post("/test/update-destination") {
        val q = call.request.queryParameters
        val dto = service(call).updateDestination(q["destinationId"]!!, q["label"]!!, q["rtmpUrl"]!!, q["newStreamKey"])
        call.respondText(dto.toPipeString())
    }
    post("/test/set-destination-enabled") {
        val q = call.request.queryParameters
        val dto = service(call).setDestinationEnabled(q["destinationId"]!!, q["enabled"]!!.toBoolean())
        call.respondText(dto.toPipeString())
    }
    post("/test/delete-destination") {
        val q = call.request.queryParameters
        val ok = service(call).deleteDestination(q["destinationId"]!!)
        call.respondText(ok.toString())
    }
    get("/test/list-destinations") {
        val dtos = service(call).listDestinations()
        call.respondText(dtos.joinToString(";") { it.toPipeString() })
    }
    get("/test/list-stream-targets") {
        val dtos = service(call).listStreamTargets()
        call.respondText(dtos.joinToString(";") { "${it.id}|${it.label}|${it.platform}" })
    }
    post("/test/start-stream") {
        val q = call.request.queryParameters
        val dto =
            service(call).startStream(
                q["roomId"]!!,
                q["destinationIds"]!!.split(","),
                ConferenceStreamLayout.valueOf(q["layout"]!!),
                ConferenceStreamLatencyMode.valueOf(q["latencyMode"]!!),
                q["participantIdentity"],
            )
        call.respondText(dto.toPipeString())
    }
    post("/test/pause-stream") {
        val q = call.request.queryParameters
        val dto = service(call).pauseStream(q["streamId"]!!)
        call.respondText(dto.toPipeString())
    }
    post("/test/resume-stream") {
        val q = call.request.queryParameters
        val dto = service(call).resumeStream(q["streamId"]!!)
        call.respondText(dto.toPipeString())
    }
    post("/test/stop-stream") {
        val q = call.request.queryParameters
        val dto = service(call).stopStream(q["streamId"]!!)
        call.respondText(dto.toPipeString())
    }
    get("/test/active-stream") {
        val q = call.request.queryParameters
        val dtos = service(call).getActiveStream(q["roomId"]!!)
        call.respondText(dtos.joinToString(";") { it.toPipeString() })
    }
    get("/test/list-streams") {
        val q = call.request.queryParameters
        val dtos = service(call).listStreams(q["roomId"])
        call.respondText(dtos.joinToString(";") { it.toPipeString() })
    }
}

/** id|label|platform|rtmpUrl|streamKeyMask|createdByDisplayName|enabled */
private fun ConferenceStreamDestinationDto.toPipeString(): String =
    "$id|$label|$platform|$rtmpUrl|$streamKeyMask|$createdByDisplayName|$enabled"

private fun String.toDestinationDto(): ConferenceStreamDestinationDto {
    val parts = split("|")
    return ConferenceStreamDestinationDto(
        id = parts[0],
        label = parts[1],
        platform = ConferenceStreamPlatform.valueOf(parts[2]),
        rtmpUrl = parts[3],
        streamKeyMask = parts[4],
        streamKeySetAt = DbClock.nowLocalDateTime(),
        createdByDisplayName = parts[5],
        enabled = parts[6].toBoolean(),
    )
}

/** id|roomId|status|layout|startedByMemberId|restartCount|failureReason */
private fun ConferenceStreamDto.toPipeString(): String = "$id|$roomId|$status|$layout|$startedByMemberId|$restartCount|$failureReason"

private fun String.toStreamDto(): ConferenceStreamDto {
    val parts = split("|")
    return ConferenceStreamDto(
        id = parts[0],
        roomId = parts[1],
        roomTitle = "",
        status = ConferenceStreamStatus.valueOf(parts[2]),
        layout = ConferenceStreamLayout.valueOf(parts[3]),
        latencyMode = ConferenceStreamLatencyMode.STANDARD,
        startedByMemberId = parts[4],
        startedByDisplayName = "",
        startedAt = DbClock.nowLocalDateTime(),
        pausedAt = null,
        endedAt = null,
        restartCount = parts[5].toInt(),
        targets = emptyList(),
        failureReason = parts[6].takeIf { it != "null" },
        pauseReason = null,
    )
}

/** enabled|encryptionConfigured|maxDestinations|configuredDestinationCount */
private fun network.lapis.cloud.shared.domain.ConferenceStreamAvailabilityDto.toPipeString(): String =
    "$enabled|$encryptionConfigured|$maxDestinations|$configuredDestinationCount"

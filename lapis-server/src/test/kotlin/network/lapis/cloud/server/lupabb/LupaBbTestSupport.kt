package network.lapis.cloud.server.lupabb

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.LupaBbSyncConfigTable
import network.lapis.cloud.server.db.generated.LupaBbSyncRunTable
import network.lapis.cloud.server.db.generated.LupaBbSyncSuppressionTable
import network.lapis.cloud.server.db.generated.MemberLupaBbProfileTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.LupaBbSyncService
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** A fixed key for the tests: the same box seals the credentials and keys the suppression list. Test value only. */
internal val TEST_BOX = SecretBox(ByteArray(SecretBox.KEY_SIZE_BYTES) { (it + 1).toByte() })

internal const val TEST_EMAIL_PREFIX = "lupabbtest-"

/** Harness of the V1.9.226 DB tests: own rows only, removed again by [cleanUp]. */
internal class LupaBbFixtures(
    val chapterEnforced: Boolean = false,
) {
    val memberIds = mutableListOf<Uuid>()
    val chapterIds = mutableListOf<Uuid>()
    val credentialLimiter = FederationInboxRateLimiter(maxRequests = 100_000, window = 60.minutes)

    fun runtime(
        server: FakeLupaBbServer? = null,
        box: SecretBox? = TEST_BOX,
        limits: LupaBbLimits = LupaBbLimits.DEFAULT,
        store: LupaBbPreviewStore = LupaBbPreviewStore(),
        limiter: FederationInboxRateLimiter = credentialLimiter,
    ) = LupaBbSyncRuntime(
        secretBox = box,
        endpoint = server?.endpoint ?: LupaBbEndpoint("http://127.0.0.1:1/api/"),
        limits = limits,
        previewStore = store,
        credentialRateLimiter = limiter,
        chapterEnforced = { chapterEnforced },
    )

    fun service(
        call: ApplicationCall,
        runtime: LupaBbSyncRuntime,
    ) = LupaBbSyncService(call = call, runtime = runtime)

    @Suppress("LongParameterList")
    fun member(
        label: String = "m",
        role: AccountRole? = null,
        status: MemberStatus = MemberStatus.ACTIVE,
        displayName: String = "Erika Muster",
        email: String = "$TEST_EMAIL_PREFIX$label-${Uuid.random()}@example.org",
        personNumber: Long? = null,
        anonymized: Boolean = false,
        chapterId: Uuid? = null,
        street: String? = null,
        externalReference: String? = null,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[MemberTable.displayName] = displayName
                it[MemberTable.email] = email
                it[MemberTable.status] = status
                it[joinedAt] = LocalDate(2026, 1, 1)
                it[membershipTierId] = null
                it[lupabbPersonNumber] = personNumber
                it[regionalChapterId] = chapterId
                it[MemberTable.street] = street
                it[MemberTable.externalReference] = externalReference
                if (anonymized) it[anonymizedAt] = ServerClock.now()
            }
            if (role != null) {
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            MemberStatusHistoryTable.insert {
                it[memberId] = id
                it[effectiveFrom] = ServerClock.now()
                it[MemberStatusHistoryTable.status] = status.name
                it[sourceKind] = "SEED"
                it[recordedAt] = ServerClock.now()
            }
        }
        memberIds += id
        return id
    }

    fun admin(label: String = "admin") = member(label = label, role = AccountRole.ADMIN, displayName = "Admin $label")

    fun chapter(name: String): Uuid {
        val id = Uuid.random()
        transaction {
            RegionalChapterTable.insert {
                it[RegionalChapterTable.id] = id
                it[RegionalChapterTable.name] = name
                it[nameKey] = name.lowercase() + Uuid.random().toString().take(8)
                it[createdAt] = ServerClock.now()
            }
        }
        chapterIds += id
        return id
    }

    fun current(
        id: Uuid,
        role: AccountRole = AccountRole.ADMIN,
    ) = CurrentMember(memberId = id, role = role, status = MemberStatus.ACTIVE)

    /** Seals credentials for [server] directly (no probe), as a finished "Prüfen und speichern" would. */
    fun storeCredentials(
        server: FakeLupaBbServer,
        actor: Uuid,
        transport: ApiKeyTransport = ApiKeyTransport.HEADER,
        box: SecretBox = TEST_BOX,
    ) {
        transaction {
            LupaBbCredentialStore.save(
                box = box,
                credentials = LupaBbCredentials(username = server.username, password = server.password, apiKey = server.apiKey),
                transport = transport,
                actor = actor,
                now = ServerClock.now(),
            )
        }
    }

    /** Lets the next fetch through (the 60 second pause between two fetches is a rule of the service, not of the test). */
    fun allowNextFetch() {
        transaction { LupaBbSyncConfigTable.update({ LupaBbSyncConfigTable.id eq 1 }) { it[lastFetchStartedAt] = null } }
    }

    fun memberRow(id: Uuid) = transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single() }

    fun byPersonNumber(number: Long) =
        transaction { MemberTable.selectAll().where { MemberTable.lupabbPersonNumber eq number }.singleOrNull() }

    fun testMemberIds(): List<Uuid> =
        transaction {
            val byEmail = MemberTable.selectAll().where { MemberTable.email like "$TEST_EMAIL_PREFIX%" }.map { it[MemberTable.id] }
            val byNumber =
                MemberTable
                    .selectAll()
                    .where { MemberTable.lupabbPersonNumber inList (9_000_000_000L..9_000_000_100L).toList() }
                    .map { it[MemberTable.id] }
            (memberIds + byEmail + byNumber).distinct()
        }

    fun cleanUp() {
        val ids = testMemberIds()
        transaction {
            LupaBbSyncRunTable.deleteWhere { LupaBbSyncRunTable.startedBy inList ids }
            LupaBbSyncConfigTable.deleteWhere { LupaBbSyncConfigTable.id eq 1 }
            LupaBbSyncSuppressionTable.deleteWhere { LupaBbSyncSuppressionTable.personNumberHmac like "%" }
            MemberLupaBbProfileTable.deleteWhere { MemberLupaBbProfileTable.memberId inList ids }
            MemberStatusHistoryTable.deleteWhere { MemberStatusHistoryTable.memberId inList ids }
            SessionTable.deleteWhere { SessionTable.memberId inList ids }
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList ids }) { it[actorMemberId] = null }
            MemberTable.update({ MemberTable.id inList ids }) { it[regionalChapterId] = null }
            RegionalChapterTable.deleteWhere { RegionalChapterTable.id inList chapterIds }
            AccountTable.deleteWhere { AccountTable.memberId inList ids }
            MemberTable.deleteWhere { MemberTable.id inList ids }
        }
        memberIds.clear()
        chapterIds.clear()
        LupaBbSuppression.configure(null)
    }
}

/** One generic throw-away route runs a lambda against the call of its request (`X-Member-Id` resolves the caller). */
internal class LupaBbApp(
    private val client: HttpClient,
    private val ops: ConcurrentHashMap<String, suspend (ApplicationCall) -> Any?>,
    private val results: ConcurrentHashMap<String, Result<Any?>>,
    val fixtures: LupaBbFixtures,
) {
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> attempt(
        member: Uuid,
        op: suspend (ApplicationCall) -> T,
    ): Result<T> {
        val id = Uuid.random().toString()
        ops[id] = op
        client.post("/lupabb-op/$id") { header("X-Member-Id", member.toString()) }
        ops.remove(id)
        return results.remove(id)!! as Result<T>
    }

    suspend fun <T> call(
        member: Uuid,
        op: suspend (ApplicationCall) -> T,
    ): T = attempt(member = member, op = op).getOrThrow()
}

internal fun lupaBbTestApplication(
    fixtures: LupaBbFixtures,
    block: suspend LupaBbApp.() -> Unit,
) {
    val ops = ConcurrentHashMap<String, suspend (ApplicationCall) -> Any?>()
    val results = ConcurrentHashMap<String, Result<Any?>>()
    testApplication {
        application {
            routing {
                post("/lupabb-op/{id}") {
                    val id = call.parameters["id"]!!
                    val op = ops.getValue(id)
                    results[id] =
                        try {
                            Result.success(op(call))
                        } catch (e: Throwable) {
                            Result.failure(e)
                        }
                    call.respondText("done")
                }
            }
        }
        LupaBbApp(client = client, ops = ops, results = results, fixtures = fixtures).block()
    }
}

internal val TEST_LOOKUPS =
    LupaBbLookups(
        statuses =
            mapOf(
                1L to "Mitglied",
                2L to "Neumitglied",
                3L to "Förderer",
                4L to "Spender",
                5L to "verstorben",
                6L to "Widerrufen",
                7L to "Ausgeschlossen",
                8L to "Gekündigt",
                9L to "Nur Zahler",
                10L to "Storniert",
                11L to "Ablehnung",
                12L to "in Aufnahme",
            ),
        countries = mapOf(1L to "Deutschland", 2L to "Atlantis"),
        regionalAssociations = mapOf(5L to "Testland-Nord", 6L to "Atlantis-Verband"),
    )

/** A wire record with sensible defaults; a unique family name keeps the name matching of one test away from the members of another. */
@Suppress("LongParameterList")
internal fun wireMember(
    personNumber: Long,
    statusId: Long = 1,
    given: String? = "Erika",
    family: String? = "Muster",
    email: String? = "$TEST_EMAIL_PREFIX$personNumber@example.org",
    street: String? = "Musterweg",
    houseNumber: String? = "1",
    countryId: Long? = 1,
    associationId: Long? = null,
    birthDate: String? = null,
    company: String? = null,
    personType: String? = "nat",
) = WireMember(
    personNumber = personNumber,
    statusId = statusId,
    company = company,
    title = null,
    givenName = given,
    familyName = family,
    nameAffix = null,
    personType = personType,
    birthDate = birthDate,
    street = street,
    houseNumber = houseNumber,
    poBox = null,
    city = "Braunschweig",
    postalCode = "38100",
    addressSupplement = null,
    countryId = countryId,
    email = email,
    phone = null,
    mobile = null,
    regionalAssociationId = associationId,
    joinedAt = null,
)

internal fun datasetOf(vararg members: WireMember): LupaBbDataset = LupaBbFetcher.assemble(wire = members.toList(), lookups = TEST_LOOKUPS)

internal fun LupaBbPlan.row(personNumber: Long): PlanRow = rows.single { it.personNumber == personNumber }

internal fun planOf(
    dataset: LupaBbDataset,
    actor: Uuid,
    enforced: Boolean = false,
    box: SecretBox? = TEST_BOX,
): LupaBbPlan =
    LupaBbPlanner.plan(dataset = dataset, actor = actor, secretBox = box, chapterEnforced = enforced, today = LocalDate(2026, 10, 26))

/** Wraps a plan in a preview entry the applier can take. */
internal fun entryOf(
    plan: LupaBbPlan,
    admin: Uuid,
): LupaBbPreviewStore.Entry {
    val store = LupaBbPreviewStore()
    val now = ServerClock.now()
    val token = store.put(adminId = admin, plan = plan, startedAt = now, expiresAt = now)
    return store.take(token = token, adminId = admin)!!
}

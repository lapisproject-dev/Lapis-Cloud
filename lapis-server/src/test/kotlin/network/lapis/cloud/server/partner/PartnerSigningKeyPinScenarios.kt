package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.PartnerBlocklistTable
import network.lapis.cloud.server.db.generated.PartnerEventTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val PIN_T0 = LocalDateTime(2026, 11, 5, 10, 0, 0)

private fun okFeed(
    remoteId: Uuid,
    key: PinnedSigningKey?,
) = PartnerParseResult.Ok(
    remoteServerId = remoteId,
    remoteName = "Partner",
    events = emptyList(),
    partnersJson = null,
    droppedEvents = 0,
    signingKey = key,
)

/**
 * V1.9.312 -- the pin of the announced signing key by the partner poll, on H2 AND PostgreSQL: the probe pins, a successful fetch updates and (when the
 * key is missing) CLEARS it, a `304` keeps it, a blocked host gets no update, and the lookup by key id finds exactly the pinned row.
 */
abstract class PartnerSigningKeyPinScenarios(
    private val db: TestDatabase,
) : FunSpec({
        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        beforeTest {
            transaction {
                PartnerEventTable.deleteAll()
                PartnerServerTable.deleteAll()
                PartnerBlocklistTable.deleteAll()
            }
        }
        afterSpec {
            try {
                transaction {
                    PartnerEventTable.deleteAll()
                    PartnerServerTable.deleteAll()
                    PartnerBlocklistTable.deleteAll()
                }
            } finally {
                db.deactivate()
            }
        }

        fun key(
            host: String,
            suffix: String = "main-key",
            pem: String = "PEM-A",
        ) = PinnedSigningKey(keyId = "https://$host/federation/actor#$suffix", publicKeyPem = pem)

        fun insert(
            host: String,
            key: PinnedSigningKey?,
            remoteId: Uuid = Uuid.random(),
        ): Uuid =
            (
                transaction {
                    PartnerServerStore.insertProbedPartner(
                        host = host,
                        displayName = null,
                        parsed = okFeed(remoteId = remoteId, key = key),
                        etag = null,
                        now = PIN_T0,
                    )
                } as PartnerInsertResult.Inserted
            ).id

        fun row(id: Uuid) = transaction { PartnerServerStore.findPartner(id)!! }

        test("the probe pins the announced key and the lookup by key id finds exactly that row") {
            val a = insert(host = "a.beispiel.de", key = key("a.beispiel.de"))
            val b = insert(host = "b.beispiel.de", key = key("b.beispiel.de"))
            row(a).signingKeyId shouldBe "https://a.beispiel.de/federation/actor#main-key"
            row(a).signingPublicKeyPem shouldBe "PEM-A"
            transaction { PartnerServerStore.findBySigningKeyId("https://a.beispiel.de/federation/actor#main-key") }.map { it.id } shouldBe
                listOf(a)
            transaction { PartnerServerStore.findBySigningKeyId("https://c.beispiel.de/federation/actor#main-key") } shouldHaveSize 0
            row(b).carpoolFeedServedCount shouldBe 0L
        }

        test("a partner announcing no key has none pinned") {
            val a = insert(host = "a.beispiel.de", key = null)
            row(a).signingKeyId shouldBe null
            row(a).signingPublicKeyPem shouldBe null
        }

        test("a successful fetch updates a changed key, keeps an equal one, and CLEARS the pin when the key is missing") {
            val remote = Uuid.random()
            val a = insert(host = "a.beispiel.de", key = key("a.beispiel.de"), remoteId = remote)
            transaction {
                PartnerServerStore.applyFetchSuccess(
                    id = a,
                    parsed = okFeed(remoteId = remote, key = key("a.beispiel.de", pem = "PEM-B")),
                    etag = null,
                    now = LocalDateTime(2026, 11, 5, 11, 0, 0),
                )
            }
            row(a).signingPublicKeyPem shouldBe "PEM-B"
            transaction {
                PartnerServerStore.applyFetchSuccess(
                    id = a,
                    parsed = okFeed(remoteId = remote, key = key("a.beispiel.de", pem = "PEM-B")),
                    etag = null,
                    now = PIN_T0,
                )
            }
            row(a).signingPublicKeyPem shouldBe "PEM-B"
            transaction {
                PartnerServerStore.applyFetchSuccess(
                    id = a,
                    parsed = okFeed(remoteId = remote, key = null),
                    etag = null,
                    now = PIN_T0,
                )
            }
            row(a).signingKeyId shouldBe null
            row(a).signingPublicKeyPem shouldBe null
            transaction { PartnerServerStore.findBySigningKeyId("https://a.beispiel.de/federation/actor#main-key") } shouldHaveSize 0
        }

        test("a 304 keeps the pin; a host blocked while the request was on the wire gets no update") {
            val remote = Uuid.random()
            val a = insert(host = "a.beispiel.de", key = key("a.beispiel.de"), remoteId = remote)
            transaction { PartnerServerStore.applyNotModified(id = a, now = PIN_T0) }
            row(a).signingPublicKeyPem shouldBe "PEM-A"
            transaction { PartnerServerStore.addBlock(pattern = "a.beispiel.de", now = PIN_T0) }
            transaction {
                PartnerServerStore.applyFetchSuccess(
                    id = a,
                    parsed = okFeed(remoteId = remote, key = key("a.beispiel.de", pem = "PEM-X")),
                    etag = null,
                    now = PIN_T0,
                )
            } shouldBe false
            row(a).signingPublicKeyPem shouldBe "PEM-A"
        }

        test("upgrade order: the V160 ETag reset makes the next poll unconditional, so a partner with an ETag and no pin gets pinned") {
            val remote = Uuid.random()
            val a = insert(host = "a.beispiel.de", key = null, remoteId = remote)
            transaction {
                PartnerServerTable.update({ PartnerServerTable.id eq a }) { it[etag] = "E" }
            }
            row(a).etag shouldBe "E"
            // The statement is taken from the migration file itself, so a removed reset fails this test.
            val resource = "/db/migration/V160__carpool_teaser_feed.sql"
            val file = checkNotNull(PartnerSigningKeyPinScenarios::class.java.getResource(resource))
            val sql =
                file
                    .readText()
                    .lines()
                    .first { it.trim().startsWith("UPDATE partner_server SET etag = NULL") }
                    .trim()
            transaction { exec(sql) }
            row(a).etag shouldBe null
            row(a).signingKeyId shouldBe null
            transaction {
                PartnerServerStore.applyFetchSuccess(
                    id = a,
                    parsed = okFeed(remoteId = remote, key = key("a.beispiel.de")),
                    etag = "E2",
                    now = PIN_T0,
                )
            }
            row(a).signingPublicKeyPem shouldBe "PEM-A"
        }

        test("recordCarpoolFeedServed counts up, sets the time, and writes the item count only for a 200") {
            val a = insert(host = "a.beispiel.de", key = null)
            transaction { PartnerServerStore.recordCarpoolFeedServed(id = a, itemCount = 4, now = PIN_T0) }
            transaction { PartnerServerStore.recordCarpoolFeedServed(id = a, itemCount = null, now = LocalDateTime(2026, 11, 5, 12, 0, 0)) }
            val r = row(a)
            r.carpoolFeedServedCount shouldBe 2L
            r.carpoolFeedLastItems shouldBe 4
            r.carpoolFeedServedAt shouldBe LocalDateTime(2026, 11, 5, 12, 0, 0)
        }
    })

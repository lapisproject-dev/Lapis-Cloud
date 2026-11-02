package network.lapis.cloud.server.events

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.mail.MailDispatcher
import network.lapis.cloud.server.mail.NoOpMailTransport
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventOnlineKind
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.261 -- `event_registration.embed_origin` on H2 AND PostgreSQL: the column of migration V137 (width, nullability, NULL for old rows),
 * the guest-only CHECK, the 255 limit, and storage through the shared submission path for the placements that need no payment provider
 * (free seat, waitlist). The paid path (deferred checkout, resume from the mail link) is covered by `EventRegistrationEmbedOriginPaidTest`.
 */
abstract class EventRegistrationEmbedOriginScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = EventOnlineFixtures()
        val partner = "https://partei.example"

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val submission =
            EventRegistrationSubmission(
                checkoutGateways = emptyMap(),
                baseUrl = "https://example.org",
                mailDispatcher = MailDispatcher(transport = NoOpMailTransport(), scope = scope),
            )

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db)
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        fun eventOf(
            creator: Uuid,
            capacity: Int? = null,
        ) = fx.event(createdBy = creator, kind = EventOnlineKind.NONE, capacity = capacity, title = "Sommerfest")

        fun guest(n: Int = 1) = EventParticipant.Guest(name = "Gast $n", normalizedEmail = "embed-origin-${Uuid.random()}-$n@example.org")

        fun originOf(registrationId: Uuid): String? =
            transaction {
                EventRegistrationTable
                    .selectAll()
                    .where { EventRegistrationTable.id eq registrationId }
                    .single()[EventRegistrationTable.embedOrigin]
            }

        test("the column is VARCHAR(255), nullable, and a registration without it reads NULL") {
            var length: Int? = null
            var nullable: String? = null
            var type: String? = null
            transaction {
                TransactionManager.current().exec(
                    "SELECT data_type, character_maximum_length, is_nullable FROM information_schema.columns " +
                        "WHERE lower(table_name) = 'event_registration' AND lower(column_name) = 'embed_origin'",
                ) { rs ->
                    if (rs.next()) {
                        type = rs.getString("data_type")
                        length = rs.getInt("character_maximum_length")
                        nullable = rs.getString("is_nullable")
                    }
                }
            }
            type?.lowercase() shouldBe "character varying"
            length shouldBe 255
            nullable shouldBe "YES"
            // a row created without the column (as every row before V137) reads NULL
            val plain = fx.registration(eventId = eventOf(fx.member(role = AccountRole.BOARD)), memberId = null)
            originOf(plain) shouldBe null
        }

        test("the CHECK: a guest row may carry an origin, a member row may not") {
            val event = eventOf(fx.member(role = AccountRole.BOARD))
            val guestRow = fx.registration(eventId = event, memberId = null)
            transaction { EventRegistrationTable.update({ EventRegistrationTable.id eq guestRow }) { it[embedOrigin] = partner } }
            originOf(guestRow) shouldBe partner
            val memberRow = fx.registration(eventId = event, memberId = fx.member())
            val failure =
                shouldThrowAny {
                    transaction { EventRegistrationTable.update({ EventRegistrationTable.id eq memberRow }) { it[embedOrigin] = partner } }
                }
            generateSequence<Throwable>(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }.lowercase() shouldContain
                "chk_event_registration_embed_origin_guest"
            originOf(memberRow) shouldBe null
        }

        test("255 characters fit, 256 are rejected by the database (no silent truncation)") {
            val event = eventOf(fx.member(role = AccountRole.BOARD))
            val ok = fx.registration(eventId = event, memberId = null)
            transaction { EventRegistrationTable.update({ EventRegistrationTable.id eq ok }) { it[embedOrigin] = "a".repeat(255) } }
            originOf(ok) shouldBe "a".repeat(255)
            val tooLong = fx.registration(eventId = event, memberId = null)
            shouldThrowAny {
                transaction {
                    EventRegistrationTable.update(
                        { EventRegistrationTable.id eq tooLong },
                    ) { it[embedOrigin] = "a".repeat(256) }
                }
            }
            originOf(tooLong) shouldBe null
        }

        test("submit: a guest on a free event stores the origin") {
            val event = eventOf(fx.member(role = AccountRole.BOARD))
            val result = submission.submit(eventId = event, participant = guest(), embedOrigin = partner)
            val id = result.shouldBeInstanceOf<EventRegistrationResult.Confirmed>().registrationId
            originOf(id) shouldBe partner
        }

        test("submit: a guest without an origin stores NULL") {
            val event = eventOf(fx.member(role = AccountRole.BOARD))
            val id =
                submission
                    .submit(
                        eventId = event,
                        participant = guest(),
                    ).shouldBeInstanceOf<EventRegistrationResult.Confirmed>()
                    .registrationId
            originOf(id) shouldBe null
        }

        test("submit: a waitlisted guest stores the origin") {
            val event = eventOf(fx.member(role = AccountRole.BOARD), capacity = 1)
            submission.submit(eventId = event, participant = guest(1), embedOrigin = partner)
            val waitlisted = submission.submit(eventId = event, participant = guest(2), embedOrigin = partner)
            val id = waitlisted.shouldBeInstanceOf<EventRegistrationResult.Waitlisted>().registrationId
            originOf(id) shouldBe partner
        }

        test("submit: a member passed with an origin stores NULL and does not fail") {
            val event = eventOf(fx.member(role = AccountRole.BOARD))
            val memberId = fx.member()
            val member =
                EventParticipant.Member(
                    memberId = memberId,
                    displayName = "Mitglied",
                    email = "event-online-$memberId@example.org",
                )
            val id =
                submission
                    .submit(eventId = event, participant = member, embedOrigin = partner)
                    .shouldBeInstanceOf<EventRegistrationResult.Confirmed>()
                    .registrationId
            originOf(id) shouldBe null
        }

        test("submit: an origin longer than the column is dropped instead of failing the registration") {
            val event = eventOf(fx.member(role = AccountRole.BOARD))
            val result = submission.submit(eventId = event, participant = guest(), embedOrigin = "https://" + "a".repeat(260))
            val id = result.shouldBeInstanceOf<EventRegistrationResult.Confirmed>().registrationId
            originOf(id) shouldBe null
        }
    })

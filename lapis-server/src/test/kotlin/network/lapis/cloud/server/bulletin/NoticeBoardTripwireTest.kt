package network.lapis.cloud.server.bulletin

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import network.lapis.cloud.server.db.SourceScan
import network.lapis.cloud.server.encounter.EncounterSourceScan
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.NoticeDto
import network.lapis.cloud.shared.rpc.INoticeBoardService
import java.io.File

/**
 * V1.9.297 -- structural guarantees of the bulletin board that no behavioural test can see: the access set, one gate on every method, the roles
 * of the moderation, the rate limiter before the transaction, the weight coming from the one decay implementation, no content in a log line, who
 * may touch the tables, and the declaration order of the RPC interface (Kilua numbers routes by order).
 */
class NoticeBoardTripwireTest :
    FunSpec({
        val files = SourceScan.mainFiles()
        val serviceFile = EncounterSourceScan.mainFile("rpc/NoticeBoardService.kt")
        val bulletinFiles = files.filter { "/server/bulletin/" in it.path.replace('\\', '/') }
        val ownFiles = bulletinFiles + serviceFile + EncounterSourceScan.mainFile("dsgvo/NoticePersonalData.kt")

        test("NOTICE_BOARD is exactly the ACTIVE members: narrower than the carpool centre, no guest, no friend") {
            MemberStatusSets.NOTICE_BOARD shouldBe setOf(MemberStatus.ACTIVE)
            (MemberStatusSets.CARPOOL_PASSENGER.size > MemberStatusSets.NOTICE_BOARD.size) shouldBe true
        }

        test("the files of the board never mention the wider carpool gate") {
            val offenders =
                ownFiles.filter { f ->
                    val code = SourceScan.blank(f.readText())
                    code.contains("CARPOOL_PASSENGER") || code.contains("requireCarpoolPassengerMembership")
                }
            offenders.map { it.name }.shouldBeEmpty()
        }

        test("every RPC method starts with the NOTICE_BOARD gate, and the gate is the only membership check of the service") {
            val fns = EncounterSourceScan.functions(serviceFile)
            val methods = fns.filter { it.isOverride }
            methods.map { it.name }.toSet() shouldBe
                INoticeBoardService::class
                    .members
                    .filter { it.name != "equals" && it.name != "hashCode" && it.name != "toString" }
                    .map { it.name }
                    .toSet()
            methods.size shouldBe 11
            methods.forEach { m ->
                withClue("${m.name} must call the gate first") {
                    val gate = m.body.indexOf("gate(current")
                    (gate >= 0) shouldBe true
                    // nothing that reads or writes a table comes before the gate inside the transaction
                    val firstTx = m.body.indexOf("transaction {")
                    (firstTx in 0 until gate) shouldBe true
                    val between = m.body.substring(firstTx, gate)
                    between.contains("Table") shouldBe false
                }
            }
            val gateFn = fns.first { it.name == "gate" }
            gateFn.body shouldContain "MemberStatusSets.NOTICE_BOARD"
            val code = SourceScan.blank(serviceFile.readText())
            code.contains("requireActiveMembership") shouldBe false
        }

        test("pin, unpin and remove need the BOARD or ADMIN role; the role check is one function") {
            val fns = EncounterSourceScan.functions(serviceFile)
            listOf("pinNotice", "unpinNotice", "removeNotice").forEach { name ->
                withClue("$name must call requireModerator after the gate") {
                    val body = fns.first { it.name == name && it.isOverride }.body
                    (body.indexOf("requireModerator(current)") > body.indexOf("gate(current")) shouldBe true
                }
            }
            val reader = SourceScan.blank(EncounterSourceScan.mainFile("bulletin/NoticeBoardReader.kt").readText())
            reader shouldContain "AccountRole.BOARD"
            reader shouldContain "AccountRole.ADMIN"
            EncounterSourceScan.functions(serviceFile).first { it.name == "requireModerator" }.body shouldContain "isModerator"
        }

        test("the rate limiter is charged BEFORE the transaction and never inside one") {
            val fns = EncounterSourceScan.functions(serviceFile)
            fns.filter { it.isOverride }.forEach { m ->
                withClue("${m.name}: limiter before transaction") {
                    val limiter = m.body.indexOf("deps.limiter.checkAndRecord")
                    val tx = m.body.indexOf("transaction {")
                    (limiter in 0 until tx) shouldBe true
                }
            }
            val code = SourceScan.blank(serviceFile.readText())
            val blocks = SourceScan.transactionBlocks(code)
            Regex("""\bcheckAndRecord\w*\b|\btryAcquire\b""")
                .findAll(code)
                .filter { m ->
                    blocks.any { m.range.first in it }
                }.toList()
                .shouldBeEmpty()
        }

        test("the weight comes from SocialPostWeight and WeightDecayClock only: no decay maths of its own in the board") {
            val offenders =
                ownFiles.filter { f ->
                    val code = SourceScan.blank(f.readText())
                    Regex("""\.pow\(|KEEP_RATE_PER_DAY|\bPOWER\b|\bMathContext\b|Math\.pow""").containsMatchIn(code)
                }
            offenders.map { it.name }.shouldBeEmpty()
            val weight = SourceScan.blank(EncounterSourceScan.mainFile("bulletin/NoticeWeight.kt").readText())
            weight shouldContain "SocialPostWeight.ownWeightUnrounded"
            weight shouldContain "WeightDecayClock.round2"
            SourceScan.blank(serviceFile.readText()).contains("decayedUnrounded") shouldBe false
        }

        test("a log line of the board never names a title, a text, a note, a name or a file") {
            val offenders =
                ownFiles.flatMap { f ->
                    f
                        .readLines()
                        .withIndex()
                        .filter { (_, line) ->
                            line.contains("logger.") &&
                                listOf(
                                    "title",
                                    "body",
                                    "note",
                                    "displayName",
                                    "authorName",
                                    "message",
                                    "altText",
                                    "storageKey",
                                ).any { line.contains(it) }
                        }.map { "${f.name}:${it.index + 1}" }
                }
            offenders.shouldBeEmpty()
        }

        test("only the board, its erasure, the picture policy, the seed and the generated objects touch the two tables") {
            val users =
                files
                    .filter { "/db/generated/" !in it.path.replace('\\', '/') }
                    .filter { Regex("""\bBulletinNotice(Boost)?Table\b""").containsMatchIn(SourceScan.blank(it.readText())) }
                    .map { it.name }
                    .toSet()
            val allowed =
                setOf(
                    "NoticeBoardService.kt",
                    "NoticeBoardReader.kt",
                    "NoticeRetention.kt",
                    "NoticePersonalData.kt",
                    "GalleryOwnerPolicy.kt",
                    "StagingSeedNotices.kt",
                    "OrganizationSchemaCatalog.kt",
                )
            (users - allowed).shouldBeEmpty()
            // the ledger entry of a boost is written in exactly one place
            files
                .filter { Regex("""NOTICE_BOOST""").containsMatchIn(SourceScan.blank(it.readText())) && "/db/generated/" !in it.path }
                .map { it.name }
                .toSet() shouldBe setOf("NoticeBoardService.kt", "StagingSeedNotices.kt")
        }

        test("a delete of a notice always goes through the one function that also deletes its pictures and boosts") {
            val deleters =
                files.filter {
                    Regex(
                        """BulletinNoticeTable\s*\.\s*(deleteWhere|deleteAll)""",
                    ).containsMatchIn(SourceScan.blank(it.readText()))
                }
            deleters.map { it.name } shouldBe listOf("NoticeRetention.kt")
            val body = SourceScan.blank(EncounterSourceScan.mainFile("bulletin/NoticeRetention.kt").readText())
            body shouldContain "deleteAllForOwnerInTx"
        }

        test("the RPC interface keeps its order: new methods only at the end (Kilua numbers routes by declaration order)") {
            INoticeBoardService::class
                .members
                .filter {
                    it.name in
                        setOf(
                            "listNotices",
                            "listMyNotices",
                            "getNotice",
                            "createNotice",
                            "updateNotice",
                            "withdrawNotice",
                            "boostNotice",
                            "replyToNotice",
                            "pinNotice",
                            "unpinNotice",
                            "removeNotice",
                        )
                }.size shouldBe 11
            val source =
                File("../lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/rpc/INoticeBoardService.kt")
                    .let {
                        if (it.exists()) {
                            it
                        } else {
                            File(
                                "lapis-shared/src/commonMain/kotlin/network/lapis/cloud/shared/rpc/INoticeBoardService.kt",
                            )
                        }
                    }.readText()
            val order = Regex("""suspend fun (\w+)""").findAll(source).map { it.groupValues[1] }.toList()
            order shouldBe
                listOf(
                    "listNotices",
                    "listMyNotices",
                    "getNotice",
                    "createNotice",
                    "updateNotice",
                    "withdrawNotice",
                    "boostNotice",
                    "replyToNotice",
                    "pinNotice",
                    "unpinNotice",
                    "removeNotice",
                )
        }

        test("the notice DTO carries ONE picture reference and no author id") {
            val names = NoticeDto.serializer().descriptor.let { d -> (0 until d.elementsCount).map { d.getElementName(it) } }
            names.filter { it.contains("image", ignoreCase = true) } shouldBe listOf("image")
            names.none { it.equals("authorMemberId", ignoreCase = true) || it.equals("authorId", ignoreCase = true) } shouldBe true
        }
    })

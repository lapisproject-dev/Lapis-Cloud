package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.agreementtexts.AgreementTextTestState
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AgreementTextVersionTable
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val ADMIN_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")
private val BOARD_ID = Uuid.parse("00000000-0000-0000-0000-000000000002")

/** V1.9.134 -- [AgreementTextPersonalData]: the actor reference is exported without the text and retained on erasure (the version is append-only evidence). */
class AgreementTextPersonalDataTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterEach { AgreementTextTestState.reset() }
        afterSpec { AgreementTextTestState.reset() }

        test("the contributor covers exactly the two tables") {
            AgreementTextPersonalData.coveredTables.map { it.tableName }.toSet() shouldBe
                setOf("agreement_text_version", "agreement_text_draft")
        }

        test("export lists the versions the member published (kind, label, time) and never the text") {
            AgreementTextTestState.publishDirect(
                kind = AgreementTextKind.MEMBERSHIP,
                label = "dsgvo.v1",
                body = "Geheimer Vertragsinhalt NICHT im Export",
            )
            val exported = transaction { AgreementTextPersonalData.exportMember(ADMIN_ID) }
            val versions = exported["publishedVersions"]!!.jsonArray
            versions.any { it.jsonObject["versionLabel"]!!.jsonPrimitive.content == "dsgvo.v1" } shouldBe true
            exported.toString().contains("Geheimer Vertragsinhalt") shouldBe false
            val other = transaction { AgreementTextPersonalData.exportMember(BOARD_ID) }
            other["publishedVersions"]!!.jsonArray.size shouldBe 0
        }

        test("erase retains every row whatever the mode, with a reason, and leaves the rows untouched") {
            AgreementTextTestState.publishDirect(kind = AgreementTextKind.FRIEND, label = "dsgvo.f1", body = "Freund-Text")
            listOf(ErasureMode.ANONYMIZE, ErasureMode.HARD_DELETE_WHERE_UNCONSTRAINED).forEach { mode ->
                val outcomes = transaction { AgreementTextPersonalData.eraseMember(memberId = ADMIN_ID, mode = mode) }
                outcomes.map { it.table }.toSet() shouldBe setOf("agreement_text_version", "agreement_text_draft")
                outcomes.forEach {
                    it.rowsAnonymized shouldBe 0
                    it.rowsDeleted shouldBe 0
                    (it.retentionReason?.isNotBlank() ?: false) shouldBe true
                }
                (outcomes.single { it.table == "agreement_text_version" }.rowsRetained >= 1) shouldBe true
                transaction { AgreementTextVersionTable.selectAll().count() } shouldBe 1L
            }
        }
    })

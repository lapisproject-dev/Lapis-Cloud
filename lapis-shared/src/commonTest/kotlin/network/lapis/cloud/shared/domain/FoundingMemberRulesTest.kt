package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FoundingMemberRulesTest {
    private val today = LocalDate(2026, 11, 16)

    @Test
    fun futureDateIsAViolationTodayPastAndNullAreFine() {
        assertEquals(
            FoundingMemberSinceViolation.IN_FUTURE,
            FoundingMemberRules.validateSince(since = LocalDate(2026, 11, 17), today = today),
        )
        assertNull(FoundingMemberRules.validateSince(since = today, today = today))
        assertNull(FoundingMemberRules.validateSince(since = LocalDate(1999, 1, 1), today = today))
        assertNull(FoundingMemberRules.validateSince(since = null, today = today))
    }

    @Test
    fun labelAndConsentTextArePinned() {
        assertEquals("Gründungsmitglied", FoundingMemberRules.LABEL)
        assertEquals("1", FoundingMemberRules.CONSENT_TEXT_VERSION)
        // A change of the wording REQUIRES a new CONSENT_TEXT_VERSION: update both together, never only the text.
        assertEquals(
            "Ich bin damit einverstanden, dass die Kennzeichnung „Gründungsmitglied“ öffentlich neben meinem Namen " +
                "angezeigt wird: auf den öffentlichen Seiten Vorstand und Politiker (nur, wenn ich dort geführt werde), " +
                "in den einbettbaren Verzeichnissen und auf der Prüfseite meines Mitgliedsausweises, die der QR-Code aufruft. " +
                "Ich kann diese Einwilligung jederzeit widerrufen. Sie erlischt, wenn der Vorstand die Kennzeichnung entfernt " +
                "oder ich nicht mehr aktives Mitglied bin.",
            FoundingMemberRules.CONSENT_TEXT,
        )
        // names every place where the label appears
        listOf("Vorstand", "Politiker", "einbettbaren", "QR-Code").forEach { assertTrue(it in FoundingMemberRules.CONSENT_TEXT, it) }
    }

    @Test
    fun publicationEligibilityIsExactlyActive() {
        assertEquals(setOf(MemberStatus.ACTIVE), MemberStatusSets.FOUNDING_MEMBER_PUBLICATION_ELIGIBLE)
    }

    @Test
    fun olderPayloadsDecodeToTheDefaults() {
        val json = Json { ignoreUnknownKeys = true }
        val row =
            json.decodeFromString(
                MemberAdminRowDto.serializer(),
                """{"id":"1","displayName":"A","email":"a@example.org","status":"ACTIVE","role":null,"joinedAt":"2024-01-01"}""",
            )
        assertFalse(row.foundingMember)
        assertNull(row.foundingMemberSince)
        assertFalse(row.foundingMemberPublicConsent)
        assertFalse(MemberAdminQuery().foundingMemberOnly)
        val old =
            json.decodeFromString(
                MemberChangeSnapshot.serializer(),
                """{"displayNameChanged":false,"emailChanged":false,"status":"ACTIVE","role":null}""",
            )
        assertNull(old.foundingMember)
    }

    @Test
    fun auditFactsUseStableKeys() {
        val text =
            Json.encodeToString(
                FoundingMemberAuditFacts.serializer(),
                FoundingMemberAuditFacts(event = FoundingMemberAuditEvent.PUBLICATION_REVOKED_BY_STATUS_CHANGE),
            )
        assertTrue("member.founding_member.publication_revoked_by_status_change" in text)
    }
}

package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * Foundation stub (see CLAUDE.md "Vorab-Befund"): V0.1.2-V0.1.4 (Mitglieder-Stammdaten,
 * Beitritts-/Austrittsworkflow, Auth/Session) do not exist yet. [MemberStatus] and
 * [AccountRole] are modelled here only as granularly as V0.1.5 (Beitraege, Dokumente,
 * Kommunikation) needs them as foreign keys / authorization checks. A real member
 * management wave replaces this stub without breaking the foreign keys defined against it.
 */
@Serializable
enum class MemberStatus { ANTRAG, AKTIV, GAST, AUSGETRETEN }

@Serializable
enum class AccountRole { MEMBER, BOARD, TREASURER, ADMIN }

/**
 * [street]/[postalCode]/[city]/[country] (V0.4.1) are a minimal, single, nullable postal address
 * -- needed by the Serienbrief/PDF engine (Beitragsrechnung/Spendenbescheinigung/Einladung all
 * mail-merge a member's postal address) and reused as-is by V0.4.2's later postal (Letterxpress)
 * dispatch. All default to `null` so existing call sites stay source-compatible. Not every member
 * has provided an address yet, and an email-only member may never need one.
 */
@Serializable
data class MemberDto(
    val id: String,
    val displayName: String,
    val email: String,
    val status: MemberStatus,
    val joinedAt: LocalDate,
    val role: AccountRole,
    val street: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val country: String? = null,
)

/**
 * Reduced projection of [MemberDto] for the unauthenticated "current member" picker
 * (see [network.lapis.cloud.shared.rpc.IMemberService.listMembers]). Deliberately excludes
 * [MemberDto.email] and [MemberDto.role] — those are PII / authorization-relevant fields that
 * must not be readable by a caller who hasn't authenticated yet.
 */
@Serializable
data class MemberSummaryDto(
    val id: String,
    val displayName: String,
)

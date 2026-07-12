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

@Serializable
data class MemberDto(
    val id: String,
    val displayName: String,
    val email: String,
    val status: MemberStatus,
    val joinedAt: LocalDate,
    val role: AccountRole,
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

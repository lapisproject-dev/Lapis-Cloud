package network.lapis.cloud.server.dsgvo

import network.lapis.cloud.shared.domain.DsgvoSubjectKind
import kotlin.uuid.Uuid

/**
 * Welle V1.4.2 "Interessenten-/Sympathisanten-CRM" -- the DSGVO export/erasure framework's subject
 * type, widened from "always a member" to "a member OR a `crm_contact`". Server-only: `lapis-shared`
 * uses `kotlin.uuid.Uuid` nowhere (every DTO id is a plain `String`, verified against the whole
 * module), and the client never needs to construct one -- it only ever passes a plain member/contact
 * id string across RPC, exactly as before this wave. [DsgvoSubjectKind] (the wire-visible
 * discriminator [subjectKind] mirrors) is what actually crosses the wire, on
 * [network.lapis.cloud.shared.domain.DsgvoAuditLogEntryDto.subjectKind].
 *
 * See [PersonalDataContributor]/[PersonalDataRegistry] KDoc for how this plugs into the existing
 * export/erasure framework, and [PersonalDataCoverageTest] for the actual enforcement mechanism
 * (now a walk over BOTH subject-root tables, not just `member`).
 */
sealed interface DataSubject {
    val kind: DsgvoSubjectKind
    val id: Uuid

    data class Member(
        override val id: Uuid,
    ) : DataSubject {
        override val kind = DsgvoSubjectKind.MEMBER
    }

    data class CrmContact(
        override val id: Uuid,
    ) : DataSubject {
        override val kind = DsgvoSubjectKind.CRM_CONTACT
    }
}

/**
 * The one cast this wave's [MemberPersonalDataContributor] adapter needs, defensively checked. See
 * [MemberPersonalDataContributor] KDoc for why 29 pre-existing contributors are adapted this way
 * rather than each growing a `when (subject)` of their own. A [PersonalDataRegistry.contributors]
 * iteration that is correctly filtered by [PersonalDataContributor.handledSubjects] before dispatch
 * (see [network.lapis.cloud.server.rpc.DsgvoService]/[network.lapis.cloud.server.dsgvo.CrmPersonalData]
 * call sites) never reaches the `error` branch -- reaching it is a caller bug, not a data condition,
 * hence `error` rather than a typed exception.
 */
internal fun DataSubject.asMember(): Uuid =
    (this as? DataSubject.Member)?.id
        ?: error(
            "MemberPersonalDataContributor reached with subject kind $kind -- the caller's " +
                "handledSubjects filter is broken",
        )

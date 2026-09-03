package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.db.generated.OidcAuthorizationCodeTable
import network.lapis.cloud.server.db.generated.OidcGuestProfileTable
import network.lapis.cloud.server.db.generated.OidcIssuedTokenTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Owns every V0.8.2 OIDC-Gastzugang-Federation table that carries a `member_id` FK --
 * [OidcGuestProfileTable] (this member's OWN guest profile, when this member IS a guest here),
 * [OidcAuthorizationCodeTable]/[OidcIssuedTokenTable] (this member acting as ISSUER's subject,
 * i.e. this member went out as a guest elsewhere and this server minted tokens on their behalf).
 * Modeled directly on [SessionPersonalData]'s shape -- same "no retention duty, purely
 * access-control/profile artifact" reasoning: none of these rows are anything a member, the
 * organization, or GoBD/§25 PartG has a legal or organizational interest in keeping after erasure.
 *
 * **NOT covered here, by design**: `oidc_guest_login_event.member_id` -- deliberately has NO real
 * FK to `member` at all (see `25-oidc-guest-federation.kuml.kts` file header), so it never appears
 * in `PersonalDataCoverageTest`'s `information_schema` FK walk in the first place; it is listed in
 * [PersonalDataRegistry.noPersonalDataAllowlist] purely for documentation, same treatment
 * `dsgvo_audit_log` already gets for its own accountability-is-its-own-legal-basis retention.
 * `oidc_signing_key`/`oidc_client_registration`/`oidc_client_redirect_uri`/
 * `oidc_home_server_registration`/`oidc_rp_login_attempt` carry no member FK at all (they describe
 * remote SERVERS or pre-auth scratch state, same "actor = organization, not member" treatment
 * V0.8.1's `federation_relationship`/`federation_actor_key` already get) -- no contributor or
 * allowlist entry needed for them either.
 */
object OidcGuestPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "oidc_guest_federation"
    override val displayName = "OIDC-Gastzugang"
    override val coveredTables = setOf(OidcGuestProfileTable, OidcAuthorizationCodeTable, OidcIssuedTokenTable)

    override fun exportMember(memberId: Uuid) =
        buildJsonObject {
            val profileRow = OidcGuestProfileTable.selectAll().where { OidcGuestProfileTable.memberId eq memberId }.singleOrNull()
            if (profileRow != null) {
                put(
                    "guestProfile",
                    buildJsonObject {
                        put("homeserverUrl", profileRow[OidcGuestProfileTable.homeserverUrl])
                        put("membershipStatus", profileRow[OidcGuestProfileTable.membershipStatus])
                        put("grantedScope", profileRow[OidcGuestProfileTable.grantedScope])
                        put("lastLoginAt", profileRow[OidcGuestProfileTable.lastLoginAt].toString())
                        // pictureUrl deliberately omitted from export -- a remote-hosted URL, not
                        // meaningful "our own" personal data beyond what the home server already holds.
                    },
                )
            }
            put(
                "authorizationCodesIssuedForThisMemberAsSubject",
                buildJsonArray {
                    OidcAuthorizationCodeTable
                        .selectAll()
                        .where { OidcAuthorizationCodeTable.memberId eq memberId }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("createdAt", row[OidcAuthorizationCodeTable.createdAt].toString())
                                    put("scope", row[OidcAuthorizationCodeTable.scope])
                                },
                            )
                        }
                },
            )
            put(
                "tokensIssuedForThisMemberAsSubject",
                buildJsonArray {
                    OidcIssuedTokenTable
                        .selectAll()
                        .where { OidcIssuedTokenTable.memberId eq memberId }
                        .forEach { row ->
                            add(
                                buildJsonObject {
                                    put("issuedAt", row[OidcIssuedTokenTable.issuedAt].toString())
                                    put("scope", row[OidcIssuedTokenTable.scope])
                                    put("revokedAt", row[OidcIssuedTokenTable.revokedAt]?.toString())
                                    // Never export the hash values themselves -- one-way digests of
                                    // bearer secrets, same posture SessionPersonalData already takes.
                                },
                            )
                        }
                },
            )
        }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val profilesDeleted = OidcGuestProfileTable.deleteWhere { OidcGuestProfileTable.memberId eq memberId }
        val codesDeleted = OidcAuthorizationCodeTable.deleteWhere { OidcAuthorizationCodeTable.memberId eq memberId }
        val tokensDeleted = OidcIssuedTokenTable.deleteWhere { OidcIssuedTokenTable.memberId eq memberId }
        return listOf(
            TableErasureOutcome(table = "oidc_guest_profile", rowsDeleted = profilesDeleted),
            TableErasureOutcome(table = "oidc_authorization_code", rowsDeleted = codesDeleted),
            TableErasureOutcome(table = "oidc_issued_token", rowsDeleted = tokensDeleted),
        )
    }
}

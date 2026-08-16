package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.formUrlEncode
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OidcAuthorizationCodeTable
import network.lapis.cloud.server.db.generated.OidcClientRedirectUriTable
import network.lapis.cloud.server.db.generated.OidcClientRegistrationTable
import network.lapis.cloud.server.db.generated.OidcIssuedTokenTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.federation.OidcDynamicClientRegistrationResponse
import network.lapis.cloud.server.federation.OidcPkce
import network.lapis.cloud.server.module
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val TEST_JSON = Json { ignoreUnknownKeys = true }

/**
 * V0.11.0's single most severe security fix -- see `OidcRoutes.issueTokens` KDoc "the single most
 * severe gap the FRIEND wave forced into the open": before this wave, ANY authenticated caller
 * (including a self-service FRIEND) could complete the OIDC OP flow and walk away with an ID token
 * asserting `membership_status` to a federated partner, i.e. an identity-laundering endpoint. This
 * test proves a FRIEND session is refused at the ONE place `issueTokens` is actually called --
 * `/token`, for BOTH the `authorization_code` exchange and (via a second flow reusing the same
 * refusal) implicitly the `refresh_token` grant, since a refused exchange never produces a refresh
 * token to test in the first place. Reuses [OidcRoutesTest]'s own house pattern (real, fully-wired
 * [network.lapis.cloud.server.module], not throwaway routes) since the OIDC OP surface has no
 * service-class layer to call directly.
 */
class OidcFriendCannotFederateTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdClientIds = mutableListOf<String>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                OidcAuthorizationCodeTable.deleteWhere { OidcAuthorizationCodeTable.memberId inList createdMemberIds }
                OidcIssuedTokenTable.deleteWhere { OidcIssuedTokenTable.memberId inList createdMemberIds }
                SessionTable.deleteWhere { SessionTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                val registrationIds =
                    OidcClientRegistrationTable
                        .selectAll()
                        .where { OidcClientRegistrationTable.clientId inList createdClientIds }
                        .map { it[OidcClientRegistrationTable.id] }
                OidcClientRedirectUriTable.deleteWhere { OidcClientRedirectUriTable.clientRegistrationId inList registrationIds }
                OidcClientRegistrationTable.deleteWhere { OidcClientRegistrationTable.clientId inList createdClientIds }
            }
        }

        fun createTestMember(
            email: String,
            status: MemberStatus,
        ): Pair<Uuid, String> {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "OIDC Friend Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                    if (status == MemberStatus.FRIEND) it[friendSince] = LocalDate(2026, 1, 1)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                    it[passwordHash] = PasswordHasher.hash("irrelevant-password-1234")
                }
            }
            createdMemberIds += id
            val rawToken = SessionStore.createSession(id).rawToken
            return id to rawToken
        }

        suspend fun registerDcrClient(client: HttpClient): OidcDynamicClientRegistrationResponse {
            val response =
                client.post("/federation/oidc/register") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"client_name":"Friend-Cannot-Federate RP ${Uuid.random()}","redirect_uris":["https://rp.example/callback"],""" +
                            """"backchannel_logout_uri":"https://rp.example/backchannel-logout"}""",
                    )
                }
            response.status shouldBe HttpStatusCode.Created
            val dto = TEST_JSON.decodeFromString(OidcDynamicClientRegistrationResponse.serializer(), response.bodyAsText())
            createdClientIds += dto.client_id
            return dto
        }

        suspend fun consentAndExtractCode(
            noRedirectClient: HttpClient,
            rawSession: String,
            clientId: String,
            codeChallenge: String,
            state: String,
            nonce: String,
        ): HttpResponse =
            noRedirectClient.post("/federation/oidc/authorize/consent") {
                header(HttpHeaders.Cookie, "lapis_session=$rawSession")
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(
                    Parameters
                        .build {
                            append("decision", "allow")
                            append("client_id", clientId)
                            append("redirect_uri", "https://rp.example/callback")
                            append("scope", "openid profile_basic")
                            append("state", state)
                            append("code_challenge", codeChallenge)
                            append("nonce", nonce)
                        }.formUrlEncode(),
                )
            }

        fun codeFromLocation(response: HttpResponse): String {
            val location = requireNotNull(response.headers[HttpHeaders.Location])
            return requireNotNull(Regex("code=([^&]+)").find(location)) { "no code in $location" }.groupValues[1]
        }

        test(
            "A FRIEND session completes /authorize consent (a real code IS issued -- the gate is not there) but /token exchange is refused with invalid_grant, no tokens issued",
        ) {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module() }

                val dcrDto = registerDcrClient(client)
                val (friendId, rawSession) =
                    createTestMember(
                        "oidc-friend-cannot-federate-${Uuid.random()}@example.org",
                        MemberStatus.FRIEND,
                    )

                val codeVerifier = "test-code-verifier-${Uuid.random()}-with-enough-length-1234567890"
                val codeChallenge = OidcPkce.codeChallengeS256(codeVerifier)
                val state = "state-${Uuid.random()}"
                val nonce = "nonce-${Uuid.random()}"

                val consentResponse =
                    consentAndExtractCode(
                        noRedirectClient = noRedirectClient,
                        rawSession = rawSession,
                        clientId = dcrDto.client_id,
                        codeChallenge = codeChallenge,
                        state = state,
                        nonce = nonce,
                    )
                consentResponse.status shouldBe HttpStatusCode.Found
                val code = codeFromLocation(consentResponse)

                val tokenResponse =
                    client.post("/federation/oidc/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(
                            Parameters
                                .build {
                                    append("grant_type", "authorization_code")
                                    append("code", code)
                                    append("redirect_uri", "https://rp.example/callback")
                                    append("client_id", dcrDto.client_id)
                                    append("client_secret", dcrDto.client_secret)
                                    append("code_verifier", codeVerifier)
                                }.formUrlEncode(),
                        )
                    }
                tokenResponse.status shouldBe HttpStatusCode.BadRequest
                tokenResponse.bodyAsText().contains("invalid_grant") shouldBe true

                // No token row was ever persisted for this member -- the refusal is not just an
                // HTTP-layer cosmetic error, no OidcIssuedTokenTable row exists to later refresh.
                transaction {
                    OidcIssuedTokenTable.selectAll().where { OidcIssuedTokenTable.memberId eq friendId }.count()
                } shouldBe 0L
            }
        }

        test(
            "Sanity control: the IDENTICAL flow for an ACTIVE member succeeds -- the refusal above is FRIEND-specific, not a broken flow",
        ) {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module() }

                val dcrDto = registerDcrClient(client)
                val (_, rawSession) = createTestMember("oidc-friend-control-active-${Uuid.random()}@example.org", MemberStatus.ACTIVE)

                val codeVerifier = "test-code-verifier-${Uuid.random()}-with-enough-length-1234567890"
                val codeChallenge = OidcPkce.codeChallengeS256(codeVerifier)
                val state = "state-${Uuid.random()}"
                val nonce = "nonce-${Uuid.random()}"

                val consentResponse =
                    consentAndExtractCode(
                        noRedirectClient = noRedirectClient,
                        rawSession = rawSession,
                        clientId = dcrDto.client_id,
                        codeChallenge = codeChallenge,
                        state = state,
                        nonce = nonce,
                    )
                consentResponse.status shouldBe HttpStatusCode.Found
                val code = codeFromLocation(consentResponse)

                val tokenResponse =
                    client.post("/federation/oidc/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(
                            Parameters
                                .build {
                                    append("grant_type", "authorization_code")
                                    append("code", code)
                                    append("redirect_uri", "https://rp.example/callback")
                                    append("client_id", dcrDto.client_id)
                                    append("client_secret", dcrDto.client_secret)
                                    append("code_verifier", codeVerifier)
                                }.formUrlEncode(),
                        )
                    }
                tokenResponse.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "A status change between grants takes effect on the next refresh: an ACTIVE member obtains tokens, then leaves membership, then a refresh is refused",
        ) {
            testApplication {
                val noRedirectClient = createClient { followRedirects = false }
                application { module() }

                val dcrDto = registerDcrClient(client)
                val (memberId, rawSession) =
                    createTestMember("oidc-friend-status-change-${Uuid.random()}@example.org", MemberStatus.ACTIVE)

                val codeVerifier = "test-code-verifier-${Uuid.random()}-with-enough-length-1234567890"
                val codeChallenge = OidcPkce.codeChallengeS256(codeVerifier)
                val state = "state-${Uuid.random()}"
                val nonce = "nonce-${Uuid.random()}"

                val consentResponse =
                    consentAndExtractCode(
                        noRedirectClient = noRedirectClient,
                        rawSession = rawSession,
                        clientId = dcrDto.client_id,
                        codeChallenge = codeChallenge,
                        state = state,
                        nonce = nonce,
                    )
                val code = codeFromLocation(consentResponse)

                val tokenResponse =
                    client.post("/federation/oidc/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(
                            Parameters
                                .build {
                                    append("grant_type", "authorization_code")
                                    append("code", code)
                                    append("redirect_uri", "https://rp.example/callback")
                                    append("client_id", dcrDto.client_id)
                                    append("client_secret", dcrDto.client_secret)
                                    append("code_verifier", codeVerifier)
                                }.formUrlEncode(),
                        )
                    }
                tokenResponse.status shouldBe HttpStatusCode.OK
                val refreshTokenValue =
                    requireNotNull(
                        Regex(""""refresh_token"\s*:\s*"([^"]+)"""").find(tokenResponse.bodyAsText())?.groupValues?.get(1),
                    )

                // Downgrade the member out of ORGANIZATION_MEMBER, simulating leaveMembership --
                // hand-rolled, not the real RPC call, since this test only needs the DB state
                // effect issueTokens reads.
                transaction {
                    MemberTable.update({ MemberTable.id eq memberId }) { it[status] = MemberStatus.WITHDRAWN }
                }

                val refreshResponse =
                    client.post("/federation/oidc/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(
                            Parameters
                                .build {
                                    append("grant_type", "refresh_token")
                                    append("refresh_token", refreshTokenValue)
                                    append("client_id", dcrDto.client_id)
                                    append("client_secret", dcrDto.client_secret)
                                }.formUrlEncode(),
                        )
                    }
                refreshResponse.status shouldBe HttpStatusCode.BadRequest
                refreshResponse.bodyAsText().contains("invalid_grant") shouldBe true
            }
        }
    })

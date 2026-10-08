package network.lapis.cloud.server.member

import network.lapis.cloud.server.security.FriendEmailVerificationTokenStore
import network.lapis.cloud.server.security.PasswordResetTokenStore
import network.lapis.cloud.server.security.SessionStore
import kotlin.uuid.Uuid

/**
 * The consequences every effective change of a member's address has, shared by [EmailChangeService] and the Keycloak profile
 * sync (V1.9.73): the address IS the login identifier, so every other session ends and a token minted for the OLD address must
 * not go on resetting a password or verifying the NEW address. Run AFTER the transaction that changed the address.
 */
internal object AddressChangeSideEffects {
    fun invalidateAfterAddressChange(
        memberId: Uuid,
        exceptRawToken: String?,
    ) {
        SessionStore.revokeAllForMember(memberId = memberId, exceptRawToken = exceptRawToken)
        PasswordResetTokenStore.invalidateAllForMember(memberId = memberId)
        FriendEmailVerificationTokenStore.invalidateAllForMember(memberId = memberId)
    }
}

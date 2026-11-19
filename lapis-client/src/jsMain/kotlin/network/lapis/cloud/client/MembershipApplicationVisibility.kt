package network.lapis.cloud.client

/**
 * V1.9.315 -- the ONE rule for every "Mitglied werden" link and the registration form: the instance switch is on AND the instance does not
 * run in Keycloak mode (there the server refuses self-registration anyway). Mirrors the server's `membershipApplicationOpen`. The server
 * stays the authority; this only decides what is shown.
 */
fun isMembershipApplicationOpen(
    enabled: Boolean,
    keycloakMode: Boolean,
): Boolean = enabled && !keycloakMode

/** [isMembershipApplicationOpen] for the current page's brand payload. */
fun membershipApplicationOpenNow(): Boolean = isMembershipApplicationOpen(Branding.membershipApplicationEnabled, Branding.keycloakMode)

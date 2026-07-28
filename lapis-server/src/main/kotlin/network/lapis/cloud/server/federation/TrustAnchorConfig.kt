package network.lapis.cloud.server.federation

/**
 * V0.8.3 Trust-Anchor-Governance -- this server's own Trust Anchor federation Entity Identifier and
 * derived well-known/fetch endpoint URLs. Reuses [FederationConfig.publicBaseUrl] as the entity
 * identifier's origin -- the SAME bare origin V0.8.2's OIDC Issuer already uses (`FederationConfig.
 * publicBaseUrl` itself, no extra path segment), consistent with how a single deployment commonly
 * plays more than one federated role off the same public origin (ActivityPub Actor gets its own
 * `/federation/actor` child path, OIDC Issuer and Trust Anchor Entity Identifier both stay at the
 * bare origin, per OpenID Federation's own convention of inserting `/.well-known/openid-federation`
 * between the origin and any path component -- here there is no path component to insert before).
 */
object TrustAnchorConfig {
    /** This server's own Trust Anchor Entity Identifier -- also doubles as the `iss`/`sub` of its self-signed Entity Configuration. */
    val entityUri: String get() = FederationConfig.publicBaseUrl

    /** Where this server publishes its self-signed Entity Configuration, per RFC 9678 §5. */
    val wellKnownUri: String get() = "$entityUri/.well-known/openid-federation"

    /** Where a Subordinate Statement about one specific pool member can be fetched (`?sub=<uri>`), advertised as `metadata.federation_entity.federation_fetch_endpoint` in the Entity Configuration. */
    val fetchEndpointUri: String get() = "$entityUri/federation/trust-anchor/fetch"
}

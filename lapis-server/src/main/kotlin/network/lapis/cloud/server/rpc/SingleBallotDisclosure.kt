package network.lapis.cloud.server.rpc

/**
 * V1.9.46 -- one rule for every secret/anonymous vote: single ballots are never delivered
 * (owner decisions 2026-10-03 for consensus and elections). Pure, no DB access.
 */
internal fun singleBallotsDisclosable(secret: Boolean): Boolean = !secret

package network.lapis.cloud.client.livekit

/**
 * V1.9.304 -- the acceptance rule of the room-poll nudge, pure and DOM-free so `jsTest` pins it: the packet counts ONLY when it was sent by the
 * server, i.e. when it carries NO sending participant. A packet from a participant is a forgery (anybody present can publish on any topic) and is
 * dropped. The payload is never looked at, whatever the sender wrote.
 */
internal fun roomPollNudgeAccepted(fromParticipant: Boolean): Boolean = !fromParticipant

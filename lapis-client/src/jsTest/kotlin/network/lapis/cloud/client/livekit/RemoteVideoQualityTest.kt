package network.lapis.cloud.client.livekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.71 -- `requestRemoteVideoQuality` only ever sends `setVideoQuality` for a subscribed publication that has the method. */
class RemoteVideoQualityTest {
    private fun publication(
        subscribed: Boolean,
        withMethod: Boolean = true,
        record: MutableList<Int>? = null,
        throwing: Boolean = false,
    ): TrackPublication {
        val pub: dynamic = js("({})")
        pub.isSubscribed = subscribed
        pub.trackSid = "TR_1"
        pub.source = "camera"
        if (withMethod) {
            pub.setVideoQuality = { quality: Int ->
                if (throwing) error("sdk failure")
                record?.add(quality)
                Unit
            }
        }
        return pub.unsafeCast<TrackPublication>()
    }

    @Test
    fun aSubscribedPublication_getsTheQuality_exactlyOnceAndOnlyInRange() {
        val seen = mutableListOf<Int>()
        val pub = publication(subscribed = true, record = seen)
        assertTrue(requestRemoteVideoQuality(pub, 0))
        assertTrue(requestRemoteVideoQuality(pub, 1))
        assertTrue(requestRemoteVideoQuality(pub, 2))
        assertFalse(requestRemoteVideoQuality(pub, 3))
        assertFalse(requestRemoteVideoQuality(pub, -1))
        assertEquals(listOf(0, 1, 2), seen)
    }

    @Test
    fun anUnsubscribedPublication_andOneWithoutTheMethod_areLeftAlone() {
        val seen = mutableListOf<Int>()
        assertFalse(requestRemoteVideoQuality(publication(subscribed = false, record = seen), 1))
        assertFalse(
            requestRemoteVideoQuality(publication(subscribed = true, withMethod = false), 1),
            "a local publication has no such method",
        )
        assertTrue(seen.isEmpty())
    }

    @Test
    fun anSdkFailure_isSwallowed() {
        assertFalse(requestRemoteVideoQuality(publication(subscribed = true, throwing = true), 1))
    }

    @Test
    fun theAdaptationCanBeSwitchedOff_byOneConstant() {
        assertTrue(FLOAT_ADAPTS_REMOTE_QUALITY, "on by default; set to false to keep every picture at the negotiated quality")
    }
}

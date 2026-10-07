package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.71 -- which picture is big, which go into the strip, what folds into "+N", which quality each needs. */
class ConferenceFloatSelectionTest {
    private fun remote(
        key: String,
        video: Boolean = true,
        spoke: Long = 0L,
    ) = FloatSourceInfo(key, isLocal = false, isScreenShare = false, hasVideo = video, lastSpokeAtMs = spoke)

    private fun self(video: Boolean = true) =
        FloatSourceInfo("me", isLocal = true, isScreenShare = false, hasVideo = video, lastSpokeAtMs = 0L)

    private fun share(key: String = "share") =
        FloatSourceInfo(key, isLocal = false, isScreenShare = true, hasVideo = true, lastSpokeAtMs = 0L)

    private val fresh = FloatSpeakerMemo(null, 0L)

    private fun select(
        sources: List<FloatSourceInfo>,
        size: FloatSize = FloatSize.MEDIUM,
        cameraOn: Boolean = true,
        now: Long = 100_000L,
        memo: FloatSpeakerMemo = fresh,
    ) = floatSelectionOf(sources, size, cameraOn, now, memo)

    @Test
    fun aForeignScreenShareHasPriorityOverEveryPerson() {
        val (sel, _) = select(listOf(remote("a", spoke = 99_900), share(), self()))
        assertEquals("share", sel.mainKey)
        assertTrue("a" in sel.stripKeys)
    }

    @Test
    fun theSpeaker_isTheBigPicture_otherwiseTheFirstRemotePerson() {
        val (quiet, _) = select(listOf(remote("a"), remote("b")))
        assertEquals("a", quiet.mainKey)
        val (speaking, _) = select(listOf(remote("a"), remote("b", spoke = 99_800)))
        assertEquals("b", speaking.mainKey)
    }

    @Test
    fun theFirstRemotePersonWithVideo_isPreferredToANameTile() {
        val (sel, _) = select(listOf(remote("a", video = false), remote("b")))
        assertEquals("b", sel.mainKey)
        val (onlyNames, _) = select(listOf(remote("a", video = false)))
        assertEquals("a", onlyNames.mainKey, "a name tile is still shown")
    }

    @Test
    fun hysteresis_aNewSpeakerTakesOverOnlyAfterTwoSeconds() {
        var memo = fresh
        val a = remote("a", spoke = 100_000)
        // A is the big picture
        val (sel, m1) = select(listOf(a, remote("b")), now = 100_000, memo = memo)
        assertEquals("a", sel.mainKey)
        memo = m1
        // B starts to speak: A stays for the first two seconds
        val b = { at: Long -> remote("b", spoke = at) }
        var now = 101_000L
        val first = select(listOf(remote("a"), b(now)), now = now, memo = memo)
        assertEquals("a", first.first.mainKey, "just noticed")
        memo = first.second
        now = 102_000L
        val second = select(listOf(remote("a"), b(now)), now = now, memo = memo)
        assertEquals("a", second.first.mainKey, "one second of leading is not enough")
        memo = second.second
        now = 103_000L
        val third = select(listOf(remote("a"), b(now)), now = now, memo = memo)
        assertEquals("b", third.first.mainKey, "two seconds of uninterrupted leading")
    }

    @Test
    fun hysteresis_aShortInterjectionDoesNotChangeThePicture() {
        var memo = select(listOf(remote("a", spoke = 100_000), remote("b")), now = 100_000).second
        // B blips once
        val blip = select(listOf(remote("a"), remote("b", spoke = 101_000)), now = 101_000, memo = memo)
        memo = blip.second
        assertEquals("a", blip.first.mainKey)
        // two seconds later nobody speaks (B's blip is long over)
        val quiet = select(listOf(remote("a"), remote("b", spoke = 101_000)), now = 104_000, memo = memo)
        memo = quiet.second
        assertEquals("a", quiet.first.mainKey)
        // B speaks again: the clock starts anew, so no switch at once
        val again = select(listOf(remote("a"), remote("b", spoke = 104_500)), now = 104_500, memo = memo)
        assertEquals("a", again.first.mainKey)
    }

    @Test
    fun aloneTheOwnPictureIsTheBigPicture_onlyWithTheCameraOn() {
        assertEquals("me", select(listOf(self())).first.mainKey)
        assertNull(select(listOf(self()), cameraOn = false).first.mainKey, "camera off: no stale picture")
        assertNull(select(listOf(self(video = false))).first.mainKey, "no video, no own picture")
    }

    @Test
    fun cameraOff_hasNoOwnPictureInStripOrInset() {
        val people = listOf(remote("a"), remote("b"), self())
        assertTrue("me" !in select(people, cameraOn = false).first.stripKeys)
        assertNull(select(people, size = FloatSize.SMALL, cameraOn = false).first.insetKey)
        assertEquals("me", select(people, size = FloatSize.SMALL).first.insetKey)
    }

    @Test
    fun small_hasNoStrip_andCountsTheRestInPlusN() {
        val people = listOf(remote("a"), remote("b"), remote("c"), remote("d"), self())
        val (sel, _) = select(people, size = FloatSize.SMALL)
        assertTrue(sel.stripKeys.isEmpty())
        assertEquals("me", sel.insetKey)
        assertEquals(3, sel.hiddenCount, "b, c, d")
    }

    @Test
    fun mediumAndLarge_showAtMostThreeInTheStrip_theOwnPictureTakesAPlace() {
        val people = listOf(remote("a"), remote("b"), remote("c"), remote("d"), remote("e"), self())
        for (size in listOf(FloatSize.MEDIUM, FloatSize.LARGE)) {
            val (sel, _) = select(people, size = size)
            assertEquals(3, sel.stripKeys.size)
            assertEquals("me", sel.stripKeys.last())
            assertEquals(2, sel.stripKeys.count { it != "me" })
            assertEquals(2, sel.hiddenCount, "e of five others minus the two shown: c... counted, got ${sel.hiddenCount}")
            assertNull(sel.insetKey)
        }
        val (withoutSelf, _) = select(people.filter { !it.isLocal })
        assertEquals(3, withoutSelf.stripKeys.size)
        assertEquals(1, withoutSelf.hiddenCount)
    }

    @Test
    fun qualityPlan_forFullFloatAndBar() {
        val sel = FloatSelection("a", listOf("b"), null, 0)
        val keys = listOf("a", "b", "c")
        assertEquals(mapOf("a" to 2, "b" to 2, "c" to 2), floatQualityPlan(sel, keys, DockPresentation.FULL))
        assertEquals(mapOf("a" to 1, "b" to 0, "c" to 0), floatQualityPlan(sel, keys, DockPresentation.FLOAT))
        assertEquals(mapOf("a" to 0, "b" to 0, "c" to 0), floatQualityPlan(sel, keys, DockPresentation.BAR))
        assertEquals(mapOf("a" to 0), floatQualityPlan(null, listOf("a"), DockPresentation.FLOAT))
    }
}

package network.lapis.cloud.client

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** V1.9.71 -- the storage codec (whitelist parser) and the one store: robust against missing, throwing and hostile storage. */
class ConferenceFloatStoreTest {
    private class FakeStorage(
        initial: String? = null,
        private val failRead: Boolean = false,
        private val failWrite: Boolean = false,
    ) : StorageLike {
        var value: String? = initial
        var removed = 0
        val written = mutableListOf<String>()

        override fun getItem(key: String): String? {
            if (failRead) error("storage blocked")
            return if (key == FLOAT_STORAGE_KEY) value else null
        }

        override fun setItem(
            key: String,
            value: String,
        ) {
            if (failWrite) error("quota")
            written += value
            this.value = value
        }

        override fun removeItem(key: String) {
            removed++
            value = null
        }
    }

    @AfterTest
    fun cleanup() {
        ConferenceFloatStore.storageForTest = null
    }

    private val sample = FloatPreference(FloatMode.BAR, FloatGeometry(2, 40, 50, 480))

    @Test
    fun codec_roundTrips_andTheDefaultHasTheDocumentedShape() {
        assertEquals("v1|float|0|16|16|352", encodeFloatPreference(DEFAULT_FLOAT_PREFERENCE))
        val decoded = assertIs<FloatDecode.Ok>(decodeFloatPreference(encodeFloatPreference(sample)))
        assertEquals(sample, decoded.pref)
        for (corner in 0..3) {
            val p = FloatPreference(FloatMode.FLOAT, FloatGeometry(corner, 0, 4000, 640))
            assertEquals(p, assertIs<FloatDecode.Ok>(decodeFloatPreference(encodeFloatPreference(p))).pref)
        }
    }

    @Test
    fun codec_absent_forNullAndEmpty() {
        assertEquals(FloatDecode.Absent, decodeFloatPreference(null))
        assertEquals(FloatDecode.Absent, decodeFloatPreference(""))
    }

    @Test
    fun codec_invalid_forEveryHostileShape() {
        val hostile =
            listOf(
                "v1|float|0|16|16", // truncated
                "v1|float|0|16|16|35", // truncated width
                "hello",
                "v1|float|0|-1|16|352", // negative
                "v1|float|0|4001|16|352", // dx too large
                "v1|float|0|16|4001|352",
                "v1|float|0|16|16|255", // width below the minimum
                "v1|float|0|16|16|641", // width above the maximum
                "v1|float|4|16|16|352", // corner out of range
                "v1|float|0|NaN|16|352",
                "null",
                "undefined",
                "v2|float|0|16|16|352", // foreign version
                "v1|float|0|16|16|352|1", // seven fields
                "v1|window|0|16|16|352", // unknown mode
                "v1|float|0|١٦|16|352", // arabic-indic digits
                " v1|float|0|16|16|352",
                "v1|float|0|16|16|352\n",
                "v1|FLOAT|0|16|16|352",
            )
        for (raw in hostile) assertEquals(FloatDecode.Invalid, decodeFloatPreference(raw), "must reject: $raw")
    }

    @Test
    fun everyWrittenValue_matchesThePattern() {
        val extreme = FloatPreference(FloatMode.FLOAT, FloatGeometry(9, 99999, -5, 10))
        assertTrue(FLOAT_STORAGE_PATTERN.matches(encodeFloatPreference(extreme)), "encoder clamps into the pattern")
        assertTrue(FLOAT_STORAGE_PATTERN.matches(encodeFloatPreference(sample)))
        val storage = FakeStorage()
        ConferenceFloatStore.storageForTest = storage
        ConferenceFloatStore.save(sample)
        ConferenceFloatStore.save(extreme)
        assertEquals(2, storage.written.size)
        storage.written.forEach { assertTrue(FLOAT_STORAGE_PATTERN.matches(it), "written: $it") }
    }

    @Test
    fun load_returnsTheStoredValue_orTheDefault() {
        ConferenceFloatStore.storageForTest = FakeStorage(encodeFloatPreference(sample))
        assertEquals(sample, ConferenceFloatStore.load())
        ConferenceFloatStore.storageForTest = FakeStorage(null)
        assertEquals(DEFAULT_FLOAT_PREFERENCE, ConferenceFloatStore.load())
    }

    @Test
    fun load_ofGarbage_removesTheKey_andReturnsTheDefault() {
        val storage = FakeStorage("v1|float|0|16|16|99999")
        ConferenceFloatStore.storageForTest = storage
        assertEquals(DEFAULT_FLOAT_PREFERENCE, ConferenceFloatStore.load())
        assertEquals(1, storage.removed, "an invalid value is deleted")
    }

    @Test
    fun aStorageThatThrows_neverCrashes() {
        ConferenceFloatStore.storageForTest = FakeStorage(failRead = true, failWrite = true)
        assertEquals(DEFAULT_FLOAT_PREFERENCE, ConferenceFloatStore.load())
        ConferenceFloatStore.save(sample) // must not throw
    }
}

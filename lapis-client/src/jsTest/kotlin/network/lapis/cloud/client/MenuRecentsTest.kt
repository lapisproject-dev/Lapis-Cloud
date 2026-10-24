package network.lapis.cloud.client

import kotlinx.browser.localStorage
import org.w3c.dom.get
import org.w3c.dom.set
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** V1.9.203: "Zuletzt besucht" of the menu search -- order, duplicates, the cap and a storage that fails or holds rubbish. */
class MenuRecentsTest {
    @BeforeTest
    fun setUp() {
        localStorage.removeItem(MenuRecents.KEY)
    }

    @AfterTest
    fun tearDown() {
        localStorage.removeItem(MenuRecents.KEY)
    }

    @Test
    fun push_putsTheNewestFirst_movesARepeatToTheFront_andCapsAtFive() {
        assertEquals(listOf("/a"), MenuRecents.push(emptyList(), "/a"))
        assertEquals(listOf("/b", "/a"), MenuRecents.push(listOf("/a"), "/b"))
        assertEquals(listOf("/a", "/b", "/c"), MenuRecents.push(listOf("/b", "/a", "/c"), "/a"))
        val full = listOf("/1", "/2", "/3", "/4", "/5")
        assertEquals(listOf("/6", "/1", "/2", "/3", "/4"), MenuRecents.push(full, "/6"))
        assertEquals(5, MenuRecents.push(full, "/6").size)
    }

    @Test
    fun remember_thenLoad_roundTrips_inOrder() {
        MenuRecents.remember("/contributions")
        MenuRecents.remember("/documents")
        MenuRecents.remember("/contributions")
        assertEquals(listOf("/contributions", "/documents"), MenuRecents.load())
    }

    @Test
    fun load_withNothingStored_isEmpty() {
        assertNull(localStorage[MenuRecents.KEY])
        assertEquals(emptyList(), MenuRecents.load())
    }

    @Test
    fun load_withBrokenJson_orTheWrongShape_isEmpty_andRememberRepairsIt() {
        localStorage[MenuRecents.KEY] = "{not json"
        assertEquals(emptyList(), MenuRecents.load())
        localStorage[MenuRecents.KEY] = "{\"a\":1}"
        assertEquals(emptyList(), MenuRecents.load())
        localStorage[MenuRecents.KEY] = "[1,2,3]"
        assertEquals(emptyList(), MenuRecents.load())
        MenuRecents.remember("/documents")
        assertEquals(listOf("/documents"), MenuRecents.load())
    }

    @Test
    fun load_dropsEntriesThatAreNotRoutes_andCapsAStoredList() {
        localStorage[MenuRecents.KEY] = "[\"javascript:alert(1)\",\"/a\",\"/a\",\"/b\",\"/c\",\"/d\",\"/e\",\"/f\"]"
        assertEquals(listOf("/a", "/b", "/c", "/d", "/e"), MenuRecents.load())
    }

    @Test
    fun aStorageThatThrows_neverBreaksAnything() {
        val proto = js("Storage.prototype")
        val originalGet = proto.getItem
        val originalSet = proto.setItem
        try {
            proto.getItem = js("(function() { throw new Error('blocked'); })")
            proto.setItem = js("(function() { throw new Error('blocked'); })")
            assertEquals(emptyList(), MenuRecents.load())
            MenuRecents.remember("/documents")
            assertEquals(emptyList(), MenuRecents.load())
        } finally {
            proto.getItem = originalGet
            proto.setItem = originalSet
        }
    }
}

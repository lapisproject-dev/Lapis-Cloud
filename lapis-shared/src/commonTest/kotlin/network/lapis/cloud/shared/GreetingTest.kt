package network.lapis.cloud.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class GreetingTest {
    @Test
    fun messageIsNotBlank() {
        assertEquals("Hello from Lapis Cloud", Greeting.message())
    }
}

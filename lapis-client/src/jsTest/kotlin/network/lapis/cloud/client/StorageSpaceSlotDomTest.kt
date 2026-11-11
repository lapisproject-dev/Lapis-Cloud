package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.StorageSpaceDto
import network.lapis.cloud.shared.domain.StorageSpaceLevel
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The free-space bar on the video conference and encounter screens: ADMIN only, one call, no error text when the call fails. */
class StorageSpaceSlotDomTest {
    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "m-1",
            displayName = "Person",
            role = role,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    private class FakeStorage(
        private val answer: () -> StorageSpaceDto,
    ) : StorageSpaceRpc {
        var calls = 0

        override suspend fun storageSpace(): StorageSpaceDto {
            calls++
            return answer()
        }
    }

    private fun run(
        id: String,
        role: AccountRole,
        storage: StorageSpaceRpc,
        block: suspend (HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session(role))
            mountedForm(id) { root, element ->
                root.storageSpaceSlot(session(role), storage)
                delay(80)
                block(element())
            }
        }

    @Test
    fun adminSeesTheBarWithTheLink(): Promise<Unit> {
        val fake = FakeStorage { StorageSpaceDto(StorageSpaceLevel.INFO, 150_000_000_000L, 1_000_000_000_000L) }
        return run("slot-admin", AccountRole.ADMIN, fake) { el ->
            awaitUntil("the bar") { el.querySelector(".lapis-storage") != null }
            assertTrue(assertNotNull(el.querySelector(".lapis-storage")).textContent.orEmpty().contains("Belegt 850,0 GB von 1,0 TB"))
            assertNotNull(el.querySelector(".lapis-storage__link"), "link to the document page")
            assertEquals(1, fake.calls)
        }
    }

    @Test
    fun aMemberGetsNothingAndNoCallIsMade(): Promise<Unit> {
        val fake = FakeStorage { error("must not be called") }
        return run("slot-member", AccountRole.MEMBER, fake) { el ->
            assertNull(el.querySelector(".lapis-storage-slot"))
            assertNull(el.querySelector(".lapis-storage"))
            assertEquals(0, fake.calls)
        }
    }

    @Test
    fun aFailedCallRemovesTheSlotWithoutAnErrorText(): Promise<Unit> {
        val fake = FakeStorage { error("refused") }
        return run("slot-fail", AccountRole.ADMIN, fake) { el ->
            awaitUntil("the slot is gone") { el.querySelector(".lapis-storage-slot") == null }
            assertNull(el.querySelector(".lapis-storage"))
            assertEquals(1, fake.calls)
        }
    }
}

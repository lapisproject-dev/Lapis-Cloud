package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentFolderDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.domain.StorageSpaceDto
import network.lapis.cloud.shared.domain.StorageSpaceLevel
import network.lapis.cloud.shared.rpc.IDocumentService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.263/V1.9.273 -- the storage-space bar on the document page: ADMIN only, all four states, before the folder list. */
class DocumentsStorageSpaceBarDomTest {
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
            val listFoldersRoute = routeOf { rpcService<IDocumentService>().listFolders() }
            withFetchStub(
                respond = { request ->
                    if (!request.isRpc) {
                        StubResponse()
                    } else {
                        request.answerWith(
                            if (request.rpcRoute == listFoldersRoute) {
                                jsonOf(ListSerializer(DocumentFolderDto.serializer()), emptyList())
                            } else {
                                "[]"
                            },
                        )
                    }
                },
            ) {
                mountedForm(id) { root, element ->
                    renderDocumentsScreen(root, storage)
                    delay(80)
                    block(element())
                }
            }
        }

    private fun HTMLElement.line(): HTMLElement? = querySelector(".lapis-storage") as HTMLElement?

    private fun checkLine(
        level: StorageSpaceLevel,
        headline: String,
        cssClass: String,
    ): Promise<Unit> {
        val dto =
            if (level == StorageSpaceLevel.UNKNOWN) {
                StorageSpaceDto(level = level, freeBytes = null, totalBytes = null)
            } else {
                StorageSpaceDto(level = level, freeBytes = 150_000_000_000L, totalBytes = 1_000_000_000_000L)
            }
        return run("documents-storage-$level", AccountRole.ADMIN, FakeStorage { dto }) { el ->
            awaitUntil("the bar for $level") { el.line() != null }
            val bar = assertNotNull(el.line())
            assertTrue(bar.classList.contains(cssClass), bar.className)
            assertTrue(bar.textContent.orEmpty().contains(headline), bar.textContent.orEmpty())
            assertNotNull(bar.querySelector("[role=img]"))
            assertNull(bar.querySelector("a"), "no link on the document page")
            assertEquals("true", assertNotNull(bar.querySelector(".fas")).getAttribute("aria-hidden"))
            val folderTitle =
                assertNotNull(
                    el.allOf("*").firstOrNull { it.children.length == 0 && it.textContent?.trim() == "Ordner" },
                    "no 'Ordner' title",
                )
            val following = bar.compareDocumentPosition(folderTitle).toInt() and 4
            assertTrue(following != 0, "the bar stands before the folder list")
        }
    }

    @Test
    fun adminSeesTheBar_info(): Promise<Unit> = checkLine(StorageSpaceLevel.INFO, "Belegt 850,0 GB von 1,0 TB", "lapis-storage--info")

    @Test
    fun adminSeesTheBar_hint(): Promise<Unit> = checkLine(StorageSpaceLevel.HINT, "Speicherplatz knapp", "lapis-storage--hint")

    @Test
    fun adminSeesTheBar_warning(): Promise<Unit> = checkLine(StorageSpaceLevel.WARNING, "Speicherplatz fast voll", "lapis-storage--warning")

    @Test
    fun adminSeesTheBar_unknown(): Promise<Unit> =
        checkLine(StorageSpaceLevel.UNKNOWN, "Der freie Platz lässt sich derzeit nicht messen.", "lapis-storage--unknown")

    @Test
    fun boardSeesNoLine_andTheRpcIsNotCalled(): Promise<Unit> {
        val storage = FakeStorage { StorageSpaceDto(StorageSpaceLevel.INFO, 1L, 2L) }
        return run("documents-storage-board", AccountRole.BOARD, storage) { el ->
            assertNull(el.line())
            assertEquals(0, storage.calls)
        }
    }

    @Test
    fun anRpcFailure_leavesNoLineAndNoToast(): Promise<Unit> =
        run("documents-storage-failure", AccountRole.ADMIN, FakeStorage { error("boom") }) { el ->
            assertNull(el.line())
            assertFalse(el.textContent.orEmpty().contains("Speicherplatz"))
            assertNull(kotlinx.browser.document.querySelector(".toast, .lapis-toast"))
        }
}

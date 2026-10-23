package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.DocumentVersionDto
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.189: the icon-only download action of the version table and the "not yet downloaded" badge. */
class DocumentsDownloadButtonDomTest {
    private fun version(
        fileName: String,
        downloads: Long = 0,
    ) = DocumentVersionDto(
        id = "v1",
        documentId = "doc",
        versionNumber = 1,
        fileName = fileName,
        mimeType = "application/pdf",
        fileSizeBytes = 1024,
        checksumSha256 = "x",
        uploadedBy = "acct-1",
        uploadedByDisplayName = "Anna Muster",
        uploadedAt = LocalDateTime(2026, 2, 1, 9, 0),
        changeNote = null,
        downloadCount = downloads,
    )

    @Test
    fun downloadButton_isIconOnly_andNamesVersionAndFile() {
        withMountedRoot("documents-download-button") { root, element ->
            root.versionDownloadButton("/x", version("x.pdf"))
            val button = element().querySelector("button") as HTMLElement
            assertEquals("Version v1 von x.pdf herunterladen", button.getAttribute("aria-label"))
            assertEquals("Version v1 von x.pdf herunterladen", button.getAttribute("title"))
            assertEquals("", button.textContent.orEmpty().trim(), "no visible text")
            assertFalse(button.getAttribute("aria-label").orEmpty().contains("###KvI18nS###"))
            assertTrue(button.querySelector("i.fa-download") != null, "the download icon")
        }
    }

    @Test
    fun downloadButton_hostileFileName_cannotForgeAnI18nMarker() {
        withMountedRoot("documents-download-button-hostile") { root, element ->
            root.versionDownloadButton("/x", version("###KvI18nS###Passwort"))
            val button = element().querySelector("button") as HTMLElement
            assertFalse(button.getAttribute("title").orEmpty().contains("###KvI18nS###"))
            assertFalse(button.getAttribute("aria-label").orEmpty().contains("###KvI18nS###"))
        }
    }

    @Test
    fun downloadCount_zeroIsABadge_aRealCountStaysQuietText() {
        assertEquals("badge text-bg-light small", downloadCountClasses(0))
        assertEquals("text-muted small", downloadCountClasses(3))
    }
}

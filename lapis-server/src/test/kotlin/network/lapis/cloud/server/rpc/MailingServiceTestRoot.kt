package network.lapis.cloud.server.rpc

import java.io.File

/** V1.9.234 -- shared attachment root for tests that build a [MailingService] without touching attachment files. */
internal val TEST_ATTACHMENT_ROOT: File by lazy {
    java.nio.file.Files
        .createTempDirectory("lapis-test-attachments")
        .toFile()
        .also { it.deleteOnExit() }
}

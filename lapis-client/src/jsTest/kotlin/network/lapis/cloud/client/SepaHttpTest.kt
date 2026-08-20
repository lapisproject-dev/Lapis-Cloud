package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * V1.2.2 SEPA-Client-UI wave -- covers the pure, DOM-independent URL builder in [SepaHttp], same
 * scope posture as [MailmergeHttpTest]/[BackupHttpTest] (no DOM/rendering test harness exists in
 * this module).
 */
class SepaHttpTest {
    @Test
    fun batchFileUrl_buildsTheExactHttpRoutePath() {
        assertEquals("/api/sepa/batches/batch-1/file.xml", SepaHttp.batchFileUrl("batch-1"))
    }
}

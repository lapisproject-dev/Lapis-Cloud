package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Client-UI wave for GitHub Issue #5 -- covers the pure, DOM-independent URL builder in
 * [DunningHttp], same scope posture as [SepaHttpTest]/[MailmergeHttpTest]/[BackupHttpTest] (no
 * DOM/rendering test harness exists in this module; [DunningHttp.submitNoticePreviewPdf] builds
 * and submits a real `<form>` element, so it is exercised only through the pure URL/path it is
 * given -- covering `noticePdfUrl` is what catches a route-path typo before it becomes a 404 in a
 * newly opened tab, see review finding).
 */
class DunningHttpTest {
    @Test
    fun noticePdfUrl_buildsTheExactHttpRoutePath() {
        assertEquals("/api/dunning/notices/notice-1/notice.pdf", DunningHttp.noticePdfUrl("notice-1"))
    }
}

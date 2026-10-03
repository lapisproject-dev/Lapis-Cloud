// Mocha's default per-test timeout is 2000 ms. The DOM tests wait for real (stubbed) async work -- several mounted
// forms with 250-600 ms stub delays, failed-load retries -- and that is fine on a fast machine but exceeds 2 s on the
// slower GitHub runners ("Timeout of 2000ms exceeded", found in CI 2026-09-21). Every wait inside the tests is
// bounded by its own poll limit, so a generous ceiling here only turns a hung test into a slower failure.
//
// 2026-10-03: raised from 30 s to 120 s. One `awaitUntil` may take 15 s of polls (more wall-clock time on a slow
// runner), and a Mocha timeout is the WORST way for a test to fail: Mocha reports "Error: Timeout ... exceeded" without
// saying where the test stood, and only stops waiting -- the test's coroutine runs on into the next test. `formTest`
// therefore has its own deadline (FORM_TEST_DEADLINE_MS = 100 s in FormTestSupport.kt), which cancels the body cleanly and
// names the wait it was in; this ceiling must stay above it.
config.client = config.client || {};
config.client.mocha = config.client.mocha || {};
config.client.mocha.timeout = 120000;

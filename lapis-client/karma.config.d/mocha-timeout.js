// Mocha's default per-test timeout is 2000 ms. The DOM tests wait for real (stubbed) async work -- several mounted
// forms with 250-600 ms stub delays, failed-load retries -- and that is fine on a fast machine but exceeds 2 s on the
// slower GitHub runners ("Timeout of 2000ms exceeded", found in CI 2026-09-21). Every wait inside the tests is
// bounded by its own poll limit, so a generous ceiling here only turns a hung test into a slower failure.
config.client = config.client || {};
config.client.mocha = config.client.mocha || {};
config.client.mocha.timeout = 30000;

// CI runs on slow shared GitHub runners: a headless Chrome that is busy for a moment misses a Karma ping and the run
// ends with "Disconnected (0 times) reconnect failed before timeout of 2000ms (ping timeout)", marking whichever test was
// running as failed (seen on 2026-10-03 on four different DOM tests, always green on a rerun). Karma's default disconnect
// timeout is 2000 ms; give the browser time to answer and allow one reconnect before the run is declared dead.
config.browserDisconnectTimeout = 30000;
config.browserDisconnectTolerance = 2;
config.browserNoActivityTimeout = 300000;
config.pingTimeout = 60000;

// Raises karma-mocha's per-test timeout from its 2000ms default. Root cause of the flake this
// fixes: PaymentReferenceCodeTest.everySingleCharacterSubstitutionAtEveryPositionIsDetectedForASampleOfPayloads
// (~186,000 PaymentReferenceCode.isValid() calls across 1,000 sampled payloads) completes in
// single-digit milliseconds in isolation (see its own JUnit XML "time" attribute on a passing run),
// so the 2000ms timeout is not being exceeded by the test's own CPU work -- it is exceeded when the
// whole ChromeHeadless/Karma/Mocha process is starved of CPU by the dozens of concurrent Gradle
// workers, JVMs, and webpack/kotlinc processes a full `./gradlew clean check` spins up across this
// multi-module project. Raising the timeout (not shrinking the sample) preserves the test's full
// assurance value -- see PaymentReferenceCodeTest's own class KDoc on why the sample size is chosen
// for runtime, not statistical power, against an algebraically-proven property.
//
// Picked up automatically by the Kotlin Gradle plugin's karma.config.d convention (any *.js file
// here is appended to the generated karma.conf.js) -- see KotlinKarma.appendFromConfigDir.
config.set({
    client: {
        mocha: {
            timeout: 15000,
        },
    },
});

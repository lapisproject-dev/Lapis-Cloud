package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * Regression guard for [StagingSeedData]'s class-KDoc "lock 3" and
 * `deploy/production-staging/README.adoc` "Staging seed mechanism": `deploy/production/` (PdV) and
 * `deploy/production-elb/` (ELB) must never forward `LAPIS_STAGING_MODE`/`LAPIS_STAGING_SEED_PASSWORD`
 * into their containers. Unlike locks 1, 2 and 4 (covered by [StagingSeedConfigSafetyTest] /
 * [StagingSeedDataTest]), this one was pure convention with no regression test -- a later wave that
 * copies a new env block from `production-staging/docker-compose.yml` into one of these two files
 * (exactly the "trimmed copy of ../production-elb/" relationship the files document about
 * themselves) could carry the two staging-only lines along without anything in the build noticing.
 */
class StagingEnvNotForwardedInProductionComposeTest :
    FunSpec({
        // Test working directory is the `lapis-server` module root -- repo root is one level up.
        val repoRoot = File("..").canonicalFile

        listOf(
            "deploy/production/docker-compose.yml",
            "deploy/production-elb/docker-compose.yml",
        ).forEach { relativePath ->
            test("$relativePath does not forward LAPIS_STAGING_ vars into the container") {
                val composeFile = File(repoRoot, relativePath)
                check(composeFile.exists()) { "expected compose file at ${composeFile.absolutePath}" }
                composeFile.readText() shouldNotContain "LAPIS_STAGING_"
            }
        }
    })

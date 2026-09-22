package network.lapis.cloud.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * Regression guard for [StagingSeedData]'s class-KDoc "lock 3": a production compose file must never
 * forward `LAPIS_STAGING_MODE`/`LAPIS_STAGING_SEED_PASSWORD` into its container. Unlike locks 1, 2 and 4
 * (covered by [StagingSeedConfigSafetyTest] / [StagingSeedDataTest]), this one was pure convention with no
 * regression test -- a later wave that copies a staging-only env block into a real production compose file
 * could carry it along without anything in the build noticing.
 *
 * 2026-09-22: the real instance-specific compose files (`deploy/production/`, `deploy/production-elb/`,
 * `deploy/production-staging/`) moved out of this repo entirely into the private
 * `lapisproject-dev/Lapis-Cloud-Ops` companion repo (see the NOTE block in `deploy/example/README.adoc`) --
 * this test now checks the generic public template instead; the equivalent check for the real instances
 * must be run by hand (or scripted) against that private repo, not covered by this repo's CI.
 */
class StagingEnvNotForwardedInProductionComposeTest :
    FunSpec({
        // Test working directory is the `lapis-server` module root -- repo root is one level up.
        val repoRoot = File("..").canonicalFile

        listOf(
            "deploy/example/docker-compose.yml",
        ).forEach { relativePath ->
            test("$relativePath does not forward LAPIS_STAGING_ vars into the container") {
                val composeFile = File(repoRoot, relativePath)
                check(composeFile.exists()) { "expected compose file at ${composeFile.absolutePath}" }
                composeFile.readText() shouldNotContain "LAPIS_STAGING_"
            }
        }
    })

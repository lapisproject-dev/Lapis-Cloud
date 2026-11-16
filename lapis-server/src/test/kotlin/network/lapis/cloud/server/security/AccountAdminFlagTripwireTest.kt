package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import network.lapis.cloud.server.db.SourceScan
import java.io.File

/**
 * V155 -- source-scan tripwire: administrator rights are the flag `account.is_admin`, the base role is never ADMIN, and the raw columns
 * `account.role` / `is_admin` / `admin_changed_at` are touched ONLY by `security/AccountRights.kt` (everything else reads the effective
 * role through it). A stray `AccountTable.role eq ...` would silently lose every administrator, there is no compiler signal.
 */
class AccountAdminFlagTripwireTest :
    FunSpec({
        val allowed =
            setOf(
                "network/lapis/cloud/server/security/AccountRights.kt",
                // generated Exposed table: declares the columns
                "network/lapis/cloud/server/db/generated/AccountTable.kt",
            )
        val rawAccess =
            Regex("""AccountTable\.(role|isAdmin|adminChangedAt)\b""")
        // an explicit select-list may name the columns (the row is then read through AccountRights)
        val selectList = setOf("network/lapis/cloud/server/lupabb/LupaBbMemberLoader.kt")
        val roleAssign = Regex("""\[\s*(AccountTable\.)?role\s*]\s*=\s*AccountRole\.ADMIN\b""")

        test("no file outside AccountRights reads or writes account.role / is_admin / admin_changed_at") {
            val root = SourceScan.mainRoot()
            val hits =
                SourceScan.mainFiles().flatMap { file ->
                    val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                    if (relative in allowed || relative in selectList) return@flatMap emptyList()
                    val code = SourceScan.blank(file.readText())
                    rawAccess
                        .findAll(
                            code,
                        ).map { "$relative:${SourceScan.lineOf(text = code, index = it.range.first)} ${it.value}" }
                        .toList()
                }
            hits.shouldBeEmpty()
        }

        test("no source writes the literal role ADMIN into account.role") {
            val root = SourceScan.mainRoot()
            val hits =
                SourceScan.mainFiles().flatMap { file ->
                    val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
                    val code = SourceScan.blank(file.readText())
                    roleAssign.findAll(code).map { "$relative:${SourceScan.lineOf(text = code, index = it.range.first)}" }.toList()
                }
            hits.shouldBeEmpty()
        }

        test("every writer of is_admin stamps admin_changed_at in the same statement") {
            val code = SourceScan.blank(File(SourceScan.mainRoot(), "network/lapis/cloud/server/security/AccountRights.kt").readText())
            val flagWrite = Regex("""it\[isAdmin]\s*=""").findAll(code).count()
            val stamp = Regex("""it\[adminChangedAt]\s*=""").findAll(code).count()
            check(flagWrite == 1 && stamp == 1) { "setAdminFlagLocked must be the only writer: flag=$flagWrite stamp=$stamp" }
        }
    })

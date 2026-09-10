package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Welle V1.4.5.1.1 -- covers only the pure, DOM-free [bankStatementImportHash] function factored
 * out of `BankStatementImportScreen.kt`: same scope posture as every other screen test in this
 * module (no KVision render/mount harness exists here).
 */
class BankStatementImportScreenTest {
    @Test
    fun bankStatementImportHash_null_isBareRoute() {
        assertEquals("#/bank-import", bankStatementImportHash(null))
    }

    @Test
    fun bankStatementImportHash_withId_appendsQueryParam() {
        assertEquals("#/bank-import?import=abc", bankStatementImportHash("abc"))
    }

    @Test
    fun bankStatementImportHash_blankString_isTreatedAsNull() {
        assertEquals("#/bank-import", bankStatementImportHash(""))
        assertEquals("#/bank-import", bankStatementImportHash("   "))
    }
}

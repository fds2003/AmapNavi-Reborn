package tech.touka.amapnavi

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-10 静态契约检查（纯 JVM，不依赖 Android Binder）：
 * 读 HsaeClusterTransport.kt 源文件，断言 transact 的 catch 块
 * 不再同时清空 nav 和 vehicle（改为调用 onError 回调），
 * 且各 send 方法与 probe 调用点均传了对应的 onError 回调。
 */
class HsaeClusterTransportContractTest {

    private val source: String = locateSource()

    /** 从多个候选目录定位源文件（JVM 单测工作目录通常为 app 模块）。 */
    private fun locateSource(): String {
        val candidates = listOf(
            "app/src/main/java/tech/touka/amapnavi/cluster/HsaeClusterTransport.kt",
            "src/main/java/tech/touka/amapnavi/cluster/HsaeClusterTransport.kt"
        )
        for (c in candidates) {
            val f = File(c)
            if (f.exists()) return f.readText()
        }
        throw AssertionError("找不到 HsaeClusterTransport.kt（尝试：$candidates）")
    }

    /** 统计子串出现次数（indexOf 逐个推进，避免与 Kotlin 单字符 count 重载混淆）。 */
    private fun occurrencesOf(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (from >= 0) {
            val idx = haystack.indexOf(needle, from)
            if (idx < 0) break
            count++
            from = idx + 1
        }
        return count
    }

    @Test
    fun `catch块不再同时清空nav和vehicle`() {
        // transact 的 catch(Throwable) 块内不应再出现 "nav = null" 与 "vehicle = null" 同时出现
        // 提取 transact 函数体
        val transactStart = source.indexOf("private fun transact(")
        val transactEnd = source.indexOf("private class ParcelSinkAdapter")
        val transactBody = source.substring(transactStart, transactEnd)
        // 断言：transact 体内不应直接双清 nav/vehicle（改为 onError()）
        assertFalse(
            "transact catch 块不应直接双清 nav 和 vehicle",
            "nav = null" in transactBody && "vehicle = null" in transactBody
        )
        // 断言：catch 块调用了 onError()
        assertTrue("transact catch 块应调用 onError()", "onError()" in transactBody)
    }

    @Test
    fun `send方法传对应onError回调`() {
        // sendTbt/sendSpeed/sendEst/sendAdas 走 nav，传 { nav = null }
        // sendText 走 vehicle，传 { vehicle = null }
        // probe 走 nav，传 { nav = null }
        val navCallbackPattern = ") { nav = null }"
        val vehicleCallbackPattern = ") { vehicle = null }"
        val navCount = occurrencesOf(source, navCallbackPattern)
        val vehicleCount = occurrencesOf(source, vehicleCallbackPattern)
        // nav 回调应出现 5 次（sendTbt, sendSpeed, sendEst, sendAdas, probe）
        // vehicle 回调应出现 1 次（sendText）
        // close() 内的裸赋值不带 lambda 括号模式，不会被计入
        assertTrue(
            "nav onError 回调应出现 5 次（实际 $navCount）",
            navCount == 5
        )
        assertTrue(
            "vehicle onError 回调应出现 1 次（实际 $vehicleCount）",
            vehicleCount == 1
        )
    }

    @Test
    fun `transact签名含onError参数`() {
        assertTrue(
            "transact 签名应含 onError: () -> Unit 参数",
            "onError: () -> Unit = {}" in source
        )
    }
}

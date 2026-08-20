package com.github.izerui.imux.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryProbeTest {

    @Test
    fun `脚本对每个二进制输出一行 名称 制表符 路径`() {
        val script = buildProbeScript(listOf("gopls", "jdtls"))

        assertTrue("必须逐个查询", script.contains("'gopls'") && script.contains("'jdtls'"))
        assertTrue("必须用 command -v 而不是 which（后者在部分 shell 里不是内建）", script.contains("command -v"))
        assertTrue("失败时不能让整条脚本中断", script.contains("2>/dev/null"))
    }

    /** 二进制名来自本仓库的静态表，但拼进 shell 的东西一律当作不可信。 */
    @Test
    fun `二进制名被单引号包裹`() {
        assertTrue(buildProbeScript(listOf("a'b")).contains("""'a'\''b'"""))
    }

    @Test
    fun `解析输出得到路径映射`() {
        val output = "gopls\t/Users/demo/go/bin/gopls\njdtls\t/opt/homebrew/bin/jdtls\n"

        assertEquals(
            mapOf("gopls" to "/Users/demo/go/bin/gopls", "jdtls" to "/opt/homebrew/bin/jdtls"),
            parseProbeOutput(output),
        )
    }

    /** 未安装时 command -v 无输出，制表符后为空——这一条必须解析成 null 而不是空串。 */
    @Test
    fun `路径为空表示未安装`() {
        val parsed = parseProbeOutput("gopls\t\njdtls\t/opt/homebrew/bin/jdtls\n")

        assertTrue("键必须在", "gopls" in parsed)
        assertNull(parsed["gopls"])
        assertEquals("/opt/homebrew/bin/jdtls", parsed["jdtls"])
    }

    /** 登录 shell 会打印 profile 里的欢迎语、版本提示等噪音，不能让它污染结果。 */
    @Test
    fun `忽略不含制表符的噪音行`() {
        val output = "Welcome to zsh!\n\ngopls\t/usr/local/bin/gopls\nnvm: v22\n"

        assertEquals(mapOf("gopls" to "/usr/local/bin/gopls"), parseProbeOutput(output))
    }

    @Test
    fun `空输入得到空映射`() {
        assertEquals(emptyMap<String, String?>(), parseProbeOutput(""))
    }
}

package com.github.izerui.imux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DevScriptTest {

    @Test
    fun `非交互环境会明确拒绝运行`() {
        val process = ProcessBuilder("bash", "dev.sh")
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()

        assertTrue(process.waitFor() != 0)
        assertTrue(output.contains("需要交互式终端"))
    }

    @Test
    fun `只保留方向键交互入口`() {
        val script = File("dev.sh").readText()

        assertTrue("应逐键读取用户输入", script.contains("read -rsn1"))
        assertTrue("应处理向上方向键", script.contains("""'[A'"""))
        assertTrue("应处理向下方向键", script.contains("""'[B'"""))
        assertTrue("菜单应提示方向键和回车操作", script.contains("↑/↓ 选择，Enter 执行"))
        assertFalse("不再接受命令行参数", script.contains("""action="${'$'}{1:-}""""))
        assertFalse("不再提供数字输入提示", script.contains("请输入编号"))
        assertFalse("不再提供数字快捷选择", script.contains("1|2|3"))
        assertFalse("macOS Bash 3.2 不支持小数 read timeout", script.contains("-t 0.1"))
    }

    @Test
    fun `测试不再作为独立菜单选项`() {
        val script = File("dev.sh").readText()

        assertFalse("不应提供独立测试菜单项", script.contains("\"运行测试\""))
        assertFalse("不应保留独立测试 action", script.contains("\"test\" \"package\""))
        assertFalse("不应保留独立测试分支", script.contains("\n  test)\n"))
        assertTrue("打包插件仍应先运行测试", script.contains("./gradlew test buildPlugin"))
        assertTrue("三个菜单项应上移七行重绘", script.contains("printf '\\033[7A'"))
        assertFalse("不应保留四个菜单项的上移行数", script.contains("printf '\\033[8A'"))
    }
}

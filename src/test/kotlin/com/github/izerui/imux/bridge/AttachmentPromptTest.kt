package com.github.izerui.imux.bridge

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * Claude Code 粘贴时，只要某一行以图片扩展名结尾就走异步读图，读完会丢掉粘贴期间收到的回车，
 * 提交给终端的附件提示就停在输入框里不发出去（实测）。附件路径因此必须包在反引号里，让每一行都不以扩展名结尾。
 */
class AttachmentPromptTest {
    private val imageLineEnd = Regex("""\.(png|jpe?g|gif|webp)$""", RegexOption.IGNORE_CASE)

    @Test
    fun `所有语言的附件提示都把路径包在反引号里`() {
        File("src/main/resources/messages").listFiles { f -> f.name.startsWith("ImuxBundle") && f.extension == "properties" }!!.forEach { file ->
            val props = Properties().apply { file.reader(Charsets.UTF_8).use(::load) }
            for (key in listOf("bridge.attachment.line", "bridge.attachment.only")) {
                assertTrue("${file.name} $key", props.getProperty(key).contains("`{0}`"))
            }
        }
    }

    @Test
    fun `提交给终端的附件文字没有一行以图片扩展名结尾`() {
        val texts =
            listOf(
                BundleBridgeTexts.attachmentLine(".imux/attachments/2026-10-27/a.png"),
                BundleBridgeTexts.attachmentsOnly(listOf(".imux/attachments/2026-10-27/a.jpg")),
                BundleBridgeTexts.attachmentsOnly(listOf(".imux/a.jpeg", ".imux/b.webp")),
            )
        texts.flatMap { it.lines() }.forEach { line -> assertTrue(line, !imageLineEnd.containsMatchIn(line.trim())) }
    }
}

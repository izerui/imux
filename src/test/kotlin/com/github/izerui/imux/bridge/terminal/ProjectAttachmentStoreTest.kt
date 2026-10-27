package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.FetchResult
import com.github.izerui.imux.bridge.core.FetchedFile
import com.github.izerui.imux.bridge.core.InboundAttachment
import com.github.izerui.imux.bridge.core.PlaceResult
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.model.AgentType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

class ProjectAttachmentStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val now = LocalDateTime.of(2026, 10, 27, 15, 30, 12)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)

    private fun project(git: Boolean = true): Path {
        val root = temp.newFolder().toPath()
        if (git) Files.createDirectories(root.resolve(".git"))
        return root
    }

    private fun target(root: Path) = SessionTarget(root.toString(), "tab-1", AgentType.CLAUDE, "s1")

    private inner class FakeAttachment(
        private val content: ByteArray,
        private val name: String?,
        override val kind: AttachmentKind = AttachmentKind.IMAGE,
        private val result: FetchResult? = null,
    ) : InboundAttachment {
        var fetched = 0

        override suspend fun fetch(maxBytes: Long): FetchResult {
            fetched++
            result?.let { return it }
            val file = temp.newFile().toPath()
            Files.write(file, content)
            return FetchResult.Ok(FetchedFile(file, name))
        }
    }

    private fun store() = ProjectAttachmentStore(clock = { now })

    @Test
    fun `没有文件名时按类型、时间与文件头命名，存到日期目录并返回相对路径`() =
        runBlocking {
            val root = project()
            val result = store().place(target(root), FakeAttachment(png, null))
            assertEquals(PlaceResult.Saved(".imux/attachments/2026-10-27/image-153012.png"), result)
            assertTrue(Files.exists(root.resolve(".imux/attachments/2026-10-27/image-153012.png")))
        }

    @Test
    fun `有文件名时清理后使用，没有扩展名时补上识别出的扩展名，同名编号`() =
        runBlocking {
            val root = project()
            val s = store()
            assertEquals(PlaceResult.Saved(".imux/attachments/2026-10-27/报告.pdf"), s.place(target(root), FakeAttachment("%PDF-1".toByteArray(), "../报告.pdf", AttachmentKind.FILE)))
            assertEquals(PlaceResult.Saved(".imux/attachments/2026-10-27/报告-1.pdf"), s.place(target(root), FakeAttachment("%PDF-1".toByteArray(), "报告.pdf", AttachmentKind.FILE)))
            assertEquals(PlaceResult.Saved(".imux/attachments/2026-10-27/shot.png"), s.place(target(root), FakeAttachment(png, "shot")))
        }

    @Test
    fun `git 仓库只追加一次 exclude，非 git 仓库跳过`() =
        runBlocking {
            val root = project()
            Files.createDirectories(root.resolve(".git/info"))
            Files.writeString(root.resolve(".git/info/exclude"), "# local\n*.tmp")
            store().place(target(root), FakeAttachment(png, null))
            store().place(target(root), FakeAttachment(png, null))
            assertEquals(listOf("# local", "*.tmp", ".imux/"), Files.readAllLines(root.resolve(".git/info/exclude")))

            val plain = project(git = false)
            store().place(target(plain), FakeAttachment(png, null))
            assertFalse(Files.exists(plain.resolve(".git")))
        }

    @Test
    fun `删除早于 7 天的日期目录，保留其它目录`() =
        runBlocking {
            val root = project()
            val base = root.resolve(".imux/attachments")
            listOf("2026-10-19", "2026-10-20", "2026-10-26", "notes").forEach { Files.createDirectories(base.resolve(it)) }
            Files.writeString(base.resolve("2026-10-19/old.txt"), "x")
            store().place(target(root), FakeAttachment(png, null))
            assertFalse(Files.exists(base.resolve("2026-10-19")))
            assertTrue(Files.exists(base.resolve("2026-10-20")))
            assertTrue(Files.exists(base.resolve("2026-10-26")))
            assertTrue(Files.exists(base.resolve("notes")))
        }

    @Test
    fun `取回过大或失败时原样返回，不创建目录`() =
        runBlocking {
            val root = project()
            assertEquals(PlaceResult.TooLarge, store().place(target(root), FakeAttachment(png, null, result = FetchResult.TooLarge)))
            assertEquals(PlaceResult.Failed("boom"), store().place(target(root), FakeAttachment(png, null, result = FetchResult.Failed("boom"))))
            assertFalse(Files.exists(root.resolve(".imux")))
        }

    @Test
    fun `取回时传入 100 MB 上限`() =
        runBlocking {
            var limit = 0L
            val attachment =
                object : InboundAttachment {
                    override val kind = AttachmentKind.FILE

                    override suspend fun fetch(maxBytes: Long): FetchResult {
                        limit = maxBytes
                        return FetchResult.Failed("x")
                    }
                }
            store().place(target(project()), attachment)
            assertEquals(100L * 1024 * 1024, limit)
        }
}

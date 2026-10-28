package com.github.izerui.imux.bridge.terminal

import com.github.izerui.imux.bridge.core.AttachmentKind
import com.github.izerui.imux.bridge.core.FetchResult
import com.github.izerui.imux.bridge.core.FetchedFile
import com.github.izerui.imux.bridge.core.InboundAttachment
import com.github.izerui.imux.bridge.core.PlaceResult
import com.github.izerui.imux.bridge.core.SessionTarget
import com.github.izerui.imux.model.AgentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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

    /** 固定后缀序列，按调用顺序取 s1、s2……，便于断言文件名。 */
    private fun store(): ProjectAttachmentStore {
        var next = 0
        return ProjectAttachmentStore(clock = { now }, suffix = { "s${++next}" })
    }

    @Test
    fun `没有文件名时按类型、时间与文件头命名，存到日期目录并返回相对路径`() =
        runBlocking {
            val root = project()
            val result = store().place(target(root), FakeAttachment(png, null))
            assertEquals(PlaceResult.Saved(".imux/attachments/2026-10-27/image-153012-s1.png"), result)
            assertTrue(Files.exists(root.resolve(".imux/attachments/2026-10-27/image-153012-s1.png")))
        }

    @Test
    fun `有文件名时清理后使用并加后缀，没有扩展名时补上识别出的扩展名`() =
        runBlocking {
            val root = project()
            val s = store()
            assertEquals(PlaceResult.Saved(".imux/attachments/2026-10-27/报告-s1.pdf"), s.place(target(root), FakeAttachment("%PDF-1".toByteArray(), "../报告.pdf", AttachmentKind.FILE)))
            assertEquals(PlaceResult.Saved(".imux/attachments/2026-10-27/报告-s2.pdf"), s.place(target(root), FakeAttachment("%PDF-1".toByteArray(), "报告.pdf", AttachmentKind.FILE)))
            assertEquals(PlaceResult.Saved(".imux/attachments/2026-10-27/shot-s3.png"), s.place(target(root), FakeAttachment(png, "shot")))
        }

    @Test
    fun `随机后缀撞名时换一个，不覆盖已有文件`() =
        runBlocking {
            val root = project()
            val suffixes = ArrayDeque(listOf("dup", "dup", "new"))
            val s = ProjectAttachmentStore(clock = { now }, suffix = { suffixes.removeFirst() })
            assertEquals(PlaceResult.Saved(".imux/attachments/2026-10-27/a-dup.txt"), s.place(target(root), FakeAttachment("1".toByteArray(), "a.txt", AttachmentKind.FILE)))
            assertEquals(PlaceResult.Saved(".imux/attachments/2026-10-27/a-new.txt"), s.place(target(root), FakeAttachment("2".toByteArray(), "a.txt", AttachmentKind.FILE)))
            assertEquals("1", Files.readString(root.resolve(".imux/attachments/2026-10-27/a-dup.txt")))
        }

    @Test
    fun `同名附件同时保存：全部成功、各自一个文件，互不覆盖`() =
        runBlocking {
            val root = project()
            val s = ProjectAttachmentStore(clock = { now })
            val contents = List(32) { "%PDF-$it".toByteArray() }
            val attachments = contents.map { FakeAttachment(it, "报告.pdf", AttachmentKind.FILE) }
            // 先取回到临时文件，再让保存同时开始，把竞争集中在挑名字与落盘上
            val results =
                attachments
                    .map { attachment -> async(Dispatchers.IO) { s.place(target(root), attachment) } }
                    .awaitAll()
            val paths = results.map { (it as PlaceResult.Saved).relativePath }
            assertEquals(contents.size, paths.toSet().size)
            assertEquals(
                contents.map { String(it) }.toSet(),
                paths.map { Files.readString(root.resolve(it)) }.toSet(),
            )
            Files.list(root.resolve(".imux/attachments/2026-10-27")).use { assertEquals(contents.size.toLong(), it.count()) }
            assertEquals(1, Files.readAllLines(root.resolve(".git/info/exclude")).count { it == ".imux/" })
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
    fun `git worktree 里 exclude 写到公共目录`() =
        runBlocking {
            val main = project()
            val worktreeGitDir = Files.createDirectories(main.resolve(".git/worktrees/wt"))
            Files.writeString(worktreeGitDir.resolve("commondir"), "../..\n")
            val worktree = temp.newFolder().toPath()
            Files.writeString(worktree.resolve(".git"), "gitdir: $worktreeGitDir\n")
            store().place(target(worktree), FakeAttachment(png, null))
            assertEquals(listOf(".imux/"), Files.readAllLines(main.resolve(".git/info/exclude")))
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

    /** 取回成功后把临时文件交给调用方，记下路径以便检查残留。 */
    private inner class TempFileAttachment(
        private val beforeReturn: suspend () -> Unit = {},
    ) : InboundAttachment {
        override val kind = AttachmentKind.IMAGE
        lateinit var file: Path

        override suspend fun fetch(maxBytes: Long): FetchResult {
            file = temp.newFile().toPath()
            Files.write(file, png)
            beforeReturn()
            return FetchResult.Ok(FetchedFile(file, null))
        }
    }

    @Test
    fun `写 exclude 失败不影响保存结果`() =
        runBlocking {
            val root = project()
            // exclude 是目录，读写都会失败
            Files.createDirectories(root.resolve(".git/info/exclude"))
            val result = store().place(target(root), FakeAttachment(png, null))
            assertEquals(PlaceResult.Saved(".imux/attachments/2026-10-27/image-153012-s1.png"), result)
            assertTrue(Files.exists(root.resolve(".imux/attachments/2026-10-27/image-153012-s1.png")))
        }

    @Test
    fun `保存时出现非 IO 异常返回失败并删除临时文件`() =
        runBlocking {
            val attachment = TempFileAttachment()
            // 含 NUL 的路径会抛 InvalidPathException
            val result = store().place(SessionTarget("bad\u0000path", "tab-1", AgentType.CLAUDE, "s1"), attachment)
            assertEquals(PlaceResult.Failed("save"), result)
            assertFalse("临时文件应删除", Files.exists(attachment.file))
        }

    @Test
    fun `取回之后被取消时删除临时文件`() =
        runBlocking {
            val root = project()
            val attachment = TempFileAttachment { currentCoroutineContext().job.cancel() }
            val job = launch { store().place(target(root), attachment) }
            job.join()
            assertTrue(job.isCancelled)
            assertFalse("临时文件应删除", Files.exists(attachment.file))
            assertFalse(Files.exists(root.resolve(".imux/attachments/2026-10-27/image-153012-s1.png")))
        }
}

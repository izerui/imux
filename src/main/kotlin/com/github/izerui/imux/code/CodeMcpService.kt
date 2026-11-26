package com.github.izerui.imux.code

import com.github.izerui.imux.mcp.McpTokenRegistry
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.ManagingFS
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

const val CODE_MCP_PATH = "/imux/code-mcp"

/** 按终端发放 `imux_code` 凭证；凭证只对应启动它的那个项目，终端关闭时撤销。 */
@Service(Service.Level.APP)
class CodeMcpService(val scope: CoroutineScope) : Disposable {
    private val protocols = McpTokenRegistry<CodeMcpProtocol>()
    /** 凭证对应的项目：结束前自动检查（[EditCheckHandler]）用同一份凭证找到会话所在的项目。 */
    private val projects = java.util.concurrent.ConcurrentHashMap<String, Project>()

    fun register(project: Project): String {
        return protocols.register(CodeMcpProtocol(ProjectCodeBackend(project), scope)).also { projects[it] = project }
    }

    /** 结束前自动检查：每个会话这一轮里变过的文件（绝对路径），你每发一句话就重新开始记。 */
    private val turns = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    @Volatile private var listening = false

    fun protocol(token: String): CodeMcpProtocol? = protocols[token]
    /** 凭证已撤销时为 null（撤销时一并删除）。 */
    fun project(token: String): Project? = projects[token]?.takeIf { protocols[token] != null }
    fun revoke(token: String) { protocols.revoke(token); projects.remove(token); turns.remove(token) }
    override fun dispose() { protocols.clear(); projects.clear(); turns.clear() }

    /** 这个会话开始新的一轮：清空记录，从现在起记它所在项目里变过的文件。凭证无效时返回 false。 */
    fun startTurn(token: String): Boolean {
        if (project(token) == null) return false
        ensureListening()
        turns[token] = java.util.concurrent.ConcurrentHashMap.newKeySet()
        return true
    }

    /** 这一轮记下的文件；这一轮没开始记（比如插件更新后的旧会话）时为 null。 */
    fun turnChanges(token: String): Set<String>? = turns[token]?.toSet()

    /**
     * 第一次有会话开始记时才接上两种通知，跟着服务一起释放：
     * 文件内容变了（命令行、改文件工具在 IDE 外的修改，经文件监听上报后才有）；
     * IDE 里的文档变了（IDE 重命名之类的修改可能还没存盘，文件层面看不到）。
     */
    private fun ensureListening() {
        if (listening) return
        synchronized(this) {
            if (listening) return
            listening = true
            ApplicationManager.getApplication().messageBus.connect(this).subscribe(
                com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
                object : com.intellij.openapi.vfs.newvfs.BulkFileListener {
                    override fun after(events: List<com.intellij.openapi.vfs.newvfs.events.VFileEvent>) {
                        if (turns.isEmpty()) return
                        for (event in events) {
                            if (event is com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent) continue
                            record(event.file?.path ?: event.path)
                        }
                    }
                },
            )
            com.intellij.openapi.editor.EditorFactory.getInstance().eventMulticaster.addDocumentListener(
                object : com.intellij.openapi.editor.event.DocumentListener {
                    override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                        if (turns.isEmpty()) return
                        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(event.document)?.path?.let(::record)
                    }
                },
                this,
            )
        }
    }

    /** 记到所在项目匹配的每个会话名下；这里只按项目路径粗筛，是不是项目代码留到检查时再判断。 */
    private fun record(path: String) {
        for ((token, changed) in turns) {
            val base = projects[token]?.basePath ?: continue
            if (path.startsWith("$base/")) changed += path
        }
    }

    companion object {
        fun getInstance(): CodeMcpService = ApplicationManager.getApplication().getService(CodeMcpService::class.java)
    }
}

/**
 * 在读锁里执行查询，进读锁前先把磁盘上的改动同步进 IDE。
 *
 * 智能体刚用自己的工具写完文件就会来查：IDE 的文件监听是异步的，这时 IDE 手里可能还是旧内容
 * （新文件「找不到」，改过的文件行号错位、查出错误结果）。读锁里不能刷新磁盘，所以在锁外先
 * 同步被查的文件和已上报的磁盘变化（见 [syncFromDisk]）；读锁再要求文档已提交、索引已就绪。
 * 索引迟迟不就绪时如实告诉智能体「IDE 还在建索引」，而不是返回空结果让它误以为没人调用。
 */
private class ProjectCodeBackend(private val project: Project) : CodeToolsBackend {
    @Volatile private var lastGlobalRefresh = 0L

    override suspend fun references(query: CodeQuery) = run(query) { CodeNavigator(project).references(query) }
    override suspend fun callHierarchy(query: CodeQuery, direction: CallDirection, depth: Int) =
        run(query) { CodeNavigator(project).callHierarchy(query, direction, depth) }
    override suspend fun typeHierarchy(query: CodeQuery, direction: HierarchyDirection) =
        run(query) { CodeNavigator(project).typeHierarchy(query, direction) }
    // 结构视图只看这一个文件的语法树，不靠索引：IDE 建索引时侧栏的结构视图照样能看，大纲也不必等。
    override suspend fun fileOutline(file: String, limit: Int) =
        run(file, needsIndex = false) { CodeNavigator(project).fileOutline(file, limit) }

    private suspend fun run(query: CodeQuery, block: () -> CodeToolResult): CodeToolResult = run(query.file, needsIndex = true, block)

    private suspend fun run(file: String, needsIndex: Boolean, block: () -> CodeToolResult): CodeToolResult {
        if (project.isDisposed) return codeFailure("project_closed", "The project of this session is closed in the IDE.")
        return try {
            // 建索引可能要几分钟：短等一下，还没好就直接告诉智能体稍后重试，不让它干等到超时。
            // 不在建索引就不等：runWhenSmart 经界面线程回调，开着模态对话框时会一直排不上，白等到超时还误报「在建索引」。
            // 用公开的 runWhenSmart 等「索引就绪」通知；带超时的 waitForSmartMode(long) 是 Internal API。
            if (needsIndex && DumbService.isDumb(project)) withTimeoutOrNull(INDEX_WAIT_MS) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    DumbService.getInstance(project).runWhenSmart { if (continuation.isActive) continuation.resume(Unit) }
                }
            } ?: return codeFailure("indexing", "The IDE is still indexing this project. Retry in a minute or so.")
            withTimeoutOrNull(TIMEOUT_MS) {
                syncFromDisk(file)
                val committed = ReadConstraint.withDocumentsCommitted(project)
                if (needsIndex) constrainedReadAction(ReadConstraint.inSmartMode(project), committed) { block() }
                else constrainedReadAction(committed) { block() }
            }
                ?: if (needsIndex && DumbService.isDumb(project)) codeFailure("indexing", "The IDE is still indexing this project. Retry in a moment.")
                else codeFailure("timeout", "The search took longer than ${TIMEOUT_MS / 1000}s. ${if (needsIndex) "Narrow it with a smaller max_results or depth." else "Try a smaller max_results."}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("imux_code query failed", e)
            codeFailure("internal_error", "The IDE failed to run this query: ${e.javaClass.simpleName}. Fall back to the language server or text search.")
        }
    }

    /**
     * 同步刷新（`syncRefresh`、`refreshAndFindFileByPath`）要求在主线程持写意图锁，后台调用会被平台断言拦下，
     * 所以走异步刷新、等它完成：先刷新被查的文件（还不在 VFS 里就刷新它最近的已知上级目录，
     * 让新建的文件出现），再处理文件监听已上报的其它改动。刷新完成时 VFS 事件和文档重载都已应用。
     * 最多等 [REFRESH_WAIT_MS]，刷不完也照常查询，只是可能读到稍旧的内容。
     */
    private suspend fun syncFromDisk(file: String) {
        // 库文件（jar、jrt 里的）不在本地磁盘上，不用刷新。
        val path = resolveQueryPath(project, file)?.takeUnless { "!/" in it }
        withTimeoutOrNull(REFRESH_WAIT_MS) {
            if (path != null) {
                val fs = LocalFileSystem.getInstance()
                var existing: String? = path
                var target = fs.findFileByPath(path)
                while (target == null && existing != null) {
                    existing = existing.substringBeforeLast('/', "").ifEmpty { null }
                    target = existing?.let(fs::findFileByPath)
                }
                // 目录只有被标记为「有变化」才会重新列子文件，新建的文件才会出现；文件本身也一并标记。
                // 完成通知指定 ModalityState.any()：默认的回调要排进界面线程的非模态队列，开着设置等模态对话框时发不出来，
                // 会白等到超时。挂起版的 RefreshQueue.refresh 没有这个问题，但它是 Experimental API。
                if (target != null) {
                    VfsUtil.markDirty(false, false, target)
                    awaitRefresh { done -> RefreshQueue.getInstance().refresh(true, false, done, ModalityState.any(), target) }
                }
            }
            // 全局刷新只做「文件监听已上报」的改动，但没有文件监听的环境（WSL、网络盘）会扫遍所有根目录：
            // 智能体连续查询时节流，被查的文件上面已单独刷新，不受影响。
            val now = System.currentTimeMillis()
            if (now - lastGlobalRefresh >= GLOBAL_REFRESH_INTERVAL_MS) {
                lastGlobalRefresh = now
                // 递归刷新本地根目录只会深入被标记为「有变化」的目录，即文件监听已上报的那些。
                awaitRefresh { done -> RefreshQueue.getInstance().refresh(true, true, done, ModalityState.any(), *ManagingFS.getInstance().localRoots) }
            }
        }
    }

    private suspend fun awaitRefresh(start: (Runnable) -> Unit) = suspendCancellableCoroutine { continuation ->
        start(Runnable { if (continuation.isActive) continuation.resume(Unit) })
    }

    companion object {
        private const val TIMEOUT_MS = 120_000L
        private const val INDEX_WAIT_MS = 15_000L
        private const val REFRESH_WAIT_MS = 10_000L
        private const val GLOBAL_REFRESH_INTERVAL_MS = 5_000L
        private val LOG = logger<ProjectCodeBackend>()
    }
}

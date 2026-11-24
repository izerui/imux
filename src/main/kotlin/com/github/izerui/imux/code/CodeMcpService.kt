package com.github.izerui.imux.code

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
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

const val CODE_MCP_PATH = "/imux/code-mcp"

/** 按终端发放 `imux_code` 凭证；凭证只对应启动它的那个项目，终端关闭时撤销。 */
@Service(Service.Level.APP)
class CodeMcpService(val scope: CoroutineScope) : Disposable {
    private val protocols = ConcurrentHashMap<String, CodeMcpProtocol>()

    fun register(project: Project): String {
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        protocols[token] = CodeMcpProtocol(ProjectCodeBackend(project), scope)
        return token
    }

    fun protocol(token: String): CodeMcpProtocol? = protocols[token]
    fun revoke(token: String) { protocols.remove(token) }
    override fun dispose() { protocols.clear() }

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

    private suspend fun run(query: CodeQuery, block: () -> CodeToolResult): CodeToolResult {
        if (project.isDisposed) return failure("project_closed", "The project of this session is closed in the IDE.")
        return try {
            // 建索引可能要几分钟：短等一下，还没好就直接告诉智能体稍后重试，不让它干等到超时。
            // 不在建索引就不等：runWhenSmart 经界面线程回调，开着模态对话框时会一直排不上，白等到超时还误报「在建索引」。
            // 用公开的 runWhenSmart 等「索引就绪」通知；带超时的 waitForSmartMode(long) 是 Internal API。
            if (DumbService.isDumb(project)) withTimeoutOrNull(INDEX_WAIT_MS) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    DumbService.getInstance(project).runWhenSmart { if (continuation.isActive) continuation.resume(Unit) }
                }
            } ?: return failure("indexing", "The IDE is still indexing this project. Retry in a minute or so.")
            withTimeoutOrNull(TIMEOUT_MS) {
                syncFromDisk(query.file)
                constrainedReadAction(ReadConstraint.inSmartMode(project), ReadConstraint.withDocumentsCommitted(project)) { block() }
            }
                ?: if (DumbService.isDumb(project)) failure("indexing", "The IDE is still indexing this project. Retry in a moment.")
                else failure("timeout", "The search took longer than ${TIMEOUT_MS / 1000}s. Narrow it with a smaller max_results or depth.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("imux_code query failed", e)
            failure("internal_error", "The IDE failed to run this query: ${e.javaClass.simpleName}. Fall back to the language server or text search.")
        }
    }

    /**
     * 同步刷新（`syncRefresh`、`refreshAndFindFileByPath`）要求在主线程持写意图锁，后台调用会被平台断言拦下，
     * 所以走异步刷新、等它完成：先刷新被查的文件（还不在 VFS 里就刷新它最近的已知上级目录，
     * 让新建的文件出现），再处理文件监听已上报的其它改动。刷新完成时 VFS 事件和文档重载都已应用。
     * 最多等 [REFRESH_WAIT_MS]，刷不完也照常查询，只是可能读到稍旧的内容。
     */
    private suspend fun syncFromDisk(file: String) {
        val path = resolveQueryPath(project, file)
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

    private fun failure(reason: String, message: String) = CodeToolResult(JsonObject().apply {
        addProperty("reason", reason); addProperty("message", message)
    }, failed = true)

    companion object {
        private const val TIMEOUT_MS = 120_000L
        private const val INDEX_WAIT_MS = 15_000L
        private const val REFRESH_WAIT_MS = 10_000L
        private const val GLOBAL_REFRESH_INTERVAL_MS = 5_000L
        private val LOG = logger<ProjectCodeBackend>()
    }
}

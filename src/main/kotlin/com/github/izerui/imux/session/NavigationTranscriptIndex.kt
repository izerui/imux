package com.github.izerui.imux.session

import com.github.izerui.imux.model.AgentSession
import com.github.izerui.imux.model.AgentType
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

internal data class NavigationTranscriptUpdate(
    val exchanges: List<SessionExchange>,
    val changed: Boolean,
)

/**
 * 打开会话的增量 transcript 索引。
 *
 * 首次只读取与旧实现相同的尾部窗口；之后按文件字节位置续读。终端每一帧都会变化，
 * 会话文件却只在消息真正落盘时增长，把两者拆开后，流式输出不再反复解析 4MB JSONL。
 *
 * 本类由单个导航器的 IO 协程串行调用，不自行加锁。文件缩短、原地替换或会话切换时
 * 自动清空并重建；半行保留为原始字节，避免把分段 UTF-8 解码成替换字符。
 */
internal class NavigationTranscriptIndex(
    private val maxExchanges: Int = DEFAULT_MAX_EXCHANGES,
    private val maxMessageChars: Int = DEFAULT_MAX_MESSAGE_CHARS,
    private val initialTailBytes: Long = DEFAULT_INITIAL_TAIL_BYTES,
    private val maxPendingLineBytes: Int = DEFAULT_MAX_PENDING_LINE_BYTES,
    private val openChannel: (Path) -> FileChannel = { FileChannel.open(it, StandardOpenOption.READ) },
) {
    private var identity: FileIdentity? = null
    private var position = 0L
    private var modifiedAt = Long.MIN_VALUE
    private val lineBuffer = ByteArrayOutputStream()
    private var discardUntilNewline = false
    private var skipNextNewline = false
    private val exchanges = ArrayDeque<SessionExchange>()
    private val treeNodes = LinkedHashMap<String, TranscriptNode>()
    private var treeLeafId: String? = null
    private var activeUserNodeIds = emptyList<String>()
    private var treeDirty = false
    private var rebuildBeforeNextRead = false

    /** 测试与性能回归使用：未变化的 refresh 不应增加这个计数。 */
    internal var totalBytesRead: Long = 0
        private set

    internal var branchRebuildCount: Int = 0
        private set

    fun refresh(session: AgentSession): NavigationTranscriptUpdate {
        val before = exchanges.toList()
        val beforeUserNodeIds = activeUserNodeIds
        val currentIdentity = identity
        val sameSessionIdentity =
            currentIdentity != null &&
                currentIdentity.path == session.filePath &&
                currentIdentity.agentType == session.agentType
        if (currentIdentity != null && !sameSessionIdentity) {
            reset()
        }

        val attrs =
            runCatching {
                Files.readAttributes(session.filePath, BasicFileAttributes::class.java)
            }.getOrNull()
                ?: return NavigationTranscriptUpdate(
                    exchanges.toList(),
                    changed = exchanges.toList() != before || activeUserNodeIds != beforeUserNodeIds,
                )
        val nextIdentity = FileIdentity(session.filePath, session.agentType, attrs.fileKey())
        val sameFile = identity == nextIdentity
        val rewrittenAtSameSize = sameFile && attrs.size() == position && attrs.lastModifiedTime().toMillis() != modifiedAt
        val reset = !sameFile || attrs.size() < position || rewrittenAtSameSize

        if (rebuildBeforeNextRead) {
            reset(nextIdentity, attrs.size())
        } else if (reset) {
            reset(nextIdentity, attrs.size())
        }
        if (attrs.size() > position) {
            try {
                readAppended(session.filePath, session.agentType, attrs.size())
            } catch (_: IOException) {
                reset(nextIdentity, attrs.size())
                if (sameSessionIdentity) before.takeLast(maxExchanges).forEach(exchanges::addLast)
                rebuildBeforeNextRead = true
                return NavigationTranscriptUpdate(
                    exchanges.toList(),
                    changed = exchanges.toList() != before,
                )
            }
        }
        modifiedAt = attrs.lastModifiedTime().toMillis()

        val current = exchanges.toList()
        return NavigationTranscriptUpdate(
            current,
            changed = current != before || activeUserNodeIds != beforeUserNodeIds,
        )
    }

    fun reset() {
        identity = null
        position = 0L
        modifiedAt = Long.MIN_VALUE
        lineBuffer.reset()
        discardUntilNewline = false
        skipNextNewline = false
        exchanges.clear()
        treeNodes.clear()
        treeLeafId = null
        activeUserNodeIds = emptyList()
        treeDirty = false
        rebuildBeforeNextRead = false
    }

    private fun reset(
        nextIdentity: FileIdentity,
        size: Long,
    ) {
        identity = nextIdentity
        position = (size - initialTailBytes).coerceAtLeast(0L)
        modifiedAt = Long.MIN_VALUE
        lineBuffer.reset()
        discardUntilNewline = position > 0L
        skipNextNewline = false
        exchanges.clear()
        treeNodes.clear()
        treeLeafId = null
        activeUserNodeIds = emptyList()
        treeDirty = false
        rebuildBeforeNextRead = false
    }

    private fun readAppended(
        file: Path,
        agentType: AgentType,
        size: Long,
    ) {
        openChannel(file).use { channel ->
            channel.position(position)
            val buffer = ByteBuffer.allocate(READ_BUFFER_BYTES)
            while (channel.position() < size) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), size - channel.position()).toInt())
                val read = channel.read(buffer)
                if (read <= 0) break
                totalBytesRead += read
                buffer.flip()
                while (buffer.hasRemaining()) consumeByte(buffer.get(), agentType)
            }
            position = channel.position()
        }
        if (lineBuffer.size() > 0) acceptCompleteEofLine(agentType)
        if (treeDirty) {
            rebuildActiveBranch()
            treeDirty = false
        }
    }

    private fun consumeByte(
        byte: Byte,
        agentType: AgentType,
    ) {
        if (skipNextNewline) {
            skipNextNewline = false
            if (byte == NEWLINE) return
        }
        if (discardUntilNewline) {
            if (byte == NEWLINE) discardUntilNewline = false
            return
        }
        if (byte != NEWLINE) {
            if (lineBuffer.size() >= maxPendingLineBytes) {
                lineBuffer.reset()
                discardUntilNewline = true
                return
            }
            lineBuffer.write(byte.toInt())
            return
        }

        val line = lineBuffer.toString(StandardCharsets.UTF_8)
        lineBuffer.reset()
        acceptLine(line, agentType)
    }

    private fun acceptCompleteEofLine(agentType: AgentType) {
        val line = lineBuffer.toString(StandardCharsets.UTF_8)
        val complete = runCatching { JsonParser.parseString(line).isJsonObject }.getOrDefault(false)
        if (!complete) return
        lineBuffer.reset()
        skipNextNewline = true
        acceptLine(line, agentType)
    }

    private fun acceptLine(
        line: String,
        agentType: AgentType,
    ) {
        val message = parseNavigatorTranscriptMessage(line, agentType, maxMessageChars)
        if (agentType == AgentType.CODEX) {
            message?.let(::accept)
            return
        }

        val type = JsonLineScanner.topLevelStringValue(line, "type")
        val idKey = if (agentType == AgentType.PI) "id" else "uuid"
        val parentKey = if (agentType == AgentType.PI) "parentId" else "parentUuid"
        val id = JsonLineScanner.topLevelStringValue(line, idKey)
        if (id == null || (agentType == AgentType.PI && type == "session")) {
            // 兼容旧版线性记录与测试 fixture；现代 Pi/Claude 生产记录都有树节点 id。
            message?.let(::accept)
            return
        }

        treeNodes[id] = TranscriptNode(JsonLineScanner.topLevelStringValue(line, parentKey), message)
        treeLeafId = id
        treeDirty = true
        while (treeNodes.size > MAX_TREE_NODES) treeNodes.remove(treeNodes.keys.first())
    }

    private fun rebuildActiveBranch() {
        branchRebuildCount++
        val branch = mutableListOf<Pair<String, SessionTranscriptMessage?>>()
        val visited = HashSet<String>()
        var cursor = treeLeafId
        while (cursor != null && visited.add(cursor)) {
            val node = treeNodes[cursor] ?: break
            branch += cursor to node.message
            cursor = node.parentId
        }

        val activeBranch = branch.asReversed()
        activeUserNodeIds =
            activeBranch.mapNotNull { (id, message) ->
                id.takeIf { message?.role == "user" && !message.hiddenFromTerminal }
            }
        exchanges.clear()
        pairExchanges(activeBranch.mapNotNull { it.second })
            .takeLast(maxExchanges)
            .forEach(exchanges::addLast)
    }

    private fun accept(message: SessionTranscriptMessage) {
        when {
            message.hiddenFromTerminal -> {
                Unit
            }

            message.role == "user" -> {
                exchanges.addLast(SessionExchange(message.text, ""))
                while (exchanges.size > maxExchanges) exchanges.removeFirst()
            }

            exchanges.lastOrNull()?.assistantReply?.isEmpty() == true -> {
                val current = exchanges.removeLast()
                exchanges.addLast(current.copy(assistantReply = message.text))
            }
        }
    }

    private data class TranscriptNode(
        val parentId: String?,
        val message: SessionTranscriptMessage?,
    )

    private data class FileIdentity(
        val path: Path,
        val agentType: AgentType,
        val fileKey: Any?,
    )

    private companion object {
        const val DEFAULT_MAX_EXCHANGES = 200
        const val DEFAULT_MAX_MESSAGE_CHARS = 1_000
        const val DEFAULT_INITIAL_TAIL_BYTES = 4L * 1024 * 1024
        const val DEFAULT_MAX_PENDING_LINE_BYTES = 8 * 1024 * 1024
        const val READ_BUFFER_BYTES = 64 * 1024
        const val MAX_TREE_NODES = 10_000
        const val NEWLINE: Byte = 10
    }
}

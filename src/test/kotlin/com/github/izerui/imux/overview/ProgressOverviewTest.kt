package com.github.izerui.imux.overview

import com.github.izerui.imux.ImuxBundle
import com.github.izerui.imux.model.AgentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ProgressOverviewTest {
    // ── parseOverviewOutput 测试 ──

    @Test
    fun `解析正常的节点输出`() {
        val output = """
            [MAIN] 确认需求：为用户列表页添加分页功能
            [MAIN] 创建 PaginationComponent.vue 组件
            [SIDE] 解释 Vue 3 Composition API 用法
            [MAIN] 实现后端分页接口
            [CHANGE] 需求变更：改为无限滚动
            [ERROR] API 超时错误
            [IN_PROGRESS] 正在修复超时问题
        """.trimIndent()

        val nodes = parseOverviewOutput(output)

        assertEquals(7, nodes.size)
        assertEquals(NodeSymbol.MAIN, nodes[0].symbol)
        assertEquals("确认需求：为用户列表页添加分页功能", nodes[0].text)
        assertEquals(NodeSymbol.SIDE, nodes[2].symbol)
        assertEquals(NodeSymbol.CHANGE, nodes[4].symbol)
        assertEquals(NodeSymbol.ERROR, nodes[5].symbol)
        assertEquals(NodeSymbol.IN_PROGRESS, nodes[6].symbol)
    }

    @Test
    fun `解析包含回滚区间的输出`() {
        val output = """
            [MAIN] 分析性能瓶颈
            [ROLLBACK_START] 尝试引入 SSR
            [MAIN] 配置 Nuxt SSR
            [ROLLBACK_END] SSR 导致部署复杂，放弃
            [MAIN] 改为图片懒加载
        """.trimIndent()

        val nodes = parseOverviewOutput(output)

        assertEquals(5, nodes.size)
        assertEquals(NodeSymbol.ROLLBACK_START, nodes[1].symbol)
        assertEquals(NodeSymbol.ROLLBACK_END, nodes[3].symbol)
    }

    @Test
    fun `忽略不符合格式的行`() {
        val nodes = parseOverviewOutput("[MAIN] 第一步\n这是额外文字\n\n[MAIN] 第二步")
        assertEquals(2, nodes.size)
    }

    @Test
    fun `空输出返回空列表`() {
        assertEquals(emptyList<OverviewNode>(), parseOverviewOutput(""))
        assertEquals(emptyList<OverviewNode>(), parseOverviewOutput("   \n  "))
    }

    @Test
    fun `未知 TYPE 降级为 MAIN`() {
        val nodes = parseOverviewOutput("[UNKNOWN] 某个节点")
        assertEquals(NodeSymbol.MAIN, nodes.single().symbol)
    }

    // ── readConversationEntries 测试 ──

    private fun user(text: String) =
        """{"type":"user","message":{"role":"user","content":${com.google.gson.JsonPrimitive(text)}}}"""

    private fun assistant(text: String) =
        """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]}}"""

    // ── OverviewCache 测试（走生产缓存路径） ──

    @Test
    fun `put 写入缓存`() {
        val file = Files.createTempFile("cache-test", ".jsonl")
        file.toFile().deleteOnExit()
        Files.writeString(file, "content")
        val snapshotSize = Files.size(file)

        val cache = OverviewCache(null)
        val result = OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "测试")), Instant.now())

        val stored = cache.put("s1", result, snapshotSize)

        assertTrue("应缓存成功", stored)
        assertNotNull("缓存应命中", cache.get("s1"))
        assertEquals("缓存的摘要应一致", result, cache.get("s1")!!.result)
    }

    @Test
    fun `生成期间文件增长时仍保存本次结果与截止位置，只判定过期`() {
        val file = Files.createTempFile("cache-test", ".jsonl")
        file.toFile().deleteOnExit()
        Files.writeString(file, "u1\n")
        val cursor = trailCursor(file, Files.size(file))

        // 模拟 CLI 生成期间主会话追加内容
        Files.writeString(file, "u1\nnew content appended during CLI execution\n")

        val cache = OverviewCache(null)
        val shown = storeOverview(cache, "s1", OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "本次摘要")), Instant.now()), file, cursor)

        assertTrue("截止之后有新内容，展示为过期", shown.stale)
        val cached = cache.get("s1")!!
        assertEquals("本次摘要仍要保存，作为下次增量的基线", "本次摘要", cached.result.nodes.single().text)
        assertEquals(cursor.lines, cached.lineCount)
        assertEquals(cursor.bytes, cached.fileSize)
        assertTrue(isStale(cached, file))
    }

    @Test
    fun `缓存命中后文件变大则 fileSize 不匹配`() {
        val file = Files.createTempFile("cache-race", ".jsonl")
        file.toFile().deleteOnExit()
        Files.writeString(file, "initial content")
        val snapshotSize = Files.size(file)

        val cache = OverviewCache(null)
        val result = OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "旧摘要")), Instant.now())
        cache.put("s1", result, snapshotSize)

        // 模拟之后主会话追加了新消息
        Files.writeString(file, "initial content\nnew message after caching")

        val cached = cache.get("s1")!!
        val currentSize = Files.size(file)
        assertTrue(
            "缓存的 fileSize 与当前实际大小不同，调用方应判定缓存失效",
            cached.fileSize != currentSize,
        )
    }

    @Test
    fun `缓存写入磁盘后新实例可读取`() {
        val storageDir = Files.createTempDirectory("overview-cache-test")
        storageDir.toFile().deleteOnExit()
        val sessionFile = Files.createTempFile("session", ".jsonl")
        sessionFile.toFile().deleteOnExit()
        Files.writeString(sessionFile, "content")
        val fileSize = Files.size(sessionFile)

        val cache1 = OverviewCache(storageDir)
        val result = OverviewResult(
            listOf(
                OverviewNode(NodeSymbol.MAIN, "实现了 JWT 认证"),
                OverviewNode(NodeSymbol.SIDE, "讨论了 Session 方案"),
                OverviewNode(NodeSymbol.CHANGE, "改用方案 B"),
            ),
            Instant.now(),
        )
        cache1.put("s1", result, fileSize)

        val cache2 = OverviewCache(storageDir)
        val loaded = cache2.get("s1")

        assertNotNull("新实例应能从磁盘加载缓存", loaded)
        assertEquals("节点数应一致", 3, loaded!!.result.nodes.size)
        assertEquals("第一个节点文字一致", "实现了 JWT 认证", loaded.result.nodes[0].text)
        assertEquals("第一个节点类型一致", NodeSymbol.MAIN, loaded.result.nodes[0].symbol)
        assertEquals("第三个节点类型一致", NodeSymbol.CHANGE, loaded.result.nodes[2].symbol)
        assertEquals("文件大小一致", fileSize, loaded.fileSize)
    }

    @Test
    fun `会话文件变大后磁盘缓存不命中`() {
        val storageDir = Files.createTempDirectory("overview-cache-test")
        storageDir.toFile().deleteOnExit()
        val sessionFile = Files.createTempFile("session", ".jsonl")
        sessionFile.toFile().deleteOnExit()
        Files.writeString(sessionFile, "original")
        val fileSize = Files.size(sessionFile)

        val cache = OverviewCache(storageDir)
        val result = OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "旧摘要")), Instant.now())
        cache.put("s1", result, fileSize)

        Files.writeString(sessionFile, "original\nnew content")

        val cache2 = OverviewCache(storageDir)
        val loaded = cache2.get("s1")

        assertNotNull("磁盘缓存文件存在", loaded)
        assertTrue(
            "缓存的 fileSize 与当前不同，调用方应判定过期",
            loaded!!.fileSize != Files.size(sessionFile),
        )
    }

    @Test
    fun `remove 同时删除内存和磁盘`() {
        val storageDir = Files.createTempDirectory("overview-cache-test")
        storageDir.toFile().deleteOnExit()
        val sessionFile = Files.createTempFile("session", ".jsonl")
        sessionFile.toFile().deleteOnExit()
        Files.writeString(sessionFile, "content")

        val cache = OverviewCache(storageDir)
        cache.put("s1", OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "test")), Instant.now()), Files.size(sessionFile))

        cache.remove("s1")

        assertNull("内存缓存应清除", cache.get("s1"))
        val cache2 = OverviewCache(storageDir)
        assertNull("磁盘缓存也应清除", cache2.get("s1"))
    }

    @Test
    fun `刷新取消后同一实例仍能读到旧摘要`() {
        val storageDir = Files.createTempDirectory("overview-cache-test")
        storageDir.toFile().deleteOnExit()
        val sessionFile = Files.createTempFile("session", ".jsonl")
        sessionFile.toFile().deleteOnExit()
        Files.writeString(sessionFile, "content")
        val size = Files.size(sessionFile)

        val cache = OverviewCache(storageDir)
        val old = OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "旧摘要")), Instant.now())
        assertTrue(cache.put("s1", old, size))

        // 刷新：直接重新生成，调用前就被取消，缓存不应被触碰
        try {
            generateTrail("prompt", { "[MAIN] 新摘要" }, isCancelled = { true })
            org.junit.Assert.fail("应被取消")
        } catch (_: java.util.concurrent.CancellationException) {
        }

        assertEquals("同一实例仍应读到旧摘要", "旧摘要", cache.get("s1")!!.result.nodes.single().text)
        assertEquals("重启后仍应读到旧摘要", "旧摘要", OverviewCache(storageDir).get("s1")!!.result.nodes.single().text)
    }

    @Test
    fun `刷新成功后新摘要替换旧摘要`() {
        val storageDir = Files.createTempDirectory("overview-cache-test")
        storageDir.toFile().deleteOnExit()
        val sessionFile = Files.createTempFile("session", ".jsonl")
        sessionFile.toFile().deleteOnExit()
        Files.writeString(sessionFile, "content")
        val size = Files.size(sessionFile)

        val cache = OverviewCache(storageDir)
        cache.put("s1", OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "旧摘要")), Instant.now()), size)
        cache.put("s1", OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "新摘要")), Instant.now()), size)

        assertEquals("新摘要", cache.get("s1")!!.result.nodes.single().text)
        assertEquals("新摘要", OverviewCache(storageDir).get("s1")!!.result.nodes.single().text)
    }

    @Test
    fun `存储目录不可写时保存失败状态传到生成结果`() {
        // 用普通文件充当存储目录，createDirectories 必然失败
        val notADir = Files.createTempFile("overview-not-a-dir", ".txt")
        notADir.toFile().deleteOnExit()
        val sessionFile = Files.createTempFile("session", ".jsonl")
        sessionFile.toFile().deleteOnExit()
        Files.writeString(sessionFile, "content")
        val size = Files.size(sessionFile)

        val cache = OverviewCache(notADir)
        val generated = OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "摘要")), Instant.now())

        val shown = storeOverview(cache, "s1", generated, sessionFile, TrailCursor(0, size))

        assertTrue("返回给界面的结果应标记保存失败", shown.saveFailed)
        assertEquals("摘要内容仍应展示", "摘要", shown.nodes.single().text)
        assertTrue("本次会话再次打开仍应提示未保存", cache.get("s1")!!.result.saveFailed)
        assertNull("磁盘上不应有可恢复的缓存", OverviewCache(notADir).get("s1"))
    }

    @Test
    fun `保存成功时结果不带失败标记`() {
        val storageDir = Files.createTempDirectory("overview-cache-test")
        storageDir.toFile().deleteOnExit()
        val sessionFile = Files.createTempFile("session", ".jsonl")
        sessionFile.toFile().deleteOnExit()
        Files.writeString(sessionFile, "content")

        val shown = storeOverview(
            OverviewCache(storageDir), "s1",
            OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "摘要")), Instant.now()),
            sessionFile, TrailCursor(0, Files.size(sessionFile)),
        )

        assertFalse(shown.saveFailed)
    }

    // ── 布局测试 ──

    @Test
    fun `ScrollablePanel 声明跟随 viewport 宽度`() {
        val panel = ScrollablePanel()
        assertTrue("getScrollableTracksViewportWidth 应返回 true", panel.scrollableTracksViewportWidth)
        assertFalse("getScrollableTracksViewportHeight 应返回 false", panel.scrollableTracksViewportHeight)
    }

    @Test
    fun `createNodePanel 使用 JTextPane 而非 JLabel 以支持换行和选中`() {
        val longText = "这是一段很长的中文节点描述。".repeat(10)
        val node = OverviewNode(NodeSymbol.MAIN, longText)
        val panel = createNodePanel(node, inRollback = false, isChinese = true)

        fun findTextPanes(c: java.awt.Container): List<javax.swing.JTextPane> {
            val result = mutableListOf<javax.swing.JTextPane>()
            for (child in c.components) {
                if (child is javax.swing.JTextPane) result.add(child)
                if (child is java.awt.Container) result.addAll(findTextPanes(child))
            }
            return result
        }

        val textPanes = findTextPanes(panel)
        assertTrue("节点面板应包含 JTextPane", textPanes.isNotEmpty())

        val pane = textPanes.first()
        assertEquals("JTextPane 应包含完整文本", longText, pane.text)
        assertFalse("JTextPane 应不可编辑", pane.isEditable)
    }

    @Test
    fun `nodePanel 使用 BorderLayout 且文本在 CENTER 位置`() {
        val node = OverviewNode(NodeSymbol.MAIN, "测试节点")
        val panel = createNodePanel(node, inRollback = false, isChinese = true)
        assertTrue("应使用 BorderLayout", panel.layout is java.awt.BorderLayout)
        val layout = panel.layout as java.awt.BorderLayout
        assertNotNull("CENTER 应有组件", layout.getLayoutComponent(java.awt.BorderLayout.CENTER))
        assertNotNull("WEST 应有 symbol 标签", layout.getLayoutComponent(java.awt.BorderLayout.WEST))
    }

    @Test
    fun `多节点在 BoxLayout 中按顺序排列`() {
        val nodes = listOf(
            OverviewNode(NodeSymbol.MAIN, "第一个节点"),
            OverviewNode(NodeSymbol.SIDE, "第二个节点"),
            OverviewNode(NodeSymbol.MAIN, "第三个节点"),
        )

        val panel = ScrollablePanel()
        for (node in nodes) {
            panel.add(createNodePanel(node, inRollback = false, isChinese = true))
        }

        assertEquals("应有 3 个子组件", 3, panel.componentCount)
        assertTrue("布局应为 BoxLayout", panel.layout is javax.swing.BoxLayout)
    }

    @Test
    fun `会话没有新交互时摘要不过期`() {
        val sessionFile = Files.createTempFile("session", ".jsonl")
        sessionFile.toFile().deleteOnExit()
        Files.writeString(sessionFile, user("修复登录"))
        val cached = CachedOverview(OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "摘要")), Instant.now()), Files.size(sessionFile))

        assertFalse(isStale(cached, sessionFile))
    }

    @Test
    fun `会话有新交互后摘要过期但仍可读到旧摘要`() {
        val storageDir = Files.createTempDirectory("overview-cache-test")
        storageDir.toFile().deleteOnExit()
        val sessionFile = Files.createTempFile("session", ".jsonl")
        sessionFile.toFile().deleteOnExit()
        Files.writeString(sessionFile, user("修复登录"))

        val cache = OverviewCache(storageDir)
        cache.put("s1", OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "旧摘要")), Instant.now()), Files.size(sessionFile))

        Files.writeString(sessionFile, user("修复登录") + "\n" + user("再加个日志"))

        val cached = OverviewCache(storageDir).get("s1")!!
        assertTrue("新交互后应判定为过期", isStale(cached, sessionFile))
        assertEquals("过期时仍展示旧摘要", "旧摘要", cached.result.nodes.single().text)
    }

    @Test
    fun `会话文件不存在时视为过期`() {
        val cached = CachedOverview(OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "摘要")), Instant.now()), 10)
        assertTrue(isStale(cached, Path.of("build", "missing-session.jsonl")))
    }

    @Test
    fun `生成期间追加消息时展示层拿到过期状态，新摘要写入内存和磁盘`() {
        val storageDir = Files.createTempDirectory("overview-cache-test")
        storageDir.toFile().deleteOnExit()
        val sessionFile = Files.createTempFile("session", ".jsonl")
        sessionFile.toFile().deleteOnExit()
        Files.writeString(sessionFile, user("修复登录") + "\n")

        val cache = OverviewCache(storageDir)
        cache.put("s1", OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "旧摘要")), Instant.now()), 1)

        // 重新生成：生成期间主会话追加消息
        val shown = runTrailGeneration(cache, "s1", sessionFile, { _, _ -> "prompt" }, runAgent = {
            Files.writeString(sessionFile, user("修复登录") + "\n" + user("生成期间的新要求") + "\n")
            "[MAIN] 新摘要"
        })

        assertTrue("展示层应拿到过期状态", shown.stale)
        assertFalse(shown.saveFailed)
        assertEquals("仍展示本次生成的内容", "新摘要", shown.nodes.single().text)
        assertEquals("内存缓存换成本次摘要", "新摘要", cache.get("s1")!!.result.nodes.single().text)
        assertEquals("磁盘缓存换成本次摘要", "新摘要", OverviewCache(storageDir).get("s1")!!.result.nodes.single().text)
        assertEquals("截止在生成开始时的第 1 行", 1L, cache.get("s1")!!.lineCount)
    }

    @Test
    fun `生成期间无新消息时展示层不标过期`() {
        val sessionFile = Files.createTempFile("session", ".jsonl")
        sessionFile.toFile().deleteOnExit()
        Files.writeString(sessionFile, user("修复登录"))

        val shown = storeOverview(
            OverviewCache(null), "s1",
            OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "摘要")), Instant.now()),
            sessionFile, TrailCursor(1, Files.size(sessionFile)),
        )

        assertFalse(shown.stale)
    }

    private fun allComponents(c: java.awt.Component): List<java.awt.Component> =
        listOf(c) + ((c as? java.awt.Container)?.components?.flatMap { allComponents(it) } ?: emptyList())

    private fun labelTexts(c: java.awt.Component) =
        allComponents(c).filterIsInstance<javax.swing.JLabel>().mapNotNull { it.text }

    @Test
    fun `空摘要落盘后会话追加消息再打开可以重新生成`() {
        val storageDir = Files.createTempDirectory("overview-cache-test")
        storageDir.toFile().deleteOnExit()
        val sessionFile = Files.createTempFile("session", ".jsonl")
        sessionFile.toFile().deleteOnExit()

        // 会话刚开始时打开总览：空摘要也会缓存并落盘
        storeOverview(OverviewCache(storageDir), "s1", OverviewResult(emptyList(), Instant.now()), sessionFile, TrailCursor(0, Files.size(sessionFile)))

        // 之后有了真实对话
        Files.writeString(sessionFile, user("帮我实现 JWT 认证"))

        // 重启 IDE 后再打开：从磁盘读回空摘要并判断过期
        val cached = OverviewCache(storageDir).get("s1")!!
        assertTrue(cached.result.nodes.isEmpty())
        val stale = isStale(cached, sessionFile)

        var regenerateClicks = 0
        val panel = buildResultPanel(cached.result, stale) { regenerateClicks++ }

        assertTrue("应显示过期提示", labelTexts(panel).contains(ImuxBundle.message("action.overview.stale")))
        val retry = allComponents(panel).filterIsInstance<com.intellij.ui.components.ActionLink>()
            .single { it.text == ImuxBundle.message("action.overview.retry") }
        retry.doClick()
        assertEquals("点击重新生成应触发生成", 1, regenerateClicks)
    }

    @Test
    fun `空摘要未过期时只显示会话刚开始提示`() {
        val panel = buildResultPanel(OverviewResult(emptyList(), Instant.now()), stale = false) {}

        assertTrue(labelTexts(panel).contains(ImuxBundle.message("action.overview.single.hint")))
        assertTrue("未过期时不需要重新生成入口",
            allComponents(panel).none { it is com.intellij.ui.components.ActionLink })
    }

    @Test
    fun `有内容的过期摘要也显示重新生成入口`() {
        var clicks = 0
        val panel = buildResultPanel(
            OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "实现 JWT")), Instant.now()), stale = true,
        ) { clicks++ }

        allComponents(panel).filterIsInstance<com.intellij.ui.components.ActionLink>().single().doClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `无旧结果的失败视图显示错误信息并可重新生成`() {
        var clicks = 0
        val panel = buildErrorPanel("CLI 退出码 1") { clicks++ }

        assertTrue(labelTexts(panel).contains(ImuxBundle.message("action.overview.failed")))
        assertEquals(
            "错误全文放在可选中复制的文本区里",
            "CLI 退出码 1",
            allComponents(panel).filterIsInstance<javax.swing.JTextArea>().single().text,
        )
        allComponents(panel).filterIsInstance<com.intellij.ui.components.ActionLink>().single().doClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `失败横幅带上错误信息并可重新生成`() {
        var clicks = 0
        val banner = buildFailedBanner("超时") { clicks++ }

        assertTrue(labelTexts(banner).contains(ImuxBundle.message("action.overview.failed.banner", "超时")))
        allComponents(banner).filterIsInstance<com.intellij.ui.components.ActionLink>().single().doClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `提示视图按需带操作链接`() {
        var clicks = 0
        val withAction = buildHintPanel("还没有轨迹", "生成轨迹" to { clicks++ })
        val link = allComponents(withAction).filterIsInstance<com.intellij.ui.components.ActionLink>().single()
        assertEquals("生成轨迹", link.text)
        assertTrue("链接不应抢走终端焦点", !link.isFocusable)
        link.doClick()
        assertEquals(1, clicks)

        val plain = buildHintPanel("会话尚未保存", null)
        assertTrue(labelTexts(plain).any { it.contains("会话尚未保存") })
        assertTrue(allComponents(plain).none { it is com.intellij.ui.components.ActionLink })
    }

    // ── 实际时间线布局（离屏、EDT） ──

    /**
     * 离屏递归布局。无窗口时 Container.validate() 不执行（没有 peer），组件也永远处于未验证状态，
     * 文本组件宽度变化后的失效不会向上传播。这里按真实 validate 的效果模拟：
     * 每轮先清空各层 LayoutManager2 的尺寸缓存，再自上而下 doLayout，多轮直到换行后的高度收敛。
     */
    private fun layoutOffscreen(root: javax.swing.JComponent, width: Int, height: Int): javax.swing.JPanel {
        val host = javax.swing.JPanel(java.awt.BorderLayout())
        host.add(root, java.awt.BorderLayout.CENTER)
        host.setSize(width, height)
        fun invalidateLayouts(c: java.awt.Component) {
            if (c !is java.awt.Container) return
            c.components.forEach { invalidateLayouts(it) }
            (c.layout as? java.awt.LayoutManager2)?.invalidateLayout(c)
        }
        fun layout(c: java.awt.Component) {
            if (c !is java.awt.Container) return
            c.doLayout()
            c.components.forEach { layout(it) }
        }
        repeat(4) { invalidateLayouts(host); layout(host) }
        return host
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        javax.swing.SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    // ── 横幅里的「重新生成」在窄卡片中必须完整可见 ──

    private val longCliError =
        "codex exited with code 1: error: unexpected argument '--dangerously-bypass-approvals-and-sandbox' found; " +
            "tip: to pass '--dangerously-bypass-approvals-and-sandbox' as a value, use '-- --dangerously-bypass-approvals-and-sandbox'"

    private val previousTrail =
        OverviewResult(listOf(OverviewNode(NodeSymbol.MAIN, "实现 JWT"), OverviewNode(NodeSymbol.MAIN, "补测试")), Instant.now())

    /**
     * 离屏布局后，链接按首选尺寸完整画出（没有被压扁），且整块落在横幅里、横幅又落在根面板里。
     * 只找到链接再 doClick 抓不到被挤到第二行、超出 NORTH 高度而被裁掉的情况。
     */
    private fun assertRetryLinkVisible(root: javax.swing.JComponent, width: Int, case: String) {
        val host = layoutOffscreen(root, width, 400)
        val banner = (root.layout as java.awt.BorderLayout).getLayoutComponent(java.awt.BorderLayout.NORTH)
        assertNotNull("$case：应有横幅", banner)
        val link = allComponents(banner).filterIsInstance<com.intellij.ui.components.ActionLink>()
            .single { it.text == ImuxBundle.message("action.overview.retry") }

        val linkRect = javax.swing.SwingUtilities.convertRectangle(link.parent, link.bounds, host)
        val bannerRect = javax.swing.SwingUtilities.convertRectangle(banner.parent, banner.bounds, host)
        val where = "$case @ ${width}px：link=$linkRect banner=$bannerRect"
        assertTrue("链接不能被压扁：$where", link.width >= link.preferredSize.width && link.height >= link.preferredSize.height)
        assertTrue("链接必须完整落在横幅可见区域内：$where", bannerRect.contains(linkRect))
        assertTrue("横幅必须完整落在面板内：$where", java.awt.Rectangle(0, 0, host.width, host.height).contains(bannerRect))

        // 链接紧跟在文字后面，而不是推到横幅最右侧；文字过长时截断的是文字
        val text = allComponents(banner).filterIsInstance<javax.swing.JLabel>()
            .single { it !is com.intellij.ui.components.ActionLink && it.icon == null }
        assertEquals(
            "链接应紧跟文字：$where text=${text.bounds}",
            text.x + text.width + com.intellij.util.ui.JBUI.scale(6),
            link.x,
        )
    }

    @Test
    fun `长错误文本的失败横幅在窄卡片里仍完整显示重新生成`() = onEdt {
        for (width in listOf(240, 360)) {
            assertRetryLinkVisible(buildFailedWithPrevious(longCliError, previousTrail) {}, width, "失败横幅")
        }
    }

    @Test
    fun `短文字时链接紧跟文字，右侧留空`() = onEdt {
        val root = buildResultPanel(previousTrail, stale = true) {}
        val host = layoutOffscreen(root, 800, 400)
        val banner = (root.layout as java.awt.BorderLayout).getLayoutComponent(java.awt.BorderLayout.NORTH)
        val link = allComponents(banner).filterIsInstance<com.intellij.ui.components.ActionLink>().single()
        val linkRect = javax.swing.SwingUtilities.convertRectangle(link.parent, link.bounds, host)
        val bannerRect = javax.swing.SwingUtilities.convertRectangle(banner.parent, banner.bounds, host)
        assertTrue("宽卡片里链接不应被推到最右侧：link=$linkRect banner=$bannerRect", linkRect.maxX < bannerRect.maxX - 100)
    }

    @Test
    fun `更新中进度在顶部，旧时间线照常可看，且不再提供更新入口`() = onEdt {
        var cancels = 0
        val view = buildUpdatingPanel(previousTrail) { cancels++ }
        view.progress.update("执行命令 sed -n '1143,1144p' /Users/x/.claude/projects/a.jsonl", 9)
        val root = view.component
        val host = layoutOffscreen(root, 360, 400)

        val north = (root.layout as java.awt.BorderLayout).getLayoutComponent(java.awt.BorderLayout.NORTH)
        assertTrue("进度放在顶部横幅里", javax.swing.SwingUtilities.isDescendingFrom(view.progress.component, north))
        val panes = allComponents(root).filterIsInstance<javax.swing.JTextPane>()
        assertEquals("旧时间线照常显示", listOf("实现 JWT", "补测试"), panes.map { it.text })
        val nodeRect = javax.swing.SwingUtilities.convertRectangle(panes.first().parent, panes.first().bounds, host)
        val bannerRect = javax.swing.SwingUtilities.convertRectangle(north.parent, north.bounds, host)
        assertTrue("旧时间线在进度横幅下方：node=$nodeRect banner=$bannerRect", nodeRect.y >= bannerRect.maxY && nodeRect.height > 0)

        val links = allComponents(root).filterIsInstance<com.intellij.ui.components.ActionLink>()
        assertEquals("只有取消，没有更新轨迹", listOf(ImuxBundle.message("action.overview.cancel")), links.map { it.text })
        links.single().doClick()
        assertEquals(1, cancels)
    }

    @Test
    fun `窄卡片里顶部进度仍看得到动作，耗时与取消完整显示`() = onEdt {
        for (width in listOf(240, 360)) {
            val view = buildUpdatingPanel(previousTrail) {}
            view.progress.update("执行命令 sed -n '1143,1144p' /Users/liuyuhua/.claude/projects/-Users-liuyuhua-github-imux/a.jsonl", 123)
            val host = layoutOffscreen(view.component, width, 400)
            val north = (view.component.layout as java.awt.BorderLayout).getLayoutComponent(java.awt.BorderLayout.NORTH)
            val banner = javax.swing.SwingUtilities.convertRectangle(north.parent, north.bounds, host)
            fun rect(c: java.awt.Component) = javax.swing.SwingUtilities.convertRectangle(c.parent, c.bounds, host)
            val cancel = allComponents(north).filterIsInstance<com.intellij.ui.components.ActionLink>().single()
            val where = "@${width}px banner=$banner action=${rect(view.progress.action)} elapsed=${rect(view.progress.elapsed)} cancel=${rect(cancel)}"

            assertTrue("动作文字仍要留出可读宽度：$where", view.progress.action.width >= com.intellij.util.ui.JBUI.scale(100))
            for (c in listOf(view.progress.elapsed, cancel)) {
                assertTrue("${c.javaClass.simpleName} 不能被压扁：$where", c.width >= c.preferredSize.width)
                assertTrue("${c.javaClass.simpleName} 要完整落在横幅内：$where", banner.contains(rect(c)))
            }
            assertTrue("耗时完整显示三位数：$where", view.progress.elapsed.text == elapsedText(123))
            assertTrue("动作在耗时左侧、不重叠：$where", rect(view.progress.action).maxX <= rect(view.progress.elapsed).x)
            assertTrue("耗时在取消左侧、不重叠：$where", rect(view.progress.elapsed).maxX <= rect(cancel).x)
        }
    }

    @Test
    fun `失败横幅截断的错误文字可以悬停查看全文`() = onEdt {
        val root = buildFailedWithPrevious(longCliError, previousTrail) {}
        layoutOffscreen(root, 240, 400)
        val banner = (root.layout as java.awt.BorderLayout).getLayoutComponent(java.awt.BorderLayout.NORTH)
        val message = allComponents(banner).filterIsInstance<javax.swing.JLabel>()
            .single { it.text == ImuxBundle.message("action.overview.failed.banner", longCliError) }
        assertEquals(longCliError, message.toolTipText)
    }

    @Test
    fun `过期与保存失败横幅在窄卡片里仍完整显示重新生成`() = onEdt {
        for (width in listOf(240, 360)) {
            assertRetryLinkVisible(buildResultPanel(previousTrail, stale = true) {}, width, "过期横幅")
            assertRetryLinkVisible(buildResultPanel(previousTrail.copy(saveFailed = true), stale = false) {}, width, "保存失败横幅")
        }
    }

    @Test
    fun `没有旧结果时超长报错也不会把重新生成挤出卡片`() = onEdt {
        val stackTrace = (1..80).joinToString("\n") { "    at com.example.cli.Runner.step$it(Runner.kt:$it)" }
        for (width in listOf(240, 360)) {
            val root = buildErrorPanel("codex exited with code 1\n$stackTrace") {}
            val host = layoutOffscreen(root, width, 200)
            val link = allComponents(root).filterIsInstance<com.intellij.ui.components.ActionLink>()
                .single { it.text == ImuxBundle.message("action.overview.retry") }
            val linkRect = javax.swing.SwingUtilities.convertRectangle(link.parent, link.bounds, host)
            val where = "@ ${width}px：link=$linkRect host=${host.size}"
            assertTrue("链接不能被压扁：$where", link.width >= link.preferredSize.width && link.height >= link.preferredSize.height)
            assertTrue("链接必须完整落在卡片内：$where", java.awt.Rectangle(0, 0, host.width, host.height).contains(linkRect))
        }
    }

    private data class NodeLayout(
        val viewportWidth: Int,
        val timelineWidth: Int,
        val nodeHeight: Int,
        val nodeBottom: Int,
        val nextTop: Int,
        val secondNodeTop: Int,
        val connectorBottom: Int,
        val textSize: java.awt.Dimension,
        val textBottomInNode: Int,
        val endRect: java.awt.geom.Rectangle2D,
        val lineHeight: Int,
    )

    private fun measureTimeline(longText: String, width: Int): NodeLayout = onEdt {
        val panel = buildResultPanel(
            OverviewResult(
                listOf(OverviewNode(NodeSymbol.MAIN, longText), OverviewNode(NodeSymbol.MAIN, "第二个节点")),
                Instant.now(),
            ),
            stale = false,
        ) {}
        layoutOffscreen(panel, width, 400)

        val scroll = allComponents(panel).filterIsInstance<javax.swing.JScrollPane>().single()
        val timeline = scroll.viewport.view as ScrollablePanel
        val node = timeline.getComponent(0) as java.awt.Container
        val connector = timeline.getComponent(1)
        val secondNode = timeline.getComponent(2)
        val text = node.components.filterIsInstance<javax.swing.JTextPane>().single()
        NodeLayout(
            viewportWidth = scroll.viewport.width,
            timelineWidth = timeline.width,
            nodeHeight = node.height,
            nodeBottom = node.y + node.height,
            nextTop = connector.y,
            secondNodeTop = secondNode.y,
            connectorBottom = connector.y + connector.height,
            textSize = text.size,
            textBottomInNode = text.y + text.height,
            endRect = text.modelToView2D(text.document.length),
            lineHeight = text.getFontMetrics(text.font).height,
        )
    }

    private fun assertWholeNodeVisible(l: NodeLayout, width: Int) {
        assertTrue("viewport 应有宽度 ($width): ${l.viewportWidth}", l.viewportWidth > 0)
        assertTrue("viewport 不应超过容器 ($width): ${l.viewportWidth}", l.viewportWidth <= width)
        assertEquals("时间线宽度应等于 viewport 宽度 ($width)", l.viewportWidth, l.timelineWidth)
        assertTrue("文本组件应有尺寸 ($width): ${l.textSize}", l.textSize.width > 0 && l.textSize.height > 0)
        assertTrue("文本组件不应超出时间线宽度 ($width)", l.textSize.width <= l.timelineWidth)
        assertTrue("长正文应换成多行 ($width): 高 ${l.textSize.height}，单行 ${l.lineHeight}",
            l.textSize.height >= l.lineHeight * 2)
        assertTrue("正文末尾应在文本组件可见范围内 ($width): ${l.endRect} / ${l.textSize}",
            l.endRect.maxY <= l.textSize.height && l.endRect.maxX <= l.textSize.width && l.endRect.y >= 0)
        assertTrue("文本组件应完整落在节点内 ($width)", l.textBottomInNode <= l.nodeHeight)
        assertTrue("连接线不应与节点重叠 ($width): ${l.nextTop} < ${l.nodeBottom}", l.nextTop >= l.nodeBottom)
        assertTrue("下一节点不应与连接线重叠 ($width)", l.secondNodeTop >= l.connectorBottom)
    }

    @Test
    fun `长中文节点在 500 宽和 300 宽下完整换行且下一节点不重叠`() {
        val longText = "这是一段很长的中文节点描述，用于测试文本在弹窗宽度限制下是否正确换行而不被裁掉。".repeat(4) +
            "末尾结论：全部验证通过"

        val wide = measureTimeline(longText, 500)
        assertWholeNodeVisible(wide, 500)

        val narrow = measureTimeline(longText, 300)
        assertWholeNodeVisible(narrow, 300)

        assertTrue("缩窄后 viewport 应变窄: ${narrow.viewportWidth} vs ${wide.viewportWidth}",
            narrow.viewportWidth < wide.viewportWidth)
        assertTrue("缩窄后节点应增高: ${narrow.nodeHeight} vs ${wide.nodeHeight}",
            narrow.nodeHeight > wide.nodeHeight)
    }

    // ── 超过 128000 字符的单条记录 ──

    private fun hugeLog(reason: String) =
        (1..4_000).joinToString("\n") { "[INFO] compiling module $it ..........................." } + "\nBUILD FAILED: $reason"

    @Test
    fun `导航器仍保持 128000 字符的单行上限`() {
        val line = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":${com.google.gson.JsonPrimitive(hugeLog("x"))}}]}}"""
        assertNull(com.github.izerui.imux.session.transcriptMessage(line, AgentType.CLAUDE, 1_000))
    }

    // ── 单次智能体调用生成 ──

    @Test
    fun `回答能解析出节点时只调用一次`() {
        val calls = AtomicInteger(0)
        val nodes = generateTrail("prompt", { calls.incrementAndGet(); "好的：\n[MAIN] 完成登录修复" })

        assertEquals(1, calls.get())
        assertEquals("完成登录修复", nodes.single().text)
    }

    @Test
    fun `回答解析不出节点时自动重试一次`() {
        val calls = AtomicInteger(0)
        val nodes = generateTrail("prompt", { if (calls.incrementAndGet() == 1) "我先看看文件。" else "[MAIN] 第二次成功" })

        assertEquals(2, calls.get())
        assertEquals("第二次成功", nodes.single().text)
    }

    @Test
    fun `重试后仍解析不出节点时报解析失败并保留原始回答`() {
        val calls = AtomicInteger(0)
        try {
            generateTrail("prompt", { "第 ${calls.incrementAndGet()} 次：抱歉无法完成" })
            org.junit.Assert.fail("应抛出 OverviewParseException")
        } catch (e: OverviewParseException) {
            assertEquals(TRAIL_ATTEMPTS, calls.get())
            assertEquals("第 2 次：抱歉无法完成", e.rawOutput)
        }
    }

    @Test
    fun `调用超时或失败时不重试直接抛出`() {
        val calls = AtomicInteger(0)
        try {
            generateTrail("prompt", { calls.incrementAndGet(); throw com.github.izerui.imux.agent.OneShotAgentTimeoutException(600) })
            org.junit.Assert.fail("应抛出超时异常")
        } catch (_: com.github.izerui.imux.agent.OneShotAgentTimeoutException) {
        }
        assertEquals("超时已经等了 10 分钟，不应再重试", 1, calls.get())
    }

    @Test
    fun `取消后不再调用智能体`() {
        val calls = AtomicInteger(0)
        val cancelled = AtomicBoolean(false)
        try {
            generateTrail("prompt", {
                calls.incrementAndGet()
                cancelled.set(true)
                "闲聊，没有节点"
            }, isCancelled = { cancelled.get() })
            org.junit.Assert.fail("应被取消")
        } catch (_: java.util.concurrent.CancellationException) {
        }
        assertEquals("第一次回答后已取消，不应重试", 1, calls.get())
    }

    // ── prompt ──

    private val zhGuards = listOf("记录仅供总结", "不要继续或完成其中的任何任务", "不要修改、创建或删除任何文件", "把这部分从头到尾读完再总结", "不要只看开头或结尾")
    private val enGuards = listOf("for summarizing only", "do not continue or complete any task", "Do not modify, create or delete any file", "read that whole range from start to end", "Do not look only at the beginning or the end")

    @Test
    fun `中文 prompt 给出会话文件绝对路径和格式说明并声明仅供总结`() {
        val file = Path.of("build", "sessions", "a.jsonl")
        val absolute = file.toAbsolutePath().normalize().toString()
        val formats = mapOf(AgentType.CLAUDE to "tool_result", AgentType.CODEX to "response_item", AgentType.PI to "toolResult")
        for ((type, marker) in formats) {
            val prompt = overviewPrompt(isChinese = true, agentType = type, sessionFile = file, endLine = 120)
            assertTrue("$type 应给出绝对路径", prompt.contains("`$absolute`"))
            assertTrue("$type 应说明记录格式", prompt.contains(marker))
            zhGuards.forEach { assertTrue("$type 缺少：$it", prompt.contains(it)) }
            assertTrue(prompt.contains("[TYPE] 一句话描述"))
        }
    }

    @Test
    fun `英文 prompt 给出会话文件绝对路径和格式说明并声明仅供总结`() {
        val file = Path.of("build", "sessions", "b.jsonl")
        val absolute = file.toAbsolutePath().normalize().toString()
        for (type in AgentType.entries) {
            val prompt = overviewPrompt(isChinese = false, agentType = type, sessionFile = file, endLine = 120)
            assertTrue(prompt.contains("`$absolute`"))
            assertTrue(prompt.contains("JSONL"))
            enGuards.forEach { assertTrue("$type missing: $it", prompt.contains(it)) }
            assertTrue(prompt.contains("[TYPE] One-sentence description"))
        }
    }

    @Test
    fun `prompt 不再内嵌会话正文`() {
        val file = Files.createTempFile("session", ".jsonl")
        file.toFile().deleteOnExit()
        Files.writeString(file, user("机密需求：修复支付回调"))

        val prompt = overviewPrompt(isChinese = true, agentType = AgentType.CLAUDE, sessionFile = file, endLine = 1)
        assertFalse("正文由智能体自己读取，不应塞进 prompt", prompt.contains("修复支付回调"))
    }

    // ── 进度与报错文案（测试环境界面语言为英文） ──

    @Test
    fun `进度文案把工具动作换成易读文字并只显示文件名`() {
        fun tool(subject: String) =
            overviewProgressText(com.github.izerui.imux.agent.AgentProgressEvent(com.github.izerui.imux.agent.AgentProgressKind.TOOL_STARTED, subject))

        assertEquals("Reading session.jsonl", tool("Read: /Users/me/.claude/projects/-work/abc.jsonl".replace("abc", "session")))
        assertEquals("Reading s.jsonl", tool("read: C:\\Users\\me\\s.jsonl"))
        assertEquals("Searching role\":\"user", tool("Grep: role\":\"user"))
        assertEquals("Finding files *.jsonl", tool("Glob: *.jsonl"))
        assertEquals("命令里有空格，原样保留", "Running command rg -n user /tmp/a.jsonl", tool("Bash: rg -n user /tmp/a.jsonl"))
        assertEquals("Running command rg -n x", tool("shell: rg -n x"))
        assertEquals("未知工具保留名字", "Running mcp_lint src", tool("mcp_lint: src"))
        assertEquals("Running tool", tool(""))
    }

    @Test
    fun `其他进度事件的文案`() {
        fun text(kind: com.github.izerui.imux.agent.AgentProgressKind) =
            overviewProgressText(com.github.izerui.imux.agent.AgentProgressEvent(kind, "Grep"))
        assertEquals("Writing the timeline...", text(com.github.izerui.imux.agent.AgentProgressKind.RESPONDING))
        assertNull("工具完成不换文字，保留上一条动作", text(com.github.izerui.imux.agent.AgentProgressKind.TOOL_FINISHED))
        assertNull(text(com.github.izerui.imux.agent.AgentProgressKind.COMPLETED))
        assertEquals("35s", elapsedText(35))
    }

    @Test
    fun `加载行的宽度不随动作文字长短变化且耗时始终完整可见`() {
        val long = "Running command " + "F=/Users/liuyuhua/.claude/projects/-Users-liuyuhua-github-imux/".repeat(20)
        onEdt {
            val shortRow = LoadingRow().apply { update("Reading s.jsonl", 3) }
            val longRow = LoadingRow().apply { update(long, 3) }
            assertEquals("最小宽度不能随文字变长：否则会把弹窗撑宽", shortRow.component.minimumSize.width, longRow.component.minimumSize.width)
            assertEquals("首选宽度也不能随文字变长", shortRow.component.preferredSize.width, longRow.component.preferredSize.width)
            assertTrue("整行首选宽度应远小于弹窗宽度", longRow.component.preferredSize.width < JBUI_500())

            for (width in listOf(500, 300)) {
                val row = LoadingRow().apply { update(long, 1234) }
                layoutOffscreen(row.component, width, 400)
                val elapsedRight = javax.swing.SwingUtilities.convertPoint(row.elapsed, row.elapsed.width, 0, row.component).x
                assertTrue("$width 宽：耗时应完整显示", row.elapsed.width >= row.elapsed.preferredSize.width)
                assertTrue("$width 宽：耗时不能超出弹窗（$elapsedRight）", elapsedRight <= width)
                assertTrue("$width 宽：动作文字被截断而不是撑宽", row.action.width < row.action.getFontMetrics(row.action.font).stringWidth(long))
                assertTrue("$width 宽：只占一行", row.action.height <= row.action.getFontMetrics(row.action.font).height * 2)
                assertEquals("悬停可看全文", long, row.action.toolTipText)
            }
        }
    }

    @Test
    fun `加载行作为一组居中且耗时紧跟在文字后面`() {
        onEdt {
            for (text in listOf("Starting the agent...", "Reading session.jsonl")) {
                val row = LoadingRow().apply { update(text, 6) }
                layoutOffscreen(row.component, 500, 400)
                val left = row.spinner.x
                val right = 500 - (row.elapsed.x + row.elapsed.width)
                assertTrue("左右留白应相等（左 $left，右 $right）", kotlin.math.abs(left - right) <= 2)
                assertTrue("不应贴着左边（$left）", left > 100)
                val gapToElapsed = row.elapsed.x - (row.action.x + row.action.width)
                assertTrue("耗时应紧跟文字，不能被推到最右边（间距 $gapToElapsed）", gapToElapsed <= com.intellij.util.ui.JBUI.scale(8))
                assertEquals("短文字完整显示，不截断", row.action.preferredSize.width, row.action.width)
                assertEquals("垂直居中", 400 / 2, row.action.y + row.action.height / 2, 1.0)
            }
        }
    }

    private fun assertEquals(message: String, expected: Int, actual: Int, delta: Double) =
        org.junit.Assert.assertEquals(message, expected.toDouble(), actual.toDouble(), delta)

    private fun JBUI_500() = com.intellij.util.ui.JBUI.scale(500)

    @Test
    fun `超时与解析失败显示可读提示其他错误显示原因`() {
        assertEquals(
            "The AI did not finish in time. Try regenerating later.",
            overviewErrorText(com.github.izerui.imux.agent.OneShotAgentTimeoutException(600)),
        )
        assertEquals(
            "Could not parse the AI response into a timeline (retried once). Try regenerating.",
            overviewErrorText(OverviewParseException("x")),
        )
        assertEquals("command not found: claude", overviewErrorText(com.github.izerui.imux.agent.OneShotAgentException("command not found: claude")))

        val zh = com.github.izerui.imux.settings.PluginLanguage.SIMPLIFIED_CHINESE
        assertEquals("AI 响应超时，请稍后再更新", ImuxBundle.message(zh, "action.overview.failed.timeout"))
        assertEquals("正在执行 Read", ImuxBundle.message(zh, "action.overview.progress.tool", "Read"))
        assertEquals("35 秒", ImuxBundle.message(zh, "action.overview.progress.elapsed", 35))
    }
}

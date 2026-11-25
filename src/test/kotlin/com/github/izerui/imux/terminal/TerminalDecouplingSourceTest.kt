package com.github.izerui.imux.terminal

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 终端只认识通用的附加启动配置：要带哪部分引导词由各功能在 [TerminalLaunchContribution.guidance] 里声明，
 * 终端不能回到「按服务名认功能」的写法（以前靠 `servers.any { it.name == "imux_code" }` 判断）。
 */
class TerminalDecouplingSourceTest {
    private val terminalSources = File("src/main/kotlin/com/github/izerui/imux/terminal").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `终端包的每个源文件都不认识查代码工具的服务名，也不引用查代码功能包`() {
        assertTrue("找不到终端包源文件，下面的断言会恒真", terminalSources.size > 5)
        assertTrue("子目录没扫到", terminalSources.any { it.parentFile.name != "terminal" })
        for (file in terminalSources) {
            val code = SourceCode(file.path).normalized
            assertFalse(file.name, code.contains("CODE_TOOLS_SERVER"))
            assertFalse(file.name, code.contains("\"imux_code\""))
            assertFalse(file.name, code.contains("com.github.izerui.imux.code."))
        }
    }

    @Test
    fun `终端按启动配置里声明的引导能力拼引导词`() {
        val host = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt")
        assertTrue(host.normalized.contains("capabilities = launchContributions[tabId]?.guidance.orEmpty()"))
    }

    /**
     * 同一份引导词不能原样发给所有角色：副驾驶被禁止改动，却曾经拿到教它重命名、跑构建、执行 SQL 的完整版；
     * 会话报告只读一个记录文件，却曾经带着整套 IDE 工具说明。
     */
    @Test
    fun `副驾驶和需求澄清拿只查不改的引导词，会话报告什么都不挂，会话终端拿完整版`() {
        val peer = SourceCode("src/main/kotlin/com/github/izerui/imux/peer/PeerCoordinator.kt").normalized
        assertTrue(peer.contains("capabilities = codeTools?.guidance.orEmpty(), role = GuidanceRole.INVESTIGATOR"))
        val oneShot = SourceCode("src/main/kotlin/com/github/izerui/imux/agent/OneShotAgentCommand.kt").normalized
        assertTrue(oneShot.contains("configuredAgentGuidance(endpoint != null, codeTools?.guidance.orEmpty(), GuidanceRole.INVESTIGATOR)"))
        val clarify = SourceCode("src/main/kotlin/com/github/izerui/imux/clarification/ClarificationService.kt").normalized
        assertTrue(clarify.contains("AgentMcpConfig.investigator(project, projectPath, agentType)"))
        val report = SourceCode("src/main/kotlin/com/github/izerui/imux/overview/ProgressOverview.kt").normalized
        assertTrue(report.contains("AgentMcpConfig.NONE,"))
        assertFalse(report.contains("AgentMcpConfig.investigator"))
        val host = SourceCode("src/main/kotlin/com/github/izerui/imux/terminal/TerminalHost.kt").normalized
        assertFalse("会话终端用默认的干活角色", host.contains("GuidanceRole.INVESTIGATOR"))
    }

    @Test
    fun `审查意见带上固定开头再发进主会话，记进结果的仍是意见原文`() {
        val peer = SourceCode("src/main/kotlin/com/github/izerui/imux/peer/PeerCoordinator.kt").normalized
        assertTrue(peer.contains("val message = peerFeedbackMessage(feedback, ImuxBundle.currentLanguage().isChinese())"))
        assertTrue(peer.contains("if (injectFeedback(mainSessionKey, message, run, guard)) { recordOutcome(mainSessionKey, run, outcome(PeerOutcomeKind.FEEDBACK), feedback) }"))
    }

    /**
     * 文件大纲去噪音的三条规则只对 JavaScript 一族生效：早先对所有语言生效时，Python 带父类的类（显示名 `User(Base)`）
     * 方法全丢，名字带括号的 YAML/JSON 键下级全丢。大纲的整理要在 IDE 里跑，这里钉住三条规则都带着语言判断。
     */
    @Test
    fun `文件大纲的去噪音规则只对 JavaScript 一族生效、遍历有预算、按列号区分重载、父类按位置读`() {
        val nav = SourceCode("src/main/kotlin/com/github/izerui/imux/code/CodeNavigator.kt").normalized
        assertTrue("函数体不展开", nav.contains("if (script && insideOf != null && insideOf.contains(range)) continue"))
        assertTrue("赋值语句", nav.contains("if (script && leaf && isReference(value)) {"))
        assertTrue("对象字面量字段", nav.contains("if (script && underAnonymous && leaf && lineOf(range.startOffset) == lineOf(range.endOffset)) continue"))
        assertTrue("带括号才算函数也限于这一族", nav.contains("val bodyRange = if (script && name.contains('(')) range else null"))
        assertTrue(nav.contains("generateSequence(element.language) { it.baseLanguage }.any { it.id == \"JavaScript\" }"))
        assertTrue("每取一个节点都检查预算，挡住同层几万项的扁平文件", nav.contains("if (++visited > OUTLINE_MAX_NODES || System.nanoTime() > outlineDeadline) {"))
        assertTrue("无名节点也计入遍历层数，挡住连续的无名节点", nav.contains("walk(tree, grandchildren, level + 1, true, insideOf, out)"))
        assertTrue("同时给名字和列号时按列号挑", nav.contains(".sortedBy { offset -> query.column?.let { kotlin.math.abs(offset - (lineStart + it - 1)) } ?: 0 }"))
        assertTrue("只给有成员、显示名带括号、或用 extends/implements 的节点标父类，宁可少标不标错",
            nav.contains("(item.structureChildren.isNotEmpty() || name.contains('(') || inheritsByKeyword(value, document))"))
        assertTrue("父类一律按位置读，不搜索", nav.contains("when (val found = safeSupers(value, name, document, memberRanges)) {"))
        assertTrue("JS 一族名字后紧跟冒号的是类型注解", nav.contains("if (k < range.endOffset && chars[k] == ':') return emptyList()"))
        assertTrue("建索引中途打断要注明", nav.contains("if (!supersAvailable || supersInterrupted) addProperty(\"supers_skipped\""))
        assertFalse("不再有搜索确认那条路", nav.contains("verifiedSupers"))
        assertFalse("标父类不再设时限（结果会因 IDE 缓存冷热而不同）", nav.contains("OUTLINE_SUPERS_BUDGET_NANOS"))
        assertTrue("建索引时不标父类，大纲照常给", nav.contains("val supersAvailable = !DumbService.isDumb(project)"))
        assertTrue("标父类出意外不拖垮整个大纲", nav.contains("writtenSupers(element, displayName, document, memberRanges) } catch (e: com.intellij.openapi.project.IndexNotReadyException) {"))
        assertTrue("语言插件给的坏引用位置只跳过这一个", nav.contains("val absolute = try { reference.absoluteRange } catch (e: IllegalArgumentException) { continue }"))
        assertTrue("不是代码元素的分组节点不整棵丢掉", nav.contains("walk(tree, childrenOf(tree), level + 1, underAnonymous, insideOf, out)"))
        assertTrue("名额不够时按层挑，先给全顶层", nav.contains("while (queue.isNotEmpty() && kept.size < limit) {"))
        assertTrue("顶层没装下要说明", nav.contains("roots.count { it !in kept }.takeIf { it > 0 }?.let { addProperty(\"top_level_omitted\", it) }"))
        assertTrue("下级没给全要说明", nav.contains("if (shown.size < item.children.size) addProperty(\"children_omitted\", item.children.size - shown.size)"))
        assertTrue("查用法顺着别名导入继续找", nav.contains("for ((aliasUse, alias) in aliasUses(reference, targetName)) {"))
        assertTrue("查调用方顺着别名导入继续找", nav.contains("for ((aliasUse, alias) in aliasUses(reference, (searched as? PsiNamedElement)?.name)) {"))
        assertTrue("只认导入语句里的别名", nav.contains("text.startsWith(\"import \") || text.startsWith(\"from \")"))
        assertTrue("一条语句导入多个名字时分不清就不跟", nav.contains("if (alias != null && statement.text.contains(',')) return emptyList()"))
        assertTrue("认不出名字时如实说明", nav.contains("\"unresolved\","))
    }

    /**
     * 按名字找「它可能实现了谁」时，曾把项目里 `.verify` 下 IDE 副本中几兆的打包 JS 也拿来建结构视图：
     * 一个文件几十秒，读锁被写操作打断又从头建，查一个 Python 函数的用法直到超时都回不来。
     */
    @Test
    fun `按名字找重写对象只看同语言、项目自己的、不超大的文件`() {
        val nav = SourceCode("src/main/kotlin/com/github/izerui/imux/code/CodeNavigator.kt").normalized
        assertTrue("别的语言跳过", nav.contains("if (file.viewProvider.languages.none { rootLanguage(it) == language } ||"))
        assertTrue("超大文件跳过", nav.contains("vf.length > SUPER_SCAN_MAX_FILE_BYTES ||"))
        assertTrue("隐藏目录与说明文件跳过", nav.contains("|| isProseOrHidden(vf)) {"))
        assertTrue("方言归到同一种语言", nav.contains("return generateSequence(language) { it.baseLanguage }.last()"))
    }

    @Test
    fun `用法条目和声明节点都标测试代码，且只问 IDE`() {
        val nav = SourceCode("src/main/kotlin/com/github/izerui/imux/code/CodeNavigator.kt").normalized
        assertTrue("用法条目", nav.contains("if (PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) != null) addProperty(\"in_comment\", true) markTest(file)"))
        assertTrue("声明节点", nav.contains("file.virtualFile?.let { addProperty(\"in_project\", fileIndex.isInContent(it)) } markTest(file)"))
        assertTrue("判断交给 IDE", nav.contains("if (TestSourcesFilter.isTestSources(vf, project)) addProperty(\"in_test\", true)"))
    }

    @Test
    fun `框架隐式使用、读写、对应测试都只问 IDE`() {
        val nav = SourceCode("src/main/kotlin/com/github/izerui/imux/code/CodeNavigator.kt").normalized
        assertTrue("查用法标隐式使用", nav.contains("if (implicitlyUsed(target)) addProperty(\"implicit_usage\", true)"))
        assertTrue("调用链只在查询目标和链条断开处标隐式使用", nav.contains("if ((path.size == 1 || chainEndsHere) && implicitlyUsed(element)) node.addProperty(\"implicit_usage\", true)"))
        assertTrue("链条断开指三类都没有", nav.contains("val chainEndsHere = callers.size() == 0 && referencedBy.size() == 0 && loose.size() == 0"))
        assertTrue("隐式使用问插件", nav.contains("provider.isImplicitUsage(element) || provider.isImplicitRead(element) || provider.isImplicitWrite(element)"))
        assertTrue("读写只对分得出的符号标", nav.contains("ReadWriteAccessDetector.findDetector(target)?.takeIf { it.isReadWriteAccessible(target) }"))
        assertTrue("别名用法不标读写", nav.contains("access?.let { accessOf(it, target, reference) }?.let { site.addProperty(\"access\", it) }"))
        assertTrue("对应测试走 IDE 的跳到测试", nav.contains("if (finder.isTest(source)) tested += finder.findClassesForTest(source) else tests += finder.findTestsForClass(source)"))
        assertTrue("变量、字段、参数不配测试", nav.contains("if (access == null) addRelatedTests(target)"))
        assertTrue("嵌在里面的类型改用最外层的类型配", nav.contains("else generateSequence(found) { it.parent }.firstOrNull { it.parent is PsiFile } ?: continue"))
        assertTrue("超出名额的按去重后的数目说明", nav.contains("omitted += maxOf(items.size - RELATED_TESTS_LIMIT, 0)"))
        assertTrue("文件和类重复报时只留类", nav.contains("return valid.filter { item -> item !is PsiFile || valid.none { it !is PsiFile && it.containingFile == item } }"))
    }

    /**
     * PyCharm、GoLand 等没有 Java 插件：引用 Java 插件类的代码一旦被加载就会失败，查代码工具整个不可用。
     * 所以 Java 插件的东西只能出现在可选配置 imux-java.xml 登记的那一个类里。
     * 这里只钉住最可能被顺手引用的几个包（UAST、未使用检查、Java 代码结构）；完整的把关是对 PyCharm 跑插件验证器，
     * 它会报出任何在没有 Java 插件时解析不了的引用。
     */
    @Test
    fun `UAST、未使用检查和 Java 代码结构的类只在可选配置登记的那一个文件里引用`() {
        val javaOnly = Regex("""import (org\.jetbrains\.uast|com\.intellij\.codeInspection\.deadCode|com\.intellij\.lang\.java|com\.intellij\.psi\.(JavaPsiFacade|PsiClass|PsiMethod|PsiField|PsiModifierListOwner|PsiJavaFile|PsiMember)\b)""")
        val offenders = java.io.File("src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.endsWith("code/java/JavaImplicitUsageSupplement.kt") }
            .filter { javaOnly.containsMatchIn(it.readText()) }
            .map { it.path }.toList()
        assertEquals(emptyList<String>(), offenders)
        val supplement = SourceCode("src/main/kotlin/com/github/izerui/imux/code/java/JavaImplicitUsageSupplement.kt").normalized
        assertTrue("那个文件确实用到了 Java 插件（断言不是空转）", javaOnly.containsMatchIn(supplement))
    }

    @Test
    fun `Java 补充判断只在可选依赖里登记，查询时会问它，出错只当不知道`() {
        val plugin = SourceCode("src/main/resources/META-INF/plugin.xml").normalized
        assertTrue("可选依赖 Java 插件", plugin.contains("<depends optional=\"true\" config-file=\"imux-java.xml\">com.intellij.java</depends>"))
        assertTrue("扩展点", plugin.contains("qualifiedName=\"com.github.izerui.imux.implicitUsageSupplement\""))
        assertFalse("主配置里不能直接登记实现", plugin.contains("JavaImplicitUsageSupplement"))
        val optional = SourceCode("src/main/resources/META-INF/imux-java.xml").normalized
        assertTrue("实现登记在可选配置里", optional.contains("<implicitUsageSupplement implementation=\"com.github.izerui.imux.code.java.JavaImplicitUsageSupplement\"/>"))
        val nav = SourceCode("src/main/kotlin/com/github/izerui/imux/code/CodeNavigator.kt").normalized
        assertTrue("查询时问补充判断", nav.contains("} || ImplicitUsageSupplement.anyImplicitlyUsed(element)"))
        val supplement = SourceCode("src/main/kotlin/com/github/izerui/imux/code/ImplicitUsageSupplement.kt").normalized
        assertTrue("取消原样抛出", supplement.contains("} catch (e: ProcessCanceledException) { throw e } catch (e: CancellationException) { throw e }"))
        assertTrue("链接错误（类找不到、签名不符）只当不知道", supplement.contains("} catch (_: LinkageError) { false }"))
    }

    @Test
    fun `文件大纲不等索引，其它查询要等索引`() {
        val service = SourceCode("src/main/kotlin/com/github/izerui/imux/code/CodeMcpService.kt").normalized
        assertTrue(service.contains("run(file, needsIndex = false) { CodeNavigator(project).fileOutline(file, limit) }"))
        assertTrue(service.contains("if (needsIndex && DumbService.isDumb(project))"))
    }
}

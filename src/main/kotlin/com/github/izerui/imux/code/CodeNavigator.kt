package com.github.izerui.imux.code

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.findUsages.EmptyFindUsagesProvider
import com.intellij.lang.findUsages.LanguageFindUsages
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.ElementDescriptionUtil
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.UsageSearchContext
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageViewShortNameLocation
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.Processor
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Path

/**
 * 用 IDE 自己的引用索引查「谁用了它」「它用了谁」，对 IDE 能解析的每一种语言都成立。
 *
 * 为什么不直接让智能体用 IDEA MCP 的 `analyze_calls`：那个工具先要把名字换算成全限定名
 * （依赖语言插件注册 `qualifiedNameProvider`），Go 等插件没有注册，任何写法都返回
 * 「No callable symbol found」，而 IDE 自己的「查找用法」对这些语言照样好用。这里改成
 * **按文件和行列定位**符号，再走平台通用的 [ReferencesSearch] / [DefinitionsScopedSearch]，
 * 绕开名字换算；「在哪个函数里」用语言自带的结构视图判定，同样不依赖某一门语言。
 *
 * 全部函数都要求调用方已持有读锁（见 [CodeMcpService] 的 smartReadAction）。
 */
internal class CodeNavigator(private val project: Project) {
    private val outlines = HashMap<PsiFile, List<PsiElement>>()
    private val superCache = HashMap<Pair<PsiElement, Boolean>, List<PsiElement>>()
    /** 按名字扫同名候选时撞上限、上层可能没找全的元素；结果里标 `overrides_incomplete`。 */
    private val supersIncomplete = HashSet<PsiElement>()
    private val unanalyzedCache = HashMap<String, Pair<JsonArray, Boolean>>()
    /**
     * 整次查询共用的扫描时限：按名字找重写对象、扫「分析不了的文件」都按名字走单词索引，调用链上每个节点各扫一次，
     * 节点一多就会把整次查询拖过总超时、一点结果都拿不到。超过这个时限后不再扫，结果里如实标出没查的地方。
     */
    private val scanDeadline = System.nanoTime() + QUERY_SCAN_BUDGET_NANOS
    private fun scanTimeLeft() = System.nanoTime() < scanDeadline
    private val fileIndex = ProjectFileIndex.getInstance(project)

    fun references(query: CodeQuery): CodeToolResult {
        val target = when (val located = locate(query)) {
            is Located.Found -> located.element
            is Located.Failed -> return located.result
        }
        val scope = scope(query.includeLibraries)
        val references = JsonArray()
        var truncated = false
        ReferencesSearch.search(target, scope).forEach(Processor { reference ->
            if (!inScope(reference, scope) || isSelfReference(target, reference)) return@Processor true
            if (references.size() >= query.limit) { truncated = true; return@Processor false }
            usageSite(reference.element, reference.rangeInElement)?.let(references::add)
            true
        })
        val implementations = JsonArray()
        DefinitionsScopedSearch.search(target, scope).forEach(Processor { definition ->
            if (implementations.size() >= query.limit) { truncated = true; return@Processor false }
            if (definition != target) implementations.add(describe(definition))
            true
        })
        // 通过接口、父类引用它的地方（`greeter.greet()` 的变量类型是接口）记在上层声明名下，要单独补上。
        val supers = supers(target)
        val viaSupers = JsonArray()
        // 「经由上层调用」只对方法成立：类型的父类型被别处用到，不是对这个子类的使用。
        for (parent in memberSupers(target)) {
            ReferencesSearch.search(parent, scope).forEach(Processor { reference ->
                if (!inScope(reference, scope) || isSelfReference(parent, reference)) return@Processor true
                if (viaSupers.size() >= query.limit) { truncated = true; return@Processor false }
                usageSite(reference.element, reference.rangeInElement)?.let { site ->
                    site.addProperty("via", "${nameOf(parent)} (${kindOf(parent)})")
                    viaSupers.add(site)
                }
                true
            })
        }
        return CodeToolResult(JsonObject().apply {
            add("target", describe(target))
            addProperty("reference_count", references.size())
            add("references", references)
            if (viaSupers.size() > 0) add("references_via_overridden", viaSupers)
            if (supers.isNotEmpty()) add("overrides", JsonArray().apply { supers.forEach { add(describe(it)) } })
            if (target in supersIncomplete) addProperty("overrides_incomplete", true)
            if (implementations.size() > 0) add("overridden_by", implementations)
            addUnanalyzedMentions(target, scope)
            if (truncated) addProperty("truncated", true)
        })
    }

    /** 继承关系：supers 列出它重写、实现、继承的上层；implementations 列出重写、实现、继承它的下层。 */
    fun typeHierarchy(query: CodeQuery, direction: HierarchyDirection): CodeToolResult {
        val target = when (val located = locate(query)) {
            is Located.Found -> located.element
            is Located.Failed -> return located.result
        }
        val items = JsonArray()
        var truncated = false
        when (direction) {
            HierarchyDirection.SUPERS -> supers(target, query.includeLibraries).forEach { if (items.size() < query.limit) items.add(describe(it)) else truncated = true }
            HierarchyDirection.IMPLEMENTATIONS ->
                DefinitionsScopedSearch.search(target, scope(query.includeLibraries)).forEach(Processor { definition ->
                    if (items.size() >= query.limit) { truncated = true; return@Processor false }
                    if (definition != target) items.add(describe(definition))
                    true
                })
        }
        return CodeToolResult(JsonObject().apply {
            add("target", describe(target))
            addProperty("direction", direction.id)
            add(direction.id, items)
            if (direction == HierarchyDirection.SUPERS && target in supersIncomplete) addProperty("overrides_incomplete", true)
            if (truncated) addProperty("truncated", true)
        })
    }

    /**
     * 它重写、实现、继承的上层声明（父类方法、接口方法、父类型、Go 里它满足的接口）。
     *
     * 平台只有「往下找实现」的通用接口（[DefinitionsScopedSearch]，各语言插件都接了，Go 的隐式接口也算），
     * 没有通用的「往上找」。所以反过来查：同名的候选声明、以及声明头部（主体之前）引用到的类型里，
     * 往下找实现能找到它的，就是它的上层。只在项目内查：库里的接口（如 Runnable.run）引用遍布全项目，
     * 当成上层只会把无关调用全拉进来。候选数设上限，常见名字（get、run）不至于拖慢查询。
     */
    private fun supers(element: PsiElement, includeLibraries: Boolean = false): List<PsiElement> = superCache.getOrPut(element to includeLibraries) {
        val name = (element as? PsiNamedElement)?.name ?: return@getOrPut emptyList()
        val scope = scope(includeLibraries)
        val candidates = LinkedHashSet<PsiElement>()
        // 先按结构找：类型看自己声明头部的父类型；方法看所在类型的父类型（逐级往上）里同名的成员。
        // 这一步不受同名候选上限影响，Java、Kotlin、TS、Python 这类方法写在类型里面的语言靠它就够。
        headerTypes(element, includeLibraries).forEach(candidates::add)
        val owner = enclosingType(element)
        owner?.let {
            for (type in supertypesOf(it, includeLibraries)) {
                membersOf(type).filter { member -> (member as? PsiNamedElement)?.name == name }.forEach(candidates::add)
            }
        }
        // 再按名字扫，只用于写在类型外面的方法（Go 的方法、顶层函数）：Go 的接口是隐式实现的，只能靠同名候选。
        // 写在类型里面的方法（Java、Kotlin、TS、Python）按结构找已经可靠；对它们按名字扫，常见名字（dispose、get）
        // 有几十上百个候选，每个都要做一次「谁重写了它」的搜索，一次查询就会超时。扫描设时间和数量上限，扫不完如实标出。
        if (owner == null && membersOf(element).isEmpty() && name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' || it == '$' }) {
            val deadline = minOf(System.nanoTime() + SUPER_SCAN_BUDGET_NANOS, scanDeadline)
            // 同时限制候选数和扫过的文件数：常见名字（get、run）出现在成千上万个文件里，每个文件都要建结构视图。
            var scanned = 0
            var files = 0
            // 按名字扫始终只在项目内：库里同名方法成千上万（如平台里的 dispose），每个都要反编译、建结构视图，
            // 一次查询就会超时；库里的父类型、接口方法由上面按结构的那一步找到。
            PsiSearchHelper.getInstance(project).processAllFilesWithWord(name, GlobalSearchScope.projectScope(project), Processor { file ->
                outline(file).filter { it != element && (it as? PsiNamedElement)?.name == name }.forEach {
                    if (candidates.add(it)) scanned++
                }
                if (scanned >= SUPER_CANDIDATE_LIMIT || ++files >= SUPER_FILE_LIMIT || System.nanoTime() > deadline) {
                    supersIncomplete += element; false
                } else true
            }, true)
            return@getOrPut candidates.filter { candidate ->
                if (System.nanoTime() > deadline) { supersIncomplete += element; return@filter false }
                candidate != element && implementedBy(candidate, element, scope)
            }
        }
        candidates.filter { candidate -> candidate != element && implementedBy(candidate, element, scope) }
    }

    /** 方法、函数重写或实现的上层方法（不含类型的父类型），用于补上经由接口、父类的调用。类型本身返回空。 */
    private fun memberSupers(element: PsiElement): List<PsiElement> =
        if (membersOf(element).isNotEmpty()) emptyList() else supers(element).filter { membersOf(it).isEmpty() }

    /** 结构视图里直接包含它的类型节点（有成员的节点）；顶层函数返回 null。 */
    private fun enclosingType(element: PsiElement): PsiElement? {
        val file = element.containingFile ?: return null
        val offset = element.textRange?.startOffset ?: return null
        return outline(file)
            .filter { it != element && it.textRange?.let { r -> r.contains(offset) && r != element.textRange } == true && membersOf(it).isNotEmpty() }
            .minByOrNull { it.textRange.length }
    }

    /** 结构视图里位于它范围内的成员节点。 */
    private fun membersOf(type: PsiElement): List<PsiElement> {
        val file = type.containingFile ?: return emptyList()
        val range = type.textRange ?: return emptyList()
        return outline(file).filter { it != type && it.textRange?.let(range::contains) == true }
    }

    /** 逐级往上的父类型（只在项目内，最多 8 层，防止循环）。 */
    private fun supertypesOf(type: PsiElement, includeLibraries: Boolean): List<PsiElement> {
        val result = LinkedHashSet<PsiElement>()
        var frontier = listOf(type)
        repeat(8) {
            frontier = frontier.flatMap { headerTypes(it, includeLibraries) }.filter { it != type && result.add(it) }
            if (frontier.isEmpty()) return result.toList()
        }
        return result.toList()
    }

    /**
     * 声明头部（主体之外）引用到的声明：extends、implements、冒号后的父类型都在这里。头部怎么划见函数体注释；
     * 不能因为没有成员就不看头部（`class Closed : Base("…")` 没有类体），那样会漏掉不同名的父类。
     * 默认只要项目内的；[includeLibraries] 时也要库里的父类型。
     */
    private fun headerTypes(element: PsiElement, includeLibraries: Boolean = false): List<PsiElement> {
        // IDE 内部生成的声明（TS 的导入别名、隐式声明这类）没有源码文本，没有头部可看。
        val range = element.textRange ?: return emptyList()
        val text = element.text ?: return emptyList()
        val members = membersOf(element)
        // 从声明自己的名字开始：名字前面的文档注释（`/** {@link Foo} */`）、注解（`@SuppressWarnings({"a"})`）、
        // 装饰器（`@Component({...})`）里的花括号不能把头部提前截断。往后找时跳过注释、不看括号里的内容。
        val from = (element as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange?.startOffset ?: range.startOffset
        val (brace, equals, newline) = headerStops(element, text, from - range.startOffset)
        // 头部范围（绝对偏移，左闭右开），可能是两段：
        // - 有 `{`：到第一个 `{` 为止（Java、Kotlin、TS、Go 的类型与带类体的子类）；
        // - 没有 `{` 但有成员：第一个成员之前 + 最后一个成员之后。Kotlin 构造参数换行写的 `class Foo(\n val x: Int,\n) : Base(x)`
        //   里 `val x` 是成员，父类在它后面；Python 类的成员一直到类结尾，后一段为空；
        // - 都没有：到第一个 `=` 或换行为止。没有花括号的函数（Python、Kotlin 表达式函数）不能把整个函数体当头部，
        //   否则函数体里引用到的每个项目符号都要做一次继承搜索；没有类体的子类通常一行写完。
        val header: List<IntRange> = when {
            brace >= 0 -> listOf(range.startOffset until range.startOffset + brace)
            members.isNotEmpty() -> listOf(
                range.startOffset until members.minOf { it.textRange.startOffset },
                members.maxOf { it.textRange.endOffset } until range.endOffset,
            )
            else -> {
                val stop = listOf(equals, newline).filter { it >= 0 }.minOrNull()
                listOf(range.startOffset until (stop?.let { range.startOffset + it } ?: range.endOffset))
            }
        }
        val result = LinkedHashSet<PsiElement>()
        PsiTreeUtil.processElements(element) { child ->
            if (header.none { child.textRange.startOffset in it }) return@processElements true
            for (reference in child.references) {
                val resolved = resolve(reference) ?: continue
                if (resolved == element || (!includeLibraries && !isInProject(resolved))) continue
                result += resolved
                // `: Base("…")`、`super(…)` 解析到的是父类的构造函数，不是父类本身：构造函数与所在类同名，
                // 往外找到同名的那一层就是父类（Kotlin 主构造函数、Java 构造函数都是这样）。
                val name = (resolved as? PsiNamedElement)?.name
                generateSequence(resolved.parent) { it.parent }
                    .takeWhile { it !is PsiFile }
                    .firstOrNull { it is PsiNamedElement && it.name == name && it != element }
                    ?.let(result::add)
            }
            true
        }
        return result.toList()
    }

    /**
     * 从 [from]（相对 [element] 文本的偏移）往后，第一个不在注释里、不在括号里的 `{`、`=`、换行的位置，找不到为 -1。
     */
    private fun headerStops(element: PsiElement, text: String, from: Int): Triple<Int, Int, Int> {
        val start = element.textRange.startOffset
        val comments = PsiTreeUtil.findChildrenOfType(element, PsiComment::class.java).mapNotNull { it.textRange?.shiftLeft(start) }
        var depth = 0
        var brace = -1
        var equals = -1
        var newline = -1
        var i = from.coerceAtLeast(0)
        while (i < text.length && brace < 0) {
            val comment = comments.firstOrNull { i >= it.startOffset && i < it.endOffset }
            if (comment != null) { i = comment.endOffset; continue }
            when (text[i]) {
                '(', '[' -> depth++
                ')', ']' -> if (depth > 0) depth--
                '{' -> if (depth == 0) brace = i
                '=' -> if (depth == 0 && equals < 0) equals = i
                '\n' -> if (depth == 0 && newline < 0) newline = i
            }
            i++
        }
        return Triple(brace, equals, newline)
    }

    private fun implementedBy(candidate: PsiElement, element: PsiElement, scope: GlobalSearchScope): Boolean {
        var found = false
        DefinitionsScopedSearch.search(candidate, scope).forEach(Processor { definition ->
            found = definition == element || definition.navigationElement == element
            !found
        })
        return found
    }

    /** 调用方向上建树：callers 沿引用逐层上溯，callees 列出函数体内引用到的外部声明。 */
    fun callHierarchy(query: CodeQuery, direction: CallDirection, depth: Int): CodeToolResult {
        val target = when (val located = locate(query)) {
            is Located.Found -> located.element
            is Located.Failed -> return located.result
        }
        val budget = Budget(query.limit)
        val root = when (direction) {
            CallDirection.CALLERS -> callers(target, depth, scope(query.includeLibraries), budget, hashSetOf(target), HashMap())
            CallDirection.CALLEES -> callees(target, depth, query.includeLibraries, budget, hashSetOf(target))
        }
        return CodeToolResult(JsonObject().apply {
            addProperty("direction", direction.id)
            add("tree", root)
            if (budget.exhausted) addProperty("truncated", true)
        })
    }

    /**
     * IDE 分析不了的文件（没装插件的语言，如 IDEA 里的 PHP、没开 Vue 支持时的 .vue）里出现同名单词的位置。
     *
     * 引用索引只覆盖 IDE 能解析的语言，这些文件里的调用会被**悄悄漏掉**——实测 Pi 拿到结果就停了，
     * 没像 Claude、Codex 那样自己再搜一遍。所以用平台的单词索引把它们单独列出来，明确交给智能体去核对。
     */
    private fun JsonObject.addUnanalyzedMentions(target: PsiElement, scope: GlobalSearchScope) {
        val name = (target as? PsiNamedElement)?.name ?: return
        if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' || it == '$' }) return
        // 同一次查询里同名节点只扫一次单词索引（调用链里同一个名字常出现多次）。
        val cached = unanalyzedCache[name]
        if (cached == null && !scanTimeLeft()) {
            addProperty("unanalyzed_not_checked", true)
            return
        }
        val (mentions, truncated) = cached ?: scanUnanalyzed(name, scope).also { unanalyzedCache[name] = it }
        if (mentions.size() == 0) return
        add("unanalyzed_mentions", mentions.deepCopy())
        if (truncated) addProperty("unanalyzed_truncated", true)
        addProperty(
            "unanalyzed_note",
            "The IDE cannot analyze these files' language, so they are not in the results above. Open them and check whether each mention is a real use." +
                if (truncated) " Only the first $UNANALYZED_LIMIT are listed: search the name yourself for the rest." else "",
        )
    }

    /**
     * 不是项目代码的文件：没有扩展名的授权、说明文件（LICENSE、NOTICE……），以及项目里以 `.` 开头的隐藏目录下的文件
     * （`.verify` 里整套 IDE 副本、`.reference` 里的官方文档、`.idea`、`.gradle`）。这些目录常被版本控制忽略，
     * 但 IDE 仍把它们算进项目；不排除的话，常见单词（receive、get）会把这一栏塞满授权声明和文档片段。
     * 不直接问版本控制「是否被忽略」：那个接口在 vcs 模块里，插件没有声明依赖。
     */
    private fun isProseOrHidden(vf: VirtualFile): Boolean {
        if (vf.extension.isNullOrEmpty() && vf.nameWithoutExtension.lowercase() in PROSE_NAMES) return true
        val base = project.basePath ?: return false
        val relative = vf.path.removePrefix("$base/").takeIf { it != vf.path } ?: return false
        return relative.split('/').dropLast(1).any { it.startsWith('.') }
    }

    private fun scanUnanalyzed(name: String, scope: GlobalSearchScope): Pair<JsonArray, Boolean> {
        val mentions = JsonArray()
        var truncated = false
        // 同一个文件可能经不同根目录被扫到两次（如同时在源码根和库根下），按「路径 + 行」去重。
        val seen = HashSet<String>()
        // processAllFilesWithWord 只查「代码」上下文，纯文本、TextMate 接管的文件不在其中；要用 ANY。
        val psiManager = PsiManager.getInstance(project)
        PsiSearchHelper.getInstance(project).processCandidateFilesForText(scope, UsageSearchContext.ANY, true, name, Processor { vf ->
            // 说明文档、日志这类纯文字文件不是「分析不了的代码」，列出来只会挤掉真正要核对的地方。
            if (vf.extension?.lowercase() in PROSE_EXTENSIONS || isProseOrHidden(vf)) return@Processor true
            val file = psiManager.findFile(vf) ?: return@Processor true
            if (supportsFindUsages(file)) return@Processor true
            val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return@Processor true
            for (line in 0 until document.lineCount) {
                val start = document.getLineStartOffset(line)
                val text = document.charsSequence.subSequence(start, document.getLineEndOffset(line)).toString()
                if (wordOffsets(text, name).isEmpty()) continue
                if (!seen.add("${vf.path}:$line")) continue
                if (!scanTimeLeft()) { truncated = true; return@Processor false }
                if (mentions.size() >= UNANALYZED_LIMIT) { truncated = true; return@Processor false }
                mentions.add(JsonObject().apply {
                    addProperty("file", relativePath(file))
                    addProperty("line", line + 1)
                    addProperty("text", text.trim().take(200))
                })
            }
            true
        })
        return mentions to truncated
    }

    private class Budget(var remaining: Int) {
        var exhausted = false
        fun take(): Boolean = if (remaining > 0) { remaining--; true } else { exhausted = true; false }
    }

    /**
     * [expanded] 记下已经展开过的调用方及当时还能往上追几层：同一个函数经不同路径出现时（如 A、B 都被 C 调用），
     * 第二次只标 `expanded_above`，不再重新搜一遍，否则大项目里搜索次数会按层数成倍增长。
     * 但如果第一次是从深处遇到、只追了一两层，后来在更浅处遇到、还能追更多层，就要重新展开，否则更上层的调用方会丢。
     */
    private fun callers(
        element: PsiElement,
        depth: Int,
        scope: GlobalSearchScope,
        budget: Budget,
        path: Set<PsiElement>,
        expanded: MutableMap<PsiElement, Int>,
    ): JsonObject {
        val node = describe(element)
        if (depth <= 0) return node
        expanded[element] = depth
        // 每个往上展开的节点都补查一次：B 调 A、而 IDE 分析不了的文件又调 B 时，提醒要挂在 B 上，不能只在 A 上查。
        node.addUnanalyzedMentions(element, scope)
        // 同一个调用方可能在多处调用：按调用方分组，调用点挂在它下面。
        val groups = LinkedHashMap<PsiElement, MutableList<JsonObject>>()
        val loose = JsonArray()
        // 通过接口、父类调用的地方记在上层声明名下（`greeter.greet()` 里 greeter 是接口类型），也是它的调用方，标明经由谁。
        val parents = memberSupers(element)
        if (element in supersIncomplete) node.addProperty("overrides_incomplete", true)
        for (searched in listOf(element) + parents) ReferencesSearch.search(searched, scope).forEach(Processor { reference ->
            // 调用链只看真实调用；注释里提到名字的地方 find_references 会单独列出。
            if (PsiTreeUtil.getParentOfType(reference.element, PsiComment::class.java, false) != null) return@Processor true
            if (!inScope(reference, scope) || isSelfReference(searched, reference)) return@Processor true
            val site = usageSite(reference.element, reference.rangeInElement) ?: return@Processor true
            site.addProperty("usage", if (isCall(reference, searched)) "call" else "reference")
            if (searched != element) site.addProperty("via", "${nameOf(searched)} (${kindOf(searched)})")
            // 每个调用点都计入 max_results；用完就停止搜索，免得被调用上万次的函数把搜索拖到底、把结果撑爆。
            if (!budget.take()) return@Processor false
            // 页面标签（JSX、Vue 模板里的 <span>、<script>）不能当调用方往上追。
            val owner = hostPosition(reference.element, reference.element.textRange.startOffset)
                ?.let { (file, offset) -> enclosingMember(file, offset, callableOnly = true) }
            if (owner == null) loose.add(site) else groups.getOrPut(owner) { mutableListOf() }.add(site)
            true
        })
        val callers = JsonArray()
        val referencedBy = JsonArray()
        // 调用点在搜索时已计入额度，这里不再为调用方节点重复扣：否则额度刚好被调用点用完时，调用方会一个都显示不出来。
        for ((owner, sites) in groups) {
            // 只是把它当值交出去（`it::receive`、回调、事件处理器）的地方不是执行入口：单列出来，不往上追。
            if (sites.none { it.get("usage")?.asString == "call" }) {
                referencedBy.add(describe(owner).apply {
                    add("reference_sites", JsonArray().apply { sites.take(CALL_SITES_PER_CALLER).forEach(::add) })
                })
                continue
            }
            val child = when {
                budget.exhausted -> describe(owner).apply { addProperty("not_expanded", true) }
                owner in path -> describe(owner).apply { addProperty("recursive", true) }
                (expanded[owner] ?: -1) >= depth - 1 -> describe(owner).apply { addProperty("expanded_above", true) }
                else -> callers(owner, depth - 1, scope, budget, path + owner, expanded)
            }
            child.add("call_sites", JsonArray().apply { sites.take(CALL_SITES_PER_CALLER).forEach(::add) })
            if (sites.size > CALL_SITES_PER_CALLER) child.addProperty("call_site_count", sites.size)
            callers.add(child)
        }
        node.add("callers", callers)
        if (referencedBy.size() > 0) node.add("referenced_without_calling", referencedBy)
        if (loose.size() > 0) node.add("references_outside_functions", loose)
        return node
    }

    /**
     * 这处引用是不是在调用它。不按语言写规则，只看引用前后紧挨的字符：名字后面紧跟 `(`，或函数后面紧跟尾随 lambda 的 `{`
     * （可隔泛型实参 `<…>`），或前面紧贴 `<`（JSX、Vue 模板里把组件当标签渲染，`<` 前面不是操作数）、`@`（装饰器、注解），算调用；`::receive`、
     * 作为参数传出去、赋给变量、Vue 模板 `@click="reset"` 这类只把它当值交出去的，算引用。
     * 判断不了时归为引用：宁可少追一层，也不把注册回调的地方当成执行入口。
     */
    private fun isCall(reference: com.intellij.psi.PsiReference, target: PsiElement): Boolean {
        val range = reference.absoluteRange
        val (file, start) = hostPosition(reference.element, range.startOffset) ?: return false
        val end = start + range.length
        val text = PsiDocumentManager.getInstance(project).getDocument(file)?.charsSequence ?: return false
        val isFunction = membersOf(target).isEmpty()

        // 一、明确是调用的迹象。
        val before = start - 1
        if (before >= 0) {
            // 装饰器、注解：`@decor`。
            if (text[before] == '@') return true
            // 组件标签：`<Header />`。`<` 必须紧贴名字，且前面不是操作数——`i < size`、`i<size`、`List<Foo>` 都不算。
            if (text[before] == '<') {
                val operand = before - 1
                return operand < 0 || !(text[operand].isLetterOrDigit() || text[operand] == '_' || text[operand] == ')' || text[operand] == ']')
            }
        }
        var after = end
        while (after < text.length && text[after].isWhitespace() && text[after] != '\n') after++
        if (after < text.length && text[after] == '<') {
            var depth = 0
            while (after < text.length) {
                when (text[after]) {
                    '<' -> depth++
                    '>' -> if (--depth == 0) { after++; break }
                    '\n', ';', '{', '}' -> return false
                }
                after++
            }
        }
        if (after < text.length && text[after] == '(') return true
        // `{`：Kotlin、Swift 把最后一个参数写成尾随 lambda 的调用，如 `doWork { … }`。
        // 只对函数类目标算数：类型名后面跟 `{` 是声明头里的父类型（`class Impl : Base {`）或结构体字面量。
        if (after < text.length && text[after] == '{' && isFunction) return true

        // 二、明确是当值交出去的迹象：这些都不是调用。
        val lineStart = (start - 1 downTo 0).firstOrNull { text[it] == '\n' }?.let { it + 1 } ?: 0
        val lineEnd = (end until text.length).firstOrNull { text[it] == '\n' } ?: text.length
        val head = text.subSequence(lineStart, start).toString()
        val tail = text.subSequence(end, lineEnd).toString()
        // 去掉限定前缀（`controller.HandleOAuth`、`this::tick`、`Foo::bar`）再看前面紧挨的是什么。
        val prefix = head.trimEnd().replace(Regex("""[\w$]+(\s*(\.|::|->)\s*)$"""), "").trimEnd()
        val next = tail.trimStart().firstOrNull()
        if (head.trimEnd().endsWith("::")) return false
        // 作为参数：`register(handler)`、`f(a, handler)`；参数换行写时名字单独一行、后面跟 `,` 或 `)`。
        if (next == ',' || (next == ')' && (prefix.isEmpty() || prefix.endsWith("(") || prefix.endsWith(",")))) return false
        if (prefix.endsWith("=") && !prefix.endsWith("==") && (next == null || next == ';' || next == ',' || next == ')')) return false
        if ((prefix.endsWith("\"") || prefix.endsWith("'")) && (next == '"' || next == '\'')) return false

        // 三、都没有：函数出现在命令开头（Shell、Ruby、Scala 等不写括号的调用，如 `limit_notes "$f"`）算调用。
        if (!isFunction || next == '=' || next == '.') return false
        val command = head.trimEnd()
        return command.isEmpty() || COMMAND_SEPARATORS.any { command.endsWith(it) && boundaryBefore(command, it) }
    }

    /** 关键字类分隔符（`then`、`do`）前面必须是空白或行首，避免 `undo` 这种词尾误配。 */
    private fun boundaryBefore(command: String, separator: String): Boolean {
        if (!separator.first().isLetter()) return true
        val at = command.length - separator.length - 1
        return at < 0 || !command[at].isLetterOrDigit()
    }

    private fun callees(element: PsiElement, depth: Int, includeLibraries: Boolean, budget: Budget, path: Set<PsiElement>): JsonObject {
        val node = describe(element)
        if (depth <= 0) return node
        val targets = LinkedHashSet<PsiElement>()
        var callsItself = false
        PsiTreeUtil.processElements(element) { child ->
            for (reference in child.references) {
                val resolved = resolve(reference) ?: continue
                // 直接递归单独记下：下面排除「自己体内的东西」时会连自己一起排除掉。
                // 只把自己当值交出去（`register(::tick)`）不是递归。
                if (resolved == element) { if (isCall(reference, element)) callsItself = true; continue }
                // 自己体内的局部变量、参数不算依赖；包、目录这类没有文件的节点也跳过。
                if (resolved is PsiDirectory || resolved.containingFile == null || PsiTreeUtil.isAncestor(element, resolved, false)) continue
                // 项目里的依赖只算结构视图里有的声明（函数、类型、字段、常量）：具名参数 `f(callableOnly = true)`
                // 解析到的是别的函数的参数，不是依赖。库里的只计数，不在这里建结构视图。
                if (!isInProject(resolved)) { targets += resolved; continue }
                // 构造调用 `Budget(…)` 解析到构造函数，它不在结构视图里：换成外层同名的类。
                val declaration = if (resolved in outline(resolved.containingFile)) resolved else sameNameOwner(resolved)
                if (declaration != null && declaration != element) targets += declaration
            }
            true
        }
        // 语言内置和依赖库（String、equals、标准库函数……）默认只计数：改项目代码时要看的是项目里的依赖。
        val (project, libraries) = targets.partition { isInProject(it) }
        val callees = JsonArray()
        for (target in if (includeLibraries) project + libraries else project) {
            if (!budget.take()) break
            val expand = depth > 1 && target !in path && isProjectLeafMember(target)
            callees.add(if (expand) callees(target, depth - 1, includeLibraries, budget, path + target) else describe(target))
        }
        if (callsItself) callees.add(describe(element).apply { addProperty("recursive", true) })
        node.add("callees", callees)
        if (!includeLibraries && libraries.isNotEmpty()) node.addProperty("library_callees_omitted", libraries.size)
        return node
    }

    /** 往外找第一个与它同名、并且在结构视图里的声明（构造函数所在的类）。 */
    private fun sameNameOwner(element: PsiElement): PsiElement? {
        val name = (element as? PsiNamedElement)?.name ?: return null
        val file = element.containingFile ?: return null
        val outline = outline(file)
        return generateSequence(element.parent) { it.parent }
            .takeWhile { it !is PsiFile }
            .firstOrNull { it is PsiNamedElement && it.name == name && it in outline }
    }

    private fun isInProject(element: PsiElement): Boolean =
        (element.navigationElement ?: element).containingFile?.virtualFile?.let(fileIndex::isInContent) == true

    /** 只往项目内、结构视图里没有子节点的成员（函数、方法、属性）继续展开，类型和包不展开。 */
    private fun isProjectLeafMember(element: PsiElement): Boolean {
        val file = element.containingFile ?: return false
        val vf = file.virtualFile ?: return false
        if (!fileIndex.isInContent(vf)) return false
        val outline = outline(file)
        return element in outline && outline.none { it != element && PsiTreeUtil.isAncestor(element, it, true) }
    }

    private sealed interface Located {
        data class Found(val element: PsiElement) : Located
        data class Failed(val result: CodeToolResult) : Located
    }

    private fun failure(reason: String, message: String) = Located.Failed(CodeToolResult(JsonObject().apply {
        addProperty("reason", reason)
        addProperty("message", message)
    }, failed = true))

    private fun locate(query: CodeQuery): Located {
        val vf = findFile(query.file) ?: return failure("file_not_found", "No file at ${query.file}. Pass a path relative to the project root or an absolute path.")
        val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return failure("file_not_found", "The IDE cannot open ${query.file}.")
        if (!supportsFindUsages(psiFile)) {
            return failure(
                "language_not_supported",
                "The IDE has no Find Usages support for ${vf.name} (${psiFile.language.displayName}), so it cannot resolve references in it. " +
                    "Use the language server (LSP) tools or text search for this file instead.",
            )
        }
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return failure("file_not_found", "The IDE has no text for ${query.file}.")
        if (query.line < 1 || query.line > document.lineCount) return failure("bad_position", "line ${query.line} is outside 1..${document.lineCount}.")
        val lineStart = document.getLineStartOffset(query.line - 1)
        val lineEnd = document.getLineEndOffset(query.line - 1)
        val lineText = document.charsSequence.subSequence(lineStart, lineEnd).toString()
        val offsets: List<Int> = when {
            query.symbol != null -> wordOffsets(lineText, query.symbol).map { lineStart + it }.ifEmpty {
                return failure("symbol_not_on_line", "`${query.symbol}` does not appear on line ${query.line}: ${lineText.trim().take(200)}")
            }
            query.column != null -> listOf((lineStart + query.column - 1).coerceIn(lineStart, lineEnd))
            else -> (lineStart until lineEnd).filter { !lineText[it - lineStart].isWhitespace() }
        }
        for (offset in offsets) {
            symbolAt(psiFile, offset, query.line, document, query.symbol)?.let { return Located.Found(it) }
        }
        return failure(
            "no_symbol",
            "No resolvable symbol at line ${query.line}${query.column?.let { ", column $it" }.orEmpty()}: ${lineText.trim().take(200)}. " +
                "Pass `symbol` with the name to look up on that line, or the exact column of the name.",
        )
    }

    /**
     * 不按后缀名列白名单，而是问 IDE：这个文件涉及的语言里有没有一种注册了「查找用法」。
     * 纯文本没有；TextMate 只做语法高亮（没装语言插件的 Rust、PHP、Ruby、C 等会被它接管），它登记的是空实现，
     * 同样算没有；
     * Vue、JSP 这类混合文件只要其中一种语言有就算支持。
     */
    private fun supportsFindUsages(file: PsiFile): Boolean =
        file.viewProvider.languages.any { language ->
            // TextMate 显式登记了一个什么都不做的 EmptyFindUsagesProvider，登记了不等于支持。
            language != PlainTextLanguage.INSTANCE &&
                LanguageFindUsages.INSTANCE.allForLanguage(language).any { it !is EmptyFindUsagesProvider }
        }

    /** 优先取该位置引用解析到的声明；位置落在声明自己的名字或声明头上时取声明本身。 */
    private fun symbolAt(file: PsiFile, offset: Int, line: Int, document: com.intellij.openapi.editor.Document, symbol: String?): PsiElement? {
        for (candidate in listOf(offset, offset - 1).filter { it >= 0 }) {
            file.findReferenceAt(candidate)?.let(::resolve)?.let { return it }
        }
        var element: PsiElement? = file.findElementAt(offset)
        while (element != null && element !is PsiFile) {
            // 有些语言的声明不实现 PsiNameIdentifierOwner（如 YAML 的键）：给了名字时，
            // 同一行上叫这个名字的命名元素就是要找的声明。
            if (symbol != null && element is PsiNamedElement && element !is PsiNameIdentifierOwner && element.name == symbol &&
                document.getLineNumber(element.textRange.startOffset) + 1 == line
            ) return element
            if (element is PsiNameIdentifierOwner && element.name != null) {
                // 只认名字在同一行的声明：位置在函数体里时不能悄悄换成外层函数。
                val name = element.nameIdentifier ?: return null
                if (document.getLineNumber(name.textRange.startOffset) + 1 != line) return null
                // 给了名字就必须是这个名字：`fun bill() = 查不到的调用()` 问的是那个调用，不能落到 bill 上。
                if (symbol != null) return element.takeIf { it.name == symbol }
                // 只给位置时，位置要在声明名字之前或之上（关键字、修饰符、名字本身），名字后面的调用同理不能落到外层。
                return element.takeIf { offset < name.textRange.endOffset }
            }
            element = element.parent
        }
        return null
    }

    private fun resolve(reference: com.intellij.psi.PsiReference): PsiElement? =
        reference.resolve()
            ?: (reference as? PsiPolyVariantReference)?.multiResolve(false)?.firstNotNullOfOrNull { it.element?.takeIf { e -> e.isValid } }

    /** 读锁里不能刷新磁盘：磁盘同步由 [CodeMcpService] 在进锁前做完，这里只查 VFS。 */
    private fun findFile(path: String): VirtualFile? {
        val resolved = resolveQueryPath(project, path) ?: return null
        // 结果里库文件的路径形如 `…/guava.jar!/com/…/Foo.class`、`…/Home!/java.base/…/URI.class`，
        // 要从 jar、jrt 文件系统找，结果里的每个节点才都能拿回来再查。
        if ("!/" in resolved) {
            val manager = VirtualFileManager.getInstance()
            return manager.findFileByUrl("jar://$resolved") ?: manager.findFileByUrl("jrt://$resolved")
        }
        return LocalFileSystem.getInstance().findFileByPath(resolved)
    }

    private fun scope(includeLibraries: Boolean): GlobalSearchScope =
        if (includeLibraries) GlobalSearchScope.allScope(project) else GlobalSearchScope.projectScope(project)

    /**
     * 引用所在的成员：结构视图里同一文件、范围包含该位置的最内层节点。
     *
     * 前端文件的结构视图还列出页面标签（JSX、Vue 模板、HTML 里的 `<span>`、`<script>`），
     * 有时还有无名节点。优先取带名字节点的声明（函数、方法、变量）；[callableOnly] 为 false 时
     * 才退回到其它节点，至少说明「在哪个标签里」——HTML 里本来就只有标签。
     */
    private fun enclosingMember(file: PsiFile, offset: Int, callableOnly: Boolean = false): PsiElement? {
        // 从引用位置本身开始、且只占一行的节点，是这次引用所在的赋值（JS 的 `total.value = 0`），不是外层成员。
        // 只限单行：Java 不写修饰符的方法 `Result compute() {…}` 也从返回类型那个引用开始，但它跨多行，不能排除。
        val document = PsiDocumentManager.getInstance(project).getDocument(file)
        val containing = outline(file).filter {
            val range = it.textRange
            range != null && range.contains(offset) && nameOf(it).isNotBlank() &&
                !(range.startOffset == offset && document != null && document.getLineNumber(range.endOffset) == document.getLineNumber(offset))
        }
        fun innermost(candidates: List<PsiElement>) = candidates.minByOrNull { it.textRange.length }
        // 先找真正的声明（函数、方法、变量都有名字节点）；JS 结构视图里 `total.value = 0` 这种赋值也是节点，不该算归属。
        // 页面标签没有名字节点，自然落到兜底那一档；不直接判断 XmlTag，是因为它属于 XML 模块，
        // 插件没有声明依赖，在不带该模块的 IDE 里会类加载失败（插件验证器会报出来）。
        return innermost(containing.filterIsInstance<PsiNameIdentifierOwner>())
            ?: if (callableOnly) null else innermost(containing)
    }

    /**
     * 引用实际所在的文件是否在查询范围内。只查项目时，IDE 自带的 JavaScript 类库（DOM 定义等）仍会混进来：
     * JS 按名字模糊匹配，类库里的 `.name(` 也被当成对项目方法的调用。按宿主文件再核对一遍范围。
     */
    private fun inScope(reference: com.intellij.psi.PsiReference, scope: GlobalSearchScope): Boolean {
        val (file, _) = hostPosition(reference.element, reference.element.textRange.startOffset) ?: return false
        return file.virtualFile?.let(scope::contains) ?: false
    }

    /** CSS 选择器、SQL 建表、SCSS 变量这类声明会把自身也报成一次引用，按声明名字的位置剔除。 */
    private fun isSelfReference(target: PsiElement, reference: com.intellij.psi.PsiReference): Boolean {
        if (reference.element.containingFile != target.containingFile) return false
        // 没有名字节点时只取声明的第一行，免得把函数体里的递归调用也当成自身剔掉。
        val anchor = (target as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange ?: run {
            val range = target.textRange ?: return false
            val document = PsiDocumentManager.getInstance(project).getDocument(target.containingFile) ?: return false
            TextRange(range.startOffset, minOf(range.endOffset, document.getLineEndOffset(document.getLineNumber(range.startOffset))))
        }
        return reference.absoluteRange.intersects(anchor)
    }

    /**
     * 文件结构视图展开成扁平列表。只保留同一文件里的节点：Go 的结构视图会把同包其它
     * 文件的函数也挂进来，不过滤的话会按偏移量误判成别的文件里的函数。
     */
    private fun outline(file: PsiFile): List<PsiElement> {
        outlines[file]?.let { return it }
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(file) as? TreeBasedStructureViewBuilder
            ?: return emptyList<PsiElement>().also { outlines[file] = it }
        val model = builder.createStructureViewModel(null)
        return try {
            val result = mutableListOf<PsiElement>()
            fun walk(node: StructureViewTreeElement, depth: Int) {
                if (depth > 32) return
                for (child in node.children) {
                    val tree = child as? StructureViewTreeElement ?: continue
                    val value = tree.value as? PsiElement
                    if (value != null && value.isValid && value.containingFile == file) result += value
                    walk(tree, depth + 1)
                }
            }
            walk(model.root, 0)
            result.also { outlines[file] = it }
        } catch (e: Exception) {
            // 读锁被写操作抢占、协程被取消时必须原样抛出，读锁才会重试；也不能把空结果缓存下来，
            // 否则同一次查询里这个文件的「在哪个函数里」「继承关系」全部悄悄算错。
            if (e is com.intellij.openapi.progress.ProcessCanceledException || e is java.util.concurrent.CancellationException) throw e
            emptyList()
        } finally {
            Disposer.dispose(model)
        }
    }

    /**
     * 嵌入片段（Vue 模板的 `{{ }}`、HTML 里的脚本、字符串里注入的 SQL……）在 IDE 里是一份独立的
     * 小文件，位置要换算回宿主文件，否则报出来的是 `Summary.vue.int:1` 这种不存在的路径。
     */
    private fun hostPosition(element: PsiElement, offsetInFile: Int): Pair<PsiFile, Int>? {
        val file = element.containingFile ?: return null
        val injection = InjectedLanguageManager.getInstance(project)
        if (!injection.isInjectedFragment(file)) return file to offsetInFile
        return injection.getTopLevelFile(element) to injection.injectedToHost(element, offsetInFile)
    }

    private fun usageSite(element: PsiElement, rangeInElement: TextRange): JsonObject? {
        val (file, offset) = hostPosition(element, element.textRange.startOffset + rangeInElement.startOffset) ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val line = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(line)
        return JsonObject().apply {
            addProperty("file", relativePath(file))
            addProperty("line", line + 1)
            addProperty("column", offset - lineStart + 1)
            addProperty("text", document.charsSequence.subSequence(lineStart, document.getLineEndOffset(line)).toString().trim().take(200))
            enclosingMember(file, offset)?.let { owner -> addProperty("in", "${nameOf(owner)} (${kindOf(owner)})") }
            if (PsiTreeUtil.getParentOfType(element, PsiComment::class.java, false) != null) addProperty("in_comment", true)
        }
    }

    private fun describe(element: PsiElement): JsonObject {
        val navigation = element.navigationElement ?: element
        val file = navigation.containingFile
        return JsonObject().apply {
            addProperty("name", nameOf(element))
            addProperty("kind", kindOf(element))
            if (file != null) {
                addProperty("file", relativePath(file))
                val document = PsiDocumentManager.getInstance(project).getDocument(file)
                val anchor = (navigation as? PsiNameIdentifierOwner)?.nameIdentifier ?: navigation
                val offset = anchor.textRange?.startOffset
                if (document != null && offset != null && offset <= document.textLength) {
                    val line = document.getLineNumber(offset)
                    addProperty("line", line + 1)
                    addProperty("column", offset - document.getLineStartOffset(line) + 1)
                }
                file.virtualFile?.let { addProperty("in_project", fileIndex.isInContent(it)) }
            }
        }
    }

    private fun nameOf(element: PsiElement): String =
        ElementDescriptionUtil.getElementDescription(element, UsageViewShortNameLocation.INSTANCE).ifBlank { (element as? PsiNamedElement)?.name.orEmpty() }

    private fun kindOf(element: PsiElement): String =
        ElementDescriptionUtil.getElementDescription(element, UsageViewTypeLocation.INSTANCE).ifBlank { "symbol" }

    private fun relativePath(file: PsiFile): String {
        val path = file.virtualFile?.path ?: return file.name
        val base = project.basePath ?: return path
        return if (path.startsWith("$base/")) path.removePrefix("$base/") else path
    }
}

/** 智能体给的路径（相对项目根或绝对路径）换成 VFS 认的路径；VFS 只认正斜杠，Windows 的反斜杠要先统一。 */
internal fun resolveQueryPath(project: Project, path: String): String? {
    val raw = runCatching { Path.of(path) }.getOrNull() ?: return null
    val absolute = if (raw.isAbsolute) raw else project.basePath?.let { Path.of(it).resolve(raw) } ?: return null
    return FileUtil.toSystemIndependentName(absolute.normalize().toString())
}

private const val UNANALYZED_LIMIT = 50

/** 出现在这些符号之后的函数名，处在一条命令的开头。 */
private val COMMAND_SEPARATORS = listOf(";", "&&", "||", "|", "$(", "`", "then", "do", "else", "!")

/** 往上找重写对象时最多检查的同名候选数。 */
private const val SUPER_CANDIDATE_LIMIT = 60

/** 整次查询里所有按名字扫描（找重写对象、找分析不了的文件）共用的时间上限。 */
private const val QUERY_SCAN_BUDGET_NANOS = 40_000_000_000L

/** 按名字找重写对象（含逐个核对候选）的时间上限。 */
private const val SUPER_SCAN_BUDGET_NANOS = 15_000_000_000L

/** 往上找重写对象时按名字最多扫的文件数。 */
private const val SUPER_FILE_LIMIT = 300

/** 纯文字文件：其中提到函数名多半是说明，不是代码里的调用。 */
private val PROSE_EXTENSIONS = setOf("md", "markdown", "txt", "log", "rst", "adoc", "csv", "tsv", "license", "notice")

/** 没有扩展名时按文件名识别的纯文字文件。 */
private val PROSE_NAMES = setOf("license", "licence", "notice", "copying", "authors", "readme", "changelog")

/** 每个调用方下最多列出的调用点；超出时给出总数 call_site_count。 */
private const val CALL_SITES_PER_CALLER = 20

/** 一行里 [word] 作为完整标识符出现的位置（0 起），用于按名字在行内定位。 */
internal fun wordOffsets(line: String, word: String): List<Int> {
    if (word.isEmpty()) return emptyList()
    fun identifier(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
    val result = mutableListOf<Int>()
    var index = line.indexOf(word)
    while (index >= 0) {
        val before = index == 0 || !identifier(line[index - 1])
        val after = index + word.length >= line.length || !identifier(line[index + word.length])
        if (before && after) result += index
        index = line.indexOf(word, index + 1)
    }
    return result
}

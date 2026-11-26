package com.github.izerui.imux.code

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.findUsages.EmptyFindUsagesProvider
import com.intellij.lang.findUsages.LanguageFindUsages
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.TestSourcesFilter
import com.intellij.codeInsight.daemon.ImplicitUsageProvider
import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.testIntegration.TestFinder
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
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
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
        val targetName = (target as? PsiNamedElement)?.name
        val access = accessDetector(target)
        ReferencesSearch.search(target, scope).forEach(Processor { reference ->
            if (!inScope(reference, scope) || isSelfReference(target, reference)) return@Processor true
            if (references.size() >= query.limit) { truncated = true; return@Processor false }
            usageSite(reference.element, reference.rangeInElement)?.let { site ->
                access?.let { accessOf(it, target, reference) }?.let { site.addProperty("access", it) }
                references.add(site)
            }
            // 起了别名导入（`from m import f as _f`）时，后面用的是别名：顺着别名在这个文件里接着找。
            for ((aliasUse, alias) in aliasUses(reference, targetName)) {
                if (references.size() >= query.limit) { truncated = true; return@Processor false }
                usageSite(aliasUse.element, aliasUse.rangeInElement)?.let { site ->
                    site.addProperty("via_alias", alias)
                    references.add(site)
                }
            }
            true
        })
        val implementations = JsonArray()
        DefinitionsScopedSearch.search(target, scope).forEach(Processor { definition ->
            if (implementations.size() >= query.limit) { truncated = true; return@Processor false }
            if (definition != target) implementations.add(describe(definition))
            true
        })
        // 通过接口、父类引用它的地方（`greeter.greet()` 的变量类型是接口）记在上层声明名下，要单独补上。
        val supers = supers(target, query.includeLibraries)
        val viaSupers = JsonArray()
        // 「经由上层调用」只对方法成立：类型的父类型被别处用到，不是对这个子类的使用。
        for (parent in memberSupers(target) { supers }) {
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
            if (implicitlyUsed(target)) addProperty("implicit_usage", true)
            // 变量、字段、参数不配测试：IDE 会往外找到所在的类，列出的是整个类的测试，不是它的。
            if (access == null) addRelatedTests(target)
            addUnanalyzedMentions(target, scope)
            if (truncated) addProperty("truncated", true)
        })
    }

    /**
     * 没有显式调用、却会被框架用到（Spring Bean、JUnit 5 测试、JPA 字段、Vue/React 组件成员……）：
     * 问各插件注册的 [ImplicitUsageProvider]，和 IDE 的「未使用」检查用的是同一套判断。
     * 没有插件接入的语言（Python、Go）永远是 false，所以说明里不能写成「没标就没人用」。
     * 装了 Java 插件时再由 [ImplicitUsageSupplement] 补上 Kotlin 写的 Spring 代码和程序入口（测试方法、main）。
     * 单个插件出错只当它不认识，不拖垮整个查询。
     */
    private fun implicitlyUsed(element: PsiElement): Boolean =
        ImplicitUsageProvider.EP_NAME.extensionList.any { provider ->
            try {
                provider.isImplicitUsage(element) || provider.isImplicitRead(element) || provider.isImplicitWrite(element)
            } catch (e: Exception) {
                if (e is com.intellij.openapi.progress.ProcessCanceledException || e is java.util.concurrent.CancellationException) throw e
                false
            }
        } || ImplicitUsageSupplement.anyImplicitlyUsed(element)

    /** 只有变量、字段、属性分得出读和写；函数、类没有这个区分，返回 null 就不标。 */
    private fun accessDetector(target: PsiElement): ReadWriteAccessDetector? =
        ReadWriteAccessDetector.findDetector(target)?.takeIf { it.isReadWriteAccessible(target) }

    /**
     * 这一处是读还是写：交给语言插件按语法位置判断（赋值号左边是写，`x += 1` 是又读又写），
     * 和编辑器里两种颜色的高亮是同一个来源。看不出「改了它指向的内容」（`items.add(x)` 对 items 是读）。
     */
    private fun accessOf(detector: ReadWriteAccessDetector, target: PsiElement, reference: PsiReference): String? =
        try {
            when (detector.getReferenceAccess(target, reference)) {
                ReadWriteAccessDetector.Access.Read -> "read"
                ReadWriteAccessDetector.Access.Write -> "write"
                ReadWriteAccessDetector.Access.ReadWrite -> "read_write"
                null -> null
            }
        } catch (e: Exception) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException || e is java.util.concurrent.CancellationException) throw e
            null
        }

    /**
     * 对应的测试：问各语言注册的 [TestFinder]（IDE 里「跳到测试」用的就是它），按所在的类或文件、按命名惯例配对，
     * 能找到没有直接调用它的测试。它本身就是测试时反过来，列出它测的代码。
     * 平台的 TestFinderHelper 不在公开模块里，这里照它的做法直接遍历扩展点。
     */
    private fun JsonObject.addRelatedTests(target: PsiElement) {
        val tests = LinkedHashSet<PsiElement>()
        val tested = LinkedHashSet<PsiElement>()
        for (finder in TestFinder.EP_NAME.extensionList) {
            try {
                val found = finder.findSourceElement(target) ?: continue
                // 往外找到的是嵌在里面的类型时（Kotlin 的 companion object 名字都叫 Companion），按它的名字配出来的是
                // 一堆不相干的测试：改用最外层的类型。它自己、所在的文件、最外层的类型照原样用。
                val source = if (found == target || found is PsiFile) found
                    else generateSequence(found) { it.parent }.firstOrNull { it.parent is PsiFile } ?: continue
                if (finder.isTest(source)) tested += finder.findClassesForTest(source) else tests += finder.findTestsForClass(source)
            } catch (e: Exception) {
                if (e is com.intellij.openapi.progress.ProcessCanceledException || e is java.util.concurrent.CancellationException) throw e
            }
        }
        // Kotlin 等语言会把文件和文件里的类各报一次：同一个文件里已有具体的类，文件本身就不再列。
        fun distinct(items: Set<PsiElement>): List<PsiElement> {
            val valid = items.filter { it.isValid }
            return valid.filter { item -> item !is PsiFile || valid.none { it !is PsiFile && it.containingFile == item } }
        }
        var omitted = 0
        for ((key, items) in listOf("tests" to distinct(tests), "tested_code" to distinct(tested))) {
            if (items.isEmpty()) continue
            add(key, JsonArray().apply { items.take(RELATED_TESTS_LIMIT).forEach { add(describe(it)) } })
            omitted += maxOf(items.size - RELATED_TESTS_LIMIT, 0)
        }
        // 名额外还有的要说出来：没列出不等于没有。
        if (omitted > 0) addProperty("tests_omitted", omitted)
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
     * 往下找实现能找到它的，就是它的上层。默认只在项目内查：库里的接口（如 Runnable.run）引用遍布全项目，
     * 当成上层只会把无关调用全拉进来。候选数设上限，常见名字（get、run）不至于拖慢查询。
     */
    private fun supers(element: PsiElement, includeLibraries: Boolean): List<PsiElement> = superCache.getOrPut(element to includeLibraries) {
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
            // 只看含同一种语言（Vue 这类一个文件混几种语言的，看其中任一种）、项目自己的、不是超大打包产物的文件：隐式实现只发生在同一种语言里；
            // 不过滤时，项目里的 IDE 副本（.verify）中几兆的打包 JS 也含这个词，给它建结构视图一个文件就要几十秒，
            // 写操作一来读锁重试又从头建，整次查询直到超时都回不来。
            val language = rootLanguage(element.language)
            PsiSearchHelper.getInstance(project).processAllFilesWithWord(name, GlobalSearchScope.projectScope(project), Processor { file ->
                val vf = file.virtualFile
                if (file.viewProvider.languages.none { rootLanguage(it) == language } || vf == null || vf.length > SUPER_SCAN_MAX_FILE_BYTES || isProseOrHidden(vf)) {
                    return@Processor true
                }
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

    /**
     * 方法、函数重写或实现的上层方法（不含类型的父类型），用于补上经由接口、父类的调用。类型本身返回空。
     *
     * 只认和它同名的上层：方法重写、实现的一定同名（`run` 对 `run`），类型的父类型一般不同名（`Impl` 对 `Marker`）。
     * 不能只看「有没有成员」：空类、空接口也没有成员，`class Impl : Marker` 会被当成方法，
     * 只接受 `Marker` 的代码就全被算成经由接口用到了 `Impl`。
     */
    private inline fun memberSupers(element: PsiElement, supers: () -> List<PsiElement>): List<PsiElement> {
        if (membersOf(element).isNotEmpty()) return emptyList()
        val name = nameOf(element)
        return supers().filter { nameOf(it) == name && membersOf(it).isEmpty() }
    }

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

    /** 逐级往上的父类型（按请求决定是否含库，最多 8 层，防止循环）。 */
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
    private fun headerTypes(element: PsiElement, includeLibraries: Boolean): List<PsiElement> {
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
        val header = headerRanges(range, members, brace, equals, newline)
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

    private fun headerRanges(range: TextRange, members: List<PsiElement>, brace: Int, equals: Int, newline: Int): List<IntRange> = when {
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
            CallDirection.CALLERS -> callers(target, depth, query.includeLibraries, budget, hashSetOf(target), HashMap())
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
        if (mentions.size() == 0) {
            // 一条没找到但被截断：是刚开扫就用完了时间，不是「没有遗漏」，要如实标成没查。
            if (truncated) addProperty("unanalyzed_not_checked", true)
            return
        }
        add("unanalyzed_mentions", mentions.deepCopy())
        if (truncated) addProperty("unanalyzed_truncated", true)
        addProperty(
            "unanalyzed_note",
            "The IDE cannot analyze these files' language, so they are not in the results above. Open them and check whether each mention is a real use." +
                if (truncated) " The list is incomplete (it stops at $UNANALYZED_LIMIT entries or when the query runs out of time): search the name yourself for the rest." else "",
        )
    }

    /** 语言的最上层基础语言：TypeScript、JSX 都归到 JavaScript 名下，方言之间仍算同一种语言。 */
    private fun rootLanguage(language: com.intellij.lang.Language): com.intellij.lang.Language {
        return generateSequence(language) { it.baseLanguage }.last()
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
        includeLibraries: Boolean,
        budget: Budget,
        path: Set<PsiElement>,
        expanded: MutableMap<PsiElement, Int>,
    ): JsonObject {
        val node = describe(element)
        if (depth <= 0) return node
        expanded[element] = depth
        val scope = scope(includeLibraries)
        // 每个往上展开的节点都补查一次：B 调 A、而 IDE 分析不了的文件又调 B 时，提醒要挂在 B 上，不能只在 A 上查。
        node.addUnanalyzedMentions(element, scope)
        // 同一个调用方可能在多处调用：按调用方分组，调用点挂在它下面。
        val groups = LinkedHashMap<PsiElement, MutableList<JsonObject>>()
        val loose = JsonArray()
        // 通过接口、父类调用的地方记在上层声明名下（`greeter.greet()` 里 greeter 是接口类型），也是它的调用方，标明经由谁。
        val parents = memberSupers(element) { supers(element, includeLibraries) }
        if (element in supersIncomplete) node.addProperty("overrides_incomplete", true)
        // 记一处调用点；返回 false 表示额度用完、停止搜索。
        fun record(reference: PsiReference, searched: PsiElement, alias: String?): Boolean {
            val site = usageSite(reference.element, reference.rangeInElement) ?: return true
            site.addProperty("usage", syntaxUsage(reference))
            if (searched != element) site.addProperty("via", "${nameOf(searched)} (${kindOf(searched)})")
            alias?.let { site.addProperty("via_alias", it) }
            // 每个调用点都计入 max_results；用完就停止搜索，免得被调用上万次的函数把搜索拖到底、把结果撑爆。
            if (!budget.take()) return false
            // 页面标签（JSX、Vue 模板里的 <span>、<script>）不能当调用方往上追。
            val owner = hostPosition(reference.element, reference.element.textRange.startOffset)
                ?.let { (file, offset) -> enclosingMember(file, offset, callableOnly = true) }
            if (owner == null) loose.add(site) else groups.getOrPut(owner) { mutableListOf() }.add(site)
            return true
        }
        for (searched in listOf(element) + parents) ReferencesSearch.search(searched, scope).forEach(Processor { reference ->
            // 调用链只看真实调用；注释里提到名字的地方 find_references 会单独列出。
            if (PsiTreeUtil.getParentOfType(reference.element, PsiComment::class.java, false) != null) return@Processor true
            if (!inScope(reference, scope) || isSelfReference(searched, reference)) return@Processor true
            if (!record(reference, searched, null)) return@Processor false
            // 起了别名导入时，真正的调用写的是别名（`_prompt(...)`）：顺着别名接着找，这些也是它的调用方。
            for ((aliasUse, alias) in aliasUses(reference, (searched as? PsiNamedElement)?.name)) {
                if (!record(aliasUse, searched, alias)) return@Processor false
            }
            true
        })
        val callers = JsonArray()
        val referencedBy = JsonArray()
        val uncertain = JsonArray()
        // 调用点在搜索时已计入额度，这里不再为调用方节点重复扣：否则额度刚好被调用点用完时，调用方会一个都显示不出来。
        for ((owner, sites) in groups) {
            // 只是把它当值交出去（`it::receive`、回调、事件处理器）的地方不是执行入口：单列出来，不往上追。
            if (sites.none { it.get("usage")?.asString == "call" }) {
                val group = if (sites.any { it["usage"].asString == "unknown" }) uncertain else referencedBy
                group.add(describe(owner).apply {
                    add("reference_sites", JsonArray().apply { sites.take(CALL_SITES_PER_CALLER).forEach(::add) })
                    if (sites.size > CALL_SITES_PER_CALLER) addProperty("site_count", sites.size)
                })
                continue
            }
            val child = when {
                budget.exhausted -> describe(owner).apply { addProperty("not_expanded", true) }
                owner in path -> describe(owner).apply { addProperty("recursive", true) }
                (expanded[owner] ?: -1) >= depth - 1 -> describe(owner).apply { addProperty("expanded_above", true) }
                else -> callers(owner, depth - 1, includeLibraries, budget, path + owner, expanded)
            }
            child.add("call_sites", JsonArray().apply { sites.take(CALL_SITES_PER_CALLER).forEach(::add) })
            if (sites.size > CALL_SITES_PER_CALLER) child.addProperty("call_site_count", sites.size)
            callers.add(child)
        }
        node.add("callers", callers)
        if (referencedBy.size() > 0) node.add("referenced_without_calling", referencedBy)
        if (uncertain.size() > 0) node.add("unconfirmed_callers", uncertain)
        if (loose.size() > 0) node.add("references_outside_functions", loose)
        // 调用链在这里断了、却是框架或运行环境的入口时要说清：不是没人用，是框架在调。
        // 只查查询目标本身和找不到调用方的节点：判断测试方法这类入口一处要几毫秒到几百毫秒，而常用函数的调用方往往上百个是测试方法，
        // 每个都查会把查询拖慢一两秒；有调用方的节点链条没断，标不标都不影响结论。
        val chainEndsHere = callers.size() == 0 && referencedBy.size() == 0 && loose.size() == 0 && uncertain.size() == 0
        if ((path.size == 1 || chainEndsHere) && implicitlyUsed(element)) node.addProperty("implicit_usage", true)
        return node
    }

    private fun syntaxUsage(reference: PsiReference): String {
        val range = reference.absoluteRange
        if (range.isEmpty) return "unknown"
        var node = reference.element.containingFile?.findElementAt(range.endOffset - 1)?.node
        while (node != null && node.textRange.endOffset == range.endOffset) {
            val parent = node.treeParent ?: break
            val kind = parent.elementType.toString().substringAfterLast(':').trim().uppercase()
            val usage = classifyUsageRole(kind, parent.firstChildNode === node)
            if (usage != null) return usage
            node = parent
        }
        return "unknown"
    }

    private fun callees(element: PsiElement, depth: Int, includeLibraries: Boolean, budget: Budget, path: Set<PsiElement>): JsonObject {
        val node = describe(element)
        if (depth <= 0) return node
        val targets = LinkedHashSet<PsiElement>()
        val evidence = mutableMapOf<PsiElement, CallSiteEvidence>()
        // 声明本身不会执行函数体；从根查起时才进入它。lambda 沿用外层的归属。
        val body = com.intellij.psi.SyntaxTraverser.psiTraverser(element).expand { it == element || !isNamedFunction(it) }
        for (child in body) {
            if (child != element && isNamedFunction(child)) continue
            for (reference in child.references) {
                val resolved = resolve(reference) ?: continue
                // 直接递归单独记下：下面排除「自己体内的东西」时会连自己一起排除掉。
                // 只把自己当值交出去（`register(::tick)`）不是递归。
                // 自己体内的局部变量、参数不算依赖；包、目录这类没有文件的节点也跳过。
                if (resolved is PsiDirectory || resolved.containingFile == null) continue
                val function = isNamedFunction(resolved)
                if (!function && resolved != element && PsiTreeUtil.isAncestor(element, resolved, false)) continue
                // 项目里的依赖只算结构视图里有的声明（函数、类型、字段、常量）：具名参数 `f(callableOnly = true)`
                // 解析到的是别的函数的参数，不是依赖。库里的只计数，不在这里建结构视图。
                // 构造调用 `Budget(…)` 解析到构造函数，它不在结构视图里：换成外层同名的类。
                val declaration = if (function || !isInProject(resolved) || resolved in outline(resolved.containingFile)) resolved else sameNameOwner(resolved)
                if (declaration != null) {
                    targets += declaration
                    if (!includeLibraries && !isInProject(declaration)) continue
                    evidence.getOrPut(declaration) { CallSiteEvidence() }.record(syntaxUsage(reference)) {
                        usageSite(reference.element, reference.rangeInElement)
                    }
                }
            }
        }
        // 语言内置和依赖库（String、equals、标准库函数……）默认只计数：改项目代码时要看的是项目里的依赖。
        val (project, libraries) = targets.partition { isInProject(it) }
        val callees = JsonArray()
        for (target in if (includeLibraries) project + libraries else project) {
            if (!budget.take()) break
            val uses = evidence.getValue(target)
            val expand = uses.called && depth > 1 && target !in path && isProjectLeafMember(target)
            val child = if (expand) callees(target, depth - 1, includeLibraries, budget, path + target) else describe(target)
            uses.writeTo(child, self = target == element)
            callees.add(child)
        }
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

    // 用 PSI 的语法角色而非本地化 kind 文案或结构视图：后者可能省略 Kotlin 的局部 fun，
    // 也可能把赋给变量的匿名 JS 箭头显示成具名 function。
    private fun isNamedFunction(element: PsiElement): Boolean = element is PsiNameIdentifierOwner &&
        element.nameIdentifier != null && when (element.node?.elementType?.toString()?.substringAfterLast(':')?.trim()) {
            "FUN", "FUNCTION_DECLARATION", "FUNCTION", "METHOD" -> true
            else -> false
        }

    /** 项目内的具名函数允许有局部声明；其它成员仍只展开结构视图叶子。 */
    private fun isProjectLeafMember(element: PsiElement): Boolean {
        val file = element.containingFile ?: return false
        val vf = file.virtualFile ?: return false
        if (!fileIndex.isInContent(vf)) return false
        if (isNamedFunction(element)) return true
        val outline = outline(file)
        return element in outline && outline.none { it != element && PsiTreeUtil.isAncestor(element, it, true) }
    }

    private sealed interface Located {
        data class Found(val element: PsiElement) : Located
        data class Failed(val result: CodeToolResult) : Located
    }

    private fun failure(reason: String, message: String) = Located.Failed(codeFailure(reason, message))

    private fun locate(query: CodeQuery): Located {
        val (psiFile, document) = when (val opened = openFile(query.file)) {
            is Opened.File -> opened.file to opened.document
            is Opened.Failed -> return Located.Failed(opened.result)
        }
        if (!supportsFindUsages(psiFile)) {
            return failure(
                "language_not_supported",
                "The IDE has no Find Usages support for ${psiFile.name} (${psiFile.language.displayName}), so it cannot resolve references in it. " +
                    "Use the language server (LSP) tools or text search for this file instead.",
            )
        }
        if (query.line < 1 || query.line > document.lineCount) return failure("bad_position", "line ${query.line} is outside 1..${document.lineCount}.")
        val lineStart = document.getLineStartOffset(query.line - 1)
        val lineEnd = document.getLineEndOffset(query.line - 1)
        val lineText = document.charsSequence.subSequence(lineStart, lineEnd).toString()
        val offsets: List<Int> = when {
            // 同时给了名字和列号：同一行有几个同名符号时（一行里写了两个重载 `void run(); void run(int n);`），
            // 按列号挑最近的那个，否则总会选中第一个、悄悄查错对象。文件大纲给每个节点都带了列号，就是为了这种情况。
            query.symbol != null -> wordOffsets(lineText, query.symbol).map { lineStart + it }
                .sortedBy { offset -> query.column?.let { kotlin.math.abs(offset - (lineStart + it - 1)) } ?: 0 }
                .ifEmpty {
                // 智能体常拿改动前的行号来查：顺带给出附近出现这个名字的行，下一次就能直接查对，不必再翻文件。
                val nearby = nearbyLines(query.line, document.lineCount, query.symbol) { n ->
                    document.charsSequence.subSequence(document.getLineStartOffset(n - 1), document.getLineEndOffset(n - 1))
                }
                val hint = if (nearby.isEmpty()) "" else " It appears nearby on line ${nearby.joinToString(", ")}."
                return failure("symbol_not_on_line", "`${query.symbol}` does not appear on line ${query.line}: ${lineText.trim().take(200)}$hint")
            }
            query.column != null -> listOf((lineStart + query.column - 1).coerceIn(lineStart, lineEnd))
            else -> (lineStart until lineEnd).filter { !lineText[it - lineStart].isWhitespace() }
        }
        for (offset in offsets) {
            symbolAt(psiFile, offset, query.line, document, query.symbol)?.let { return Located.Found(it) }
        }
        // 名字就在这一行、IDE 也认出这是个引用，但解析不到它指的是谁：多半是库里的名字，而项目没配 SDK 或缺依赖
        // （实测：没配 Python 解释器时，`class _QuizQuestion(BaseModel)` 里的 BaseModel 就是这样）。
        // 早先提示「请传入 symbol」，可 symbol 明明传了，会把智能体引到反复换写法重试上。
        if (query.symbol != null && offsets.any { psiFile.findReferenceAt(it) != null }) {
            return failure(
                "unresolved",
                "The IDE sees `${query.symbol}` on line ${query.line} as a reference but cannot tell what it refers to. " +
                    "It is most likely a library name, and the project's SDK (Python interpreter or virtual environment, JDK) or that dependency " +
                    "is not set up in the IDE. Tell the user if it matters; for now use the language server or text search for this name.",
            )
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
     * 文件大纲：IDE 结构视图的那棵树，整理成智能体能直接用的样子——每个节点带显示名（语言给了签名就带签名）、
     * 能交给另外三个工具的名字 `symbol`、种类、名字所在的行和结束行。
     *
     * 结构视图是给人在侧栏里浏览用的。JavaScript 一族（JS、TS、Vue 里的脚本）的结构视图有三类别的语言没有的噪音，
     * 下面的规则**只对这一族的节点生效**——早先对所有语言生效时，误伤了 Python 带父类的类（显示名是 `User(Base)`，
     * 被当成函数、方法全丢）、Python 在 `__init__` 里赋值的实例字段、名字带括号的 YAML/JSON 键（下级全丢）：
     * - 对象字面量被列成无名节点、底下挂一条条字段：无名节点不列，底下只保留跨多行或还有下级的（Vue 选项式写法的方法
     *   正写在无名对象里，不能整棵丢掉）；
     * - 会深入函数体，列出局部变量、临时对象：显示名带括号的节点（函数）不展开范围在它内部的下级；
     * - 赋值语句（`this.total = 0`）被列成节点、还常挂错位置：带引用、没下级、并且超出父节点范围或落在兄弟节点范围内的不列。
     * 是不是这一族，按节点 PSI 的语言往上找基础语言、看有没有 JavaScript；不判断「种类」文字——那是按界面语言翻译过的。
     * 这些规则都在真实 IDE 里逐一实测过（Kotlin、Java、Go、Python、TypeScript、JavaScript、Vue、YAML、JSON、Markdown、XML、.properties）。
     */
    fun fileOutline(path: String, limit: Int): CodeToolResult {
        val (psiFile, document) = when (val opened = openFile(path)) {
            is Opened.File -> opened.file to opened.document
            is Opened.Failed -> return opened.result
        }
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psiFile) as? TreeBasedStructureViewBuilder
            ?: return codeFailure(
                "language_not_supported",
                "The IDE has no structure view for ${psiFile.name} (${psiFile.language.displayName}). Read the file directly or use text search instead.",
            )
        val model = builder.createStructureViewModel(null)
        try {
            var emitted = 0
            var total = 0
            var visited = 0
            var cutShort = false
            val outlineDeadline = System.nanoTime() + OUTLINE_TIME_BUDGET_NANOS
            // 可见性借各语言自己的「显示非公开成员」过滤器判断：Java、Kotlin 有，TypeScript、Python 没有。
            // 过滤器的名字各语言不同（SHOW_NON_PUBLIC、KOTLIN_SHOW_NON_PUBLIC），按结尾认；可见性的判断类在各语言插件里，不能直接用。
            val nonPublicFilter = model.filters.firstOrNull { it.name.endsWith("SHOW_NON_PUBLIC") }
            // 大纲本身不需要索引，标父类要解析引用、需要索引：IDE 在建索引时只给大纲，不标父类，结果里注明。
            val supersAvailable = !DumbService.isDumb(project)
            // 大纲进行到一半 IDE 开始建索引：后面的类标不了父类，要在结果里说明，不能让智能体以为它们没有父类。
            var supersInterrupted = false
            fun lineOf(offset: Int) = document.getLineNumber(offset.coerceIn(0, document.textLength)) + 1
            fun inFile(element: PsiElement?) = element != null && element.isValid && element.containingFile == psiFile
            fun childrenOf(node: StructureViewTreeElement) = node.children.filterIsInstance<StructureViewTreeElement>()

            // level 是实际遍历的层数，无名节点也算一层；输出时无名节点摊平，但遍历预算照样计入——
            // 否则一串连续的无名节点能绕过层数上限，递归把线程栈撑爆。
            fun walk(node: StructureViewTreeElement, children: List<StructureViewTreeElement>, level: Int, underAnonymous: Boolean, insideOf: TextRange?, out: MutableList<OutlineItem>) {
                if (level > OUTLINE_MAX_DEPTH) {
                    if (children.isNotEmpty()) cutShort = true
                    return
                }
                val parentRange = (node.value as? PsiElement)?.takeIf(::inFile)?.textRange
                // 兄弟节点的范围只算一次：每个叶子都要拿来比，大文件里同级节点可能成千上万。
                val siblingRanges: List<TextRange> by lazy { children.mapNotNull { (it.value as? PsiElement)?.takeIf(::inFile)?.textRange } }
                for (tree in children) {
                    ProgressManager.checkCanceled()
                    // 每取一个节点都检查预算：几万项都在同一层的扁平文件（.properties），只在进下一层时检查是挡不住的。
                    if (++visited > OUTLINE_MAX_NODES || System.nanoTime() > outlineDeadline) {
                        cutShort = true
                        return
                    }
                    val raw = tree.value
                    if (raw !is PsiElement) {
                        // 不是代码元素的分组节点（有些语言用来归类）：它本身不列，下级照样看，不能连同下级整棵丢掉。
                        walk(tree, childrenOf(tree), level + 1, underAnonymous, insideOf, out)
                        continue
                    }
                    // 指向别的文件的节点不看：Go 的结构视图会把同包其它文件的函数也挂进来。
                    val value = raw.takeIf(::inFile) ?: continue
                    val range = value.textRange ?: continue
                    val script = isJavaScriptFamily(value)
                    if (script && insideOf != null && insideOf.contains(range)) continue
                    val grandchildren = childrenOf(tree)
                    val leaf = grandchildren.isEmpty()
                    if (script && leaf && isReference(value)) {
                        if (parentRange != null && !parentRange.contains(range)) continue
                        if (siblingRanges.any { it != range && it.contains(range) }) continue
                    }
                    val name = tree.presentation.presentableText?.trim()
                    if (name.isNullOrEmpty()) {
                        walk(tree, grandchildren, level + 1, true, insideOf, out)
                        continue
                    }
                    if (script && underAnonymous && leaf && lineOf(range.startOffset) == lineOf(range.endOffset)) continue
                    total++
                    val bodyRange = if (script && name.contains('(')) range else null
                    val item = OutlineItem(tree, value, name, range, bodyRange, grandchildren)
                    walk(tree, grandchildren, level + 1, false, bodyRange, item.children)
                    out += item
                }
            }

            // 详细信息只给挑中的节点生成：种类、父类这些要解析，没挑中的不做多余计算。
            fun toJson(item: OutlineItem, kept: Set<OutlineItem>): JsonObject = JsonObject().apply {
                val value = item.value
                val name = item.name
                val range = item.range
                addProperty("name", name)
                // 显示名常带签名（`compute(Int): Result`），不能直接当 symbol 交给另外三个工具；这里给出声明自己的名字。
                (value as? PsiNamedElement)?.name?.takeIf { it.isNotBlank() && it != name }?.let { addProperty("symbol", it) }
                addProperty("kind", kindOf(value))
                val nameOffset = (value as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange?.startOffset ?: range.startOffset
                addProperty("line", lineOf(nameOffset))
                // 列号：同一行写了两个重载时，只靠行号和名字分不出是哪一个。
                addProperty("column", nameOffset - document.getLineStartOffset(lineOf(nameOffset) - 1) + 1)
                addProperty("end_line", lineOf(range.endOffset))
                item.tree.presentation.locationString?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("detail", it.take(OUTLINE_DETAIL_CHARS)) }
                if (nonPublicFilter != null && !nonPublicFilter.isVisible(item.tree)) addProperty("non_public", true)
                // 只给这几种节点标父类，宁可少标不标错：
                // - 下面有成员的（类、接口、对象、枚举）；
                // - 显示名带括号的：Python 的空类 `MyError(Exception)`，括号里就是父类（函数也会进来，但头部有参数声明，读出来是空的）；
                // - 名字后面（跳过泛型）紧跟 extends / implements 的空类：Java、TS 的 `class Base implements Shape {}`。
                // 其余没有成员的节点不标：按位置分不出它是空的类还是变量——Python 类属性 `id: int`、TS 的 `const repo: Repository`
                // 显示名都不带类型，按类处理会把类型注解标成父类。Kotlin 用冒号表示继承，和类型注解分不开，它的空类
                // （`class MyErr : Exception()`）要看父类用查继承工具。带上父类的位置，智能体就知道接下来该去哪个文件看大纲。
                if (item.bodyRange == null && supersAvailable &&
                    (item.structureChildren.isNotEmpty() || name.contains('(') || inheritsByKeyword(value, document))
                ) {
                    val memberRanges = item.structureChildren.mapNotNull { (it.value as? PsiElement)?.takeIf(::inFile)?.textRange }
                    when (val found = safeSupers(value, name, document, memberRanges)) {
                        null -> supersInterrupted = true
                        else -> if (found.isNotEmpty()) add("supers", JsonArray().apply { found.forEach { add(describe(it)) } })
                    }
                }
                val shown = item.children.filter { it in kept }
                if (shown.isNotEmpty()) add("children", JsonArray().apply { shown.forEach { add(toJson(it, kept)) } })
                // 名额用完、下级没给全：说明还有多少，想看可以把 max_results 调大再查。
                if (shown.size < item.children.size) addProperty("children_omitted", item.children.size - shown.size)
            }

            val roots = ArrayList<OutlineItem>()
            walk(model.root, childrenOf(model.root), 0, false, null, roots)
            // 名额不够时按层挑：先给全顶层，再给第二层……大纲是用来纵观全局的。早先一边遍历一边输出（深度优先），
            // 名额被前几个类的构造参数、枚举值占满，文件里最重要的类反倒没出现（实测：上限 25 时，副驾驶文件只列出前 4 个数据类的内部）。
            val kept = LinkedHashSet<OutlineItem>()
            val queue = ArrayDeque(roots)
            while (queue.isNotEmpty() && kept.size < limit) {
                val next = queue.removeFirst()
                kept += next
                queue.addAll(next.children)
            }
            emitted = kept.size
            val nodes = JsonArray().apply { roots.filter { it in kept }.forEach { add(toJson(it, kept)) } }

            return CodeToolResult(JsonObject().apply {
                addProperty("file", relativePath(psiFile))
                addProperty("language", psiFile.language.displayName)
                addProperty("line_count", document.lineCount)
                // 有没有可见性信息要说清：没有时不标 non_public，不等于全是公开的。
                addProperty("visibility_known", nonPublicFilter != null)
                if (!supersAvailable || supersInterrupted) addProperty("supers_skipped", "The IDE is indexing; parents (supers) are not listed for some or all types this time. Ask again once indexing finishes.")
                add("outline", nodes)
                // 名额连顶层都没装下：说明顶层还有多少没列出，智能体才知道这不是文件的全部目录。
                roots.count { it !in kept }.takeIf { it > 0 }?.let { addProperty("top_level_omitted", it) }
                addProperty("node_count", emitted)
                if (emitted < total || cutShort) {
                    addProperty("truncated", true)
                    addProperty("total_nodes", total)
                    // 撞到层数或总数的硬上限时没数完：总数只是下限，不能拿来估算文件规模。
                    if (cutShort) addProperty("total_nodes_is_lower_bound", true)
                }
            })
        } finally {
            Disposer.dispose(model)
        }
    }

    /**
     * 标父类出任何意外都只让这个节点不标父类，不能拖垮整个大纲：实测 Kotlin 插件会给个别引用一个起点为负的位置，
     * 一取位置就抛异常。被建索引打断时返回 null，调用方据此注明「父类没标全」。取消信号照常抛出。
     */
    private fun safeSupers(
        element: PsiElement,
        displayName: String,
        document: com.intellij.openapi.editor.Document,
        memberRanges: List<TextRange>,
    ): List<PsiElement>? =
        try {
            writtenSupers(element, displayName, document, memberRanges)
        } catch (e: com.intellij.openapi.project.IndexNotReadyException) {
            null // 返回 null 表示「被建索引打断」，由调用方在结果里注明
        } catch (e: Exception) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException || e is java.util.concurrent.CancellationException) throw e
            emptyList()
        }

    /**
     * 声明头部写明的父类、接口（`class User(Base)`、`class HttpRepo extends Cache implements Repo`、`class Impl(x: X) : Base(1), Marker`）。
     *
     * 不靠搜索确认：搜索要先让 IDE 分析整个文件的类型关系，第一次看一个上千行的 Kotlin 文件要四秒左右，分析过后又只要零点一秒，
     * 同一个文件两次调用结果会不一样（实测）。改成只读声明头部，按位置排除不是父类的引用。规则都与语言无关，在真实 IDE 里
     * 对 Java、Kotlin、TypeScript、Python 的各种写法逐一核对过：
     * - 头部从声明自己的名字开始（名字前面的注解 `@Entity` 不算）：名字后面先出现 `{` 就到 `{` 为止（Java、Kotlin、TS、Go，
     *   多行的 `extends ... implements ...` 也在内）；否则到括号外的第一个换行为止——Python 的类头部只有 `class User(Base):`
     *   这一行，类体里的类型注解（`tags: list[Tag]`）、文档字符串都不会混进来。字符串、注释里的字符不参与判断。
     * - 名字后面紧跟的那对括号是什么，看显示名：Python 的类显示成 `User(Base)`，括号里就是父类，只取括号里的；其它语言的
     *   显示名不带括号（`Impl`、`RED`），头部里任何括号里的都不算：名字后面那对是构造参数或枚举常量的参数，
     *   父类后面那对是传给父类构造函数的参数（`: LocalMcpHandler(CODE_MCP_PATH)`）。
     * - 头部里有参数声明的是函数（`fun foo(a: Int): Result`、`def greet(self)`），不标：函数的返回类型、参数类型都不是父类。
     * - 落在这个声明的成员范围里的不算（Kotlin 的构造参数、构造函数）；注解里的不算（`@Inject constructor`）；
     *   紧贴名字的尖括号、方括号里的不算（泛型参数和约束 `ArrayList<String>`、`<T extends Number>`，`a <= b` 这种比较不算尖括号）；
     *   赋值右边的不算（Python 的 `metaclass=ABCMeta`、JS 的 `X = Symbol.for(...)`）；后面紧跟点号的不算（`unittest.TestCase` 里的 `unittest`）；
     *   解析到这个声明自己或它内部的不算（`Base(x)` 里的参数 `x`）。
     * 可能多标的写法（比如 TS 的 `extends mixin(A, B)`）很少见，多标只会让智能体多看一个文件，不会让它漏看。
     * 解析引用要用索引：调用方在 IDE 建索引时不调这里。
     */
    private fun writtenSupers(
        element: PsiElement,
        displayName: String,
        document: com.intellij.openapi.editor.Document,
        memberRanges: List<TextRange>,
    ): List<PsiElement> {
        val nameRange = (element as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange ?: return emptyList()
        val range = element.textRange ?: return emptyList()
        if (!range.contains(nameRange) || range.endOffset > document.textLength) return emptyList()
        val chars = document.charsSequence
        // JavaScript 一族里，名字后面紧跟冒号的是类型注解（`const repo: Repository`）：TS 的类用 extends/implements 表示继承，不用冒号。
        if (isJavaScriptFamily(element)) {
            var k = nameRange.endOffset
            while (k < range.endOffset && chars[k].isWhitespace()) k++
            if (k < range.endOffset && chars[k] == ':') return emptyList()
        }

        // 从名字后面扫一遍（跳过字符串和注释）：括号外的第一个 `{`、第一个换行，各组括号的范围，紧贴名字的尖括号、方括号。
        val end = range.endOffset
        val parenGroups = ArrayList<TextRange>()
        val genericRanges = ArrayList<TextRange>()
        var firstBrace = -1
        var firstNewline = -1
        run {
            var i = nameRange.endOffset
            var depth = 0
            var groupStart = -1
            var quote: Char? = null
            while (i < end && (firstBrace < 0 || firstNewline < 0)) {
                val c = chars[i]
                if (quote != null) {
                    if (c == '\\') i++ else if (c == quote) quote = null
                    i++
                    continue
                }
                // 行注释：`//`，以及 Python、Ruby、Shell 的 `#`（注释里的撇号、括号不能搅乱引号和括号的计数）。
                if ((c == '/' && i + 1 < end && chars[i + 1] == '/') || c == '#') {
                    while (i < end && chars[i] != '\n') i++
                    continue
                }
                if (c == '/' && i + 1 < end && chars[i + 1] == '*') {
                    var k = i + 2
                    while (k + 1 < end && !(chars[k] == '*' && chars[k + 1] == '/')) k++
                    i = k + 2
                    continue
                }
                val previous = if (i > 0) chars[i - 1] else ' '
                val glued = previous.isLetterOrDigit() || previous == '_' || previous == '>' || previous == ']'
                when {
                    c == '"' || c == '\'' || c == '`' -> quote = c
                    // 紧贴名字的尖括号、方括号才是泛型（`ArrayList<String>`、`Generic[T]`）；`a <= b`、`a < b` 不算。
                    (c == '<' || c == '[') && glued && !(c == '<' && i + 1 < end && chars[i + 1] == '=') -> {
                        val close = if (c == '<') '>' else ']'
                        var k = i + 1
                        var nest = 1
                        while (k < end && nest > 0) {
                            if (chars[k] == c) nest++ else if (chars[k] == close && !(close == '>' && chars[k - 1] == '-')) nest--
                            k++
                        }
                        genericRanges += TextRange(i, k)
                        i = k
                        continue
                    }
                    c == '(' -> { if (depth == 0) groupStart = i; depth++ }
                    c == ')' -> { if (depth > 0) depth--; if (depth == 0 && groupStart >= 0) { parenGroups += TextRange(groupStart, i + 1); groupStart = -1 } }
                    c == '{' && depth == 0 && firstBrace < 0 -> firstBrace = i
                    c == '\n' && depth == 0 && firstNewline < 0 -> firstNewline = i
                }
                i++
            }
        }
        // 类体里的成员（不算括号里的构造参数）出现在换行和 `{` 之间，说明这个 `{` 已经在类体里了（Python 的字典），头部到换行为止；
        // 否则 `{` 是类体的开始，头部到 `{` 为止（Java 多行的 extends/implements 也在内）。
        val bodyMembers = memberRanges.filter { m -> parenGroups.none { it.contains(m.startOffset) } }
        val headerEnd = when {
            firstBrace >= 0 && (firstNewline < 0 || firstNewline > firstBrace || bodyMembers.none { it.startOffset in firstNewline until firstBrace }) -> firstBrace
            firstNewline >= 0 -> firstNewline
            else -> end
        }
        val parenGroup = parenGroups.firstOrNull()?.takeIf { chars.subSequence(nameRange.endOffset, it.startOffset).isBlank() }
        val header = TextRange(nameRange.endOffset, headerEnd.coerceAtLeast(nameRange.endOffset))
        if (header.isEmpty) return emptyList()
        val onlyInsideParens = displayName.contains('(')
        fun counts(offset: Int): Boolean {
            if (!header.contains(offset) || memberRanges.any { it.contains(offset) } || genericRanges.any { it.contains(offset) }) return false
            // Python 只取类名后面那对括号里的（父类就写在那里）；其它语言头部里任何括号里的都不算：
            // 类名后面那对是构造参数，父类后面那对是传给父类构造函数的参数（Kotlin 的 `: Base(CODE_PATH)` 里的 CODE_PATH）。
            return if (onlyInsideParens) parenGroup?.contains(offset) == true else parenGroups.none { it.contains(offset) }
        }
        // 头部里有参数声明的是函数：返回类型、参数类型都不是父类。
        val elementsInHeader = ArrayList<PsiElement>()
        fun collect(node: PsiElement) {
            var child = node.firstChild
            while (child != null) {
                val r = child.textRange
                if (r != null && r.intersects(header)) {
                    elementsInHeader += child
                    collect(child)
                }
                child = child.nextSibling
            }
        }
        collect(element)
        if (elementsInHeader.any { it is PsiNameIdentifierOwner && it != element && memberRanges.none { m -> m.contains(it.textRange) } && genericRanges.none { g -> g.contains(it.textRange.startOffset) } }) {
            return emptyList()
        }
        // 赋值右边的不算：从引用往前看，在离开当前这对括号或遇到逗号之前碰到了赋值的 `=`，它就在等号右边。
        // JS 的 `X = Symbol.for(...)` 整段都不算；Python 的 `(Base, metaclass=Meta)` 里 `Meta` 不算、`Base` 算。
        // `==`、`<=`、`>=`、`!=`、`=>`、`->` 不是赋值。
        fun afterAssignment(offset: Int): Boolean {
            var k = offset - 1
            var depth = 0
            while (k >= nameRange.endOffset) {
                // 泛型里的 `=`（TS 的 `class Store<S = {}>`）不是赋值：整段跳过。
                val generic = genericRanges.firstOrNull { it.contains(k) }
                if (generic != null) {
                    k = generic.startOffset - 1
                    continue
                }
                val c = chars[k]
                when {
                    c == ')' || c == ']' -> depth++
                    c == '(' || c == '[' -> { if (depth == 0) return false; depth-- }
                    c == ',' && depth == 0 -> return false
                    c == '=' && depth == 0 -> {
                        val before = if (k > 0) chars[k - 1] else ' '
                        val after = if (k + 1 < chars.length) chars[k + 1] else ' '
                        if (before !in "=<>!:" && after != '=' && after != '>') return true
                    }
                }
                k--
            }
            return false
        }
        val result = LinkedHashSet<PsiElement>()
        for (child in elementsInHeader) {
            if (generateSequence(child) { it.parent }.takeWhile { it != element }.any { it.textMatches("@") || it.text?.startsWith("@") == true }) continue
            for (reference in child.references) {
                // 个别语言插件给出的引用位置是坏的（起点为负），取位置就抛异常：跳过这一个引用。
                val absolute = try { reference.absoluteRange } catch (e: IllegalArgumentException) { continue }
                if (!counts(absolute.startOffset)) continue
                if (afterAssignment(absolute.startOffset) || chars.getOrNull(absolute.endOffset) == '.') continue
                val resolved = resolve(reference) ?: continue
                if (resolved == element || resolved.textRange?.let { r -> resolved.containingFile == element.containingFile && range.contains(r) } == true) continue
                // `: Base(1)`、`super(…)` 解析到的是父类的构造函数：往外找到同名的那一层就是父类本身。
                val name = (resolved as? PsiNamedElement)?.name
                val type = generateSequence(resolved.parent) { it.parent }
                    .takeWhile { it !is PsiFile }
                    .firstOrNull { it is PsiNamedElement && it.name == name && it != element }
                    ?: resolved
                result += type
            }
        }
        return result.distinctBy { it.navigationElement ?: it }
    }

    /**
     * 别名导入（`from m import f as _f`、Kotlin 的 `import a.f as g`）之后，文件里用的是别名：`_f(...)` 指向的是别名这个声明，
     * 查原名的用法只找得到那条 import（实测：DeepTutor 里 grounded_prompt 被四个文件以 `as _prompt` 导入、调用，
     * 查调用方结果为 0）。这里在 [reference] 是一条导入语句里的引用、并且紧跟着给它起了别名时，返回别名在这个文件里的用法。
     *
     * 只认导入语句（语句以 `import`、`from` 开头）：`except Foo as e`、`with open() as f`、`x = foo` 这些都不是别名。
     * 别名要写在被导入的名字后面，并且在离它最近的那一层里找；同一条语句导入了好几个名字（`from m import a, b as y`）时，
     * 只认属于它自己的那个 `as`，`y` 不能算成 `a` 的别名。JS/TS 的别名 IDE 自己会跟过去（查原名就能找到 `_f(...)`），
     * 别名声明在引用内部，这里不会重复。别名只在本文件有效，所以只在本文件里找，开销很小。
     */
    private fun aliasUses(reference: PsiReference, targetName: String?): List<Pair<PsiReference, String>> {
        val element = reference.element
        val file = element.containingFile ?: return emptyList()
        val statement = generateSequence(element) { it.parent }.take(6).takeWhile { it !is PsiFile }
            .firstOrNull { it.text?.trimStart()?.let { text -> text.startsWith("import ") || text.startsWith("from ") } == true }
            ?: return emptyList()
        val end = element.textRange?.endOffset ?: return emptyList()
        var holder: PsiElement? = element.parent
        var alias: PsiNameIdentifierOwner? = null
        while (holder != null && alias == null) {
            alias = PsiTreeUtil.findChildrenOfType(holder, PsiNameIdentifierOwner::class.java).firstOrNull { candidate ->
                val start = candidate.textRange?.startOffset ?: -1
                start >= end && candidate.name != null && candidate.name != targetName && !PsiTreeUtil.isAncestor(candidate, element, false)
            }
            if (holder == statement) {
                // 到了整条语句这一层才找到：语句里导入了不止一个名字时，分不清这个 as 属于谁，宁可不跟。
                if (alias != null && statement.text.contains(',')) return emptyList()
                break
            }
            holder = holder.parent
        }
        val found = alias ?: return emptyList()
        val name = found.name ?: return emptyList()
        val statementRange = statement.textRange
        return ReferencesSearch.search(found, LocalSearchScope(file)).findAll()
            .filter { use -> use.element.textRange?.let { !statementRange.contains(it) } == true }
            .map { it to name }
    }

    /** 名字后面（跳过空白和紧贴的泛型 `<...>`）紧跟 `extends` 或 `implements`：这是类的写法，变量不会这样写。 */
    private fun inheritsByKeyword(element: PsiElement, document: com.intellij.openapi.editor.Document): Boolean {
        val name = (element as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange ?: return false
        val end = element.textRange?.endOffset?.coerceAtMost(document.textLength) ?: return false
        val chars = document.charsSequence
        var i = name.endOffset
        if (i < end && chars[i] == '<') {
            var nest = 0
            while (i < end) {
                if (chars[i] == '<') nest++ else if (chars[i] == '>') nest--
                i++
                if (nest == 0) break
            }
        }
        while (i < end && chars[i].isWhitespace()) i++
        return listOf("extends", "implements").any { keyword ->
            i + keyword.length <= end && chars.subSequence(i, i + keyword.length).toString() == keyword &&
                (i + keyword.length == end || !chars[i + keyword.length].isLetterOrDigit())
        }
    }

    /** 大纲遍历时记下的一个节点：只有轻量信息，挑中了才生成详细信息。 */
    private class OutlineItem(
        val tree: StructureViewTreeElement,
        val value: PsiElement,
        val name: String,
        val range: TextRange,
        val bodyRange: TextRange?,
        val structureChildren: List<StructureViewTreeElement>,
    ) {
        val children = ArrayList<OutlineItem>()
    }

    /** JavaScript 一族（JS、TS，以及 Vue/HTML 里的脚本）：按节点自己的语言往上找基础语言。不依赖 JavaScript 插件的类。 */
    private fun isJavaScriptFamily(element: PsiElement): Boolean =
        generateSequence(element.language) { it.baseLanguage }.any { it.id == "JavaScript" }

    private fun isReference(element: PsiElement) = element.reference != null || element.firstChild?.reference != null

    private sealed interface Opened {
        class File(val file: PsiFile, val document: com.intellij.openapi.editor.Document) : Opened
        class Failed(val result: CodeToolResult) : Opened
    }

    /** 按路径打开文件：找不到、IDE 打不开、没有文本时各自如实说明。定位符号（[locate]）和文件大纲共用。 */
    private fun openFile(path: String): Opened {
        fun fail(message: String) = Opened.Failed(codeFailure("file_not_found", message))
        val vf = findFile(path) ?: return fail("No file at $path. Pass a path relative to the project root or an absolute path.")
        val canonical = vf.canonicalFile ?: return fail("Cannot resolve this file in the project or its dependencies.")
        if (!fileIndex.isInContent(canonical) && !fileIndex.isInLibrary(canonical)) {
            return Opened.Failed(codeFailure("outside_project", "The file is outside this project's content, libraries and SDK."))
        }
        val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return fail("The IDE cannot open $path.")
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return fail("The IDE has no text for $path.")
        return Opened.File(psiFile, document)
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
            markTest(file)
        }
    }

    /**
     * IDE 认作测试代码的文件标上 `in_test`：判断影响范围时，测试里的用法和产品代码里的分量不同。
     * 只问 IDE（测试源根目录，加上各语言插件的规则，如 Go 的 `_test.go`、JS 的 `.spec`/`.test`），不按文件名自己猜；
     * Python 插件没有这条规则，没把测试目录标成测试源的 Python 项目里就标不出来，所以没标不等于不是测试。
     */
    private fun JsonObject.markTest(file: PsiFile) {
        val vf = file.virtualFile ?: return
        if (TestSourcesFilter.isTestSources(vf, project)) addProperty("in_test", true)
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
                markTest(file)
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

internal class CallSiteEvidence {
    private val sites = JsonArray()
    private var count = 0
    private var usage = "reference"
    val called get() = usage == "call"

    fun record(role: String, describeSite: () -> JsonObject?) {
        count++
        if (role == "call" || (role == "unknown" && !called)) usage = role
        if (sites.size() < CALL_SITES_PER_CALLER) {
            describeSite()?.let { site ->
                site.addProperty("usage", role)
                sites.add(site)
            }
        }
    }

    fun writeTo(node: JsonObject, self: Boolean = false) {
        node.addProperty("usage", usage)
        node.add("sites", sites)
        if (self && called) node.addProperty("recursive", true)
        if (count > sites.size()) node.addProperty("site_count", count)
    }
}

/** Only accept syntactic roles with a known meaning. Unrecognized language nodes stay unconfirmed. */
internal fun classifyUsageRole(parentType: String, firstChild: Boolean): String? = when (parentType) {
    "CALL_EXPRESSION", "METHOD_CALL_EXPRESSION", "JS_CALL_EXPRESSION", "GO_CALL_EXPR" ->
        if (firstChild) "call" else null
    "ARGUMENT_LIST", "VALUE_ARGUMENT", "EXPRESSION_LIST", "JS_ARGUMENT_LIST",
    "GO_ARGUMENT_LIST", "CALLABLE_REFERENCE_EXPRESSION", "METHOD_REF_EXPRESSION" -> "reference"
    else -> null
}

/** 对应的测试最多列几个。 */
private const val RELATED_TESTS_LIMIT = 20

/** 往上找重写对象时最多检查的同名候选数。 */
private const val SUPER_CANDIDATE_LIMIT = 60

/** 整次查询里所有按名字扫描（找重写对象、找分析不了的文件）共用的时间上限。 */
private const val QUERY_SCAN_BUDGET_NANOS = 40_000_000_000L

/** 按名字找重写对象（含逐个核对候选）的时间上限。 */
private const val SUPER_SCAN_BUDGET_NANOS = 15_000_000_000L

/** 按名字找重写对象时跳过的文件大小：再大的多是打包、生成产物，建结构视图要几十秒。 */
private const val SUPER_SCAN_MAX_FILE_BYTES = 1_000_000L

/** 往上找重写对象时按名字最多扫的文件数。 */
private const val SUPER_FILE_LIMIT = 300

/** 纯文字文件：其中提到函数名多半是说明，不是代码里的调用。 */
private val PROSE_EXTENSIONS = setOf("md", "markdown", "txt", "log", "rst", "adoc", "csv", "tsv", "license", "notice")

/** 没有扩展名时按文件名识别的纯文字文件。 */
private val PROSE_NAMES = setOf("license", "licence", "notice", "copying", "authors", "readme", "changelog")

/** 每个调用方下最多列出的调用点；超出时给出总数 call_site_count。 */
private const val CALL_SITES_PER_CALLER = 20

/** 文件大纲最多往下走几层（无名节点也算）、最多看多少个节点（含无名、被过滤掉的），防止病态的大文件拖垮查询或撑爆调用栈。 */
private const val OUTLINE_MAX_DEPTH = 32
private const val OUTLINE_MAX_NODES = 20_000


/** 一次大纲的总时限：最坏情况（几万个节点、语言插件取显示名很慢）也要在查询总超时之前停下，给出已有的部分。 */
private const val OUTLINE_TIME_BUDGET_NANOS = 20_000_000_000L

/** 结构视图附带的说明（XML 标签的属性等）可能很长，只留开头。 */
private const val OUTLINE_DETAIL_CHARS = 120

/** [line] 前后 [window] 行内出现 [word]（完整标识符）的行号，按离 [line] 由近到远，最多 [max] 个。 */
internal fun nearbyLines(line: Int, lineCount: Int, word: String, window: Int = 40, max: Int = 3, text: (Int) -> CharSequence): List<Int> =
    (maxOf(1, line - window)..minOf(lineCount, line + window))
        .filter { it != line && wordOffsets(text(it).toString(), word).isNotEmpty() }
        .sortedBy { kotlin.math.abs(it - line) }
        .take(max)

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

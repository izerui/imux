package com.github.izerui.imux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/**
 * 对一份源文件做结构断言的公共工具。
 *
 * 本项目未引入平台 test-framework（见 build.gradle.kts），UI 与接线层没法跑起来做
 * 行为测试，只能对源码做结构断言。这套归一化规则是 `settings/ImuxLspUiSourceTest`
 * 一条一条被实测击穿之后攒出来的，**新的源码断言必须复用它，不要再发明一套**：
 * 两套规则一旦并存，「这里能绕过、那里不能」会变成下一位维护者的猜谜题。
 *
 * 每条规则为什么长这样，见各成员的 KDoc。
 */
class SourceCode(
    path: String,
) {
    /** 原始文本。**绝大多数断言不该用它**，理由见 [normalized]。 */
    val source: String by lazy { File(path).readText() }

    /**
     * 剥掉注释、再把空白归一后的源码。**几乎所有断言都必须跑在它上面，而不是 [source]。**
     *
     * 三个理由，每一个都被实测击穿过：
     *
     * 1. 跑在 [source] 上的 `contains` **可以用注释满足**——把调用点改掉、在下面补一行
     *    注释放上原来的字面量，断言照样绿，而缺陷已经复活。
     * 2. 不归一空白，断言会连缩进和换行位置一起钉死，重新格式化一次就误报。
     * 3. 不剥注释，在被钉住的那段代码里补一行说明就会红——本代码库注释密度极高，
     *    那是大概率发生的误报，报错信息还会把维护者往「你改坏了逻辑」的方向指。
     *
     * 行注释用 `(?<!:)` 排除 `https://`，免得把字符串里的 URL 当成注释吃掉。
     */
    val normalized: String by lazy {
        source
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""(?<!:)//[^\n]*"""), " ")
            .replace(Regex("""\s+"""), " ")
    }

    /**
     * 把空白**全部**去掉、并抹平尾逗号，供代码片段的等价比对使用。
     *
     * 两步各治一类误报，都是实测出来的：
     *
     * 1. [normalized] 只把连续空白压成一个空格，于是「这里必须有一个空格」变成了隐含
     *    要求：`f(panel {` 与 `f( panel {` 行为完全一样，前者却会让「归一后不在意排成
     *    几行」的断言变红。
     * 2. IDEA 一拆行就会自动补尾逗号——`label(command)` 变成 `label(\n command,\n)`，
     *    去空白之后是 `label(command,)`，`contains("label(command)")` 当场误报。
     *    尾逗号在 Kotlin 里**没有任何语义**，抹掉它两个方向都不再误报，
     *    而真正的结构改动仍然会改变结果。
     *
     * 只抹**尾**逗号（后面紧跟收尾定界符的那个），`f(a, b)` 里的分隔逗号原样保留。
     */
    fun compact(code: String): String = code.replace(Regex("""\s+"""), "").replace(Regex(""",(?=[)\]}>])"""), "")

    /**
     * 在 [compact] 之上再抹掉**具名实参的名字**——`f(a, b = x)` 与 `f(a, x)` 等价。
     *
     * IDEA 的「Add name to argument」意图是一次按键的纯重构，语义零变化；不抹的话
     * 那次操作会让整段比对变红，而失败信息说的是「你改坏了逻辑」——又一次「敏感面比
     * 不变量大一圈」，与尾逗号是同一类装饰。
     *
     * 只抹 `(` 或 `,` 紧跟着的 `名字=`，且用 `(?![=])` 排除 `==` / `!=` / `>=` / `<=`。
     * **实参的先后顺序仍然被检查**：具名重排（`f(b = b, a = a)`）抹完是 `f(b,a)`，
     * 与 `f(a,b)` 仍然不等——那本来也不是纯格式改动。
     */
    fun compactArgs(code: String): String = compact(code).replace(Regex("""(?<=[(,])\w+=(?![=])"""), "")

    /** 忽略空白差异地比对整段代码。 */
    fun assertSameCode(
        message: String,
        expected: String,
        actual: String,
    ) {
        assertTrue(
            "$message\n期望（忽略空白）：${expected.replace(Regex("""\s+"""), " ").trim()}\n实际：$actual",
            compactArgs(expected) == compactArgs(actual),
        )
    }

    /**
     * [normalized] 的**压缩视图**，外加「压缩串的第 i 个字符原本落在 normalized 的哪个
     * 下标」这张映射表。规则与 [compact] 完全一致（去全部空白、抹尾逗号）。
     *
     * [bodyAfter] 用它做**格式无关**的锚点定位：锚点写的是人能读的函数签名，
     * 而 IDEA 一把参数表拆行就会写成 `f(\n a: A,\n b: B,\n)`，逐字节匹配的锚点当场
     * 找不到——那是纯格式改动，不该红。定位在压缩视图上做、切片仍然回到 [normalized]
     * 上切，失败信息因此还是人能读的原文。
     */
    private val compactIndex: Pair<String, IntArray> by lazy {
        val dense = StringBuilder()
        val origin = ArrayList<Int>()
        normalized.forEachIndexed { i, c ->
            if (!c.isWhitespace()) {
                dense.append(c)
                origin.add(i)
            }
        }
        val packed = StringBuilder()
        val map = ArrayList<Int>()
        dense.indices.forEach { i ->
            if (dense[i] == ',' && i + 1 < dense.length && dense[i + 1] in ")]}>") return@forEach
            packed.append(dense[i])
            map.add(origin[i])
        }
        packed.toString() to map.toIntArray()
    }

    /**
     * 取出 [anchor] **之后**、直到与第一个 [open] 配对的定界符为止的整段源码。
     *
     * 返回值从 anchor 末尾算起而不是从 [open] 算起，这样表达式体函数的分派表达式
     *（`= when (…) {`）也在结果里——它恰恰是最该被断言的那部分。
     *
     * 在 [normalized] 上做，所以不必担心注释里的括号打乱配平。
     * 用它把断言限定在**某个函数体内**：「这个函数里不得出现 X」这种话，
     * 在整份源码上说会被别处的合法用法搅黄，在函数体内说才是准的。
     *
     * **锚点必须唯一**。用 `indexOf` 取第一个匹配，意味着「把原函数原封不动留成死代码、
     * 另写一个真正被调用的同形函数」能让整组断言钉在一段没人执行的代码上——Kotlin 对
     * 没人调用的 private 函数只报 warning，拦不住。这里直接把「出现两次」判为失败。
     *
     * 锚点写**完整签名**（含形参名），而返回的切片正是从锚点末尾算起。代价是 Rename
     * 一个形参会走到「找不到锚点」这条路上，所以那句失败信息必须**自己把这种可能说出来**，
     * 别让维护者以为函数被删了。
     */
    fun bodyAfter(
        anchor: String,
        open: Char,
    ): String {
        val (packed, map) = compactIndex
        val needle = compact(anchor)
        val at = packed.indexOf(needle)
        assertTrue(
            "源码里找不到锚点：$anchor\n" +
                "锚点写的是完整签名。如果你只是给形参改了个名（纯重构），把这里的签名同步过去即可；" +
                "只有在函数确实被删掉或拆开时，这条失败才意味着逻辑变了。",
            at >= 0,
        )
        assertEquals(
            "锚点出现了不止一次，断言可能钉在一段没人调用的死代码上：$anchor",
            at,
            packed.lastIndexOf(needle),
        )
        val start = map[at + needle.length - 1] + 1
        val from = normalized.indexOf(open, start)
        assertTrue("锚点 $anchor 之后找不到 $open", from >= 0)
        val close = if (open == '{') '}' else ')'
        var depth = 0
        for (i in from until normalized.length) {
            when (normalized[i]) {
                open -> depth++
                close -> if (--depth == 0) return normalized.substring(start, i + 1).trim()
            }
        }
        throw AssertionError("锚点 $anchor 之后的 $open 没有配对")
    }
}

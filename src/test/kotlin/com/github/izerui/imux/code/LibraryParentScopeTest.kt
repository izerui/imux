package com.github.izerui.imux.code

import com.github.izerui.imux.SourceCode
import org.junit.Assert.assertTrue
import org.junit.Test

/** 不启动 IDE 的套件只守查询接线；SDK 索引是否真的给出引用须在 IDE 中验证。 */
class LibraryParentScopeTest {
    private val source = SourceCode("src/main/kotlin/com/github/izerui/imux/code/CodeNavigator.kt")
    private fun wired(fragment: String) = assertTrue(fragment, source.compact(source.normalized).contains(source.compact(fragment)))

    @Test fun `引用结果按请求范围列出上层并据此查接口调用`() {
        wired("val supers = supers(target, query.includeLibraries)")
        wired("for (parent in memberSupers(target) { supers })")
        wired("ReferencesSearch.search(parent, scope)")
    }

    @Test fun `逐层查调用者时保留请求的库范围`() {
        wired("callers(target, depth, query.includeLibraries, budget")
        wired("val scope = scope(includeLibraries)")
        wired("memberSupers(element) { supers(element, includeLibraries) }")
        wired("callers(owner, depth - 1, includeLibraries, budget")
    }
}

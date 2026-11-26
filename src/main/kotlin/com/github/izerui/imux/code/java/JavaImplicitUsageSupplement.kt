package com.github.izerui.imux.code.java

import com.github.izerui.imux.code.ImplicitUsageSupplement
import com.intellij.codeInsight.daemon.ImplicitUsageProvider
import com.intellij.codeInspection.deadCode.UnusedDeclarationInspectionBase
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiModifierListOwner
import org.jetbrains.uast.toUElement
import java.util.concurrent.CancellationException

/**
 * 只在装了 Java 插件的 IDE 里加载（登记在可选配置 `imux-java.xml`），补两种认不出的情形：
 *
 * 1. Kotlin 写的 Spring 代码：Spring 等插件只认 Java 视图，查代码时拿到的却是 Kotlin 源码元素，原样问它们答「不认识」。
 *    这里用 UAST 翻译成 Java 视图（Kotlin 类对应的 Java 类、Kotlin 函数对应的 Java 方法）再问一遍。
 * 2. 程序入口：测试方法、`main`、用户在「未使用代码」检查里设成入口的注解，IDE 不走插件判断，而是查这份入口清单。
 *    用项目当前生效的检查设置，用户改过的入口配置也算数。
 *
 * 本文件以外不能引用 Java 插件的类：没有 Java 插件的 IDE 里，引用它们的代码一旦被加载就会失败。
 */
internal class JavaImplicitUsageSupplement : ImplicitUsageSupplement {
    override fun isImplicitlyUsed(element: PsiElement): Boolean {
        // Java 元素翻译后还是它自己，平台那一步已经问过插件，这里不重复问。
        val javaView = element.toUElement()?.javaPsi?.takeIf { it !== element && it.isValid }
        if (javaView != null && ImplicitUsageProvider.EP_NAME.extensionList.any { askSafely(it, javaView) }) return true
        // 入口清单只对类、方法、字段这类 Java 声明有意义；别的语言元素（Python 函数等）翻译不出 Java 视图，不查。
        val declaration = (javaView ?: element) as? PsiModifierListOwner ?: return false
        val unusedDeclaration = UnusedDeclarationInspectionBase.findUnusedDeclarationInspection(declaration) ?: return false
        return unusedDeclaration.isEntryPoint(declaration)
    }

    /** 单个插件出错只当它不认识，不影响其它插件和后面的入口判断。 */
    private fun askSafely(provider: ImplicitUsageProvider, element: PsiElement): Boolean =
        try {
            provider.isImplicitUsage(element) || provider.isImplicitRead(element) || provider.isImplicitWrite(element)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
}

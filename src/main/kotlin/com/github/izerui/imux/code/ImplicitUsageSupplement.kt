package com.github.izerui.imux.code

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiElement
import java.util.concurrent.CancellationException

/**
 * 补充判断「这段代码会不会被框架或运行环境自动调用」，补平台 [com.intellij.codeInsight.daemon.ImplicitUsageProvider] 认不出的情形。
 *
 * 为什么要另开一个扩展点：能补的判断都要用到 Java 插件（把 Kotlin 元素翻译成 Java 视图、查「程序入口」清单），
 * 而 PyCharm、GoLand 等 IDE 没有 Java 插件。实现登记在可选配置 `imux-java.xml` 里，没有 Java 插件时整个配置不加载，
 * 实现类一次都不会被读进来；这里看到的就是空列表，行为与不补时完全一样。
 */
internal interface ImplicitUsageSupplement {
    fun isImplicitlyUsed(element: PsiElement): Boolean

    companion object {
        private val EP_NAME = ExtensionPointName<ImplicitUsageSupplement>("com.github.izerui.imux.implicitUsageSupplement")

        /**
         * 任一补充判断认出就算。补充判断出任何问题（含 Java 插件改了接口导致的类找不到、方法签名不符这类链接错误）
         * 都只当「不知道」：少一个标记，不能让查询失败。只有取消要原样抛出，读锁才会重试、超时才会生效。
         */
        fun anyImplicitlyUsed(element: PsiElement): Boolean =
            EP_NAME.extensionList.any { supplement ->
                try {
                    supplement.isImplicitlyUsed(element)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                } catch (_: LinkageError) {
                    false
                }
            }
    }
}

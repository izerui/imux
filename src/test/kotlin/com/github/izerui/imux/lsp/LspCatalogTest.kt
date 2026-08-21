package com.github.izerui.imux.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LspCatalogTest {

    @Test
    fun `语言 id 不重复`() {
        val ids = LspCatalog.languages.map(LspLanguage::id)
        assertEquals("语言 id 必须唯一", ids.size, ids.toSet().size)
    }

    /**
     * Claude Code 与 pi-lens 对同一语言可能用不同的 server（Kotlin 上是
     * kotlin-lsp 与 kotlin-language-server），两个二进制都必须在服务器表里
     * 有安装信息，否则 UI 拿不到修复命令，缺口只能显示成一句「未安装」。
     */
    @Test
    fun `每个引用到的二进制都能在服务器表里查到`() {
        LspCatalog.languages.forEach { language ->
            listOfNotNull(language.claudeBinary, language.piLensBinary).forEach { binary ->
                assertNotNull("服务器表缺少 $binary（${language.id} 引用）", LspCatalog.server(binary))
            }
        }
    }

    @Test
    fun `声明了 Claude 插件就必须同时声明其二进制`() {
        LspCatalog.languages.forEach { language ->
            assertEquals(
                "${language.id}: claudePlugin 与 claudeBinary 必须同时存在或同时缺失",
                language.claudePlugin == null,
                language.claudeBinary == null,
            )
        }
    }

    /** gated 的语言才需要用户自己装 server，非 gated 由 pi-lens 自动安装、不该有二进制名。 */
    @Test
    fun `pi-lens 二进制只在 gated 语言上声明`() {
        LspCatalog.languages.forEach { language ->
            assertEquals(
                "${language.id}: piLensGated 与 piLensBinary 必须一致",
                language.piLensGated,
                language.piLensBinary != null,
            )
        }
    }

    /**
     * 共用同一个 server 二进制的语言，pi-lens 侧的判定必须一致。
     *
     * C 与 C++ 都由 clangd-lsp 这一个插件供能，pi-lens 的 docs/language-coverage.md 里
     * 也是 `C/C++` 合并的一行——不可能一个自动装、一个要手动装。目录表里曾经把 C 写成
     * 非 gated，这个矛盾被「只显示有问题的语言」藏住了；改成全量列表后，同一个 clangd
     * 会在同一张表里一行显示「自动安装」、一行显示「不在 PATH」，当场自相矛盾。
     */
    @Test
    fun `共用同一个 Claude 插件二进制的语言 pi-lens 判定必须一致`() {
        LspCatalog.languages
            .filter { it.claudeBinary != null }
            .groupBy { it.claudeBinary }
            .forEach { (binary, group) ->
                val ids = group.map(LspLanguage::id)
                assertEquals(
                    "$binary 被 $ids 共用，piLensGated 必须一致",
                    1,
                    group.map(LspLanguage::piLensGated).toSet().size,
                )
                assertEquals(
                    "$binary 被 $ids 共用，piLensBinary 必须一致",
                    1,
                    group.map(LspLanguage::piLensBinary).toSet().size,
                )
            }
    }

    /** 没有已知安装命令时必须给文档链接，否则用户在 UI 上拿不到任何下一步。 */
    @Test
    fun `没有安装命令的服务器必须有文档链接`() {
        LspCatalog.servers.values.forEach { server ->
            assertTrue(
                "${server.binary}: installCommand 与 docsUrl 不能同时为空",
                server.installCommand != null || server.docsUrl.isNotBlank(),
            )
        }
    }

    @Test
    fun `覆盖 spec 点名的 toolchain-gated 语言`() {
        val gated = LspCatalog.languages.filter(LspLanguage::piLensGated).map(LspLanguage::id).toSet()
        // `c` 与 `cpp` 同属 spec 的 C/C++ 一行，两个都要在
        setOf("go", "java", "kotlin", "swift", "lua", "c", "cpp", "haskell", "elixir", "ocaml", "nix", "fsharp")
            .forEach { assertTrue("缺少 pi-lens gated 语言 $it", it in gated) }
    }

    @Test
    fun `覆盖官方 12 个 Claude Code LSP 插件`() {
        val plugins = LspCatalog.languages.mapNotNull(LspLanguage::claudePlugin).toSet()
        setOf(
            "clangd-lsp", "csharp-lsp", "gopls-lsp", "jdtls-lsp", "kotlin-lsp", "lua-lsp",
            "php-lsp", "pyright-lsp", "ruby-lsp", "rust-analyzer-lsp", "swift-lsp", "typescript-lsp",
        ).forEach { assertTrue("缺少官方插件 $it", it in plugins) }
    }
}

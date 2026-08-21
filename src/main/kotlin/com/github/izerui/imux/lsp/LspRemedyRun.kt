package com.github.izerui.imux.lsp

// 「一条修复建议能不能点一下就跑、跑起来长什么样」的纯逻辑。
//
// 与 LspStatusPresentation 同样的理由单独成文件、且**一行平台 API 都不碰**：
// 设置页只能做源码文本断言，而文本断言总能被「保留被钉住的字面量、在别处改语义」绕开。
// 搬到这里之后，这几个函数能被普通 JUnit 4 **真正调用**——把 canRun 改成恒 true，
// LspRemedyRunTest 当场变红，而设置页那一侧只剩一个调用点，壳里不许再有平台判断。
//
// isMac 作为**参数注入**而不是在这里读 SystemInfo，正是为了这一点：
// SystemInfo 是平台类，一旦在纯函数里读，这个函数就只能在开发者自己的机器上被测到
// 一半——而这里最要命的分支恰恰是「不在 macOS 上会怎样」。
//
// 用行注释而不是 KDoc：这段说的是文件用途，不挂在任何声明上。

/**
 * 这条修复建议该不该配一个执行按钮。
 *
 * 两个条件缺一不可：
 *
 * 1. **有命令可跑**。只有文档链接的（`kotlin-language-server`、`sourcekit-lsp` 等
 *    目录表里 installCommand 为 null 的那几门）跑不了任何东西，给按钮是空头承诺。
 * 2. **这条命令在本平台上验证过**。[RemedyKind.ACTIVATE] 是 CLI 自己的子命令，
 *    跨平台；[RemedyKind.INSTALL] 只在 macOS 上核实过——目录表里有 `brew install llvm`、
 *    `gem install ruby-lsp`、`opam install ocaml-lsp-server` 这样的形状。
 *
 * 第 2 条是这次改动**唯一**真正危险的地方。这些命令从前只是显示出来给人复制，
 * 平台不对用户自己一眼就看出来了；现在按钮点下去是直接执行，Windows 用户点一下
 * 就是在自己机器上跑 `brew`。所以非 macOS 上 INSTALL 一律退回原样——只有
 * `[复制]` 与文档链接，用户仍然拿得到信息，只是不由 imux 代劳。
 */
internal fun canRun(remedy: Remedy, isMac: Boolean): Boolean =
    remedy.command != null && (remedy.kind == RemedyKind.ACTIVATE || isMac)

/**
 * 执行按钮上那个词的 bundle 键。
 *
 * 「激活」与「安装」对用户是两件事：前者一两秒就完，后者可能下载几百兆。
 * 按钮上写对了，用户才知道点下去要不要等。
 *
 * 做成纯映射（而不是在设置页里 `when (remedy.kind)`）是为了让它能被真正调用着测——
 * 壳里一旦出现按 kind 的分支，就能在「两个字面量都还在源码里」的前提下把两个词对调。
 */
internal fun runActionKey(kind: RemedyKind): String = when (kind) {
    RemedyKind.ACTIVATE -> "settings.lsp.action.activate"
    RemedyKind.INSTALL -> "settings.lsp.action.install"
}

/**
 * 丢给终端标签的完整命令行。
 *
 * `-l` 与 `-i` 缺一不可，理由与 [com.github.izerui.imux.terminal.launchCommand]、
 * [ShellBinaryProbe] 完全相同，这个坑项目里踩过两次：
 *
 * - `-l` 读 profile。从 Dock/Finder 启动的 IDE 只有系统默认 PATH
 *   （`/usr/bin:/bin:/usr/sbin:/sbin`），而 `brew`、`go`、`npm`、`rustup`、`gem`
 *   一个都不在里面——不读 profile 的话，用户点「安装」得到的是 `command not found`。
 * - `-i` 读 rc，用户配成 **alias** 或 shell 函数的工具（本机的 `claude` 就是个 alias）
 *   才存在。
 *
 * 从终端 `runIde` 起的沙箱继承了终端的 PATH，所以这个缺陷**在沙箱里永远不会出现**，
 * 只有装到正式 IDE 上才暴露。
 */
internal fun runCommandLine(shell: String, command: String): List<String> =
    listOf(shell, "-l", "-i", "-c", command)

/**
 * 从命令里认出「这一标签在装什么」，用作终端标签名。
 *
 * 用户可能同时开着好几个安装标签（18 门语言，缺口往往不止一处），标签栏上只有
 * 十来个字符的位置——写完整命令的话，`brew install llvm` 与
 * `brew install lua-language-server` 在标签栏上前缀完全一样，认不出是哪个。
 * 取目标名之后是「安装 llvm」「安装 lua-language-server」，一眼可辨。
 *
 * 三步各去掉一层噪音，都对着目录表里的真实命令核过：
 * - 末段：`brew install --cask kotlin-lsp` &#8594; `kotlin-lsp`
 * - 去 `&#64;版本`：`gopls-lsp&#64;claude-plugins-official` &#8594; `gopls-lsp`
 * - 去路径前缀：`golang.org/x/tools/gopls` &#8594; `gopls`
 *
 * 认不出来时退回整条命令：标签名难看远好过一个空标签。
 */
internal fun runTabTarget(command: String): String =
    command.trim()
        .substringAfterLast(' ')
        .substringBefore('@')
        .substringAfterLast('/')
        .ifEmpty { command.trim() }

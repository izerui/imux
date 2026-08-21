package com.github.izerui.imux.lsp

import com.github.izerui.imux.model.AgentType

// 「状态 → 怎么显示」的纯映射。
//
// 单独成文件、且**一行平台 API 都不碰**（不引 ImuxBundle、不引 AllIcons）是刻意的。
// 本项目没有引入平台 test-framework（见 build.gradle.kts），设置页只能做源码文本断言，
// 而源码文本断言天然可以被「原有字面量一个字不改、在它前面加一句守卫」绕开——
// 这一条已经让同一类缺陷在 ImuxLspConfigurable 上反复复活。
//
// 把映射搬到这里之后，它可以被普通 JUnit 4 **真正调用**：改一个字，
// LspStatusPresentationTest 当场变红，前置守卫没有藏身的地方。
// 设置页那一侧只剩两个薄壳（拿键去查 bundle、拿类别去查图标），壳里不许再有任何判断。
//
// 用行注释而不是 KDoc：这段说的是文件用途，不挂在任何声明上，
// 写成 /** */ 会变成悬空 KDoc，被 Dokka 与 IDE 当成孤儿注释。

/**
 * 图标的**语义类别**，而不是某个具体的 `AllIcons` 常量。
 *
 * 这一层存在的理由有两个。其一是可测：枚举不依赖平台，能直接断言。
 * 其二是别把取舍冻死——`NEUTRAL` 现在落在 `AllIcons.General.Note` 上、
 * `QUESTION` 落在 `QuestionDialog` 上，都只是「AllIcons 里语义最接近的那个」，
 * 将来换成更贴的常量是无害微调，不该因此改测试、更不该报得像抓到了缺陷。
 * 测试钉的是「哪些状态属于哪个语义类别」，那才是真正不该变的东西。
 */
internal enum class StatusIconKind {
    /** 一切就绪。 */
    OK,

    /** 用户**真能采取行动**的缺口——只有这一类配警告。 */
    WARNING,

    /** 好消息，但值得说一句（pi-lens 会自动装）。 */
    INFO,

    /** 中性注记：用户对它做不了任何事（官方无对应插件）。 */
    NEUTRAL,

    /** 我们没查出来，不猜。 */
    QUESTION,
}

/**
 * 该状态对应的 bundle 键。
 *
 * [LspStatus.READY] 返回 null——就绪时那一栏显示的是 server 二进制名
 *（见 [serverBinaryFor]），绿勾已经说了「就绪」，这一栏用来回答「是谁在供能」，
 * 没有可翻译的文案。
 */
internal fun statusMessageKey(status: LspStatus): String? = when (status) {
    LspStatus.READY -> null
    LspStatus.MISSING_CONFIG -> "settings.lsp.status.config"
    LspStatus.MISSING_BINARY -> "settings.lsp.status.binary"
    LspStatus.UNKNOWN -> "settings.lsp.status.unknown"
    LspStatus.AUTO_MANAGED -> "settings.lsp.status.auto"
    LspStatus.NOT_AVAILABLE -> "settings.lsp.status.unavailable"
}

/**
 * 该状态的图标语义类别。
 *
 * 唯一真正要守住的不变量：**只有 [LspStatus.MISSING_CONFIG] 与
 * [LspStatus.MISSING_BINARY] 配 [StatusIconKind.WARNING]**。
 * 「pi-lens 会自动装」是好消息，挂个警告牌等于把这次改造要纠正的误解换个形式又说一遍；
 * 「官方无对应插件」和「没查出来」用户都无从处理，警告只会制造焦虑。
 */
internal fun statusIconKind(status: LspStatus): StatusIconKind = when (status) {
    LspStatus.READY -> StatusIconKind.OK
    LspStatus.MISSING_CONFIG, LspStatus.MISSING_BINARY -> StatusIconKind.WARNING
    LspStatus.AUTO_MANAGED -> StatusIconKind.INFO
    LspStatus.NOT_AVAILABLE -> StatusIconKind.NEUTRAL
    LspStatus.UNKNOWN -> StatusIconKind.QUESTION
}

/**
 * 这一组语言的 server 二进制取自哪一边。
 *
 * pi 与挂了 `pi-lens-mcp` 的 Codex 走的是同一套 pi-lens server，Claude Code 用的是
 * 自己的官方插件——Kotlin 上这两者是**不同的两个程序**（`kotlin-language-server`
 * 与 `kotlin-lsp`），所以不能合并成一个字段，也不能在这里取错边。
 */
internal fun serverBinaryFor(language: LspLanguage, agentType: AgentType): String? = when (agentType) {
    AgentType.PI, AgentType.CODEX -> language.piLensBinary
    else -> language.claudeBinary
}

/**
 * 「就绪」那一列**显示出来的那串字**。
 *
 * 绿勾已经说了「就绪」，这一栏回答的是**是谁在供能**，所以显示 server 二进制名。
 * 取不到名字时是空串——那只发生在 pi 侧按需安装的语言上，而那些语言从不是
 * [LspStatus.READY]，界面上落不到这一栏。
 *
 * 连这一句兜底都搬进来，是因为设置页那一侧只能做源码文本断言，而
 * `serverBinaryFor(…).orEmpty()` 里的 `orEmpty` 来自 `kotlin.text` 的**默认导入**：
 * 在设置页里加一句 `private fun String?.orEmpty(): String = ""`，成员扩展的优先级
 * 高于默认导入，被钉死的函数体一个字节都不用改，就绪那一列却会变成整整 18 行空白。
 * 搬到这里之后它能被真正调用着测——改成恒空当场变红，没有藏身的地方。
 *
 * 内部写成 `?: ""` 而不是 `.orEmpty()`，是不让同一个把柄在这边重新长出来。
 */
internal fun readyServerText(language: LspLanguage, agentType: AgentType): String =
    serverBinaryFor(language, agentType) ?: ""

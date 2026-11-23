const STATE_KEY = Symbol.for("com.github.izerui.imux.pi-reply-style");

/**
 * 「最终回复说人话」引导词：作为系统提示里单独的一段加给 pi。
 *
 * 与 IDEA MCP 扩展分开：关掉 MCP 注入时回复风格照样要生效。
 * 环境变量在首次加载时读取并立即删除，不留给 bash 工具的子进程；
 * /new、/resume 会重载扩展，所以把内容留在 globalThis 上供新实例恢复。
 * 任何故障都只是少一段引导，绝不能让 pi 会话起不来。
 */
export default function (pi) {
  try {
    const fromEnvironment = process.env.IMUX_REPLY_STYLE;
    delete process.env.IMUX_REPLY_STYLE;
    if (fromEnvironment?.trim()) globalThis[STATE_KEY] = fromEnvironment;

    const guidance = globalThis[STATE_KEY];
    if (!guidance?.trim()) return;

    pi.on("before_agent_start", (event) => {
      try {
        event.systemPromptOptions.sections.imux_reply_style = guidance;
      } catch {
        // 系统提示结构变化时只少这一段，不影响本轮对话
      }
    });
  } catch {
    // 扩展 API 不兼容时退回无引导，pi 会话照常启动
  }
}

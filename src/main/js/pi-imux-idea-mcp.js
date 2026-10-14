const STATE_KEY = Symbol.for("com.github.izerui.imux.pi-idea-mcp");

export default function (pi) {
  try {
    const fromEnvironment = {
      url: process.env.IMUX_IDEA_MCP_URL,
      projectPath: process.env.IMUX_IDEA_MCP_PROJECT,
      guidance: process.env.IMUX_IDEA_MCP_GUIDANCE,
    };
    delete process.env.IMUX_IDEA_MCP_URL;
    delete process.env.IMUX_IDEA_MCP_PROJECT;
    delete process.env.IMUX_IDEA_MCP_GUIDANCE;

    // Older Pi versions run normally without IDEA integration; no custom MCP fallback.
    if (typeof pi?.registerMcpServer !== "function") return;

    // Pi reloads extensions after the launch environment has been consumed.
    if (fromEnvironment.url) globalThis[STATE_KEY] = fromEnvironment;
    const state = globalThis[STATE_KEY];
    if (!state?.url) return;

    pi.registerMcpServer("idea", {
      url: state.url,
      headers: state.projectPath
        ? { IJ_MCP_SERVER_PROJECT_PATH: state.projectPath }
        : {},
      exposure: "deferred",
      description: "IntelliJ IDEA code intelligence, inspections, refactoring, and run configurations.",
      // Builds and run configurations can take minutes; Pi owns request cancellation.
      timeout: 600,
    });

    if (state.guidance?.trim()) {
      pi.on("before_agent_start", (event) => {
        event.systemPromptOptions.sections.imux_idea_mcp = state.guidance;
      });
    }
  } catch {
    // IDEA integration must never prevent the Pi session from starting.
  }
}

const STATE_KEY = Symbol.for("com.github.izerui.imux.pi-mcp");
const SERVERS = [
  { name: "imux_mobile", url: "IMUX_MOBILE_URL", token: "IMUX_MOBILE_TOKEN" },
  { name: "imux_session", url: "IMUX_SESSION_URL", token: "IMUX_SESSION_TOKEN" },
  { name: "imux_code", url: "IMUX_CODE_URL", token: "IMUX_CODE_TOKEN" },
];

export default function (pi) {
  // 凭证读出后立即从环境中删除，不泄给子进程；扩展重载时从全局状态恢复。
  const state = (globalThis[STATE_KEY] ??= {});
  for (const server of SERVERS) {
    const supplied = { url: process.env[server.url], token: process.env[server.token] };
    delete process.env[server.url];
    delete process.env[server.token];
    if (supplied.url && supplied.token) state[server.name] = supplied;
  }
  // 引导词也由这里注入，与 pi-imux-idea-mcp.js 写同一段（imux_idea_mcp）：两个扩展都在时后写的覆盖先写的，
  // 任何一个缺失或提前退出，另一个都能补上。
  const suppliedGuidance = process.env.IMUX_MCP_GUIDANCE;
  delete process.env.IMUX_MCP_GUIDANCE;
  if (suppliedGuidance?.trim()) state.guidance = suppliedGuidance;
  const available = SERVERS.filter((server) => state[server.name]);
  if (available.length === 0) return;
  if (typeof pi?.registerMcpServer !== "function") {
    console.error(`${available.map((server) => server.name).join(", ")} unavailable: this Pi version has no native MCP support.`);
    return;
  }
  for (const server of available) {
    const { url, token } = state[server.name];
    try {
      pi.registerMcpServer(server.name, {
        url,
        headers: { Authorization: `Bearer ${token}` },
        exposure: "direct",
        timeout: 180,
      });
    } catch {
      console.error(`${server.name} unavailable: MCP registration failed.`);
    }
  }
  // 与 pi-imux-idea-mcp.js 一致：每次加载都注册，扩展重载后钩子会被重建。
  if (state.guidance && typeof pi.on === "function") {
    pi.on("before_agent_start", (event) => {
      event.systemPromptOptions.sections.imux_idea_mcp = state.guidance;
    });
  }
}

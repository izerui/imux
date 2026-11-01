const STATE_KEY = Symbol.for("com.github.izerui.imux.pi-mcp");
const SERVERS = [
  { name: "imux_mobile", url: "IMUX_MOBILE_URL", token: "IMUX_MOBILE_TOKEN" },
  { name: "imux_session", url: "IMUX_SESSION_URL", token: "IMUX_SESSION_TOKEN" },
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
}

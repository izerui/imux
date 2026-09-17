import { StringEnum } from "@earendil-works/pi-ai";
import { Type } from "typebox";

const STATE_KEY = Symbol.for("com.github.izerui.imux.pi-idea-mcp");

// 只给握手（initialize / notifications/initialized / tools/list）设超时：这三个请求
// 不碰用户代码，卡住就是服务端不对，早失败比让 pi 干等好。
//
// **tools/call 绝不能套这个超时。** IDEA 暴露的 build_project、execute_run_configuration、
// execute_terminal_command、xdebug_* 的等待动作正常就会跑几十秒到几分钟，被中断后拿不回
// 结果；而中断在 callTool 那一侧又与「连接断了」长得一样。工具调用只受 pi 传进来的
// 取消信号管辖——用户取消才取消。
const HANDSHAKE_TIMEOUT_MS = 10000;

/** 带上 HTTP 状态码，好让 [callTool] 只对「会话确实失效」那一种失败重试。 */
class McpHttpError extends Error {
  constructor(status) {
    super(`IDEA MCP returned HTTP ${status}`);
    this.status = status;
  }
}

function parseResponse(text) {
  const trimmed = text.trim();
  if (!trimmed) return undefined;
  if (trimmed.startsWith("{")) return JSON.parse(trimmed);

  const payload = trimmed
    .split(/\r?\n/)
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(5).trim())
    .find((line) => line && line !== "[DONE]");
  return payload ? JSON.parse(payload) : undefined;
}

function linkedSignal(signal, timeoutMs) {
  const timeout = AbortSignal.timeout(timeoutMs);
  return signal && typeof AbortSignal.any === "function"
    ? AbortSignal.any([signal, timeout])
    : timeout;
}

async function post(state, message, signal, { expectResponse = true, timeoutMs } = {}) {
  const headers = {
    "content-type": "application/json",
    accept: "application/json, text/event-stream",
  };
  // 项目定向。多项目窗口下不带它，任何没写 projectPath 实参的工具调用都会被服务端
  // 拒绝，返回「Unable to determine the target project」并要求反过来问用户选项目。
  // 下面补 projectPath 实参只能覆盖 schema 里声明了该参数的工具，这个头覆盖全部。
  if (state.projectPath) headers["IJ_MCP_SERVER_PROJECT_PATH"] = state.projectPath;
  if (state.sessionId) headers["mcp-session-id"] = state.sessionId;

  const response = await fetch(state.url, {
    method: "POST",
    headers,
    body: JSON.stringify(message),
    signal: timeoutMs === undefined ? signal : linkedSignal(signal, timeoutMs),
  });
  if (!response.ok) {
    throw new McpHttpError(response.status);
  }
  state.sessionId ||= response.headers.get("mcp-session-id") || undefined;
  if (!expectResponse) return undefined;

  const payload = parseResponse(await response.text());
  if (payload?.error) {
    throw new Error(payload.error.message || "IDEA MCP request failed");
  }
  return payload?.result;
}

async function ensureInitialized(state, signal) {
  if (state.tools) return state.tools;
  if (!state.initializing) {
    state.initializing = (async () => {
      if (!state.initialized) {
        await post(state, {
          jsonrpc: "2.0",
          id: state.nextId++,
          method: "initialize",
          params: {
            protocolVersion: "2025-06-18",
            capabilities: {},
            clientInfo: { name: "imux-pi", version: "1" },
          },
        }, signal, { timeoutMs: HANDSHAKE_TIMEOUT_MS });
        await post(state, {
          jsonrpc: "2.0",
          method: "notifications/initialized",
          params: {},
        }, signal, { expectResponse: false, timeoutMs: HANDSHAKE_TIMEOUT_MS });
        state.initialized = true;
      }
      const listed = await post(state, {
        jsonrpc: "2.0",
        id: state.nextId++,
        method: "tools/list",
        params: {},
      }, signal, { timeoutMs: HANDSHAKE_TIMEOUT_MS });
      const tools = Array.isArray(listed?.tools) ? listed.tools : [];
      // 空清单不进缓存：`if (state.tools) return state.tools` 会把 `[]` 当成有效缓存，
      // 于是「项目还在建索引时问到的那一次空清单」会把整个 pi 会话钉死在零工具上，
      // 除了重启 pi 没有别的出路。不缓存的代价只是下次调用再握手一次。
      if (tools.length) state.tools = tools;
      return tools;
    })().finally(() => {
      state.initializing = undefined;
    });
  }
  return state.initializing;
}

function renderTools(tools) {
  if (!tools.length) return "IDEA MCP connected, but it exposed no tools.";
  return tools
    .map((tool) => `${tool.name}: ${tool.description || "No description"}`)
    .join("\n");
}

function resetConnection(state) {
  state.sessionId = undefined;
  state.initialized = false;
  state.tools = undefined;
  state.initializing = undefined;
}

/**
 * **只对「服务端明确判定会话失效」重试，别的一律如实抛出。**
 *
 * IDEA 或 MCP Server 在长会话中重启后旧 session id 会失效，这一种值得重连；但超时、
 * 网络中断、用户取消、JSON-RPC 业务错误都**不能**重试——工具可能已经在 IDE 里执行完了，
 * 只是响应没回来，重发一次就是 rename/apply_patch/构建**做第二遍**。
 *
 * 判据取 MCP Streamable HTTP 的规定：会话被终止后，服务端对携带该 session id 的请求
 * 返回 404。因此只认「本来就带着 session id」且「收到 404」这一种组合，且只重试一次。
 */
async function callTool(state, tool, args, signal) {
  const request = () => post(state, {
    jsonrpc: "2.0",
    id: state.nextId++,
    method: "tools/call",
    params: { name: tool, arguments: args },
  }, signal);
  const hadSession = Boolean(state.sessionId);
  try {
    return await request();
  } catch (error) {
    if (!hadSession || error?.status !== 404) throw error;
    resetConnection(state);
    await ensureInitialized(state, signal);
    return request();
  }
}

/** MCP 用 `result.isError` 表示工具自身执行失败，这里要还原成文字好放进异常。 */
function errorTextOf(result) {
  const text = (Array.isArray(result?.content) ? result.content : [])
    .filter((part) => part?.type === "text" && part.text)
    .map((part) => part.text)
    .join("\n")
    .trim();
  return text || "no details";
}

export default function (pi) {
  try {
    const fromEnvironment = {
      url: process.env.IMUX_IDEA_MCP_URL,
      projectPath: process.env.IMUX_IDEA_MCP_PROJECT,
    };
    delete process.env.IMUX_IDEA_MCP_URL;
    delete process.env.IMUX_IDEA_MCP_PROJECT;

    if (!globalThis[STATE_KEY] && fromEnvironment.url) {
      globalThis[STATE_KEY] = {
        ...fromEnvironment,
        nextId: 1,
        sessionId: undefined,
        initialized: false,
        tools: undefined,
        initializing: undefined,
      };
    }
    const state = globalThis[STATE_KEY];
    if (!state?.url || typeof pi?.registerTool !== "function") return;

    pi.registerTool({
      name: "idea_mcp",
      label: "IDEA",
      description: "List or call IntelliJ IDEA MCP tools for symbol search, diagnostics, formatting, and refactoring.",
      promptSnippet: "Use IntelliJ IDEA code intelligence through its MCP tools",
      promptGuidelines: [
        "Use idea_mcp for IDE-aware symbol search, diagnostics, call analysis, formatting, and refactoring.",
        "Call idea_mcp with action=list when the required IDEA tool name or arguments are unknown.",
      ],
      parameters: Type.Object({
        action: StringEnum(["list", "call"]),
        tool: Type.Optional(Type.String({ description: "IDEA MCP tool name for action=call" })),
        arguments: Type.Optional(Type.Record(Type.String(), Type.Unknown())),
      }),
      async execute(_toolCallId, params, signal) {
        const tools = await ensureInitialized(state, signal);
        if (params.action === "list") {
          return { content: [{ type: "text", text: renderTools(tools) }], details: { count: tools.length } };
        }
        if (!params.tool) throw new Error("tool is required when action=call");
        const selected = tools.find((tool) => tool.name === params.tool);
        if (!selected) throw new Error(`Unknown IDEA MCP tool: ${params.tool}`);

        const args = { ...(params.arguments || {}) };
        const properties = selected.inputSchema?.properties || {};
        if (state.projectPath && "projectPath" in properties && args.projectPath === undefined) {
          args.projectPath = state.projectPath;
        }
        const result = await callTool(state, params.tool, args, signal);
        // pi 只认「execute 抛异常」这一种失败信号：正常返回一律记成调用成功。把
        // isError 放进 details 不够——模型会看到一次「成功」的重构，实际 IDEA 侧报了错。
        if (result?.isError === true) {
          throw new Error(`IDEA MCP tool ${params.tool} failed: ${errorTextOf(result)}`);
        }
        return {
          content: Array.isArray(result?.content)
            ? result.content
            : [{ type: "text", text: JSON.stringify(result ?? null) }],
          details: { tool: params.tool },
        };
      },
    });
  } catch {
    // IDEA 集成失败只少一个工具，绝不能影响 pi 会话启动。
  }
}

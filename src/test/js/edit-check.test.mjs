import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const script = fileURLToPath(new URL("../../main/js/codex-imux-edit-check.cjs", import.meta.url));
const piSource = await readFile(new URL("../../main/js/pi-imux-mcp.js", import.meta.url), "utf8");
const { default: loadPi } = await import(`data:text/javascript;base64,${Buffer.from(piSource).toString("base64")}`);

async function exerciseAdapters() {
  const events = [];
  const reason = "src/Example.kt:14: Unresolved reference";
  const server = createServer(async (req, res) => {
    assert.equal(req.url, "/imux/code-edit-check");
    assert.equal(req.headers.authorization, "Bearer test-token");
    let body = "";
    for await (const chunk of req) body += chunk;
    const {hook_event_name: event, stop_hook_active: closing} = JSON.parse(body);
    events.push(closing ? "release" : event);
    res.end(JSON.stringify(event === "Stop" ? { decision: "block", reason } : {}));
  });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  try {
  const env = {
    IMUX_CODE_URL: `http://127.0.0.1:${server.address().port}/imux/code-mcp`,
    IMUX_CODE_TOKEN: "test-token",
  };
  async function codex(event, alreadyBlocked = false) {
    const child = spawn(process.execPath, [script], { env: { ...process.env, ...env } });
    let output = "";
    child.stdout.on("data", chunk => { output += chunk; });
    child.stdin.end(JSON.stringify({ hook_event_name: event, stop_hook_active: alreadyBlocked }));
    assert.equal(await new Promise(resolve => child.on("close", resolve)), 0);
    return output;
  }
  assert.equal(await codex("UserPromptSubmit"), "");
  assert.deepEqual(JSON.parse(await codex("Stop")), { decision: "block", reason });
  assert.equal(await codex("Stop", true), "");
  assert.deepEqual(events.splice(0), ["UserPromptSubmit", "Stop", "release"]);

  Object.assign(process.env, env, { IMUX_EDIT_CHECK: "1" });
  const handlers = new Map();
  loadPi({ on: (event, callback) => handlers.set(event, callback), registerMcpServer() {} });
  const start = handlers.get("before_agent_start");
  const settle = handlers.get("agent_before_settle");
  const boundary = { outcome: "completed", continue: false, context: { canContinue: false } };
  await start({ messageType: "user" });
  const result = await settle(boundary);
  assert.equal(result.continue, true);
  assert.equal(result.entries[0].content, reason);
  assert.equal(await settle(boundary), undefined);
  assert.deepEqual(events.splice(0), ["UserPromptSubmit", "Stop", "release"]);
  await start({ messageType: "user" });
  assert.equal(await settle({ ...boundary, outcome: "aborted" }), undefined);
  assert.deepEqual(events, ["UserPromptSubmit", "release"]);
  } finally {
    server.close();
  }
}

await exerciseAdapters();

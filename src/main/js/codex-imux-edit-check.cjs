// A Codex command hook. Keep its definition stable across sessions; the IDE
// endpoint and tab credentials come from the process that launched this hook.
async function main() {
  const url = new URL("/imux/code-edit-check", process.env.IMUX_CODE_URL);
  if (url.origin !== `http://127.0.0.1:${url.port}` || !process.env.IMUX_CODE_TOKEN) return;
  let input = "";
  process.stdin.setEncoding("utf8");
  for await (const chunk of process.stdin) input += chunk;
  const { hook_event_name, stop_hook_active } = JSON.parse(input);
  if (!["UserPromptSubmit", "Stop"].includes(hook_event_name)) return;
  const response = await fetch(url, {
    method: "POST",
    redirect: "error",
    headers: { Authorization: `Bearer ${process.env.IMUX_CODE_TOKEN}`, "Content-Type": "application/json" },
    body: JSON.stringify({ hook_event_name, stop_hook_active }),
    signal: AbortSignal.timeout(hook_event_name === "Stop" ? 40000 : 5000),
  });
  if (!response.ok) return;
  const result = await response.json();
  if (!stop_hook_active && hook_event_name === "Stop" && result.decision === "block" && typeof result.reason === "string") {
    process.stdout.write(JSON.stringify({ decision: "block", reason: result.reason }));
  }
}

main().catch(() => {});

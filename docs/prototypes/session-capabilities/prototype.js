const agents = {
  codex: {
    label: "Codex", version: "0.166.0", session: "会话能力面板", source: "配置查询",
    tabs: [["mcp", "MCP"], ["skills", "技能"], ["runtime", "运行信息"]],
    mcp: [
      ["idea", "JetBrains IDE 工具", "已配置", "config", "MCP 配置", "启动注入", "HTTP · 当前项目", "配置已传给 CLI；当前终端的连接状态未确认。"],
      ["imux_session", "结对编程与单次审查", "已注入", "config", "imux", "本次启动", "HTTP · 会话级", "已为这个会话注入服务配置，不代表它已完成连接。"],
      ["pi-lens", "代码诊断与语言服务", "已配置", "config", "MCP 配置", "用户配置", "stdio", "CLI 配置中启用；没有当前会话的工具加载报告。"]
    ],
    skills: [
      ["openai-docs", "OpenAI 官方文档", "已发现", "config", "skills/list", "用户范围", "~/.codex/skills/.system/openai-docs/SKILL.md", "查询返回启用状态；不表示技能正文已经进入这次会话。"],
      ["huashu-design", "界面原型与设计评审", "已发现", "config", "skills/list", "用户范围", "~/.agents/skills/huashu-design/SKILL.md", "可发现的技能；调用与正文加载状态未确认。"],
      ["agent-browser", "浏览器交互与页面验证", "已发现", "config", "skills/list", "用户范围", "~/.agents/skills/agent-browser/SKILL.md", "未执行浏览器依赖体检。"],
      ["skill-creator", "创建和维护技能", "已发现", "config", "skills/list", "用户范围", "~/.codex/skills/.system/skill-creator/SKILL.md", "从技能查询结果读取。"]
    ],
    runtime: [
      ["工作目录", "~/github/imux", "启动信息", "config", "imux", "本次启动", "~/github/imux", "会话的启动目录。"],
      ["模型", "由 CLI 配置决定", "未确认", "", "配置查询", "当前查询", "config/read · model", "不使用配置值冒充会话中途切换后的实际模型。"],
      ["权限策略", "由 CLI 管理", "配置值", "config", "config/read", "当前查询", "approval_policy / sandbox_mode", "实际生效状态还需会话级证据。"],
      ["上下文占用", "未取得会话数据", "未确认", "", "暂无上报", "当前会话", "暂无", "不根据会话文件大小推算模型上下文占用。"]
    ]
  },
  claude: {
    label: "Claude Code", version: "2.1.308", session: "手机桥接重连", source: "配置与事件",
    tabs: [["mcp", "MCP"], ["plugins", "插件"], ["rules", "规则"], ["runtime", "运行信息"]],
    mcp: [
      ["idea", "JetBrains IDE 工具", "已配置", "config", "启动参数", "本次启动", "HTTP · 当前项目", "当前会话连接状态未确认。"],
      ["imux_session", "结对编程与单次审查", "已注入", "config", "imux", "本次启动", "HTTP · 会话级", "没有用独立健康检查代替当前会话连接结果。"],
      ["imux_mobile", "手机图片与文件发送", "已注入", "config", "imux", "本次启动", "HTTP · 会话级", "服务配置已注入，手机连接状态由桥接单独管理。"]
    ],
    plugins: [
      ["kotlin-lsp", "Kotlin 语言服务", "配置启用", "config", "插件清单", "用户范围", "~/.claude/plugins/", "已安装、配置启用；不等于语言服务器当前正在运行。"],
      ["frontend-design", "前端设计技能", "配置启用", "config", "插件清单", "用户范围", "~/.claude/plugins/", "示例插件条目，不是本机插件清单。"],
      ["code-review", "代码审查工作流", "配置启用", "config", "插件清单", "项目范围", ".claude/settings.json", "项目配置启用状态，不代表已在会话中执行。"]
    ],
    rules: [
      ["CLAUDE.md", "项目开发约定", "已加载", "verified", "InstructionsLoaded", "会话事件", "~/github/imux/CLAUDE.md", "示例加载事件。加载只表示进入上下文，不保证模型遵守。"],
      ["testing.md", "测试命名与验证范围", "已加载", "verified", "InstructionsLoaded", "会话事件", ".claude/rules/testing.md", "示例：按路径匹配后加载的规则。"]
    ],
    runtime: [
      ["当前模型", "等待模型事件", "待上报", "", "模型切换 Hook", "会话事件", "PostModelSwitch", "尚未收到事件时保留未知。"],
      ["权限模式", "acceptEdits", "最近上报", "verified", "会话 Hook", "11:59:47", "permission_mode", "示例状态；以后续会话事件更新。"],
      ["上下文占用", "尚未接入状态栏", "未采集", "", "statusLine", "可选接入", "context_window", "接入时需要保留用户已有的状态栏。"]
    ]
  },
  pi: {
    label: "Pi", version: "1.4.0", session: "补充回归测试", source: "会话内上报",
    tabs: [["mcp", "MCP"], ["tools", "工具"], ["skills", "技能"], ["runtime", "运行信息"]],
    mcp: [
      ["idea", "JetBrains IDE 工具", "已注册", "config", "Pi 扩展", "会话内上报", "exposure: deferred", "会话内注册记录；不直接推断完整连接健康。"],
      ["imux_session", "结对编程与单次审查", "已注册", "config", "Pi 扩展", "会话内上报", "exposure: direct", "直接暴露模式；工具实际声明情况在工具页查看。"]
    ],
    tools: [
      ["read", "读取文件内容", "已声明", "verified", "getActiveTools", "会话内上报", "内置工具", "当前向模型声明的工具。"],
      ["bash", "运行终端命令", "已声明", "verified", "getActiveTools", "会话内上报", "内置工具", "工具可用不等于每次调用都无需审批。"],
      ["edit", "修改现有文件", "已声明", "verified", "getActiveTools", "会话内上报", "内置工具", "从当前工具集合读取。"],
      ["write", "写入文件", "已声明", "verified", "getActiveTools", "会话内上报", "内置工具", "从当前工具集合读取。"],
      ["grep", "搜索文件内容", "已注册", "config", "getAllTools", "会话内上报", "内置工具", "注册与当前声明是不同状态。"],
      ["find", "查找文件", "已注册", "config", "getAllTools", "会话内上报", "内置工具", "不把未声明的工具一律称为不可用。"]
    ],
    skills: [
      ["openai-docs", "OpenAI 官方文档", "可调用", "verified", "getCommands", "用户范围", "~/.codex/skills/.system/openai-docs/SKILL.md", "会话命令清单中存在 skill:openai-docs；不表示正文已加载。"],
      ["huashu-design", "界面原型与设计评审", "可调用", "verified", "getCommands", "用户范围", "~/.agents/skills/huashu-design/SKILL.md", "示例技能，sourceInfo 提供来源路径和范围。"],
      ["fix-tests", "处理失败测试", "可调用", "verified", "getCommands", "项目范围", ".pi/prompts/fix-tests.md", "类型：提示词模板，不是技能。"]
    ],
    runtime: [
      ["模型", "来自当前会话", "已上报", "verified", "ctx.model", "会话内上报", "provider / model", "设计示例不填写未经验证的模型名。"],
      ["思考级别", "high", "已上报", "verified", "getThinkingLevel", "会话内上报", "high", "示例思考级别。"],
      ["上下文占用", "等待首轮响应", "暂无数据", "", "getContextUsage", "会话内上报", "暂无", "没有有效 usage 时不展示虚构百分比。"]
    ]
  }
};
const ide = document.querySelector(".ide");
const inspector = document.querySelector(".inspector");
const agentSelect = document.querySelector("#agent-select");
const usageSummary = document.querySelector("#session-usage");
const sampleUsage = {
  codex: ["44,959,909", `≈ ${Math.round(262758 / 950000 * 100)}%`],
  claude: ["36,280", "等待状态栏上报"],
  pi: ["等待首次响应", "等待首次响应"],
};
if (usageSummary) {
  const services = [
    ["imux_code", "文件大纲、引用、调用链和继承关系"],
    ["imux_session", "开关结对、查询伙伴、预约一次审查"],
    ["imux_mobile", "查询手机连接，发送图片、视频和文件"],
    ["idea", "IDE 检查、构建、运行和调试"],
  ];
  for (const [agent, data] of Object.entries(agents)) {
    data.mcp = services.map(([name, purpose]) => {
      if (agent === "pi" && name === "imux_mobile") {
        return [name, "手机文件传送注册失败，重新打开会话可重试", "接入失败", "warning"];
      }
      return [name, purpose, agent === "pi" ? "已注册" : "已注入", "config"];
    });
    data.tabs = [["runtime", "运行信息"], ["mcp", "MCP"]];
    data.runtime = [
      ["工作目录", "~/github/imux"],
      ["结对编程", agent === "claude" ? "已开启 · Codex" : "已关闭"],
      ["单次审查", agent === "codex" ? "已预约 · Claude Code" : "未预约"],
      ["手机绑定", agent === "claude" ? "已绑定 · 飞书" : "未绑定"],
    ];
  }
  agents.codex.version = "0.171.0";
  agents.claude.version = "2.1.323";
  agents.codex.runtime.push(["最近一次请求", "262,758 Token"], ["上下文窗口", "950,000 Token"]);
  agents.codex.tabs.push(["skills", "技能"]);
  agents.pi.tabs.push(["tools", "工具"]);
  document.querySelector("#resource-search").placeholder = "搜索名称或功能";
  document.querySelector(".toolstrip #open-inspector").insertAdjacentHTML("beforebegin",
    '<span class="quiet-icon" data-icon="phone" title="连接手机"></span>');
}
const search = document.querySelector("#resource-search");
let activeAgent = "codex";
let activeCategory = usageSummary ? "runtime" : "mcp";
let query = "";
const escapeHTML = text => String(text).replace(/[&<>"']/g, char => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[char]));
function icon(name, className = "icon") {
  const theme = ide.classList.contains("light") ? "light" : "dark";
  return `<img class="${className}" alt="" src="${ASSETS[name]?.[theme] ?? ASSETS[name]?.light}">`;
}
function paintList() {
  const data = agents[activeAgent];
  if (usageSummary) {
    const entries = data[activeCategory].map(([name, description, state, status, , , path]) => {
      const location = ["skills", "rules", "plugins"].includes(activeCategory) ? path : "";
      const badge = activeCategory === "runtime" ? "" : state;
      return { name, description, badge, status, location };
    });
    const matches = entries.filter(({ name, description, badge, location }) =>
      [name, description, badge, location].join(" ").toLowerCase().includes(query.toLowerCase()));
    document.querySelector(".resource-list").innerHTML = matches.map(({ name, description, badge, status, location }) => {
      const text = `<span class="resource-text"><span class="resource-title">${escapeHTML(name)}</span><span class="resource-subtitle" style="display:block">${escapeHTML(description)}</span></span>`;
      const value = badge ? `<span class="state ${status}">${escapeHTML(badge)}</span>` : "";
      if (!location) return `<div class="resource plain-row">${text}${value}</div>`;
      return `<details class="resource"><summary>${text}${value}<span aria-hidden="true">⌄</span></summary><div class="detail mono">${escapeHTML(location)}</div></details>`;
    }).join("") || '<p class="empty">没有匹配的条目</p>';
    document.querySelector(".filterbar").hidden = activeCategory === "runtime";
    document.querySelector(".list-heading").hidden = true;
    document.querySelector(".notice").hidden = true;
    return;
  }
  const rows = (data[activeCategory] ?? []).filter(row => row.join(" ").toLowerCase().includes(query.toLowerCase()));
  document.querySelector(".list-heading").innerHTML = `<span>${activeCategory === "runtime" ? "会话与配置" : "名称与来源"}</span><span>${rows.length} 项</span>`;
  document.querySelector(".resource-list").innerHTML = rows.length ? rows.map(row => {
    const [name, description, state, status, source, scope, path, note] = row;
    return `<details class="resource"><summary><span class="resource-icon">${icon(activeCategory === "mcp" ? "settings" : activeCategory === "runtime" ? "info" : "list")}</span><span class="resource-text"><span class="resource-title">${escapeHTML(name)}</span><span class="resource-subtitle" style="display:block">${escapeHTML(description)}</span></span><span class="state ${status}">${escapeHTML(state)}</span></summary><div class="detail"><dl><dt>来源</dt><dd>${escapeHTML(source)}</dd><dt>范围</dt><dd>${escapeHTML(scope)}</dd><dt>位置</dt><dd class="mono">${escapeHTML(path)}</dd></dl><p class="explain">${escapeHTML(note)}</p></div></details>`;
  }).join("") : '<p class="empty">没有匹配的条目</p>';
  const notice = document.querySelector(".notice");
  const message = activeCategory === "mcp" ? "当前列表不代表所有服务已连接。" : activeCategory === "skills" ? "技能可发现或可调用，不等于正文已加载。" : activeCategory === "rules" ? "记录规则的加载事件，不判断模型是否遵守。" : "";
  notice.textContent = message;
  notice.hidden = !message;
}
function paintTabs() {
  const data = agents[activeAgent];
  document.querySelector(".category-nav").innerHTML = data.tabs.map(([id, name]) => `<button type="button" data-category="${id}" aria-pressed="${id === activeCategory}">${name}<span class="count">${data[id].length}</span></button>`).join("");
}
function paintAgent() {
  const data = agents[activeAgent];
  document.querySelector(".identity-icon").innerHTML = icon(activeAgent, "brand");
  document.querySelector(".identity .name").textContent = usageSummary ? data.session : data.label;
  document.querySelector(".identity .meta").textContent = `${data.label} ${data.version} · imux`;
  if (usageSummary) {
    const [tokens, context] = sampleUsage[activeAgent];
    usageSummary.innerHTML = `<div><small>累计 Token</small><strong>${escapeHTML(tokens)}</strong></div><div><small>上下文占用</small><span>${escapeHTML(context)}</span></div>`;
    document.querySelector(".terminal-line.blue").textContent = {
      codex: "› 做完让 Claude Code 审查一次。",
      claude: "› 把刚生成的截图发到手机。",
      pi: "› 查看这个文件的调用链。",
    }[activeAgent];
    document.querySelector(".terminal").querySelectorAll(".terminal-line:not(.blue)").forEach((line, i) => {
      const lines = {
        codex: ["review_once", "已预约 Claude Code 在本轮结束后审查。"],
        claude: ["get_mobile_status", "send_image", "截图已发送到绑定的飞书会话。"],
        pi: ["call_hierarchy", "已取得当前函数的调用位置。"],
      }[activeAgent];
      line.hidden = i >= lines.length;
      line.textContent = lines[i] ?? "";
    });
  }
  document.querySelector(".editor-tab.active").innerHTML = `${icon(activeAgent)}<span>${data.session}</span>`;
  document.querySelector(".session-label").textContent = data.session;
  document.querySelector(".evidence-text").textContent = usageSummary ? "原型快照" : data.source;
  if (agentSelect) agentSelect.value = activeAgent;
  for (const button of document.querySelectorAll("[data-agent]")) {
    button.classList.toggle("active", button.dataset.agent === activeAgent);
    button.setAttribute("aria-pressed", String(button.dataset.agent === activeAgent));
  }
  if (!data.tabs.some(([id]) => id === activeCategory)) activeCategory = "mcp";
  paintTabs();
  paintList();
}
function switchAgent(key) {
  if (usageSummary && activeAgent !== key) {
    setInspector(false);
    activeCategory = "runtime";
    document.querySelector("#updated-at").textContent = "11:59:47";
  }
  activeAgent = key; query = ""; search.value = ""; paintAgent();
}
agentSelect?.addEventListener("change", () => switchAgent(agentSelect.value));
document.querySelectorAll("[data-agent]").forEach(button => button.addEventListener("click", () => switchAgent(button.dataset.agent)));
document.querySelector(".category-nav").addEventListener("click", event => {
  const button = event.target.closest("[data-category]");
  if (!button) return;
  activeCategory = button.dataset.category; query = ""; search.value = ""; paintTabs(); paintList();
  document.querySelector(`[data-category="${activeCategory}"]`).focus();
});
search.addEventListener("input", () => { query = search.value; paintList(); });
document.querySelector("#theme").addEventListener("change", event => {
  ide.classList.toggle("light", event.target.value === "light");
  document.querySelectorAll("[data-icon]").forEach(el => el.innerHTML = icon(el.dataset.icon));
  document.querySelectorAll("[data-brand]").forEach(el => el.innerHTML = icon(el.dataset.brand, "brand"));
  paintAgent();
});
const openButton = document.querySelector("#open-inspector");
function setInspector(open) {
  if (open && usageSummary) {
    activeCategory = "runtime";
    query = "";
    search.value = "";
    paintTabs();
    paintList();
  }
  inspector.hidden = !open;
  openButton.setAttribute("aria-expanded", String(open));
  openButton.classList.toggle("selected", open);
  if (open) document.querySelector("#close-inspector").focus();
  else openButton.focus();
}
openButton.addEventListener("click", () => setInspector(inspector.hidden));
document.querySelector("#close-inspector").addEventListener("click", () => setInspector(false));
document.addEventListener("keydown", event => {
  if (event.key === "Escape" && !document.querySelector("#source-dialog").open) setInspector(false);
});
document.querySelector("#refresh").addEventListener("click", () => {
  document.querySelector("#updated-at").textContent = new Date().toLocaleTimeString("zh-CN", {hour12:false});
  document.querySelector("#announcer").textContent = "示例查询时间已更新，未连接真实 CLI，原有状态保持不变。";
});
document.querySelector("#show-sources").addEventListener("click", () => document.querySelector("#source-dialog").showModal());
document.querySelector("#close-sources").addEventListener("click", () => document.querySelector("#source-dialog").close());
document.querySelectorAll("[data-icon]").forEach(el => el.innerHTML = icon(el.dataset.icon));
document.querySelectorAll("[data-brand]").forEach(el => el.innerHTML = icon(el.dataset.brand, "brand"));
paintAgent();

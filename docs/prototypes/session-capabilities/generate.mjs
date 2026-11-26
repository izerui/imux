import {readFileSync, writeFileSync, mkdirSync, existsSync} from "node:fs";
import {dirname, resolve, extname} from "node:path";
import {fileURLToPath} from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "../../..");
const out = resolve(here, "design-demos");
mkdirSync(out, {recursive:true});
const css = readFileSync(resolve(here, "prototype.css"), "utf8");
const js = readFileSync(resolve(here, "prototype.js"), "utf8");
const assetMap = {
  codex:"src/main/resources/icons/codex@2x.png",
  claude:"src/main/resources/icons/claude@2x.png",
  pi:"src/main/resources/icons/pi@2x.png",
  pair:"src/main/resources/icons/pair.svg",
  trail:"src/main/resources/icons/trail.svg",
  talk:"src/main/resources/icons/talk.svg",
  phone:"src/main/resources/icons/phone.svg",
  info:".reference/icon-gallery/assets/expui/status/info.svg",
  refresh:".reference/icon-gallery/assets/expui/general/refresh.svg",
  close:".reference/icon-gallery/assets/expui/general/close.svg",
  search:".reference/icon-gallery/assets/expui/general/search.svg",
  settings:".reference/icon-gallery/assets/expui/general/settings.svg",
  list:".reference/icon-gallery/assets/expui/general/listFiles.svg",
  folder:".reference/icon-gallery/assets/expui/nodes/folder.svg",
};
function dataURL(path) {
  return `data:image/${extname(path) === ".svg" ? "svg+xml" : "png"};base64,${readFileSync(path).toString("base64")}`;
}
const assets = Object.fromEntries(Object.entries(assetMap).map(([key, value]) => {
  const light = resolve(root, value);
  const dark = light.replace(/(\.\w+)$/, "_dark$1");
  return [key, {light:dataURL(light), dark:dataURL(existsSync(dark) ? dark : light)}];
}));
const directions = [
  {key:"a",label:"A 快速弹窗",name:"快速弹窗",summary:"临时查看，保留终端工作现场。",theme:"dark",reason:"从工具栏直接展开，适合快速确认接入情况。"},
  {key:"b",label:"B 常驻侧栏",name:"常驻侧栏",summary:"与终端并排，适合边工作边查看。",theme:"dark",reason:"信息归属于当前会话，切换智能体时一起更新。"},
  {key:"c",label:"C 分类窗口",name:"分类详情窗口",summary:"集中查看，适合较长的资源清单。",theme:"light",reason:"左侧分类、右侧内容，沿用 IDE 详情窗口习惯。"},
];
function inspectorPanel(currentSessionOnly) {
const selector = currentSessionOnly ? "" : `<label class="sr-only" for="agent-select">当前智能体</label><select id="agent-select" class="agent-switch"><option value="codex">Codex</option><option value="claude">Claude Code</option><option value="pi">Pi</option></select>`;
return `<aside class="inspector" aria-label="会话信息与能力" id="inspector">
  <div class="panel-head"><h2>会话信息与能力</h2><div class="panel-actions"><button id="refresh" class="icon-button" aria-label="刷新示例数据" title="刷新示例数据" data-icon="refresh"></button><button id="close-inspector" class="icon-button" aria-label="关闭详情" title="关闭详情" data-icon="close"></button></div></div>
  <div class="identity"><span class="identity-icon"></span><div><div class="name"></div><div class="meta"></div></div>${selector}</div>
  ${currentSessionOnly ? '<div class="identity meta" id="session-usage"></div>' : ''}
  <div class="panel-content"><nav class="category-nav" aria-label="能力分类"></nav><div class="data-main"><div class="filterbar"><label class="search"><span data-icon="search"></span><span class="sr-only">搜索名称或来源</span><input id="resource-search" type="search" placeholder="搜索名称或来源" autocomplete="off"></label></div><div class="list-heading"></div><div class="resource-list"></div><p class="notice"></p></div></div>
  <div class="panel-foot"><span class="evidence"><span data-icon="info"></span><span class="evidence-text"></span></span><span>更新于 <time id="updated-at">11:59:47</time></span></div>
</aside>`;
}
const terminal = `<div class="terminal">
  <div class="terminal-line blue">› 看看当前会话可以使用哪些能力。</div>
  <div class="terminal-line">我会核对启动配置和会话接口。</div>
  <div class="terminal-line faint">Read  TerminalLaunchContributions.kt</div>
  <div class="terminal-line faint indent">会话级 MCP 配置与启动扩展</div>
  <div class="terminal-line faint">Read  PiSessionReport.kt</div>
  <div class="terminal-line faint indent">会话身份与事件上报</div>
  <div class="terminal-line green">已完成接口核对。</div>
  <div class="terminal-line">配置、查询结果和会话上报会分别标注。</div>
  <div class="terminal-prompt"><span class="blue">›</span><span class="cursor"></span></div>
  <div class="terminal-footer"><span>~/github/imux</span><span>等待输入</span></div>
</div>`;
for (const [index, dir] of directions.entries()) {
  const panel = inspectorPanel(dir.key === "a");
  const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${dir.label} · 会话信息与能力 · imux</title><style>${css}</style></head><body>
<header class="review-header"><div class="review-title"><strong>imux</strong><span class="divider"></span><div><h1>会话信息与能力</h1><small>交互原型 · 示例数据</small></div></div><nav class="directions" aria-label="设计方向">${directions.map((item,i) => `<a href="dir-${i+1}.html"${i===index?' class="current" aria-current="page"':""}>${item.label}</a>`).join("")}</nav></header>
<div class="review-tools"><p class="caption">${dir.summary}</p><div class="theme-control"><label for="theme">IDE 主题</label><select id="theme"><option value="dark"${dir.theme==="dark"?" selected":""}>深色主题</option><option value="light"${dir.theme==="light"?" selected":""}>浅色主题</option></select></div></div>
<main class="ide layout-${dir.key}${dir.theme==="light"?" light":""}">
  <div class="window-title"><span class="traffic" aria-hidden="true"><i></i><i></i><i></i></span><span>imux</span><span class="branch">main <span> / </span> IntelliJ IDEA</span></div>
  <div class="workspace">
    <aside class="project-tree" aria-label="项目会话"><h2>AI 智能体</h2><div class="tree-heading">当前项目</div><button class="session-link active" data-agent="codex" aria-pressed="true"><span data-brand="codex"></span><span>会话能力面板</span></button><button class="session-link" data-agent="claude" aria-pressed="false"><span data-brand="claude"></span><span>手机桥接重连</span></button><button class="session-link" data-agent="pi" aria-pressed="false"><span data-brand="pi"></span><span>补充回归测试</span></button><div class="tree-heading">项目文件</div><div class="tree-file">src / main / kotlin</div><div class="tree-file">docs / prototypes</div><div class="tree-file">AGENTS.md</div></aside>
    <div class="editor"><div class="editor-tabs"><div class="editor-tab active"></div><div class="editor-tab"><span>TerminalHost.kt</span></div></div><div class="editor-area">${terminal}<div class="toolstrip"><span class="quiet-icon" data-icon="pair" title="结对编程"></span><span class="quiet-icon" data-icon="talk" title="先聊再做"></span><span class="quiet-icon" data-icon="trail" title="会话轨迹"></span><button id="open-inspector" class="icon-button selected" aria-label="会话信息与能力" aria-expanded="true" aria-controls="inspector" title="会话信息与能力" data-icon="info"></button></div>${dir.key==="c"?"":panel}</div></div>
    ${dir.key==="c"?panel:""}
  </div><div class="statusbar"><span>imux / <span class="session-label">会话能力面板</span></span><span>UTF-8 · LF · 4 spaces</span></div>
</main>
<footer class="review-footer"><span><strong>${dir.name}</strong>　${dir.reason}</span><span><a href="thumbs.html">查看六个候选</a>　<button id="show-sources" style="border:0;padding:0;background:none;text-decoration:underline;text-underline-offset:4px;min-height:24px">数据口径</button></span></footer>
<dialog id="source-dialog" class="source-dialog" aria-labelledby="source-title"><h2 id="source-title">数据口径</h2><p>当前是设计原型。列表内容用于演示状态差异，未连接真实 CLI。</p><dl class="legend"><dt>已配置／注入</dt><dd>存在配置或启动参数，未证明会话已连接。</dd><dt>已发现</dt><dd>技能查询存在该条目，未证明正文已加载。</dd><dt>会话上报</dt><dd>由会话内接口或事件提供，展示最近上报结果。</dd><dt>未确认</dt><dd>没有取得证据，刷新也不会自动变为成功。</dd></dl><button id="close-sources" type="button">知道了</button></dialog>
<div id="announcer" class="sr-only" role="status"></div>
<script>const ASSETS=${JSON.stringify(assets)};</script><script>${js}</script></body></html>`;
  writeFileSync(resolve(out,`dir-${index+1}.html`),html);
}

const miniRows = `<div class="mini-row"><span>idea</span><span>已配置</span></div><div class="mini-row"><span>imux_session</span><span>已注入</span></div><div class="mini-row"><span>pi-lens</span><span>已配置</span></div>`;
const minis = [
  `<div class="mini-terminal"><p>› 当前会话可以使用哪些能力？</p><p>已完成接口核对。</p></div><div class="mini-popup"><b>会话信息与能力</b><p>Codex · imux</p>${miniRows}</div>`,
  `<div class="mini-terminal"><p>› 当前会话可以使用哪些能力？</p><p>已完成接口核对。</p></div><div class="mini-side"><b>会话信息与能力</b><p>MCP　技能　运行</p>${miniRows}</div>`,
  `<div class="mini-modal"><b>会话信息与能力</b><div class="mini-modal-body"><nav>MCP<br><br>技能<br><br>运行信息</nav><div>${miniRows}</div></div></div>`,
  `<div class="mini-terminal"><p>› 当前会话可以使用哪些能力？</p><p>已完成接口核对。</p></div><div class="mini-bottom"><b>会话能力</b><p>MCP　技能　运行信息</p>${miniRows}</div>`,
  `<div class="mini-tree"><b>会话能力面板</b><p>⌄ MCP</p><p>　idea</p><p>　imux_session</p><p>⌄ 技能</p><p>　openai-docs</p></div><div class="mini-terminal right"><p>› 当前会话</p><p>已完成核对。</p></div>`,
  `<div class="mini-page"><b>会话资料 / Codex</b><p>MCP　技能　规则　运行信息</p><div class="mini-row"><span>名称</span><span>来源</span><span>状态</span></div>${miniRows}<p>更新于 11:59:47</p></div>`,
];
const miniTitles = ["A 快速弹窗","B 常驻侧栏","C 分类窗口","D 底部检查区","E 会话树展开","F 资料标签页"];
const miniDescriptions = ["即开即看，最少打断","与终端并排，持续查看","完整分类，集中排查","保留终端宽度","信息跟随会话节点","为较长清单提供完整空间"];
const thumbStyle = `*{box-sizing:border-box}body{margin:0;padding:30px;background:#f0f1f3;color:#25262b;font:14px/1.5 -apple-system,"PingFang SC",sans-serif}h1{font-size:23px;margin:0}header{display:flex;justify-content:space-between;align-items:center;margin-bottom:23px}header p{color:#626874;font-size:13px;margin:6px 0 0}header a{color:#245fcd}.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:24px 20px}article{min-width:0}h2{font-size:15px;margin:10px 0 3px;font-weight:600}article p{margin:0;color:#626874;font-size:13px}article>a{color:inherit;text-decoration:none}.mini{position:relative;height:250px;background:#1e1f22;border:1px solid #43454a;border-radius:6px;color:#dfe1e5;overflow:hidden;font-size:12px}.mini.light{background:#fff;color:#25262b;border-color:#dfe1e5}.bar{height:28px;background:#2b2d30;border-bottom:1px solid #43454a;padding:5px 10px;font-size:11px}.light .bar{background:#f7f8fa;border-color:#dfe1e5}.mini p{font-size:11px;color:#a4a8b1;margin:12px 0}.mini-terminal{padding:23px 15px;color:#8aafff}.mini-terminal.right{margin-left:155px}.mini-popup,.mini-side{position:absolute;background:#2b2d30;border:1px solid #43454a;padding:12px}.mini-popup{top:48px;right:12px;width:58%;border-radius:5px;box-shadow:0 8px 20px #0005}.mini-side{top:28px;right:0;width:56%;bottom:0}.mini-row{display:flex;justify-content:space-between;border-bottom:1px solid #43454a;padding:10px 0;gap:10px}.mini-row span:last-child{color:#8aafff}.mini-modal{position:absolute;inset:45px 22px 25px;background:#fff;border:1px solid #dfe1e5;box-shadow:0 8px 25px #0002;padding:12px;border-radius:5px}.mini-modal-body{display:grid;grid-template-columns:75px 1fr;margin-top:12px}.mini-modal nav{background:#f7f8fa;padding:10px 7px}.light .mini-row{border-color:#dfe1e5}.light .mini-row span:last-child{color:#245fcd}.mini-bottom{position:absolute;bottom:0;left:0;right:0;height:138px;background:#2b2d30;border-top:1px solid #43454a;padding:10px 15px}.mini-bottom .mini-row{padding:4px 0}.mini-bottom p{margin:5px 0}.mini-tree{position:absolute;top:28px;bottom:0;left:0;width:155px;background:#2b2d30;border-right:1px solid #43454a;padding:15px}.mini-tree p{margin:8px 0}.mini-page{padding:15px 18px}.mini-page p{color:#646b77}.foot{margin-top:25px;color:#646b77;font-size:13px}@media(max-width:900px){.grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:550px){body{padding:18px}.grid{grid-template-columns:1fr}header{display:block}header a{display:inline-block;margin-top:10px}}`;
writeFileSync(resolve(out,"thumbs.html"),`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>六个方向 · imux 会话信息与能力</title><style>${thumbStyle}</style></head><body><header><div><h1>imux · 会话信息与能力</h1><p>三个可交互方向与三个备选 · 示例数据</p></div><a href="dir-1.html">打开交互原型</a></header><main class="grid">${minis.map((content,i)=>`<article>${i<3?`<a href="dir-${i+1}.html">`:""}<div class="mini${i===2||i===5?" light":""}"><div class="bar">imux / 会话能力面板</div>${content}</div><h2>${miniTitles[i]}${i>=3?" · 备选":""}</h2><p>${miniDescriptions[i]}</p>${i<3?"</a>":""}</article>`).join("")}</main><footer class="foot">A、B、C 均可切换 Codex / Claude Code / Pi，并查看条目来源与状态。</footer></body></html>`);
console.log(`Generated 3 standalone prototypes and 6-direction board in ${out}`);

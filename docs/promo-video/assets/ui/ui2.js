// v2 新增的界面重建：文案取自 src/main/resources/messages/ImuxBundle_zh_CN.properties 与 docs/guide.md。
Object.assign(window.UI, (() => {
  const ic = (base, n) => `${base}materials/${n}`;
  const lights = '<i style="background:#ff5f57"></i><i style="background:#febc2e"></i><i style="background:#28c840"></i>';

  // 窗口标题栏 + 终端（完成提醒那一镜）
  function win(base = '', inner = '') {
    return `<div class="ui ui-win"><div class="tb">${lights}<span class="ttl" data-ttl>imux<b data-ttl-st>（2 个运行中）</b></span></div>${inner}</div>`;
  }

  function balloon(base = '') {
    return `<div class="ui ui-balloon"><div class="h"><img src="${ic(base, 'codex@2x_dark.png')}" width="30">Codex · 耗时 2 分 13 秒</div>
      <div class="b">重构 SessionRepository 的扫描逻辑</div><a data-open>打开会话</a></div>`;
  }

  function menu(items) {
    return `<div class="ui ui-menu">${items.map((it) => it === '-' ? '<div class="sep"></div>'
      : it.hd ? `<div class="hd">${it.hd}</div>`
      : `<div class="it" data-it="${it.id || ''}">${it.img ? `<img src="${it.img}">` : ''}<span>${it.t}</span>${it.r ? `<span class="r">${it.r}</span>` : ''}</div>`).join('')}</div>`;
  }

  // 设置对话框：page = general | mcp | lsp | robots
  const tree = (page) => {
    const n = (t, on, pad = 0) => `<div class="n ${on ? 'on' : ''}" style="padding-left:${14 + pad}px">${t}</div>`;
    return `<div class="tree"><div class="srch">⌕ 搜索</div>
      ${n('外观与行为')}${n('键盘映射')}${n('编辑器')}${n('插件')}${n('版本控制')}${n('构建、执行、部署')}${n('语言和框架')}
      ${n('▾ 工具')}${n('▾ Imux', page === 'general', 28)}${n('IDEA MCP', page === 'mcp', 66)}${n('LSP', page === 'lsp', 66)}${n('通讯机器人', page === 'robots', 66)}</div>`;
  };
  const cb = (on = true) => `<span class="cb ${on ? '' : 'off'}">✓</span>`;
  const pages = {
    general: () => `<div class="crumb">工具 › <b>Imux</b></div>
      <div class="row">界面语言：<span class="fld" style="width:260px">跟随 IDE ▾</span><span style="color:#868a91;font-size:21px">仅影响 Imux</span></div>
      <div class="grp">会话</div>
      <div class="row">打开会话：<span class="rb"></span>单击 <span class="rb on"></span>双击</div>
      <div class="row">${cb()}关闭会话前要求确认</div>
      <div class="grp">智能体</div>
      <div class="row">${cb()}<img src="materials/claude@2x.png" width="28">Claude Code　${cb()}<img src="materials/codex@2x_dark.png" width="28">Codex　${cb()}<img src="materials/pi@2x_dark.png" width="28">Pi</div>
      <div class="grp">结对编程</div>
      <div class="row">最大轮次：<span class="fld" style="width:90px" data-rounds>5</span></div>
      <div class="row">默认伙伴：<span class="fld" style="width:760px">自动（已启用列表里排在会话自身之后的下一个）▾</span></div>
      <div class="grp">Project 窗口</div>
      <div class="row">${cb()}在“新建”菜单中显示 AI 智能体</div>`,
    mcp: () => `<div class="crumb">工具 › Imux › <b>IDEA MCP</b></div>
      <div style="color:#9a9da3">这些设置只影响 Imux 启动的智能体会话，不修改 CLI 配置文件。</div>
      <div class="grp">连接</div>
      <div class="row"><span data-inject>${cb()}</span>将 Imux 会话连接到 IDEA MCP Server</div>
      <div class="cm">只给 Imux 启动的智能体添加 IDEA 工具，不修改 Claude、Codex 和 pi 的全局配置。</div>
      <div class="row">MCP Server 端口：<span class="fld" style="width:150px">64342</span><span class="btn" data-check>检测连接</span><span data-conn class="ok">● 已连接</span></div>
      <div class="grp">智能体引导</div>
      <div class="row">${cb()}引导智能体在语义工作中优先使用 IDEA MCP</div>
      <div class="row" style="align-items:flex-start;margin-top:8px">引导提示词：<div class="ta" style="width:860px;height:250px">| 你想要……                        | 使用这个 IDEA MCP 工具
|---------------------------------|---------------------------
| 重命名变量、方法、类或任何符号      | rename_refactoring
| 调试运行时 bug                    | xdebug_set_breakpoint → …
| 检查 IDE 级别问题                 | lint_files / get_file_problems
| 按项目代码风格格式化              | reformat_file
| 追踪调用层级                      | analyze_calls</div></div>`,
    lsp: () => `<div class="crumb">工具 › Imux › <b>LSP</b></div>
      <div style="color:#9a9da3">仅检查 CLI 的全局配置，不读取各项目自己的配置文件。</div>
      <div class="grp"><img src="materials/claude@2x.png" width="28">Claude Code <span class="ok" style="font-weight:500">已就绪（4）</span><span class="warn" style="font-weight:500">待补充（2）</span></div>
      ${[['Kotlin', '已就绪', ''], ['Java', '已就绪', ''], ['TypeScript', '服务器不在 PATH 中', '启用'], ['Python', '未启用插件', '启用'], ['Go', '已就绪', ''], ['Rust', '已就绪', '']]
        .map(([l, s, a]) => `<div class="lang"><span>${l}</span><span class="${a ? 'warn' : 'ok'}">${s}</span><span class="act">${a}</span></div>`).join('')}
      <div class="grp"><img src="materials/pi@2x_dark.png" width="28">Pi <span class="ok" style="font-weight:500">由 pi-lens 提供 · 36 种以上语言</span></div>
      <div class="grp"><img src="materials/codex@2x_dark.png" width="28">Codex</div>
      <div class="cm" style="padding-left:22px">Codex 自身没有 LSP。挂载 pi-lens 的 MCP 服务端后，即可获得与 pi 相同的覆盖。</div>`,
    robots: () => `<div class="crumb">工具 › Imux › <b>通讯机器人</b></div>
      <div class="row" style="padding-left:0;gap:24px;font-size:30px;color:#cfd1d6">＋　－　✎</div>
      <div class="tbl">
        <div class="tr h"><span>机器人</span><span>渠道</span><span>状态</span><span>当前连接的会话</span></div>
        <div class="tr on"><span>兰陵王</span><span>飞书</span><span class="ok">已连接会话</span><span>登录接口限流</span></div>
        <div class="tr"><span>西门吹雪</span><span>企业微信</span><span class="ok">在线 · 空闲</span><span style="color:#868a91">—</span></div>
        <div class="tr"><span>小钉</span><span>钉钉</span><span class="ok">在线 · 空闲</span><span style="color:#868a91">—</span></div>
        <div class="tr" style="border-bottom:0"><span>ClawBot</span><span>微信</span><span class="warn">待配对</span><span style="color:#868a91">—</span></div>
      </div>
      <div style="color:#868a91;margin-top:16px">每个机器人同时连接一个会话。</div>`,
  };
  function settings(page = 'general') {
    return `<div class="ui ui-set"><div class="tb">${lights}<span class="ttl">设置</span></div>
      <div class="bd">${tree(page)}<div class="pane">${pages[page]()}</div></div>
      <div class="ft"><span class="btn">取消</span><span class="btn pri">确定</span></div></div>`;
  }

  // 添加机器人（add-robot.png）
  function robot(ch = '企业微信') {
    const chs = ['飞书', '企业微信', '钉钉', '微信'];
    return `<div class="ui ui-robot"><div class="tb">${lights.replace('#febc2e', '#5c5e63')}<span class="ttl">添加机器人</span></div>
      <div class="side"><div class="lb">渠道</div>${chs.map((c) => `<div class="ch ${c === ch ? 'on' : ''}" data-ch="${c}">${c}</div>`).join('')}</div>
      <canvas class="qr" width="340" height="340"></canvas>
      <div class="info"><b data-scan>用${ch}扫码</b><div class="st">1. 打开手机<span data-ch2>${ch}</span>，扫一扫<br>2. 确认创建机器人<br>3. 回到这里继续配对</div>
        <div class="w" data-wait><i></i>等待扫码…</div></div>
      <div class="bt"><span class="on">取消</span><span style="color:#868a91">下一步</span></div></div>`;
  }
  function drawQR(canvas, seed) {
    const g = canvas.getContext('2d'), n = 25, c = 340 / (n + 4);
    let s = seed; const r = () => (s = (s * 16807) % 2147483647) / 2147483647;
    g.fillStyle = '#fff'; g.fillRect(0, 0, 340, 340); g.fillStyle = '#000';
    for (let y = 0; y < n; y++) for (let x = 0; x < n; x++) if (r() < .48) g.fillRect((x + 2) * c, (y + 2) * c, c + .5, c + .5);
    for (const [fx, fy] of [[0, 0], [n - 7, 0], [0, n - 7]]) {
      g.fillStyle = '#fff'; g.fillRect((fx + 1.5) * c, (fy + 1.5) * c, 8 * c, 8 * c);
      g.fillStyle = '#000'; g.fillRect((fx + 2) * c, (fy + 2) * c, 7 * c, 7 * c);
      g.fillStyle = '#fff'; g.fillRect((fx + 3) * c, (fy + 3) * c, 5 * c, 5 * c);
      g.fillStyle = '#000'; g.fillRect((fx + 4) * c, (fy + 4) * c, 3 * c, 3 * c);
    }
  }

  const cursor = () => `<svg class="cursor" viewBox="0 0 22 28"><path d="M2 2 L2 22 L7.5 17 L11 25.5 L14.5 24 L11 15.8 L18.5 15.8 Z" fill="#fff" stroke="#111" stroke-width="1.6" stroke-linejoin="round"/></svg>`;

  return { win, balloon, menu, settings, robot, drawQR, cursor };
})());

// imux 界面重建（文字与结构照 docs/images 截图）。每个函数返回 HTML 字符串；base 是到工程根目录的相对路径。
window.UI = (() => {
  const ic = (base, n) => `${base}materials/${n}`;
  const row = (r) => `<div class="row ${r.cls || ''}" data-row="${r.id || ''}">
      <span class="st">${r.spin ? '<span class="spin"></span>' : r.dot === false ? '<span class="nodot"></span>' : '<span class="dot"></span>'}</span>
      ${r.phone ? '<span class="phone-ic"></span>' : ''}<span class="n">${r.n}</span><span class="t">${r.t}</span></div>`;

  function panel(base = '', o = {}) {
    const claude = o.claude || [
      { id: 'c1', n: '登录接口限流', t: '2 分钟前', spin: true, phone: true },
      { id: 'c2', n: '首页改版', t: '18 分钟前', cls: 'unread' },
      { id: 'c3', n: '重构 SessionRepository 的扫描逻辑', t: '1 小时前', cls: 'sel' },
      { id: 'c4', n: '修复 Windows 下路径大小写问题', t: '3 小时前', dot: false },
      { id: 'c5', n: '升级 Gradle 插件到 2.x', t: '1 天前', dot: false },
    ];
    const codex = o.codex || [
      { id: 'x1', n: '审查限流实现的并发问题', t: '40 秒前', spin: true },
      { id: 'x2', n: '补充 BridgeRouter 的分段测试', t: '2 天前' },
      { id: 'x3', n: '整理发布脚本', t: '5 天前', dot: false },
    ];
    return `<div class="ui ui-panel">
      <div class="hd">AI 智能体<span class="acts"><span>＋</span><span>↻</span><span>✕</span><span>⋮</span></span></div>
      <div class="grp" data-grp="claude"><span class="car"></span><img src="${ic(base, 'claude@2x.png')}">Claude Code</div>
      ${claude.map(row).join('')}
      <div class="more">显示更多…</div>
      <div class="grp" data-grp="codex"><span class="car"></span><img src="${ic(base, 'codex@2x_dark.png')}">Codex</div>
      ${codex.map(row).join('')}
      <div class="grp" data-grp="pi"><span class="car closed"></span><img src="${ic(base, 'pi@2x_dark.png')}">Pi</div>
      <div style="height:${o.tail ?? 120}px"></div>
    </div>`;
  }

  // 主会话：给登录接口加限流，Codex 审查中
  function term(base = '', o = {}) {
    const lines = o.lines || [
      ['q', '❯ 给登录接口加上按 IP 的限流，每分钟 10 次'],
      ['w', '我先看一下登录接口和现有的过滤器。'],
      ['t', 'Read(src/main/kotlin/auth/LoginController.kt)'],
      ['s', '⎿ Read 186 lines'],
      ['t', 'Write(src/main/kotlin/auth/RateLimiter.kt)'],
      ['s', '⎿ Wrote 64 lines'],
      ['t', 'Update(src/main/kotlin/auth/LoginController.kt)'],
      ['s', '⎿ Updated with <span class="ok">9 additions</span> and <span class="warn">2 removals</span>'],
      ['t', 'Bash(./gradlew test --tests "*Login*")'],
      ['s', '⎿ <span class="ok">BUILD SUCCESSFUL in 12s · 14 tests passed</span>'],
      ['w', '已完成：登录接口按 IP 限流，每分钟 10 次，超出返回 429。'],
    ];
    const ln = ([k, s], i) => k === 'q' ? `<div class="ln q" data-ln="${i}">${s}</div>`
      : k === 's' ? `<div class="ln sub mono" data-ln="${i}">${s}</div>`
      : `<div class="ln ${k === 'w' ? 'w' : ''}" data-ln="${i}"><span class="b"></span><span class="${k === 't' ? 'tool mono' : ''}">${s}</span></div>`;
    const banner = o.banner === false ? '' : `<div class="banner" data-om-id="banner"><span class="spin"></span>${o.banner || 'Codex 正在审查第 1 轮 · 已完成 2 项 · 读取 RateLimiter.kt · 18 秒'}<a>取消本轮审查</a></div>`;
    return `<div class="ui ui-term" style="${o.w ? `width:${o.w}px` : ''}">
      <div class="tabs"><div class="tab on">登录接口限流</div><div class="tab">首页改版</div></div>
      ${banner}
      <div class="body" style="padding-top:${o.top ?? 130}px">${lines.map(ln).join('')}
        <div class="input">❯</div>
        <div class="foot mono"><span>⏵⏵ accept edits on · shift+tab to cycle</span><span>Opus · imux</span></div>
      </div>
      ${o.bar === false ? '' : iconbar(base, o.barState || 'review')}
    </div>`;
  }

  function iconbar(base, state) {
    const first = state === 'review' ? `<div class="ib review"><span class="spin"></span>审查中 18s</div>`
      : state === 'booked' ? `<div class="ib review"><img src="${ic(base, 'pair.svg')}">已预约</div>`
      : `<div class="ib ${state === 'pair' ? 'on' : ''}"><img src="${ic(base, 'pair.svg')}"></div>`;
    const phone = state === 'review' ? `<div class="ib phone"><img src="${ic(base, 'phone.svg')}">飞书 · 西门吹雪</div>` : `<div class="ib ${state === 'phone' ? 'on' : ''}"><img src="${ic(base, 'phone.svg')}"></div>`;
    return `<div class="ui-iconbar" style="right:56px;top:${state === 'review' || state === 'booked' ? 150 : 86}px">${first}
      <div class="ib ${state === 'talk' ? 'on' : ''}"><img src="${ic(base, 'talk.svg')}"></div>
      <div class="ib ${state === 'trail' ? 'on' : ''}"><img src="${ic(base, 'trail.svg')}"></div>${phone}</div>`;
  }

  function trail() {
    const nd = (tm, s, c = '', tag = '') => `<div class="nd ${c}"><span class="tm">${tm}</span><span class="m"></span><span>${s}</span>${tag}</div>`;
    return `<div class="ui ui-trail">
      <div class="hd">会话轨迹<span style="margin-left:auto;color:#9a9da3">⇥</span></div>
      <div class="stale">⚠ 会话有新进展，摘要已过期<a>更新轨迹</a></div>
      ${nd('10:12', '明确目标：登录接口按 IP 限流，每分钟 10 次')}
      ${nd('10:18', '新增 RateLimiter，接入登录过滤器')}
      ${nd('10:25', '需求变更：改为按账号 + IP 双维度限流', 'chg')}
      ${nd('10:31', '尝试用 Redis 共享计数', '', '<span class="tag">已回滚</span>')}
      ${nd('10:36', '引入 Redis 依赖与配置', 'gone')}
      ${nd('10:40', '本地没有 Redis，放弃该方案', 'gone')}
      ${nd('10:44', '并发测试发现计数竞态', 'err')}
      ${nd('10:52', '改用原子计数，并发测试通过')}
      ${nd('11:03', '顺带讨论 429 响应的提示文案', 'gone')}
      ${nd('11:10', '正在补充多实例部署的说明', 'now')}
      <div class="ft">共 10 个节点 · 主线 9 · 旁支 1</div>
    </div>`;
  }

  function phone() {
    return `<div class="ui ui-phone">
      <div class="hd">西门吹雪<span class="bot">BOT</span></div>
      <div class="me">首页改完了吗？截个图发我看看，顺便把性能报告也发过来</div>
      <div class="who">西门吹雪</div>
      <div class="card" style="padding:0;overflow:hidden;width:520px;background:#f3f4f6;border:2px solid #555">
        <div style="height:34px;background:#e3e4e8"></div>
        <div style="margin:20px;height:120px;border-radius:14px;background:linear-gradient(90deg,#3b7cf0,#64a0ff)"></div>
        <div style="display:flex;gap:14px;margin:0 20px 20px"><i style="flex:1;height:84px;background:#fff;border-radius:12px"></i><i style="flex:1;height:84px;background:#fff;border-radius:12px"></i><i style="flex:1;height:84px;background:#fff;border-radius:12px"></i></div>
      </div>
      <div class="card" style="display:flex;gap:20px;align-items:center;width:470px"><span style="background:#c0392b;border-radius:8px;padding:22px 10px 8px;font-weight:700;font-size:20px">PDF</span><span>lighthouse-report.pdf<br><small style="color:#9a9a9f">1.2 MB</small></span></div>
      <div class="card">首页改版已完成，截图和性能报告都发你了：<br>· 首屏改成大图 + 三栏卡片；<br>· LCP 从 3.1s 降到 1.4s；<br>· 移动端布局已核对。
        <div class="sig">website · Claude Code · 首页改版</div></div>
    </div>`;
  }

  function clarify() {
    return `<div class="ui ui-dlg">
      <div class="tb"><i style="background:#ff5f57"></i><i style="background:#5c5e63"></i><i style="background:#28c840"></i><span class="ttl">先聊再做 · 给登录接口加限流，防止撞库</span></div>
      <div class="goal">目标：给登录接口加限流，防止撞库</div>
      <div class="qtabs"><span style="color:#5fb865">✓</span><span>限流维度</span><span class="on">计数存储</span><span>我的回答</span></div>
      <div class="qb">项目里登录服务部署了 3 个实例，目前没有引入 Redis。<br><b>限流的计数放在哪里？</b>
        <div class="opt on"><span class="r"></span><span>1. 进程内存（推荐）<small>实现最简单；多实例时各算各的，总阈值会放大</small></span></div>
        <div class="opt"><span class="r"></span><span>2. Redis<small>多实例共享计数；需要新增依赖和部署</small></span></div>
        <div class="opt"><span class="r"></span><span>3. 数据库<small>不用新组件；每次登录多一次写入</small></span></div>
        <div class="btn">看一眼</div><div style="clear:both"></div>
      </div>
    </div>`;
  }

  return { panel, term, trail, phone, clarify, iconbar };
})();

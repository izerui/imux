/**
 * imux 会话上报器。
 *
 * pi 不对外暴露「此刻在跑哪个会话」：没有运行态文件、不持有会话文件句柄，
 * 又因为设了 process.title 而让 ps 读不到它的环境变量。于是改由 pi 自己说——
 * 每次会话开始或切换（/new、/resume、/fork、/clone）都报一次，
 * imux 据此把终端标签页迁到新会话上。
 *
 * 三条硬约束，改动时不要破坏：
 * 1. 不 await：阻塞 session_start 会让用户敲 /new 时卡顿
 * 2. 全程吞异常：本扩展的任何故障都不该影响用户的 pi 会话
 * 3. 短超时：IDE 已关闭或端口变了，不能把 pi 拖住
 *
 * 只做上报，不解析、不改动任何东西——会话 id 由 IDE 端从路径解析，那边能单测。
 */
export default function (pi) {
  // 整个函数体都要兜住，不能只兜回调内部。`pi.on(...)` 这次调用本身也会抛：
  // pi 若改了扩展 API（on 改名、签名变化、事件名不再受支持），TypeError 会直接
  // 抛给 pi 的扩展加载器，用户的会话可能因此起不来——而这个扩展只负责标签页跟随，
  // 它的任何故障都不该有这种代价。降级要求是「脚本缺失 / 内置服务未就绪 /
  // pi 版本变更导致 API 不兼容」三种都退回不上报但会话正常启动，这一层守的是第三种。
  try {
    const url = process.env.IMUX_REPORT_URL;
    const token = process.env.IMUX_TOKEN;
    const tabId = process.env.IMUX_TAB;

    // 用户自己在终端里跑 pi 时这个扩展根本不会被加载（-e 是 imux 启动时才加的）；
    // 这里再挡一道，缺任何一项就彻底不干活。
    if (!url || !token || !tabId) return;

    pi.on("session_start", async (_event, ctx) => {
      try {
        const sessionFile = ctx?.sessionManager?.getSessionFile?.();
        if (!sessionFile) return;
        fetch(url, {
          method: "POST",
          headers: { "content-type": "application/json", "x-imux-token": token },
          body: JSON.stringify({ tabId, sessionFile }),
          signal: AbortSignal.timeout(1000),
        }).catch(() => {});
      } catch {
        // 故意留空：上报失败只影响标签页跟随，绝不能影响会话本身
      }
    });
  } catch {
    // 同上：注册失败就彻底不上报，但 pi 会话必须照常启动
  }
}

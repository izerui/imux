import * as PiCodingAgent from "@earendil-works/pi-coding-agent";

const CURSOR_MARKER = "\x1b_pi:c\x07";
const BLINKING_BAR_CURSOR = "\x1b[5 q";
const FAKE_CURSOR_AFTER_MARKER = /\x1b_pi:c\x07\x1b\[7m(.*?)\x1b\[(?:0|27)m/g;
const FULL_REDRAW_CLEAR = "\x1b[2J\x1b[H\x1b[3J";
const SYNCHRONIZED_OUTPUT_END = "\x1b[?2026l";
const FULL_REDRAW_OUTPUT = Symbol.for("com.github.izerui.imux.pi-full-redraw-output");
const FULL_REDRAW_CURSOR = Symbol.for("com.github.izerui.imux.pi-full-redraw-cursor");

/**
 * IDEA 必须看到 pi 的硬件光标，macOS 输入法候选窗才会跟随；pi 默认又会在同一位置
 * 画一个反色假光标。中文双宽字符间移动后，两套 painter 还可能错开。
 *
 * 光标压在真实字符上时只移除反色样式并保留字符；光标在行尾时，pi 会人为追加一个
 * 反色空格作为假光标，必须连这个合成单元一起移除。marker 保留给 TUI 定位硬件光标，
 * 输入与已有自定义 editor 的其余行为不变。
 */
function stripFakeCursor(line, cursorAtLineEnd = false) {
  return line.replace(
    FAKE_CURSOR_AFTER_MARKER,
    (_match, cursorCell) => `${CURSOR_MARKER}${cursorAtLineEnd ? "" : cursorCell}`,
  );
}

function isCursorAtLineEnd(editor) {
  try {
    const cursor = editor.getCursor?.();
    const lines = editor.getLines?.();
    if (!cursor || !Array.isArray(lines) || typeof cursor.line !== "number" || typeof cursor.col !== "number") {
      return false;
    }
    const line = lines[cursor.line];
    return typeof line === "string" && cursor.col >= line.length;
  } catch {
    return false;
  }
}

function configureHardwareCursor(tui) {
  const terminal = tui?.terminal;
  if (!terminal || typeof terminal.write !== "function") return;

  terminal.write(BLINKING_BAR_CURSOR);
}

/**
 * pi regular-screen 的 full redraw 会先清屏、从左上角重画全部内容，再用相对行移动
 * 恢复硬件光标。IDEA 262 在 resize/reflow 时可能在这两个 write 之间发布 footer 的
 * 光标位置，而且相对移动使用的旧 viewport 偶尔已经失效。
 *
 * 这里不再猜测「列数是否变化」：直接识别 pi 实际发出的清屏帧，等
 * positionHardwareCursor 给出 marker 坐标后，把绝对 CUP 插进同一个 synchronized
 * output 帧再一次写出。宽度、高度、连续 resize 与其它 full redraw 因而走同一路径；
 * 普通 differential render 仍完整调用 pi 原实现。
 */
function installFullRedrawCursorRecovery(tui) {
  if (!tui || tui.mode !== "regular" || typeof tui.positionHardwareCursor !== "function") return;

  const terminal = tui.terminal;
  if (!terminal || typeof terminal.write !== "function") return;

  let output = terminal[FULL_REDRAW_OUTPUT];
  if (!output) {
    const rawWrite = terminal.write;
    output = {
      pendingFrame: undefined,
      rawWrite,
    };
    terminal[FULL_REDRAW_OUTPUT] = output;

    terminal.write = function (data) {
      if (typeof data === "string" && data.includes(FULL_REDRAW_CLEAR)) {
        if (output.pendingFrame !== undefined) {
          output.rawWrite.call(this, output.pendingFrame);
        }
        output.pendingFrame = data;
        // positionHardwareCursor 应在同步调用链中紧接着消费这帧。
        // 若 pi 版本变化导致调用顺序改变，microtask 在同步链结束后立即冲刷，
        // 不依赖任何超时猜测，也不会无限期挂起。
        const self = this;
        Promise.resolve().then(() => {
          if (output.pendingFrame !== undefined) {
            output.rawWrite.call(self, output.pendingFrame);
            output.pendingFrame = undefined;
          }
        });
        return;
      }

      // fullRender() 会同步调用 positionHardwareCursor()。若 pi 将来改变调用顺序，
      // 先原样冲刷帧，宁可退回 pi 行为，也不能吞掉终端输出。
      if (output.pendingFrame !== undefined) {
        output.rawWrite.call(this, output.pendingFrame);
        output.pendingFrame = undefined;
      }
      return output.rawWrite.call(this, data);
    };
  }

  if (tui[FULL_REDRAW_CURSOR]) return;

  const originalPositionHardwareCursor = tui.positionHardwareCursor;
  tui[FULL_REDRAW_CURSOR] = true;
  tui.positionHardwareCursor = function (cursorPosition, totalLines) {
    const frame = output.pendingFrame;
    if (frame === undefined) {
      return originalPositionHardwareCursor.call(this, cursorPosition, totalLines);
    }
    output.pendingFrame = undefined;

    const activeTerminal = this?.terminal;
    const rows = Number(activeTerminal?.rows);
    const columns = Number(activeTerminal?.columns);
    const row = Number(cursorPosition?.row);
    const col = Number(cursorPosition?.col);
    if (
      activeTerminal !== terminal
      || typeof output.rawWrite !== "function"
      || !Number.isFinite(rows)
      || rows <= 0
      || !Number.isFinite(totalLines)
      || totalLines <= 0
      || !Number.isFinite(row)
      || !Number.isFinite(col)
    ) {
      output.rawWrite.call(terminal, frame);
      return originalPositionHardwareCursor.call(this, cursorPosition, totalLines);
    }

    const targetRow = Math.max(0, Math.min(totalLines - 1, row));
    const viewportTop = Math.max(0, totalLines - rows);
    const screenRow = Math.max(0, Math.min(rows - 1, targetRow - viewportTop));
    const maxColumn = Number.isFinite(columns) && columns > 0 ? columns - 1 : Number.MAX_SAFE_INTEGER;
    const screenCol = Math.max(0, Math.min(maxColumn, col));
    const cursorSequence = `\x1b[${screenRow + 1};${screenCol + 1}H`;
    const syncEnd = frame.lastIndexOf(SYNCHRONIZED_OUTPUT_END);
    const positionedFrame = syncEnd >= 0
      ? `${frame.slice(0, syncEnd)}${cursorSequence}${frame.slice(syncEnd)}`
      : `${frame}${cursorSequence}`;

    output.rawWrite.call(terminal, positionedFrame);
    // 与 pi 的逻辑坐标保持一致，后续 differential render 才能继续计算相对移动。
    this.hardwareCursorRow = targetRow;
    if (this.getShowHardwareCursor?.()) {
      activeTerminal.showCursor?.();
    } else {
      activeTerminal.hideCursor?.();
    }
  };
}

function installHardwareCursorEditor(ctx) {
  if (ctx?.mode !== "tui") return;
  if (typeof ctx.ui?.getEditorComponent !== "function" || typeof ctx.ui?.setEditorComponent !== "function") return;

  const wrapperKey = Symbol.for("com.github.izerui.imux.pi-cursor-editor");
  const previousFactory = ctx.ui.getEditorComponent();
  if (previousFactory?.[wrapperKey]) return;

  const CustomEditor = PiCodingAgent.CustomEditor;
  if (!previousFactory && typeof CustomEditor !== "function") return;

  const factory = (tui, theme, keybindings) => {
    configureHardwareCursor(tui);
    // settings.showHardwareCursor=false 会覆盖环境变量；只在 imux 的 TUI 实例上重新启用，
    // 不改写用户配置文件，也不影响用户从普通终端启动的 pi。
    tui.setShowHardwareCursor?.(true);
    const editor = previousFactory
      ? previousFactory(tui, theme, keybindings)
      : new CustomEditor(tui, theme, keybindings);
    const render = editor.render.bind(editor);
    editor.render = (width) => {
      installFullRedrawCursorRecovery(tui);
      const cursorAtLineEnd = isCursorAtLineEnd(editor);
      return render(width).map((line) => stripFakeCursor(line, cursorAtLineEnd));
    };
    return editor;
  };
  factory[wrapperKey] = true;
  ctx.ui.setEditorComponent(factory);
}

/**
 * imux 会话上报与 IDEA 光标适配扩展。
 *
 * pi 不对外暴露「此刻在跑哪个会话」：没有运行态文件、不持有会话文件句柄，
 * 又因为设了 process.title 而让 ps 读不到它的环境变量。于是改由 pi 自己说——
 * 每次会话开始或切换（/new、/resume、/fork、/clone）都报一次，
 * imux 据此把终端标签页迁到新会话上。
 *
 * 四条硬约束，改动时不要破坏：
 * 1. 不 await：阻塞 session_start 会让用户敲 /new 时卡顿
 * 2. 全程吞异常：本扩展的任何故障都不该影响用户的 pi 会话
 * 3. 短超时：IDE 已关闭或端口变了，不能把 pi 拖住
 * 4. IDEA 下保留硬件光标定位，但去掉 pi 与之重叠的反色假光标
 *
 * 上报部分不解析、不改动会话内容；光标适配只包装 pi 官方 editor 扩展点。
 */
export default function (pi) {
  // 整个函数体都要兜住，不能只兜回调内部。`pi.on(...)` 这次调用本身也会抛：
  // pi 若改了扩展 API（on 改名、签名变化、事件名不再受支持），TypeError 会直接
  // 抛给 pi 的扩展加载器，用户的会话可能因此起不来——而这个扩展只负责标签页跟随，
  // 它的任何故障都不该有这种代价。降级要求是「脚本缺失 / 内置服务未就绪 /
  // pi 版本变更导致 API 不兼容」三种都退回不上报但会话正常启动，这一层守的是第三种。
  try {
    const stateKey = Symbol.for("com.github.izerui.imux.pi-reporter");
    const fromEnvironment = {
      url: process.env.IMUX_REPORT_URL,
      token: process.env.IMUX_TOKEN,
      tabId: process.env.IMUX_TAB,
    };

    // pi 的 bash 工具继承当前进程环境。凭据只在首次加载时读取，随后立即删除，
    // 避免用户执行的子进程拿到本机 HTTP 接口令牌。/new、/resume 会重载扩展，
    // 所以把凭据留在不会传给子进程的 globalThis 上供新实例恢复。
    if (!globalThis[stateKey] && fromEnvironment.url && fromEnvironment.token && fromEnvironment.tabId) {
      globalThis[stateKey] = Object.freeze(fromEnvironment);
    }
    delete process.env.IMUX_REPORT_URL;
    delete process.env.IMUX_TOKEN;
    delete process.env.IMUX_TAB;

    const credentials = globalThis[stateKey];

    const report = (type, ctx, stopReason, messageId) => {
      if (!credentials?.url || !credentials?.token || !credentials?.tabId) return;
      try {
        const sessionId = ctx?.sessionManager?.getSessionId?.();
        const cwd = ctx?.sessionManager?.getCwd?.();
        if (!sessionId || !cwd) return;
        fetch(credentials.url, {
          method: "POST",
          headers: { "content-type": "application/json", "x-imux-token": credentials.token },
          body: JSON.stringify({
            type,
            tabId: credentials.tabId,
            sessionId,
            cwd,
            ...(stopReason ? { stopReason } : {}),
            ...(messageId ? { messageId } : {}),
          }),
          signal: AbortSignal.timeout(1000),
        }).catch(() => {});
      } catch {
        // 故意留空：上报失败只影响标签页跟随，绝不能影响会话本身
      }
    };

    pi.on("session_start", async (_event, ctx) => {
      try {
        // 光标适配不依赖 HTTP 上报服务；endpoint 暂不可用时也不能退回双光标。
        installHardwareCursorEditor(ctx);
      } catch {
        // editor API 或渲染实现变化时退回 pi 默认 editor，不影响会话与上报
      }
      report("session_start", ctx);
    });

    // 没有凭据时只保留光标适配，不注册无意义的终态上报。
    if (!credentials?.url || !credentials?.token || !credentials?.tabId) return;

    // error / length 可能先触发 pi 的自动重试或自动压缩，不能在会话文件刚写下时
    // 就当作整轮结束。agent_settled 是 pi 明确提供给状态集成的最终信号：
    // 到这里已没有 retry、compaction retry 或排队的 follow-up。
    pi.on("agent_settled", async (_event, ctx) => {
      try {
        const branch = ctx?.sessionManager?.getBranch?.();
        if (!Array.isArray(branch)) return;
        const lastAssistant = branch.findLast?.(
          (entry) => entry?.type === "message" && entry?.message?.role === "assistant",
        ) ?? branch.toReversed().find(
          (entry) => entry?.type === "message" && entry?.message?.role === "assistant",
        );
        const stopReason = lastAssistant?.message?.stopReason;
        if (stopReason !== "error" && stopReason !== "length") return;
        report("agent_settled", ctx, stopReason, lastAssistant?.id);
      } catch {
        // 同上：终态补报失败不能影响 pi
      }
    });
  } catch {
    // 同上：注册失败就彻底不上报，但 pi 会话必须照常启动
  }
}

# TODO: 恢复标签首次显示内容不全

## 现象

Claude 恢复的窗口，内容显示有点不对，感觉没显示全，关闭再打开就好了。

## 已确认的调查结论（通过沙箱调试器实测）

### 后台恢复标签的终端组件尺寸为 0x0

恢复标签以 `selectAsCurrent=false` 打开（`TerminalHost.restoreTabs` L175），
调试器求值确认：

```
tab1(选中) = 1556x833   ✓
tab2(后台) = 0x0         ✗
tab3(后台) = 0x0         ✗
```

后台标签的终端组件**不在任何容器中**，尺寸一直是 0x0，直到用户首次切过去。

### SHOWING_CHANGED 在 doLayout 之前触发

DIAG 日志时序（选中标签 d6c54a89）：

```
[create]    07:55:22.025  terminal.size=0x0, gridSize=null
[firstShow] 07:55:22.043  terminal.size=0x0, gridSize=null   ← SHOWING_CHANGED
[addNotify] 07:55:23.593  layerSize=0x0, isShowing=true      ← 1.5秒后
[doLayout]  07:55:23.596  0x0 → 1556x833, gridSize=null
```

SHOWING_CHANGED 比 doLayout 早约 1.5 秒。在 doLayout 执行时 gridSize 仍为 null。

### 待验证

以下问题尚未通过完整验收路径确认：

1. `setBounds` 本身已经触发 `componentResized` → `sendResizeEvent`，
   额外的尺寸抖动（`setSize(w+1,h)` → `setSize(w,h)`）是否有实际效果
2. `initOnShow` 是否真的在零尺寸时就启动了进程（代码注释说它会等待真实网格尺寸）
3. 后台标签首次显示稳定后的 `gridSize`、active editor `visibleArea`、滚动偏移
   与关闭重开后的对比数据

## 修复方向（待验证）

在 `AgentTerminalFileEditor` 的 `doLayout` 中，当终端组件从 0x0 首次获得非零尺寸时，
通过 `invokeLater` 异步执行一次尺寸抖动：

```kotlin
// 在 doLayout 中
val prevW = terminal.width
val prevH = terminal.height
terminal.setBounds(0, 0, width, height)

if (!terminalSizeSettled && prevW == 0 && prevH == 0 && width > 0 && height > 0) {
    terminalSizeSettled = true
    ApplicationManager.getApplication().invokeLater {
        if (!disposed && !project.isDisposed && terminal.isShowing) {
            val w = terminal.width
            val h = terminal.height
            if (w > 0 && h > 0) {
                terminal.setSize(w + 1, h)
                terminal.setSize(w, h)
            }
        }
    }
}
```

## 验收路径

下次复现时需要走完整条路径：

1. 注入 3 个恢复标签到 workspace.xml
2. 用调试模式启动沙箱 `Run Plugin`
3. 打开项目，等恢复标签加载
4. **首次逐个切到两个后台标签**，采集稳定后的数据：
   - `terminal.component` 的 `width` × `height`
   - `terminalView.gridSize`（需在 EDT 线程求值）
   - active output editor 的 `visibleArea` 和 `scrollOffset`
5. **关闭再打开同一会话**，采集对照数据
6. 对比两组数据，确认差异点
7. 应用修复后重复步骤 1-6，确认差异消除

## 涉及文件

- `AgentTerminalFileEditor.kt` — `createEditorComponent` / `doLayout`
- `TerminalHost.kt` — `restoreTabs` / `open`（`selectAsCurrent=false`）
- `RestorableSessionTabs.kt` — 工作区持久化
- `ImuxStartupActivity.kt` — 启动恢复入口

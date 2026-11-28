# Windows 启动参数验证

GitHub Actions 的 `Windows tests` 包含三类任务：原有的数据目录与会话路径测试、`powershell-modes` 的三种传参模式回归，以及 `launch-transport` 的真实 Windows 进程测试。提交到 main、创建或更新 PR、在 Actions 页面手动运行都会触发。运行中可用 GitHub 的 Cancel workflow 取消，失败后可 Re-run failed jobs。

## 覆盖范围

启动测试在 Windows Server 2022 和 2025 的 GitHub 托管 runner 上，分别使用 Windows PowerShell 5.1 (`powershell.exe`) 和 runner 安装的 PowerShell 7 (`pwsh.exe`)。运行日志记录具体版本。这不是 Windows 10/11 桌面的 GUI 验收。

每个环境执行 18 个用例：Claude、Codex、Pi × 新建/恢复 × 原生 exe/npm ps1/npm cmd。测试调用项目实际的 `launchCommand`，用 JVM `ProcessBuilder` 启动真实 Shell，再从接收进程读取最终参数。npm 包装器由固定版本的 `cmd-shim@6.0.3` 生成；cmd 用例移除 ps1，确保真正经过 cmd。

`powershell-modes` 在 Windows Server 2022 和 2025 上分别执行 `PowerShellLaunchArgumentsTest`，显式设置 `IMUX_TEST_PWSH=pwsh.exe`。每个环境另有 18 个用例：Legacy、Standard、Windows 三种模式 × 三个 CLI × 新建/恢复。通过 PowerShell 脚本转发到 Node 接收进程，核对 JSON、TOML、引号、反斜杠、换行和变量占位符。该任务与原生 exe/npm 包装器矩阵互补，不替代真实 cmd.exe 的验证。

Windows PowerShell 5.1 与 PowerShell 7 的 Legacy 模式不能视为相同实现：5.1 在判断是否自动包裹参数时，也会计算反斜杠转义的双引号，导致含空格的 JSON/TOML 被拆成多个参数。启动层在 5.1 下使用外层引号内的成对双引号编码，PowerShell 6+ 保持原有编码。必须保留真实 `powershell.exe` 矩阵，不能只靠 PowerShell 7 的 Legacy 测试判断兼容。回归输入还覆盖独立双引号、引号前的反斜杠及含空格路径末尾的反斜杠。

断言内容：

- Claude 的 `--settings` hook JSON、MCP JSON 和系统引导完整抵达，环境变量占位符不被提前展开。
- Codex 的 MCP TOML 参数、系统引导、普通终端选项、新建/恢复参数完整抵达。
- Pi 的普通终端选项、会话 ID 和含空格及中文的扩展路径完整抵达。
- 初始提示中的双引号、单引号、中文及美元符号原样传递；只有 Claude 接收 `--settings`。
- Windows 标签 PID 文件确实写出。

不需要模型 API 密钥，不安装或登录三个真实 CLI。接收进程仅保存参数：原生 exe 使用 .NET Framework 的 `Main(string[] args)`，npm 入口使用 Node.js 的 `process.argv`。因此测试能定位 Shell/启动脚本传参问题，但不验证某个 Claude 版本是否接受 hook 配置、不验证真实 CLI 的业务行为，也不覆盖 JetBrains Terminal 的 PTY 启动层。不能把测试通过解释为 WebStorm 启动报错已修复。

## 查看失败证据

每个矩阵任务上传独立 artifact，包括 JUnit XML、HTML 测试报告和 `build/reports/windows-launch/` 下的证据：

- `command.json`：项目生成并交给 JVM 的命令参数。
- `expected.json`：预期抵达接收进程的参数。
- `received.json`：接收进程实际收到的参数。
- `process.log`：Shell 或接收进程的输出。

这些文件只使用固定测试输入和假令牌，不读取真实 CLI 凭据。进程尚未启动成功时，可能没有 `received.json`。

macOS/Linux 本地运行会明确跳过 Windows 进程测试；`PowerShellLaunchArgumentsTest` 在本机安装 PowerShell 7.3+ 后可运行，未在 PATH 时可通过 `IMUX_TEST_PWSH` 指定。两个 Windows 启动验证任务各自要求每个环境执行 18 项、零跳过且零失败，报告缺失、测试数不符或跳过都会使任务失败。

Gradle 的 `test` 任务禁用增量跳过和测试结果缓存，每次实际执行；编译和打包仍可使用缓存。这避免了“第一次缺少 pwsh 而跳过测试，后来在同一个 PATH 目录装好 pwsh，却继续复用跳过结果”的假绿。代价是重复调用 `test` 时会重新运行所选测试。

这次调整测试执行、CI 和测试资料，不改变界面入口、MCP 工具、手机消息、多语言或插件生命周期；README、用户指南、商店描述和宣传视频的功能说明不受影响。

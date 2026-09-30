> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# 开始使用桌面应用

> 安装 Claude 桌面应用，打开 Code 选项卡，并在您计算机上的项目文件夹中开始您的第一个 Claude Code 会话。

桌面应用为您提供具有图形界面的 Claude Code，因此您可以要求 Claude 处理计算机上文件夹中的代码，并查看其更改，而无需使用终端。本页面将指导您安装应用并在 **Code** 选项卡中开始您的第一个会话。Claude Code 需要 [Pro、Max、Team 或 Enterprise 订阅](https://claude.com/pricing?utm_source=claude_code\&utm_medium=docs\&utm_content=desktop_quickstart_pricing)。

<CardGroup cols={3}>
  <Card title="下载 macOS 版本" icon="apple" href="https://claude.ai/api/desktop/darwin/universal/dmg/latest/redirect?utm_source=claude_code&utm_medium=docs">
    适用于 Intel 和 Apple Silicon 的通用版本
  </Card>

  <Card title="下载 Windows 版本" icon="windows" href="https://claude.ai/api/desktop/win32/x64/setup/latest/redirect?utm_source=claude_code&utm_medium=docs">
    适用于 x64 处理器
  </Card>

  <Card title="获取 Claude for Linux（测试版）" icon="linux" href="/docs/zh-CN/desktop-linux">
    Ubuntu 和 Debian 的 apt 或 .deb
  </Card>
</CardGroup>

对于 Windows ARM64，请下载 [ARM64 安装程序](https://claude.ai/api/desktop/win32/arm64/setup/latest/redirect?utm_source=claude_code\&utm_medium=docs)。在 Linux 上，使用 apt 安装；请参阅 [Claude Desktop on Linux](/docs/zh-CN/desktop-linux)。

<Note>
  这些情况在其他页面中有介绍：

  * **已经设置完成**：请参阅[使用 Claude Code Desktop](/docs/zh-CN/desktop)了解 Code 选项卡可以执行的所有操作
  * **想在您的终端中使用 `claude`**：[单独安装 CLI](/docs/zh-CN/quickstart)
</Note>

桌面应用有三个选项卡：

* **Chat**：无文件访问权限的常规对话，类似于 claude.ai。
* **Cowork**：一个自主后台代理，在您进行其他工作时独立处理任务。
* **Code**：一个交互式编码助手，可直接访问您的本地文件。根据权限模式，您可以在 Claude 提出更改时批准每项更改，或在 Claude 进行更改后审查这些更改。

Chat 和 Cowork 在 [Claude 帮助中心](https://support.claude.com/)中有介绍；安装和部署桌面应用在 [Claude Desktop 支持文章](https://support.claude.com/en/collections/16163169-claude-desktop)中有介绍。本页面重点关注 **Code** 选项卡。

<h2 id="install">
  安装
</h2>

<Steps>
  <Step title="安装并登录">
    在 macOS 和 Windows 上，从上面的链接下载安装程序并运行它。在 Linux 上，请按照 [Claude Desktop on Linux](/docs/zh-CN/desktop-linux) 中的安装步骤进行操作。在 macOS 上从应用程序文件夹启动 Claude，在 Windows 上从开始菜单启动，或在 Linux 上从应用程序启动器启动，然后使用您的 Anthropic 账户登录。
  </Step>

  <Step title="打开 Code 选项卡">
    点击顶部中心的 **Code** 选项卡。如果点击 Code 提示您升级，您需要先[订阅付费计划](https://claude.com/pricing?utm_source=claude_code\&utm_medium=docs\&utm_content=desktop_quickstart_upgrade)。如果提示您在线登录，请完成登录并重启应用。如果您看到 403 错误，请参阅[身份验证故障排除](/docs/zh-CN/desktop#403-or-authentication-errors-in-the-code-tab)。
  </Step>
</Steps>

桌面应用包含 Claude Code，因此您无需安装 Node.js 或 CLI 即可使用 Code 选项卡。

<h2 id="start-your-first-session">
  开始您的第一个会话
</h2>

打开代码选项卡，选择一个项目，并告诉 Claude 要做什么。

<Steps>
  <Step title="选择环境和文件夹">
    选择**本地**以在您的机器上运行 Claude，直接使用您的文件。点击**选择文件夹**并选择您的项目目录。

    <Tip>
      从一个您熟悉的小项目开始。这是查看 Claude Code 能做什么的最快方式。
    </Tip>

    您也可以选择：

    * **云**：在云中运行会话，即使关闭应用也能继续。请参阅 [在云中使用 Claude Code](/docs/zh-CN/claude-code-on-the-web) 了解云会话的工作原理。
    * **SSH**：通过 SSH 连接到远程机器，例如您自己的服务器、云虚拟机或开发容器。桌面版在您第一次连接时会自动在远程机器上安装 Claude Code。
    * **WSL**（Windows）：在 [WSL 2 发行版](/docs/zh-CN/desktop-wsl) 内运行会话；Claude Code、工具和 git 在 Linux 端执行，使用本机路径。
  </Step>

  <Step title="选择模型">
    从发送按钮旁的下拉菜单中选择一个模型。请参阅 [模型](/docs/zh-CN/model-config#available-models) 以比较可用的模型。您可以稍后从同一下拉菜单更改模型。
  </Step>

  <Step title="告诉 Claude 要做什么">
    输入您想让 Claude 做的事情：

    * `查找 TODO 注释并修复它`
    * `为主函数添加测试`
    * `为此代码库创建一个 CLAUDE.md 文件，包含说明`

    [会话](/docs/zh-CN/desktop#work-in-parallel-with-sessions) 是与 Claude 关于您的代码的对话。每个会话跟踪自己的上下文和更改。
  </Step>

  <Step title="审查并接受更改">
    接下来发生的情况取决于发送按钮旁的选择器中显示的 [权限模式](/docs/zh-CN/desktop#choose-a-permission-mode)：

    * **自动或接受编辑**：Claude 应用其文件更改，并显示一个指示器（如 `+12 -1`），以便您可以在差异视图中审查它们
    * **手动**：Claude 提议每项更改并等待您的批准后再应用。在您接受之前，您的文件不会被修改，如果您拒绝更改，Claude 会询问您希望如何继续

    在手动模式下，您将看到：

    1. 一个 [差异视图](/docs/zh-CN/desktop#review-changes-with-diff-view)，显示每个文件中将发生的确切更改
    2. 接受/拒绝按钮以批准或拒绝每项更改
    3. Claude 处理您的请求时的实时更新
  </Step>
</Steps>

<h2 id="now-what">
  接下来呢？
</h2>

您已经进行了第一次编辑。有关 Desktop 可以执行的所有操作的完整参考，请参阅 [使用 Claude Code Desktop](/docs/zh-CN/desktop)。以下是一些可以尝试的操作。

**中断并调整方向。** 您可以随时重定向 Claude。点击停止按钮立即中断，或输入更正并按 **Enter** 发送，无需停止正在运行的操作。无论哪种方式，您都不必等待它完成或重新开始。

**为 Claude 提供更多上下文。** 在提示框中输入 `@filename` 以将特定文件拉入对话，使用附件按钮附加图像和 PDF，或直接将文件拖放到提示框中。Claude 拥有的上下文越多，结果就越好。请参阅 [添加文件和上下文](/docs/zh-CN/desktop#add-files-and-context-to-prompts)。

**使用 skills 处理可重复的任务。** 输入 `/` 或点击 **+** → **Slash commands** 以浏览 [内置命令](/docs/zh-CN/commands)、[自定义 skills](/docs/zh-CN/skills) 和插件 skills。Skills 是可重用的提示，您可以在需要时调用它们，例如代码审查清单或部署步骤。

**在提交前审查更改。** Claude 编辑文件后，会出现 `+12 -1` 指示符。点击它以打开 [diff 视图](/docs/zh-CN/desktop#review-changes-with-diff-view)，逐个文件审查修改，并对特定行进行评论。Claude 会读取您的评论并进行修订。点击 **Review code** 让 Claude 自己评估 diffs 并留下内联建议。

**调整您拥有的控制权。** 您的 [permission mode](/docs/zh-CN/desktop#choose-a-permission-mode) 设置了 Claude 在不请求批准的情况下可以执行的操作：

* **Auto**：分类器在后台审查操作，并阻止风险操作，而不是询问您。
* **Manual**：Claude 在编辑文件或运行命令前询问。
* **Accept edits**：Claude 自动接受文件编辑以加快迭代。
* **Plan**：Claude 提出一种方法而不编辑任何文件，这在大型重构前很有用。

**添加插件以获得更多功能。** 点击提示框旁边的 **+** 按钮并选择 **Plugins** 以浏览和安装 [plugins](/docs/zh-CN/desktop#install-plugins)，这些插件添加 skills、agents、MCP servers 等。

**整理您的工作区。** 将聊天、diff、终端、文件和浏览器窗格拖动到您想要的任何布局中。使用 **Ctrl+\`** 打开终端以在您的会话旁边运行命令，或点击文件路径以在文件窗格中打开它。请参阅 [整理您的工作区](/docs/zh-CN/desktop#arrange-your-workspace)。

**预览您的应用。** 当您在 desktop 中运行开发服务器时，您的应用会在浏览器窗格中打开，该窗格也可以 [打开外部网站](/docs/zh-CN/desktop#browse-external-sites)。Claude 可以查看正在运行的应用、测试端点、检查日志并对其看到的内容进行迭代。请参阅 [预览您的应用](/docs/zh-CN/desktop#preview-your-app)。

**跟踪您的拉取请求。** 打开 PR 后，Claude Code 会监控 CI 检查结果，并可以自动修复失败，或在所有检查通过后合并 PR。请参阅 [监控拉取请求状态](/docs/zh-CN/desktop#monitor-pull-request-status)。

**将 Claude 放在日程上。** 设置 [scheduled tasks](/docs/zh-CN/desktop-scheduled-tasks) 以定期自动运行 Claude：每天早上进行代码审查、每周进行依赖项审计，或从您连接的工具中提取信息的简报。

**准备好时扩展。** 从侧边栏打开 [parallel sessions](/docs/zh-CN/desktop#work-in-parallel-with-sessions) 以同时处理多个任务，可选择每个任务都在其自己的 Git worktree 中，并打开 [tasks pane](/docs/zh-CN/desktop#watch-background-tasks) 以观看会话正在运行的子代理和后台命令。打开 [side chat](/docs/zh-CN/desktop#ask-a-side-question-without-derailing-the-session) 以提出问题而不偏离主线程。将 [long-running work 发送到云](/docs/zh-CN/desktop#run-long-running-tasks-in-the-cloud) 以便即使关闭应用也能继续，或 [在 web 或 IDE 中继续会话](/docs/zh-CN/desktop#continue-in-another-surface)（如果任务花费的时间比预期长）。[连接外部工具](/docs/zh-CN/desktop#extend-claude-code)（如 GitHub、Slack 和 Linear）以整合您的工作流。

<h2 id="what’s-next">
  接下来
</h2>

* [使用 Claude Code Desktop](/docs/zh-CN/desktop)：权限模式、并行会话、差异视图、连接器和企业配置
* [从 CLI 迁移过来？](/docs/zh-CN/desktop#coming-from-the-cli)：在同一项目上运行 Desktop 和 CLI，并比较功能、标志等效项以及 Desktop 中不可用的功能
* [故障排除](/docs/zh-CN/desktop#troubleshooting)：常见错误和设置问题的解决方案
* [最佳实践](/docs/zh-CN/best-practices)：编写有效提示和充分利用 Claude Code 的提示
* [常见工作流](/docs/zh-CN/common-workflows)：调试、重构、测试等教程

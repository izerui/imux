> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# 在云端使用 Claude Code

> 从浏览器、手机、桌面应用或终端在云端运行 Claude Code 会话，使用 `--cloud` 和 `--teleport` 移动会话，以及自动修复拉取请求。

<Note>
  云会话在 Pro、Max 和 Team 计划上可用，以及拥有高级席位或 Chat + Claude Code 席位的 Enterprise 用户。
</Note>

云会话是在云基础设施上运行的 Claude Code 会话，而不是在你的机器上运行。默认情况下，它在 Anthropic 管理的基础设施上运行，或在路由到你的组织的[自托管环境](/docs/zh-CN/self-hosted-environments)时在那里运行。即使关闭笔记本电脑后，会话也会继续运行，你可以从任何设备检查或控制它。

你可以从以下任何界面启动云会话：

* **浏览器**：[claude.ai/code](https://claude.ai/code)，也称为网络上的 Claude Code
* **移动设备**：[Claude 应用](/docs/zh-CN/mobile)中的 **Code** 标签页
* **桌面应用**：当你[启动会话](/docs/zh-CN/desktop#run-long-running-tasks-in-the-cloud)时，选择 **Cloud** 而不是 **Local**
* **终端**：[`claude --cloud`](#from-terminal-to-cloud)
* **例程**：[计划和触发的运行](/docs/zh-CN/routines)每次都作为云会话运行

要让 Claude 为一项工作启动并跟踪许多云会话，请使用[项目](/docs/zh-CN/claude-projects)。在你的终端、IDE 或选择了 **Local** 的桌面应用中的会话在你自己的机器上运行。要从手机或浏览器控制这些本地会话之一，请使用[远程控制](/docs/zh-CN/remote-control)。

<Tip>
  初次使用云会话？从[入门](/docs/zh-CN/web-quickstart)开始，连接你的 GitHub 账户并提交你的第一个任务。
</Tip>

本页涵盖：

* [云环境](#cloud-environments)：会话运行的位置，以及在哪里配置
* [GitHub 身份验证选项](#github-authentication-options)：两种连接 GitHub 的方式
* [在终端和云之间移动任务](#move-tasks-between-terminal-and-cloud)，使用 `--cloud` 和 `--teleport`
* [处理会话](#work-with-sessions)：权限模式、审查、共享、归档、删除
* [自动修复拉取请求](#auto-fix-pull-requests)：自动响应 CI 失败和审查评论
* [安全和隔离](#security-and-isolation)：会话如何隔离
* [限制](#limitations)：速率限制和平台限制

<h2 id="cloud-environments">
  云环境
</h2>

每个云会话都在一个[云环境](/docs/zh-CN/cloud-environments)中运行，这是一个保存的配置，控制网络访问、环境变量和设置脚本。如果你还没有环境，入门会设置一个**默认**环境，具有[**受信任**网络访问](/docs/zh-CN/cloud-environments#access-levels)，要么为你创建它，要么要求你创建它。请参阅[默认环境](/docs/zh-CN/cloud-environments#the-default-environment)，了解在你的计划上会发生哪种情况，以及当你有多个环境时会话如何选择环境。

相同的环境适用于你启动云会话的任何地方：网络、终端、[Claude Tag](https://claude.com/docs/claude-tag/overview)、[routines](/docs/zh-CN/routines) 以及移动和 Desktop 应用。Claude Tag 频道会话仅使用组织级环境，要么是[共享环境](/docs/zh-CN/cloud-environments#organization-shared-environments)，要么是[自托管环境](/docs/zh-CN/self-hosted-environments)。

请参阅[配置云环境](/docs/zh-CN/cloud-environments)以更改环境允许的内容、设置变量或添加设置脚本，以及[已安装的工具](/docs/zh-CN/cloud-environments#installed-tools)以了解会话在没有任何配置的情况下包含的内容。

<h2 id="github-authentication-options">
  GitHub 身份验证选项
</h2>

云会话需要访问你的 GitHub 存储库来克隆代码和推送分支。你可以通过两种方式授予访问权限：

| 方法 | 如何连接 | 会话可以访问的存储库 | 最适合 |
| :- | :- | :- | :- |
| **GitHub App** | 在[网络快速入门](/docs/zh-CN/web-quickstart)期间授权 Claude GitHub App | 任何公开存储库，以及安装了 Claude GitHub App 的私有存储库 | 浏览器入门；想要[自动修复](#auto-fix-pull-requests)的团队 |
| **`/web-setup`** | 在终端中运行 `/web-setup` 以将本地 `gh` CLI 令牌发送到你的 Claude 账户 | 你的 `gh` 令牌可以访问的任何存储库，无论是否安装了 Claude GitHub App | 已经使用 `gh` 的个人开发者 |

在存储库上安装 Claude GitHub App 也会为其中的拉取请求启用[自动修复](#auto-fix-pull-requests)。

[项目](/docs/zh-CN/claude-projects)中的线程需要在每个克隆的存储库上安装 Claude GitHub App，无论你使用哪种连接方法。请参阅[设置 GitHub 访问权限](/docs/zh-CN/claude-projects#set-up-github-access)。

在 Anthropic 托管的环境中，你的 GitHub 凭证在 Anthropic 的服务器上保持加密状态，永远不会进入会话的虚拟机。来自虚拟机的 GitHub 操作通过 [GitHub 代理](/docs/zh-CN/cloud-environments#github-proxy)进行，它在服务器端附加凭证。

有关 `/schedule` 如何在创建 routine 之前检查存储库访问权限，请参阅[存储库和分支权限](/docs/zh-CN/routines#repositories-and-branch-permissions)。有关 `/web-setup` 演练（包括 `/web-setup` 存储的内容以及如何删除它），请参阅[从终端连接](/docs/zh-CN/web-quickstart#connect-from-your-terminal)。

快速网络设置是一个组织设置，允许成员使用 `/web-setup` 连接 GitHub，在浏览器入门期间跳过 Claude GitHub App 安装提示，并让浏览器入门为他们创建[**默认**环境](/docs/zh-CN/cloud-environments#the-default-environment)，而不是显示环境表单。在 Team 和 Enterprise 计划上，默认情况下它是关闭的，这会隐藏 `/web-setup`。[所有者](/docs/zh-CN/server-managed-settings#access-control)可以在 [**Admin settings > Claude Code**](https://claude.ai/admin-settings/claude-code) 处使用**快速网络设置**切换来打开它。

<Note>
  启用了[零数据保留](/docs/zh-CN/zero-data-retention)的组织无法使用 `/web-setup` 或其他云会话功能。
</Note>

<h2 id="move-tasks-between-terminal-and-cloud">
  在终端和云之间移动任务
</h2>

这些工作流需要 [Claude Code CLI](/docs/zh-CN/quickstart) 登录到同一个 claude.ai 账户。您可以从终端启动新的云会话，或将云会话拉入终端以继续本地工作。云会话即使在您关闭笔记本电脑后也会持续存在，您可以从任何地方（包括 Claude 移动应用）监控它们。

<Note>
  从 CLI 来看，会话切换是单向的：您可以使用 `--teleport` 将云会话拉入终端，但无法将现有的终端会话推送到云。带有任务描述的 `--cloud` 标志为您当前的存储库创建新的云会话；带有 `-p` 和会话 ID 或 claude.ai/code URL 时，它会改为 [将消息排队到该现有会话](/docs/zh-CN/claude-code-on-the-web#send-follow-ups-from-the-cli)。[Desktop 应用](/docs/zh-CN/desktop#continue-in-another-surface) 提供了一个"继续在"菜单，可以将本地会话发送到云。
</Note>

<h3 id="from-terminal-to-cloud">
  从终端到云
</h3>

使用 `--cloud` 标志从命令行启动云会话：

```bash theme={null}
claude --cloud "Fix the authentication bug in src/auth/login.ts"
```

这会在 claude.ai 上创建新的云会话。云 VM 会在您当前分支克隆您当前目录的 GitHub 远程，而不是您的本地检出，因此如果您有本地提交，请先推送。有关 Claude Code 上传本地存储库而不是克隆的情况，请参阅 [发送没有 GitHub 的本地存储库](#send-local-repositories-without-github)。

`--cloud` 一次只能与单个存储库配合使用。任务在云中运行，而您继续在本地工作。较旧的 `--remote` 拼写仍然可以作为 `--cloud` 的已弃用别名使用。

当云容器启动时，CLI 显示设置步骤的实时清单，例如克隆存储库和运行您的 [设置脚本](/docs/zh-CN/cloud-environments#setup-scripts)。它会将您在配置期间键入的消息排队，并在会话准备好后发送它们。

<Note>
  `--cloud` 创建云会话。`--remote-control` 无关：它让您能够从 claude.ai 或 Claude 应用监控和引导本地 CLI 会话。请参阅 [Remote Control](/docs/zh-CN/remote-control)。
</Note>

在 claude.ai 或 Claude 移动应用上打开会话以检查进度或直接交互。从那里您可以引导 Claude、提供反馈或像在任何其他对话中一样回答问题。

如果 Claude 提出问题且会话处于空闲状态，您仍然可以在返回时回答，直到 [环境过期](#environment-expired)，会话会从您的答案继续。

<h4 id="tips-for-cloud-tasks">
  云任务提示
</h4>

**在本地规划，在云中执行**：对于复杂任务，启动 Claude 处于规划模式以协作制定方法，然后将工作发送到云：

```bash theme={null}
claude --permission-mode plan
```

在规划模式下，Claude 读取文件、运行命令进行探索，并提出计划而不编辑源代码。一旦您满意，将计划保存到存储库、提交并推送，以便云 VM 可以克隆它。然后启动云会话以进行自主执行：

```bash theme={null}
claude --cloud "Execute the migration plan in docs/migration-plan.md"
```

**并行运行任务**：每个 `--cloud` 命令创建自己的云会话，独立运行。您可以启动多个任务，它们都会在单独的会话中同时运行：

```bash theme={null}
claude --cloud "Fix the flaky test in auth.spec.ts"
claude --cloud "Update the API documentation"
claude --cloud "Refactor the logger to use structured output"
```

当会话完成时，您可以从 claude.ai/code 创建 PR，或 [teleport](#from-cloud-to-terminal) 会话到您的终端以继续工作。

<h4 id="send-local-repositories-without-github">
  发送没有 GitHub 的本地存储库
</h4>

当您从没有 git 远程的存储库运行 `claude --cloud` 时，或从 Claude GitHub App 未安装的 github.com 存储库运行时，Claude Code 会捆绑您的本地存储库并直接上传到云会话。即使您使用 `/web-setup` 连接了 GitHub，这也适用。该捆绑包包括您在所有分支上的完整存储库历史记录，加上对跟踪文件的未提交更改。

在 macOS、Linux 和 WSL 上，Claude Code 将未提交的更改排除在上传之外，这些更改涉及名称类似于凭据或密钥的文件，并命名它排除的文件。这涵盖 `.env` 文件、Terraform `*.tfvars` 文件和密钥文件，例如 `id_rsa` 和 `*.pem`。会话以每个文件的已提交版本启动，或如果未提交任何内容，则不包含该文件。

要在 Claude Code 会克隆远程时上传捆绑包，请设置 `CCR_FORCE_BUNDLE=1`：

```bash theme={null}
CCR_FORCE_BUNDLE=1 claude --cloud "Run the test suite and fix any failures"
```

捆绑的存储库必须满足这些限制：

* 目录必须是具有至少一个提交的 git 存储库
* 捆绑的存储库必须小于 100 MB。较大的存储库会回退到仅捆绑当前分支，然后回退到工作树的单个压缩快照，如果快照仍然太大则失败
* 未跟踪的文件不包括在内；对您希望云会话看到的文件运行 `git add`
* 从捆绑创建的会话只有在您的 [GitHub 连接](#github-authentication-options) 对该存储库具有推送访问权限时，才能推送回 GitHub 远程

<h3 id="send-follow-ups-from-the-cli">
  从 CLI 发送后续消息
</h3>

一旦云会话运行，无论它在哪里执行，都可以从任何您使用 `claude auth login` 登录的机器上的 `claude` CLI 向它发送后续消息。CLI 使用您的 Anthropic 账户凭据进行身份验证，不发送任何本地会话状态，因此该命令不需要从启动会话的机器运行，并且在每个 shell 中都是相同的，包括 PowerShell。

该命令发布一条消息并退出：

```bash theme={null}
claude -p "your message" --cloud <session-id>
```

CLI 将消息排队到会话中并退出，无需等待回复。使用它来引导长时间运行的会话、在当前步骤仍在完成时排队下一步，或从 [CI 脚本](/docs/zh-CN/self-hosted-environments-testing#run-the-test-loop) 发送后续消息。您也可以在 stdin 上管道消息，而不是将其作为参数传递：`echo "your message" | claude -p --cloud <session-id>`。

对于 `<session-id>`，传递裸 ID，例如 `session_...` 或 `cse_...`，或会话的 `claude.ai/code/<id>` URL，带或不带方案或查询字符串。在 claude.ai/code 的会话列表中找到 ID。

<Note>
  `--cloud` 需要 Anthropic 账户。当 Claude Code 为 Amazon Bedrock、Google Cloud 的 Agent Platform 或其他第三方提供商配置时，它不可用。仅通过 `ANTHROPIC_BASE_URL` 配置的 [LLM 网关](/docs/zh-CN/llm-gateway) 不算作此检查的第三方提供商，但您仍需要使用 `claude auth login` 登录。您组织的 `allow_remote_sessions` 策略也必须启用。所有者可以在 claude.ai/admin-settings/claude-code 的 Claude Code 管理设置中打开它。
</Note>

<h4 id="output-and-errors">
  输出和错误
</h4>

成功时，该命令打印会话 ID 和查看会话的链接：

```
Sent to cloud session.
Session ID: session_01DiUkqY2kzbUbDmW1w96rfi
View: https://claude.ai/code/session_01DiUkqY2kzbUbDmW1w96rfi?from=cli&m=0
```

传递 `--output-format json` 以获得机器可读的结果：成功时为 `{ok, session_id, url}`，或发送失败时为 `{ok: false, session_id, error}`，例如当会话缺失或已存档时。配置错误（例如不支持的提供商或禁用的组织策略）打印到 stderr，不带 JSON。`--output-format stream-json` 不支持 `--cloud <session-id>`。

CLI 在错误前加上 `Error: ` 前缀。失败的传递被包装为 `failed to send message to cloud session <id>: <reason>`。

| 消息 | 含义 |
| - | - |
| `Cloud sessions aren't available with <provider>. They run on Anthropic's infrastructure and require an Anthropic account.` | Claude Code 为第三方提供商配置。该消息使用您的配置使用的标签命名提供商，例如 `Amazon Bedrock` 或 `Google Vertex AI`。删除该提供商的配置，例如通过取消设置 `CLAUDE_CODE_USE_BEDROCK`，并使用 Anthropic 账户登录（`claude auth login`）。 |
| `Cloud sessions are disabled by your organization's policy. Contact your organization admin to enable them.` | `allow_remote_sessions` 组织策略已关闭。 |
| `Couldn't verify your organization's policy for cloud sessions. Check your network connection and try again.` | Claude Code 无法获取您的组织策略，因此它拒绝发送而不是假设云会话被允许。检查您的网络连接并重试。 |
| `Attaching to an existing cloud session is not enabled for your account.` | 您运行了 `--cloud <session-id>` 而没有 `-p`。使用 `claude -p "your message" --cloud <session-id>` 发送消息。 |
| `Session not found: <id>` | ID 或 URL 与您可以访问的会话不匹配。根据会话的 claude.ai/code URL 检查它。 |
| `cloud session <id> is archived and cannot accept new messages` | 会话已被存档。改为启动新会话。 |

<h3 id="from-cloud-to-terminal">
  从云到终端
</h3>

使用以下任何方法将云会话拉入您的终端：

* **使用 `--teleport`**：从命令行运行 `claude --teleport` 以获得交互式会话选择器，或 `claude --teleport <session-id>` 以直接恢复特定会话。如果您有未提交的更改，系统会提示您先隐藏它们。
* **使用 `/teleport`**：在现有 CLI 会话内，运行 `/teleport` 或 `/tp` 以打开相同的会话选择器，无需重启 Claude Code。
* **从 `/tasks`**：运行 `/tasks` 以查看您的后台会话，然后按 `t` 以 teleport 到其中一个。
* **从 claude.ai/code**：从会话菜单中选择 **Open in > Terminal** 以复制可以粘贴到终端的命令。
* **从云会话内部**：键入 `/teleport`，Claude Code 会回复该会话的确切 `claude --teleport <session-id>` 命令，准备从存储库的检出运行。需要会话环境中的 Claude Code v2.1.223 或更高版本。

当您 teleport 会话时，Claude 验证您在正确的存储库中，从云会话获取并检出分支，并将完整的对话历史记录加载到您的终端。终端获得会话的自己的副本：那里的新工作保持本地，不会出现在 claude.ai 上的云会话或 Claude 移动应用中。在 teleport 后继续从您的手机引导，在本地会话中启动 [`/remote-control`](/docs/zh-CN/remote-control)。

`--teleport` 与 `--resume` 不同。`--resume` 从此机器的本地历史记录重新打开对话，不列出云会话；`--teleport` 拉取云会话及其分支。

<h4 id="teleport-requirements">
  Teleport 要求
</h4>

Teleport 在恢复会话前检查这些要求。如果任何要求未满足，您会看到错误或被提示解决问题。

| 要求 | 详情 |
| - | - |
| 干净的 git 状态 | 您的工作目录必须没有未提交的更改。如果需要，Teleport 会提示您隐藏更改。 |
| 正确的存储库 | 您必须从同一存储库的检出运行 `--teleport`，而不是 fork。如果您从不同存储库的检出运行它，Claude Code 会显示一个错误，命名会话的存储库和您的检出的存储库。在 v2.1.219 之前，错误没有命名您的检出的存储库。如果 Claude Code 无法将您的远程解析为主机名，例如 SSH 主机别名如 `git@work:owner/repo.git`，它会要求您确认，并在远程的所有者和存储库名称与会话的存储库匹配时接受检出。 |
| 分支可用 | 来自云会话的分支必须已推送到远程。Teleport 会自动获取并检出它。 |
| 相同账户 | 您必须使用云会话中使用的相同 claude.ai 账户进行身份验证。 |

<h4 id="teleport-is-unavailable">
  `--teleport` 不可用
</h4>

Teleport 需要 claude.ai 订阅身份验证。如果您通过 API 密钥进行身份验证，请运行 `/login` 以改为使用您的 claude.ai 账户登录。如果错误命名您的提供商，云会话不可通过第三方提供商获得；请参阅 [错误表](#output-and-errors)。如果您已通过 claude.ai 登录且 `--teleport` 仍不可用，您的组织可能已禁用云会话。

<h2 id="work-with-sessions">
  处理会话
</h2>

会话出现在 claude.ai/code 的侧边栏中。从那里你可以审查更改、与队友共享、归档完成的工作或永久删除会话。

<h3 id="take-back-a-queued-message">
  取回已排队的消息
</h3>

如果你在 Claude 工作时发送消息，该消息会排队直到 Claude 读取它。要取回已排队的消息，请点击它上面的 ✕。文本返回到消息框，以便你可以编辑它或发送其他内容。

如果 Claude 已经读取了消息，它会保留在对话中。

<h3 id="manage-context">
  管理上下文
</h3>

云会话支持产生文本输出的[内置命令](/docs/zh-CN/commands)。仅在终端界面中运行的命令，如 `/plugin` 或 `/resume`，不可用。在云会话中打开选择器或面板的命令表现不同：

* **`/model`、`/effort`、`/color` 和 `/rename`**：将值作为参数传递，例如 `/model sonnet`，而不是打开终端选择器或滑块。参数形式需要会话环境中的 Claude Code v2.1.205 或更高版本，并遵循每个命令的[可用性说明](/docs/zh-CN/commands#all-commands)。
* **`/fast`**：当快速模式在[你的账户上可用](/docs/zh-CN/fast-mode#requirements)时，为会话切换[快速模式](/docs/zh-CN/fast-mode#use-fast-mode-in-cloud-sessions)。需要会话环境中的 Claude Code v2.1.271 或更高版本。
* **`/config`**：在你的浏览器中的 claude.ai/code 上，打开你的设置的 Claude Code 部分，而不是设置值，命令后的文本（包括 `key=value`）被忽略。要更改云会话的设置，请设置[环境变量](/docs/zh-CN/cloud-environments#set-environment-variables)，或在具有一个存储库的会话中，将密钥提交到该存储库的 `.claude/settings.json`。[云会话中的设置](/docs/zh-CN/settings#settings-in-cloud-sessions)列出了每个会话读取的内容。

对于上下文管理特别是：

| 命令 | 在云会话中工作 | 注释 |
| :- | :- | :- |
| `/compact` | 是 | 总结对话以释放上下文。接受可选的焦点指令，如 `/compact keep the test output` |
| `/context` | 是 | 显示当前在上下文窗口中的内容 |
| `/clear` | 否 | 从侧边栏启动新会话 |

自动压缩在上下文窗口接近容量时自动运行。云会话自己设置 [`CLAUDE_AUTOCOMPACT_PCT_OVERRIDE`](/docs/zh-CN/env-vars)，所以压缩在[自动压缩窗口](/docs/zh-CN/model-config#set-the-auto-compact-window)的中途触发，而不是当窗口填满时。该值覆盖你在[环境变量](/docs/zh-CN/cloud-environments#set-environment-variables)中添加的值，所以在那里添加变量不会改变压缩何时触发。

要改为更改自动压缩窗口，请在你的环境变量中设置 [`CLAUDE_CODE_AUTO_COMPACT_WINDOW`](/docs/zh-CN/env-vars)，或在变量未设置的会话中运行带有令牌计数的 [`/autocompact`](/docs/zh-CN/commands#all-commands)。

[Subagents](/docs/zh-CN/sub-agents) 的工作方式与本地相同。Claude 可以使用 Agent 工具生成它们，以将研究或并行工作卸载到单独的上下文窗口中，保持主对话更轻。在你的存储库的 `.claude/agents/` 中定义的 Subagents 会自动被拾取。

[Agent teams](/docs/zh-CN/agent-teams) 默认关闭，但可以通过将 `CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1` 添加到你的[环境变量](/docs/zh-CN/cloud-environments#set-environment-variables)来启用。

<h3 id="permission-modes-in-cloud-sessions">
  云会话中的权限模式
</h3>

你从[模式下拉菜单](/docs/zh-CN/permission-modes#switch-permission-modes)为云会话选择[权限模式](/docs/zh-CN/permission-modes)，既在你创建任务时，也在会话运行时。当你重新打开其 Anthropic 托管[环境已过期](#environment-expired)的会话，或向自托管运行程序在空闲时[释放的会话](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags)发送消息时，Claude Code 在它所在的权限模式中恢复会话。

<h3 id="review-changes">
  审查更改
</h3>

每个会话显示一个 diff 指示器，显示添加和删除的行数，如 `+42 -18`。选择它以打开 diff 视图，在特定行上留下内联评论，并使用你的下一条消息将它们发送给 Claude。

diff 视图默认将会话的更改与其基础分支进行比较。要与存储库中的任何其他分支进行比较，请选择 **Compare against** 并选择一个分支。

Claude Code 从原始 git blob 内容计算这些 diffs，包括 Claude 编辑时显示的每个文件 diffs，所以存储库中配置的 diff 驱动程序和 `textconv` 过滤器不适用。对于不是会话自己的检出之一的存储库中的文件，如在会话期间克隆到工作区内的文件，每个文件 diff 显示 Claude 的编辑本身，而不是 git 比较。

有关完整演练（包括 PR 创建），请参阅[审查和迭代](/docs/zh-CN/web-quickstart#review-and-iterate)。要让 Claude 自动监控 PR 以查找 CI 失败和审查评论，请参阅[自动修复拉取请求](#auto-fix-pull-requests)。

<h3 id="share-sessions">
  共享会话
</h3>

要共享会话，请根据下面的账户类型切换其可见性。之后，按原样共享会话链接。打开链接时，收件人会看到最新状态，但他们的视图不会实时更新。

<h4 id="share-from-an-enterprise-or-team-account">
  从 Enterprise 或 Team 账户共享
</h4>

对于 Enterprise 和 Team 账户，两个可见性选项是**私有**和**团队**。团队可见性使会话对你的 claude.ai 组织的其他成员可见。[Claude in Slack](/docs/zh-CN/slack) 会话会自动以团队可见性共享。

默认情况下启用存储库访问验证，基于连接到收件人账户的 GitHub 账户。你的账户显示名称对所有有访问权限的收件人可见。

<h4 id="share-from-a-max-or-pro-account">
  从 Max 或 Pro 账户共享
</h4>

对于 Max 和 Pro 账户，两个可见性选项是**私有**和**公开**。公开可见性使会话对任何登录到 claude.ai 的用户可见。

在共享之前检查你的会话是否包含敏感内容。会话可能包含来自私有 GitHub 存储库的代码和凭证。默认情况下不启用存储库访问验证。

要要求收件人拥有存储库访问权限，或从共享会话中隐藏你的名称，请转到 [**Settings > Claude Code > Sharing settings**](https://claude.ai/settings/claude-code)。

<h3 id="archive-sessions">
  归档会话
</h3>

你可以归档会话以保持你的会话列表有序。归档的会话从默认会话列表中隐藏，但可以通过筛选已归档会话来查看。

要归档会话，请在侧边栏中悬停在会话上并选择归档图标。

<h3 id="delete-sessions">
  删除会话
</h3>

删除会话会永久删除会话及其数据。此操作无法撤销。你可以通过两种方式删除会话：

* **从侧边栏**：筛选已归档会话，然后悬停在你想删除的会话上并选择删除图标
* **从会话菜单**：打开会话，选择会话标题旁的下拉菜单，然后选择**删除**

删除会话前会要求你确认。

<h2 id="auto-fix-pull-requests">
  自动修复拉取请求
</h2>

Claude 可以监视拉取请求并自动响应 CI 失败和审查评论。Claude 订阅 PR 上的 GitHub 活动，当检查失败或审查者留下评论时，Claude 会调查并推送修复（如果有明确的修复）。

<Note>
  自动修复需要在你的存储库上安装 Claude GitHub App。如果你还没有，请从 [GitHub App 页面](https://github.com/apps/claude) 安装它。
</Note>

根据 PR 来自何处以及你使用的设备，有几种方法可以打开自动修复：

* **在云会话中创建的 PR**：打开 claude.ai/code 处的会话，打开 CI 状态栏，并选择**自动修复**
* **从终端**：在 PR 的分支上运行 [`/autofix-pr`](/docs/zh-CN/commands)。Claude Code 使用 `gh` 检测打开的 PR，生成云会话，并一步启用自动修复
* **从移动应用**：告诉 Claude 自动修复 PR，例如"watch this PR and fix any CI failures or review comments"
* **任何现有 PR**：将 PR URL 粘贴到会话中并告诉 Claude 自动修复它

自动修复是按 PR 的切换开关。要停止监视，请在 claude.ai/code 处的会话中打开 CI 状态栏并清除**自动修复**切换，或告诉 Claude 停止监视 PR。

<h3 id="how-claude-responds-to-pr-activity">
  Claude 如何响应 PR 活动
</h3>

当自动修复处于活动状态时，Claude 接收 PR 的 GitHub 事件，包括新的审查评论和 CI 检查失败。对于每个事件，Claude 调查并决定如何进行：

* **明确的修复**：如果 Claude 对修复有信心且不与早期指令冲突，Claude 会进行更改、推送它，并在会话中解释所做的工作
* **模糊的请求**：如果审查者的评论可以以多种方式解释或涉及架构上重要的内容，Claude 会在采取行动前询问你
* **重复或无操作事件**：如果事件是重复的或不需要更改，Claude 会在会话中记录它并继续

GitHub 不会在基础分支推进并创建合并冲突时发出 webhook，因此自动修复无法自行对冲突做出反应。要解决冲突，请打开会话并要求 Claude 进行变基。

Claude 可能会作为解决审查评论线程的一部分在 GitHub 上回复它们。这些回复使用你的 GitHub 账户发布，所以它们出现在你的用户名下，但每个回复都标记为来自 Claude Code，以便审查者知道它是由代理编写的，而不是由你直接编写的。

<Warning>
  如果你的存储库使用注释触发的自动化，例如 Atlantis、Terraform Cloud 或在 `issue_comment` 事件上运行的自定义 GitHub Actions，请注意 Claude 可以代表你回复，这可能会触发这些工作流。在启用自动修复之前审查你的存储库的自动化，并考虑为可能部署基础设施或运行特权操作的 PR 注释的存储库禁用自动修复。
</Warning>

<h2 id="security-and-isolation">
  安全和隔离
</h2>

每个云会话通过多个层与你的机器和其他会话分离：

* **隔离的虚拟机**：每个会话在隔离的、Anthropic 管理的 VM 中运行。你的组织路由到[自托管环境](/docs/zh-CN/self-hosted-environments)的会话改为在你自己的基础设施上运行，其中隔离是你的部署的责任
* <span id="default-allowed-domains" />**网络访问控制**：在 Anthropic 托管的环境中，网络访问默认受限，可以禁用。请参阅[网络访问](/docs/zh-CN/cloud-environments#network-access)了解访问级别、[默认允许的域](/docs/zh-CN/cloud-environments#default-allowed-domains)和不通过允许列表的流量。在自托管环境中，你在自己的网络边界处限制会话出口。当在禁用网络访问的情况下运行时，Claude Code 仍然可以与 Anthropic API 通信，这可能允许数据从 VM 中退出。
* **凭证保护**：在 Anthropic 托管的环境中，git 凭证和签名密钥保持在沙箱外，代理使用作用域凭证代表会话进行身份验证。在自托管环境中，你的部署提供 git 凭证；请参阅[配置 git](/docs/zh-CN/self-hosted-environments-deploy#configure-git)
* **API 凭证**：在 Pro 和 Max 计划的 Anthropic 托管环境中，你[添加到云环境](/docs/zh-CN/cloud-environments#add-api-credentials)的密钥保持在沙箱外，以相同的方式，在它们离开会话后附加到匹配的请求。自托管环境没有 API 凭证，Team 和 Enterprise 计划还没有
* **安全分析**：代码在会话的隔离环境内分析和修改，然后创建 PR

<h2 id="troubleshooting">
  故障排除
</h2>

对于出现在对话中的运行时 API 错误，如 `API Error: 500`、`529 Overloaded`、`429` 或 `Prompt is too long`，请参阅[错误参考](/docs/zh-CN/errors)。这些错误及其修复与 CLI 和 Desktop 应用共享。下面的部分涵盖特定于云会话的问题。

<h3 id="session-creation-failed">
  会话创建失败
</h3>

如果新会话无法启动，显示 `Session creation failed` 或在配置时停滞，Claude Code 无法为会话分配 VM。

* 检查 [status.claude.com](https://status.claude.com) 以了解云会话事件
* 一分钟后重试，因为容量是按需配置的
* 通过遵循[连接 GitHub 后没有存储库出现](/docs/zh-CN/web-quickstart#no-repositories-appear-after-connecting-github)来确认你的 GitHub 连接可以访问存储库

<h3 id="unable-to-get-organization-uuid">
  无法获取组织 UUID
</h3>

`claude --cloud` 和 `claude --teleport` 需要使用 claude.ai 账户登录。如果你使用 API 密钥进行身份验证，或你的存储账户详情已过期，这些命令会失败，显示 `Unable to get organization UUID` 或消息 API 密钥身份验证不足。使用 API 密钥身份验证或过期账户详情，运行不带会话 ID 的 `claude --teleport` 会在会话选择器中显示 `Error loading Claude Code sessions`，而不是任一消息，相同的修复适用。

运行 `/login` 以使用你的 claude.ai 账户登录，然后重试命令。如果错误命名你的提供商，请参阅[错误表](#output-and-errors)：云会话不通过第三方提供商可用。

<h3 id="remote-control-session-expired-or-access-denied">
  Remote Control 会话已过期或访问被拒绝
</h3>

`--teleport` 通过与云会话使用的相同 Remote Control 会话基础设施连接，所以身份验证和会话过期错误会显示 Remote Control 措辞。你可能会看到 `Remote Control session expired` 或 `Access denied`。连接令牌是短期的，并限定于你的账户。

* 在本地运行 `/login` 以刷新你的凭证，然后重新连接
* 确认你已登录到拥有会话的相同账户
* 如果你看到 `Remote Control may not be available for this organization`，所有者尚未为你的组织启用云会话

<h3 id="environment-expired">
  环境已过期
</h3>

云会话在不活动一段时间后停止，会话的 VM 被回收。会话在等待你批准 [MCP 连接器](/docs/zh-CN/cloud-environments#network-access)工具调用或登录到 MCP 服务器时计为不活动，它可以在该等待期间过期。

从 [claude.ai/code](https://claude.ai/code) 重新打开会话以配置新 VM，并恢复你的对话历史。在 VM 被回收时仍在运行的后台工作，如 subagents 和 shell 命令，不会被恢复。

<h2 id="limitations">
  限制
</h2>

在依赖云会话进行工作流之前，请考虑这些约束：

* **速率限制**：云会话与你账户内所有其他 Claude 和 Claude Code 使用共享速率限制。并行运行多个任务会按比例消耗更多速率限制。云 VM 没有单独的计算费用。
* **时间限制**：Claude 运行的命令和 SessionStart hooks 有你可以更改的默认超时，设置脚本仅在大约五分钟内完成时才被缓存。请参阅[时间限制](/docs/zh-CN/cloud-environments#time-limits)
* **存储库身份验证**：你只能在认证到相同账户时将云会话拉入你的终端
* **平台限制**：存储库克隆和拉取请求创建需要 GitHub。自托管[GitHub Enterprise Server](/docs/zh-CN/github-enterprise-server) 实例支持 Team 和 Enterprise 计划。你可以通过设置 `CCR_FORCE_BUNDLE=1` 将 GitLab、Bitbucket 或其他非 GitHub 存储库作为[本地捆绑](#send-local-repositories-without-github)发送到云会话，但会话无法将结果推送回该远程
* **组织 IP 允许列表**：云会话从 Anthropic 管理的基础设施而不是你的网络调用 Anthropic API，而[自托管环境](/docs/zh-CN/self-hosted-environments)中的会话从你自己的网络调用它。如果你的组织启用了 [IP 允许列表](https://support.claude.com/en/articles/13200993-restrict-access-to-claude-with-ip-allowlisting)，每个 Anthropic 托管的云会话都会失败，显示身份验证错误。这同样适用于[代码审查](/docs/zh-CN/code-review)和在 Anthropic 托管的环境中运行的[routines](/docs/zh-CN/routines)；路由到自托管环境的 routine 从你自己的网络调用 API。联系 [Anthropic 支持](https://support.claude.com/)以从你的组织的 IP 允许列表中豁免 Anthropic 托管的服务。

<h2 id="related-resources">
  相关资源
</h2>

* [云环境](/docs/zh-CN/cloud-environments)：为云会话配置网络访问、环境变量和设置脚本
* [Projects](/docs/zh-CN/claude-projects)：一个对话，Claude 在其中协调您的存储库上的并行云会话并报告结果
* [Ultrareview](/docs/zh-CN/ultrareview)：在云沙箱中运行深度多代理代码审查
* [Routines](/docs/zh-CN/routines)：按计划、通过 API 调用或响应 GitHub 事件自动化工作
* [Hooks 配置](/docs/zh-CN/hooks)：在会话生命周期事件处运行脚本
* [所有设置](/docs/zh-CN/settings-reference)：所有配置选项
* [安全](/docs/zh-CN/security)：隔离保证和数据处理
* [数据使用](/docs/zh-CN/data-usage)：Anthropic 从云会话保留的内容
* [Claude Tag](https://claude.com/docs/claude-tag/overview)：在 Slack 中由组织管理的 @Claude，运行在相同的云基础设施上

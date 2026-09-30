> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# 在云中开始使用 Claude Code

> 从浏览器或手机在云中运行 Claude Code。连接 GitHub 仓库、提交任务，并在无需本地设置的情况下审查 PR。

<Note>
  云会话可在 Pro、Max 和 Team 计划上使用，以及拥有高级席位或 Chat + Claude Code 席位的企业用户。
</Note>

云会话在云基础设施上运行 Claude Code，而不是在您的机器上，默认由 Anthropic 管理。此快速入门从浏览器中的 [claude.ai/code](https://claude.ai/code) 启动一个会话。您也可以从 Claude 移动应用、Desktop 应用或终端使用 `claude --cloud` 启动一个会话。

您需要一个 GitHub 仓库来[开始使用](#connect-github)。Claude 将其克隆到隔离的虚拟机中，进行更改，并为您推送一个分支以供审查。会话在设备间持久化，因此您在笔记本电脑上开始的任务稍后可以从手机上审查。

云会话适用于：

* **并行任务**：同时运行多个独立任务，每个任务在自己的会话和分支中，无需管理多个 worktrees
* **您本地没有的仓库**：Claude 在每个会话中新鲜克隆仓库，因此您无需检出它
* **不需要频繁指导的任务**：提交一个定义明确的任务，做其他事情，当 Claude 完成时审查结果
* **代码问题和探索**：理解代码库或追踪功能如何实现，无需本地检出

对于需要您的本地配置、工具或环境的工作，在本地运行 Claude Code 或使用 [Remote Control](/docs/zh-CN/remote-control) 更合适。

<h2 id="how-sessions-run">
  会话如何运行
</h2>

下面的步骤描述了 Anthropic 托管的会话。在[自托管环境](/docs/zh-CN/self-hosted-environments)中，克隆及其之后的所有操作都在您组织自己的运行器上运行，其中网络边界、设置和推送行为由操作员配置。当您提交任务时：

1. **克隆和准备**：您的仓库被克隆到 Anthropic 管理的 VM，如果配置了，您的[设置脚本](/docs/zh-CN/cloud-environments#setup-scripts)会运行。
2. **配置网络**：互联网访问根据您的环境的[访问级别](/docs/zh-CN/cloud-environments#access-levels)设置。
3. **工作**：Claude 分析代码、进行更改、运行测试并检查其工作。您可以全程观看和指导，或者离开，当完成时返回。
4. **推送分支**：当 Claude 到达停止点时，它将其分支推送到 GitHub。您审查差异、留下内联注释、创建 PR 或发送另一条消息以继续。

推送分支时会话不会关闭。PR 创建和进一步编辑都在同一对话中进行。

<h2 id="compare-ways-to-run-claude-code">
  比较运行 Claude Code 的方式
</h2>

Claude Code 在任何地方的行为都相同。改变的是会话运行的位置以及您的本地配置是否可用：

| | Cloud session | Local session | Local session with [Remote Control](/docs/zh-CN/remote-control) |
| :- | :- | :- | :- |
| **代码运行在** | Cloud VM，默认由 Anthropic 管理 | 您的机器 | 您的机器 |
| **您从以下位置启动它** | claude.ai/code、Claude 移动应用、选择了 **Cloud** 的 Desktop 应用，或 `claude --cloud` | 您的终端、您的 IDE，或选择了 **Local** 的 Desktop 应用 | 您的终端、VS Code 扩展，或 Desktop 应用 |
| **您从以下位置聊天** | claude.ai、移动应用，或 Desktop 应用 | 您启动它的位置 | claude.ai 或移动应用，以及您启动它的位置 |
| **使用您的本地配置** | 否，仅限仓库 | 是 | 是 |
| **需要 GitHub** | 是，或通过 `--cloud` [捆绑本地仓库](/docs/zh-CN/claude-code-on-the-web#send-local-repositories-without-github) | 否 | 否 |
| **断开连接时继续运行** | 是 | 否 | 当会话在您的机器上保持打开时 |
| **[权限模式](/docs/zh-CN/permission-modes)** | 接受编辑、Plan、自动 | 终端中的所有模式；请参阅 [切换权限模式](/docs/zh-CN/permission-modes#switch-permission-modes) 了解 IDE 和 Desktop 应用 | 从 claude.ai 和移动应用中的手动、接受编辑或 Plan |
| **网络访问** | 每个环境可配置 | 您的机器网络 | 您的机器网络 |

请参阅[终端快速入门](/docs/zh-CN/quickstart)、[Desktop 应用](/docs/zh-CN/desktop)或 [Remote Control](/docs/zh-CN/remote-control) 文档来设置本地会话。

<h2 id="connect-github">
  连接 GitHub
</h2>

连接 GitHub 是一次性步骤。如果您已经使用 GitHub CLI，可以[从终端执行此操作](#connect-from-your-terminal)，而不是使用浏览器。

<Note>
  在 Team 和 Enterprise 计划上，**Sign in with GitHub** 步骤仅在您的 Claude 组织的[所有者](/docs/zh-CN/server-managed-settings#access-control)在[**Admin settings > Connectors**](https://claude.ai/admin-settings/connectors)处打开 GitHub 连接器后才有效。在此之前，该步骤显示"GitHub access is required for Claude Code on the web"而不是登录按钮。连接器打开后，重新加载 [claude.ai/code](https://claude.ai/code)并从第一步重新开始。第二个切换开关[Quick web setup](/docs/zh-CN/claude-code-on-the-web#github-authentication-options)位于[**Admin settings > Claude Code**](https://claude.ai/admin-settings/claude-code)，是可选的：打开它后，`/web-setup` 可以工作，入门流程会为成员创建环境。
</Note>

<Steps>
  <Step title="访问 claude.ai/code">
    转到 [claude.ai/code](https://claude.ai/code)并使用您的 claude.ai 账户登录。
  </Step>

  <Step title="使用 GitHub 登录">
    登录后，claude.ai/code 会提示您连接 GitHub。按照提示操作，claude.ai/code 会将您发送到 GitHub 的授权页面。批准授权请求，GitHub 会将您返回到 claude.ai/code。云会话可以与现有 GitHub 存储库配合使用。要启动新项目，请先[在 GitHub 上创建一个空存储库](https://github.com/new)。

    通过此连接，会话可以克隆任何公共存储库，但只有在 Claude GitHub App 安装在私有存储库上时，才能在私有存储库中工作。[安装 Claude GitHub App](https://github.com/apps/claude/installations/new) 到您想要使用其私有存储库的每个 GitHub 账户或组织。在 GitHub 组织上，组织所有者可能需要批准安装。安装应用还会启用[Auto-fix](/docs/zh-CN/claude-code-on-the-web#auto-fix-pull-requests)，这让 Claude 能够响应这些存储库中拉取请求的 CI 失败和审查评论。

    如果入门流程在此时提示您安装 Claude GitHub App，而您想稍后再做，请单击**Skip**。
  </Step>

  <Step title="设置您的默认环境">
    [云环境](/docs/zh-CN/cloud-environments)是保存的配置，控制会话期间 Claude 拥有的网络访问权限以及会话启动时运行的内容。连接 GitHub 后发生的情况取决于您的计划：

    * **Pro 和 Max**：入门流程为您创建一个名为**Default**的环境。
    * **Team 和 Enterprise**：入门流程显示**Create your first cloud environment**表单。保持预填充的名称和网络访问不变，然后单击**Create & finish**以创建**Default**环境。如果所有者已打开[Quick web setup](/docs/zh-CN/claude-code-on-the-web#github-authentication-options)，入门流程会为您创建**Default**。

    **Default** 使用[`Trusted` 网络访问](/docs/zh-CN/cloud-environments#access-levels)：会话可以访问[常见包注册表](/docs/zh-CN/cloud-environments#default-allowed-domains)和其他允许列表中的域，以及通过会话网络的其他任何内容都无法访问。有关无需任何配置即可使用的内容，请参阅[已安装的工具](/docs/zh-CN/cloud-environments#installed-tools)。

    对于第一个项目，**Default** 环境可以按原样使用。要更改其网络访问、添加环境变量或在会话启动前运行[设置脚本](/docs/zh-CN/cloud-environments#setup-scripts)，请[编辑它或创建其他环境](/docs/zh-CN/cloud-environments#configure-your-environment)。
  </Step>
</Steps>

<h3 id="connect-from-your-terminal">
  从终端连接
</h3>

如果您已经使用 GitHub CLI (`gh`)，可以从终端为云会话连接 GitHub。这需要[Claude Code CLI](/docs/zh-CN/quickstart)。在 Team 和 Enterprise 计划上，只有在所有者打开[Quick web setup](/docs/zh-CN/claude-code-on-the-web#github-authentication-options)后，`/web-setup` 才可用。

运行 `/web-setup` 时，Claude Code 读取 `gh auth token` 打印的令牌，要求您确认，并将令牌发送给 Anthropic。Anthropic 使用您的 claude.ai 账户加密存储它，您的云会话使用它进行 GitHub 访问，直到您[删除它](#remove-the-web-setup-token)。您自己启动的云会话随后可以访问该令牌可以访问的任何存储库，无需安装 Claude GitHub App。[项目](/docs/zh-CN/claude-projects#set-up-github-access)中的线程仍然需要该应用。

如果您已经在浏览器中连接了 GitHub，`/web-setup` 会警告您继续将替换您的云会话的该连接。

<Note>
  启用了[零数据保留](/docs/zh-CN/zero-data-retention)的组织无法使用 `/web-setup` 或其他云会话功能。如果未安装 GitHub CLI 或未进行身份验证，Claude Code 会打开浏览器入门流程。
</Note>

<Steps>
  <Step title="使用 GitHub CLI 进行身份验证">
    在您的 shell 中，如果您还没有进行身份验证，请对 GitHub CLI 进行身份验证：

    ```bash theme={null}
    gh auth login
    ```
  </Step>

  <Step title="登录到 Claude">
    在 Claude Code CLI 中，运行 `/login` 以使用您的 claude.ai 账户登录。如果您已经使用 claude.ai 账户登录，请跳过此步骤。使用 API 密钥进行身份验证不计数。要检查，请运行 `/status` 并确认**Login method**行显示 claude.ai 账户。
  </Step>

  <Step title="运行 /web-setup">
    在 Claude Code CLI 中，运行：

    ```text theme={null}
    /web-setup
    ```

    确认提示以将您的 `gh` 令牌发送到您的 Claude 账户。成功后，Claude Code 打印 `Connected as <your-github-username>` 并在您的浏览器中打开 [claude.ai/code](https://claude.ai/code)。如果您还没有云环境，`/web-setup` 会创建一个具有 Trusted 网络访问且没有设置脚本的环境。您可以[稍后编辑环境或添加变量](/docs/zh-CN/cloud-environments#configure-your-environment)。`/web-setup` 完成后，您可以使用 [`--cloud`](/docs/zh-CN/claude-code-on-the-web#from-terminal-to-cloud) 从终端启动云会话，或使用 [`/schedule`](/docs/zh-CN/routines) 设置定期任务。
  </Step>
</Steps>

<h4 id="remove-the-web-setup-token">
  删除 `/web-setup` 令牌
</h4>

要从您的 Claude 账户中删除令牌，请在 [claude.ai/customize/connectors](https://claude.ai/customize/connectors) 处断开 GitHub 连接。断开连接会删除您的云会话使用的 GitHub 凭据，无论它们来自浏览器还是 `/web-setup`，因此云会话会失去 GitHub 访问权限，直到您再次连接。您的本地 `gh` 保持登录状态，令牌在 GitHub 上保持有效。

要使令牌本身失效，请在 GitHub 上撤销它。如果您通过浏览器登录到 `gh`，令牌属于 GitHub 上[**Settings > Applications > Authorized OAuth Apps**](https://github.com/settings/applications)下的**GitHub CLI**条目，撤销该条目也会在您的机器上将 GitHub CLI 注销。云会话随后会失去 GitHub 访问权限，直到您再次运行 `gh auth login` 和 `/web-setup`。

<h2 id="start-a-task">
  开始任务
</h2>

连接了 GitHub 和创建了环境后，您已准备好提交任务。

<Steps>
  <Step title="选择仓库和分支">
    从 [claude.ai/code](https://claude.ai/code) 或 Claude 移动应用中的 Code 选项卡，单击输入框下方的仓库选择器，并为 Claude 选择要在其中工作的仓库。每个仓库都显示一个分支选择器。更改它以从功能分支而不是默认分支启动 Claude。您可以添加多个仓库以在一个会话中跨它们工作。
  </Step>

  <Step title="选择权限模式">
    输入旁边的模式下拉菜单显示会话将在其中运行的模式：

    * **Auto**：分类器审查 Claude 的操作，而不是询问您。当您的组织允许自动模式且所选模型支持它时出现
    * **Accept edits**：Claude 进行更改并推送分支而无需停止以获得批准
    * **Plan**：Claude 提出方法并等待您批准后再编辑文件

    云会话不提供 Manual 或 Bypass 权限。请参阅[权限模式的完整列表](/docs/zh-CN/permission-modes#available-modes)了解每种权限允许的操作。
  </Step>

  <Step title="描述任务并提交">
    输入您想要的内容的描述并按 Enter。要具体：

    * 命名文件或函数："Add a README with setup instructions" 或 "Fix the failing auth test in `tests/test_auth.py`" 比 "fix tests" 更好
    * 如果您有错误输出，请粘贴它
    * 描述预期行为，而不仅仅是症状

    Claude 克隆仓库，如果配置了则运行您的设置脚本，并开始工作。每个任务都有自己的会话和自己的分支，因此您无需等待一个完成就可以开始另一个。
  </Step>
</Steps>

<h2 id="pre-fill-sessions">
  预填充会话
</h2>

您可以通过向 [claude.ai/code](https://claude.ai/code) URL 添加查询参数来预填充新会话的提示、仓库和环境。使用此功能来构建集成，例如问题跟踪器中的按钮，该按钮使用问题描述作为提示打开 Claude Code。

| 参数 | 描述 |
| :- | :- |
| `prompt` | 要在输入框中预填充的提示文本。也接受别名 `q`。 |
| `prompt_url` | 要从中获取提示文本的 URL，用于太长而无法嵌入查询字符串的提示。URL 必须允许跨源请求。当也设置了 `prompt` 时被忽略。 |
| `repositories` | 要预选的 `owner/repo` 段的逗号分隔列表。也接受别名 `repo`。 |
| `environment` | [环境](#connect-github)的名称或 ID 以预选。 |

对每个值进行 URL 编码。下面的示例打开表单，其中已选择提示和仓库：

```text theme={null}
https://claude.ai/code?prompt=Fix%20the%20login%20bug&repositories=acme/webapp
```

<h2 id="review-and-iterate">
  审查和迭代
</h2>

当 Claude 完成时，审查更改，在特定行上留下反馈，并继续直到差异看起来正确。

<Steps>
  <Step title="打开差异视图">
    差异指示器显示整个会话中添加和删除的行，例如 `+42 -18`。选择它以打开差异视图，左侧是文件列表，右侧是更改。

    差异默认将会话的更改与其基础分支进行比较。要与不同的分支进行比较，选择**Compare against**并选择一个。
  </Step>

  <Step title="留下内联注释">
    选择差异中的任何行，输入您的反馈，然后按 Enter。注释排队直到您发送下一条消息，然后它们与其捆绑。Claude 看到"at `src/auth.ts:47`, don't catch the error here"与您的主要指令一起，因此您不必描述问题在哪里。
  </Step>

  <Step title="创建拉取请求">
    当差异看起来正确时，选择差异视图顶部的**Create PR**。您可以将其作为完整 PR、草稿打开，或跳转到 GitHub 的撰写页面，其中包含生成的标题和描述。
  </Step>

  <Step title="在 PR 后继续迭代">
    会话在创建 PR 后保持活跃。将 CI 失败输出或审查者注释粘贴到聊天中，并要求 Claude 解决它们。要让 Claude 自动监控 PR，请参阅[自动修复拉取请求](/docs/zh-CN/claude-code-on-the-web#auto-fix-pull-requests)。
  </Step>
</Steps>

<h2 id="troubleshoot-setup">
  故障排除设置
</h2>

<h3 id="no-repositories-appear-after-connecting-github">
  连接 GitHub 后没有仓库出现
</h3>

如果您在浏览器中连接了 GitHub，会话可以克隆任何公共仓库，但私有仓库仅在 Claude GitHub App 安装在拥有该仓库的账户或组织上，且安装的仓库访问权限包括该仓库时才会出现。[安装 Claude GitHub App](https://github.com/apps/claude/installations/new)，或要求组织所有者安装或批准它。

如果您使用 `/web-setup` 连接，会话可以访问您的 `gh` 令牌可以访问的每个仓库。在您的 shell 中运行 `gh repo view OWNER/REPO` 以检查您的 GitHub CLI 登录是否可以看到该仓库，如果您自连接以来已切换 `gh` 账户，请再次运行 `/web-setup`。

<h3 id="the-page-only-shows-a-github-login-button">
  页面仅显示 GitHub 登录按钮
</h3>

云会话需要连接的 GitHub 账户。通过上面的浏览器流程连接，或如果您使用 GitHub CLI，从您的终端运行 `/web-setup`。如果您根本不想连接 GitHub，请参阅 [Remote Control](/docs/zh-CN/remote-control) 以在您自己的机器上运行 Claude Code 并从浏览器或手机监控它。

<h3 id="claude-code-isn’t-available-on-your-account">
  "Claude Code isn't available on your account"
</h3>

您在所选组织中的席位不包括 Claude Code。如果您属于另一个组织，请点击该页面上的**切换组织**。否则，请要求组织的所有者为您分配一个[包括 Claude Code 的席位](https://support.claude.com/en/articles/11845131-use-claude-code-with-your-team-or-enterprise-plan)。

<h3 id="/web-setup-says-not-signed-in-to-claude">
  `/web-setup` 说 "Not signed in to Claude"
</h3>

如果 `/web-setup` 响应 "Not signed in to Claude. Run /login first."，CLI 没有有效的 claude.ai 登录。当之前的登录已过期时，也会发生这种情况。运行 `/login`，使用您的 claude.ai 账户登录，然后再次运行 `/web-setup`。

<h3 id="/web-setup-warns-that-your-token-doesn’t-have-the-workflow-scope">
  `/web-setup` 警告您的令牌没有 `workflow` 作用域
</h3>

如果 `/web-setup` 说您的 GitHub CLI 令牌没有 `workflow` 作用域，您可以继续，但 GitHub 可能会拒绝使用该令牌进行的某些推送，例如更改 GitHub Actions 工作流文件的推送。要添加作用域，在您的 shell 中运行 `gh auth refresh -s workflow`，然后再次运行 `/web-setup`。

<h3 id="web-setup-shows-no-commands-match-or-unknown-command">
  `/web-setup` 显示 "No commands match" 或 "Unknown command"
</h3>

`/web-setup` 在 Claude Code CLI 内运行，而不是在您的 shell 中。首先启动 `claude`，然后在提示符处输入 `/web-setup`。

如果您在 Claude Code 内输入它，命令菜单显示 `No commands match "/web-setup"`，或提交它返回 `Unknown command: /web-setup`，该命令被隐藏是因为未满足要求。原因通常是您使用 API 密钥或第三方提供商而不是 claude.ai 订阅进行身份验证。运行 `/login` 以使用您的 claude.ai 账户登录。

在 Team 和 Enterprise 计划上，该命令默认被隐藏：[快速网络设置切换](/docs/zh-CN/claude-code-on-the-web#github-authentication-options)关闭，直到所有者打开它。当它关闭时，[从浏览器连接 GitHub](#connect-github) 代替。

该命令在另外两种情况下也被隐藏：

* 管理员为您的组织禁用了云会话。在这种情况下，提交 `/web-setup` 返回 [`Cloud sessions are disabled by your organization's policy`](/docs/zh-CN/errors#cloud-sessions-are-disabled-by-your-organizations-policy)。在 v2.1.268 之前，这种情况也返回 `Unknown command: /web-setup`。
* 您的 Enterprise 组织启用了[零数据保留](/docs/zh-CN/zero-data-retention)，这使云会话不可用。

<h3 id="could-not-create-a-cloud-environment-or-no-cloud-environment-available-when-using-cloud">
  使用 `--cloud` 时出现 "Could not create a cloud environment" 或 "No cloud environment available"
</h3>

云会话功能如果您没有云环境，会自动创建一个默认的云环境。如果您看到 "Could not create a cloud environment"，自动创建失败。如果您看到 "No cloud environment available"，您的 CLI 早于自动创建。在任何一种情况下，在 Claude Code CLI 中运行 `/web-setup`，或从 [claude.ai/code](https://claude.ai/code) 的[环境选择器](/docs/zh-CN/cloud-environments#configure-your-environment)添加环境。

<h3 id="setup-script-failed">
  设置脚本失败
</h3>

设置脚本以非零状态退出，这会阻止会话启动。常见原因：

* 包安装失败，因为注册表不在您的[网络访问级别](/docs/zh-CN/cloud-environments#access-levels)中。`Trusted` 涵盖大多数包管理器；`None` 阻止它们全部。
* 脚本引用在新鲜克隆中不存在的文件或路径。
* 在本地工作的命令在 Ubuntu 上需要不同的调用。

要调试，在脚本顶部添加 `set -x` 以查看哪个命令失败。对于非关键命令，附加 `|| true` 以便它们不会阻止会话启动。

<h3 id="new-sessions-hang-or-time-out-during-setup">
  新会话在设置期间挂起或超时
</h3>

如果新会话在设置脚本步骤上停滞或在脚本完成前因通用容器错误而失败，脚本可能超过了构建[环境缓存](/docs/zh-CN/cloud-environments#environment-caching)的大约五分钟时间预算。拉取大型 Docker 镜像、同步完整依赖树或下载模型权重等繁重步骤经常会将总数推过限制，特别是当它们一个接一个运行时。

要解决此问题，修剪脚本使其可靠地在五分钟内完成：

* 使用 `&` 和最后的 `wait` 并行运行独立安装，而不是按顺序运行它们。
* 将最大的下载移出设置脚本，进入[SessionStart hook](/docs/zh-CN/cloud-environments#setup-scripts-vs-sessionstart-hooks)，在后台启动它们，以便会话在它们完成时变得可用。
* 从设置脚本中删除长重试睡眠，因为停滞的重试循环会计入预算。

<h3 id="session-keeps-running-after-closing-the-tab">
  关闭选项卡后会话继续运行
</h3>

这是设计使然。关闭选项卡或导航离开不会停止会话。它在后台继续运行，直到 Claude 完成当前任务，然后空闲。从侧边栏，您可以[存档会话](/docs/zh-CN/claude-code-on-the-web#archive-sessions)以将其从列表中隐藏，或[删除它](/docs/zh-CN/claude-code-on-the-web#delete-sessions)以永久删除它。

<h2 id="next-steps">
  后续步骤
</h2>

现在您可以提交和审查任务，这些页面涵盖接下来的内容：从您的终端启动云会话、安排定期工作以及给 Claude 常设指令。

* [使用 Claude Code on the web](/docs/zh-CN/claude-code-on-the-web)：完整参考，包括将会话传送到您的终端、会话共享和自动修复拉取请求
* [配置云环境](/docs/zh-CN/cloud-environments)：网络访问级别、环境变量和云会话的设置脚本
* [Routines](/docs/zh-CN/routines)：按计划、通过 API 调用或响应 GitHub 事件自动化工作
* [CLAUDE.md](/docs/zh-CN/memory)：给 Claude 持久指令和上下文，在每个会话开始时加载
* 为 [iOS](https://apps.apple.com/us/app/claude-by-anthropic/id6473753684) 或 [Android](https://play.google.com/store/apps/details?id=com.anthropic.claude) 安装 Claude 移动应用以从您的手机监控会话。从 Claude Code CLI，`/mobile` 显示 QR 码，用于 [claude.ai/mobile](https://claude.ai/mobile)，该码会为您的手机打开正确的应用商店。

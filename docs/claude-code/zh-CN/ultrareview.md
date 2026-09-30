> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# 使用 Ultrareview 查找错误

> 使用 /code-review ultra 在云中运行深度多代理代码审查，在合并前查找和验证错误。

<Note>
  Ultrareview 是一个研究预览功能。该功能、定价和可用性可能会根据反馈而改变。该命令是 `/code-review ultra`。当 ultrareview 对您的账户可用时，`/ultrareview` 是一个别名。
</Note>

Ultrareview 是在 Anthropic 基础设施上运行的[云会话](/docs/zh-CN/claude-code-on-the-web)中进行的深度代码审查。当您运行 `/code-review ultra` 时，Claude Code 在云沙箱中启动一队审查代理来查找您的分支或拉取请求中的错误。

与本地 `/code-review` 相比，ultrareview 提供：

* **更高的信号质量**：每个报告的发现都经过独立复现和验证，因此结果专注于真实的错误而不是风格建议
* **更广泛的覆盖范围**：许多审查代理并行探索更改，这会发现本地审查可能遗漏的问题
* **无本地资源使用**：审查完全在云沙箱中运行，因此您的终端在运行时保持空闲，可用于其他工作

Ultrareview 需要使用 claude.ai 账户进行身份验证，因为它在 Anthropic 基础设施上作为云会话运行。如果您仅使用 API 密钥登录，请先运行 `/login` 并使用 claude.ai 进行身份验证。当使用 Claude Code 与 Amazon Bedrock、Google Cloud 的 Agent Platform 或 Microsoft Foundry 时，Ultrareview 不可用，对于已启用零数据保留的组织也不可用。当 ultrareview 不可用时，`/code-review ultra` 会在您的会话中运行本地审查。

<h2 id="run-ultrareview-from-the-cli">
  从 CLI 运行 ultrareview
</h2>

从任何 git 存储库启动审查：

```text theme={null}
/code-review ultra
```

不带参数时，ultrareview 审查您当前分支与默认分支之间的差异，包括未提交和暂存的更改。对于名称类似凭证或密钥的文件（如 `.env` 和 `*.tfvars` 文件）中的未提交更改，Claude Code 遵循[将本地存储库上传到云会话](/docs/zh-CN/claude-code-on-the-web#send-local-repositories-without-github)的规则。

对于分支审查，Claude Code 捆绑存储库状态并将其上传到云沙箱；当您[审查拉取请求](#review-a-pull-request)时，Claude Code 不会从您的计算机上传任何内容。

启动前，Claude Code 显示一个确认对话框，其中包含审查范围、您剩余的免费运行次数和估计成本；对于分支审查，范围包括文件和行数。确认后，审查在后台继续进行，您可以继续使用您的会话。

该命令仅在您使用 `/code-review ultra` 调用时运行；Claude 不会自动启动 ultrareview。

<h3 id="review-against-a-different-base">
  针对不同的基础进行审查
</h3>

要与默认分支以外的基础进行比较，请传递分支名称。此示例针对 `develop` 而不是默认分支审查您的当前分支：

```text theme={null}
/code-review ultra develop
```

基础分支不需要存在于您的本地克隆中；Claude Code 从 `origin` 获取它。如果名称有拼写错误，Claude Code 会在错误中建议最接近的分支名称。

提交 id 或标签也可以作为基础，审查将涵盖您的分支自该提交以来的更改。

<h3 id="review-a-pull-request">
  审查拉取请求
</h3>

要审查 GitHub 拉取请求而不是本地分支，请传递 PR 编号：

```text theme={null}
/code-review ultra 1234
```

该命令也接受 `#1234`、`PR 1234` 和粘贴的 PR URL；粘贴的 URL 必须指向您当前目录中的存储库。

在 PR 模式下，云沙箱直接从主机克隆拉取请求，而不是捆绑您的本地工作树。PR 模式适用于 `github.com` 上的存储库以及 Owner 已连接到 Claude Code 的 [GitHub Enterprise Server](/docs/zh-CN/github-enterprise-server) 实例。

对于 `github.com` 上的存储库，沙箱使用连接到您的 Claude 账户的 GitHub 账户进行克隆，因此该账户必须能够读取 PR 的存储库。Claude Code 在创建云会话之前检查这一点，除非您已设置 [`CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC`](/docs/zh-CN/env-vars#variables)，并在[未连接账户](/docs/zh-CN/errors#no-github-account-is-connected-to-your-claude-account)或[账户无法看到存储库](/docs/zh-CN/errors#your-connected-github-account-cant-see-the-repository)时拒绝启动；拒绝会说明修复方法。在 v2.1.248 之前，Claude Code 在启动前不检查这一点。

运行 [`/web-setup`](/docs/zh-CN/web-quickstart#connect-from-your-terminal) 将您的 GitHub CLI 登录连接到您的 Claude 账户。

<h3 id="post-findings-to-the-pull-request">
  将发现发布到拉取请求
</h3>

在 Claude Code v2.1.227 或更高版本上，当您在 `github.com` 上审查拉取请求时，您可以让 Claude 将完成的发现作为来自您自己 GitHub 账户的单个纯文本评论发布到 PR。该评论不是审查或批准，并以"由 Claude Code 生成"的说明结尾。当您审查分支或 GitHub Enterprise Server 拉取请求时，Claude Code 仅在您的会话中显示发现。

Claude Code 永远不会发布，除非您在该运行中选择，`--no-post` 是默认值。发布是您为每次运行做出的选择：

* **交互式**：在启动对话框中，选择**运行并将发现作为我发布到 PR**。如果您将 `--post` 添加到命令中，如 `/code-review ultra 1234 --post`，Claude Code 会预选该选择，但仍会在启动前询问。
* **非交互式**：使用 `--post` 运行 [`claude ultrareview` 子命令](#run-ultrareview-non-interactively)。通过使用该标志运行子命令，您同意发布，因此 Claude Code 会发布而不询问。在 `claude -p '/code-review ultra'` 运行中，Claude Code 在发现到达前退出，因此不会发布任何内容；改用子命令。

Claude Code 不会从您的计算机发布。它将审查的会话 ID 发送到 Anthropic API，该 API 通过您连接到 Claude 的 GitHub 账户将审查的存储发现作为评论发布。发布需要与审查本身相同的 claude.ai 登录，并且在第三方提供商上或当您设置 [`CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC`](/docs/zh-CN/env-vars) 时不可用。

在交互式会话中，Claude Code 在发现到达时启动发布，因此请保持会话打开直到审查完成。Claude Code 仅在该会话中保留发布选择。如果会话在审查完成前结束，Claude Code 不会发布任何内容，即使您稍后恢复对话。

当发布完成时，Claude 会告诉您结果：

* **已发布**：Claude 为您提供评论的链接。
* **已发布**：同一审查的早期发布已将评论放在 PR 上，因此 Claude 会链接您到拉取请求，而不是再次发布。
* **失败**：Claude 会告诉您原因，发现会保留在您的终端中，以便您可以手动发布。

<h3 id="pass-a-request-in-plain-words">
  用纯文本传递请求
</h3>

在 Claude Code v2.1.218 或更高版本上，您也可以用纯文本描述您正在处理的内容：

```text theme={null}
/code-review ultra check my auth changes
```

审查仍然涵盖您的当前分支，与不带参数运行的范围相同。Claude 将您的文本保留为一个说明，显示在启动对话框中，并在发现到达时将其与发现相关联。

当 Claude Code 的文本超过一个单词且不是分支名称或 PR 引用时，它将您的文本视为说明。它将单个单词读取为分支名称或 PR 引用，因此拼写错误的分支名称会从[针对不同的基础进行审查](#review-against-a-different-base)获得最接近分支的错误，而不是使用说明启动。如果您的文本将 PR 引用与其他单词结合，如 `check PR 123 again`，Claude Code 也不会启动；它会要求您重新运行仅使用 PR 编号来审查该 PR，或不使用引用来审查您的当前分支。

<Tip>
  如果您的存储库太大而无法捆绑，Claude Code 会提示您改用 PR 模式。推送您的分支并打开草稿 PR，然后运行 `/code-review ultra <PR-number>`。
</Tip>

<h3 id="diff-limits-and-fallbacks">
  差异限制和回退
</h3>

Ultrareview 在任何审查工作运行之前检查差异，并在无法按原样审查时告诉您：

* **差异过大**：分支审查默认最多可包括 500 个更改的文件和 8,000 个更改的行。确切的值可能会改变，[拒绝](/docs/zh-CN/errors#diff-is-too-large-for-ultrareview)会说明生效的值、您的差异大小以及更改行数最多的文件。Claude Code 以相同的方式拒绝过大的拉取请求，说明其文件和行数，但不说明每个文件的细分
* **没有要审查的内容**：当针对基础的差异为空时，ultrareview 拒绝并说明它比较的分支或提交以及您所处的情况，例如在基础分支本身上且没有未提交的内容，或一个分支的所有提交都已是基础的一部分。它还为该情况建议解决方法，例如切换到您的工作分支、暂存或提交本地编辑，或传递不同的基础
* **首次提交**：存储库的首次提交没有更早的内容可比较，因此 ultrareview 在您在启动对话框中确认后审查其中的每个文件。如果您有未跟踪的文件，它会拒绝并告诉您 `git add` 您想审查的文件。相同的大小限制适用。

  首次提交仅在该确认后才被整体审查，因此 `claude ultrareview` 子命令和 `claude -p` 拒绝它并指向您使用交互式会话。需要 Claude Code v2.1.277 或更高版本
* **没有合并基础**：当您的分支与基础分支没有共享历史时，或存储库没有基础分支可比较时，ultrareview 审查存储库中的每个跟踪文件。回退需要完整克隆并应用相同的大小限制。它仅在您在启动对话框中确认或自己运行 `claude ultrareview` 子命令时启动。在 `claude -p` 和任何其他两者都不发生的地方，ultrareview 拒绝，说审查将涵盖每个文件，并指向您使用交互式会话。

  在没有分支或其他引用的检出上，例如通过在获取 URL 后检出 `FETCH_HEAD` 创建的分离 HEAD，Claude Code [拒绝审查](/docs/zh-CN/errors#your-checkout-has-no-branches)并建议首先创建分支

<h2 id="pricing-and-free-runs">
  定价和免费运行
</h2>

Ultrareview 是一项高级功能，按额外使用量而不是您计划的包含使用量计费。

| 计划 | 包含的免费运行 | 免费运行后 |
| - | - | - |
| Pro | 3 次免费运行 | 按 [额外使用量](https://support.claude.com/zh-CN/articles/12429409-extra-usage-for-paid-claude-plans) 计费 |
| Max | 3 次免费运行 | 按 [额外使用量](https://support.claude.com/zh-CN/articles/12429409-extra-usage-for-paid-claude-plans) 计费 |
| Team 和 Enterprise | 无 | 按 [额外使用量](https://support.claude.com/zh-CN/articles/12429409-extra-usage-for-paid-claude-plans) 计费 |

* **免费运行**：Pro 和 Max 的三次运行是每个账户的一次性分配，不会刷新。
* **每次审查的成本**：使用完免费运行后，通常花费 \$5 到 \$25 的使用额度，具体取决于更改的大小，与启动对话框在每次运行前显示的估计相匹配。
* **何时计数一次运行**：一旦云会话启动。您提前停止或未能完成的审查仍然会使用一次免费运行；付费审查仅对运行的部分计费。

由于 ultrareview 在免费运行之外始终按使用额度计费，您的账户或组织必须在启动付费审查之前启用使用额度。如果未启用使用额度，Claude Code 会阻止启动，启用方式取决于您的计费访问权限：

* 如果您可以管理您账户的计费，Claude Code 会将您链接到计费设置，您可以在那里启用使用额度。
* 在 Team 和 Enterprise 计划上，没有计费访问权限的成员可以从 CLI 发送请求，要求其管理员启用使用额度。

您也可以运行 `/usage-credits` 来检查或更改您的使用额度设置。

Claude Code 在每次对话中要求您确认一次使用额度计费：例如，当您使用 `/clear` 启动新对话时，Claude Code 会在下一次付费审查时再次显示确认。

<h2 id="track-a-running-review">
  跟踪正在运行的审查
</h2>

审查通常需要 5 到 10 分钟。审查作为后台任务运行，因此您可以继续在会话中工作、启动其他命令或完全关闭终端。如果您选择了[将发现发布到拉取请求](#post-findings-to-the-pull-request)，请保持会话打开直到审查完成；如果会话先结束，Claude Code 将不会发布任何内容。

使用 `/tasks` 查看正在运行和已完成的审查、打开审查的详细视图或停止正在进行的审查。如果您停止审查，Claude Code 会存档云会话，不会返回部分发现。

Claude 还可以告诉您审查已停止或其会话未找到：

* 如果审查的云会话在审查完成前在 claude.ai 上停止或[存档](/docs/zh-CN/claude-code-on-the-web#archive-sessions)，Claude 会告诉您它已停止。
* 如果审查的云会话被删除，或者您自启动它以来已登录到不同的 Claude 账户或组织，Claude 会告诉您会话未找到。
* 如果您切换了账户，审查可能仍会在启动它的账户下完成。如果审查仍在运行，请以该账户重新登录，并使用 `claude --resume` 恢复对话以重新附加它。

审查完成后，Claude Code 会在您的会话中将验证的发现显示为通知。每个发现都包括文件位置和问题的解释，因此您可以要求 Claude 直接修复它。

<h2 id="run-ultrareview-non-interactively">
  非交互式运行 ultrareview
</h2>

使用 `claude ultrareview` 子命令从 CI 或脚本启动 ultrareview，无需交互式会话。该子命令启动与 `/code-review ultra` 相同的审查，阻止直到远程审查完成，并将发现打印到 stdout。

```bash theme={null}
claude ultrareview
claude ultrareview 1234
claude ultrareview origin/main
```

不带参数时，该子命令审查您当前分支与默认分支之间的差异，当不存在合并基础时具有与 `/code-review ultra` 相同的[整个存储库回退](#diff-limits-and-fallbacks)。传递 PR 编号来审查拉取请求，或传递基础分支来审查与该分支的差异；[基础分支处理](#review-against-a-different-base)与交互式命令匹配。

运行该子命令时，您同意整个存储库回退以及计费和条款提示，因此运行开始时无需等待输入。运行它本身就是您的同意。当 Claude 代替您运行该子命令时，例如通过 Bash 工具，Claude Code 会拒绝整个存储库审查。

在 Claude Code v2.1.218 或更高版本上，您也可以通过在非交互式会话中运行 `/code-review ultra` 来启动云审查，例如 `claude -p '/code-review ultra'`。Claude Code 启动审查并打印跟踪链接，无需等待发现，与 `claude ultrareview` 不同，后者会阻止直到发现到达。当审查会计费使用额度时，Claude Code 在启动前停止并指向 `claude ultrareview`，因为计费确认需要交互式会话。在 v2.1.218 之前，非交互式会话中的 `/code-review ultra` 运行本地审查。

进度消息和实时会话 URL 转到 stderr，以便 stdout 保持可解析。使用这些标志来控制输出、超时以及是否发布发现：

| 标志 | 描述 |
| - | - |
| `--json` | 打印原始 `bugs.json` 有效负载而不是格式化的发现 |
| `--timeout <minutes>` | 等待审查完成的最大分钟数。默认为 45 |
| `--post` | [将完成的发现作为来自您 GitHub 账户的一条纯文本注释发布](#post-findings-to-the-pull-request)到拉取请求。适用于 `github.com` 拉取请求目标；在其他目标上，Claude Code 忽略该标志并说明。需要 Claude Code v2.1.227 或更高版本 |
| `--no-post` | 不发布发现。这是默认值，如果您同时传递两个标志，Claude Code 不会发布。需要 Claude Code v2.1.227 或更高版本 |

运行 `claude ultrareview` 需要与 `/code-review ultra` 相同的身份验证和使用额度配置。

该子命令以三个代码之一退出：

* **0**：审查完成，无论是否有发现
* **1**：审查无法启动、云会话出错或超时已过
* **130**：您使用 Ctrl-C 中断了子命令

如果您中断子命令，远程审查会继续运行；按照打印到 stderr 的会话 URL 在浏览器中观看它。

使用 `--post` 时，子命令在打印发现后立即开始发布，并将链接打印到 stderr。

* 如果运行失败、停止或超时，或者您中断它，子命令不发布任何内容。
* 如果审查完成但注释未发布，Claude Code 将原因打印到 stderr，发现保留在 stdout 上，以便您可以手动发布它们。

对于 GitHub 拉取请求上的自动审查，[Code Review](/docs/zh-CN/code-review) 直接与您的存储库集成，并将发现作为内联 PR 注释发布，无需 CLI 步骤。

<h2 id="how-ultrareview-compares-to-/code-review">
  ultrareview 与 /code-review 的比较
</h2>

两个审查都检查代码，但您在工作流的不同阶段使用它们。

| | `/code-review` | `/code-review ultra` |
| - | - | - |
| 目标 | 您的工作差异、pull request、分支或路径 | 您的工作差异或 pull request |
| 运行位置 | 在您的会话中本地运行 | 在云沙箱中运行 |
| 深度 | 随着 effort 参数扩展 | 具有独立验证的多代理队列 |
| 持续时间 | 几秒到几分钟 | 大约 5 到 10 分钟 |
| 成本 | 计入正常使用量 | 免费运行，然后大约 \$5 到 \$25 每次审查作为使用额度 |
| 最适合 | 迭代时的快速反馈 | 合并前对重大更改的信心 |

使用 `/code-review` 获得工作时的快速反馈，或传递 PR 编号以在批准前审查团队成员的 pull request。在合并重大更改前使用 `/code-review ultra`，当您想要更深入的审查来捕捉本地审查可能遗漏的问题时。

<h2 id="related-resources">
  相关资源
</h2>

* [在云端使用 Claude Code](/docs/zh-CN/claude-code-on-the-web)：了解云会话和云沙箱如何工作
* [有效管理成本](/docs/zh-CN/costs)：跟踪使用情况并设置支出限制

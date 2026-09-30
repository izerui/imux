> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Code Review

> 设置自动化 PR 审查，通过对完整代码库的多代理分析来捕获逻辑错误、安全漏洞和回归问题

<Note>
  Code Review 处于研究预览阶段，仅适用于 [Team 和 Enterprise](https://claude.ai/admin-settings/claude-code) 订阅。对于启用了 [Zero Data Retention](/docs/zh-CN/zero-data-retention) 的组织，此功能不可用。在其他计划上，您仍然可以使用 `/code-review` 命令[在本地审查差异](#review-a-diff-locally)。
</Note>

Code Review 分析您的 GitHub pull request，并在发现问题的代码行上发布内联评论。一支由专业代理组成的团队在完整代码库的上下文中检查代码更改，寻找逻辑错误、安全漏洞、破损的边界情况和微妙的回归问题。

发现结果按严重程度标记，不会批准或阻止您的 PR，因此现有的审查工作流保持不变。您可以通过向存储库添加 `CLAUDE.md` 或 `REVIEW.md` 文件来调整 Claude 标记的内容。

要在您自己的 CI 基础设施中运行 Claude 而不是使用此托管服务，请参阅 [GitHub Actions](/docs/zh-CN/github-actions) 或 [GitLab CI/CD](/docs/zh-CN/gitlab-ci-cd)。对于自托管 GitHub 实例上的存储库，请参阅 [GitHub Enterprise Server](/docs/zh-CN/github-enterprise-server)。

本页涵盖：

* [审查工作原理](#how-reviews-work)
* [设置](#set-up-code-review)
* [手动触发审查](#manually-trigger-reviews)，使用 `@claude review` 和 `@claude review always`
* [自定义审查](#customize-reviews)，使用 `CLAUDE.md` 和 `REVIEW.md`
* [定价](#pricing)
* [故障排除](#troubleshooting)失败的运行和缺失的评论
* [在本地审查差异](#review-a-diff-locally)，使用 `/code-review` 命令

<h2 id="how-reviews-work">
  审查工作原理
</h2>

一旦管理员为您的组织[启用 Code Review](#set-up-code-review)，审查将在 PR 打开时、每次推送时或手动请求时触发，具体取决于存储库的配置行为。在任何模式下，注释 `@claude review` 可以[在 PR 上启动审查](#manually-trigger-reviews)。

当审查运行时，多个代理在 Anthropic 基础设施上并行分析差异和周围代码。每个代理寻找不同类别的问题，然后验证步骤检查候选项是否与实际代码行为相符，以过滤掉误报。结果被去重、按严重程度排序，并作为内联评论发布在发现问题的特定行上，并在审查正文中包含摘要。如果未发现问题，Code Review 会更新 GitHub 检查运行以显示未检测到问题。Claude 也可能在 PR 上发布简短的确认评论。

审查成本随 PR 大小和复杂性而扩展，平均在 20 分钟内完成。管理员可以通过[分析仪表板](#view-usage)监控审查活动和支出。

<h3 id="severity-levels">
  严重程度级别
</h3>

每个发现都标有严重程度级别：

| 标记 | 严重程度 | 含义 |
| :- | :- | :- |
| 🔴 | 重要 | 应在合并前修复的错误 |
| 🟡 | 小问题 | 轻微问题，值得修复但不阻止 |
| 🟣 | 预先存在 | 代码库中存在但不是由此 PR 引入的错误 |

发现包括可折叠的**为什么被标记**部分，您可以展开以了解 Claude 为什么标记该问题以及它如何验证问题。

<h3 id="rate-and-reply-to-findings">
  对发现进行评分和回复
</h3>

Claude 的每条审查评论都已附加 👍 和 👎，因此两个按钮都会在 GitHub UI 中出现，以便一键评分。如果发现有用，请点击 👍；如果发现错误或嘈杂，请点击 👎。Anthropic 在 PR 合并后收集反应计数，并使用它们来调整审查者。反应不会触发重新审查或更改 PR 上的任何内容。

回复内联评论不会提示 Claude 响应或更新 PR。要对发现采取行动，请修复代码并推送。如果 PR 订阅了推送触发的审查，下一次运行将在问题修复时解决线程。要请求新审查而不推送，请作为[顶级 PR 评论](#manually-trigger-reviews)注释 `@claude review`。

要在不更改代码的情况下关闭发现，请解决其线程；回复不会关闭它。

<h3 id="check-run-output">
  检查运行输出
</h3>

除了内联审查评论外，每次审查都会填充 **Claude Code Review** 检查运行，该运行与您的 CI 检查一起出现。展开其 **Details** 链接以在一个地方查看每个发现的摘要，按严重程度排序：

| 严重程度 | 文件:行 | 问题 |
| - | - | - |
| 🔴 重要 | `src/auth/session.ts:142` | 令牌刷新与登出竞争，导致过期会话保持活跃 |
| 🟡 小问题 | `src/auth/session.ts:88` | `parseExpiry` 在格式错误的输入上静默返回 0 |

每个发现也作为 **Files changed** 选项卡中的注释出现，直接标记在相关的差异行上。重要发现用红色标记呈现，小问题用黄色警告，预先存在的错误用灰色通知。注释和严重程度表独立于内联审查评论写入检查运行，因此即使 GitHub 拒绝在移动的行上的内联评论，它们仍然可用。

检查运行始终以中立结论完成，因此它永远不会通过分支保护规则阻止合并。如果您想根据 Code Review 发现来限制合并，请在您自己的 CI 中读取检查运行输出中的严重程度分解。Details 文本的最后一行是一个机器可读的评论，您的工作流可以使用 `gh` 和 jq 解析。要找到检查运行 ID，请使用 `gh api repos/OWNER/REPO/commits/<commit-sha>/check-runs --jq '.check_runs[] | {id, name}'` 列出提交的检查运行，并获取 `Claude Code Review` 运行的 `id`。将 `OWNER`、`REPO` 和 `CHECK_RUN_ID` 替换为您的存储库所有者、存储库名称和该 ID：

```bash theme={null}
gh api repos/OWNER/REPO/check-runs/CHECK_RUN_ID \
  --jq '.output.text | split("bughunter-severity: ")[1] | split(" -->")[0] | fromjson'
```

这返回一个 JSON 对象，其中包含每个严重程度的计数，例如 `{"normal": 2, "nit": 1, "pre_existing": 0}`。`normal` 键保存重要发现的计数；非零值意味着 Claude 发现了至少一个在合并前值得修复的错误。

<h3 id="what-code-review-checks">
  Code Review 检查的内容
</h3>

默认情况下，Code Review 专注于正确性：会破坏生产的错误，而不是格式偏好或缺失的测试覆盖。您可以通过[向存储库添加指导文件](#customize-reviews)来扩展其检查范围。

<h2 id="set-up-code-review">
  设置 Code Review
</h2>

管理员为组织启用一次 Code Review，并选择要包含的存储库。

<Steps>
  <Step title="打开 Claude Code 管理员设置">
    转到 [claude.ai/admin-settings/claude-code](https://claude.ai/admin-settings/claude-code) 并找到 Code Review 部分。您需要对 Claude 组织具有 Owner 或 Primary Owner 角色，并有权在 GitHub 组织中安装 GitHub Apps。
  </Step>

  <Step title="开始设置">
    点击**设置**。这将开始 GitHub App 安装流程。
  </Step>

  <Step title="安装 Claude GitHub App">
    按照提示安装 Claude GitHub App：选择拥有您要审查的存储库的 GitHub 组织，选择应用可以访问的存储库，并批准请求的权限。

    要审查 pull request，Claude 通过应用的读取访问权限读取您的存储库内容，并通过其对 pull request 和检查的写入访问权限发布评论和 [check run](#check-run-output)。在安装期间，您授予由其他 Claude 功能（如 [GitHub Actions](/docs/zh-CN/github-actions)）共享的更广泛权限集；有关完整列表，请参阅 [GitHub App 权限](/docs/zh-CN/github-actions#github-app-permissions)。
  </Step>

  <Step title="选择存储库">
    选择要为 Code Review 启用的存储库。如果您看不到存储库，请确保在安装期间为 Claude GitHub App 提供了对其的访问权限。您可以稍后添加更多存储库。
  </Step>

  <Step title="为每个存储库设置审查触发器">
    设置完成后，Code Review 部分在表格中显示您的存储库。对于每个存储库，使用**审查行为**下拉菜单选择何时运行审查：

    * **PR 创建后一次**：当 PR 打开或标记为准备审查时运行一次审查
    * **每次推送后**：在每次推送到 PR 分支时运行审查，在 PR 演变时捕获新问题，并在您修复标记的问题时自动解决线程
    * **手动**：打开或推送到 PR 不会启动审查；注释 [`@claude review`](#manually-trigger-reviews) 以请求审查，或 `@claude review always` 以同时订阅 PR 到后续推送的审查

    无论您选择哪个选项，Claude 仅在有人在 [fork 中的 pull request](#review-pull-requests-from-forks) 上注释 `@claude review` 时才审查它。

    每次推送时审查会运行最多审查并花费最多。手动模式对于高流量存储库很有用，您可以选择特定 PR 进行审查，或仅在 PR 准备好后才开始审查。
  </Step>
</Steps>

存储库表还显示每个存储库基于最近活动的平均审查成本。使用行操作菜单为每个存储库打开或关闭 Code Review，或完全删除存储库。

要验证设置，请打开测试 PR。如果您选择了自动触发器，在几分钟内会出现名为 **Claude Code Review** 的检查运行。如果您选择了手动，在 PR 上注释 `@claude review` 以启动第一次审查。如果没有出现检查运行，请确认存储库在您的管理员设置中列出，并且 Claude GitHub App 有权访问它。

<h2 id="manually-trigger-reviews">
  手动触发审查
</h2>

注释命令按需启动审查。无论存储库的配置触发器如何，它们都有效，因此您可以使用它们在手动模式下选择特定 PR 进行审查，或在其他模式下获得立即重新审查。

| 命令 | 作用 |
| :- | :- |
| `@claude review` | 启动单次审查，不订阅未来推送 |
| `@claude review always` | 启动审查并将 PR 订阅到今后的推送触发审查 |
| `@claude review once` | 与 `@claude review` 相同：启动单次审查，不订阅 |

当您想要对 PR 的每个后续推送启动新审查时，使用 `@claude review always`，例如在设置为手动模式的存储库中的高优先级 PR 上。由于基础命令不订阅 PR，您可以请求一次性第二意见，而不改变后续推送是否触发审查。

<Note>
  在 2026 年 7 月更新之前，`@claude review` 将 PR 订阅到推送触发审查。如果您依赖该行为，请改为注释 `@claude review always`。`@claude review once` 仍然有效，行为与基础命令相同。
</Note>

对于任一命令触发审查：

* 将其作为顶级 PR 评论发布，而不是差异行上的内联评论
* 在注释开头放置命令，`once` 或 `always` 与命令的其余部分在同一行
* 您必须对存储库具有写入、维护或管理员权限
* PR 必须打开

如果存储库属于某个组织，且您在该组织中的成员身份是私密的（这是 GitHub 的默认设置），GitHub 不会将您标识为 Claude 的成员。Claude 可能仍会用 👀 对您的评论做出反应，但除非您被直接添加到存储库作为协作者，否则它不会启动审查，即使团队或组织的基础权限给予您写入访问权限。要解决此问题，[公开您的组织成员身份](https://docs.github.com/en/account-and-profile/setting-up-and-managing-your-personal-account-on-github/managing-your-membership-in-organizations/publicizing-or-hiding-organization-membership)或要求存储库管理员将您添加到存储库作为协作者。

与自动触发不同，手动触发在草稿 PR 上运行，因为显式请求表示您想要现在的审查，无论草稿状态如何。

如果该 PR 上已有审查正在运行，请求将排队等待进行中的审查完成。您可以通过 PR 上的检查运行监控进度。

<h3 id="review-pull-requests-from-forks">
  审查来自分支的拉取请求
</h3>

Claude 不会自动审查来自分支的拉取请求，无论存储库的**审查行为**设置如何。要启动一个，请在拉取请求上注释 `@claude review`。[注释命令的要求](#manually-trigger-reviews)仍然适用，您需要的写入访问权限是针对基础存储库的，而不是分支。

要获得分支拉取请求的另一次审查，请发布新的 `@claude review` 注释。`@claude review always` 也有效，但不会将拉取请求订阅到后续推送上的审查。除了注释命令外，没有其他任何东西会在分支拉取请求上启动审查：

* 在检查运行上单击**重新运行**不会启动审查
* 推送新提交不会启动审查，即使在设置为**每次推送后**的存储库中也不会

<h2 id="customize-reviews">
  自定义审查
</h2>

Code Review 从您的存储库读取两个文件来指导它标记的内容。它们在如何强烈影响审查方面有所不同：

* **`CLAUDE.md`**：共享项目说明，Claude Code 用于所有任务，不仅仅是审查。Code Review 将其作为项目上下文读取，并将新引入的违规标记为小问题。
* **`REVIEW.md`**：仅审查说明，提供给审查管道中查找和验证发现的代理，并由排名和报告发现的代理咨询。使用它来说明您的团队想要标记的内容、严重程度以及如何报告发现。

<h3 id="claude-md">
  CLAUDE.md
</h3>

Code Review 读取您的存储库的 `CLAUDE.md` 文件，并将新引入的违规视为[小问题级别](#severity-levels)的发现。这是双向工作的：如果您的 PR 以使 `CLAUDE.md` 语句过时的方式更改代码，Claude 会标记文档需要更新。

Claude 在目录层次结构的每个级别读取 `CLAUDE.md` 文件，因此子目录的 `CLAUDE.md` 中的规则仅适用于该路径下的文件。有关 `CLAUDE.md` 如何工作的更多信息，请参阅[内存文档](/docs/zh-CN/memory)。

对于您不想应用于常规 Claude Code 会话的仅审查指导，请改用 [`REVIEW.md`](#review-md)。

<h3 id="review-md">
  REVIEW\.md
</h3>

`REVIEW.md` 是位于您的存储库根目录的文件，它为您的存储库定制 Code Review。审查管道中查找和验证发现的代理接收其内容作为您的存储库的审查说明，以及 Code Review 的默认审查指导，排名和报告发现的代理在确定严重程度和编写审查之前咨询它。

将您想要强制执行的规则直接放在 `REVIEW.md` 中。

<h4 id="what-you-can-tune">
  您可以调整的内容
</h4>

`REVIEW.md` 是自由格式的 markdown，因此任何您可以表达为审查说明的内容都在范围内。下面的模式在实践中影响最大。

**严重程度**：为您的存储库重新定义 🔴 重要的含义。默认校准针对生产代码；文档存储库、配置存储库或原型可能想要更窄的定义。明确说明哪些类别的发现是重要的，哪些最多是小问题。您也可以向另一个方向升级，例如将任何 `CLAUDE.md` 违规视为重要而不是默认小问题。

**小问题数量**：限制单次审查发布的 🟡 小问题评论数量。散文和配置文件可以永远被打磨。像"最多报告五个小问题，在摘要中提及其余的计数"这样的上限使审查可操作。

**跳过规则**：列出 Claude 应该不发布任何发现的路径、分支模式和发现类别。常见候选是生成的代码、lockfiles、供应商依赖和机器创作的分支，以及您的 CI 已经强制执行的任何内容，如 linting 或拼写检查。对于值得一些审查但不需要完全审查的路径，设置更高的标准而不是完全跳过："在 `scripts/` 中，仅在接近确定且严重时报告。"

**存储库特定检查**：添加您想在每个 PR 上标记的规则，如"新 API 路由必须有集成测试。"因为 `REVIEW.md` 到达每个发现和验证代理，这些比长 `CLAUDE.md` 中的相同规则更可靠地着陆。

**验证标准**：在发布发现类别之前需要证据。例如，"行为声明需要源中的 `file:line` 引用，而不是从命名推断"会减少否则会花费作者往返的误报。

**重新审查收敛**：告诉 Claude 当 PR 已经被审查时如何表现。像"在第一次审查后，抑制新的小问题并仅发布重要发现"这样的规则会阻止单行修复仅因风格而达到第七轮。

**摘要形状**：要求审查正文以一行计数开头，如 `2 factual, 4 style`，并在这种情况下以"没有事实问题"开头。作者想在详细信息之前知道工作的形状。

<h4 id="example">
  示例
</h4>

这个 `REVIEW.md` 为后端服务重新校准严重程度，限制小问题，跳过生成的文件，并添加存储库特定检查。

```markdown theme={null}
# 审查说明

## 重要在这里的含义

保留重要用于会破坏行为、泄露数据或阻止回滚的发现：不正确的逻辑、无范围的数据库查询、日志或错误消息中的 PII，以及不向后兼容的迁移。风格、命名和重构建议最多是小问题。

## 限制小问题

每次审查最多报告五个小问题。如果您发现了更多，请在摘要中说"加上 N 个类似项目"而不是内联发布它们。如果您发现的一切都是小问题，请以"没有阻止问题"开头摘要。

## 不要报告

- CI 已经强制执行的任何内容：lint、格式化、类型错误
- `src/gen/` 下生成的文件和任何 `*.lock` 文件
- 故意违反生产规则的仅测试代码

## 始终检查

- 新 API 路由有集成测试
- 日志行不包括电子邮件地址、用户 ID 或请求正文
- 数据库查询的范围限定为调用者的租户
```

<h4 id="keep-it-focused">
  保持专注
</h4>

长度有成本：长 `REVIEW.md` 会稀释最重要的规则。将其保持为改变审查行为的说明，并将常规项目上下文留在 `CLAUDE.md` 中。

<h2 id="view-usage">
  查看使用情况
</h2>

转到 [claude.ai/analytics/code-review](https://claude.ai/analytics/code-review) 以查看整个组织的 Code Review 活动。仪表板显示：

| 部分 | 显示内容 |
| :- | :- |
| 审查的 PR | 所选时间范围内每日审查的 pull request 计数 |
| 每周成本 | Code Review 的每周支出 |
| 反馈 | 因开发人员解决问题而自动解决的审查评论计数 |
| 存储库分解 | 每个存储库的审查 PR 计数和已解决评论 |

仪表板成本数字是用于监控活动的估计。对于发票准确的支出，请参考您的 Anthropic 账单。

<h2 id="pricing">
  定价
</h2>

Code Review 根据令牌使用情况计费。每次审查平均花费 \$15-25，随 PR 大小、代码库复杂性和需要验证的问题数量而扩展。Code Review 使用通过[使用额度](https://support.claude.com/zh-CN/articles/12429409-extra-usage-for-paid-claude-plans)单独计费，不计入您的计划包含的使用。

您选择的审查触发器影响总成本：

* **PR 创建后一次**：每个 PR 运行一次
* **每次推送后**：在每次推送时运行，将成本乘以推送次数
* **手动**：没有在打开或推送时进行审查，因此成本仅从某人请求的审查中产生

在 PR 创建后一次或手动模式下，注释 `@claude review always` [选择 PR 进入推送触发审查](#manually-trigger-reviews)，因此在该注释后每次推送都会产生额外成本。在每次推送后模式下，推送已经触发审查，因此订阅不会改变每次推送的成本。注释 `@claude review` 运行单次审查而不订阅未来推送。Claude 仅在有人注释 `@claude review` 时审查[来自分支的拉取请求](#review-pull-requests-from-forks)，因此分支拉取请求在任何模式下都不会产生每次推送的成本。

无论您的组织是否为其他 Claude Code 功能使用 Amazon Bedrock 或 Google Cloud 的 Agent Platform，成本都会出现在您的 Anthropic 账单上。要为 Code Review 设置每月支出上限，请转到 [claude.ai/admin-settings/usage](https://claude.ai/admin-settings/usage) 并为 Claude Code Review 服务配置限制。

通过[分析](#view-usage)中的每周成本图表或管理员设置中的每个存储库平均成本列监控支出。

<h2 id="troubleshooting">
  故障排除
</h2>

审查运行是尽力而为的，失败的运行永远不会阻止您的 PR。Code Review 会自动重试一些中断的审查。本部分介绍如何自己再次运行审查，以及当检查运行报告您找不到的问题时在哪里查看。

<h3 id="retrigger-a-failed-or-timed-out-review">
  重新触发失败或超时的审查
</h3>

当审查失败或超过时间限制时，检查运行完成，标题为 **Code review failed** 或 **Code review timed out**。结论仍然是中立的，因此没有任何东西阻止您的合并。除非检查运行的摘要说新的提交审查已自动排队，否则请自己再次运行审查。

要再次运行审查，在 PR 上注释 `@claude review`。这启动一个新的审查，不订阅 PR 到未来推送。如果 PR 不是[来自 fork](#review-pull-requests-from-forks)，您可以改为在 GitHub 的 Checks 选项卡中的 **Claude Code Review** 检查上点击 **Re-run**。重新运行也会启动一个新的审查，不订阅 PR。

<h3 id="review-didn’t-run-and-the-pr-shows-a-spend-cap-message">
  审查未运行，PR 显示支出上限消息
</h3>

当您的组织的每月支出上限达到时，Code Review 在 PR 上发布单条评论，解释审查被跳过。审查在下一个计费周期开始时自动恢复，或当管理员在 [claude.ai/admin-settings/usage](https://claude.ai/admin-settings/usage) 提高上限时立即恢复。

<h3 id="find-issues-that-aren’t-showing-as-inline-comments">
  查找未显示为内联评论的问题
</h3>

如果检查运行标题说发现了问题但您在差异上看不到内联审查评论，请在这些其他位置查看发现的位置：

* **检查运行 Details**：在检查选项卡中的 Claude Code Review 检查旁边点击 **Details**。严重程度表列出每个发现及其文件、行和摘要，无论内联评论是否被接受。
* **Files changed 注释**：在 PR 上打开 **Files changed** 选项卡。发现呈现为直接附加到差异行的注释，与审查评论分开。
* **审查正文**：如果您在审查运行时推送到 PR，某些发现可能引用当前差异中不再存在的行。这些出现在审查正文文本中的 **Additional findings** 标题下，而不是作为内联评论。

<h2 id="review-a-diff-locally">
  在本地审查差异
</h2>

[`/code-review` 命令](/docs/zh-CN/commands)在您的终端中审查差异，无需安装 GitHub App。它报告正确性错误和重用、简化和效率清理。

`/review` 是 `/code-review` 的别名；在 v2.1.223 之前，它是一个单独的命令，对 GitHub pull request 进行单次通过、只读审查。

<Steps>
  <Step title="运行 /code-review">
    从您正在工作的会话中，运行命令：

    ```text theme={null}
    /code-review
    ```

    它审查您分支相对于其上游的提前提交加上任何未提交的更改，因此它需要在分支或工作树中进行工作以有内容可报告。要审查其他内容，请传递一个目标：文件路径、PR 编号、分支名称或引用范围，例如 `main...my-feature`。

    您还可以添加标志：

    * `--fix`：在审查后将发现应用到您的工作树
    * `--comment`：将发现作为内联评论发布在 GitHub pull request 上，或作为单个注释发布在 GitLab merge request 上
    * `--post`：在 `github.com` pull request 的 `ultra` 云审查上，在启动对话框中预选将完成的发现发布到 PR；请参阅[将发现发布到 pull request](/docs/zh-CN/ultrareview#post-findings-to-the-pull-request)。需要 Claude Code v2.1.227 或更高版本

    当您为 GitLab merge request 传递 `--comment` 时，Claude Code 通过 GitLab 的 `glab` CLI 发布发现。需要 Claude Code v2.1.257 或更高版本。当 `glab` 未安装时，Claude 在终端中打印发现。

    将 merge request 作为其 URL 或 `!123` 引用传递。Claude Code 仅当检出的源在 `gitlab.com` 上时才将裸数字或分支名称视为 merge request。在自管理的 GitLab 实例上，传递 URL 或 `!123` 形式。
  </Step>

  <Step title="继续工作">
    审查作为具有自己上下文窗口的后台[子代理](/docs/zh-CN/sub-agents)运行，因此它不会填充您的对话。发现在审查完成时到达您的对话。
  </Step>

  <Step title="根据发现采取行动">
    要求 Claude 修复审查发现的内容。如果您传递了 `--fix` 或 `--comment`，审查已经应用或发布了其发现。
  </Step>
</Steps>

Claude 在这两个运行中都将发现作为文本报告在回复中，即使主机应用程序请求发现列表：

* 在终端会话中，其中 `/code-review` 作为[分叉子代理](/docs/zh-CN/skills#run-skills-in-a-subagent)运行审查
* 在带有文本或 JSON 输出的 `-p` 运行中

在请求发现列表的主机应用程序中，例如[桌面应用](/docs/zh-CN/desktop)，Claude 通过[`ReportFindings` 工具](/docs/zh-CN/tools-reference)报告审查的发现。Claude Code 将报告呈现为发现列表，每个条目显示文件位置、单句摘要和类别标签，例如当发现包含一个时的 `correctness`。主机请求在每个工作量级别应用，需要 Claude Code v2.1.218 或更高版本。

当 Claude 稍后在会话中修复报告的发现时，它会再次报告它们，Claude Code 将更新的发现列表中的每个发现标记为已修复、已跳过或无需更改。

<h3 id="what-the-review-reads-and-edits">
  审查读取和编辑的内容
</h3>

审查遵循您的 `CLAUDE.md`，就像任何 Claude Code 会话一样，但它不读取[`REVIEW.md`](#review-md)。后台审查在您的会话的[检查点](/docs/zh-CN/checkpointing#subagent-edits-not-restored)之外应用其 `--fix` 编辑，因此 `/rewind` 不会撤销它们；使用 git 来恢复它们。当审查[在前台运行](#run-in-the-foreground)时，它在您自己的回合期间编辑您的工作树，因此 `/rewind` 照常恢复其编辑。

<h3 id="tune-effort-and-arguments">
  调整工作量和参数
</h3>

传递一个[工作量级别](/docs/zh-CN/model-config#adjust-effort-level)以权衡覆盖范围和置信度。在 `low` 和 `medium` 处，审查仅报告它最有信心的发现，因此您看到更少的误报；`high` 到 `max` 扩大覆盖范围，可能包括审查不太确定的发现。

当您不输入级别时，审查重用您上次输入的 `low` 到 `max` 的级别，即使在较早的会话中，Claude Code 显示一个通知，例如 `Reusing high effort, the level you typed last time`。输入一个级别，例如 `/code-review high`，以更改后续运行重用的内容；您在非交互式 `-p` 运行中传递的级别不会更新它。`ultra` 既不更新也不使用记住的级别。如果您从未输入过级别，审查使用会话的当前工作量。在 v2.1.223 之前，没有级别的 `/code-review` 总是使用会话的当前工作量。

在工作量级别和标志之后，Claude Code 以两种方式之一读取行的其余部分：

* **不使用 `ultra`**：左边的所有内容都是审查目标，即使它以另一个命令名称开头。`/code-review /fix-issue 123` 使用 `/fix-issue 123` 作为目标文本进行审查，而不是将 `/fix-issue` 作为第二个[堆叠技能](/docs/zh-CN/skills#pass-arguments-to-skills)加载。在 v2.1.218 之前，堆叠在 `/code-review` 之后的命令作为其自己的技能展开。
* **使用 `ultra`**：Claude Code 读取单个单词作为基础分支或 PR 编号，并将不命名分支或 PR 的较长文本转换为[附加到审查的注释](/docs/zh-CN/ultrareview#pass-a-request-in-plain-words)。`/code-review ultra check my auth changes` 审查您的当前分支，Claude 将发现与您的注释相关联。

<h3 id="run-in-the-foreground">
  在前台运行
</h3>

审查默认在后台运行；在 v2.1.218 之前，它在您的对话中运行。在以下情况下它在前台运行：

* 您在较早的审查仍在进行时再次运行 `/code-review`
* 您以非交互式模式运行它，使用 `-p` 标志或 Agent SDK；Claude Code 等待审查并在响应中包含发现，除了 `ultra`，它[启动云审查而不等待](#escalate-to-ultrareview)
* 您将 [`CLAUDE_CODE_DISABLE_BACKGROUND_TASKS`](/docs/zh-CN/env-vars) 设置为 `1`，这也关闭了所有其他后台任务功能

<h3 id="let-claude-start-the-review">
  让 Claude 启动审查
</h3>

Claude 可以自己启动 `/code-review`。要求它以纯语言审查您的更改，它可以在不输入命令的情况下运行技能，[计划任务](/docs/zh-CN/scheduled-tasks)以 `/code-review` 作为其提示运行审查。

计划任务永远不会启动[云审查](#escalate-to-ultrareview)，因此计划 `/code-review` 不带 `ultra` 参数。

要在保持 `/code-review` 可供您输入的同时停止 Claude 和计划任务启动审查，请向[设置文件](/docs/zh-CN/settings#where-settings-live)（例如 `~/.claude/settings.json`）添加[`skillOverrides`](/docs/zh-CN/skills#override-skill-visibility-from-settings)条目：

```json theme={null}
{
  "skillOverrides": {
    "code-review": "user-invocable-only"
  }
}
```

在 v2.1.246 之前，Claude 仅在从 Anthropic 获取的功能标志打开它的地方自己启动 `/code-review`。在[不获取功能标志的会话](/docs/zh-CN/env-vars#features-that-need-feature-flag-fetching)中，`/code-review` 仅在您输入时运行，计划的 `/code-review` 作为纯文本到达 Claude。

<h3 id="escalate-to-ultrareview">
  升级到 ultrareview
</h3>

`/code-review ultra --fix` 在云中运行更深入的 [ultrareview](/docs/zh-CN/ultrareview)，然后在发现返回到您的会话时将其应用到您的工作树。

Ultrareview 使用其自己的范围：您当前的分支与存储库的默认分支，加上工作树中的任何未提交和暂存的更改。对于命名为凭证或密钥的未提交文件更改，例如 `.env` 和 `*.tfvars` 文件，Claude Code 遵循[将本地存储库上传到云会话](/docs/zh-CN/claude-code-on-the-web#send-local-repositories-without-github)的规则。传递分支名称，例如 `/code-review ultra develop`，以与不同的基础进行比较。

当目标是 `github.com` pull request 时，您可以让 Claude[将完成的发现发布到 PR](/docs/zh-CN/ultrareview#post-findings-to-the-pull-request)作为来自您 GitHub 账户的评论。需要 Claude Code v2.1.227 或更高版本。

<Note>
  Ultrareview 需要使用 claude.ai 账户进行身份验证，在 Amazon Bedrock、Google Cloud 的 Agent Platform 或 Microsoft Foundry 上不可用，或对启用了零数据保留的组织不可用。当 ultrareview 不可用时，`/code-review ultra` 在您的会话中运行本地审查。
</Note>

要从脚本或 CI 启动云审查，请运行 `claude -p '/code-review ultra'`。Claude Code 启动审查并打印用于跟踪它的链接。需要 Claude Code v2.1.218 或更高版本。

当审查会计费[使用额度](https://support.claude.com/en/articles/12429409-extra-usage-for-paid-claude-plans)时，Claude Code 在启动前停止，因为计费确认需要交互式会话。改为运行[`claude ultrareview` 子命令](/docs/zh-CN/ultrareview#run-ultrareview-non-interactively)；通过运行它，您同意该费用。

该命令在 v2.1.147 之前被命名为 `/simplify`，当时它默认应用修复。`/simplify` 运行单独的仅清理审查，应用修复而不寻找错误。如果您为错误查找编写了 `/simplify` 脚本，请切换到 `/code-review --fix`。

<h2 id="related-resources">
  相关资源
</h2>

* [Commands](/docs/zh-CN/commands)：在本地 Claude Code 会话中运行 `/code-review` 以在推送前检查差异
* [GitHub Actions](/docs/zh-CN/github-actions)：在您自己的 GitHub Actions 工作流中运行 Claude，以实现超越代码审查的自定义自动化
* [GitLab CI/CD](/docs/zh-CN/gitlab-ci-cd)：GitLab 管道的自托管 Claude 集成
* [Memory](/docs/zh-CN/memory)：`CLAUDE.md` 文件如何在 Claude Code 中工作
* [Analytics](/docs/zh-CN/analytics)：跟踪超越代码审查的 Claude Code 使用情况
* [How Anthropic secures its AI-native software development lifecycle](https://claude.com/blog/how-anthropic-secures-its-ai-native-software-development-lifecycle)：自动化审查如何作为 Anthropic 安全开发流程的一个层级

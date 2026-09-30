> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# 将自托管环境部署到生产环境

> 在生产环境中运行自托管运行器：安全加固、网络出站流量控制、git 凭证、Kubernetes 和 Compose 配方以及故障排除。

<Note>
  自托管环境在 Team 和 Enterprise 计划上处于公开测试阶段；[可用性和限制](/docs/zh-CN/self-hosted-environments#availability-and-limitations)涵盖启用路径。本页面涵盖在生产环境中运行队列；有关首个运行器和会话，请参阅[快速入门](/docs/zh-CN/self-hosted-environments-quickstart)。
</Note>

[自托管环境](/docs/zh-CN/self-hosted-environments)在您部署在网络内的运行器上运行 Claude Code [云会话](/docs/zh-CN/claude-code-on-the-web)，在生产环境中，这些会话代表所有可以向环境分派会话的人执行模型指导的代码。本页面适用于将工作环境投入生产的操作员。它按部署顺序进行：在连接真实系统之前要锁定什么、队列需要的出站流量、会话如何向您的 git 主机进行身份验证、部署配方本身，以及会话出现故障时要检查什么。

<h2 id="harden-your-deployment">
  加固您的部署
</h2>

自托管运行器代表所有可以向其环境分派会话的人在您的基础设施上执行任意的、模型指导的代码。这是您 Anthropic 组织的任何成员，以及任何可以在所有者路由到环境的范围内启动 [Claude Tag](https://claude.com/docs/claude-tag/overview) 频道会话的人。在将环境连接到生产系统之前，请逐项完成以下操作：

* **临时的、按会话的容器**：在新容器或 VM 中运行每个运行器进程，该容器或 VM 在进程退出时被销毁，使用 `--capacity 1` 和默认的 `--drain-grace-sec 0`，以便每个容器恰好服务一个会话。在更高的容量或正的 drain grace 下，一个容器为来自同一[锁定所有者](/docs/zh-CN/self-hosted-environments#key-concepts)的多个会话服务；请参阅[运行器生命周期](/docs/zh-CN/self-hosted-environments#runner-lifecycle)。不要在运行器重启之间重用文件系统，除了在刻意的[预热检出](#reuse-a-pre-warmed-checkout)设置中，并且永远不要跨所有者。
* **镜像中没有广泛的凭证**：不要包含长期的 SSH 密钥、云提供商凭证或授予超过会话需要的个人访问令牌。从您的[包装脚本](/docs/zh-CN/self-hosted-environments-configuration#wrapper-scripts)按会话铸造会话期间使用的凭证，例如推送或 API 令牌。对于在包装脚本运行之前发生的初始克隆，使用 [`checkout` 生命周期钩子](/docs/zh-CN/self-hosted-environments-configuration#checkout)或 [`--use-anthropic-git-proxy`](#use-the-anthropic-git-proxy)；请参阅[配置 git](#configure-git)。
* **将环境密钥保持在运行会话的主机之外**：环境密钥可以注册运行器并获取在环境上排队的任何会话。在固定队列上，它存在于每个运行器主机上，任何会话的代码都可以读取密钥文件。优先使用[按需运行器](/docs/zh-CN/self-hosted-environments-configuration#on-demand-runners)，其中密钥保留在编排器主机上，该主机从不运行用户代码，每个运行器接收单次使用的工作单，恰好注册一个运行器。在固定队列上，将环境密钥文件视为可由每个会话读取，并在任何可疑会话泄露后轮换密钥。
* **默认拒绝网络出站流量**：在每个环境上限制运行器和会话容器的出站流量在您自己的网络边界；[默认拒绝出站流量](#default-deny-egress)涵盖允许什么以及原因。
* **最小权限主机 IAM**：附加到运行器主机的计算身份（例如实例配置文件或节点服务帐户）应仅授予运行器本身需要的内容。会话应通过您的包装脚本而不是继承主机的身份获取自己的凭证。
* **阻止会话访问云元数据端点**：保持会话不访问主机身份需要阻止它们访问云元数据端点，子网级出站策略不会拦截链接本地元数据流量，因此在容器本身中阻止它：

  * IMDSv2，跳数限制为 1
  * GKE Workload Identity，隐藏元数据
  * 会话容器网络命名空间中 `169.254.169.254` 的显式拒绝

  该块也适用于您的包装脚本和生命周期钩子，因为它们共享容器。使用[会话 JWT](/docs/zh-CN/self-hosted-environments-identity)针对您自己的令牌服务通过允许列表出站流量验证任何令牌交换，或使用基于文件的 Web 身份，例如 Amazon EKS 上的 IAM Roles for Service Accounts (IRSA)。
* **按运行器文件系统隔离**：每个运行器进程获得自己的工作目录，主机上的其他进程无法读取或写入。使 `--hooks-dir`、包装脚本和主机的 `~/.claude/` 对会话只读，无论是内置在镜像中还是以只读方式挂载。
* **分派没有按环境的访问控制**：您 Anthropic 组织的任何成员都可以向其任何环境分派会话。如果所有者[将 Claude Tag 频道路由到环境](/docs/zh-CN/cloud-environments#set-the-environment-a-claude-tag-channel-uses)，[Claude Tag 访问设置](https://claude.com/docs/claude-tag/admins/restrict-access#restrict-who-can-use-claude)允许的任何人都可以启动在那里运行的频道会话。默认情况下，这是连接的 Slack 工作区中的任何人，无论是否有 Claude 帐户。将每个运行器主机视为可由所有可以向其分派的人访问以执行代码，并仅在运行器主机上放置所有这些人都被允许读取的数据和凭证。[`--lock-to-account`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags)限制给定主机执行哪个帐户的会话，但它不会缩小谁可以分派到环境中。要使自托管环境成为唯一的选择器选项，[所有者](/docs/zh-CN/cloud-environments#organization-shared-environments)可以从[**云环境**页面](https://claude.ai/admin-settings/cloud-environments)为整个组织隐藏 Anthropic 托管的环境。
* **强制执行 repo-settings 保护**：使用 [`--confine-repo-settings`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags) 选择保护模式。默认的 `warn` 记录违规并仍然生成会话，`enforce` 拒绝会话，`off` 禁用扫描。运行器扫描每个存储库的提交设置以查找：

  * 在该会话自己的工作区之外解析的授予：`additionalDirectories` 条目、`permissions.allow` 中的 `Edit`、`Write` 或 `NotebookEdit` 规则，或 `sandbox.filesystem.allowWrite` 或 `allowRead` 条目
  * 非空的 `env` 块
  * 操作员态势覆盖，例如 `sandbox.enabled: false`

  无论 [`--trust-workspace`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags) 如何，保护都会运行，并且不涵盖存储库钩子、`.mcp.json` 或 Bash 规则；请参阅[权限和工具批准](/docs/zh-CN/self-hosted-environments-configuration#permissions-and-tool-approval)了解这些授予的位置。

<Note>
  您组织的 IP 允许列表默认不涵盖自托管运行器流量。不要将其作为运行器或会话流量的网络控制；而是在您自己的网络边界应用默认拒绝出站流量，如果您想为您的组织强制执行 IP 允许列表，请联系您的 Anthropic 帐户团队。
</Note>

<h2 id="network-requirements">
  网络要求
</h2>

运行器及其生成的会话子进程向以下主机进行出站连接。将会话容器出站流量限制为这些主机和会话需要到达的特定内部服务；[默认拒绝出站流量](#default-deny-egress)涵盖如何以及为什么。

这些主机始终是必需的：

| 主机 | 端口 | 用途 |
| :- | :- | :- |
| `api.anthropic.com` | 443，HTTPS；仅 SCM 连接器的 WSS | 运行器控制平面和会话流式传输、模型推理、功能标志、产品分析、[JWKS](/docs/zh-CN/self-hosted-environments-identity) 密钥获取、提交签名、设置 `--use-anthropic-git-proxy` 时的 git 代理，以及设置 `--scm-connector-host` 时编排器的 [SCM 连接器](/docs/zh-CN/self-hosted-environments-reference#scm-connector-flags)隧道 |
| 您的 git 主机，例如 `github.com` 或您的 GitHub Enterprise 主机 | 443 或 22 | 克隆和推送存储库。如果运行器使用 `--use-anthropic-git-proxy`（通过 `api.anthropic.com` 路由 git 流量）则不需要。 |

这些主机是否需要取决于您的配置：

| 主机 | 端口 | 何时需要 |
| :- | :- | :- |
| `downloads.claude.ai` | 443 | 在安装时，当您使用本机安装程序在主机上安装或更新 Claude Code 时；`install.sh` 脚本本身从 `claude.ai` 提供。在会话运行时，仅当会话从官方 Anthropic 市场安装插件时。 |
| `storage.googleapis.com` | 443 | 在会话运行时，用于 `/plugin` 中显示的插件安装计数和元数据。 |
| `code.claude.com` 和 `claude.com` | 443 | 内置 claude-code-guide 代理的文档查找和会话期间预批准的 WebFetch 请求。阻止这些主机仅影响文档查找。 |
| `*.frame.claudeusercontent.com` | 443 | 仅当[工件工具](/docs/zh-CN/artifacts#availability)对您组织中的会话可用时；默认值因计划而异，请参阅那里的可用性表。在运行器上设置 `CLAUDE_CODE_DISABLE_ARTIFACT=1` 以保持工具禁用，无论组织设置如何。 |
| `registry.npmjs.org` | 443 | 当会话安装插件时，用于获取 npm 源插件包和安装插件的 Node.js 依赖项，或当 `npx` 启动的 MCP 服务器运行时 |
| `http-intake.logs.us5.datadoghq.com` | 443 | Anthropic 操作指标。仅当设置 `CLAUDE_CODE_BYOC_ENABLE_DATADOG=1` 时；在自托管环境中默认关闭。 |
| `browser-intake-us5-datadoghq.com` | 443 | Anthropic 错误报告上传，仅在为会话帐户启用[错误报告](/docs/zh-CN/data-usage#telemetry-services)时发送。由 `DISABLE_ERROR_REPORTING=1` 或 `DISABLE_TELEMETRY=1` 抑制。 |

运行器不会到达 `statsig.anthropic.com`、`*.sentry.io`、`claude.ai` 或 `platform.claude.com`。这些主机出现在一些较旧的企业网络检查清单中，但您不需要为运行器或会话流量允许列表它们：功能标志获取转到 `api.anthropic.com`，运行器使用环境密钥而不是交互式 OAuth 进行身份验证。两个主机端流程确实到达 `claude.ai`，因此从其出站允许它的主机运行它们，而不是扩大会话容器出站流量：单行安装程序在安装时从 `claude.ai` 获取 `install.sh`，交互式 `claude auth login`（[引导设置](/docs/zh-CN/self-hosted-environments-quickstart#set-up-an-environment-and-runner)、`doctor` 的已登录模式和 [CI 分派](/docs/zh-CN/self-hosted-environments-testing#authenticate-from-ci)使用）通过 `claude.ai`、`claude.com` 和 `platform.claude.com` 登录。`mcp-proxy.anthropic.com` 也不是必需的：自托管会话不使用它，当为您的组织启用时，您组织的 claude.ai 连接器向会话的交付通过 `api.anthropic.com` 路由。请参阅 [MCP 服务器](/docs/zh-CN/self-hosted-environments-configuration#mcp-servers)。

<h3 id="default-deny-egress">
  默认拒绝出站流量
</h3>

在网络段或命名空间中部署运行器和会话容器，其出站流量限制为[网络要求表](#network-requirements)中的主机、您的 git 主机和会话需要到达的特定内部服务。该产品无法验证或强制执行此操作，因此在每个环境的您自己的网络边界应用它。会话代码是模型指导的，可以尝试连接到任意主机；网络层的默认拒绝出站流量限制这些尝试可以到达的位置。这适用于任何权限模式：默认预批准工具集已包括 `Bash`，因此 shell 出站流量在没有[自动模式](/docs/zh-CN/self-hosted-environments-configuration#permissions-and-tool-approval)的情况下运行而不提示。

有关每个会话发出的遥测详情以及如何关闭它，请参阅[遥测](/docs/zh-CN/self-hosted-environments-reference#telemetry)。

<h3 id="authenticate-to-an-egress-proxy">
  向出站代理进行身份验证
</h3>

某些企业出站代理在每个连接上需要 `Proxy-Authorization` 标头。该标头中的令牌通常轮换太快而无法写入您在 `HTTPS_PROXY` 中设置的代理 URL。像往常一样将 `HTTPS_PROXY` 或 `HTTP_PROXY` 设置为您的代理 URL，然后设置 `--proxy-authorization-command` 或 `--proxy-authorization-file` 以告诉运行器从何处读取标头值。两个标志都需要 Claude Code v2.1.238 或更高版本。

<h4 id="choose-where-the-proxy-authorization-value-comes-from">
  选择 `Proxy-Authorization` 值的来源
</h4>

选择与您生成 `Proxy-Authorization` 令牌的方式相匹配的标志：

* **[`--proxy-authorization-command <command>`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags)**：为您按需生成的令牌选择此选项。运行器运行 shell 命令并使用其修剪的 stdout 作为标头值，例如 `Bearer <token>`。
* **[`--proxy-authorization-file <path>`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags)**：为另一个进程轮换到位的令牌选择此选项。运行器读取文件并使用其修剪的内容作为标头值。

<h4 id="configurations-the-runner-refuses-to-start-with">
  运行器拒绝启动的配置
</h4>

每个标志也有一个环境变量形式，在[运行器 CLI 标志参考](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags)中列在其旁边。在运行器联系您的代理或控制平面之前，它检查标志及其变量，并在三种情况下拒绝启动：

* **两个标志都设置**：一个标志加上另一个标志的环境变量计为设置两个。
* **没有代理 URL**：`HTTPS_PROXY` 和 `HTTP_PROXY` 都不包含 `http://` 或 `https://` URL。运行器以大写或小写读取两个变量，不查询 `ALL_PROXY`。
* **任一标志传递给编排器子命令**：`self-hosted-runner orchestrator` 不接受标志或其环境变量。改为将标志传递给编排器启动的每个运行器。

<h4 id="what-the-runner-changes-while-a-proxy-authorization-flag-is-set">
  设置代理授权标志时运行器更改的内容
</h4>

设置任一标志后，运行器启动自己的侦听器并通过该侦听器发送来自自身、其生命周期钩子和其会话的代理流量。侦听器在到达您的代理的途中添加 `Proxy-Authorization` 标头。

* **侦听器**：侦听器是 `127.0.0.1` 上的转发代理。运行器在向控制平面注册之前启动侦听器，如果侦听器无法启动则在启动时退出。
* **代理变量**：运行器重写您设置的 `HTTPS_PROXY` 和 `HTTP_PROXY` 中的任何一个，使其指向侦听器。该重写的值到达运行器本身、其生命周期钩子和它运行的每个会话。
* **令牌轮换**：轮换的令牌无需重启即可生效。对于侦听器打开到您的代理的每个连接，运行器再次运行您的命令或读取您的文件并将结果添加为标头。
* **会话环境**：会话仅通过侦听器到达您的代理。在每个会话的环境中，运行器删除 `ALL_PROXY`，删除您未设置的 `HTTPS_PROXY` 或 `HTTP_PROXY` 的任何拼写，并将 `NO_PROXY` 固定到运行器自己的值。
* **日志**：运行器从不记录标头值。

<h2 id="configure-git">
  配置 git
</h2>

运行器管理存储库检出但默认不配置 git 身份或凭证。您控制运行器的镜像和进程环境，因此您控制 git 配置。选择两种方法之一：

* **让运行器配置 git**：使用 `--configure-git` 启动运行器，使其写入 Anthropic 托管会话使用的相同身份和提交签名配置
* **在镜像中提供 git 配置**：自己设置身份和推送凭证，例如在您自己的机器人身份下提交

运行器主机上的 Git 版本下限：[`--configure-git`](#let-the-runner-configure-git) SSH 提交签名需要 Git 2.34 或更高版本，[`--use-anthropic-git-proxy`](#use-the-anthropic-git-proxy) 需要 2.32 或更高版本，从 [`--push-outcome-on-release`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags) 推送的分支恢复会话需要 2.29 或更高版本。如果您省略所有三个并自己管理 git 身份，Git 2.24 就足够了。

<h3 id="let-the-runner-configure-git">
  让运行器配置 git
</h3>

使用 `--configure-git` 启动运行器，或设置 `SELF_HOSTED_RUNNER_CONFIGURE_GIT=1`，使其在启动时写入全局 git 配置：

* `user.name = Claude` 和 `user.email = noreply@anthropic.com`，与 Anthropic 托管会话匹配
* SSH 格式提交和标签签名，通过运行器管理的垫片路由，使用会话自己的凭证通过 Anthropic 的签名服务签署每个提交。签名可在 GitHub 上针对 Anthropic 的已发布 SSH 签名密钥进行验证。
* `push.negotiate = true`，所以 git 在打包推送之前询问您的 git 主机它已经拥有哪些提交。需要 Claude Code v2.1.257 或更高版本。
* `core.hooksPath` 指向运行器管理的钩子目录。其 `commit-msg` 和 `prepare-commit-msg` 钩子为每个提交添加 `Co-authored-by:` 预告片，用于会话的创建者，从 [`CCR_SESSION_ACCOUNT_EMAIL`](/docs/zh-CN/self-hosted-environments-configuration#wrapper-scripts) 构建，当该变量未设置时省略。如果您的镜像已设置 `core.hooksPath`，运行器保留您的设置，跳过安装这些钩子，并打印 `[runner:git]` 警告。

提交签名需要 git 2.34 或更高版本；运行器在启动时检查并在您的 git 较旧时以错误退出。此标志不配置推送凭证，您仍然在镜像中提供。

<h3 id="ship-git-config-in-your-image">
  在镜像中提供 git 配置
</h3>

git 身份对任何提交都是必需的。在您的 Dockerfile 中系统范围设置它，以便配置适用于运行器进程运行的任何用户：

```dockerfile theme={null}
RUN git config --system user.name "Claude" && \
    git config --system user.email "noreply@anthropic.com"
```

没有身份，`git commit` 失败并显示 `Please tell me who you are`，会话无法取得进展。您可以改用自己的机器人身份；运行器不会覆盖这些值。

不要将长期或广泛范围的推送凭证烘焙到共享运行器镜像中：镜像中的凭证可用于镜像运行的每个会话，无论谁启动它。相反，从您的[包装脚本](/docs/zh-CN/self-hosted-environments-configuration#wrapper-scripts)按会话铸造短期、最小范围的令牌，使用从会话 JWT 解码的会话创建者的身份。将其与临时的按会话容器配对，这需要 `--capacity 1`，因此没有凭证超过铸造它的会话；请参阅[加固部分](#harden-your-deployment)。

如果您必须在镜像级别配置推送凭证，例如对于只读部署密钥，请尽可能紧密地限制它们：

* SSH 部署密钥限制为一个存储库，带有 `url.<base>.insteadOf` 重写
* 返回最小范围令牌的 `credential.helper`
* `GIT_SSH_COMMAND` 指向狭义范围的密钥

您配置的任何机制都必须无需提示即可工作，因为运行器的内置克隆和获取禁用 git、SSH 和 Git Credential Manager 否则会显示的提示：

* 运行器设置 `GIT_TERMINAL_PROMPT=0`，所以 git 不要求用户名或密码。
* 运行器使用 `BatchMode=yes` 运行 SSH，如果您设置了一个，则附加到您的 `GIT_SSH_COMMAND`，所以 SSH 不要求密码短语或主机确认。
* 运行器设置 `GCM_INTERACTIVE=never`，所以 Git Credential Manager 不打开登录对话框。
* 运行器清除 `core.askPass`，所以如果您使用 askpass 助手，改为通过 `GIT_ASKPASS` 环境变量设置它。

如果您的 git 主机拒绝凭证，或您没有配置凭证，运行器重试几次然后失败存储库准备（当存储库是会话推送结果的存储库时）。对于会话仅从中读取的存储库，[故障排除](#troubleshooting)涵盖运行器何时改为跳过它。运行器不会将这些设置传递到会话的环境中。

保持您在 `GIT_SSH_COMMAND` 或 `GIT_ASKPASS` 中命名的任何程序，会话无法写入它，就像[加固清单](#harden-your-deployment)要求钩子目录和包装脚本的方式一样。该程序命令行上的任何密钥或文件也是如此。运行器自己的 git 在克隆或获取时运行该程序。

如果检出目录由与运行器进程不同的 uid 拥有，git 拒绝对其进行操作；添加 `safe.directory`：

```dockerfile theme={null}
RUN git config --system --add safe.directory '*'
```

<h3 id="use-the-anthropic-git-proxy">
  使用 Anthropic git 代理
</h3>

使用 `--use-anthropic-git-proxy` 启动运行器，或设置 `CLAUDE_RUNNER_USE_GIT_PROXY=1`，使其通过 Anthropic 的 git 代理克隆，使用会话自己的短期令牌进行身份验证。对于普通用户会话，代理使用为会话创建者存储的 GitHub 或 GitHub Enterprise OAuth 令牌；对于机器人和代理会话，它使用您组织的 GitHub App 安装令牌。无论哪种方式，运行器镜像根本不需要 git 凭证：没有 SSH 密钥、没有凭证助手、没有 `.netrc`。这是 Anthropic 托管环境使用的相同身份验证路径。

代理需要 `--capacity 1`，因为代理 URL 是按会话的，以及 git 2.32 或更高版本，因为较旧的 git 忽略代理用来隔离会话的配置机制。如果任一要求未满足，运行器拒绝启动。因为代理从 Anthropic 端获取，您的 git 主机必须可从 Anthropic 基础设施到达，与 Anthropic 托管会话相同的要求；对于仅在您的网络内可路由的 git 主机，改用 [`checkout` 生命周期钩子](/docs/zh-CN/self-hosted-environments-configuration#checkout)。每个运行器进程一次处理一个会话，因此运行更多副本以获得并行性。启用代理后，`--git-host-rewrite` 和 `--git-ssh-rewrite` 无效：代理 URL 指向 `api.anthropic.com`，而不是您的 git 主机。

<Warning>
  本页上的 [Kubernetes](#kubernetes) 和 [Docker Compose](#docker-compose) 配方使用 `--capacity 4`。如果您在不将容量更改为 `1` 的情况下向其中一个添加 `--use-anthropic-git-proxy` 或 `CLAUDE_RUNNER_USE_GIT_PROXY=1`，每次您的编排器重新启动它时，运行器都会在启动时退出。设置 `--capacity 1` 并运行更多副本以获得并行性。[当运行器退出](#when-the-runner-exits)显示运行器打印的行。
</Warning>

运行器还在注册时向 Anthropic 报告选择加入，在启动时打印 `Registering as opted in to Anthropic-managed git (--use-anthropic-git-proxy)`。报告选择加入需要 Claude Code v2.1.267 或更高版本，较早的版本接受该标志而不报告它或打印该行。选择加入运行器上的每个会话然后使用 Anthropic 管理的 git 或按会话代理 URL。当会话使用按会话代理 URL 时，运行器记录一行 `[runner:warn]` 说明这一点。

<h4 id="trust-a-private-certificate-authority-with-anthropic-managed-git">
  使用 Anthropic 管理的 git 信任专用证书颁发机构
</h4>

如果您在运行器的环境中设置 `GIT_SSL_CAINFO` 或 `GIT_SSL_NO_VERIFY`，其会话使用 Anthropic 管理的 git，本部分适用。它描述的处理需要运行器运行 Claude Code v2.1.283 或更高版本。

当运行器上的 git 必须信任专用证书颁发机构 (CA)（例如 TLS 检查代理签署的证书颁发机构）时，通常的方法如下所示：

* **系统证书存储**：在运行器主机的系统证书存储中安装您的 CA，git 无需任何变量即可信任它。
* **`GIT_SSL_CAINFO`**：将其设置为您的 CA 的 PEM 文件，例如 `GIT_SSL_CAINFO=/etc/ssl/corp-ca.pem`。
* **`GIT_SSL_NO_VERIFY`**：在重新签名代理后面没有帮助。运行器自己通过 Anthropic 管理的 git 克隆检查证书，即使设置了变量，所以克隆失败，直到 git 通过其他两种方法之一信任您的 CA。

对于将会话令牌传送到 Anthropic 管理的 git 的 git 连接，运行器应用这两个变量如下。[`command` 钩子](/docs/zh-CN/self-hosted-environments-configuration#command)以会话的环境开始，所以它获得 git 在会话内获得的内容：

* **`GIT_SSL_CAINFO`**：git 检查 Anthropic 管理的 git 的内容取决于 git 运行的位置：
  * **运行器自己的克隆和获取**：运行时不使用变量，并根据运行器写入的按会话证书文件检查 Anthropic 管理的 git。该文件保存运行器主机的系统 CA 包加上您的文件中的证书。
  * **会话内的 Git**：获得 `http.sslCAInfo` 配置，命名您的文件代替变量，加上 `http.<url>.sslCAInfo` 条目，根据按会话文件检查 Anthropic 管理的 git。
  * **`checkout` 和 `post-session` 钩子**：继承变量不变。
* **`GIT_SSL_NO_VERIFY`**：哪些证书检查保持关闭取决于 git 运行的位置：
  * **运行器自己的克隆和获取**：运行时不使用变量，并检查它们呈现的证书。
  * **会话内的 Git**：获得 `http.sslVerify=false` 配置代替变量，所以检查对其他主机保持关闭。它还获得 `http.<url>.sslVerify=true` 条目，为 Anthropic 管理的 git 保持检查打开。
  * **`checkout` 和 `post-session` 钩子**：当会话在 Anthropic 管理的 git 上有存储库时，获得 `http.sslVerify=false` 配置代替变量。它们还获得 `http.<url>.sslVerify=true` 条目，为 Anthropic 管理的 git 保持检查打开。

按会话证书文件需要在运行器主机上的 `/etc/ssl/certs/ca-certificates.crt` 或 `/etc/pki/tls/certs/ca-bundle.crt` 处的系统 CA 包。它还需要一个 `GIT_SSL_CAINFO` 文件，运行器的用户可以读取，保存 PEM `CERTIFICATE` 块，最多 1 MiB。当运行器无法构建按会话文件时，它记录一行 `[runner:warn]` 包含 `did not build the certificate file` 和原因。Git 然后按原样为 Anthropic 管理的 git 使用您的文件。修复该行命名的内容。

对于使用 Anthropic 管理的 git 的每个会话，运行器还记录一行 `[runner:warn]` 开始于 `governed git: GIT_SSL_CAINFO is set` 或 `governed git: GIT_SSL_NO_VERIFY is set`。该行说明运行器对其自己的 git、会话内的 git 和您的生命周期钩子对该变量所做的操作。它以您是否需要更改任何内容结束。

<h3 id="rewrite-git-urls-for-private-networks">
  为专用网络重写 git URL
</h3>

存储库 URL 从控制平面作为 HTTPS 到达，带有您的 git 主机的主机名；对于 GitHub Enterprise，这是您在 claude.ai 上的 Claude Code 管理设置中为 [GitHub Enterprise 集成](/docs/zh-CN/github-enterprise-server)配置的主机名。两个可重复的标志在克隆之前重写这些 URL：

* `--git-host-rewrite <from>=<to>`：对于分割视界 DNS，其中 Anthropic 通过外部主机名到达您的 git 主机，但运行器必须使用内部主机名
* `--git-ssh-rewrite <host>`：对于仅接受 SSH 的 git 主机，将 `https://<host>/owner/repo` 重写为 `git@<host>:owner/repo`

主机重写首先运行，因此如果您需要两者，请在 `--git-ssh-rewrite` 中列出内部主机名。为了完全控制检出，使用 [`checkout` 生命周期钩子](/docs/zh-CN/self-hosted-environments-configuration#checkout)。

<h2 id="build-the-runner-image">
  构建运行器镜像
</h2>

Anthropic 不发布预构建的运行器镜像。围绕 `claude` 二进制文件构建您自己的，分层您的存储库需要的任何工具链：语言运行时、编译器、包管理器和 [MCP](/docs/zh-CN/mcp) 边车。

下面的配方使用 `--capacity 4`，所以一个容器为来自同一锁定所有者的最多四个并发会话服务。这不提供[加固部分](#harden-your-deployment)中的按会话容器隔离：在将环境连接到生产系统之前，要么以 `--capacity 1` 运行配方，每个会话一个容器，要么使用[按需运行器](/docs/zh-CN/self-hosted-environments-configuration#on-demand-runners)，它也将环境密钥保持在会话运行主机之外。如果您将[Anthropic git 代理](#use-the-anthropic-git-proxy)添加到这些配方之一，也要将 `--capacity` 更改为 `1`。

这个 Dockerfile 是一个最小的起点：

```dockerfile theme={null}
FROM debian:bookworm-slim
ARG CLAUDE_CODE_VERSION
RUN apt-get update && apt-get install -y --no-install-recommends git curl ca-certificates openssh-client \
 && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL "https://downloads.claude.ai/claude-code-releases/${CLAUDE_CODE_VERSION:?set with --build-arg CLAUDE_CODE_VERSION}/linux-x64/claude" \
      -o /usr/local/bin/claude && chmod +x /usr/local/bin/claude
RUN git config --system user.name "Claude" \
 && git config --system user.email "noreply@anthropic.com" \
 && git config --system --add safe.directory '*'
ENTRYPOINT ["claude"]
```

如果您的节点是 ARM，将 `linux-x64` 交换为 `linux-arm64`，或在 Alpine 等 musl 基础镜像上交换为 `linux-x64-musl` 或 `linux-arm64-musl`；请参阅 [Alpine Linux 设置](/docs/zh-CN/setup#alpine-linux-and-musl-based-distributions)了解 musl 镜像需要的额外包。URL 是标准 Claude Code 发布位置，因此您可以根据[二进制完整性和代码签名](/docs/zh-CN/setup#binary-integrity-and-code-signing)中描述的发布的已签名清单验证下载的二进制文件。运行器需要 Claude Code 版本 2.1.224 或更高版本。构建镜像，然后将其推送到您的注册表并在下面的配方中引用它：

```bash theme={null}
docker build \
  --build-arg CLAUDE_CODE_VERSION="$(curl -fsSL https://downloads.claude.ai/claude-code-releases/stable)" \
  -t <your-registry>/claude-runner:latest .
```

命令替换查找当前 `stable` 发布号并将其作为构建参数传递，因此在新的稳定版本发布后运行相同的命令会使用较新的二进制文件重建下载层。要为可重现的构建固定特定版本，请直接将版本号作为 `CLAUDE_CODE_VERSION` 传递。当您需要比稳定通道更新的版本（例如[新推出的模型所需的版本](/docs/zh-CN/model-config)）时，在查找 URL 中将 `stable` 替换为 `latest`。

<h2 id="size-cpu-and-memory-for-sessions">
  为会话调整 CPU 和内存大小
</h2>

为运行器运行的会话而不是运行器进程调整运行器的容器或主机大小。运行器本身轮询工作、准备每个会话的检出、运行您的[生命周期钩子](/docs/zh-CN/self-hosted-environments-configuration#lifecycle-hooks)，以及启动和监督会话进程。负载来自会话：每个都是一个 Claude Code 进程加上它启动的任何东西，例如构建、测试套件、包安装和 [MCP 服务器](/docs/zh-CN/mcp)。

对于一个会话，从以下值开始，表示为 Kubernetes 请求和限制或您平台的等效值，并将它们视为起点而不是要求：

* **内存**：请求和限制各 4 GiB，满足 Claude Code [系统要求](/docs/zh-CN/setup#system-requirements)中的 4 GB 最小值。保持两者相等，以便调度程序考虑容器的完整内存。当容器达到其内存限制时，内核杀死其中的进程，这可能会结束会话中途。
* **CPU**：请求 2 个 CPU，限制 4 个 CPU，所以会话可以在构建期间突发超过请求。内核在其 CPU 限制处限制容器，而不是杀死其中的进程，所以会话在限制处运行较慢但继续运行。

在 Kubernetes 容器规范中，使用以下 `resources` 块设置这些起始值：

```yaml theme={null}
resources:
  requests:
    cpu: "2"
    memory: 4Gi
  limits:
    cpu: "4"
    memory: 4Gi
```

构建和测试通常是会话负载中最大和最可变的部分，因此运行您的存储库的代表性构建，测量其峰值 CPU 和内存，并提高任何在该峰值之上没有为 Claude Code 进程留出空间的起始值。

运行器使用 `--capacity` 来限制它一次运行多少个会话。它不在它们之间分割 CPU 或内存，所以运行器上的会话共享容器的 CPU 和内存。要限制一个会话的份额，从您的[包装脚本](/docs/zh-CN/self-hosted-environments-configuration#wrapper-scripts)应用限制。因此，给一个容器什么取决于它一次服务多少个会话：

* **每个运行器一个会话**：给每个容器一个会话的值。在 `--capacity 1` 使用此大小，[加固部分](#harden-your-deployment)推荐，以及对于[按需运行器](/docs/zh-CN/self-hosted-environments-configuration#on-demand-runners)，您在您的 [`spawn-runner` 钩子](/docs/zh-CN/self-hosted-environments-configuration#the-spawn-runner-hook)提交的工作负载上设置值，例如 Kubernetes Job 的 pod 模板。
* **每个运行器多个会话**：在 `--capacity` 高于 1 时，将一个会话的值乘以容量，因为最多那么多会话可以在容器中同时运行。[Kubernetes](#kubernetes) 和 [Docker Compose](#docker-compose) 配方以 `--capacity 4` 运行，没有 CPU 或内存限制，因此添加为您运行的容量调整大小的限制。

<h2 id="kubernetes">
  Kubernetes
</h2>

运行器默认在端口 8080 上提供 `GET /healthz`，可使用 `--health-port` 配置，因此 Kubernetes 探针无需额外设置即可工作。端点在进程活着时返回 `200`，所以下面的探针检测死进程，而不是卡住的进程；要捕获停止轮询的运行器，请在 [`/metrics`](/docs/zh-CN/self-hosted-environments-reference#prometheus-metrics) 的 `last_poll_age_seconds` 系列上发出警报。下面的 Deployment 从 Kubernetes Secret 挂载环境密钥，将活跃度和就绪探针指向 `/healthz`，并设置 90 秒的终止宽限期。请参阅[关闭时序](#shutdown-timing)了解为什么宽限期很重要。

清单在运行器容器上设置没有 CPU 或内存 `resources`。添加为您运行的容量调整大小的块，如[为会话调整 CPU 和内存大小](#size-cpu-and-memory-for-sessions)所述。

```yaml theme={null}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: claude-runner
  namespace: claude-runners
spec:
  replicas: 3
  selector:
    matchLabels:
      app: claude-runner
  template:
    metadata:
      labels:
        app: claude-runner
        app.kubernetes.io/part-of: claude-code-self-hosted-runner
    spec:
      terminationGracePeriodSeconds: 90
      containers:
        - name: runner
          image: <your-registry>/claude-runner:latest
          args:
            - self-hosted-runner
            - --environment-secret-file
            - /etc/claude/environment-secret
            - --capacity
            - "4"
          volumeMounts:
            - name: environment-secret
              mountPath: /etc/claude
              readOnly: true
          ports:
            - name: health
              containerPort: 8080
          readinessProbe:
            httpGet:
              path: /healthz
              port: 8080
            initialDelaySeconds: 5
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /healthz
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 30
      volumes:
        - name: environment-secret
          secret:
            secretName: claude-runner-environment-secret
```

上面的 Deployment 存在于 `claude-runners` 命名空间中。首先创建命名空间：

```bash theme={null}
kubectl create namespace claude-runners
```

从保存您在管理 UI 的[**复制环境密钥**步骤](/docs/zh-CN/self-hosted-environments-quickstart#set-up-an-environment-and-runner)中复制的值的本地文件创建支持 Secret，以便密钥永远不会出现在您的 shell 历史记录中。运行 `(umask 077 && cat > ./environment-secret)`，粘贴密钥，按 Enter，然后按 Ctrl-D。然后创建 Secret 并删除文件：

```bash theme={null}
kubectl create secret generic claude-runner-environment-secret -n claude-runners --from-file=environment-secret=./environment-secret
```

<h2 id="docker-compose">
  Docker Compose
</h2>

下面的 Compose 服务在运行器退出时重启它，这涵盖崩溃和正常退出后的 drain。Docker 重启策略重启同一容器及其可写层完整，所以运行器以重用的文件系统而不是[加固态势](#harden-your-deployment)推荐的新文件系统回来；为评估使用此配方，对于生产要么每次运行重新创建容器，要么使用执行此操作的编排器。

Docker 在容器不断退出时会在每次重启前等待更长时间，直到达到上限，所以在此配方下无法启动的运行器不会在紧密循环中不断重启。[当运行器退出时](#when-the-runner-exits)描述了发生这种情况时要检查的内容。

```yaml theme={null}
services:
  claude-runner:
    image: <your-registry>/claude-runner:latest
    command:
      - self-hosted-runner
      - --environment-secret-file
      - /run/secrets/environment-secret
      - --capacity
      - "4"
    secrets:
      - environment-secret
    restart: always
    stop_grace_period: 90s

secrets:
  environment-secret:
    file: ./environment-secret
```

<h2 id="shutdown-timing">
  关闭时序
</h2>

在 `SIGTERM` 上，运行器停止接收新工作，除非你设置了 [`--defer-shutdown-max-min`](#defer-the-drain-past-the-first-signal)，否则最多等待 `--drain-wait-sec`（默认为零）以完成进行中的轮次，终止每个会话的进程树，并运行 [`post-session` 生命周期钩子](/docs/zh-CN/self-hosted-environments-configuration#post-session)。该进程树包括 Claude 仍在会话中运行的命令。

完整的排空路径最多需要 `--session-stop-grace-sec` + `--drain-wait-sec` + `--post-session-hook-timeout-sec`，加上 15 秒的固定进程清理开销，加上当设置 [`--push-outcome-on-release`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags) 时的额外 30 秒。在默认设置下为 80 秒，运行器在启动时记录总时间。会话在这一个预算下并行排空，因此总时间不会随 `--capacity` 增长。

在默认的 `--drain-wait-sec 0` 下，滚动重启会中断进行中的轮次；每个会话在另一个运行器上恢复，丧失未推送的工作，如 [已知问题](#additional-limitations) 中所述。设置 `--drain-wait-sec`，并提高宽限期以匹配，以让轮次先完成。

在整个路径中，运行器以零容量持续向控制平面发送心跳，因此会话租约不会过期并被重新排队到另一个运行器，同时 `post-session` 钩子仍在写出未提交的工作。心跳在运行器注销前停止。

在主机停止运行器之前，至少给运行器启动时记录的总时间。你在哪里设置这个取决于你的主机如何停止：

* **使用 `SIGTERM` 宽限期**：在 Kubernetes 上设置 `terminationGracePeriodSeconds`，在 Docker Compose 上设置 `stop_grace_period`，或你的编排器的等效项至少为该总时间。Kubernetes 的默认值 30 秒短于运行器的排空路径，因此 Kubernetes 在运行器完成排空前停止 pod。
* **使用 [`--retire-at`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags)**：调整退休时间和主机停止时间之间的边距以覆盖典型轮次，加上 [Runner lifecycle](/docs/zh-CN/self-hosted-environments#runner-lifecycle) 描述的后台任务保持，加上相同的总时间。在每次启动时计算退休时间，例如 `date +%s` 加上运行器的预期生命周期。
* **使用 [`--defer-shutdown-max-min`](#defer-the-drain-past-the-first-signal)**：向排空路径总时间添加两个额外部分。第一个是你配置的分钟数。第二个是 [Defer the drain past the first signal](#defer-the-drain-past-the-first-signal) 描述的发布后宽限期，默认为 75 秒。设置该标志后，运行器也会在启动时打印组合数字，在排空路径总时间之后。

<h3 id="defer-the-drain-past-the-first-signal">
  延迟排空超过第一个信号
</h3>

如果你想要重启的运行器继续为其持有的会话服务长达 `n` 分钟，而不是在第一个信号上排空它们，请设置 [`--defer-shutdown-max-min <n>`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags)。在第一个 `SIGTERM` 或 `SIGINT` 上，运行器停止接收新工作并继续为其持有的会话服务。它持续轮询，以便控制平面不会重新排队这些会话。需要 Claude Code v2.1.238 或更高版本。

<h4 id="what-happens-to-the-sessions-the-runner-holds-after-the-first-signal">
  第一个信号后运行器持有的会话会发生什么
</h4>

在信号后的前两个阶段，运行器释放会话，释放的会话在其用户发送下一条消息时在新运行器上恢复。从第一个信号开始计数，运行器经过三个阶段：

* **在前 `n` 分钟内**：运行器正常为其会话服务，并继续强制执行 `--startup-timeout-min` 和 `--kill-session-after-min`。如果你也设置了 [`--release-idle-session-min`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags)，运行器会释放任何用户空闲该长时间的会话；没有它，空闲会话保留在运行器上。
* **当 `n` 分钟用完时**：运行器释放它仍然持有的每个会话，无论是否空闲。运行器等待中途轮次的轮次结束，以及最多 60 秒的轮次后台任务，然后释放该会话。
* **当发布后宽限期用完时**：运行器排空它仍然持有的任何会话，控制平面立即将每个排空的会话重新排队到另一个运行器。发布后宽限期从 `n` 分钟用完时开始，默认为 75 秒。如果你设置 `--drain-wait-sec` 超过 60 秒，发布后宽限期是 `--drain-wait-sec` 加 15 秒。

在任何阶段，运行器一旦不持有任何会话就以 0 退出。第二个信号缩短阶段：运行器立即排空，就像在没有 `--defer-shutdown-max-min` 的第一个信号上一样。一旦排空开始，下一个信号强制退出运行器。这适用于第二个信号或发布后宽限期用完是否启动了排空。

<h4 id="size-the-stop-timeout">
  调整停止超时
</h4>

给你的主机停止超时至少三个部分的总和：你配置的 `n` 分钟、发布后宽限期和 [Shutdown timing](#shutdown-timing) 描述的完整排空路径。使用默认设置，发布后宽限期为 75 秒，排空路径为 80 秒，因此允许 `n` 分钟加 155 秒。当设置 `--defer-shutdown-max-min` 时，运行器在启动时打印此总和。

如果停止超时在运行器完成前用完，主机会杀死运行器。它仍然持有的会话不会获得 `post-session` 钩子。运行器不会注销，控制平面大约一分钟后重新排队会话。如果你无法给停止超时该总和，请不设置 `--defer-shutdown-max-min`，以便运行器在第一个信号上排空。

<h3 id="what-reaches-a-running-post-session-hook">
  什么到达运行的 post-session 钩子
</h3>

`post-session` 钩子和 Claude 会话子进程各自在自己的 POSIX 进程组中运行，与运行器分离，因此停止机制以不同方式到达它们：

* **运行器已在排空时的 `SIGTERM`**：立即强制退出运行器，跳过排空路径的任何剩余部分。没有 [`--defer-shutdown-max-min`](#defer-the-drain-past-the-first-signal)，那是运行器接收的第二个 `SIGTERM`。没有信号发送到正在运行的 `post-session` 钩子，因此在采用孤儿的初始进程的裸主机上，它自行完成，但不受监督：其超时预算不再适用，写入关闭的日志管道可以用 `SIGPIPE` 杀死它，因此需要在强制退出时存活的钩子应该将其自己的输出重定向到文件。在此页面的容器配方中，运行器是容器的 PID 1，其退出结束容器，在 systemd 的默认 `KillMode=control-group` 下，cgroup 范围的杀死到达钩子，如 **Cgroup-wide kills** 条目所述；在两者中，将强制退出视为对钩子致命，并依赖宽限期。
* **进程组范围的信号**，例如包装脚本中的 `kill -- -<pid>`、shell 作业控制或组范围的看门狗：到达运行器和正在进行的 `checkout` 钩子子进程（故意保持组附加），但不到达正在运行的 `post-session` 钩子或会话子进程。
* **Cgroup 范围的杀死**，例如 systemd 的默认 `KillMode=control-group` 或当 `terminationGracePeriodSeconds` 过期时 Kubernetes 传递给整个容器的 `SIGKILL`：到达一切，包括钩子。进程组隔离不能防止这些，这就是为什么宽限期必须覆盖完整排空路径。
* **钩子自己的超时**：当钩子超过 `--post-session-hook-timeout-sec` 时，运行器向钩子的整个进程组发送 `SIGTERM`，然后两秒后发送 `SIGKILL`，因此钩子分叉的工作进程（例如 tar、rsync 或 git）与包装 shell 一起终止，而不是作为孤儿存活。运行器的监督在钩子的 stdio 关闭后结束：将其自己的输出重定向到文件并在 `SIGTERM` 阶段后存活的工作进程超出运行器的范围。

当排空开始时，以及在强制退出时，运行器记录仍在运行的 `post-session` 钩子数量，因此你可以区分安静的排空和正在进行快照的排空。

<h2 id="keep-the-base-directory-and-capacity-identical-across-runners">
  在运行器之间保持基目录和容量相同
</h2>

如果运行器在会话中途死亡，服务器重新排队会话，环境中的另一个运行器获取它。该运行器从其自己的 `--base-dir` 和 `--capacity` 派生检出路径：`--capacity 1` 直接在 `--base-dir` 下检出，`--capacity` 高于 1 改用按会话 worktrees。当同一环境中的运行器对任一标志使用不同的值时，恢复的会话的工作目录更改，代理之前记录的绝对路径（在编辑、工具调用或其自己的笔记中）指向不再存在的位置。

在环境中的每个运行器上使用相同的 `--base-dir` 和 `--capacity`，并且不要使用按主机的值，例如实例 ID 或主机名。

基目录默认为 `/workspace`，除了 [`--base-dir` 参考行](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags)记录的例外。运行器需要对其的写入访问。在启动时，在注册之前，运行器创建目录并确认它可以写入，当它不能时以 `cannot create or write to base directory` 退出。以 root 身份启动的运行器自己创建默认 `/workspace`。对于非 root 运行器，在启动运行器之前创建目录并给运行器的用户所有权，或将 `--base-dir` 指向该用户已拥有的目录。

<h2 id="reuse-a-pre-warmed-checkout">
  重用预热的检出
</h2>

对于大型仓库，克隆可能会主导会话启动。在 `--capacity 1` 且没有 [`checkout` hook](/docs/zh-CN/self-hosted-environments-configuration#checkout) 的情况下，运行器在 `<base-dir>/<repo-owner>/<repo>` 处为每个仓库保持一个规范克隆，并在会话间重用它：它获取请求的引用，分离 `HEAD`，并硬重置到该引用，当变化不大时这几乎是瞬间完成的。要跳过冷克隆，可以通过以下两种方式之一提供克隆：

* **在镜像中克隆**：在该路径处将克隆构建到运行器镜像中。每个新容器随后都会以预热克隆启动，而无需重用磁盘。
* **在持久卷上克隆**：在使用 [`--lock-to-account`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags) 预锁定到一个用户账户的运行器上，将 `--base-dir` 指向持久卷，这样磁盘只为该账户服务。预锁定的运行器永远不会接收 Claude Tag 频道会话，因此此选项不适用于为其服务的运行器。

重用路径的保证和不保证的内容：

* **任何克隆形状都可以工作**：路径处的完整、浅层或单分支克隆按原样使用。运行器在获取到现有克隆时永远不会传递 `--depth`，因此完整的预热保持其完整历史，浅层克隆保持浅层。`CLAUDE_RUNNER_FETCH_DEPTH`（`full`、`0` 或一个数字；默认 50）仅控制当尚不存在克隆时运行器进行的冷克隆。
* **跟踪的更改重置，未跟踪的文件保留**：每个会话从硬重置开始，该重置会清除前一个会话的跟踪修改，但运行器永远不会运行 `git clean`，因此来自锁定所有者早期会话的未跟踪文件保留在树中。
* **按会话目录也会保留**：在检出旁边，运行器在 `<base-dir>/_sessions/` 下为其运行的每个会话创建按会话条目。会话的 Claude 配置目录保存对话记录的本地副本。在其旁边是会话的上传文件，当会话有任何文件时。会话目录也在那里：它保存会话运行时的任何按会话工作树和 `checkout` hook 检出，以及 Claude 在其中写入的任何其他内容。

  默认情况下，运行器在会话结束时将这些保留在原地，因此在持久化的磁盘上它们会累积。每个会话都以运行器自己的用户身份运行，因此该磁盘服务的任何后续会话都可以读取它们。如果保持持久的 `--base-dir`，请为该增长调整卷的大小。同样适用于在同一文件系统上重启运行器的任何设置，包括 [Docker Compose 配方](#docker-compose)。
* **使用 `--remove-session-state` 时，按会话目录不会保留**：使用 [`--remove-session-state`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags) 启动运行器，以便在会话结束时删除每个会话的按会话目录。删除是尽力而为的：当运行器在清理运行前被杀死时，目录保留。规范克隆和会话在主机上其他地方写入的文件，例如临时目录，无论如何都会保留。
* **使用 git 代理时，重置变成检出**：使用 [`--use-anthropic-git-proxy`](#use-the-anthropic-git-proxy)，运行器在每个会话前清理克隆的 `.git/`，保留对象存储、引用和浅层状态，但删除索引，因此每个会话需要进行完整的工作树检出而不是近乎瞬间的重置；它仍然永远不会重新克隆。代理下不支持子模块预热。
* **长克隆不需要解决方法**：运行器使用 120 秒无进度监视器和 30 分钟硬上限来限制每个 git 操作，而不是平面超时，因此保持报告进度的缓慢冷克隆会完成。

<h2 id="pin-the-version">
  固定版本
</h2>

每个会话的子 Claude Code 进程运行运行器自己的二进制文件，运行器在它生成的会话内关闭自动更新，所以每个会话运行您在主机上安装或构建到镜像中的版本。主机级更新在运行器下次启动时生效。

您的会话使用的模型可能需要比它们运行的 Claude Code 版本更新的版本。服务器随后会以 [Claude Code does not support this model](/docs/zh-CN/errors#claude-code-does-not-support-this-model) 拒绝对该模型的请求。在您固定版本之前，请检查[模型需要的 Claude Code 版本](/docs/zh-CN/model-config#available-models)，以了解您的会话使用的每个模型。

* **将队列保持在一个版本上**：使用固定版本构建镜像，或在裸主机上安装特定版本并[禁用自动更新](/docs/zh-CN/setup#disable-auto-updates)
* **升级**：安装较新版本或重建镜像，然后重启运行器
* **插件**：插件市场也不自动更新；在运行器的环境中设置 `FORCE_AUTOUPDATE_PLUGINS=1` 以让插件自动更新，同时二进制保持固定

<h2 id="scale-the-fleet">
  扩展队列
</h2>

您的编排器决定何时添加或删除运行器。由于[每个运行器一个所有者锁](/docs/zh-CN/self-hosted-environments#runner-lifecycle)，最小副本计数是您期望并发活跃的用户和 Claude Tag 代理数；`--capacity` 控制一个所有者内的并行性，而不是跨所有者。

两种扩展方法可用：

* **固定队列**：运行静态运行器副本集并在每个运行器提供的 [Prometheus 指标](/docs/zh-CN/self-hosted-environments-reference#prometheus-metrics)上扩展
* **按需运行器**：运行 `claude self-hosted-runner orchestrator` 子命令，它轮询 Anthropic 以查找没有可用运行器排队的会话，并调用您的 `spawn-runner` 钩子为每个会话启动一个。请参阅[按需运行器](/docs/zh-CN/self-hosted-environments-configuration#on-demand-runners)。

<h2 id="known-issues-and-limitations">
  已知问题和限制
</h2>

以下是此版本中的限制，其中存在解决方法。

<h3 id="connector-traffic-leaves-your-network">
  连接器流量离开您的网络
</h3>

Anthropic 从其自己的基础设施而不是从您的运行器调用连接器工具。连接器工具是 claude.ai 连接器，例如 GitHub、Slack 和 Linear。当 Claude 在自托管会话中使用连接器时，该流量通过 `api.anthropic.com` 而不是源自您的网络边界内。

要将连接器排除在自托管会话之外，使用 [`allowedMcpServers` 和 `deniedMcpServers` 策略设置](/docs/zh-CN/managed-mcp#policy-based-control-with-allowlists-and-denylists)过滤它。Claude Code 将这些设置应用于 Anthropic 交付的连接器以及您从运行器主机播种的服务器和用户添加的服务器，所以如果您为其他服务器部署允许列表，Claude Code 也会阻止交付的连接器。要在 URL 基础允许列表旁边保持连接器可用，添加与 Anthropic 代理路径匹配的条目以获得交付的连接器：

* `https://api.anthropic.com/v2/ccr-sessions/*`
* `https://api.anthropic.com/v1/code/sessions/*`
* `https://api.anthropic.com/v1/code/mcp/*`

如果工具流量必须保留在您的网络内，改为在运行器镜像上作为本地 MCP 服务器运行等效工具。请参阅 [MCP 服务器](/docs/zh-CN/self-hosted-environments-configuration#mcp-servers)。

<h3 id="some-sessions-don’t-count-as-idle">
  某些会话不计为空闲
</h3>

持有永不完成的后台任务的会话不计为空闲，所以 `--release-idle-session-min` 不会释放该会话的槽。等待从运行中工具调用内请求的批准的会话也不计为空闲。始终将 `--kill-session-after-min` 与其一起设置作为硬后挡，以便没有会话可以无限期地持有槽。

`--kill-session-after-min` 是失控会话的后挡。在 v2.1.260 或更高版本的运行器上，达到限制的会话不会立即终止。运行器给它一个宽限窗口，默认 15 分钟，您可以使用 [`SELF_HOSTED_RUNNER_MAX_LIFETIME_GRACE_MS`](/docs/zh-CN/self-hosted-environments-reference#environment-variable-only-settings) 更改：

* 如果会话等待其用户，运行器释放它。如果其转向已结束并仅持有后台任务，运行器最多等待 60 秒以完成这些任务，然后释放它。会话在其用户发送下一条消息时恢复。
* 如果转向仍在运行，运行器等待转向完成，或会话下一次等待其用户，然后释放它。
* 如果会话在宽限窗口结束时仍在运行器上，运行器终止它，任何运行转向的工作丢失。等待从运行中工具调用内请求的批准的转向是会话超过窗口的一种方式。

释放的会话从新克隆恢复，所以它未推送的工作无论如何都消失了；请参阅[恢复的会话丢失未推送的工作](#additional-limitations)。在 v2.1.260 之前，运行器在限制处终止每个会话，最多等待宽限窗口以完成运行转向。

将该标志设置为高于您预期的最长会话时长，例如 `--kill-session-after-min 480` 为 8 小时。要从空闲的对话释放槽，改用 `--release-idle-session-min`。

<h3 id="additional-limitations">
  其他限制
</h3>

* **恢复的会话丢失未推送的工作**：当会话被释放或其运行器重启，用户发送另一条消息时，会话在新运行器上恢复，该运行器从其起始分支再次克隆存储库，所以会话未推送的工作消失。设置 [`--push-outcome-on-release`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags) 使运行器在释放之前尽力推送会话的结果分支，所以恢复的会话从这些提交开始；这保留提交的工作，而不是脏工作树。在启用之前，限制谁可以推送到源远程上的 `claude/*` refs，例如使用分支规则集：在恢复时，运行器获取之前推送的分支而不验证谁推送了它，所以任何有推送访问这些 refs 的人都可以将内容放入恢复的工作区。运行器也在恢复时丢弃按会话配置，意味着会话的 Claude 配置目录和会话写入的任何 shell 状态；`--push-outcome-on-release` 不涵盖这些。
* **私有存储库无法在会话中途添加**：在自托管运行器上，添加到已启动会话的存储库不使用凭证克隆，所以添加失败。创建会话时选择会话需要的每个存储库。
* **某些连接器不出现在自托管会话中**：您在 claude.ai Settings 中尚未连接的连接器不在自托管会话中列出，会话不会提示您连接它。首先在 Settings 中连接它，然后启动新会话。向已运行的会话添加连接器也不会使其工具对 Claude 可用；启动新会话以获取新添加的连接器。

<h3 id="report-an-issue">
  报告问题
</h3>

对于自托管环境的问题，请联系您的 Anthropic 帐户团队。

<h2 id="troubleshooting">
  故障排除
</h2>

如需引导式诊断，请在运行器主机上运行 doctor 子命令。doctor 子命令启动一个交互式 Claude Code 会话，并附加运行器的日志和状态。首先在该主机上使用 `claude auth login` 登录，以便会话可以查询您的环境、其运行器和排队的会话。如果没有该登录（例如当主机使用 API 密钥进行身份验证时），它仅限于本地健康端点、指标和运行器日志，并且仅当您使用 `--log-file` 启动运行器时才读取日志。

```bash theme={null}
claude self-hosted-runner doctor
```

常见问题：

* **运行器未出现在环境中**：确认主机可以通过 HTTPS 到达 `api.anthropic.com`，环境密钥是最新的，并且主机时钟与实际时间相差在五分钟以内；更大的时间偏差会导致身份验证失败。运行器在身份验证失败时会记录 `[runner:fatal]` 和拒绝原因。
* **运行器在启动时退出，显示 `cannot create or write to base directory`**：运行器无法创建或写入 `--base-dir`，其默认值为 `/workspace`。修复目录的所有权或将 `--base-dir` 指向可写路径，如 [保持基础目录和容量在运行器之间相同](#keep-the-base-directory-and-capacity-identical-across-runners) 中所述。如果运行器改为记录 `[runner:fatal]` 说基础目录检查超时，则该目录位于挂起的 NFS 或 CSI 挂载上。检查挂载健康状况而不是权限。运行器在打开 `--log-file` 之前将这两个启动失败打印到 stderr，因此请在终端或您的平台的容器日志中查找它们，而不是日志文件。在 v2.1.225 之前，运行器在启动时不检查基础目录，此错误配置在拾取后失败会话。
* **会话保持排队**：每个在线运行器可能被锁定到不同的所有者。检查每个运行器的 `claude_code_self_hosted_runner_locked_account` [指标](/docs/zh-CN/self-hosted-environments-reference#prometheus-metrics) 或其 `[runner:health]` 日志行的 `locked_account` 字段，以查看谁持有它。两者仅在运行器被颁发携带 `act.email` 声明的会话令牌后才显示所有者的电子邮件，Claude Tag 代理的会话永远不会这样做。没有该声明，运行器不发出 `locked_account` 系列，并记录 `locked_account=yes`，这告诉您运行器被锁定但不知道是哪个所有者。添加副本，或等待现有运行器耗尽并重新启动。如果环境使用按需运行器，请改为检查编排器；请参阅 [按需运行器](/docs/zh-CN/self-hosted-environments-configuration#on-demand-runners)。
* **会话在拾取后立即失败**：在 claude.ai/code 中打开会话以查看错误。最常见的原因是运行器镜像中缺少 [git 凭证](#configure-git) 和未安装的构建工具。不可写的基础目录会在启动时停止运行器，而不是失败会话。请参阅此列表中的 **运行器在启动时退出，显示 `cannot create or write to base directory`** 条目。
* **会话无法通过身份验证出口代理到达网络**：当您使用 [`--proxy-authorization-command` 或 `--proxy-authorization-file`](#authenticate-to-an-egress-proxy) 设置的源失败、在 30 秒后超时或产生空值时，运行器以 `502 Bad Gateway` 应答该连接并记录原因。运行器在该日志中编辑命令的 stderr，并且永远不会记录标头值。使用 `--proxy-authorization-command` 时，在主机上自己运行该命令以确认它在 stdout 上打印整个标头值。如果运行器改为在启动时退出，显示 `could not start the proxy-authorization listener`，则它无法打开其环回监听器。
* **运行器记录包含 `rejecting the malformed poll response` 的 `Poll failed` 行**：运行器收到的工作轮询响应的正文不是队列的预期 JSON，最常见的原因是运行器和 `api.anthropic.com` 之间的某些内容（例如拦截代理或强制门户）用自己的页面进行了应答。运行器拒绝响应，在 `claude_code_self_hosted_runner_poll_errors_total` [指标](/docs/zh-CN/self-hosted-environments-reference#prometheus-metrics) 的 `transport` 类型下计数，并按 [会话生命周期](/docs/zh-CN/self-hosted-environments#session-lifecycle) 中描述的失败轮询计划重试。运行器继续为其实时会话提供服务。配置代理以将来自 `api.anthropic.com` 的响应原封不动地传递。在 v2.1.246 之前，运行器将这样的响应读取为空工作队列，这可能会结束其实时会话或使其退出。
* **会话的分支在远程上不再存在**：对于会话仅从中读取的 git 源，运行器跳过该源并继续处理其余源。对于会话推送结果的源，删除的分支（通常是因为它被合并并自动删除）会导致会话失败，并显示一个错误，命名存储库和分支，并要求您恢复分支并重试。当跳过会导致它完全没有存储库时，运行器会以相同的错误失败会话。在 v2.1.228 之前，这样的会话在空目录中启动。
* **会话启动时缺少其中一个存储库**：在没有 [`checkout` hook](/docs/zh-CN/self-hosted-environments-configuration#checkout) 的运行器上，git 主机可能会拒绝运行器对会话仅从中读取的存储库的访问检查。运行器随后跳过该存储库，记录一条 `[runner:warn] could not access context source` 行，命名拒绝，并在其余存储库上启动会话。

  运行器仅跳过明确的拒绝：主机回答存储库未找到，git 找不到主机的凭证，或身份验证失败。网络故障、超时或 HTTP `403` 仍会导致会话启动失败，对于会话推送结果的存储库的拒绝也是如此。运行器仍会失败一个会话，跳过会导致它完全没有存储库。使用 [`--use-anthropic-git-proxy`](#use-the-anthropic-git-proxy)，运行器仅跳过 git 代理本身拒绝的存储库。

  访问检查在每次会话在运行器上启动时再次运行，因此一旦运行器的 git 身份具有读取访问权限，下一次启动就会克隆存储库。在 v2.1.274 之前，这些拒绝中的每一个都导致会话启动失败。
* **会话需要数分钟才能启动**：初始克隆通常占主导地位。观察 `claude_code_self_hosted_runner_session_init_duration_seconds` [指标](/docs/zh-CN/self-hosted-environments-reference#prometheus-metrics) 以确认，并使用 [预热检出](#reuse-a-pre-warmed-checkout) 或更小的 `CLAUDE_RUNNER_FETCH_DEPTH` 减少克隆。
* **轮次以 401 失败**：每个会话使用运行器从 Anthropic 获取并通过会话的 stdin 轮换的短期 [`CLAUDE_CODE_OAUTH_TOKEN`](/docs/zh-CN/self-hosted-environments-configuration#wrapper-scripts) 对模型调用进行身份验证。当轮次以来自模型 API 的 401 或 403 结束时，运行器获取新令牌并将其传递给会话。失败的轮次不会重试。

  当获取失败时，运行器记录一条 `inference_token refresh failed` 行，说明何时重试，并在会话运行期间继续重试。

  如果每个调用在会话大约 30 分钟后开始失败，包装脚本可能已断开会话的 stdin，因此令牌轮换无法到达它；请参阅 [保持 stdin 和文件描述符 3 附加](/docs/zh-CN/self-hosted-environments-configuration#keep-stdin-and-file-descriptor-3-attached)。

  在 v2.1.274 之前，运行器在几次尝试后停止重试失败的获取，并等待下一个计划的获取。失败的轮次不会触发获取，因此每个轮次都会失败，显示 401，直到下一个计划的获取。
* **Pod 在耗尽中途被杀死**：将 `terminationGracePeriodSeconds` 提高到至少运行器在启动时记录的值。请参阅 [关闭时序](#shutdown-timing)。

初始化日志后，运行器将其生命周期日志（包括 `[runner:fatal]` 行）写入 stdout，将调试输出写入 stderr，全部作为纯文本行而不是 JSON。上述故障排除条目中描述的启动失败在该点之前打印到 stderr。使用 `--log-file` 捕获两个流，这也让 `self-hosted-runner doctor` 能够跟踪它们，或使用您的平台的日志收集。

每个会话的子进程写入单独的调试日志。失败时，运行器在 claude.ai/code 中将日志的尾部与会话一起显示。除非您使用 [`--remove-session-state`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags) 启动了运行器，否则它也会在磁盘上保留失败会话的日志，并在运行器日志中打印其路径。

<h3 id="when-the-runner-exits">
  当运行器退出时
</h3>

不要重新启动 [按需运行器](/docs/zh-CN/self-hosted-environments-configuration#on-demand-runners)，因为其工作订单是一次性的。在启动后立即退出的运行器需要与因任何其他原因退出的运行器不同的处理方式。

* **正常退出**：运行器完成了其会话并耗尽，达到了其退休时间，或被告知停止。重新启动它以便环境再次具有容量。[运行器生命周期](/docs/zh-CN/self-hosted-environments#runner-lifecycle) 描述了这些退出。
* **启动失败**：运行器无法使用给定的配置或主机启动，因此它在启动后几秒钟退出，并且每次重新启动时都以相同的方式退出。更快地重新启动它没有帮助。有人需要阅读其输出并修复原因。

配置您的监督程序在运行器退出时重新启动它，当运行器在启动后立即保持退出时等待更长时间，并在这种情况持续发生时告知某人。

<h4 id="recognize-a-failed-start">
  识别启动失败
</h4>

当运行器无法启动时，它会打印一行说明原因，然后退出。对于大多数原因，该行包含 `[runner:fatal]`。对于某些原因，该行以 `error:` 开头，包括当运行器无法解析其标志、无法读取环境密钥或无法创建或写入基础目录时。下一行然后指向 `--help`。

大多数日志行以时间戳和 `[self-hosted-runner]` 开头，下面的示例省略了这些。例如，使用 Anthropic git 代理和容量大于 1 启动的运行器会打印如下一行：

```text theme={null}
[runner:fatal] --use-anthropic-git-proxy requires --capacity 1 (the proxy URL is per-session and linked worktrees share origin). Omit --use-anthropic-git-proxy or set --capacity 1.
```

在运行器的标准输出和标准错误、您的平台的容器日志或您使用 [`--log-file`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags) 设置的文件中查找该行。运行器在打开日志文件之前打印 `error:` 行，因此请在终端或您的容器日志中查找它，如 [故障排除](#troubleshooting) 所述。

当您阅读启动失败时，这些也有帮助：

* **根本没有行**：主机杀死的运行器不会打印任何内容。如果输出以没有 `[runner:fatal]` 行和没有 `error:` 行结束，请检查主机或您的编排器是否停止了该进程，例如因为超过了内存限制。
* **退出代码**：运行器不会为在每次启动时重复的错误预留退出代码。它对配置错误（例如不支持的标志组合）和可以自行清除的失败（例如 API 通过运行器自己的重试保持不可达）退出相同的代码。根据运行器退出的速度快慢来决定是否等待更长时间，并阅读运行器的输出以了解原因。
* **看起来健康的环境**：某些启动步骤在运行器向您的环境注册后运行，例如 [`--configure-git`](#let-the-runner-configure-git) 和 Anthropic git 代理的凭证设置。如果其中一个步骤失败，环境可以在该进程退出后的几分钟内继续列出该运行器，并且 **Cloud environments** 页面可以读取 **Healthy**，而没有运行器拾取工作。如果会话在看起来健康的环境中保持排队，请检查您的监督程序是否在重新启动运行器。

<h4 id="restart-with-a-wait-that-grows">
  使用增长的等待时间重新启动
</h4>

如何获得增长的等待时间取决于您的监督程序。

* **Kubernetes**：此页面上的 [Deployment](#kubernetes) 不需要更改。容器退出后，kubelet 默认在重新启动容器之前等待，并且等待时间在每次重新启动时增长到一个上限。一旦容器运行了一段时间而没有退出，等待就会重新开始。

  当容器仅运行很短时间时，kubelet 在正常退出后应用相同的等待。经常耗尽的运行器因此也可以显示 `CrashLoopBackOff` 状态，所以在得出运行器无法启动的结论之前请阅读输出。下面的命令从 Deployment 的一个 pod 读取最后一次运行的输出：

  ```bash theme={null}
  kubectl logs --previous -n claude-runners deploy/claude-runner
  ```

  当最后一次运行是启动失败时，`[runner:fatal]` 或 `error:` 行在输出的最后几行中。要读取另一个 pod 的最后一次运行，请在 `deploy/claude-runner` 的位置命名该 pod。
* **Docker 和 Docker Compose**：此页面上的 [Compose recipe](#docker-compose) 不需要更改。使用 `restart: always`，Docker 在保持退出的容器的每次重新启动之前等待更长时间，直到一个上限。在下面的命令中用容器的名称替换 `<container>`，该命令读取 Docker 重新启动容器的次数：

  ```bash theme={null}
  docker inspect --format '{{.RestartCount}}' <container>
  ```

  该命令打印一个数字。不断增加的数字意味着 Docker 不断重新启动运行器。
* **systemd 单元**：默认情况下，systemd 在每次重新启动之前等待相同的 `RestartSec`，并且不会延长它，因此具有 `Restart=always` 的单元以相同的间隔重新启动无法启动的运行器。当启动速度足够快以达到单元的启动速率限制（默认为 10 秒内 5 次启动）时，systemd 停止重新启动该单元。该单元保持停止状态，直到有人再次启动它，systemd 允许在速率限制的间隔已过或在 `systemctl reset-failed` 之后启动。因为 `RestartSec` 适用于每次重新启动，更长的值也会延迟正常退出后的重新启动。选择一个平衡两者的值，并对单元的重新启动计数进行警报。
* **shell 循环或您自己的监督程序**：自己应用相同的规则。从 5 秒的等待开始。在每次在一分钟内结束的运行之后，将下一次重新启动的等待加倍，最多 5 分钟。在运行了一分钟或更长时间的运行之后，回到 5 秒。

<h4 id="check-why-the-runner-keeps-exiting">
  检查运行器为什么保持退出
</h4>

当运行器连续多次在启动后立即退出时，在再次重新启动之前停止并检查这些。

* **最后的 `[runner:fatal]` 或 `error:` 行**：它说明运行器停止的原因。[故障排除](#troubleshooting) 列出了常见原因。
* **标志的组合**：[Anthropic git 代理](#use-the-anthropic-git-proxy) 需要 `--capacity 1`。此页面上的配方使用更高的容量，因此当您将代理添加到其中一个时降低它。
* **服务的环境可以到达什么**：如果运行器手动启动并在您的监督程序下失败，请比较用户、主目录、`PATH` 和内存限制。`--configure-git` 和 Anthropic git 代理需要 `PATH` 上的 git 和可写的 `~/.gitconfig`。
* **环境密钥**：如果您撤销了密钥或输入错误，运行器会打印一行包含 `RegisterRunner auth failed`。
* **环境的 Activity 标签**：打开环境并选择 **Activity**。如果新运行器不断出现在那里，但没有拾取工作，您的监督程序正在重新启动运行器。

如需在运行器主机上进行引导式诊断，请运行 [doctor 子命令](#troubleshooting)。

<h2 id="what’s-next">
  接下来
</h2>

* [自定义会话](/docs/zh-CN/self-hosted-environments-configuration)：包装脚本、生命周期钩子、按需运行器、MCP 服务器和权限
* [端到端测试](/docs/zh-CN/self-hosted-environments-testing)：在推广新运行器镜像之前从 CI 验证它
* [参考](/docs/zh-CN/self-hosted-environments-reference)：每个 CLI 标志、环境变量和指标

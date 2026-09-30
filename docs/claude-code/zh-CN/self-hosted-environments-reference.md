> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# 自托管环境参考

> 自托管运行器和编排器的完整参考：CLI 标志、环境变量和 Prometheus 指标。

<Note>
  自托管环境在 Team 和 Enterprise 计划上处于公开测试阶段；[所有者](/docs/zh-CN/cloud-environments#organization-shared-environments)通过在[**云环境**管理页面](https://claude.ai/admin-settings/cloud-environments)上打开**允许自托管环境**来启用它们。本页面是标志和指标参考；有关设置，请参阅[快速入门](/docs/zh-CN/self-hosted-environments-quickstart)，有关舰队配方，请参阅[部署到生产](/docs/zh-CN/self-hosted-environments-deploy)。
</Note>

本页面是您在[自托管环境](/docs/zh-CN/self-hosted-environments)中运行的两个进程的参考：运行器，它在您的主机上执行 Claude Code [云会话](/docs/zh-CN/claude-code-on-the-web)，以及可选的自动扩展编排器，它在会话队列时启动运行器。每个都有自己的标志表。两者都在 Linux 或 macOS 主机上运行，默认值如 `/workspace` 和 `~/.claude` 假设。运行 `claude self-hosted-runner --help` 以获取已安装版本上的权威列表。

指标系列和一些 API 字段仍然使用 `pool` 来表示这些页面所称的环境；两个术语都指同一事物。环境 ID 是 `pool_id` 字段，形式为 `ccpool_...`：无论这些页面在哪里显示 `pool` 标识符，它都命名环境。CLI 标志和环境变量将其拼写为 `environment`，例如 `--environment-secret-file`；已弃用的 `pool` 拼写仍然有效，如[`--environment-secret-file` 行](#runner-cli-flags)所述。

<h2 id="runner-cli-flags">
  Runner CLI 标志
</h2>

大多数标志都有相应的环境变量。当两者都设置时，标志优先。持续时间标志在 CLI 上采用分钟或秒，但配对的环境变量始终以毫秒为单位，由 `_MS` 后缀表示，默认列显示标志的单位：`--exit-if-unused-min 10` 等同于 `SELF_HOSTED_RUNNER_IDLE_SHUTDOWN_MS=600000`，而 Helm 值如 `SELF_HOSTED_RUNNER_STARTUP_TIMEOUT_MS: "15"` 表示 15 毫秒，而不是 15 分钟的默认值。

| 标志 | 环境变量 | 默认值 | 描述 |
| :- | :- | :- | :- |
| `--api-url <url>` | 无 | `https://api.anthropic.com` | API 基础 URL。仅为测试覆盖。 |
| `--base-dir <path>` | `SELF_HOSTED_RUNNER_BASE_DIR` | `/workspace`；Windows 上无 | 用于存储库检出和每个会话工作目录的目录。运行器需要对此路径或其父路径的写入访问权限。运行器在启动时创建目录，当无法创建或写入时以 `cannot create or write to base directory` 退出。在 v2.1.225 之前，运行器在第一个会话启动时创建目录，因此不可用的路径会导致会话失败而不是启动失败。在 Windows 上（不是受支持的运行器主机），没有默认值：除非您传递标志或设置变量，否则运行器在启动时退出。在环境中的每个运行器上使用相同的值。请参阅[在运行器之间保持基础目录和容量相同](/docs/zh-CN/self-hosted-environments-deploy#keep-the-base-directory-and-capacity-identical-across-runners)。 |
| `--capacity <n>` | 无 | `1` | 此运行器处理的最大并发会话数。所有会话都属于同一个锁定的[所有者](/docs/zh-CN/self-hosted-environments#key-concepts)。在环境中的每个运行器上使用相同的值；请参阅[在运行器之间保持基础目录和容量相同](/docs/zh-CN/self-hosted-environments-deploy#keep-the-base-directory-and-capacity-identical-across-runners)。 |
| `--client-label <label>` | `SELF_HOSTED_RUNNER_CLIENT_LABEL` | 主机的主机名 | 运行器注册时发送的标签。运行器还将其报告为 [`claude_code_self_hosted_runner_info`](#prometheus-metrics) 的 `client_label` 标签。需要 Claude Code v2.1.248 或更高版本。 |
| `--configure-git` | `SELF_HOSTED_RUNNER_CONFIGURE_GIT=1` | 关闭 | 在启动时，写入全局 git 身份，启用 Anthropic 提交签名，打开 git push 协商，并安装追加 `Co-authored-by:` 预告片的提交钩子。Push 协商需要 Claude Code v2.1.257 或更高版本。请参阅[配置 git](/docs/zh-CN/self-hosted-environments-deploy#configure-git)。 |
| `--confine-repo-settings <mode>` | `SELF_HOSTED_RUNNER_CONFINE_REPO_SETTINGS` | `warn` | 设置保护的模式，当存储库的已提交设置尝试授予对该会话自己的工作区之外的写入或读取访问权限、设置环境变量或覆盖操作员的沙箱或钩子姿态（例如 `sandbox.enabled: false` 或 `disableAllHooks`）时标记会话。默认 `warn` 记录违规并仍然启动会话，`enforce` 拒绝会话，`off` 禁用扫描。请参阅[加强您的部署](/docs/zh-CN/self-hosted-environments-deploy#harden-your-deployment)。 |
| `--debug-token-dir <path>` | `SELF_HOSTED_RUNNER_DEBUG_TOKEN_DIR` | 未设置 | 将实时令牌写入磁盘以供检查。仅用于调试；不要在生产中使用。 |
| `--defer-shutdown-max-min <n>` | `SELF_HOSTED_RUNNER_DEFER_SHUTDOWN_MAX_MS` | `0` | 在第一个 `SIGTERM` 或 `SIGINT` 上，继续为已附加的会话提供服务而不是排空它们，然后在 N 分钟后释放仍然附加的任何内容并退出。在设置此值之前提高主机的停止超时。请参阅[将排空推迟到第一个信号之后](/docs/zh-CN/self-hosted-environments-deploy#defer-the-drain-past-the-first-signal)。`0` 禁用。需要 Claude Code v2.1.238 或更高版本。 |
| `--drain-grace-sec <n>` | `SELF_HOSTED_RUNNER_DRAIN_GRACE_MS` | `0` | 在运行器接收到关闭信号或达到其退休时间之前，控制运行器在其活跃会话完成后何时退出：`0` 立即退出而不轮询更多内容，正值使运行器保持活跃并首先重新轮询锁定所有者的队列那么多秒，代价是[加强部分](/docs/zh-CN/self-hosted-environments-deploy#harden-your-deployment)中描述的每个会话容器隔离。在您使用 [`--defer-shutdown-max-min`](/docs/zh-CN/self-hosted-environments-deploy#defer-the-drain-past-the-first-signal) 推迟的第一个信号之后，运行器在不持有任何会话时立即退出，无论您在此处设置什么。 |
| `--drain-marker-file <path>` | `SELF_HOSTED_RUNNER_DRAIN_MARKER_FILE` | 未设置 | 标记文件，您的主机写入该文件以在发送 `SIGTERM` 之前宣布优雅排空。当文件在排空开始时存在时，运行器将其退出报告给 Anthropic 作为主机排空而不是普通关闭信号。排空本身（包括 `--drain-wait-sec` 保持）的运行方式与没有标志相同。在会话无法写入的本地文件系统上命名路径。需要 Claude Code v2.1.271 或更高版本。 |
| `--drain-wait-sec <n>` | `SELF_HOSTED_RUNNER_DRAIN_WAIT_MS` | `0` | 一旦排空开始（在 `SIGTERM` 上，除非您设置 [`--defer-shutdown-max-min`](/docs/zh-CN/self-hosted-environments-deploy#defer-the-drain-past-the-first-signal)），等待最多 N 秒以完成每个会话的进行中的轮次和后台任务，然后终止子进程。在此等待期间，运行器将刚刚完成的后台任务计为仍在运行，直到读取其结果的后续轮次开始，最多为 [`SELF_HOSTED_RUNNER_BG_RESULT_GRACE_MS`](#environment-variable-only-settings) 窗口。 |
| `--environment-secret-file <path>` | `SELF_HOSTED_RUNNER_ENVIRONMENT_SECRET` | 必需 | 包含环境密钥的文件的路径，或对于由[编排器](/docs/zh-CN/self-hosted-environments-configuration#on-demand-runners)生成的运行器，单次使用的工作订单 JWT。`SELF_HOSTED_RUNNER_ENVIRONMENT_SECRET` 直接携带密钥值，而不是文件路径。较旧的 `--pool-secret-file` 标志和 `SELF_HOSTED_RUNNER_POOL_SECRET` 变量仍然有效并向 stderr 打印弃用通知；早于 2.1.216 的预览程序运行器构建仅识别这些较旧的名称。 |
| `--exec-path <path>` | `SELF_HOSTED_RUNNER_EXEC_PATH` | 自己的二进制文件 | 为每个会话生成的二进制文件或包装脚本。请参阅[包装脚本](/docs/zh-CN/self-hosted-environments-configuration#wrapper-scripts)。 |
| `--exit-if-unused-min <n>` | `SELF_HOSTED_RUNNER_IDLE_SHUTDOWN_MS` | `0` | 在 N 分钟的轮询后退出，没有任何工作被分配，用于自动扩展器缩小。`0` 禁用。 |
| `--git-host-rewrite <from>=<to>` | 无 | 未设置 | 在克隆之前将 `https://<from>/...` 源 URL 重写为 `https://<to>/...`，用于分割视界 DNS。可重复；仅标志。 |
| `--git-ssh-rewrite <host>` | 无 | 未设置 | 在克隆之前将 `https://<host>/...` 源 URL 重写为 `git@<host>:...`，用于仅 SSH git 主机。可重复；仅标志。 |
| `--health-port <port>` | `SELF_HOSTED_RUNNER_HEALTH_PORT` | `8080` | `/healthz` 和 `/metrics` 侦听器的端口。设置 `0` 以禁用。 |
| `--hooks-dir <path>` | `SELF_HOSTED_RUNNER_HOOKS_DIR` | 未设置 | 生命周期钩子脚本的目录。请参阅[生命周期钩子](/docs/zh-CN/self-hosted-environments-configuration#lifecycle-hooks)。 |
| `--host-config-snapshot <mode>` | `SELF_HOSTED_RUNNER_HOST_CONFIG_SNAPSHOT` | `disk` | 运行器保持[主机配置目录](#environment-variable-only-settings)启动快照的位置，它从该快照为每个会话播种。`disk` 将快照复制到 `--base-dir` 下的运行器拥有的目录中，并在每个会话启动时，验证每个文件与内存中的摘要。如果副本中的文件已被修改，会话失败，运行器拒绝会话，直到您重新启动它。`memory` 在堆上保持整个快照，上限为 64 MiB；超过上限，会话启动时没有主机配置，并显示说明这一点的通知。当运行器无法写入磁盘快照时，它记录失败并为该运行使用 `memory`。需要 Claude Code v2.1.271 或更高版本。 |
| `--kill-session-after-min <n>` | `SELF_HOSTED_RUNNER_MAX_LIFETIME_MS` | `0` | 将会话限制为 N 分钟的挂钟时间，作为卡住会话的安全限制。在 v2.1.260 或更高版本上，运行器释放达到限制的会话，以便它可以在其用户的下一条消息上恢复，仅当它在 [`SELF_HOSTED_RUNNER_MAX_LIFETIME_GRACE_MS`](#environment-variable-only-settings) 宽限期结束时仍在运行器上时才终止它。在 v2.1.260 之前，运行器在限制处终止会话。请参阅[某些会话不计为空闲](/docs/zh-CN/self-hosted-environments-deploy#some-sessions-don%E2%80%99t-count-as-idle)了解详情以及如何选择值。`0` 禁用。 |
| `--lock-to-account <id>` | `SELF_HOSTED_RUNNER_LOCK_TO_ACCOUNT` | 未设置 | 在启动时将运行器预锁定到特定帐户，而不是在第一个会话上锁定。接受环境组织中的电子邮件地址或 `user_...` ID。预锁定的运行器永远不会拾取 Claude Tag 频道会话，这些会话没有帐户。 |
| `--log-file <path>` | `SELF_HOSTED_RUNNER_LOG_FILE` | 未设置 | 除了 stdout 和 stderr 之外，还将运行器日志镜像到文件，使用 `0600` 权限创建。`self-hosted-runner doctor` 需要在本地尾部日志。 |
| `--log-level <level>` | 无 | `info` | `info` 或 `debug` |
| `--post-session-hook-timeout-sec <n>` | `SELF_HOSTED_RUNNER_POST_SESSION_HOOK_TIMEOUT_MS` | `60` | 每个会话结束（包括运行器关闭）时 [`post-session` 钩子](/docs/zh-CN/self-hosted-environments-configuration#post-session)的预算 |
| `--proxy-authorization-command <command>` | `SELF_HOSTED_RUNNER_PROXY_AUTHORIZATION_COMMAND` | 未设置 | 运行器为每个到您的出口代理的连接运行的 shell 命令，使用其修剪的 stdout 作为 `Proxy-Authorization` 标头值。需要 `HTTPS_PROXY` 或 `HTTP_PROXY`，不能与 `--proxy-authorization-file` 结合。请参阅[向出口代理进行身份验证](/docs/zh-CN/self-hosted-environments-deploy#authenticate-to-an-egress-proxy)。需要 Claude Code v2.1.238 或更高版本。 |
| `--proxy-authorization-file <path>` | `SELF_HOSTED_RUNNER_PROXY_AUTHORIZATION_FILE` | 未设置 | 运行器为每个到您的出口代理的连接读取的文件，使用其修剪的内容作为 `Proxy-Authorization` 标头值。对另一个进程轮换到位的令牌使用此标志。与 `--proxy-authorization-command` 具有相同的要求，不能与其结合。请参阅[向出口代理进行身份验证](/docs/zh-CN/self-hosted-environments-deploy#authenticate-to-an-egress-proxy)。需要 Claude Code v2.1.238 或更高版本。 |
| `--push-outcome-on-release` | `SELF_HOSTED_RUNNER_PUSH_OUTCOME_ON_RELEASE` | 关闭 | 在运行器启动的会话结束（例如排空或空闲释放）时，在删除工作区之前将跟踪的结果分支推送到 `origin`，以便进行中的提交在重新启动后存活。尽力而为；向关闭预算添加 30 秒，需要 git 2.29 或更高版本以从推送的分支恢复。在启用之前限制对 `claude/*` refs 的推送访问；请参阅[恢复的会话丢失未推送的工作](/docs/zh-CN/self-hosted-environments-deploy#additional-limitations)。通过 `checkout` 生命周期钩子检出的存储库不会被推送；改为从 [`post-session` 钩子](/docs/zh-CN/self-hosted-environments-configuration#post-session)快照这些。 |
| `--release-idle-session-min <n>` | `SELF_HOSTED_RUNNER_SESSION_IDLE_MS` | `0` | 在轮次完成或会话等待用户操作后，在 N 分钟的不活动后释放会话槽。仍在进行中的会话（包括持有永不完成的后台任务或从运行的工具调用内部请求的批准的会话）不计为空闲；与 `--kill-session-after-min` 配对作为硬后挡。在会话的后台任务完成后，运行器认为会话繁忙，直到读取结果的后续轮次开始，最多为 [`SELF_HOSTED_RUNNER_BG_RESULT_GRACE_MS`](#environment-variable-only-settings) 窗口。在运行器接收到关闭信号或达到其退休时间之前，留下运行器没有活跃会话的释放启动与正常排空相同的退出路径，由 `--drain-grace-sec` 管理。在您使用 [`--defer-shutdown-max-min`](/docs/zh-CN/self-hosted-environments-deploy#defer-the-drain-past-the-first-signal) 推迟的第一个信号之后，运行器在释放使其不持有任何会话时立即退出。`0` 禁用。 |
| `--remove-session-state [bool]` | `SELF_HOSTED_RUNNER_REMOVE_SESSION_STATE` | 关闭 | 当会话在此运行器上结束时，删除 `<base-dir>/_sessions/` 下的会话的每个会话目录，无论结果如何。[重用预热的检出](/docs/zh-CN/self-hosted-environments-deploy#reuse-a-pre-warmed-checkout)描述它们保存的内容以及当它们保留时谁可以读取它们。删除是尽力而为：当运行器被杀死或在清理运行之前达到其排空截止时间时，每个会话目录保持到位。启用标志后，失败或中断的会话的调试日志不会保留在磁盘上。需要 Claude Code v2.1.268 或更高版本。 |
| `--retire-at <epoch-seconds>` | `SELF_HOSTED_RUNNER_RETIRE_AT` | 未设置 | 在绝对 Unix 时间戳（以秒为单位）处退休运行器，用于在已知时间杀死运行器的基础设施；[运行器生命周期](/docs/zh-CN/self-hosted-environments#runner-lifecycle)描述释放序列以及如何调整边距。2001 年之前或 5138 年之后的值被标志拒绝，被环境变量忽略。 |
| `--session-stop-grace-sec <n>` | `SELF_HOSTED_RUNNER_SESSION_STOP_GRACE_MS` | `5` | 会话结束后等待 Claude 进程干净退出的时间，然后强制杀死它。如果子进程自己的 `SessionEnd` 钩子需要更多时间，请提高该值。 |
| `--startup-timeout-min <n>` | `SELF_HOSTED_RUNNER_STARTUP_TIMEOUT_MS` | `15` | 如果子进程在生成后 N 分钟内未在[活动频道](/docs/zh-CN/self-hosted-environments-configuration#keep-stdin-and-file-descriptor-3-attached)上发出初始化信号，则释放会话槽。由子进程的初始化信号清除，而不是普通输出，之后 `--release-idle-session-min` 接管。`0` 禁用。 |
| `--trust-workspace [bool]` | `SELF_HOSTED_RUNNER_TRUST_WORKSPACE` | 开启 | 为每个会话的存储库路径播种持久化信任，以便遵守存储库提交的 `permissions.allow` 和 `additionalDirectories`。设置 `false` 以删除存储库提交的权限授予，并在主机配置的 `settings.json` 中配置允许规则；无论如何，存储库提交的 `sandbox.*` 设置仍然适用，这就是为什么[存储库设置保护](/docs/zh-CN/self-hosted-environments-deploy#harden-your-deployment)无论此标志如何都扫描它们。 |
| `--use-anthropic-git-proxy` | `CLAUDE_RUNNER_USE_GIT_PROXY=1` | 关闭 | 通过 [Anthropic git 代理](/docs/zh-CN/self-hosted-environments-deploy#use-the-anthropic-git-proxy)而不是客户管理的 git 身份验证进行克隆。需要 `--capacity 1` 和 git 2.32 或更高版本；运行器否则拒绝启动。取代重写标志。 |

大多数持续时间标志都有最大值，选择以将每个超时保持在运行时的 32 位计时器上限内，大约 24.85 天。`--*-min` 标志上限为 10080 分钟，7 天；`--drain-grace-sec` 为 604800 秒，也是 7 天；`--drain-wait-sec` 为 86400 秒，24 小时。`--session-stop-grace-sec` 和 `--post-session-hook-timeout-sec` 无上限。超过上限的行为因表面而异：

* **标志**：启动失败并出现错误。
* **环境变量**：运行器将值夹紧到计时器上限，而不是拒绝它。

<h2 id="orchestrator-cli-flags">
  Orchestrator CLI 标志
</h2>

`self-hosted-runner orchestrator` 子命令（生成[按需运行器](/docs/zh-CN/self-hosted-environments-configuration#on-demand-runners)）接受 `--api-url`、`--environment-secret-file`、`--hooks-dir`、`--health-port` 和 `--log-level`，与运行器具有相同的默认值，以及运行器的标志具有的相同环境变量（除了 `--hooks-dir` 是必需的，必须包含 `spawn-runner` 钩子）。它还采用自己的标志：

| 标志 | 默认值 | 描述 |
| :- | :- | :- |
| `--hook-concurrency <n>` | `4` | 最大 `spawn-runner` 钩子并行运行。还限制每次轮询声称的生成请求数。 |
| `--hook-timeout <sec>` | `60` | 在这么多秒后终止钩子的进程树。超时加其 5 秒杀死宽限期必须保持在 `--expected-spawn-seconds` 以下；编排器在启动时强制执行此操作。 |
| `--expected-spawn-seconds <sec>` | `120` | 生成的运行器的预期 p99 启动时间，在服务器强制的范围 10 到 3600 内。在每次轮询时发送作为服务器端租约；如果没有运行器在其过期前注册，会话将使用新的订单 ID 重新提供。所有副本必须共享此值。 |
| `--min-idle <n>` | `0` | 通过主动生成待命运行器来保持至少 N 个空闲会话槽。`0` 禁用预热。与运行器的 `--exit-if-unused-min` 配对，以便多余的待命运行器回收自己。 |
| `--debug-dir <path>` | 未设置 | 将每个生成请求的工作订单和钩子 stderr 写入磁盘。仅用于调试；永远不要在生产中设置。 |

<h3 id="scm-connector-flags">
  SCM 连接器标志
</h3>

编排器可以与 Anthropic 的控制平面保持一个常设 WebSocket 连接，以便托管的预会话流（例如存储库选择器和分支或 ref 解析器）可以到达仅从您的网络内部可路由的 GitHub Enterprise Server 主机。除非您设置 `--scm-connector-host`，否则连接器保持关闭。

| 标志 | 默认值 | 描述 |
| :- | :- | :- |
| `--scm-connector-host <host[:port]>` | 未设置 | GitHub Enterprise Server 主机名以转发请求。端口默认为 `443`。设置此标志启用连接器。 |
| `--scm-connector-id <n>` | 与 `--scm-connector-host` 一起需要 | 您的组织的 GitHub Enterprise Server 连接的数字 ID。启用连接器时，请与您的 Anthropic 帐户团队联系以获取该值。 |
| `--scm-connector-provider <slug>` | `ghe` | 标识提供程序的路径段，匹配 `^[a-z0-9-]{1,32}$`。 |
| `--scm-connector-ca-file <path>` | 未设置 | 额外的 CA 包，PEM 格式，用于到 GitHub Enterprise Server 主机的 TLS 连接。 |
| `--scm-connector-host-rewrite <from>=<to_host:to_port>` | 未设置 | 仅用于端到端测试：重定向 TCP 连接，同时将 Host 标头和 TLS SNI 保持为 `--scm-connector-host`。 |

连接器使用编排器的现有环境密钥进行身份验证并自动重新连接：在连接断开时使用指数退避，或当控制平面关闭连接因为另一个编排器副本已持有它时使用固定的 30 秒延迟。

<h2 id="environment-variable-only-settings">
  仅环境变量设置
</h2>

这些运行器设置仅从环境读取，涵盖大多数部署保留在默认值的行为：

| 环境变量 | 默认值 | 描述 |
| :- | :- | :- |
| `SELF_HOSTED_RUNNER_BG_RESULT_GRACE_MS` | `30000` | 运行器在后台任务完成后认为会话繁忙的时间，而读取结果的后续轮次尚未开始。[`--drain-wait-sec` 和 `--release-idle-session-min` 行](#runner-cli-flags)描述了保持在排空和空闲释放时的应用位置，[运行器生命周期](/docs/zh-CN/self-hosted-environments#runner-lifecycle)描述了它在 `--retire-at` 退休时的应用位置。`0` 或不可用的值回退到默认值，因此无法关闭保持。需要 Claude Code v2.1.228 或更高版本。 |
| `SELF_HOSTED_RUNNER_HOST_CONFIG_DIR` | `~/.claude` | 捕获到运行器启动快照中并播种到每个会话的 `CLAUDE_CONFIG_DIR` 的目录；磁盘上的更改在运行器重新启动后应用。设置变量也会移动运行器读取 `.claude.json` 的位置以进行 [MCP 播种](/docs/zh-CN/self-hosted-environments-configuration#mcp-servers)，因此设置它（包括其自己的默认值）会重新定位该查找；指向空目录以完全禁用播种。 |
| `SELF_HOSTED_RUNNER_MAX_LIFETIME_GRACE_MS` | `900000` | 运行器在会话达到其 `--kill-session-after-min` 限制后等待的时间，以便运行中的轮次完成或释放完成，然后才终止会话 |
| `SELF_HOSTED_RUNNER_POST_TURN_SETTLE_MS` | `7000` | 运行器在轮次完成后计算会话繁忙的时间上限，用于 `--drain-wait-sec` 排空，而会话的进程向 Anthropic 报告轮次的结束。`0` 或不可用的值回退到默认值，因此无法关闭保持。需要 Claude Code v2.1.275 或更高版本。 |
| `SELF_HOSTED_RUNNER_SIGKILL_GRACE_MS` | `30000` | 运行器等待操作系统向陷入不可中断 I/O 的子进程传递 `SIGKILL` 的时间，然后自己退出。下限为 `--post-session-hook-timeout-sec` 加 15 秒，设置 `--push-outcome-on-release` 时再加 30 秒，因此有效最小值在默认值处为 75 秒。 |
| `CLAUDE_RUNNER_FETCH_DEPTH` | `50` | 新克隆的 git 获取深度。设置正整数，或 `full` 或 `0` 以进行完整获取。工作区中已存在的存储库保持其现有深度。 |
| `CLAUDE_RUNNER_SKIP_GIT_VERIFY` | 未设置 | 当为 `1` 时，跳过 `checkout` 钩子运行后的 `.git` 存在检查。当您的钩子物化非 git 源时设置此项。 |
| `FORCE_AUTOUPDATE_PLUGINS` | 未设置 | 当为 `1` 时，让插件市场自动更新，即使二进制文件被固定 |
| `CLAUDE_CODE_DISABLE_ARTIFACT` | 未设置 | 当为 `1` 时，无论组织的管理员设置如何，都在会话中禁用 Artifact 工具，并删除 `*.frame.claudeusercontent.com` 出口要求 |

<h2 id="telemetry">
  遥测
</h2>

会话子进程向 Anthropic 发送操作遥测，除非您关闭它。不发送代码或存储库内容。在运行器进程上设置遥测变量；运行器在应用服务器提供的环境变量后重新声明它们，因此操作员的设置始终优先。

一个控制特定于自托管环境：`CLAUDE_CODE_BYOC_ENABLE_DATADOG=1` 选择加入 Datadog 操作指标，这在自托管环境中默认关闭。一般 Claude Code 遥测控制 `DISABLE_TELEMETRY`、`DO_NOT_TRACK`、`DISABLE_ERROR_REPORTING` 和 `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC` 适用于会话子进程，如[环境变量参考](/docs/zh-CN/env-vars)中所述。`DISABLE_GROWTHBOOK` 相关但不同：设置 `DISABLE_GROWTHBOOK=1` 禁用功能标志获取，遥测保持开启，除非也设置了 `DISABLE_TELEMETRY`。

`CLAUDE_CODE_ENABLE_TELEMETRY` 无关：它启用 OpenTelemetry 导出到您自己的收集器，如[监控](/docs/zh-CN/monitoring-usage)中所述，不控制 Anthropic 的分析。

<h2 id="health-endpoint">
  健康端点
</h2>

运行器在配置的健康端口上提供 `GET /healthz`。只要进程活跃，响应就是 `200 OK`，无论轮询循环处于什么状态，因此此端点上的 HTTP 探针仅检测死进程。JSON 正文描述当前状态：

```json theme={null}
{
  "status": "ok",
  "runner_id": "ccrunner_...",
  "active_sessions": 2,
  "last_poll_at": "2026-03-31T18:04:11.220Z",
  "last_poll_age_ms": 842
}
```

在自定义探针中使用 `last_poll_age_ms` 作为活跃信号；无限增长的值表示轮询循环卡住。`last_poll_at` 和 `last_poll_age_ms` 都是 `null`，直到第一次轮询完成。

编排器在其健康端口上提供自己的 `/healthz`。其端点始终返回 `200`，正文携带报告最近一次轮询是否成功的 `connected` 字段，加上 `queue_counts` 中的每个状态生成队列计数。在自定义探针中使用 `connected` 而不是状态代码来控制就绪和警报。

当[SCM 连接器](#scm-connector-flags)被配置时，编排器的 `/healthz` 正文也携带 `scm_connector_connected` 和一个 `scm_connector` 对象，包含 `connected`、`last_connected_at`、`last_error`、`reconnects` 和 `requests_forwarded`。当未设置 `--scm-connector-host` 时，两个字段都是 `null`。

<h2 id="prometheus-metrics">
  Prometheus 指标
</h2>

每个运行器在与 `/healthz` 相同的端口上的 `GET /metrics` 处提供 Prometheus 指标。关键系列：

| 系列 | 注释 |
| :- | :- |
| `claude_code_self_hosted_runner_info{runner_id,version,client_label}` | 始终为 `1`；对舰队库存和版本漂移检测有用 |
| `claude_code_self_hosted_runner_capacity` | 配置的 `--capacity` |
| `claude_code_self_hosted_runner_active_sessions` | 当前运行的会话 |
| `claude_code_self_hosted_runner_locked_account{email}` | 一旦运行器锁定到用户并发出携带 `act.email` 声明的会话令牌，就存在。该系列在锁定到 Claude Tag 代理的运行器上不存在，其会话令牌不携带 `act.email`。标签值是帐户电子邮件；如果您的指标存储被广泛读取，在抓取时删除或哈希标签，例如使用 Prometheus `metric_relabel_configs`。 |
| `claude_code_self_hosted_runner_last_poll_age_seconds` | 自上次成功轮询以来的秒数。如果超过 60，则发出警报。 |
| `claude_code_self_hosted_runner_poll_errors_total{error_kind}` | 按类型累积 PollWork 失败：`transport`、`timeout`、`5xx`、`429` 或 `4xx`。所有五个系列从进程启动时存在；在 `rate(...[5m]) > 0` 时发出警报。 |
| `claude_code_self_hosted_runner_sessions_started_total{client_platform}` | 在运行器的生命周期内生成的会话子进程，每个会话来源一个系列，例如 `web_claude_ai`、`ios`、`android`、`desktop_app` 或 `claude_code_cli`，或当服务器未发送时为 `unknown`。Slack 会话根据哪个 Slack 集成创建它们，携带 `claude_in_slack` 或 `claude-in-slack`，因此使用正则表达式选择器（例如 `{client_platform=~"claude[-_]in[-_]slack"}` 匹配两者。对舰队总数使用 `sum()`。 |
| `claude_code_self_hosted_runner_sessions_completed_total{client_platform}` | 干净结束的会话，标签方式相同。比普通干净退出更广泛：请参阅[会话生命周期计数器语义](#session-lifecycle-counter-semantics)了解计数内容。 |
| `claude_code_self_hosted_runner_sessions_failed_total{client_platform}` | 以失败结束的会话，标签方式相同。相同的注意事项：请参阅[会话生命周期计数器语义](#session-lifecycle-counter-semantics)。 |
| `claude_code_self_hosted_runner_sessions_interrupted_total{client_platform}` | 运行器因操作原因而不是会话结果终止的会话，标签方式相同。请参阅[会话生命周期计数器语义](#session-lifecycle-counter-semantics)。 |
| `claude_code_self_hosted_runner_initializing_sessions` | 当前处于初始化阶段的会话，从分配到子进程的初始化事件 |
| `claude_code_self_hosted_runner_session_init_duration_seconds` | 会话初始化持续时间的直方图 |
| `claude_code_self_hosted_runner_session_init_errors_total` | 在达到初始化前失败的会话：checkout 钩子失败、git 准备、令牌问题或初始化前子进程崩溃 |
| `claude_code_self_hosted_runner_session_start_hook_errors_total` | 报告错误结果的 `SessionStart` 钩子，每个失败的钩子执行一个 |
| `claude_code_self_hosted_runner_session_idle_seconds{session_id,client_platform}` | 每个会话的仪表，显示会话空闲以来的秒数。对于终止卡在未回答权限提示上的会话很有用。 |

编排器在与其 `/healthz` 相同的端口上的 `GET /metrics` 处提供自己的系列：

| 系列 | 注释 |
| :- | :- |
| `claude_code_self_hosted_orchestrator_info{version,pool_id,orchestrator_uuid,hostname}` | 始终为 `1` |
| `claude_code_self_hosted_orchestrator_connected` | 当最近一次轮询成功时为 `1`；在任何失败的轮询后下降到 `0`，无论失败类型如何 |
| `claude_code_self_hosted_orchestrator_last_poll_age_seconds` | 自上次轮询尝试以来的秒数，成功或失败，与运行器的同名指标不同，后者测量自上次成功以来；与 `connected` 配对以捕获失败的轮询。编排器的轮询循环等待钩子执行，因此在 `--hook-timeout` 加边距（默认值约 90 秒）之上发出警报，而不是固定的 60。 |
| `claude_code_self_hosted_orchestrator_poll_errors_total{error_kind}` | 按类型累积 PollSpawnHints 失败：`transport`、`timeout`、`5xx`、`429` 或 `4xx`。所有五个系列从进程启动时存在；在 `rate(...[5m]) > 0` 时发出警报。 |
| `claude_code_self_hosted_orchestrator_queue_pending_sessions` | 现在可声称的生成请求 |
| `claude_code_self_hosted_orchestrator_queue_backing_off_sessions` | 在可重试钩子失败后处于重试退避中的生成请求 |
| `claude_code_self_hosted_orchestrator_queue_circuit_broken_sessions` | 被阻止的生成请求，直到所有者从环境的**活动**选项卡重试它们；如果高于零则发出警报 |
| `claude_code_self_hosted_orchestrator_pool_pending_sessions` | 等待此环境中的运行器的总会话。环境范围的聚合，在每个编排器实例上相同：在实例间使用 `MAX` 而不是 `SUM`。 |
| `claude_code_self_hosted_orchestrator_pool_active_sessions` | 当前分配给此环境中活跃运行器的会话。环境范围的聚合，在每个编排器实例上相同：在实例间使用 `MAX` 而不是 `SUM`。 |
| `claude_code_self_hosted_orchestrator_spawn_hooks_total{result}` | 累积 `spawn-runner` 钩子结果：`ok`、`retryable`、`non_retryable`。计数编排器钩子调用，而不是运行器生成的会话子进程：与 `sessions_started_total` 不可比，因为容量高于 1、热池和为同一会话再次生成的运行器都使两者分散。 |
| `claude_code_self_hosted_orchestrator_spawn_hook_duration_seconds` | 钩子持续时间的直方图 |
| `claude_code_self_hosted_orchestrator_warm_hints_dispatched_total` | 自进程启动以来分派的待命生成请求 |
| `claude_code_self_hosted_orchestrator_session_queue_wait_seconds` | 每个会话在队列中等待的秒数的直方图，然后编排器声称它用于生成，从控制平面与每个会话的生成请求一起发送的队列等待时间戳记录。用于 p50/p99 队列时间警报。预热生成不被采样。 |
| `claude_code_self_hosted_orchestrator_clock_skew_seconds` | 本地减去服务器时钟偏差；诊断，一旦测量就存在 |
| `claude_code_self_hosted_orchestrator_scm_connector_connected` | 当 [SCM 连接器](#scm-connector-flags) 的 WebSocket 打开时为 `1`；在拨号或退避时为 `0`。当未设置 `--scm-connector-host` 时不存在。 |
| `claude_code_self_hosted_orchestrator_scm_connector_requests_forwarded_total` | 自进程启动以来代理到配置的 SCM 主机的累积 HTTP 请求。当未设置 `--scm-connector-host` 时不存在。 |

对于自动扩展，选择与您的扩展风格匹配的系列，并在将其馈送到扩展器之前对其进行门控：

* **队列深度扩展**：将 `claude_code_self_hosted_orchestrator_pool_pending_sessions` 馈送到您的 HPA 或 KEDA 扩展器，而不是 `queue_pending_sessions`。
* **容量扩展**：按运行器的 `active_sessions` 与 `capacity` 的比率进行扩展。
* **在 `connected` 上门控**：使用 `claude_code_self_hosted_orchestrator_connected == 1` 按实例过滤查询，以便断开连接的副本的陈旧值不会馈送到扩展器。

在完整轮询中断期间，每个副本都断开连接，门控查询返回无数据。HPA 在缺少指标时保持当前副本计数，但 KEDA 的 Prometheus 扩展器在其默认 `ignoreNullValues: "true"` 处将空结果读取为零并缩小；在 ScaledObject 上设置 `ignoreNullValues: "false"`，可选择使用 `fallback` 副本下限。

以下 Prometheus Operator `PodMonitor` 涵盖两个进程。它通过 `app.kubernetes.io/part-of: claude-code-self-hosted-runner` 标签和[Kubernetes 配方](/docs/zh-CN/self-hosted-environments-deploy#kubernetes)设置的命名 `health` 端口选择 pod；调整命名空间以匹配您的部署：

```yaml theme={null}
# Claude Code 自托管运行器 + 编排器的示例 Prometheus Operator PodMonitor。
# 调整命名空间和标签选择器以匹配您的部署。运行器和编排器都在其
# --health-port（默认 8080）上提供 /metrics。
apiVersion: monitoring.coreos.com/v1
kind: PodMonitor
metadata:
  name: claude-code-self-hosted-runner
  namespace: monitoring
spec:
  namespaceSelector:
    matchNames:
      - claude-runners
  selector:
    matchExpressions:
      # 匹配 Kubernetes 配方中的运行器部署，加上您标记相同方式的任何
      # 按需运行器作业和编排器 pod，并给予命名的"health"containerPort。
      - key: app.kubernetes.io/part-of
        operator: In
        values: [claude-code-self-hosted-runner]
  podMetricsEndpoints:
    - port: health
      path: /metrics
      interval: 30s
```

这些示例警报规则是一个起点；为您的舰队大小调整阈值：

```yaml theme={null}
# Claude Code 自托管运行器 + 编排器的示例 Prometheus 警报规则。
# 为您的舰队大小和 SLO 调整阈值。
groups:
  - name: claude-code-self-hosted-runner
    rules:
      - alert: ClaudeRunnerPollStale
        expr: claude_code_self_hosted_runner_last_poll_age_seconds > 60
        for: 2m
        labels: {severity: warning}
        annotations:
          summary: "运行器 {{ $labels.pod }} 已超过 60 秒未轮询"
      - alert: ClaudeRunnerVersionDrift
        expr: count(count by (version) (claude_code_self_hosted_runner_info)) > 1
        for: 30m
        labels: {severity: info}
        annotations:
          summary: "运行器正在运行混合版本"
      - alert: ClaudeRunnerInitErrorsHigh
        expr: increase(claude_code_self_hosted_runner_session_init_errors_total[10m]) > 3
        for: 5m
        labels: {severity: warning}
        annotations:
          summary: "运行器 {{ $labels.pod }}：10 分钟内 >3 个会话初始化失败（checkout 钩子 / git / 令牌 / 初始化前崩溃）"
      - alert: ClaudeRunnerPollErrors
        expr: sum by (pod) (rate(claude_code_self_hosted_runner_poll_errors_total[5m])) > 0
        for: 2m
        labels: {severity: warning}
        annotations:
          summary: "运行器 {{ $labels.pod }}：PollWork 失败（{{ $value | humanize }}/s 超过 5m）"
      - alert: ClaudeRunnerSessionStartHookErrors
        expr: increase(claude_code_self_hosted_runner_session_start_hook_errors_total[10m]) > 3
        for: 5m
        labels: {severity: warning}
        annotations:
          summary: "运行器 {{ $labels.pod }}：10 分钟内 >3 个 SessionStart 钩子失败"

  - name: claude-code-self-hosted-orchestrator
    rules:
      - alert: ClaudeOrchestratorDisconnected
        expr: claude_code_self_hosted_orchestrator_connected == 0
        for: 2m
        labels: {severity: critical}
        annotations:
          summary: "编排器 {{ $labels.pod }} 无法到达 Anthropic 控制平面"
      - alert: ClaudeOrchestratorPollStale
        expr: claude_code_self_hosted_orchestrator_last_poll_age_seconds > 90
        for: 2m
        labels: {severity: warning}
        annotations:
          summary: "编排器 {{ $labels.pod }} 已超过 90 秒未轮询（轮询循环等待钩子执行）"
      - alert: ClaudeOrchestratorCircuitBroken
        expr: claude_code_self_hosted_orchestrator_queue_circuit_broken_sessions > 0
        for: 1m
        labels: {severity: critical}
        annotations:
          summary: "{{ $value }} 个会话断路 — spawn-runner 钩子反复不可重试；修复基础设施然后从活动选项卡重试"
      - alert: ClaudeOrchestratorPollErrors
        expr: sum by (pod) (rate(claude_code_self_hosted_orchestrator_poll_errors_total[5m])) > 0
        for: 2m
        labels: {severity: warning}
        annotations:
          summary: "编排器 {{ $labels.pod }}：PollSpawnHints 失败（{{ $value | humanize }}/s 超过 5m）"
      - alert: ClaudeOrchestratorSpawnHookFailing
        expr: sum by (pod) (increase(claude_code_self_hosted_orchestrator_spawn_hooks_total{result!="ok"}[5m])) > 3
        for: 5m
        labels: {severity: warning}
        annotations:
          summary: "编排器 {{ $labels.pod }}：5 分钟内 >3 个 spawn-runner 钩子失败"
```

<h3 id="pass-through-session-child-metrics">
  传递会话子进程指标
</h3>

每个会话在其自己的子进程中运行，具有自己的 OpenTelemetry 指标；在 `--capacity` 高于 1 时，运行器重写这些子指标的公开方式。在运行器主机上设置 `OTEL_METRICS_EXPORTER=prometheus` 和会话环境中的 `CLAUDE_CODE_ENABLE_TELEMETRY=1`（例如从您的[包装脚本](/docs/zh-CN/self-hosted-environments-configuration#wrapper-scripts)或运行器自己的环境，会话继承），重新公开每个子进程的计数器和仪表工具在运行器自己的 `/metrics` 端点上，与运行器的系列一起。运行器将子进程的导出器重写为通过 OTLP 推送到健康端口上的仅环回接收器，用 `session_id` 和 `client_platform` 标签标记每个系列，并在该会话结束时驱逐会话的系列。直方图不通过，子指标名称与运行器自己的前缀冲突的会被删除。

在默认 `--capacity 1` 处，重写不适用：会话的子进程照常在端口 9464 上绑定自己的 Prometheus 端点。

<h3 id="session-lifecycle-counter-semantics">
  会话生命周期计数器语义
</h3>

`sessions_started_total`、`sessions_completed_total`、`sessions_failed_total` 和 `sessions_interrupted_total` 计数器按会话如何结束对其进行分类。每个生成的会话子进程在生成时增加 `sessions_started_total`，并且在退出时恰好增加其他三个中的一个，因此 `sessions_started_total` 减去其他三个的总和等于当前运行的会话子进程数。

* `completed`：会话干净结束。这涵盖子进程以代码 `0` 自行退出、会话在子进程仍连接时被存档或删除，以及运行器干净地交还槽：在空闲超时、退休时间或 `--kill-session-after-min` 限制处释放会话；启动超时；或轮询循环在子进程退出前注意到的服务器端取消分配。增加 `sessions_completed_total`。
* `failed`：子进程以非零代码自行退出，要么是崩溃，要么是生成后的设置失败。增加 `sessions_failed_total`。
* `interrupted`：运行器因既不是会话成功也不是运行器故障的操作原因终止子进程，例如排空，或终止在 [`SELF_HOSTED_RUNNER_MAX_LIFETIME_GRACE_MS`](#environment-variable-only-settings) 宽限期结束时仍在运行器上的会话，在其 `--kill-session-after-min` 限制之后。Kubernetes 滚动重启发送 `SIGTERM` 是排空的一个示例。增加 `sessions_interrupted_total`。

在 v2.1.260 之前，运行器终止达到其 `--kill-session-after-min` 限制的每个会话，并在 `sessions_interrupted_total` 中计数。

[`post-session` 钩子](/docs/zh-CN/self-hosted-environments-configuration#post-session)的 `CLAUDE_RUNNER_EXIT_REASON` 以不同方式分类干净交接。钩子将释放、启动超时和服务器取消分配报告为 `interrupted`，因为运行器停止了子进程。这些计数器记录与 `completed` 相同的事件，因为槽被干净地交还。

如果您直接根据 `sessions_completed_total` 协调钩子收据，您会低估完成。对每个会话保证使用钩子，对聚合速率使用计数器。

在一次性环境中，`--capacity 1` 与默认 `--drain-grace-sec 0`，每个运行器进程在其一个会话结束后片刻退出。`sessions_completed_total`、`sessions_failed_total` 和 `sessions_interrupted_total` 仅在会话结束时增加，在该退出之前，因此每 15 到 60 秒轮询一次 Prometheus 很少在运行器的系列消失前捕获增加；这三个会话结束计数器是本节其余部分所指的终端计数器。`sessions_started_total` 在生成时增加并在会话的生命周期内保持可见，因此它可靠地显示，但在一次性环境中它读取更接近"当前运行的会话"而不是累积计数。

对应目标使用此表中的系列而不是终端计数器：

| 目标 | 使用 |
| :- | :- |
| 吞吐量 | `claude_code_self_hosted_orchestrator_spawn_hooks_total{result="ok"}`，长期编排器上的计数器，每个成功的 `spawn-runner` 钩子增加一次，在 `rate()` 下保持有意义。它计数钩子调用而不是会话，因此预热和为同一会话重复生成使其与会话计数分散。 |
| 利用率 | `sum(claude_code_self_hosted_runner_active_sessions)` 对 `sum(claude_code_self_hosted_runner_capacity)`，两个仪表在每次抓取时有效，无论运行器生命周期如何 |
| 积压 | `claude_code_self_hosted_orchestrator_pool_pending_sessions` 用于队列深度，以及 `claude_code_self_hosted_orchestrator_queue_circuit_broken_sessions`，如果高于零则发出警报 |
| 失败 | `claude_code_self_hosted_runner_sessions_failed_total`，尽力而为：生成后的真实崩溃确实增加它，`rate()` 在运行器上有意义，这些运行器以 `--drain-grace-sec` 高于 `0` 的方式超过其会话。一次性环境与其他终端计数器具有相同的抓取窗口问题，因此将您看到的任何非零值视为值得调查。生成前的失败，例如 checkout 钩子失败、git 准备或令牌问题，仅出现在 `session_init_errors_total` 中。 |

`orchestrator_*` 行仅存在于运行[按需编排器](/docs/zh-CN/self-hosted-environments-configuration#on-demand-runners)的环境中。在固定舰队上，其运行器以 `--drain-grace-sec` 高于 `0` 的方式超过其会话，对吞吐量使用 `sum(rate(claude_code_self_hosted_runner_sessions_started_total[5m]))`；在一次性舰队上，该系列与终端计数器具有相同的抓取窗口问题，因此依赖排队会话计数。在环境的**活动**选项卡上检查积压，在[**云环境**管理页面](https://claude.ai/admin-settings/cloud-environments)上：运行器不导出队列深度系列。

对于每个会话结果报告，改为使用 [`post-session` 钩子](/docs/zh-CN/self-hosted-environments-configuration#post-session)：它在每个会话结束处触发，其中生成了子进程，除了突然的运行器终止（例如 VM 抢占），根据[钩子自己的合同](/docs/zh-CN/self-hosted-environments-configuration#post-session)。

<h2 id="what’s-next">
  接下来
</h2>

* [自托管环境](/docs/zh-CN/self-hosted-environments)：环境、运行器和会话模型；[快速入门](/docs/zh-CN/self-hosted-environments-quickstart)和[部署到生产](/docs/zh-CN/self-hosted-environments-deploy)包含设置和操作
* [自定义会话](/docs/zh-CN/self-hosted-environments-configuration)：包装脚本、生命周期钩子和按需运行器
* [验证会话身份](/docs/zh-CN/self-hosted-environments-identity)：会话令牌、其声明以及如何验证它

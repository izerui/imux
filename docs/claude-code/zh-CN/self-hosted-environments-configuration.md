> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# 在自托管环境中自定义会话

> 使用包装脚本在自托管环境会话中自定义每个会话的凭证、生命周期钩子和按需运行程序生成。

<Note>
  自托管环境在 Team 和 Enterprise 计划中处于公开测试阶段；[Owner](/docs/zh-CN/cloud-environments#organization-shared-environments) 通过在 [**Cloud environments** 管理页面](https://claude.ai/admin-settings/cloud-environments) 上打开 **Allow self-hosted environments** 来启用它们。本页面假设您已有一个正常运行的运行程序；有关设置，请参阅 [quickstart](/docs/zh-CN/self-hosted-environments-quickstart)，有关 fleet recipes，请参阅 [Deploy to production](/docs/zh-CN/self-hosted-environments-deploy)。
</Note>

[self-hosted environment](/docs/zh-CN/self-hosted-environments) 在您自己的基础设施上运行 Claude Code [cloud sessions](/docs/zh-CN/claude-code-on-the-web)，由您部署的运行程序进程执行。在没有配置的情况下，该运行程序克隆会话的存储库，生成 Claude Code，然后进行清理。本页面适用于操作运行程序的平台工程师：它涵盖了当这些默认值不适用时的扩展点，从每个会话的凭证配置到完全替换检出。包装脚本和钩子作为运行程序主机上的可执行文件运行，该主机是 Linux 或 macOS，本页面上的示例假设使用 POSIX shell。

本页面上的一些钩子环境变量仍然使用 `pool`，例如 `CLAUDE_RUNNER_POOL_ID`；CLI 标志和环境变量名称使用 `environment`，例如 `--environment-secret-file`。

<h2 id="wrapper-scripts">
  包装脚本
</h2>

当每个会话需要运行器无法自行完成的设置时，使用包装脚本：为会话创建者配置作用域的短期凭证、导出特定于环境的密钥、准备语言工具链或围绕子进程应用资源限制。运行器每个会话启动一次您的包装脚本，而不是 Claude Code 二进制文件。通过 `exec` 进入 `$CLAUDE_RUNNER_CLAUDE_BIN`（运行器自己的二进制文件）来结束包装脚本，以便信号和退出代码正确传播。

启动运行器时，使用 `--exec-path` 或 `SELF_HOSTED_RUNNER_EXEC_PATH` 指向包装脚本：

```bash theme={null}
claude self-hosted-runner --environment-secret-file /etc/claude/environment-secret --exec-path /etc/claude/session-wrapper.sh
```

运行器在包装脚本的环境中设置以下内容：

| 变量 | 描述 |
| :- | :- |
| `CLAUDE_CODE_SESSION_ACCESS_TOKEN` | 会话 JWT，前缀为 `sk-ant-cc-`。其 `act` 声明标识会话创建者，包含创建者的电子邮件和上游身份提供者主题（如果创建表面记录了它们）。该值是生成时的令牌；刷新通过子进程的 stdin 到达，因此包装脚本只看到初始值。请参阅 [Verify session identity](/docs/zh-CN/self-hosted-environments-identity)。 |
| `CCR_SESSION_ACCOUNT_EMAIL` | 会话创建者的电子邮件，由运行器从令牌的 `act.email` 声明中预先提取，无需签名验证。适合用于标记，例如提交预告片。当电子邮件控制凭证发放时，验证令牌并从中读取声明；请参阅 [Provision credentials scoped to the session creator](#provision-credentials-scoped-to-the-session-creator)。当令牌不包含创建者电子邮件时未设置。视为个人可识别信息。 |
| `CLAUDE_RUNNER_CLIENT_PLATFORM` | 创建会话的客户端表面，例如 `web_claude_ai`、`desktop_app`、`ios`、`claude_code_cli` 或 `scheduled_trigger`。Anthropic 在会话创建时记录该值一次，因此包装脚本和每个生命周期钩子都看到相同的值。仅将其用于采用分析和标记，不用作授权信号。当会话没有记录或识别的表面时未设置，因此在 `set -u` 下将其引用为 `${CLAUDE_RUNNER_CLIENT_PLATFORM:-}`。需要 Claude Code v2.1.229 或更高版本。 |
| `CLAUDE_RUNNER_CLAUDE_BIN` | 运行器自己的 Claude Code 二进制文件的绝对路径。使用 `exec "$CLAUDE_RUNNER_CLAUDE_BIN" "$@"` 结束您的包装脚本，以移交到固定的二进制文件，而无需硬编码安装路径。 |
| `CLAUDE_CODE_REMOTE_SESSION_ID` | 会话 ID，采用标记的 `cse_...` 形式。这与[生命周期钩子](#lifecycle-hooks)以 `session_...` 形式在 `CLAUDE_RUNNER_SESSION_ID` 中看到的是同一个会话；UUID 变量在两者之间匹配，将 `cse_` 前缀替换为 `session_` 会产生会话 URL 中显示的 ID。 |
| `CLAUDE_CODE_REMOTE_SESSION_UUID` | 相同的会话 ID，采用规范 UUID 形式。 |
| `CLAUDE_SESSION_INGRESS_TOKEN_FILE` | 绝对路径，指向保存当前会话 JWT 的按会话文件，在令牌刷新时保持最新。Shell 子进程在下载用户添加到会话的附件时从中读取其 `Authorization` 标头。`exec` 自动保留该变量；重建子进程环境的包装脚本必须携带该变量，否则附件下载会无声地停止工作。 |
| `CLAUDE_CONFIG_DIR` | 按会话 Claude 配置目录，在会话启动时从运行器在启动时捕获的运行器主机配置快照中写入；请参阅 [Permissions and tool approval](#permissions-and-tool-approval)。此处的写入仅限于此会话。 |
| `ANTHROPIC_BASE_URL` | 子进程将使用的 API 基础 URL，由控制平面按会话交付，通常为 `https://api.anthropic.com`。不要覆盖它：会话的推理凭证是 Anthropic 颁发的 OAuth 令牌，其他提供者不接受，因此自托管环境中的推理无法路由到其他地方。 |
| `CLAUDE_CODE_OAUTH_TOKEN` | 子进程用于模型推理的短期 OAuth 访问令牌，作用域仅限于模型推理和文件上传，生命周期约为 30 分钟。运行器在过期前重新生成它，并通过子进程的 stdin 交付轮换，因此不 [keep stdin attached](#keep-stdin-and-file-descriptor-3-attached) 的包装脚本只看到初始值。不要依赖您的组织 IP 允许列表来限制此令牌的使用：将其视为持有者凭证，如果泄露，大约 30 分钟内仍可使用，不要记录它、写入磁盘或在会话容器外转发它。 |

包装脚本还继承子进程的其余托管环境，包括任何服务器提供的环境变量。`exec` 自动传播所有内容；如果您的包装脚本以其他方式生成子进程，请转发完整环境。

<h3 id="keep-stdin-and-file-descriptor-3-attached">
  保持 stdin 和文件描述符 3 的连接
</h3>

子进程的 stdin 是运行器的控制通道。令牌轮换和会话结束信号在其上到达。运行器还在文件描述符 3 上打开一个管道，并从中读取子进程的活动信号以驱动空闲和启动超时。普通的 `exec "$CLAUDE_RUNNER_CLAUDE_BIN" "$@"` 自动保留两者。

如果您的包装脚本使用裸 `&` 在后台运行子进程，它会切断子进程的 stdin：会话看起来健康，直到初始 OAuth 令牌的大约 30 分钟生命周期过期，然后每个 API 调用都失败，出现 `401 authentication_error`。如果您的包装脚本必须在后台运行子进程，例如保持拆卸陷阱活跃，请在文件描述符 4 或更高版本上保存 stdin 并显式重新连接它：

```bash theme={null}
exec 4<&0
"$CLAUDE_RUNNER_CLAUDE_BIN" "$@" <&4 4<&- &
CHILD=$!
trap 'teardown' EXIT
wait "$CHILD"
```

不要在包装脚本中关闭或重用文件描述符 3。重定向子进程的 stdout 和 stderr 是可以的。

<h3 id="provision-credentials-scoped-to-the-session-creator">
  配置作用域限定为会话创建者的凭证
</h3>

使用 `decode-token` 子命令从会话 JWT 读取声明。它从参数、`CLAUDE_CODE_SESSION_ACCESS_TOKEN` 或 stdin 读取令牌，按该顺序；请参阅 [Verify the token inside the session](/docs/zh-CN/self-hosted-environments-identity#verify-the-token-inside-the-session) 了解它检查的内容。下面的示例解码创建者身份，将其交换为短期 AWS 凭证，并 exec 进入 Claude Code：

```bash theme={null}
#!/bin/bash
# Key on the stable Anthropic user ID and require a human creator.
CREATOR_SUB=$("$CLAUDE_RUNNER_CLAUDE_BIN" self-hosted-runner decode-token \
  | jq -re '.act.sub // "" | select(startswith("user:"))') \
  || { echo "decode-token: verification failed or no human creator" >&2; exit 1; }

creds=$(your-sts-helper assume-role --subject "$CREATOR_SUB") \
  || { echo "credential exchange failed" >&2; exit 1; }
eval "$creds"

exec "$CLAUDE_RUNNER_CLAUDE_BIN" "$@"
```

在提取的声明控制身份验证决策时，使用 `jq -re` 而不是 `jq -r`，以便缺失的声明以非零状态退出，而不是将字面字符串 `null` 传递给下游。由组织服务身份（例如机器人和代理会话）创建的会话携带 `agent:` 主题而不是 `user:`，因此此示例拒绝它们；如果您的环境为这些会话提供服务，请明确决定包装脚本是否为它们回退到默认凭证，而不是退出。当您的凭证交换需要 SSO 主题或电子邮件时，读取 `.act.attested_by.sub` 或 `.act.email` 并处理它们的缺失：令牌仅在创建表面记录它们时才携带它们，[CLI 分派的会话](/docs/zh-CN/self-hosted-environments-testing#run-the-test-loop) 可能两者都缺少。有关完整的声明参考和来自运行器外部服务的验证，请参阅 [Verify session identity](/docs/zh-CN/self-hosted-environments-identity)。

<h2 id="lifecycle-hooks">
  生命周期钩子
</h2>

生命周期钩子用您自己的脚本替换运行器按会话管道的阶段。使用 `--hooks-dir <path>` 或 `SELF_HOSTED_RUNNER_HOOKS_DIR` 将运行器指向钩子目录。运行器查找具有众所周知名称的可执行文件；任何不存在的钩子都会回退到内置行为，因此您只需编写需要的钩子。钩子以运行器自己的权限运行，会话子进程共享该 UID，因此请以只读方式挂载钩子目录，或将其烘焙到镜像中，以便会话代码无法修改它；请参阅 [hardening section](/docs/zh-CN/self-hosted-environments-deploy#harden-your-deployment)。

这些钩子不同于 [Claude Code hooks](/docs/zh-CN/hooks)，后者在会话内运行；生命周期钩子在运行器上运行，围绕会话。

<h3 id="checkout">
  checkout
</h3>

每个存储库运行一次，代替运行器的内置克隆和获取。使用钩子从读通镜像克隆、从存档为工作树设置种子或应用按会话 git 身份验证。运行器设置：

| 变量 | 描述 |
| :- | :- |
| `CLAUDE_RUNNER_REPO_URL` | 要克隆的存储库 URL，在应用任何 `--git-host-rewrite` 和 `--git-ssh-rewrite` 之后 |
| `CLAUDE_RUNNER_REPO_REF` | 要检出的修订版本：分支、标签或提交 SHA，如会话请求的那样。空表示存储库的默认分支。 |
| `CLAUDE_RUNNER_CHECKOUT_PATH` | 必须留下工作树的绝对路径 |
| `CLAUDE_RUNNER_SESSION_ID` | 会话 ID，采用标记的 `session_...` 形式，用于日志记录和关联 |
| `CLAUDE_RUNNER_SESSION_UUID` | 相同的会话 ID，采用规范 UUID 形式 |
| `CLAUDE_RUNNER_API_BASE_URL` | Anthropic API 基础 URL，用于会话范围的调用 |
| `CLAUDE_RUNNER_CLIENT_PLATFORM` | 创建会话的客户端表面，例如 `web_claude_ai`、`desktop_app` 或 `ios`。当会话没有记录或识别的表面时未设置。 |
| `CLAUDE_CODE_SESSION_ACCESS_TOKEN` | 会话访问令牌，用于会话范围的 API 调用 |

脚本必须在 `CLAUDE_RUNNER_CHECKOUT_PATH` 处留下一个工作树，检出到请求的修订版本。分离的 HEAD 是可以的；运行器在其上创建会话的工作分支。运行器之后验证路径包含 `.git`；如果您的钩子具体化非 git 源（例如 Perforce 或解包的 tarball），请在运行器的环境中设置 `CLAUDE_RUNNER_SKIP_GIT_VERIFY=1` 以跳过该检查。基于 Git 的流程（例如工作分支创建和推送结果）需要 git 检出，因此使用 [`post-session` 钩子](#post-session) 从非 git 树导出结果。

运行器不会将 git 凭证传递给钩子。相反，从会话的身份生成按会话克隆凭证：使用标准 JWT 库针对 `CLAUDE_RUNNER_API_BASE_URL` 下的 JWKS 端点验证 `CLAUDE_CODE_SESSION_ACCESS_TOKEN`，如 [Verify the token from your service](/docs/zh-CN/self-hosted-environments-identity#verify-the-token-from-your-service) 中所述，然后让您的凭证服务为令牌的 `act` 声明中的身份发放短期克隆凭证。`CLAUDE_RUNNER_CLAUDE_BIN` 未在 checkout-hook 环境中设置，因此 `decode-token` 子命令在此处不可用。回退到主机已有的任何 git 身份验证（例如 SSH 代理、凭证助手或 `.netrc`）也是一个选项。

当钩子以非零状态退出，或以 0 退出但没有留下可用的检出时，运行器的行为取决于存储库：

* **会话推送结果的存储库**：运行器失败会话，在非零退出时将脚本的 stderr 尾部呈现给用户。
* **会话仅从中读取的存储库**，例如添加到运行会话的存储库：运行器记录带有失败详情的 `[runner:warn]` 行，向会话发布 `Skipped` 步骤，删除钩子在检出路径处留下的任何内容，并继续处理其余存储库。当运行器无法立即删除路径时，它会在会话结束时重试删除。如果跳过使会话完全没有存储库，运行器仍然会失败会话。

在 v2.1.228 之前，运行器对任何存储库的钩子失败都会失败会话，因此钩子无法提供的只读存储库在会话恢复到的每个新运行器上再次失败会话。

运行器在会话结束后删除检出路径。

<h3 id="post-session">
  post-session
</h3>

每个会话运行一次，在 Claude Code 子进程退出后和运行器拆卸工作区之前。此钩子是保存未提交工作的唯一机会：在 `--capacity` 高于 1 时，运行器在钩子返回后立即删除按会话工作树，在 `--capacity 1` 时重用的 [canonical clone](/docs/zh-CN/self-hosted-environments-deploy#reuse-a-pre-warmed-checkout) 在下一个会话启动时硬重置，因此未提交的跟踪更改在两条路径上都不会存活。典型用途是推送未提交更改的快照分支、存档日志或向您自己的系统发出会话结束事件。

钩子在每个会话结束时触发，其中生成了子进程，无论原因如何；下面的 `CLAUDE_RUNNER_EXIT_REASON` 值枚举了这些情况。当运行器突然终止时（例如 VM 抢占或断电）它无法触发；如果您需要针对突然终止的保证，请改为使用 Claude Code `PostToolUse` 钩子从会话内定期快照。运行器设置：

| 变量 | 描述 |
| :- | :- |
| `CLAUDE_RUNNER_SESSION_ID` | 会话 ID，采用标记的 `session_...` 形式 |
| `CLAUDE_RUNNER_SESSION_UUID` | 相同的会话 ID，采用规范 UUID 形式 |
| `CLAUDE_RUNNER_EXIT_REASON` | 会话如何结束；请参阅表下方的值 |
| `CLAUDE_RUNNER_WORKSPACE_PATHS` | 会话工作树的冒号分隔绝对路径。对于零存储库会话为空。 |
| `CLAUDE_RUNNER_DEBUG_LOG_PATH` | 会话的调试日志的路径，在钩子运行时仍在磁盘上 |
| `CLAUDE_RUNNER_API_BASE_URL` | Anthropic API 基础 URL，用于会话范围的调用 |
| `CLAUDE_RUNNER_CLIENT_PLATFORM` | 创建会话的客户端表面，例如 `web_claude_ai`、`desktop_app` 或 `ios`。当会话没有记录或识别的表面时未设置。需要 Claude Code v2.1.229 或更高版本。 |
| `CLAUDE_CODE_SESSION_ACCESS_TOKEN` | 会话访问令牌，用于会话范围的 API 调用 |

`CLAUDE_RUNNER_EXIT_REASON` 采用四个值之一：

* `completed`：会话干净地结束。Claude Code 进程正常退出，或会话在仍在运行时被存档或删除。
* `failed`：Claude Code 进程崩溃，或在启动后设置失败。
* `interrupted`：运行器停止了会话。它释放了会话以释放插槽、会话在启动时超时、服务器将会话移出此运行器、运行器正在排空，或会话超过了其 [`--kill-session-after-min`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags) 限制。
* `abandoned`：为另一个运行器声称的会话保留。钩子目前在这种情况下不触发。

[session lifecycle counters](/docs/zh-CN/self-hosted-environments-reference#session-lifecycle-counter-semantics) 将释放、启动超时和服务器移动计为 `completed` 而不是 `interrupted`，因为运行器干净地交还了插槽。如果您将钩子收据与计数器进行比较，请预期这种差异。

钩子的退出状态永远不会影响会话结果；失败被记录并忽略。运行器在每个会话结束（包括运行器关闭）时等待最多 `--post-session-hook-timeout-sec`（默认 60 秒）。此示例将未提交的工作保存到救援分支：

```bash theme={null}
#!/usr/bin/env bash
set -u
IFS=':'
# Pin config the session could have planted in the checkout's .git/config:
# -c overrides beat repo-local settings, blocking session-written fsmonitor,
# hook-path, and gpg-program config from executing code with the hook's
# privileges. Repo-local credential.helper, core.sshCommand, and pushurl
# still apply; if the hook holds credentials the session didn't, pin the
# push URL and helper too (see the note below the script).
g() { git -c core.fsmonitor=false -c core.hooksPath=/dev/null \
        -c commit.gpgsign=false "$@"; }
for ws in $CLAUDE_RUNNER_WORKSPACE_PATHS; do
  cd "$ws" 2>/dev/null || continue
  [ -z "$(g status --porcelain 2>/dev/null)" ] && continue
  g add -A
  g commit -q -m "runner snapshot: $CLAUDE_RUNNER_SESSION_ID ($CLAUDE_RUNNER_EXIT_REASON)" || continue
  g push -q origin "HEAD:refs/heads/rescue/$CLAUDE_RUNNER_SESSION_ID" || true
done
```

钩子使用运行器主机上其自己环境中可用的任何 git 凭证进行推送。在 [no-credentials-in-the-image posture](/docs/zh-CN/self-hosted-environments-deploy#configure-git) 下，包括当内置克隆通过 Anthropic git 代理时，没有凭证，因此在推送前在钩子内生成短期推送凭证：将钩子在 `CLAUDE_CODE_SESSION_ACCESS_TOKEN` 中接收的会话令牌与您自己的令牌服务交换，如 [Verify session identity](/docs/zh-CN/self-hosted-environments-identity) 所述进行验证，然后让您的凭证服务为令牌的 `act` 声明中的身份发放短期推送凭证。当钩子持有会话没有的凭证时，也要固定它推送的位置：将 `origin` 替换为操作员提供的 URL，并传递 `-c credential.helper=` 加上您自己的助手，以便会话写入的 repo-local 配置无法重定向凭证推送。

<h4 id="hook-timing-when-the-runner-releases-a-session">
  运行器释放会话时的钩子时序
</h4>

已释放的会话可以在另一个运行器上恢复。在 v2.1.236 或更高版本的运行器上，会话在释放时所做的事情决定了它是否可以在此钩子完成前在另一个运行器上恢复：

* **在轮次后空闲，或在启动时超时**：运行器停止子进程并运行此钩子至完成。只有这样它才会释放会话。在钩子运行时发送的用户消息无法在钩子完成前在另一个运行器上恢复会话。
* **等待用户回答提示，例如权限提示**：运行器首先释放会话，然后运行此钩子。在钩子运行时发送的用户消息可以在钩子完成前在另一个运行器上恢复会话。

这适用于运行器释放会话的任何时候：在空闲超时、在 [`--retire-at`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags) 时间，以及 在 v2.1.260 或更高版本的运行器上，在会话的 [`--kill-session-after-min`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags) 限制。其轮次已结束且仅持有后台任务的会话在此处计为空闲。在 v2.1.236 之前，运行器在两种情况下都首先释放会话，然后运行此钩子。

在 `SIGTERM` 排空期间，运行器持有会话租约直到钩子完成；请参阅 [Shutdown timing](/docs/zh-CN/self-hosted-environments-deploy#shutdown-timing)。

<h3 id="command">
  command
</h3>

每个会话在检出后运行一次，代替内置子进程生成。钩子接收与 [wrapper script](#wrapper-scripts) 相同的环境，应该以相同的方式 `exec` 进入 `"$CLAUDE_RUNNER_CLAUDE_BIN"`。使用 `command` 钩子将所有自定义保留在一个钩子目录中；当包装脚本在其他地方时使用 `--exec-path`。如果也设置了 `--exec-path`，标志优先，`command` 钩子被忽略。

始终 `exec` 运行器自己的二进制文件，而不是 PATH 解析的 `claude`；否则您会破坏 [version pinning](/docs/zh-CN/self-hosted-environments-deploy#pin-the-version)。

<h2 id="on-demand-runners">
  按需运行器
</h2>

您可以为每个会话启动一个运行器，而不是运行固定的队列。编排器是一个单独的、无状态的子命令，它轮询 Anthropic 以获取生成请求（每个没有可用运行器的排队会话一个），并为每个运行您的 `spawn-runner` 钩子。您的钩子向您的平台提交工作负载：Kubernetes Job、EC2 实例、Nomad dispatch。

按需运行器改进了凭证卫生。在固定队列上，环境密钥存在于每个运行器主机上，这是运行用户会话的同一主机。使用编排器，环境密钥仅保留在编排器主机上，该主机从不运行用户代码；每个生成的运行器接收一个单次使用的工作单，恰好注册一个运行器，然后过期。

要启动编排器，请传递环境密钥和包含可执行 `spawn-runner` 脚本的钩子目录：

```bash theme={null}
claude self-hosted-runner orchestrator \
  --environment-secret-file /etc/claude/environment-secret \
  --hooks-dir /etc/claude/hooks
```

编排器在轮询之间保持无状态，因此您可以针对同一环境运行两个或多个副本以实现可用性。每个生成请求由服务器端的恰好一个副本声称。所有副本必须使用相同的 `--expected-spawn-seconds` 值；请参阅 [hook contract](#the-spawn-runner-hook)。

<h3 id="the-spawn-runner-hook">
  spawn-runner 钩子
</h3>

编排器为每个生成请求运行一次 `${hooks-dir}/spawn-runner`。钩子必须异步提交工作，不等待运行器启动，并在 `--hook-timeout`（默认 60 秒）内返回。钩子接收：

| 变量 | 描述 |
| :- | :- |
| `CLAUDE_RUNNER_WORK_ORDER_FILE` | 包含新运行器注册的已签名工作单 JWT 的临时文件的路径。钩子退出后删除。不要记录文件的内容。 |
| `CLAUDE_RUNNER_ORDER_ID` | 不透明的幂等性密钥，每个生成请求唯一，对 Kubernetes 资源名称安全。将其用作您的配置器的去重密钥。 |
| `CLAUDE_RUNNER_SESSION_ID` | 此请求所针对的会话。对于预热请求为空，当设置 [`--min-idle`](/docs/zh-CN/self-hosted-environments-reference#orchestrator-cli-flags) 时启动待命运行器，在任何特定会话之前，因此不要假设变量已设置。 |
| `CLAUDE_RUNNER_SESSION_UUID` | 相同的会话 ID，采用规范 UUID 形式。对于预热请求为空。 |
| `CLAUDE_RUNNER_ATTEMPT` | 此会话已有多少个生成请求。对于预热请求为 `0`。 |
| `CLAUDE_RUNNER_ORDER_SERVER_TIME` | 来自轮询响应的 HTTP `Date` 标头的服务器时间。当钩子验证工作单 JWT 的 `exp` 时，与此值进行比较而不是本地时钟，以容忍时钟偏差。当网关省略标头时为空。 |
| `CLAUDE_RUNNER_POOL_ID` | 新运行器应加入的环境的 ID，采用 `ccpool_...` 形式 |
| `CLAUDE_RUNNER_ACCOUNT_ID` | 排队会话的帐户的标记 ID，用于按帐户路由、配额或退款。当不可用时为空，对于 Claude Tag 频道会话始终为空，这些会话没有帐户排队。 |
| `CLAUDE_RUNNER_ACCOUNT_EMAIL` | 排队会话的帐户的电子邮件。当不可用时为空。将电子邮件视为个人可识别信息，不要记录它。 |
| `CLAUDE_RUNNER_PRIMARY_REPO_URL` | 会话的第一个 git 源的 URL，用于路由到具有该存储库预热的运行器。当会话没有 git 源时为空。 |
| `CLAUDE_RUNNER_PRIMARY_REPO_REVISION` | 会话的第一个 git 源的修订版本：分支、SHA 或标签。当未指定时为空。 |
| `CLAUDE_RUNNER_REPO_SOURCES` | 所有会话的 git 源的 `{url, revision}` 的 JSON 数组，用于在辅助存储库上路由的钩子。当没有源时为空。 |
| `CLAUDE_RUNNER_CORRELATION_ID` | 在会话创建时提供的关联 ID，回显以便钩子可以将此工作单映射到创建会话的请求。当会话没有时为空。 |
| `CLAUDE_RUNNER_CLIENT_PLATFORM` | 创建会话的客户端表面，例如 `web_claude_ai`、`desktop_app`、`ios` 或 `scheduled_trigger`，用于采用分析。当会话没有记录或识别的表面时未设置，对于预热请求也未设置；使用 `[ -n "${CLAUDE_RUNNER_CLIENT_PLATFORM:-}" ]` 检查它，这在 `set -u` 下保持安全。 |

生成的运行器使用工作单代替环境密钥进行注册：

* **使用工作单启动它**：将 [`--environment-secret-file`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags) 指向包含工作单 JWT 的文件，或将 `SELF_HOSTED_RUNNER_ENVIRONMENT_SECRET` 设置为 JWT 值。
* **在钩子退出前复制 JWT**：编排器在钩子退出后删除工作单文件，因此将 JWT 复制到您提交的工作负载中，例如生成的 Job 上的 Kubernetes Secret，而不是通过文件路径。
* **在生成的运行器上使用 `--capacity 1`**：会话绑定的工作单恰好注册一个绑定到该会话的运行器，因此更高的容量添加永远不会接收工作的插槽，运行器在启动时记录警告。
* **预热工作单注册未绑定**：待命运行器未绑定到会话，并像固定队列运行器一样声称排队的工作。

合同有四个配置器不可知的规则：

1. **在 `CLAUDE_RUNNER_ORDER_ID` 上是幂等的。** 相同请求的重新交付必须最多生成一个运行器。从 ID 派生确定性资源名称，让您的平台拒绝重复。
2. **不要重试工作负载。** 一个订单 ID 意味着最多创建一个工作负载。如果运行器从不注册，Anthropic 在 `--expected-spawn-seconds` 后使用新订单 ID 重新请求。
3. **使用退出代码合同。** 退出 0 表示已提交。退出 1 表示可重试失败；会话退避并被重新提供。退出 2 或更高表示不可重试；会话被阻止再次生成，直到 [Owner](/docs/zh-CN/cloud-environments#organization-shared-environments) 在环境的 **Activity** 标签中选择 **Retry**。在非零退出时，钩子的 stderr 尾部出现在那里作为失败原因，因此将可操作的错误写入 stderr，永远不要写密钥。对于预热请求，没有会话失败：编排器仅在本地记录非零退出，服务器在租约后重新请求生成。
4. **将 `--expected-spawn-seconds` 设置为至少您的 p99 启动时间。** 这是服务器端租约。所有编排器副本必须使用相同的值。

钩子写入 stdout 或 stderr 的所有内容都出现在编排器的日志中，凭证自动删除。如果会话保持排队，检查编排器的 `/healthz` 正文以获取队列计数，然后在 [**Cloud environments** 管理页面](https://claude.ai/admin-settings/cloud-environments) 上打开您的环境的 **Activity** 标签：在那里展开失败的会话以获取其生成错误，并选择 **Retry** 以重新请求它。

<h2 id="mcp-servers">
  MCP 服务器
</h2>

要在每个会话中提供 [MCP servers](/docs/zh-CN/mcp)，请在镜像构建时使用与桌面安装上使用的相同 `claude mcp add` 命令添加它们。如果您的运行器是裸进程而不是容器，请在主机上以运行器的用户身份运行相同的命令，然后重启运行器：它在启动时读取主机配置一次。`--scope user` 标志是必需的；默认本地作用域写入运行器不播种的按目录密钥下。例如，在您的 Dockerfile 中：

```dockerfile theme={null}
RUN claude mcp add --scope user sidecar -- /usr/local/bin/mcp-sidecar
RUN claude mcp add --scope user --transport http internal http://mcp-gateway.svc.cluster.local:8080
```

运行器在启动时快照主机的配置一次。快照从主机的 `.claude.json` 捕获 `mcpServers` 密钥，该密钥位于 `~/.claude/` 旁边而不是内部，运行器仅将该密钥播种到每个会话的隔离配置中；帐户状态和项目历史被删除。要确认服务器到达会话，请在环境上启动会话并要求 Claude 列出其 MCP 工具；运行器还为任何捕获的条目记录启动警告，其 `type` 它不识别并删除条目，因此您可以看到为什么该服务器从会话中丢失。当设置 `SELF_HOSTED_RUNNER_HOST_CONFIG_DIR` 时，运行器从该目录读取 `.claude.json` 而不是，因此将变量指向空目录也禁用 MCP 播种。

Claude Code 还从其他源加载 MCP 服务器：

* 企业范围的 [managed MCP file](/docs/zh-CN/managed-mcp) 在其标准系统路径：Linux 运行器主机上的 `/etc/claude-code/managed-mcp.json`，macOS 主机上的 `/Library/Application Support/ClaudeCode/managed-mcp.json`。将其用于锁定的队列，其中只有管理员列出的服务器可能加载。有关优先级规则，请参阅 [exclusive control with managed-mcp.json](/docs/zh-CN/managed-mcp#exclusive-control-with-managed-mcp-json)。当此文件在运行器主机上时，Claude Code 跳过 Anthropic 的控制平面交付给会话的 MCP 服务器（包括 claude.ai 连接器），并在会话子进程的 stderr 上命名它们，运行器在 `debug` 日志级别记录。在 v2.1.229 之前，这些会话在启动时以 `You cannot dynamically configure MCP servers when an enterprise MCP config is present` 退出。
* 运行器主机上 [managed settings](/docs/zh-CN/managed-settings) 中的 [`managedMcpServers`](/docs/zh-CN/settings-reference#managedmcpservers) 密钥：提供 HTTP 和 SSE 服务器而不获得独占控制，因此来自其他源的服务器仍然加载。需要 Claude Code v2.1.259 或更高版本。
* `<repo>/.mcp.json`：项目范围。将文件提交到存储库；其服务器在云会话中自动批准。

当为您的组织启用连接器交付时，Anthropic 的控制平面将您在 claude.ai 上配置的连接器交付给通过服务器提供的 MCP 配置路由的交互式创建的会话，通过 `api.anthropic.com` 路由。以编程方式创建的会话（例如 [CLI dispatches](/docs/zh-CN/self-hosted-environments-testing#run-the-test-loop)）不接收连接器交付；通过本节列出的任何其他源为它们提供 MCP 服务器。子进程的 OAuth 令牌不携带直接获取连接器的作用域，因此子进程不尝试该获取本身；交付是服务器驱动的。

`settings.json` 不携带 MCP 服务器定义，设置架构中没有顶级 `mcpServers` 字段。在托管设置中，使用 [`managedMcpServers`](/docs/zh-CN/settings-reference#managedmcpservers) 密钥提供服务器。

会话继承运行器的环境，因此在那里设置 [`ENABLE_TOOL_SEARCH`](/docs/zh-CN/mcp#scale-with-mcp-tool-search) 以控制运行器生成的每个会话的 MCP 工具搜索；MCP 页面涵盖了这些值。

<h2 id="prompt-sessions-to-push-their-work">
  提示会话推送其工作
</h2>

Anthropic 托管的会话运行 [`Stop` hook](/docs/zh-CN/hooks#stop)，Claude Code 钩子在 Claude 完成响应时运行，提示 Claude 提交并推送其工作。运行器不安装一个。没有它，以未提交更改结束的会话仅在运行器的磁盘上留下该工作，claude.ai/code 中的 **Create PR** 按钮保持不活跃，直到分支存在于远程。

下面的参考实现有两部分。将设置块合并到运行器主机上的 `~/.claude/settings.json` 中，运行器将其播种到每个会话中，并将脚本保存为运行器主机上的 `~/.claude/hooks/stop-hook-nudge.sh` 并使其可执行：

```json theme={null}
{
  "hooks": {
    "Stop": [
      {
        "hooks": [
          {
            "type": "command",
            "timeout": 10,
            "command": "\"$CLAUDE_CONFIG_DIR/hooks/stop-hook-nudge.sh\""
          }
        ]
      }
    ]
  }
}
```

```sh theme={null}
#!/bin/sh
# Stop-hook reference implementation for self-hosted runners.
#
# Nudges Claude once per turn if the project directory has uncommitted
# changes OR unpushed commits, so work isn't lost when an idle session
# is released and so the "Create PR" button on claude.ai/code lights up.
#
# Runner-level (no repo changes): drop this file at ~/.claude/hooks/ on
# the runner host and merge the accompanying Stop-hook settings block
# into ~/.claude/settings.json — the runner seeds both into every session.
# Repo-level alternative: commit to <repo>/.claude/hooks/ and change the
# settings.json command path to $CLAUDE_PROJECT_DIR/.claude/hooks/.
#
# stdin: hook JSON payload (see https://code.claude.com/docs/en/hooks)
# stdout: {"decision":"block","reason":"..."} to nudge, or nothing to allow stop.

# Re-entry guard: the harness sets stop_hook_active=true when re-invoking
# the Stop hook after a block. Bail so we only nudge once per turn. The
# harness emits compact JSON (no space after the colon), which this
# pattern relies on; use jq if you need a whitespace-tolerant check.
in=$(cat)
case "$in" in *'"stop_hook_active":true'*) exit 0 ;; esac

d="$CLAUDE_PROJECT_DIR"

# Not a git repo → nothing to nudge.
git -C "$d" rev-parse --git-dir >/dev/null 2>&1 || exit 0

# No remote → "push to the remote" is unsatisfiable; bail.
[ -z "$(git -C "$d" remote 2>/dev/null)" ] && exit 0

# Uncommitted changes (staged, unstaged, or untracked). Exclude .claude/
# entirely — operator-seeded settings and CLI-written runtime state
# (scheduler lock, worktrees, routine state) live there and neither is
# "uncommitted work" the model needs to push.
s=$(git -C "$d" status --porcelain -- . ':(exclude).claude/' 2>/dev/null)
if [ -n "$s" ]; then
  printf '{"decision":"block","reason":"There are uncommitted changes in the repository. Please commit and push these changes to the remote branch."}'
  exit 0
fi

# Unpushed commits. Count commits on HEAD not reachable from any
# remote-tracking ref or FETCH_HEAD. This works uniformly for:
#   - init+fetch checkouts (runner default: only FETCH_HEAD exists)
#   - clone-based checkouts (origin/* exist)
#   - the runner default: the child starts on the session's outcome
#     branch, which the runner creates after checkout
#   - detached HEAD, when a custom setup skips that branch creation
# With no reference point at all (never fetched), stay silent rather
# than false-positive on a read-only turn.
base=""
git -C "$d" rev-parse --verify -q FETCH_HEAD >/dev/null && base="FETCH_HEAD"
if [ -z "$base" ] && [ -z "$(git -C "$d" for-each-ref --count=1 refs/remotes/origin 2>/dev/null)" ]; then
  exit 0
fi
# shellcheck disable=SC2086  # $base is either "" or "FETCH_HEAD", intentional word-split
unpushed=$(git -C "$d" rev-list HEAD --not $base --remotes=origin --count 2>/dev/null) || unpushed=0
if [ "$unpushed" -gt 0 ]; then
  branch=$(git -C "$d" symbolic-ref --short -q HEAD)
  if [ -n "$branch" ]; then
    # $branch is attacker-influenced — git-check-ref-format(1) allows `"`
    # in ref names. `\` is forbidden (rule 10) but escaped anyway as cheap
    # defense-in-depth.
    # Escape JSON metacharacters before interpolating into the hand-built
    # payload so a branch like x","continue":false can't inject keys into
    # the hook-output JSON the harness parses. $unpushed is safe — the
    # -gt guard above rejects anything that isn't a plain integer.
    branch_esc=$(printf '%s' "$branch" | sed 's/\\/\\\\/g; s/"/\\"/g')
    printf '{"decision":"block","reason":"There are %s unpushed commit(s) on branch '\''%s'\''. Please push these changes to the remote repository."}' "$unpushed" "$branch_esc"
  else
    printf '{"decision":"block","reason":"There are %s unpushed commit(s) on a detached HEAD. Please create a branch and push it to the remote repository."}' "$unpushed"
  fi
  exit 0
fi

exit 0
```

钩子在会话结束前提示 Claude 提交并推送，当目录不是 git 存储库或没有远程时保持沉默。

<h2 id="permissions-and-tool-approval">
  权限和工具批准
</h2>

自托管会话没有连接的终端，因此未回答的权限提示会停止轮次，直到用户在 UI 中响应。Anthropic 的控制平面使用工作负载发送每个会话的工具列表和权限规则；默认配置预批准例行工具调用（包括 `Bash`），云会话 [pre-approve file edits regardless of mode](/docs/zh-CN/permission-modes#switch-permission-modes)。没有任何东西预批准的调用通过会话 UI 提示。

<Note>
  仅在会话容器运行 [default-deny network egress](/docs/zh-CN/self-hosted-environments-deploy#default-deny-egress) 和 [hardening section](/docs/zh-CN/self-hosted-environments-deploy#harden-your-deployment) 中其余部分的环境上固定自动模式。例行工具调用（包括 `Bash` 网络请求）在默认预批准工具集和自动模式中都无需人工干预运行，因此网络边界是限制这些调用可以到达的位置的原因。
</Note>

要无论控制平面发送什么都将提示保持在最低限度，请从您的包装脚本或 [`command` 钩子](#command) 固定 [auto mode](/docs/zh-CN/permission-modes#eliminate-prompts-with-auto-mode)。自动模式让会话无需例行权限提示运行：单独的分类器模型在运行前审查操作并阻止它拒绝的操作，显式询问规则仍然强制提示；权限模式页面涵盖分类器检查的内容。运行器在调用包装脚本前追加服务器计算的标志，对于单值标志（如 `--permission-mode`），解析器尊重最后出现的标志，因此您在 `"$@"` 后追加的标志覆盖服务器发送的值：

```bash theme={null}
#!/bin/bash
exec "$CLAUDE_RUNNER_CLAUDE_BIN" "$@" --permission-mode auto
```

要预批准特定工具，请改为追加 `--allowed-tools` 和您的规则，例如 `--allowed-tools "Bash(bazel *) Bash(yarn *) mcp__internal__*"`。列表标志（如 `--allowed-tools` 和 `--disallowed-tools`）在出现时累积而不是覆盖，因此您的规则应用在控制平面发送的任何规则之上。要缩小范围，请追加 `--disallowed-tools`，即使另一个规则允许工具也拒绝工具。

<h3 id="how-each-session’s-config-is-assembled">
  每个会话的配置如何组装
</h3>

运行器为每个会话提供自己的配置目录，从运行器在启动时捕获的主机 `~/.claude/` 的快照中播种：`settings.json`、`CLAUDE.md`、钩子、代理、命令和技能在您的运行器镜像中应用于每个会话作为用户级基线。如果您更改运行主机上的配置，更改仅在您重启运行器后生效。

设置 `SELF_HOSTED_RUNNER_HOST_CONFIG_DIR` 以从不同路径播种，或将其指向空目录以禁用播种。

存储库提交的 `.claude/settings.json` 作为项目设置分层。会话还从运行器镜像中的标准系统路径读取 [`managed-settings.json`](/docs/zh-CN/settings#where-settings-live)。其密钥是否与 [server-managed settings](/docs/zh-CN/server-managed-settings) 一起应用遵循 [how Claude Code combines managed sources](/docs/zh-CN/managed-settings#how-claude-code-combines-managed-sources)：默认情况下，当您的组织交付任何服务器管理的密钥时，会话忽略运行器镜像的文件，除了 [keys Claude Code reads from every admin source](/docs/zh-CN/managed-settings#keys-read-from-every-admin-source)，例如 `env` 块、沙箱锁、沙箱二进制路径和 `forceRemoteSettingsRefresh`。请参阅 [settings precedence](/docs/zh-CN/settings#settings-precedence)。

当 Anthropic 的控制平面为会话提供 [Claude Code hooks](/docs/zh-CN/hooks) 时，运行器将它们安装在旁边，而不是覆盖您自己的配置。需要 Claude Code v2.1.229 或更高版本。

* **它们落在哪里**：运行器将每个提供的钩子脚本写入会话配置目录的保留 `hooks/.ccr-launcher/` 子目录，并在单独的设置文件中注册脚本，它使用 `--settings` 传递给会话，保留播种的 `settings.json` 和您自己的脚本在 `hooks/<name>` 不变。运行器为每个会话重新创建保留的子目录，不播种主机内容在 `~/.claude/hooks/.ccr-launcher/` 到会话。
* **谁编写它们**：控制平面从其自己部署中的固定常量填充脚本，永远不从按会话或第三方输入。
* **什么仍然管理它们**：通过 `--settings` 交付的钩子进入普通合并的钩子配置，而不是托管层，因此您的托管设置仍然适用。`disableAllHooks` 禁用它们，它们不在 [`allowManagedHooksOnly`](/docs/zh-CN/settings-reference#allowmanagedhooksonly) 保持加载的类别中。

<h3 id="repository-committed-permission-rules">
  存储库提交的权限规则
</h3>

不要在存储库提交的 `permissions.allow` 中放置裸 `"Edit"`、`"Write"` 或 `"NotebookEdit"` 条目。裸文件工具规则匹配工具，无论路径如何，授予主机任何地方的写入而不仅仅是工作区，因此运行器的写入范围限制守卫标记会话；使用 [`--confine-repo-settings enforce`](/docs/zh-CN/self-hosted-environments-reference#runner-cli-flags) 它拒绝生成会话而不是记录并继续。请参阅 [hardening section](/docs/zh-CN/self-hosted-environments-deploy#harden-your-deployment)。

存储库根本不需要文件工具规则：云会话 [pre-approve file edits regardless of mode](/docs/zh-CN/permission-modes#switch-permission-modes)。如果您确实提交规则，将其作用域限制到工作区，例如 `"Edit(/**)"`；单个前导斜杠相对于项目根目录，这是会话的工作区。裸文件工具规则在操作员的主机级 `settings.json` 中很好，因为该文件不是存储库提交的。

`defaultMode` 为 `auto` 仅从镜像范围或用户级设置文件中受尊重，因此检出的存储库无法为自己授予自动模式。有关云会话接受的模式和完整规则语法，请参阅 [permission modes](/docs/zh-CN/permission-modes)。

<h2 id="what’s-next">
  接下来
</h2>

* [Reference](/docs/zh-CN/self-hosted-environments-reference)：每个 CLI 标志、环境变量和指标
* [Verify session identity](/docs/zh-CN/self-hosted-environments-identity)：从运行器外部的服务验证会话令牌

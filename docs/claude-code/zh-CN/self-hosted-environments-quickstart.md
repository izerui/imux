> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# 自托管环境快速入门

> 设置您的第一个自托管环境：安装 Claude Code、创建环境、启动运行器，并将会话路由到该环境。

<Note>
  自托管环境在 Team 和 Enterprise 计划上处于公开测试阶段；[可用性和限制](/docs/zh-CN/self-hosted-environments#availability-and-limitations)涵盖了启用路径。本页面让您的第一个会话运行；有关它们是什么，请参阅[自托管环境](/docs/zh-CN/self-hosted-environments)，有关强化和部队配方，请参阅[部署到生产环境](/docs/zh-CN/self-hosted-environments-deploy)。
</Note>

[自托管环境](/docs/zh-CN/self-hosted-environments)在您的组织运营的基础设施上运行 Claude Code [云会话](/docs/zh-CN/claude-code-on-the-web)，由您部署的运行器进程执行。本快速入门建立您的第一个环境，这是最小的可行配置：一个主机上的一个运行器，运行一个测试会话。有两个步骤：[创建环境、启动运行器并将会话路由到该环境](#set-up-an-environment-and-runner)，然后[从您的终端向该会话发送后续消息](#send-a-follow-up-message-to-a-running-session)。您将在两个界面之间切换：claude.ai 用于创建环境、检查其状态和路由会话，以及主机上的终端用于运行器执行的所有操作。

完成后，您将在[**Cloud environments** 管理页面](https://claude.ai/admin-settings/cloud-environments)上拥有一个环境、一个轮询工作的运行器，以及在您的主机上运行的会话。在连接真实存储库或内部系统之前，请完成[部署到生产环境](/docs/zh-CN/self-hosted-environments-deploy)，其中涵盖了安全态势、出口控制、git 凭证和编排。

<h2 id="prerequisites">
  前置条件
</h2>

<h3 id="organization-and-roles">
  组织和角色
</h3>

claude.ai 端需要：

* **Allow self-hosted environments** 由[所有者](/docs/zh-CN/cloud-environments#organization-shared-environments)在[**Cloud environments** 管理页面](https://claude.ai/admin-settings/cloud-environments)上启用；在启用之前，**New** 按钮不会出现。如果您不拥有该角色，拥有该角色的人可以创建环境并将其密钥交给您；本页面上的运行器和终端步骤不需要 claude.ai 角色，当步骤在管理 UI 中检查状态时，运行器自己的日志行会给您相同的信号。
* 您的组织的 [GitHub 连接](/docs/zh-CN/claude-code-on-the-web#github-authentication-options)，以便开发人员在启动会话时可以选择存储库。

<h3 id="host-and-network">
  主机和网络
</h3>

运行器主机需要：

* 一个 Linux 或 macOS 主机或容器，具有到 `api.anthropic.com` 的出站 HTTPS、到 `claude.ai` 和下面安装步骤重定向到的下载主机的出站 HTTPS，以及到您的 git 主机的出站 HTTPS 用于克隆；[网络要求表](/docs/zh-CN/self-hosted-environments-deploy#network-requirements)有完整列表。Windows 不支持作为运行器主机；改为在 Linux 容器中运行运行器。开发人员工作站不受影响，因为会话从浏览器中的 claude.ai 启动。
* 与实时同步的时钟，例如使用 NTP。当时钟偏离超过五分钟时，身份验证失败；请参阅[故障排除](/docs/zh-CN/self-hosted-environments-deploy#troubleshooting)。

<h3 id="software-on-the-runner-host">
  运行器主机上的软件
</h3>

在启动之前在主机上安装：

* **Claude Code v2.1.224 或更高版本**，使用任何[标准安装方法](/docs/zh-CN/setup)。运行器是标准 `claude` 二进制文件的一部分，较早的版本不识别 `self-hosted-runner` 子命令。本机安装程序的默认 `latest` 频道在发布后立即携带每个版本；`stable` 频道、Homebrew `claude-code` cask 和稳定的 apt、dnf 和 apk 存储库滞后约一周。要固定您的部队运行的确切版本，请参阅[安装特定版本](/docs/zh-CN/setup#install-a-specific-version)。对于容器镜像，请参阅[部署到生产环境](/docs/zh-CN/self-hosted-environments-deploy#build-the-runner-image)中的 Dockerfile。
* **Git 2.24 或更高版本**。部署页面上的某些 git 选项需要更高版本；[配置 git](/docs/zh-CN/self-hosted-environments-deploy#configure-git)说明了每个下限。

确认主机已准备好：

```bash theme={null}
claude self-hosted-runner --help
```

准备好的主机打印运行器的使用文本，列出诸如 `--environment-secret-file` 之类的标志。在 2.1.224 之前的版本上，该命令改为打印常规 `claude --help` 输出；使用 `claude update` 升级或从 `latest` 频道重新安装。

<h2 id="set-up-an-environment-and-runner">
  设置环境和运行器
</h2>

Claude Code 包括一个引导式设置：一个交互式 Claude Code 会话，引导您在管理 UI 中创建环境、使用您保存的密钥文件启动本地运行器、确认运行器注册，并将速查表写入 `./runner-setup/CHEAT-SHEET.md`。在您已使用拥有所有者角色的帐户使用 `claude auth login` 登录的机器上运行它；它不适用于 API 密钥或第三方模型提供商。在无法进行交互式会话的主机上，改为使用下面的手动步骤。首先确认[版本检查](#software-on-the-runner-host)通过：在 2.1.224 之前的版本上，此命令启动一个普通的 Claude 会话，将这些词作为提示而不是引导式设置。要启动引导式设置，请运行设置子命令并按照提示进行：

```bash theme={null}
claude self-hosted-runner setup
```

要改为手动设置：

<Steps>
  <Step title="创建环境">
    转到管理设置中的[**Cloud environments** 页面](https://claude.ai/admin-settings/cloud-environments)。在 **Self-hosted environments** 下，选择 **New**，命名环境，然后选择 **Create**。在向导的第二步，选择 **Copy environment key** 以复制环境密钥，管理 UI 将其标记为环境密钥。claude.ai 仅显示一次密钥，您之后无法检索它；它在创建后 365 天过期。环境的 `ccpool_...` ID 在其详细信息对话框中保持可见；您需要它用于[令牌验证](/docs/zh-CN/self-hosted-environments-identity)中的 `aud` 检查，以及用于从 CI [分派测试会话](/docs/zh-CN/self-hosted-environments-testing#run-the-test-loop)。

    如果您丢失了密钥或需要轮换它，请从环境的 **Configuration** 选项卡创建新密钥，将新密钥推出到您的运行器，然后撤销旧密钥。持有已撤销密钥的运行器在其下一次经过身份验证的轮询时失败并退出，记录 `poll auth failed`，您的编排器使用新密钥重新启动它们。
  </Step>

  <Step title="启动运行器">
    创建密钥目录。此步骤和下一步需要 root 用于 `/etc/claude` 路径；运行器进程可以读取的任何路径都有效，因此如果您使用不同的路径，请一起调整两个命令和 `--environment-secret-file` 值。

    ```bash theme={null}
    mkdir -p /etc/claude
    ```

    将环境密钥写入文件。下面的命令从您的终端读取，以便密钥保持在 shell 历史记录之外：粘贴您复制的值，按 Enter，然后按 Ctrl-D，子 shell 的 `umask` 使文件仅可由其所有者读取。

    ```bash theme={null}
    (umask 077 && cat > /etc/claude/environment-secret)
    ```

    选择一个基目录，将下面运行器命令中的 `<writable-dir>` 替换为运行器可以写入或创建的绝对路径。运行器在启动时创建目录，然后检查存储库并在其下创建每个会话的目录。没有 `--base-dir`，它使用 `/workspace`，这仅在该目录已存在且可写或您以 root 身份启动运行器时有效。

    如果运行器无法创建或写入路径，它在启动时以命名目录的错误退出，而不是注册。请参阅[故障排除](/docs/zh-CN/self-hosted-environments-deploy#troubleshooting)。

    然后使用 `--environment-secret-file` 和 `--base-dir` 启动运行器。运行器向您的环境注册并开始轮询工作。如果运行器退出，请手动重新启动它。生产部署在编排器下运行运行器，该编排器重新启动已退出的运行器，通常每次重新启动时使用新的文件系统；[重用预热的检查](/docs/zh-CN/self-hosted-environments-deploy#reuse-a-pre-warmed-checkout)涵盖了支持的持久磁盘设置。

    ```bash theme={null}
    claude self-hosted-runner --environment-secret-file '/etc/claude/environment-secret' --base-dir '<writable-dir>'
    ```
  </Step>

  <Step title="验证运行器出现">
    返回[**Cloud environments** 页面](https://claude.ai/admin-settings/cloud-environments)。您的环境状态在运行器启动后几秒内从 **No runners deployed** 更改为 **Healthy**；打开环境并选择 **Activity** 以查看运行器本身。
  </Step>

  <Step title="将会话路由到环境">
    在 claude.ai/code 启动会话，并从环境选择器中选择您的环境，其中自托管环境与 Anthropic 托管的环境一起出现。运行器使用主机已有的任何 git 凭证进行克隆，因此选择此主机已可以克隆的存储库或公共存储库；生产中私有存储库的凭证选项在[配置 git](/docs/zh-CN/self-hosted-environments-deploy#configure-git)上。下一个可用的运行器拾取排队的会话并记录 `Picked up session <session-id>` 以及其活跃计数和容量，因此您可以从运行器自己的输出中确认哪个主机接收了会话。在 [claude.ai/code](https://claude.ai/code) 观看会话工作并阅读 Claude 的回复。如果会话保持排队状态，请参阅[故障排除](/docs/zh-CN/self-hosted-environments-deploy#troubleshooting)。
  </Step>
</Steps>

运行器在其活跃会话完成后按设计退出；请参阅[运行器生命周期](/docs/zh-CN/self-hosted-environments#runner-lifecycle)。对于生产，在编排器下部署它，该编排器在退出时重新启动它，并在运行器启动后立即继续退出时等待更长的时间再重新启动。请参阅[部署到生产环境](/docs/zh-CN/self-hosted-environments-deploy)和[当运行器退出时](/docs/zh-CN/self-hosted-environments-deploy#when-the-runner-exits)。

<h2 id="send-a-follow-up-message-to-a-running-session">
  向运行中的会话发送后续消息
</h2>

一旦会话在您的环境上运行，从任何您使用 `claude auth login` 登录的机器上的 `claude` CLI 向其发送后续消息；该命令不需要从启动会话的机器运行。该命令发布一条消息：

```bash theme={null}
claude -p "your message" --cloud <session-id>
```

对于 `<session-id>`，传递裸 `session_...` 或 `cse_...` ID 或会话的 claude.ai/code URL。成功发送打印 `Sent to cloud session.` 以及会话 ID 和查看链接。接受的 ID 形式、JSON 输出、帐户和策略要求以及错误参考在[从 CLI 发送后续消息](/docs/zh-CN/claude-code-on-the-web#send-follow-ups-from-the-cli)上，因为该命令对 Anthropic 托管的会话的工作方式相同。

<h2 id="what’s-next">
  接下来的步骤
</h2>

* [部署到生产环境](/docs/zh-CN/self-hosted-environments-deploy)：强化部署、控制出口、配置 git 凭证，并在 Kubernetes 或 Compose 下运行部队
* [自定义会话](/docs/zh-CN/self-hosted-environments-configuration)：包装脚本、生命周期钩子、按需运行器、MCP 服务器和权限
* [端到端测试](/docs/zh-CN/self-hosted-environments-testing)：一个 CI 烟雾测试，分派会话并读取 Claude 的回复

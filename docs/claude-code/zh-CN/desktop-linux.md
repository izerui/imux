> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Linux 上的 Claude Desktop（测试版）

> 在 Ubuntu 和 Debian 上安装和更新 Claude 桌面应用

<Note>
  Claude 桌面应用的 Linux 支持处于测试版阶段。
</Note>

Linux 上的桌面应用提供与 macOS 和 Windows 相同的 Chat、Cowork 和 Claude Code 体验：并行会话、可视化差异审查、集成终端和编辑器以及实时应用预览。有关功能参考，请参阅[使用 Claude Code Desktop](/docs/zh-CN/desktop)。

<h2 id="requirements">
  要求
</h2>

* 基于 Debian 的发行版：Ubuntu 22.04 或更高版本，或 Debian 12 或更高版本
* x86\_64 或 arm64

其他满足这些要求的基于 Debian 的发行版可能可以工作，但未经过官方测试。在非基于 Debian 的发行版上，例如 Fedora 或 Arch，请改为运行 [CLI](/docs/zh-CN/setup#system-requirements)。如果您在 WSL 2 上使用 Windows，请安装 Windows 桌面应用程序并在您的发行版内运行会话；请参阅 [Claude Code Desktop in WSL](/docs/zh-CN/desktop-wsl)。

<h3 id="cowork-requirements">
  Cowork 要求
</h3>

Cowork 是 [Dispatch 和更长的代理工作](https://claude.com/docs/cowork/overview) 的桌面选项卡。在 Linux 上，Cowork 在桌面应用程序使用 QEMU 和 KVM 托管的虚拟机中运行这些任务。要使用 Cowork，您的机器需要：

* **硬件虚拟化**：在您的固件设置中启用。没有它，Cowork 选项卡会报告"Cowork requires hardware virtualization (KVM)"。
* **QEMU 和 UEFI 固件**：在 x86\_64 上为 `qemu-system-x86`、`ovmf` 和 `virtiofsd`，或在 arm64 上为 `qemu-system-arm`、`qemu-efi-aarch64` 和 `virtiofsd`。`apt install claude-desktop` 默认将它们作为推荐包安装。如果您使用 `--no-install-recommends` 安装，或您的系统是跳过推荐包的最小镜像，Cowork 选项卡会报告"Cowork requires QEMU"并显示要运行的 `apt install` 命令。Ubuntu 22.04 没有 `virtiofsd` 包；应用程序在那里使用捆绑的副本。
* **访问 `/dev/kvm`**：使用 `sudo usermod -aG kvm $USER` 将您的用户添加到 `kvm` 组，然后注销并重新登录。某些桌面环境会向已登录的用户授予对 `/dev/kvm` 的访问权限而无需该组，但 Cowork 还需要 `/dev/vhost-vsock`，只有 `kvm` 组成员才能打开。即使 `/dev/kvm` 已经对您有效，也要加入该组。

应用程序在启动时检查这些要求一次：安装包后重新启动它，加入组后注销并重新登录。如果 `/dev/vhost-vsock` 缺失且您运行的内核在 `/lib/modules` 下没有模块目录，Cowork 选项卡会报告内核不包含 Cowork 需要的虚拟化支持，并且无法手动添加。这种组合在 ChromeOS 和基于容器的 Linux 环境中很常见。

<h2 id="install">
  安装
</h2>

从 Anthropic 的 apt 存储库安装，以便更新通过系统的常规包更新到达。打开终端并运行每个步骤中的命令。

<Steps>
  <Step title="添加 Anthropic 的 apt 存储库">
    此步骤使用 `curl` 下载签名密钥，并使用 `gpg` 验证它，新的 Debian 和 Ubuntu 安装可能不包含这两个工具。如果任一命令报告 `command not found`，请先安装两者：

    ```bash theme={null}
    sudo apt install curl gnupg
    ```

    下载 Anthropic 的签名密钥：

    ```bash theme={null}
    sudo curl -fsSLo /usr/share/keyrings/claude-desktop-archive-keyring.asc https://downloads.claude.ai/claude-desktop/key.asc
    ```

    该命令在成功时不打印任何内容，在失败时打印 `curl:` 错误。缺少或错误的密钥会导致 `apt update` 稍后失败并显示 `NO_PUBKEY BAA929FF1A7ECACE`，因此在继续之前请确认密钥已下载并属于 Anthropic：

    ```bash theme={null}
    gpg --show-keys /usr/share/keyrings/claude-desktop-archive-keyring.asc
    ```

    gpg 打印的指纹应该是 `31DDDE24DDFAB679F42D7BD2BAA929FF1A7ECACE`。如果 gpg 报告文件无法打开或不包含有效的 OpenPGP 数据，则下载失败或返回了错误的内容：确认您的网络可以访问 `downloads.claude.ai`，然后重新运行下载命令。

    注册存储库：

    ```bash theme={null}
    echo "deb [arch=amd64,arm64 signed-by=/usr/share/keyrings/claude-desktop-archive-keyring.asc] https://downloads.claude.ai/claude-desktop/apt/stable stable main" | sudo tee /etc/apt/sources.list.d/claude-desktop.list
    ```
  </Step>

  <Step title="安装软件包">
    ```bash theme={null}
    sudo apt update && sudo apt install claude-desktop
    ```
  </Step>

  <Step title="启动并登录">
    从应用启动器启动 **Claude**，或从终端运行 `claude-desktop`，然后使用您的 Anthropic 账户登录。

    Linux 应用的登录方式与 macOS 和 Windows 上相同：使用 claude.ai 订阅或通过您组织的 SSO。Desktop 不直接接受 Claude Console API 密钥；请使用 [CLI](/docs/zh-CN/quickstart) 进行 API 密钥身份验证。对于路由 Desktop 到 Google Cloud 的 Agent Platform 或 LLM 网关的企业部署，请参阅 [Claude Desktop on 3P](https://claude.com/docs/third-party/claude-desktop/overview) 和 [网络配置](/docs/zh-CN/network-config)。
  </Step>
</Steps>

<h3 id="install-from-a-downloaded-file">
  从下载的文件安装
</h3>

如果您无法通过 apt 存储库安装，请直接从存储库的软件包池下载 `.deb` 软件包。此命令在存储库索引中查找您的架构的最新软件包，然后将其下载到当前目录：

```bash theme={null}
curl -fLO "https://downloads.claude.ai/claude-desktop/apt/stable/$(curl -s "https://downloads.claude.ai/claude-desktop/apt/stable/dists/stable/main/binary-$(dpkg --print-architecture)/Packages" | grep '^Filename: pool/main/c/claude-desktop/claude-desktop_' | sort -V | tail -n 1 | cut -d' ' -f2)"
```

如果命令失败并显示 `Remote file name has no length`，则查找未返回软件包路径。这可能意味着无法获取存储库索引，例如当您的网络阻止 `downloads.claude.ai` 时，或者您的架构不存在软件包。确认您的网络可以访问 `downloads.claude.ai`，并且 `dpkg --print-architecture` 输出 `amd64` 或 `arm64`；存储库不为其他架构发布软件包。

要在不注册 Anthropic 的 apt 存储库的情况下安装，首先创建 `/etc/default/claude-desktop`，其中包含行 `CLAUDE_DESKTOP_ADD_REPO="false"`。没有存储库，apt 不会提供新版本；要更新，请重新运行下载命令并重新安装，或稍后 [注册存储库](#install)。

然后使用软件安装程序（如 GNOME Software）打开下载的文件，或从包含下载文件的目录使用 apt 安装它：

```bash theme={null}
sudo apt install ./claude-desktop_*.deb
```

如果 apt 报告 `E: Unsupported file ./claude-desktop_*.deb given on commandline`，则该模式与当前目录中的 `.deb` 文件不匹配。确认下载已完成，然后从包含该文件的目录再次运行该命令。

安装 `.deb` 还会在 `/etc/apt/sources.list.d/claude-desktop.list` 注册 Anthropic 的 apt 存储库，因此未来的更新会随您系统的 [常规包更新](#update) 到达。

<h2 id="update">
  更新
</h2>

桌面应用在 Linux 上不会自动更新。更新通过系统的常规包更新到达：

```bash theme={null}
sudo apt update && sudo apt upgrade
```

您的发行版的图形软件更新程序也会获取新版本。

<h2 id="uninstall">
  卸载
</h2>

```bash theme={null}
sudo apt remove claude-desktop
```

卸载该软件包也会删除它注册的存储库条目和签名密钥。如果您在[添加 Anthropic 的 apt 存储库](#install)步骤中自己添加了存储库条目，也要删除它：

```bash theme={null}
sudo rm /etc/apt/sources.list.d/claude-desktop.list
```

<h2 id="troubleshoot">
  故障排除
</h2>

<h3 id="unable-to-locate-package-claude-desktop">
  无法定位软件包 claude-desktop
</h3>

如果 `sudo apt install claude-desktop` 失败并显示 `E: Unable to locate package claude-desktop`，说明 apt 没有找到您添加的存储库。请检查以下内容：

* 添加存储库后运行 `sudo apt update`。`apt install` 本身在您上次运行 `apt update` 之后添加的存储库中看不到。
* 确认存储库条目已写入。`cat /etc/apt/sources.list.d/claude-desktop.list` 应该显示来自[添加 Anthropic 的 apt 存储库](#install)步骤的 `deb` 行。如果文件为空或缺失，请再次运行该步骤。
* 确认您的架构受支持。`dpkg --print-architecture` 应该打印 `amd64` 或 `arm64`。该存储库不为其他架构发布软件包。
* 再次运行 `sudo apt update` 并检查其输出中是否有与 `downloads.claude.ai` 相关的错误。那里的网络或密钥错误意味着存储库已添加但无法访问或验证。

如果存储库已就位且可访问，但仍然找不到该软件包，请改为[从下载的文件安装](#install-from-a-downloaded-file)。

<h3 id="unmet-dependencies">
  未满足的依赖关系
</h3>

如果 `apt` 停止并显示 `The following packages have unmet dependencies` 或 `Unsatisfied dependencies`，请阅读它命名的依赖关系：

* `libc6 (>= 2.34)`：您的发行版比该软件包支持的版本更旧。Ubuntu 20.04 附带 `libc6` 2.31。升级到 Ubuntu 22.04 或更高版本，或 Debian 12 或更高版本。
* 所有缺失的依赖关系都显示 `not installable`，带有 `:amd64` 或 `:arm64` 后缀：您下载的 `.deb` 与您的机器架构不同。运行 `dpkg --print-architecture` 并下载匹配的 `.deb`，或[从 apt 存储库安装](#install)，它会为您的架构选择软件包。

<h3 id="running-as-root-without-no-sandbox-is-not-supported">
  以 root 身份运行而不使用 `--no-sandbox` 不受支持
</h3>

如果 `claude-desktop` 以此消息退出，说明您以 root 身份启动了它。以普通用户身份登录并从那里启动它。

<h3 id="cowork-isn’t-available">
  Cowork 不可用
</h3>

如果 Cowork 选项卡显示以下消息之一，请修复它命名的要求，然后重新启动应用：

* **Cowork 需要 QEMU**：安装消息列出的 [QEMU 和 UEFI 固件软件包](#cowork-requirements)。
* **Cowork 需要硬件虚拟化 (KVM)**：在您的固件设置中打开[硬件虚拟化](#cowork-requirements)。
* **Claude 没有权限使用虚拟化 (/dev/kvm)**：将您的用户添加到 [`kvm` 组](#cowork-requirements)，然后注销并重新登录。
* **Cowork 需要 `vhost_vsock` 内核模块**：运行 `sudo modprobe vhost_vsock`，然后重新启动应用。这仅为当前启动加载模块。要在每次启动时加载它，请运行 `echo vhost_vsock | sudo tee /etc/modules-load.d/vhost_vsock.conf`。

<h2 id="what’s-not-in-the-linux-beta-yet">
  Linux 测试版中尚未包含的内容
</h2>

* **Computer Use**：[应用和屏幕控制](/docs/zh-CN/desktop#let-claude-use-your-computer)在 Linux 上不可用。
* **Dictation**：语音输入在 Linux 桌面应用中不可用。请改用 CLI 中的[语音听写](/docs/zh-CN/voice-dictation)。
* **Quick Entry 全局热键**：在 X11 上有效。在原生 Wayland 上，它需要您的桌面环境的 GlobalShortcuts 门户。
* **Fedora 和 RHEL**：目前仅支持基于 Debian 的发行版。对其他发行版的支持将在未来推出。

对于桌面应用中尚未提供的任何功能，[CLI](/docs/zh-CN/quickstart) 运行相同的 Claude Code 引擎并支持更广泛的 Linux 发行版范围；请参阅[系统要求](/docs/zh-CN/setup#system-requirements)。

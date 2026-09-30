> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Plugin 命令参考

> claude plugin shell 命令、会话中的 /plugin 和 /reload-plugins 的完整参考，以及在一个会话中加载 plugin 的标志。

您可以从 shell 或脚本中以 `claude plugin` 的形式运行 plugin 命令，或在 Claude Code 会话中以 `/plugin` 和 `/reload-plugins` 的形式运行。本参考给出每个命令的标志、默认值、输出和退出代码，以及在一个会话中加载 plugin 的两个标志。

在您的构建上运行 `claude plugin --help` 以确认您的版本具有哪些子命令。

<Note>
  这些情况在其他页面上有介绍：

  * **安装和管理步骤，以及 `/plugin` 运行的位置**：请参阅 [安装和管理 plugins](/docs/zh-CN/plugins/install)
  * **命令在磁盘上更改的内容以及哪个作用域优先**：请参阅 [Plugin 加载参考](/docs/zh-CN/plugins/loading)
  * **错误消息的含义**：请参阅 [Plugin 故障排除](/docs/zh-CN/plugins/troubleshooting)
</Note>

<h2 id="claude-plugin-commands">
  claude plugin 命令
</h2>

从 shell 或脚本中运行 `claude plugin <subcommand>`，在 Claude Code 会话外。这些子命令安装和管理 plugins，而不打开 [`/plugin`](#plugin-in-a-session) 面板。

`claude plugins` 是 `claude plugin` 的别名。

每个子命令共享这些退出代码、plugin 参数和作用域值：

* **退出代码**：成功时为 `0`，失败时为 `1`。`validate` 为意外错误添加退出 `2`，`eval` 添加 [其部分](#plugin-eval) 中列出的代码。
* **Plugin 参数**：`<plugin>` 参数是 plugin `name` 或 `name@marketplace`。当两个市场提供相同的名称时，使用限定形式。
* **作用域**：`--scope` 接受 `user`、`project` 或 `local`，并命名命令写入的设置文件。`update` 也接受 `managed`。

<h3 id="plugin-init">
  plugin init
</h3>

在 `~/.claude/skills/<name>/` 处搭建新 plugin。它在您的下一个会话中作为 `<name>@skills-dir` 加载，无需安装步骤。

`new` 是 `init` 的别名。

对于以此命令开始的创建、测试和编辑工作流，请参阅 [创建 plugin](/docs/zh-CN/plugins/create)。

```bash theme={null}
claude plugin init <name> [options]
```

`<name>` 成为 `~/.claude/skills/` 下的目录名称和 plugin 清单中的 `name`。

该命令没有用于另一个位置的标志。要改为在项目内搭建，请参阅 [创建 plugin](/docs/zh-CN/plugins/create)。

| 标志 | 描述 |
| :- | :- |
| `--description <text>` | 清单描述 |
| `--author <name>` | 作者名称。默认为 `git config user.name` |
| `--author-email <email>` | 作者电子邮件。默认为 `git config user.email` |
| `--with <components...>` | 也为 `skills`、`agents`、`hooks`、`mcp`、`lsp`、`output-style` 或 `channel` 搭建启动文件 |
| `-f, --force` | 覆盖目标处的现有 `.claude-plugin/` |

使用启动 skill 和 hook 文件搭建 plugin：

```bash theme={null}
claude plugin init my-helper --with skills hooks
```

Claude Code 验证其写入的内容并打印 `Created plugin "my-helper" at ~/.claude/skills/my-helper`，后跟它加载的 id 和关闭它的 `claude plugin disable` 命令。

当 Claude Code 无法安全搭建时，它退出 `1` 而不写入，消息命名原因。这些是常见原因：

* 未知的 `--with` 值
* 目标处的现有搭建，没有 `--force`
* 阻止 skills-directory plugins 的托管设置

<h3 id="plugin-install">
  plugin install
</h3>

从您添加的市场安装 plugin。`i` 是 `install` 的别名。

```bash theme={null}
claude plugin install <plugin> [options]
```

大多数 plugins 无需提示即可安装。对于其市场条目 [运行命令来安装它](/docs/zh-CN/plugins/host-marketplace) 或 [为其下载设置 `headersHelper`](/docs/zh-CN/plugins/host-marketplace#how-users-accept-a-headershelper-command) 的 plugin，Claude Code 首先打印命令并询问 `Run this command now? [y/N]`。

| 标志 | 描述 |
| :- | :- |
| `-s, --scope <scope>` | 安装作用域：`user`、`project` 或 `local`。默认为 `user` |
| `--config <key=value>` | 设置 plugin 清单声明的 [`userConfig`](/docs/zh-CN/plugins/manifest-reference) 选项。为每个选项重复该标志。需要 Claude Code v2.1.147 或更高版本 |
| `-y, --yes` | 接受显示的安装命令，无需 `Run this command now?` 提示。当命令在 Claude Code 会话内运行时（例如从 Bash 工具或 hook）被忽略。需要 Claude Code v2.1.229 或更高版本 |
| `--accept-command <sha256>` | 接受显示的安装命令，其 `sha256` 之前的 [`--json` 运行](#plugin-json-result) 在 `shownCommand` 中报告，代替 `-y`。不能与 `-y` 组合。请参阅 [接受显示的安装命令](#accept-a-displayed-install-command)。需要 Claude Code v2.1.271 或更高版本 |
| `--json` | 将结果作为一个 JSON 对象打印在 stdout 的最后一行，而不是人类可读的消息，供脚本使用。请参阅 [JSON 结果格式](#plugin-json-result)。需要 Claude Code v2.1.268 或更高版本 |

从您自己的终端传递 `-y` 以接受显示的命令而无需提示。以下是没有 TTY 和 Claude 运行命令时发生的情况：

* **stdin 或 stdout 不是 TTY，您既不传递 `-y` 也不传递 `--accept-command`**：安装被拒绝。输出说命令仅被显示，退出代码为 `1`
* **Claude 通过其 Bash 工具运行命令**：`-y` 被忽略。改为从您自己的终端运行命令

为克隆项目的每个人安装 plugin：

```bash theme={null}
claude plugin install formatter@my-marketplace --scope project
```

Claude Code 打印 `Successfully installed plugin: formatter@my-marketplace (scope: project)`。当没有新内容被安装时，输出说明原因：

* **已在该作用域安装**：输出为 `Plugin "formatter@my-marketplace" is already installed (scope: project)`，退出代码为 `0`
* **您拒绝命令源提示**：输出为 `Aborted.`，退出代码为 `1`
* **您拒绝 `headersHelper` 提示，或无法在没有 TTY 的情况下确认**：输出为 `Aborted — the command was not run.`，退出代码为 `1`

<h4 id="plugin-json-result">
  JSON 结果格式
</h4>

当您向 `plugin install` 传递 `--json` 时，stdout 的最后一行是一个 JSON 对象。仅解析该行，因为 Claude Code 在其前面打印市场声明的任何命令。

三个字段始终存在：

* `command`：运行的子命令，例如 `install`
* `outcome`：`ok` 或 `failed`
* `message`：结果的人类可读描述

其他字段，例如 `pluginId`、`scope` 和 `failureCode`，仅在适用时出现。

`plugin uninstall`、`plugin update`、`plugin enable` 和 `plugin disable` 上的 `--json` 选项打印相同的对象，带有该子命令自己的字段。

使用错误（例如无效的 `--scope`）不打印结果行，退出 `1`，原因在 stderr 上。

<h4 id="accept-a-displayed-install-command">
  接受显示的安装命令
</h4>

当 `--json` 运行显示市场声明的命令且不运行它时，`failed` 结果也带有 `shownCommand` 对象。其字段包括显示的命令、它所属的 plugin 和命令的 `sha256`。

要接受完全相同的命令，从您自己的终端使用该 `sha256` 作为 `--accept-command` 重新运行，因为该标志在 Claude Code 会话内无效。需要 Claude Code v2.1.271 或更高版本。

`sha256` 计为完全相同的命令、plugin 和市场目录的接受。如果自命令显示以来其中任何一个发生了变化，Claude Code 不接受 `sha256` 并再次显示命令。运行自己的市场刷新获取的更改也计为此类更改。

如果 `shownCommand.acceptCommandMatched` 为 `false`，您传递的 `sha256` 与现在显示的命令不匹配。在使用其 `sha256` 重新运行之前查看该命令。

<h3 id="plugin-uninstall">
  plugin uninstall
</h3>

从一个作用域删除已安装的 plugin。`remove` 和 `rm` 是 `uninstall` 的别名。

```bash theme={null}
claude plugin uninstall <plugin> [options]
```

| 标志 | 描述 |
| :- | :- |
| `-s, --scope <scope>` | 从作用域卸载：`user`、`project` 或 `local`。默认为 `user` |
| `--keep-data` | 保留 plugin 的持久数据目录 `~/.claude/plugins/data/<id>/` |
| `--prune` | 也删除自动安装的 [dependencies](/docs/zh-CN/plugins/dependencies)，没有剩余 plugin 需要 |
| `-y, --yes` | 跳过 `--prune` 确认提示。当 stdin 或 stdout 不是 TTY 时，`--prune` 需要 |
| `--json` | 将结果作为一个 JSON 对象打印在 stdout 的最后一行，格式与 [`plugin install --json`](#plugin-json-result) 相同。不能与 `--prune` 组合。需要 Claude Code v2.1.268 或更高版本 |

从项目作用域卸载 plugin：

```bash theme={null}
claude plugin uninstall formatter@my-marketplace --scope project
```

Claude Code 打印 `Successfully uninstalled plugin: formatter (scope: project)`。当 plugin 未在该作用域安装时，命令打印以 `Failed to uninstall plugin "formatter@my-marketplace":` 开头的行，退出 `1`。

如果失败行继续显示 `"formatter" was not uninstalled:`，Claude Code 无法确认该作用域的设置不再打开 plugin，因此 plugin 保持安装状态，并保留其保存的所有内容。使用 `--json`，结果带有 `failureCode: "settings_still_on"`。此设置检查需要 Claude Code v2.1.282 或更高版本。

<h4 id="what-an-uninstall-deletes-and-keeps">
  卸载删除和保留的内容
</h4>

当您从最后一个安装 plugin 的作用域卸载它时，Claude Code 也删除 plugin 的存储 [options 和 secrets](/docs/zh-CN/plugins/manifest-reference#user-configuration) 及其数据目录 `~/.claude/plugins/data/<id>/`。有三个例外：

* 使用 `--keep-data`，数据目录保留
* 当另一个已安装的 plugin 使用相同的文件夹时，例如其 ID 仅在字母大小写上与此不同的 plugin，数据目录保留
* 当 Claude Code 无法在从该作用域删除 plugin 后读回已安装 plugins 的列表时，options、secrets 和数据目录都保留，因为 plugin 可能仍在另一个作用域安装。卸载仍然成功。消息列出保留的内容及如何删除它，使用 `--json` 结果带有 `savedKept: "install_records_unreadable"`

使用 `--json`，`keptData` 报告目录是否保留，`/plugin` 在保留时显示 `· data preserved`。对于在没有 `--keep-data` 的情况下保留的目录，此报告需要 Claude Code v2.1.281 或更高版本。`savedKept` 字段需要 Claude Code v2.1.282 或更高版本。

<h3 id="plugin-enable">
  plugin enable
</h3>

启用禁用的 plugin。对于 [从 claude.ai 同步的 plugin](/docs/zh-CN/plugins/loading#synced-plugins)，将 `<name>@synced` 作为 plugin 传递。

```bash theme={null}
claude plugin enable <plugin> [options]
```

| 标志 | 描述 |
| :- | :- |
| `-s, --scope <scope>` | 启用的作用域：`user`、`project` 或 `local`。省略时自动检测 |
| `--json` | 将结果作为一个 JSON 对象打印在 stdout 的最后一行，格式与 [`plugin install --json`](#plugin-json-result) 相同。需要 Claude Code v2.1.268 或更高版本 |

不使用 `--scope`，命令按本地、项目、用户的顺序检查您的设置文件，并使用提及 plugin 的第一个作用域。

如果您传递 plugin 未声明的 `--scope`，命令要么写入覆盖，要么失败：

* **[优先于](/docs/zh-CN/plugins/loading) 声明作用域的作用域**：Claude Code 在您传递的作用域处写入覆盖。例如，`claude plugin disable formatter --scope local` 仅为您关闭项目启用的 plugin
* **任何其他作用域**：命令失败，显示 `Plugin "formatter" is installed at project scope, not user. Use --scope project or omit --scope to auto-detect.`

如果 plugin 已在解析的作用域启用，命令打印 `Plugin "formatter" is already enabled` 并退出 `1`。使用 `--json`，结果具有 `"failureCode": "already_in_goal_state"` 和 `"alreadyInGoalState": true`，因此脚本可以将该情况视为成功。

当 plugin 声明 [dependencies](/docs/zh-CN/plugins/dependencies) 时，Claude Code 也启用它们。命令在这些情况下失败：

* **dependency 未安装**：启用失败并为每个缺失的 dependency 打印 `claude plugin install` 命令
* **dependency 被您组织的 plugin 策略阻止**：启用失败并命名被阻止的 dependency
* **dependency 在优先级高于目标作用域的作用域处设置为 `false`**：启用失败。在该作用域启用 dependency，或传递 `--scope` 以在那里写入

在声明它的任何地方重新启用 plugin：

```bash theme={null}
claude plugin enable formatter
```

Claude Code 打印 `Successfully enabled plugin: formatter (scope: project)`，命名它检测到的作用域。

<h3 id="plugin-disable">
  plugin disable
</h3>

禁用 plugin 而不卸载它。对于 [从 claude.ai 同步的 plugin](/docs/zh-CN/plugins/loading#synced-plugins)，将 `<name>@synced` 作为 plugin 传递。

```bash theme={null}
claude plugin disable [plugin] [options]
```

| 标志 | 描述 |
| :- | :- |
| `-a, --all` | 禁用每个启用的 plugin。不能与 plugin 名称或 `--scope` 组合 |
| `-s, --scope <scope>` | 禁用的作用域：`user`、`project` 或 `local`。省略时自动检测 |
| `--json` | 将结果作为一个 JSON 对象打印在 stdout 的最后一行，格式与 [`plugin install --json`](#plugin-json-result) 相同。需要 Claude Code v2.1.268 或更高版本 |

不使用 `--scope`，作用域以与 [`plugin enable`](#plugin-enable) 相同的本地、项目、用户顺序自动检测。

如果您既不传递 plugin 名称也不传递 `--all`，Claude Code 打印 `Please specify a plugin name or use --all to disable all plugins` 并退出 `1`。禁用已禁用的 plugin 打印 `Plugin "formatter" is already disabled` 并退出 `1`，如 [`plugin enable`](#plugin-enable) 对已启用的 plugin 所做的那样。

命令对仍然需要的 plugin 失败：

* **另一个启用的 plugin [depends on](/docs/zh-CN/plugins/dependencies) 它**：命令失败并命名要首先禁用的依赖项
* **您的组织要求它作为同步 plugin**：命令失败并保存任何内容

禁用一个 plugin：

```bash theme={null}
claude plugin disable formatter
```

Claude Code 打印 `Successfully disabled plugin: formatter (scope: project)`。

<h3 id="plugin-update">
  plugin update
</h3>

将 plugin 更新到其市场提供的最新版本。新版本在您的下一个会话中加载，或在您在运行的会话中运行 `/reload-plugins` 后加载。

```bash theme={null}
claude plugin update <plugin> [options]
```

| 标志 | 描述 |
| :- | :- |
| `-s, --scope <scope>` | 更新的作用域：`user`、`project`、`local` 或 `managed`。省略时自动检测 |
| `-y, --yes` | 接受来自 [command-source](/docs/zh-CN/plugins/host-marketplace) plugin 的更改的安装命令，无需提示。当 stdin 或 stdout 不是 TTY 时需要，除非您传递 `--accept-command`。需要 Claude Code v2.1.229 或更高版本 |
| `--accept-command <sha256>` | 接受市场声明的命令，其 `sha256` 之前的 [`--json` 运行](#plugin-json-result) 在 `shownCommand` 中报告，代替 `-y`。不能与 `-y` 组合。需要 Claude Code v2.1.271 或更高版本 |
| `--json` | 将结果作为一个 JSON 对象打印在 stdout 的最后一行，格式与 [`plugin install --json`](#plugin-json-result) 相同。需要 Claude Code v2.1.268 或更高版本 |

如果您省略 `--scope`，命令在您当前项目安装的最具体作用域处更新 plugin，检查本地、项目、用户，然后托管。

在 v2.1.281 之前，当您省略 `--scope` 时命令使用 `user`，因此更新仅在项目或本地作用域安装的 plugin 失败，显示 `Plugin "<name>" is not installed at scope user`。在这些版本上，传递 `--scope`。

`managed` 是您可以更新但不能安装的唯一作用域。对于管理员安装的 plugins，请参阅 [为您的组织管理 plugins](/docs/zh-CN/plugins/org)。

更新 plugin：

```bash theme={null}
claude plugin update formatter@my-marketplace
```

Claude Code 打印 `Checking for updates for plugin "formatter@my-marketplace"…`，然后是结果。当没有更新时，它打印 `formatter is already at the latest version (1.0.0).` 并退出 `0`。

您可以传递裸 plugin 名称，命令将其与您安装的 plugins 匹配。当来自不同市场的已安装 plugins 共享名称时，命令拒绝更新并列出要运行的限定 `plugin-name@marketplace-name` 命令。按裸名称更新需要 Claude Code v2.1.246 或更高版本。

<h3 id="plugin-list">
  plugin list
</h3>

列出已安装的 plugins，包括其版本、作用域和状态。

```bash theme={null}
claude plugin list [options]
```

| 标志 | 描述 |
| :- | :- |
| `--json` | 将列表打印为 JSON |
| `--available` | 也列出您的市场提供但您未安装的 plugins。没有 `--json` 时无效 |

Claude Code 按每个 plugin 的加载方式对人类可读的输出进行分组：

* **`Installed plugins:`**：您从市场安装的 plugins
* **`Session-only plugins (--plugin-dir / --plugin-url):`**：由同一命令中的这些标志加载的 plugins，如 `claude --plugin-dir ./my-plugin plugin list`
* **`Skills-directory plugins (.claude/skills/*):`**：Claude Code 在 skills 目录中找到的 plugins
* **`Synced from claude.ai`**：[从您的 claude.ai 账户同步的 plugins](/docs/zh-CN/plugins/loading#synced-plugins)

当任何组中都没有内容时，Claude Code 打印 ``No plugins installed. Use `claude plugin install` to install a plugin.``

<h4 id="json-output">
  JSON 输出
</h4>

使用 `--json`，Claude Code 打印一个数组，每个安装一个对象。每个对象都带有下面的字段。`id`、`version`、`scope`、`enabled` 和 `installPath` 始终存在，其他字段仅在适用时出现。

| 字段 | 类型 | 描述 |
| :- | :- | :- |
| `id` | string | 安装为 `name@marketplace`，会话内 plugins 为 `name@inline`，skills-directory plugins 为 `name@skills-dir`，从 claude.ai 同步的 plugins 为 `name@synced` |
| `version` | string | 对于市场安装，[Claude Code 在安装时计算的](/docs/zh-CN/plugins/loading#versions-and-updates) 版本。对于会话内、skills-directory 或同步 plugin，清单的 `version`，或当它不声明任何内容时为 `unknown` |
| `scope` | string | 安装为 `user`、`project`、`local` 或 `managed`；skills-directory plugins 为 `user` 或 `project`；会话内 plugins 为 `session`；从 claude.ai 同步的 plugins 为 `synced` |
| `enabled` | boolean | plugin 在您的合并设置中是否启用 |
| `installPath` | string | plugin 加载的目录 |
| `installedAt` | string | 安装的 ISO 时间戳。仅市场安装 |
| `lastUpdated` | string | 最后更新的 ISO 时间戳。仅市场安装 |
| `projectPath` | string | 安装所属的项目。仅 `project` 和 `local` 作用域 |
| `mcpServers` | object | plugin 的 MCP 服务器定义，当市场安装的 plugin 有任何时 |
| `errors` | array of strings | 加载错误，当 plugin 加载失败时 |
| `notes` | array of strings | plugin 加载并工作时的创作警告 |
| `errorDetails` | array of objects | 每个 `errors` 条目一个对象，给出其诊断 `type` 和它引用的名称，例如 plugin、市场、服务器或文件。需要 Claude Code v2.1.268 或更高版本 |
| `noteDetails` | array of objects | 每个 `notes` 条目的相同详细对象。需要 Claude Code v2.1.268 或更高版本 |

使用 `--json --available`，Claude Code 打印一个对象而不是数组。其 `installed` 字段保存已安装 plugin 对象的数组，其 `available` 字段保存每个未安装市场 plugin 的一个对象，带有下面的字段。

| 字段 | 类型 | 描述 |
| :- | :- | :- |
| `pluginId` | string | `name@marketplace` |
| `name` | string | plugin 在市场中的名称 |
| `marketplaceName` | string | 提供它的市场 |
| `source` | string or object | 市场条目的 [source](/docs/zh-CN/plugins/marketplace-reference)：相对路径为字符串，否则为对象 |
| `description` | string | 条目的描述，当它有时 |
| `version` | string | 条目的版本，当它声明时 |
| `installCount` | number | 安装计数，当 Claude Code 有 plugin 的计数时 |

<h3 id="plugin-details">
  plugin details
</h3>

显示 plugin 的组件清单及其预计令牌成本。

plugin 必须被加载：已安装、在 skills 目录中找到，或在同一命令中使用 `--plugin-dir` 或 `--plugin-url` 传递。`<name>` 是 plugin `name` 或 `name@marketplace`。

```bash theme={null}
claude plugin details <name>
```

命令除了 `--help` 外不接受任何标志。

显示已安装 plugin 的贡献：

```bash theme={null}
claude plugin details formatter
```

Claude Code 打印 plugin 的名称、版本、描述和源，然后是这些部分：

* **`Component inventory`**：plugin 的 skills、agents、hooks、MCP 服务器和 LSP 服务器
* **`Projected token cost`**：plugin 添加到每个会话的始终开启令牌
* **`Per-component (rounded)`**：每个 skill、agent 和命令的始终开启和按调用估计。当 plugin 没有时省略

对于两个成本数字的含义，请参阅 [测量 plugin 成本和使用](/docs/zh-CN/plugins/measure)。

对于未加载的 plugin，Claude Code 打印 ``Plugin "formatter" not found. Run `claude plugin list` to see installed plugins, or pass --plugin-dir <path> to load one from disk.`` 并退出 `1`。

<h3 id="plugin-prune">
  plugin prune
</h3>

删除自动安装的 [dependencies](/docs/zh-CN/plugins/dependencies)，没有已安装的 plugin 需要。命令永远不会删除您自己安装的 plugin。`autoremove` 是 `prune` 的别名。

```bash theme={null}
claude plugin prune [options]
```

| 标志 | 描述 |
| :- | :- |
| `-s, --scope <scope>` | 在作用域处修剪：`user`、`project` 或 `local`。默认为 `user` |
| `--dry-run` | 列出将被删除的内容而不删除它 |
| `-y, --yes` | 跳过确认提示。当 stdin 或 stdout 不是 TTY 时需要 |

预览修剪将删除的内容：

```bash theme={null}
claude plugin prune --dry-run
```

Claude Code 列出孤立的 dependencies 并以 `(dry run — nothing removed)` 结尾。当没有要删除的内容时，它打印以 `Nothing to prune` 开头的行。

不使用 `--dry-run`，命令仅在您在提示处确认或传递 `-y` 后删除孤立的 dependencies。

无论您在提示处的答案如何，退出代码都是 `0`。

`prune` 的作用取决于是否附加了终端以及您是否传递了 `-y`：

| 终端和标志 | 发生的情况 |
| :- | :- |
| 交互式终端，无 `-y` | 列出孤立的 dependencies 并询问 `Remove? [y/N]` |
| 任何终端，`-y` | 删除它们并打印 `Removed N auto-installed plugins: <names>` |
| 非 TTY stdin 或 stdout，无 `-y` | 打印列表和 ``Not a TTY — run `claude plugin prune -y` to remove.``，不删除任何内容 |

<h3 id="plugin-eval">
  plugin eval
</h3>

运行 plugin 的 [eval cases](/docs/zh-CN/plugin-evals) 并报告评分结果。需要 Claude Code v2.1.269 或更高版本。

每个案例是一个提示加评分器。Claude Code 在仅加载目标 plugin 的隔离会话中多次运行它，默认情况下也不使用 plugin 运行，以便报告显示差异。

有关案例格式、评分器、结果和 CI 使用，请参阅 [使用 evals 测试 plugins](/docs/zh-CN/plugin-evals)。

```bash theme={null}
claude plugin eval [target] [options]
```

可选的 `target` 默认为当前目录，采用以下任何形式：

* plugin 目录
* 单个 `prompt.md` 或 `case.yaml` 文件
* 已安装的 plugin，如 `name` 或 `name@marketplace`
* `name@skills-dir`

将目标放在 `--tag`、`--allow-tools` 和 `--json` 之前。这些选项中的每一个都将其后的单词作为其值，因此在其中一个之后写入的目标被读作标签、工具名称或 JSON 输出路径，而不是目标。

此表列出大多数运行使用的选项。运行 `claude plugin eval --help` 以获取完整集合，包括 `--case`、`--tag`、`--output-dir`、`--report`、`--allow-real-servers`、`--keep-temp` 和 `--verbose`。

| 选项 | 描述 | 默认 |
| :- | :- | :- |
| `--runs <n>` | 每个 [arm](/docs/zh-CN/plugin-evals#compare-against-a-no-plugin-baseline) 中每个案例的运行 | 每个案例的 `runs`，否则 3 |
| `-j, --concurrency <n>` | 一次运行的代理会话，1 到 8。它们共享您的速率限制 | `1` |
| `--model <model>` | 被测试代理的模型 | 每个案例的 `model`，否则 `ANTHROPIC_MODEL`（如果设置），否则 Claude Code 的默认值 |
| `--judge-model <model>` | `llm` 和 `baseline` 评分器的模型 | 一个小的快速模型 |
| `--ablation <mode>` | `none` 或 `with-without`。请参阅 [与无 plugin 基线比较](/docs/zh-CN/plugin-evals#compare-against-a-no-plugin-baseline) | 当 plugin 解析时为 `with-without`，否则为 `none` |
| `--threshold <0..1>` | 如果任何案例评分低于此，退出 1 | `1.0` |
| `--max-cost-usd <usd>` | 一旦支出达到此值，在下一次运行前停止，退出 2，并报告部分结果 | 无限制 |
| `--allow-tools <tools...>` | 授予超出只读集合的工具，例如 `Bash`、`Write`、`Edit` 或 `"mcp__plugin_<plugin>_<server>__*"`。请参阅 [授予工具](/docs/zh-CN/plugin-evals#grant-tools) | |
| `--scaffold` | 运行每个案例的 [`scaffold_script`](/docs/zh-CN/plugin-evals#add-setup-or-history-with-case-yaml) | 关闭 |
| `--trust-plugin` | 跳过首次运行信任提示，用于 CI。请参阅 [运行可以访问的内容](/docs/zh-CN/plugin-evals#security) | 关闭 |
| `--mocks <mode>` | `record` 或 `off`。请参阅 [Mock MCP 服务器](/docs/zh-CN/plugin-evals#mock-mcp-servers) | `record` |
| `--eval-dir <dir>` | plugin 下方保存案例的目录 | 清单的 `experimental.evals`，否则 `evals` |
| `--json [path]` | 将 [结果文档](/docs/zh-CN/plugin-evals#json-result) 打印到 stdout，或将其写入 `.json` 路径 | |
| `--no-publish` | 保持 HTML 报告本地 | |

退出代码报告运行如何结束。要在管道中对其进行操作，请参阅 [在 CI 中运行 evals](/docs/zh-CN/plugin-evals#run-evals-in-ci)。

| 退出代码 | 含义 |
| :- | :- |
| `0` | 每个案例都满足阈值 |
| `1` | 失败的案例、加载错误或不受信任的 plugin 目录 |
| `2` | 部分运行 |
| `130` | 中断 |
| `143` | 终止 |

<h3 id="plugin-eval-init">
  plugin eval init
</h3>

为当前目录中的 plugin 创建 eval 套件。需要 Claude Code v2.1.269 或更高版本。请参阅 [创建您的第一个 eval 套件](/docs/zh-CN/plugin-evals#create-your-first-eval-suite)。

```bash theme={null}
claude plugin eval init [name] [options]
```

在终端中，命令打开交互式 Claude Code 会话以进行创作访谈。在访谈中，Claude 执行以下操作：

1. 读取 plugin
2. 询问您它应该做什么
3. 提议案例和评分器
4. 写入案例文件
5. 运行案例并与您一起查看评分，以检查评分器是否按您的方式评分

使用 `--bare` 或没有终端，命令改为写入空白单案例模板。当 Claude 从 Claude Code 会话内运行命令时，命令打印该会话要遵循的访谈说明，而不是写入模板。

可选的 `name` 是案例名称。它对于 `--bare` 或没有终端是必需的，因为命令为该案例写入空白模板。访谈不需要。

命令接受这些选项：

| 选项 | 描述 | 默认 |
| :- | :- | :- |
| `--bare` | 为 `<name>` 写入空白 `prompt.md` 和 `graders/criteria.md`，而不是运行访谈 | |
| `-i, --interactive` | 需要访谈。没有终端时失败，而不是写入模板 | |
| `--eval-dir <dir>` | 当前目录下方写入案例的目录 | 清单的 `experimental.evals`，否则 `evals` |

<h3 id="plugin-tag">
  plugin tag
</h3>

为 plugin 发布创建名为 `<name>--v<version>` 的带注释 git 标签。在标记之前，命令检查 plugin 的 `plugin.json` 和任何列出它的市场条目是否同意版本。

有关何时标记发布，请参阅 [发布 plugin](/docs/zh-CN/plugins/publish)。

```bash theme={null}
claude plugin tag [path] [options]
```

`[path]` 是 plugin 目录，默认为当前目录。命令通过从该目录向上走到列出 plugin 的 `.claude-plugin/marketplace.json` 来查找市场条目。

| 标志 | 描述 |
| :- | :- |
| `--push` | 创建后将标签推送到 `--remote` |
| `--dry-run` | 打印将被标记的内容而不创建标签 |
| `-f, --force` | 跳过脏工作树和标签已存在检查 |
| `-m, --message <msg>` | 标签注释消息。`%s` 代表版本。默认为 `<name> <version>` |
| `--remote <name>` | 使用 `--push` 推送到的远程。默认为 `origin` |

预览市场检出中 plugin 的标签：

```bash theme={null}
claude plugin tag plugins/formatter --dry-run
```

Claude Code 打印计划：

* plugin 名称
* 版本和它来自哪个文件
* 匹配的市场条目，当有时
* 标签名称
* 它将运行的 `git tag` 和 `git push` 命令

不使用 `--dry-run`，Claude Code 打印 `Created tag formatter--v1.0.0` 和 `Pushed to origin` 或您自己运行的推送命令。如果推送失败，标签仍在本地创建，命令以错误退出。

当它无法安全标记时，命令退出 `1` 并打印原因。常见原因是：

* `plugin.json` 或市场条目中没有 `version`
* 标签已存在
* 工作树是脏的

<h3 id="plugin-validate">
  plugin validate
</h3>

验证 plugin 清单、市场清单或目录中的 skills、agents 和命令，并以 CI 作业可以操作的代码退出。对于创建、测试和编辑工作流，请参阅 [创建 plugin](/docs/zh-CN/plugins/create)。对于验证器在每个清单中检查的内容，请参阅 [plugin 清单参考](/docs/zh-CN/plugins/manifest-reference) 和 [市场参考](/docs/zh-CN/plugins/marketplace-reference)。

```bash theme={null}
claude plugin validate <path> [options]
```

| 标志 | 描述 |
| :- | :- |
| `--strict` | 将警告视为错误，因此运行时容忍的未识别字段和缺失元数据失败。需要 Claude Code v2.1.145 或更高版本 |
| `--json` | 将验证报告输出为一个 JSON 对象，具有相同的退出代码。需要 Claude Code v2.1.259 或更高版本 |

在提交前验证 plugin：

```bash theme={null}
claude plugin validate ./my-plugin --strict
```

<h4 id="validate-a-directory">
  验证目录
</h4>

`<path>` 是清单文件或目录。给定目录，Claude Code 通过它找到的内容选择要验证的内容：

* `.claude-plugin/marketplace.json`，当它存在时
* 否则 `.claude-plugin/plugin.json`
* 否则组件文件，由目录的名称选择。在没有清单的情况下验证组件文件需要 Claude Code v2.1.233 或更高版本：
  * 名为 `skills`、`agents` 或 `commands` 的目录：其中的文件
  * 名为 `.claude` 的目录：其中的 `skills`、`agents` 和 `commands` 目录
  * 任何其他目录：其 `.claude` 下的这三个目录

Claude Code 不跟随您命名的目录内的符号链接。它的作用取决于链接的位置：

* **plugin 或 `.claude` 根下的链接 `skills`、`agents` 或 `commands` 目录**：Claude Code 警告其中的任何内容都未被读取。
* **`skills`、`agents` 或 `commands` 目录内的链接条目**：Claude Code 跳过它并警告，每个目录，它跳过了多少条目，会话会加载。
* **您命名的 `skills`、`agents` 或 `commands` 目录本身是符号链接，或其父 `.claude` 目录是**：Claude Code 报告错误并检查其中的任何内容。改为命名真实目录。

验证运行不读取几个文件：

* **plugin 根处的 `SKILL.md`**：当您针对 plugin 目录运行 `claude plugin validate` 时，Claude Code 不检查 plugin 根处的 `SKILL.md`
* **plugin 根处的 `CLAUDE.md`**：在 plugin 运行中，Claude Code 也警告 plugin 根处的 `CLAUDE.md`
* **市场运行中的 Plugin 文件**：从市场目录，Claude Code 不打开 plugins 的 skill、agent、command 或 hook 文件。要在这些文件中查找错误，验证每个 plugin 目录

<h4 id="output-and-exit-codes">
  输出和退出代码
</h4>

Claude Code 打印它验证的文件、任何错误和警告及其路径，以及判决行。退出代码遵循判决：

| 退出代码 | 判决行 | 含义 |
| :- | :- | :- |
| `0` | `Validation passed` 或 `Validation passed with warnings` | 清单加载。使用 `--strict`，也没有警告 |
| `1` | `Validation failed` 或 `Validation failed (--strict treats warnings as errors)` | 错误，或 `--strict` 下的警告 |
| `2` | `Unexpected error during validation: <reason>` | 验证器本身失败，例如在不可读的路径上 |

使用 `--json`，Claude Code 将报告作为一个 JSON 对象写入 stdout，具有这些顶级字段：

* `success`：退出代码给出的相同判决
* `strict`：运行是否将警告视为错误
* `target`：Claude Code 验证的解析路径
* `manifest`：清单自己的结果，或没有清单的运行为 `null`
* `contents`：每个文件的结果，命名其 `file` 并携带 `errors`、`warnings` 和 `notes` 数组

在退出 `2` 时，命令不向 stdout 写入任何内容。错误消息转到 stderr。

<h2 id="claude-plugin-marketplace-commands">
  claude plugin marketplace 命令
</h2>

从你的 shell 运行 `claude plugin marketplace <subcommand>` 来添加、列出、刷新和移除你安装插件的市场。

* **退出代码**：这些子命令遵循插件命令的[退出代码约定](#claude-plugin-commands)
* **作用域**：它们的 `--scope` 标志没有 `-s` 短形式

关于市场是什么以及 Claude Code 如何缓存它，请参阅[插件加载参考](/docs/zh-CN/plugins/loading)。

<h3 id="plugin-marketplace-add">
  plugin marketplace add
</h3>

从 GitHub 仓库、git URL、托管的 `marketplace.json` 或本地路径添加市场，并在设置文件中声明它。

添加后，Claude Code 会安装你已安装的插件缺失的任何[依赖项](/docs/zh-CN/plugins/dependencies)。

```bash theme={null}
claude plugin marketplace add <source> [options]
```

| 标志 | 描述 |
| :- | :- |
| `--scope <scope>` | 声明市场的设置文件：`user`、`project` 或 `local`。默认为 `user` |
| `--sparse <paths...>` | 将 git 检出限制在这些目录，用于 monorepos。仅限 `github` 和 `git` 源 |
| `--claudeai` | 将参数读取为[托管在 claude.ai 上的市场](/docs/zh-CN/plugins/install#add-from-claude-ai)的名称，而不是源。需要 Claude Code v2.1.273 或更高版本 |

`<source>` 采用下表中的任何形式，其形式决定了源类型以及 Claude Code 如何获取市场。关于生成的源对象，请参阅[市场参考](/docs/zh-CN/plugins/marketplace-reference)。

| 你输入的 | 源类型 | Claude Code 如何获取它 |
| :- | :- | :- |
| `owner/repo`、`owner/repo#ref` 或 `owner/repo@ref` | `github` | 克隆 GitHub 仓库，给定时固定到 `ref`。所有者和仓库必须遵循 GitHub 命名规则 |
| `user@host:path[.git][#ref]` | `git` | 通过 SSH 克隆 |
| `https://example.com/repo.git[#ref]` 或包含 `/_git/` 的 URL | `git` | 通过 HTTPS 克隆，包括 Azure DevOps URL |
| `https://github.com/owner/repo` 或 `https://gitlab.com/namespace/project` | `git` | 在追加 `.git` 后通过 HTTPS 克隆 |
| 任何其他 `http://` 或 `https://` URL，包括没有 `.git` 的自托管 git 主机 | `url` | 将 URL 作为 `marketplace.json` 获取。要改为克隆那里的仓库，请追加 `.git` |
| `./path`、`../path`、`/path` 或 `~/path` 到目录 | `directory` | 就地读取目录。在 Windows 上，`.\`、`..\` 和 `C:\` 形式也可以工作 |
| 相同的路径形式，到 `.json` 文件 | `file` | 就地读取文件 |

对于克隆 URL 不带 `.git` 后缀的主机（如 AWS CodeCommit），请改为在 [`extraKnownMarketplaces`](/docs/zh-CN/settings-reference#extraknownmarketplaces) 中将市场添加为 git 条目。Claude Code 克隆 git 条目，无论其 URL 是否以 `.git` 结尾。

Claude Code 也克隆具有嵌套子组的 `gitlab.com` URL，例如 `https://gitlab.com/group/subgroup/project`。

添加市场并与项目共享：

```bash theme={null}
claude plugin marketplace add your-org/your-marketplace --scope project
```

Claude Code 打印 `Successfully added marketplace: your-marketplace (declared in project settings)`，使用市场自己清单中的 `name`。重复添加或无效源会改为打印以下结果之一：

* **市场已在磁盘上**：输出为 `Marketplace 'your-marketplace' already on disk — declared in project settings`，退出代码为 `0`
* **无法识别的源**：输出为 `Invalid marketplace source format. Try: owner/repo, https://..., or ./path`，退出代码为 `1`
* **裸主机，如 `gitlab.example.com/team/plugins`**：添加失败，作为无效的 `owner/repo` 简写，消息告诉你添加 `https://` 或使用本地路径

通过 `claude plugin marketplace list` 的 `From claude.ai:` 部分中打印的名称添加[托管在 claude.ai 上的市场](/docs/zh-CN/plugins/install#add-from-claude-ai)：

```bash theme={null}
claude plugin marketplace add --claudeai claudeai-organization-library
```

使用 `--claudeai` 时，命令拒绝 `--scope` 和 `--sparse`。市场为你的账户托管，未在设置文件中声明，因此你无法通过项目的 `.claude/settings.json` 共享它。

<h3 id="plugin-marketplace-list">
  plugin marketplace list
</h3>

列出你添加的每个市场及其源。

```bash theme={null}
claude plugin marketplace list [options]
```

| 标志 | 描述 |
| :- | :- |
| `--json` | 将列表打印为 JSON |

Claude Code 打印 `Configured marketplaces:` 和每个市场一行 `Source:`，或 `No marketplaces configured`。

使用 `--json` 时，Claude Code 打印一个数组，每个市场一个对象，包含下面的字段。每个字段都是字符串。

| 字段 | 描述 |
| :- | :- |
| `name` | 市场的名称 |
| `source` | `github`、`git`、`url`、`directory`、`file` 或 `claudeai` |
| `repo` | `owner/repo`。仅限 `github` 源 |
| `url` | 克隆或获取 URL。仅限 `git` 和 `url` 源 |
| `path` | 本地路径。仅限 `directory` 和 `file` 源 |
| `ref` | 固定的分支或标签。`github` 和 `git` 源，仅在固定时 |
| `installLocation` | Claude Code 缓存市场的位置 |

添加的 [claude.ai 市场](/docs/zh-CN/plugins/install#add-from-claude-ai)没有本地克隆，因此其条目在 `installLocation` 的位置携带其 claude.ai 标识符 `marketplaceId` 和 `organizationUuid`。它也在记录时携带 `scope` 和 `status`。

如果你的终端会话[从你的 claude.ai 账户同步插件](/docs/zh-CN/plugins/loading#synced-plugins)，文本列表以 `From claude.ai:` 部分结尾。该部分命名 claude.ai 为你的账户列出的市场，你还没有添加的，包括基于 git 的和托管的。它需要 Claude Code v2.1.273 或更高版本。

要从该部分添加市场，请参阅[从 claude.ai 添加市场](/docs/zh-CN/plugins/install#add-from-claude-ai)。

`--json` 输出仅覆盖已配置的市场，并排除该部分。

<h3 id="plugin-marketplace-remove">
  plugin marketplace remove
</h3>

从你的设置中移除市场的声明。`rm` 是 `remove` 的别名。

<Warning>
  当你从最后一个声明市场的作用域中移除市场时，Claude Code 也会删除其缓存并卸载你从中安装的每个插件。不使用 `--scope` 时，命令从每个作用域中移除声明。要在不丢失其插件的情况下刷新市场，请改为运行 `plugin marketplace update`。
</Warning>

```bash theme={null}
claude plugin marketplace remove <name> [options]
```

`<name>` 是 `plugin marketplace list` 显示的市场名称，而不是你传递给 `add` 的源。

| 标志 | 描述 |
| :- | :- |
| `--scope <scope>` | 从一个设置作用域中移除声明：`user`、`project` 或 `local`。不使用它时，Claude Code 从每个作用域中移除声明 |

从每个作用域中移除市场：

```bash theme={null}
claude plugin marketplace remove your-marketplace
```

Claude Code 打印 `Successfully removed marketplace: your-marketplace`，当你限定作用域时添加 `(from project settings)`。如果你限定作用域到不声明市场的设置文件，命令失败，显示 `Marketplace 'your-marketplace' is not declared in project settings. Omit --scope to remove it from all scopes.`

<h3 id="plugin-marketplace-update">
  plugin marketplace update
</h3>

从其源刷新一个市场或每个市场，以获取新插件和版本。使用分支或标签 `ref` 添加的市场更新到该 ref 的最新提交，而不是仓库的默认分支。

```bash theme={null}
claude plugin marketplace update [name]
```

该命令除了 `--help` 外不接受任何标志。

刷新一个市场：

```bash theme={null}
claude plugin marketplace update your-marketplace
```

Claude Code 打印 `Successfully updated marketplace: your-marketplace`。当你省略名称时，它打印计数，如 `Successfully updated 2 marketplaces`。没有添加市场时，它打印 `No marketplaces configured` 并退出 `0`。

<h2 id="plugin-in-a-session">
  会话中的 /plugin
</h2>

在交互式会话中，`/plugin` 打开 plugin 面板。每个子命令在选项卡上打开面板、在那里运行操作或内联打印结果。`/plugins` 和 `/marketplace` 是 `/plugin` 的别名。

您只能在交互式终端会话中运行这些命令。在非交互式运行（例如 `claude -p`）中，Claude Code 回复 `/plugin` 在此环境中不可用。

有关哪些表面有 `/plugin`、如何在没有它的情况下安装以及每个面板选项卡显示的内容，请参阅 [安装和管理 plugins](/docs/zh-CN/plugins/install)。

`<plugin>` 是 plugin `name` 或 `name@marketplace`。

下表列出每个会话形式。shell 子命令 `init`、`update`、`details`、`prune`、`eval` 和 `eval init` 没有会话形式。

| 命令 | 别名 | 它做什么 |
| :- | :- | :- |
| `/plugin` | | 在 **Discover** 选项卡上打开面板。`/plugin` 后的任何无法识别的第一个单词也这样做 |
| `/plugin help` | `/plugin --help`、`/plugin -h` | 显示 `/plugin` 子命令的使用列表 |
| `/plugin list [--enabled\|--disabled]` | `ls` | 内联打印您的市场安装 plugins，带有版本、作用域和状态。过滤标志仅显示该状态。启用状态尚未应用的 plugin 标记为 `— run /reload-plugins to apply`。需要 Claude Code v2.1.163 或更高版本 |
| `/plugin install` | `i` | 打开 **Discover** 选项卡 |
| `/plugin install <plugin>` | `i` | 在 **Discover** 选项卡中打开 plugin 的详细信息。使用 `name@marketplace`，在该市场的列表中打开它们 |
| `/plugin install <plugin> --marketplace <source>` | `i` | 当您尚未添加时添加 `<source>` 处的市场，要求您首先确认，然后打开 plugin 的详细信息。请参阅 [在一个命令中添加市场和安装](/docs/zh-CN/plugins/install#add-a-marketplace-and-install-in-one-command)。需要 Claude Code v2.1.275 或更高版本 |
| `/plugin manage` | | 打开 **Installed** 选项卡 |
| `/plugin stats` | | 打开 **Stats** 选项卡，在 [`/skill-doctor`](/docs/zh-CN/skills#find-unused-skills) 可用的会话中。其他任何地方它在 **Discover** 选项卡上打开面板 |
| `/plugin enable <plugin>` | | 在 plugin 处打开 **Installed** 选项卡并启用它 |
| `/plugin disable <plugin>` | | 在 plugin 处打开 **Installed** 选项卡并禁用它 |
| `/plugin uninstall <plugin>` | | 在 plugin 处打开 **Installed** 选项卡并卸载它 |
| `/plugin configure <plugin>` | `config` | 打开 plugin 的 [`userConfig`](/docs/zh-CN/plugins/manifest-reference) 对话框，或报告 plugin 不声明任何。需要 Claude Code v2.1.147 或更高版本 |
| `/plugin validate <path>` | | 打印与 `claude plugin validate` 相同的报告，内联 |
| `/plugin tag [path] [--push] [--dry-run] [--force]` | | 创建发布标签，如 `claude plugin tag` 所做的那样。接受 `--push`、`--dry-run` 和 `--force` 或 `-f`；使用任何其他标志或额外参数，Claude Code 改为打印使用 |
| `/plugin marketplace` | `market` | 不做任何可见的事情。传递 `add`、`list`、`update` 或 `remove` |
| `/plugin marketplace add [source]` | `market add` | 使用源，添加它并报告结果。不使用源，打开 **Add marketplace** 输入 |
| `/plugin marketplace list` | `market list` | 内联打印您的市场名称 |
| `/plugin marketplace update [name]` | `market update` | 打开 **Marketplaces** 选项卡。使用名称，在那里刷新该市场 |
| `/plugin marketplace remove [name]` | `market remove`、`market rm`、`marketplace rm` | 打开 **Marketplaces** 选项卡。使用名称，在那里删除该市场 |

如果您在 `/plugin enable`、`disable`、`uninstall` 或 `configure` 中命名当前项目中未安装的 plugin，Claude Code 打印 `Plugin "<plugin>" is not installed in this project` 而不是操作。

<h2 id="reload-plugins">
  /reload-plugins
</h2>

应用待处理的插件更改到正在运行的会话中，无需重新启动。待处理的更改是指自会话启动以来在磁盘上安装、更新、启用、禁用或编辑的插件。

当你关闭 `/plugin` 面板时，如果你在其中进行了待处理的更改，Claude Code 会为你运行 `/reload-plugins`。在面板外发生的插件更改（例如你在另一个终端中运行的 `claude plugin` 命令）之后，请自己运行它。

```text theme={null}
/reload-plugins [--force]
```

| 标志 | 描述 |
| :- | :- |
| `--force` | 应用重新加载，即使它会使 prompt 缓存失效。不带破折号的 `force` 也可以 |

<h3 id="reload-summary">
  重新加载摘要
</h3>

Claude Code 重新加载每个活跃的插件并打印一行摘要，`Reloaded: N plugins · N skills · N agents · N hooks · N plugin MCP servers · N plugin LSP servers`，在没有交互式终端的会话中省略插件 MCP 服务器计数。当任何插件失败时，摘要会添加 `N errors during load. Run /plugin for details.`

技能计数涵盖插件提供的每个技能，包括其 `commands/` 条目和其 SKILL.md 技能。代理计数是会话中加载的代理数量，包括不来自插件的代理。

当重新加载的插件的[依赖项](/docs/zh-CN/plugins/dependencies)缺失时，Claude Code 会安装它们，再次重新加载，并在摘要中附加 `(+ N dependencies: <names>) resolved`。

<h3 id="reloads-that-change-mcp-tools">
  更改 MCP 工具的重新加载
</h3>

当重新加载会添加或删除插件 MCP 服务器或 `LSP` 工具时，该更改会使[prompt 缓存](/docs/zh-CN/prompt-caching#enabling-or-disabling-a-plugin)失效，Claude Code 不会应用重新加载。它会打印一行，例如 `This reload changes MCP tools (<server>) — your next message will re-read the whole conversation instead of using the cache. Run /reload-plugins --force to apply.` 传递 `--force` 以应用它。

<h3 id="sessions-without-an-interactive-terminal">
  没有交互式终端的会话
</h3>

`/reload-plugins` 也在没有交互式终端的会话中运行，例如桌面应用、Agent SDK 和带有 `-p` 的[非交互模式](/docs/zh-CN/headless)。需要 Claude Code v2.1.260 或更高版本。

在这些会话中，该命令仅在你自己将其键入会话时运行，例如在 `-p` 提示或桌面应用的提示框中。当它以其他方式到达时，例如通过[远程控制](/docs/zh-CN/remote-control)或从 Slack 中继的消息，该命令回复 `/reload-plugins isn't available over a remote connection in this session.` 并且不重新加载任何内容。

这些会话中的重新加载不连接或断开插件 MCP 服务器。这些更改在你的下一个会话中生效。

<h2 id="flags-that-load-a-plugin-for-one-session">
  为一个会话加载 plugin 的标志
</h2>

两个 `claude` 标志仅为一个会话加载 plugin，而不安装它。两者都是可重复的。

Plugin 作者使用它们在发布前测试 plugin。对于加载-编辑-重新加载工作流，请参阅 [在没有市场的情况下开发](/docs/zh-CN/plugins/create#develop-without-a-marketplace)。

| 标志 | 描述 | 示例 |
| :- | :- | :- |
| `--plugin-dir <path>` | 从目录或其 `.zip` 存档加载 plugin。plugins 的文件夹加载每个包含 `.claude-plugin/plugin.json` 的子文件夹。每个标志接受一个路径 | `claude --plugin-dir ./my-plugin --plugin-dir ./other.zip` |
| `--plugin-url <url>` | 从 URL 获取 plugin `.zip` 存档。重复标志，或在一个引用值中传递多个 URL 空格分隔 | `claude --plugin-url "https://example.com/a.zip https://example.com/b.zip"` |

任一标志加载的 plugin 是会话内 plugin。`claude plugin list` 将其显示为 `<name>@inline`，作用域为 `session`，但仅当相同的标志在子命令前时。例如，运行 `claude --plugin-dir ./my-plugin plugin list`。

当会话内 plugin 与已安装的 plugin 共享名称时，Claude Code 为该会话加载会话内副本并跳过已安装的副本。如果您使用 `claude plugin disable <name>@inline` 禁用了会话内副本，或托管设置锁定该 plugin 名称，已安装的副本改为加载。有关优先级，请参阅 [Plugin 加载参考](/docs/zh-CN/plugins/loading)。

管理员可以拒绝两个标志和 [`CLAUDE_CODE_PLUGIN_DIRS`](/docs/zh-CN/env-vars#variables) 变量中命名的文件夹，使用托管 [`disableSideloadFlags`](/docs/zh-CN/settings-reference#disablesideloadflags) 设置。Claude Code 然后打印标志被您组织的托管设置禁用，并退出 `1` 而不启动。

从 Agent SDK，[`plugins`](/docs/zh-CN/agent-sdk/plugins) 选项等同于 `--plugin-dir`。

<h2 id="next-steps">
  后续步骤
</h2>

* [安装和管理 plugins](/docs/zh-CN/plugins/install)：与步骤相同的操作，带有您在每个步骤看到的内容
* [Plugin 加载参考](/docs/zh-CN/plugins/loading)：每个命令在磁盘上更改的内容以及哪个作用域生效
* [Plugin 故障排除](/docs/zh-CN/plugins/troubleshooting)：安装、市场、加载和验证错误消息及其修复
* [Plugin 清单参考](/docs/zh-CN/plugins/manifest-reference)：`claude plugin validate` 检查的字段

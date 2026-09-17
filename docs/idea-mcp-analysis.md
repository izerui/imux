# IDEA MCP：让 AI 像开发者一样使用 IDE

> 核对日期：2026-09-17 | JetBrains MCP Server 2026.2.2（58 个工具）

## 背景：AI 编程的瓶颈不在 AI，在工具链

过去，写代码靠的是**开发者 + IDE**。JetBrains 系列 IDE（IntelliJ IDEA、PyCharm、
WebStorm、GoLand、Rider、CLion 等）为 Java、Kotlin、Python、TypeScript、JavaScript、
Go、Rust、C/C++、C# 等几乎所有主流语言提供了代码语义理解、智能重命名、断点调试、
框架级检查、运行配置管理、数据库连接等数十项能力，开发者通过鼠标键盘直接操作这些功能
来写出高质量代码。

现在，AI 智能体（Claude Code、Codex、Pi）可以自动编写和修改代码，
但它们的工作方式更像是**一个只有文本编辑器和终端的程序员**——能读文件、写文件、跑命令，
却用不了 IDE 里那些真正提升代码质量的功能。

**IDEA MCP 解决的就是这个瓶颈**：它把 JetBrains IDE 的能力以标准协议（MCP）暴露出来，
让 AI 智能体可以**像人类开发者操作 IDE 一样**——重命名变量时自动更新所有引用、
设断点单步调试来定位 bug、用 IDE 的检查规则发现潜在问题、直接复用 IDE 保存的运行配置和
数据库连接。**不限语言，不限 IDE 产品——只要是 JetBrains 系列 IDE 打开的项目，
同一套 MCP 工具都能工作。**

一句话总结：**IDEA MCP 不是给 AI 加了 58 个工具，而是让 AI 从"文本编辑器级"
升级到了"专业 IDE 级"——覆盖所有 JetBrains IDE 支持的语言和框架。**

---

## IDEA MCP 带来了什么

下面按开发者日常工作流来组织，说明 IDEA MCP 在每个环节带来的提升。

### 写代码：从"文本替换"到"语义操作"

| 场景 | 没有 IDEA MCP | 有 IDEA MCP |
|---|---|---|
| **重命名变量/方法/类** | 文本全局替换，容易误改注释、字符串、不同作用域的同名变量 | `rename_refactoring`：按语义身份定位，自动更新所有引用，跳过无关同名 |
| **按团队规范格式化** | 用外部 formatter，规则可能和 IDE 不一致 | `reformat_file`：直接用项目配置的 Code Style |
| **查看符号定义和调用关系** | 文本搜索 grep，噪音多、跨继承跟不到 | `analyze_calls` `search_symbol` `get_symbol_info`：按语义跨模块追踪 |
| **读依赖库源码** | 看不到依赖包内部 | `read_file`：直接读 JAR/JRT/依赖包中的源码或反编译 class |

### 找问题：从"print 调试"到"断点调试"

这是 IDEA MCP 提升最大的地方。

没有 IDEA MCP 时，AI 排查 bug 的方式是：**猜一个假设 → 加 print 语句 → 重新运行 → 看输出 → 再猜**。
每验证一个假设就是一轮编辑-运行循环，效率很低。

有 IDEA MCP 后，AI 可以：

```
设断点 → 启动调试 → 程序暂停在断点处 → 查看当前所有变量值
→ 单步执行观察变化 → 求值任意表达式 → 修改变量继续运行
→ 一次运行中验证多个假设
```

IDEA MCP 暴露了 **13 个调试工具**，覆盖完整调试生命周期：

| 调试动作 | 工具 |
|---|---|
| 启动/停止调试会话 | `xdebug_start_debugger_session` `xdebug_get_debugger_status` |
| 管理断点 | `xdebug_set_breakpoint` `xdebug_list_breakpoints` `xdebug_remove_breakpoint` |
| 控制执行（单步/继续/暂停） | `xdebug_control_session` `xdebug_run_to_line` |
| 查看运行时状态 | `xdebug_get_stack` `xdebug_get_threads` `xdebug_get_frame_values` `xdebug_get_value_by_path` |
| 动态求值和修改 | `xdebug_evaluate_expression` `xdebug_set_variable` |

### 检查质量：从"编译通过就行"到"IDE 级深度检查"

| 检查层次 | 没有 IDEA MCP | 有 IDEA MCP |
|---|---|---|
| **语法和类型错误** | 编译器/基础 linter 可以发现 | `build_project` 同样能做 |
| **框架级问题**（Spring/Django/Express 注解错误、Android 资源引用、JPA 映射、ESLint 规则等） | 编译器看不到 | `lint_files` `get_file_problems` 一次检出 |
| **自定义检查规则** | 做不到 | `run_inspection_kts`：编写并运行 Kotlin 脚本检查 |
| **项目结构理解** | 手动解析 build 文件 | `get_project_modules` `get_project_dependencies`：直接拿到已解析的模块图 |

### 运行和测试：从"手拼命令"到"一键复用 IDE 配置"

IDE 中保存的运行配置包含环境变量、运行时参数（JVM 参数、Node 参数、pytest 参数等）、
工作目录、模块路径、测试过滤器等细节。AI 原生只能用 Shell 手拼命令，经常漏参数导致
运行结果和开发者在 IDE 里点 Run 不一样。

`get_run_configurations` + `execute_run_configuration` 让 AI 直接复用 IDE 的配置，
**保证测试和运行环境与人工操作完全一致**。

### 数据库：从"手动配连接"到"复用 IDE 凭据"

IDEA MCP 暴露了 **14 个数据库工具**，可以直接使用 IDE 中已保存的数据库连接
（包括凭据），执行 SQL、浏览表结构、管理 schema——不需要 AI 单独配置连接信息。

---

## 三个智能体各自获益多少

三个智能体接入 IDEA MCP 后都能使用全部 58 个工具，但**获益程度不同**——
取决于它们原生的 IDE 能力有多少。

### 效能跃升对比

| 能力维度 | Claude Code 原生水平 | Codex 原生水平 | Pi 原生水平 | 接入 IDEA MCP 后 |
|---|---|---|---|---|
| 语义重命名 | 无 | 无 | 无 | 三者均可 ✓ |
| 断点调试 | 无 | 无 | 无 | 三者均可 ✓ |
| 运行 IDE 配置 | 无 | 无 | 无 | 三者均可 ✓ |
| PSI 自定义检查 | 无 | 无 | 无 | 三者均可 ✓ |
| 语义导航（定义/引用/调用链） | **LSP 可用** | 无 | 无（pi-lens 可补部分） | 三者均可 ✓ |
| 框架级 inspections | LSP 诊断有限 | 编译器/linter | pi-lens 诊断 | 三者均可 ✓ |
| 代码格式化（IDE Code Style） | 外部 formatter | 无 | pi-lens 可格式化 | 三者均可 ✓ |
| 项目模块/依赖图 | 无 | 无 | 无 | 三者均可 ✓ |
| 数据库（IDE 连接） | CLI 需自备凭据 | CLI 需自备凭据 | CLI 需自备凭据 | 三者均可 ✓ |
| 依赖库源码/反编译 | 无 | 无 | 无 | 三者均可 ✓ |

### 获益排名

| 排名 | 智能体 | 原因 |
|---|---|---|
| 🥇 获益最大 | **Codex** | 原生既没有 LSP 也没有工程扩展，语义分析完全靠搜索和编译器。接入后直接从"文本搜索级"跳到"完整 IDE 级"，每个维度都是质变。 |
| 🥈 获益第二 | **Pi** | pi-lens 提供了不错的诊断和格式化，但缺少重命名、调试、PSI 检查、运行配置和项目模型。IDEA MCP 补上了这些高价值缺口。 |
| 🥉 获益相对最小 | **Claude Code** | 原生 LSP 已经覆盖了语义导航的基础需求。但在重命名、调试、PSI 检查、框架级 inspections 这些维度上，IDEA MCP 仍然是不可替代的。 |

> 注意："获益最小"不等于"不需要"。Claude Code 在重命名、调试、IDE 检查等维度上的原生能力
> 和 Codex、Pi 一样是零，接入 IDEA MCP 后的提升同样是从 0 到 1。

### 接入方式差异

| | Claude Code | Codex | Pi |
|---|---|---|---|
| 注入方式 | `--mcp-config` | `-c mcp_servers.idea.url=...` | `idea_mcp` 路由工具 |
| AI 看到什么 | 58 个 `mcp__idea__*` 工具 | 58 个工具 | 1 个路由工具（内部转发 58 个） |
| 优点 | 模型直接选工具，最直观 | 同左，且有沙箱审批 | 上下文只常驻 1 个 schema，最省 |
| 代价 | 58 个 schema 占上下文 | 同左 | 多一次发现步骤，选工具不如直接暴露直观 |

---

## 什么时候用 IDEA MCP，什么时候用原生

| 用 IDEA MCP | 用原生工具 |
|---|---|
| 重命名变量/方法/类 | 文本搜索（grep/rg 更快） |
| 断点调试排查 bug | 文件读写 |
| 运行带 IDE 参数的测试 | Git 操作 |
| 修改后做 inspections 检查 | Shell 命令 |
| 按 Code Style 格式化 | 目录浏览 |
| 查看调用链/类型层级 | |
| 读依赖库源码或反编译 | |
| 用 IDE 保存的凭据查数据库 | |

**原则**：IDE 擅长的事交给 IDEA MCP，终端擅长的事交给原生工具。

---

## 58 个工具逐项对比

图例：◎ 原生有等价能力 | ○ 原生有部分能力但不如 IDEA MCP | △ 只能靠 Shell/文本搜索勉强做 | × 做不到

### 调试器（13 个）—— 三者原生均为 ×，接入后均为 ◎

| # | IDEA MCP 工具 | 做什么 | Claude Code 原生 | Codex 原生 | Pi 原生 |
|---:|---|---|---|---|---|
| 1 | `xdebug_start_debugger_session` | 启动调试会话 | × | × | × |
| 2 | `xdebug_get_debugger_status` | 查看调试状态 | × | × | × |
| 3 | `xdebug_set_breakpoint` | 设置断点/日志点 | × | × | × |
| 4 | `xdebug_list_breakpoints` | 列出所有断点 | × | × | × |
| 5 | `xdebug_remove_breakpoint` | 删除断点 | × | × | × |
| 6 | `xdebug_control_session` | 单步/继续/暂停/停止 | × | × | × |
| 7 | `xdebug_run_to_line` | 运行到指定行 | × | × | × |
| 8 | `xdebug_get_stack` | 查看调用栈 | × | × | × |
| 9 | `xdebug_get_threads` | 查看线程列表 | × | × | × |
| 10 | `xdebug_get_frame_values` | 查看栈帧变量 | × | × | × |
| 11 | `xdebug_get_value_by_path` | 按路径钻取嵌套对象 | × | × | × |
| 12 | `xdebug_evaluate_expression` | 求值任意表达式 | × | × | × |
| 13 | `xdebug_set_variable` | 修改变量值 | × | × | × |

> **IDEA MCP 优势**：这 13 个工具是最大的效能提升点。三个智能体原生都只能靠 print 调试，
> 接入后直接获得完整的交互式调试能力。

### 数据库（14 个）—— 三者原生 ○（CLI 可查但需自备凭据），接入后 ◎

| # | IDEA MCP 工具 | 做什么 | Claude Code 原生 | Codex 原生 | Pi 原生 |
|---:|---|---|---|---|---|
| 14 | `list_database_connections` | 列出 IDE 配置的数据库连接 | × | × | × |
| 15 | `create_database_connection` | 创建数据库连接 | × | × | × |
| 16 | `edit_database_connection` | 编辑数据库连接 | × | × | × |
| 17 | `test_database_connection` | 测试连接是否可达 | △ Shell 手动测 | △ | △ |
| 18 | `list_database_schemas` | 列出 schema | ○ CLI 工具 | ○ | ○ |
| 19 | `introspect_schema` | 加载 schema 元数据 | △ | △ | △ |
| 20 | `list_schema_object_kinds` | 列出对象类型（表/视图等） | ○ CLI 工具 | ○ | ○ |
| 21 | `list_schema_objects` | 列出 schema 内对象 | ○ CLI 工具 | ○ | ○ |
| 22 | `get_database_object_description` | 查看表/视图结构 | ○ CLI 工具 | ○ | ○ |
| 23 | `preview_table_data` | 预览表数据 | ○ CLI 工具 | ○ | ○ |
| 24 | `execute_sql_query` | 执行 SQL | ○ CLI 工具 | ○ | ○ |
| 25 | `fetch_query_result` | 分页获取查询结果 | △ | △ | △ |
| 26 | `list_recent_sql_queries` | 查看最近执行的 SQL | × | × | × |
| 27 | `cancel_sql_query` | 取消正在运行的查询 | △ | △ | △ |

> **IDEA MCP 优势**：核心价值是**复用 IDE 已保存的连接和凭据**，免去 AI 自行配置数据库连接。
> 如果项目有独立的数据库 CLI 凭据，原生也够用。

### 文件与搜索（6 个）—— 三者原生大部分为 ◎（`read_file` 读依赖包时例外）

| # | IDEA MCP 工具 | 做什么 | Claude Code 原生 | Codex 原生 | Pi 原生 |
|---:|---|---|---|---|---|
| 28 | `create_new_file` | 创建文件 | ◎ `Write` | ◎ 文件工具 | ◎ `write` |
| 29 | `read_file` | 读取文件（含 JAR/JRT/反编译） | ○ `Read`（不能读 JAR） | ○（不能读 JAR） | ○（不能读 JAR） |
| 30 | `apply_patch` | 应用补丁 | ◎ `Edit` | ◎ patch 工具 | ◎ `edit` |
| 31 | `search_file` | 按 glob 搜索文件 | ◎ `Glob` / `Bash` | ◎ Shell | ◎ `find` |
| 32 | `search_regex` | 正则搜索文件内容 | ◎ `Grep` / `Bash rg` | ◎ Shell `rg` | ◎ `grep` |
| 33 | `search_text` | 文本搜索文件内容 | ◎ `Grep` / `Bash rg` | ◎ Shell `rg` | ◎ `grep` |

> **IDEA MCP 优势**：基本没有。原生工具在文件读写和搜索上更快更直接。
> **唯一例外**：`read_file` 可以读 JAR/JRT 内的源码和反编译 class，这是原生做不到的。

### 重构与编辑器（5 个）

| # | IDEA MCP 工具 | 做什么 | Claude Code 原生 | Codex 原生 | Pi 原生 |
|---:|---|---|---|---|---|
| 34 | `rename_refactoring` | 语义安全重命名 | × 只能文本替换 | × | × |
| 35 | `reformat_file` | 按 IDE Code Style 格式化 | △ 外部 formatter | △ | ○ pi-lens 可格式化 |
| 36 | `open_file_in_editor` | 在 IDE 中打开文件 | × | × | × |
| 37 | `get_all_open_file_paths` | 获取 IDE 当前打开的文件 | × | × | × |
| 38 | `list_directory_tree` | 目录树 | ◎ `Bash ls/tree` | ◎ Shell | ◎ `ls` |

> **IDEA MCP 优势**：`rename_refactoring` 是**必须用 IDEA MCP 的工具**，文本替换无法安全替代。
> `reformat_file` 保证与团队 Code Style 一致。`open_file_in_editor` 适用于需要让开发者
> 在 IDE 中查看某个文件的场景（如定位到问题文件后引导人工确认）。`get_all_open_file_paths`
> 可以了解开发者当前正在编辑哪些文件，从而推断工作上下文。目录树用原生更好。

### 检查与项目模型（4 个）

| # | IDEA MCP 工具 | 做什么 | Claude Code 原生 | Codex 原生 | Pi 原生 |
|---:|---|---|---|---|---|
| 39 | `get_file_problems` | 单文件 inspections 检查 | ○ LSP 诊断（不含框架检查） | △ 编译器 | ○ pi-lens 诊断 |
| 40 | `lint_files` | 批量文件 inspections | ○ LSP 诊断 | △ linter | ○ pi-lens |
| 41 | `get_project_dependencies` | 已解析的项目依赖 | △ 手动解析 build 文件 | △ | △ |
| 42 | `get_project_modules` | 已解析的模块列表 | △ 手动解析 build 文件 | △ | △ |

> **IDEA MCP 优势**：inspections 覆盖框架级问题（Spring/Android/JPA 等），
> 远超编译器和基础 linter。项目模块/依赖图原生需要手动解析 build 文件，IDEA 直接给结果。

### PSI 自定义检查（4 个）—— 三者原生均为 ×

| # | IDEA MCP 工具 | 做什么 | Claude Code 原生 | Codex 原生 | Pi 原生 |
|---:|---|---|---|---|---|
| 43 | `generate_inspection_kts_api` | 获取检查脚本 API 文档 | × | × | × |
| 44 | `generate_inspection_kts_examples` | 获取检查脚本示例 | × | × | × |
| 45 | `generate_psi_tree` | 生成代码的 PSI 语法树 | × | × | × |
| 46 | `run_inspection_kts` | 运行自定义 Kotlin 检查脚本 | × | × | × |

> **IDEA MCP 优势**：IntelliJ 独有能力，可以让 AI 编写并执行自定义代码检查规则。

### 语义导航（3 个）

| # | IDEA MCP 工具 | 做什么 | Claude Code 原生 | Codex 原生 | Pi 原生 |
|---:|---|---|---|---|---|
| 47 | `analyze_calls` | 调用层级分析（调用者/被调用者） | ○ LSP 有调用层级 | × | × |
| 48 | `search_symbol` | 按名称搜索符号 | ○ LSP 工作区符号 | △ 文本搜索 | △ 文本搜索 |
| 49 | `get_symbol_info` | 获取符号的类型/文档/声明 | ○ LSP Hover/定义 | × | × |

> **IDEA MCP 优势**：IDE 的语义分析在各语言上的精度通常优于通用 LSP，尤其是跨模块的类型推断和继承链追踪。
> Claude Code 的 LSP 可以覆盖基础场景，Codex 和 Pi 原生在这个维度基本空白。

### 运行配置（2 个）—— 三者原生均为 △

| # | IDEA MCP 工具 | 做什么 | Claude Code 原生 | Codex 原生 | Pi 原生 |
|---:|---|---|---|---|---|
| 50 | `get_run_configurations` | 获取 IDE 运行配置 | △ 手拼命令 | △ | △ |
| 51 | `execute_run_configuration` | 按 IDE 配置运行/测试 | △ 手拼命令 | △ | △ |

> **IDEA MCP 优势**：复用 IDE 保存的环境变量、运行时参数、工作目录，保证与人工点 Run 一致。

### Python 环境（2 个）

| # | IDEA MCP 工具 | 做什么 | Claude Code 原生 | Codex 原生 | Pi 原生 |
|---:|---|---|---|---|---|
| 52 | `get_python_environment` | 获取当前模块的 Python 解释器信息 | △ `which python` | △ | △ |
| 53 | `configure_python_interpreter` | 配置 Python 解释器 | × | × | × |

> **IDEA MCP 优势**：准确获取和管理 IDE 配置的解释器，尤其多 venv/Conda 环境时不会搞混。

### VCS（2 个）—— 三者原生均为 ◎

| # | IDEA MCP 工具 | 做什么 | Claude Code 原生 | Codex 原生 | Pi 原生 |
|---:|---|---|---|---|---|
| 54 | `get_repositories` | 获取项目 VCS 根 | ◎ `git remote` | ◎ | ◎ |
| 55 | `git_status` | 获取 Git 状态 | ◎ `git status` | ◎ | ◎ |

> **IDEA MCP 优势**：无。原生 git 命令功能更完整、更灵活。

### 构建（1 个）

| # | IDEA MCP 工具 | 做什么 | Claude Code 原生 | Codex 原生 | Pi 原生 |
|---:|---|---|---|---|---|
| 56 | `build_project` | 构建项目并返回错误 | ○ `Bash gradle/mvn` | ○ Shell | ○ `bash` |

> **IDEA MCP 优势**：返回结构化的错误位置信息；但大多数情况下直接跑构建命令也够用。

### 终端（1 个）—— 三者原生均为 ◎

| # | IDEA MCP 工具 | 做什么 | Claude Code 原生 | Codex 原生 | Pi 原生 |
|---:|---|---|---|---|---|
| 57 | `execute_terminal_command` | 在 IDE 终端执行命令 | ◎ `Bash` | ◎ Shell | ◎ `bash` |

> **IDEA MCP 优势**：无。原生 Shell 更直接、更灵活。

### 动态工具（1 个）—— 三者原生均为 ×

| # | IDEA MCP 工具 | 做什么 | Claude Code 原生 | Codex 原生 | Pi 原生 |
|---:|---|---|---|---|---|
| 58 | `execute_tool` | 动态调用 IDE 内部工具 | × | × | × |

> **IDEA MCP 优势**：IDEA 独占入口，可动态调用 IDE 内注册的任意工具（包括第三方插件注册的
> Action/Tool）。典型场景：调用 IDE 内置的代码生成器、触发特定插件的分析功能、
> 执行 IDE 菜单中可用但未独立暴露为 MCP 工具的操作。

### 汇总统计

| 提升程度 | 工具数 | 占比 | 说明 |
|---|---:|---:|---|
| **决定性提升**（原生做不到） | 22 | 38% | 调试器 13、PSI 检查 4、重命名 1、编辑器状态 2、动态工具 1、配置解释器 1 |
| **显著提升**（原生远不如） | 20 | 34% | 数据库整组 14（含连接管理等原生无法复用 IDE 凭据的工具）、项目模型 2、语义导航 3、执行运行配置 1 |
| **有一定提升** | 7 | 12% | inspections 2、格式化 1、构建 1、Python 环境查询 1、依赖库源码读取 1、查看运行配置 1 |
| **无提升**（原生已经够好） | 9 | 16% | 文件创建/补丁/搜索 5、目录树 1、VCS 2、终端 1 |

> 说明：数据库 14 个工具中有 4 个（连接管理、最近查询）原生完全做不到（×），
> 其余 10 个原生可通过 CLI 工具部分实现（○/△），但因为核心价值是"复用 IDE 凭据"，
> 整组归入"显著提升"。22 + 20 + 7 + 9 = **58**。

---

## 核对依据

- 对本机 IDEA MCP Server 完成 `tools/list`，确认 58 个工具。
- Imux 注入实现：`AgentCommand.kt`、`pi-imux-idea-mcp.js`。
- 三种 CLI 本机版本与仓库内资料：`docs/claude-code/`、`docs/codex/`、`docs/pi/`。

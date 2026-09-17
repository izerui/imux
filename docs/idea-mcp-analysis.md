# IDEA MCP vs 智能体原生工具对比分析

## 智能体原生工具概览

| 能力维度 | Claude Code | Codex | Pi |
|---------|------------|-------|-----|
| 文件读写 | Read/Edit/Write | read_file/write_file | 文件读写 |
| Shell | Bash (rg, grep, git, 编译器等) | run_bash_command (rg, grep, git 等) | shell |
| 文本搜索 | Glob, Grep, Bash+rg | search_files (类 rg) | 搜索 |
| LSP | **有** (9 个操作: goToDefinition, findReferences, hover, documentSymbol, workspaceSymbol, goToImplementation, prepareCallHierarchy, incomingCalls, outgoingCalls) | **无** | **无** |
| 代码诊断 | 无专用工具，靠 Bash 调编译器/linter | 同左 | 同左 |
| 重构 | 无专用工具，靠 Edit 手动改 | 同左 | 同左 |
| 格式化 | 无专用工具，靠 Bash 调 formatter | 同左 | 同左 |
| 调试 | 无 | 无 | 无 |
| 数据库 | 无专用工具，靠 Bash 调 CLI | 同左 | 同左 |
| 项目模型 | 无，靠读 build 文件 | 同左 | 同左 |
| Python 环境 | 无专用工具，靠 Bash | 同左 | 同左 |
| 扩展系统 | Agent, MCP | MCP | 扩展(-e) |

---

## 逐工具对比

### A. IDEA MCP 独占能力 — 智能体原生工具完全没有对应能力

这些能力三个智能体都不具备，IDEA MCP 是唯一来源。

| IDEA MCP 工具 | 能力说明 | 为什么原生做不到 |
|--------------|---------|----------------|
| **rename_refactoring** | 基于 PSI 语义的安全重命名，更新所有引用，跳过注释/字符串/无关同名 | 原生只能文本替换（Edit/sed），无法区分同名符号、会误改注释和字符串 |
| **reformat_file** | 按项目 IDEA Code Style 格式化 | 原生需 Bash 调外部 formatter，但无法读取 IDEA 的 Code Style 配置 |
| **generate_psi_tree** | 可视化 Java/Kotlin PSI 语法树 | 原生完全没有 PSI 访问能力 |
| **generate_inspection_kts_api** | 获取 IntelliJ 检查脚本 API 文档 | IDEA 内部 API，外部无法获取 |
| **generate_inspection_kts_examples** | 获取检查脚本示例模板 | 同上 |
| **run_inspection_kts** | 编译运行自定义检查脚本 | 需要 IDEA PSI 引擎，外部无法执行 |
| **xdebug_start_debugger_session** | 启动 IDE 调试会话 | 原生无任何调试能力 |
| **xdebug_control_session** | 控制调试执行流 (step/resume/pause) | 同上 |
| **xdebug_get_debugger_status** | 查询调试会话状态 | 同上 |
| **xdebug_get_stack** | 获取调用栈 | 同上 |
| **xdebug_get_threads** | 列出线程及状态 | 同上 |
| **xdebug_get_frame_values** | 检查栈帧变量值 | 同上 |
| **xdebug_get_value_by_path** | 按路径深入嵌套对象 | 同上 |
| **xdebug_evaluate_expression** | 在调试上下文求值表达式 | 同上 |
| **xdebug_set_variable** | 运行时修改变量值 | 同上 |
| **xdebug_set_breakpoint** | 设置断点/logpoint | 同上 |
| **xdebug_list_breakpoints** | 列出所有断点 | 同上 |
| **xdebug_remove_breakpoint** | 移除断点 | 同上 |
| **xdebug_run_to_line** | 运行到指定行 | 同上 |
| **open_file_in_editor** | 在 IDE 编辑器中打开文件 | 原生在终端工作，无法控制 IDE 编辑器 |
| **get_all_open_file_paths** | 获取 IDE 当前打开的文件 | 原生无法感知 IDE 编辑器状态 |
| **get_run_configurations** | 获取 IDE 运行配置和代码入口点 | 原生需手动解析 .idea/runConfigurations XML |
| **execute_run_configuration** | 按 IDE 运行配置执行程序 | 原生只能 Bash 手动拼命令，不含 IDE 配置的环境/参数/JVM选项 |
| **get_project_modules** | 获取 IDE 模块结构 | 原生需解析 build.gradle/pom.xml，不如 IDE 已解析的模型准确 |
| **get_project_dependencies** | 获取项目依赖及版本 | 同上 |
| **configure_python_interpreter** | 通过 PyCharm 检测配置 Python 解释器 | 原生无法触发 IDE 的自动虚拟环境发现 |

**共 26 个工具 — 占 45%**

---

### B. IDEA MCP 显著优于原生 — 原生工具能做但质量/准确度差很多

| IDEA MCP 工具 | 能力说明 | 原生替代 | IDEA MCP 优势 | Claude Code 特殊情况 |
|--------------|---------|---------|--------------|-------------------|
| **search_symbol** | 按标识符片段搜索符号（类/方法/字段） | rg/grep 搜文本 | 理解符号边界、区分声明和引用、支持模糊匹配、过滤外部库 | Claude Code 有 LSP workspaceSymbol，能力接近但受限于 LSP server 实现质量 |
| **get_symbol_info** | 获取符号类型、签名、文档、声明位置 | rg 找到后 Read 上下文 | 直接返回完整类型信息和 KDoc/Javadoc，不需要猜测上下文 | Claude Code 有 LSP hover，功能类似但 IDEA PSI 通常提供更丰富的类型推导和文档 |
| **analyze_calls** | 真实调用层级（按类型解析） | rg 搜方法名 | 基于类型解析区分同名方法、覆盖重写、忽略注释/字符串中的匹配 | Claude Code 有 LSP incomingCalls/outgoingCalls，能力接近；但 IDEA 的调用分析跨模块更完整、对 Kotlin 扩展函数等支持更好 |
| **get_file_problems** | 单文件 IDE inspections | Bash 调 linter/编译器 | 同时包含类型检查、空安全、弃用、框架规则等，远超单一 linter | — |
| **lint_files** | 批量 IDE inspections | Bash 调 linter | 同上，且支持批量 | — |
| **build_project** | IDE 构建并返回错误 | Bash 调 gradle/mvn | 返回结构化错误位置，且使用 IDE 已配置的 SDK 和编译选项 | — |
| **apply_patch** | 应用 patch | Edit 工具 / Bash patch | IDEA 的 patch 理解项目结构，能处理移动重命名 | Claude Code 的 Edit 工具在小修改上更便捷 |
| **execute_tool** | 动态调用 IDE 工具 | 无直接对应 | 可调用 IDE 注册的任何 action/tool | — |
| **get_python_environment** | 获取当前配置的 Python 解释器信息 | Bash: which python, python --version | 返回 IDE 认定的解释器、venv 类型、包管理器，与 IDE 运行行为一致 | — |

**共 10 个工具 — 占 17%**

#### Claude Code LSP vs IDEA MCP 的关键差异

| 维度 | Claude Code LSP | IDEA MCP |
|-----|----------------|----------|
| 引擎 | 依赖项目配置的 LSP server（如 typescript-language-server, jdtls 等） | IDEA 内置 PSI + 索引引擎 |
| 语言覆盖 | 取决于安装了哪个 LSP server | IDEA 支持的所有语言（Java/Kotlin/Python/JS/TS/Go/Rust 等通过插件） |
| 跨模块 | 受限于 LSP server 的项目模型理解 | IDEA 完整理解多模块项目、source root、依赖图 |
| Kotlin 支持 | kotlin-language-server 成熟度有限 | IDEA Kotlin 插件是参考实现，分析最完整 |
| 可用性 | 需要项目配置了对应 LSP server | IDEA 打开项目即可用，无需额外配置 |
| 调用层级深度 | 通常 1-2 层 | 可配置深度，最大 1000 节点 |

**结论：对于 Java/Kotlin 项目，IDEA MCP 在符号分析和调用层级上显著优于 Claude Code LSP；对于 TypeScript 项目，Claude Code LSP（通过 typescript-language-server）差距较小。Codex 和 Pi 没有 LSP，差距更大。**

---

### C. 与原生工具功能重叠 — 原生工具就够用

| IDEA MCP 工具 | 能力说明 | 原生替代 | 为什么原生够用 |
|--------------|---------|---------|-------------|
| **search_text** | 文本搜索（带位置坐标） | rg/grep | rg 在纯文本搜索上更快更灵活，支持更多 flag |
| **search_regex** | 正则搜索（带位置坐标） | rg -e | 同上 |
| **search_file** | 按 glob 搜索文件 | find/fd/Glob 工具 | 原生工具在文件查找上完全够用 |
| **read_file** | 读取文件内容 | Read 工具 / read_file | 功能完全一致；IDEA MCP 额外支持读 JAR 内类和反编译（见独占能力），但普通文件读取原生足够 |
| **create_new_file** | 创建文件 | Write 工具 / write_file | 功能一致 |
| **list_directory_tree** | 目录树 | tree / ls -R / find | 原生完全够用 |
| **execute_terminal_command** | 在 IDE 终端执行命令 | Bash / run_bash_command | 原生 shell 更直接，无需绕道 IDE 终端 |
| **git_status** | Git 状态 | git status | 原生 git 命令更灵活 |
| **get_repositories** | 获取仓库列表 | git remote / find .git | 原生够用 |
| **list_database_connections** | 列出 IDE 数据库连接 | — | 功能独占，但如果用户没在 IDE 配置数据库则无用 |
| **list_database_schemas** | 列出 schema | psql/mysql CLI | 如果有 CLI 访问权限，原生够用 |
| **list_schema_objects** | 列出表/视图等 | SQL CLI | 同上 |
| **list_schema_object_kinds** | 列出支持的对象类型 | SQL CLI | 同上 |
| **get_database_object_description** | 获取表结构 | SQL CLI: DESCRIBE/\d | 同上 |
| **introspect_schema** | 加载 schema 元数据 | SQL CLI | 同上 |
| **execute_sql_query** | 执行 SQL | SQL CLI | 同上；但 IDEA MCP 可复用 IDE 已存的连接凭据（不需要重新输入密码） |
| **fetch_query_result** | 翻页查询结果 | SQL CLI | 同上 |
| **preview_table_data** | 预览表数据 | SQL: SELECT * LIMIT | 同上 |
| **list_recent_sql_queries** | 列出最近查询 | 无对应 | IDEA 独占，但价值有限 |
| **cancel_sql_query** | 取消查询 | Ctrl+C / pg_cancel_backend | 原生够用 |
| **create_database_connection** | 创建连接 | SQL CLI | 功能重叠 |
| **edit_database_connection** | 编辑连接 | SQL CLI | 功能重叠 |
| **test_database_connection** | 测试连接 | SQL CLI | 功能重叠 |

**共 22 个工具 — 占 38%**

> **注意：数据库工具虽然功能重叠，但 IDEA MCP 的核心优势是复用 IDE 已配置的连接（含凭据），智能体无需知道密码。如果用户的数据库连接只在 IDE 中配置了，IDEA MCP 就是唯一通道。**

---

## 按场景汇总

### 引导词应重点引导的场景（IDEA MCP 独占或显著领先）

| 场景 | 核心理由 | 适用智能体 |
|-----|---------|----------|
| **符号查找与类型信息** | PSI 理解继承/重载/泛型，rg 只做文本匹配 | Codex, Pi（Claude Code 有 LSP 但 IDEA 更强，尤其 Java/Kotlin） |
| **调用链分析** | 基于类型解析，不是 grep 同名方法 | 三个都需要（Claude Code LSP 有但精度低于 IDEA） |
| **重命名重构** | 语义安全更新所有引用，跳过注释/字符串 | **三个都强烈需要** — 原生只能文本替换 |
| **代码诊断/检查** | IDE inspections 覆盖类型检查+空安全+弃用+框架规则 | **三个都强烈需要** — 原生只能调单一 linter |
| **格式化** | 使用项目 IDEA Code Style | **三个都需要** — 原生无法读 IDEA 格式配置 |
| **调试** | 完整调试生命周期管理 | **三个都是独占** — 原生零调试能力 |
| **运行 IDE 配置** | 包含 IDE 配置的环境/JVM参数/依赖 | 三个都需要 — Bash 拼命令容易遗漏配置 |
| **PSI 分析与自定义检查** | IntelliJ 内部能力 | 三个都是独占 |
| **项目模型** | IDE 已解析的模块/依赖图 | 三个都需要 — 原生需手动解析 build 文件 |
| **读取依赖源码/反编译** | IDEA read_file 可读 JAR 内 .class | **三个都是独占** — 原生无法反编译 |
| **IDE 数据库连接** | 复用已配置的连接凭据 | 三个都需要（但仅当用户在 IDE 中配了数据库时） |

### 不需要引导的场景（原生够用或更好）

| 场景 | 理由 |
|-----|------|
| **纯文本搜索** | rg/grep 更快更灵活 |
| **文件查找** | find/fd/Glob 完全够用 |
| **普通文件读写** | 原生 Read/Write 工具更直接 |
| **目录浏览** | tree/ls 足够 |
| **Shell 命令** | 原生 Bash 更直接，无需绕道 IDE 终端 |
| **Git 操作** | 原生 git 命令更灵活完整 |
| **数据库 SQL（有 CLI 凭据时）** | 原生 SQL CLI 更灵活 |

---

## 三个智能体各自从 IDEA MCP 获益程度

| 维度 | Claude Code | Codex | Pi |
|-----|------------|-------|-----|
| 符号查找 | 中等提升（已有 LSP，但 IDEA PSI 更强） | **大幅提升**（无 LSP） | **大幅提升**（无 LSP） |
| 调用层级 | 中等提升（LSP 有但精度低） | **大幅提升** | **大幅提升** |
| 重构 | **大幅提升**（原生只有文本替换） | **大幅提升** | **大幅提升** |
| 诊断 | **大幅提升** | **大幅提升** | **大幅提升** |
| 格式化 | **大幅提升** | **大幅提升** | **大幅提升** |
| 调试 | **从无到有** | **从无到有** | **从无到有** |
| 项目模型 | **大幅提升** | **大幅提升** | **大幅提升** |
| 文件读写 | 无提升 | 无提升 | 无提升 |
| 文本搜索 | 无提升 | 无提升 | 无提升 |
| Git | 无提升 | 无提升 | 无提升 |

---

## 引导词优化建议

### 应该引导的（按优先级排序）

1. **重构（重命名）** — 三个智能体都没有语义安全的重构能力，这是最大的效率提升点
2. **代码诊断** — IDE inspections 比任何单一 linter 覆盖面广得多
3. **调试** — 完全独占能力，原生零基础
4. **符号查找与调用层级** — 对 Codex 和 Pi 是从无到有，对 Claude Code 是精度提升
5. **格式化** — 确保使用项目统一的 Code Style
6. **运行 IDE 配置** — 包含完整的环境设置
7. **读取依赖源码** — 反编译 JAR 是独占能力
8. **IDE 数据库连接** — 复用凭据是关键优势

### 不需要引导的

- 纯文本搜索/文件查找/目录浏览 — 原生更好
- 普通文件读写 — 原生更直接
- Shell 命令 — 原生更灵活
- Git 操作 — 原生 git 更完整

### 引导词风格建议

引导词应该告诉智能体**什么时候选 IDEA MCP 而不是原生工具**，而不是罗列工具名（MCP 协议已经把工具描述发给了智能体）。重点是决策路由：遇到这类任务时，走 IDEA MCP 会得到更好的结果。

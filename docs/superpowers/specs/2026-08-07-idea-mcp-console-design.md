# IDEA 内一体化 MCP 控制台设计

## 背景

当前 `Tools > MCP Server` 页面已经具备基础能力：

- 启用/关闭插件内置 MCP Server
- 展示本地暴露地址（如 SSE、HTTP Stream）
- 为部分客户端提供 `Auto-Configure`
- 提供 `Exposed Tools` 入口

但从产品体验看，它仍更像“一个配置页”，而不是“一个完整可用的控制台”。

用户在 IDEA 中打开这个页面时，天然会期待这件事是闭环的：

1. 我能在这里看懂 MCP Server 当前是否可用
2. 我能在这里知道 Claude Code / Codex 应该怎么接入
3. 我能在这里一键完成配置，而不是自己去外部找文件手改
4. 我能在这里知道当前配置是否已经与当前 IDEA Server 对齐
5. 出错时，我能在这里得到明确原因与下一步建议

项目方向也进一步收敛：

- **目标不是做一个多客户端 MCP 管理平台**
- **目标是把 IDEA 插件自己的 MCP 能力做成强一体化体验**
- Claude Code 与 Codex 只是当前优先照顾的接入对象

因此，本设计不以“扩客户端矩阵”为核心，而以“IDEA 内一体化 MCP 控制台”为核心。

## 目标

在 IDEA 内提供一个一体化 MCP 控制台，使 `imux` 插件对外暴露 MCP Server 的同时，也负责接入辅助、配置校验与诊断反馈，让用户尽量不离开 IDEA 即可完成从启用、理解、配置到排错的闭环。

## 非目标

本次不做以下内容：

- 不把页面扩展成面向所有 MCP 客户端的通用平台
- 不支持更多客户端（如 Junie、VSCode）的一体化适配
- 不做运行时“客户端是否正在实际调用 MCP Server”的探针
- 不做连接历史、最近调用时间、调用统计等运行态分析
- 不为旧版 IntelliJ Platform 增加兼容分支、反射回退或旧 API 适配

## 约束

- 本项目只支持最新的 IntelliJ IDEA 2026.2，对应 IntelliJ Platform build `262`
- IntelliJ Platform 原生 UI 优先：复用 IntelliJ Platform 262 自带能力，不自行绘制图标、仿造控件或重复实现事件机制
- 第一阶段只在 IDEA 内强调一体化体验，不把“支持的客户端数量”作为目标
- Claude Code 与 Codex 是当前页面内优先支持的接入对象

## 核心设计原则

### 1. IDEA 是唯一主控入口

用户应该把 `Tools > MCP Server` 理解为“这个插件的 MCP 控制台”，而不是“这里只给我一个地址，剩下我自己去外面折腾”。

因此页面要同时承担：

- Server 状态展示
- 接入引导
- 自动配置
- 配置校验
- 诊断反馈

### 2. 一体化重于泛化

页面面向的是“当前 IDEA 插件内置 MCP Server 的使用体验”，而不是抽象一套面向所有客户端的配置平台。第一阶段应优先把 Claude Code / Codex 这两条最常用链路做顺。

### 3. 状态必须可解释

用户看到的不应该只是：

- Configured
- Not configured

而应该知道：

- 现在 server 开没开
- 当前配置是否和当前 server 对齐
- 如果不对，错在哪里
- 下一步该点哪个按钮修复

### 4. UI 原生优先

控制台的信息架构与交互状态基于 IntelliJ 原生设置页组织方式实现，优先使用现有的：

- Settings 分组布局
- 状态文案 / 辅助说明文案
- 按钮与下拉操作
- 链接、复制操作、详情展开等现成能力

不引入自定义绘制面板、状态卡片体系或非平台风格控件。

## 信息架构

页面拆为三段，分别回答三个问题。

### A. Server

回答：**当前 IDEA 内的 MCP Server 是否可用？**

展示内容：

- `Enable MCP Server`
- 当前 server 状态：
  - `Disabled`
  - `Running`
  - `Error`
- 当前可用 transport：
  - `HTTP Stream`
  - `SSE`
  - `Stdio`
- 当前 endpoint / stream / stdio 信息
- `Exposed Tools` 入口
- `Copy endpoint` 类快捷动作（对可复制的 endpoint）

这部分是“基础设施视图”，优先回答 server 是否在工作、暴露了什么、用户能复制什么。

### B. Connect

回答：**如果我要从 Claude Code / Codex 使用它，现在该怎么接入？**

只保留与“把当前 IDEA Server 接给客户端”直接相关的内容。

展示对象：

- Claude Code
- Codex

每个对象展示：

- 当前接入状态
- 推荐接入方式
- `Auto-Configure`
- `Verify`
- `Reconfigure`
- `Copy config`
- `Open config file`
- 必要的简短说明文案

这里不是做客户端矩阵展示，而是做“把当前 server 接出去”的操作区。

### C. Diagnose

回答：**如果它现在不能用，问题在哪里，该怎么修？**

展示内容：

- 最近一次配置检查结果
- mismatch 摘要
- 文件写入错误
- transport 不兼容提示
- 期望值 vs 实际值
- 推荐修复动作
- 必要的帮助说明入口

没有 Diagnose，这个页面就是配置页；有了 Diagnose，它才是控制台。

## 状态模型

### Server 状态

用于页面顶部基础设施层：

- `Disabled`：server 未启用
- `Running`：server 已启用并可提供 MCP 入口
- `Error`：server 启动或暴露过程出现错误

### Client 接入状态

用于 Claude Code / Codex 条目：

- `Not configured`：未检测到目标客户端对当前 IDEA Server 的配置
- `Ready`：已检测到配置，且配置与当前 IDEA Server snapshot 对齐
- `Mismatch`：存在配置，但和当前 server 不一致
- `Error`：读配置、写配置或校验过程中发生错误

这里使用 `Ready` 而不是 `Verified`，因为页面要表达的是“从这个 IDEA 控制台视角看，已经可以用了”，而不是强调底层协议校验术语。

## Server Snapshot 概念

页面内部需要一个统一的“当前 MCP Server 快照”，供连接与诊断区复用。该 snapshot 代表当前 IDEA 插件对外暴露的 MCP 事实视图，至少包含：

- server 是否启用
- server 当前状态（Disabled / Running / Error）
- 当前可用 transport 列表
- SSE 地址
- HTTP Stream 地址
- stdio 对应的接入信息
- 暴露工具概览（如数量或入口）

这样页面各部分都读取统一事实来源，避免：

- 各区块各自拼装状态
- 地址、transport、状态文案不一致
- 配置校验与页面展示口径不同

## 接入辅助模型

虽然页面不以“通用客户端平台”为目标，但内部仍需要给 Claude Code 与 Codex 各自封装一份接入逻辑，以支撑一体化体验。每个接入对象至少定义：

- 支持的 transport 列表
- 推荐 transport
- 配置文件路径解析方式
- 配置写入逻辑
- 配置读取逻辑
- 与当前 Server Snapshot 的对齐校验逻辑
- 产生 `Not configured / Ready / Mismatch / Error` 所需的数据

注意：

- 这只是页面内部的接入辅助模型
- 不把它抽象成“大而全的客户端插件平台”
- 第一阶段只需要覆盖 Claude Code 与 Codex

## 推荐 transport

页面需要显式展示“推荐接入方式”，而不是让用户自行猜测。

对 Claude Code 与 Codex，每个条目都应展示：

- 支持的 transport 范围
- 当前推荐值
- 推荐原因的简短说明（可选，若文案不啰嗦）

推荐值来自客户端接入辅助模型，而不是写死在 UI 层。这样以后即使某个客户端优先 transport 有变化，也只需要修改适配逻辑而非到处改文案。

## Auto-Configure 行为

`Auto-Configure` 不再是一个模糊动作，而是标准化的接入流程。

### 用户点击后流程

1. 基于当前 Server Snapshot 计算目标配置
2. 依据该客户端的推荐 transport 生成配置内容
3. 写入客户端配置文件
4. 写入后立即重新读取配置
5. 将读回结果与当前 Server Snapshot 做对齐校验
6. 更新页面状态为：
   - `Ready`
   - `Mismatch`
   - `Error`

### 设计要求

- 写入成功不等于 Ready
- 只有“写入后重新验证通过”才进入 Ready
- 失败时必须给出可解释结果，而不是静默停留在旧状态

## Verify 行为

`Verify` 的目标是重新执行一次“当前配置是否与当前 IDEA Server 对齐”的检查。

第一阶段的验证只定义为：

> **配置验证（Level 1）**：客户端配置是否存在、是否可读、是否存在目标 server 条目、transport / URL / command / args 是否与当前 Server Snapshot 对齐。

这意味着第一阶段的 `Ready` 仅代表：

- 当前配置与当前 server 一致

不代表：

- 客户端已经发起真实调用
- 真实会话中已经成功消费该 server

这种定义既可靠，也不会过度承诺。

## Reconfigure 行为

`Reconfigure` 用于以下场景：

- 当前状态是 `Mismatch`
- 当前状态是 `Error`，但属于可通过重写配置修复的问题
- 用户手动修改过配置，需要重新对齐
- IDEA MCP Server 地址或推荐 transport 变化后，需要更新客户端配置

行为与 `Auto-Configure` 类似，但语义上强调“修复/更新现有配置”。

## 复制与打开动作

为增强一体化体验，页面应提供以下低成本高价值动作：

### Copy endpoint

对 SSE / HTTP Stream 地址提供复制操作。适用于：

- 用户想手动验证
- 用户想查看真实地址
- 用户不想手动选中文本

### Copy config

生成当前推荐配置片段并复制到剪贴板。适用于：

- Auto-Configure 失败但用户仍能手动粘贴
- 用户想审查实际写入内容
- 用户想用于其他环境测试

### Open config file

直接打开目标客户端配置文件。适用于：

- 用户想手动修复
- 用户想比对 mismatch
- 页面提示写入失败，需要手动介入

这些动作让页面不只是“告诉你失败了”，而是直接帮你进入修复流程。

## Diagnose 设计

Diagnose 区用于聚合失败与不一致信息。第一阶段至少覆盖三类问题。

### 1. 配置文件不可写

展示内容：

- 目标文件路径
- 失败原因（权限、路径不存在且无法创建、写入异常等）
- 建议动作

### 2. transport 不兼容

展示内容：

- 当前客户端支持哪些 transport
- 当前 server 暴露了哪些 transport
- 推荐 transport 是什么
- 为什么当前选择不合适

### 3. 配置不一致（Mismatch）

展示内容：

- expected
- actual
- 不一致字段（如 transport、URL、server name、command、args）
- 修复建议（通常为 Reconfigure）

Diagnose 的核心不是“报错”，而是“给出下一步动作”。

## 典型交互流

### Flow 1：首次使用

1. 用户打开 `Tools > MCP Server`
2. 看到 server 为 `Disabled`
3. 用户启用 `Enable MCP Server`
4. 页面显示 `Running`，并展示可用 transport 与 endpoint
5. 用户在 Claude Code 或 Codex 条目点击 `Auto-Configure`
6. 页面写入配置并立即验证
7. 若成功，状态进入 `Ready`
8. 若失败，进入 `Error` 或 `Mismatch`，并在 Diagnose 区给出解释

### Flow 2：server 信息变化后重新对齐

1. 页面启动时读取客户端现有配置
2. 发现配置与当前 Server Snapshot 不一致
3. 条目状态显示 `Mismatch`
4. Diagnose 区展示 expected / actual
5. 用户点击 `Reconfigure`
6. 写回并重验
7. 状态恢复为 `Ready`

### Flow 3：自动写配置失败

1. 用户点击 `Auto-Configure`
2. 配置文件写入失败
3. 条目状态显示 `Error`
4. Diagnose 区显示错误路径与原因
5. 用户可点击 `Open config file`、`Copy config` 或重试

## MVP 范围

### 第一阶段必须包含

- Server 区：
  - `Enable MCP Server`
  - server 状态
  - transport 展示
  - endpoint / stdio 信息
  - `Exposed Tools` 入口
  - `Copy endpoint`
- Connect 区：
  - Claude Code 条目
  - Codex 条目
  - `Not configured / Ready / Mismatch / Error`
  - 推荐 transport 展示
  - `Auto-Configure`
  - `Verify`
  - `Reconfigure`
  - `Copy config`
  - `Open config file`
- Diagnose 区：
  - 最近一次检查结果
  - mismatch 详情
  - 错误原因
  - 修复建议

### 第一阶段明确不包含

- Junie / VSCode 等更多客户端支持
- 运行时真实调用探针
- 最近一次客户端调用时间
- 连接历史、统计、调用分析
- 大而全客户端框架

## 测试策略

### 1. 接入辅助模型单测

覆盖：

- 支持的 transport 列表
- 推荐 transport
- 生成配置内容是否正确
- 读回后是否能正确识别 `Not configured / Ready / Mismatch / Error`
- mismatch 字段是否能准确定位

### 2. Server Snapshot / 状态映射单测

覆盖：

- Server 状态映射是否正确
- 页面区块是否基于统一 snapshot 输出一致信息
- 在 server 关闭、running、error 下，Connect / Diagnose 是否进入正确状态

### 3. 设置页行为测试

覆盖：

- 点击 `Auto-Configure` 是否触发正确写入流程
- 写入后是否触发重新验证
- 点击 `Verify` 是否重新校验
- 点击 `Reconfigure` 是否覆盖旧配置
- 错误时是否展示 Diagnose 内容
- `Copy endpoint / Copy config / Open config file` 是否绑定正确动作

### 4. MVP 成功标准

若满足以下条件，即认为第一阶段已经达成目标：

1. 用户在 IDEA 内能看懂 MCP Server 是否可用
2. 用户不需要猜 Claude Code / Codex 该怎么接
3. 用户能一键完成配置
4. 用户能在 IDEA 内知道当前接入是不是对的
5. 出错时能在 IDEA 内直接获得修复路径
6. 整体体验像控制台，而不是零散设置项

## 后续阶段

在第一阶段稳定后，可考虑第二阶段增强：

- 增加更多客户端支持
- 增加更强的运行时验证
- 增加修复向导
- 增加更丰富的诊断与帮助信息

但这些都不属于本设计的实施范围。

## 结论

本设计将 `Tools > MCP Server` 从“配置入口”提升为“IDEA 内一体化 MCP 控制台”。

它的重点不是支持更多客户端，而是：

- 让 IDEA 成为唯一主控入口
- 让用户在 IDEA 内完成理解、配置、校验与排错
- 让 `imux` 的 MCP 能力呈现出完整产品感，而不是底层协议暴露感

第一阶段以 Claude Code 与 Codex 为接入对象，以最小闭环为目标，通过原生 IntelliJ UI 能力实现强一体化体验。
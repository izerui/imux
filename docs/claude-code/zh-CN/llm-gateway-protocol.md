> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Claude Code 网关兼容性指南

> 保持 LLM 网关与 Claude Code 兼容：它调用的端点、必须转发的标头和正文字段，以及删除它们时会破坏的功能。

本页面记录了 Claude Code 发送到网关的请求，包括它调用的端点、网关必须转发的标头和正文字段，以及不转发时停止工作的功能。本指南是为配置网关产品以与 Claude Code 兼容的运营人员编写的。

[Claude apps gateway](/docs/zh-CN/claude-apps-gateway) 是 Anthropic 的自托管网关，在 `GET /protocol` 处提供自己的端点参考，涵盖该网关的登录、推理、托管设置、模型发现和遥测端点。这是一份与本指南分开的文档。

<Note>
  * 要为您的组织推出现有或第三方网关，请参阅[推出 LLM 网关](/docs/zh-CN/llm-gateway-rollout)
  * 如果您是使用给定的凭证向网关验证 Claude Code 的个人开发者，请参阅[将 Claude Code 连接到 LLM 网关](/docs/zh-CN/llm-gateway-connect)
</Note>

本页面涵盖：

* [API 格式](#api-formats)和每种格式要提供的端点
* [按连接方法的客户端行为](#how-the-connection-method-changes-client-behavior)：模型 ID、`anthropic-beta` 值、请求字段和默认值在格式和 Claude apps 网关登录之间的差异
* [请求标头](#request-headers)：哪些必须到达上游，哪些您的网关可以使用
* [响应标头](#response-headers)：返回什么以使停滞检测、重试和使用限制显示工作
* [系统提示属性块](#system-prompt-attribution-block)及其与提示缓存的交互方式
* [功能传递](#feature-pass-through)：删除标头或正文字段时会破坏什么
* [模型发现](#model-discovery)

本页面使用两个术语来描述您的网关对每个标头和正文字段的处理方式：

* **转发不变**：逐字节将其传递到上游
* **使用**：网关可能会读取它以进行路由、属性或跟踪，不需要转发它

任何未标记为转发不变的内容都可以由您使用或忽略。

<h2 id="api-formats">
  API 格式
</h2>

网关必须向 Claude Code 客户端公开以下至少一种 API 格式。客户端选择一种格式，并通过下表"选择方式"列中的变量将 Claude Code 指向您的网关。

Google Cloud 的 Agent Platform 是 Google Cloud 的 Claude 端点，原名为 Vertex AI；其变量名保留 `VERTEX` 拼写。

| 格式 | 选择方式 | 端点 | 原样转发 |
| :- | :- | :- | :- |
| Anthropic Messages | `ANTHROPIC_BASE_URL` | `/v1/messages`, `/v1/messages/count_tokens`（可选） | `anthropic-beta` 和 `anthropic-version` 请求头 |
| Amazon Bedrock InvokeModel | `ANTHROPIC_BEDROCK_BASE_URL` 配合 `CLAUDE_CODE_USE_BEDROCK=1` | `/model/{model}/invoke`, `/model/{model}/invoke-with-response-stream`, `/model/{model}/count-tokens`（可选） | `anthropic_beta` 和 `anthropic_version` 请求体字段 |
| Google Cloud's Agent Platform rawPredict | `ANTHROPIC_VERTEX_BASE_URL` 配合 `CLAUDE_CODE_USE_VERTEX=1` | `:rawPredict`, `:streamRawPredict`, `count-tokens:rawPredict`（可选） | `anthropic-beta` 和 `anthropic-version` 请求头，以及 `anthropic_version` 请求体字段 |

<h3 id="foundry-and-claude-platform-on-aws">
  Foundry 和 AWS 上的 Claude Platform
</h3>

Microsoft Foundry 和 [AWS 上的 Claude Platform](/docs/zh-CN/claude-platform-on-aws) 实现了 Anthropic Messages 格式。Claude Code 通过它们自己的变量 `ANTHROPIC_FOUNDRY_BASE_URL` 和 `ANTHROPIC_AWS_BASE_URL` 路由到它们，但网关在任一前面实现上述 Anthropic Messages 行。在 AWS 上的 Claude Platform 前面的网关还必须转发 `anthropic-workspace-id` 头，[该平台在每个请求上都需要](/docs/zh-CN/claude-platform-on-aws)。

<h3 id="optional-endpoints-and-startup-traffic">
  可选端点和启动流量
</h3>

令牌计数端点是唯一可选的：当它们不存在时，Claude Code 会回退到基于字符的上下文使用估计。

根据路径而不是完整 URL 进行匹配：

* 推理请求发送到 `/v1/messages?beta=true`
* Google Cloud 的 Agent Platform 方法后缀附加到发布者模型路径，如 `/projects/{project}/locations/{location}/publishers/anthropic/models/{model}:streamRawPredict`

网关还会看到尽力而为的启动流量，可以拒绝而不会破坏任何东西。Anthropic Messages 格式的网关接收 `HEAD /api/hello` 连接预热探针，当配置了 HTTP 代理或客户端证书时，Claude Code 会跳过该探针。Amazon Bedrock 格式的网关接收 `GET /inference-profiles?type=SYSTEM_DEFINED` 请求，以及当配置的模型是推理配置文件时，`GET /inference-profiles/{profile}` 查询。

[快速模式](/docs/zh-CN/fast-mode)可用性检查永远不会出现在网关日志中：它直接调用 `api.anthropic.com` 而不是遵循 `ANTHROPIC_BASE_URL`，因此在阻止直接出站到 `api.anthropic.com` 的网络上，快速模式可能会报告连接错误，而通过网关的推理继续工作。[WebFetch 域安全检查](/docs/zh-CN/data-usage#webfetch-domain-safety-check)也直接调用 `api.anthropic.com`。[在代理和 LLM 网关后面使用快速模式](/docs/zh-CN/fast-mode#use-fast-mode-behind-proxies-and-llm-gateways)涵盖了恢复它的变量。

<h3 id="streaming">
  流式传输
</h3>

流式传输推理响应。Claude Code 在流到达时读取流，因此如果您的网关在中继之前缓冲完整响应，Claude Code 会停滞。

传递每个响应的完整事件序列，不要丢弃、重复或重新排序事件。当事件引用的内容块的 `content_block_start` 从未到达，或块的 `content_block_stop` 已经到达时，Claude Code 会在该事件处停止读取流，而不是应用它，因此重复的 `content_block_stop` 不能运行相同的工具调用两次。[上述响应可能不完整](/docs/zh-CN/errors#the-response-above-may-be-incomplete)描述了用户看到的内容，在 `部分响应从未到达` 和 `响应流格式错误` 变体下。

在结束正文之前，通过每个响应的最终 `message_delta` 和 `message_stop` 事件中继每个响应。在 `message_delta` 携带 `stop_reason` 之后结束的正文，没有内容块仍然打开，该帧之后没有内容块事件，即使 `message_stop` 缺失，也计为完整。您的网关更早结束的正文，一旦内容块已启动，就被视为与断开连接相同：[自动重试](/docs/zh-CN/errors#automatic-retries)说明 Claude Code 何时重新发出请求，[上述响应可能不完整](/docs/zh-CN/errors#the-response-above-may-be-incomplete)涵盖了一旦可见内容到达它保留的内容。Claude Code 保留 `message_delta` 传递的 `stop_reason`，因此稍后仅使用情况的 `message_delta`，其 `delta` 具有 `stop_reason: null` 或没有 `stop_reason` 键，不会清除它。

当客户端使用 Amazon Bedrock 格式时，原样中继 `InvokeModelWithResponseStream` 响应体及其 `Content-Type: application/vnd.amazon.eventstream` 头，不要将流转换为服务器发送事件。请参阅[网关或代理后面的流式传输错误](/docs/zh-CN/amazon-bedrock#streaming-errors-behind-a-gateway-or-proxy)。

也转发保活 ping。在通过 `ANTHROPIC_BASE_URL` 或 `ANTHROPIC_AWS_BASE_URL` 的连接上，Claude Code 计算网关中继的每个字节，包括 SSE `ping` 事件和注释行，并默认在 300 秒内中止无声流。上游的 ping 是长思考暂停期间的唯一流量，因此如果您的网关剥离或缓冲它们，Claude Code 会在这些暂停期间中止流；[自动重试](/docs/zh-CN/errors#automatic-retries)涵盖了根据响应进度如何报告中止的流。完全不发送 ping 的上游（如 Amazon Bedrock 的二进制事件流）在这些暂停中没有任何东西可转发。从这样的上游转换时，在无声间隙期间发出您自己的 `ping` 事件。通过 `ANTHROPIC_BEDROCK_BASE_URL`、`ANTHROPIC_VERTEX_BASE_URL` 或 `ANTHROPIC_FOUNDRY_BASE_URL` 到达的网关不受此字节级监视程序的包装，即使它们中继 Anthropic Messages 格式；在那里，[5 分钟空闲超时](/docs/zh-CN/env-vars)会中止无声流，在 `ANTHROPIC_BEDROCK_BASE_URL` 连接上，您可以使用 [`CLAUDE_ENABLE_BYTE_WATCHDOG_BEDROCK`](/docs/zh-CN/env-vars) 添加字节监视程序。

<h3 id="format-mismatch-with-the-upstream">
  与上游的格式不匹配
</h3>

客户端使用的格式决定了您的网关接收的内容。常见的失败模式是客户端发送到您的网关的格式与其后面的上游提供商接受的格式不匹配。

* 当客户端使用 Amazon Bedrock 或 Google Cloud 的 Agent Platform 格式时，Claude Code 仅发送这些提供商接受的完整功能集的子集
* 当客户端使用 Anthropic Messages 格式时，Claude Code 发送完整集，即使您的网关转发到 Amazon Bedrock 或 Google Cloud 的 Agent Platform 上游

弥合这种差异是您的网关的工作。[功能传递](#feature-pass-through)描述了当它不这样做时会破坏什么。

如果您的上游是 Amazon Bedrock 或 Google Cloud 的 Agent Platform，您可以通过公开该提供商的格式来避免桥接。[通过网关路由到云提供商](/docs/zh-CN/llm-gateway-connect#route-to-a-cloud-provider-through-a-gateway)显示了该格式的客户端配置。

<h2 id="how-the-connection-method-changes-client-behavior">
  连接方法如何改变客户端行为
</h2>

开发者连接到网关的方式决定了 Claude Code 发送的模型 ID、`anthropic-beta` 值和请求字段，以及它应用的默认值。您的网关会看到以下三种客户端行为之一：

* **Amazon Bedrock 或 Agent Platform 格式**：开发者设置 `CLAUDE_CODE_USE_BEDROCK=1` 和 `ANTHROPIC_BEDROCK_BASE_URL`，或 `CLAUDE_CODE_USE_VERTEX=1` 和 `ANTHROPIC_VERTEX_BASE_URL`，指向您的网关。Claude Code 使用该提供商的模型 ID、请求字段和默认值。
* **Anthropic Messages 格式**：开发者将 `ANTHROPIC_BASE_URL` 设置为您的网关。Claude Code 将网关视为 Claude API，无法判断您转发到哪个上游。
* **Claude apps gateway 登录**：开发者登录到 [Claude apps gateway](/docs/zh-CN/claude-apps-gateway)。该网关使用 Anthropic Messages 格式，但可以路由到任何上游，因此 Claude Code 仅发送 Amazon Bedrock 和 Agent Platform 也接受的 `anthropic-beta` 值和模型能力假设。

<h3 id="requests-and-defaults-by-connection-method">
  按连接方法的请求和默认值
</h3>

下表比较了三种连接方法，每行一个行为。它省略了 Microsoft Foundry 和 Claude Platform on AWS，它们也使用 Anthropic Messages 格式，但 Claude Code 通过它们自己的变量访问。有关这些，请参阅 [Microsoft Foundry](/docs/zh-CN/microsoft-foundry) 和 [Claude Platform on AWS](/docs/zh-CN/claude-platform-on-aws) 页面。

| 行为 | Amazon Bedrock 或 Agent Platform 格式 | Anthropic Messages 格式 | Claude apps gateway 登录 |
| :- | :- | :- | :- |
| 默认情况下请求中的模型 ID | 提供商的形式，例如 Amazon Bedrock 上的 `us.anthropic.claude-opus-4-8` | Anthropic ID，例如 `claude-opus-4-8` | Anthropic ID |
| 发送的 `anthropic-beta` 值 | Amazon Bedrock 和 Agent Platform 接受的子集 | [功能传递](#feature-pass-through)下描述的完整集合，除非开发者设置 [`CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS`](#disable-pre-release-capabilities) | Amazon Bedrock 和 Agent Platform 接受的子集 |
| Claude Code 无法识别的模型 ID（例如网关别名）的请求字段 | 使用固定预算的思考而不是自适应推理，以及没有努力或上下文管理字段 | 当前 Claude 模型在 Claude API 上接受的所有内容，包括自适应推理、努力和上下文管理，Amazon Bedrock 或 Agent Platform 上游可能会拒绝 | 与 Amazon Bedrock 或 Agent Platform 格式相同 |
| 开发者选择加入时的一小时 [prompt cache TTL](/docs/zh-CN/prompt-caching#choose-the-ttl-yourself) | 通过 `cache_control` 中的 `ttl` 字段请求，没有 beta 值 | 通过 `ttl` 字段加上 `anthropic-beta` 中的 `extended-cache-ttl` 值请求，您必须转发 | 请参阅 Claude apps gateway [可用性和限制](/docs/zh-CN/claude-apps-gateway#availability-and-limitations) 表 |
| [后台任务](/docs/zh-CN/costs#background-token-usage) 的模型，除非 `ANTHROPIC_DEFAULT_HAIKU_MODEL` 固定一个 | 默认 Sonnet 模型，或选择主模型后的主模型，如 [Amazon Bedrock](/docs/zh-CN/amazon-bedrock#4-pin-model-versions) 和 [Agent Platform](/docs/zh-CN/google-vertex-ai#5-pin-model-versions) 页面所述 | 主模型，或当 `ANTHROPIC_API_KEY` 或 `apiKeyHelper` 提供 Anthropic Console 密钥且 `ANTHROPIC_AUTH_TOKEN` 未设置时的默认 Haiku 模型 | 主模型 |

有关每个连接支持的功能以及它默认发送给 Anthropic 的遥测，请参阅 [功能可用性](/docs/zh-CN/feature-availability#availability-by-model-provider) 和 [按 API 提供商的默认行为](/docs/zh-CN/data-usage#default-behaviors-by-api-provider)。

<h3 id="settings-for-unrecognized-model-ids">
  未识别模型 ID 的设置
</h3>

两个客户端设置改变了 Claude Code 对它无法识别的模型 ID 的假设，无论开发者使用哪种连接方法：

* **上下文窗口**：Claude Code 假设 200K，或当 ID 包含 `[1m]` 时为 1M。要声明真实窗口，请参阅 [为网关或自定义模型 ID 更正窗口](/docs/zh-CN/model-config#correct-the-window-for-a-gateway-or-custom-model-id)
* **能力**：要给网关别名赋予其后面模型的能力，请使用您分发的设置中的 [`modelOverrides`](/docs/zh-CN/errors#unrecognized-model-id-on-a-request) 条目将该模型的 Anthropic ID 映射到您的别名。有关 `ANTHROPIC_DEFAULT_*_MODEL_SUPPORTED_CAPABILITIES` 变量适用的位置，请参阅 [功能传递](#feature-pass-through)

<h2 id="request-headers">
  请求头
</h2>

Claude Code 在 API 请求中包含这些头。头名称在网络上不区分大小写。转发 `anthropic-version` 和 `anthropic-beta` 不变，以及当上游是 [AWS 上的 Claude Platform](/docs/zh-CN/claude-platform-on-aws) 时的 `anthropic-workspace-id`；其余的网关可能会用于路由、归属和追踪，不需要转发。

| 头 | 描述 |
| :- | :- |
| `Authorization`, `x-api-key` | 开发者的网关凭证，根据他们设置的 [凭证变量](/docs/zh-CN/llm-gateway-connect#set-the-credential-variable) 在一个或两个头中 |
| `anthropic-version` | API 版本，目前为 `2023-06-01`。Amazon Bedrock 和 Google Cloud 的 Agent Platform 格式请求也在 `anthropic_version` 请求体字段中携带，其值是提供商方言字符串，而不是此头的值 |
| `anthropic-beta` | 请求的逗号分隔的能力值。逐字转发该头；不要对单个值进行白名单，因为该集合随 Claude Code 版本而变化。当开发者使用 claude.ai 登录进行身份验证时（当设置 `ANTHROPIC_BASE_URL` 而没有网关凭证变量时可能），此头也会携带上游所需的 OAuth 能力，删除它会导致这些请求失败并返回 `401` |
| `x-claude-code-session-id` | 当前 Claude Code 会话的唯一标识符。使用它来聚合来自一个会话的所有请求，无需解析请求体 |
| `x-claude-code-agent-id` | 发出请求的 [子代理](/docs/zh-CN/sub-agents) 的标识符，仅在来自代理在会话内生成的 Claude Code 的请求上存在。将其与会话 ID 一起使用以将成本归属于并行代理 |
| `x-claude-code-parent-agent-id` | 生成请求代理的代理的标识符，仅对嵌套代理存在 |

子代理 ID 在每次 Claude Code 生成子代理时都会生成新的。队友代理是 [代理团队](/docs/zh-CN/agent-teams) 的命名成员，在重新连接时重用基于名称的稳定 ID。在两种情况下，ID 都标识一个代理，而不是一个人或设备，因此不要将代理 ID 头视为用户标识符。

如果您的开发者设置了 `ANTHROPIC_CUSTOM_HEADERS`，这些头也会出现在请求上。

<h3 id="gateway-hint-headers">
  网关提示头
</h3>

Claude Code 也可以发送路由提示：网关或路由器可以用来调度、缓存或归属请求的每个请求事实。需要 Claude Code v2.1.273 或更高版本。

请求是否携带它们取决于 Claude Code 将其发送到何处：

* 直接连接到 Anthropic API：默认发送
* 自定义基础 URL：默认关闭，因为拒绝未知头的代理会导致请求失败。要接收它们，请为您的开发者设置 [`CLAUDE_CODE_GATEWAY_HINT_HEADERS=1`](/docs/zh-CN/env-vars)，例如在 [托管设置](/docs/zh-CN/managed-settings) 的 `env` 块中
* 任何其他后端，包括 Amazon Bedrock、Google Cloud 的 Agent Platform、Microsoft Foundry 和 AWS 上的 Claude Platform：仅当设置 `CLAUDE_CODE_GATEWAY_HINT_HEADERS=1` 时发送

将 `CLAUDE_CODE_GATEWAY_HINT_HEADERS` 设置为 `0` 会在每个连接上停止这些头。

这些头仅携带下面行列出的内容：固定词汇、工具名称、持续时间和随机提示标识符，从不携带提示文本或文件内容。每个值都是可打印的 ASCII。

| 头 | 描述 |
| :- | :- |
| `x-claude-code-request-class` | 这是什么类型的请求：`main` 用于主对话的一个回合，`subagent` 用于 [子代理](/docs/zh-CN/sub-agents) 的一个回合，`workflow` 用于在工作流内运行的代理，`compaction` 用于压缩对话的总结请求，或 `auxiliary` 用于侧面请求，如会话标题、分类器和摘要。在每个请求上发送 |
| `x-claude-code-agent-type` | 发出请求的子代理的类型：内置代理类型名称，如 `Explore`、`Plan` 或 `general-purpose`，或 `custom` 用于用户定义的代理，`teammate` 用于在主导的进程中运行的 [代理团队](/docs/zh-CN/agent-teams) 成员，或 `fork` 用于 [分叉](/docs/zh-CN/sub-agents#fork-the-current-conversation)。仅在子代理自己的回合上存在；子代理的压缩或侧面请求保留代理 ID 但不携带类型。用户选择的代理名称永远不会被发送 |
| `x-claude-code-compaction` | 在 [压缩](/docs/zh-CN/prompt-caching#compacting-the-conversation) 期间总结对话的请求上存在。该值说明触发了什么：`auto` 当上下文窗口接近容量时，`manual` 用于 `/compact`，或 `reactive` 当 API 拒绝请求过长时。在所有其他请求上不存在 |
| `x-claude-code-context-compacted` | 在压缩后的第一个主对话请求上存在一次，具有与 `x-claude-code-compaction` 相同的值。此请求之前的对话前缀不再使用，因此可以删除以其为键的缓存 |
| `x-claude-code-prev-tool-durations` | 此请求携带其结果的工具调用的测量运行时间，格式为 `<name>=<ms>;<name>=<ms>`，例如 `Bash=742;Read=9`。在同一对话的下一个请求之后发送，来自主会话或子代理的一批工具调用 |
| `x-claude-code-prompt-id` | 标识请求所服务的用户提示的随机 UUID。服务一个提示的请求共享该值，包括提示启动的子代理的回合。未归属于提示的请求省略它。使用它按提示对会话的请求进行分组。需要 Claude Code v2.1.283 或更高版本 |

在解析 `x-claude-code-prev-tool-durations` 之前，检查 Claude Code 如何构建该值以及它遗漏了什么：

* 条目：每个运行的工具调用一个，按其结果被收集的顺序，以整毫秒为单位
* 上限：Claude Code 最多发送 32 个条目和 4 KB，保留第一个条目
* 编码：工具名称是百分比编码的，涵盖 `%`、`;`、`=`、逗号、空格和任何可打印 ASCII 之外的字符
* 解析：在 `;` 上分割，然后在 `=` 上分割，并解码每个名称
* 缺失：压缩调用、侧面请求和新提示的第一个请求永远不会携带它。不要将缺失的头读作运行无工具的回合
* 时间：每个都排除权限提示和钩子，并行工具调用各自报告自己的时间，因此条目不会加起来等于请求之间的间隙

<h3 id="forward-as-open-lists">
  作为开放列表转发
</h3>

将头和请求体字段视为开放列表，而不是封闭列表。Claude Code 在版本中获得能力，它们作为新的 `anthropic-beta` 值、新的请求体字段，以及偶尔新的 `anthropic-*` 或 `x-claude-code-*` 头到达。

转发到 Anthropic 格式上游时，通过 `anthropic-*` 请求头和请求体字段不变，而不是对您今天看到的进行白名单。固定在观察列表上的网关会删除下一个能力的头或字段，并在引入它的版本上破坏它。

例外是非 Anthropic 上游，如 Amazon Bedrock 或 Google Cloud 的 Agent Platform，其中桥接模式差异是网关的工作；请参阅 [功能传递](#feature-pass-through)。

<h2 id="response-headers">
  响应头
</h2>

Claude Code 读取这些响应头来检测停滞的流、决定是否以及何时重试，以及显示使用限制。该表列出了每个响应头应返回的内容。同时转发错误响应体不做修改，以便 Claude Code 的[能力拒绝恢复](#automatic-retry-and-error-forwarding)可以匹配上游的错误措辞。

| 头部 | 返回内容及原因 |
| :- | :- |
| `content-type` | 在流式 Anthropic Messages 格式响应上返回 `text/event-stream`，在 Amazon Bedrock 格式响应上返回 `application/vnd.amazon.eventstream`（不做修改），其中[不同的类型会导致请求失败](/docs/zh-CN/amazon-bedrock#streaming-errors-behind-a-gateway-or-proxy)。[流式传输](#streaming)列出了哪些连接在这些流上运行停滞检测 |
| `retry-after` | 返回整数秒而不是 HTTP 日期。Claude Code 在下一次[自动重试](/docs/zh-CN/errors#automatic-retries)之前至少等待该时长，在 [`CLAUDE_CODE_RETRY_WATCHDOG`](/docs/zh-CN/env-vars) 会话之外，超过 60 的值会停止重试并立即显示错误 |
| `x-should-retry` | 原样转发上游的值。Claude Code 在决定是否重试失败的请求时将此头部作为一个输入来读取：`true` 标记响应可重试，`false` 标记响应不可重试。有关重试次数、退避和 Claude Code 重试的失败情况，请参阅[自动重试](/docs/zh-CN/errors#automatic-retries) |
| `anthropic-ratelimit-unified-*` | 在每个响应上原样转发上游的值。Claude Code 在成功响应上读取它们以向使用 claude.ai 登录的开发人员显示针对计划限制的使用情况，在 `429` 上读取它们以区分计划限制或支出上限与临时限流；请参阅[使用限制](/docs/zh-CN/errors#usage-limits) |

<h2 id="system-prompt-attribution-block">
  系统提示归属块
</h2>

Claude Code 在系统提示前面加上一个短的归属块，其中包含客户端版本和从对话派生的指纹。`api.anthropic.com` 端点在处理前删除该块，因此它不会影响第一方提示缓存。任何其他上游都会将其作为提示的一部分接收。

该删除是位置相关的，因此只有在网关原样转发 `system` 数组时才有效。要在不丢失其他系统内容的情况下将该块排除在提示之外：

* 完全按照接收的方式转发 `system` 数组，保持该块在最前面：在前面加上另一个系统块、重新排序数组或将其转换为单个字符串会破坏删除，该块随后会到达模型和提示缓存键。
* 将该块保留在其自己的数组条目中：端点将以归属标头开头的合并块视为完整的归属，并删除合并到其中的所有内容，包括系统提示的其余部分。
* 如果您的网关必须重新整形系统内容，请设置 [`CLAUDE_CODE_ATTRIBUTION_HEADER=0`](/docs/zh-CN/env-vars) 以便 Claude Code 省略该块。Anthropic 和云提供商的 Claude 端点读取该块以进行归属，因此要在客户端省略它，而不是在网关中删除或移动它。

该变量存在是为了网关和第三方缓存兼容性，而不是作为隐私控制：在直接连接上，完整请求无论如何都已经发送到 Anthropic API。当以下两个条件都成立时，Claude Code 即使在您将变量设置为 `0` 时也会在 [auto mode](/docs/zh-CN/permission-modes#eliminate-prompts-with-auto-mode) 分类器请求上保留该块：

* 请求发送到 `api.anthropic.com`，`ANTHROPIC_BASE_URL` 未设置或命名该主机，且未选择第三方提供商。
* 活跃凭证不是 [Anthropic 配置文件或联合凭证](/docs/zh-CN/authentication#anthropic-profiles-and-federation-credentials)。

分类器请求跳过 Claude Code 系统提示的其余部分，因此在这些请求上该块是请求体中唯一标识它们为 Claude Code 流量的标记。当任一条件失败时，通过 LLM 网关、在第三方提供商上或使用活跃的配置文件或联合凭证，设置 `0` 也会从分类器请求中删除该块。在 v2.1.229 之前，此例外不存在：设置 `0` 会从这些分类器请求中删除该块，当 API 拒绝未识别的请求时，auto mode 在它发送给分类器的每个操作上都会失败。

从 Claude Code v2.1.181 开始，当请求通过自定义基础 URL 路由时，该块在对话的生命周期内是稳定的，因此以完整请求体为键的网关端提示缓存可以在不禁用它的情况下工作，您的网关转发到的任何提供商都会接收稳定的提示前缀。在 v2.1.181 之前，该块包含每个请求的令牌，在系统提示的开始处改变了每个请求。在这些版本上，当您的网关执行以下任一操作时，请设置 `CLAUDE_CODE_ATTRIBUTION_HEADER=0`：

* 实现以请求体为键的提示缓存。
* 将请求转发到第三方提供商，例如 Amazon Bedrock、Microsoft Foundry 或 Google Cloud 的 Agent Platform，采用 Anthropic Messages 格式或提供商自己的格式，其中变化的前缀会减少该提供商上的提示缓存重用。

<h2 id="feature-pass-through">
  功能传递
</h2>

Claude Code 将 `ANTHROPIC_BASE_URL` gateway 视为 Anthropic 格式端点，并向其发送它发送到 `api.anthropic.com` 的 beta 请求头和请求体字段，除了为直接连接保留的一小组诊断和默认值，例如下面涵盖的细粒度工具流式传输默认值。该集合因版本而异，因此不要依赖其内容。

添加请求体字段的功能将它们与 beta 请求头配对，该对一起传递。删除请求头同时传递请求体的 gateway，或将 Anthropic 格式请求体转发到具有不同架构的上游，会产生硬 `400` 错误；只有当两个部分一起缺失时，功能才会安静地关闭。重写或编辑请求体以进行内容检查的 gateway 会以与删除相同的方式破坏配对，因此在不修改的情况下检查。该表注明了功能偏离配对的位置。

细粒度工具流式传输是直接连接默认值之一：每当请求通过自定义基础 URL 路由时，它默认关闭，当开发者设置 [`CLAUDE_CODE_ENABLE_FINE_GRAINED_TOOL_STREAMING=1`](/docs/zh-CN/env-vars) 时，gateway 会接收它。

| 功能 | 请求头和请求体对 | 破坏时的症状 | 补救 |
| :- | :- | :- | :- |
| [自适应推理](/docs/zh-CN/model-config#adjust-effort-level) | 无 beta 请求头。Claude Code 为 Claude 4.6 及更高版本发送 `thinking: {"type": "adaptive"}`，并将它不识别的模型名称（如 gateway 别名）视为接收该字段的当前模型 | 当上游模型构建不接受它时，命名 `thinking` 字段或 `adaptive` 标签的 `400` | 升级上游。在 Opus 4.6 和 Sonnet 4.6 上，开发者可以改为设置 `CLAUDE_CODE_DISABLE_ADAPTIVE_THINKING=1` |
| [上下文管理](https://platform.claude.com/docs/en/build-with-claude/context-editing) | 上下文管理 beta 请求头与 `context_management` 请求体字段配对 | `400` 带有 `Extra inputs are not permitted`。常见于 gateway 接受 Anthropic 格式请求但将其转发到 Amazon Bedrock 时 | 转发两者，或 [`CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS=1`](/docs/zh-CN/env-vars) |
| [扩展上下文](https://platform.claude.com/docs/en/build-with-claude/context-windows#context-window-sizes-by-model)和[交错思考](https://platform.claude.com/docs/en/build-with-claude/extended-thinking#interleaved-thinking) | 仅 Beta 请求头，无请求体字段 | 当请求头被删除时无声地不可用；上游永远不会看到功能请求 | 逐字转发 `anthropic-beta` |
| Beta [工具字段](https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview) | 工具相关的 beta 请求头与工具架构字段（如 `strict` 和 `defer_loading`）配对 | 当请求体通过而没有其请求头时，命名无法识别的工具架构字段的 `400` | 转发两者，或 [`CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS=1`](#disable-pre-release-capabilities) |
| [努力](https://platform.claude.com/docs/en/build-with-claude/effort)和[结构化输出](https://platform.claude.com/docs/en/build-with-claude/structured-outputs) | `output_config` 请求体字段携带努力、结构化输出格式和任务预算设置；每个都与自己的 beta 请求头配对 | 在 Amazon Bedrock 和 Google Cloud 的 Agent Platform 上游上命名 `output_config` 的 `400`，通常是 `Extra inputs are not permitted` | 一起转发字段及其请求头 |
| [提示缓存](/docs/zh-CN/prompt-caching) | 无 beta 配对。Claude Code 将 `cache_control` 标记附加到 `system` 块和 `messages` 条目，包括在对话中途附加的 `role: "system"` 条目 | 无错误：对话在每个回合都作为未缓存的输入计费，在 `usage` 中可见为高 `input_tokens` 且缓存活动很少或没有 | 在任何地方原封不动地转发 `cache_control`，并且不要将块形式的 `system` 或消息内容转换为纯字符串 |
| [令牌计数](https://platform.claude.com/docs/en/build-with-claude/token-counting) | 无 beta 配对；使用 `count_tokens` 端点 | 无错误：Claude Code 回退到基于字符的估计，因此 `/context` 显示近似计数 | 公开该端点以获得精确的令牌计数 |

`ANTHROPIC_DEFAULT_*_MODEL_SUPPORTED_CAPABILITIES` [变量](/docs/zh-CN/model-config)仅在提供商配置中声明模型功能：`CLAUDE_CODE_USE_BEDROCK`、`CLAUDE_CODE_USE_VERTEX`、`CLAUDE_CODE_USE_FOUNDRY` 和 [`CLAUDE_CODE_USE_MANTLE`](/docs/zh-CN/amazon-bedrock#use-the-mantle-endpoint)。它们在 `ANTHROPIC_BASE_URL` gateway 后面没有效果。

<h3 id="automatic-retry-and-error-forwarding">
  自动重试和错误转发
</h3>

Claude Code 在上游拒绝后的操作取决于被拒绝的内容：

* 当上游拒绝 `thinking` 字段、中途对话系统消息或这些消息之一上的 `cache_control` 标记时，Claude Code 会重试请求并为对话的其余部分禁用被拒绝的功能
* 当上游拒绝[思考签名](https://platform.claude.com/docs/en/build-with-claude/extended-thinking)时，包括带有 `400` 的拒绝，其消息说该块被 `bound to a different conversation`，Claude Code 会从请求中删除早期思考块，重试，并将其排除在每个后续请求之外。新响应仍然包括思考
* 当 gateway 或其上游将[顾问工具](/docs/zh-CN/advisor)条目在 `tools` 中拒绝为无法识别的工具类型时，Claude Code 会重试一次请求，不包含该条目及其 `anthropic-beta` 值。对该基础 URL 的后续请求会将顾问排除在外，直到 Claude Code 退出，在该时间内 `/advisor` 对开发者不可用。Claude Code 通过 `400` 或 `422` 响应识别此拒绝，其消息在 `Input tag` 之后命名工具类型，例如 `Input tag 'advisor_20260301'`。在 v2.1.280 之前，Claude Code 没有重试此拒绝
* Claude Code 不重试上下文管理或工具架构字段拒绝，因此这些 `400` 错误到达开发者

`bound to a different conversation` 拒绝来自 API 的[保留思考](https://platform.claude.com/docs/en/build-with-claude/preserved-thinking)检查，当 `system`、`tools` 或早期 `messages` 内容与产生思考的请求不同时，该检查失败。重写任何该内容的 gateway 可能会导致拒绝本身；[库、代理和网关](https://platform.claude.com/docs/en/build-with-claude/preserved-thinking#libraries-proxies-gateways)涵盖了要原封不动地传递的内容。

重试逻辑与上游的错误措辞匹配，因此原封不动地转发错误响应体。将上游错误包装在自己的信封中的 gateway 会破坏恢复路径，即使它保留了状态代码，除非信封的消息携带稳定的 `capability_rejected:` 令牌。[Claude apps gateway 为云提供商的错误措辞替换这些令牌](/docs/zh-CN/claude-apps-gateway-config#upstream-error-messages)，例如 `capability_rejected: prompt_too_long`。

<h3 id="disable-pre-release-capabilities">
  禁用预发布功能
</h3>

`CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS=1` 阻止 Claude Code 发送预发布功能及其请求体字段，包括上下文管理和 beta 工具字段。该变量不影响自适应推理，后者由模型而不是 beta 选择。它永远不会抑制订阅身份验证所需的 OAuth 功能。

当嵌入 Claude Code 的主机平台设置 [`CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST`](/docs/zh-CN/env-vars) 时，`CLAUDE_CODE_DISABLE_EXPERIMENTAL_BETAS` 不会阻止 Amazon Bedrock、Google Cloud 的 Agent Platform、Microsoft Foundry 或 [Claude apps gateway](/docs/zh-CN/claude-apps-gateway) 上的自动模式会话向服务器请求[分类器审查](/docs/zh-CN/permission-modes#server-side-classifier-review)。该审查添加了 `anthropic-beta` 值和 `safeguards` 请求字段。设置 `CLAUDE_CODE_AUTO_MODE_SERVER=0` 以在那里停止它。

在 Claude Code v2.1.227 或更高版本上，您的组织可以通过[托管设置](/docs/zh-CN/managed-settings)在此变量下保持 [MCP 工具搜索](/docs/zh-CN/mcp#scale-with-mcp-tool-search)打开。Claude Code 在该覆盖生效时发送的内容取决于您如何连接：

* 在直接连接上，或通过设置了 `ANTHROPIC_BASE_URL` 的 gateway，Claude Code 继续发送工具搜索 beta 请求头、`defer_loading` 工具字段和 `tool_reference` 块，并删除其余部分
* 在云提供商上，或通过 [Claude apps gateway](/docs/zh-CN/claude-apps-gateway) 登录，覆盖没有效果

Claude Code 发送的功能集随版本增长。有关当前 beta 请求头字符串，请参阅 [beta 请求头参考](https://platform.claude.com/docs/en/api/beta-headers)；针对新的 Claude Code 版本测试您的 gateway，而不是固定到观察列表。

<h2 id="model-discovery">
  模型发现
</h2>

当 `ANTHROPIC_BASE_URL` 指向公开 Anthropic Messages 格式的 gateway 时，Claude Code 可以在启动时查询 gateway 的 `/v1/models` 端点，并将返回的模型添加到 `/model` 选择器。如果您或您的管理员在 [`modelPicker`](/docs/zh-CN/settings-reference#modelpicker) 配置中设置了 `replaceBuiltInOptions`，Claude Code 会从选择器中隐藏发现的模型。

开发者通过在自己的环境中或通过托管设置设置 [`CLAUDE_CODE_ENABLE_GATEWAY_MODEL_DISCOVERY=1`](/docs/zh-CN/env-vars) 来启用它。发现默认关闭，以便由共享 API 密钥支持的 gateway 不会向每个用户公开密钥可以访问的每个模型。

<h3 id="when-discovery-runs">
  发现何时运行
</h3>

发现仅适用于 Anthropic Messages 格式。在以下情况下不运行：

* 设置了任何 `CLAUDE_CODE_USE_*` 提供商变量，即使也设置了 `ANTHROPIC_BASE_URL`
* `ANTHROPIC_BASE_URL` 未设置或指向 `api.anthropic.com`

当[非必要流量被禁用](/docs/zh-CN/llm-gateway-connect#turn-off-traffic-outside-the-gateway-path)时，发现仍然运行，因为请求仅发送到您的 gateway。在 v2.1.257 之前，非必要流量被禁用时发现不运行。

<h3 id="request-and-response">
  请求和响应
</h3>

请求是 `GET /v1/models?limit=1000`，超时为 3 秒，任何重定向都被视为失败，因此凭证不会泄露到重定向目标。响应缓慢或重定向 `/v1/models` 的 gateway，即使是 `http` 到 `https`，也会无声地失败发现；在配置的基础 URL 处直接提供端点。

要给缓慢的 gateway 更长的时间，请设置 [`CLAUDE_CODE_GATEWAY_MODEL_DISCOVERY_TIMEOUT_MS`](/docs/zh-CN/env-vars#variables)。该变量需要 Claude Code v2.1.269 或更高版本。

Claude Code 使用下面两个凭证请求头发送发现请求，并省略其值无法解析的请求头。发送两个请求头需要 Claude Code v2.1.248 或更高版本。早期版本在设置了 `ANTHROPIC_AUTH_TOKEN` 时仅发送 `Authorization`，否则仅发送 `x-api-key`。

* `Authorization`：`ANTHROPIC_AUTH_TOKEN` 作为承载令牌，否则 [`apiKeyHelper`](/docs/zh-CN/llm-gateway-connect#rotate-credentials-with-apikeyhelper) 值作为承载令牌。在这种情况下，Claude Code 在发送请求前等待助手返回。
* `x-api-key`：Claude Code 解析的 API 密钥，例如 `ANTHROPIC_API_KEY`。当助手值是唯一的凭证时，此请求头也会携带它，因此该值会在两个请求头中到达。

Claude Code 还发送来自 `ANTHROPIC_CUSTOM_HEADERS` 的任何请求头。当自定义请求头具有非空值时，Claude Code 会发送它来代替同名的内置请求头，不区分大小写地匹配名称。

当两个凭证请求头的值都无法解析时，Claude Code 会跳过发现，并在 `claude --debug` 会话的调试日志中写入 `[gatewayDiscovery] skipped` 行。如果您仅通过 `ANTHROPIC_CUSTOM_HEADERS` 提供凭证，Claude Code 仍然会跳过发现。

Claude Code 从响应的 `data` 数组中的每个条目读取 `id`、可选的 `display_name` 和可选的 `description`：

```json theme={null}
{
  "data": [
    {
      "id": "claude-sonnet-4-6",
      "display_name": "Claude Sonnet 4.6",
      "description": "Default model for everyday coding tasks"
    },
    { "id": "claude-opus-4-8" }
  ]
}
```

Claude Code 在其 `id` 中任何位置包含 `claude` 或 `anthropic` 的条目会被保留，不区分大小写，其余的会被忽略。提供商前缀的 ID，例如 `vertex_ai/claude-sonnet-4-6` 或 `bedrock/anthropic.claude-sonnet-4-5` 会通过过滤器；不包含任何一个子字符串的 ID 则不会。在 v2.1.223 之前，Claude Code 仅在其 `id` 以 `claude` 或 `anthropic` 开头时保留条目，这隐藏了提供商前缀的 ID。

<h3 id="picker-entries-and-caching">
  选择器条目和缓存
</h3>

选择器是当开发者在 Claude Code 中运行 `/model` 时打开的交互式模型列表。每个发现的条目在 gateway 发送与 `id` 不同的 `display_name` 时使用 `display_name` 作为其名称。否则，当 Claude Code [识别 `id`](/docs/zh-CN/model-config#customize-pinned-model-display-and-capabilities) 时，条目显示模型的名称，当不识别时显示 `id`。例如，具有 `id` `my-gateway-claude-sonnet-4-6` 且没有 `display_name` 的条目显示为 `Sonnet 4.6`。

发现仅添加 [`availableModels` 托管设置](/docs/zh-CN/settings-reference#availablemodels) 允许的模型。

每个条目还显示模型的 `description`，折叠为一行。没有 `description` 的条目改为显示"From gateway"。在 v2.1.257 之前，每个发现的条目都显示"From gateway"。

当发现的 ID 与选择器中已有的行匹配时，它不会获得自己的行：

* 相同 ID：发现的 ID 完全匹配现有行的 ID，或两个 ID 是同一 [Fable](/docs/zh-CN/model-config#work-with-fable) 版本的拼写。
* 与内置别名相同的模型：当发现的显式 ID 命名内置别名当前解析到的模型时，选择器仅显示别名行。例如，当 `sonnet` 解析为 `claude-sonnet-5-5` 时，发现的 `claude-sonnet-5-5` 会折叠到 `sonnet` 行中，而发现的 `claude-sonnet-5` 仍会获得自己的行。在 v2.1.197 之前，Claude Code 不会将这些 ID 折叠到内置行中，因此别名解析到的 ID 也会获得自己的"From gateway"行。

结果被缓存到 `~/.claude/cache/gateway-models.json`，或在 Windows 上 `%USERPROFILE%\.claude\cache\gateway-models.json`，并在每次启动时刷新。如果您设置了 [`CLAUDE_CONFIG_DIR`](/docs/zh-CN/env-vars)，缓存会改为位于该目录下。如果请求失败或 gateway 未实现 `/v1/models`，选择器会回退到上次启动的缓存列表或内置模型列表。如果您的 gateway 在不匹配发现过滤器的别名下提供 Claude 模型，开发者可以使用[模型配置](/docs/zh-CN/model-config)变量手动添加这些别名。

<h2 id="related-resources">
  相关资源
</h2>

有关 gateway 文档集的其余部分和基础 API 参考：

* [Gateway 概述](/docs/zh-CN/gateways)：什么是 gateway 以及如何在 Claude 应用 gateway 和其他产品之间进行选择
* [其他 LLM gateway](/docs/zh-CN/llm-gateway)：如何推出您的组织运行的 gateway 以及它如何与 claude.ai 订阅交互
* [为您的组织推出 LLM gateway](/docs/zh-CN/llm-gateway-rollout)：使用此指南的管理员检查清单
* [将 Claude Code 连接到 LLM gateway](/docs/zh-CN/llm-gateway-connect)：每个开发者的配置和故障排除表
* [Beta 请求头参考](https://platform.claude.com/docs/en/api/beta-headers)：当前的 `anthropic-beta` 值集
* [Messages API](https://platform.claude.com/docs/en/api/messages)：Anthropic 格式 gateway 实现的 API 格式

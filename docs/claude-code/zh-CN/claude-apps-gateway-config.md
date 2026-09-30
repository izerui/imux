> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Claude 应用网关配置

> 每个 gateway.yaml 选项的参考：监听器和 TLS、OIDC、会话、Postgres 存储、Amazon Bedrock、Claude Platform on AWS、Google Cloud 的 Agent Platform 和 Microsoft Foundry 上游、模型路由、托管策略和遥测。

Claude 应用网关部署由一个 YAML 文件配置，按惯例命名为 `gateway.yaml`。该文件定义网关所做的一切：它在哪里监听、开发者如何登录、推理去往何处，以及应用哪些策略和遥测。本页是该文件中每个选项的参考。

要编写你的第一个配置，请从[快速入门](/docs/zh-CN/claude-apps-gateway#quickstart)开始，它构建一个最小的工作配置并运行它。一旦你有了满意的配置，[部署指南](/docs/zh-CN/claude-apps-gateway-deploy)涵盖了在 Kubernetes、Cloud Run 或你自己的平台上容器化和托管它。

网关在启动时使用 `claude gateway --config /path/to/gateway.yaml` 读取该文件一次。每个选项都在启动时根据模式进行验证，因此格式错误的配置在启动时失败并显示字段级错误，而不是在首次使用时失败。

本页末尾的[完整示例](#complete-example)演示了每个部分。

<h2 id="file-structure">
  文件结构
</h2>

五个部分是[必需的](#required-sections)。其他所有部分都是[可选的](#optional-sections)，省略的部分采用其默认值。未知的键会导致启动失败，因此拼写错误会显示为命名错误，而不是被静默忽略的设置。

**必需部分：**

* [`listen`](#listen)：绑定地址、公共 URL、TLS 终止
* [`oidc`](#oidc)：您的身份提供商 (IdP)，包括颁发者、客户端、声明映射以及谁可以登录
* [`session`](#session)：网关铸造的持有者令牌，包括密钥和生命周期
* [`store`](#store)：PostgreSQL，用于设备授权和速率限制计数器
* [`upstreams`](#upstreams)：推理的去向，无论是 Anthropic、Amazon Bedrock、Claude Platform on AWS、Google Cloud 的 Agent Platform 还是 Microsoft Foundry

**可选部分：**

* [`admin`](#admin)：Admin API 身份验证和支出限制的保留
* [`enforcement`](#enforcement)：支出限制故障开放或故障关闭行为
* [`pricing`](#pricing)：合同费率和支出计量器的折扣乘数以及开发人员看到的成本数字的折扣乘数
* [`models`](#models) 和 `auto_include_builtin_models`：管理员策划的模型列表和每个上游 ID
* [`managed`](#managed)：按 IdP 组的托管设置策略
* [`telemetry`](#telemetry)：OTLP 转发到您的可观测性堆栈
* [`access_control`、`limits`、`timeouts`、`rate_limits`](#http-tuning)：IP 允许/拒绝、请求大小上限、上游首字节时间和每 IP 登录限制
* [`load_test_mode`](#load_test_mode)：在不调用模型提供商的情况下对网关进行负载测试

<h2 id="secret-expansion">
  密钥扩展
</h2>

不要直接在 `gateway.yaml` 中写入密钥，如 `client_secret`、`jwt_secret` 或 `postgres_url`。使用下面的一种形式引用它们，网关在启动时从环境变量或文件解析该值：

| 形式 | 解析为 | 用于 |
| - | - | - |
| `${VAR}` | 环境变量 `VAR`。如果未定义，启动失败。 | 容器环境变量、通过环境注入的 AWS Secrets Manager |
| `${file:/path}` | 该绝对路径处的文件内容，已修剪。该引用必须是字段的整个值：与 `${VAR}` 不同，它不会在较长的字符串内展开，因此对于数据库密码，请设置 `store.password` 而不是将其嵌入 `postgres_url`。 | Kubernetes Secret 卷挂载、Vault Agent、SOPS |

<h2 id="required-sections">
  必需部分
</h2>

<h3 id="listen">
  `listen`
</h3>

`listen` 块控制网关服务的位置：绑定地址和端口、外部可见的源和可选的 TLS 终止。

| 字段 | 必需 | 描述 |
| - | - | - |
| `host` | 否 | 绑定地址。默认 `0.0.0.0`。 |
| `port` | 否 | 绑定端口。默认 `8080`。 |
| `public_url` | 除非 `host` 是环回地址 | 外部可见的 `https://` 源，用于构建 IdP `redirect_uri` 和发现元数据。在 `host` 不是环回地址时是必需的，无论 TLS 是在代理（如 ALB、Ingress 或 Cloud Run）还是通过 `tls` 在网关本身终止，因为网关从不从 `X-Forwarded-*` 头派生自己的源；它们是客户端可欺骗的。没有它启动会失败。下面的 `trusted_proxies` 仅控制客户端 IP 解析。要启用[遥测](#telemetry)也需要它，因为网关从此 URL 构建它推送给客户端的 OTLP 端点。 |
| `tls.cert` / `tls.key` | 否 | 如果网关自己终止 TLS，则为 PEM 路径 |
| `trusted_proxies` | 否 | 网关前面的负载均衡器的 CIDR 或 IP。设置时，网关仅从这些对等体信任 `X-Forwarded-For`，并记录真实客户端 IP 用于每 IP 速率限制和审计。等同于 nginx `set_real_ip_from`。`X-Forwarded-For` 条目写成 `ipv4:port` 或 `[ipv6]:port`（如某些负载均衡器所做的那样）被读取时端口被丢弃。带有端口附加且无括号的 IPv6 地址可能被读取为不同的地址或根本不被读取，因此在任何写入该形式的代理上关闭端口选项。 |

<h3 id="oidc">
  `oidc`
</h3>

`oidc` 块将网关连接到你的身份提供者，并决定谁可以登录。它命名发行者和 OAuth 客户端，映射携带电子邮件和组的声明，并按电子邮件域或组限制登录。

OpenID Connect (OIDC) 是网关与你的身份提供者一起使用的 SSO 协议；有关在 IdP 端注册的内容，请参阅[身份提供者设置](/docs/zh-CN/claude-apps-gateway-deploy#identity-provider-setup)。

| 字段 | 必需 | 描述 |
| - | - | - |
| `issuer` | 是 | OIDC 发现基础。必须在 `/.well-known/openid-configuration` 提供发现。在生产中使用 HTTPS；网关接受 `http://` 发行者。环回发行者（如 `http://localhost:8081`）被[SSRF 防护](/docs/zh-CN/claude-apps-gateway-deploy#threat-model-summary)拒绝，除非在网关的环境中设置了 `CLAUDE_GATEWAY_ALLOW_LOOPBACK=1`。 |
| `client_id` / `client_secret` | 是 | 来自你的 OAuth 客户端注册 |
| `allowed_email_domains` | 否 | 拒绝其 `email` 声明不在这些域之一中的 id\_token，不区分大小写。针对多租户 IdP 配置错误的纵深防御。独立于此设置，其 `email_verified` 声明明确为 `false` 的 id\_token 总是被拒绝。 |
| `allowed_groups` | 否 | 限制登录到这些 IdP 组的成员，与 `groups_claim` 匹配。允许的电子邮件域中但不在这些组中的用户被拒绝。需要 IdP 发出组声明。匹配是对该声明中的值的精确、区分大小写的字符串比较，网关不展开嵌套组：要允许子组的成员，在此处列出子组或配置 IdP 发出扁平成员身份。 |
| `groups_claim` | 否 | 哪个 id\_token 声明携带组成员身份。默认 `groups`。Microsoft Entra 在 `roles` 下发出应用角色。接受平面键或 RFC 6901 JSON 指针，如 `/resource_access/gateway/roles` 用于嵌套声明。 |
| `google_groups` | 否 | 通过 Google Workspace Admin SDK Directory API 查找已登录用户的组，因为 Google 的 id\_token 不携带组声明。将 `service_account_json_path` 设置为具有 `https://www.googleapis.com/auth/admin.directory.group.readonly` 范围的域范围委派的服务帐户密钥文件，并将 `admin_email` 设置为服务帐户模拟的 Workspace 管理员；Directory API 需要真实的管理员主体。每个用户的组电子邮件地址成为他们的组声明，因此 `allowed_groups` 和 `managed.policies.match.groups` 匹配组电子邮件。 |
| `email_claim` | 否 | 哪个 id\_token 声明携带用户的电子邮件。默认 `email`。某些 IdP（如 ADFS 和 Entra B2C）改为发出 `upn` 或 `preferred_username`。接受平面键、JSON 指针或回退键列表，其中使用第一个存在的键。 |
| `scopes` | 否 | 网关请求的 OIDC 范围的完全覆盖。默认 `[openid, profile, email, offline_access]`。当你的 IdP 拒绝它不识别的范围或需要自定义范围来发出组或电子邮件时设置。必须包括 `openid`。删除 `offline_access` 会禁用刷新令牌，因此开发者每 `session.ttl_hours` 重新运行浏览器登录。有关每个 IdP 范围配方（如 Google 的刷新令牌流），请参阅[身份提供者设置](/docs/zh-CN/claude-apps-gateway-deploy#identity-provider-setup)。 |
| `scope_on_refresh` | 否 | 在网关交换刷新令牌时也发送 `scope`，具有与登录请求相同的列表。默认 `false`：刷新请求省略 `scope`。大多数 IdP 在每次刷新时返回 id\_token，不需要这个。当你的 IdP 仅在再次请求 `openid` 时在刷新时返回 id\_token 时设置 `true`，这是 Okta 为其刷新授权记录的。没有 id\_token，每次刷新都依赖于 IdP 的 userinfo 端点接受刷新的访问令牌。如果你在登录或匹配策略上门控组，并且你的 IdP 的刷新时间 id\_token 省略了它们，也设置 `userinfo_fallback: true`，以便网关从 userinfo 端点填充它们。授予的范围少于请求的 IdP 可以用 `invalid_scope` 拒绝刷新，包括现有会话，如果你在此打开时向 `scopes` 添加条目。如果在设置后刷新在 `token_endpoint` 开始失败，取消设置该键。需要网关服务器上的 Claude Code v2.1.260 或更高版本。 |
| `extra_auth_params` | 否 | 附加到 IdP 授权请求的额外查询参数，逐字。这是 IdP 特定行为的覆盖机制，如 Google 刷新令牌的 `access_type: offline`、某些 Entra 租户的 `domain_hint` 或分步流的 `acr_values`。不能覆盖网关管理的协议参数：`state`、`nonce`、`redirect_uri`、PKCE、`scope`、`response_type`、`response_mode` 和 `client_id`。 |
| `userinfo_fallback` | 否 | 当 id\_token 省略电子邮件或组时，从 `/userinfo` 获取它们。Keycloak 轻量级访问令牌、Okta 组织服务器和 ADFS 最小令牌需要。id\_token 保持权威；userinfo 仅填补空白。默认 `false`。 |
| `use_pkce` | 否 | 在授权请求上发送 PKCE (S256) 质询。默认 `true`。仅当你的 IdP 为此机密客户端拒绝 PKCE 时设置 `false`。 |
| `clock_skew_seconds` | 否 | 验证 id\_token 时间声明时容忍时钟漂移。默认 `0`，这是严格的。如果由于主机/IdP 时钟偏差在登录后立即看到"令牌过期/尚未有效"错误，请提高。 |
| `token_endpoint_auth_method` | 否 | 覆盖令牌端点身份验证方法。接受 `client_secret_basic` 或 `client_secret_post`。默认自动协商。 |
| `id_token_signed_response_alg` | 否 | 预期的 id\_token 签名算法。默认 `RS256`。为使用 ES256、PS256 或 EdDSA 签名的 IdP 设置。 |
| `additional_authorized_parties` | 否 | 除 `client_id` 外要接受的额外 `azp` 值，用于 Keycloak 代理和令牌交换流 |
| `discovery_url` | 否 | 从此 URL 而不是从 `issuer` 派生发现文档，用于代理后面重写发行者主机的 IdP。路径必须包含 `/.well-known/`。 |
| `use_proxy` | 否 | 通过 `HTTPS_PROXY` 或 `HTTP_PROXY` 中的转发代理发送网关自己的 IdP 请求，尊重 `NO_PROXY`。`false` 保持这些请求直接。需要 v2.1.227 或更高版本；请参阅下面的[通过转发代理的 IdP 请求](#idp-requests-through-a-forward-proxy)。 |
| `form_action_origins` | 否 | `/device` 页面的 `Content-Security-Policy: form-action` 指令的其他源。网关已允许 `'self'` 和发现的 `authorization_endpoint` 源，但 Chrome 对整个重定向链强制执行 `form-action`。如果你的 IdP 通过第二个主机重定向，如 Azure AD 联合到 ADFS、中心辐射 Okta 或公司 SSO 拦截器，列出授权请求可能重定向通过的每个源。 |
| `ca_cert_pem` | 否 | PEM 编码的 CA 证书本身，而不是文件的路径。它替换 IdP 请求的系统信任存储。要加载挂载的文件，写 `${file:/etc/gateway/idp-ca.pem}`。用于公司 PKI 后面的 Keycloak 或 Dex。 |

<h4 id="idp-requests-through-a-forward-proxy">
  通过转发代理的 IdP 请求
</h4>

推理上游在每个版本上都尊重 `HTTPS_PROXY` 和 `HTTP_PROXY`。网关自己对 IdP、发现、JWKS、令牌和 userinfo 的请求直接进行，除非你设置 `oidc.use_proxy: true`，这需要 v2.1.227 或更高版本。当代理变量被设置、`use_proxy` 未设置且发行者不被 `NO_PROXY` 覆盖时，网关保持这些请求直接并在启动时记录通知，要求你选择；`use_proxy: false` 保持它们直接并沉默通知。

使用 `use_proxy: true`，pod 自己解析每个 IdP 端点的主机名，并要求代理 `CONNECT` 到解析的 IP 地址，因此代理必须接受 `CONNECT` 到发现文档命名的每个主机的 IP 地址，而不仅仅是发行者。使用 `http://` 代理 URL。`ca_cert_pem` 和[SSRF 防护](/docs/zh-CN/claude-apps-gateway-deploy#threat-model-summary)也适用于代理路径。

[仅代理出口](#proxy-only-egress)改变这两者：当它活跃时，IdP 请求遵循代理，除非你设置 `use_proxy: false`，网关将每个 IdP 主机名交给代理，而不首先解析它。

<h4 id="proxy-only-egress">
  仅代理出口
</h4>

在网关的环境中设置 `CLAUDE_GATEWAY_PROXY_IS_EGRESS_BOUNDARY=1`，在 `HTTPS_PROXY` 旁边，当 pod 仅通过该转发代理到达其他主机且无法自己解析公共 DNS 名称时，或当代理拒绝 `CONNECT` 到 IP 地址时。需要 v2.1.277 或更高版本。它是一个环境变量而不是 `gateway.yaml` 键，因此配置文件中的任何内容都无法放松网关的地址检查。

```bash theme={null}
export HTTPS_PROXY=http://proxy.corp.example.com:3128
export NO_PROXY=
export no_proxy=
export CLAUDE_GATEWAY_PROXY_IS_EGRESS_BOUNDARY=1
```

当仅代理出口活跃时，网关在启动时记录一条 `network:` 行。

下面的每一行是具有 `HTTPS_PROXY` 设置的网关上的一类出站请求，默认情况下和仅代理出口活跃时。

| 出站请求 | 默认 | 仅代理出口活跃 |
| - | - | - |
| `provider: anthropic` 上游、工作负载身份联合令牌交换、`telemetry.forward_to` 导出 | 在本地解析和检查，然后通过代理 `CONNECT` 到检查的 IP 地址。`NO_PROXY` 中列出的遥测收集器改为直接到达 | 主机名交给代理 |
| IdP 发现、JWKS、令牌和 userinfo | 直接，除非 [`oidc.use_proxy: true`](#idp-requests-through-a-forward-proxy)，然后 `CONNECT` 到检查的 IP 地址 | 主机名交给代理，除非 `oidc.use_proxy: false` 保持内部 IdP 直接 |
| Amazon Bedrock、Claude Platform on AWS、Google Cloud 的 Agent Platform 和 Microsoft Foundry 上游；Google 组查找 | 主机名交给代理 | 不变 |

仅代理出口保持关闭，除非网关的环境满足所有这三个条件：

* `HTTPS_PROXY` 或 `HTTP_PROXY` 被设置。
* `NO_PROXY` 和 `no_proxy` 为空。如果你的平台将任一个注入到 pod 中，在网关容器上将两者设置为空值。在 `NO_PROXY` 中列出遥测收集器保持仅代理出口关闭。
* `CLAUDE_GATEWAY_ALLOW_LOOPBACK` 未打开。pod 自己环回上的收集器或 IdP 无法与仅代理出口结合，因为交给代理的环回地址将是代理主机自己的，因此给这些服务一个代理可以到达的地址。出于同样的原因，当仅代理出口活跃时，网关完全拒绝 `localhost` 风格的名称。

当这些条件之一未满足时，网关在启动时记录警告，命名停止它的变量，并保持默认行为。

一旦仅代理出口活跃，允许代理中的每个目的地，包括内部收集器和任何由 IP 地址配置的主机。你仍然可以使用 [`oidc.use_proxy: false`](#idp-requests-through-a-forward-proxy) 保持内部 IdP 直接。

<Warning>
  仅在代理的允许列表至少与网关自己的检查一样严格时打开这个。代理必须拒绝云元数据端点，如 `169.254.169.254` 和 `metadata.google.internal`、链路本地地址和代理主机自己的环回，并且它必须按名称解析到的地址拒绝它们，而不仅仅是按名称，因为网关不再捕获解析到其中之一的主机名。连接到任何被要求的地方的代理移除网关的[SSRF 防护](/docs/zh-CN/claude-apps-gateway-deploy#threat-model-summary)用于这些请求。
</Warning>

<h3 id="session">
  `session`
</h3>

`session` 块塑造网关在登录后铸造的持有者令牌：签署它们的密钥和它们的生命周期。

| 字段 | 必需 | 描述 |
| - | - | - |
| `jwt_secret` | 是 | 至少 32 字节的熵，例如来自 `openssl rand -base64 32`。签署网关的 HS256 持有者令牌。接受单个字符串或用于轮换的数组：索引 0 签署，所有条目验证。要轮换，前置新密钥，等待 `ttl_hours`，然后删除旧密钥。 |
| `ttl_hours` | 否 | 网关持有者令牌生命周期。默认 `1`。当 IdP 发出刷新令牌时，CLI 在过期前静默刷新。较短的生命周期更快地取消配置；较长的生命周期减少 IdP 往返。如果你的 IdP 因为 `offline_access` 不可用而无法发出刷新令牌，则没有静默刷新，因此提高到 `8` 或 `12` 以避免每小时将开发者发送回浏览器登录。 |

<h3 id="store">
  `store`
</h3>

`store` 块指向网关的 PostgreSQL 数据库，该数据库保存设备授权和速率限制计数器。

| 字段 | 必需 | 描述 |
| - | - | - |
| `postgres_url` | 是 | `postgres://` 或 `postgresql://` URL。必需：设备授权会合点，浏览器回调写入，轮询 CLI 读取，需要跨副本状态。网关在启动时运行自己的模式迁移，因此角色需要在目标模式上具有创建和修改表的权限。请参阅[升级](/docs/zh-CN/claude-apps-gateway-deploy#upgrades)和 [Postgres](/docs/zh-CN/claude-apps-gateway-deploy#postgres)。 |
| `username` | 否 | 覆盖 `postgres_url` 中的用户 |
| `password` | 否 | 数据库凭证。在此处设置而不是在 `postgres_url` 中，以便凭证保持在 URL 之外。接受任何字符并优先于 URL 凭证。 |
| `max_connections` | 否 | 每个副本的 Postgres 连接池大小。默认 `5`，这是保守的，对共享数据库友好。启用[支出限制](#admin)后，热路径在每个推理请求中执行几个操作，因此在负载下为专用数据库提高它，并保持副本 × 这个值低于数据库的 `max_connections`。 |
| `connect_timeout_seconds` | 否 | 网关打开 Postgres 连接时等待的秒数。从 `1` 到 `60` 的整数，默认 `5`。如果当新网关实例启动时连接尝试超时，请提高它。需要网关服务器上的 Claude Code v2.1.274 或更高版本。早期版本在设置该键时拒绝启动。 |
| `readiness_grace_seconds` | 否 | 在 Postgres 停止应答后 `/readyz` 继续报告就绪的秒数。从 `0` 到 `3600` 的整数，默认 `0`。请参阅[中断行为](/docs/zh-CN/claude-apps-gateway-deploy#outage-behavior)以了解如何选择值。需要网关服务器上的 Claude Code v2.1.282 或更高版本。早期版本在设置该键时拒绝启动。 |

对于本地开发，将 `postgres_url` 指向一个一次性 Postgres 容器，例如 `docker run --rm -p 5432:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres`。

<h3 id="upstreams">
  `upstreams`
</h3>

`upstreams` 是一个有序列表。网关将推理转发到解析请求的模型的第一个上游。

在 `5xx`、`429`、`401`、`403`、`404` 或超时时，网关故障转移到下一个上游；其他 `4xx` 不会，因为这些错误归因于请求而不是上游。`401` 或 `403` 意味着网关自己的凭证对该上游失败。`404` 意味着该上游不服务请求的模型，因此列表中的后续上游仍然可以。

如果你在上游上设置 `forward_user_identity: true`，它返回给携带开发者电子邮件的请求的 `429` 不会故障转移。请参阅[如何每用户限制拒绝到达开发者](#per-user-identity-headers-for-a-proxy-you-run)。

在 `404` 上故障转移需要网关 v2.1.198 或更高版本。早期版本即使列表中的后续上游服务该模型，也会将第一个 `404` 返回给客户端。

同一提供者的多个上游必须设置不同的 `name:`。

Amazon Bedrock、Claude Platform on AWS、Google Cloud 的 Agent Platform 和 Microsoft Foundry 客户端在启动时构建一次，它们的 SDK 在内部刷新凭证，因此轮换云凭证不需要重启。静态 Anthropic API 密钥和持有者在启动时读取；请参阅 [Anthropic API](#anthropic-api)。

<h4 id="upstream-error-messages">
  上游错误消息
</h4>

网关返回一个上游的错误响应或其自己的 `502`，取决于上游如何应答：

* **上游返回了网关不[故障转移](#multiple-upstreams)的状态**：该上游的响应。网关不尝试进一步的上游。
* **网关尝试的每个上游都以网关[故障转移](#multiple-upstreams)的方式失败**：最后的 `429`。当没有返回 `429` 时，网关按顺序优先选择最后的 `401` 或 `403`、最后的 `404` 和最后的 `501`。当没有返回任何这些时，网关自己的 `502`，`all upstreams failed (N attempted)`，其中 N 计数 [`upstreams`](#upstreams) 中的每个条目，包括网关跳过的条目，因为它们不服务请求的模型。

当网关返回上游的响应时，它保持上游的状态代码。它是否保持上游的消息取决于提供者。Anthropic API 上游的错误正文到达开发者不变。

Amazon Bedrock、Claude Platform on AWS、Google Cloud 的 Agent Platform 和 Microsoft Foundry 上游可以在其错误文本中命名你的帐户 ID、角色 ARN 和项目 ID。网关在[操作日志](/docs/zh-CN/claude-apps-gateway-deploy#logs)中记录该完整文本。开发者从这些上游看到的取决于拒绝：

* Anthropic 标准错误信封中的 `400` 或 `413`：上游自己的消息，如 `prompt is too long`。Claude Platform on AWS、Agent Platform 和 Microsoft Foundry 为模型 API 拒绝返回此信封。
* 提供者自己形状中的 `400` 或 `413`：`capability_rejected:` 令牌。当网关无法分类拒绝时，`upstream rejected the request` 在 `400` 或 `request too large for this upstream` 在 `413`。
* 任何其他状态：通用的每状态副本，如 `upstream rate limit exceeded` 在 `429`。

例如，网关将 Amazon Bedrock 的 `Input is too long for requested model.` 替换为 `capability_rejected: prompt_too_long`。Claude Code [自动压缩](/docs/zh-CN/errors#prompt-is-too-long)该令牌，就像它对 `prompt is too long` 所做的那样。

保持云上游的 `400` 或 `413` 消息或将其替换为 `capability_rejected:` 令牌需要网关 v2.1.233 或更高版本。

<h4 id="anthropic-api">
  Anthropic API
</h4>

最小的 Anthropic 上游是来自 [Claude 控制台](https://platform.claude.com) 的 API 密钥：

```yaml theme={null}
upstreams:
  - provider: anthropic
    auth:
      api_key: ${ANTHROPIC_API_KEY}
    # 或 OAuth 持有者（例如工作负载身份联合交换的令牌）：
    #   oauth_token: ${file:/var/run/secrets/anthropic-oauth-token}
    # base_url: https://api.anthropic.com   # 默认；为转发代理覆盖
```

两种凭证形式在它们发送的头中有所不同：

* **`api_key`**：发送 `x-api-key`。在 Claude 控制台中轮换它并更新环境变量。
* **`oauth_token`**：发送 `Authorization: Bearer`。当你的组织发出短期令牌而不是长期 API 密钥时使用持有者形式。持有者在启动时读取一次，因此通过重新挂载密钥和重启来刷新。

代替静态密钥或持有者，你可以使用工作负载身份联合。按照[工作负载身份联合指南](https://platform.claude.com/docs/en/manage-claude/workload-identity-federation)创建联合规则，然后将你的工作负载的 OIDC JWT 挂载为文件，如 Kubernetes 投影服务帐户令牌或 CI 平台的 id-token。网关将 JWT 交换为短期持有者并自动刷新它。令牌文件在每次交换时重新读取，因此轮换的投影令牌被拾取而无需重启。

```yaml theme={null}
upstreams:
  - provider: anthropic
    auth:
      federation_rule_id: ${ANTHROPIC_FEDERATION_RULE_ID}
      organization_id: ${ANTHROPIC_ORGANIZATION_ID}
      identity_token_file: /var/run/secrets/anthropic/id-token
      # workspace_id: wrkspc_...       # 如果规则覆盖 >1 个工作区，则必需
      # service_account_id: svac_...   # 可选的预期目标检查
```

<a id="per-user-identity-headers-for-a-proxy-you-run" />

<h5 id="per-user-identity-headers-for-a-proxy-you-run">
  为你运行的代理的每用户身份头
</h5>

你可以将 `provider: anthropic` 上游的 `base_url` 指向你运行的代理，而不是 Anthropic API。要告诉该代理哪个开发者发送了每个请求，在该上游上设置 `forward_user_identity: true`。代理然后可以按开发者属性支出。需要运行 Claude Code v2.1.233 或更高版本的网关。

例如，对于 `upstream-gateway.internal.example.com` 上的代理：

```yaml theme={null}
upstreams:
  - provider: anthropic
    base_url: https://upstream-gateway.internal.example.com
    auth:
      api_key: ${PROXY_KEY}
    forward_user_identity: true        # 默认 false
```

网关将这些头添加到它转发到该上游的每个请求。

| 头 | 值 |
| - | - |
| `x-litellm-end-user-id` | 开发者的电子邮件，当 IdP 提供时。 |
| `x-claude-gateway-user-id` | 开发者的 IdP 主体，来自令牌的 `sub` 声明。 |
| `x-claude-gateway-user-email` | 开发者的电子邮件，当 IdP 提供时。 |

当 IdP 令牌不携带电子邮件时，网关仅发送 `x-claude-gateway-user-id` 并省略两个电子邮件头。如果你的 IdP 将电子邮件放在不同的声明中，将 [`oidc.email_claim`](#oidc) 设置为该声明。

当你的代理答复 `429` 给携带开发者电子邮件的请求时，网关将该响应按原样返回给开发者，而不是故障转移到下一个上游，因此你的代理的每用户预算或速率限制保持。代理的其他响应遵循普通[故障转移规则](#upstreams)。如果开发者的 IdP 令牌不携带电子邮件，网关转发他们的请求而不带电子邮件头，因此对其中一个请求的 `429` 计为上游容量并故障转移。在网关服务器上的 v2.1.267 之前，每个 `429` 都故障转移。

仅在 `base_url` 是你操作的代理的上游上设置 `forward_user_identity`。网关将开发者电子邮件发送到该 `base_url` 命名的任何服务器。如果 `base_url` 是 Anthropic API（这是默认值），网关拒绝启动。

<h4 id="amazon-bedrock">
  Amazon Bedrock
</h4>

对于网关替换或前置的客户端 Bedrock 部署，请参阅 [Amazon Bedrock 上的 Claude Code](/docs/zh-CN/amazon-bedrock)。网关端上游：

```yaml theme={null}
upstreams:
  - provider: bedrock
    region: us-east-1
    auth: {}                           # 首选：AWS 默认凭证链
    # 或显式凭证：
    # auth:
    #   aws_access_key_id: ${AWS_AKID}
    #   aws_secret_access_key: ${AWS_SK}
    #   aws_session_token: ${AWS_ST}
    # 或 Bedrock API 持有者令牌：
    # auth:
    #   aws_bearer_token: ${AWS_BEARER_TOKEN}
    # 为 FIPS 或 VPC 端点部署覆盖 bedrock-runtime 端点：
    # base_url: https://bedrock-runtime-fips.us-east-1.amazonaws.com
```

空的 `auth` 块使用 AWS SDK 的默认凭证链：环境变量、`~/.aws/credentials`、ECS 任务角色、EC2 实例元数据或 EKS 上的 IRSA。在生产中，给网关 pod 一个 IAM 角色，而不是在容器镜像中嵌入静态密钥。

显式凭证必须完整：当 `aws_access_key_id` 和 `aws_secret_access_key` 未一起设置时，或当 `aws_session_token` 在没有它们的情况下设置时，网关在启动时失败。在 v2.1.207 之前，部分 `auth:` 块通过验证。

| 设置 | 如何 |
| - | - |
| IAM 权限 | 授予网关的主体 `bedrock:InvokeModel` 和 `bedrock:InvokeModelWithResponseStream` 在推理配置文件 ARN 和底层基础模型 ARN 上。对于美国地区的内置目录：`arn:aws:bedrock:<region>:<account>:inference-profile/us.anthropic.*` 和 `arn:aws:bedrock:*::foundation-model/anthropic.*`。也授予基础模型 ARN 上的 `bedrock:CountTokens`。网关使用它（无需付费）来计数客户端放弃的请求的输入令牌，因此[支出限制](#admin)保持准确。没有它，网关回退到该计数的一令牌 Bedrock 请求。 |
| 模型访问 | Amazon Bedrock 在商业地区默认启用模型访问。剩余的帐户级门是 Anthropic 的一次性用例表单：如果你的 AWS 帐户中没有人提交过，打开 Amazon Bedrock 控制台，从模型目录中选择 Anthropic 模型，并完成表单。有关 AWS Organizations 表单和提交者需要的权限，请参阅[提交用例详情](/docs/zh-CN/amazon-bedrock#1-submit-use-case-details)。 |
| EKS (IRSA) | 创建一个具有上述策略的 IAM 角色和针对你的集群的 OIDC 提供者的信任策略，范围限定为网关的服务帐户。使用 `eks.amazonaws.com/role-arn: arn:aws:iam::<acct>:role/claude-gateway` 注释服务帐户。`auth: {}` 拾取它。 |
| ECS / EC2 | 将 IAM 角色附加到任务定义或实例配置文件。`auth: {}` 拾取它。 |
| 其他任何地方 | 通过 `AWS_ACCESS_KEY_ID`、`AWS_SECRET_ACCESS_KEY` 和 `AWS_SESSION_TOKEN` 环境变量传递凭证，或在 `auth:` 中使用 `${VAR}` 扩展显式设置它们 |
| 地区 | `region:` 是 API 端点地区。跨地区推理配置文件跨地理位置（美国、欧盟、亚太）路由，无论你选择哪一个。对于非美国地区或预配吞吐量 ARN，添加一个[`models:`](#models)块，其中包含正确的每上游 ID。 |

<h4 id="claude-platform-on-aws">
  Claude Platform on AWS
</h4>

Claude Platform on AWS 在 `aws-external-anthropic.<region>.api.aws` 上的 AWS 基础设施上服务第一方 Anthropic API。它使用第一方模型 ID，按发送方式尊重 `anthropic-beta` 头，并服务 `count_tokens`，因此 Bedrock 特定的翻译都不适用。`anthropicAws` 提供者需要 Claude Code v2.1.198 或更高版本；早期网关版本在启动时拒绝它。

对于同一平台的客户端部署，请参阅 [Claude Platform on AWS 上的 Claude Code](/docs/zh-CN/claude-platform-on-aws)。网关端上游：

```yaml theme={null}
upstreams:
  - provider: anthropicAws
    region: us-east-1
    workspace_id: wrkspc_...
    auth:
      api_key: ${ANTHROPIC_AWS_API_KEY}   # 作为 x-api-key 发送
    # 或通过 AWS 默认凭证链的 SigV4：
    # auth: {}
    # 或显式 SigV4 凭证：
    # auth:
    #   aws_access_key_id: ${AWS_ACCESS_KEY_ID}
    #   aws_secret_access_key: ${AWS_SECRET_ACCESS_KEY}
    # 覆盖派生的端点：
    # base_url: https://aws-external-anthropic.us-east-1.api.aws
```

该平台在与 Amazon Bedrock 不同的 AWS 账户中运行，并为其自己的服务名称 `aws-external-anthropic` 签署 SigV4 请求，因此 Bedrock 范围的 IAM 角色不授权它。`auth.api_key` 中的 API 密钥在同时设置 SigV4 凭证时优先。空的 `auth` 块使用 AWS SDK 的默认凭证链，与 [Amazon Bedrock](#amazon-bedrock) 上游使用的链相同。

| 字段 | 必需 | 描述 |
| - | - | - |
| `region` | 是 | AWS 地区，小写字母、数字和连字符。网关从它派生端点为 `https://aws-external-anthropic.<region>.api.aws`。 |
| `workspace_id` | 是 | 在每个请求上作为头发送；平台需要它 |
| `auth.api_key` | 否 | 平台的 API 密钥，作为 `x-api-key` 发送。不是持有者令牌：两种身份验证模式是 API 密钥或 SigV4。 |
| `auth.aws_access_key_id` / `auth.aws_secret_access_key` | 否 | 显式 SigV4 凭证。设置其中一个而不设置另一个在启动时失败。`auth.aws_session_token` 与它们一起被接受。 |
| `base_url` | 否 | 覆盖派生的端点 |

因为平台解析第一方模型 ID，内置目录路由到它，无需 [`models:`](#models) 块。当你策划 `models:` 列表时，使用第一方 ID 键入 `anthropicAws:` 条目。

<h4 id="google-cloud-agent-platform">
  Google Cloud Agent Platform
</h4>

对于等效的客户端设置，请参阅 [Google Cloud 上的 Claude Code](/docs/zh-CN/google-vertex-ai)。网关端上游：

```yaml theme={null}
upstreams:
  - provider: vertex
    region: us-east5
    project_id: example-prod
    auth: {}                           # 首选：应用默认凭证
    # 或服务帐户密钥文件：
    # auth: { service_account_json: /secrets/sa.json }
    # 为私有服务连接覆盖 aiplatform 端点：
    # base_url: https://us-east5-aiplatform.p.googleapis.com
```

空的 `auth` 块使用应用默认凭证：`GOOGLE_APPLICATION_CREDENTIALS`、GCE 元数据或 GKE 工作负载身份。支持服务帐户 JSON 密钥文件但不推荐；使用工作负载身份或将服务帐户附加到 GCE 或 Cloud Run 实例。

设置 `region: global` 以使用 [Agent Platform 的全局端点](https://cloud.google.com/vertex-ai/generative-ai/docs/learn/locations)而不是区域端点。Google 然后将每个请求路由到可用地区，因此你不跟踪每地区模型可用性。设置特定地区会将每个请求固定到它。

| 设置 | 如何 |
| - | - |
| IAM 权限 | 授予网关的服务帐户项目上的 `roles/aiplatform.user`，或具有 `aiplatform.endpoints.predict` 的自定义角色。启用 Agent Platform API (`aiplatform.googleapis.com`)。 |
| 模型访问 | 在 Model Garden 中，为你的项目启用 Claude 模型。它们发布到特定地区；检查模型卡以了解支持的地区。 |
| GKE (工作负载身份) | 将 GCP 服务帐户绑定到网关的 Kubernetes 服务帐户，并使用 `iam.gke.io/gcp-service-account: claude-gateway@<proj>.iam.gserviceaccount.com` 注释 KSA。`auth: {}` 拾取它。 |
| Cloud Run / GCE | 将服务的服务帐户设置为具有 `roles/aiplatform.user` 的服务帐户。`auth: {}` 拾取它。 |
| 其他任何地方 | `auth: { service_account_json: /secrets/sa.json }`，JSON 密钥文件的路径，挂载为密钥。该字段采用文件路径，而不是密钥内容，因此不涉及 `${file:…}` 扩展。 |

<h4 id="microsoft-foundry">
  Microsoft Foundry
</h4>

对于客户端 Foundry 部署，请参阅 [Microsoft Foundry 上的 Claude Code](/docs/zh-CN/microsoft-foundry)。网关端上游：

```yaml theme={null}
upstreams:
  - provider: foundry
    resource: example-foundry              # https://example-foundry.services.ai.azure.com
    auth: { use_azure_ad: true }        # 首选：DefaultAzureCredential / 托管身份
    # 或 API 密钥：
    # auth:
    #   api_key: ${FOUNDRY_API_KEY}
```

`use_azure_ad: true` 通过 `DefaultAzureCredential` 解析：AKS、ACI 或 App Service 上的托管身份；Azure CLI；或环境凭证。API 密钥有效但是项目范围的，不会自动轮换。Foundry 的端点从 `resource:` 派生；设置可选的 `base_url` 以为主权云（如 Azure Government）覆盖它。

| 设置 | 如何 |
| - | - |
| RBAC | 授予网关的身份 Foundry 资源上的 `Azure AI User` 或 `Cognitive Services User` |
| 部署 | Foundry 使用管理员选择的部署名称，而不是规范模型 ID。添加一个[`models:`](#models)块，将每个规范 ID 映射到你的部署名称。 |
| AKS (工作负载身份) | 将用户分配的托管身份与集群的 OIDC 发行者联合，并将其绑定到网关的服务帐户。`use_azure_ad: true` 通过 `WorkloadIdentityCredential` 拾取它。 |
| ACI / App Service | 在资源上启用系统分配或用户分配的托管身份。`use_azure_ad: true` 拾取它。 |
| 其他任何地方 | `auth: { api_key: "${FOUNDRY_API_KEY}" }`。在 `{ }` 内引用 `${…}`。 |

<h4 id="static-headers-on-upstream-requests">
  上游请求上的静态头
</h4>

要将固定头添加到网关发送到一个上游的请求，在该上游上设置 `headers:`。当你运行的代理通过头路由或属性流量时使用它。

`headers:` 需要网关服务器上的 Claude Code v2.1.277 或更高版本。早期网关在找到该键时拒绝启动。在添加该键之前升级每个副本，并在回滚到早期版本之前删除该键。

头转到 `base_url` 命名的服务器，或当 `base_url` 未设置时转到提供者自己的端点。提供者也接收它们，除非你的代理删除它们。

此示例通过 `upstream-proxy.internal.example.com` 上的代理到达 `provider: vertex` 上游。它设置代理读取的 `x-source` 头，并从 `PROXY_TOKEN` 环境变量发送令牌作为 `x-proxy-token`：

```yaml theme={null}
upstreams:
  - provider: vertex
    region: us-east5
    project_id: example-prod
    base_url: https://upstream-proxy.internal.example.com
    auth: {}
    headers:
      x-source: claude-apps-gateway
      x-proxy-token: ${PROXY_TOKEN}
```

值是可打印的 ASCII 文本，两端没有空格。引用数字、`true` 或 `false`，以便 YAML 将其读取为文本。

要将密钥保持在配置文件之外，使用[密钥扩展](#secret-expansion)从环境变量使用 `${VAR}` 或从文件使用 `${file:/path}` 加载值。解析为空值的 `${VAR}` 停止网关启动。

`headers:` 适用于每个提供者，每个上游仅发送自己的。

并非网关发送到上游的每个请求都携带它们：

| 网关发送到此上游的请求 | 携带 `headers:` |
| - | - |
| `/v1/messages`、流式或非流式，和 `/v1/messages/count_tokens` | 是 |
| 从另一个上游故障转移的请求 | 是，仅此上游的 `headers:` |
| 客户端放弃的请求的 Amazon Bedrock 的 `CountTokens` 调用 | 否 |
| 工作负载身份联合令牌交换 | 否 |

在使用 AWS SigV4 签署请求的 Amazon Bedrock 或 Claude Platform on AWS 上游上，这些头是签名的一部分，因此你的代理必须原样传递它们。

如果你使用网关保留的名称，它拒绝启动，启动错误命名该头。保留名称包括：

* `authorization` 和 `x-api-key`
* `host`、`content-type` 和 `user-agent`
* 任何以 `anthropic-`、`x-goog-`、`x-amz-` 或 `x-amzn-` 开头的名称

<h4 id="multiple-upstreams">
  多个上游
</h4>

同一提供者可以出现多次，具有不同的 `name:`。这涵盖不同的地区、通过不同凭证链的不同帐户、预配吞吐量与按需以及跨提供者故障转移。

网关按顺序尝试上游。`5xx`、`429`、`401`、`403`、`404`、超时和缺失端点（`501`）故障转移；其他 `4xx` 不会。

`429` 是每上游容量，因此预配吞吐量 (PT) 耗尽故障转移到按需。如果你在上游上设置 [`forward_user_identity: true`](#per-user-identity-headers-for-a-proxy-you-run)，对携带开发者电子邮件的请求的 `429` 是每用户拒绝而不是故障转移。

每个请求从第一个上游开始。请求仅在每个前面的上游都失败或不服务请求的模型时才到达后续上游。

网关不保留失败上游的记录，因此当上游关闭时，到达它的每个请求仍然尝试它并等待它失败后再继续。

对于 Anthropic API 上游，[`timeouts.upstream_ttfb_ms`](#http-tuning)限制在关闭上游上的等待。该设置不适用于其他提供者，网关在那里等待最多一小时以便上游开始响应。

`404` 是每上游模型可用性，因此未启用模型的上游不会阻止服务它的后续上游。无法解析请求的模型的上游被跳过，无需网络往返。

此示例首先路由预配吞吐量 Bedrock 分配，溢出到按需和第二个帐户，最后回退到 Anthropic API：

```yaml theme={null}
upstreams:
  # 主要：你的主地区的预配吞吐量。
  - name: bedrock-pt
    provider: bedrock
    region: us-east-1
    auth: {}
  # 溢出：按需跨地区。
  - name: bedrock-od
    provider: bedrock
    region: us-west-2
    auth: {}
  # 不同帐户：通过假定角色凭证的单独 Bedrock 分配。
  - name: bedrock-acct2
    provider: bedrock
    region: us-east-1
    auth:
      aws_access_key_id: ${ACCT2_AKID}
      aws_secret_access_key: ${ACCT2_SK}
  # 最后的手段：直接 Anthropic API。
  - name: anthropic-fallback
    provider: anthropic
    auth:
      api_key: ${ANTHROPIC_API_KEY}

# 每上游模型 ID 由上游的 `name:` 键入。
models:
  - id: claude-opus-4-8
    label: Claude Opus 4.8
    upstream_model:
      bedrock-pt: arn:aws:bedrock:us-east-1:111111111111:provisioned-model/abcdef
      bedrock-od: us.anthropic.claude-opus-4-8
      bedrock-acct2: us.anthropic.claude-opus-4-8
      anthropic-fallback: claude-opus-4-8
```

| 杠杆 | 如何 |
| - | - |
| 不同地区 | 每个地区一个 Bedrock 上游，每个都有自己的 `region:`。使用 [`auto_include_builtin_models: true`](#models)，跨地区推理配置文件自动路由；对于地区固定部署，使用 `models:` 块。 |
| 不同帐户 | 每个帐户一个 Bedrock 上游，每个在 `auth:` 中都有自己的凭证。默认链 (`auth: {}`) 使用 pod 的身份；对于第二个帐户，设置显式凭证或持有者令牌。 |
| 预配吞吐量 | 在该上游名称的 `models:` 中将模型映射到预配吞吐量 ARN。其他上游保持按需 ID，因此 PT 容量在故障转移前耗尽。 |
| VPC / FIPS 端点 | 在上游上设置 `base_url:` 到你的 VPC 端点或 FIPS 端点 URL |
| 模型范围路由 | 仅自定义模型 `id`（不是内置 Claude 模型的模型）从其 `upstream_model:` 映射中省略的上游被跳过。网关按顺序尝试每个上游上的内置模型，并在映射没有条目时使用提供者的默认 ID，因此对于内置模型，映射改变上游接收哪个 ID 而不是它是否被尝试；拒绝 ID 的上游遵循与任何其他上游错误相同的[故障转移规则](#upstreams)。 |

在云提供者之间或直接 Anthropic API 之间故障转移会改变哪个协议、地理位置和其他条款管理请求。

CLI 对网关应用相同的功能门控，无论哪个上游服务给定请求，因此故障转移不会发送上游会拒绝的正文字段。

<h2 id="optional-sections">
  可选部分
</h2>

<h3 id="admin">
  `admin`
</h3>

可选。启用 `/v1/organizations/spend_limits`，它镜像 Anthropic 的公共 Admin API，以及在 `/v1/messages` 上的每个开发者支出强制执行。请参阅[支出限制](/docs/zh-CN/claude-apps-gateway-spend-limits)了解如何设置和强制执行上限；本部分涵盖启用该功能并调整它的 `gateway.yaml` 键。

```yaml theme={null}
admin:
  # 用于管理员端点的命名静态 API 密钥，作为 x-api-key 发送。
  # id 在审计日志中显示为 admin-key:<id>，因此每个密钥都是
  # 可追踪的。数组用于轮换：添加新密钥，滚动客户端，
  # 删除旧密钥。
  write_keys:
    - { id: terraform, key: "${GATEWAY_ADMIN_WRITE_KEY_TF}" }
    - { id: ci,        key: "${GATEWAY_ADMIN_WRITE_KEY_CI}" }
  read_keys:
    - { id: reporting, key: "${GATEWAY_ADMIN_READ_KEY}" }
  # 通过普通网关 JWT（无 API 密钥）授予完全管理员权限的 IdP 组。
  admin_groups: [platform-finops]
  blocked_message: request an increase at https://go.example.com/claude-limits
```

| 字段 | 必需 | 描述 |
| - | - | - |
| `write_keys` | 否 | `{id, key}` 数组。与其中一个匹配的 `x-api-key` 可以列出、设置和删除支出限制。密钥值必须至少 32 个字符；`id` 必须在 `read_keys` 和 `write_keys` 中唯一。 |
| `read_keys` | 否 | `{id, key}` 数组。只读：每个 `GET` 端点，包括列出上限、按 ID 获取一个，以及读取 [`/effective`](/docs/zh-CN/claude-apps-gateway-spend-limits#%2Feffective) 和 [`/audit`](/docs/zh-CN/claude-apps-gateway-spend-limits#%2Faudit)。 |
| `admin_groups` | 否 | IdP 组名称。网关 JWT 的 `groups` 声明包含其中一个的具有完全管理员访问权限（读和写），并审计为 `oidc:<sub>`。将此用于人类管理员；为机器使用 API 密钥。此列表中的空条目会在启动时停止网关。请参阅[在启动时停止网关的匹配器值](#matcher-values-that-stop-the-gateway-at-boot)。 |
| `blocked_message` | 否 | 逐字附加到被阻止的开发者看到的 `429 billing_error`。编写完整的说明，例如 URL 或 Slack 频道。未设置时，网关仅发送默认消息。请参阅[强制执行如何工作](/docs/zh-CN/claude-apps-gateway-spend-limits#how-enforcement-works)。 |
| `audit_retention_days` | 否 | 默认 `365`。较旧的 `admin_audit` 行被清除。 |
| `spend_retention_months` | 否 | 默认 `13`。早于此的 `spend` 计数器行被清除。默认值保留整整一年加当前部分月份，用于年度对比报告。 |
| `identity_retention_days` | 否 | 默认 `90`。`principal_emails` 行的最后一次看到 TTL，其中包含每个开发者的电子邮件、显示名称和组（PII）。故意比支出保留期短，以便已取消配置的身份在其匿名支出计数器保留时过期。 |
| `group_limit_mode` | 否 | `min`（默认）或 `max`。当开发者在多个具有上限的组中时，`min` 强制执行最严格的，`max` 强制执行最宽松的。由强制执行和 `/effective` 使用。 |

<h3 id="enforcement">
  `enforcement`
</h3>

`enforcement` 块控制当存储不可用时支出限制检查的行为。

| 字段 | 必需 | 描述 |
| - | - | - |
| `fail_closed_on_error` | 否 | 默认 `false`。支出强制执行在 Postgres 中断时失败开放，因此推理保持运行。设置 `true` 以失败关闭：超出上限的开发者被阻止，但如果存储无法访问，所有人都被阻止。需要 [`admin:`](#admin) 块：支出强制执行仅在配置 `admin` 时运行，如果在没有 `admin` 块的情况下设置此 `true`，网关拒绝启动。 |

<h3 id="pricing">
  `pricing`
</h3>

`pricing` 块告诉支出计量器收费而不是美元列表价格，因此上限和 [`/effective`](/docs/zh-CN/claude-apps-gateway-spend-limits#%2Feffective) 反映您的合同费率。金额保持为美元，并保持为估计值，而不是发票。两个先决条件：

* 网关服务器上的 Claude Code v2.1.227 或更高版本。早期版本在启动时拒绝未知密钥。
* [`admin:`](#admin) 块或在 v2.1.268 或更高版本中，至少有一个策略的 [`managed:`](#managed) 块。网关拒绝在设置 `pricing` 且没有任何块的情况下启动，因为没有任何东西会读取它。

```yaml theme={null}
pricing:
  multiplier: 0.85
  overrides:
    - upstream: bedrock-eu
      model: claude-sonnet-4-6
      input: 3.30
      output: 16.50
      cache_read: 0.33
      cache_write: 4.125
```

| 字段 | 必需 | 描述 |
| - | - | - |
| `multiplier` | 否 | 默认 `1`。计量器将每个计量金额乘以此值，无论是列表价格还是覆盖，因此 `0.85` 按价格的 85% 计费。必须大于 0 且最多 10，值大于 1 是[标记价格上升](#mark-prices-up)。 |
| `overrides` | 否 | `{upstream, model, input, output, cache_read, cache_write}` 行，单位为美元/百万令牌。所有四个费率都是必需的。每个必须大于 0 且最多 10000。 |

计量器如何匹配覆盖行：

* 一行替换列表价格，用于 `upstream`（一个 [`upstreams[].name`](#upstreams)）为 `model` 提供的请求。这包括更高的[快速模式](/docs/zh-CN/fast-mode#understand-the-cost-tradeoff)费率，因此快速和标准请求以相同的四个费率计量。
* 内置 ID（如 `claude-sonnet-4-6`）匹配 [`models[].id`](#models)，涵盖计量器定价为该模型的每个日期形式、区域 Amazon Bedrock 形式或 Google Cloud 的 Agent Platform 形式。任何其他字符串（如别名或推理配置文件 ARN）匹配客户端发送的 ID 或上游发送的字符串，不区分大小写。
* 当行重叠时，计量器选择最具体的行而不是第一行：一行其 `model` 是上游发送的确切模型字符串，然后是匹配客户端发送的确切 ID 的行，然后是命名内置模型的行。
* 未知的上游名称会导致启动失败，两行用于一个上游命名相同的模型也会导致启动失败，包括一个内置模型的两个拼写。网关在启动时警告没有可请求模型可以使用的行。
* Web 搜索请求保持在 \$0.01 列表价格；乘数仍然适用于它们。

对于按地区的费率，为每个地区提供自己的命名上游和每个上游一行。

<h4 id="mark-prices-up">
  标记价格上升
</h4>

使用网关服务器上的 v2.1.271 或更高版本，您可以将 `multiplier` 设置为大于 1，最多 10，以计量超过提供商收费的金额，例如内部退款费率。此示例以价格的 120% 计量每个请求：

```yaml theme={null}
pricing:
  multiplier: 1.2
```

使用 [`admin:`](#admin) 块，标记也适用于支出限制。计量器计数价格的 120%，因此开发者更快达到其上限。网关在启动时记录警告，说明这一点。

乘数不会改变上游提供商对请求的收费。

如果网关还[将费率发送给已登录的客户端](#send-the-rates-to-signed-in-clients)，开发者需要 Claude Code v2.1.271 或更高版本才能看到标记。早期客户端忽略大于 1 的 `multiplier` 并显示不带它的成本。

早于 v2.1.271 的网关服务器拒绝在设置 `multiplier` 大于 1 时启动。

<h4 id="send-the-rates-to-signed-in-clients">
  将费率发送给已登录的客户端
</h4>

使用网关服务器上的 v2.1.268 或更高版本，网关还将 `pricing` 中的费率放入它提供的 [`managed`](#managed) 策略中，作为 [`modelPricing`](/docs/zh-CN/settings-reference#modelpricing) 托管设置。与策略匹配的开发者随后在 `/usage`、状态行和 OpenTelemetry 中看到第一个为每个模型 ID 提供服务的上游的 `pricing` 费率。与任何策略不匹配的开发者不会收到托管设置，因此他们的数字保持在列表价格。客户端在 Claude Code v2.1.242 或更高版本中应用该设置。

* 网关添加的内容：除非策略的 `cli` 块已经设置 `modelPricing`，网关添加 `multiplier` 和，对于客户端可以请求的每个模型 ID，为该 ID 提供服务的第一个上游的覆盖行。仅故障转移上游收费的费率保留在网关上。
* 选择一个策略退出：在该策略的 `cli` 块中将 `modelPricing` 设置为 `{}`，其开发者保持在列表价格。
* 保留策略自己的费率：其 `cli` 块使用自己的 `multiplier` 或 `overrides` 设置 `modelPricing` 的策略保留该 `modelPricing` 完整，网关不向其添加自己的费率。

<h3 id="models">
  `models`
</h3>

`models` 块是可选的管理员策划的模型列表，在 `/v1/models` 提供，用于按上游翻译模型 ID。它对于非美国 Amazon Bedrock 地区、Amazon Bedrock 预配置吞吐量 ARN 和 Microsoft Foundry 部署名称是必需的。

```yaml theme={null}
auto_include_builtin_models: true   # false: 仅公开下面的列表
models:
  - id: claude-opus-4-8
    label: Claude Opus 4.8
    # description: 可选文本显示在表面它的客户端中
    upstream_model:
      anthropic: claude-opus-4-8
      bedrock: us.anthropic.claude-opus-4-8   # 或推理配置文件 ARN
      foundry: your-opus-deployment-name
```

`upstream_model` 下的每个键必须匹配配置的上游的 `name`，默认为提供商名称。与任何上游不匹配的键会导致启动失败，因此省略您不使用的提供商的行。

<h3 id="managed">
  `managed`
</h3>

`managed` 块定义基于 IdP 组或电子邮件域的基于角色的访问策略。策略按顺序评估；选择第一个匹配，然后合并到 `match: {}` 全部捕获基础。它们按用户在 `GET /managed/settings` 提供，带有 ETag/304 缓存。

```yaml theme={null}
managed:
  policies:
    # 首先是特定组。
    - match: { groups: [eng-contractors] }
      cli:
        availableModels: [claude-sonnet-4-6]
        permissions: { deny: ["WebFetch", "WebSearch"] }
    # 默认全部捕获最后：匹配每个已认证的用户。
    - match: {}
      cli:
        availableModels: [claude-opus-4-8, claude-sonnet-4-6, claude-haiku-4-5]
```

`match: {}` 全部捕获，按惯例列在最后，被视为基础层。每个其他策略从全部捕获继承它不设置的任何键，因此每个角色条目只需列出与组织默认值不同的内容。合并规则取决于键类型：

* **允许列表**：`availableModels` 和 `permissions.allow`。特定策略的列表完全替换基础的。
* **拒绝列表和钩子数组**：`permissions.deny`、`permissions.ask`、`disabledMcpjsonServers`、`deniedMcpServers`、`blockedMarketplaces` 和每个 `hooks` 事件类型数组。这些取基础和策略的并集，因此组织范围的拒绝或审计钩子不会被每个角色覆盖意外删除。
* **记录类型的键**：`env`、`modelOverrides` 和 `skillOverrides`。这些浅合并，因此每个角色 `env` 块覆盖它设置的键并从基础继承其余的。

`availableModels` 也在 `/v1/messages` 服务器端强制执行，因此被拒绝的模型返回 `400`，无论客户端发送什么。

网关在中继请求之前验证 `model` 值本身，因此格式错误的值永远不会到达上游。它在两种情况下以 `400` 拒绝请求：

* 当值缺失或为空时，网关以消息 `model is required` 拒绝请求。该检查需要运行 Claude Code v2.1.228 或更高版本的网关。
* 当值存在但不是字符串时，网关以消息 `model must be a string` 拒绝请求。需要运行 Claude Code v2.1.221 或更高版本的网关。

| 匹配器 | 行为 |
| - | - |
| `match: {}` | 匹配每个已认证的用户。从其中一个开始，稍后在其上方添加组范围的策略。 |
| `match: { groups: [a, b] }` | 如果 JWT 的 `groups` 声明包含任何列出的组，则匹配。区分大小写：组必须匹配 IdP 的确切大小写。 |
| `match: { email_domain: example.com }` | 匹配 JWT 的 `email` 声明中最后一个 `@` 之后的部分，不区分大小写。每个策略接受一个域。 |
| `match: { groups: [a], email_domain: example.com }` | 两个条件都必须匹配 |

与任何策略不匹配的已认证用户获得网关的默认值，这意味着目录中的每个模型和没有托管设置。如果您想要保证的默认策略，请在最后添加 `match: {}` 全部捕获。

<Note>
  网关不保留自己的用户目录。它从用户的 IdP 令牌授权每个请求，从令牌的 `groups` 声明读取组成员身份，并根据它评估策略。没有名册可以枚举，没有账户需要预先创建，因此没有 SCIM 端点，因为没有东西可以让 SCIM 同步到。

  在真实来源（您的 IdP 的本地 SCIM 配置或专用身份治理平台）运行用户和组生命周期管理。那里管理的成员身份和取消配置通过令牌自动流入网关。如果您想要 Claude 账户本身的 SCIM 配置，那是[Claude for Enterprise](/docs/zh-CN/admin-setup) 功能。

  两个传播时钟适用：

  * **策略内容**：编辑策略并重新部署在连接的客户端的下一个托管设置轮询中到达，在一小时内，除了[仅在下一次启动时应用的更改](/docs/zh-CN/server-managed-settings#fetch-and-caching-behavior)
  * **组成员身份**：更改用户的组成员身份更改哪个策略与他们匹配。这在下一个会话重新铸造时生效，意味着下一个静默刷新，受 `session.ttl_hours` 限制。
</Note>

<h4 id="matcher-values-that-stop-the-gateway-at-boot">
  在启动时停止网关的匹配器值
</h4>

在启动时，网关检查每个策略的 `match` 块和 [`admin_groups`](#admin) 列表。这些值中的任何一个都会停止网关，出现命名该字段的错误：

* 空 `groups` 列表
* `groups` 或 `admin_groups` 中的空条目
* 空 `email_domain`
* 包含 `@`、空格或逗号的 `email_domain`。网关修剪值并在此检查之前删除一个前导 `@`。编写一个裸域，例如 `example.com`。

在 v2.1.232 之前，网关以这些值启动。每个值有这个效果：

* 空 `email_domain`：网关跳过域检查，因此具有空 `email_domain` 和没有 `groups` 列表的策略匹配每个已认证的用户
* 空 `groups` 列表：策略与任何人都不匹配
* 包含 `@`、空格或逗号的 `email_domain`：策略与任何人都不匹配
* `groups` 或 `admin_groups` 中的空条目：条目仅当该用户的 IdP `groups` 声明也包含空条目时才与用户匹配。在 `admin_groups` 中，该匹配授予管理员访问权限。如果您的 `admin_groups` 列表从不包含空条目，没有人以这种方式获得管理员访问权限。

<h4 id="what-goes-in-cli">
  `cli` 中的内容
</h4>

每个 `cli` 值是完整的 Claude Code `managed-settings.json` 文档，与您通过 MDM 或 `/etc/claude-code/managed-settings.json` 部署的相同架构，在此表示为 YAML。CLI 在托管层应用交付的文档，在用户和项目设置之上，代替服务器托管的设置。因此它忽略[限制为操作系统级策略来源的设置](/docs/zh-CN/server-managed-settings#current-limitations)，例如 `policyHelper` 和 `wslInheritsWindowsSettings`。

网关在启动时根据 CLI 的设置架构验证每个文档，因此无法识别的顶级键会导致启动失败，出现命名每个违规键的错误。架构的故意开放部分仍然接受任意值，因为较新的客户端可能识别网关架构不识别的条目。这些开放键包括 `env`、`pluginConfigs` 和 `permissions` 下嵌套的键。

因为验证使用与网关安装版本捆绑的架构，将由较新 Claude Code 版本引入的顶级设置键放入托管配置需要首先升级网关。在将新策略推出到一个客户端之前进行烟雾测试。

完整的键参考在[Claude Code 设置](/docs/zh-CN/settings-reference#all-settings)中。操作员首先寻求的键：

```yaml theme={null}
managed:
  policies:
    - match: {}
      cli:
        # 模型访问（也在 /v1/messages 服务器端强制执行）
        availableModels: [claude-opus-4-8, claude-sonnet-4-6, claude-haiku-4-5]

        # 权限策略
        permissions:
          deny:
            - "WebFetch"
            - "Read(./.env)"
            - "Read(./secrets/**)"
          disableBypassPermissionsMode: disable   # 阻止 --dangerously-skip-permissions
        allowManagedPermissionRulesOnly: true     # 忽略用户/项目权限规则

        # 推送到 CLI 进程的环境。DISABLE_UPDATES 阻止
        # 后台和手动更新；DISABLE_AUTOUPDATER 仅停止
        # 后台更新。
        env:
          DISABLE_UPDATES: "1"                    # 通过您自己的分发固定版本

        # 组织范围的钩子。钩子命令在开发者机器上运行，不是
        # 网关，因此路径必须存在于策略中每个客户端操作系统上。
        hooks:
          PostToolUse:
            - matcher: "Edit|Write"
              hooks:
                - { type: command, command: /usr/local/bin/audit-edit.sh }
```

| 键 | 由以下强制执行 | 效果 |
| - | - | - |
| `availableModels` | 网关 + CLI | 模型允许列表。也在 `/v1/messages` 检查，因此修补的客户端无法绕过它。 |
| `permissions.allow` / `.deny` | CLI | 工具和命令规则。请参阅[权限](/docs/zh-CN/permissions)。 |
| `permissions.disableBypassPermissionsMode` | CLI | 设置为 `disable` 以阻止 [`bypassPermissions`](/docs/zh-CN/permission-modes#skip-all-checks-with-bypasspermissions-mode)，跳过权限提示的模式，以及 `--dangerously-skip-permissions` 标志 |
| `allowManagedPermissionRulesOnly` | CLI | 当 `true` 时，托管设置成为权限规则的唯一设置来源。[`allowManagedPermissionRulesOnly`](/docs/zh-CN/settings-reference#allowmanagedpermissionrulesonly) 条目列出 Claude Code 随后忽略的每个来源。 |
| `env` | CLI | 合并到 CLI 进程的环境变量。用于遥测、自动更新和模型名称覆盖。 |
| `hooks` | CLI | 组织范围的[钩子](/docs/zh-CN/hooks) |
| `managedMcpServers` | CLI | 远程 MCP 服务器[提供给每个匹配的开发者](/docs/zh-CN/managed-mcp#provide-servers-through-managed-settings)以及他们自己添加的服务器，仅 `http` 和 `sse`。请参阅[策略中的 MCP 服务器](#mcp-servers-in-a-policy)。需要网关服务器和客户端上的 Claude Code v2.1.259 或更高版本。早期客户端忽略该键。 |

因为这些设置通过网络到达，CLI 在应用下面列出的设置之前向每个开发者显示安全批准对话框：

* `hooks`
* 需要开发者批准的 `env` 变量，例如代理和基础 URL 变量
* shell 执行设置，例如 `apiKeyHelper` 和 `statusLine`
* 沙箱二进制设置 `sandbox.bwrapPath`、`sandbox.socatPath` 和 `sandbox.ripgrep`
* 拦截流量、注入凭证或削弱隔离的沙箱设置，例如 `sandbox.network.tlsTerminate` 和代理端口设置。[安全批准对话框](/docs/zh-CN/server-managed-settings#security-approval-dialogs)列出所有这些。

[批准记忆](/docs/zh-CN/server-managed-settings#approval-memory)涵盖批准持续多长时间以及对话框何时再次出现。

Claude Code 应用一些交付的 `env` 变量而不向开发者显示批准对话框，例如模型选择设置和数值限制。其他交付的变量可能需要开发者的批准才能生效；非空代理、基础 URL 或 `OTEL_EXPORTER_OTLP_ENDPOINT` 值总是这样。当交付的变量需要批准时，对话框命名它。

[环境变量和批准对话框](/docs/zh-CN/server-managed-settings#environment-variables-and-the-approval-dialog)有详细信息，包括四个隐私切换，其交付值决定是否需要批准。在 v2.1.218 之前，Claude Code 应用更少的变量而不询问开发者，因此更多交付的变量触发对话框。

网关的[遥测](#telemetry)配置推送 `OTEL_EXPORTER_OTLP_ENDPOINT`，因此设置 `telemetry.forward_to` 在每个交互式客户端上触发对话框。对话框保护开发者的机器免受受损或敌对网关的影响，而不是保护组织免受开发者的影响。

带有 `-p` 标志的非交互式运行无法显示对话框。它仅为该运行应用推送的设置，不将其记录为已批准，因此开发者的下一个交互式会话仍然显示对话框。在 v2.1.207 之前，非交互式运行将设置保存为已批准，没有后来的交互式会话为它们显示对话框。

如果开发者拒绝，Claude Code 退出该会话而不是应用策略。当您推送新钩子或任何触发对话框的 env 变量到广泛策略时，Claude Code 因此向每个匹配的开发者显示对话框。它在运行会话中的下一个小时轮询显示对话框，否则在开发者的下一个启动时显示。

`cli` 键在早期版本中被命名为 `settings`。该拼写仍然被接受为别名，但新部署应该使用 `cli`。

<h4 id="mcp-servers-in-a-policy">
  策略中的 MCP 服务器
</h4>

要向策略匹配的 Claude Code 客户端提供 MCP 服务器，在该策略的 `cli` 块中设置 [`managedMcpServers`](/docs/zh-CN/managed-mcp#provide-servers-through-managed-settings)。您需要网关服务器和客户端上的 Claude Code v2.1.259 或更高版本。

网关在启动时使用[Claude Code 在客户端应用的相同规则](/docs/zh-CN/managed-mcp#what-an-entry-can-contain)检查每个条目，如果条目失败检查，网关拒绝启动并命名该条目。

如果您在 `gateway.yaml` 中编写 `${VAR}` 引用，网关在启动时通过[秘密扩展](#secret-expansion)从其环境解析它，然后运行条目检查，因此每个匹配的客户端接收文字值并可以读取它。[为提供的服务器的标头指导](/docs/zh-CN/managed-mcp#provide-servers-through-managed-settings)适用于扩展值。

网关拒绝 `cli` 块中的 `.mcp.json` 拼写 `mcpServers`，其启动错误命名 `managedMcpServers` 作为要使用的键。在 v2.1.259 之前，网关拒绝 `cli` 块中的任何 MCP 服务器定义。

<h4 id="claude-desktop-overlay">
  Claude Desktop 覆盖
</h4>

如果您的组织也部署[Claude Desktop](/docs/zh-CN/desktop)，同一网关为两个客户端提供服务。在 Claude Desktop 的[托管配置](https://claude.com/docs/third-party/claude-desktop/configuration)中指向 `bootstrapUrl` 到 `<listen.public_url>/user/bootstrap`。Claude Desktop 从该 URL 派生 OAuth 发行者，针对此网关运行相同的设备代码登录，并从响应获取其配置。

<Note>
  需要网关服务器上的 Claude Code v2.1.203 或更高版本，以及显式选择加入：除非与用户匹配的策略携带 `desktop` 键，否则 `/user/bootstrap` 返回 404。空 `desktop: {}` 选择策略加入，`match: {}` 基础层上的 `desktop` 键选择继承它的每个策略加入。审计日志将每个请求记录为 `desktop_bootstrap.serve` 或 `desktop_bootstrap.denied`。
</Note>

网关从匹配策略的 `cli` 块和顶级网关配置派生响应的大部分：

* 模型列表，来自 `availableModels`
* 禁用的工具，来自裸工具名称 `permissions.deny` 条目。如果您在策略的 `desktop` 块中设置 `disabledBuiltinTools`，网关提供您的值和派生列表的并集，因此您可以通过这种方式禁用更多工具，但无法重新启用您通过 `permissions.deny` 禁用的工具
* 出口允许列表，来自 `sandbox.network.allowedDomains`。如果您在策略的 `desktop` 块中设置 `coworkEgressAllowedHosts`，网关使用该值而不是派生列表
* 指向网关本身的 OTLP 端点，以及已登录用户的身份属性。网关将它在该端点接收的导出中继到您的 `forward_to` 目的地。当您同时设置 [`telemetry.forward_to`](#telemetry) 和 `listen.public_url` 时，它包括端点和属性。

  Claude Desktop 以一种编码导出每个信号：`http/protobuf`，或当您在策略的 `env` 中将 `OTEL_EXPORTER_OTLP_PROTOCOL` 或其每个信号变体设置为 `http/json` 时为 `http/json`。在网关服务器上的 Claude Code v2.1.261 之前，响应设置 `http/json` 无论如何，因此仅接受 protobuf 的收集器拒绝了 Claude Desktop 的导出

要在策略的 `desktop` 块中设置 `disabledBuiltinTools`、`coworkEgressAllowedHosts` 或 Claude Desktop 自己的 `managedMcpServers` 设置，您需要网关服务器上的 Claude Code v2.1.232 或更高版本。Claude Desktop 的 `managedMcpServers` 采用数组值而不是对象。

网关省略没有 Claude Desktop 等效项的键，例如 `hooks` 和范围权限规则如 `Bash(npm *)`，来自引导响应。

添加可选的 `desktop` 块与 `cli` 一起直接设置 Claude Desktop 设置。从 Claude Desktop 的[托管配置参考](https://claude.com/docs/third-party/claude-desktop/configuration)编写设置为平面键名。省略 Claude Desktop 仅从 MDM 或本地文件读取的键，例如 `bootstrapUrl`；网关在启动时拒绝它们。在 v2.1.232 之前，网关接受固定的 11 个功能门键列表，例如 `chatTabEnabled` 和 `disableAutoUpdates`，并在启动时拒绝每个其他键。在 v2.1.227 之前，网关也在启动时拒绝 `chatTabEnabled` 和 `chatAdvancedFileAnalysisEnabled`。

```yaml theme={null}
managed:
  policies:
    - match: { groups: [eng-contractors] }
      cli:
        availableModels: [claude-sonnet-4-6]
      desktop:
        isLocalDevMcpEnabled: false
        disableAutoUpdates: true
        banner: { text: "Contractor build: internal use only" }
```

每个键都是可选的；Claude Desktop 为您省略的任何键应用自己的默认值。网关在启动时根据 Claude Desktop 本身使用的配置架构验证每个 `desktop` 块，因此错误会在网关启动时显示为命名该键的错误，而不是到达每个连接的桌面。网关在块包含以下内容时在启动时失败：

* 未知键
* 识别的键，其值 Claude Desktop 会拒绝或静默删除，例如空值或嵌套条目内的拼写错误的子键。在 v2.1.260 之前，网关静默删除 `managedMcpServers` 或 `orgPluginSettings` 条目的嵌套对象内的拼写错误字段，而不是在启动时失败。
* 网关自己计算的键：推理连接、模型列表和 OTLP 中继。通过 [`upstreams`](#upstreams)、[`models`](#models) 和 [`telemetry`](#telemetry) 部分的 `forward_to` 配置这些。
* 当前键的遗留别名。在启动错误中，网关命名规范键来编写。

如果您使用已弃用的值或条目形状，例如没有 `transport` 的 `managedMcpServers` 条目，网关启动并记录命名替换的警告。

网关根据与其安装版本捆绑的架构验证 `desktop` 块，就像它对 `cli` 块所做的那样。要交付由较新 Claude Desktop 版本引入的设置，首先升级网关。例如，`userPluginMarketplacesEnabled` 和 `userPluginUploadsEnabled` 需要网关服务器上的 Claude Code v2.1.260 或更高版本以及成员机器上的 Claude Desktop 1.37937.0 或更高版本。

如果您在策略的 `desktop` 块中设置 `orgPluginSettings`，网关以 Claude Desktop 1.15200.0 及更高版本读取的数组形式提供它。较旧的桌面忽略数组并强制执行无插件工具策略，因此在依赖它之前将成员更新到 1.15200.0 或更高版本。

网关从策略的 `desktop` 块不设置的 `match: {}` 全部捕获的 `desktop` 块填充键，与它填充策略的 `cli` 块的方式相同。如果您在基础和角色策略中都设置 `disabledBuiltinTools` 或 `builtinToolPolicy`，网关保留基础的限制：

* `disabledBuiltinTools`：网关使用基础列表和策略列表的并集
* `builtinToolPolicy`：如果您在基础中为工具设置除 `allow` 之外的值，网关保留该值，即使您在角色策略中为同一工具设置 `allow`

对于每个其他键，如果您在角色策略中设置它，网关使用角色策略的值。网关整体替换数组或嵌套对象（如 `banner`），因此如果您在角色策略中设置 `banner.text`，网关删除基础的 `banner.backgroundColor`。

如果您不部署 Claude Desktop，完全从您的策略中省略 `desktop`；网关随后为每个用户从 `/user/bootstrap` 返回 404。

<h4 id="precedence-with-other-managed-sources">
  与其他托管来源的优先级
</h4>

如果设备也有 MDM 交付的策略或本地 `managed-settings.json`，网关交付的设置排名第一。[托管层内的优先级](/docs/zh-CN/managed-settings#precedence-within-the-managed-tier)在托管设置页面上说明本地来源何时应用，并具有[Claude Code 从每个管理来源读取的键](/docs/zh-CN/managed-settings#keys-read-from-every-admin-source)，无论它选择哪个来源，例如沙箱锁键、`forceRemoteSettingsRefresh` 和每个变量 `env` 合并。在 MDM 配置文件或托管设置文件中配置的 [`policyHelper`](/docs/zh-CN/settings-reference#policyhelper) 仅在网关不交付设置时运行；条目说明其输出替换什么。

嵌入主机（如[Claude Desktop](/docs/zh-CN/desktop)）可以通过 SDK `managedSettings` 选项提供策略。[来自嵌入主机的父设置](/docs/zh-CN/managed-settings#parent-settings-from-embedding-hosts)说明 Claude Code 何时应用它，[限制父设置](/docs/zh-CN/claude-apps-gateway#restrict-parent-settings)列出哪些允许方向设置仍然适用而不需要 `allowManaged*Only` 锁。

网关策略适用于机器上的每个 Claude Code 调用，包括非交互式 `claude -p` 运行和由 Agent SDK 生成的会话。如果网关在启动时无法访问，已登录的会话退出并出现错误，而不是在没有其策略的情况下运行。

<h3 id="telemetry">
  `telemetry`
</h3>

CLI 将指标、日志和（启用时）跟踪发送到网关，网关将它们逐字中继到每个配置的目的地。导出使用 OpenTelemetry Protocol (OTLP) over HTTP。要跳过中继并让会话直接导出到您的收集器，[在策略中命名收集器](#export-directly-to-your-collector)。请参阅[监控使用](/docs/zh-CN/monitoring-usage)了解 CLI 发出的指标和事件。

CLI 使用从网关颁发的 JWT 读取的已认证用户的身份为每个导出加盖时间戳：`user.id`、`user.email` 和 `user.groups` 属性。因此，每个开发者的成本和使用归属无需开发者端配置即可工作。

[Claude Desktop](#claude-desktop-overlay) 和通过网关登录的 Cowork 会话使用 `user.email` 和 `user.groups` 以及 `enduser.id` 为其遥测加盖时间戳，因此您可以使用一个关于 `user.email` 或 `user.groups` 的查询覆盖终端、Desktop 和 Cowork 使用。`user.groups` 是逗号分隔的 IdP 组列表。

Desktop 和 Cowork 遥测也携带 `enduser.sub`，您的身份提供商为用户颁发的 `sub` 声明，当用户的电子邮件更改时保持不变。终端会话在 `user.id` 下加盖相同的值，因此与终端 `user.id` 匹配 `enduser.sub` 的查询涵盖一个用户的终端、Desktop 和 Cowork 使用。在 Desktop 和 Cowork 导出上，`user.id` 是匿名标识符，而不是主体。

像来自 Claude Code 的所有 OpenTelemetry 数据一样，这些属性仅转到您的组织配置的目的地，从不转到 Anthropic。

如果用户的组列表在百分比编码后长于 255 个字符，或组名包含逗号或等号，网关会从该用户的 Desktop 和 Cowork 遥测中省略 `user.groups`，而不是截断它。该用户的终端会话仍然携带完整列表。

当主体在百分比编码后长于 255 个字符，或包含空格、可打印 ASCII 外的字符或 `,` `;` `=` `\` `"` `%` 之一时，网关会省略 `enduser.sub`。该用户的 Desktop 和 Cowork 遥测保留其他属性。

您需要网关服务器上的 Claude Code v2.1.265 或更高版本才能在 Desktop 和 Cowork 遥测上获得 `user.email` 和 `user.groups`，以及每个开发者机器上的 Claude Desktop 1.24012 或更高版本才能获得 `user.groups`。

您需要网关服务器上的 Claude Code v2.1.274 或更高版本才能获得 `enduser.sub`。

```yaml theme={null}
telemetry:
  forward_to:
    - url: https://otel-collector.internal.example.com
      headers:
        Authorization: ${OTLP_TOKEN}
      # 每个信号选择加入。默认：仅指标。
      metrics: true
      logs: false
      traces: false
    - url: https://api.datadoghq.com/api/v2/otlp
      headers:
        DD-API-KEY: ${DD_API_KEY}
```

<Warning>
  每个目的地独立选择加入 `metrics`、`logs` 和 `traces`，默认仅为指标。信号的敏感性不同：

  * **指标**：聚合计数器，例如令牌计数、请求计数和延迟
  * **日志和跟踪**：可以携带完整的 Bash 命令、工具输入和文件路径，涵盖 Claude Code 在开发者机器上所做的任何事情

  仅在具有该数据保证的访问控制和保留策略的目的地启用日志和跟踪。
</Warning>

每个 `forward_to` URL 必须使用 `https://`，有一个例外是网关自己的环回接口上的收集器：

* `http://localhost:<port>` 通过配置验证，但[SSRF 防护](/docs/zh-CN/claude-apps-gateway-deploy#threat-model-summary)除非您在网关的环境中设置 `CLAUDE_GATEWAY_ALLOW_LOOPBACK=1`，否则阻止每个导出为 `ECONNREFUSED_SSRF`
* `http://127.0.0.1:<port>` 或 `http://[::1]:<port>` 除非设置该变量，否则启动失败

对于集群内收集器，在其自己的内部地址上通过 HTTPS 公开它，或将其作为设置了变量的 sidecar 运行。

当设置 `HTTPS_PROXY` 时，网关通过该代理发送导出。

要直接到达内部收集器，通过主机名或带有前导点的域（如 `.internal.example.com`）将其添加到 `NO_PROXY`，这需要网关服务器上的 Claude Code v2.1.277 或更高版本。确保网关可以在没有代理的情况下到达收集器。没有前导点的条目仅匹配该确切名称，不匹配其下的名称。CIDR 范围不匹配。

启用[仅代理出口](#proxy-only-egress)后，改为在代理中允许收集器，因为任何 `NO_PROXY` 条目都会关闭仅代理出口。

遥测在 CLI 中默认关闭。当您同时设置 `telemetry.forward_to` 和 `listen.public_url` 时，网关通过 `/managed/settings` 推送六个环境变量为连接的客户端打开它：

* `CLAUDE_CODE_ENABLE_TELEMETRY=1`
* `OTEL_METRICS_EXPORTER`、`OTEL_LOGS_EXPORTER` 和 `OTEL_TRACES_EXPORTER`，如果至少一个 `forward_to` 目的地启用该信号，则每个设置为 `otlp`，否则设置为 `none`
* `OTEL_EXPORTER_OTLP_ENDPOINT=<public_url>`
* `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`

当您[添加您自己的标签](#add-your-own-labels)时，网关也推送 `OTEL_RESOURCE_ATTRIBUTES`。

在网关服务器上的 Claude Code v2.1.265 之前，网关将所有三个导出器选择器推送为 `otlp`，包括没有目的地选择加入的信号。

推送的端点是从公共 URL 构建的，因此指标和日志不需要来自开发者或策略的 OTEL 配置。

通过 `/login` 登录的开发者无法使用自己的 OTEL 配置重定向导出：

* **本地设置的变量**：Claude Code 在托管层应用推送的变量，因此每个变量覆盖开发者为其本地设置的值。
* **本地配置的端点**：启用 OTLP/HTTP 导出后，CLI 忽略任何本地配置的端点，无论网关是否推送了遥测变量。其导出转到网关，除非策略[将您的收集器命名为端点](#export-directly-to-your-collector)。

没有信号的 `forward_to` 目的地，网关接受并丢弃它。如果开发者已经将 Claude Code 遥测导出到您的一个收集器，将其添加为 `forward_to` 目的地，如果他们导出这些，则启用日志或跟踪，以便在他们登录后继续接收其数据。要跳过中继，改为[在策略中命名收集器](#export-directly-to-your-collector)。

[跟踪](/docs/zh-CN/monitoring-usage#traces-beta)也需要每个客户端上的 `CLAUDE_CODE_ENHANCED_TELEMETRY_BETA=1`。在托管策略的 `env` 块中设置它，因为网关不推送它。开发者在推送端点已经触发的相同[安全批准对话框](#managed)中批准它。

仅在您想要跟踪的组的策略中将其设置为 `1`。不设置它的策略从您的 `match: {}` 全部捕获策略继承值（如果该策略设置一个），根据[合并规则](#managed)。要防止组的客户端发送跟踪，即使开发者在本地设置变量，在该组的策略中将其设置为 `0`。

Protobuf 和 JSON OTLP 编码都被中继，任何 OpenTelemetry 兼容的后端都可以作为目的地。

<h4 id="add-your-own-labels">
  添加您自己的标签
</h4>

要在通过网关登录的会话的遥测上放置固定标签（如 `service.namespace` 或 `deployment.environment.name`），设置 `telemetry.resource_attributes`。每个标签是一个 OpenTelemetry 资源属性，每个目的地接收相同的标签。

会话仅在您也设置 `telemetry.forward_to` 和 `listen.public_url` 时获得标签。此示例添加两个标签：

```yaml theme={null}
telemetry:
  forward_to:
    - url: https://otel-collector.internal.example.com
  resource_attributes:
    service.namespace: claude
    deployment.environment.name: prod
```

当标签违反这些规则之一时，网关拒绝启动，启动错误命名标签：

* 名称仅使用字母、数字、`.`、`_` 和 `-`
* 名称不是保留的。以任何字母大小写比较，保留名称是以 `user.`、`enduser.` 或 `identity.` 开头的所有内容，加上 `service.name`、`service.version`、`claude.deployment_mode`、`host.arch`、`os.type`、`os.version` 和 `wsl.version`
* 值是非空可打印 ASCII，没有空格和 `, ; = \ " %` 中的任何一个
* 值最多 255 个字符，网关在百分比编码后计数，因此 `/`、`:` 和 `@` 各计为三个
* 值是文本，因此引用数字、`true` 或 `false`

您需要网关服务器上的 Claude Code v2.1.281 或更高版本才能设置 `telemetry.resource_attributes`。早期网关在找到该键时拒绝启动。在添加该键之前升级每个副本，并在回滚到早期版本之前删除该键。

通过 `/login` 登录的终端会话接收标签作为 `OTEL_RESOURCE_ATTRIBUTES`，与其他[遥测变量](#telemetry)一起推送。如果您在策略的 `env` 块中设置 `OTEL_RESOURCE_ATTRIBUTES`，与该策略匹配的终端会话获得该值而不是标签。Claude Desktop 从网关接收标签以及 `user.email` 和其他身份属性。

Claude Code 也将每个标签复制到每个指标数据点，因此您可以在不索引资源属性的后端中按它过滤指标。要关闭该复制，请参阅[指标基数控制](/docs/zh-CN/monitoring-usage#metrics-cardinality-control)。

<h4 id="export-directly-to-your-collector">
  直接导出到您的收集器
</h4>

要让通过 `/login` 登录的会话直接将遥测发送到您的收集器而不是通过中继，在[托管策略](#managed)的 `env` 块中将 `OTEL_EXPORTER_OTLP_ENDPOINT` 设置为收集器的 `https://` 基础 URL。Claude Code 将 `/v1/metrics`、`/v1/logs` 或 `/v1/traces` 附加到您设置的 URL，例如 `https://otel-collector.example.com:4318`，并通过 OTLP/HTTP 在那里导出每个信号。需要每个开发者机器上的 Claude Code v2.1.265 或更高版本。早期客户端通过中继导出。

要向收集器进行身份验证，在同一 `env` 块中设置 `OTEL_EXPORTER_OTLP_HEADERS`。会话从不将开发者的网关会话令牌发送到以这种方式命名的收集器。

当您在策略中添加或更改此端点时，Claude Code 在应用它于交互式会话之前要求每个开发者在[安全批准对话框](#managed)中批准它。

Claude Code 在直接导出信号之前检查端点，当检查失败时将该信号保留在中继上。检查包括：

* 端点来自网关本身。如果您在 MDM 配置文件或本地 `managed-settings.json` 中设置相同的变量，导出保留在中继上。
* URL 使用 `https://`，或 `http://` 到环回地址
* URL 解析为以 `/v1/<signal>` 结尾的路径，没有查询或片段。Claude Code 自己从通用变量构建该路径。它使用每个信号变量（如 `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT`）按原样编写，因此在那里包括完整路径。
* URL 不是网关自己的主机。寻址到网关的端点保留中继路径及其会话令牌。
* 您和开发者都没有在任何设置来源中配置 [`otelHeadersHelper`](/docs/zh-CN/settings-reference#otelheadershelper)。配置了助手，每个信号保留在中继上。

您命名的端点仅改变导出的去向。您仍然使用 `OTEL_*_EXPORTER` 选择器选择哪些信号导出。

端点单独不打开导出，因此也设置执行此操作的变量，除非网关已经推送它们：

* 如果网关已经[推送遥测变量](#telemetry)，它们涵盖启用、选择器和协议，您的显式端点覆盖推送的 `<public_url>` 值。仅为网关不推送的信号自己设置 `OTEL_*_EXPORTER` 选择器为 `otlp`。
* 如果它没有，也设置 `CLAUDE_CODE_ENABLE_TELEMETRY=1`、`OTEL_*_EXPORTER` 选择器和 `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`。

当开发者登出或登入不同的网关时，对收集器的导出停止，Claude Code 删除每个剩余批次而不是发送它。

<h4 id="when-a-destination-fails">
  当目的地失败时
</h4>

网关不缓冲、重试或存储遥测，因此它删除未到达目的地的导出而不是晚期交付它。每个目的地独立成功或失败，导出客户端无论如何都收到成功响应，因此失败的交付仅出现在网关的日志中。

在五次连续失败交付到目的地后，网关在 30 秒拉伸中暂停转发到它，记录每个暂停，直到交付成功。任何错误响应、超时或连接错误都计为失败交付，除了 `400`、`413`、`415`、`422` 和 `431`，这意味着收集器拒绝该导出的有效负载为格式错误或太大。

拒绝的有效负载既不推进也不重置失败计数：网关继续转发到目的地并记录警告，命名它和状态，在目的地的第一次拒绝和之后每一百次。

<h3 id="http-tuning">
  HTTP 调整
</h3>

四个可选的顶级块 `access_control`、`limits`、`timeouts` 和 `rate_limits` 调整 HTTP 表面。默认值适合大多数部署。

| 块 | 键 | 默认 | 描述 |
| - | - | - | - |
| `access_control` | `allow_cidrs` / `deny_cidrs` | 空 | 入站 IP 允许/拒绝按客户端地址，在 `trusted_proxies` 解析后。`deny_cidrs` 首先检查；与它匹配的客户端被拒绝，即使 `allow_cidrs` 也匹配。如果 `allow_cidrs` 非空，网关是默认拒绝。`/healthz` 和 `/readyz` 免除 `allow_cidrs`。当受信任的代理发送不是 IP 地址的 `X-Forwarded-For` 条目时，真实客户端未知，网关记录一次警告，命名要检查的内容。其中任一列表适用于请求，它以 `403` 和审计原因 `xff_unparseable` 拒绝它。其中都不适用，它提供请求并使代理自己的地址用作客户端 IP，用于每 IP 速率限制和审计。 |
| `limits` | `max_request_bytes` | 32 MiB | 最大入站请求体；超大请求在缓冲体之前获得 `413`。为大文件或图像请求提高。 |
| `limits` | `max_request_header_bytes` | 未设置 | 设置时，超大标头返回 `431` |
| `limits` | `max_url_length` | 未设置 | 设置时，过长 URL 返回 `414` |
| `timeouts` | `upstream_ttfb_ms` | 120000 | 等待上游响应标头（首字节时间）的最大时间。响应体随后以无墙钟上限流式传输。适用于直接 Anthropic 上游路径；在每个其他提供商上，网关等待最多一小时以使响应开始。 |
| `rate_limits` | `device_authorization.max` / `.window_seconds` | 30 / 600 | 未认证设备授权端点上的每 IP 速率限制。为共享出口 IP 或 NAT 后面的大型组织提高。[大型推出](/docs/zh-CN/claude-apps-gateway-deploy#large-rollouts)显示如何调整大小。这些限制仅适用于设备授予登录流，不适用于 `/v1/messages` 推理。请参阅[用户代码暴力破解抵抗](/docs/zh-CN/claude-apps-gateway-deploy#user-code-brute-force-resistance)。 |
| `rate_limits` | `device_verify.max` / `.window_seconds` | 10 / 600 | `/device` 上 `user_code` 提交的每 IP 速率限制。这是阻止某人猜测另一个开发者代码的原因。[大型推出](/docs/zh-CN/claude-apps-gateway-deploy#large-rollouts)显示提高多远。 |

如果您将两个 `access_control` 列表都留空，这是默认值，网关为任何客户端地址提供服务，因此仅您的网络限制谁可以到达它。这很重要，因为网关可以推送[托管设置](#managed)，在开发者机器上运行命令。

虽然 `allow_cidrs` 为空，网关在两个地方警告，不改变它如何回答任何请求：

* **在启动时**：操作日志中的警告建议仅允许私有范围 `10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`、`100.64.0.0/10`、`127.0.0.0/8`、`::1/128` 和 `fc00::/7`，加上您的开发者连接的任何其他内部范围。如果您将网关绑定到环回地址并既不设置 `trusted_proxies` 也不设置 `public_url`，如在本地开发中，警告不出现。
* **在运行时**：第一次请求从私有范围外的地址到达时，网关记录警告并发出 [`access.public_client` 审计事件](/docs/zh-CN/claude-apps-gateway-deploy#logs)，携带客户端 IP。两者每个进程触发一次。链接本地地址 `169.254.0.0/16` 和 `fe80::/10` 不计为公共。网关在此检查运行之前回答 `/healthz` 和 `/readyz`，因此来自公共范围的健康探针不触发它。

两个信号都使用网关解析的客户端地址。如果负载均衡器、端口转发或隧道中继流量且未在 `listen.trusted_proxies` 中列出，网关看到中继的地址，通常是私有的，因此既不是运行时警告也不是私有允许列表捕获通过它中继的流量。

在这样的前端后面，首先设置 [`listen.trusted_proxies`](#listen)，以便网关看到真实客户端地址，并保持网关和其前面的所有东西从公共互联网无法访问，无论如何。

<h3 id="load_test_mode">
  `load_test_mode`
</h3>

`load_test_mode` 块让您在不调用模型提供商的情况下对网关进行负载测试。启用它时，网关像往常一样构建和签署每个提供商请求，丢弃它而不是发送它，并通过其正常响应路径流式传输罐装回复。回复是填充文本，以说明它是罐装的句子开头。

需要网关服务器上的 Claude Code v2.1.282 或更高版本。早期版本在找到该键时拒绝启动。在添加块之前升级每个副本，并在回滚之前删除块。

下面的示例以默认值打开模式，回复为大约 750 个令牌的文本，在大约 10 秒内流式传输：

```yaml theme={null}
load_test_mode:
  enabled: true
  reply_tokens: 750     # 大约每个罐装回复携带多少令牌的文本
  reply_seconds: 9.5    # 流式回复需要多长时间
```

| 字段 | 必需 | 描述 |
| - | - | - |
| `enabled` | 是 | `true` 打开模式。`false` 在模式关闭的情况下将您的数字保留在文件中。如果块存在而没有它，网关拒绝启动。 |
| `reply_tokens` | 否 | 默认 `750`。大约每个罐装回复携带多少令牌的文本，从 1 到 100000 的整数。 |
| `reply_seconds` | 否 | 默认 `9.5`。流式回复需要多长时间，从 0 到 600。`0` 一次发送整个回复。对非流式请求的回复总是一次返回。 |

此模式下的负载测试涵盖网关、您的 Postgres 和网关前面的所有内容。它不涵盖提供商的限制、速度或网络路径。

没有模型请求发送到提供商，因此副本的每个请求的 CPU 是估计值，读取低于生产，生产也加密其到提供商的流量。使用小试点对真实提供商确认副本计数。在 v2.1.283 之前，估计读取低得多。

启用模式时，请求可以携带 `x-load-test-user` 标头，保存最多七位数的整数。网关将每个数字计为具有请求附带的令牌的开发者的电子邮件和组的单独开发者。

为负载测试部署提供自己的空数据库，因为网关拒绝在任何开发者已经花费任何东西的数据库中启动模式。

<Warning>
  永远不要为开发者使用的网关打开此功能。每个请求都获得罐装回复，没有模型被调用。网关在启动时记录 `load_test_mode is on` 警告，并在模式启用时使用 `load_test: true` 标记每个 `inference` [审计事件](/docs/zh-CN/claude-apps-gateway-deploy#logs)。
</Warning>

<h2 id="complete-example">
  完整示例
</h2>

此完整参考配置演示了每个核心部分；[HTTP 调整块](#http-tuning)保持其默认值。复制它，删除你不需要的，并填入你的值。[快速入门](/docs/zh-CN/claude-apps-gateway#quickstart)中的配置是此的最小版本。

```yaml gateway.yaml theme={null}
# 运行方式：
#   claude gateway --config gateway.yaml
#
# 操作日志详细程度由 CLAUDE_GATEWAY_LOG_LEVEL
# 环境变量控制（debug | info | warn | error；默认 info）。debug
# 也会记录每个 id_token 中的声明名称，用于 groups_claim 诊断。
# 它不影响审计事件，总是发出。

listen:
  host: 0.0.0.0
  port: 8080
  public_url: https://claude-gateway.internal.example.com
  # 在 TLS 终止入口后面运行时省略 tls 块。
  # tls:
  #   cert: /certs/gateway.crt
  #   key: /certs/gateway.key
  # trusted_proxies:
  #   - 10.0.0.0/8

oidc:
  issuer: https://example.okta.com
  client_id: 0oa1example2
  client_secret: ${OIDC_CLIENT_SECRET}
  allowed_email_domains:
    - example.com
  # 当发行者是 Okta 组织服务器时需要，其 id_tokens
  # 可以省略电子邮件和组；网关从 /userinfo 填充它们。
  userinfo_fallback: true
  # allowed_groups: [claude-code-users]
  # Okta 仅在请求 `groups` 范围且
  # 应用的组声明过滤允许它们时发出组。下面的承包商策略
  # 匹配组，因此范围在此处请求。
  scopes: [openid, profile, email, offline_access, groups]
  # extra_auth_params: { access_type: offline, prompt: consent }  # Google
  # groups_claim: groups          # Entra 应用角色：使用 `roles`
  # email_claim: email

session:
  jwt_secret: ${GATEWAY_JWT_SECRET}   # openssl rand -base64 32
  # ttl_hours: 1

store:
  postgres_url: ${GATEWAY_POSTGRES_URL}
  # max_connections: 5
  # connect_timeout_seconds: 5
  # readiness_grace_seconds: 300   # 在数据库故障转移期间保持通过就绪检查

# 启用 /v1/organizations/spend_limits（镜像 Anthropic Admin API）
# 和 /v1/messages 上的每开发者支出强制。省略以禁用。
# 上限本身通过 admin API 设置，而不是此处。
# admin:
#   write_keys:
#     - { id: terraform, key: "${GATEWAY_ADMIN_WRITE_KEY_TF}" }
#   read_keys:
#     - { id: reporting, key: "${GATEWAY_ADMIN_READ_KEY}" }
#   admin_groups: [platform-finops]
#   blocked_message: request an increase at https://go.example.com/claude-limits
#   # audit_retention_days: 365
#   # spend_retention_months: 13
#   # identity_retention_days: 90
#   # group_limit_mode: min

# enforcement:
#   fail_closed_on_error: false

# 在不调用模型提供商的情况下对此部署进行负载测试。永远不要在
# 开发人员使用的网关上：每个请求都会获得一个预设回复。
# load_test_mode:
#   enabled: true
#   # reply_tokens: 750
#   # reply_seconds: 9.5

# 按合同费率而不是美元列表价格计费。需要 admin: 或
# managed: 策略。使用 managed:，相同的费率也会发送到已登录的客户端。
# 下面的费率是占位符，不是真实合同价格。
# pricing:
#   multiplier: 0.85
#   overrides:
#     - { upstream: anthropic, model: claude-sonnet-4-6, input: 3.30, output: 16.50, cache_read: 0.33, cache_write: 4.125 }

upstreams:
  - provider: anthropic
    auth:
      api_key: ${ANTHROPIC_API_KEY}

  # - provider: bedrock
  #   region: us-east-1
  #   auth: {}

  # - provider: anthropicAws
  #   region: us-east-1
  #   workspace_id: wrkspc_...
  #   auth:
  #     api_key: ${ANTHROPIC_AWS_API_KEY}

  # - provider: vertex
  #   region: us-east5
  #   project_id: example-prod
  #   auth: {}

  # - provider: foundry
  #   resource: example-foundry
  #   auth: { use_azure_ad: true }

auto_include_builtin_models: true
models:
  - id: claude-opus-4-8
    label: Claude Opus 4.8
    upstream_model:
      anthropic: claude-opus-4-8
      # bedrock: us.anthropic.claude-opus-4-8
      # anthropicAws: claude-opus-4-8
      # vertex: claude-opus-4-8
      # foundry: <your-opus-deployment-name>
  - id: claude-sonnet-4-6
    label: Claude Sonnet 4.6
    upstream_model:
      anthropic: claude-sonnet-4-6
  - id: claude-haiku-4-5
    label: Claude Haiku 4.5
    upstream_model:
      anthropic: claude-haiku-4-5

managed:
  policies:
    - match: { groups: [contractors] }
      cli:
        availableModels: [claude-haiku-4-5]
        # 将默认选择器选项限制为 availableModels 而不是
        # 层默认，因此承包商不会在默认上获得 400。
        enforceAvailableModels: true
        # allow 自动批准这些工具；它不阻止其余的。
        # 添加拒绝规则以限制工具。
        permissions: { allow: [Read, Grep] }
    - match: {}
      cli:
        availableModels: [claude-opus-4-8, claude-sonnet-4-6, claude-haiku-4-5]
        permissions:
          allow: [Read, Grep, Bash, Edit]
          deny: ["WebFetch"]
        env: { HTTP_PROXY: http://proxy.example.com:8080 }

telemetry:
  forward_to:
    - url: https://otel.internal.example.com:4318
      headers:
        Authorization: Bearer ${OTEL_TOKEN}
```

<h2 id="client-side-managed-settings">
  客户端托管设置
</h2>

上面的所有内容配置网关服务器。将开发者机器指向网关是在每个设备上单独配置的，通过 Claude Code 的[托管设置](/docs/zh-CN/managed-settings)。网关无法自己推送登录密钥，因为这些密钥是告诉客户端网关在哪里的内容。

对于 CLI，在每个 OS 的 `managed-settings.json` 中设置这些密钥。这两个登录密钥将每个开发者的 `/login` 路由到你的网关：

```json theme={null}
{
  "forceLoginMethod": "gateway",
  "forceLoginGatewayUrl": "https://claude-gateway.internal.example.com",
  "parentSettingsBehavior": "merge"
}
```

`parentSettingsBehavior: "merge"` 保持 Claude Desktop 向其嵌入式 Claude Code 会话传递出站允许列表的功能；[向 Claude Desktop 会话传递策略](/docs/zh-CN/claude-apps-gateway#deliver-policy-to-claude-desktop-sessions)解释了该机制以及选择加入必须位于的位置。

将 `managed-settings.json` 文件部署到每个设备，通常通过你的 MDM 平台。文件路径因平台而异。请参阅[每个机制存储策略的位置](/docs/zh-CN/managed-settings#where-each-mechanism-stores-the-policy)。

默认情况下，Windows 上的注册表策略或 macOS 上的托管首选项 plist 会替换 `managed-settings.json` 文件而不是与其合并，除了[上面的例外密钥和跨源检查](#precedence-with-other-managed-sources)。此代码片段中的所有三个密钥都遵循最高优先级源规则，因此通过组策略或配置文件传递策略的团队必须改为将所有三个密钥放在该机制中。

对于 Claude Desktop，在 Claude Desktop 自己的[托管配置](https://claude.com/docs/third-party/claude-desktop/configuration)中设置 `bootstrapUrl` 密钥为 `<listen.public_url>/user/bootstrap`。登录流程和每组策略随后与 CLI 的匹配，一旦策略通过 `desktop` 密钥在服务器端选择加入；没有选择加入，`/user/bootstrap` 返回 404。有关服务器端部分，请参阅 [Claude Desktop 覆盖层](#claude-desktop-overlay)。

Claude Code 仅从机器上的托管源尊重 [`forceLoginGatewayUrl`](/docs/zh-CN/settings-reference#forcelogingatewayurl)、[`gatewayInternalNetworks`](/docs/zh-CN/settings-reference#gatewayinternalnetworks) 和 [`forceLoginMethod`](/docs/zh-CN/settings-reference#forceloginmethod) 的 `"gateway"` 值：`managed-settings.json`、macOS plist 或 Windows HKLM 注册表，或策略助手。在开发者自己的 `~/.claude/settings.json` 中设置它们或在网关有效负载中设置它们不会配置网关登录。

不要在有效负载中包含 `forceLoginMethod` 和 `forceLoginOrgUUID`。Claude Code 仍然从有效负载中读取这两个密钥以进行启动凭证检查，因此在机器上保留 Anthropic 颁发的凭证的开发者会获得[管理员策略需要云网关登录](/docs/zh-CN/errors#administrator-policy-requires-a-cloud-gateway-sign-in)下描述的启动退出，即使他们已经登录。

<h2 id="related">
  相关
</h2>

* [Claude 应用网关概述](/docs/zh-CN/claude-apps-gateway)：快速入门和开发者连接
* [部署指南](/docs/zh-CN/claude-apps-gateway-deploy)：IdP 设置、容器镜像、Kubernetes 和 Cloud Run，以及操作
* [支出限制](/docs/zh-CN/claude-apps-gateway-spend-limits)：每开发者上限和 Admin API

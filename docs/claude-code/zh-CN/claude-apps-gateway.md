> ## Documentation Index
> Fetch the complete documentation index at: https://code.claude.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# Amazon Bedrock、Claude Platform on AWS、Google Cloud 和 Microsoft Foundry 的 Claude 应用网关

> 通过自托管网关在 Amazon Bedrock、Claude Platform on AWS、Google Cloud 或 Microsoft Foundry 上运行 Claude Code，支持 SSO 登录、按组模型访问和 OTLP 遥测。

<Note>
  Claude 应用网关专为必须或倾向于通过自己的云提供商路由推理的组织设计，例如满足[数据驻留](/docs/zh-CN/claude-apps-gateway-deploy#compliance-posture)要求。如果您没有此要求，并且想要访问其他功能，例如 SCIM 配置或 Claude Code 网页和移动版本，Claude Enterprise 可能更适合。请参阅[功能可用性](/docs/zh-CN/feature-availability)页面，了解所有部署方法的完整比较。
</Note>

Claude 应用网关是一个自托管服务，位于开发人员的 Claude Code 客户端和模型提供商之间。开发人员使用您的企业身份提供商 (IdP) 登录，而不是持有 API 密钥或云凭证。网关持有上游凭证，按 IdP 组强制执行模型访问和[托管设置](/docs/zh-CN/managed-settings)，并将使用情况遥测转发到您自己的可观测性堆栈。

它包含在 `claude` 二进制文件中，因此在笔记本电脑上运行 Claude Code 的同一可执行文件可以使用 `claude gateway --config gateway.yaml` 运行网关服务器。

本页涵盖：

* [为什么使用 Claude 应用网关](#why-claude-apps-gateway)，它相比自己运行的优势，以及何时其他解决方案更合适
* 一个[快速入门](#quickstart)，包含[前置条件](#prerequisites)，可将网关从零配置到已登录的开发人员
* [连接开发人员](#connect-developers)，包括通过托管设置设置网关 URL
* [可用性和限制](#availability-and-limitations)，涵盖哪些 Claude Code 功能可通过网关工作以及服务器支持什么

配套页面深入讲解。[配置参考](/docs/zh-CN/claude-apps-gateway-config)涵盖快速入门编写的 YAML 文件中的每个选项，[部署指南](/docs/zh-CN/claude-apps-gateway-deploy)涵盖每个 IdP 的设置、Kubernetes 和 Cloud Run 部署以及操作。

<h2 id="why-claude-apps-gateway">
  为什么使用 Claude apps gateway
</h2>

[网关概述](/docs/zh-CN/gateways)涵盖网关的功能以及为什么要运行它。Claude apps gateway 是 Anthropic 自己的网关，内置于 `claude` 二进制文件中，并与每个 Claude Code 版本一起测试，因此它转发 Claude Code 发送的标头和请求字段，无需操作员维护单独的允许列表。部署后，它为您提供：

* **凭证**：上游 API 密钥或云凭证仅存在于您的基础设施中。开发人员使用公司 SSO 进行身份验证并接收短期的持有者令牌，因此离职发生在您的 IdP 中。取消配置用户，其网关访问权限在会话生命周期内过期，默认为一小时。
* **访问控制**：您的 IdP 组映射到模型允许列表和[托管设置](/docs/zh-CN/managed-settings)策略。网关在服务器端强制执行模型访问，拒绝非授予模型的请求，并选择每个组的托管设置策略，CLI 在[托管设置层](/docs/zh-CN/settings#settings-precedence)应用该策略。不同的团队获得不同的模型、工具和权限，开发人员无法覆盖其策略锁定的内容。
* **设置交付**：网关本身将托管设置交付给已登录的客户端，取代来自 claude.ai 管理员控制台的[服务器管理设置](/docs/zh-CN/server-managed-settings)。
* **遥测**：每个配置的目标接收[OpenTelemetry Protocol (OTLP) 指标](/docs/zh-CN/monitoring-usage)，默认包含令牌计数、模型、用户身份和延迟，日志和跟踪作为按目标的选择加入。
* **上游路由**：客户端向网关发送 Anthropic Messages API，网关为每个上游进行转换，无论是 Amazon Bedrock、[AWS 上的 Claude Platform](/docs/zh-CN/claude-platform-on-aws)、Google Cloud 的 Agent Platform、Microsoft Foundry 还是 Anthropic API，并在它们之间进行故障转移。您可以更改区域、提供商或故障转移顺序，而开发人员无需注意或重新配置。

<Frame>
  <img src="https://mintcdn.com/claude-code/VbyXug8hBU9UK6oT/images/claude-gateway-architecture.svg?fit=max&auto=format&n=VbyXug8hBU9UK6oT&q=85&s=9e4f1190fc56718144190a3db61c63af" alt="显示 Claude Code 客户端和 Claude Desktop 的 Chat、Cowork 和 Code 选项卡通过 HTTPS 和持有者令牌连接到基础设施内自托管的 Claude apps gateway 的图表，网关针对您的 IdP 对用户进行签名，在 PostgreSQL 中存储身份验证状态，将遥测转发到您的 OTLP 收集器，并将推理转发到 Amazon Bedrock、AWS 上的 Claude Platform、Google Cloud、Microsoft Foundry 或 Anthropic API" width="760" height="320" data-path="images/claude-gateway-architecture.svg" />
</Frame>

<Note>
  网关自己的数据平面不会向 Anthropic 基础设施发送任何内容，除非 Anthropic API 是配置的上游。您控制遥测、审计日志、托管设置和开发人员的 IdP 身份的去向，网关不会将它们中的任何一个发送给 Anthropic。对于其余流量，CLI 进程可以发送什么以及如何关闭它，请参阅[合规态势](/docs/zh-CN/claude-apps-gateway-deploy#compliance-posture)。
</Note>

有关哪些 Claude Code 功能通过网关工作以及服务器本身支持什么，请参阅下面的[可用性和限制](#availability-and-limitations)。有关成本、绕过、运行多个网关和无服务器平台等决策，请参阅[部署指南](/docs/zh-CN/claude-apps-gateway-deploy#deployment)。

<h3 id="other-gateway-implementations">
  其他网关实现
</h3>

如果您已经运行满足您需求的 LLM 网关或 API 网关，请继续使用它；[其他 LLM 网关](/docs/zh-CN/llm-gateway)涵盖针对它配置 Claude Code。

[网关兼容性指南](/docs/zh-CN/llm-gateway-protocol)记录了 Claude Code 期望从任何网关获得的内容：它调用的端点、要转发的标头和正文字段，以及删除它们时停止工作的内容。运行中的 Claude apps gateway 在 `GET /protocol` 处提供其自己的协议参考，该参考描述了它向 Claude Code 客户端公开的端点：SSO 登录、推理、托管设置交付、模型发现和遥测。使用 `curl https://claude-gateway.internal.example.com/protocol` 从任何已部署的网关（例如下面[快速入门](#quickstart)生成的网关）获取它。协议的重大更改会提前宣布，但不保证无限期的向后兼容性。

<h2 id="quickstart">
  快速入门
</h2>

本快速入门演示最小路径：在您的 IdP 中注册 OAuth 客户端，编写 `gateway.yaml`，使用 Docker Compose 运行网关和 Postgres，并端到端验证登录。它使用 Amazon Bedrock 上游；Claude Platform on AWS、Google Cloud 的 Agent Platform、Microsoft Foundry 和 Anthropic API 同样受支持，只需交换[配置参考](/docs/zh-CN/claude-apps-gateway-config#upstreams)中所示的 `upstreams` 块。最后，您有一个开发人员可以 `/login` 的网关。

<Note>
  **在您的私有网络上部署。** Claude Code 仅连接到地址为私有的网关。这是一个安全防护，因为受信任的网关可以推送在开发人员机器上运行命令的设置。将网关放在内部负载均衡器或 VPN 后面，并给它一个仅解析为私有 IP 的主机名。如果您的内部网络使用您的组织拥有的公共 IPv4 空间编号，请参阅[允许网关在您拥有的公共地址空间上](#allow-a-gateway-on-public-address-space-you-own)。
</Note>

<h3 id="prerequisites">
  前置条件
</h3>

在开始之前，请准备好以下内容：

| 您需要 | 详情 |
| - | - |
| Claude Code v2.1.195 或更高版本 | `claude gateway` 子命令和网关登录流在 v2.1.195 中发布。早期的公开版本不包含它们。运行网关服务器的机器和每个开发人员的机器都必须是 v2.1.195 或更高版本；运行 `claude update` 获取最新版本。[Claude Platform on AWS 上游](/docs/zh-CN/claude-apps-gateway-config#claude-platform-on-aws)在网关服务器上需要 Claude Code v2.1.198 或更高版本。 |
| OpenID Connect (OIDC) 身份提供商 | Okta、Microsoft Entra ID、Google Workspace、Keycloak 或 Dex，或任何其他符合 OIDC 的 IdP，如 PingFederate。网关针对它运行标准 OIDC 发现和授权代码流。不支持 SAML 和 LDAP。 |
| PostgreSQL 14 或更高版本 | 支持设备登录流，其中浏览器回调写入，轮询 CLI 读取，加上速率限制计数器。任何托管 Postgres 都可以，包括最小层级。在没有配置支出限制的情况下，网关存储几 KB 的短期身份验证状态；使用[支出限制](/docs/zh-CN/claude-apps-gateway-spend-limits)，它还保存应备份的持久支出、审计和身份表。建议通过 `?sslmode=require` 使用 TLS。 |
| 模型上游 | Amazon Bedrock 凭证、Claude Platform on AWS 凭证、Google Cloud 凭证、Microsoft Foundry 资源或 Anthropic API 密钥。支持多个上游和故障转移。 |
| HTTPS | 网关必须可从开发人员笔记本电脑和用于登录的任何浏览器通过 `https://` 访问；网关在同一侦听器上提供设备验证页面。通过 `listen.tls` 提供 TLS 证书，或在 TLS 终止入口后运行并设置 `listen.public_url` 为外部源，两种情况都是如此。纯 `http://` 源仅在网关主机是环回时接受：`localhost`、`127.0.0.1` 或 `::1`。 |
| 私有网络地址 | 在 `/login` 处，Claude Code 要求网关的主机名或 IP 地址仅解析为私有地址：RFC 1918、链路本地、CGNAT `100.64.0.0/10`、IPv6 ULA `fc00::/7` 或环回。对于您托管的网关，任何公共地址都被拒绝；请参阅部署指南中的[威胁模型](/docs/zh-CN/claude-apps-gateway-deploy#threat-model-summary)。如果开发人员机器通过公司代理路由 HTTPS，登录还要求代理主机解析为私有地址；如果不是，将网关主机添加到 `NO_PROXY`，以便 CLI 直接连接。如果您的内部网络使用您的组织拥有的公共 IPv4 空间编号，[声明这些块](#allow-a-gateway-on-public-address-space-you-own)，以便 `/login` 接受那里的网关。 |
| Linux 运行时 | 网关服务器仅在本机 Linux 二进制文件上运行。macOS 适用于本地开发。Windows 不支持作为服务器平台。 |

<h3 id="steps">
  步骤
</h3>

<Steps>
  <Step title="在您的 IdP 中注册 OAuth 客户端">
    首先决定网关的主机名，因为重定向 URI 必须与其匹配。创建新的 OIDC Web 应用程序并将重定向 URI 设置为 `https://claude-gateway.<your-domain>/oauth/callback`，其中主机是您在步骤 3 中设置为 [`listen.public_url`](/docs/zh-CN/claude-apps-gateway-config#listen) 的相同值。记下 `client_id` 和 `client_secret`。每个 IdP 的说明在[身份提供商设置](/docs/zh-CN/claude-apps-gateway-deploy#identity-provider-setup)中。
  </Step>

  <Step title="配置 PostgreSQL 数据库">
    任何 Postgres 14 或更高版本都可以，包括最小的托管层级。网关在启动时运行自己的架构迁移，因此数据库角色需要创建和修改表的权限；请参阅 [`store`](/docs/zh-CN/claude-apps-gateway-config#store)。
  </Step>

  <Step title="编写 gateway.yaml">
    通过 `${ENV_VAR}` 扩展读取机密，因此文件本身可以存在于版本控制中。使用在您的网络上解析为私有 IP 的 `public_url` 主机名，因为 `/login` 拒绝公共地址。最小配置有五个部分，其他所有字段都有默认值：

    ```yaml gateway.yaml theme={null}
    listen:
      host: 0.0.0.0
      port: 8080
      # 除非主机是环回地址，否则必需。用于 IdP
      # redirect_uri 和发现文档。
      public_url: https://claude-gateway.internal.example.com

    oidc:
      issuer: https://login.example.com        # 必须提供 /.well-known/openid-configuration
      client_id: 0oa1example2
      client_secret: ${OIDC_CLIENT_SECRET}
      allowed_email_domains: [example.com]        # 拒绝组织外的 id_tokens
      userinfo_fallback: true                  # 对于 id_token 省略 email/groups 的 IdP；否则无害

    session:
      jwt_secret: ${GATEWAY_JWT_SECRET}        # openssl rand -base64 32
      ttl_hours: 1                             # 也限制 IdP 取消配置时的撤销延迟

    store:
      postgres_url: ${GATEWAY_POSTGRES_URL}    # 为托管 Postgres 添加 ?sslmode=require

    upstreams:
      - provider: bedrock
        region: us-east-1
        auth: {} # 空：AWS 默认凭证链
    # (IRSA, EC2/ECS task role, env vars, ~/.aws)

    # 模型会自动按上游转换。内置目录
    # 将 claude-opus-4-8 映射到 us.anthropic.claude-opus-4-8 等，
    # 对于每个 Bedrock 支持的 Claude 模型。设置为 false 并添加 `models:` 列表以
    # 仅公开特定模型。
    auto_include_builtin_models: true
    ```

    此配置足以使用默认 Amazon Bedrock 模型目录进行工作登录循环。运行后，通过 [`managed.policies`](/docs/zh-CN/claude-apps-gateway-config#managed) 添加按组 RBAC 和托管设置，通过 [`telemetry`](/docs/zh-CN/claude-apps-gateway-config#telemetry) 添加遥测扇出，以及通过 [`models`](/docs/zh-CN/claude-apps-gateway-config#models) 添加多上游故障转移、预配置吞吐量 ARN 或非美国地区。

    <Note>
      Amazon Bedrock 上游需要一个 AWS 主体，具有对 `inference-profile/us.anthropic.*` ARN 和底层 `foundation-model/anthropic.*` ARN 的 `bedrock:InvokeModel` 和 `bedrock:InvokeModelWithResponseStream`，以及在 Bedrock 控制台的模型目录中为该账户提交的 Anthropic 一次性用例表单。使用 EKS 上的 IRSA、ECS 任务角色或 EC2 实例配置文件提供凭证，而不是静态密钥。[`upstreams` 参考](/docs/zh-CN/claude-apps-gateway-config#upstreams)具有完整的 IAM 详情、跨云凭证矩阵以及其他提供商的 `auth` 块。
    </Note>
  </Step>

  <Step title="运行它">
    围绕满足[镜像要求](/docs/zh-CN/claude-apps-gateway-deploy#container-image)的 `claude` 二进制文件构建容器镜像，然后将其与 Postgres 一起运行。Compose 文件将镜像引用为 `registry.example.com/claude-gateway:2.1.198`；替换为您自己的注册表和镜像标签：

    ```yaml docker-compose.yaml theme={null}
    services:
      gateway:
        image: registry.example.com/claude-gateway:2.1.198
        ports: ["8080:8080"]
        volumes: ["./gateway.yaml:/etc/claude/gateway.yaml:ro"]
        environment:
          OIDC_CLIENT_SECRET: ${OIDC_CLIENT_SECRET}
          GATEWAY_JWT_SECRET: ${GATEWAY_JWT_SECRET}
          GATEWAY_POSTGRES_URL: postgres://gw:pw@postgres/gateway
          # AWS 凭证：在生产中，省略这些并使用实例
          # 角色。对于本地 Compose 测试，传递您自己的：
          AWS_ACCESS_KEY_ID: ${AWS_ACCESS_KEY_ID}
          AWS_SECRET_ACCESS_KEY: ${AWS_SECRET_ACCESS_KEY}
          AWS_SESSION_TOKEN: ${AWS_SESSION_TOKEN}
        depends_on:
          postgres:
            condition: service_healthy
      postgres:
        image: postgres:16-alpine
        environment: { POSTGRES_USER: gw, POSTGRES_PASSWORD: pw, POSTGRES_DB: gateway }
        healthcheck:
          test: ["CMD-SHELL", "pg_isready -U gw"]
          interval: 5s
        volumes: ["pgdata:/var/lib/postgresql/data"]
    volumes: { pgdata: }
    ```

    网关是一个单一的 Linux 二进制文件，读取配置，连接到 Postgres 并应用其架构迁移，针对您的 IdP 运行 OIDC 发现，构建上游客户端，并开始侦听。启动对配置、Postgres 连接、OIDC 发现和上游客户端构造是失败关闭的。如果其中任何一个无法访问或配置错误，网关会以错误退出，而不是以降级状态提供流量。

    成功启动不会验证推理路径，因为 Amazon Bedrock 和 Google Cloud 的 Agent Platform 实例凭证在第一个请求时解析，而不是在启动时。

    监视 stderr 以获取启动序列。日志行使用格式 `[gateway] <timestamp> <level> <message>`，审计事件是带有 `evt` 字段的单行 JSON，启动横幅（下面省略）在迁移和侦听行之间打印。新数据库每个架构迁移打印一行 `migration N applied`；已迁移的数据库不打印任何内容。您应该按顺序看到：

    ```text theme={null}
    {"ts":"2026-06-10T17:03:21.114Z","evt":"config.load","path":"/etc/claude/gateway.yaml","sha256":"…"}
    [gateway] 2026-06-10T17:03:21.395Z info waiting for migration lock (another replica may be migrating; check pg_locks for key 6775156 if this persists)
    [gateway] 2026-06-10T17:03:21.408Z info migration 1 applied
    …
    [gateway] 2026-06-10T17:03:21.431Z info migration 6 applied
    [gateway] 2026-06-10T17:03:21.512Z info claude gateway listening on http://0.0.0.0:8080
    ```

    网关还会记录一个警告，`access_control.allow_cidrs` 为空。这在这里是预期的，因为在您设置允许列表之前，没有任何东西限制网关提供的客户端地址。[`access_control` 参考](/docs/zh-CN/claude-apps-gateway-config#http-tuning)有推荐的范围。

    如果启动在 `claude gateway listening on` 行之前退出，stderr 的最后一行命名问题：

    * 无法访问的 Postgres
    * 没有 DDL 权限的 Postgres 角色
    * 无法访问或无效的 OIDC 发现文档
    * 配置架构违规，带有违规字段路径

    修复它并重新启动。

    如果您已经有 TLS 终止入口，请跳过 Compose 并直接使用 `claude gateway --config gateway.yaml` 运行二进制文件。将 `public_url` 设置为入口源，并将 `listen` 绑定到环回或集群内部地址。
  </Step>

  <Step title="验证身份验证表面">
    三个检查确认网关可以在将其交给开发人员之前对真实用户进行身份验证。

    示例使用网关的公共 URL；对于没有入口的本地 Compose 设置，在前两个检查中替换 `http://localhost:8080`。第三个检查打开 `verification_uri_complete`，它从 `public_url` 构建，因此对于本地 Compose，在 `gateway.yaml` 中设置 `public_url: http://localhost:8080`，并在步骤 1 的 OAuth 客户端上添加 `http://localhost:8080/oauth/callback` 作为第二个重定向 URI，因为网关从 `public_url` 构建 IdP `redirect_uri`。验证链接然后在您的本地浏览器中打开。

    在 Windows PowerShell 中，运行 `curl.exe`；裸 `curl` 是 `Invoke-WebRequest` 的别名，拒绝这些标志。

    首先，获取发现文档，确认网关已启动，配置有效，所有启动检查都通过：

    ```bash theme={null}
    curl -s https://claude-gateway.internal.example.com/.well-known/oauth-authorization-server | jq
    ```

    ```json theme={null}
    {
      "issuer": "https://claude-gateway.internal.example.com",
      "device_authorization_endpoint": "…/oauth/device_authorization",
      "token_endpoint": "…/oauth/token",
      "grant_types_supported": ["urn:ietf:params:oauth:grant-type:device_code", "refresh_token"]
    }
    ```

    响应包括其他字段，如 `response_types_supported` 和 `scopes_supported`。

    其次，请求设备授权，确认设备登录流工作且 Postgres 可访问且可写：

    ```bash theme={null}
    curl -s -X POST https://claude-gateway.internal.example.com/oauth/device_authorization | jq
    ```

    ```json theme={null}
    {
      "device_code": "…",
      "user_code": "WDJB-MJHT",
      "verification_uri": "https://claude-gateway.internal.example.com/device",
      "verification_uri_complete": "https://claude-gateway.internal.example.com/device?user_code=WDJB-MJHT",
      "expires_in": 600,
      "interval": 5
    }
    ```

    第三，通过在浏览器中打开 `verification_uri_complete` 并确认代码来测试浏览器部分。您应该被重定向到您的 IdP 的登录页面，登录后，返回网关并显示已登录确认。

    使用第一个失败的检查来定位问题：

    * **第一个检查失败**：启动未完成；检查 stderr
    * **第二个检查失败**：Postgres 无法从网关访问或角色无法写入；检查连接字符串和授予
    * **第三个检查未到达 IdP**：检查 IdP 的重定向 URI 是否与 `https://<gateway>/oauth/callback` 完全匹配
    * **第三个检查到达 IdP 但以错误反弹**：读取网关的审计日志，它记录每个身份验证拒绝及其原因，例如 `email domain not allowed`
  </Step>

  <Step title="登录开发人员">
    最后一步发生在开发人员机器上，而不是服务器上。在该机器的[托管设置文件](/docs/zh-CN/managed-settings#delivery-mechanisms)中将 `forceLoginMethod` 设置为 `"gateway"` 并将 `forceLoginGatewayUrl` 设置为您的网关的 `public_url`，然后运行 `/login`，在**Cloud gateway** 屏幕上按 Enter，并完成浏览器登录。下面的[设置网关 URL](#set-the-gateway-url)涵盖大规模分发两个密钥。
  </Step>
</Steps>

<h2 id="connect-developers">
  连接开发人员
</h2>

开发人员从自己的笔记本电脑使用一次浏览器登录进行连接，使用他们的公司工作账户。他们不需要 claude.ai 账户、API 密钥或订阅，因为对模型的请求通过网关使用组织的上游凭证。连接由您通过 MDM 推送的[客户端托管设置](/docs/zh-CN/claude-apps-gateway-config#client-side-managed-settings)驱动，因此开发人员端没有手动设置；本部分涵盖管理员配置的内容。

CLI 在首次连接时对网关的 TLS 叶证书进行指纹识别，并按主机名固定它。它在登录期间、静默会话刷新期间和托管设置获取期间再次检查该固定，而推理请求使用标准 TLS 验证而不使用固定。通过 HTTPS 代理路由的请求跳过固定检查，因此将网关主机添加到 `NO_PROXY` 以保持它们直接连接。

发布预期的 SHA-256 指纹以及网关 URL，以便开发人员有东西可以比较。`/login` 提示显示指纹的前 16 个字符作为小写十六进制，无冒号。要从证书文件以该形式打印完整指纹，请运行：

```bash theme={null}
openssl x509 -noout -fingerprint -sha256 -in cert.pem | cut -d= -f2 | tr -d : | tr 'A-F' 'a-f'
```

当证书轮换时，每个开发人员都会再次看到信任提示，因此将轮换视为计划事件并重新发布指纹。如果您的网关策略包含[需要批准的设置](/docs/zh-CN/server-managed-settings#security-approval-dialogs)，开发人员在接受新证书后也会再次看到该批准对话框，因为 Claude Code 将[批准记忆](/docs/zh-CN/server-managed-settings#approval-memory)关键到固定的证书。

网关可以在其令牌响应中返回可选的 `email` 字段，以命名登录使用的账户。当它这样做时，开发人员在 Claude Code 保存凭证之前确认账户。确认登录后，`/status` 显示账户。

确认需要开发人员机器上的 Claude Code v2.1.275 或更高版本；低于该版本的客户端忽略该字段。`claude` 二进制文件中的网关服务器不返回该字段，因此其登录完成时没有确认。

开发人员登录后，[模型选择器](/docs/zh-CN/model-config)显示其 `availableModels` 允许列表中的模型。托管设置在启动时应用并每小时刷新一次，遥测路由到您的收集器。

会话在 `ttl_hours` 过期前静默刷新。当 IdP 取消配置后刷新失败时，Claude Code 会提示开发人员重新登录。

<h3 id="set-the-gateway-url">
  设置网关 URL
</h3>

三个密钥进入您通过 MDM 或直接在磁盘上部署的每个操作系统[托管设置文件](/docs/zh-CN/managed-settings#delivery-mechanisms)。`forceLoginMethod` 和 `forceLoginGatewayUrl` 在**Cloud gateway** 屏幕上直接打开 `/login`，URL 已填入，`parentSettingsBehavior: "merge"` 让 Claude Desktop 将网关的出口允许列表传递给它启动的 Claude Code 会话，在[将策略传递给 Claude Desktop 会话](#deliver-policy-to-claude-desktop-sessions)中解释：

```json theme={null}
{
  "forceLoginMethod": "gateway",
  "forceLoginGatewayUrl": "https://claude-gateway.internal.example.com",
  "parentSettingsBehavior": "merge"
}
```

开发人员按 Enter 连接。[首次连接 TLS 指纹提示](#connect-developers)仍然出现。文件在机器上后，未完成网关登录的开发人员会看到[管理员策略需要 Cloud 网关登录](/docs/zh-CN/errors#administrator-policy-requires-a-cloud-gateway-sign-in)下描述的消息之一。通过环境变量（如 `CLAUDE_CODE_USE_BEDROCK`）选择云提供商的开发人员不需要网关登录。

开发人员无法手动设置此项。登录选择器中没有网关选项，`forceLoginGatewayUrl` 在开发人员自己的设置文件中被忽略。单独的 `forceLoginMethod`，没有 URL，将开发人员留在"联系您的 IT 管理员"消息处。登录密钥属于您推送到机器的文件中，而不是网关的 `managed.policies[].cli` 块中，该块仅到达已连接的客户端。

<h3 id="allow-a-gateway-on-public-address-space-you-own">
  在您拥有的公共地址空间上允许网关
</h3>

某些组织从他们拥有的公共 IPv4 块对其内部网络进行编号，例如运营商自己的地址空间或遗留的 `/8`，因此他们的网关不能有私有地址。在 `gatewayInternalNetworks` 托管设置中列出这些块。当开发人员的机器从同一块内的地址连接到它时，`/login` 然后接受列出块内的网关。这需要开发人员机器上的 Claude Code v2.1.268 或更高版本；早期版本忽略该密钥并应用私有地址规则。

<Warning>
  `gatewayInternalNetworks` 用于恰好从公共地址空间编号的内部网络。它不会使将网关暴露到互联网变得安全：受信任的网关可以推送在开发人员机器上运行命令的设置。

  使用您的防火墙或负载均衡器规则将网关保持在网络外部无法访问。将网关的 [`access_control.allow_cidrs`](/docs/zh-CN/claude-apps-gateway-config#http-tuning) 设置为您在此处声明的相同块，以便网关本身拒绝来自其他任何地方的客户端。在负载均衡器或入口后面，也将 `listen.trusted_proxies` 设置为该前端，因为网关否则会针对前端自己的地址而不是开发人员的地址匹配 `allow_cidrs`。
</Warning>

将密钥添加到与登录密钥相同的托管设置源：托管设置文件、MDM 配置文件或注册表策略。Claude Code 在用户、项目和服务器托管设置中忽略它。

此示例声明一个块。将 `203.0.113.0/24` 替换为您自己的块。它是文档范围，Claude Code 拒绝这些。

```json theme={null}
{
  "gatewayInternalNetworks": ["203.0.113.0/24"]
}
```

Claude Code 在 `/login` 处验证列表，然后再联系任何网关：

* 每个条目是一个 IPv4 块，写成其第一个地址和从 `/8` 到 `/32` 的前缀。
* 列表最多包含四个块，没有两个重叠。
* 没有块与私有地址空间重叠：`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`、`127.0.0.0/8`、`169.254.0.0/16` 和 `100.64.0.0/10`。`/login` 已经在没有此密钥的情况下接受那里的网关。
* 没有块与从不是组织网络的空间重叠：`198.18.0.0/15` 和 `192.0.0.0/24`，VPN 和 NAT64 客户端将其作为本地地址；文档范围 `192.0.2.0/24`、`198.51.100.0/24` 和 `203.0.113.0/24`；以及保留范围 `0.0.0.0/8`、`192.88.99.0/24` 和多播 `224.0.0.0/4`。您可以声明 `240.0.0.0/4` 内的块，某些大型网络将其用作内部单播空间。

来自 `managed-settings.json` 及其 `managed-settings.d/` 插入文件的块合并为一个列表，这些限制适用于合并列表。要缩小块，请替换其条目而不是在插入中添加第二个重叠的；`/login` 拒绝重叠。

如果条目违反规则，或值不是字符串列表，Claude Code 拒绝该机器上的每个新网关登录并在消息中命名问题。登录到私有地址上的网关也失败，现有登录继续工作。在部署前在一台机器上尝试该值。Claude Code 还在[它报告的无效托管设置](/docs/zh-CN/managed-settings#keys-that-fail-closed)中列出错误类型的值。

使用有效列表，`/login` 对地址在列出块内的网关应用三个检查：

* 网关主机名解析到的每个地址都在该块内。Claude Code 拒绝也在块外有记录的名称，包括私有和 IPv6 地址。
* 开发人员的机器从同一块内连接。Claude Code 拒绝 NAT 后面、容器或 WSL2 内或 VPN 上的机器，其地址池位于块外，并命名机器连接的地址。
* 连接是直接的。如果 `HTTPS_PROXY` 适用于网关主机，`/login` 拒绝并命名要添加的 `NO_PROXY` 条目。

当所有三个通过时，[信任提示](#connect-developers)添加一行命名机器的地址、网关的地址和包含两者的声明块。

该密钥对其他网关不改变任何内容：登录到私有地址上的网关像以前一样工作，登录到每个列出块外的公共地址上的网关像以前一样被拒绝。

声明的块缩小了谁可以登录但不证明机器在哪里，因此仅声明您的组织控制的地址空间。与其他租户共享的块，例如云提供商的公共范围，让其中的任何人通过相同的检查。

<h3 id="deliver-policy-to-claude-desktop-sessions">
  将策略传递给 Claude Desktop 会话
</h3>

Claude Desktop 在嵌入式 Claude Code 会话上运行其 Cowork 和 Code 选项卡，以及启用时的 Chat 选项卡，并通过网关发送其模型请求。它将策略传递给每个会话，从网关在 `/user/bootstrap` 处提供的配置构建：模型允许列表、禁用的工具和从匹配策略的 `cli` 块派生的出口允许列表，加上[`desktop` 覆盖](/docs/zh-CN/claude-apps-gateway-config#claude-desktop-overlay)。

其他 `cli` 密钥，例如 hooks、`env` 和作用域权限规则（如 `Bash(npm *)`），仅到达通过 `/login` 登录的客户端。Claude Desktop 从其自己的托管配置读取网关 URL，并使用其自己的流程登录，与[设置网关 URL](#set-the-gateway-url)中的 `forceLoginMethod` 和 `forceLoginGatewayUrl` 密钥分开。

由启动过程传递的设置是父设置。Claude Code 在任何具有管理员部署的托管源的机器上忽略父设置，除非[传递策略的源](/docs/zh-CN/managed-settings#which-managed-source-claude-code-uses)设置 `parentSettingsBehavior: "merge"`。

<h4 id="which-machines-need-the-opt-in">
  哪些机器需要选择加入
</h4>

仅运行 Claude Desktop 的机器需要它。Claude Desktop 将模型列表和禁用工具列表应用于嵌入式会话本身，但出口允许列表仅作为父设置到达它们，形式为 `WebFetch` 域规则和沙箱网络规则。没有选择加入，这些会话运行时没有出口限制，没有任何警告。网关仍然拒绝策略未授予的模型的推理请求。

插件市场允许列表也仅作为父设置到达嵌入式会话。当您在 Claude Desktop 的托管配置中关闭用户添加的插件市场时，Claude Desktop 2.16120.0 或更高版本隐藏您的组织未配置的市场，并拒绝从它们安装。要停止嵌入式会话加载已从这些市场安装的插件，它将 `strictKnownMarketplaces` 列表作为父设置发送给它们。没有选择加入，Claude Code 忽略该列表，这些插件继续加载。

开发人员通过 `/login` 登录的机器不需要它；每个 Claude Code 会话从网关获取其策略。

其[`policyHelper`](/docs/zh-CN/settings-reference#policyhelper)提供托管设置的舰队无法使用它：Claude Code 从不在这些舰队上合并父设置，因为它仅从助手的输出读取托管设置。

<h4 id="set-the-opt-in">
  设置选择加入
</h4>

从[设置网关 URL](#set-the-gateway-url)部署托管设置片段，将其镜像到任何优先于文件的客户端源，然后验证。

<Steps>
  <Step title="在托管设置文件中部署选择加入">
    上面的[片段](#set-the-gateway-url)已包含 `parentSettingsBehavior: "merge"`，因此您推送到机器的文件携带它。
  </Step>

  <Step title="将片段镜像到任何优先于文件的源">
    Claude Code 仅从[选定的源](/docs/zh-CN/managed-settings#which-managed-source-claude-code-uses)读取 `parentSettingsBehavior`。向源添加任何策略密钥可以使该源成为选定的源，因此在客户端源中，镜像整个片段而不仅仅是 `parentSettingsBehavior`。[客户端托管设置](/docs/zh-CN/claude-apps-gateway-config#client-side-managed-settings)涵盖通过组策略或配置文件传递策略的舰队。macOS 上的托管首选项 plist 或 Windows 上的 HKLM 策略优先于 `managed-settings.json` 文件，网关自己的远程托管设置优先于两者，因此在登录到网关的机器上，也在网关策略的 [`cli` 块](/docs/zh-CN/claude-apps-gateway-config#managed)中设置 `parentSettingsBehavior`。
  </Step>

  <Step title="检查选定的源">
    在仅运行 Claude Desktop 的机器上，调用 Agent SDK 的 [`resolveSettings()`](/docs/zh-CN/agent-sdk/typescript#resolvesettings) 并在其 `sources` 列表中的 `managed` 条目上读取 `policyOrigin`。该值命名选定的客户端源，`plist`、`hklm` 或 `file`，这是必须携带片段的源。Claude Desktop 的嵌入式会话不获取网关策略，因此网关的 `cli` 块从不计为它们的选定源。
  </Step>
</Steps>

<h3 id="restrict-parent-settings">
  限制父设置
</h3>

一旦您部署 `parentSettingsBehavior: "merge"`，任何启动 Claude Code 的主机进程都可以提供父设置，不仅是 Claude Desktop，还有 Agent SDK 应用程序或 IDE 扩展。

Claude Code 根据限制性密钥的允许列表过滤父设置，但某些允许的密钥可以授予访问权限而不是限制它。除非您设置 `allowManaged*Only` 锁，主机提供的权限允许规则和沙箱允许列表仍然适用。您的策略的拒绝和询问规则无论如何都保持有效；[它们在任何允许规则之前被评估](/docs/zh-CN/permissions#manage-permissions)。

Claude Code 以剥离形式转发父提供的 [`sandbox.credentials`](/docs/zh-CN/settings-reference#sandbox-credentials) 条目：

* **`deny` 条目**：仅使用其 `path` 或 `name` 和模式转发。
* **具有 [`mode: mask`](/docs/zh-CN/sandboxing#mask-credential-files) 的文件条目**：转发为仅哨兵，作为整个文件掩码，其 `injectHosts` 是空列表，因此代理从不在任何平台上用真实值替换父提供的条目。所有结构化掩码字段也被删除，因此父提供的提取模式无法替代另一个源为同一路径设置的更严格掩码。
* **具有 `mode: mask` 的 `envVars` 条目**：不转发。`deny` 是父通道可以通过 `envVars` 条目表达的唯一限制。
* **[`awsPairs` 和 `sigv4`](/docs/zh-CN/sandboxing#re-sign-aws-requests)**：仅转发限制。从 `sigv4`，仅保留 `deny` 值，定义 `sigv4` 块的父将所有三种请求形式 `streaming`、`presigned` 和 `sigv4a` 固定到 `deny`。`awsPairs` 对从不以可以重新签名的形式转发；命名常规 AWS 变量之一的对被替换为保持 `AWS_ACCESS_KEY_ID`、`AWS_SECRET_ACCESS_KEY` 和 `AWS_SESSION_TOKEN` 自动配对被抑制的惯性条目。

<h4 id="deploy-the-locks">
  部署锁
</h4>

要将父设置保持尽可能接近仅限制，将所有五个 `allowManaged*Only` 锁和它们管理的允许列表添加到与合并选择加入相同的源：

```json theme={null}
{
  "forceLoginMethod": "gateway",
  "forceLoginGatewayUrl": "https://claude-gateway.internal.example.com",
  "parentSettingsBehavior": "merge",
  "allowManagedPermissionRulesOnly": true,
  "allowManagedMcpServersOnly": true,
  "allowManagedHooksOnly": true,
  "allowedMcpServers": [{ "serverUrl": "https://mcp.internal.example.com/*" }],
  "sandbox": {
    "network": {
      "allowManagedDomainsOnly": true,
      "allowedDomains": ["github.com", "*.npmjs.org"]
    },
    "filesystem": {
      "allowManagedReadPathsOnly": true,
      "denyRead": ["~/"],
      "allowRead": ["~/projects"]
    }
  }
}
```

操作系统策略（例如 HKLM 注册表策略或托管首选项 plist）优先于此文件，因此通过它而不是文件传递整个片段。网关的远程托管设置优先于操作系统策略和文件源，但仅到达已连接的客户端。将锁、允许列表和合并选择加入镜像到策略的 [`cli` 块](/docs/zh-CN/claude-apps-gateway-config#managed)中，并保持此文件部署，因为从不连接的机器（包括仅运行 Claude Desktop 的机器）仅从文件获取其策略。

<h4 id="lock-behavior-across-sources">
  跨源的锁行为
</h4>

设置一个锁不会限制其他锁；每个密钥都在[设置参考](/docs/zh-CN/settings-reference#all-settings)中记录。

从赢家下方的管理员源，两个沙箱锁仍然适用，`allowManagedPermissionRulesOnly` 仍然阻止父提供的允许规则和 `additionalDirectories`。在 Claude Code v2.1.273 或更高版本上，MCP 服务器锁也从赢家下方的源应用，当它打开时，托管 `allowedMcpServers` 列表来自设置一个的最高优先级管理员源。

hooks 锁和 `allowManagedPermissionRulesOnly` 对开发人员自己规则的影响默认需要赢家源；在[Claude Code 如何组合托管源](/docs/zh-CN/managed-settings#how-claude-code-combines-managed-sources)中的 `managedSourcesBehavior` 合并选择加入下，Claude Code 应用任何源为每个锁设置的最严格值。在 [`policyHelper`](/docs/zh-CN/settings-reference#policyhelper) 舰队上，锁仅从助手的输出读取。

每个锁使 Claude Code 忽略开发人员对该设置的自己条目，因此在锁旁边包含您组织的允许列表：

* **网络域**：使用空托管域列表锁定会阻止所有沙箱出站流量。
* **MCP 服务器**：使用任何管理员源或父提供的设置中都没有 `allowedMcpServers` 的锁会加载 `deniedMcpServers` 不阻止的每个服务器。
* **读取路径**：`allowRead` 条目仅重新允许 `denyRead` 区域内的路径，因此将它们与托管 `denyRead` 配对。

<h4 id="settings-the-locks-don’t-cover">
  锁不涵盖的设置
</h4>

即使设置了所有五个锁，六个父提供的设置也会通过过滤器。在默认的先赢设置下，阻止父设置的管理员值是最高优先级管理员源中的值，除了 `allowedMcpServers` 当[MCP 服务器锁](#lock-behavior-across-sources)打开时。在 `managedSourcesBehavior` 合并选择加入下，[Claude Code 如何组合托管源](/docs/zh-CN/managed-settings#how-claude-code-combines-managed-sources)说明哪个源的值改为适用。

* **`forceLoginOrgUUID`**：当最高优先级管理员源未设置组织 UUID 时，Claude Code 尊重父提供的值。网关登录不检查此密钥。最高优先级管理员源中的组织 UUID 阻止父的值，是 Claude Code 强制执行的值。
* **`allowedMcpServers`**：当最高优先级管理员源未设置允许列表时，Claude Code 尊重父提供的允许列表，`allowManagedMcpServersOnly` 不阻止它，因为锁强制执行任何赢家列表作为托管值，包括当最高优先级管理员源未设置时的父提供列表。最高优先级管理员源中的列表阻止父的并是 Claude Code 强制执行的列表，因此在那里设置 `allowedMcpServers`，在锁旁边。在 v2.1.223 之前，任何管理员源中任一密钥的值都阻止父的。
* **`availableModels`**：当赢家托管源未设置模型列表时，Claude Code 尊重父提供的模型列表。如果您的舰队限制模型，在赢家源中设置 `availableModels`。
* **`strictKnownMarketplaces`**：当赢家托管源未设置一个时，Claude Code 尊重父提供的插件市场允许列表。Claude Desktop 2.16120.0 或更高版本在其托管配置关闭用户添加的插件市场时发送一个。如果您的舰队限制市场，在赢家源中设置 `strictKnownMarketplaces`。需要 Claude Code v2.1.282 或更高版本。
* **`blockedMarketplaces`**：父提供的市场阻止列表通过并添加到任何托管源设置的阻止列表，因为阻止列表只能进一步限制。需要 Claude Code v2.1.282 或更高版本。
* **`strictPluginOnlyCustomization`**：此密钥无论任何锁都通过过滤器，它使 Claude Code 忽略开发人员的自己定制，包括保护性 hooks。没有锁阻止它。

<h3 id="connect-claude-desktop">
  连接 Claude Desktop
</h3>

[Claude Desktop](/docs/zh-CN/desktop)通过不同的 MDM 密钥连接到同一网关：在 Claude Desktop 的[托管配置](https://claude.com/docs/third-party/claude-desktop/configuration)中设置 `bootstrapUrl` 为 `<listen.public_url>/user/bootstrap`，并使用 `desktop` 密钥选择加入用户的策略。[Claude Desktop 覆盖](/docs/zh-CN/claude-apps-gateway-config#claude-desktop-overlay)涵盖两个部分。需要网关服务器上的 Claude Code v2.1.203 或更高版本。

Claude Desktop 通过网关的身份提供商使用相同的浏览器 SSO 步骤对开发人员进行签名，然后从网关而不是从 Anthropic 获取其配置。模型访问和策略遵循与 CLI 相同的每组规则。同时使用 CLI 和 Claude Desktop 的开发人员分别登录到每个；网关会话不在它们之间共享。

连接后，Claude Desktop 从每个启用的选项卡通过网关发送模型请求。它默认显示 Cowork 和 Code 选项卡。要同时打开 Chat 选项卡，在 Claude Desktop 的[托管配置](https://claude.com/docs/third-party/claude-desktop/configuration)中设置 `chatTabEnabled` 为 `true`，或在运行 Claude Code v2.1.227 或更高版本的网关上的策略的 [`desktop` 块](/docs/zh-CN/claude-apps-gateway-config#claude-desktop-overlay)中。

<h3 id="ci-pipelines-and-remote-machines">
  CI 管道和远程机器
</h3>

没有用于无人值守管道的服务令牌流。网关登录始终运行浏览器设备流，因此没有开发人员批准登录的 CI 作业无法进行身份验证；针对您的提供商直接配置这些。

开发人员登录后，该机器上的每个 Claude Code 会话都使用网关会话，包括非交互式 `claude -p` 运行和由 Agent SDK 启动的会话。Claude Code 将[网关策略](/docs/zh-CN/claude-apps-gateway-config#managed)应用于每个会话。

设备流将轮询 CLI 与批准浏览器分开，因此没有显示的远程开发框仍然有效：开发人员通过 SSH 在远程机器上运行 `/login`，并在笔记本电脑上的浏览器中打开验证链接。

<h3 id="whats-enforced-on-developers">
  对开发人员强制执行的内容
</h3>

这些保证适用于每个通过 `/login` 登录的会话。Claude Desktop 启动的嵌入式会话按[将策略传递给 Claude Desktop 会话](#deliver-policy-to-claude-desktop-sessions)中所述获取其策略，遥测项目说明其导出的去向。

* **模型访问**：对策略未授予的模型的请求返回 400，`/model` 选择器被过滤到策略的 `availableModels` 允许列表。在策略中设置 [`enforceAvailableModels: true`](/docs/zh-CN/model-config#default-model-behavior)，以便 Default 选项解析为 `availableModels` 内的模型，而不是 Claude Code 的内置默认值；没有它，Default 保持可选，如果该模型未被授予，则在请求时被拒绝。
* **遥测目标**：在通过 `/login` 登录的会话中，CLI 将其 OTLP/HTTP 导出发送到网关，而不是任何本地设置的 `OTEL_EXPORTER_OTLP_ENDPOINT`，除非策略[将您的收集器命名为端点](/docs/zh-CN/claude-apps-gateway-config#export-directly-to-your-collector)。网关将它接收的导出中继到 [`telemetry.forward_to`](/docs/zh-CN/claude-apps-gateway-config#telemetry) 中的目标。
  * 在[Claude Desktop 启动](#connect-claude-desktop)的嵌入式会话中，CLI 将其导出发送到配置的 `OTEL_EXPORTER_OTLP_ENDPOINT`。CLI 仅当该端点指向网关本身时才将网关会话令牌附加到这些导出。
  * 没有为信号配置目标时，网关接受并丢弃它。
  * 如果您已直接收集 Claude Code 遥测，将您的收集器添加为 `forward_to` 目标，或在策略中命名它以跳过中继。
* **凭证**：网关令牌是会话的唯一凭证。[Anthropic 配置文件](/docs/zh-CN/authentication#anthropic-profiles-and-federation-credentials)和任何早期的 claude.ai 登录在登录时被忽略，因此开发人员不需要首先从 claude.ai 注销。对于配置的 `ANTHROPIC_API_KEY`、`ANTHROPIC_AUTH_TOKEN` 或 `apiKeyHelper` 凭证，或早期 Claude Console 登录保存的 API 密钥，请参阅[管理员策略需要 Cloud 网关登录](/docs/zh-CN/errors#administrator-policy-requires-a-cloud-gateway-sign-in)。
* **托管设置**：锁定的密钥无法在本地覆盖。CLI 在启动时应用策略，并在每个小时轮询时应用更改，除了[仅在下一次启动时应用的更改](/docs/zh-CN/server-managed-settings#fetch-and-caching-behavior)。
* **启动时网关无法访问**：已登录的会话在启动时约 10 秒后以错误退出，而不是在没有其设置的情况下启动。
* **启动后网关结束会话**：请参阅[强制执行故障关闭启动](/docs/zh-CN/server-managed-settings#enforce-fail-closed-startup)，了解哪些启动从网关登出打开，哪些在网关以 `401` 应答时退出。
* **取消配置**：用户在 IdP 中被禁用的会话在下一次刷新失败时在 `ttl_hours` 内过期。
* **登出**：`/logout` 从开发人员的机器删除网关凭证。
  * 当网关的发现文档在网关 URL 自己的方案、主机和端口上宣传 `revocation_endpoint` 时，`/logout` 也将存储的令牌发送到该端点，以便网关可以在其端结束会话。请求是尽力而为的，因此登出在开发人员的机器上完成，无论端点是否应答。撤销需要开发人员机器上的 Claude Code v2.1.275 或更高版本。
  * `claude` 二进制文件中的网关服务器不宣传任何，因此从它登出仅在开发人员的机器上结束会话。要强制会话从服务器端退出，请参阅 [JWT 密钥轮换](/docs/zh-CN/claude-apps-gateway-deploy#jwt-secret-rotation)。

<h3 id="what-the-organization-can-see">
  组织可以看到什么
</h3>

使用情况遥测携带开发人员的身份、令牌计数、模型和延迟到组织的收集器。网关不记录或存储提示或完成内容。是否收集更丰富的遥测（如日志和跟踪），可能包括命令和文件路径，是组织的[按目标选择](/docs/zh-CN/claude-apps-gateway-config#telemetry)。

<h2 id="availability-and-limitations">
  可用性和限制
</h2>

该表涵盖当开发人员通过网关连接时哪些 Claude Code 功能有效，以及网关服务器本身支持什么。如果不支持某些内容，Notes 列给出替代方案。

网关交付 CLI 发送给每个上游的 [`anthropic-beta`](https://platform.claude.com/docs/en/api/beta-headers) 值，因此操作员不维护 beta 允许列表。对于忽略标头的 Amazon Bedrock，网关将值移到请求正文的 `anthropic_beta` 字段中；其他上游按发送的方式接收标头。

| 功能 | 状态 | 注释 |
| - | - | - |
| 推理转发 (Amazon Bedrock、Claude Platform on AWS、Google Cloud 的 Agent Platform、Microsoft Foundry、Anthropic) | 可用 | 具有按上游模型转换和故障转移。Amazon Bedrock 上游使用 `bedrock-runtime` 端点和 AWS 默认凭证链；Amazon Bedrock [Mantle 端点](/docs/zh-CN/amazon-bedrock#use-the-mantle-endpoint)不是支持的上游。[Claude Platform on AWS 上游](/docs/zh-CN/claude-apps-gateway-config#claude-platform-on-aws)需要网关服务器上的 Claude Code v2.1.198 或更高版本。 |
| 按 IdP 组的模型访问和托管设置 | 可用 | 模型访问在服务器端强制执行；托管设置按 IdP 组交付，由 CLI 在[托管设置层](/docs/zh-CN/settings#settings-precedence)应用 |
| Claude Desktop | 可用（需要选择加入） | 网关在 `/user/bootstrap` 处为 Claude Desktop 的配置提供服务，一旦策略[使用 `desktop` 密钥选择加入](/docs/zh-CN/claude-apps-gateway-config#claude-desktop-overlay)，Claude Desktop 从其 Cowork 和 Code 选项卡以及从 Chat 选项卡（当您启用它时）发送模型请求通过网关。要打开 Chat 选项卡，请参阅[连接 Claude Desktop](#connect-claude-desktop)。需要网关服务器上的 Claude Code v2.1.203 或更高版本。 |
| 遥测扇出 (OTLP/HTTP) | 可用 | 按导出标识戳；protobuf 和 JSON 编码 |
| OIDC 身份提供商 | 可用 | 任何符合 OIDC 的 IdP；网关运行标准 OIDC 发现和授权代码流。请参阅[身份提供商设置](/docs/zh-CN/claude-apps-gateway-deploy#identity-provider-setup)了解每个 IdP 的配置 |
| 按用户和按组支出限制 | 可用 | 请参阅[支出限制](/docs/zh-CN/claude-apps-gateway-spend-limits) |
| 服务器端网络搜索 | 不可用 | CLI 无法看到网关路由到哪个上游提供商，因此无法验证网络搜索支持并在网关会话上禁用 WebSearch |
| [Remote Control](/docs/zh-CN/remote-control) | 不可用 | CLI 显示[命名网关的错误](/docs/zh-CN/errors#remote-control-requires-the-anthropic-api) |
| [`/design-sync`](/docs/zh-CN/commands#all-commands) 和 `/design-login` | 不可用 | 两者都需要 claude.ai，CLI 在网关会话上不联系，因此两个命令都不会出现 |
| 需要功能标志获取的功能，例如 `/import` 和 `claude import` | 不可用 | CLI 在网关会话上跳过标志获取。[需要功能标志获取的功能](/docs/zh-CN/env-vars#features-that-need-feature-flag-fetching)列出了关闭的内容 |
| 标准提示缓存 | 可用 | 网关将 `cache_control` 断点转发到每个上游。[缓存位置](/docs/zh-CN/prompt-caching#where-the-cache-lives)涵盖 CLI 标记的块，包括它在对话中途追加的系统上下文 |
| 1 小时缓存 TTL | 不可用 | CLI 在网关会话上省略扩展缓存 TTL beta，因为并非网关可以路由到的每个上游都支持 1 小时 TTL，因此通过网关的提示缓存使用 5 分钟 TTL；请参阅上面的 beta 标头注释 |
| Auto 模式 | 可用 | 遵循[第三方提供商规则](/docs/zh-CN/permission-modes#enable-auto-mode-on-bedrock-agent-platform-or-foundry)：仅第三方提供商上符合条件的模型可以使用它。在 v2.1.207 之前，网关会话上的 auto 模式需要设置 `CLAUDE_CODE_ENABLE_AUTO_MODE=1`，可通过托管策略 `env` 块交付 |
| 仅第一方优化，如全局缓存范围和令牌高效工具 | 不可用 | CLI 在网关会话上不启用它们；请参阅上面的 beta 标头注释 |
| OTLP/gRPC | 不支持 | 仅 OTLP over HTTP |
| SAML、LDAP 和其他非 OIDC 身份验证 | 不支持 | 仅 OIDC。如果需要，使用 OIDC 桥前置 |
| 多租户（多个 OIDC 发行者） | 不支持 | 每个网关一个发行者。运行单独的实例 |
| Windows 服务器 | 不支持 | 在 Linux 上部署。仅本地开发的 macOS |
| Helm 图表 | 不可用 | 网关作为标准无状态 Deployment 运行；请参阅[部署指南](/docs/zh-CN/claude-apps-gateway-deploy#kubernetes) |
| 管理员 UI | 不可用 | 配置是 YAML 文件；重新部署以更改它 |

<h2 id="next-steps">
  后续步骤
</h2>

快速入门让您在 Docker Compose 下运行最小配置。要进一步进行：

* 扩展 `gateway.yaml` 超越最小配置，例如添加按组 RBAC、多上游故障转移或遥测目标。[配置参考](/docs/zh-CN/claude-apps-gateway-config)涵盖每个选项。
* 从 Compose 迁移到 Kubernetes 或 Cloud Run 上的生产部署，正确设置您的 IdP，并审查安全模型。[部署和操作指南](/docs/zh-CN/claude-apps-gateway-deploy)涵盖每个 IdP 的设置、容器镜像要求、健康探针和故障排除。
* 对个别开发人员或组设置支出上限，以便失控的工作负载无法消耗您的整个承诺。[支出限制](/docs/zh-CN/claude-apps-gateway-spend-limits)涵盖管理 API 以及强制执行如何工作。
* 有关 AWS 上的完整工作示例，包括 ECS Fargate 或 EKS、Amazon RDS 和 Secrets Manager，请参阅[在 AWS 上部署](/docs/zh-CN/claude-apps-gateway-on-aws)。
* 有关 Google Cloud 的完整工作示例，包括 Cloud Run、Cloud SQL 和 Secret Manager，请参阅[在 Google Cloud 上部署](/docs/zh-CN/claude-apps-gateway-on-gcp)。

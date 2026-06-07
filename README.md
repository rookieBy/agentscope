# agentscope-router

> 多 LLM 路由服务 — 基于 [AgentScope Java](https://github.com/agentscope-ai/agentscope-java) v1.0.12 +
> Spring Boot 3.3 + JDK 21 LTS 构建。支持多 provider 自动切换、TTFT 熔断降级、ReAct Agent 工具调用、
> 文生图 / 文生视频多模态能力，以及 L1 多租户逻辑隔离。

## 项目定位

`agentscope-router` 是一个**多 LLM 路由网关**，对外暴露统一的 OpenAI 兼容 / 自定义 SSE 协议，
对内聚合 minimax、OpenAI、DashScope、Anthropic、Gemini 等多 provider 资源。当主 provider
响应过慢时自动切换到备选 provider；当某个 provider 持续失败时自动熔断并冷却。

- **核心能力**:
  - 文本对话（同步 / 流式 SSE）
  - Function Call / ReAct Agent（"一切皆 Tool"）
  - 文生图（`text_to_image`）
  - 文生视频（异步任务 + SSE 进度推送）
  - TTFT（Time-To-First-Token）熔断 + 自动降级
  - L1 多租户逻辑隔离
- **协议**:
  - LLM 协议: OpenAI Compatible + AgentScope native
  - 视频: `https://api.minimaxi.com/v1` HTTP API

## 技术栈

| 维度 | 选型 |
|------|------|
| Java | **JDK 21 LTS**（虚拟线程 / Record Patterns） |
| Agent 框架 | **AgentScope Java v1.0.12** (`io.agentscope:agentscope-spring-boot-starter`) |
| 容器 | **Spring Boot 3.3.5** |
| 反应式流 | Project Reactor（`Flux` / `Mono`） |
| 工具链 | **Maven 多模块**（5 个模块） |
| 持久化 | **Redis**（Hash 存储 + Pub/Sub 事件分发） |
| 多租户 | **L1 逻辑隔离**（共享存储 + `tenantId` 字段过滤） |

## Maven 多模块结构

```
agentscope-router/
├── pom.xml                          # 父 POM（dependencyManagement + modules）
├── launcher/                        # Spring Boot 启动模块
├── api/                             # Controller / DTO / SSE 转换
├── business/                        # 业务编排层（ChatService / MultimodalService / 路由 / 多租户）
├── llm/                             # LLM 适配层（ProviderAdapter / RoutingChatModel / 熔断）
└── common/                          # 公共层（异常 / 常量 / 工具 / 多租户地基）
```

模块依赖方向（自上而下）:

```
launcher → api → business → llm → common
```

## 快速开始

### 1. 前置条件

- **JDK 21 LTS**（必须 — 启用了虚拟线程）
- **Maven 3.9+**
- **Redis 6.x+**（多模态视频任务用 Pub/Sub 推送 SSE 进度）
- 至少一个 LLM provider 的 API Key

### 2. 编译

```bash
cd agentscope-router
mvn -B -DskipTests clean package
```

生成的可执行 jar:

```
launcher/target/agentscope-router.jar
```

### 3. 启动

```bash
# 必须通过环境变量注入 API key（**绝对不要写入任何配置文件 / 源码**）
export MINIMAX_API_KEY="<your-key>"
export REDIS_HOST="localhost"
export REDIS_PORT="6379"

# 启动（默认 profile，providers 全关闭）
java -jar launcher/target/agentscope-router.jar

# 启用 smoke profile（开启 minimax provider 用于联调）
java -jar launcher/target/agentscope-router.jar --spring.profiles.active=smoke
```

启动成功后会看到:

```
Started Application in 2.7 seconds (process running for 3.0)
Tomcat started on port 8080 (http) with context path '/'
```

### 4. 端到端验证

所有请求必须携带 `X-Tenant-Id` header（多租户 L1 隔离）。

#### 健康检查

```bash
curl -H "X-Tenant-Id: acme" http://localhost:8080/api/v1/health
# {"status":"UP","service":"agentscope-router","timestamp":"..."}
```

#### 路由状态 / 候选

```bash
curl -H "X-Tenant-Id: acme" http://localhost:8080/api/v1/routing/candidates
# {"qualifiedNames":["minimax:MiniMax-M3"],"count":1,"timestamp":"..."}

curl -H "X-Tenant-Id: acme" http://localhost:8080/api/v1/routing/status | jq
# {
#   "tenantId":"acme",
#   "candidates":[{
#     "modelName":"minimax:MiniMax-M3",
#     "score":0.5, "ttftEmaMs":0, "errorRateEma":0,
#     "consecutiveFailures":0, "cooldownUntil":0
#   }]
# }
```

#### Agent 流式对话（Function Call）

```bash
curl -N -X POST http://localhost:8080/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: acme" \
  -d '{
    "messages":[{"role":"user","content":"用一张水彩风格的猫的图片"}]
  }'
```

SSE 事件流:

```
event:message
data:{"type":"text","delta":"我来生成一张图片..."}

event:message
data:{"type":"tool_call","name":"text_to_image","args":{"prompt":"a watercolor cat"}}

event:message
data:{"type":"tool_result","name":"text_to_image","output":{"url":"https://..."}}

event:message
data:{"type":"text","delta":"图片已生成..."}

event:message
data:{"type":"finish","reason":"stop"}
```

#### 直调 — 文生图

```bash
curl -X POST http://localhost:8080/api/v1/media/image \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: acme" \
  -d '{"prompt":"a cat sitting on a chair, watercolor style", "aspectRatio":"1:1", "n":1}'
# {"model":"image-01","imageUrls":["https://..."]}
```

#### 直调 — 文生视频（异步）

```bash
# 1. 提交
TASK=$(curl -s -X POST http://localhost:8080/api/v1/media/video \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: acme" \
  -d '{"prompt":"a cat walking in a garden","duration":6,"resolution":"768P"}' \
  | jq -r .taskId)

# 2. 查状态
curl -H "X-Tenant-Id: acme" http://localhost:8080/api/v1/media/video/$TASK
# {"taskId":"...","state":"QUEUED","model":"MiniMax-Hailuo-2.3",...}

# 3. SSE 订阅进度（任务终态时自动关闭）
curl -N -H "X-Tenant-Id: acme" \
  http://localhost:8080/api/v1/media/video/$TASK/stream
```

#### 多租户隔离

```bash
# 缺 header → 400
curl -i -X POST http://localhost:8080/api/v1/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"hi"}]}'
# HTTP/1.1 400
# {"code":"MISSING_TENANT_ID","message":"X-Tenant-Id header is required"}

# 跨租户查视频任务 → 404（不是 200 + 空数据）
TASK=$(curl -s -X POST http://localhost:8080/api/v1/media/video \
  -H "X-Tenant-Id: tenant-a" -H "Content-Type: application/json" \
  -d '{"prompt":"x"}' | jq -r .taskId)
curl -i -H "X-Tenant-Id: tenant-b" \
  http://localhost:8080/api/v1/media/video/$TASK
# HTTP/1.1 404
# {"code":"VIDEO_TASK_NOT_FOUND","message":"No video task ... for tenant tenant-b"}
```

## 关键设计

### 1. 路由层

`RoutingChatModel` 装饰器:
- 选最佳候选（`HealthScoreService.select()`）
- 启动 TTFT 计时器（默认 2s，可在 profile 调高）
- 监听首 token 到达 → 取消计时器
- TTFT 超时 → 主动 dispose 当前候选，重选并重发

`HealthScoreService` 打分公式:

```
score = (1 / (1 + ttftEma/1000))     # 速度项
      * (1 - errorRateEma)           # 成功率项
      * exp(-consecutiveFailures)    # 连续失败惩罚
      * recencyFactor                # 静默降权
```

熔断: 当 `consecutiveFailures >= maxConsecutiveFailures`（默认 5）→ 冷却 30s，
冷却期间该 provider 不被选。

### 2. 多租户 L1 隔离

- `X-Tenant-Id` header → `TenantContextFilter` → `TenantContextHolder` ThreadLocal
- 所有 Redis key 前缀 `t:{tenantId}:`，由 `RedisKeyFactory` 统一构造
- tenantId 格式校验: `[a-zA-Z0-9_-]{1,64}`，非法 → `INVALID_TENANT_ID` 400
- **不存 API key 到租户级**: 平台统一持有，租户只能消耗 quota

### 3. 多模态（视频）

- 文生视频 → 异步任务 → 写到 Redis Hash（`t:{tenantId}:video:task:{taskId}`，TTL 24h）
- `VideoTaskWorker`（`@Scheduled fixedDelay=5000`）轮询所有非终态任务
- 状态变更 → 发 Pub/Sub 消息到 `t:{tenantId}:video:event:{taskId}` channel
- `VideoTaskEventListener`（基于 `RedisMessageListenerContainer`）按 channel 订阅
- SSE 推送给前端

状态机:

```
PENDING → QUEUED → RUNNING → SUCCEEDED
                              → FAILED
                              → EXPIRED
```

### 4. Agent / Function Call

AgentScope "一切皆 Tool":
- `MediaTools.textToImage` / `textToVideo` / `checkVideoStatus`
- `MediaTools.listAvailableModels` / `modelHealth`
- `ChatAgentService` 构造 `ReActAgent`，`chatModel = RoutingChatModel`
- 系统提示中指导 agent 自主选择 tool

## 配置说明

`application.yml` 是默认配置（providers 全部关闭）。`application-smoke.yml` 启用
minimax provider 用于联调。**绝对不要把任何 API key 写入 yml 文件** — 全部用
`${VAR_NAME:}` 占位符 + 环境变量。

主要配置项:

| Key | 说明 | 默认 |
|-----|------|------|
| `agentscope.routing.ttft-timeout-ms` | TTFT 超时阈值 | 2000 |
| `agentscope.routing.max-consecutive-failures` | 熔断阈值 | 5 |
| `agentscope.routing.cooldown-ms` | 熔断冷却时长 | 30000 |
| `agentscope.routing.min-score-threshold` | 候选分数下限 | 0.05 |
| `agentscope.multimodal.poll-interval-ms` | 视频轮询间隔 | 5000 |
| `spring.threads.virtual.enabled` | 虚拟线程 | true |

## API 端点一览

| Method | Path | 用途 | Tenant |
|--------|------|------|--------|
| GET | `/api/v1/health` | 服务存活 | 否 |
| GET | `/api/v1/routing/candidates` | 当前候选 | 是 |
| GET | `/api/v1/routing/status` | 候选健康分 | 是 |
| POST | `/api/v1/chat/stream` | Agent 流式对话（SSE） | 是 |
| POST | `/api/v1/media/image` | 文生图（直调） | 是 |
| POST | `/api/v1/media/video` | 文生视频（直调，异步） | 是 |
| GET | `/api/v1/media/video/{taskId}` | 查视频任务状态 | 是 |
| GET | `/api/v1/media/video/{taskId}/stream` | SSE 订阅视频进度 | 是 |

## 测试

```bash
# 全模块单元测试
mvn -B test

# 只跑某个模块
mvn -B -pl llm test
```

覆盖率目标: 80%+（重点覆盖 `business/llm` 业务逻辑，跳过 DTO/Config）。

## 不做的事（YAGNI）

- ❌ 用户管理 / 鉴权（交给上游网关）
- ❌ 完整的 cost accounting
- ❌ RAG / 知识库
- ❌ L2 / L3 多租户隔离
- ❌ MySQL 持久化
- ❌ 自定义 Function Calling 协议（用 AgentScope 内置 `@Tool`）

## License

MIT

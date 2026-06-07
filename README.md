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
  - **多 Agent 协同 Demo** (`POST /api/v1/demo/ai-promo`):
    文案子 agent → 视频生成子 agent → 文件下载子 agent，一个 curl 触发完整 ReAct 流程
- **协议**:
  - LLM 协议: OpenAI Compatible + AgentScope native
  - 视频: `https://api.minimaxi.com/v1` HTTP API (`POST /v1/video_generation`)

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
| 日志 | **Log4j2**（接管 spring-boot-starter-logging，MDC 注入 `tenantId`，demo 独立日志文件） |

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

Log4j2 接管了所有日志输出, 默认两类滚动文件 (相对 `user.dir`, 即 jar 所在目录):

| 文件 | 级别 | 内容 |
|------|------|------|
| `logs/agentscope-router.log` | INFO+ | 全局运行日志, 30 天 / 单文件 50 MB 滚动 |
| `logs/demo.log` | TRACE+ (仅 demo 4 个包) | 多 Agent Demo 的 ReAct 思考/工具调用/结果, 10 天 / 20 MB 滚动, 适合 `tail -f` |

Pattern 含 MDC `%X{tenantId}`, 多租户场景下按行 `grep '\[tenant-A\]'` 即可过滤.

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

#### 多 Agent 协同 Demo (需 `agentscope.demo.ai-promo.enabled=true`)

完整流程与日志解读见 [§5 多 Agent 协同示例](#5-多-agent-协同示例-ai-宣传视频-demo).
这里给最小可复现 curl:

```bash
# smoke profile 默认打开 demo; duration 只能 6 或 10 (Hailuo-2.3 限制)
curl -N -X POST http://localhost:8080/api/v1/demo/ai-promo \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: acme" \
  -d '{"topic":"讲讲 AI 可以帮我们做哪些工作","duration":6,"language":"en"}'

# 另开一个终端, 实时看 ReAct 全流程:
tail -f logs/demo.log
```

落盘文件: `business/src/main/resources/output/ai-promo-6s.mp4` (默认).
`enabled=false` 时该路径返回 404, 不会有任何 bean 占用内存.

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

### 5. 多 Agent 协同示例: AI 宣传视频 Demo

> **目标读者**: 第一次接触 AgentScope 的初学者. 这一节用一个 `curl` 演示一个
> `ReActAgent` 如何把 3 个能力完全不同的 "子 agent" 串成一条流水线, 同时在
> 终端实时看到 ReAct 的 thinking / tool_call / tool_result 事件流.

#### 5.1 整体流程

```
curl POST /api/v1/demo/ai-promo
        │
        ▼
DemoController                          ← api/  模块, HTTP 边界
        │   topic="What AI can do for us", duration=6
        ▼
AiPromoDemoService                      ← business/ 模块, 编排入口
        │   1. 解析 X-Tenant-Id 写入 ToolContext
        │   2. 拼装 Msg(metadata={tenantId, requestId, saveTo, duration, language})
        ▼
promoDemoAgent (ReActAgent)             ← business/agent/AgentConfig
        │   sysPrompt 明确写出 5 步 SOP
        ▼
┌──────────────────────────────────────────────────────────┐
│  STEP 1: write_promo_copy (Tool)                        │
│    ├─ 输入: topic, duration, language='en'               │
│    └─ 内部: blocking call RoutingChatModel.stream(...)   │
│             ── 又触发一次 TTFT 熔断 + 多 provider 切换   │
│        → 返回 3-5 段英文 scene-by-scene 脚本             │
│                                                          │
│  STEP 2: text_to_video (Tool, 来自 MediaTools)          │
│    ├─ 输入: prompt=<脚本原样>, duration=6, resolution=768P│
│    └─ 内部: minimax HTTP POST /v1/video_generation       │
│        → 返回 taskId                                    │
│                                                          │
│  STEP 3: check_video_status × N (Tool)                  │
│    └─ 轮询 minimax GET /v1/query/video_generation       │
│        直到 state ∈ {SUCCEEDED, FAILED}                 │
│                                                          │
│  STEP 4: download_video_file (Tool)                     │
│    ├─ 输入: taskId, save_to=<metadata 里的路径>          │
│    └─ 内部: minimax GET /v1/files/retrieve              │
│             → WebClient 下载 .mp4 字节流                │
│             → 写入 business/src/main/resources/output/  │
│                                                          │
│  STEP 5: ReActAgent 输出一句中文短句, 告知落盘路径       │
└──────────────────────────────────────────────────────────┘
        │
        ▼
DemoController 把 4 类 Event 序列化为 SSE chunk
        │
        ▼
curl -N 收到 data: {"type":"agent", "eventType":"TOOL_CALL", ...}
       data: {"type":"agent", "eventType":"TOOL_RESULT", ...}
       data: {"type":"agent", "eventType":"AGENT_RESULT", "isLast":true, ...}
```

每一段 agent 事件都会被 `AiPromoDemoService.toChunk(...)` 装成如下 JSON:

```json
{
  "type": "agent",
  "eventType": "TOOL_CALL",
  "role": "ASSISTANT",
  "content": "",
  "isLast": false,
  "toolCalls": [{"id": "...", "name": "write_promo_copy", "input": {"topic": "..."}}]
}
```

客户端只需按行解析 `data: {...}`，就能完整看到 ReAct 的思考/调用/结果三阶段。

#### 5.2 启用 Demo

`agentscope.demo.ai-promo.enabled` 默认 `false`。生产 profile 不要开启,
只在 `application-smoke.yml` 之类的调试 profile 下打开:

```yaml
# launcher/src/main/resources/application-smoke.yml
agentscope:
  demo:
    ai-promo:
      enabled: true          # 开启后 AiPromoDemoService + DemoController + promoDemoAgent 才注册
      default-duration: 6
      output-dir: business/src/main/resources/output
```

启动:

```bash
MINIMAX_API_KEY="<your-key>" \
  java -jar launcher/target/agentscope-router.jar \
       --spring.profiles.active=smoke
```

`enabled=false` 时访问 `/api/v1/demo/ai-promo` 会直接 404, 不会有任何 bean 占用内存.

#### 5.3 触发 (curl)

> **注意: 视频时长只能传 `6` 或 `10`** —— 这是 minimax Hailuo-2.3 的限制,
> 即使你写 30 也会被 controller 立刻 400 拒绝. 想要 "看起来 30s" 的成片,
> 在 `write_promo_copy` 阶段写 5 段 6s 镜头拼接即可.

```bash
# 1. 一句话: 让 agent 帮你做一个 AI 宣传视频, duration 作为参数传进来
curl -N -X POST http://localhost:8080/api/v1/demo/ai-promo \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: acme" \
  -d '{
    "topic":   "讲讲 AI 可以帮我们做哪些工作",
    "duration": 6,
    "language": "en"
  }'

# 2. 服务端按事件流回推, 你会依次看到:
#    - tool_call  write_promo_copy
#    - tool_result {"script": "[Push in] ..."]
#    - tool_call  text_to_video
#    - tool_result {"taskId": "..."}
#    - tool_call  check_video_status  (×N, 每隔几秒一次)
#    - tool_result {"state":"RUNNING"} ...
#    - tool_result {"state":"SUCCEEDED", "file_id":"..."}
#    - tool_call  download_video_file
#    - tool_result {"ok":true, "path":".../output/ai-promo-6s.mp4", "bytes":...}
#    - isLast=true 的 agent_result: "已生成 6s 宣传片, 保存到 ..."

# 3. 视频落盘位置 (生产可改 agentscope.demo.ai-promo.output-dir):
ls -la business/src/main/resources/output/ai-promo-6s.mp4
```

错误用例 (校验在 controller 层完成, 不消耗 LLM quota):

```bash
# 缺 topic → 400
curl -i -X POST http://localhost:8080/api/v1/demo/ai-promo \
  -H "X-Tenant-Id: acme" -H "Content-Type: application/json" \
  -d '{"duration":6}' | head
# {"code":"INVALID_TENANT_ID","message":"Request body must contain a non-blank 'topic' field",...}

# 非法 duration (不是 6/10) → 400
curl -i -X POST http://localhost:8080/api/v1/demo/ai-promo \
  -H "X-Tenant-Id: acme" -H "Content-Type: application/json" \
  -d '{"topic":"hi","duration":30}' | head
# {"code":"INVALID_VIDEO_DURATION","message":"duration must be 6 or 10 (Hailuo-2.3 constraint), got 30",...}

# 缺 X-Tenant-Id → 400
curl -i -X POST http://localhost:8080/api/v1/demo/ai-promo \
  -H "Content-Type: application/json" -d '{"topic":"hi","duration":6}' | head
# {"code":"MISSING_TENANT_ID","message":"X-Tenant-Id header is required",...}
```

#### 5.4 看 ReAct 完整流程的日志

Log4j2 (`launcher/src/main/resources/log4j2-spring.xml`) 把 `business.demo` /
`business.tools` / `business.multimodal` / `business.agent` 四个包单独路由到
`logs/demo.log`, 关闭向上冒泡 (`additivity=false`), 不会污染全局日志:

```bash
# 一条命令看完整个 ReAct 链
tail -f logs/demo.log
```

每条记录的 pattern 含 MDC `%X{tenantId}`, 多个租户并行跑也能按行过滤:

```bash
tail -f logs/demo.log | grep '\[acme\]'   # 只看某个租户
```

输出的 mp4 同样按租户路径 (通过 `saveTo` 入参 + 输出目录前缀) 隔离,
但**当前 demo 走的是单一输出目录** (`business/src/main/resources/output/`),
如果要多租户分流需要自行扩展 `DemoProperties.outputDir` 模板.

#### 5.5 代码地图 (从哪里读起)

| 关注点 | 文件 |
|--------|------|
| HTTP 边界 + 校验 | `api/.../controller/DemoController.java` |
| 请求 DTO | `api/.../dto/AiPromoRequest.java` |
| 编排入口 (stream → chunk) | `business/.../demo/AiPromoDemoService.java` |
| ReAct Agent 定义 + sysPrompt | `business/.../agent/AgentConfig.java#promoDemoAgent` |
| 文案子 agent (Tool) | `business/.../tools/PromoDemoTools.java#writePromoCopy` |
| 视频生成子 agent (Tool) | `business/.../tools/MediaTools.java#textToVideo` / `checkVideoStatus` |
| 文件下载子 agent (Tool) | `business/.../tools/PromoDemoTools.java#downloadVideoFile` |
| 异步任务落盘 + Redis Pub/Sub | `business/.../multimodal/{MultimodalService,VideoTaskManager,VideoFileDownloader}.java` |
| 跨线程 Tenant 上下文 | `business/.../tools/{ToolContext,RequestContextStore}.java` |
| Demo 配置 | `business/.../demo/DemoProperties.java` |

> **想弄懂 "一切皆 Tool" 的协奏, 只看 `promoDemoAgent` 的 sysPrompt
> (`AgentConfig.java`) 和 3 个 `@Tool` 方法就够了** — 整个 demo 业务逻辑
> 收敛在 ~150 行里, 没有任何业务代码, 完全靠 AgentScope 自身驱动.

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
| `agentscope.demo.ai-promo.enabled` | 是否注册 Demo Controller / Agent / Tools（false 时 `/api/v1/demo/ai-promo` 返回 404） | false |
| `agentscope.demo.ai-promo.default-duration` | curl 不传 `duration` 时的默认秒数（**只能 6 或 10**，Hailuo-2.3 限制） | 6 |
| `agentscope.demo.ai-promo.default-resolution` | 默认视频分辨率 | 768P |
| `agentscope.demo.ai-promo.default-model` | 默认视频模型 | MiniMax-Hailuo-2.3 |
| `agentscope.demo.ai-promo.output-dir` | Demo 视频落盘目录 | `business/src/main/resources/output` |
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
| POST | `/api/v1/demo/ai-promo` | **多 Agent 协同 Demo**（文案 → 视频 → 下载，SSE 流） | 是 |

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

## Controller API Reference

所有业务端点强制要求 `X-Tenant-Id` 请求头（regex `[a-zA-Z0-9_-]{1,64}`，缺失或非法一律返回 400 `MISSING_TENANT_ID` / `INVALID_TENANT_ID`）。SSE 端点的响应是 `text/event-stream`，每条事件独占一行 `data: { ...json... }\n\n`。

下文示例默认服务监听 `http://localhost:8080`，tenant id 取 `demo-tenant-1`。

### `HealthController` — `/api/v1/health`

Liveness 探针，**不**访问任何 LLM provider，K8s / LB 健康检查可直接打。

| Method | Path | Headers | 用途 |
|---|---|---|---|
| `GET` | `/api/v1/health` | — | 服务存活 + 元信息 |

**响应 200**

```json
{
  "status": "UP",
  "service": "agentscope-router",
  "timestamp": "2026-06-07T13:00:00Z"
}
```

**示例**

```bash
curl -s http://localhost:8080/api/v1/health
```

---

### `RoutingController` — `/api/v1/routing/*`

只读路由内省。所有路由都通过 `X-Tenant-Id` 区分租户（由 `TenantContextFilter` 写入 `TenantContextHolder`）。

#### `GET /api/v1/routing/candidates`

返回当前注册的全部 `(providerId, modelName)` 候选清单，**不**带租户隔离（registry 是进程级共享的）。

**响应 200**

```json
{
  "qualifiedNames": ["minimax:abab6.5s-chat", "openai:gpt-4o-mini", "dashscope:qwen-plus"],
  "count": 3,
  "timestamp": "2026-06-07T13:00:00Z"
}
```

**示例**

```bash
curl -s -H "X-Tenant-Id: demo-tenant-1" http://localhost:8080/api/v1/routing/candidates
```

#### `GET /api/v1/routing/status`

按租户返回各候选模型的健康度快照（EMA + 滑动窗口 + 连续失败冷却，由 `HealthScoreService` 计算）。

**Headers**：`X-Tenant-Id`（必填）

**响应 200**

```json
{
  "tenantId": "demo-tenant-1",
  "candidateCount": 3,
  "candidates": [
    {
      "qualifiedName": "minimax:abab6.5s-chat",
      "score": 0.95,
      "consecutiveFailures": 0,
      "lastFailureAt": null,
      "cooldownUntil": null,
      "samples": 42
    }
  ],
  "timestamp": "2026-06-07T13:00:00Z"
}
```

**示例**

```bash
curl -s -H "X-Tenant-Id: demo-tenant-1" http://localhost:8080/api/v1/routing/status
```

---

### `ChatController` — `/api/v1/chat/*`

流式聊天。**两条端点**都返回 `text/event-stream`，每条事件格式：`data: {"type":"...","...":"..."}\n\n`。

#### `POST /api/v1/chat/stream`

直接调 `RoutingChatModel`，由路由层按 TTFT 阈值 + 健康度做多 provider 切换。每次模型产出 `ChatResponse` 即推送一条 `data:` 事件，**不**经过 ReAct agent / `@Tool`。

**Headers**：`X-Tenant-Id`（必填）  
**Body**（`application/json`）：

```json
{
  "messages": [
    {"role": "system", "content": "你是一个简洁的助手。"},
    {"role": "user",   "content": "用一句话介绍 Spring Boot 3"}
  ]
}
```

- `messages[]` 必填，至少一条；非空 OpenAI 形态（`role` + `content`）
- 整段响应被路由到**单个** provider（无 TTFT 超时时切到下一个候选）

**SSE chunk 形态**

```json
{ "type": "model", "model": "minimax:abab6.5s-chat", "content": "Spring Boot 3 是..." }
```

**示例**

```bash
curl -N \
  -H "X-Tenant-Id: demo-tenant-1" \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"你好"}]}' \
  http://localhost:8080/api/v1/chat/stream
```

#### `POST /api/v1/chat/agent/stream`

走 `ReActAgent`：`RoutingChatModel` 作为底座 chat model，agent 会按需调用 `MediaTools` 里注册的 `@Tool`（`text_to_image` / `text_to_video` / `query_video_task` / `download_video_file` 等）。每个 agent 事件（`REASONING` / `TOOL_RESULT` / `AGENT_RESULT` / `TOOL_CALL`）会独立推送一条 SSE chunk。

**Headers**：`X-Tenant-Id`（必填）  
**Body**：

```json
{ "message": "用一句话介绍 Spring Boot 3" }
```

- `message` 必填，非空字符串
- 工具调用跨 async 边界时，`ChatAgentService` 会把 `tenantId` 注入到 `Msg.metadata` + `MediaTools` 上下文（`ToolContext`），`@Tool` 内部统一从 `MediaTools.getCurrentRequest()` 读租户

**示例**

```bash
curl -N \
  -H "X-Tenant-Id: demo-tenant-1" \
  -H "Content-Type: application/json" \
  -d '{"message":"给我生成一张猫咪的图片"}' \
  http://localhost:8080/api/v1/chat/agent/stream
```

---

### `MediaController` — `/api/v1/media/*`

直接的多模态 HTTP 端点（与 `MediaTools` 上的 `@Tool` 并行存在，便于不想付 LLM token 的前端/调用方绕开 agent）。所有路由强制 `X-Tenant-Id`。

#### `POST /api/v1/media/image`

同步文生图。`MultimodalService` 直接调底层图像 provider，**不**经过路由切换。

**Headers**：`X-Tenant-Id`（必填）、`Content-Type: application/json`  
**Body**：

```json
{
  "prompt": "A cute calico cat sitting on a window sill, watercolor",
  "aspectRatio": "16:9",
  "n": 1
}
```

- `prompt` 必填，非空
- `aspectRatio`、`n` 选填；具体取值由 provider 决定

**响应 200**

```json
{
  "model": "minimax:image-01",
  "imageUrls": ["https://.../cat-1.png"]
}
```

**示例**

```bash
curl -s \
  -H "X-Tenant-Id: demo-tenant-1" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"a sunset over the sea","aspectRatio":"16:9","n":1}' \
  http://localhost:8080/api/v1/media/image
```

#### `POST /api/v1/media/video`

异步文生视频。提交后立即返回 `taskId`，后续通过 `GET /api/v1/media/video/{taskId}` 查状态，或 `GET /api/v1/media/video/{taskId}/stream` 订阅 SSE 进度。视频任务状态机：`PENDING → QUEUED → RUNNING → SUCCEEDED | FAILED`。

**Headers**：`X-Tenant-Id`（必填）、`Content-Type: application/json`  
**Body**：

```json
{
  "prompt": "A drone shot rising over a misty forest at dawn",
  "duration": 6,
  "resolution": "1080P",
  "firstFrameImageUrl": ""
}
```

- `prompt` 必填，非空
- `duration` 选填，单位秒；当前 provider（Hailuo-2.3）仅支持 `6` / `10`
- `resolution` 选填，如 `720P` / `1080P`
- `firstFrameImageUrl` 选填，图生视频时使用

**响应 200**

```json
{
  "taskId": "t:abc123:video:xyz",
  "state": "PENDING",
  "model": "minimax:hailuo-2.3",
  "createdAt": "2026-06-07T13:00:00Z"
}
```

**示例**

```bash
curl -s \
  -H "X-Tenant-Id: demo-tenant-1" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"a corgi running on the beach","duration":6,"resolution":"1080P"}' \
  http://localhost:8080/api/v1/media/video
```

#### `GET /api/v1/media/video/{taskId}`

查询视频任务当前快照。租户隔离：跨租户查一律返回 404 `VIDEO_TASK_NOT_FOUND`（**不会**返回空数据，避免存在性泄漏）。

**Headers**：`X-Tenant-Id`（必填）  
**Path 参数**：`taskId`（非空）

**响应 200**

```json
{
  "taskId": "t:abc123:video:xyz",
  "state": "RUNNING",
  "model": "minimax:hailuo-2.3",
  "videoUrl": null,
  "fileId": null,
  "failureReason": null,
  "createdAt": "2026-06-07T13:00:00Z",
  "updatedAt": "2026-06-07T13:00:42Z"
}
```

`SUCCEEDED` 时 `videoUrl` 是 provider 返回的下载链接（worker 会先调 `retrieveFileDownloadUrl(fileId)`）；`FAILED` 时 `failureReason` 是 provider 错误信息。

**示例**

```bash
curl -s -H "X-Tenant-Id: demo-tenant-1" \
  http://localhost:8080/api/v1/media/video/t:abc123:video:xyz
```

#### `GET /api/v1/media/video/{taskId}/stream`

SSE 订阅视频任务状态变化。第一条事件是**当前**快照（任务不存在时推 `{"type":"NOT_FOUND","message":"..."}`），后续事件由 `VideoTaskWorker` 每次轮询到状态变化时通过 Redis Pub/Sub 推过来。流在 `SUCCEEDED` / `FAILED` 时自动关闭。

**Headers**：`X-Tenant-Id`（必填）  
**Path 参数**：`taskId`（非空）

**SSE chunk 形态**

```json
{ "type": "PENDING",   "task": { ... } }
{ "type": "RUNNING",   "task": { ... } }
{ "type": "SUCCEEDED", "task": { ..., "videoUrl": "https://..." } }
```

**示例**

```bash
curl -N -H "X-Tenant-Id: demo-tenant-1" \
  http://localhost:8080/api/v1/media/video/t:abc123:video:xyz/stream
```

---

### `DemoController` — `/api/v1/demo/ai-promo`

**多 Agent 协同 Demo**（受 `agentscope.demo.ai-promo.enabled=true` 控制开关，关闭时整个 controller 不注册 → 端点 404）。该端点串联展示 AgentScope 的 ReAct + `@Tool` 协作流：

```
user prompt
  → Agent (decides)
  → write_promo_copy (@Tool)        写一段推广文案
  → text_to_video   (@Tool)         调 minimax Hailuo-2.3 生成视频
  → query_video_task(@Tool)         轮询到 SUCCEEDED
  → download_video_file(@Tool)      拉回 videoUrl
  → final result                    SSE 推送给客户端
```

每条 agent 事件独立推送一条 SSE chunk，与 `/api/v1/chat/agent/stream` 共用一套前端解析。

#### `POST /api/v1/demo/ai-promo`

**Headers**：`X-Tenant-Id`（必填）、`Content-Type: application/json`  
**Body**：

```json
{
  "topic": "智能保温杯，让冬日办公更有温度",
  "duration": 6,
  "language": "zh"
}
```

- `topic` 必填，非空；产品/推广主题
- `duration` 选填，单位秒；受 Hailuo-2.3 限制仅接受 `6` / `10`，不传则取 `agentscope.demo.ai-promo.default-duration`（默认 6）
- `language` 选填，文案输出语言（如 `zh` / `en`），空字符串等同于 `en`

**示例**

```bash
curl -N \
  -H "X-Tenant-Id: demo-tenant-1" \
  -H "Content-Type: application/json" \
  -d '{"topic":"智能保温杯","duration":6,"language":"zh"}' \
  http://localhost:8080/api/v1/demo/ai-promo
```

---

### 公共错误码（节选）

| HTTP | `code` | 触发条件 |
|---|---|---|
| 400 | `MISSING_TENANT_ID` | `X-Tenant-Id` 头缺失 |
| 400 | `INVALID_TENANT_ID` | `X-Tenant-Id` 不符合 `[a-zA-Z0-9_-]{1,64}` 或 body 字段非法 |
| 400 | `INVALID_VIDEO_DURATION` | `duration` 不是 `{6, 10}`（仅 `ai-promo`） |
| 404 | `VIDEO_TASK_NOT_FOUND` | 视频任务对当前租户不可见 |
| 500 | `INTERNAL_ERROR` | 兜底；详见 `logs/agentscope-router.log` |

完整错误码定义见 `common/src/main/java/io/agentscope/router/common/exception/ErrorCode.java`。

---

## License

MIT

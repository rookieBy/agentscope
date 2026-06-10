# 多 Agent 协调架构总结

> 配套源码:`business/src/main/java/io/agentscope/router/business/demo/AiPromoDemoService.java`
> 配套重构 commit:`2324371 refactor(agent): split monolithic agents into real MsgHub multi-agent teams`

---

## 1. `stream()` 方法的协调机制 —— 6 个阶段

`AiPromoDemoService.stream()` **自己不是 Agent,是编排者(orchestrator)**。核心骨架(`AiPromoDemoService.java:141-160`):

```java
return Flux.using(
    ()        -> hub.enter().block(),                            // 资源供给
    activeHub -> Flux.concat(                                    // 资源消费
        copywriterAgent.stream(msgs, opts),
        videoProducerAgent.stream(msgs, opts),
        fileCollectorAgent.stream(msgs, opts)
    ),
    activeHub -> activeHub.exit().block()                        // 资源释放
)
```

### 6 个阶段拆解

| # | 阶段 | 行号 | 关键行为 |
|---|---|---|---|
| 1 | 上下文解析 | L102-L110 | 从 `TenantContextHolder` 取 `tenantId`,应用 `DemoProperties` 的默认值(`duration=6`, `lang="en"`) |
| 2 | User Msg 构造 | L115-L128 | 构造**单条** user `Msg`,metadata 含 `{tenantId, requestId, saveTo, duration, language}` 五个字段 |
| 3 | MsgHub 构造 | L133-L137 | `name="promo-hub"` + `participants(3 agents)` + `enableAutoBroadcast(true)` |
| 4 | `Flux.using` 三段式 | L141-L160 | `enter()` → `Flux.concat(3 streams)` → `exit()`,生命周期与 Reactor 订阅严格对齐 |
| 5 | ThreadLocal 修复 | L161-L170 | `doOnSubscribe` 调 `MediaTools.setCurrentRequest(ctx)`,`doFinally` 调 `clearCurrentRequest()` |
| 6 | Event → SSE Chunk | L171-L173 | `toChunk` 把 `Event` 转成 `Map<String,Object>`,最终在 controller 包成 `data: {...}\n\n` |

### 关键设计点

- **`Flux.using` 三段式** 把 `MsgHub` 的 `enter/exit` 绑到 Reactor 流的"订阅即启、终止即关"。3 种终止信号(成功 / 错误 / 取消)都会触发 cleanup。
- **`Flux.concat` 强制串行** —— 顺序由外部代码决定(`copywriter → videoProducer → fileCollector`),不是 Hub。
- **`doOnSubscribe` / `doFinally`** 配对维护 `MediaTools` 的 ThreadLocal,解决 Reactor 线程**不**继承 servlet 线程 `TenantContextHolder` 的问题(参见 `CLAUDE.md` 的 "ThreadLocal Propagation Trap")。
- **user Msg 的 metadata 是**关键 trick:`saveTo` / `duration` / `language` 提前算好,Agent 无需自己编造,避免幻觉。

---

## 2. MsgHub 编排原理

`MsgHub` 来自 `io.agentscope.core.pipeline.MsgHub`(AgentScope 1.0.12)。**它是"被动的消息总线",不是"主动的工作流引擎"**。

### 2.1 本项目只用了 API 的 7 个方法

| API | 是否使用 | 用途 |
|---|---|---|
| `MsgHub.builder()` | ✅ | 构造入口 |
| `.name(String)` | ✅ | 日志标识 |
| `.participants(AgentBase...)` | ✅ | 注册 Agent |
| `.enableAutoBroadcast(true)` | ✅ | **关键开关** |
| `.build()` | ✅ | 创建实例 |
| `hub.enter()` | ✅ | 启动 Hub,返回 `Mono<MsgHub>` |
| `hub.exit()` | ✅ | 关闭 Hub,返回 `Mono<Void>` |
| `hub.broadcast(Msg)` | ❌ | 显式广播,**不**调,靠 autoBroadcast |
| `hub.add/delete/setAutoBroadcast` | ❌ | 动态成员管理,**不**调 |
| `Pipelines.sequential/fanout/compose` | ❌ | 流水线工具,**不**用 |

### 2.2 `autoBroadcast=true` 的工作机制(从 `agentscope-core-1.0.12.jar` 反编译)

```java
public class MsgHub implements AutoCloseable {
    private boolean entered;

    public Mono<MsgHub> enter() {
        // 1. entered = true
        // 2. resetSubscribers(): 遍历 participants,
        //    把"我之外的其他人"塞进每个 agent 的 hubSubscribers 映射
    }

    public Mono<Void> exit() {
        // 1. entered = false
        // 2. 清空每个 agent 的 hubSubscribers
    }
}
```

`AgentBase`(ReActAgent 的父类)内部有 `Map<String, List<AgentBase>> hubSubscribers`,每个 Agent 的 `stream()` 在产生新 `Msg` 时会触发 hook:

> **如果 `entered==true && enableAutoBroadcast==true`,就把这条 Msg 转发到 `hubSubscribers` 中所有其他 Agent 的 memory 列表。**

**所以代码里看不到任何 `hub.publish(...)` —— 这是 AgentScope 框架底层的隐式行为,不是项目自定义的逻辑。**

### 2.3 消息流向图(3 Agent 场景)

```
┌─────────────────────────────────────────────────────────────────────┐
│                          MsgHub("promo-hub")                        │
│                       enableAutoBroadcast = true                    │
│                                                                     │
│   ┌──────────────────┐   auto-broadcast   ┌──────────────────┐      │
│   │ copywriterAgent  │ ─────────────────► │ videoProducerAgent│     │
│   │ maxIters=2       │                    │ maxIters=8        │     │
│   │ no toolkit       │ ◄───────────────── │ toolkit=MediaTools│     │
│   │ memory=[userMsg] │   auto-broadcast   │ memory grows as   │     │
│   │                  │                    │ upstream writes   │     │
│   │ Produces:        │                    │                   │     │
│   │  • script 文本   │                    │ Produces:         │     │
│   │  • 镜头列表       │                    │  • text_to_video  │     │
│   │                  │                    │  • check_status   │     │
│   │                  │                    │  • SUCCEEDED 摘要 │     │
│   └──────────────────┘                    └──────────────────┘      │
│            ▲                                         │              │
│            │                                         ▼              │
│            │              ┌──────────────────┐                       │
│            │              │ fileCollectorAgent│                      │
│            └──────────────│ maxIters=3        │                      │
│       auto-broadcast      │ toolkit=PromoDemo │                      │
│                           └──────────────────┘                       │
└─────────────────────────────────────────────────────────────────────┘

时间线 (Flux.concat 强制顺序):
  T0: userMsg 进入所有 3 个 Agent 的 memory
  T1: copywriterAgent.stream() ── produces 1..N 个 Event
       每个 Event → 自动 broadcast 到 videoProducer + fileCollector
  T2: videoProducerAgent.stream() ── consumes 共享 memory
       读文案 → 调 text_to_video → 轮询 status → 输出 SUCCEEDED 摘要
  T3: fileCollectorAgent.stream() ── consumes 共享 memory
       读 taskId → 调 download_video_file → 输出 saved path
  T4: concat 结束 → Flux.using 触发 cleanup
  T5: hub.exit() + MediaTools.clearCurrentRequest()
```

### 2.4 顺序与并发的对比

| 维度 | 本项目选择 | 备选方案 |
|---|---|---|
| 执行顺序 | **严格串行**(`Flux.concat` 3 个 stream) | `Pipelines.fanout` 让 Agent 并行 |
| 为什么串行? | 后一个 Agent **依赖**前一个的输出(视频必须等文案;下载必须等 taskId) | — |
| Hub 自身 | **不知道**顺序,Hub 只做"广播共享" | Hub 也可以配合并行 `Pipelines.fanout` |
| 消息可见性 | 上游一输出,下游在 `Flux.concat` 切换时**立刻**看到(autoBroadcast 已推送) | 若用并行,需"等所有上游完成"才能启动下游 |

`Flux.concat` + `autoBroadcast` 的组合 === **链式管线 + 共享黑板**。

---

## 3. 与生产版 `ChatAgentService` 的对比

| 维度 | `ChatAgentService`(生产) | `AiPromoDemoService`(Demo) |
|---|---|---|
| 文件 | `agent/ChatAgentService.java`(180 行) | `demo/AiPromoDemoService.java`(219 行) |
| Bean 条件 | 无 — 永远存在 | `@ConditionalOnProperty(...ai-promo.enabled=true)` |
| Agent 数量 | 2(`routingModelsAgent` + `routingAdvisorAgent`) | 3(`copywriter` + `videoProducer` + `fileCollector`) |
| Hub name | `"routing-hub"` | `"promo-hub"` |
| 工具分工 | `routingModelsAgent` 用 `MediaTools`;advisor 不用 | `videoProducer` 用 `MediaTools`;`fileCollector` 用 `PromoDemoTools`;`copywriter` 不用 |
| 业务目的 | "问模型能选哪个 provider,让 advisor 选最优" | "写文案 → 生成视频 → 下载到本地" |
| user Msg metadata | `{tenantId, requestId}` | `{tenantId, requestId, saveTo, duration, language}` |
| `toChunk` / `textOf` | **完全相同** | **完全相同** |
| `Flux.using` 三段 | **完全相同** | **完全相同** |
| `doOnSubscribe/doFinally` | **完全相同** | **完全相同** |

**结论:** 这两个 service 是**同模板的 N=2 / N=3 实例**。一旦理解了 `AiPromoDemoService`,生产版完全一致 —— 这就是 Refactor `2324371` 想要达到的效果:**统一多 Agent 编排模板**。

---

## 4. 8 条关键 takeaway

1. **Spring service 不是 Agent,是编排者**。`AiPromoDemoService` 自己不做推理,只负责:解析上下文 → 构造 Hub → 串接 Agent stream → 包装成 SSE。
2. **顺序由 `Flux.concat` 强制**,Hub 不知道也不关心谁先谁后。
3. **消息共享由 `autoBroadcast` 隐式完成**。代码里看不到 `hub.publish(...)`,因为 AgentScope 在 `AgentBase` 内部 hook 了"我产出的 Msg 自动推给 hub 内其他 Agent 的 memory"。
4. **`Flux.using` 是 Hub 生命周期的正确绑法** —— 订阅即 enter,终止即 exit,且 3 种终止信号(成功/错误/取消)都会触发 cleanup。
5. **`MediaTools.setCurrentRequest` / `clearCurrentRequest` 是 Reactor 线程的 ThreadLocal 修复**,与 Hub 生命周期**独立**但**配对**。
6. **`@ConditionalOnProperty` 是 demo 特性的开关** —— `agentscope.demo.ai-promo.enabled=false` 时整个 service + controller + 3 个 demo Agent bean 都不会被创建,SSE 端点直接 404,生产路径不受影响。
7. **生产与 demo 共用同一套模板** —— 理解了 `AiPromoDemoService`(3 Agent)就理解了 `ChatAgentService`(2 Agent),反之亦然。
8. **`MsgHub` 是"被动的消息总线",不是"主动的工作流引擎"**。Hub 帮 Agent 看见彼此的输出,但**谁依赖谁、谁先谁后**由外部代码 + sysPrompt 决定。

---

## 5. 关键文件清单

| 角色 | 文件 | 关键行号 |
|---|---|---|
| Demo service (本次分析主体) | `business/src/main/java/io/agentscope/router/business/demo/AiPromoDemoService.java` | L101-L174 (stream 方法体) |
| Demo controller | `api/src/main/java/io/agentscope/router/api/controller/DemoController.java` | L61-L83 (SSE 入口) |
| Agent Bean 装配 | `business/src/main/java/io/agentscope/router/business/agent/AgentConfig.java` | L154-L312 (3 demo Agent + 2 demo Toolkit) |
| 生产版对照 | `business/src/main/java/io/agentscope/router/business/agent/ChatAgentService.java` | L74-L133 (2-Agent 范式) |
| 视频工具(被 videoProducer 用) | `business/src/main/java/io/agentscope/router/business/tools/MediaTools.java` | `text_to_video` / `check_video_status` |
| 下载工具(被 fileCollector 用) | `business/src/main/java/io/agentscope/router/business/tools/PromoDemoTools.java` | `download_video_file` |
| 重构 commit | `2324371` | `refactor(agent): split monolithic agents into real MsgHub multi-agent teams` |
| MsgHub 字节码 | `~/.m2/repository/io/agentscope/agentscope-core/1.0.12/agentscope-core-1.0.12.jar` | `io/agentscope/core/pipeline/MsgHub.class` |

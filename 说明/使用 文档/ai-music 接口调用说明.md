# `/api/v1/demo/ai-music` 接口调用说明

> 本文档记录 `ai-music` 端到端音乐生成接口的调用方式、参数、响应格式与处理流程。
> 实现位于 `agentscope` 项目(根目录 `/root/coding_plan/agentscope`),由 3 个 ReAct agent 协同完成。

---

## 1. 概述

`POST /api/v1/demo/ai-music` 接收一段主题描述(中文/英文均可)与期望时长,经过:

```
copywriter  →  musicProducer  →  fileCollector
   写歌词         调 music-2.6-free        落盘到 outputDir
```

三步流水线,生成一段带结构化歌词的音乐 MP3,落盘到 `agentscope-output/ai-music-{duration}s.mp3`。

- **LLM**: minimax(`MINIMAX_API_KEY`,env-var 注入)
- **音乐模型**: `music-2.6-free`(minimax 官方推荐免费模型,RPM 受限)
- **音乐 API**: `POST https://api.minimaxi.com/v1/music_generation`
- **音乐接口文档**: https://platform.minimaxi.com/docs/api-reference/music-generation

---

## 2. 快速开始

### 2.1 编译

```bash
cd /root/coding_plan/agentscope
export http_proxy="http://172.20.176.1:7897" https_proxy="http://172.20.176.1:7897"
/mnt/d/software_install/maven/apache-maven-3.9.11/bin/mvn -B \
    -Dmaven.repo.local=/mnt/d/software_install/maven/maven-repository-3.9.11 \
    -DskipTests -pl common,llm,business,api,launcher -am clean package
```

产物:`launcher/target/agentscope-router.jar`(可执行 Spring Boot fat-jar)

### 2.2 启动

```bash
MINIMAX_API_KEY="<your minimax api key>" \
    java -jar launcher/target/agentscope-router.jar \
    --spring.profiles.active=smoke > /tmp/agentscope.log 2>&1 &

# 等待就绪
until curl -s http://localhost:8080/actuator/health | grep -q '"status":"UP"'; do sleep 1; done
```

`smoke` profile 启用 minimax provider + 把路由 TTFT 超时放宽到 15s 适配冷启动。
`MINIMAX_API_KEY` 必须从环境变量注入,**绝不写进 yml / 源码**。

### 2.3 一行 curl 调用

```bash
curl -sN -X POST http://localhost:8080/api/v1/demo/ai-music \
    -H 'Content-Type: application/json' \
    -H 'X-Tenant-Id: smoke' \
    -d '{
      "topic": "帮我生成一段宣传本届世界杯关于法国队的10秒钟音乐,先生成中文歌词,然后调用生成音乐的agent",
      "duration": 10
    }'
```

### 2.4 验证产物

```bash
ls -lh /root/coding_plan/agentscope/agentscope-output/ai-music-10s.mp3
file   /root/coding_plan/agentscope/agentscope-output/ai-music-10s.mp3
# 期望: Audio file with ID3 version 2.4.0, contains: MPEG ADTS, layer III, v1, 256 kbps, 44.1 kHz, Stereo
```

---

## 3. 接口参数详解

### 3.1 Endpoint

| 项 | 值 |
|----|----|
| Method | `POST` |
| Path | `/api/v1/demo/ai-music` |
| Content-Type | `application/json` |
| Auth | 无(本 demo 端点公开,但要求 L1 多租户头) |

### 3.2 Headers

| Header | 必填 | 说明 |
|--------|------|------|
| `Content-Type` | 是 | `application/json` |
| `X-Tenant-Id` | 是 | 租户 ID,匹配正则 `[a-zA-Z0-9_-]{1,64}`。本机可用 `smoke` |

### 3.3 Request Body

| 字段 | 类型 | 必填 | 范围 / 默认 | 说明 |
|------|------|------|-------------|------|
| `topic` | string | 是 | 非空 | 音乐主题(如 `"世界杯法国队宣传"`) |
| `duration` | integer | 否 | `1..600` | 期望时长(秒),仅作为给 LLM 写歌词的语义提示,不传给 minimax(因为 `music-2.6-free` 不接 `duration` 参数,实际生成时长由模型自行决定) |

**字段名注意**: 早期接口字段叫 `prompt`,现已统一为 `topic`。传 `prompt` 会回 `INVALID_TENANT_ID` 错误并提示"Request body must contain a non-blank 'topic' field"。

### 3.4 校验失败响应

```json
{
  "code": "INVALID_TENANT_ID",
  "message": "Request body must contain a non-blank 'topic' field",
  "timestamp": "2026-06-11T06:41:59.999301600Z"
}
```

错误码见 `common/.../ErrorCode.java`;`duration` 越界回 `INVALID_VIDEO_DURATION`(语义保留,follow-up 可重命名)。

---

## 4. 响应格式(SSE 流)

接口以 `text/event-stream` 返回增量事件,**每个事件一行 `data: {json}`**。事件类型如下:

| `eventType` | `type` | 含义 |
|-------------|--------|------|
| `REASONING` | `agent` | agent 的中间思考(token 级流式) |
| `AGENT_RESULT` | `agent` | agent 的最终输出(可能与最近一条 REASONING 重复) |
| `TOOL_RESULT` | `agent` | 工具调用结果(如 `text_to_music` 返回的 tempPath) |

完整事件顺序(3 个 agent 顺序触发):

```
# 1. copywriterAgent 思考 + 输出歌词
data: {"type":"agent","eventType":"REASONING","role":"ASSISTANT","content":"<think>…用户想要 10 秒法国队…</think>\n\n[verse]\n高卢雄鸡出征，…","isLast":false}
data: {"type":"agent","eventType":"AGENT_RESULT","role":"ASSISTANT","content":"[verse]\n高卢雄鸡出征，\n…","isLast":true}

# 2. musicProducerAgent 调 text_to_music
data: {"type":"agent","eventType":"REASONING","role":"ASSISTANT","content":"…call the text_to_music tool…","isLast":false}
data: {"type":"agent","eventType":"REASONING","role":"ASSISTANT","content":"","isLast":true,"toolCalls":[{"id":"…","input":{"lyrics":"…","prompt":"upbeat pop, celebratory, ~10 seconds, energetic vocals, sports anthem feel"},"name":"text_to_music"}]}
data: {"type":"agent","eventType":"TOOL_RESULT","role":"TOOL","toolResults":[{"id":"…","name":"text_to_music","output":"[{\"tempPath\":\"./agentscope-output/_tmp_music-<uuid>.mp3\",\"model\":null,\"fileSize\":1132972,\"audioLengthMs\":0,\"fileExtension\":\"mp3\"}]"}]}
data: {"type":"agent","eventType":"REASONING","role":"ASSISTANT","content":"MUSIC_READY: ./agentscope-output/_tmp_music-<uuid>.mp3","isLast":true}

# 3. fileCollectorAgent 调 download_music_file
data: {"type":"agent","eventType":"REASONING","role":"ASSISTANT","content":"…call download_music_file…","isLast":false}
data: {"type":"agent","eventType":"REASONING","role":"ASSISTANT","content":"","isLast":true,"toolCalls":[{"id":"…","input":{"audio_path":"…","save_to":"/…/ai-music-10s.mp3"},"name":"download_music_file"}]}
data: {"type":"agent","eventType":"TOOL_RESULT","role":"TOOL","toolResults":[{"id":"…","name":"download_music_file","output":"[\"/…/ai-music-10s.mp3\"]"}]}
data: {"type":"agent","eventType":"AGENT_RESULT","role":"ASSISTANT","content":"SAVED: /…/ai-music-10s.mp3","isLast":true}
```

**关键标记字符串**(agent 间约定的 hub 协议):
- `MUSIC_READY: <tempPath>` — musicProducer 调完 `text_to_music` 后发出
- `SAVED: <saveTo>` — fileCollector 调完 `download_music_file` 后发出

---

## 5. 处理流程(3-Agent 流水线)

```
                           ┌──────────────────────┐
       topic,duration ───→ │  DemoController      │
                           │  POST /ai-music      │
                           └──────────┬───────────┘
                                      │
                                      ▼
                           ┌──────────────────────┐
                           │ AiPromoDemoService   │
                           │  (orchestrator)      │
                           │  组装 user message:  │
                           │  "Generate a {n}-s   │
                           │   music about: {topic}│
                           │   Save to: {path}"   │
                           └──────────┬───────────┘
                                      │
            ┌─────────────────────────┼─────────────────────────┐
            ▼                         ▼                         ▼
   ┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐
   │ copywriterAgent  │ →  │ musicProducer    │ →  │ fileCollector    │
   │ (ReAct)          │    │ Agent (ReAct)    │    │ Agent (ReAct)    │
   │                  │    │                  │    │                  │
   │ sysPrompt:       │    │ sysPrompt:       │    │ sysPrompt:       │
   │ 写带 [verse]/    │    │ 调 text_to_music │    │ 解析 MUSIC_READY │
   │ [chorus] 标签的  │    │ EXACTLY ONCE,    │    │ 调 download_     │
   │ 歌词,不调工具    │    │ 然后发 tempPath  │    │ music_file,发    │
   │                  │    │ 到 hub           │    │ SAVED 到 hub     │
   │ 输出: 歌词       │    │                  │    │                  │
   │                  │    │ 工具: text_to_   │    │ 工具: download_  │
   │                  │    │ music(lyrics,    │    │ music_file       │
   │                  │    │ prompt, model)   │    │ (audioPath,      │
   │                  │    │                  │    │  saveTo)         │
   └──────────────────┘    └────────┬─────────┘    └────────┬─────────┘
            MsgHub:                 │                      │
            "lyrics"  ─────────────→│                      │
                                     │   MUSIC_READY: <p>  │
                                     │ ────────────────────→│
                                     │                      │
                                     │                      ▼
                                     │              outputDir/ai-music-{n}s.mp3
                                     ▼
                              (中间态 hub)
```

### 5.1 Agent 协同协议

- 三个 agent 通过 `MsgHub`(AgentScope 1.0.12 提供的共享消息总线)交换**纯文本消息**。
- 不调工具的 agent(`copywriter`)直接 publish 歌词到 hub。
- 调工具的 agent(`musicProducer` / `fileCollector`)在工具返回后 publish 一个**约定字符串** 到 hub,让下游 agent 能用字符串匹配触发。

### 5.2 LLM sysPrompt 约束(摘自 `AgentConfig.java`)

- `copywriter`: 写带 `[verse]/[chorus]/[bridge]` 标签的歌词,长度 ≤ 800 字符,**不调任何工具**,只输出歌词。
- `musicProducer`: 调 `text_to_music` **恰好 1 次**,成功后发 `MUSIC_READY: <tempPath>`,不重试、不轮询、不调其他工具。
- `fileCollector`: 看到 `MUSIC_READY: ` 前缀消息,提取路径后调 `download_music_file(audioPath, saveTo)`,发 `SAVED: <returned-path>`。

---

## 6. 输出文件

### 6.1 落盘路径

- **最终文件**: `{outputDir}/ai-music-{duration}s.mp3`,默认 `outputDir=./agentscope-output`,可通过 `agentscope.demo.ai-promo.output-dir` 改。
- **临时文件**: `{outputDir}/_tmp_music-{uuid}.{ext}`,由 `MultimodalService.persistAudio()` 写入,落盘 MP3 后被 `fileCollector` 用 `Files.copy` 拷到最终路径。

### 6.2 文件格式

实测 minimax music-2.6-free 输出(以 10s `topic` 为例):

| 属性 | 值 |
|------|----|
| 大小 | ~1.1 MB(取决于模型生成的实际时长,典型 30-40s) |
| 容器 | MPEG ADTS, layer III, v1 |
| 比特率 | 256 kbps CBR |
| 采样率 | 44.1 kHz |
| 声道 | Stereo |
| ID3 标签 | v2.4.0 |

`file` 命令识别为:
```
Audio file with ID3 version 2.4.0, contains: MPEG ADTS, layer III, v1, 256 kbps, 44.1 kHz, Stereo
```

### 6.3 临时文件清理

`{outputDir}/_tmp_music-{uuid}.mp3` 不会自动删除 — 设计上让 `fileCollector` 负责拷贝,临时文件由运维定期 GC。如要立即清理:
```bash
rm -f /root/coding_plan/agentscope/agentscope-output/_tmp_music-*.mp3
```

---

## 7. 已知限制

| 现象 | 原因 | 处置 |
|------|------|------|
| 工具返回里 `model: null` | minimax music-2.6-free 响应**没有** `data.model` 字段(实测) | 不影响功能,忽略 |
| 工具返回里 `audioLengthMs: 0` | minimax 响应**没有** `data.extra_info` 字段(实测),文档里写的 `music_duration` 不返 | 不影响功能,文件本身有效;若要估算可用 `fileSize*8/bitrate` |
| 生成的 MP3 约 30-40s,不是用户传的 `duration` 秒 | minimax music-2.6-free **不接 `duration` 参数**;`duration` 字段只作为给 LLM 写歌词的语义提示 | 真实时长由模型决定,文档需向用户说明 |
| minimax 视频模型报 `status_code=2056 "usage limit exceeded"` | 用户账号对所有视频模型都超配额 | 本接口用 music 模型绕开,与视频链路无关 |
| `ErrorCode.INVALID_VIDEO_DURATION` 语义不准 | duration 字段仍用旧错误码 | 兼容旧调用;follow-up 重命名 |

---

## 8. 故障排查

### 8.1 服务起不来

```bash
tail -100 /tmp/agentscope.log | grep -iE "error|exception|caused by"
```

常见原因:
- `MINIMAX_API_KEY` 未设 → provider 启动失败
- Redis 未启动 → 启动时探测失败。加 `--spring.data.redis.repositories.enabled=false` 或 `redis-cli ping` 检查

### 8.2 `/actuator/health` 不 UP

```bash
curl -sv http://localhost:8080/actuator/health
```

Spring Boot 启动典型耗时 3-5s(冷启动含 Tomcat + minimax provider 初始化),等待后重试。

### 8.3 音乐生成报 `status_code != 0`

`/tmp/agentscope.log` 搜索 `minimax music API error`:
- `2013` — 模型参数不匹配(检查是否误传了 `refer_voice` / `audio_setting`)
- `2056` — usage limit exceeded(用户配额)
- `1002` / `1004` / `1008` — 鉴权 / 限流 / 余额,检查 API key 是否有效

### 8.4 3-agent 卡住

`/tmp/agentscope.log` 搜 `textToMusic` / `download_music_file` 看是哪个 agent 卡住。常见:
- copywriter 死循环 → 调大 `agentscope.agent.max-iterations`(默认 5)
- musicProducer 多次重试 → 已在 sysPrompt 写死"EXACTLY ONCE",若发生说明 LLM 不听话,可考虑换更小的模型

---

## 9. 配置项速查

| 配置 | 默认 | 含义 |
|------|------|------|
| `agentscope.providers.minimax.enabled` | `true` | 启用 minimax LLM |
| `agentscope.providers.minimax.api-key` | `${MINIMAX_API_KEY:}` | env-var 注入 |
| `agentscope.providers.minimax.base-url` | `https://api.minimaxi.com` | minimax baseUrl(不带 `/v1`,由 OpenAIClient 补) |
| `agentscope.demo.ai-promo.enabled` | `true` | 启用 `/ai-music` 端点 |
| `agentscope.demo.ai-promo.output-dir` | `./agentscope-output` | MP3 落盘目录 |
| `agentscope.demo.ai-promo.default-duration` | `10` | curl 不传 `duration` 时的默认值 |
| `agentscope.multimodal.minimax.music-model` | `music-2.6-free` | 音乐模型 |
| `agentscope.multimodal.minimax.music-prompt` | `upbeat pop, suitable for a short promo, ~10 seconds` | 默认风格描述(LLM 调 `text_to_music` 不传 `prompt` 时回退用) |
| `agentscope.routing.ttft-timeout-ms` | `15000` | 单次 LLM 调用 TTFT 超时,smoke profile 放宽到 15s |

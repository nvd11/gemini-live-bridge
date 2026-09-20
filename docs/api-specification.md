# Gemini Live Bridge - 接口与协议规格说明书 (API & Protocol Spec)

| 版本 | 日期 | 状态 | 规范范围 |
| :--- | :--- | :--- | :--- |
| v1.0.0 | 2026-09-20 | 正式定稿 | RESTful API, IM Webhooks, WebSocket Protocol |

---

## 目录
1. [全局规范与约定](#1-全局规范与约定)
2. [IM 交互与 Webhook 接口](#2-im-交互与-webhook-接口)
   - 2.1 飞书指令与卡片回调 (`/api/v1/feishu/*`)
   - 2.2 Slack 指令与卡片交互 (`/api/v1/slack/*`)
3. [呼叫会话管理接口](#3-呼叫会话管理接口)
   - 3.1 创建通话会话 (`POST /api/v1/call/session`)
   - 3.2 校验会话有效性 (`GET /api/v1/call/session/{token}/validate`)
   - 3.3 结束通话会话 (`POST /api/v1/call/session/{token}/terminate`)
4. [全双工 WebSocket 流式协议](#4-全双工-websocket-流式协议)
   - 4.1 握手与连接点 (`/ws/live/{token}`)
   - 4.2 上行报文规范 (Client -> Server)
   - 4.3 下行报文规范 (Server -> Client)
   - 4.4 打断控制机制 (Barge-in Sequence)
5. [前端静态资源与页面接入](#5-前端静态资源与页面接入)
6. [监控与运维接口](#6-监控与运维接口)
7. [统一错误码与异常定义](#7-统一错误码与异常定义)

---

## 1. 全局规范与约定

- **基础 URL**: `https://<DOMAIN>`
- **WSS URL**: `wss://<DOMAIN>`
- **字符编码**: 统一采用 `UTF-8`
- **时间格式**: ISO 8601 标准 (`YYYY-MM-DDTHH:mm:ssZ`)
- **认证机制**:
  - IM 回调基于各平台签名校验（飞书 Header 签名 / Slack `X-Slack-Signature`）
  - 内部管理接口采用 `X-API-Key` 鉴权
  - 客户端通话连接采用单次短期凭证 `token`
- **统一响应外层结构**:
  ```json
  {
    "code": 0,
    "message": "success",
    "data": {},
    "timestamp": 1726848000
  }
  ```

---

## 2. IM 交互与 Webhook 接口

### 2.1 飞书 (Feishu) 接口规范

#### 2.1.1 飞书事件与指令入口 (Event / Command Webhook)
- **URL**: `POST /api/v1/feishu/event`
- **说明**: 接收飞书开放平台推送的事件，包含 URL 挑战（Challenge Verification）以及用户触发的 Slash Command `/call` 或 `@机器人 call` 事件。
- **请求头 (Headers)**:
  - `Content-Type: application/json; charset=utf-8`
  - `X-Lark-Request-Timestamp: <timestamp>`
  - `X-Lark-Request-Nonce: <nonce>`
  - `X-Lark-Signature: <signature>`
- **请求体 (Request Body)**:
  - *场景 A: 首次接入挑战*
    ```json
    {
      "challenge": "ajls384kdjx98XX",
      "token": "xxxx_feishu_verification_token",
      "type": "url_verification"
    }
    ```
  - *场景 B: 用户触发指令或消息*
    ```json
    {
      "schema": "2.0",
      "header": {
        "event_id": "e_b471694f483b38c2b7d9039bf",
        "token": "xxxx_feishu_verification_token",
        "create_time": "1726848000000",
        "event_type": "im.message.receive_v1",
        "tenant_key": "tenant_12345"
      },
      "event": {
        "sender": {
          "sender_id": {
            "open_id": "ou_c9876543210abcdef",
            "user_id": "user_123456"
          },
          "sender_type": "user"
        },
        "message": {
          "message_id": "om_5a1234567890",
          "chat_id": "oc_7890abcdef1234",
          "chat_type": "p2p",
          "message_type": "text",
          "content": "{\"text\":\"/call thinking\"}"
        }
      }
    }
    ```
- **响应规格**:
  - *挑战响应 (HTTP 200)*:
    ```json
    { "challenge": "ajls384kdjx98XX" }
    ```
  - *指令响应 (HTTP 200)*:
    ```json
    { "code": 0, "msg": "success" }
    ```
  - *系统动作*: 异步调用飞书发卡片接口回推交互卡片（见下）。

#### 2.1.2 飞书交互卡片回调 (Card Action Callback)
- **URL**: `POST /api/v1/feishu/card-action`
- **说明**: 用户点击卡片上的【📞 发起语音连线】时飞书回传的动作回调。
- **请求体 (Request Body)**:
  ```json
  {
    "open_id": "ou_c9876543210abcdef",
    "user_id": "user_123456",
    "open_message_id": "om_5a1234567890",
    "tenant_key": "tenant_12345",
    "action": {
      "value": {
        "action": "start_voice_call",
        "model_variant": "gemini-3.8-live"
      },
      "tag": "button"
    }
  }
  ```
- **响应体 (HTTP 200)**: 返回更新后的卡片或 toast 提示，并包含呼起 URL。
  ```json
  {
    "toast": {
      "type": "info",
      "content": "通话准备就绪，正在开启..."
    }
  }
  ```

---

### 2.2 Slack 接口规范

#### 2.2.1 Slack Slash Command (`/call`)
- **URL**: `POST /api/v1/slack/command`
- **Content-Type**: `application/x-www-form-urlencoded`
- **请求头 (Headers)**:
  - `X-Slack-Request-Timestamp: 1726848000`
  - `X-Slack-Signature: v0=a2114d57b48eac39b9ad1ea9e66c4664474ac3a95c571b7ff5f86aa6134ea16a`
- **Form 参数**:
  | 参数名 | 类型 | 说明 | 示例 |
  | :--- | :--- | :--- | :--- |
  | `command` | string | 指令名称 | `/call` |
  | `text` | string | 指令附带参数 | `extended-thinking` / 空 |
  | `user_id` | string | 触发用户 Slack ID | `U0AM8G9AARF` |
  | `user_name` | string | 用户名 | `gateman` |
  | `channel_id` | string | 所在频道或会话 ID | `C0B2JA5PSKW` |
  | `trigger_id` | string | 交互动作触发 ID | `13345224609.73847492` |
  | `response_url` | string | 异步响应 URL | `https://hooks.slack.com/commands/...` |
- **响应规格 (HTTP 200)**: 直接下发 Block Kit 富文本交互卡片。
  ```json
  {
    "response_type": "ephemeral",
    "blocks": [
      {
        "type": "header",
        "text": {
          "type": "plain_text",
          "text": "🎙️ Hebe 实时语音连线",
          "emoji": true
        }
      },
      {
        "type": "section",
        "text": {
          "type": "mrkdwn",
          "text": "主人，语音通道已为您建立！点击下方按钮即可进入全双工低延迟通话界面："
        }
      },
      {
        "type": "actions",
        "elements": [
          {
            "type": "button",
            "text": {
              "type": "plain_text",
              "text": "📞 立即接通通话",
              "emoji": true
            },
            "style": "primary",
            "url": "https://voice.domain.com/call?token=ses_live_9b4e72c81a53f0",
            "action_id": "btn_join_voice"
          }
        ]
      },
      {
        "type": "context",
        "elements": [
          {
            "type": "mrkdwn",
            "text": "⚠️ 临时链接 5 分钟内有效，支持随时打断与全双工对讲。"
          }
        ]
      }
    ]
  }
  ```

---

## 3. 呼叫会话管理接口

### 3.1 创建通话会话 (Create Call Session)
- **URL**: `POST /api/v1/call/session`
- **说明**: 内部由 Webhook/Command 调用，或由前端授权发起，生成短期一次性加入令牌。
- **请求头**:
  - `Content-Type: application/json`
  - `X-API-Key: <ADMIN_SECRET_KEY>` (仅外部调用时需要，内部服务可免)
- **请求体 (Request Body)**:
  ```json
  {
    "user_id": "U0AM8G9AARF",
    "platform": "slack",
    "channel_id": "C0B2JA5PSKW",
    "model_name": "gemini-3.8-live",
    "voice_name": "Puck",
    "system_instruction": "你是一个贴心、专业、高效的私人管家，回答言简意赅，语调自然。",
    "ttl_seconds": 300,
    "max_duration_seconds": 1800
  }
  ```
  *字段约束*:
  - `model_name`: 可选 `gemini-3.8-live` (默认) 或 `gemini-3.8-live-extended-thinking`
  - `voice_name`: Google 预设发音人 (`Puck`, `Charon`, `Kore`, `Fenrir`, `Aoede`)
  - `ttl_seconds`: 范围 `60~900` 秒，默认 `300` 秒
- **响应体 (HTTP 200)**:
  ```json
  {
    "code": 0,
    "message": "success",
    "data": {
      "session_id": "call_sess_20260920_0001",
      "token": "ses_live_9b4e72c81a53f0b2ce17698a",
      "call_url": "https://voice.domain.com/call?token=ses_live_9b4e72c81a53f0b2ce17698a",
      "ws_url": "wss://voice.domain.com/ws/live/ses_live_9b4e72c81a53f0b2ce17698a",
      "expires_at": 1726848300,
      "max_duration_seconds": 1800
    },
    "timestamp": 1726848000
  }
  ```

### 3.2 校验会话有效性 (Validate Call Session)
- **URL**: `GET /api/v1/call/session/{token}/validate`
- **说明**: H5 页面加载时执行前置校验，避免无效或过期的 Token 进入麦克风授权逻辑。
- **URL 路径参数**:
  - `token`: 字符串，签发的临时会话令牌。
- **响应体 (HTTP 200)**:
  ```json
  {
    "code": 0,
    "message": "valid",
    "data": {
      "session_id": "call_sess_20260920_0001",
      "user_name": "Jason",
      "agent_name": "Hebe",
      "status": "ready",
      "model_variant": "gemini-3.8-live",
      "voice": "Puck",
      "ttl_remaining_seconds": 284
    },
    "timestamp": 1726848016
  }
  ```
- **错误响应示例 (HTTP 401/404)**:
  ```json
  {
    "code": 40101,
    "message": "Session token has expired or already been consumed.",
    "data": null,
    "timestamp": 1726848016
  }
  ```

### 3.3 结束通话会话 (Terminate Call Session)
- **URL**: `POST /api/v1/call/session/{token}/terminate`
- **说明**: 客户端点击挂断时主动调用的平滑关闭接口（亦可通过 WebSocket 发送 `hangup` 帧）。
- **请求体**:
  ```json
  {
    "reason": "user_hangup"
  }
  ```
- **响应体 (HTTP 200)**:
  ```json
  {
    "code": 0,
    "message": "session terminated",
    "data": {
      "duration_seconds": 142,
      "bytes_uploaded": 4544000,
      "bytes_downloaded": 6816000
    },
    "timestamp": 1726848158
  }
  ```

---

## 4. 全双工 WebSocket 流式协议

### 4.1 握手与连接点
- **连接 URL**: `wss://<DOMAIN>/ws/live/{token}`
- **子协议 (Subprotocols)**: `live.gemini.v1`
- **连接建立规则**:
  1. 服务端校验 `token`，若已过期或已核销，服务端立即发送 WebSocket Close Frame (`1008 Policy Violation`) 并断开。
  2. 握手成功后，服务端将 `token` 状态标记为 `consumed`，并启动与 Google Gemini 3.8 Live API 的长连接通道。
  3. 服务端向客户端推送 `session.ready` 欢迎事件。

---

### 4.2 上行报文规范 (Client -> Server)

客户端与服务端之间通过**混合模式（JSON 控制文本帧 + 二进制音频帧）**传输：

#### 4.2.1 实时音频帧 (Audio PCM Chunk) - 二进制帧 (Binary Frame)
- **传输类型**: WebSocket Binary Message
- **音频技术指标**:
  - **采样率**: `16,000 Hz`
  - **位深**: `16-bit` (Signed Integer)
  - **声道**: `Mono` (单声道)
  - **字节序**: `Little-Endian`
  - **切片间隔**: `100ms`（即每帧固定大小为 `16000 * 2 * 0.1 = 3200 字节`）
- **处理方式**: 后端网关不落盘，直接封入 Gemini Live 上行信道。

#### 4.2.2 客户端控制事件 (Client Control Events) - 文本帧 (Text Frame / JSON)

##### 事件 A: `client.interrupt` (随时打断信号)
用户在 AI 说话过程中开始发声时，前端本地 VAD 立即发出该信号。
```json
{
  "event": "client.interrupt",
  "timestamp": 1726848035120
}
```
*服务端动作*: 立即向 Google 发送取消当前响应信号，截断下行音频队列，并回复 `server.interrupted`。

##### 事件 B: `client.text` (文本插话/附带指令)
用户在通话界面输入文字辅助表达。
```json
{
  "event": "client.text",
  "text": "帮我查一下今天下午三点的日程",
  "timestamp": 1726848045000
}
```

##### 事件 C: `client.mute` (静音状态变更)
```json
{
  "event": "client.mute",
  "muted": true,
  "timestamp": 1726848050000
}
```

##### 事件 D: `client.hangup` (挂断通话)
```json
{
  "event": "client.hangup",
  "reason": "normal",
  "timestamp": 1726848158000
}
```

---

### 4.3 下行报文规范 (Server -> Client)

#### 4.3.1 服务端音频输出帧 (Audio PCM Chunk) - 二进制帧 (Binary Frame)
- **传输类型**: WebSocket Binary Message
- **音频技术指标**:
  - **采样率**: `24,000 Hz` (Google 官方输出规格)
  - **位深**: `16-bit Signed Linear PCM`
  - **声道**: `Mono`
  - **字节序**: `Little-Endian`
- **前端播放**: 客户端 AudioWorklet 接收后直接写入 Circular Buffer 进行超低延迟播放。

#### 4.3.2 服务端控制与字幕事件 (Server Events) - 文本帧 (JSON)

##### 事件 1: `session.ready` (连接准备就绪)
```json
{
  "event": "session.ready",
  "data": {
    "session_id": "call_sess_20260920_0001",
    "model": "gemini-3.8-live",
    "audio_format": {
      "input": { "sample_rate": 16000, "channels": 1, "bit_depth": 16 },
      "output": { "sample_rate": 24000, "channels": 1, "bit_depth": 16 }
    }
  }
}
```

##### 事件 2: `transcript.delta` (双向流式字幕转写)
```json
{
  "event": "transcript.delta",
  "role": "model",       // 可选: "model" 或 "user"
  "delta": "主人您好，",
  "is_final": false,
  "timestamp": 1726848021000
}
```

##### 事件 3: `thinking.delta` (思考链状态透传)
使用 `extended-thinking` 模型时的思考阶段广播。
```json
{
  "event": "thinking.delta",
  "thinking": true,
  "content": "正在分析日历事件冲突...",
  "timestamp": 1726848021500
}
```

##### 事件 4: `server.interrupted` (打断已确认)
确认当前 AI 播报已被打断，前端清空待播音频。
```json
{
  "event": "server.interrupted",
  "timestamp": 1726848035200
}
```

##### 事件 5: `session.closed` (会话结束)
```json
{
  "event": "session.closed",
  "reason": "user_hangup",   // 可选: "user_hangup", "timeout", "max_duration_exceeded", "upstream_error"
  "duration_seconds": 128
}
```

---

## 5. 前端静态资源与页面接入

### 5.1 页面入口
- **URL**: `GET /call`
- **Query 参数**:
  - `token`: 字符串，必填。
- **说明**: 返回轻量单页面应用（SPA）HTML。页面内置：
  1. Web Audio Context 初始化逻辑；
  2. `audio-processor.js` (AudioWorklet 脚本，负责 16kHz PCM 降采样与帧提取)；
  3. 双向动态波形动效与打断控制器；
  4. 响应式布局（完美适配手机竖屏与电脑宽屏）。

---

## 6. 监控与运维接口

### 6.1 健康检查 (Health Check)
- **URL**: `GET /healthz`
- **响应体 (HTTP 200)**:
  ```json
  {
    "status": "UP",
    "version": "1.0.0",
    "active_calls": 2,
    "uptime_seconds": 86400
  }
  ```

### 6.2 Prometheus 指标监控
- **URL**: `GET /metrics`
- **标准指标项**:
  - `active_voice_sessions{model="gemini-3.8-live"}`
  - `voice_call_duration_seconds_bucket`
  - `e2e_voice_latency_ms_bucket`
  - `upstream_gemini_errors_total`

---

## 7. 统一错误码与异常定义

| 错误码 (Code) | HTTP 状态码 | 说明 (Description) | 解决方案 |
| :--- | :--- | :--- | :--- |
| `0` | 200 | 成功 (Success) | 正常交互 |
| `40001` | 400 | 参数校验失败 (Invalid Parameter) | 检查入参格式与必填项 |
| `40101` | 401 | 会话 Token 无效或已过期 | 重新在飞书/Slack 发起 `/call` 获取新卡片 |
| `40102` | 401 | 会话 Token 已被使用 (Consumed) | Token 为单次有效，不可重放连接 |
| `40301` | 403 | 平台签名校验失败 (Invalid Signature) | 检查 Feishu/Slack Secret 配置 |
| `42901` | 429 | 系统并发通话数达到上限 | 扩容实例或稍后重试 |
| `50001` | 500 | 内部服务异常 (Internal Error) | 查看服务端日志 |
| `50201` | 502 | Google Gemini Live API 连接失败 | 检查 Google API Key 与海外网络通道 |
| `50401` | 504 | 上游响应超时 (Upstream Timeout) | 重连或切换备用链路 |

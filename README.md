# Gemini Live Bridge (gemini-live-bridge)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Python 3.11+](https://img.shields.io/badge/python-3.11+-blue.svg)](https://www.python.org/downloads/)
[![FastAPI](https://img.shields.io/badge/Framework-FastAPI-009688.svg)](https://fastapi.tiangolo.com)
[![Google Gemini Live](https://img.shields.io/badge/AI-Gemini%203.8%20Live-4285F4.svg)](https://ai.google.dev)

> 🚀 **Realtime Voice Bridge Service** connecting IM platforms (**Feishu / Lark** & **Slack**) to Google **Gemini 3.8 Live API** for ultra-low latency, full-duplex bidirectional voice calls and natural agent conversations.

---

## 🌟 核心特性 (Key Features)

- 🎙️ **原生端到端全双工实时通话 (Native Full-Duplex Audio)**:
  - 基于 Google `gemini-3.8-live` 及 `gemini-3.8-live-extended-thinking` 原生音频大模型，拒绝传统 "ASR → LLM → TTS" 级联高延迟体验。
  - 上行支持客户端 16kHz PCM 音频实时切片推流，下行接收 24kHz PCM 极清语音平滑播放。
- ⚡ **随时打断与自然插话 (Seamless Barge-in)**:
  - 结合 Web Audio API 与 Gemini Live 原生打断信号，用户开口说话瞬间自动停止远端音频播报，实现真人对讲般流畅体验。
- 🧠 **后台深度思考链 (Extended Thinking)**:
  - 支持挂载带有思维链推理的模型变体，语音交互过程中兼具逻辑推导深度与自然口语化表达。
- 📱 **飞书 / Slack 一键呼起 (IM Integration)**:
  - 用户在 IM 中通过快捷指令（如 `/call`）触发，机器人秒级回推精美交互卡片。
  - 点击卡片直接在飞书内置浏览器或外部安全浏览器中打开轻量 H5 通话界面，零客户端安装门槛。
- 🛡️ **会话安全与隔离 (Ephemeral Token Security)**:
  - 前端严禁直接接触 Gemini API Key；通过后端生成单次有效、短期过期的 `session_token` 完成 WSS 握手与长连接中继。
- 🛠️ **实时工具调用 (Live Function Calling & Transcriptions)**:
  - 支持在双向通话过程中并发进行工具调用与上下文注入，界面同步流式呈现双向文本转写字幕。

---

## 🏗️ 架构概览 (Architecture Overview)

```
[ 用户 / 移动端或电脑端 ]
        │
        │ 1. 触发 /call 指令
        ▼
[ 飞书 (Feishu) / Slack ]
        │
        │ 2. Webhook / Slash Command 回调
        ▼
[ Gemini Live Bridge 微服务 ]  <───────────┐
   ├── 会话控制面 (Token / TTL 管理)        │
   ├── IM 交互卡片渲染器                    │ 4. 点击呼起通话
   └── WebSocket 全双工流式网关 (Proxy)      │
        │                                  │
        │ 3. 下发卡片与一次性 Token 链接       │
        ▼                                  │
[ 手机/PC 浏览器 H5 通话页面 (AudioWorklet) ] ┘
        │
        │ 5. WSS: 16kHz PCM 音频帧 / 文本 / 控制指令
        ▼
[ Gemini Live Bridge: WSS Relay Engine ]
        │
        │ 6. Stateful WSS (google-genai SDK / Direct WSS)
        ▼
[ Google Gemini 3.8 Live API (`gemini-3.8-live`) ]
```

---

## 📚 详细文档导航 (Documentation)

- 📋 [**需求规格说明书 (Product Requirements Document)**](docs/requirements.md): 详述项目背景、用户旅程、功能边界、性能指标与异常流设计。
- 🔌 [**接口与协议规格说明书 (API & Protocol Specification)**](docs/api-specification.md):
  - 飞书 / Slack Webhook 回调与卡片交互接口；
  - 会话管理 RESTful API；
  - 全双工 WebSocket 报文格式（Client/Server 帧定义、PCM 音频二进制流封装、打断机制与状态迁移）；
  - 错误码定义与统一响应规范。
- 📐 [**系统架构与设计时序 (Architecture & Sequence Diagrams)**](docs/architecture.md):
  - 呼叫建立、实时对讲、随时打断（Barge-in）的时序流转图；
  - 前端 AudioWorklet 采集与重采样管线；
  - 后端长连接中继与并发连接池模型。

---

## 📂 仓库目录规划 (Repository Structure)

```text
gemini-live-bridge/
├── docs/                             # 规格文档与架构设计
│   ├── requirements.md               # 需求说明书 (PRD)
│   ├── api-specification.md          # 详细接口与 WS 报文协议规格
│   └── architecture.md               # 架构时序与音频管线
├── app/                              # 服务端源码目录 (待实现)
│   ├── api/                          # REST & Webhook 路由
│   │   ├── feishu.py                 # 飞书回调与卡片交互
│   │   ├── slack.py                  # Slack 交互与 Slash Command
│   │   └── session.py                # 呼叫会话鉴权与 Token 生成
│   ├── websocket/                    # 实时长连接中继
│   │   ├── gateway.py                # 客户端 WebSocket 处理器
│   │   ├── gemini_client.py          # Google Gemini 3.8 Live 客户端
│   │   └── audio_converter.py        # 音频分片与格式转换
│   ├── core/                         # 基础配置、日志与安全
│   └── static/                       # 轻量 H5 电话呼叫前端单页 (AudioWorklet)
├── .env.example                      # 环境变量模版
├── .gitignore
├── requirements.txt                  # 依赖清单
└── README.md
```

---

## ⚙️ 环境配置 (Environment Variables)

复制 `.env.example` 并填入必要配置：

```bash
# 服务监听配置
HOST=0.0.0.0
PORT=8765
PUBLIC_BASE_URL=https://voice.yourdomain.com

# Google Gemini API
GEMINI_API_KEY=your_gemini_api_key_here
GEMINI_MODEL=gemini-3.8-live
GEMINI_VOICE_NAME=Puck                 # 可选: Aoede, Charon, Fenrir, Kore, Puck
SYSTEM_INSTRUCTION="你是一个贴心、专业且高效的私人智能助理，语言风格自然生动，回答言简意赅。"

# 会话与安全
SESSION_SECRET_KEY=your_random_secret_string
SESSION_TTL_SECONDS=300                # 一次性 Token 5 分钟有效
MAX_CALL_DURATION_SECONDS=1800         # 单次通话最大时长 30 分钟

# 飞书应用凭证
FEISHU_APP_ID=cli_xxxxxxxxxxxx
FEISHU_APP_SECRET=xxxxxxxxxxxxxxxx
FEISHU_VERIFICATION_TOKEN=xxxxxxxxxxxx

# Slack 应用凭证
SLACK_BOT_TOKEN=xoxb-xxxxxxxxxxxx
SLACK_SIGNING_SECRET=xxxxxxxxxxxxxxxx
```

---

## 🤝 参与贡献与开发路线

- [x] **Phase 1: 架构与需求定义 (当前阶段)** - 完成系统全流程架构设计、需求规格书、全量接口与 WebSocket 协议规范。
- [ ] **Phase 2: 服务端中继核心打通** - 实现 FastAPI + WebSocket 代理与 Google Gemini 3.8 Live 连接池。
- [ ] **Phase 3: Web H5 语音前端交付** - 基于 AudioWorklet 打造超低延迟 PCM 采集与播放器界面。
- [ ] **Phase 4: 飞书 / Slack 交互闭环** - 接入消息卡片与 Slash Command，进行移动端实测调优。

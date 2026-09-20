# Gemini Live Bridge (gemini-live-bridge)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21%20LTS-ED8B00?logo=openjdk&logoColor=white)](https://adoptium.net)
[![Quarkus 3.x](https://img.shields.io/badge/Framework-Quarkus%203.x-4695EB?logo=quarkus&logoColor=white)](https://quarkus.io)
[![LangChain4j](https://img.shields.io/badge/AI-LangChain4j-FF6F00)](https://github.com/langchain4j/langchain4j)
[![k3s OCI ARM](https://img.shields.io/badge/Deploy-k3s%20OCI%20ARM64-326CE5?logo=kubernetes&logoColor=white)](https://k3s.io)
[![Google Gemini Live](https://img.shields.io/badge/Model-Gemini%203.8%20Live-4285F4?logo=google&logoColor=white)](https://ai.google.dev)

> 🚀 **Realtime Voice Bridge Service** connecting IM platforms (**Feishu / Lark** & **Slack**) to Google **Gemini 3.8 Live API** for ultra-low latency, full-duplex bidirectional voice calls and natural agent conversations.
> 
> **Enterprise Architecture**: Built on **Java 21 + Quarkus 3.x + LangChain4j**, engineered for native performance, zero-copy audio streaming, and seamless deployment on **k3s OCI Free ARM (Ampere A1)**.

---

## 🌟 核心特性 (Key Features)

- 🎙️ **原生端到端全双工实时通话 (Native Full-Duplex Audio)**:
  - 基于 Google `gemini-3.8-live` 及 `gemini-3.8-live-extended-thinking` 原生音频大模型，告别传统 "ASR → LLM → TTS" 级联高延迟。
  - 上行 16kHz PCM (100ms 切片) 直推，下行接收 24kHz PCM 极清语音平滑播放。
- ⚡ **Quarkus 极致吞吐与零拷贝流式转发 (Reactive & Zero-Copy)**:
  - 底层基于 **Eclipse Vert.x** 事件循环，音频分片通过响应式 Buffer 零拷贝直推，常驻内存仅 **~80MB (JVM) / ~30MB (Native)**，GC 毫秒级无感知。
- 🧠 **LangChain4j 强类型智能体大脑 (Agent Brain & Tools)**:
  - 采用 `quarkus-langchain4j` 声明式定义 `@RegisterAiService` 与 `@Tool` 注解，在实时语音对讲中原生触发企业日程查询、邮件发送与数据库操作。
- ⚡ **随时打断与自然插话 (Seamless Barge-in)**:
  - 结合 Web Audio API 与 Gemini Live 原生截断信号，用户开口说话瞬间自动停止远端音频播报，实现真人对讲般流畅体验。
- 📱 **飞书 / Slack 一键呼起 (IM Integration)**:
  - 用户在 IM 中通过 `/call` 触发，机器人秒级回推富文本交互卡片。点击即可直接在移动端飞书内置 Webview 或浏览器中全屏呼起。
- 🛡️ **k3s OCI-Free-ARM 原生适配 (Cloud Native)**:
  - 开箱提供针对 **Oracle Cloud Infrastructure (OCI) Free Tier ARM (Ampere A1, 4 OCPU, 24GB RAM)** 优化的 k3s 编排配置与 Traefik WSS 路由配置。

---

## 🏗️ 系统部署架构图 (OCI ARM k3s)

```
[ 用户 / 移动端或电脑端 ]
        │ 1. 触发 /call 指令
        ▼
[ 飞书 (Feishu) / Slack ]
        │ 2. Webhook / Slash Command
        ▼
[ OCI Free ARM 节点 (Ampere A1) - k3s 集群 ]
   ├── Traefik Ingress (TLS 443 + WSS 升级 + 3600s 长连接保活)
   │        │
   │        ▼
   └── Pod: gemini-live-bridge (Java 21 + Quarkus 3.x)
        ├── [REST 控制面] 飞书/Slack 签名验证 & 单次 Token (5min) 签发
        ├── [实时网关] quarkus-websockets-next 监听 /ws/live/{token}
        ├── [流式中继] Vert.x 零拷贝中继 (16k PCM 上行 / 24k PCM 下行)
        ├── [打断控制器] Barge-in 拦截与 Jitter Buffer 秒级清空
        └── [Agent 大脑] quarkus-langchain4j 驱动 Function Calling 工具调用
                    │
                    │ Stateful WSS (Gemini Live API)
                    ▼
[ Google Gemini 3.8 Live API (`gemini-3.8-live`) ]
```

---

## 📚 详细文档导航 (Documentation)

- 📐 [**系统架构与部署设计说明书 (Architecture & Deployment Spec)**](docs/architecture.md):
  - Java 21 + Quarkus 3.x 响应式分层架构；
  - 音频流 AudioWorklet 采样与 Jitter Buffer 管线；
  - k3s OCI-Free-ARM 节点编排与 Traefik Ingress WSS 配置；
  - 生产级 Dockerfile.arm64 与多阶段构建。
- 📋 [**需求规格说明书 (Product Requirements Document)**](docs/requirements.md): 业务场景、用户旅程、功能边界与 SLA 指标。
- 🔌 [**接口与协议规格说明书 (API & Protocol Specification)**](docs/api-specification.md):
  - 飞书 / Slack Webhook 与交互卡片接口；
  - 会话管理 RESTful 接口；
  - WebSocket 报文格式（PCM 音频二进制帧、JSON 打断与转写控制帧）。

---

## 📂 仓库目录规划 (Repository Structure)

```text
gemini-live-bridge/
├── docs/                             # 规格文档与架构设计
│   ├── architecture.md               # Quarkus 架构与 k3s OCI-ARM 部署设计
│   ├── requirements.md               # 需求说明书 (PRD)
│   └── api-specification.md          # 详细接口与 WS 报文协议规格
├── k8s/                              # k3s / Kubernetes 生产部署清单
│   ├── 00-namespace.yaml             # 专属命名空间 (gemini-bridge)
│   ├── 01-configmap.yaml             # 基础运行时环境变量
│   ├── 02-secret.yaml                # 密钥与证书凭据模版
│   ├── 03-deployment.yaml            # ARM64 节点亲和性与探针编排
│   ├── 04-service.yaml               # 集群内 Service 定义
│   └── 05-ingress.yaml               # Traefik WSS 长连接路由
├── src/                              # Java 源码目录 (待实现)
│   └── main/
│       ├── java/asia/jppwl/bridge/
│       │   ├── api/                  # 飞书/Slack Webhook & Session 控制器
│       │   ├── websocket/            # quarkus-websockets-next 实时网关
│       │   ├── relay/                # Vert.x Gemini Live 双向流式中继
│       │   └── agent/                # LangChain4j Agent & Tools 定义
│       └── resources/
│           ├── application.properties
│           └── META-INF/resources/   # 轻量 H5 电话呼叫前端单页 (AudioWorklet)
├── pom.xml                           # Quarkus 3.x + Java 21 Maven 工程描述
├── .env.example                      # 环境变量模版
├── .gitignore
└── README.md
```

---

## 🚀 k3s 快速部署指引 (OCI ARM 节点)

### 1. 准备密钥配置
修改 `k8s/02-secret.yaml`，填入真实的 Google Gemini API Key 与各平台 Secret：
```yaml
stringData:
  GEMINI_API_KEY: "AIzaSy..."
  FEISHU_APP_SECRET: "sec_..."
  SLACK_SIGNING_SECRET: "slk_..."
  SESSION_SECRET_KEY: "your_random_jwt_secret"
```

### 2. 一键应用到 k3s
```bash
kubectl apply -f k8s/
```

### 3. 查看运行状态与日志
```bash
kubectl get pods -n gemini-bridge -o wide
kubectl logs -n gemini-bridge -l app=gemini-live-bridge -f
```

---

## 🤝 研发路线 (Roadmap)

- [x] **Phase 1: 架构与技术栈定稿 (当前阶段)** - 选型 Java 21 + Quarkus 3.x + LangChain4j，完成全量接口协议、PRD 需求与 k3s OCI ARM 部署架构设计。
- [ ] **Phase 2: Quarkus WebSocket 网关与 Vert.x 桥接** - 实现客户端 WSS 接入与 Google Gemini 3.8 Live API 零拷贝音频双向流转发。
- [ ] **Phase 3: LangChain4j 智能体工具集成** - 注入 Hebe 人设并绑定日程/指令查询 `@Tool`。
- [ ] **Phase 4: H5 网页呼叫界面与飞书/Slack 卡片打通** - 完成 AudioWorklet 采样与端到端测试。

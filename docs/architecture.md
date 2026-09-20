# Gemini Live Bridge - 系统架构与部署设计说明书 (Quarkus on k3s OCI-ARM)

| 文档版本 | 创建日期 | 状态 | 编写人 | 核心技术栈 | 部署目标 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| v2.0.0 | 2026-09-20 | 架构定稿 | Hebe | Java 21 / Quarkus 3.x / LangChain4j | k3s (OCI Free ARM Ampere A1) |

---

## 目录
1. [系统总体架构与设计原则](#1-系统总体架构与设计原则)
2. [技术栈详细选型规格 (Tech Stack)](#2-技术栈详细选型规格-tech-stack)
3. [服务内部架构与分层设计](#3-服务内部架构与分层设计)
   - 3.1 响应式网络与 WebSocket 网关 (`quarkus-websockets-next`)
   - 3.2 智能体与工具大脑 (`quarkus-langchain4j`)
   - 3.3 音频流式中继与双向打断拦截器 (Bidi Audio & Barge-in Pipeline)
4. [k3s on OCI-Free-ARM 部署架构](#4-k3s-on-oci-free-arm-部署架构)
   - 4.1 节点环境与拓扑规范 (OCI Ampere A1)
   - 4.2 网络穿透与 Traefik Ingress WSS 长连接配置
   - 4.3 资源配额与调度亲和性 (NodeSelector & Resources)
5. [Kubernetes (k3s) 生产级编排配置声明](#5-kubernetes-k3s-生产级编排配置声明)
   - 5.1 Namespace & ConfigMap & Secret
   - 5.2 Deployment (ARM64 容器编排与健康检查)
   - 5.3 Service & Ingress (Traefik WSS 路由)
6. [ARM64 容器构建与打包流水线 (OCI-ARM Native / Fast-Jar)](#6-arm64-容器构建与打包流水线)
7. [可观测性、弹性伸缩与安全防护](#7-可观测性弹性伸缩与安全防护)

---

## 1. 系统总体架构与设计原则

```text
+--------------------------------------------------------------------------------------------------+
|                                    客户端层 (Clients / Users)                                    |
|                                                                                                  |
|   [ 飞书 (Feishu) App ]           [ Slack Client ]          [ 手机/PC 浏览器 (AudioWorklet) ]    |
|            |                             |                                  |                    |
|       /call 指令                    /call 指令                        16kHz PCM 音频切片         |
|       消息卡片交互                  交互动作按钮                      实时波形与字幕展示         |
+------------|-----------------------------|----------------------------------|--------------------+
             | HTTPS                       | HTTPS                            | WSS
             +-----------------------------+----------------------------------+
                                           |
                                           v
+--------------------------------------------------------------------------------------------------+
|                   Oracle Cloud Infrastructure (OCI Free Tier) - ARM 节点                         |
|                   Host: oci-free-arm-* (Ampere A1 4 OCPU, 24GB RAM, aarch64)                     |
|                                                                                                  |
|   +------------------------------------------------------------------------------------------+   |
|   |   k3s Ingress Controller (Traefik v2/v3)                                                 |   |
|   |   - TLS/HTTPS 终止 (Let's Encrypt / Custom SSL)                                          |   |
|   |   - WSS 协议升级头支持 (Upgrade, Connection)                                             |   |
|   |   - 响应超时与长连接空闲保活 (Idle Timeout: 3600s)                                       |   |
|   +------------------------------------------------------------------------------------------+   |
|                                              |                                                   |
|                                              v (ClusterIP / Service)                             |
|   +------------------------------------------------------------------------------------------+   |
|   |   Pod: gemini-live-bridge (Quarkus 3.x Native / Fast-Jar Container on aarch64)           |   |
|   |                                                                                          |   |
|   |   [ Web / Webhook 控制面 ]                                                               |   |
|   |     - quarkus-rest: 接收飞书/Slack 回调与签名验证 (HMAC-SHA256)                          |   |
|   |     - Token Manager: 签发 5min 单次有效 Ephemeral Token                                  |   |
|   |                                                                                          |   |
|   |   [ WebSocket 实时网关 (quarkus-websockets-next) ]                                      |   |
|   |     - @WebSocket: 监听 /ws/live/{token}                                                  |   |
|   |     - 二进制帧处理器: 16k PCM (100ms 帧) 零拷贝通道                                      |   |
|   |     - JSON 控制事件: client.interrupt (打断), client.text (文字)                         |   |
|   |                                                                                          |   |
|   |   [ 响应式双向流式中继 (Eclipse Vert.x & Mutiny Engine) ]                                |   |
|   |     - Vert.x WebSocketClient: 直连 Google Gemini Live API WSS                            |   |
|   |     - Jitter Buffer & Backpressure 管理                                                  |   |
|   |     - Barge-in 双向拦截队列清理 (秒级切断扬声器流)                                       |   |
|   |                                                                                          |   |
|   |   [ 智能体与工具大脑 (quarkus-langchain4j) ]                                             |   |
|   |     - @RegisterAiService: 业务 Agent 行为调度                                            |   |
|   |     - @Tool (Function Calling): 语音对话中动态执行日程/邮件/数据库业务                   |   |
|   |                                                                                          |   |
|   |   [ 基础可观测组件 ]                                                                     |   |
|   |     - /q/health (Liveness & Readiness 探针)                                              |   |
|   |     - /q/metrics (Prometheus Micrometer 性能指标)                                        |   |
|   +------------------------------------------------------------------------------------------+   |
+--------------------------------------------------------------------------------------------------+
                                           |
                                           | Stateful WSS (Live API Protocol)
                                           v
+--------------------------------------------------------------------------------------------------+
|                            Google 官方大模型基础设施 (Google Cloud)                                |
|                                                                                                  |
|   [ Gemini 3.8 Live API (`gemini-3.8-live`) ]                                                    |
|   [ Gemini 3.8 Live Extended Thinking (`gemini-3.8-live-extended-thinking`) ]                    |
+--------------------------------------------------------------------------------------------------+
```

---

## 2. 技术栈详细选型规格 (Tech Stack)

| 层次 / 领域 | 技术选型 | 版本规格 | 选型依据与优势 |
| :--- | :--- | :--- | :--- |
| **基础语言** | **Java 21 (LTS)** | Eclipse Temurin 21 (aarch64) | 现代强类型生态、虚拟线程 (Virtual Threads / Loom)、卓越的 ARM64 性能表现 |
| **应用框架** | **Quarkus** | **3.15+ LTS** | 云原生超快启动、超低内存常驻（RSS ~80MB）、原生编译（Native）对 ARM 友好 |
| **底层响应式引擎** | **Eclipse Vert.x** | 内置于 Quarkus | 业界顶级的高并发异步非阻塞网络 I/O 内核，零拷贝（Zero-Copy）处理音视频切片 |
| **全双工 WebSocket** | **`quarkus-websockets-next`** | 3.x | 注解驱动（`@WebSocket`, `@OnBinary`, `@OnTextMessage`），支持高吞吐双向流 |
| **LLM 与智能体框架** | **`quarkus-langchain4j`** | 0.20+ / 1.x | 强类型 Agent 抽象、声明式 `@RegisterAiService`、自动 Function Calling 工具提取 |
| **JSON 序列化** | **Jackson (FasterXML)** | 2.17+ | 极速 JSON 解析与 DTO 绑定，支持 Protobuf 二进制扩展 |
| **构建与依赖管理** | **Apache Maven** | 3.9+ | 与 Quarkus 官方插件生态完美集成 |
| **容器运行时** | **k3s (Kubernetes)** | v1.30+ | 极轻量 K8s 发行版，内嵌 Containerd 与 Traefik Ingress，完美契合边缘与云端单板 |
| **宿主环境** | **OCI Ampere A1** | ARM Neoverse-N1 (aarch64) | 4 OCPU, 24GB RAM 永久免费实例，多核并发处理音频网络流毫无压力 |
| **反向代理与入口网关** | **Traefik Ingress** | k3s 内置 Traefik v2/v3 | 原生支持 WebSocket 连接升级、超时时间自愈配置、ACME/Let's Encrypt 证书自动化 |

---

## 3. 服务内部架构与分层设计

### 3.1 响应式网络与 WebSocket 网关 (`quarkus-websockets-next`)
使用 Quarkus 最新的 WebSockets Next，支持纯响应式或虚拟线程执行模型：
1. **统一端点**：`@WebSocket(path = "/ws/live/{token}")` 负责承接客户端 H5 建立的双向通道；
2. **二进制上行通道**：客户端以 100ms 间隔推送采集的 16kHz PCM 二进制切片（3200 字节/帧），服务端通过 Vert.x `Buffer` 零拷贝管道直发上游；
3. **控制文本帧**：实时解析用户打断（`client.interrupt`）、文本插话（`client.text`）、静音与挂断指令。

### 3.2 智能体与工具大脑 (`quarkus-langchain4j`)
集成 LangChain4j 作为 Agent 控制平面：
1. **声明式服务定义**：
   ```java
   @RegisterAiService(tools = { CalendarTools.class, SystemControlTools.class })
   @ApplicationScoped
   public interface VoiceAgentBrain {
       @SystemMessage("你是主人的贴心专业私人女仆秘书 Hebe，回答简短干练、富有温度。")
       String executeAction(@UserMessage String userPrompt);
   }
   ```
2. **工具自省 (Tool Inspection)**：
   - 带有 `@Tool` 注解的 Java CDI Bean 会在编译期自动生成 JSON Schema，并注入给 Gemini Live API 的 Tool 列表中。
   - 当模型产生 Tool Call 决策时，Quarkus 自动在当前工作线程执行该方法，并将结果作为 `tool_response` 回塞模型继续吐出语音回复。

### 3.3 音频流式中继与打断拦截器 (Bidi Audio & Barge-in Pipeline)
1. **音频流格式规范**：
   - **客户端输入**：`16,000 Hz, 16-bit Signed Linear PCM, Mono, Little-Endian`
   - **服务端输出**：`24,000 Hz, 16-bit Signed Linear PCM, Mono, Little-Endian`
2. **Barge-in 双向打断处理**：
   - 当客户端监测到用户声音触发打断，立即发送 `client.interrupt` 报文；
   - 网关层收到后，同步执行三项操作：
     1. 向 Google WSS 发送 Client Content 截断信令；
     2. 瞬间清空内部尚未发送给客户端的 24kHz 音频缓冲队列（Flush Jitter Buffer）；
     3. 回复客户端 `server.interrupted`，通知前端清空本地 AudioWorklet 播放队列。

---

## 4. k3s on OCI-Free-ARM 部署架构

### 4.1 节点环境与拓扑规范 (OCI Ampere A1)
- **架构标识**：`linux/arm64` (aarch64)
- **节点标签 (Node Labels)**：
  - `kubernetes.io/arch: arm64`
  - `node.kubernetes.io/instance-type: oci-free-arm`
- **节点资源规划**：
  - 4 核心（4 OCPU）+ 24GB 物理内存，为 Java 虚拟线程和网络 I/O 提供了充沛的空间。
  - 微服务实例常规只占 128MB ~ 256MB 内存，支持高并发多路语音通话并行。

### 4.2 网络穿透与 Traefik Ingress WSS 长连接配置
由于语音对讲依赖长时间稳定的 WebSocket 连接（单次通话最长可达 30 分钟），传统的 Ingress 默认 30s~60s 空闲超时会导致连接被强行切断。必须针对 Traefik 配置专用的注解与超时策略：
- `traefik.ingress.kubernetes.io/router.entrypoints: websecure`
- `traefik.ingress.kubernetes.io/router.tls: "true"`
- `traefik.ingress.kubernetes.io/transport.respondingTimeouts.readTimeout: 3600s`
- `traefik.ingress.kubernetes.io/transport.respondingTimeouts.writeTimeout: 3600s`

---

## 5. Kubernetes (k3s) 生产级编排配置声明

本节给出完整的可直接执行的 Kubernetes 资源清单（已配置好 ARM64 亲和性与 Traefik WSS 路由）。

### 5.1 Namespace、ConfigMap 与 Secret (`k8s/01-config.yaml`)

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: gemini-bridge
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: gemini-live-bridge-config
  namespace: gemini-bridge
data:
  QUARKUS_HTTP_PORT: "8080"
  QUARKUS_HTTP_HOST: "0.0.0.0"
  QUARKUS_LOG_LEVEL: "INFO"
  APP_PUBLIC_BASE_URL: "https://voice.jppwl.asia"
  GEMINI_MODEL_NAME: "gemini-3.8-live"
  GEMINI_VOICE_NAME: "Puck"
  SESSION_TTL_SECONDS: "300"
  MAX_CALL_DURATION_SECONDS: "1800"
---
apiVersion: v1
kind: Secret
metadata:
  name: gemini-live-bridge-secrets
  namespace: gemini-bridge
type: Opaque
stringData:
  GEMINI_API_KEY: "AIzaSy..."               # Google Gemini 官方 API Key
  FEISHU_APP_SECRET: "sec_..."              # 飞书 App 密钥
  FEISHU_VERIFICATION_TOKEN: "tok_..."      # 飞书回调验证 Token
  SLACK_SIGNING_SECRET: "slk_..."           # Slack 签名 Secret
  SESSION_SECRET_KEY: "jwt_secret_..."      # Token 生成密钥
```

### 5.2 Deployment 部署清单 (`k8s/02-deployment.yaml`)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: gemini-live-bridge
  namespace: gemini-bridge
  labels:
    app: gemini-live-bridge
spec:
  replicas: 1
  selector:
    matchLabels:
      app: gemini-live-bridge
  template:
    metadata:
      labels:
        app: gemini-live-bridge
    spec:
      # 显式调度到 OCI ARM 节点
      nodeSelector:
        kubernetes.io/arch: arm64
      containers:
        - name: bridge-service
          # 基于 ARM64 构建的轻量镜像
          image: nvd11/gemini-live-bridge:latest
          imagePullPolicy: Always
          ports:
            - name: http-ws
              containerPort: 8080
              protocol: TCP
          envFrom:
            - configMapRef:
                name: gemini-live-bridge-config
            - secretRef:
                name: gemini-live-bridge-secrets
          resources:
            requests:
              cpu: 100m
              memory: 128Mi
            limits:
              cpu: 1000m
              memory: 512Mi
          # Quarkus MicroProfile 存活与就绪探针
          livenessProbe:
            httpGet:
              path: /q/health/live
              port: 8080
            initialDelaySeconds: 5
            periodSeconds: 10
            timeoutSeconds: 3
          readinessProbe:
            httpGet:
              path: /q/health/ready
              port: 8080
            initialDelaySeconds: 3
            periodSeconds: 5
            timeoutSeconds: 2
```

### 5.3 Service 与 Ingress 清单 (`k8s/03-ingress.yaml`)

```yaml
apiVersion: v1
kind: Service
metadata:
  name: gemini-live-bridge-svc
  namespace: gemini-bridge
  labels:
    app: gemini-live-bridge
spec:
  type: ClusterIP
  ports:
    - name: http-ws
      port: 8080
      targetPort: 8080
  selector:
    app: gemini-live-bridge
---
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: gemini-live-bridge-ingress
  namespace: gemini-bridge
  annotations:
    kubernetes.io/ingress.class: traefik
    traefik.ingress.kubernetes.io/router.entrypoints: websecure
    traefik.ingress.kubernetes.io/router.tls: "true"
    # 保证 WebSocket 长时间对讲不被反代强行中断
    traefik.ingress.kubernetes.io/transport.respondingTimeouts.readTimeout: "3600s"
    traefik.ingress.kubernetes.io/transport.respondingTimeouts.writeTimeout: "3600s"
spec:
  rules:
    - host: voice.jppwl.asia
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: gemini-live-bridge-svc
                port:
                  number: 8080
```

---

## 6. ARM64 容器构建与打包流水线

针对 OCI Ampere A1 (ARM64) 节点，构建产物建议优先采用 **Quarkus Fast-Jar (JVM 模式)** 或 **GraalVM Native Binary (原生模式)**：

### 6.1 Dockerfile.arm64 (JVM Fast-Jar 模式 - 首选推荐)
```dockerfile
# 第一阶段：基于 ARM64 编译打包
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw dependency:go-offline

COPY src src
RUN ./mvnw package -DskipTests

# 第二阶段：极小运行环境
FROM eclipse-temurin:21-jre-jammy
ENV LANGUAGE='en_US:en'
WORKDIR /deployments

# 配置虚拟线程与 GC 优化
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=75.0"

COPY --from=build /workspace/target/quarkus-app/lib/ /deployments/lib/
COPY --from=build /workspace/target/quarkus-app/*.jar /deployments/
COPY --from=build /workspace/target/quarkus-app/app/ /deployments/app/
COPY --from=build /workspace/target/quarkus-app/quarkus/ /deployments/quarkus/

EXPOSE 8080
USER 185
ENTRYPOINT [ "java", "-jar", "/deployments/quarkus-run.jar" ]
```

### 6.2 快速多架构/本地构建指令
```bash
# 本地直接使用 docker buildx 构建并推送到 DockerHub
docker buildx build --platform linux/arm64 \
  -t nvd11/gemini-live-bridge:latest \
  -f src/main/docker/Dockerfile.jvm --push .
```

---

## 7. 可观测性与告警指标 (Observability)

微服务默认暴露标准 Quarkus 监控端点：
- **健康检测端点**：`GET /q/health`
  - 自动上报与 Google Live API WSS 的链路连通性、内存水位及活跃会话状态。
- **Prometheus 采集端点**：`GET /q/metrics`
  - `gemini_active_voice_calls`: 当前活跃语音通话数；
  - `gemini_audio_bytes_transferred_total`: 音频上下行流吞吐量；
  - `gemini_barge_in_events_total`: 用户打断触发计数；
  - `jvm_gc_pause_seconds_max`: GC 停顿时间监控（保证流式音频不发生卡顿抖动）。

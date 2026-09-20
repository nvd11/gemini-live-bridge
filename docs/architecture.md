# Gemini Live Bridge - 系统架构与部署设计说明书 (Quarkus on k3s OCI-ARM)

| 文档版本 | 创建日期 | 状态 | 编写人 | 核心技术栈 | 部署目标 | 交付模式 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| v2.1.0 | 2026-09-20 | 架构定稿 | Hebe | Java 21 / Quarkus 3.x / LangChain4j | k3s (OCI Free ARM Ampere A1) | GitHub Actions + ArgoCD (GitOps) |

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
8. [GitOps 全自动持续交付体系 (GitHub Actions + ArgoCD)](#8-gitops-全自动持续交付体系-github-actions--argocd)
   - 8.1 GitOps 核心理念与端到端闭环
   - 8.2 持续集成 (CI) 流水线：多架构镜像构建与推送
   - 8.3 跨仓库触发机制 (Repository Dispatch)
   - 8.4 ArgoCD 编排清单与 App-of-Apps 纳管
   - 8.5 自动化同步 (Auto-Sync) 与零停机滚动更新
   - 8.6 故障自愈 (Self-Healing) 与极速回滚机制

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
| **CI 持续集成** | **GitHub Actions** | Hosted Runner + Buildx | 多架构构建 (`linux/arm64`)、自动推送到 GHCR，触发 GitOps Dispatch |
| **CD 持续交付** | **ArgoCD** | v2.10+ (App-of-Apps) | 声明式 Git 驱动、自动同步 (Auto-Sync)、故障自愈 (Self-Healing) |
| **入口与反向代理** | **Traefik Ingress** | k3s 内置 Traefik v2/v3 | 原生支持 WebSocket 升级、3600s 长连接保活、ACME/Let's Encrypt 证书自动化 |

---

## 3. 服务内部架构与分层设计

### 3.1 响应式网络与 WebSocket 网关 (`quarkus-websockets-next`)
使用 Quarkus 最新的 WebSockets Next，支持纯响应式或虚拟线程执行模型：
1. **统一端点**：`@WebSocket(path = "/ws/live/{token}")` 承接客户端 H5 建立的双向通道；
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
  - `node.kubernetes.io/instance-type: oci-free-arm` 或 `kubernetes.io/hostname: free-arm-vm`
- **节点资源规划**：
  - 4 核心（4 OCPU）+ 24GB 物理内存，为 Java 虚拟线程和网络 I/O 提供了充沛的空间。
  - 微服务实例常规只占 128MB ~ 256MB 内存，支持高并发多路语音通话并行。

### 4.2 网络穿透与 Traefik Ingress WSS 长连接配置
语音对讲依赖长时间稳定的 WebSocket 连接（单次通话最长可达 30 分钟），传统的 Ingress 默认 30s~60s 空闲超时会导致连接被强行切断。必须针对 Traefik 配置专用的注解与超时策略：
- `traefik.ingress.kubernetes.io/router.entrypoints: websecure`
- `traefik.ingress.kubernetes.io/router.tls: "true"`
- `traefik.ingress.kubernetes.io/transport.respondingTimeouts.readTimeout: 3600s`
- `traefik.ingress.kubernetes.io/transport.respondingTimeouts.writeTimeout: 3600s`

---

## 5. Kubernetes (k3s) 生产级编排配置声明

本节给出完整的可直接执行的 Kubernetes 资源清单（已配置好 ARM64 亲和性与 Traefik WSS 路由）。

### 5.1 Namespace、ConfigMap 与 Secret (`k8s/`)

- `k8s/00-namespace.yaml`: 独立命名空间 `gemini-bridge`
- `k8s/01-configmap.yaml`: 运行时环境变量
- `k8s/02-secret.yaml`: 敏感凭据（Gemini API Key、飞书/Slack 签名 Secret、JWT 密钥）

### 5.2 Deployment 部署清单 (`k8s/03-deployment.yaml`)

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
          image: ghcr.io/nvd11/gemini-live-bridge:latest
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
          livenessProbe:
            httpGet:
              path: /q/health/live
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 15
            timeoutSeconds: 3
          readinessProbe:
            httpGet:
              path: /q/health/ready
              port: 8080
            initialDelaySeconds: 5
            periodSeconds: 10
            timeoutSeconds: 3
```

### 5.3 Service 与 Ingress 清单 (`k8s/04-service.yaml` & `k8s/05-ingress.yaml`)

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: gemini-live-bridge-ingress
  namespace: gemini-bridge
  annotations:
    kubernetes.io/ingress.class: traefik
    traefik.ingress.kubernetes.io/router.entrypoints: websecure
    traefik.ingress.kubernetes.io/router.tls: "true"
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

针对 OCI Ampere A1 (ARM64) 节点，构建产物采用 **Quarkus Fast-Jar (JVM 模式)**：

```dockerfile
# 多阶段构建：第一阶段基于 ARM64 Temurin JDK 21 打包
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace
COPY pom.xml mvnw ./
COPY .mvn/ .mvn/
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B || true
COPY src/ src/
RUN ./mvnw package -DskipTests -B

# 第二阶段：极小 JRE 运行环境，启用 Generational ZGC
FROM eclipse-temurin:21-jre-jammy
WORKDIR /deployments
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=75.0"
COPY --from=builder /workspace/target/quarkus-app/lib/ /deployments/lib/
COPY --from=builder /workspace/target/quarkus-app/*.jar /deployments/
COPY --from=builder /workspace/target/quarkus-app/app/ /deployments/app/
COPY --from=builder /workspace/target/quarkus-app/quarkus/ /deployments/quarkus/
EXPOSE 8080
USER 185
ENTRYPOINT [ "sh", "-c", "java $JAVA_OPTS -jar /deployments/quarkus-run.jar" ]
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

---

## 8. GitOps 全自动持续交付体系 (GitHub Actions + ArgoCD)

项目全面遵循现代 **GitOps 黄金标准**：业务代码与集群部署清单物理隔离，集群状态以 Git 仓库为单一信任源（Single Source of Truth），实现**“提交代码即上线，回退 Git 即回滚”**的全自动化无人值守运维。

### 8.1 GitOps 核心流程全景时序图

```mermaid
sequenceDiagram
    autonumber
    actor Dev as 开发者 / Jason
    participant AppRepo as 应用源码库 (gemini-live-bridge)
    participant GHA as GitHub Actions (CI Runner)
    participant GHCR as 容器镜像仓库 (ghcr.io)
    participant CDRepo as GitOps 清单库 (my-argocd-manifests)
    participant Argo as ArgoCD 控制面 (k8s 集群)
    participant K3s as OCI-Free-ARM 节点 (k3s)

    Dev->>AppRepo: git push origin main
    AppRepo->>GHA: 触发 CI 流程 (paths-ignore: docs/**)
    
    rect rgb(240, 248, 255)
    Note over GHA,GHCR: 持续集成 (CI) 阶段
    GHA->>GHA: 1. JDK 21 单元测试与 Maven 打包
    GHA->>GHA: 2. Docker Buildx 构建 ARM64/AMD64 多架构镜像
    GHA->>GHCR: 3. 推送镜像 (tag: <commit-sha> & latest)
    end

    rect rgb(255, 250, 240)
    Note over GHA,CDRepo: 跨仓库唤醒 (GitOps Dispatch) 阶段
    GHA->>CDRepo: POST /repos/nvd11/my-argocd-manifests/dispatches<br/>(event: update-image-tag, tag: <commit-sha>)
    CDRepo->>CDRepo: 运行 CD Configuration Update 工作流
    CDRepo->>CDRepo: sed 修改 argocd-apps/gemini-live-bridge-app.yaml 中的 tag
    CDRepo->>CDRepo: git commit & push (更新 Git 信任源)
    end

    rect rgb(240, 255, 240)
    Note over Argo,K3s: 持续交付与调度部署 (CD) 阶段
    Argo->>CDRepo: 检测到清单版本变更 (Polling / Webhook)
    Argo->>K3s: 对比 Live State 与 Desired State (发现差异 OutOfSync)
    Argo->>K3s: 执行自动化同步 (Auto-Sync) 滚动更新 Pod
    K3s->>GHCR: 拉取最新 ARM64 镜像 (sha tag)
    K3s->>K3s: 启动新 Pod，通过 /q/health/ready 健康检查
    K3s->>K3s: 优雅终止旧 Pod (零宕机切换)
    Argo-->>Dev: 状态刷新为 Synced & Healthy 绿色常态
    end
```

---

### 8.2 持续集成 (CI) 流水线设计 (`.github/workflows/ci-cd.yaml`)

- **触发条件**：仅在 `main` 分支代码发生实际变更时触发，自动忽略 `docs/` 文档与 `.md` 提交，防止无关构建浪费 GitHub Actions 免费额度。
- **跨平台构建优化**：
  - 集成 `docker/setup-qemu-action` 与 `docker/setup-buildx-action`；
  - 采用 GitHub Actions Cache (`type=gha`) 缓存 Maven 依赖与 Docker 镜像分层，将 CI 时长缩短至 2 分钟内。
- **安全免密登录**：利用内置 `${{ secrets.GITHUB_TOKEN }}` 直接具备向 GHCR 发布 Packages 的权限。

---

### 8.3 跨仓库触发机制 (Repository Dispatch)

为了保持代码仓库与部署仓库解耦：
1. 应用仓库 `gemini-live-bridge` 在镜像推送到 GHCR 后，读取 Secret `ARGOCD_MANIFESTS_DISPATCH_TOKEN`（具有 `repo` 作用域的 Personal Access Token）；
2. 向清单仓库 `nvd11/my-argocd-manifests` 发起 `repository_dispatch` 请求：
   ```json
   {
     "event_type": "update-image-tag",
     "client_payload": {
       "svc_name": "gemini-live-bridge",
       "image_tag": "0902a8f8137351651eb72851a6572"
     }
   }
   ```
3. `my-argocd-manifests` 仓库内置的 `update-image-tag.yml` 机器人监听到该事件后，自动执行 `sed` 修改对应 App 的清单文件并提交入库。

---

### 8.4 ArgoCD 编排清单纳管 (`argocd-apps/gemini-live-bridge-app.yaml`)

在 `my-argocd-manifests` 的 `argocd-apps/` 目录下纳入本应用（遵循 App-of-Apps 模式）：

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: gemini-live-bridge
  namespace: argocd
  finalizers:
    - resources-finalizer.argocd.argoproj.io
spec:
  project: default
  source:
    repoURL: 'https://github.com/nvd11/gemini-live-bridge.git'
    path: k8s
    targetRevision: main
  destination:
    name: 'tencent-dp1-cluster' # 或集群直连地址
    namespace: gemini-bridge
  syncPolicy:
    automated:
      prune: true       # 自动清理已在 Git 中删除的废弃资源
      selfHeal: true    # 自动纠偏（若有人在集群中手动篡改，ArgoCD 自动覆盖修正）
    syncOptions:
      - CreateNamespace=true
      - ServerSideApply=true
```

---

### 8.5 自动化同步与故障自愈 (Self-Healing)

1. **自动纠偏 (Self-Healing)**：
   - 任何在 k3s 物理集群内通过 `kubectl edit` 的临时篡改，均会在 3 分钟内被 ArgoCD 强制抹平并回滚到 Git 中声明的状态。
2. **零宕机滚动发布 (Zero-Downtime Rollout)**：
   - Kubernetes Deployment 默认采用 `RollingUpdate`（`maxUnavailable: 0`, `maxSurge: 1`）；
   - 新版本的 Quarkus Pod 必须通过 `/q/health/ready` 探针校验，Traefik Ingress 才会将 WebSocket 新流量接入，正在进行的旧通话通过优雅停机等待自然结束。
3. **极速回滚机制 (Instant Rollback)**：
   - 生产环境一旦发生异常，无需登录服务器调试，只需在 `my-argocd-manifests` 仓库执行一次 `git revert HEAD`；
   - ArgoCD 秒级检测到 Git commit 变化，自动拉取上一个稳定版本的镜像进行回滚，整个过程在 30 秒内全自动闭环。

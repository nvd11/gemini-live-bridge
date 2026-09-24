package asia.jppwl.bridge.websocket;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jboss.logging.Logger;

import asia.jppwl.bridge.domain.CallSession;
import asia.jppwl.bridge.domain.SessionManager;
import asia.jppwl.bridge.domain.SessionSummary;
import asia.jppwl.bridge.relay.DownstreamSink;
import asia.jppwl.bridge.relay.GeminiLiveRelayService;
import asia.jppwl.bridge.relay.GeminiLiveSession;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnBinaryMessage;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 客户端全双工 WebSocket 实时接入网关.
 *
 * <p>端点路径：{@code /ws/live/{token}}
 * <p>纯原生 Vert.x JsonObject 驱动，完全规避 Jackson 反射在 GraalVM Native 下的序列化陷阱。
 */
@WebSocket(path = "/ws/live/{token}")
@ApplicationScoped
public class LiveWebSocketGateway {

    private static final Logger LOG = Logger.getLogger(LiveWebSocketGateway.class);

    public static final int CLOSE_UNAUTHORIZED = 4001;
    public static final int CLOSE_NORMAL = 1000;
    public static final int CLOSE_IDLE_TIMEOUT = 4008;

    /** 连接 ID (conn.id()) 到 CallSession 的映射 */
    private final ConcurrentMap<String, CallSession> connectionSessionMap = new ConcurrentHashMap<>();

    /** 会话 ID 到上游 GeminiLiveSession 的映射 */
    private final ConcurrentMap<String, GeminiLiveSession> upstreamSessionMap = new ConcurrentHashMap<>();

    private final SessionManager sessionManager;
    private final BargeInController bargeInController;
    private final GeminiLiveRelayService relayService;

    @Inject
    public LiveWebSocketGateway(
            SessionManager sessionManager,
            BargeInController bargeInController,
            GeminiLiveRelayService relayService
    ) {
        this.sessionManager = sessionManager;
        this.bargeInController = bargeInController;
        this.relayService = relayService;
    }

    /**
     * 握手接入拦截.
     */
    @OnOpen
    public void onOpen(WebSocketConnection conn, @PathParam("token") String token) {
        LOG.infof("Incoming WebSocket connection attempt from connId=%s with token=%s", conn.id(), token);

        // 1. 原子核销 Token (CAS 防重放验证)
        Optional<CallSession> sessionOpt = sessionManager.validateAndConsumeToken(token);
        if (sessionOpt.isEmpty()) {
            LOG.warnf("Rejecting connection: invalid or consumed token=%s, connId=%s", token, conn.id());
            conn.closeAndAwait(new CloseReason(CLOSE_UNAUTHORIZED, "Unauthorized or token consumed"));
            return;
        }

        CallSession session = sessionOpt.get();
        connectionSessionMap.put(conn.id(), session);

        // 2. 原生构造下发 session.ready 欢迎与音频规格声明帧
        JsonObject readyJson = new JsonObject()
                .put("event", "session.ready")
                .put("data", new JsonObject()
                        .put("session_id", session.sessionId())
                        .put("model", session.modelVariant())
                        .put("audio_format", new JsonObject()
                                .put("input", new JsonObject().put("sample_rate", 16000).put("channels", 1).put("bit_depth", 16))
                                .put("output", new JsonObject().put("sample_rate", 24000).put("channels", 1).put("bit_depth", 16))));

        sendJson(conn, readyJson);
        LOG.infof("WebSocket handshake completed: sessionId=%s, connId=%s, user=%s",
                session.sessionId(), conn.id(), session.userId());

        // 3. 构建下游分发回调接口 (DownstreamSink)
        DownstreamSink sink = new DownstreamSink() {
            @Override
            public void sendAudioChunk(Buffer pcm24kChunk) {
                if (!conn.isClosed()) {
                    conn.sendBinary(pcm24kChunk).subscribe().with(
                            v -> {},
                            err -> LOG.debugf("Failed to send 24k audio chunk to connId=%s", conn.id())
                    );
                }
            }

            @Override
            public void sendTranscriptDelta(String role, String delta, boolean isFinal) {
                JsonObject transcriptJson = new JsonObject()
                        .put("event", "transcript.delta")
                        .put("role", role)
                        .put("delta", delta)
                        .put("is_final", isFinal)
                        .put("timestamp", System.currentTimeMillis());
                sendJson(conn, transcriptJson);
            }

            @Override
            public void sendInterruptedNotification() {
                JsonObject interruptedJson = new JsonObject()
                        .put("event", "server.interrupted")
                        .put("timestamp", System.currentTimeMillis());
                sendJson(conn, interruptedJson);
            }

            @Override
            public void onUpstreamClosed(String reason) {
                LOG.infof("Upstream closed notification received for session %s: %s", session.sessionId(), reason);
                if (!conn.isClosed()) {
                    JsonObject closedJson = new JsonObject()
                            .put("event", "session.closed")
                            .put("reason", reason)
                            .put("duration_seconds", session.getActiveDurationSeconds());
                    sendJson(conn, closedJson);
                    conn.closeAndAwait(new CloseReason(CLOSE_NORMAL, reason));
                }
            }
        };

        // 4. 异步开启直连 Google Gemini Live API 上游 WSS 管道
        relayService.openUpstreamChannel(session, sink)
                .subscribe().with(
                        upstreamSession -> {
                            upstreamSessionMap.put(session.sessionId(), upstreamSession);
                            LOG.infof("Google Gemini Live upstream channel established for session %s", session.sessionId());
                        },
                        err -> {
                            LOG.errorf(err, "Failed to connect to Google Live API for session %s", session.sessionId());
                            JsonObject errJson = new JsonObject().put("event", "session.closed").put("reason", "upstream_error");
                            sendJson(conn, errJson);
                            conn.closeAndAwait(new CloseReason(CLOSE_NORMAL, "upstream_error"));
                        }
                );
    }

    /**
     * 客户端 16kHz PCM 二进制音频切片接收通道 (零拷贝管道直推).
     */
    @OnBinaryMessage
    public void onBinary(Buffer pcmChunk, WebSocketConnection conn) {
        CallSession session = connectionSessionMap.get(conn.id());
        if (session == null || pcmChunk == null || pcmChunk.length() == 0) {
            return;
        }

        // 累加上行音频字节数 (统计计费与监控指标)
        session.recordUploadBytes(pcmChunk.length());

        // 零拷贝直推上游 Google Gemini Live WebSocket 管道
        GeminiLiveSession upstream = upstreamSessionMap.get(session.sessionId());
        if (upstream != null && upstream.isAlive()) {
            upstream.sendAudioChunk(pcmChunk);
        }
    }

    /**
     * 客户端控制事件 (JSON 文本帧) 路由器.
     */
    @OnTextMessage
    public void onTextMessage(String jsonPayload, WebSocketConnection conn) {
        if (jsonPayload == null || jsonPayload.isBlank()) {
            return;
        }

        try {
            JsonObject json = new JsonObject(jsonPayload);
            String event = json.getString("event");
            if (event == null) {
                return;
            }

            switch (event) {
                case "client.ping" -> handleHeartbeat(conn, json);
                case "client.interrupt" -> handleInterrupt(conn);
                case "client.text" -> handleClientText(conn, json);
                case "client.hangup" -> handleHangup(conn, json);
                default -> LOG.debugf("Ignored unhandled client control event: %s", event);
            }
        } catch (Exception e) {
            LOG.warnf("Failed to parse client control frame: %s, error=%s", jsonPayload, e.getMessage());
        }
    }

    /**
     * 抵消 Cloudflare 100s 空闲超时的双向心跳处理 (每 15s 一次).
     */
    public void handleHeartbeat(WebSocketConnection conn, JsonObject json) {
        long ts = json.getLong("timestamp", System.currentTimeMillis());
        JsonObject pong = new JsonObject()
                .put("event", "server.pong")
                .put("timestamp", ts);
        sendJson(conn, pong);
        LOG.trace("Heartbeat cycle handled: client.ping -> server.pong");
    }

    /**
     * 处理客户端 Barge-in 打断事件.
     */
    private void handleInterrupt(WebSocketConnection conn) {
        CallSession session = connectionSessionMap.get(conn.id());
        if (session != null) {
            // 1. 本地 Flush Jitter Buffer
            bargeInController.handleClientInterrupt(session.sessionId());

            // 2. 向上游 Google 发送截断信令
            GeminiLiveSession upstream = upstreamSessionMap.get(session.sessionId());
            if (upstream != null && upstream.isAlive()) {
                upstream.signalInterrupt();
            }

            // 3. 回发打断确认帧给前端，前端重置本地 AudioWorklet 播放器
            JsonObject interrupted = new JsonObject()
                    .put("event", "server.interrupted")
                    .put("timestamp", System.currentTimeMillis());
            sendJson(conn, interrupted);
        }
    }

    /**
     * 处理客户端文字插话 / 辅助指令.
     */
    private void handleClientText(WebSocketConnection conn, JsonObject json) {
        CallSession session = connectionSessionMap.get(conn.id());
        if (session != null) {
            String text = json.getString("text", "");
            LOG.infof("Forwarding client.text to Google for session %s: %s", session.sessionId(), text);

            GeminiLiveSession upstream = upstreamSessionMap.get(session.sessionId());
            if (upstream != null && upstream.isAlive()) {
                upstream.sendTextMessage(text);
            }
        }
    }

    /**
     * 处理客户端主动挂断.
     */
    private void handleHangup(WebSocketConnection conn, JsonObject json) {
        CallSession session = connectionSessionMap.get(conn.id());
        String sessionId = session != null ? session.sessionId() : "unknown";
        String reason = json.getString("reason", "user_hangup");

        LOG.infof("Client requested hangup for session %s, reason=%s", sessionId, reason);
        conn.closeAndAwait(new CloseReason(CLOSE_NORMAL, reason));
    }

    /**
     * 连接断开回调：结算会话生命周期、关闭上游长连接并清理内存.
     */
    @OnClose
    public void onClose(WebSocketConnection conn) {
        CallSession session = connectionSessionMap.remove(conn.id());
        if (session != null) {
            String sessionId = session.sessionId();

            // 1. 关闭上游 Google Live 长连接
            GeminiLiveSession upstream = upstreamSessionMap.remove(sessionId);
            if (upstream != null) {
                upstream.close();
            }

            // 2. 结算通话与清理 Jitter Buffer
            Optional<SessionSummary> summaryOpt = sessionManager.terminateSession(sessionId, "connection_closed");
            bargeInController.cleanupSession(sessionId);
            summaryOpt.ifPresent(s -> LOG.infof("Call session closed: id=%s, duration=%ds, uploaded=%d bytes",
                    sessionId, s.durationSeconds(), s.bytesUploaded()));
        }
    }

    /**
     * 异常边界捕获.
     */
    @OnError
    public void onError(WebSocketConnection conn, Throwable error) {
        CallSession session = connectionSessionMap.get(conn.id());
        String sessionId = session != null ? session.sessionId() : conn.id();
        LOG.errorf(error, "WebSocket connection error for session: %s", sessionId);
    }

    /**
     * 安全序列化并异步下发 JSON 文本帧 (原生 Vert.x JsonObject 零反射直推).
     */
    private void sendJson(WebSocketConnection conn, JsonObject json) {
        if (!conn.isClosed()) {
            try {
                conn.sendText(json.encode()).subscribe().with(
                        v -> {},
                        err -> LOG.errorf(err, "Failed to send text frame to connId=%s", conn.id())
                );
            } catch (Exception e) {
                LOG.errorf(e, "Failed to serialize text frame: %s", json);
            }
        }
    }
}

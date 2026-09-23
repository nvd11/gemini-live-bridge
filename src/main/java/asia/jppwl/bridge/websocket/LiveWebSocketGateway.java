package asia.jppwl.bridge.websocket;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import asia.jppwl.bridge.domain.CallSession;
import asia.jppwl.bridge.domain.SessionManager;
import asia.jppwl.bridge.domain.SessionSummary;
import asia.jppwl.bridge.relay.DownstreamSink;
import asia.jppwl.bridge.relay.GeminiLiveRelayService;
import asia.jppwl.bridge.relay.GeminiLiveSession;
import asia.jppwl.bridge.websocket.dto.ClientControlEvent;
import asia.jppwl.bridge.websocket.dto.ServerControlEvent;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 客户端全双工 WebSocket 实时接入网关.
 *
 * <p>端点路径：{@code /ws/live/{token}}
 * <p>技术栈：Quarkus WebSockets Next + Vert.x Buffer 零拷贝通道 + Gemini Live 上游中继
 *
 * <p><b>核心边界职责：</b>
 * <ol>
 *   <li><b>安全防重放：</b>握手建立首秒，原子核销 {@code token}。若已被使用或超期，下发 4001 拒绝握手；</li>
 *   <li><b>防 Cloudflare 100s 熔断心跳：</b>响应 {@code client.ping} 文本帧，秒级回推 {@code server.pong}，重置 CDN 倒计时；</li>
 *   <li><b>Barge-in 双向打断：</b>响应 {@code client.interrupt}，秒级清空下行缓冲队列并向 Google 发送截断信令；</li>
 *   <li><b>零拷贝双向音频中继：</b>
 *     <ul>
 *       <li>上行：接收客户端 16kHz PCM 二进制帧，零拷贝推送至上游 {@link GeminiLiveSession}；</li>
 *       <li>下行：接收 Google 吐出的 24kHz PCM 音频切片与字幕增量，直推客户端扬声器与字幕框。</li>
 *     </ul>
 *   </li>
 * </ol>
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
    private final ObjectMapper objectMapper;

    @Inject
    public LiveWebSocketGateway(
            SessionManager sessionManager,
            BargeInController bargeInController,
            GeminiLiveRelayService relayService,
            ObjectMapper objectMapper
    ) {
        this.sessionManager = sessionManager;
        this.bargeInController = bargeInController;
        this.relayService = relayService;
        this.objectMapper = objectMapper;
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

        // 2. 下发 session.ready 欢迎与音频规格声明帧
        ServerControlEvent.SessionReady readyEvent = ServerControlEvent.SessionReady.of(
                session.sessionId(), session.modelVariant()
        );
        sendJson(conn, readyEvent);
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
                sendJson(conn, new ServerControlEvent.TranscriptDelta(
                        ServerControlEvent.TranscriptDelta.EVENT_NAME, role, delta, isFinal, System.currentTimeMillis()
                ));
            }

            @Override
            public void sendInterruptedNotification() {
                sendJson(conn, ServerControlEvent.Interrupted.now());
            }

            @Override
            public void onUpstreamClosed(String reason) {
                LOG.infof("Upstream closed notification received for session %s: %s", session.sessionId(), reason);
                if (!conn.isClosed()) {
                    sendJson(conn, ServerControlEvent.SessionClosed.of(reason, session.getActiveDurationSeconds()));
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
                            sendJson(conn, ServerControlEvent.SessionClosed.of("upstream_error", 0));
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
            ClientControlEvent.BaseEvent base = objectMapper.readValue(jsonPayload, ClientControlEvent.BaseEvent.class);
            if (base.event() == null) {
                return;
            }

            switch (base.event()) {
                case ClientControlEvent.Ping.EVENT_NAME -> handleHeartbeat(conn, jsonPayload);
                case ClientControlEvent.Interrupt.EVENT_NAME -> handleInterrupt(conn);
                case ClientControlEvent.TextMessage.EVENT_NAME -> handleClientText(conn, jsonPayload);
                case ClientControlEvent.Hangup.EVENT_NAME -> handleHangup(conn, jsonPayload);
                default -> LOG.debugf("Ignored unhandled client control event: %s", base.event());
            }
        } catch (IOException e) {
            LOG.warnf("Failed to parse client control frame: %s, error=%s", jsonPayload, e.getMessage());
        }
    }

    /**
     * 抵消 Cloudflare 100s 空闲超时的双向心跳处理 (每 15s 一次).
     */
    public void handleHeartbeat(WebSocketConnection conn, String jsonPayload) {
        try {
            ClientControlEvent.Ping ping = objectMapper.readValue(jsonPayload, ClientControlEvent.Ping.class);
            ServerControlEvent.Pong pong = ServerControlEvent.Pong.of(ping.timestamp());
            sendJson(conn, pong);
            LOG.trace("Heartbeat cycle handled: client.ping -> server.pong");
        } catch (IOException e) {
            sendJson(conn, ServerControlEvent.Pong.now());
        }
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
            sendJson(conn, ServerControlEvent.Interrupted.now());
        }
    }

    /**
     * 处理客户端文字插话 / 辅助指令.
     */
    private void handleClientText(WebSocketConnection conn, String jsonPayload) throws IOException {
        CallSession session = connectionSessionMap.get(conn.id());
        if (session != null) {
            ClientControlEvent.TextMessage textMsg = objectMapper.readValue(jsonPayload, ClientControlEvent.TextMessage.class);
            LOG.infof("Forwarding client.text to Google for session %s: %s", session.sessionId(), textMsg.text());

            GeminiLiveSession upstream = upstreamSessionMap.get(session.sessionId());
            if (upstream != null && upstream.isAlive()) {
                upstream.sendTextMessage(textMsg.text());
            }
        }
    }

    /**
     * 处理客户端主动挂断.
     */
    private void handleHangup(WebSocketConnection conn, String jsonPayload) {
        CallSession session = connectionSessionMap.get(conn.id());
        String sessionId = session != null ? session.sessionId() : "unknown";
        String reason = "user_hangup";
        try {
            ClientControlEvent.Hangup hangup = objectMapper.readValue(jsonPayload, ClientControlEvent.Hangup.class);
            if (hangup.reason() != null && !hangup.reason().isBlank()) {
                reason = hangup.reason();
            }
        } catch (IOException ignored) {
        }

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
     * 安全序列化并异步下发 JSON 文本帧.
     */
    private void sendJson(WebSocketConnection conn, Object payload) {
        if (!conn.isClosed()) {
            try {
                String text = objectMapper.writeValueAsString(payload);
                conn.sendText(text).subscribe().with(
                        v -> {},
                        err -> LOG.errorf(err, "Failed to send text frame to connId=%s", conn.id())
                );
            } catch (Exception e) {
                LOG.errorf(e, "Failed to serialize text frame: %s", payload);
            }
        }
    }
}

package asia.jppwl.bridge.relay;

import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.logging.Logger;

import asia.jppwl.bridge.domain.CallSession;
import asia.jppwl.bridge.tool.ToolExecutionRouter;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * 维持与 Google Gemini Live API 专属长连接的单会话中继器 (Stateful Relay Bridge).
 */
public class GeminiLiveSession {

    private static final Logger LOG = Logger.getLogger(GeminiLiveSession.class);

    private final WebSocket upstreamWs;
    private final CallSession session;
    private final DownstreamSink downstreamSink;
    private final ToolExecutionRouter toolRouter;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public GeminiLiveSession(
            WebSocket upstreamWs,
            CallSession session,
            DownstreamSink downstreamSink,
            ToolExecutionRouter toolRouter
    ) {
        this.upstreamWs = upstreamWs;
        this.session = session;
        this.downstreamSink = downstreamSink;
        this.toolRouter = toolRouter;

        // 绑定上游帧监听处理器
        this.upstreamWs.handler(this::handleUpstreamFrame);
        this.upstreamWs.closeHandler(v -> handleUpstreamClosed("Google WSS closed normally"));
        this.upstreamWs.exceptionHandler(this::handleUpstreamError);
    }

    /**
     * 上行：向 Google Gemini Live 直推 16kHz PCM 音频切片.
     */
    public void sendAudioChunk(Buffer pcm16k) {
        if (isAlive() && pcm16k != null && pcm16k.length() > 0) {
            JsonObject frame = GeminiMessageCodec.buildAudioInputFrame(pcm16k);
            upstreamWs.writeTextMessage(frame.encode());
        }
    }

    /**
     * 上行：向 Google Gemini Live 发送文本插话.
     */
    public void sendTextMessage(String userText) {
        if (isAlive() && userText != null && !userText.isBlank()) {
            JsonObject frame = GeminiMessageCodec.buildTextInputFrame(userText);
            upstreamWs.writeTextMessage(frame.encode());
        }
    }

    /**
     * 上行：通知 Google 主人说话已结束 (Turn Complete - 明确催促模型开始语音回复).
     */
    public void signalTurnComplete() {
        if (isAlive()) {
            JsonObject frame = GeminiMessageCodec.buildTurnCompleteSignalFrame();
            upstreamWs.writeTextMessage(frame.encode());
            LOG.infof(">>> [TURN COMPLETE] Signaled end-of-speech to Google Live for session %s", session.sessionId());
        }
    }

    /**
     * 上行：向 Google 发送打断信令 (Barge-in Interrupt).
     */
    public void signalInterrupt() {
        if (isAlive()) {
            JsonObject frame = GeminiMessageCodec.buildInterruptSignalFrame();
            upstreamWs.writeTextMessage(frame.encode());
            LOG.debugf("Sent interrupt signal to Google upstream for session %s", session.sessionId());
        }
    }

    /**
     * 上行：回填 Tool 执行结果.
     */
    public void sendToolResponse(String callId, JsonObject output) {
        if (isAlive()) {
            JsonObject frame = GeminiMessageCodec.buildToolResponseFrame(callId, output);
            upstreamWs.writeTextMessage(frame.encode());
            LOG.infof("Sent tool response to Google upstream: callId=%s, session=%s", callId, session.sessionId());
        }
    }

    /**
     * 关闭与上游 Google Live 的连接通道.
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                if (!upstreamWs.isClosed()) {
                    upstreamWs.close();
                }
            } catch (Exception e) {
                LOG.warnf("Error closing upstream ws for session %s: %s", session.sessionId(), e.getMessage());
            }
        }
    }

    public boolean isAlive() {
        return !closed.get() && !upstreamWs.isClosed();
    }

    /**
     * 核心下行帧解析器：处理 Google 吐出的 JSON 报文.
     */
    private void handleUpstreamFrame(Buffer frame) {
        if (frame == null || frame.length() == 0) {
            return;
        }

        String rawStr = frame.toString();
        try {
            JsonObject json = new JsonObject(rawStr);

            // 0. 检查握手确认 (setupComplete)
            if (json.containsKey("setupComplete")) {
                LOG.infof("Google Gemini Live setupComplete confirmed for session %s!", session.sessionId());
            }

            // 1. 检查服务端内容下发 (serverContent)
            JsonObject serverContent = json.getJsonObject("serverContent");
            if (serverContent != null) {
                // 检查是否发生服务端主动打断
                if (serverContent.getBoolean("interrupted", false)) {
                    downstreamSink.sendInterruptedNotification();
                }

                // 🌟 【重中之重】提取 Google Live 官方实时转写字段 (outputTranscription)
                JsonObject outputTranscription = serverContent.getJsonObject("outputTranscription");
                if (outputTranscription != null) {
                    String transcriptText = outputTranscription.getString("text");
                    if (transcriptText != null && !transcriptText.isBlank()) {
                        downstreamSink.sendTranscriptDelta("model", transcriptText, false);
                        LOG.infof(">>> [TRANSCRIPTION] Output transcript: %s", transcriptText);
                    }
                }

                // 提取模型回复音频与部件文本 (modelTurn)
                JsonObject modelTurn = serverContent.getJsonObject("modelTurn");
                if (modelTurn != null) {
                    JsonArray parts = modelTurn.getJsonArray("parts");
                    if (parts != null) {
                        for (int i = 0; i < parts.size(); i++) {
                            JsonObject part = parts.getJsonObject(i);

                            // A. 提取 24kHz PCM 下行音频数据 (inlineData)
                            JsonObject inlineData = part.getJsonObject("inlineData");
                            if (inlineData != null) {
                                String base64Audio = inlineData.getString("data");
                                if (base64Audio != null && !base64Audio.isBlank()) {
                                    Buffer audioPcm24k = GeminiMessageCodec.decodeBase64Audio(base64Audio);
                                    session.recordDownloadBytes(audioPcm24k.length());
                                    downstreamSink.sendAudioChunk(audioPcm24k);
                                    LOG.tracef("Pushed %d bytes of 24k audio down to client", audioPcm24k.length());
                                }
                            }

                            // B. 备用：提取 parts 里的显式文本片段
                            String textPart = part.getString("text");
                            if (textPart != null && !textPart.isBlank()) {
                                downstreamSink.sendTranscriptDelta("model", textPart, false);
                                LOG.infof(">>> [MODEL PART TEXT] text delta: %s", textPart);
                            }
                        }
                    }
                }
            }

            // 2. 检查 Function Calling 工具调用 (toolCall)
            JsonObject toolCall = json.getJsonObject("toolCall");
            if (toolCall != null && toolRouter != null) {
                JsonArray functionCalls = toolCall.getJsonArray("functionCalls");
                if (functionCalls != null) {
                    for (int i = 0; i < functionCalls.size(); i++) {
                        JsonObject fc = functionCalls.getJsonObject(i);
                        String callId = fc.getString("id", fc.getString("name"));
                        String functionName = fc.getString("name");
                        JsonObject args = fc.getJsonObject("args", new JsonObject());

                        LOG.infof("Handling toolCall from Gemini for session %s: func=%s, id=%s, args=%s",
                                session.sessionId(), functionName, callId, args);

                        toolRouter.executeToolCall(functionName, args)
                                .subscribe().with(
                                        toolOutput -> sendToolResponse(functionName, toolOutput),
                                        err -> {
                                            LOG.errorf(err, "Tool execution failed for %s", functionName);
                                            sendToolResponse(functionName, new JsonObject().put("error", err.getMessage()));
                                        }
                                );
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to process Google upstream frame for session %s: %s", session.sessionId(), e.getMessage());
        }
    }

    private void handleUpstreamClosed(String reason) {
        if (closed.compareAndSet(false, true)) {
            LOG.infof("Google Live upstream channel closed for session %s, reason=%s", session.sessionId(), reason);
            downstreamSink.onUpstreamClosed(reason);
        }
    }

    private void handleUpstreamError(Throwable error) {
        LOG.errorf(error, "Google Live upstream channel error for session %s", session.sessionId());
        handleUpstreamClosed(error.getMessage());
    }
}

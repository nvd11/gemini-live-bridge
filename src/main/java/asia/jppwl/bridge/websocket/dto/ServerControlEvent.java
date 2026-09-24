package asia.jppwl.bridge.websocket.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 服务端下行控制与字幕事件 DTO 集合 (Server -> Client).
 */
@RegisterForReflection
public final class ServerControlEvent {

    private ServerControlEvent() {
    }

    /**
     * 心跳回执帧 (秒级回传，重置 Edge CDN 计时器).
     */
    @RegisterForReflection
    public record Pong(
            String event,
            long timestamp
    ) {
        public static final String EVENT_NAME = "server.pong";

        public static Pong of(long timestamp) {
            return new Pong(EVENT_NAME, timestamp);
        }

        public static Pong now() {
            return new Pong(EVENT_NAME, System.currentTimeMillis());
        }
    }

    /**
     * 会话就绪与音频技术指标声明帧.
     */
    @RegisterForReflection
    public record SessionReady(
            String event,
            SessionReadyData data
    ) {
        public static final String EVENT_NAME = "session.ready";

        public static SessionReady of(String sessionId, String model) {
            AudioFormat format = new AudioFormat(
                    new StreamSpecs(16000, 1, 16),
                    new StreamSpecs(24000, 1, 16)
            );
            return new SessionReady(EVENT_NAME, new SessionReadyData(sessionId, model, format));
        }

        @RegisterForReflection
        public record SessionReadyData(
                @JsonProperty("session_id") String sessionId,
                String model,
                @JsonProperty("audio_format") AudioFormat audioFormat
        ) {}

        @RegisterForReflection
        public record AudioFormat(
                StreamSpecs input,
                StreamSpecs output
        ) {}

        @RegisterForReflection
        public record StreamSpecs(
                @JsonProperty("sample_rate") int sampleRate,
                int channels,
                @JsonProperty("bit_depth") int bitDepth
        ) {}
    }

    /**
     * 打断确认回执帧 (通知前端立即清空本地 AudioWorklet 播放队列).
     */
    @RegisterForReflection
    public record Interrupted(
            String event,
            long timestamp
    ) {
        public static final String EVENT_NAME = "server.interrupted";

        public static Interrupted now() {
            return new Interrupted(EVENT_NAME, System.currentTimeMillis());
        }
    }

    /**
     * 双向流式字幕增量转写帧.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @RegisterForReflection
    public record TranscriptDelta(
            String event,
            String role,
            String delta,
            @JsonProperty("is_final") boolean isFinal,
            long timestamp
    ) {
        public static final String EVENT_NAME = "transcript.delta";

        public static TranscriptDelta model(String delta, boolean isFinal) {
            return new TranscriptDelta(EVENT_NAME, "model", delta, isFinal, System.currentTimeMillis());
        }

        public static TranscriptDelta user(String delta, boolean isFinal) {
            return new TranscriptDelta(EVENT_NAME, "user", delta, isFinal, System.currentTimeMillis());
        }
    }

    /**
     * 会话结束通知帧.
     */
    @RegisterForReflection
    public record SessionClosed(
            String event,
            String reason,
            @JsonProperty("duration_seconds") long durationSeconds
    ) {
        public static final String EVENT_NAME = "session.closed";

        public static SessionClosed of(String reason, long durationSeconds) {
            return new SessionClosed(EVENT_NAME, reason, durationSeconds);
        }
    }
}

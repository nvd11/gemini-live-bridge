package asia.jppwl.bridge.websocket.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * 客户端上行控制事件 DTO 集合 (Client -> Server).
 */
@RegisterForReflection
public final class ClientControlEvent {

    private ClientControlEvent() {
    }

    /**
     * 基础事件提取类，用于识别 {@code event} 类型.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @RegisterForReflection
    public record BaseEvent(String event, Long timestamp) {}

    /**
     * 心跳探针帧 (15s 一次，用于抵消 Cloudflare 100s 空闲熔断).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @RegisterForReflection
    public record Ping(String event, long timestamp) {
        public static final String EVENT_NAME = "client.ping";
        public static Ping of(long timestamp) {
            return new Ping(EVENT_NAME, timestamp);
        }
    }

    /**
     * 用户随时打断信号 (Barge-in).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @RegisterForReflection
    public record Interrupt(String event, long timestamp) {
        public static final String EVENT_NAME = "client.interrupt";
    }

    /**
     * 文本插话 / 辅助指令帧.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @RegisterForReflection
    public record TextMessage(String event, String text, Long timestamp) {
        public static final String EVENT_NAME = "client.text";
    }

    /**
     * 麦克风静音切换帧.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @RegisterForReflection
    public record Mute(String event, boolean muted, Long timestamp) {
        public static final String EVENT_NAME = "client.mute";
    }

    /**
     * 客户端主动挂断帧.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @RegisterForReflection
    public record Hangup(String event, String reason, Long timestamp) {
        public static final String EVENT_NAME = "client.hangup";
    }
}

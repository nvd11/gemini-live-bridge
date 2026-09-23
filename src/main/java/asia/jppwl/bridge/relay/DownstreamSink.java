package asia.jppwl.bridge.relay;

import io.vertx.core.buffer.Buffer;

/**
 * 下游客户端音频与文本分发下沉接口 (Downstream Sink).
 *
 * <p>由网关层实现并传入，当上游 Google Gemini Live API 吐出音频分片、
 * 实时字幕增量或打断回执时，通过该接口将数据非阻塞直推给用户的 WebSocket 通道。
 */
public interface DownstreamSink {

    /**
     * 向客户端下发 24kHz PCM 二进制音频切片.
     *
     * @param pcm24kChunk 零拷贝 Vert.x Buffer
     */
    void sendAudioChunk(Buffer pcm24kChunk);

    /**
     * 向客户端下发实时增量转写字幕 (JSON 文本帧).
     *
     * @param role    角色 ({@code model} 或 {@code user})
     * @param delta   字幕增量文本
     * @param isFinal 是否为本轮最终文本
     */
    void sendTranscriptDelta(String role, String delta, boolean isFinal);

    /**
     * 向客户端下发服务端主动打断回执 (重置前端播放器).
     */
    void sendInterruptedNotification();

    /**
     * 通知下游通道上游发生严重异常或主动关闭.
     *
     * @param reason 异常原因
     */
    void onUpstreamClosed(String reason);
}

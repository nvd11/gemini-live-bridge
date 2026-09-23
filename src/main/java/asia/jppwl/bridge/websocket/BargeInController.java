package asia.jppwl.bridge.websocket;

import java.util.Objects;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * 双向打断拦截与状态回执控制器 (Barge-in Controller).
 *
 * <p>当用户在模型语音播报过程中开口插话时，前端 AudioWorklet 瞬时发出 {@code client.interrupt} 信号。
 * 本控制器负责：
 * <ol>
 *   <li>立即调用 {@link JitterBufferManager} 清空内部待下发给客户端的所有音频缓冲队列；</li>
 *   <li>向下游客户端派发 {@code server.interrupted} 回执，重置浏览器本地播放器；</li>
 *   <li>向上游 Google Gemini 会话发送打断截断信令 (由 Relay 监听处理)。</li>
 * </ol>
 */
@ApplicationScoped
public class BargeInController {

    private static final Logger LOG = Logger.getLogger(BargeInController.class);

    private final JitterBufferManager jitterBufferManager;

    @Inject
    public BargeInController(JitterBufferManager jitterBufferManager) {
        this.jitterBufferManager = Objects.requireNonNull(jitterBufferManager, "jitterBufferManager must not be null");
    }

    /**
     * 处理客户端打断事件核心逻辑.
     *
     * @param sessionId 目标会话 ID
     * @return 打断处理收据 (包含丢弃切片数与时间戳)
     */
    public BargeInReceipt handleClientInterrupt(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            LOG.warn("Received client.interrupt with blank sessionId");
            return new BargeInReceipt("", 0, System.currentTimeMillis(), false);
        }

        long start = System.currentTimeMillis();
        int dropped = jitterBufferManager.clear(sessionId);
        LOG.infof("Barge-in interrupt handled: session=%s, flushed %d queued audio chunks", sessionId, dropped);

        return new BargeInReceipt(sessionId, dropped, start, true);
    }

    /**
     * 清理会话关联的抖动缓冲.
     */
    public void cleanupSession(String sessionId) {
        if (sessionId != null) {
            jitterBufferManager.removeSession(sessionId);
        }
    }

    /**
     * 打断操作回执单.
     *
     * @param sessionId     目标会话 ID
     * @param droppedChunks 本次丢弃的未播音频切片数
     * @param timestamp     处理时间戳
     * @param success       是否成功完成
     */
    public record BargeInReceipt(
            String sessionId,
            int droppedChunks,
            long timestamp,
            boolean success
    ) {}
}

package asia.jppwl.bridge.websocket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

import org.jboss.logging.Logger;

import io.vertx.core.buffer.Buffer;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * 下行音频抖动缓冲区管理器 (Jitter Buffer Manager).
 *
 * <p>用于平滑网络微小抖动，并在用户触发打断 (Barge-in) 瞬间，
 * 以纳秒级速度原子清空内部排队中尚未推送到扬声器的所有音频切片 (Flush)。
 */
@ApplicationScoped
public class JitterBufferManager {

    private static final Logger LOG = Logger.getLogger(JitterBufferManager.class);

    /** 会话 ID 到音频缓冲队列的并发映射 (每个切片为 Vert.x 响应式零拷贝 Buffer) */
    private final ConcurrentMap<String, ConcurrentLinkedQueue<Buffer>> sessionBuffers = new ConcurrentHashMap<>();

    /**
     * 将一个音频切片压入会话缓冲队列.
     *
     * @param sessionId 目标会话 ID
     * @param pcmChunk  音频分片 (24kHz 或 16kHz PCM Buffer)
     */
    public void enqueue(String sessionId, Buffer pcmChunk) {
        if (sessionId == null || pcmChunk == null) {
            return;
        }
        sessionBuffers.computeIfAbsent(sessionId, k -> new ConcurrentLinkedQueue<>()).add(pcmChunk);
    }

    /**
     * 提取并清空当前会话队列中的所有待发切片 (按入队顺序排定).
     *
     * @param sessionId 目标会话 ID
     * @return 当前积攒的全部音频切片列表
     */
    public List<Buffer> drain(String sessionId) {
        if (sessionId == null) {
            return Collections.emptyList();
        }
        ConcurrentLinkedQueue<Buffer> queue = sessionBuffers.get(sessionId);
        if (queue == null || queue.isEmpty()) {
            return Collections.emptyList();
        }

        List<Buffer> drained = new ArrayList<>();
        Buffer chunk;
        while ((chunk = queue.poll()) != null) {
            drained.add(chunk);
        }
        return drained;
    }

    /**
     * 【核心打断拦截操作】微秒级原子清空指定会话的待播缓冲队列 (Barge-in Flush).
     *
     * @param sessionId 目标会话 ID
     * @return 本轮丢弃的未播音频分片数
     */
    public int clear(String sessionId) {
        if (sessionId == null) {
            return 0;
        }
        ConcurrentLinkedQueue<Buffer> queue = sessionBuffers.get(sessionId);
        if (queue == null || queue.isEmpty()) {
            return 0;
        }

        int dropped = 0;
        while (queue.poll() != null) {
            dropped++;
        }
        LOG.debugf("Jitter buffer flushed for session %s, dropped %d chunks", sessionId, dropped);
        return dropped;
    }

    /**
     * 会话销毁时清理资源，防止内存泄漏.
     *
     * @param sessionId 目标会话 ID
     */
    public void removeSession(String sessionId) {
        if (sessionId != null) {
            sessionBuffers.remove(sessionId);
        }
    }

    /**
     * 获取指定会话当前队列深度 (排队中的切片数).
     */
    public int getQueueDepth(String sessionId) {
        if (sessionId == null) {
            return 0;
        }
        ConcurrentLinkedQueue<Buffer> queue = sessionBuffers.get(sessionId);
        return queue != null ? queue.size() : 0;
    }
}

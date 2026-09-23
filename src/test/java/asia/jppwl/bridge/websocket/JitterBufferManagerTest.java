package asia.jppwl.bridge.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.vertx.core.buffer.Buffer;

/**
 * {@link JitterBufferManager} 单元测试：验证缓冲入队、排队提取与 Barge-in 瞬间清空 (Flush).
 */
class JitterBufferManagerTest {

    private JitterBufferManager manager;

    @BeforeEach
    void setUp() {
        manager = new JitterBufferManager();
    }

    @Test
    @DisplayName("测试音频切片能够正确入队并按 FIFO 顺序完整提取")
    void shouldEnqueueAndDrainInOrder() {
        String sessionId = "call_sess_jitter_01";
        Buffer chunk1 = Buffer.buffer(new byte[] { 1, 2, 3 });
        Buffer chunk2 = Buffer.buffer(new byte[] { 4, 5, 6 });

        manager.enqueue(sessionId, chunk1);
        manager.enqueue(sessionId, chunk2);

        assertThat(manager.getQueueDepth(sessionId)).isEqualTo(2);

        List<Buffer> drained = manager.drain(sessionId);
        assertThat(drained).hasSize(2);
        assertThat(drained.get(0).getBytes()).containsExactly(1, 2, 3);
        assertThat(drained.get(1).getBytes()).containsExactly(4, 5, 6);

        // 提取后队列深度归零
        assertThat(manager.getQueueDepth(sessionId)).isZero();
        assertThat(manager.drain(sessionId)).isEmpty();
    }

    @Test
    @DisplayName("测试 Barge-in 打断瞬间微秒级清空缓冲队列 (Flush)")
    void shouldFlushQueueAtomicallyOnBargeIn() {
        String sessionId = "call_sess_jitter_02";
        for (int i = 0; i < 10; i++) {
            manager.enqueue(sessionId, Buffer.buffer(new byte[] { (byte) i }));
        }
        assertThat(manager.getQueueDepth(sessionId)).isEqualTo(10);

        // 触发打断清理
        int dropped = manager.clear(sessionId);
        assertThat(dropped).isEqualTo(10);
        assertThat(manager.getQueueDepth(sessionId)).isZero();
        assertThat(manager.drain(sessionId)).isEmpty();
    }

    @Test
    @DisplayName("测试会话销毁安全清理")
    void shouldHandleSessionRemovalSafely() {
        String sessionId = "call_sess_jitter_03";
        manager.enqueue(sessionId, Buffer.buffer(new byte[] { 9 }));
        manager.removeSession(sessionId);

        assertThat(manager.getQueueDepth(sessionId)).isZero();
        assertThat(manager.drain(sessionId)).isEmpty();
    }
}

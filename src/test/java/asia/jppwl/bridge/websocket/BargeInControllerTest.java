package asia.jppwl.bridge.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.vertx.core.buffer.Buffer;

/**
 * {@link BargeInController} 打断拦截控制器单元测试.
 */
class BargeInControllerTest {

    private JitterBufferManager jitterBufferManager;
    private BargeInController bargeInController;

    @BeforeEach
    void setUp() {
        jitterBufferManager = new JitterBufferManager();
        bargeInController = new BargeInController(jitterBufferManager);
    }

    @Test
    @DisplayName("测试用户打断信号到达时能够成功截断并丢弃积攒的音频切片")
    void shouldHandleClientInterruptAndReturnReceipt() {
        String sessionId = "call_sess_bargein_01";
        // 模拟下行排队了 5 个 24kHz 音频包
        for (int i = 0; i < 5; i++) {
            jitterBufferManager.enqueue(sessionId, Buffer.buffer(new byte[4800]));
        }
        assertThat(jitterBufferManager.getQueueDepth(sessionId)).isEqualTo(5);

        // 触发打断
        BargeInController.BargeInReceipt receipt = bargeInController.handleClientInterrupt(sessionId);

        assertThat(receipt.success()).isTrue();
        assertThat(receipt.sessionId()).isEqualTo(sessionId);
        assertThat(receipt.droppedChunks()).isEqualTo(5);
        assertThat(receipt.timestamp()).isPositive();

        // 验证底层缓冲已被清空
        assertThat(jitterBufferManager.getQueueDepth(sessionId)).isZero();
    }

    @Test
    @DisplayName("测试空会话 ID 或空队列时安全打断降级")
    void shouldHandleEmptyOrBlankSessionGracefully() {
        BargeInController.BargeInReceipt emptyReceipt = bargeInController.handleClientInterrupt("");
        assertThat(emptyReceipt.success()).isFalse();
        assertThat(emptyReceipt.droppedChunks()).isZero();

        BargeInController.BargeInReceipt nullReceipt = bargeInController.handleClientInterrupt(null);
        assertThat(nullReceipt.success()).isFalse();
    }
}

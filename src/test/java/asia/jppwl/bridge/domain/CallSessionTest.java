package asia.jppwl.bridge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CallSession} 核心实体行为与状态流转单元测试.
 */
class CallSessionTest {

    @Test
    @DisplayName("测试成功创建初始 PENDING 状态的会话，默认计数器与时间正确")
    void shouldCreatePendingSessionSuccessfully() {
        CallSession session = CallSession.createPending(
                "call_sess_001",
                "ses_live_token_12345",
                "U0AM8G9AARF",
                "slack",
                "C0B2JA5PSKW",
                "gemini-3.8-live",
                "Puck",
                Duration.ofMinutes(5),
                Duration.ofMinutes(30)
        );

        assertThat(session.sessionId()).isEqualTo("call_sess_001");
        assertThat(session.ephemeralToken()).isEqualTo("ses_live_token_12345");
        assertThat(session.status()).isEqualTo(SessionStatus.PENDING);
        assertThat(session.status().isActive()).isFalse();
        assertThat(session.status().isTerminal()).isFalse();
        assertThat(session.connectedAt()).isNull();
        assertThat(session.isTokenExpired()).isFalse();
        assertThat(session.bytesUploaded().get()).isZero();
        assertThat(session.bytesDownloaded().get()).isZero();
        assertThat(session.getActiveDurationSeconds()).isZero();
    }

    @Test
    @DisplayName("测试状态流转：从 PENDING 跃迁至 ACTIVE 与 TERMINATED")
    void shouldTransitionStateProperly() {
        CallSession pending = CallSession.createPending(
                "call_sess_002",
                "token_abc",
                "ou_feishu_user",
                "feishu",
                "oc_chat_1",
                "gemini-3.8-live",
                "Puck",
                Duration.ofMinutes(5),
                Duration.ofMinutes(30)
        );

        // 跃迁至 ACTIVE
        CallSession active = pending.toActive();
        assertThat(active.status()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(active.status().isActive()).isTrue();
        assertThat(active.connectedAt()).isNotNull();

        // 跃迁至 TERMINATED 终态
        CallSession terminated = active.toTerminal(SessionStatus.TERMINATED);
        assertThat(terminated.status()).isEqualTo(SessionStatus.TERMINATED);
        assertThat(terminated.status().isTerminal()).isTrue();
        assertThat(terminated.status().isActive()).isFalse();
    }

    @Test
    @DisplayName("测试非法状态跃迁阻断：toTerminal 传入非终态状态抛出异常")
    void shouldRejectInvalidTerminalTransition() {
        CallSession session = CallSession.createPending(
                "call_sess_003",
                "token_xyz",
                "user_1",
                "web",
                null,
                "gemini-3.8-live",
                "Puck",
                Duration.ofMinutes(5),
                Duration.ofMinutes(30)
        );

        assertThatThrownBy(() -> session.toTerminal(SessionStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target status must be terminal");
    }

    @Test
    @DisplayName("测试流量吞吐计数器的并发线程安全累加")
    void shouldAccumulateTrafficBytes() {
        CallSession session = CallSession.createPending(
                "call_sess_004",
                "token_traffic",
                "user_1",
                "slack",
                "C123",
                "gemini-3.8-live",
                "Puck",
                Duration.ofMinutes(5),
                Duration.ofMinutes(30)
        );

        session.recordUploadBytes(3200);
        session.recordUploadBytes(3200);
        session.recordDownloadBytes(4800);

        assertThat(session.bytesUploaded().get()).isEqualTo(6400L);
        assertThat(session.bytesDownloaded().get()).isEqualTo(4800L);
    }

    @Test
    @DisplayName("测试 Token 过期与单次通话超限时间断言")
    void shouldDetectExpirationsCorrectly() {
        // 构造一个已经过期的 Token (expiresAt 在过去)
        Instant past = Instant.now().minusSeconds(10);
        CallSession expiredTokenSession = new CallSession(
                "call_sess_005",
                "token_expired",
                "user_1",
                "slack",
                "C123",
                "gemini-3.8-live",
                "Puck",
                SessionStatus.PENDING,
                past.minusSeconds(300),
                past, // 已在10秒前过期
                null,
                Duration.ofMinutes(30),
                null,
                null
        );

        assertThat(expiredTokenSession.isTokenExpired()).isTrue();

        // 构造一个连线时间超过 maxDuration 的活跃会话
        Instant oldConnectTime = Instant.now().minus(Duration.ofMinutes(31));
        CallSession overDurationSession = new CallSession(
                "call_sess_006",
                "token_valid",
                "user_1",
                "slack",
                "C123",
                "gemini-3.8-live",
                "Puck",
                SessionStatus.ACTIVE,
                oldConnectTime.minusSeconds(60),
                Instant.now().plusSeconds(300),
                oldConnectTime, // 31分钟前连线
                Duration.ofMinutes(30),
                null,
                null
        );

        assertThat(overDurationSession.isMaxDurationExceeded()).isTrue();
        assertThat(overDurationSession.getActiveDurationSeconds()).isGreaterThanOrEqualTo(1860L);
    }
}
